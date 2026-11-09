package dev.oqthara.core;

import dev.oqthara.runtime.MethodResolver;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OverloadedFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OutputBudget;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.OqRange;
import dev.oqthara.runtime.OqTuple;
import dev.oqthara.runtime.Span;
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
    private final PrintStream out;
    private final PrintStream err;
    private final BufferedReader in;
    private final List<String> args;
    private final Path workingDirectory;
    private final MethodResolver methods;
    private final Map<String, Class<?>> javaTypes = new LinkedHashMap<>();
    private final Map<String, OqCallable> builtins = new LinkedHashMap<>();
    private final ThreadLocal<List<String>> callStack = ThreadLocal.withInitial(ArrayList::new);
    // Written from every virtual thread a program runs, so it must not be a plain int: two
    // threads incrementing concurrently would otherwise lose counts and weaken the budget.
    private final java.util.concurrent.atomic.AtomicLong steps =
            new java.util.concurrent.atomic.AtomicLong();
    private int maxSteps = 50_000_000;
    private int depth = 0;
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
        depth++;
        if (depth > maxDepth) {
            depth--;
            throw new OqError("call stack is too deep", Span.synthetic(),
                    "This usually means an infinite recursion. Check that the function eventually stops calling itself.");
        }
        try {
            hoist(statements, env);
            for (Ast.Stmt stmt : statements) {
                execute(stmt, env);
            }
        } finally {
            depth--;
        }
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
                env.define(data.name(), new DataCtor(data.name(), fields, defaults), true);
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
                    env.define(data.name(), new DataCtor(data.name(), fields, defaults), true);
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
                    data.set(assign.field(), value);
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
                Set<Object> items = new LinkedHashSet<>();
                for (Ast.Expr item : set.items()) items.add(evaluate(item, env));
                yield items;
            }
            case Ast.MapLit map -> {
                Map<Object, Object> entries = new LinkedHashMap<>();
                for (Ast.Entry entry : map.entries()) {
                    entries.put(evaluate(entry.key(), env), evaluate(entry.value(), env));
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
            case Ast.Field field -> evaluateField(field, env);
            case Ast.Lambda lambda -> new OqFunction("<lambda>", lambda.params(),
                    lambda.body() != null
                            ? List.of(new Ast.Return(lambda.body(), lambda.span()))
                            : lambda.blockBody(), env);
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
            return JavaBridge.construct(type, args, span, workingDirectory);
        }
        throw new OqError("'" + name + "' is not a function", span,
                "It is " + Values.typeName(callee) + ", which cannot be called.");
    }

    private Object evaluateMethod(Ast.MethodCall call, Env env) {
        Object receiver = evaluate(call.receiver(), env);
        List<Object> args = new ArrayList<>();
        for (Ast.Expr arg : call.args()) args.add(evaluate(arg, env));
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
        if (receiver instanceof Class<?> type) {
            Object result = JavaBridge.callStatic(type, call.method(), args, call.span(), workingDirectory);
            if (result == JavaBridge.NOT_FOUND) {
                throw new OqError("Java class " + type.getSimpleName() + " has no static method '"
                        + call.method() + "'", call.span());
            }
            return result;
        }
        Object result = JavaBridge.callMethod(receiver, call.method(), args, call.span(), workingDirectory);
        if (result == JavaBridge.NOT_FOUND) {
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
                           List<java.util.function.Supplier<Object>> defaults) implements OqCallable {
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
            return new OqData(name, values);
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
        return invoke(callee, args, span, "<callback>");
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
