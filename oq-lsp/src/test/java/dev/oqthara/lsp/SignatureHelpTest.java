package dev.oqthara.lsp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks signature help over real source text, in the states an editor actually sends.
 *
 * <p>The interesting cases are the half-written ones: a call with no argument yet, a call part
 * way through its argument list, and a call to something that does not exist. Those are the
 * moments a user asks for help, and they are the ones a scanner that assumes a complete
 * expression gets wrong.
 *
 * <p>Most cases are built from {@link #DECLARATIONS} plus one call fragment, because a bare
 * fragment declares nothing and would resolve only through the builtin table. Positions are
 * located by searching for a needle, so the tests survive reformatting.
 */
class SignatureHelpTest {
    private static final String URI = "file:///tmp/signature.oq";

    private static final String DECLARATIONS = """
            fun add(a, b)
              return a + b
            end

            fun pick(first, second, third)
              return first
            end

            """;

    /** The line/character pair of the offset just after {@code needle}. */
    private static int[] after(String source, String needle) {
        int offset = source.indexOf(needle);
        assertTrue(offset >= 0, "needle not found: " + needle + " in:\n" + source);
        offset += needle.length();
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
    }

    private static SignatureHelp.Reply at(String source, String needle) {
        int[] position = after(source, needle);
        return SignatureHelp.signatureHelp(URI, source, position[0], position[1]);
    }

    /** A document with the declarations above followed by {@code call}, cursor after it. */
    private static SignatureHelp.Reply call(String call) {
        return at(DECLARATIONS + call, call);
    }

    @Test
    void anEmptyArgumentListDescribesTheFirstParameter() {
        SignatureHelp.Reply reply = call("print add(");
        assertNotNull(reply);
        assertEquals(1, reply.signatures().size());
        assertEquals("add(a, b)", reply.signatures().get(0).label());
        assertEquals(0, reply.activeParameter());
    }

    @Test
    void aCursorPastACommaDescribesTheSecondParameter() {
        SignatureHelp.Reply reply = call("print add(1, ");
        assertNotNull(reply);
        assertEquals(1, reply.activeParameter());
    }

    @Test
    void aCursorPastTheLastParameterStaysOnItRatherThanGoingOutOfRange() {
        SignatureHelp.Reply reply = call("print add(1, 2, ");
        assertNotNull(reply);
        assertEquals(1, reply.activeParameter(),
                "add has two parameters, so the third position is still the last one");
    }

    @Test
    void aThreeParameterFunctionReportsTheThirdParameter() {
        SignatureHelp.Reply reply = call("print pick(one, two, ");
        assertNotNull(reply);
        assertEquals("pick(first, second, third)", reply.signatures().get(0).label());
        assertEquals(2, reply.activeParameter());
    }

    @Test
    void aBuiltinIsDescribedFromTheInterpreterTable() {
        SignatureHelp.Reply reply = call("print range(");
        assertNotNull(reply);
        // range is registered three times, so every form is offered.
        List<String> labels = reply.signatures().stream().map(SignatureHelp.Signature::label).toList();
        assertTrue(labels.contains("range(stop)"), labels.toString());
        assertTrue(labels.contains("range(start, stop)"), labels.toString());
        assertTrue(labels.contains("range(start, stop, step)"), labels.toString());
    }

    @Test
    void aBuiltinCarriesTheParameterNamesItsBodyUses() {
        SignatureHelp.Reply reply = call("print replace(");
        assertNotNull(reply);
        assertEquals("replace(text, old, new)", reply.signatures().get(0).label());
    }

    @Test
    void aCallToNothingDescribableHasNoReply() {
        assertNull(call("print no_such_function("));
    }

    @Test
    void aParenthesisThatIsNotACallHasNoReply() {
        String source = """
                if (true)
                  print 1
                end
                """;
        assertNull(at(source, "if ("),
                "control flow is not a call and must not be described as one");
    }

    @Test
    void aParenthesisInsideTextDoesNotOpenACall() {
        String source = DECLARATIONS + "print \"not a call (\"\n";
        assertNull(at(source, "not a call ("));
    }

    @Test
    void aParenthesisInsideACommentDoesNotOpenACall() {
        String source = DECLARATIONS + "# see add(1, 2) for an example\nprint 1\n";
        assertNull(at(source, "see add("));
    }

    @Test
    void aMethodCallHasNoReplyBecauseTheReceiverTypeIsUnknown() {
        String source = DECLARATIONS + "print text.slice(0, 1)\n";
        assertNull(at(source, "text.slice("),
                "the receiver's type is not knowable from the text before the dot");
    }

    @Test
    void nestedCallsDescribeTheInnermostOne() {
        SignatureHelp.Reply reply = call("print upper(lower(");
        assertNotNull(reply,
                "the cursor is being typed into the inner call, so that is the call to describe");
        assertEquals("lower(text)", reply.signatures().get(0).label());
    }

    @Test
    void aHalfTypedDocumentStillAnswersFromTheHeaderScan() {
        // The call below never closes, so the document cannot parse. The 'fun add' header above
        // is still readable text, and declining to help here would decline at the exact moment
        // the user asked for it.
        String source = DECLARATIONS + "print add(\n";
        SignatureHelp.Reply reply = at(source, "print add(");
        assertNotNull(reply);
        assertEquals("add(a, b)", reply.signatures().get(0).label());
    }

    @Test
    void aHeaderStillBeingTypedIsSkippedRatherThanGuessed() {
        String source = """
                fun half(name
                  return name
                end

                print half(
                """;
        SignatureHelp.Reply reply = at(source, "print half(");
        assertNull(reply,
                "the parameter list never closed, so there are no parameters to describe");
    }

    @Test
    void aCallInsideTextInterpolationIsNotOffered() {
        // A text literal is masked whole, interpolation included, so the scan does not enter it.
        // Interpolation is a real call -- "value {add(1, 2)}" evaluates -- but it is also the one
        // place a call site sits inside a literal, and treating literal contents as code would
        // mean describing parentheses in ordinary prose. The limitation is recorded in ADR-0044;
        // this test pins the behaviour so it changes deliberately rather than silently.
        String source = DECLARATIONS + "print \"value {add(1, 2)}\"" + "\n";
        assertNull(at(source, "{add("));
    }

    @Test
    void interleavedArgumentListsInOneDocumentAreNotConfused() {
        // A later call on the same line must not be attributed to an earlier one.
        SignatureHelp.Reply reply = call("print add(1, 2) + pick(3, ");
        assertNotNull(reply);
        assertEquals("pick(first, second, third)", reply.signatures().get(0).label());
        assertEquals(1, reply.activeParameter());
    }
}
