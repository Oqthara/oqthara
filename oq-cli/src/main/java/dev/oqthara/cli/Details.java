package dev.oqthara.cli;

import dev.oqthara.core.Diagnostics;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.io.PrintStream;

final class Details {
    private Details() {
    }

    static void render(PrintStream err, OqError error, String source, boolean color) {
        Diagnostics.print(err, error, source == null ? null : Diagnostics.lines(source), color);
    }

    static OqError plain(PrintStream err, String message) {
        return new OqError(message, Span.synthetic());
    }
}
