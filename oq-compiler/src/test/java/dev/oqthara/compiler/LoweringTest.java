package dev.oqthara.compiler;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Parser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoweringTest {
    private Ir.Program lower(String source) {
        Ast.Program program = Parser.parse("<test>", source);
        return Lowering.lower(program);
    }

    @Test
    void lowersHelloWorld() {
        Ir.Program program = lower("print \"hi\"");
        // Position marks are interleaved with real work, because code outside a `try` can
        // still raise and must report the interpreter's line and column. The test therefore
        // looks at the instructions that do work, not at fixed indices.
        List<Ir.Instr> body = program.main().body().stream()
                .filter(instr -> !(instr instanceof Ir.At))
                .toList();
        assertEquals(new Ir.Const("hi"), body.get(0));
        assertEquals(new Ir.Call("print", 1), body.get(1));
        assertEquals(new Ir.Halt(), body.get(body.size() - 1));
    }

    @Test
    void lowersArithmetic() {
        Ir.Program program = lower("x = 1 + 2");
        List<Ir.Instr> body = program.main().body();
        assertTrue(body.contains(new Ir.Binary("+")));
        assertTrue(body.contains(new Ir.Store("x")));
    }

    @Test
    void lowersFunctionsSeparately() {
        Ir.Program program = lower("""
                fun add(a, b)
                  return a + b
                end
                print add(1, 2)
                """);
        assertEquals(1, program.functions().size());
        assertEquals("add", program.functions().get(0).name());
        assertEquals(2, program.functions().get(0).params().size());
    }

    @Test
    void lowersConditionalWithJumps() {
        Ir.Program program = lower("""
                if x > 1
                  print "big"
                end
                """);
        List<Ir.Instr> body = program.main().body();
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.JumpIfFalseLabel));
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.JumpLabel));
    }

    @Test
    void logicalOperatorsProduceBooleansOnBothBackends() {
        Ir.Program program = lower("print 1 and 2");
        List<Ir.Instr> body = program.main().body();
        assertTrue(body.contains(new Ir.ToBool()),
                "each operand must be coerced to a boolean");
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Const c && Boolean.FALSE.equals(c.value())),
                "the short-circuit path must push false");
    }

    @Test
    void descendingRangeIsNotSimplifiedIntoAnAscendingLoop() {
        // A range whose bounds are variables cannot be decided at compile time, so the
        // lowering must compute the step at runtime rather than emit a fixed `<=` test.
        Ast.Program program = Parser.parse("<t>", """
                a = 5
                b = 1
                for n in a to b
                  print n
                end
                """);
        Lowering.Result result = Lowering.lowerWithReport(program);
        assertTrue(result.divergences().isEmpty());
        List<Ir.Instr> body = result.program().main().body();
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Const c && Long.valueOf(-1L).equals(c.value())),
                "the negative step must be materialized");
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Label l && l.name().endsWith("_desc")),
                "a descending branch must exist");
    }

    @Test
    void blocksOpenAndCloseScopes() {
        Ast.Program program = Parser.parse("<t>", """
                if true
                  x = 1
                end
                """);
        List<Ir.Instr> body = Lowering.lower(program).main().body();
        assertEquals(1, body.stream().filter(i -> i instanceof Ir.ScopeEnter).count());
        assertEquals(1, body.stream().filter(i -> i instanceof Ir.ScopeExit).count());
    }

    @Test
    void assignmentIsASingleStoreAndTheBackendDefinesOnFirstUse() {
        // `x = 1` parses as Assign, not VarDecl, and the interpreter's assignOrDefine
        // creates the binding. The IR keeps that as one Store; the backend resolves it to a
        // new slot the first time and to the existing slot afterwards.
        Ast.Program program = Parser.parse("<t>", "x = 1\nx = 2");
        List<Ir.Instr> body = Lowering.lower(program).main().body();
        assertEquals(2, body.stream().filter(i -> i instanceof Ir.Store s && s.name().equals("x")).count());
        assertFalse(body.stream().anyMatch(i -> i instanceof Ir.Declare));
    }

    @Test
    void loopVariablesAreDeclaredFixed() {
        Ast.Program program = Parser.parse("<t>", """
                for n in 1 to 3
                  print n
                end
                """);
        List<Ir.Instr> body = Lowering.lower(program).main().body();
        assertTrue(body.contains(new Ir.Declare("n", true)),
                "a loop variable is fixed, so assigning to it must be refused");
    }

    @Test
    void lowersForRange() {
        Ir.Program program = lower("""
                for n in 1 to 3
                  print n
                end
                """);
        List<Ir.Instr> body = program.main().body();
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Store s && s.name().startsWith("_i")));
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Binary b && b.op().equals("<=")));
    }

    @Test
    void lowersListLiteral() {
        Ir.Program program = lower("xs = [1, 2, 3]");
        assertTrue(program.main().body().contains(new Ir.MakeList(3)));
    }

    @Test
    void lowersMapLiteral() {
        Ir.Program program = lower("m = {\"a\": 1}");
        assertTrue(program.main().body().contains(new Ir.MakeMap(1)));
    }

    @Test
    void lowersInterpolation() {
        Ir.Program program = lower("name = \"a\"\nprint \"hi {name}\"");
        List<Ir.Instr> body = program.main().body();
        assertTrue(body.contains(new Ir.Text()));
        assertTrue(body.contains(new Ir.Binary("+")));
    }

    @Test
    void tryLowersToAProtectedRegionAndAHandler() {
        Ir.Program program = lower("""
                try
                  print 1
                catch e
                  print e.message
                end
                """);
        List<Ir.Instr> body = program.main().body();
        assertEquals(1, body.stream().filter(i -> i instanceof Ir.TryStart).count());
        assertEquals(1, body.stream().filter(i -> i instanceof Ir.TryEnd).count());
        assertEquals(1, body.stream().filter(i -> i instanceof Ir.HandlerLabel).count());
        assertEquals(1, body.stream().filter(i -> i instanceof Ir.CatchValue).count());
        // The handler is the only place the caught value becomes an Error record, and the
        // binding is fixed: the interpreter defines it with defineConst.
        assertTrue(body.contains(new Ir.Declare("e", true)));
        String start = Ir.disassemble(program);
        assertTrue(start.contains("try_start catch_"), start);
        assertFalse(start.contains("refuse"), start);
    }

    @Test
    void tryEndsBeforeTheHandlerAndSkipsItOnNormalCompletion() {
        String disassembly = Ir.disassemble(lower("""
                try
                  print 1
                catch e
                  print 2
                end
                """));
        // try_end must precede the handler label, and a jump over the handler must exist.
        assertTrue(disassembly.indexOf("try_end") < disassembly.indexOf("(catch)"), disassembly);
        assertTrue(disassembly.contains("jump catch_end_"), disassembly);
    }

    @Test
    void nestedTryProducesTwoRegionsInEmissionOrder() {
        Ir.Program program = lower("""
                try
                  try
                    print 1
                  catch inner
                    print 2
                  end
                catch outer
                  print 3
                end
                """);
        List<Ir.Instr> body = program.main().body();
        assertEquals(2, body.stream().filter(i -> i instanceof Ir.TryStart).count());
        assertEquals(2, body.stream().filter(i -> i instanceof Ir.TryEnd).count());
        assertEquals(2, body.stream().filter(i -> i instanceof Ir.HandlerLabel).count());
        // The inner region closes first, which is the order the class-file table needs.
        int firstEnd = indexOfType(body, Ir.TryEnd.class, 0);
        int secondEnd = indexOfType(body, Ir.TryEnd.class, firstEnd + 1);
        int firstHandler = indexOfType(body, Ir.HandlerLabel.class, 0);
        assertTrue(firstEnd < secondEnd, "inner try must close first");
        assertTrue(firstEnd < firstHandler, "the body region ends before its handler");
    }

    @Test
    void errorCallsLowerToTheRuntimeBuiltin() {
        String disassembly = Ir.disassemble(lower("""
                try
                  error("boom")
                catch e
                  print e.message
                end
                """));
        assertTrue(disassembly.contains("call error/1"), disassembly);
        // The span of the failing operation travels with the IR so a caught error reports
        // the same line and column as the interpreter.
        assertTrue(disassembly.contains("at "), disassembly);
    }

    private static int indexOfType(List<Ir.Instr> body, Class<?> type, int from) {
        for (int i = from; i < body.size(); i++) {
            if (type.isInstance(body.get(i))) return i;
        }
        return -1;
    }

    @Test
    void breakAndContinueUseLoopLabels() {
        Ast.Program program = Parser.parse("<t>", """
                for i in 1 to 5
                  if i == 2
                    continue
                  end
                  if i == 4
                    break
                  end
                  print i
                end
                """);
        Lowering.Result result = Lowering.lowerWithReport(program);
        assertTrue(result.divergences().isEmpty(),
                "break and continue must not diverge: " + result.divergences());
        List<Ir.Instr> body = result.program().main().body();
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Break b && b.loopId().equals("loop0")),
                "expected a break targeting loop0");
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Continue c && c.loopId().equals("loop0")),
                "expected a continue targeting loop0");
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Label l && l.name().equals("loop0_end")));
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Label l && l.name().equals("loop0_step")));
    }

    @Test
    void nestedLoopsTargetTheInnermost() {
        Ast.Program program = Parser.parse("<t>", """
                for i in 1 to 3
                  for j in 1 to 3
                    break
                  end
                end
                """);
        Lowering.Result result = Lowering.lowerWithReport(program);
        assertTrue(result.divergences().isEmpty());
        assertTrue(result.program().main().body().stream()
                .anyMatch(i -> i instanceof Ir.Break b && b.loopId().equals("loop1")));
    }

    @Test
    void breakOutsideALoopIsRejectedByTheCompiler() {
        Ast.Program program = Parser.parse("<t>", "break");
        dev.oqthara.runtime.OqError error = org.junit.jupiter.api.Assertions.assertThrows(
                dev.oqthara.runtime.OqError.class, () -> Lowering.lower(program));
        assertTrue(error.getMessage().contains("break"));
    }

    @Test
    void cleanProgramHasNoDivergences() {
        Ast.Program program = Parser.parse("<t>", "print 1 + 2");
        assertTrue(Lowering.lowerWithReport(program).divergences().isEmpty());
    }

    @Test
    void disassemblesWithoutThrowing() {
        String text = Ir.disassemble(lower("print \"hi\""));
        assertTrue(text.contains("function main"));
        assertTrue(text.contains("call print/1"));
    }

    @Test
    void everyConformanceProgramLowers() throws Exception {
        Path root = Path.of(System.getProperty("oq.root", ".")).resolve("conformance");
        assertTrue(Files.isDirectory(root), "missing conformance directory");
        List<Path> programs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".oq"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(programs::add);
        }
        assertFalse(programs.isEmpty());
        int lowered = 0;
        for (Path program : programs) {
            Path base = Path.of(program.toString().substring(0, program.toString().length() - 3));
            if (Files.exists(Path.of(base + ".err"))) {
                // Programs that must fail to parse are not candidates for lowering.
                continue;
            }
            String source = Files.readString(program);
            Ast.Program ast = Parser.parse(program.getFileName().toString(), source);
            Ir.Program ir = Lowering.lower(ast);
            String text = Ir.disassemble(ir);
            assertTrue(text.contains("function main"), program.toString());
            lowered++;
        }
        assertTrue(lowered >= 100, "expected at least 100 lowering cases, got " + lowered);
    }

    @Test
    void lowersDataConstructionAndFieldAccess() {
        Ir.Program program = lower("data Point(x, y)\np = Point(1, 2)\nprint p.x\np.y = 3");
        List<Ir.Instr> body = program.main().body();
        assertTrue(body.contains(new Ir.MakeData("Point", List.of("x", "y"), false)),
                Ir.disassemble(program));
        assertTrue(body.contains(new Ir.GetField("x")), Ir.disassemble(program));
        assertTrue(body.contains(new Ir.SetField("y")), Ir.disassemble(program));
    }

    @Test
    void dataDefaultsAreFilledInAtTheCallSite() {
        Ir.Program program = lower("data Person(name, age = 7)\nprint Person(\"Ada\")");
        List<Ir.Instr> body = program.main().body();
        int make = body.indexOf(new Ir.MakeData("Person", List.of("name", "age"), false));
        assertTrue(make > 0, Ir.disassemble(program));
        assertTrue(body.subList(0, make).contains(new Ir.Const(7L)),
                "the default must be evaluated as part of the construction");
    }

    @Test
    void invalidConstructionBecomesARefusal() {
        Ir.Program program = lower("data P(x, y)\nprint P(1)");
        String disassembly = Ir.disassemble(program);
        assertTrue(disassembly.contains("refuse"), disassembly);
        assertTrue(disassembly.contains("y"), disassembly);
    }

    @Test
    void methodCallsLowerToRuntimeDispatch() {
        Ir.Program program = lower("xs = [1]\nxs.push(2)\nprint \"a\".upper()");
        String disassembly = Ir.disassemble(program);
        assertTrue(disassembly.contains("method_call push/2"), disassembly);
        assertTrue(disassembly.contains("method_call upper/1"), disassembly);
    }

    @Test
    void lambdasLowerToAGeneratedMethodAndJavaInteropIsRefusedByName() {
        String disassembly = Ir.disassemble(lower("f = x -> x"));
        assertTrue(disassembly.contains("lambda lambda$0/1"), disassembly);
        assertTrue(disassembly.contains("function lambda$0"), disassembly);
        assertTrue(Ir.disassemble(lower("use java.time.LocalDate")).contains("'use'"));
    }

    @Test
    void backendSelectionIsHonest() {
        Backends.Kind kind = Backends.select();
        int feature = Backends.featureVersion();
        if (feature >= 24) {
            assertEquals(Backends.Kind.CLASSFILE, kind);
        } else {
            assertEquals(Backends.Kind.DIAGNOSTIC, kind);
            assertTrue(Backends.diagnostic(kind, feature).contains("JDK 24"));
        }
    }
}
