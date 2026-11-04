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
}
