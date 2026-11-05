package dev.oqthara.cli;

import dev.oqthara.compiler.BytecodeCompiler;
import dev.oqthara.compiler.JarBuilder;
import dev.oqthara.compiler.Lowering;
import dev.oqthara.core.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-0048: real bytecode for the straight-line arithmetic subset, with an explicit and
 * honest fallback for everything else.
 */
class BytecodeCompilerTest {
    private static dev.oqthara.compiler.Ir.Program ir(String source) {
        return Lowering.lower(Parser.parse("<t>", source));
    }

    private String compileAndRun(String source, Path dir) throws Exception {
        dev.oqthara.compiler.Ir.Program program = ir(source);
        String reason = BytecodeCompiler.whyNot(program);
        assertNull(reason, "expected the program to be compilable, refused because: " + reason);
        byte[] compiled = BytecodeCompiler.compile(program, "oq.generated.Program");
        Path jar = dir.resolve("prog.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "prog.oq", compiled);
        return runJar(jar, dir);
    }

    @Test
    void compilesIntegerArithmetic(@TempDir Path dir) throws Exception {
        assertEquals("14\n20\n2\n", compileAndRun("print 2 + 3 * 4\nprint (2 + 3) * 4\nprint 10 - 8", dir));
    }

    @Test
    void compilesDivisionAndModulo(@TempDir Path dir) throws Exception {
        assertEquals("2.5\n1\n", compileAndRun("print 10 / 4\nprint 7 % 3", dir));
    }

    @Test
    void compilesDecimals(@TempDir Path dir) throws Exception {
        assertEquals("3.0\n", compileAndRun("print 2.5 + 0.5", dir));
    }

    @Test
    void compilesTextConcatenation(@TempDir Path dir) throws Exception {
        assertEquals("sum: 7\n", compileAndRun("print \"sum: \" + 7", dir));
    }

    @Test
    void compilesUnaryMinus(@TempDir Path dir) throws Exception {
        assertEquals("-3\n5\n", compileAndRun("print -5 + 2\nprint -(-5)", dir));
    }

    @Test
    void divisionByZeroReportsTheSameMessage(@TempDir Path dir) throws Exception {
        dev.oqthara.compiler.Ir.Program program = ir("print 1 / 0");
        byte[] compiled = BytecodeCompiler.compile(program, "oq.generated.Program");
        Path jar = dir.resolve("zero.jar");
        JarBuilder.build(jar, "oq.generated.Program", "print 1 / 0", "zero.oq", compiled);
        ProcessResult result = runJarProcess(jar, dir);
        assertTrue(result.exit() != 0, "expected a non-zero exit");
        assertTrue(result.stderr().contains("division by zero"), result.stderr());
    }

    @Test
    void compiledJarContainsNoEmbeddedSource(@TempDir Path dir) throws Exception {
        // The proof that this is compilation and not packaging.
        dev.oqthara.compiler.Ir.Program program = ir("print 1 + 1");
        byte[] compiled = BytecodeCompiler.compile(program, "oq.generated.Program");
        Path jar = dir.resolve("compiled.jar");
        JarBuilder.build(jar, "oq.generated.Program", "print 1 + 1", "x.oq", compiled);
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNull(file.getEntry("oq/main.oq"), "source must not be embedded");
            assertNull(file.getEntry("oq/source-name.txt"));
            assertNotNull(file.getEntry("oq/generated/Program.class"));
            assertEquals("classfile-compiled",
                    file.getManifest().getMainAttributes().getValue("Oqthara-Backend"));
        }
        assertEquals("2\n", runJar(jar, dir));
    }

    @Test
    void compilesVariablesAndReassignment(@TempDir Path dir) throws Exception {
        assertEquals("3\n11\n22\n", compileAndRun(
                "x = 1\ny = x + 2\nprint y\nx = x + 10\nprint x\nprint x * 2", dir));
    }

    @Test
    void compilesManyVariablesAcrossSlots(@TempDir Path dir) throws Exception {
        // Exercises astore_0..3 and the wide astore form beyond slot 3.
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            source.append("v").append(i).append(" = ").append(i).append("\n");
        }
        source.append("print v0 + v7\n");
        assertEquals("7\n", compileAndRun(source.toString(), dir));
    }

    @Test
    void compiledVariablesMatchTheInterpreterExactly(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "x = 5\nprint x",
                "x = 1\nx = 2\nprint x",
                "a = 2\nb = 3\nprint a * b",
                "price = 19.5\nqty = 2\nprint price * qty",
                "name = \"oq\"\nprint name + \"thara\"",
                "x = 1\ny = x\nz = y\nprint z");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir), "mismatch for: " + source);
        }
    }

    @Test
    void unsupportedConstructsAreRefusedWithAReason() {
        // Lambdas cannot become a value in a compiled class, so they are refused by name
        // instead of letting a method call receive a placeholder receiver.
        assertFalse(BytecodeCompiler.canCompile(ir("xs = [1, 2]\nprint xs.map(x -> x)")));
        String lambdaReason = BytecodeCompiler.whyNot(ir("xs = [1, 2]\nprint xs.map(x -> x)"));
        assertTrue(lambdaReason.contains("lambda"), lambdaReason);

        assertFalse(BytecodeCompiler.canCompile(ir("x = 1\nfun f()\n  return x\nend\nprint f()")));
        assertTrue(BytecodeCompiler.whyNot(ir("x = 1\nfun f()\n  return x\nend\nprint f()"))
                .contains("enclosing"));
    }

    @Test
    void compilesListLiteralsAndIndexing(@TempDir Path dir) throws Exception {
        assertEquals("[1, 2, 3]\n1\n3\n3\n", compileAndRun(
                "xs = [1, 2, 3]\nprint xs\nprint xs[0]\nprint xs[-1]\nprint length(xs)", dir));
    }

    @Test
    void compilesListIndexAssignment(@TempDir Path dir) throws Exception {
        assertEquals("[1, 20, 3]\n", compileAndRun(
                "xs = [1, 2, 3]\nxs[1] = 20\nprint xs", dir));
    }

    @Test
    void compilesMapLiteralsIndexingAndAssignment(@TempDir Path dir) throws Exception {
        assertEquals("1\n3\nnothing\n", compileAndRun(
                "m = {\"one\": 1, \"two\": 2}\nprint m[\"one\"]\n"
                        + "m[\"three\"] = 3\nprint m[\"three\"]\nprint m[\"nope\"]", dir));
    }

    @Test
    void compiledIterationMatchesTheInterpreter(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "for x in [1, 2, 3]\n  print x\nend",
                "for x in {1, 2, 3}\n  print x\nend",
                "for k in {\"a\": 1, \"b\": 2}\n  print k\nend",
                "for n in 1 to 3\n  print n\nend",
                "for c in \"abc\"\n  print c\nend",
                "for x in [1, 2, 3, 4]\n  if x == 2\n    continue\n  end\n"
                        + "  if x == 4\n    break\n  end\n  print x\nend");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir), "mismatch for: " + source);
        }
        assertEquals("a\nb\n", compileAndRun(
                "for k in {\"a\": 1, \"b\": 2}\n  print k\nend", dir));
        assertEquals("a\nb\nc\n", compileAndRun("for c in \"abc\"\n  print c\nend", dir));
    }

    @Test
    void compilesWriteWithoutStackUnderflow(@TempDir Path dir) throws Exception {
        assertEquals("ab\n", compileAndRun("write \"a\"\nwrite \"b\"\nprint \"\"", dir));
    }

    @Test
    void compilesNestedCollectionLiterals(@TempDir Path dir) throws Exception {
        assertEquals("3\n[[1, 2], [3, 4]]\n", compileAndRun(
                "grid = [[1, 2], [3, 4]]\nprint grid[1][0]\nprint grid", dir));
    }

    @Test
    void compilesEmptyCollectionLiterals(@TempDir Path dir) throws Exception {
        String source = "print []\nprint {}";
        String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
        assertEquals(expected, compileAndRun(source, dir));
    }

    @Test
    void compilesComparisons(@TempDir Path dir) throws Exception {
        assertEquals("true\nfalse\ntrue\ntrue\nfalse\ntrue\n",
                compileAndRun("print 1 < 2\nprint 1 > 2\nprint 2 <= 2\nprint 2 >= 2\n"
                        + "print 1 == 2\nprint 1 != 2", dir));
    }

    @Test
    void compilesIfElse(@TempDir Path dir) throws Exception {
        assertEquals("big\nsmall\n",
                compileAndRun("x = 5\nif x > 3\n  print \"big\"\nelse\n  print \"small\"\nend\n"
                        + "x = 1\nif x > 3\n  print \"big\"\nelse\n  print \"small\"\nend", dir));
    }

    @Test
    void compilesWhileLoop(@TempDir Path dir) throws Exception {
        assertEquals("0\n1\n2\n",
                compileAndRun("i = 0\nwhile i < 3\n  print i\n  i = i + 1\nend", dir));
    }

    @Test
    void compilesFunctionsWithParametersAndReturn(@TempDir Path dir) throws Exception {
        assertEquals("5\nhi oq\n", compileAndRun("""
                fun add(a, b)
                  return a + b
                end
                fun greet(name)
                  return "hi " + name
                end
                print add(2, 3)
                print greet("oq")
                """, dir));
    }

    @Test
    void compilesBareReturnAsNothing(@TempDir Path dir) throws Exception {
        assertEquals("3\nnothing\n", compileAndRun("""
                fun stop()
                  return
                end
                print 3
                print stop()
                """, dir));
    }

    @Test
    void compilesRecursionAndMutualRecursion(@TempDir Path dir) throws Exception {
        String source = """
                fun fact(n)
                  if n <= 1
                    return 1
                  end
                  return n * fact(n - 1)
                end
                fun is_even(n)
                  if n == 0
                    return true
                  end
                  return is_odd(n - 1)
                end
                fun is_odd(n)
                  if n == 0
                    return false
                  end
                  return is_even(n - 1)
                end
                print fact(5)
                print is_even(10)
                print is_odd(7)
                """;
        assertEquals(OqRunner.run("<t>", source, List.of(), "", dir).stdout(),
                compileAndRun(source, dir));
    }

    @Test
    void functionTouchingAGlobalIsRefusedByName(@TempDir Path dir) {
        String source = "count = 1\nfun bump()\n  return count + 1\nend\nprint bump()";
        String reason = BytecodeCompiler.whyNot(ir(source));
        assertNotNull(reason, "a function reading a global must be refused");
        assertTrue(reason.contains("count"), reason);
        assertEquals("2\n", OqRunner.run("<t>", source, List.of(), "", dir).stdout());
    }

    @Test
    void aFunctionNameCollidingWithACommandIsRefused() {
        String reason = BytecodeCompiler.whyNot(ir("fun print()\n  return 1\nend\nprint print()"));
        assertNotNull(reason);
        assertTrue(reason.contains("print"), reason);
    }

    @Test
    void aUserFunctionShadowingLengthWinsOverTheBuiltin(@TempDir Path dir) throws Exception {
        // The interpreter hoists user functions over builtins, so `fun length` must win.
        String source = "fun length(x)\n  return 99\nend\nprint length([1, 2, 3])";
        String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
        assertEquals("99\n", expected);
        assertEquals(expected, compileAndRun(source, dir));
    }

    @Test
    void interpolationCallsTheBuiltinNotAUserFunctionNamedText(@TempDir Path dir) throws Exception {
        // Interpolation is a language construct, not a user-facing `text` call.
        String source = "fun text(x)\n  return \"CUSTOM\"\nend\nn = 5\nprint \"v={n}\"";
        String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
        assertEquals("v=5\n", expected);
        assertEquals(expected, compileAndRun(source, dir));
    }

    @Test
    void membershipCallsTheBuiltinNotAUserFunctionNamedContains(@TempDir Path dir) throws Exception {
        // `in` is a language construct, not a user-facing `contains` call.
        String source = "fun contains(a, b)\n  return false\nend\nprint 2 in [1, 2, 3]";
        String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
        assertEquals("true\n", expected);
        assertEquals(expected, compileAndRun(source, dir));
    }

    @Test
    void loopVariableDoesNotLeakAfterTheLoop(@TempDir Path dir) {
        // The interpreter scopes the loop variable to each iteration.
        String source = "for x in [1, 2, 3]\n  print x\nend\nprint x";
        String reason = BytecodeCompiler.whyNot(ir(source));
        assertNotNull(reason, "a leaked loop variable must not compile");
        assertTrue(reason.contains("x"), reason);
        // OqRunner reports a runtime error as a non-zero result instead of throwing.
        assertTrue(OqRunner.run("<t>", source, List.of(), "", dir).code() != 0,
                "the interpreter must reject reading the loop variable after the loop");
    }

    @Test
    void methodCallsCoverEveryResolverFamily(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "xs = [1, 2]\nxs.push(3)\nprint xs",
                "xs = [1, 2]\nprint xs.pop()\nprint xs",
                "xs = [1, 2]\nprint xs.add(3)",
                "m = {\"a\": 1, \"b\": 2}\nprint m.keys()\nprint m.values()",
                "s = {1, 2}\ns.add(3)\nprint s.size()",
                "print \"hello\".upper()\nprint \"HELLO\".lower()",
                "print \"hello\".length()");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir), "mismatch for: " + source);
        }
    }

    @Test
    void dataConstructionAndFieldAccessMatchTheInterpreter(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "data Point(x, y)\np = Point(1, 2)\nprint p.x\nprint p.y",
                "data Point(x, y)\np = Point(1, 2)\np.x = 10\nprint p",
                "data Point(x, y)\nprint Point(1, 2) == Point(1, 2)",
                "data Address(city)\ndata Person(name, address)\n"
                        + "p = Person(\"Ada\", Address(\"London\"))\nprint p.address.city",
                "data Team(name, members)\n"
                        + "t = Team(\"Core\", [\"Ada\", \"Bob\"])\nprint t.members.length()");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir), "mismatch for: " + source);
        }
    }

    @Test
    void dataDefaultsAndFieldMutationMatchTheInterpreter(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "data Person(name, age = 0)\nprint Person(\"Ada\")\nprint Person(\"Bob\", 30)",
                "data Bag(items = [])\na = Bag()\nb = Bag()\na.items.push(1)\n"
                        + "print a.items\nprint b.items",
                "data Bag(items)\nb = Bag([1, 2])\nb.items.push(3)\nprint b.items");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir), "mismatch for: " + source);
        }
    }

    @Test
    void defaultThatReadsANameIsRefusedByName(@TempDir Path dir) throws Exception {
        // The interpreter evaluates a default where the type is declared; the backend can
        // only evaluate it where the record is constructed, so this must not compile.
        String source = "n = 1\ndata P(x = n)\nn = 99\nprint P()";
        String reason = BytecodeCompiler.whyNot(ir(source));
        assertNotNull(reason, "a name-reading default must not compile");
        assertTrue(reason.contains("scope"), reason);
        assertEquals("P(x: 99)\n", OqRunner.run("<t>", source, List.of(), "", dir).stdout());
    }

    @Test
    void invalidConstructionIsRefusedByName() {
        String missing = BytecodeCompiler.whyNot(ir("data P(x, y)\nprint P(1)"));
        assertNotNull(missing, "a missing field without a default must not compile");
        assertTrue(missing.contains("P") && missing.contains("y"), missing);

        String extra = BytecodeCompiler.whyNot(ir("data P(x)\nprint P(1, 2)"));
        assertNotNull(extra, "too many arguments must not compile");
        assertTrue(extra.contains("P"), extra);
    }

    @Test
    void unknownMethodsFailWithTheInterpreterMessage(@TempDir Path dir) throws Exception {
        // Unknown names go through the interpreter's own Java interop, so the compiled jar
        // reports the same message instead of inventing a different one.
        String source = "print \"a\".uppercase_text()";
        String interpreter = OqRunner.run("<t>", source, List.of(), "", dir).stderr();
        Path jar = dir.resolve("unknown.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "u.oq",
                BytecodeCompiler.compile(ir(source), "oq.generated.Program"));
        ProcessResult result = runJarProcess(jar, dir);
        assertTrue(result.exit() != 0, "expected a non-zero exit");
        String expected = interpreter.lines().findFirst().orElse("");
        assertTrue(result.stderr().contains(expected), result.stderr());
    }

    @Test
    void javaInteropIsRefusedByName() {
        String use = BytecodeCompiler.whyNot(ir("use java.time.LocalDate\nprint 1"));
        assertNotNull(use);
        assertTrue(use.contains("use"), use);
    }

    @Test
    void compilesForToLoop(@TempDir Path dir) throws Exception {
        assertEquals("1\n2\n3\n",
                compileAndRun("for n in 1 to 3\n  print n\nend", dir));
    }

    @Test
    void compilesForUntilLoop(@TempDir Path dir) throws Exception {
        assertEquals("1\n2\n",
                compileAndRun("for n in 1 until 3\n  print n\nend", dir));
    }

    @Test
    void compilesDescendingForLoop(@TempDir Path dir) throws Exception {
        // The interpreter walks a descending range downwards; a naive i <= to loop prints
        // nothing here, so this pins the direction logic.
        assertEquals("5\n4\n3\n2\n1\n",
                compileAndRun("for n in 5 to 1\n  print n\nend", dir));
    }

    @Test
    void compilesDescendingExclusiveForLoop(@TempDir Path dir) throws Exception {
        assertEquals("5\n4\n3\n2\n",
                compileAndRun("for n in 5 until 1\n  print n\nend", dir));
    }

    @Test
    void compilesBreakAndContinue(@TempDir Path dir) throws Exception {
        assertEquals("1\n3\n",
                compileAndRun("""
                        for n in 1 to 5
                          if n == 2
                            continue
                          end
                          if n == 4
                            break
                          end
                          print n
                        end""", dir));
    }

    @Test
    void compilesNestedLoops(@TempDir Path dir) throws Exception {
        assertEquals("0-0\n0-1\n1-0\n1-1\n", compileAndRun("""
                for i in 0 to 1
                  for j in 0 to 1
                    print i + "-" + j
                  end
                end""", dir));
    }

    @Test
    void compilesAndOrNot(@TempDir Path dir) throws Exception {
        // `and` and `or` yield booleans and short-circuit, matching the interpreter.
        assertEquals("true\nfalse\ntrue\nfalse\nfalse\ntrue\n", compileAndRun(
                "print true and true\nprint true and false\nprint false or true\n"
                        + "print false or false\nprint not true\nprint not false", dir));
    }

    @Test
    void logicalOperatorsCoerceToBooleans(@TempDir Path dir) throws Exception {
        // `1 and 2` is true, not 2: the interpreter coerces both sides through truthiness.
        assertEquals("true\ntrue\nfalse\n", compileAndRun(
                "print 1 and 2\nprint \"x\" or 0\nprint 0 and 1", dir));
    }

    @Test
    void logicalOperatorsShortCircuit(@TempDir Path dir) throws Exception {
        // 1 / 0 would fail if the right side were evaluated.
        assertEquals("false\ntrue\n", compileAndRun(
                "print false and (1 / 0 == 0)\nprint true or (1 / 0 == 0)", dir));
    }

    @Test
    void compilesFizzBuzzEndToEnd(@TempDir Path dir) throws Exception {
        String source = """
                for n in 1 to 15
                  if n % 15 == 0
                    print "FizzBuzz"
                  else
                    if n % 3 == 0
                      print "Fizz"
                    else
                      if n % 5 == 0
                        print "Buzz"
                      else
                        print n
                      end
                    end
                  end
                end""";
        String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
        assertEquals(expected, compileAndRun(source, dir));
        assertTrue(expected.startsWith("1\n2\nFizz\n"), expected);
    }

    @Test
    void controlFlowMatchesTheInterpreterExactly(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "i = 0\nwhile i < 3\n  print i\n  i = i + 1\nend",
                "for n in 1 to 4\n  print n * n\nend",
                "for n in 4 to 1\n  print n\nend",
                "for n in 0 until 3\n  print n\nend",
                "x = 7\nif x > 5\n  print \"high\"\nelse\n  print \"low\"\nend",
                "n = 0\nwhile true\n  n = n + 1\n  if n == 3\n    break\n  end\nend\nprint n",
                "total = 0\nfor n in 1 to 10\n  if n == 5\n    continue\n  end\n  total = total + n\nend\nprint total",
                "print (1 < 2) and (3 > 4)",
                "for n in 1 to 3\n  for m in 1 to 2\n    print n * m\n  end\nend",
                "x = 1\nif true\n  x = 2\nend\nprint x");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir), "mismatch for: " + source);
        }
    }

    @Test
    void fallbackStillRunsEverything(@TempDir Path dir) throws Exception {
        // A program outside the subset must still produce a working jar via the launcher.
        String source = "xs = [1, 2, 3]\nprint xs.map(x -> x * 2)";
        Path jar = dir.resolve("fallback.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "fallback.oq");
        assertEquals("[2, 4, 6]\n", runJar(jar, dir));
    }

    @Test
    void compiledOutputMatchesTheInterpreterOnArithmeticPrograms(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "print 1 + 1",
                "print -7 % 3",
                "print 2.5 * 4",
                "print \"a\" + \"b\"",
                "print 1 + 2 + 3 + 4 + 5",
                "print (1 + 2) * (3 + 4)");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir),
                    "mismatch for: " + source);
        }
    }

    @Test
    void everyCompilableConformanceProgramMatchesTheInterpreter(@TempDir Path dir) throws Exception {
        // This is the contract of the backend: a program is only compiled when the compiler
        // can prove it, and once compiled it must behave exactly like `oq run`. Comparing
        // whole outputs across the corpus catches drift in either direction.
        Path root = Path.of(System.getProperty("oq.root", ".")).resolve("conformance");
        int compiled = 0;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path program : stream.filter(p -> p.toString().endsWith(".oq")).sorted().toList()) {
                Path base = Path.of(program.toString().substring(0, program.toString().length() - 3));
                if (Files.exists(Path.of(base + ".err"))) continue;
                String source = Files.readString(program);
                dev.oqthara.compiler.Ir.Program lowered;
                try {
                    lowered = Lowering.lower(Parser.parse(program.toString(), source));
                } catch (RuntimeException parseFailure) {
                    continue; // parse-error cases carry a .err file; anything else is not ours
                }
                if (BytecodeCompiler.whyNot(lowered) != null) continue;
                String expected = OqRunner.run(program.toString(), source, List.of(), "", dir).stdout();
                assertEquals(expected, compileAndRun(source, dir),
                        "compiled output differs from the interpreter for " + program.getFileName());
                compiled++;
            }
        }
        assertEquals(139, compiled,
                "compiled conformance programs (raise this only with newly measured evidence)");
    }

    @Test
    void loopVariableCannotBeReassignedInCompiledCode() {
        // The interpreter rejects assigning to a loop variable; compiled code must refuse to
        // build rather than silently allow it.
        dev.oqthara.compiler.Ir.Program program = ir("""
                for n in 1 to 3
                  n = 9
                end
                """);
        String reason = BytecodeCompiler.whyNot(program);
        assertNotNull(reason, "assigning to a loop variable must not compile");
        assertTrue(reason.contains("fixed"), reason);
    }

    private record ProcessResult(int exit, String stdout, String stderr) {
    }

    private String runJar(Path jar, Path dir) throws Exception {
        ProcessResult result = runJarProcess(jar, dir);
        assertEquals(0, result.exit(), result.stderr());
        return result.stdout();
    }

    private ProcessResult runJarProcess(Path jar, Path dir) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toAbsolutePath().toString());
        builder.directory(dir.toFile());
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "jar timed out");
        return new ProcessResult(process.exitValue(), out.replace("\r\n", "\n"),
                err.replace("\r\n", "\n"));
    }
}
