package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFeatureTest {
    private String ok(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    private void fails(String source, String fragment) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(1, result.code(), "expected failure, got: " + result.stdout());
        assertTrue(result.stderr().contains(fragment),
                "expected '" + fragment + "' in: " + result.stderr());
    }

    @Test
    void compoundAssignment() {
        assertEquals("15\n", ok("""
                x = 10
                x += 5
                print x
                """));
        assertEquals("5\n", ok("""
                x = 10
                x -= 5
                print x
                """));
        assertEquals("20\n", ok("""
                x = 10
                x *= 2
                print x
                """));
        assertEquals("5\n", ok("""
                x = 10
                x /= 2
                print x
                """));
    }

    @Test
    void indexAssignment() {
        assertEquals("[1, 9, 3]\n", ok("""
                xs = [1, 2, 3]
                xs[1] = 9
                print xs
                """));
        assertEquals("7\n", ok("""
                m = {"a": 1}
                m["a"] = 7
                print m["a"]
                """));
    }

    @Test
    void fieldAssignment() {
        assertEquals("9\n", ok("""
                data Box(value)
                b = Box(1)
                b.value = 9
                print b.value
                """));
    }

    @Test
    void fieldAssignmentOnMissingFieldFails() {
        fails("""
                data Box(value)
                b = Box(1)
                b.other = 9
                """, "has no field named 'other'");
    }

    @Test
    void setLiteralDeduplicates() {
        assertEquals("3\n", ok("""
                s = {1, 1, 2, 3, 3}
                print s.size()
                """));
    }

    @Test
    void emptyMapAndEmptySet() {
        assertEquals("0\n0\n", ok("""
                print {}.size()
                s = {1}
                s.remove(1)
                print s.size()
                """));
    }

    @Test
    void nestedFunctions() {
        assertEquals("42\n", ok("""
                fun outer()
                  fun inner()
                    return 42
                  end
                  return inner()
                end
                print outer()
                """));
    }

    @Test
    void recursionWithAccumulator() {
        assertEquals("5050\n", ok("""
                fun total(n, acc)
                  if n == 0
                    return acc
                  end
                  return total(n - 1, acc + n)
                end
                print total(100, 0)
                """));
    }

    @Test
    void whileWithBreak() {
        assertEquals("0\n1\n2\n", ok("""
                i = 0
                while true
                  if i == 3
                    break
                  end
                  print i
                  i = i + 1
                end
                """));
    }

    @Test
    void continueSkipsIteration() {
        assertEquals("1\n3\n5\n", ok("""
                for i in 1 to 5
                  if i % 2 == 0
                    continue
                  end
                  print i
                end
                """));
    }

    @Test
    void rangeCountsDown() {
        assertEquals("3\n2\n1\n", ok("""
                for i in 3 to 1
                  print i
                end
                """));
    }

    @Test
    void textIndexingAndSlicing() {
        assertEquals("h\no\n", ok("""
                s = "hello"
                print s[0]
                print s[-1]
                """));
        assertEquals("ell\n", ok("""
                s = "hello"
                print s.slice(1, 4)
                """));
    }

    @Test
    void sortingMixedData() {
        assertEquals("[1, 2, 3]\n", ok("print [3, 1, 2].sort()"));
    }

    @Test
    void mapMethods() {
        assertEquals("2\na,b\n1\n", ok("""
                m = {"a": 1, "b": 2}
                print m.size()
                print m.keys().sort().join(",")
                print m.values().sum() - 2
                """));
    }

    @Test
    void setAlgebra() {
        assertEquals("[2]\n[1]\n", ok("""
                a = {1, 2}
                b = {2, 3}
                print a.intersection(b).items()
                print a.difference(b).items()
                """));
    }

    @Test
    void divisionAndModulo() {
        assertEquals("2.5\n1\n-1\n", ok("""
                print 5 / 2
                print 7 % 3
                print -7 % 3
                """));
    }

    @Test
    void powerOperator() {
        assertEquals("8\n0.25\n2.0\n", ok("""
                print 2 ** 3
                print 4 ** -1
                print 4 ** 0.5
                """));
    }

    @Test
    void textAndNumberComparison() {
        assertEquals("true\ntrue\n", ok("""
                print "a" < "b"
                print 1 < 1.5
                """));
    }

    @Test
    void dataEquality() {
        assertEquals("true\nfalse\n", ok("""
                data P(x, y)
                print P(1, 2) == P(1, 2)
                print P(1, 2) == P(1, 3)
                """));
    }

    @Test
    void missingFieldFails() {
        fails("""
                data P(x)
                p = P(1)
                print p.y
                """, "has no field named 'y'");
    }

    @Test
    void lambdaBlockBody() {
        assertEquals("10\n", ok("""
                f = fun(x)
                  y = x + 1
                  return y * 2
                end
                print f(4)
                """));
    }

    @Test
    void higherOrderChain() {
        assertEquals("[18, 24, 30]\n", ok("""
                print [1, 2, 3, 4, 5].map(x -> x * 2).filter(x -> x > 5).map(x -> x * 3)
                """));
    }

    @Test
    void joinWithNumbers() {
        assertEquals("1-2-3\n", ok("print [1, 2, 3].join(\"-\")"));
    }

    @Test
    void multilineText() {
        assertEquals("a\nb\n", ok("""
                text = \"\"\"
                  a
                  b
                  \"\"\"
                print text
                """));
    }

    @Test
    void escapedBraces() {
        assertEquals("{x}\n", ok("print \"{{x}}\""));
    }

    @Test
    void nestedInterpolation() {
        assertEquals("HI\n", ok("""
                name = "hi"
                print "{name.upper()}"
                """));
    }

    @Test
    void errorMessagePointsAtLine() {
        OqRunner.Result result = OqRunner.run("""
                x = 1
                y = 2
                z = missing
                """);
        assertTrue(result.stderr().contains(":3:5"), result.stderr());
    }

    @Test
    void typeNameOfEveryValue() {
        assertEquals("number\ndecimal\ntext\nboolean\nnothing\nlist\nmap\nset\n",
                ok("""
                        print type_of(1)
                        print type_of(1.5)
                        print type_of("x")
                        print type_of(true)
                        print type_of(nothing)
                        print type_of([1])
                        print type_of({"a": 1})
                        print type_of({1})
                        """));
    }

    @Test
    void exitWithZero() {
        OqRunner.Result result = OqRunner.run("print \"a\"\nexit(0)\nprint \"b\"");
        assertEquals(0, result.code());
        assertEquals("a\n", result.stdout());
    }

    @Test
    void assertPasses() {
        assertEquals("ok\n", ok("assert 2 > 1\nprint \"ok\""));
    }

    @Test
    void stdinPrompt() {
        OqRunner.Result result = OqRunner.run("<test>", "n = input(\"name: \")\nprint n",
                java.util.List.of(), "Ada\n", java.nio.file.Path.of("."));
        assertEquals("name: Ada\n", result.stdout());
    }

    @Test
    void programArguments() {
        OqRunner.Result result = OqRunner.run("<test>", "print args().join(\",\")",
                java.util.List.of("a", "b"), "", java.nio.file.Path.of("."));
        assertEquals("a,b\n", result.stdout());
    }

    @Test
    void constBindsOnceAndKeepsItsValue() {
        assertEquals("100\n101\n", ok("""
                const MAX = 100
                print MAX
                print MAX + 1
                """));
    }

    @Test
    void constRejectsReassignment() {
        fails("""
                const MAX = 100
                MAX = 200
                """, "cannot change 'MAX'");
    }

    @Test
    void constDiagnosticNamesTheConstantAndNotSomethingElse() {
        fails("""
                const LIMIT = 3
                LIMIT = 4
                """, "A constant keeps its first value");
    }

    @Test
    void constRequiresAnInitializer() {
        fails("""
                const NAME
                print NAME
                """, "a constant needs a value");
    }

    @Test
    void constBindsTheNameNotTheObject() {
        // `const` fixes the binding, not the value behind it. A list it points at can still
        // be mutated, exactly as any other binding would allow.
        assertEquals("[1, 2, 3]\n", ok("""
                const C = [1, 2]
                C.push(3)
                print C
                """));
    }

    @Test
    void constIsScopedToItsBlock() {
        // The inner `const` is a fresh binding; the outer one is untouched and still
        // rejects assignment afterwards.
        assertEquals("1\n2\n", ok("""
                const OUTER = 1
                print OUTER
                if true
                  const INNER = 2
                  print INNER
                end
                """));
        fails("""
                const N = 1
                if true
                  N = 2
                end
                """, "cannot change 'N'");
    }

    @Test
    void constRejectsCompoundAssignment() {
        fails("""
                const N = 1
                N += 1
                """, "cannot change 'N'");
    }

    @Test
    void loopVariableExplainsWhyItCannotBeChanged() {
        fails("""
                for i in [1, 2]
                  i = 5
                end
                """, "the loop variable");
    }

    @Test
    void constIsReadableFromAFunctionBody() {
        assertEquals("6\n", ok("""
                const K = 5
                fun bumped()
                  return K + 1
                end
                print bumped()
                """));
    }

    @Test
    void constRejectsReassignmentFromAFunctionBody() {
        // The refusal has to follow the binding into a closure, not just the block that
        // declared it, or a function could defeat `const` by assigning through the parent scope.
        fails("""
                const K = 5
                fun clobber()
                  K = 6
                end
                clobber()
                """, "cannot change 'K'");
    }

    @Test
    void lambdaCapturesConstByReference() {
        assertEquals("7\n", ok("""
                const K = 3
                f = x -> x + K
                print f(4)
                """));
    }

    @Test
    void overloadsResolveByParameterCount() {
        assertEquals("9\n12\n", ok("""
                fun area(side)
                  return side * side
                end
                fun area(width, height)
                  return width * height
                end
                print area(3)
                print area(3, 4)
                """));
    }

    @Test
    void overloadResolutionIgnoresDeclarationOrder() {
        String forward = """
                fun area(side)
                  return side * side
                end
                fun area(width, height)
                  return width * height
                end
                print area(2, 5)
                print area(2)
                """;
        String reversed = """
                fun area(width, height)
                  return width * height
                end
                fun area(side)
                  return side * side
                end
                print area(2, 5)
                print area(2)
                """;
        assertEquals(ok(forward), ok(reversed));
        assertEquals("10\n4\n", ok(forward));
    }

    @Test
    void callingAnOverloadWithAnUnknownArityNamesTheAcceptedCounts() {
        fails("""
                fun area(side)
                  return side
                end
                fun area(width, height)
                  return width + height
                end
                print area(1, 2, 3)
                """, "2 arguments");
    }

    @Test
    void duplicateParameterCountIsRejectedAtTheSecondDeclaration() {
        fails("""
                fun f(a)
                  return a
                end
                fun f(b)
                  return b
                end
                """, "is already defined with 1 parameter");
    }

    @Test
    void overloadsRecurseAcrossArities() {
        assertEquals("24\n", ok("""
                fun fact(n)
                  return 1
                end
                fun fact(n, acc)
                  if n <= 1
                    return acc
                  end
                  return fact(n - 1, acc * n)
                end
                print fact(4, 1)
                """));
    }

    @Test
    void aUserFunctionReplacesABuiltinOfTheSameName() {
        assertEquals("mine\nmine2\n", ok("""
                fun length(a)
                  return "mine"
                end
                fun length(a, b)
                  return "mine2"
                end
                print length([1, 2, 3])
                print length(1, 2)
                """));
    }

    @Test
    void theBuiltinIsUnchangedWhenNoUserDefinitionExists() {
        assertEquals("3\n", ok("""
                print length([1, 2, 3])
                """));
    }

    @Test
    void plainAssignmentStillCreatesAndUpdatesVariables() {
        assertEquals("1\n2\n", ok("""
                x = 1
                print x
                x = 2
                print x
                """));
    }
}
