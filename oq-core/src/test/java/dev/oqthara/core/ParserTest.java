package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {
    @Test
    void parsesEmptyProgram() {
        Ast.Program program = Parser.parse("test", "");
        assertEquals(0, program.statements().size());
    }

    @Test
    void parsesAssignment() {
        Ast.Program program = Parser.parse("test", "x = 1");
        Ast.Assign assign = assertInstanceOf(Ast.Assign.class, program.statements().get(0));
        assertEquals("x", assign.name());
    }

    @Test
    void parsesIfChain() {
        Ast.Program program = Parser.parse("test", """
                if a
                  print 1
                else if b
                  print 2
                else
                  print 3
                end
                """);
        Ast.If conditional = assertInstanceOf(Ast.If.class, program.statements().get(0));
        assertEquals(2, conditional.branches().size());
        assertEquals(1, conditional.elseBranch().size());
    }

    @Test
    void parsesFunction() {
        Ast.Program program = Parser.parse("test", """
                fun add(a, b)
                  return a + b
                end
                """);
        Ast.FuncDecl function = assertInstanceOf(Ast.FuncDecl.class, program.statements().get(0));
        assertEquals("add", function.name());
        assertEquals(2, function.params().size());
    }

    @Test
    void parsesRange() {
        Ast.Program program = Parser.parse("test", """
                for i in 0 until 10
                  print i
                end
                """);
        Ast.ForRange loop = assertInstanceOf(Ast.ForRange.class, program.statements().get(0));
        assertEquals(false, loop.inclusive());
    }

    @Test
    void parsesLambda() {
        Ast.Program program = Parser.parse("test", "f = x -> x + 1");
        Ast.Assign assign = assertInstanceOf(Ast.Assign.class, program.statements().get(0));
        Ast.Lambda lambda = assertInstanceOf(Ast.Lambda.class, assign.value());
        assertEquals(1, lambda.params().size());
    }

    @Test
    void parsesTwoParameterLambda() {
        Ast.Program program = Parser.parse("test", "f = (a, b) -> a + b");
        Ast.Assign assign = assertInstanceOf(Ast.Assign.class, program.statements().get(0));
        Ast.Lambda lambda = assertInstanceOf(Ast.Lambda.class, assign.value());
        assertEquals(2, lambda.params().size());
    }

    @Test
    void parsesMultilineList() {
        Ast.Program program = Parser.parse("test", """
                xs = [
                  1,
                  2,
                ]
                """);
        Ast.Assign assign = assertInstanceOf(Ast.Assign.class, program.statements().get(0));
        Ast.ListLit list = assertInstanceOf(Ast.ListLit.class, assign.value());
        assertEquals(2, list.items().size());
    }

    @Test
    void reportsMissingEnd() {
        OqError error = assertThrows(OqError.class, () -> Parser.parse("test", "if true\nprint 1"));
        assertTrue(error.getMessage().contains("end"));
        assertTrue(error.hint().contains("end of the file"));
    }

    @Test
    void toIsUsableAsAVariableName() {
        Ast.Program program = Parser.parse("test", "to = 5\nprint to");
        Ast.Assign assign = assertInstanceOf(Ast.Assign.class, program.statements().get(0));
        assertEquals("to", assign.name());
    }

    @Test
    void toStillWorksAsARangeOperator() {
        Ast.Program program = Parser.parse("test", """
                for i in 1 to 3
                  print i
                end
                """);
        Ast.ForRange loop = assertInstanceOf(Ast.ForRange.class, program.statements().get(0));
        assertTrue(loop.inclusive());
    }

    @Test
    void untilIsUsableAsAParameterName() {
        Ast.Program program = Parser.parse("test", """
                fun f(from, until)
                  return from
                end
                """);
        Ast.FuncDecl function = assertInstanceOf(Ast.FuncDecl.class, program.statements().get(0));
        assertEquals(List.of("from", "until"), function.params());
    }

    @Test
    void tracksStatementSpan() {
        Ast.Program program = Parser.parse("test", "\n\nx = 1");
        assertEquals(3, program.statements().get(0).span().line());
    }
}
