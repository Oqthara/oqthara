package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The one place a user-supplied path becomes a {@link Path}.
 *
 * <p>This exists because the same nine lines were written four times and only one of the copies
 * caught {@link InvalidPathException}. {@code Path.of} throws it for text it cannot parse — a NUL
 * being the reachable case — and an uncaught copy does not fail at the call site: it escapes the
 * interpreter, which reports "internal error ... This is a bug in Oqthara" and exits 70 for a
 * program that merely passed bad text. Twenty-six builtins did exactly that.
 *
 * <p>Four copies with one guard is the defect; the guard is only the symptom. New path builtins
 * call this and nothing else, so the next copy cannot drift. There is deliberately no overload
 * that skips the parse: {@link #lexical} is the one variant, and its name says why it exists.
 */
final class OqPaths {
    private OqPaths() {}

    /**
     * A path parsed for its text alone, never resolved against the working directory.
     *
     * <p>{@code path_name}, {@code path_parent} and {@code path_join} answer questions about the
     * spelling of a path, so joining them to a base would change the answer. They still have to
     * refuse text that is not a path, which is the half worth sharing.
     */
    static Path lexical(Object value, String builtin, Span span) {
        return lexical(value, builtin, span, "path");
    }

    /**
     * As {@link #lexical}, but the diagnostic names the kind of path expected.
     *
     * <p>The three database builtins, {@code db_query}, {@code db_execute} and
     * {@code db_transaction}, say "needs a database path" because "needs a path" reads oddly for
     * something that is really a connection. They all reach this through
     * {@code DatabaseModule.withConnection}, so the code is the list. The wording differs; the
     * parse and the guard do not, which is the point of routing them here rather than leaving
     * another copy in place.
     */
    static Path lexical(Object value, String builtin, Span span, String noun) {
        try {
            return Path.of(Values.display(value));
        } catch (InvalidPathException e) {
            throw refused(value, builtin, span, noun, e);
        }
    }

    /**
     * A path parsed and made absolute against the interpreter's working directory.
     *
     * <p>Resolving against the host's directory rather than the process's means {@code oq run}
     * and a generated jar behave identically wherever the process was started.
     */
    static Path resolve(OqHost host, Object value, String builtin, Span span) {
        Path path = lexical(value, builtin, span);
        if (path.isAbsolute()) return path;
        Path base = host.workingDirectory();
        if (base == null) base = Path.of("").toAbsolutePath();
        return base.toAbsolutePath().resolve(path).normalize();
    }

    /**
     * The message every path builtin gives for text that is not a path.
     *
     * <p>Names the builtin so the diagnostic points at the call the user wrote, and never says
     * "internal error": the whole failure being repaired was that a user mistake was reported as
     * an Oqthara bug.
     */
    static OqError refused(Object value, String builtin, Span span, String noun) {
        return new OqError(builtin + "() needs a " + noun + ", and '" + Values.display(value)
                + "' is not one", span,
                builtin + "() needs a name, not empty text.");
    }

    private static OqError refused(Object value, String builtin, Span span, String noun,
            InvalidPathException cause) {
        return new OqError(builtin + "() needs a " + noun + ", and '" + Values.display(value)
                + "' is not one", span,
                "A path is text without a NUL character. (" + cause.getMessage() + ")");
    }
}
