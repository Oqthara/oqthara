package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocstringParsingTest {

    private static List<Ast.Stmt> parse(String source) {
        return Parser.parse("t.oq", source).statements();
    }

    @Test
    void aLiteralBeforeAFunctionDocumentsIt() {
        List<Ast.Stmt> statements = parse("""
                \"\"\"Adds two numbers.\"\"\"
                fun add(a, b)
                  return a + b
                end
                """);
        Ast.FuncDecl add = assertInstanceOf(Ast.FuncDecl.class, statements.get(0));
        assertEquals("Adds two numbers.", add.doc());
        assertEquals(1, statements.size(), "the docstring must not survive as its own statement");
    }

    @Test
    void aDocumentedDataDeclarationKeepsItsModifier() {
        List<Ast.Stmt> statements = parse("""
                \"\"\"A point.\"\"\"
                data Point(x, y) immutable
                """);
        Ast.DataDecl point = assertInstanceOf(Ast.DataDecl.class, statements.get(0));
        assertEquals("A point.", point.doc());
        assertTrue(point.immutable(), "the modifier must survive the docstring attachment");
    }

    @Test
    void aDocumentedClassKeepsItsMethods() {
        List<Ast.Stmt> statements = parse("""
                \"\"\"A circle.\"\"\"
                class Circle(r)
                  fun area()
                    return r
                  end
                end
                """);
        Ast.ClassDecl circle = assertInstanceOf(Ast.ClassDecl.class, statements.get(0));
        assertEquals("A circle.", circle.doc());
        assertEquals(1, circle.methods().size());
    }

    @Test
    void aDocumentedMethodKeepsItsDocNextToTheMethod() {
        List<Ast.Stmt> statements = parse(
                "\"\"\"A counter.\"\"\"\n"
                + "class Counter(value)\n"
                + "  \"\"\"Adds one.\n\n  @return the new value\n  \"\"\"\n"
                + "  fun increment()\n"
                + "    return value + 1\n"
                + "  end\n"
                + "  fun plain()\n"
                + "    return value\n"
                + "  end\n"
                + "end\n");
        Ast.ClassDecl counter = assertInstanceOf(Ast.ClassDecl.class, statements.get(0));
        assertEquals(2, counter.methods().size());
        Ast.MethodDecl increment = counter.methods().get(0);
        // The literal keeps the indentation it was written with; Docstring.parse trims it.
        assertTrue(increment.doc().startsWith("Adds one."), increment.doc());
        assertTrue(increment.doc().contains("@return the new value"), increment.doc());
        assertNull(counter.methods().get(1).doc());
        assertEquals("A counter.", counter.doc(), "the class keeps its own docstring");
    }

    @Test
    void aLiteralInAClassBodyThatPrecedesNoMethodIsStillAnError() {
        assertThrows(OqError.class,
                () -> parse("class K()\n  \"\"\"nothing to document\"\"\"\n  x = 1\nend\n"));
    }

    @Test
    void aDocumentedContractKeepsItsSignatures() {
        List<Ast.Stmt> statements = parse("""
                \"\"\"Has an area.\"\"\"
                contract Shape
                  fun area()
                end
                """);
        Ast.ContractDecl shape = assertInstanceOf(Ast.ContractDecl.class, statements.get(0));
        assertEquals("Has an area.", shape.doc());
        assertEquals(1, shape.methods().size());
    }

    @Test
    void anUndocumentedFunctionHasNoDoc() {
        List<Ast.Stmt> statements = parse("fun f()\n  return 1\nend\n");
        Ast.FuncDecl f = assertInstanceOf(Ast.FuncDecl.class, statements.get(0));
        assertNull(f.doc());
    }

    @Test
    void aMultiLineValueAssignedToAVariableIsStillAValue() {
        List<Ast.Stmt> statements = parse("s = \"\"\"\nhello\n\"\"\"\n");
        assertEquals(1, statements.size());
        assertInstanceOf(Ast.Assign.class, statements.get(0));
    }

    @Test
    void aMultiLineLiteralThatPrecedesNoDeclarationIsStillAnExpression() {
        // It was an evaluated-and-discarded expression before docstrings existed, and stays one.
        List<Ast.Stmt> statements = parse("print 1\n\"\"\"just a value\"\"\"\nprint 2\n");
        assertEquals(3, statements.size());
        assertInstanceOf(Ast.ExprStmt.class, statements.get(1));
    }

    @Test
    void aMultiLineLiteralBeforeAStatementInsideABodyIsNotADocstring() {
        List<Ast.Stmt> statements = parse("""
                fun f()
                  \"\"\"not a docstring\"\"\"
                  print 1
                end
                """);
        Ast.FuncDecl f = assertInstanceOf(Ast.FuncDecl.class, statements.get(0));
        assertNull(f.doc());
        assertEquals(2, f.body().size());
    }

    @Test
    void aNestedFunctionCanBeDocumented() {
        List<Ast.Stmt> outer = parse("""
                fun outer()
                  \"\"\"Adds one.\"\"\"
                  fun inner()
                    return 1
                  end
                  return inner
                end
                """);
        Ast.FuncDecl f = assertInstanceOf(Ast.FuncDecl.class, outer.get(0));
        Ast.FuncDecl inner = assertInstanceOf(Ast.FuncDecl.class, f.body().get(0));
        assertEquals("Adds one.", inner.doc());
    }

    @Test
    void aMultiLineValueStillEvaluatesWithItsIndentationRemoved() {
        // The lexer strips the common indent from a multi-line literal; documenting a
        // declaration must not change what the same literal is worth when it is a value.
        Ast.Program program = Parser.parse("t.oq", "s = \"\"\"\n    hi\n    \"\"\"\n");
        Ast.Assign decl = assertInstanceOf(Ast.Assign.class, program.statements().get(0));
        Ast.StringLit lit = assertInstanceOf(Ast.StringLit.class, decl.value());
        assertEquals(1, lit.parts().size());
        Ast.TextPart text = assertInstanceOf(Ast.TextPart.class, lit.parts().get(0));
        assertEquals("hi", text.text());
    }
}
