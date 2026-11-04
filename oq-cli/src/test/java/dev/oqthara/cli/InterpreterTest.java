package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterTest {
    private String run(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    @Test
    void printsHello() {
        assertEquals("Hello, World!\n", run("print \"Hello, World!\""));
    }

    @Test
    void interpolatesText() {
        assertEquals("Hi Ada\n", run("name = \"Ada\"\nprint \"Hi {name}\""));
    }

    @Test
    void arithmetic() {
        assertEquals("7\n", run("print 3 + 4"));
        assertEquals("12\n", run("print 3 * 4"));
        assertEquals("2\n", run("print 5 - 3"));
    }

    @Test
    void stringRepeat() {
        assertEquals("ababab\n", run("print \"ab\" * 3"));
    }

    @Test
    void loops() {
        assertEquals("123\n", run("""
                out = ""
                for i in 1 to 3
                  out = out + text(i)
                end
                print out
                """));
    }

    @Test
    void functionsAndRecursion() {
        assertEquals("120\n", run("""
                fun fact(n)
                  if n <= 1
                    return 1
                  end
                  return n * fact(n - 1)
                end
                print fact(5)
                """));
    }

    @Test
    void closuresCapture() {
        assertEquals("1\n2\n", run("""
                fun counter()
                  n = 0
                  return fun()
                    n = n + 1
                    return n
                  end
                end
                c = counter()
                print c()
                print c()
                """));
    }

    @Test
    void listMethods() {
        assertEquals("[2, 4, 6]\n", run("print [1, 2, 3].map(x -> x * 2)"));
    }

    @Test
    void mapAccess() {
        assertEquals("2\n", run("m = {\"a\": 1, \"b\": 2}\nprint m[\"b\"]"));
    }

    @Test
    void dataTypes() {
        assertEquals("3\n4\n", run("""
                data Point(x, y)
                p = Point(3, 4)
                print p.x
                print p.y
                """));
    }

    @Test
    void comparisonOperators() {
        assertEquals("true\nfalse\n", run("print 1 < 2\nprint 2 < 1"));
    }

    @Test
    void errorHasPosition() {
        OqRunner.Result result = OqRunner.run("x = 1\ny = missing\n");
        assertEquals(1, result.code());
        assertTrue(result.stderr().contains("missing"));
    }

    @Test
    void exitCode() {
        OqRunner.Result result = OqRunner.run("exit(3)");
        assertEquals(3, result.code());
    }

    @Test
    void readsStdin() {
        OqRunner.Result result = OqRunner.run("<test>", "name = input()\nprint \"hi {name}\"",
                java.util.List.of(), "Ada\n", java.nio.file.Path.of("."));
        assertEquals("hi Ada\n", result.stdout());
    }

    @Test
    void javaInterop() {
        assertEquals("5\n", run("""
                use java.lang.Math
                print Math.max(3, 5)
                """));
    }

    @Test
    void jsonRoundTrip() {
        assertEquals("Ada\n", run("""
                parsed = json_parse("{\\"name\\": \\"Ada\\"}")
                print parsed["name"]
                """));
    }

    @Test
    void printSeparatesWithSpace() {
        assertEquals("a 1 true\n", run("print \"a\", 1, true"));
    }
}
