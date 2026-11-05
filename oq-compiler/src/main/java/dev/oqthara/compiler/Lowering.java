package dev.oqthara.compiler;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Token;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers the AST into a flat stack IR. The IR is backend-agnostic: the classfile
 * backend and the disassembler both consume it unchanged.
 */
public final class Lowering {
    private final List<Ir.Function> functions = new ArrayList<>();
    private final List<String> divergences = new ArrayList<>();
    private final List<LoopContext> loops = new ArrayList<>();
    private int loopCounter = 0;

    private record LoopContext(String id, String breakLabel, String continueLabel) {
    }
    private List<Ir.Instr> code;
    private int tempCounter = 0;
    private int labelCounter = 0;

    public static Ir.Program lower(Ast.Program program) {
        return lowerWithReport(program).program();
    }

    public record Result(Ir.Program program, List<String> divergences) {
    }

    public static Result lowerWithReport(Ast.Program program) {
        Lowering lowering = new Lowering();
        lowering.code = Ir.list();
        for (Ast.Stmt stmt : program.statements()) {
            lowering.statement(stmt);
        }
        lowering.code.add(new Ir.Halt());
        Ir.Function main = new Ir.Function("main", List.of(), List.copyOf(lowering.code));
        return new Result(new Ir.Program(main, List.copyOf(lowering.functions)),
                List.copyOf(lowering.divergences));
    }

    private void statement(Ast.Stmt stmt) {
        switch (stmt) {
            case Ast.VarDecl decl -> {
                // VarDecl defines a new binding in the current scope, which is not the same
                // as assignment: assignment updates an existing binding instead of shadowing.
                expression(decl.value());
                code.add(new Ir.Declare(decl.name(), false));
            }
            case Ast.Assign assign -> {
                expression(assign.value());
                code.add(new Ir.Store(assign.name()));
            }
            case Ast.CompoundAssign assign -> {
                code.add(new Ir.Load(assign.name()));
                expression(assign.value());
                code.add(new Ir.Binary(operatorName(assign.op())));
                code.add(new Ir.Store(assign.name()));
            }
            case Ast.Command command -> {
                emitCallArguments(command.args());
                code.add(new Ir.Call(command.name(), command.args().size()));
                // print/write are void in the backend; every other command yields a value,
                // which a statement discards. Emitting a Pop for a void call underflows.
                if (!command.name().equals("print") && !command.name().equals("write")) {
                    code.add(new Ir.Pop());
                }
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
                // Types are runtime constructs; nothing to emit yet.
            }
            case Ast.ExprStmt expr -> {
                expression(expr.expr());
                code.add(new Ir.Pop());
            }
            case Ast.IndexAssign assign -> {
                expression(assign.target());
                expression(assign.index());
                expression(assign.value());
                // SetIndex is void in the bytecode backend, so nothing is left to pop.
                code.add(new Ir.SetIndex());
            }
            case Ast.FieldAssign assign -> {
                expression(assign.target());
                expression(assign.value());
                code.add(new Ir.SetField(assign.field()));
                code.add(new Ir.Pop());
            }
            case Ast.TryStmt attempt -> {
                int handlerJump = code.size();
                code.add(new Ir.TryStart(-1));
                for (Ast.Stmt body : attempt.body()) {
                    statement(body);
                }
                code.add(new Ir.TryEnd());
                int skipHandler = code.size();
                code.add(new Ir.Jump(-1));
                int handler = code.size();
                code.set(handlerJump, new Ir.TryStart(handler));
                for (Ast.Stmt handlerBody : attempt.handler()) {
                    statement(handlerBody);
                }
                code.set(skipHandler, new Ir.Jump(code.size()));
                code.add(new Ir.Store(attempt.errorName()));
                code.add(new Ir.Pop());
            }
            case Ast.UseStmt ignored -> {
                // Import resolution happens in the interpreter; the compiler resolves later.
            }
            case Ast.ImportStmt ignored -> {
                // Same as use.
            }
        }
    }

    private Ir.Function function(String name, List<String> params, List<Ast.Stmt> body) {
        List<Ir.Instr> saved = code;
        code = Ir.list();
        for (Ast.Stmt stmt : body) {
            statement(stmt);
        }
        code.add(new Ir.Const(null));
        code.add(new Ir.Return());
        Ir.Function function = new Ir.Function(name, params, List.copyOf(code));
        code = saved;
        return function;
    }

    private void ifStatement(Ast.If conditional) {
        String end = label("end");
        for (Ast.Branch branch : conditional.branches()) {
            String next = label("next");
            expression(branch.condition());
            code.add(new Ir.JumpIfFalseLabel(next));
            code.add(new Ir.ScopeEnter());
            for (Ast.Stmt stmt : branch.body()) {
                statement(stmt);
            }
            code.add(new Ir.ScopeExit());
            code.add(new Ir.JumpLabel(end));
            code.add(new Ir.Label(next));
        }
        if (!conditional.elseBranch().isEmpty()) {
            code.add(new Ir.ScopeEnter());
            for (Ast.Stmt stmt : conditional.elseBranch()) {
                statement(stmt);
            }
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
        for (Ast.Stmt stmt : loop.body()) {
            statement(stmt);
        }
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
        for (Ast.Stmt stmt : loop.body()) {
            statement(stmt);
        }
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
        for (Ast.Stmt stmt : loop.body()) {
            statement(stmt);
        }
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
            code.add(new Ir.Unary(unary.op() == Token.Kind.KEYWORD ? "not" : operatorName(unary.op())));
            if (unary.op() == Token.Kind.KEYWORD) {
                code.add(new Ir.ToBool());
            }
        }
            case Ast.Binary binary -> {
                expression(binary.left());
                expression(binary.right());
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
                    emitCallArguments(call.args());
                    code.add(new Ir.Call(var.name(), call.args().size()));
                } else {
                    expression(call.callee());
                    emitCallArguments(call.args());
                    code.add(new Ir.CallValue(call.args().size()));
                }
            }
            case Ast.MethodCall call -> {
                expression(call.receiver());
                emitCallArguments(call.args());
                code.add(new Ir.MethodCall(call.method(), call.args().size() + 1));
            }
            case Ast.Index index -> {
                expression(index.target());
                expression(index.index());
                code.add(new Ir.Index());
            }
            case Ast.Field field -> {
                expression(field.target());
                code.add(new Ir.GetField(field.name()));
            }
            case Ast.Lambda lambda -> code.add(new Ir.Const("<lambda>"));
            case Ast.Grouping group -> expression(group.expr());
            case Ast.InExpr in -> {
                expression(in.item());
                expression(in.container());
                code.add(new Ir.Contains());
            }
            case Ast.RangeExpr range -> {
                expression(range.from());
                expression(range.to());
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
