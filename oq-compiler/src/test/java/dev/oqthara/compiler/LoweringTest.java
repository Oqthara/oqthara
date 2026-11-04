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
        List<Ir.Instr> body = program.main().body();
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
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.JumpIfFalse));
        assertTrue(body.stream().anyMatch(i -> i instanceof Ir.Jump));
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
        assertTrue(body.contains(new Ir.Call("text", 1)));
        assertTrue(body.contains(new Ir.Binary("+")));
    }

    @Test
    void lowersTry() {
        Ir.Program program = lower("""
                try
                  print 1
                catch e
                  print 2
                end
                """);
        assertTrue(program.main().body().stream().anyMatch(i -> i instanceof Ir.TryStart));
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
