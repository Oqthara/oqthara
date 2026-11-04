package dev.oqthara.cli;

import dev.oqthara.core.Parser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatterTest {
    @Test
    void normalisesIndentation() {
        String input = "if true\n      print 1\n  else\n   print 2\nend\n";
        String expected = "if true\n  print 1\nelse\n  print 2\nend\n";
        assertEquals(expected, Formatter.format(input));
    }

    @Test
    void normalisesOperatorSpacing() {
        assertEquals("x = 1 + 2\n", Formatter.format("x=1+2\n"));
        assertEquals("print x > 1\n", Formatter.format("print x>1\n"));
        assertEquals("x = y * (a + b)\n", Formatter.format("x = y*(a + b)\n"));
    }

    @Test
    void normalisesCommaSpacing() {
        assertEquals("f(a, b)\n", Formatter.format("f(a,b)\n"));
        assertEquals("[1, 2, 3]\n", Formatter.format("[1,2,3]\n"));
        assertEquals("{\"a\": 1}\n", Formatter.format("{\"a\": 1}\n"));
    }

    @Test
    void doesNotTouchTextLiterals() {
        assertEquals("print \"a  b   c\"\n", Formatter.format("print \"a  b   c\"\n"));
        assertEquals("print \"x=1+2\"\n", Formatter.format("print \"x=1+2\"\n"));
    }

    @Test
    void dataDeclarationsDoNotIndent() {
        String input = "data A(x)\ndata B(y)\nprint 1\n";
        assertEquals(input, Formatter.format(input));
    }

    @Test
    void functionsIndentTheirBody() {
        String input = "fun f(a)\nreturn a\nend\n";
        assertEquals("fun f(a)\n  return a\nend\n", Formatter.format(input));
    }

    @Test
    void nestedBlocksIndent() {
        String input = "for i in 1 to 3\nif i > 1\nprint i\nend\nend\n";
        String expected = "for i in 1 to 3\n  if i > 1\n    print i\n  end\nend\n";
        assertEquals(expected, Formatter.format(input));
    }

    @Test
    void collapsesBlankLineRuns() {
        String input = "print 1\n\n\n\nprint 2\n";
        assertEquals("print 1\n\nprint 2\n", Formatter.format(input));
    }

    @Test
    void trailingNewlineIsAdded() {
        assertEquals("print 1\n", Formatter.format("print 1"));
    }

    @Test
    void isIdempotent() {
        String[] samples = {
                "x=1\nif x>0\nprint\"a\"\nend\n",
                "data P(x,y)\nfun f(a,b)\nreturn a+b\nend\n",
                "for i in 1 to 3\nprint i\nend\n",
        };
        for (String sample : samples) {
            String once = Formatter.format(sample);
            String twice = Formatter.format(once);
            assertEquals(once, twice, "formatter is not idempotent for:\n" + sample);
        }
    }

    @Test
    void outputAlwaysParses() {
        String[] samples = {
                "x=1\nif x>0\nprint\"a\"\nend\n",
                "data P(x,y)\nfun f(a,b)\nreturn a+b\nend\n",
                "try\nprint 1\ncatch e\nprint e.message\nend\n",
                "m={\"a\":1,\"b\":2}\nprint m[\"a\"]\n",
                "xs=[1,2,3]\nprint xs.map(x->x*2)\n",
        };
        for (String sample : samples) {
            Parser.parse("<fmt>", Formatter.format(sample));
        }
    }

    @Test
    void everyExampleFormatsIdempotentlyAndStillParses() throws Exception {
        Path examples = Path.of(System.getProperty("oq.root", ".")).resolve("examples");
        try (var stream = Files.list(examples)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".oq")).toList()) {
                String source = Files.readString(file);
                String once = Formatter.format(source);
                String twice = Formatter.format(once);
                assertEquals(once, twice, "not idempotent: " + file.getFileName());
                Parser.parse(file.getFileName().toString(), once);
            }
        }
    }

    @Test
    void keepsComments() {
        assertEquals("# top\nx = 1 # trailing\n", Formatter.format("# top\nx=1 # trailing\n"));
    }
}
