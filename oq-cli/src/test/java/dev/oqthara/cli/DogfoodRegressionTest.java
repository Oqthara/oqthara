package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regressions found by dogfooding real programs. Each test names the program that exposed
 * the bug, so the reason the case exists is never lost.
 */
class DogfoodRegressionTest {
    private String ok(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    @Test
    void helloWorldIsOneLine() throws Exception {
        java.nio.file.Path hello = java.nio.file.Path.of(System.getProperty("oq.root", "."))
                .resolve("examples/hello.oq");
        String source = java.nio.file.Files.readString(hello);
        assertEquals(1, source.strip().split("\n").length,
                "hello world must stay one line");
        assertEquals("Hello, World!\n", ok(source));
    }

    @Test
    void mutableDataDefaultsAreNotShared() {
        // Found by examples/queue.oq: dequeue(Queue()) returned the previous queue's item.
        assertEquals("[1]\n[]\n", ok("""
                data Bag(items = [])
                a = Bag()
                b = Bag()
                a.items.push(1)
                print a.items
                print b.items
                """));
    }

    @Test
    void dataDefaultExpressionsAreReevaluated() {
        // The default is evaluated at construction time, against the live environment.
        assertEquals("1\n6\n", ok("""
                counter = 0
                data Box(value = counter + 1)
                a = Box()
                print a.value
                counter = 5
                b = Box()
                print b.value
                """));
    }

    @Test
    void interpolationWithQuotedExpression() {
        // Found by an edge-case sweep: {'abc'.upper()} printed literally.
        assertEquals("ABC\n3\n0\n", ok("""
                print "{'abc'.upper()}"
                print "{\\"abc\\".length()}"
                print "{''.chars().length()}"
                """));
    }

    @Test
    void jsonTextIsNotInterpolation() {
        assertEquals("{\"a\": 1}\n1\n", ok("""
                print "{\\"a\\": 1}"
                print json_parse("{\\"a\\": 1}")["a"]
                """));
    }

    @Test
    void anagramGroupingWorks() {
        assertEquals("act, cat\nlisten, silent\n", ok("""
                words = ["listen", "silent", "act", "cat", "unique"]
                groups = {}
                for word in words
                  key = word.chars().sort().join("")
                  if not groups.has(key)
                    groups[key] = []
                  end
                  groups[key].push(word)
                end
                for key in groups.keys().sort()
                  if groups[key].length() > 1
                    print groups[key].join(", ")
                  end
                end
                """));
    }

    @Test
    void recursiveTreeInsertAndTraversal() {
        assertEquals("[1, 3, 4, 5, 7, 8]\nin order ok\n", ok("""
                data Node(value, left = nothing, right = nothing)

                fun insert(tree, value)
                  if tree == nothing
                    return Node(value)
                  end
                  if value < tree.value
                    tree.left = insert(tree.left, value)
                  else
                    tree.right = insert(tree.right, value)
                  end
                  return tree
                end

                fun inorder(tree, out)
                  if tree == nothing
                    return out
                  end
                  inorder(tree.left, out)
                  out.push(tree.value)
                  inorder(tree.right, out)
                  return out
                end

                tree = nothing
                for v in [5, 3, 8, 1, 4, 7]
                  tree = insert(tree, v)
                end
                print inorder(tree, [])
                print "in order ok"
                """));
    }

    @Test
    void multilineJsonStringWithNestedBraces() {
        // Found by conformance/191: the lexer stopped at the first closing brace.
        assertEquals("2\n", ok("""
                value = json_parse("{\\"a\\": {\\"b\\": [1, 2]}}")
                print value["a"]["b"][1]
                """));
    }

    @Test
    void successfulSortByListKey() {
        // Found by examples/frequency.oq: multi-key sorting needed list comparison.
        assertEquals("[[\"a\", 1], [\"b\", 2]]\n", ok("""
                pairs = [["b", 2], ["a", 1]]
                print pairs.sort(p -> p)
                """));
    }

    @Test
    void roundToPlaces() {
        // Found by examples/text_stats.oq: there was no way to round to two decimals.
        assertEquals("84.33\n3\n", ok("""
                print round(84.3333, 2)
                print round(2.5)
                """));
    }

    @Test
    void toAsParameterName() {
        // Found by examples/hanoi.oq: 'to' was reserved, so fun hanoi(n, from, to, via) failed.
        assertEquals("7\n", ok("""
                fun moves(n, from, to, via)
                  if n == 0
                    return 0
                  end
                  return moves(n - 1, from, via, to) + 1 + moves(n - 1, via, to, from)
                end
                print moves(3, "A", "C", "B")
                """));
    }
}
