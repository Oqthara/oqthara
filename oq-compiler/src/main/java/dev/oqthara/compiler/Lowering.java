package dev.oqthara.compiler;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Token;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers the AST into a flat stack IR. The IR is backend-agnostic: the classfile
 * backend and the disassembler both consume it unchanged.
 *
 * <p>A construct whose interpreter semantics this lowering cannot reproduce is emitted as
 * {@link Ir.Refuse} instead of a placeholder. The backend then refuses the whole program by
 * name, so `oq build` falls back to the interpreter rather than producing a jar that behaves
 * differently.
 */
public final class Lowering {
    private final List<Ir.Function> functions = new ArrayList<>();
    private final List<String> divergences = new ArrayList<>();
    private final List<LoopContext> loops = new ArrayList<>();
    private int loopCounter = 0;

    /** A `data` type visible in the current block, matching the interpreter's hoisting. */
    private record DataType(String name, List<Ast.FieldDef> fields) {
    }

    private record LoopContext(String id, String breakLabel, String continueLabel) {
    }

    private final java.util.Deque<Map<String, DataType>> typeScopes = new java.util.ArrayDeque<>();
    private List<Ir.Instr> code;
    private int tempCounter = 0;
    private int labelCounter = 0;
    /** Distinct names for the generated methods that hold lambda bodies. */
    private int lambdaCounter = 0;
    /**
     * Names a statement in this function has bound to a function value. A call to one of them
     * must dispatch through the value at run time, not to a builtin or a `fun` of that name.
     */
    private final Set<String> callableNames = new java.util.LinkedHashSet<>();

    public static Ir.Program lower(Ast.Program program) {
        return lowerWithReport(program).program();
    }

    public record Result(Ir.Program program, List<String> divergences) {
    }

    public static Result lowerWithReport(Ast.Program program) {
        Lowering lowering = new Lowering();
        lowering.code = Ir.list();
        String collision = lowering.nameCollision(program.statements());
        if (collision != null) {
            lowering.code.add(new Ir.Refuse(collision));
        }
        lowering.pushTypes(program.statements());
        for (Ast.Stmt stmt : program.statements()) {
            lowering.statement(stmt);
        }
        lowering.popTypes();
        lowering.code.add(new Ir.Halt());
        Ir.Function main = new Ir.Function("main", List.of(), List.copyOf(lowering.code));
        return new Result(new Ir.Program(main, List.copyOf(lowering.functions)),
                List.copyOf(lowering.divergences));
    }

    /**
     * A data type name is also an ordinary identifier in the grammar, so it can be shadowed
     * by a variable or a function. The interpreter resolves that through its environment
     * chain; reproducing it here is out of scope, so the program is refused by name instead
     * of silently picking a different meaning.
     */
    private String nameCollision(List<Ast.Stmt> statements) {
        Set<String> types = new LinkedHashSet<>();
        Set<String> others = new LinkedHashSet<>();
        collectNames(statements, types, others);
        for (String type : types) {
            if (others.contains(type)) {
                return "data type '" + type + "' is also used as a variable or function;"
                        + " the compiler cannot resolve that shadowing";
            }
        }
        return null;
    }

    private void collectNames(List<Ast.Stmt> statements, Set<String> types, Set<String> others) {
        for (Ast.Stmt stmt : statements) {
            switch (stmt) {
                case Ast.DataDecl data -> types.add(data.name());
                case Ast.FuncDecl function -> {
                    others.add(function.name());
                    for (String param : function.params()) others.add(param);
                    collectNames(function.body(), types, others);
                }
                case Ast.VarDecl decl -> others.add(decl.name());
                case Ast.Assign assign -> others.add(assign.name());
                case Ast.CompoundAssign assign -> others.add(assign.name());
                case Ast.ForRange loop -> {
                    others.add(loop.name());
                    collectNames(loop.body(), types, others);
                }
                case Ast.ForEach loop -> {
                    others.add(loop.name());
                    collectNames(loop.body(), types, others);
                }
                case Ast.If conditional -> {
                    for (Ast.Branch branch : conditional.branches()) {
                        collectNames(branch.body(), types, others);
                    }
                    collectNames(conditional.elseBranch(), types, others);
                }
                case Ast.While loop -> collectNames(loop.body(), types, others);
                case Ast.TryStmt attempt -> {
                    others.add(attempt.errorName());
                    collectNames(attempt.body(), types, others);
                    collectNames(attempt.handler(), types, others);
                }
                default -> {
                }
            }
        }
    }

    /** Registers the `data` declarations the interpreter hoists into this block. */
    private void pushTypes(List<Ast.Stmt> statements) {
        Map<String, DataType> types = new LinkedHashMap<>();
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.DataDecl data) {
                types.put(data.name(), new DataType(data.name(), data.fields()));
            }
        }
        typeScopes.push(types);
    }

    private void popTypes() {
        typeScopes.pop();
    }

    private DataType lookupType(String name) {
        for (Map<String, DataType> scope : typeScopes) {
            DataType type = scope.get(name);
            if (type != null) return type;
        }
        return null;
    }

    private void statement(Ast.Stmt stmt) {
        switch (stmt) {
            case Ast.VarDecl decl -> {
                // VarDecl defines a new binding in the current scope, which is not the same
                // as assignment: assignment updates an existing binding instead of shadowing.
                expression(decl.value());
                if (decl.value() instanceof Ast.Lambda) callableNames.add(decl.name());
                code.add(new Ir.Declare(decl.name(), false));
            }
            case Ast.Assign assign -> {
                expression(assign.value());
                if (assign.value() instanceof Ast.Lambda) callableNames.add(assign.name());
                else callableNames.remove(assign.name());
                code.add(new Ir.Store(assign.name()));
            }
            case Ast.CompoundAssign assign -> {
                code.add(new Ir.Load(assign.name()));
                expression(assign.value());
                mark(assign.span());
                code.add(new Ir.Binary(operatorName(assign.op())));
                code.add(new Ir.Store(assign.name()));
            }
            case Ast.Command command -> {
                emitCallArguments(command.args());
                mark(command.span());
                code.add(new Ir.Call(command.name(), command.args().size()));
                // Every call yields exactly one IR value, including print and write, whose
                // result is `nothing`. A statement discards it, so the pop is unconditional.
                code.add(new Ir.Pop());
            }
            case Ast.If conditional -> ifStatement(conditional);
            case Ast.While loop -> whileStatement(loop);
            case Ast.ForRange loop -> forRange(loop);
            case Ast.ForEach loop -> forEach(loop);
            case Ast.FuncDecl function -> functions.add(function(function.name(), function.params(), function.body()));
            case Ast.Return ret -> {
                if (ret.value() != null) {
                    expression(ret.value());
                } else {
                    code.add(new Ir.Const(null));
                }
                code.add(new Ir.Return());
            }
            case Ast.Break brk -> {
                if (loops.isEmpty()) {
                    throw new OqError("'break' outside a loop", brk.span());
                }
                code.add(new Ir.Break(loops.get(loops.size() - 1).id()));
            }
            case Ast.Continue cont -> {
                if (loops.isEmpty()) {
                    throw new OqError("'continue' outside a loop", cont.span());
                }
                code.add(new Ir.Continue(loops.get(loops.size() - 1).id()));
            }
            case Ast.DataDecl ignored -> {
                // Types are hoisted into the type scopes for this block; construction is
                // lowered where a call names the type.
            }
            case Ast.ExprStmt expr -> {
                expression(expr.expr());
                code.add(new Ir.Pop());
            }
            case Ast.IndexAssign assign -> {
                expression(assign.target());
                expression(assign.index());
                expression(assign.value());
                mark(assign.span());
                // SetIndex is void in the bytecode backend, so nothing is left to pop.
                code.add(new Ir.SetIndex());
            }
            case Ast.FieldAssign assign -> {
                expression(assign.target());
                expression(assign.value());
                mark(assign.span());
                // SetField is void in the bytecode backend, so nothing is left to pop.
                code.add(new Ir.SetField(assign.field()));
            }
            case Ast.TryStmt attempt -> tryStatement(attempt);
            case Ast.UseStmt ignored -> {
                // Resolving a Java class is interpreter-only; compiling it would need the
                // Java interop bridge, which the backend does not emit yet.
                code.add(new Ir.Refuse("'use' imports a Java class, which is not compiled yet"));
            }
            case Ast.ImportStmt ignored -> {
                code.add(new Ir.Refuse("module imports are not compiled yet"));
            }
        }
    }

    private void tryStatement(Ast.TryStmt attempt) {
        String handler = label("catch");
        String done = label("catch_end");
        code.add(new Ir.TryStart(handler));
        code.add(new Ir.ScopeEnter());
        block(attempt.body());
        code.add(new Ir.ScopeExit());
        code.add(new Ir.TryEnd(handler));
        // Normal completion skips the handler, which is reachable only from the exception
        // table entry. The handler label carries the caught OqError on the operand stack.
        code.add(new Ir.JumpLabel(done));
        code.add(new Ir.HandlerLabel(handler));
        code.add(new Ir.CatchValue());
        code.add(new Ir.ScopeEnter());
        code.add(new Ir.Declare(attempt.errorName(), true));
        block(attempt.handler());
        code.add(new Ir.ScopeExit());
        code.add(new Ir.Label(done));
    }

    /**
     * Lowers a lambda to a generated static method plus a {@link Ir.Lambda} that creates the
     * value. A method named {@code lambda$N} lives next to the program's own functions, so the
     * body is ordinary emitted bytecode and there is no second execution path.
     *
     * <p>Only a lambda that captures nothing is compiled. Capturing would mean copying the
     * enclosing variable's current value into the created function, and a lambda that reads a
     * variable assigned later must see the later value; refusing is honest, silently binding
     * the old value is not.
     */
    private void lambda(Ast.Lambda value, int captures) {
        String name = "lambda$" + lambdaCounter++;
        List<Ir.Instr> saved = code;
        code = Ir.list();
        List<String> params = new ArrayList<>(value.params());
        // Parameters already occupy their slots, exactly as for a `fun`; declaring them again
        // would allocate a second slot and emit a store with nothing on the stack.
        if (value.body() != null) {
            expression(value.body());
            code.add(new Ir.Return());
        } else {
            block(value.blockBody());
            code.add(new Ir.Const(null));
            code.add(new Ir.Return());
        }
        functions.add(new Ir.Function(name, List.copyOf(params), List.copyOf(code)));
        code = saved;
        code.add(new Ir.Lambda(name, params.size(), captures));
    }

    /** The text a failed call reports, matching the interpreter's own message. */
    private static String describeCallee(Ast.Expr callee) {
        return callee instanceof Ast.Var var ? var.name() : "<value>";
    }

    private Ir.Function function(String name, List<String> params, List<Ast.Stmt> body) {
        List<Ir.Instr> saved = code;
        code = Ir.list();
        // Every function body records its source positions unconditionally: an error raised
        // here can be caught by a caller's `try`, and the span it reports must be this
        // function's own source line.
        pushTypes(body);
        for (Ast.Stmt stmt : body) {
            statement(stmt);
        }
        popTypes();
        code.add(new Ir.Const(null));
        code.add(new Ir.Return());
        Ir.Function function = new Ir.Function(name, params, List.copyOf(code));
        code = saved;
        return function;
    }

    /** Lowers a nested block, with the `data` types the interpreter would hoist into it. */
    private void block(List<Ast.Stmt> statements) {
        pushTypes(statements);
        for (Ast.Stmt stmt : statements) {
            statement(stmt);
        }
        popTypes();
    }

    private void ifStatement(Ast.If conditional) {
        String end = label("end");
        for (Ast.Branch branch : conditional.branches()) {
            String next = label("next");
            expression(branch.condition());
            code.add(new Ir.JumpIfFalseLabel(next));
            code.add(new Ir.ScopeEnter());
            block(branch.body());
            code.add(new Ir.ScopeExit());
            code.add(new Ir.JumpLabel(end));
            code.add(new Ir.Label(next));
        }
        if (!conditional.elseBranch().isEmpty()) {
            code.add(new Ir.ScopeEnter());
            block(conditional.elseBranch());
            code.add(new Ir.ScopeExit());
        }
        code.add(new Ir.Label(end));
    }

    private void whileStatement(Ast.While loop) {
        String id = "loop" + loopCounter++;
        // `continue` in a while loop re-tests the condition, so the continue label is the
        // head of the loop. Keeping the `_step` name uniform lets the backend resolve
        // break and continue by convention without threading extra state through the IR.
        String head = id + "_step";
        String exitLabel = id + "_end";
        LoopContext context = new LoopContext(id, exitLabel, head);
        code.add(new Ir.Label(head));
        loops.add(context);
        expression(loop.condition());
        code.add(new Ir.JumpIfFalseLabel(exitLabel));
        code.add(new Ir.ScopeEnter());
        block(loop.body());
        code.add(new Ir.ScopeExit());
        code.add(new Ir.JumpLabel(head));
        loops.remove(loops.size() - 1);
        code.add(new Ir.Label(exitLabel));
    }

    /**
     * Mirrors the interpreter exactly: both bounds are evaluated once, the step is chosen from
     * the direction, and an exclusive upper bound is turned into the last included value. A
     * naive {@code i <= to} loop silently runs zero times for descending ranges, which is why
     * this is spelled out instead of being simplified.
     */
    private void forRange(Ast.ForRange loop) {
        String from = temp("from");
        String to = temp("to");
        String counter = temp("i");
        String step = temp("step");
        String last = temp("end");
        String id = "loop" + loopCounter++;
        String head = id + "_head";
        String descending = id + "_desc";
        String stepChosen = id + "_step_chosen";
        String body = id + "_body";
        String stepLabel = id + "_step";
        String exitLabel = id + "_end";

        expression(loop.from());
        code.add(new Ir.Store(from));
        expression(loop.to());
        code.add(new Ir.Store(to));
        code.add(new Ir.Load(from));
        code.add(new Ir.Store(counter));

        code.add(new Ir.Load(from));
        code.add(new Ir.Load(to));
        code.add(new Ir.Binary("<="));
        code.add(new Ir.JumpIfFalseLabel(descending));
        code.add(new Ir.Const(1L));
        code.add(new Ir.Store(step));
        code.add(new Ir.JumpLabel(stepChosen));
        code.add(new Ir.Label(descending));
        code.add(new Ir.Const(-1L));
        code.add(new Ir.Store(step));
        code.add(new Ir.Label(stepChosen));

        code.add(new Ir.Load(to));
        if (!loop.inclusive()) {
            code.add(new Ir.Load(step));
            code.add(new Ir.Binary("-"));
        }
        code.add(new Ir.Store(last));

        code.add(new Ir.Label(head));
        loops.add(new LoopContext(id, exitLabel, stepLabel));
        code.add(new Ir.Load(step));
        code.add(new Ir.Const(0L));
        code.add(new Ir.Binary(">"));
        code.add(new Ir.JumpIfFalseLabel(descending + "_check"));
        code.add(new Ir.Load(counter));
        code.add(new Ir.Load(last));
        code.add(new Ir.Binary("<="));
        code.add(new Ir.JumpIfFalseLabel(exitLabel));
        code.add(new Ir.JumpLabel(body));
        code.add(new Ir.Label(descending + "_check"));
        code.add(new Ir.Load(counter));
        code.add(new Ir.Load(last));
        code.add(new Ir.Binary(">="));
        code.add(new Ir.JumpIfFalseLabel(exitLabel));
        code.add(new Ir.Label(body));
        code.add(new Ir.ScopeEnter());
        code.add(new Ir.Load(counter));
        code.add(new Ir.Declare(loop.name(), true));
        block(loop.body());
        code.add(new Ir.ScopeExit());
        code.add(new Ir.Label(stepLabel));
        code.add(new Ir.Load(counter));
        code.add(new Ir.Load(step));
        code.add(new Ir.Binary("+"));
        code.add(new Ir.Store(counter));
        code.add(new Ir.JumpLabel(head));
        loops.remove(loops.size() - 1);
        code.add(new Ir.Label(exitLabel));
    }

    private void forEach(Ast.ForEach loop) {
        String iterator = temp("iter");
        String id = "loop" + loopCounter++;
        String head = id + "_head";
        String step = id + "_step";
        String exitLabel = id + "_end";
        // A real iterator, not length + Index: map keys and set members are not 0..n-1,
        // and the interpreter iterates the same way through Values.iterable.
        expression(loop.iterable());
        mark(loop.span());
        code.add(new Ir.IterNew());
        code.add(new Ir.Store(iterator));
        code.add(new Ir.Label(head));
        loops.add(new LoopContext(id, exitLabel, step));
        code.add(new Ir.Load(iterator));
        code.add(new Ir.IterHasNext());
        code.add(new Ir.JumpIfFalseLabel(exitLabel));
        // The loop variable belongs to the body's scope, exactly as the interpreter's
        // per-iteration environment: `ScopeEnter` must precede its `Declare`, or the
        // binding leaks into the enclosing scope after the loop ends.
        code.add(new Ir.ScopeEnter());
        code.add(new Ir.Load(iterator));
        code.add(new Ir.IterNext());
        code.add(new Ir.Declare(loop.name(), true));
        block(loop.body());
        code.add(new Ir.ScopeExit());
        code.add(new Ir.Label(step));
        code.add(new Ir.JumpLabel(head));
        loops.remove(loops.size() - 1);
        code.add(new Ir.Label(exitLabel));
    }

    private void expression(Ast.Expr expr) {
        switch (expr) {
            case Ast.NumberLit number -> code.add(new Ir.Const(number.value()));
            case Ast.BoolLit bool -> code.add(new Ir.Const(bool.value()));
            case Ast.NothingLit ignored -> code.add(new Ir.Const(null));
            case Ast.StringLit text -> stringLiteral(text);
            case Ast.Var var -> code.add(new Ir.Load(var.name()));
            case Ast.ListLit list -> {
                for (Ast.Expr item : list.items()) {
                    expression(item);
                }
                code.add(new Ir.MakeList(list.items().size()));
            }
            case Ast.SetLit set -> {
                for (Ast.Expr item : set.items()) {
                    expression(item);
                }
                code.add(new Ir.MakeSet(set.items().size()));
            }
            case Ast.MapLit map -> {
                for (Ast.Entry entry : map.entries()) {
                    expression(entry.key());
                    expression(entry.value());
                }
                code.add(new Ir.MakeMap(map.entries().size()));
            }
            case Ast.Unary unary -> {
                expression(unary.operand());
                mark(unary.span());
                code.add(new Ir.Unary(
                        unary.op() == Token.Kind.KEYWORD ? "not" : operatorName(unary.op())));
                if (unary.op() == Token.Kind.KEYWORD) {
                    code.add(new Ir.ToBool());
                }
            }
            case Ast.Binary binary -> {
                expression(binary.left());
                expression(binary.right());
                mark(binary.span());
                code.add(new Ir.Binary(operatorName(binary.op())));
            }
            case Ast.Logical logical -> {
                // The interpreter short-circuits and always yields a boolean, so `1 and 2`
                // is true, not 2. Both sides are coerced and the result lives in a temporary
                // so every label in the IR has an empty operand stack; the classfile backend
                // depends on that invariant to emit simple stack map frames.
                String result = temp("bool");
                String shortCircuit = label("logic");
                String done = label("logic_end");
                expression(logical.left());
                code.add(new Ir.ToBool());
                code.add(new Ir.JumpIfFalseLabel(shortCircuit));
                if (logical.op().equals("and")) {
                    expression(logical.right());
                    code.add(new Ir.ToBool());
                    code.add(new Ir.Store(result));
                    code.add(new Ir.JumpLabel(done));
                    code.add(new Ir.Label(shortCircuit));
                    code.add(new Ir.Const(false));
                    code.add(new Ir.Store(result));
                } else {
                    code.add(new Ir.Const(true));
                    code.add(new Ir.Store(result));
                    code.add(new Ir.JumpLabel(done));
                    code.add(new Ir.Label(shortCircuit));
                    expression(logical.right());
                    code.add(new Ir.ToBool());
                    code.add(new Ir.Store(result));
                }
                code.add(new Ir.Label(done));
                code.add(new Ir.Load(result));
            }
            case Ast.Call call -> {
                if (call.callee() instanceof Ast.Var var) {
                    DataType type = lookupType(var.name());
                    if (type != null) {
                        constructor(call, type);
                    } else if (callableNames.contains(var.name())) {
                        // The name holds a function value, so the call goes through the value.
                        // A builtin or a `fun` of the same name must not win on name alone.
                        code.add(new Ir.Load(var.name()));
                        emitCallArguments(call.args());
                        mark(call.span());
                        code.add(new Ir.CallValue(call.args().size(), var.name()));
                    } else {
                        emitCallArguments(call.args());
                        mark(call.span());
                        code.add(new Ir.Call(var.name(), call.args().size()));
                    }
                } else {
                    expression(call.callee());
                    emitCallArguments(call.args());
                    code.add(new Ir.CallValue(call.args().size(), describeCallee(call.callee())));
                }
            }
            case Ast.MethodCall call -> {
                expression(call.receiver());
                emitCallArguments(call.args());
                mark(call.span());
                code.add(new Ir.MethodCall(call.method(), call.args().size() + 1));
            }
            case Ast.Index index -> {
                expression(index.target());
                expression(index.index());
                mark(index.span());
                code.add(new Ir.Index());
            }
            case Ast.Field field -> {
                expression(field.target());
                mark(field.span());
                code.add(new Ir.GetField(field.name()));
            }
            case Ast.Lambda lambda -> lambda(lambda, 0);
            case Ast.Grouping group -> expression(group.expr());
            case Ast.InExpr in -> {
                expression(in.item());
                expression(in.container());
                mark(in.span());
                code.add(new Ir.Contains());
            }
            case Ast.RangeExpr range -> {
                expression(range.from());
                expression(range.to());
                mark(range.span());
                code.add(new Ir.Range(range.inclusive()));
            }
        }
    }

    private void stringLiteral(Ast.StringLit text) {
        if (text.parts().size() == 1 && text.parts().get(0) instanceof Ast.TextPart part) {
            code.add(new Ir.Const(part.text()));
            return;
        }
        List<Ast.StringPart> parts = text.parts();
        if (parts.isEmpty()) {
            code.add(new Ir.Const(""));
            return;
        }
        Ast.StringPart first = parts.get(0);
        if (first instanceof Ast.TextPart raw) {
            code.add(new Ir.Const(raw.text()));
        } else if (first instanceof Ast.ExprPart exprPart) {
            expression(exprPart.expr());
            code.add(new Ir.Text());
        }
        for (int i = 1; i < parts.size(); i++) {
            Ast.StringPart part = parts.get(i);
            if (part instanceof Ast.TextPart raw) {
                code.add(new Ir.Const(raw.text()));
            } else if (part instanceof Ast.ExprPart exprPart) {
                expression(exprPart.expr());
                code.add(new Ir.Text());
            }
            code.add(new Ir.Binary("+"));
        }
    }

    private void emitCallArguments(List<Ast.Expr> args) {
        for (Ast.Expr arg : args) {
            expression(arg);
        }
    }

    /**
     * Lowers a constructor call the way the interpreter's {@code DataCtor} builds a record:
     * given fields take the caller's values, omitted fields evaluate their default, and an
     * omitted field without a default is an error. Refusing at build time is safe because the
     * interpreter would fail at that same line, only later.
     */
    private void constructor(Ast.Call call, DataType type) {
        List<Ast.FieldDef> fields = type.fields();
        if (call.args().size() > fields.size()) {
            code.add(new Ir.Refuse("construction of '" + type.name() + "' with "
                    + call.args().size() + " argument(s); it declares " + fields.size()
                    + " field(s), so the interpreter would reject this call"));
            return;
        }
        for (int i = 0; i < fields.size(); i++) {
            Ast.FieldDef field = fields.get(i);
            if (i < call.args().size()) {
                expression(call.args().get(i));
            } else if (field.defaultValue() != null) {
                if (!isScopeFree(field.defaultValue())) {
                    // The interpreter evaluates a default in the scope where the type was
                    // declared; this backend evaluates it where the record is constructed.
                    // Those differ as soon as the default reads a name, so only literals
                    // are compiled and everything else is refused by name.
                    code.add(new Ir.Refuse("default for field '" + field.name() + "' of '"
                            + type.name() + "' reads a name or calls a function;"
                            + " compiled code cannot evaluate it in the declaration's scope"));
                    return;
                }
                expression(field.defaultValue());
            } else {
                code.add(new Ir.Refuse("construction of '" + type.name()
                        + "' omits field '" + field.name() + "', which has no default;"
                        + " the interpreter would reject this call"));
                return;
            }
        }
        List<String> names = new ArrayList<>();
        for (Ast.FieldDef field : fields) {
            names.add(field.name());
        }
        code.add(new Ir.MakeData(type.name(), List.copyOf(names)));
    }

    /**
     * True when an expression means the same thing wherever it is evaluated: literals only,
     * with no names to look up and no calls to run. Defaults that pass this test can be
     * lowered at the construction site without changing behaviour.
     */
    private boolean isScopeFree(Ast.Expr expr) {
        return switch (expr) {
            case Ast.NumberLit ignored -> true;
            case Ast.BoolLit ignored -> true;
            case Ast.NothingLit ignored -> true;
            case Ast.StringLit text -> text.parts().stream()
                    .allMatch(part -> part instanceof Ast.TextPart);
            case Ast.ListLit list -> list.items().stream().allMatch(this::isScopeFree);
            case Ast.SetLit set -> set.items().stream().allMatch(this::isScopeFree);
            case Ast.MapLit map -> map.entries().stream()
                    .allMatch(entry -> isScopeFree(entry.key()) && isScopeFree(entry.value()));
            case Ast.Grouping group -> isScopeFree(group.expr());
            case Ast.Unary unary -> isScopeFree(unary.operand());
            case Ast.Binary binary -> isScopeFree(binary.left()) && isScopeFree(binary.right());
            default -> false;
        };
    }

    /**
     * Records the source position of the operation that follows, so an error it raises can be
     * reported with the same line, column and file as {@code oq run}.
     *
     * <p>Marks are emitted everywhere, not only inside {@code try}: code outside a {@code try}
     * can still raise, and a compiled jar must report that error at the same position the
     * interpreter does. A mark is three constants and one call, so the cost is bytecode size,
     * not behaviour.
     */
    private void mark(Span span) {
        if (span != null) code.add(new Ir.At(span));
    }

    private String temp(String prefix) {
        return "_" + prefix + tempCounter++;
    }

    private String label(String prefix) {
        return prefix + "_" + labelCounter++;
    }

    private static String operatorName(Token.Kind kind) {
        return switch (kind) {
            case PLUS -> "+";
            case MINUS -> "-";
            case STAR -> "*";
            case SLASH -> "/";
            case PERCENT -> "%";
            case STARSTAR -> "**";
            case EQEQ -> "==";
            case NOTEQ -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            default -> throw new OqError("unsupported operator in the compiler: " + kind, Span.synthetic());
        };
    }
}
