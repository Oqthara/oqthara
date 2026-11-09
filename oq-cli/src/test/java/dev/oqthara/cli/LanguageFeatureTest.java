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
    void everyValueReportsALanguageLevelTypeName() {
        // `typeName` is the single place a value becomes text for `type_of`, diagnostics and
        // `repr`. A value missing from it leaks a host class name, which breaks the
        // abstraction and can differ between interpreter and compiled code.
        assertEquals("number\ndecimal\ntext\nboolean\nnothing\nlist\nset\nmap\ntuple\n"
                + "range\nfunction f\ntask\n", ok("""
                fun f()
                  return 1
                end
                print type_of(1)
                print type_of(1.5)
                print type_of("x")
                print type_of(true)
                print type_of(nothing)
                print type_of([1])
                print type_of({1})
                print type_of({"a": 1})
                print type_of((1, 2))
                print type_of(range(1, 3))
                print type_of(f)
                print type_of(spawn(f))
                """));
    }

    @Test
    void aTaskReportsItselfAsATask() {
        // Before this was fixed, `type_of(spawn(f))` printed the host class name `java OqTask`.
        assertEquals("task\n", ok("""
                fun f()
                  return 1
                end
                print type_of(spawn(f))
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
    void destructuringBindsEveryTarget() {
        assertEquals("1\n2\n", ok("""
                a, b = (1, 2)
                print a
                print b
                """));
    }

    @Test
    void destructuringReadsEverySequenceShape() {
        assertEquals("30\n", ok("""
                a, b = [10, 20]
                print a + b
                """));
        assertEquals("x\ny\n", ok("""
                first, second = {"x": 1, "y": 2}
                print first
                print second
                """));
        assertEquals("h\ni\n", ok("""
                p, q = "hi"
                print p
                print q
                """));
        assertEquals("1\n2\n", ok("""
                lo, hi = 1 to 2
                print lo
                print hi
                """));
    }

    @Test
    void destructuringUpdatesAnExistingBinding() {
        assertEquals("9\n", ok("""
                x = 0
                y = 0
                x, y = (9, 8)
                print x
                """));
    }

    @Test
    void aCountMismatchWritesNothing() {
        // The whole point of checking first: a failed destructuring must not leave the
        // targets half-updated.
        assertEquals("100\n200\n", ok("""
                a = 100
                b = 200
                try
                  a, b = (1, 2, 3)
                catch
                end
                print a
                print b
                """));
    }

    @Test
    void tooFewItemsIsReportedByName() {
        fails("""
                a, b, c = (1, 2)
                """, "2 items into 3 names");
    }

    @Test
    void tooManyItemsIsReportedByName() {
        fails("""
                a, b = (1, 2, 3)
                """, "3 items into 2 names");
    }

    @Test
    void aValueWithNoPartsCannotBeDestructured() {
        fails("""
                a, b = 5
                """, "cannot destructure number");
    }

    @Test
    void aDataValueIsNotDestructured() {
        // Fields are named, so positional access would invent an ordering the declaration
        // does not promise.
        fails("""
                data Point(x, y)
                p = Point(1, 2)
                a, b = p
                """, "cannot destructure Point");
    }

    @Test
    void destructuringIntoAConstRejectsReassignment() {
        fails("""
                const k, m = (1, 2)
                k = 9
                """, "cannot change 'k'");
    }

    @Test
    void constDestructuringBindsBothNames() {
        assertEquals("5\n6\n", ok("""
                const c, d = (5, 6)
                print c
                print d
                """));
    }

    @Test
    void aRepeatedTargetIsRejected() {
        fails("""
                a, a = (1, 2)
                """, "appears twice");
    }

    @Test
    void destructuringWorksInsideANestedScope() {
        assertEquals("3\n", ok("""
                fun total()
                  a, b = (1, 2)
                  return a + b
                end
                print total()
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

    @Test
    void aTryExpressionTakesTheValueOfTheBranchThatRan() {
        assertEquals("2\ncaught\n", ok("""
                items = [1]
                a = try
                  1 + 1
                catch e
                  0
                end
                print a
                b = try
                  items[9]
                catch e
                  "caught"
                end
                print b
                """));
    }

    @Test
    void aTryExpressionWhoseBranchDoesNotEndInAnExpressionIsNothing() {
        // ADR-0014: the value is the branch's final expression statement, not any
        // earlier one. A branch ending in a declaration has no value.
        assertEquals("nothing\nnothing\n", ok("""
                a = try
                  x = 1
                catch e
                  0
                end
                print a
                b = try
                  y = 1
                catch e
                  z = 2
                end
                print b
                """));
    }

    @Test
    void aTryExpressionIsUsableWhereverAnExpressionIs() {
        assertEquals("14\n", ok("""
                print (try
                  7
                catch e
                  8
                end) * 2
                """));
    }

    @Test
    void aTryExpressionNests() {
        // A `try` at the start of a statement is the statement form, exactly as a bare
        // `if` is; the nested one needs parentheses to be an expression.
        assertEquals("inner\ndeep\n", ok("""
                items = [1]
                n = try
                  items[9]
                catch outer
                  (try
                    items[8]
                  catch inner
                    "inner"
                  end)
                end
                print n
                m = try
                  items[9]
                catch outer
                  v = try
                    items[8]
                  catch inner
                    "deep"
                  end
                  v
                end
                print m
                """));
    }

    @Test
    void aTryExpressionBindsTheCaughtError() {
        assertEquals("'nope' is not defined\n", ok("""
                n = try
                  nope
                catch err
                  err["message"]
                end
                print n
                """).strip() + "\n");
    }
}
