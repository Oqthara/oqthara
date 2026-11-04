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
    private List<Ir.Instr> code;
    private int tempCounter = 0;

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
                expression(decl.value());
                code.add(new Ir.Store(decl.name()));
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
                if (!command.name().equals("print")) {
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
            case Ast.Break ignored -> {
                // Prototype limitation: the IR has no loop-exit address yet.
                code.add(new Ir.Halt());
                divergences.add("break lowered to halt (line " + ignored.span().line() + ")");
            }
            case Ast.Continue ignored -> {
                divergences.add("continue is not lowered (line " + ignored.span().line() + ")");
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
                code.add(new Ir.SetIndex());
                code.add(new Ir.Pop());
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
        List<Integer> endJumps = new ArrayList<>();
        for (Ast.Branch branch : conditional.branches()) {
            expression(branch.condition());
            int falseJump = code.size();
            code.add(new Ir.JumpIfFalse(-1));
            for (Ast.Stmt stmt : branch.body()) {
                statement(stmt);
            }
            endJumps.add(code.size());
            code.add(new Ir.Jump(-1));
            code.set(falseJump, new Ir.JumpIfFalse(code.size()));
        }
        for (Ast.Stmt stmt : conditional.elseBranch()) {
            statement(stmt);
        }
        int end = code.size();
        for (int jump : endJumps) {
            code.set(jump, new Ir.Jump(end));
        }
    }

    private void whileStatement(Ast.While loop) {
        int start = code.size();
        expression(loop.condition());
        int exit = code.size();
        code.add(new Ir.JumpIfFalse(-1));
        for (Ast.Stmt stmt : loop.body()) {
            statement(stmt);
        }
        code.add(new Ir.Jump(start));
        code.set(exit, new Ir.JumpIfFalse(code.size()));
    }

    private void forRange(Ast.ForRange loop) {
        String counter = temp("i");
        expression(loop.from());
        code.add(new Ir.Store(counter));
        int start = code.size();
        code.add(new Ir.Load(counter));
        expression(loop.to());
        code.add(new Ir.Binary(loop.inclusive() ? "<=" : "<"));
        int exit = code.size();
        code.add(new Ir.JumpIfFalse(-1));
        code.add(new Ir.Load(counter));
        code.add(new Ir.Store(loop.name()));
        for (Ast.Stmt stmt : loop.body()) {
            statement(stmt);
        }
        code.add(new Ir.Load(counter));
        code.add(new Ir.Const(1L));
        code.add(new Ir.Binary("+"));
        code.add(new Ir.Store(counter));
        code.add(new Ir.Jump(start));
        code.set(exit, new Ir.JumpIfFalse(code.size()));
    }

    private void forEach(Ast.ForEach loop) {
        String iterator = temp("iter");
        String index = temp("n");
        expression(loop.iterable());
        code.add(new Ir.Store(iterator));
        code.add(new Ir.Const(0L));
        code.add(new Ir.Store(index));
        int start = code.size();
        code.add(new Ir.Load(index));
        code.add(new Ir.Load(iterator));
        code.add(new Ir.Call("length", 1));
        code.add(new Ir.Binary("<"));
        int exit = code.size();
        code.add(new Ir.JumpIfFalse(-1));
        code.add(new Ir.Load(iterator));
        code.add(new Ir.Load(index));
        code.add(new Ir.Index());
        code.add(new Ir.Store(loop.name()));
        for (Ast.Stmt stmt : loop.body()) {
            statement(stmt);
        }
        code.add(new Ir.Load(index));
        code.add(new Ir.Const(1L));
        code.add(new Ir.Binary("+"));
        code.add(new Ir.Store(index));
        code.add(new Ir.Jump(start));
        code.set(exit, new Ir.JumpIfFalse(code.size()));
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
            }
            case Ast.Binary binary -> {
                expression(binary.left());
                expression(binary.right());
                code.add(new Ir.Binary(operatorName(binary.op())));
            }
            case Ast.Logical logical -> {
                expression(logical.left());
                if (logical.op().equals("and")) {
                    int skip = code.size();
                    code.add(new Ir.JumpIfFalse(-1));
                    expression(logical.right());
                    code.set(skip, new Ir.JumpIfFalse(code.size()));
                } else {
                    expression(logical.right());
                    code.add(new Ir.Binary("or"));
                }
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
                code.add(new Ir.Call(call.method(), call.args().size() + 1));
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
                code.add(new Ir.Call("contains", 2));
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
            code.add(new Ir.Call("text", 1));
        }
        for (int i = 1; i < parts.size(); i++) {
            Ast.StringPart part = parts.get(i);
            if (part instanceof Ast.TextPart raw) {
                code.add(new Ir.Const(raw.text()));
            } else if (part instanceof Ast.ExprPart exprPart) {
                expression(exprPart.expr());
                code.add(new Ir.Call("text", 1));
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
