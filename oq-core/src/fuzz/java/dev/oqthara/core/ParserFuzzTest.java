package dev.oqthara.core;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fuzzes the parser with arbitrary text. Parsing either succeeds or raises {@link OqError};
 * any other exception is a defect, because the CLI would report it as an internal error.
 *
 * <p>Successful trees are also walked: every node must carry a usable span. That turns a
 * silently corrupt AST into a visible finding instead of an unexplained failure later in
 * the interpreter or compiler.
 */
class ParserFuzzTest {
    @FuzzTest(maxDuration = "30s")
    void parserNeverThrowsUnexpectedExceptions(FuzzedDataProvider data) {
        String source = data.consumeRemainingAsString();
        Ast.Program program = parse("<fuzz>", source);
        if (program != null) checkSpans(program);
    }

    @FuzzTest(maxDuration = "20s")
    void deeplyNestedInputDoesNotOverflow(FuzzedDataProvider data) {
        int depth = data.consumeInt(0, 2000);
        String source = switch (data.consumeInt(0, 5)) {
            case 0 -> "x = " + "(".repeat(depth) + "1" + ")".repeat(depth);
            case 1 -> "x = " + "[".repeat(depth) + "1" + "]".repeat(depth);
            case 2 -> "x = " + "-".repeat(depth) + "1";
            case 3 -> "x = " + "not ".repeat(depth) + "true";
            case 4 -> "x = " + "1 ** ".repeat(depth) + "1";
            default -> {
                StringBuilder nested = new StringBuilder("0");
                for (int i = 0; i < Math.min(depth, 200); i++) {
                    nested.insert(0, "{\"").append("\"}");
                }
                yield "x = \"" + nested + "\"\n";
            }
        };
        parse("<fuzz>", source);
    }

    @FuzzTest(maxDuration = "20s")
    void deeplyNestedBlocksDoNotOverflow(FuzzedDataProvider data) {
        int depth = data.consumeInt(0, 600);
        String opener = data.pickValue(new String[]{"if true\n", "while true\n",
                "for i in 1 to 2\n", "fun g()\n", "try\n"});
        String closer = opener.startsWith("try") ? "catch e\nend\n" : "end\n";
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < depth; i++) source.append(opener);
        source.append("print 1\n");
        for (int i = 0; i < depth; i++) source.append(closer);
        parse("<fuzz>", source.toString());
    }

    /** Returns the program, or {@code null} when the input is a normal syntax error. */
    private static Ast.Program parse(String name, String source) {
        try {
            return Parser.parse(name, source);
        } catch (OqError expected) {
            return null;
        } catch (StackOverflowError e) {
            throw new AssertionError("parser overflowed on input of length " + source.length()
                    + ": " + repr(source), e);
        } catch (RuntimeException e) {
            throw new AssertionError("parser crashed on " + repr(source) + ": " + e, e);
        }
    }

    private static void checkSpans(Ast.Program program) {
        Deque<Object> work = new ArrayDeque<>(program.statements());
        while (!work.isEmpty()) {
            Object next = work.pop();
            if (next instanceof Ast.Branch branch) {
                work.add(branch.condition());
                work.addAll(branch.body());
                continue;
            }
            if (!(next instanceof Ast.Node node)) continue;
            Span span = node.span();
            if (span.line() < 1 || span.column() < 1
                    || span.endLine() < span.line()
                    || (span.endLine() == span.line() && span.endColumn() < span.column())) {
                throw new AssertionError("invalid span " + span + " on " + node.getClass());
            }
            pushChildren(node, work);
        }
    }

    private static void pushChildren(Ast.Node node, Deque<Object> work) {
        switch (node) {
            case Ast.If x -> {
                work.addAll(x.branches());
                work.addAll(x.elseBranch());
            }
            case Ast.While x -> {
                work.add(x.condition());
                work.addAll(x.body());
            }
            case Ast.ForEach x -> {
                work.add(x.iterable());
                work.addAll(x.body());
            }
            case Ast.ForRange x -> {
                work.add(x.from());
                work.add(x.to());
                work.addAll(x.body());
            }
            case Ast.FuncDecl x -> work.addAll(x.body());
            case Ast.Return x -> {
                if (x.value() != null) work.add(x.value());
            }
            case Ast.DataDecl x -> {
                for (Ast.FieldDef field : x.fields()) {
                    if (field.defaultValue() != null) work.add(field.defaultValue());
                }
            }
            case Ast.ClassDecl x -> {
                for (Ast.FieldDef field : x.fields()) {
                    if (field.defaultValue() != null) work.add(field.defaultValue());
                }
                for (Ast.MethodDecl method : x.methods()) work.addAll(method.body());
                if (x.init() != null) work.addAll(x.init());
            }
            case Ast.ContractDecl ignored -> {
                // Signatures carry a name and an arity, never an expression.
            }
            case Ast.ExprStmt x -> work.add(x.expr());
            case Ast.Assign x -> work.add(x.value());
            case Ast.Destructure x -> work.add(x.value());
            case Ast.CompoundAssign x -> work.add(x.value());
            case Ast.IndexAssign x -> {
                work.add(x.target());
                work.add(x.index());
                work.add(x.value());
            }
            case Ast.FieldAssign x -> {
                work.add(x.target());
                work.add(x.value());
            }
            case Ast.Command x -> work.addAll(x.args());
            case Ast.TryStmt x -> {
                work.addAll(x.body());
                work.addAll(x.handler());
            }
            case Ast.StringLit x -> {
                for (Ast.StringPart part : x.parts()) {
                    if (part instanceof Ast.ExprPart exprPart) work.add(exprPart.expr());
                }
            }
            case Ast.ListLit x -> work.addAll(x.items());
            case Ast.SetLit x -> work.addAll(x.items());
            case Ast.MapLit x -> {
                for (Ast.Entry entry : x.entries()) {
                    work.add(entry.key());
                    work.add(entry.value());
                }
            }
            case Ast.Unary x -> work.add(x.operand());
            case Ast.Binary x -> {
                work.add(x.left());
                work.add(x.right());
            }
            case Ast.Logical x -> {
                work.add(x.left());
                work.add(x.right());
            }
            case Ast.Call x -> {
                work.add(x.callee());
                work.addAll(x.args());
            }
            case Ast.MethodCall x -> {
                work.add(x.receiver());
                work.addAll(x.args());
            }
            case Ast.Index x -> {
                work.add(x.target());
                work.add(x.index());
            }
            case Ast.Field x -> work.add(x.target());
            case Ast.Lambda x -> {
                if (x.body() != null) work.add(x.body());
                if (x.blockBody() != null) work.addAll(x.blockBody());
            }
            case Ast.Grouping x -> work.add(x.expr());
            case Ast.InExpr x -> {
                work.add(x.item());
                work.add(x.container());
            }
            case Ast.RangeExpr x -> {
                work.add(x.from());
                work.add(x.to());
            }
            default -> {
                // Leaf nodes and declarations without child expressions.
            }
        }
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
