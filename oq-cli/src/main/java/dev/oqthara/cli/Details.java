package dev.oqthara.cli;

import dev.oqthara.core.Diagnostics;
import dev.oqthara.runtime.Errors;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.io.PrintStream;

final class Details {
    private Details() {
    }

    /**
     * The same render, told which file {@code source} came from.
     *
     * <p>An error raised inside an imported module carries that module's span. Quoting the
     * main program's text under a module position would show the user a line they never wrote
     * in the place the compiler claimed the failure was, so the excerpt is drawn only when the
     * two agree. The position line names the real file either way.
     */
    static void render(PrintStream err, OqError error, String source, String sourceName,
                       boolean color) {
        boolean sameFile = source != null && sourceName != null
                && Errors.sourceName(sourceName).equals(error.span().source());
        Diagnostics.print(err, error, sameFile ? Diagnostics.lines(source) : null, color);
    }

    static OqError plain(PrintStream err, String message) {
        return new OqError(message, Span.synthetic());
    }
}
