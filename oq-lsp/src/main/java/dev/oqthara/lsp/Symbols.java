package dev.oqthara.lsp;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Interpreter;
import dev.oqthara.core.Modules;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqCallable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The name lookups hover and signature help share: the builtin table and the module walk.
 *
 * <p>Both answers are built from the interpreter's own tables rather than from a second
 * description of the language, so a hover or a signature cannot disagree with what a call site
 * is actually checked against. Keeping the table in one place also means installing the
 * standard library happens once per server, not once per handler.
 */
final class Symbols {
    private Symbols() {
    }

    /** Matches the interpreter's limit, so a walk never follows a cycle the loader would refuse. */
    static final int MAX_IMPORT_DEPTH = 50;

    /** Built once; installing the standard library has no side effects on the host. */
    private static volatile Map<String, OqCallable> builtins;

    static Map<String, OqCallable> builtins() {
        Map<String, OqCallable> current = builtins;
        if (current == null) {
            synchronized (Symbols.class) {
                current = builtins;
                if (current == null) {
                    Interpreter interpreter = Interpreter.headless();
                    dev.oqthara.stdlib.Stdlib.install(interpreter);
                    current = Map.copyOf(interpreter.builtins());
                    builtins = current;
                }
            }
        }
        return current;
    }

    /**
     * The directory an imported module is resolved against, or null when the document has no
     * file of its own.
     */
    static Path directoryOf(String uri) {
        if (uri == null || !uri.startsWith("file:")) return null;
        try {
            return Path.of(URI.create(uri)).toAbsolutePath().normalize().getParent();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Applies {@code finder} to each imported module in dependency order, returning the first
     * non-null answer.
     *
     * <p>The order is the order the interpreter loads modules in, so a name found in more than
     * one module resolves to the first -- the same name the program itself would see, as far as
     * a lexical pass can tell.
     *
     * <p>Any failure -- a module that moved, a module that no longer parses -- skips that
     * module rather than failing the request. Hover and signature help are hints, and an editor
     * asking about a half-typed document must not receive a fault for asking.
     */
    static <T> T searchImports(Ast.Program program, Path base,
                               Function<Ast.Program, T> finder) {
        return search(program, base, finder, new LinkedHashSet<>(), 0);
    }

    private static <T> T search(Ast.Program program, Path base, Function<Ast.Program, T> finder,
                                Set<Path> seen, int depth) {
        if (depth > MAX_IMPORT_DEPTH) return null;
        for (Ast.Stmt statement : program.statements()) {
            if (!(statement instanceof Ast.ImportStmt importStmt)) continue;
            Path file;
            try {
                file = Modules.resolveModule(importStmt.path(), base, importStmt.span())
                        .toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue;
            }
            if (!seen.add(file)) continue;
            Ast.Program module;
            try {
                module = Parser.parse(file.getFileName().toString(),
                        Files.readString(file, StandardCharsets.UTF_8));
            } catch (RuntimeException | IOException e) {
                continue;
            }
            T found = finder.apply(module);
            if (found != null) return found;
            T nested = search(module, file.getParent(), finder, seen, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }
}
