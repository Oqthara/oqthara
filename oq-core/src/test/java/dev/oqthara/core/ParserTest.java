package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void commandFormIsLimitedToTheThreeReservedNames() {
        for (String name : List.of("print", "write", "assert")) {
            Ast.Program program = Parser.parse("test", name + " 1");
            assertInstanceOf(Ast.Command.class, program.statements().get(0), name);
        }
    }

    @Test
    void finallyIsRejectedWhereItWouldHaveBeenAbsorbed() {
        // The try succeeds, so the catch body never runs. Before ADR-0061 the statement list
        // absorbed `finally` as a variable read and the program ran to completion, silently
        // skipping the cleanup the author asked for.
        OqError error = assertThrows(OqError.class, () -> Parser.parse("t", """
                try
                    print "a"
                catch e
                    print "b"
                finally
                    print "cleanup"
                end
                """));
        assertEquals("finally does not exist", error.getMessage());
        assertTrue(error.hint().contains("no 'finally' clause"), error.hint());

        // The same word on the path where an error is actually raised. The diagnostic must
        // not depend on whether the handler ran.
        OqError afterError = assertThrows(OqError.class, () -> Parser.parse("t", """
                try
                    1/0
                catch e
                    print "caught"
                finally
                    print "cleanup"
                end
                """));
        assertEquals("finally does not exist", afterError.getMessage());
    }

    @Test
    void anUnimplementedConstructIsNamedInsteadOfItsOperand() {
        // `match x` parsed as the bare name `match` followed by a stray `x`, so the complaint
        // was about the token after a line that looked correct. Each construct must now be
        // named. ADR-0062.
        List<String> sources = List.of(
                "match x",
                "case 1:",
                "switch x",
                "yield 1",
                "def f(x)",
                "async fun g()\nend",
                "await f()",
                "from x import y",
                "import foo",
                "throw \"boom\"");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            assertEquals(leadingWord(source) + " is not a construct in Oqthara",
                    error.getMessage(), source);
            // The statement-terminator rule already emits a generic hint here, so "a hint
            // exists" passes before the change and proves nothing. Require the sentence that
            // names the replacement construct.
            assertFalse(error.hint().contains("Statements end at the end of the line"),
                    "generic hint left in place for: " + source);
            assertTrue(error.hint().length() > 20,
                    "expected a construct-specific hint for: " + source);
        }
    }

    @Test
    void anUnimplementedConstructIsNamedInAnAssignmentValue() {
        // `x = yield 1` reached the statement terminator before the value was examined and
        // still blamed the `1`, which is why the guard runs where the name is created rather
        // than on one statement path.
        List<String> sources = List.of(
                "x = yield 1",
                "x = match y",
                "x = await f()",
                "x += yield 1");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            String message = error.getMessage();
            assertTrue(message.endsWith(" is not a construct in Oqthara"),
                    source + " reported '" + message + "' instead of naming the construct");
        }
    }

    @Test
    void anUnimplementedConstructIsNamedWhereverAnExpressionMayAppear() {
        // The guard used to run on the bare-expression and assignment paths only, so every
        // nested position still blamed the operand. Running it where the name is created
        // covers these without a call site per statement form.
        List<String> sources = List.of(
                "return yield 1",
                "print yield 1",
                "if yield 1\nend",
                "while yield 1\nend",
                "f(yield 1)",
                "[yield 1]",
                "assert yield 1",
                "x[i] = yield 1",
                "for x in yield 1\nend",
                "x = [yield 1]");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            assertEquals("yield is not a construct in Oqthara", error.getMessage(), source);
        }
    }

    @Test
    void anOperatorAfterALeaderNameIsStillAnExpression() {
        // The adjacency rule must not treat an operator keyword or a symbolic operator as the
        // operand the missing construct would take. `match and 1` and `match - 3` are ordinary
        // expressions over a variable called `match`, and a false positive here would break
        // programs that use these words as names.
        List<String> sources = List.of(
                "match and 1",
                "match or 1",
                "print not match",
                "match - 3",
                "match * 2",
                "match in [1]",
                "from - 1",
                "match == 1",
                "match < 2",
                "await + 1");
        for (String source : sources) {
            assertDoesNotThrow(() -> Parser.parse("t", source),
                    "rejected an ordinary expression: " + source);
        }
    }

    @Test
    void aContextualRangeOperatorAfterALeaderNameIsStillARange() {
        // `to` and `until` are range operators that also lex as identifiers, so an identifier
        // in the next position does not always mean the missing construct. `from until until`
        // is the range from the variable `from` to the variable `until`, and refusing it broke
        // conformance 204.
        List<String> sources = List.of(
                "for n in from until until\nend",
                "for n in await to match\nend",
                "x = from until 3");
        for (String source : sources) {
            assertDoesNotThrow(() -> Parser.parse("t", source),
                    "rejected a range over variables: " + source);
        }
    }

    @Test
    void everyConstructLeaderRemainsUsableAsAName() {
        // These words are identifiers in shipped examples, so the refusal must depend on the
        // surrounding syntax and never on the word alone. If this test fails, the diagnostic
        // has become hard reservation and `examples/grep.oq` no longer parses.
        for (String leader : List.of("match", "case", "switch", "yield", "def", "async",
                "await", "from", "import", "throw")) {
            Parser.parse("t", leader + " = 1");
            Parser.parse("t", "print " + leader);
            Parser.parse("t", "for " + leader + " in [1]\nend");
            Parser.parse("t", "fun f(" + leader + ")\nend");
            Parser.parse("t", "fun " + leader + "()\nend");
            Parser.parse("t", leader);
        }
    }

    @Test
    void aComprehensionNamesTheConstructInsteadOfTheBracket() {
        // The missing-bracket diagnosis sent the writer to fix a bracket they had not
        // forgotten. A `for` inside the literal is unambiguously the construct.
        List<String> sources = List.of(
                "x = [n * 2 for n in 1 to 3]",
                "x = {n: n for n in 1 to 3}",
                "x = {n for n in 1 to 3}",
                "x = (n for n in 1 to 3)",
                "x = (1, 2, n for n in 1 to 3)");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            assertTrue(error.getMessage().endsWith("comprehensions are not implemented"),
                    source + " produced: " + error.getMessage());
        }
    }

    @Test
    void anOrdinaryCollectionLiteralIsStillAccepted() {
        // The comprehension check must not fire on a collection that merely contains the
        // identifier `for`-adjacent syntax. These are the shapes real programs use.
        Parser.parse("t", "a = [1, 2, 3]");
        Parser.parse("t", "b = {1, 2}");
        Parser.parse("t", "c = {\"x\": 1}");
        Parser.parse("t", "d = (1 + 2)");
        Parser.parse("t", "e = [[1], [2]]");
    }

    @Test
    void aBlockTerminatorAfterALeaderIsNotBlamedAsAConstruct() {
        // `match end` is invalid for the same reason `x end` is: the terminator does not belong
        // on the line. Saying the word is a construct would assert something false.
        List<String> sources = List.of(
                "if true\n  match end\nend",
                "if true\n  match else\nend",
                "x = match end",
                "fun f()\n  yield end\nend");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            assertFalse(error.getMessage().contains("is not a construct in Oqthara"),
                    source + " incorrectly blamed the leader: " + error.getMessage());
        }
    }

    private static String leadingWord(String source) {
        int end = 0;
        while (end < source.length() && Character.isLetter(source.charAt(end))) end++;
        return source.substring(0, end);
    }

    @Test
    void finallyIsNotANameInAnyPosition() {
        // Every slot that only an ordinary name may fill must refuse the reserved word, so a
        // construct that looks supported never becomes a variable by accident.
        List<String> sources = List.of(
                "finally = 3",
                "finally += 1",
                "const finally = 1",
                "fun finally()\nend",
                "fun f(finally)\nend",
                "data D(finally)\nend",
                "for finally in [1]\nend",
                "print finally",
                "x = finally",
                "[finally]",
                "use finally",
                "try\n  1/0\ncatch finally\nend");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            assertEquals("finally does not exist", error.getMessage(), source);
        }
    }

    @Test
    void finallyDiagnosticPointsAtTheWord() {
        OqError error = assertThrows(OqError.class, () -> Parser.parse("t", "x = 1\nfinally = 2"));
        assertEquals(2, error.span().line());
        assertEquals(1, error.span().column());
        assertEquals(7, error.span().width(), "the caret must cover the whole word");
    }

    @Test
    void aTypedFieldNamesTheDecisionInsteadOfABracket() {
        // Typed fields are decided against, not unimplemented (type-system-decision-brief.md,
        // 2026-11-13), so `data P(x: Int)` must not report an unclosed bracket: nothing is
        // unclosed. Every position in the list has to be covered, because the colon lands after
        // the first field in one case and a later one in another.
        List<String> sources = List.of(
                "data P(x: Int)",
                "data P(x: Int, y)",
                "data P(x, y: Int)",
                "data P(x: Int = 1)",
                "data P(x, y: Int = 2)",
                "class C(v: Int)\nend",
                "class C(v, w: Int)\nend");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            assertEquals("typed fields are not supported", error.getMessage(), source);
            assertTrue(error.hint().contains("dynamically typed"),
                    "expected the decision in the hint for: " + source);
        }
    }

    @Test
    void aParameterNamesTheDecisionInsteadOfABracket() {
        // The same decided question, one construct to the left. A colon cannot start a
        // parameter default, so there is no spelling that makes this guard ambiguous.
        List<String> sources = List.of(
                "fun f(a: Point)\n  return a\nend",
                "fun f(a, b: Point)\n  return a\nend",
                "fun f()\n  g = fun(x: Int) x\n  return g\nend",
                "class C(v)\n  fun m(a: Int)\n    return a\n  end\nend",
                "contract K\n  fun m(a: Int)\nend");
        for (String source : sources) {
            OqError error = assertThrows(OqError.class, () -> Parser.parse("t", source),
                    "expected a refusal for: " + source);
            assertEquals("typed parameters are not supported", error.getMessage(), source);
            assertTrue(error.hint().contains("dynamically typed"),
                    "expected the decision in the hint for: " + source);
        }
    }

    @Test
    void aFieldDefaultSurvivesAsADefault() {
        // Placing the colon check ahead of the default expression is only safe if a default
        // never begins with a colon. Pin the AST, not just the absence of a throw: a guard that
        // swallowed the default would parse and silently lose the value.
        Ast.DataDecl data = (Ast.DataDecl) Parser.parse("t", "data P(x = 1, y = 2)").statements().get(0);
        assertEquals(2, data.fields().size());
        assertInstanceOf(Ast.NumberLit.class, data.fields().get(0).defaultValue());
        assertInstanceOf(Ast.NumberLit.class, data.fields().get(1).defaultValue());

        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("t", "class C(v = 5)\nend").statements().get(0);
        assertEquals(1, klass.fields().size());
        assertInstanceOf(Ast.NumberLit.class, klass.fields().get(0).defaultValue());

        Ast.DataDecl bare = (Ast.DataDecl) Parser.parse("t", "data Q(x, y)").statements().get(0);
        assertNull(bare.fields().get(0).defaultValue());

        // A default whose expression contains a colon must not be mistaken for a type.
        assertDoesNotThrow(() -> Parser.parse("t", "data P(m = {\"a\": 1})"));
    }

    @Test
    void everyOtherBuiltinRequiresParentheses() {
        // `write_text` is an alias of `write` reachable only in function form. The spec once
        // showed `write_text "x"`; this pins the boundary the spec now states.
        OqError error = assertThrows(OqError.class, () -> Parser.parse("test", "write_text \"x\""));
        assertTrue(error.getMessage().contains("unexpected text after a statement"),
                error.getMessage());

        Ast.Program program = Parser.parse("test", "write_text(\"x\")");
        assertInstanceOf(Ast.ExprStmt.class, program.statements().get(0));
    }
}
