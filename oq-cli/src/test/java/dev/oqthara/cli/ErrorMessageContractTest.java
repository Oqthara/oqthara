package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the error-message contract: a user error exits 1, never 70, and always names the
 * problem with a position or a suggestion.
 */
class ErrorMessageContractTest {
    private OqRunner.Result run(String source) {
        return OqRunner.run("<test>", source, java.util.List.of(), "", java.nio.file.Path.of("."));
    }

    private String ok(String source) {
        OqRunner.Result result = run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    private void goodError(String source, String expectedFragment) {
        OqRunner.Result result = run(source);
        assertEquals(1, result.code(), "expected a user error for: " + source
                + "\nstdout=" + result.stdout() + "\nstderr=" + result.stderr());
        assertTrue(result.stderr().contains(expectedFragment),
                "expected '" + expectedFragment + "' in:\n" + result.stderr());
        assertFalse(result.stderr().contains("internal error"), result.stderr());
    }

    @Test
    void runtimeErrorsAreNotInternal() {
        goodError("print 1 / 0", "division by zero");
        goodError("print nope", "'nope' is not defined");
        goodError("x = 5\nx()", "'x' is not a function");
        goodError("print [1][4]", "out of range");
        goodError("print \"a\" < 1", "cannot compare");
        goodError("print -\"a\"", "cannot negate");
        goodError("print 5 % 0", "division by zero");
        goodError("print 1 + true", "cannot add");
    }

    @Test
    void loopControlOutsideLoopIsAUserError() {
        goodError("break", "'break' outside a loop");
        goodError("continue", "'continue' outside a loop");
    }

    @Test
    void dataErrorsNameTheType() {
        goodError("data D(a)\nd = D(1)\nprint d.b", "type D has no field named 'b'");
        goodError("data D(a)\nD(1, 2)", "D()");
        goodError("data D(a)\nD()", "missing value for field 'a'");
    }

    @Test
    void collectionErrorsExplainTheBounds() {
        goodError("xs = [1]\nprint xs[5]", "out of range");
        goodError("xs = [1]\nxs[5] = 2", "out of range");
        goodError("m = {}\nprint m.foo()", "has no method 'foo'");
        goodError("print \"a\".nope()", "has no method 'nope'");
    }

    @Test
    void syntaxErrorsPointAtTheConstruct() {
        goodError("if true\n  print 1", "expected 'end'");
        goodError("x = @", "unexpected character");
        goodError("print \"abc", "unterminated text");
        goodError("f = (a, -> a", "expected");
    }

    @Test
    void standardLibraryErrorsAreActionable() {
        goodError("json_parse(\"nope\")", "invalid JSON");
        goodError("read(\"definitely-missing-file.txt\")", "cannot read file");
        goodError("await(1)", "needs a task");
    }

    @Test
    void iterationErrorsSuggestARange() {
        OqRunner.Result result = run("for x in 5\n  print x\nend");
        assertEquals(1, result.code());
        assertTrue(result.stderr().contains("cannot loop over number"), result.stderr());
        assertTrue(result.stderr().contains("1 to"), "should suggest a range: " + result.stderr());
    }

    @Test
    void anUnchangeableCollectionIsAUserErrorNotAnOqtharaBug() {
        // List.of(...) is a normal Java API a program can reach through `use`, and its mutators
        // throw UnsupportedOperationException with a null message. That used to escape the
        // interpreter's tuple-only check and surface as `internal error:
        // UnsupportedOperationException: null` with exit 70, telling the program it had found an
        // Oqthara defect when it had simply tried to change an unchangeable value.
        goodError("use java.util.List\nl = List.of(1, 2, 3)\nl.add(4)",
                "cannot change an unchangeable collection");
        goodError("use java.util.List\nl = List.of(1, 2, 3)\nprint l.length()\nl.remove(0)",
                "cannot change an unchangeable collection");
        // A tuple still keeps its own wording: it is a language value, not a JDK one.
        goodError("t = (1, 2, 3)\nt.add(4)", "a tuple is immutable");
    }

    @Test
    void everyErrorHasAPositionOrAnExplicitReason() {
        String[] programs = {
                "print nope", "print 1 / 0", "if true", "print \"abc", "data D(a)\nD()",
                "break", "continue", "json_parse(\"nope\")", "await(1)",
        };
        for (String source : programs) {
            OqRunner.Result result = run(source);
            assertEquals(1, result.code(), source);
            String stderr = result.stderr();
            assertFalse(stderr.isBlank(), "empty diagnostic for " + source);
            assertFalse(stderr.contains("internal error"), "internal error for " + source);
            boolean hasPosition = stderr.contains("-->") || stderr.contains("line");
            assertTrue(hasPosition, "no position or line for " + source + ":\n" + stderr);
        }
    }

    /**
     * A second `const x` in one scope used to replace the first binding with a fresh one, so the
     * value changed while assignments to the name were still refused: the constant looked
     * enforced and was not. Both backends now refuse the redeclaration.
     */
    @Test
    void redeclaringAConstantIsRefusedNotSilentlyOverwritten() {
        goodError("const x = 1\nconst x = 2\nprint x", "already declared in this scope");
        goodError("x = 1\nconst x = 2\nprint x", "already declared in this scope");
        goodError("fun f()\n  return 1\nend\nconst f = 2", "already declared in this scope");
        goodError("const g = 1\nfun g()\n  return 2\nend", "already declared in this scope");
        goodError("fun h(a)\n  const a = 2\n  return a\nend\nprint(h(1))",
                "already declared in this scope");
        // A `const` destructuring declares names too, so it obeys the same rule.
        goodError("const a = 1\nconst a, b = (2, 3)", "already declared in this scope");
        goodError("a = 1\nconst a, b = (2, 3)", "already declared in this scope");
        assertEquals("1\n", ok("const x = 1\nprint x"));
        assertEquals("1 2\n", ok("const a, b = (1, 2)\nprint(a, b)"));
        // A refused destructuring writes nothing, so no target is left half-updated.
        goodError("b = 9\nconst a, b = (1, 2)", "already declared in this scope");
    }

    /**
     * Changing a collection while a loop walks it used to reach {@code java.util}'s own
     * fail-fast check, which threw {@code ConcurrentModificationException} from a place with
     * no Oqthara diagnostic available. The program then died on exit code 70 with
     * "internal error ... This is a bug in Oqthara". Iteration now walks a snapshot, so both
     * backends visit exactly the items that were present when the loop started.
     */
    @Test
    void changingACollectionDuringIterationIsWellDefined() {
        assertEquals("[1, 2, 3, 10, 20, 30]\n", ok("""
                xs = [1, 2, 3]
                for x in xs
                  xs.add(x * 10)
                end
                print xs
                """));
        assertEquals("{1, 2, 3, 11, 12, 13}\n", ok("""
                s = set([1, 2, 3])
                for v in s
                  s.add(v + 10)
                end
                print s
                """));
        // Removing the key being visited must not disturb the walk, which is over the keys
        // that existed at the start.
        assertEquals("{}\n", ok("""
                m = {"a": 1, "b": 2}
                for k in m
                  m.remove(k)
                end
                print m
                """));
    }

    /**
     * A live iterator, unlike {@code for}, reads the collection it came from. Changing that
     * collection mid-walk is genuinely invalid, and {@code java.util}'s fail-fast check used
     * to escape as {@code internal error: ... This is a bug in Oqthara} — which is wrong, and
     * leaves the user with nothing to act on. It is now an ordinary Oqthara error.
     */
    @Test
    void changingACollectionWhileAnIteratorWalksItIsAUserError() {
        goodError("""
                xs = [1, 2, 3]
                it = xs.iter()
                print it.next()
                xs.add(4)
                print it.next()
                """, "the collection changed while this iterator was walking it");
        // The hint has to name the way out, because the walk cannot be resumed.
        goodError("""
                xs = [1, 2, 3]
                it = xs.iter()
                it.next()
                xs.remove(0)
                it.next()
                """, "Use `for`, which walks a snapshot");
    }

    @Test
    void anUnhashableKeyIsReportedAtTheExpressionThatWroteIt() {
        // The refusal is raised inside the collection, which has no source position of its own.
        // Before the span was threaded through, both backends answered `<unknown>:1:1` and
        // blamed whatever the program's first line was, which sends the reader to the wrong
        // line. Each of these puts the offending literal on line 3.
        for (String stmt : new String[] {"m = {}\nm[[9]] = 1", "s = {[9]}", "m = {[9]: 1}"}) {
            String source = "a = 1\nb = 2\n" + stmt + "\n";
            int offending = 1;
            String[] lines = source.split("\n");
            for (int i = 0; i < lines.length; i++) if (lines[i].contains("[9]")) offending = i + 1;
            OqRunner.Result result = run(source);
            assertEquals(1, result.code(), stmt + "\n" + result.stderr());
            assertTrue(result.stderr().contains("cannot be used as a"),
                    stmt + "\n" + result.stderr());
            assertTrue(result.stderr().contains(":" + offending + ":"),
                    "expected the position on line " + offending + ", got:\n" + result.stderr());
            assertFalse(result.stderr().contains("<unknown>"),
                    "expected a real file position, got:\n" + result.stderr());
        }
    }

    @Test
    void anUnhashableMemberIsReportedAtTheCallThatBuiltIt() {
        // ADR-0025. The literal forms are covered above; these reach the same refusal through
        // the constructors and the set algebra, which the literals' repair did not cover. Each
        // program puts the offending call on line 3, so a synthetic `<unknown>:1:1` is visibly
        // the wrong line rather than a lucky match.
        for (String stmt : new String[] {
                "print(set([[1]]))",
                "print(frozenset([[1]]))",
                "print(unique([[1]]))",
                "print([[1]].to_set())",
                "print(set([1]).union([[1]]))",
                "print(set([1]).add([[1]]))",
                "print(frozenset([1]).union([[1]]))",
                "print(frozenset([1]).difference([[1]]))",
                "print([[1]].unique())",
                "print(par_map([[1]], x -> set([x])))"}) {
            String source = "a = 1\nb = 2\n" + stmt + "\n";
            OqRunner.Result result = run(source);
            assertEquals(1, result.code(), stmt + "\n" + result.stderr());
            assertTrue(result.stderr().contains("cannot be used as a"),
                    stmt + "\n" + result.stderr());
            assertTrue(result.stderr().contains(":3:"),
                    "expected the position on line 3, got:\n" + result.stderr());
            assertFalse(result.stderr().contains("<unknown>"),
                    "expected a real file position, got:\n" + result.stderr());
            // A callback runs on another thread, so this also proves the marked position is
            // per thread rather than one slot shared by concurrent callbacks.
            if (stmt.contains("par_map")) {
                assertTrue(result.stderr().contains(":3:30"),
                        "expected the inner call, got:\n" + result.stderr());
            }
        }
    }

    @Test
    void anImmutableCollectionRefusalNamesTheCallThatTriedToChangeIt() {
        // OqFrozenSet and the OqRange constructor raise from inside the runtime, where the
        // only available position is `Errors.current()`. Passing a null span there reported
        // `<unknown>`, and later reported whichever call in the receiver or argument list ran
        // last, because the interpreter did not reset the mark before the JavaBridge fallback.
        String[] stmts = {
            "fs = frozenset([1])\nprint fs.add(2)",
            "fs = frozenset([1])\nprint fs.remove(1)",
            "fs = frozenset([1])\nprint fs.clear()",
        };
        for (String stmt : stmts) {
            OqRunner.Result result = run(stmt);
            assertEquals(1, result.code(), stmt + "\n" + result.stderr());
            assertTrue(result.stderr().contains("immutable"), stmt + "\n" + result.stderr());
            assertFalse(result.stderr().contains("<unknown>"),
                    "expected a real file position, got:\n" + result.stderr());
            assertTrue(result.stderr().contains(":2:9"),
                    "expected the dot of the method call, got:\n" + result.stderr());
        }
        // An argument that is itself a call must not leave its own position behind.
        OqRunner.Result nested = run("fs = frozenset([1])\nprint fs.add(frozenset([2]).length())");
        assertTrue(nested.stderr().contains(":2:9"),
                "an inner call in the arguments shifted the position:\n" + nested.stderr());
        OqRunner.Result range = run("print range(0, 5, 0)");
        assertTrue(range.stderr().contains(":1:12"),
                "expected the range call's paren, got:\n" + range.stderr());
        assertFalse(range.stderr().contains("<unknown>"), range.stderr());
    }

    @Test
    void changingACollectionInsideACallbackIsWellDefined() {        assertEquals("[nothing, nothing, nothing]\n", ok("""
                xs = [1, 2, 3]
                print xs.map((x) -> xs.add(9))
                """));
        assertEquals("[1, 2, 3]\n", ok("""
                xs = [1, 2, 3]
                print xs.filter((x) -> xs.remove_value(x))
                """));
        assertEquals("{}\n", ok("""
                m = {"a": 1}
                m.each((k, v) -> m.remove(k))
                print m
                """));
    }

    /**
     * The four builtins that accepted any collection used to cast it to {@code List}, so a set
     * crashed with {@code ClassCastException}. They now take what iteration gives them.
     */
    @Test
    void collectionBuiltinsAcceptSets() {
        assertEquals("[[1, 3], [2, 4]]\n", ok("print zip({1, 2}, {3, 4})"));
        assertEquals("7\n", ok("print random_item({7})"));
        assertEquals("[2, 4]\n", ok("print par_map({1, 2}, (x) -> x * 2)"));
    }

    /**
     * A callback argument of the wrong type is the program's mistake, so it has to read as one.
     * It used to be an unchecked cast, which surfaced as {@code ClassCastException} on exit 70.
     */
    @Test
    void aCallbackArgumentOfTheWrongTypeNamesTheArgument() {
        goodError("[1, 2].reduce(0, (a, x) -> a + x)", "needs a function as argument 1");
        goodError("[1, 2].sort(0)", "needs a function as argument 1");
    }
}
