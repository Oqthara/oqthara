package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void numericEqualityReachesInsideCollections() {
        // ADR-0017. `1 == 1.0` is true, so every container has to agree: a whole number and a
        // whole decimal are the same value wherever they appear. Before ADR-0017 the first
        // line was true and the rest were false, because `Values.equal` widened numerics at
        // the top level and then fell through to `Object.equals`, where
        // `Long.equals(Double)` is false.
        assertEquals("true\ntrue\ntrue\ntrue\ntrue\n", ok("""
                print 1 == 1.0
                print [1] == [1.0]
                print (1, 2) == (1.0, 2)
                print {1: "a"} == {1.0: "a"}
                print set([1]) == set([1.0])
                """));
    }

    @Test
    void equalNumbersHashAlike() {
        // Hash consistency with equality, the other half of the ADR-0017 contract. A set or map
        // keyed by hash must not be able to hold two values that compare equal, so equal
        // numbers have to hash alike as well.
        assertEquals("true\n1\ntrue\ntrue\n1\n", ok("""
                print 1 == 1.0
                print length(set([1, 1.0]))
                print 1 in set([1.0])
                print 1.0 in set([1])
                m = {}
                m[1] = "a"
                m[1.0] = "b"
                print length(keys(m))
                """));
    }

    @Test
    void mutatingADataKeyMakesItUnreachable() {
        // `OqData.hashCode()` is derived from mutable fields, so changing a field after the
        // value is used as a map key leaves the entry present but unfindable. Recorded in
        // ADR-0015 as a reason hashability is currently unenforced.
        assertEquals("nothing\n1\n", ok("""
                data P(x)
                p = P(1)
                m = {}
                m[p] = "a"
                p.x = 2
                print m[p]
                print length(keys(m))
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

    @Test
    void anIndexOfZeroRemovesTheFirstElement() {
        assertEquals("1\n[2, 3]\n", ok("""
                l = [1, 2, 3]
                print l.remove(0)
                print l
                """));
    }

    @Test
    void aNegativeIndexOfMinusOneRemovesTheLastElement() {
        assertEquals("3\n[1, 2]\n", ok("""
                l = [1, 2, 3]
                print l.remove(-1)
                print l
                """));
    }

    @Test
    void removePastTheEndIsRefusedRatherThanIgnored() {
        fails("""
                l = [1, 2, 3]
                l.remove(99)
                """, "index 99 is out of range for a list of length 3");
        fails("""
                l = [1, 2, 3]
                l.remove(-4)
                """, "index -4 is out of range for a list of length 3");
    }

    @Test
    void removeNeedsAnIndexAndNamesRemoveValueForAValue() {
        fails("""
                l = [1, 2, 3]
                l.remove("x")
                """, "remove_value");
    }

    @Test
    void insertAcceptsTheLengthAndNegativeIndexes() {
        assertEquals("[1, 2, 3, 9]\n", ok("""
                l = [1, 2, 3]
                l.insert(3, 9)
                print l
                """));
        assertEquals("[1, 2, 9, 3]\n", ok("""
                l = [1, 2, 3]
                l.insert(-1, 9)
                print l
                """));
        assertEquals("[5]\n", ok("""
                l = []
                l.insert(0, 5)
                print l
                """));
    }

    @Test
    void insertPastTheLengthIsRefusedWithoutAnInternalError() {
        OqRunner.Result result = OqRunner.run("""
                l = [1, 2, 3]
                l.insert(4, 9)
                """);
        assertEquals(1, result.code(), "internal error path reached: " + result.stderr());
        assertTrue(result.stderr().contains("index 4 is out of range for a list of length 3"),
                "wrong message: " + result.stderr());
        assertFalse(result.stderr().contains("This is a bug in Oqthara"),
                "reported as a language defect: " + result.stderr());
    }

    @Test
    void anOutOfRangeIndexReadIsStillRefused() {
        fails("""
                l = [1, 2, 3]
                print l.get(99)
                """, "index 99 is out of range for a list of length 3");
        assertEquals("3\n", ok("""
                l = [1, 2, 3]
                print l.get(-1)
                """));
    }

    @Test
    void everyIndexOperationSharesOneMessage() {
        String[] sources = {
            "l = [1, 2, 3]\nl.get(99)\n",
            "l = [1, 2, 3]\nl[99]\n",
            "l = [1, 2, 3]\nl.remove(99)\n",
            "l = [1, 2, 3]\nl.insert(99, 9)\n",
        };
        for (String source : sources) {
            OqRunner.Result result = OqRunner.run(source);
            assertEquals(1, result.code(), "expected a refusal for: " + source);
            assertTrue(result.stderr().contains("is out of range for a list of length 3"),
                    "message drifted for: " + source + " -> " + result.stderr());
        }
    }

    @Test
    void aClonedMapKeepsTheEqualityContract() {
        assertEquals("a\ntrue\nmap\n", ok("""
                m = {1: "a"}
                c = m.clone()
                print c[1.0]
                print c == {1.0: "a"}
                print type_of(c)
                """));
    }

    @Test
    void aSliceTakesARangeAndOmittingABoundMeansThatEnd() {
        assertEquals("[2, 3]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[1:3]
                """));
        assertEquals("[1, 2, 3]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[:3]
                """));
        assertEquals("[3, 4, 5]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[2:]
                """));
        assertEquals("[1, 2, 3, 4, 5]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[:]
                """));
    }

    @Test
    void aNegativeSliceBoundCountsFromTheEnd() {
        assertEquals("[4, 5]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[-2:]
                """));
        assertEquals("[1, 2, 3, 4]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[0:-1]
                """));
        assertEquals("[3, 4]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[-3:-1]
                """));
    }

    @Test
    void aStepWalksTheSliceAndANegativeStepReversesIt() {
        assertEquals("[1, 3, 5]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[::2]
                """));
        assertEquals("[5, 4, 3, 2, 1]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[::-1]
                """));
        assertEquals("[5, 4, 3]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs[4:1:-1]
                """));
    }

    @Test
    void anOutOfRangeSliceBoundIsClampedButAZeroStepIsRefused() {
        assertEquals("[1, 2, 3]\n", ok("""
                xs = [1, 2, 3]
                print xs[0:999]
                """));
        fails("""
                xs = [1, 2, 3]
                print xs[::0]
                """, "a slice step cannot be 0");
    }

    @Test
    void aSliceKeepsTheFamilyItCameFrom() {
        assertEquals("bc\n", ok("print \"abcdef\"[1:3]"));
        assertEquals("cba\n", ok("print \"abc\"[::-1]"));
        assertEquals("(1, 2)\n", ok("""
                t = (1, 2, 3)
                print t[0:2]
                """));
        assertEquals("[2, 3, 4]\n", ok("print range(0, 10)[2:5]"));
    }

    @Test
    void theSliceMethodResolvesNegativesLikeTheSyntax() {
        assertEquals("[4, 5]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs.slice(-2, 5)
                """));
        assertEquals("[1, 2, 3, 4]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs.slice(0, -1)
                """));
        assertEquals("[4, 5]\n", ok("""
                xs = [1, 2, 3, 4, 5]
                print xs.slice(-2)
                """));
    }

    @Test
    void anIteratorWalksOnceAndThenSaysSo() {
        assertEquals("1\n2\nfalse\n", ok("""
                it = [1, 2].iter()
                print it.next()
                print it.next()
                print it.has_next()
                """));
        assertEquals("true\nfalse\n", ok("""
                print [1].iter().has_next()
                print [].iter().has_next()
                """));
    }

    @Test
    void anExhaustedIteratorRefusesRatherThanReturningNothing() {
        // A `nothing` sentinel would be indistinguishable from a list that contains `nothing`,
        // so the loop could not be written correctly. ADR-0020 rejects that alternative.
        fails("""
                it = [1].iter()
                it.next()
                it.next()
                """, "this iterator is exhausted");
    }

    @Test
    void everyIterableFamilyHasAnIterator() {
        // Maps yield keys, matching what `for` does, so one rule covers both.
        assertEquals("1\n2\n", ok("""
                for x in (1, 2).iter()
                    print x
                end
                """));
        assertEquals("a\nb\n", ok("""
                for c in "ab".iter()
                    print c
                end
                """));
        assertEquals("a\nb\n", ok("""
                for k in {"a": 1, "b": 2}.iter()
                    print k
                end
                """));
        assertEquals("0\n1\n", ok("""
                for i in range(0, 2).iter()
                    print i
                end
                """));
    }

    @Test
    void twoIteratorsOverOneListAreIndependent() {
        assertEquals("1\n1\n", ok("""
                xs = [1, 2]
                a = xs.iter()
                b = xs.iter()
                print a.next()
                print b.next()
                """));
    }

    @Test
    void anIteratorIsAPositionSoItHasNoLengthIndexOrMembership() {
        fails("print length([1, 2].iter())", "an iterator has no length");
        fails("xs = [1, 2]\nprint xs.iter()[0]", "cannot index iterator");
        fails("xs = [1, 2]\nprint xs.iter()[0:1]", "cannot slice iterator");
        fails("print 1 in [1, 2].iter()", "cannot check membership in iterator");
    }

    @Test
    void anIterableIsNotAnIterator() {
        // The two read alike, so the error has to name the fix rather than the spelling.
        fails("xs = [1, 2]\nprint xs.next()", "Call iter() first");
        fails("xs = [1, 2]\nprint xs.has_next()", "Call iter() first");
    }

    @Test
    void iterIsLazyWhileForStillSnapshots() {
        // ADR-0016's guarantee for `for` must survive adding a live iterator.
        assertEquals("1\n2\n3\n[1, 2, 3, 99]\n", ok("""
                xs = [1, 2, 3]
                for x in xs
                    if x == 1
                        xs.add(99)
                    end
                    print x
                end
                print xs
                """));
        // A for loop over an iterator drains it, so the second walk yields nothing.
        assertEquals("1\n2\n", ok("""
                it = [1, 2].iter()
                for x in it
                    print x
                end
                for y in it
                    print y
                end
                """));
    }

    @Test
    void anIteratorReprsItsSource() {
        assertEquals("iterator([1, 2, 3])\n", ok("print repr([1, 2, 3].iter())"));
        assertEquals("iterator\n", ok("print type_of([1].iter())"));
    }

    @Test
    void enumeratePairsEachItemWithItsPosition() {
        assertEquals("[[0, \"a\"], [1, \"b\"]]\n", ok("print enumerate([\"a\", \"b\"])"));
        assertEquals("[]\n", ok("print enumerate([])"));
    }

    @Test
    void aRangeCanAdvanceByAStep() {
        assertEquals("[0, 2, 4, 6, 8]\n", ok("print range(0, 10, 2).to_list()"));
        assertEquals("[5, 3, 1]\n", ok("print range(5, 0, -2).to_list()"));
        assertEquals("4\n", ok("print length(range(0, 10, 3))"));
        fails("print range(0, 5, 0).to_list()", "a range step cannot be 0");
    }

    @Test
    void aggregationReadsEveryCollectionFamily() {
        // `sum` used to delegate to a per-type method, so a set or range answered
        // "set has no method 'sum'" even though both are loopable and measurable.
        assertEquals("6\n", ok("print sum(set([1, 2, 3]))"));
        assertEquals("6\n", ok("print sum(range(0, 4))"));
        assertEquals("1\n", ok("print min([3, 1, 2])"));
        assertEquals("3\n", ok("print max([3, 1, 2])"));
        assertEquals("1\n", ok("print min(set([3, 1, 2]))"));
        fails("print min([])", "min() needs at least one value");
    }

    @Test
    void minAndMaxAcceptEitherACollectionOrValues() {
        // The arity decides the reading, so both spellings agree.
        assertEquals("1\n", ok("print min(3, 1, 2)"));
        assertEquals("1\n", ok("print min([3, 1, 2])"));
        assertEquals("3\n", ok("print max(3, 1, 2)"));
    }

    @Test
    void sortedReturnsACopyAndLeavesTheOriginalAlone() {
        assertEquals("[1, 2, 3]\n[3, 1, 2]\n", ok("""
                x = [3, 1, 2]
                y = sorted(x)
                print y
                print x"""));
        assertEquals("[\"a\", \"b\", \"c\"]\n", ok("print sorted(\"cba\")"));
        assertEquals("[1, 2, 3]\n", ok("print (3, 1, 2).sorted()"));
    }

    @Test
    void setAlgebraCoversDifferenceSubsetAndSuperset() {
        assertEquals("{1, 3}\n", ok("print set([1, 2]).symmetric_difference(set([2, 3]))"));
        assertEquals("true\nfalse\n",
                ok("print set([1]).issubset(set([1, 2]))\n"
                        + "print set([1, 9]).issubset(set([1, 2]))"));
        assertEquals("true\nfalse\n",
                ok("print set([1, 2]).issuperset(set([1]))\n"
                        + "print set([1]).issuperset(set([1, 2]))"));
    }

    @Test
    void aSetCloneIsIndependentOfItsSource() {
        assertEquals("{1, 2}\n{1, 2, 3}\n", ok("""
                s = set([1, 2])
                c = s.clone()
                c.add(3)
                print s
                print c"""));
    }

    @Test
    void mapMergeChangesInPlaceAndItemsYieldsPairs() {
        assertEquals("{\"a\": 1, \"b\": 2}\n{\"a\": 1, \"b\": 2}\n", ok("""
                m = {"a": 1}
                print m.merge({"b": 2})
                print m"""));
        assertEquals("[[\"a\", 1], [\"b\", 2]]\n", ok("print {\"a\": 1, \"b\": 2}.items()"));
        assertEquals("a 1\n", ok("""
                for kv in {"a": 1}.items()
                  print kv[0], kv[1]
                end"""));
    }

    @Test
    void mapPopRemovesOrFallsBackToTheDefault() {
        assertEquals("1\n{\"b\": 2}\n9\n", ok("""
                m = {"a": 1, "b": 2}
                print m.pop("a")
                print m
                print m.pop("z", 9)"""));
        fails("print {\"a\": 1}.pop(\"z\")", "this map has no key");
    }

    @Test
    void everyFamilyConvertsToEveryOtherFamily() {
        assertEquals("{1, 2}\n", ok("print [1, 1, 2].to_set()"));
        assertEquals("[\"a\", \"b\"]\n", ok("print \"ab\".to_list()"));
        assertEquals("{\"a\", \"b\"}\n", ok("print \"aab\".to_set()"));
        assertEquals("{1, 2}\n", ok("print (1, 1, 2).to_set()"));
        assertEquals("[\"a\"]\n", ok("print {\"a\": 1}.to_list()"));
    }

    @Test
    void keyedSortingReadsAnyFamilyAndIsStable() {
        // sort_by delegated to a `sort` method, which only a list has, so this reported
        // "set has no method 'sort'" while naming a method the caller never wrote.
        assertEquals("[1, 3]\n", ok("""
                print sort_by(set([3, 1]), fun(a)
                  return a
                end)"""));
        assertEquals("[(0, \"c\"), (1, \"a\"), (1, \"b\")]\n", ok("""
                print sort_by([(1, "a"), (1, "b"), (0, "c")], fun(t)
                  return t[0]
                end)"""));
        fails("print sort_by([1], 5)", "sort_by() needs a key function");
    }

    @Test
    void cloningATupleKeepsItATuple() {
        // A clone that returned a list would hand back something mutable and quietly change
        // what the value is, which is the one promise a tuple makes.
        assertEquals("tuple\n", ok("print type_of((1, 2).clone())"));
        fails("print (1, 2).push(3)", "a tuple is immutable");
        fails("print (3, 1).sort()", "a tuple is immutable");
    }

    @Test
    void aRangeHasAMethodSurface() {
        assertEquals("[0, 1, 2]\n", ok("print (0 until 3).to_list()"));
        assertEquals("0\n2\n", ok("print (0 until 3).first()\nprint (0 until 3).last()"));
        assertEquals("true\nfalse\n",
                ok("print (0 until 3).contains(2)\nprint (0 until 3).contains(3)"));
    }
}
