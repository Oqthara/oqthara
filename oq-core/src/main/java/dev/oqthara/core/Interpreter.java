package dev.oqthara.core;

import dev.oqthara.runtime.Errors;
import dev.oqthara.runtime.MethodResolver;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OverloadedFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqInstance;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.OqSet;
import dev.oqthara.runtime.OutputBudget;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.OqRange;
import dev.oqthara.runtime.OqTuple;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Value;
import dev.oqthara.runtime.Values;
import dev.oqthara.runtime.WaitedBudget;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Interpreter implements OqHost {
    /** Marks a call the host makes on a value a standard-library method was handed. */
    private static final String CALLBACK = "<callback>";

    private final PrintStream out;
    private final PrintStream err;
    private final BufferedReader in;
    private final List<String> args;
    private final Path workingDirectory;
    private final MethodResolver methods;
    private final Map<String, Class<?>> javaTypes = new LinkedHashMap<>();
    // Class declarations by name. Method dispatch needs the body and the scope it was
    // declared in, both of which belong to the declaration rather than to the instance.
    private final Map<String, ClassCtor> classes = new LinkedHashMap<>();
    private final Map<String, OqCallable> builtins = new LinkedHashMap<>();
    private final ThreadLocal<List<String>> callStack = ThreadLocal.withInitial(ArrayList::new);
    // The class whose method is running, used to decide whether a `_`-prefixed field
    // (ADR-0027 D7) is reachable from here. Null outside every class method.
    private final ThreadLocal<String> currentClass = new ThreadLocal<>();
    // Written from every virtual thread a program runs, so it must not be a plain int: two
    // threads incrementing concurrently would otherwise lose counts and weaken the budget.
    private final java.util.concurrent.atomic.AtomicLong steps =
            new java.util.concurrent.atomic.AtomicLong();
    private int maxSteps = 50_000_000;
    // Recursion depth is per execution, not per interpreter. A program's tasks all run on the
    // same Interpreter instance, so a shared counter lets two concurrent tasks corrupt each
    // other's accounting: one task's `++` can be lost or double-counted by another, and the
    // `finally` decrements interleave so a nested call can be refused while the real call stack
    // is shallow. It is a ThreadLocal for the same reason `callStack` is — a virtual thread is
    // one execution — and `steps` is atomic rather than thread-local only because the step
    // budget is a program-wide total while depth is a property of one call chain.
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final int maxDepth = 800;

    public Interpreter(PrintStream out, PrintStream err, BufferedReader in, List<String> args,
                       Path workingDirectory, MethodResolver methods) {
        this.out = out;
        this.err = err;
        this.in = in;
        this.args = args;
        this.workingDirectory = workingDirectory;
        this.methods = methods;
    }

    public static Interpreter headless() {
        return new Interpreter(System.out, System.err, new BufferedReader(new java.io.InputStreamReader(System.in)),
                List.of(), Path.of("."), null);
    }

    public Map<String, OqCallable> builtins() {
        return builtins;
    }

    public List<String> callStack() {
        return callStack.get();
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int maxSteps() {
        return maxSteps;
    }

    /**
     * Charges work the interpreter cannot see, such as a builtin copying a large file.
     *
     * <p>The step counter only advances on statements, loop turns and expressions, so a single
     * call to a host builtin used to cost one step no matter how much memory it allocated. The
     * stdlib charges this budget in proportion to the work it actually does, which is what
     * makes the resource controls on {@code print}, {@code read} and {@code run_command} part
     * of the same guard instead of a separate uncounted one.
     */
    public void chargeSteps(long units) {
        if (units <= 0) return;
        long spent = steps.addAndGet(units);
        if (spent > maxSteps) {
            steps.set(maxSteps + 1L);
            throw new OqError("program ran for too long", Span.synthetic(),
                    "Stopped after " + maxSteps + " steps. Check for an infinite loop.");
        }
    }

    /** Discards the step count, so one budget covers the whole session rather than each entry. */
    /**
     * Bills the program for bytes sent to the host.
     *
     * <p>Output gets its own ceiling instead of draining the step budget. Charging output as
     * steps meant the budget tripped first and the documented 16 MiB figure was unreachable:
     * a program could only print 50,000,000 / 256 = 195,312 bytes before dying. Two independent
     * limits are what the docs promise, so this keeps the byte budget real and leaves the step
     * budget for computation.
     */
    public void chargeOutput(long bytes) {
        OutputBudget.charge(bytes);
    }

    /** The largest total output one program may write, in bytes. */
    public static final long MAX_OUTPUT_BYTES = OutputBudget.MAX_BYTES;

    public void resetSteps() {
        steps.set(0);
        OutputBudget.reset();
        WaitedBudget.reset();
    }

    public void defineBuiltin(NativeFunction function) {
        OqCallable existing = builtins.get(function.name());
        if (existing == null) {
            builtins.put(function.name(), function);
            return;
        }
        List<OqCallable> candidates = new ArrayList<>();
        if (existing instanceof OverloadedFunction overload) {
            candidates.addAll(overload.candidates());
        } else {
            candidates.add(existing);
        }
        candidates.add(function);
        builtins.put(function.name(), new OverloadedFunction(function.name(), candidates));
    }

    public Object run(Ast.Program program) {
        resetSteps();
        Env env = new Env();
        for (OqCallable function : builtins.values()) {
            env.defineBuiltin(function.name(), function);
        }
        try {
            hoist(program.statements(), env);
            validateDeclarations(program.statements());
            validateNested(program.statements());
            for (Ast.Stmt stmt : program.statements()) {
                execute(stmt, env);
            }
        } catch (OqFunction.ReturnSignal signal) {
            throw new OqError("'return' outside a function", Span.synthetic(),
                    "Put it inside a 'fun' block.");
        } catch (BreakSignal signal) {
            throw new OqError("'break' outside a loop", signal.span(),
                    "'break' only works inside 'for' or 'while'. Remove it, or wrap the code in a loop.");
        } catch (ContinueSignal signal) {
            throw new OqError("'continue' outside a loop", signal.span(),
                    "'continue' only works inside 'for' or 'while'.");
        }
        return null;
    }

    public void executeBlock(List<Ast.Stmt> statements, Env env) {
        enterFrame();
        try {
            hoist(statements, env);
            for (Ast.Stmt stmt : statements) {
                execute(stmt, env);
            }
        } finally {
            leaveFrame();
        }
    }

    /**
     * Counts one nesting level and refuses the call once the limit is reached.
     *
     * <p>The increment happens before the test and is undone before throwing, so a refused call
     * leaves the counter exactly as it found it. Otherwise a caught-and-retried recursion error
     * would permanently consume a level and a later, shallower call would be refused.
     */
    private void enterFrame() {
        int next = depth.get() + 1;
        if (next > maxDepth) {
            throw new OqError("call stack is too deep", Span.synthetic(),
                    "This usually means an infinite recursion. Check that the function eventually stops calling itself.");
        }
        depth.set(next);
    }

    private void leaveFrame() {
        depth.set(depth.get() - 1);
    }

    private void hoist(List<Ast.Stmt> statements, Env env) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.FuncDecl function) {
                declareFunction(env, function, stmt.span());
            } else if (stmt instanceof Ast.DataDecl data) {
                List<Ast.FieldDef> fields = data.fields();
                List<java.util.function.Supplier<Object>> defaults = new ArrayList<>();
                for (Ast.FieldDef field : fields) {
                    if (field.defaultValue() == null) {
                        defaults.add(() -> null);
                    } else {
                        Ast.Expr defaultValue = field.defaultValue();
                        defaults.add(() -> evaluate(defaultValue, env));
                    }
                }
                env.define(data.name(), new DataCtor(data.name(), fields, defaults, data.immutable()), true);
            } else if (stmt instanceof Ast.ClassDecl classDecl) {
                ClassCtor ctor = new ClassCtor(classDecl, env);
                classes.put(classDecl.name(), ctor);
                env.define(classDecl.name(), ctor, true);
            } else if (stmt instanceof Ast.ContractDecl contract) {
                env.define(contract.name(), new ContractValue(contract), true);
            }
        }
    }

    /**
     * Registers one {@code fun} under its name, extending an existing overload set instead of
     * replacing it. Two declarations with the same name and the same parameter count are a
     * duplicate signature, which is reported here rather than silently keeping the last one.
     */
    private void declareFunction(Env env, Ast.FuncDecl function, Span span) {
        OqFunction candidate = new OqFunction(function.name(), function.params(),
                function.body(), env);
        // A built-in yields to a user definition of the same name, so the program's own
        // function wins instead of being reported as a duplicate of something it never wrote.
        if (!env.hasLocal(function.name()) || env.isShadowableLocal(function.name())) {
            env.define(function.name(), candidate, true);
            return;
        }
        Object existing = env.getLocal(function.name());
        if (existing instanceof OqCallable callable) {
            for (OqCallable other : candidatesOf(callable)) {
                if (other.arity() == candidate.arity()) {
                    throw new OqError("'" + function.name() + "' is already defined with "
                            + candidate.arity() + " parameter"
                            + (candidate.arity() == 1 ? "" : "s"), span,
                            "Give the two definitions different numbers of parameters, "
                                    + "or rename one of them.");
                }
            }
            env.define(function.name(), OverloadedFunction.combine(callable, candidate), true);
            return;
        }
        throw new OqError("'" + function.name() + "' is already used for a value", span,
                "Pick a different function name.");
    }

    private static List<OqCallable> candidatesOf(OqCallable callable) {
        return callable instanceof OverloadedFunction set ? set.candidates() : List.of(callable);
    }

    private void tick(Span span) {
        if (steps.incrementAndGet() > maxSteps) {
            throw new OqError("program ran for too long", span,
                    "Stopped after " + maxSteps + " steps. Check for an infinite loop.");
        }
    }

    public void execute(Ast.Stmt stmt, Env env) {
        tick(stmt.span());
        switch (stmt) {
            case Ast.VarDecl decl -> {
                Object value = evaluate(decl.value(), env);
                if (decl.mutable()) {
                    env.define(decl.name(), value, true);
                } else {
                    // A second `const x` in one scope used to replace the first binding with a
                    // fresh one, so the value changed while assignments to the name were still
                    // refused: the constant looked enforced and was not. Declaring a name twice
                    // is refused here, where the source span is available.
                    if (env.hasLocal(decl.name())) {
                        throw new OqError("'" + decl.name() + "' is already declared in this scope",
                                decl.span(),
                                "A name can be declared once per scope. Use a different name, "
                                        + "or assign to '" + decl.name() + "' instead.");
                    }
                    env.defineFixed(decl.name(), value, "a 'const' binding",
                            "A constant keeps its first value; use a different name,"
                                    + " or declare it with an ordinary assignment.");
                }
            }
            case Ast.Assign assign -> env.assignOrDefine(assign.name(), evaluate(assign.value(), env), assign.span());
            case Ast.Destructure destructure -> {
                // The value is measured before any target is written, so a count mismatch
                // leaves every target untouched rather than half-updated.
                Object source = evaluate(destructure.value(), env);
                List<Object> parts = Values.destructuringParts(
                        source, destructure.names().size(), destructure.span());
                if (!destructure.mutable()) {
                    // The same rule as a plain `const`, checked for every name before any is
                    // written so a refused destructuring leaves all targets unchanged.
                    for (String name : destructure.names()) {
                        if (env.hasLocal(name)) {
                            throw new OqError("'" + name + "' is already declared in this scope",
                                    destructure.span(),
                                    "A name can be declared once per scope. Use a different name, "
                                            + "or assign to '" + name + "' instead.");
                        }
                    }
                }
                for (int i = 0; i < parts.size(); i++) {
                    String name = destructure.names().get(i);
                    if (destructure.mutable()) {
                        env.assignOrDefine(name, parts.get(i), destructure.span());
                    } else {
                        env.defineFixed(name, parts.get(i), "a 'const' binding",
                                "A constant keeps its first value; use a different name,"
                                        + " or declare it with an ordinary assignment.");
                    }
                }
            }
            case Ast.CompoundAssign assign -> {
                Object current = env.get(assign.name(), assign.span());
                Object value = evaluate(assign.value(), env);
                Object result = applyBinary(assign.op(), current, value, assign.span());
                env.set(assign.name(), result, assign.span());
            }
            case Ast.Command command -> evaluate(new Ast.Call(new Ast.Var(command.name(), command.span()),
                    command.args(), command.span()), env);
            case Ast.If conditional -> {
                boolean handled = false;
                for (Ast.Branch branch : conditional.branches()) {
                    if (Values.truthy(evaluate(branch.condition(), env))) {
                        executeBlock(branch.body(), new Env(env));
                        handled = true;
                        break;
                    }
                }
                if (!handled && !conditional.elseBranch().isEmpty()) {
                    executeBlock(conditional.elseBranch(), new Env(env));
                }
            }
            case Ast.While loop -> {
                while (Values.truthy(evaluate(loop.condition(), env))) {
                    tick(loop.span());
                    try {
                        executeBlock(loop.body(), new Env(env));
                    } catch (BreakSignal ignored) {
                        break;
                    } catch (ContinueSignal ignored) {
                        continue;
                    }
                }
            }
            case Ast.ForEach loop -> {
                Object iterable = evaluate(loop.iterable(), env);
                for (Object item : Values.iterable(iterable, loop.span())) {
                    tick(loop.span());
                    Env local = new Env(env);
                    local.defineFixed(loop.name(), item, "the loop variable of this 'for'",
                            "Use a different name if you need to modify it.");
                    try {
                        executeBlock(loop.body(), local);
                    } catch (BreakSignal ignored) {
                        break;
                    } catch (ContinueSignal ignored) {
                        // keep looping
                    }
                }
            }
            case Ast.ForRange loop -> {
                long from = Values.asLong(evaluate(loop.from(), env));
                long to = Values.asLong(evaluate(loop.to(), env));
                long step = from <= to ? 1 : -1;
                long end = loop.inclusive() ? to : to - step;
                for (long i = from; step > 0 ? i <= end : i >= end; i += step) {
                    tick(loop.span());
                    Env local = new Env(env);
                    local.defineFixed(loop.name(), i, "the loop variable of this 'for'",
                            "Use a different name if you need to modify it.");
                    try {
                        executeBlock(loop.body(), local);
                    } catch (BreakSignal ignored) {
                        break;
                    } catch (ContinueSignal ignored) {
                        // keep looping
                    }
                }
            }
            case Ast.FuncDecl function -> {
                if (!env.hasLocal(function.name())) {
                    declareFunction(env, function, function.span());
                }
            }
            case Ast.Return ret -> throw new OqFunction.ReturnSignal(
                    ret.value() == null ? null : evaluate(ret.value(), env));
            case Ast.Break brk -> throw new BreakSignal(brk.span());
            case Ast.Continue cont -> throw new ContinueSignal(cont.span());
            case Ast.ClassDecl classDecl -> {
                if (!env.hasLocal(classDecl.name())) {
                    ClassCtor ctor = new ClassCtor(classDecl, env);
                    classes.put(classDecl.name(), ctor);
                    env.define(classDecl.name(), ctor, true);
                }
            }
            case Ast.ContractDecl contract -> {
                if (!env.hasLocal(contract.name())) {
                    env.define(contract.name(), new ContractValue(contract), true);
                }
            }
            case Ast.DataDecl data -> {
                if (!env.hasLocal(data.name())) {
                    List<Ast.FieldDef> fields = data.fields();
                    List<java.util.function.Supplier<Object>> defaults = new ArrayList<>();
                    for (Ast.FieldDef field : fields) {
                        if (field.defaultValue() == null) {
                            defaults.add(() -> null);
                        } else {
                            Ast.Expr defaultValue = field.defaultValue();
                            defaults.add(() -> evaluate(defaultValue, env));
                        }
                    }
                    env.define(data.name(), new DataCtor(data.name(), fields, defaults, data.immutable()), true);
                }
            }
            case Ast.ExprStmt ignored -> evaluate(((Ast.ExprStmt) stmt).expr(), env);
            case Ast.IndexAssign assign -> {
                Object target = evaluate(assign.target(), env);
                Object index = evaluate(assign.index(), env);
                Object value = evaluate(assign.value(), env);
                Values.setIndex(target, index, value, assign.span());
            }
            case Ast.FieldAssign assign -> {
                Object target = evaluate(assign.target(), env);
                Object value = evaluate(assign.value(), env);
                if (target instanceof OqData data) {
                    if (!data.has(assign.field())) {
                        throw new OqError("type " + data.typeName() + " has no field named '" + assign.field() + "'",
                                assign.span());
                    }
                    data.set(assign.field(), value, assign.span());
                } else if (target instanceof OqInstance instance) {
                    requireFieldAccess(instance, assign.field(), assign.span());
                    if (!instance.has(assign.field())) {
                        throw new OqError("type " + instance.className() + " has no field named '"
                                + assign.field() + "'", assign.span(),
                                "Available fields: " + String.join(", ", instance.fields().keySet()));
                    }
                    instance.set(assign.field(), value);
                } else if (target instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> map = (Map<Object, Object>) raw;
                    map.put(assign.field(), value);
                } else {
                    throw new OqError("cannot set field '" + assign.field() + "' on " + Values.typeName(target),
                            assign.span());
                }
            }
            case Ast.TryStmt attempt -> {
                try {
                    executeBlock(attempt.body(), new Env(env));
                } catch (OqError error) {
                    Env local = new Env(env);
                    local.defineFixed(attempt.errorName(), error.toValue(),
                            "the caught error of this 'try'",
                            "Use a different name if you need to modify it.");
                    executeBlock(attempt.handler(), local);
                }
            }
            case Ast.UseStmt use -> javaTypes.put(simpleName(use.path()), loadClass(use.path(), use.span()));
            case Ast.ImportStmt ignored -> {
                // Modules are resolved by the CLI before execution.
            }
        }
    }

    private static String simpleName(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private Class<?> loadClass(String path, Span span) {
        List<String> candidates = new ArrayList<>();
        candidates.add(path);
        if (!path.contains(".")) {
            candidates.add("java.lang." + path);
            candidates.add("java.util." + path);
        }
        for (String candidate : candidates) {
            try {
                return Class.forName(candidate);
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        throw new OqError("cannot find Java class '" + path + "'", span,
                "Check the package name, for example: use java.time.LocalDate");
    }

    /**
     * The value of a {@code try} expression: the value of the last statement of the branch that
     * ran, when that statement is an expression; {@code nothing} otherwise (ADR-0014).
     *
     * <p>Only the final statement's value is taken. An assignment or declaration is a statement,
     * not an expression, so a branch ending in one produces {@code nothing} rather than leaking
     * its right-hand side.
     */
    private Object evaluateTryExpr(Ast.TryExpr attempt, Env env) {
        try {
            return blockValue(attempt.body(), new Env(env));
        } catch (OqError error) {
            Env local = new Env(env);
            local.defineFixed(attempt.errorName(), error.toValue(), "the caught error of this 'try'",
                    "Use a different name if you need to modify it.");
            return blockValue(attempt.handler(), local);
        }
    }

    /**
     * Runs a block and returns the value of its final expression statement, or {@code null}.
     *
     * <p>Unlike a split into "everything but the last" plus "the last", the whole block is
     * hoisted first, so a function declared at the end is still visible to the statements
     * before it and a recursive function can call itself.
     */
    private Object blockValue(List<Ast.Stmt> statements, Env env) {
        enterFrame();
        try {
            hoist(statements, env);
            int last = statements.size() - 1;
            for (int i = 0; i < last; i++) {
                execute(statements.get(i), env);
            }
            if (last < 0) return null;
            Ast.Stmt finalStatement = statements.get(last);
            if (finalStatement instanceof Ast.ExprStmt exprStmt) {
                return evaluate(exprStmt.expr(), env);
            }
            execute(finalStatement, env);
            return null;
        } finally {
            leaveFrame();
        }
    }

    public Object evaluate(Ast.Expr expr, Env env) {
        tick(expr.span());
        return switch (expr) {
            case Ast.NumberLit number -> number.value();
            case Ast.StringLit text -> evaluateString(text, env);
            case Ast.BoolLit bool -> bool.value();
            case Ast.NothingLit ignored -> null;
            case Ast.Var var -> {
                if (env.hasLocal(var.name())) yield env.get(var.name(), var.span());
                Class<?> type = javaTypes.get(var.name());
                if (type != null) yield type;
                if (builtins.containsKey(var.name())) yield env.get(var.name(), var.span());
                yield env.get(var.name(), var.span());
            }
            case Ast.ListLit list -> {
                List<Object> items = new ArrayList<>();
                for (Ast.Expr item : list.items()) items.add(evaluate(item, env));
                yield items;
            }
            case Ast.TupleLit tuple -> {
                List<Object> items = new ArrayList<>();
                for (Ast.Expr item : tuple.items()) items.add(evaluate(item, env));
                yield new OqTuple(items);
            }
            case Ast.SetLit set -> {
                Set<Object> items = new OqSet();
                for (Ast.Expr item : set.items()) {
                    Object value = evaluate(item, env);
                    Value.requireHashable(value, "a set member", item.span());
                    items.add(value);
                }
                yield items;
            }
            case Ast.MapLit map -> {
                Map<Object, Object> entries = new OqMap();
                for (Ast.Entry entry : map.entries()) {
                    Object key = evaluate(entry.key(), env);
                    Value.requireHashable(key, "a map key", entry.key().span());
                    entries.put(key, evaluate(entry.value(), env));
                }
                yield entries;
            }
            case Ast.Unary unary -> {
                Object value = evaluate(unary.operand(), env);
                if (unary.op() == Token.Kind.KEYWORD) yield Values.not(value);
                if (unary.op() == Token.Kind.PLUS) {
                    if (!Values.isNumber(value)) {
                        throw new OqError("unary '+' needs a number, got " + Values.typeName(value), unary.span());
                    }
                    yield value;
                }
                yield Values.negate(value, unary.span());
            }
            case Ast.Binary binary -> {
                Object left = evaluate(binary.left(), env);
                Object right = evaluate(binary.right(), env);
                yield applyBinary(binary.op(), left, right, binary.span());
            }
            case Ast.Logical logical -> {
                Object left = evaluate(logical.left(), env);
                boolean truth = Values.truthy(left);
                if (logical.op().equals("and")) {
                    if (!truth) yield false;
                    yield Values.truthy(evaluate(logical.right(), env));
                }
                if (truth) yield true;
                yield Values.truthy(evaluate(logical.right(), env));
            }
            case Ast.Call call -> evaluateCall(call, env);
            case Ast.MethodCall call -> evaluateMethod(call, env);
            case Ast.Index index -> Values.index(evaluate(index.target(), env), evaluate(index.index(), env), index.span());
            case Ast.Slice slice -> Values.slice(
                    evaluate(slice.target(), env),
                    slice.from() == null ? null : evaluate(slice.from(), env),
                    slice.to() == null ? null : evaluate(slice.to(), env),
                    slice.step() == null ? null : evaluate(slice.step(), env),
                    slice.span());
            case Ast.Field field -> evaluateField(field, env);
            case Ast.Lambda lambda -> new OqFunction("<lambda>", lambda.params(),
                    lambda.body() != null
                            ? List.of(new Ast.Return(lambda.body(), lambda.span()))
                            : lambda.blockBody(), env);
            case Ast.TryExpr attempt -> evaluateTryExpr(attempt, env);
            case Ast.InExpr in -> Values.contains(evaluate(in.container(), env),
                    evaluate(in.item(), env), in.span());
            case Ast.RangeExpr range -> {
                long from = Values.asLong(evaluate(range.from(), env));
                long to = Values.asLong(evaluate(range.to(), env));
                yield new OqRange(from, to, range.inclusive());
            }
            case Ast.Grouping group -> evaluate(group.expr(), env);
        };
    }

    private Object evaluateString(Ast.StringLit text, Env env) {
        if (text.parts().size() == 1 && text.parts().get(0) instanceof Ast.TextPart part) {
            return part.text();
        }
        StringBuilder sb = new StringBuilder();
        for (Ast.StringPart part : text.parts()) {
            if (part instanceof Ast.TextPart raw) {
                sb.append(raw.text());
            } else if (part instanceof Ast.ExprPart exprPart) {
                sb.append(Values.display(evaluate(exprPart.expr(), env)));
            }
        }
        return sb.toString();
    }

    private Object evaluateCall(Ast.Call call, Env env) {
        Object callee = evaluate(call.callee(), env);
        List<Object> args = new ArrayList<>();
        for (Ast.Expr arg : call.args()) args.add(evaluate(arg, env));
        if (call.callee() instanceof Ast.Var var && env.hasLocal(var.name()) == false
                && javaTypes.containsKey(var.name())) {
            callee = javaTypes.get(var.name());
        }
        String name = call.callee() instanceof Ast.Var var ? var.name() : "<expression>";
        return invoke(callee, args, call.span(), name);
    }

    private Object invoke(Object callee, List<Object> args, Span span, String name) {
        // Mark before dispatch so a refusal raised inside a runtime helper reports the call
        // rather than the synthetic span (ADR-0025). Both backends then agree on positions.
        Errors.mark(span);
        if (callee instanceof OqCallable callable) {
            List<String> stack = callStack.get();
            stack.add(callable.name());
            if (stack.size() > 400) {
                stack.remove(stack.size() - 1);
                throw new OqError("call stack is too deep", span, "Check for infinite recursion.");
            }
            try {
                return callable.call(this, args, span);
            } catch (UnsupportedOperationException e) {
                // A collection that refuses to change is a program-visible condition, never an
                // Oqthara defect: a tuple reached through a view, or a JDK value the program made
                // unchangeable itself, such as List.of(...). Report it as a program error instead
                // of letting it escape as `internal error`.
                throw JavaBridge.immutableRefusal(e, span);
            } finally {
                stack.remove(stack.size() - 1);
            }
        }
        if (callee instanceof Class<?> type) {
            return JavaBridge.construct(type, args, span, workingDirectory, this);
        }
        // A callback reaches here from a standard-library higher-order method, which knows the
        // value it was handed but not the name the caller wrote, so quoting a placeholder as
        // though it were an identifier would misrepresent the program's own text.
        String subject = name.equals(CALLBACK) ? "this value" : "'" + name + "'";
        throw new OqError(subject + " is not a function", span, Values.notCallableHelp(callee));
    }

    private Object evaluateMethod(Ast.MethodCall call, Env env) {
        Object receiver = evaluate(call.receiver(), env);
        List<Object> args = new ArrayList<>();
        for (Ast.Expr arg : call.args()) args.add(evaluate(arg, env));
        // Evaluating the receiver and arguments marks their own calls, so the position must be
        // reset to this call's before dispatching. The JavaBridge fallbacks below raise errors
        // from inside the runtime, where `Errors.current()` is the only position available
        // (ADR-0025), and without this they would report the last call in the argument list.
        Errors.mark(call.span());
        if (receiver == null) {
            throw new OqError("cannot call method '" + call.method() + "' on nothing", call.span(),
                    "Check that the value is not nothing before calling a method on it.");
        }
        if (methods != null && !(receiver instanceof Class<?>)) {
            OqCallable method = methods.resolve(receiver, call.method(), call.span());
            if (method != null) {
                return invoke(method, args, call.span(), call.method());
            }
        }
        if (receiver instanceof OqInstance instance) {
            return invokeInstanceMethod(instance, call.method(), args, call.span());
        }
        if (receiver instanceof Class<?> type) {
            Object result = JavaBridge.callStatic(type, call.method(), args, call.span(),
                    workingDirectory, this);
            if (result == JavaBridge.NOT_FOUND) {
                throw new OqError("Java class " + type.getSimpleName() + " has no static method '"
                        + call.method() + "'", call.span());
            }
            return result;
        }
        Object result = JavaBridge.callMethod(receiver, call.method(), args, call.span(),
                workingDirectory, this);
        if (result == JavaBridge.NOT_FOUND) {
            // `xs.next()` and `xs.has_next()` are the two mistakes the iterator protocol
            // (ADR-0020) makes easy, because "iterate this" and "advance this" read alike.
            // Name the fix. Methods.java and Stdlib.java carry the same hint for the other
            // two paths a method call can take, so all of them agree.
            if (call.method().equals("next") || call.method().equals("has_next")
                   ) {
                throw new OqError("type " + Values.typeName(receiver)
                        + " has no method '" + call.method() + "'",
                        call.span(), "Call iter() first: " + Values.typeName(receiver)
                                + " is iterable, not an iterator.");
            }
            throw new OqError("type " + Values.typeName(receiver) + " has no method '" + call.method() + "'",
                    call.span(), "Check the spelling, or use a built-in function instead.");
        }
        return result;
    }

    private Object evaluateField(Ast.Field field, Env env) {
        Object target = evaluate(field.target(), env);
        if (target instanceof OqData data) {
            if (!data.has(field.name())) {
                throw new OqError("type " + data.typeName() + " has no field named '" + field.name() + "'",
                        field.span(), "Available fields: " + String.join(", ", data.fields().keySet()));
            }
            return data.get(field.name());
        }
        if (target instanceof OqInstance instance) {
            requireFieldAccess(instance, field.name(), field.span());
            if (!instance.has(field.name())) {
                throw new OqError("type " + instance.className() + " has no field named '"
                        + field.name() + "'", field.span(),
                        "Available fields: " + String.join(", ", instance.fields().keySet()));
            }
            return instance.get(field.name());
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(field.name());
        }
        Object value = JavaBridge.readField(target, field.name(), field.span());
        if (value == JavaBridge.NOT_FOUND) {
            throw new OqError("cannot read field '" + field.name() + "' on " + Values.typeName(target),
                    field.span());
        }
        return value;
    }

    private Object applyBinary(Token.Kind op, Object left, Object right, Span span) {
        return switch (op) {
            case PLUS -> Values.add(left, right, span);
            case MINUS -> Values.subtract(left, right, span);
            case STAR -> Values.multiply(left, right, span);
            case SLASH -> Values.divide(left, right, span);
            case PERCENT -> Values.modulo(left, right, span);
            case STARSTAR -> Values.power(left, right, span);
            case EQEQ -> Values.equal(left, right);
            case NOTEQ -> !Values.equal(left, right);
            case LT -> Values.compare(left, right, span) < 0;
            case LE -> Values.compare(left, right, span) <= 0;
            case GT -> Values.compare(left, right, span) > 0;
            case GE -> Values.compare(left, right, span) >= 0;
            default -> throw new OqError("unsupported operator " + op, span);
        };
    }

    public record DataCtor(String name, List<Ast.FieldDef> fields,
                           List<java.util.function.Supplier<Object>> defaults,
                           boolean immutable) implements OqCallable {
        @Override
        public Object call(OqHost host, List<Object> args, Span span) {
            if (args.size() > fields.size()) {
                throw new OqError(name + "() expects at most " + fields.size() + " argument"
                        + (fields.size() == 1 ? "" : "s") + ", got " + args.size(), span,
                        "Fields: " + String.join(", ", fields.stream().map(Ast.FieldDef::name).toList()));
            }
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                Ast.FieldDef field = fields.get(i);
                Object value = i < args.size() ? args.get(i) : defaults.get(i).get();
                if (i >= args.size() && field.defaultValue() == null) {
                    throw new OqError("missing value for field '" + field.name() + "' of " + name, span,
                            "Pass all " + fields.size() + " fields, or give this one a default in the data declaration.");
                }
                values.put(field.name(), value);
            }
            return new OqData(name, values, immutable);
        }
    }

    /**
     * A {@code class} declaration as a callable (ADR-0027). Construction evaluates the caller's
     * arguments left to right, then fills omitted fields from their defaults in declaration
     * order, then runs {@code init}. Nothing is visible to the program until all three succeed,
     * so a failure cannot leave a half-built instance reachable.
     */
    public static final class ClassCtor implements OqCallable {
        private final Ast.ClassDecl decl;
        private final Env declarationScope;

        ClassCtor(Ast.ClassDecl decl, Env declarationScope) {
            this.decl = decl;
            this.declarationScope = declarationScope;
        }

        public Ast.ClassDecl decl() {
            return decl;
        }

        Env declarationScope() {
            return declarationScope;
        }

        @Override
        public String name() {
            return decl.name();
        }

        @Override
        public Object call(OqHost host, List<Object> args, Span span) {
            Interpreter interpreter = (Interpreter) host;
            List<Ast.FieldDef> fields = decl.fields();
            if (args.size() > fields.size()) {
                throw new OqError(decl.name() + "() expects at most " + fields.size() + " argument"
                        + (fields.size() == 1 ? "" : "s") + ", got " + args.size(), span,
                        "Fields: " + String.join(", ", fields.stream().map(Ast.FieldDef::name).toList()));
            }
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                Ast.FieldDef field = fields.get(i);
                Object value;
                if (i < args.size()) {
                    value = args.get(i);
                } else if (field.defaultValue() != null) {
                    value = interpreter.evaluate(field.defaultValue(), declarationScope);
                } else {
                    throw new OqError("missing value for field '" + field.name() + "' of " + decl.name(),
                            span, "Pass all " + fields.size()
                                    + " fields, or give this one a default in the class declaration.");
                }
                values.put(field.name(), value);
            }
            OqInstance instance = new OqInstance(decl.name(), values);
            instance.bindDeclaration(this);
            if (decl.init() != null) {
                interpreter.runInitializer(decl, instance, span);
            }
            return instance;
        }
    }

    /** Runs a class's {@code init} body with {@code this} bound to the new instance. */
    void runInitializer(Ast.ClassDecl decl, OqInstance instance, Span span) {
        ClassCtor owner = ownerOf(instance);
        Env scope = new Env(owner == null ? new Env() : owner.declarationScope());
        bindThis(scope, instance);
        String previousClass = currentClass.get();
        currentClass.set(decl.name());
        try {
            executeBlock(decl.init(), scope);
        } finally {
            currentClass.set(previousClass);
        }
    }

    /**
     * Checks every class and contract in the program once all declarations are visible
     * (ADR-0027 D6). These are declaration-time checks, so a broken contract is reported even
     * when no instance is ever built, and a misspelled method is never a silent success.
     */
    private void validateDeclarations(List<Ast.Stmt> statements) {
        // A declaration is legal anywhere a statement is, so a class nested in a function or a
        // block must face the same checks as a top-level one. Validating only the program's own
        // statements let a nested class implement a missing contract or declare a method twice
        // without a diagnostic, purely because of where it was written.
        Map<String, Ast.ContractDecl> contracts = new LinkedHashMap<>();
        collectContracts(statements, contracts);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.ContractDecl contract) {
                Set<String> seen = new LinkedHashSet<>();
                for (Ast.Signature signature : contract.methods()) {
                    if (!seen.add(signature.name())) {
                        throw new OqError("contract " + contract.name() + " declares '"
                                + signature.name() + "' more than once", signature.span(),
                                "A class has one method per name, so a contract cannot ask for two.");
                    }
                }
            }
            if (!(stmt instanceof Ast.ClassDecl classDecl)) {
                continue;
            }
            Set<String> fieldNames = new LinkedHashSet<>();
            for (Ast.FieldDef field : classDecl.fields()) {
                if (!fieldNames.add(field.name())) {
                    throw new OqError("class " + classDecl.name() + " declares field '"
                            + field.name() + "' more than once", field.span());
                }
            }
            Map<String, Integer> aritiesByName = new LinkedHashMap<>();
            for (Ast.MethodDecl method : classDecl.methods()) {
                if (fieldNames.contains(method.name())) {
                    throw new OqError("class " + classDecl.name() + " has both a field and a method"
                            + " named '" + method.name() + "'", method.span(),
                            "Fields and methods share one name space in a class.");
                }
                Integer existing = aritiesByName.putIfAbsent(method.name(), method.params().size());
                if (existing != null) {
                    throw new OqError("class " + classDecl.name() + " declares '"
                            + method.name() + "' more than once", method.span(),
                            "A class has one method per name; give them different names.");
                }
            }
            if (classDecl.implementsName() == null) {
                continue;
            }
            Ast.ContractDecl contract = contracts.get(classDecl.implementsName());
            if (contract == null) {
                throw new OqError("class " + classDecl.name() + " implements '"
                        + classDecl.implementsName() + "', which is not declared", classDecl.span(),
                        "Declare the contract before or after the class, at the top level.");
            }
            for (Ast.Signature signature : contract.methods()) {
                Integer arity = aritiesByName.get(signature.name());
                if (arity == null || arity != signature.arity()) {
                    throw new OqError("class " + classDecl.name() + " implements "
                            + contract.name() + " but has no '" + signature.name() + "' taking "
                            + signature.arity() + " argument"
                            + (signature.arity() == 1 ? "" : "s"), classDecl.span(),
                            "Add the method, or remove 'implements " + contract.name() + "'.");
                }
            }
        }
    }

    /** Every contract reachable in the program, including ones declared in nested blocks. */
    private static void collectContracts(List<Ast.Stmt> statements,
                                         Map<String, Ast.ContractDecl> contracts) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.ContractDecl contract) {
                contracts.put(contract.name(), contract);
            }
            for (List<Ast.Stmt> nested : nestedBlocks(stmt)) {
                collectContracts(nested, contracts);
            }
        }
    }

    /**
     * The statement lists a statement owns directly. Used to find nested declarations; this is a
     * walk of the syntax tree, not of scopes, so it does not need to model any of the language's
     * binding rules.
     */
    private static List<List<Ast.Stmt>> nestedBlocks(Ast.Stmt stmt) {
        List<List<Ast.Stmt>> blocks = new ArrayList<>();
        if (stmt instanceof Ast.FuncDecl function) {
            blocks.add(function.body());
        } else if (stmt instanceof Ast.If branch) {
            for (Ast.Branch arm : branch.branches()) blocks.add(arm.body());
            if (branch.elseBranch() != null) blocks.add(branch.elseBranch());
        } else if (stmt instanceof Ast.While loop) {
            blocks.add(loop.body());
        } else if (stmt instanceof Ast.ForEach loop) {
            blocks.add(loop.body());
        } else if (stmt instanceof Ast.ForRange loop) {
            blocks.add(loop.body());
        } else if (stmt instanceof Ast.TryStmt tryStmt) {
            blocks.add(tryStmt.body());
            if (tryStmt.handler() != null) blocks.add(tryStmt.handler());
        }
        return blocks;
    }

    /** Runs the same declaration checks over every nested block in the program. */
    private void validateNested(List<Ast.Stmt> statements) {
        for (Ast.Stmt stmt : statements) {
            for (List<Ast.Stmt> nested : nestedBlocks(stmt)) {
                validateDeclarations(nested);
                validateNested(nested);
            }
        }
    }

    /**
     * The declaration an instance was built from. This reads the handle bound at construction
     * rather than looking the name up in {@code classes}, because a nested class of the same
     * name would otherwise retarget the outer one's instances (and run their {@code init} in the
     * wrong scope).
     */
    private ClassCtor ownerOf(OqInstance instance) {
        Object declaration = instance.declaration();
        return declaration instanceof ClassCtor ctor ? ctor : null;
    }

    /**
     * A field whose name starts with {@code _} is private to its class (ADR-0027 D7). The
     * check runs on both read and write, so encapsulation is one rule rather than two.
     */
    private void requireFieldAccess(OqInstance instance, String name, Span span) {
        if (!name.startsWith("_") || instance.className().equals(currentClass.get())) {
            return;
        }
        throw new OqError("field '" + name + "' of " + instance.className() + " is private", span,
                "A name starting with '_' is only reachable from inside " + instance.className()
                        + " itself. Add a method that exposes it.");
    }

    private static void bindThis(Env scope, OqInstance instance) {
        scope.defineFixed("this", instance, "this",
                "this is the object the method was called on and cannot be reassigned.");
    }

    /**
     * Dispatches an instance method (ADR-0027 D3). This runs only after {@code MethodResolver}
     * has declined, so a class cannot shadow {@code list.push} or any other built-in method.
     */
    private Object invokeInstanceMethod(OqInstance instance, String name, List<Object> args, Span span) {
        ClassCtor owner = ownerOf(instance);
        if (owner == null) {
            throw new OqError("type " + instance.className() + " has no method '" + name + "'", span,
                    "Check the spelling, or use a built-in function instead.");
        }
        Ast.MethodDecl wrongArity = null;
        for (Ast.MethodDecl method : owner.decl().methods()) {
            if (!method.name().equals(name)) {
                continue;
            }
            if (method.params().size() == args.size()) {
                return callMethod(owner, method, instance, args, span);
            }
            if (wrongArity == null) {
                wrongArity = method;
            }
        }
        if (wrongArity != null) {
            List<String> arities = new ArrayList<>();
            for (Ast.MethodDecl method : owner.decl().methods()) {
                if (method.name().equals(name)) {
                    arities.add(String.valueOf(method.params().size()));
                }
            }
            throw new OqError("method '" + name + "' of " + instance.className()
                    + " does not take " + args.size() + " argument"
                    + (args.size() == 1 ? "" : "s"), span,
                    "It takes " + String.join(" or ", arities) + ".");
        }
        throw new OqError("type " + instance.className() + " has no method '" + name + "'", span,
                "Check the spelling, or use a built-in function instead.");
    }

    private Object callMethod(ClassCtor owner, Ast.MethodDecl method, OqInstance instance,
                              List<Object> args, Span span) {
        Env scope = new Env(owner.declarationScope());
        bindThis(scope, instance);
        for (int i = 0; i < method.params().size(); i++) {
            scope.define(method.params().get(i), args.get(i), true);
        }
        String previousClass = currentClass.get();
        currentClass.set(owner.decl().name());
        try {
            executeBlock(method.body(), scope);
        } catch (OqFunction.ReturnSignal signal) {
            return signal.value();
        } finally {
            currentClass.set(previousClass);
        }
        return null;
    }

    /** A {@code contract} value. It exists so {@code implements} can be checked by name. */
    public record ContractValue(Ast.ContractDecl decl) implements OqCallable {
        @Override
        public String name() {
            return decl.name();
        }

        @Override
        public Object call(OqHost host, List<Object> args, Span span) {
            throw new OqError("a contract cannot be constructed: " + decl.name(), span,
                    "Declare a class that implements it instead.");
        }
    }

    public static final class BreakSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Span span;

        public BreakSignal(Span span) {
            super(null, null, false, false);
            this.span = span;
        }

        public Span span() {
            return span;
        }
    }

    public static final class ContinueSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Span span;

        public ContinueSignal(Span span) {
            super(null, null, false, false);
            this.span = span;
        }

        public Span span() {
            return span;
        }
    }

    @Override
    public Object call(Object callee, List<Object> args, Span span) {
        return invoke(callee, args, span, CALLBACK);
    }

    @Override
    public PrintStream out() {
        return out;
    }

    @Override
    public PrintStream err() {
        return err;
    }

    @Override
    public BufferedReader in() {
        return in;
    }

    @Override
    public List<String> programArgs() {
        return args;
    }

    @Override
    public Path workingDirectory() {
        return workingDirectory;
    }

    public Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : workingDirectory.resolve(p);
    }

    public String readLine(Span span) {
        try {
            return in.readLine();
        } catch (java.io.IOException e) {
            throw new OqError("cannot read input: " + e.getMessage(), span);
        }
    }

    public OqRange range(long from, long to, boolean inclusive) {
        return new OqRange(from, to, inclusive);
    }

    public MethodResolver methods() {
        return methods;
    }
}
