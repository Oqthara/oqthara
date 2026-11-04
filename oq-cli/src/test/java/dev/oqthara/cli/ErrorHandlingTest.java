package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorHandlingTest {
    private String ok(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    @Test
    void catchesRuntimeError() {
        assertEquals("caught\n", ok("""
                try
                  print [1][9]
                catch e
                  print "caught"
                end
                """));
    }

    @Test
    void handlerDoesNotRunWithoutError() {
        assertEquals("fine\n", ok("""
                try
                  print "fine"
                catch e
                  print "wrong"
                end
                """));
    }

    @Test
    void errorValueHasFields() {
        String out = ok("""
                try
                  error("boom")
                catch e
                  print e.message
                  print e.line > 0
                  print e.file
                end
                """);
        String[] lines = out.split("\n");
        assertEquals("boom", lines[0]);
        assertEquals("true", lines[1]);
    }

    @Test
    void nestedTryReraises() {
        assertEquals("inner\nouter\n", ok("""
                try
                  try
                    error("inner")
                  catch e
                    print e.message
                    error("outer")
                  end
                catch e2
                  print e2.message
                end
                """));
    }

    @Test
    void uncaughtErrorStillFails() {
        OqRunner.Result result = OqRunner.run("error(\"unhandled\")");
        assertEquals(1, result.code());
        assertTrue(result.stderr().contains("unhandled"));
    }

    @Test
    void tryInsideFunction() {
        assertEquals("10\ncaught\n", ok("""
                fun risky(x)
                  if x < 0
                    error("negative")
                  end
                  return x * 2
                end

                print risky(5)
                try
                  risky(-1)
                catch e
                  print "caught"
                end
                """));
    }

    @Test
    void tryInLoopContinues() {
        assertEquals("1\nskip\n3\n", ok("""
                for n in 1 to 3
                  try
                    if n == 2
                      error("two")
                    end
                    print n
                  catch e
                    print "skip"
                  end
                end
                """));
    }

    @Test
    void variablesOutsideTryRemain() {
        assertEquals("42\n", ok("""
                value = 42
                try
                  error("x")
                catch e
                end
                print value
                """));
    }
}
