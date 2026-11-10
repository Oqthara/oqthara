package dev.oqthara.cli;

import dev.oqthara.runtime.Errors;
import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailsTest {
    @Test
    void anErrorInTheProgramQuotesItsOwnLine() {
        String rendered = render("main.oq", "main.oq", "print x\n");
        assertTrue(rendered.contains("1 | print x"), rendered);
    }

    @Test
    void anErrorInAnImportedModuleDoesNotQuoteTheMainProgram() {
        String rendered = render("module.oq", "main.oq",
                "print this line belongs to the main program\n");
        assertTrue(rendered.contains("--> module.oq:1:1"), rendered);
        assertFalse(rendered.contains("belongs to the main program"),
                "a module position must not quote the main program's text: " + rendered);
    }

    @Test
    void aMissingSourceStillShowsThePosition() {
        String rendered = render("main.oq", null, null);
        assertTrue(rendered.contains("--> main.oq:1:1"), rendered);
    }

    private String render(String spanFile, String sourceName, String source) {
        OqError error = new OqError("boom", Errors.span(spanFile, 1L, 1L));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Details.render(err, error, source, sourceName, false);
        err.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
