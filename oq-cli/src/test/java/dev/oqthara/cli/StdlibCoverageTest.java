package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdlibCoverageTest {
    private String ok(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    @Test
    void textFunctions() {
        assertEquals("HELLO\nhello\nhi\n", ok("""
                print upper("hello")
                print lower("HELLO")
                print trim("  hi  ")
                """));
        assertEquals("a-b-c\n", ok("print join([\"a\", \"b\", \"c\"], \"-\")"));
        assertEquals("x.y\n", ok("print replace(\"x,y\", \",\", \".\")"));
        assertEquals("true\nfalse\n", ok("""
                print contains("hello", "ell")
                print contains("hello", "zzz")
                """));
        assertEquals("[\"a\", \"b\"]\n", ok("print split(\"a,b\", \",\")"));
        assertEquals("[[\"a1\", \"a\", \"1\"], [\"b2\", \"b\", \"2\"]]\n",
                ok("print regex(\"([a-z])([0-9])\", \"a1 b2\")"));
        assertEquals("true\n", ok("print matches(\"\\\\d+\", \"abc123\")"));
        assertEquals("hi\n", ok("print format(\"hi\")"));
    }

    @Test
    void collectionFunctions() {
        assertEquals("1\n2\n", ok("""
                print keys({"a": 1}).length()
                print keys({"a": 1, "b": 2}).length()
                """));
        assertEquals("[1, 2]\n", ok("print values({\"a\": 1, \"b\": 2}).sort()"));
        assertEquals("[1, 2, 3]\n", ok("print push([1, 2], 3)"));
        assertEquals("3\n", ok("print pop([1, 2, 3])"));
        assertEquals("6\n", ok("print sum([1, 2, 3])"));
        assertEquals("[1, 2, 3]\n", ok("print sort([3, 1, 2])"));
        assertEquals("[3, 2, 1]\n", ok("print reverse([1, 2, 3])"));
        assertEquals("[1, 2]\n", ok("print unique([1, 1, 2])"));
        assertEquals("2\n", ok("print set([1, 2, 2]).size()"));
        assertEquals("[1, 2, 3]\n", ok("print list(1 to 3)"));
        assertEquals("[[1, \"a\"], [2, \"b\"]]\n", ok("print zip([1, 2], [\"a\", \"b\"])"));
        assertEquals("[2, 3]\n", ok("print range_list(2, 4)"));
    }

    @Test
    void mathFunctions() {
        assertEquals("5\n2.5\n-3\n", ok("""
                print abs(-5)
                print abs(-2.5)
                print -abs(3)
                """));
        assertEquals("3\n4\n4\n2.0\n", ok("""
                print floor(3.9)
                print ceil(3.1)
                print round(3.5)
                print sqrt(4)
                """));
        assertEquals("8\n1\n3\n", ok("""
                print pow(2, 3)
                print min(1, 3)
                print max(1, 3)
                """));
        assertEquals("1\n5\n10\n", ok("""
                print clamp(0, 1, 10)
                print clamp(5, 1, 10)
                print clamp(50, 1, 10)
                """));
        assertEquals("true\n", ok("print random() >= 0"));
        assertEquals("true\n", ok("print random(1, 2) >= 1"));
        assertEquals("true\n", ok("print random_item([1]) == 1"));
    }

    @Test
    void fileFunctions(@TempDir Path dir) {
        Path file = dir.resolve("data.txt");
        OqRunner.Result result = OqRunner.run("<t>", """
                save("%s", "line1\\nline2\\n")
                print read("%s").strip()
                print read_lines("%s").length()
                append("%s", "line3\\n")
                print read_lines("%s").length()
                print exists("%s")
                print file_size("%s") > 0
                print list_files("%s").contains("data.txt")
                print path_name("%s")
                print path_parent("%s").length() > 0
                print path_join("a", "b")
                delete("%s")
                print exists("%s")
                """.formatted(file, file, file, file, file, file, file, dir, file, file, file, file),
                java.util.List.of(), "", dir);
        assertEquals(0, result.code(), result.stderr());
        String[] lines = result.stdout().split("\n");
        assertEquals("line1", lines[0]);
        assertEquals("line2", lines[1]);
        assertEquals("2", lines[2]);
        assertEquals("3", lines[3]);
        assertEquals("true", lines[4]);
        assertEquals("true", lines[5]);
        assertEquals("true", lines[6]);
        assertEquals("data.txt", lines[7]);
        assertEquals("true", lines[8]);
        assertEquals("a/b", lines[9].replace('\\', '/'));
        assertEquals("false", lines[10]);
    }

    @Test
    void makeDirectoryAndCwd(@TempDir Path dir) {
        OqRunner.Result result = OqRunner.run("<t>", """
                make_dir("%s/sub")
                print exists("%s/sub")
                print cwd().length() > 0
                """.formatted(dir, dir), java.util.List.of(), "", dir);
        assertEquals(0, result.code(), result.stderr());
        assertEquals("true\ntrue\n", result.stdout());
        assertTrue(Files.isDirectory(dir.resolve("sub")));
    }

    @Test
    void missingFileProducesHelpfulError(@TempDir Path dir) {
        OqRunner.Result result = OqRunner.run("<t>", "read(\"nope.txt\")", java.util.List.of(), "", dir);
        assertEquals(1, result.code());
        assertTrue(result.stderr().contains("cannot read file"), result.stderr());
    }

    @Test
    void jsonVariants() {
        assertEquals("[1, 2]\n{\"a\":1}\n", ok("""
                print json_parse("[1, 2]")
                print json({"a": 1})
                """));
        assertEquals("nothing\n", ok("print json_parse(\"null\")"));
        assertEquals("true\n", ok("print json_parse(\"true\")"));
        assertEquals("1.5\n", ok("print json_parse(\"1.5\")"));
        assertEquals("\"a\\nb\"\n", ok("print json(\"a\\nb\")"));
        assertEquals("[1,2]\n", ok("print json(set([1, 2]))"));
        assertEquals("{\"a\":1}\n", ok("data D(a)\nprint json(D(1))"));
    }

    @Test
    void invalidJsonFails() {
        OqRunner.Result result = OqRunner.run("json_parse(\"{\")");
        assertEquals(1, result.code());
        assertTrue(result.stderr().contains("invalid JSON"), result.stderr());
    }

    @Test
    void timeFunctions() {
        assertEquals("true\ntrue\ntrue\ntrue\n", ok("""
                print now().length() > 0
                print today().length() > 0
                print datetime().length() > 0
                print timestamp() > 0
                """));
        assertEquals("0\n", ok("sleep(0)\nprint 0"));
    }

    @Test
    void processFunctions() {
        assertEquals("true\n", ok("print cpu_count() > 0"));
        assertEquals("true\n", ok("print memory_mb() > 0"));
        assertEquals("true\n", ok("print env(\"PATH\") != nothing"));
    }

    @Test
    void numberAndTextConversions() {
        assertEquals("42\n3.5\n7\n", ok("""
                print number("42")
                print text(3.5)
                print number(" 7 ")
                """));
        assertEquals("1\n0\n", ok("""
                print number(true)
                print number(false)
                """));
    }

    @Test
    void reprAndTypeFunctions() {
        assertEquals("\"x\"\n[\"x\"]\n", ok("""
                print repr("x")
                print repr(["x"])
                """));
        assertEquals("true\ntrue\ntrue\nfalse\n", ok("""
                print is_number(1)
                print is_text("a")
                print is_nothing(nothing)
                print is_nothing(1)
                """));
    }

    @Test
    void errorFunction() {
        OqRunner.Result result = OqRunner.run("error(\"boom\")");
        assertEquals(1, result.code());
        assertTrue(result.stderr().contains("boom"), result.stderr());
    }

    @Test
    void globalFunctionAliases() {
        assertEquals("[2, 4]\n[2]\n[3, 1]\n", ok("""
                print map([1, 2], x -> x * 2)
                print filter([1, 2, 3], x -> x % 2 == 0)
                print sort_by([1, 3], x -> -x)
                """));
        assertEquals("[1, 2]\n", ok("print clone([1, 2])"));
        assertEquals("[1, 2]\n", ok("xs = [1, 2]\nprint xs.clone()"));
    }

    @Test
    void numberMethods() {
        assertEquals("5\n3\n-3\n2.0\n", ok("""
                print (-5).abs()
                print (3.7).floor()
                print (-3.7).ceil()
                print (4).sqrt()
                """));
        assertEquals("true\ntrue\n3\n", ok("""
                print 4.is_even()
                print 3.is_odd()
                print 3.round()
                """));
    }

    @Test
    void textMethods() {
        assertEquals("true\ntrue\n[\"a\", \"b\"]\n", ok("""
                print "hello".starts_with("he")
                print "hello".ends_with("lo")
                print "ab".chars()
                """));
        assertEquals("0\n-1\n", ok("""
                print "abc".find("a")
                print "abc".find("z")
                """));
        assertEquals("ab\n", ok("print \"ab\".repeat(1)"));
        assertEquals("007\n", ok("print \"7\".pad_start(3, \"0\")"));
        assertEquals("7..\n", ok("print \"7\".pad_end(3, \".\")"));
        assertEquals("Hello\n", ok("print \"hello\".capitalize()"));
        assertEquals("3\n", ok("print \"a b  c\".words().length()"));
        assertEquals("ab\n", ok("print \"ab\".text()"));
        assertEquals("ab\n", ok("print \"  ab\".trim_start()"));
        assertEquals("ab\n", ok("print \"ab   \".trim_end()"));
    }

    @Test
    void listMethodsExtensive() {
        assertEquals("1\n5\n", ok("""
                print [1, 2].first()
                print [1, 5].last()
                """));
        assertEquals("2\n", ok("print [1, 2, 3].get(1)"));
        assertEquals("[1, 9, 2]\n", ok("""
                xs = [1, 2]
                xs.insert(1, 9)
                print xs
                """));
        assertEquals("2\n[1, 3]\n", ok("""
                xs = [1, 2, 3]
                print xs.remove(1)
                print xs
                """));
        assertEquals("2\n[1, 3]\n", ok("""
                xs = [1, 2, 3]
                print xs.remove_value(2)
                print xs
                """));
        assertEquals("true\nfalse\n", ok("""
                print [1].any(x -> x == 1)
                print [1].all(x -> x == 2)
                """));
        assertEquals("2\n1\n", ok("""
                print [1, 2].find(x -> x > 1)
                print [1, 2].find_index(x -> x > 1)
                """));
        assertEquals("6\n", ok("print [1, 2, 3].reduce((a, b) -> a + b)"));
        assertEquals("[3, 2, 1]\n", ok("print [1, 2, 3].sorted_desc()"));
        assertEquals("1\n3\n", ok("""
                print [1, 2, 3].min()
                print [1, 2, 3].max()
                """));
        assertEquals("a,b\n", ok("print [\"a\", \"b\"].join(\",\")"));
        assertEquals("[1, 2]\n", ok("print [1, 2].clone()"));
        assertEquals("[1, 2]\n", ok("print [1, 2].text()"));
    }

    @Test
    void mapMethodsExtensive() {
        assertEquals("1\n", ok("print {\"a\": 1}.get(\"a\")"));
        assertEquals("9\n", ok("""
                m = {}
                m.set("x", 9)
                print m.get("x")
                """));
        assertEquals("true\ntrue\n", ok("""
                m = {"a": 1}
                print m.has("a")
                print m.has_value(1)
                """));
        assertEquals("1\n0\n", ok("""
                m = {"a": 1}
                print m.remove("a")
                print m.size()
                """));
        assertEquals("0\n", ok("""
                m = {"a": 1}
                m.clear()
                print m.size()
                """));
        assertEquals("a1\n", ok("""
                m = {"a": 1}
                m.each((k, v) -> write("{k}{v}\\n"))
                """));
        assertEquals("[2]\n", ok("""
                print {"a": 1, "b": 2}.map((k, v) -> v).skip(1)
                """));
        assertEquals("1\n", ok("print {\"a\": 1}.clone().size()"));
        assertEquals("{\"a\": 1}\n", ok("print {\"a\": 1}.text()"));
        assertEquals("2\n", ok("print {\"a\": 1, \"b\": 2}.entries().length()"));
        assertEquals("a\n", ok("print {\"a\": 1}.entries()[0].key"));
    }

    @Test
    void setMethodsExtensive() {
        assertEquals("3\n", ok("print {1, 2}.union({2, 3}).size()"));
        assertEquals("true\ntrue\n", ok("""
                s = {1}
                print s.has(1)
                print s.contains(1)
                """));
        assertEquals("[1]\n", ok("print set([1]).to_list()"));
        assertEquals("1\n", ok("if {1, 2}.text().length() > 0\n  print 1\nend"));
    }

    @Test
    void rangeBehaviour() {
        assertEquals("0\n", ok("print (5 until 5).size()"));
        assertEquals("1\n", ok("print (5 to 5).size()"));
        assertEquals("[1, 2, 3]\n", ok("print (1 to 3).toList()"));
        // A range count is 64-bit like every other number, so it must not be narrowed
        // through an int on its way to `length`.
        assertEquals("3000000001\n", ok("print length(0 to 3000000000)"));
    }

    @Test
    void reprKeepsQuotes() {
        assertEquals("\"boom\"\n", ok("print repr(\"boom\")"));
    }

    @Test
    void unsupportedOperationMessages() {
        OqRunner.Result result = OqRunner.run("print 1 - \"a\"");
        assertTrue(result.stderr().contains("cannot subtract"), result.stderr());
        result = OqRunner.run("print 1 + true");
        assertTrue(result.stderr().contains("cannot add"), result.stderr());
        result = OqRunner.run("print 1 % 0");
        assertTrue(result.stderr().contains("division by zero"), result.stderr());
    }
}
