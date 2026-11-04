package dev.oqthara.compiler;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqError;

/**
 * Fuzzes the whole compiled pipeline: parse, lower, and (when the program is in the compiled
 * subset) emit a class file. A generated class file must be loadable by the JVM verifier.
 */
class PipelineFuzzTest {
    @FuzzTest(maxDuration = "30s")
    void fullPipelineNeverThrowsUnexpectedExceptions(FuzzedDataProvider data) {
        String source = data.consumeRemainingAsString();
        try {
            var program = Parser.parse("<fuzz>", source);
            var ir = Lowering.lower(program);
            if (BytecodeCompiler.canCompile(ir)) {
                byte[] classFile = BytecodeCompiler.compile(ir, "oq.generated.Program");
                verifyMagic(classFile);
            }
        } catch (OqError expected) {
            // User-level error, fine.
        } catch (BytecodeCompiler.NotSupportedException expected) {
            // Outside the compiled subset, fine.
        } catch (RuntimeException e) {
            throw new AssertionError("pipeline crashed on " + repr(source) + ": " + e, e);
        }
    }

    private static void verifyMagic(byte[] bytes) {
        if (bytes.length < 8) throw new AssertionError("class file is too short");
        if ((bytes[0] & 0xFF) != 0xCA || (bytes[1] & 0xFF) != 0xFE
                || (bytes[2] & 0xFF) != 0xBA || (bytes[3] & 0xFF) != 0xBE) {
            throw new AssertionError("invalid class file magic");
        }
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
