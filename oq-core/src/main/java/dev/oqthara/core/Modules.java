package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves {@code use "oq:name"} imports. A module is another {@code .oq} file that is
 * parsed and whose top-level functions and data types are hoisted into the importing
 * program. Modules are loaded once, and cycles are rejected with a clear message.
 */
public final class Modules {
    private Modules() {
    }

    /** Resolves module imports relative to a directory, used by embedded jars. */
    public static Ast.Program resolveAgainst(Ast.Program program, Path base) {
        Set<Path> loaded = new LinkedHashSet<>();
        List<Ast.Stmt> statements = new ArrayList<>();
        collect(program, base, loaded, statements, 0);
        return new Ast.Program(statements, program.sourceName());
    }

    /**
     * Flattens module imports into one self-contained source string for {@code oq build}, so
     * a generated jar does not depend on module files existing next to it at run time.
     *
     * <p>Modules only contribute top-level {@code fun} and {@code data} declarations, and the
     * interpreter hoists those per block, so prepending each module's source (in dependency
     * order, once) is semantically equivalent to resolving the imports at run time. The
     * import lines themselves are blanked out, keeping line numbers of the main file intact.
     */
    public static String flatten(Path entry, String source) {
        Ast.Program program = Parser.parse(entry.getFileName().toString(), source);
        Set<Path> loaded = new LinkedHashSet<>();
        loaded.add(entry.toAbsolutePath().normalize());
        StringBuilder modules = new StringBuilder();
        collectSources(program, entry.toAbsolutePath().getParent(), loaded, modules, 0);
        String main = blankImports(source);
        return modules + main;
    }

    private static void collectSources(Ast.Program program, Path base, Set<Path> loaded,
                                       StringBuilder out, int depth) {
        if (depth > 50) {
            throw new OqError("module nesting is too deep", Span.synthetic(),
                    "Check for a cycle between modules.");
        }
        for (Ast.Stmt stmt : program.statements()) {
            if (!(stmt instanceof Ast.ImportStmt importStmt)) continue;
            Path file = resolveModule(importStmt.path(), base, importStmt.span());
            Path normalized = file.toAbsolutePath().normalize();
            if (!loaded.add(normalized)) continue;
            Ast.Program module = parseModule(file, importStmt.span());
            collectSources(module, file.getParent(), loaded, out, depth + 1);
            try {
                out.append("# module ").append(file.getFileName()).append('\n');
                out.append(blankImports(Files.readString(file, StandardCharsets.UTF_8)));
                out.append('\n');
            } catch (IOException e) {
                throw new OqError("cannot read module '" + file.getFileName() + "'",
                        importStmt.span(), e.getMessage());
            }
        }
    }

    private static String blankImports(String source) {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            String stripped = line.stripLeading();
            out.append(stripped.startsWith("use \"") ? "" : line).append('\n');
        }
        String result = out.toString();
        return result.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }

    public static Ast.Program resolve(Path entry, Ast.Program program) {
        Set<Path> loaded = new LinkedHashSet<>();
        loaded.add(entry.toAbsolutePath().normalize());
        List<Ast.Stmt> statements = new ArrayList<>();
        collect(program, entry.toAbsolutePath().getParent(), loaded, statements, 0);
        return new Ast.Program(statements, program.sourceName());
    }

    private static void collect(Ast.Program program, Path base, Set<Path> loaded,
                                List<Ast.Stmt> out, int depth) {
        if (depth > 50) {
            throw new OqError("module nesting is too deep", Span.synthetic(),
                    "Check for a cycle between modules.");
        }
        for (Ast.Stmt stmt : program.statements()) {
            if (stmt instanceof Ast.ImportStmt importStmt) {
                Path file = resolveModule(importStmt.path(), base, importStmt.span());
                Path normalized = file.toAbsolutePath().normalize();
                if (!loaded.add(normalized)) continue;
                Ast.Program module = parseModule(file, importStmt.span());
                collect(module, file.getParent(), loaded, out, depth + 1);
                continue;
            }
            out.add(stmt);
        }
    }

    private static Ast.Program parseModule(Path file, Span span) {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OqError("cannot read module '" + file.getFileName() + "'", span, e.getMessage());
        }
        try {
            return Parser.parse(file.getFileName().toString(), source);
        } catch (OqError error) {
            throw new OqError("error in module '" + file.getFileName() + "': " + error.getMessage(),
                    span, error.hint());
        }
    }

    /**
     * Resolves one {@code use "oq:name"} to the file that defines it.
     *
     * <p>Public so every reader of an import follows it through this exact method. {@code oq
     * doc --source} uses it to document a module reached only by import, and {@code oq-lsp}
     * uses it to answer a hover over a name the current file imports. A second copy of the
     * search order would eventually disagree with the interpreter about where a module lives,
     * and the disagreement would show up as a documented name that cannot be called.
     *
     * @param name the module name as written, with or without the {@code .oq} suffix
     * @param base the directory of the importing file, or null to search only the working
     *             directory
     * @param span the import statement, so a failure points at the {@code use} line
     * @throws OqError when no file in the search order defines the module
     */
    public static Path resolveModule(String name, Path base, Span span) {
        List<Path> candidates = new ArrayList<>();
        String fileName = name.endsWith(".oq") ? name : name + ".oq";
        if (base != null) {
            candidates.add(base.resolve(fileName));
            candidates.add(base.resolve("modules").resolve(fileName));
            candidates.add(base.resolve("lib").resolve(fileName));
        }
        candidates.add(Path.of(fileName));
        candidates.add(Path.of("modules").resolve(fileName));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new OqError("cannot find module '" + name + "'", span,
                "Looked for " + fileName + " next to the program, in modules/, and in lib/.");
    }

    /**
     * Runs module resolution for a program that is about to be executed. Returns the
     * merged program so the interpreter never sees an import statement.
     */
    public static Ast.Program load(Interpreter interpreter, Path entry, Ast.Program program) {
        return resolve(entry, program);
    }
}
