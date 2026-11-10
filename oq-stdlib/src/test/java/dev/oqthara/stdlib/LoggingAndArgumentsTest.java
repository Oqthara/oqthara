package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logging and argument parsing, per ADR-0034.
 *
 * <p>The log tests read the error stream and assert the output stream stayed empty. That split is
 * the decision the ADR is built on, so a test that only checked "the line appeared somewhere"
 * would pass while a program's data stream was being corrupted.
 */
class LoggingAndArgumentsTest {

    /** A fresh interpreter per test: the level is process-wide state and must not leak. */
    private Interpreter host;
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private Interpreter host() {
        if (host == null) {
            PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
            host = new Interpreter(out, err,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), Path.of("."), new MethodResolverImpl());
            Stdlib.install(host);
        }
        return host;
    }

    private Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    /** What the tested call wrote to the error stream. */
    private String errText() {
        host().err().flush();
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private String outText() {
        host().out().flush();
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    // --- logging -------------------------------------------------------------------------

    @AfterEach
    void restoreTheDefaultThreshold() {
        // log_level writes process-wide state, so a test that lowers it would silently starve
        // every test that follows. Restoring it through the builtin exercises the real path.
        if (host != null) {
            host = null;
            outBytes.reset();
            errBytes.reset();
            call("log_level", "info");
        }
    }

    @Test
    void logInfoWritesTheLevelAndTheMessageToTheErrorStream() {
        assertNull(call("log_info", "starting"));
        // println ends the line with the platform separator, exactly as print does, so this
        // asserts the same terminator the runtime produces rather than a Unix one.
        assertEquals("INFO  starting" + System.lineSeparator(), errText());
        assertEquals("", outText(), "the output stream is the program's data and must stay clean");
    }

    @Test
    void aMessageIsRenderedTheWayPrintRendersIt() {
        // Not repr: a string is not quoted, because a log line is for a human.
        call("log_info", "done");
        assertTrue(errText().contains("INFO  done"));
        assertFalse(errText().contains("\"done\""), "log_info must not quote a string");

        call("log_info", 42L);
        assertTrue(errText().endsWith("INFO  42" + System.lineSeparator()),
                "a non-text message is displayed, not quoted");
    }

    @Test
    void debugIsSuppressedAtTheDefaultInfoThreshold() {
        call("log_debug", "hidden");
        assertEquals("", errText(), "the default threshold is info, so debug writes nothing");
    }

    @Test
    void raisingTheLevelAdmitsDebugAndLoweringItSilencesInfo() {
        assertFalse((Boolean) call("log_enabled", "debug"));

        call("log_level", "debug");
        assertTrue((Boolean) call("log_enabled", "debug"));
        call("log_debug", "now visible");
        assertTrue(errText().contains("DEBUG now visible"));

        call("log_level", "none");
        String before = errText();
        call("log_error", "also silent");
        assertEquals(before, errText(), "level none suppresses every level, including error");
        assertFalse((Boolean) call("log_enabled", "error"));
    }

    @Test
    void logLevelRejectsAnUnknownNameInsteadOfGuessing() {
        OqError error = assertThrows(OqError.class, () -> call("log_level", "verbose"));
        assertTrue(error.getMessage().contains("verbose"));
        assertTrue(error.hint() != null && error.hint().contains("debug"),
                "the help must list the accepted names");
    }

    @Test
    void logEnabledRejectsAnUnknownName() {
        assertThrows(OqError.class, () -> call("log_enabled", "loud"));
    }

    // --- argument parsing ----------------------------------------------------------------

    private List<String> words(String... items) {
        return new ArrayList<>(List.of(items));
    }

    private OqMap parse(List<String> arguments, List<String> options) {
        return (OqMap) call("arg_parse", arguments, options);
    }

    @Test
    void aFlagIsReportedOnceHoweverManyTimesItAppears() {
        OqMap result = parse(words("--verbose", "--verbose"), words("verbose"));
        assertEquals(List.of("verbose"), result.get("flags"));
        assertEquals(List.of(), result.get("rest"));
    }

    @Test
    void aTypedValueIsConvertedAndPositionalsAreKept() {
        OqMap result = parse(
                words("--port", "8080", "file.txt", "other.txt"), words("port:int"));
        OqMap values = (OqMap) result.get("values");
        assertEquals(8080L, values.get("port"));
        assertEquals(List.of("file.txt", "other.txt"), result.get("rest"));
    }

    @Test
    void everyDeclaredTypeConverts() {
        OqMap numbers = parse(words("--ratio", "0.5"), words("ratio:number"));
        assertEquals(0.5, ((OqMap) numbers.get("values")).get("ratio"));

        OqMap texts = parse(words("--name", "a=b"), words("name:text"));
        assertEquals("a=b", ((OqMap) texts.get("values")).get("name"));
    }

    @Test
    void anAbsentOptionalValueIsAbsentRatherThanNull() {
        OqMap result = parse(words(), words("port:int?"));
        OqMap values = (OqMap) result.get("values");
        assertFalse(values.containsKey("port"),
                "absence is absence; a null would look like a present option with no value");
    }

    @Test
    void aMissingRequiredOptionIsAnError() {
        OqError error = assertThrows(OqError.class,
                () -> parse(words("file.txt"), words("port:int")));
        assertTrue(error.getMessage().contains("--port"));
    }

    @Test
    void anUnknownOptionNamesTheTokenAndListsWhatWasDeclared() {
        OqError error = assertThrows(OqError.class,
                () -> parse(words("--nope"), words("verbose", "port:int")));
        assertTrue(error.getMessage().contains("--nope"));
        assertTrue(error.hint().contains("--verbose") && error.hint().contains("--port:int"),
                "the help is the only place the caller can learn what was expected");
    }

    @Test
    void anOptionWithNoValueIsAnErrorRatherThanAnEmptyString() {
        OqError error = assertThrows(OqError.class, () -> parse(words("--port"), words("port:int")));
        assertTrue(error.getMessage().contains("--port"));
    }

    @Test
    void aValueOfTheWrongTypeNamesTheValueAndTheExpectation() {
        OqError error = assertThrows(OqError.class,
                () -> parse(words("--port", "abc"), words("port:int")));
        assertTrue(error.getMessage().contains("abc"));
        assertTrue(error.getMessage().contains("whole number"));
    }

    @Test
    void doubleDashEndsOptionParsing() {
        OqMap result = parse(words("--", "--verbose", "-"), words("verbose"));
        assertEquals(List.of(), result.get("flags"));
        assertEquals(List.of("--verbose", "-"), result.get("rest"));
    }

    @Test
    void aLoneDashIsPositionalAndNotAnOption() {
        OqMap result = parse(words("-"), words("verbose"));
        assertEquals(List.of("-"), result.get("rest"));
    }

    @Test
    void declaringTheSameOptionTwiceIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> parse(words(), words("port:int", "port:text")));
        assertTrue(error.getMessage().contains("--port"));
    }

    @Test
    void anUnknownDeclaredTypeIsRefusedBeforeAnyParsing() {
        OqError error = assertThrows(OqError.class,
                () -> parse(words(), words("port:float")));
        assertTrue(error.getMessage().contains("float"));
    }
}
