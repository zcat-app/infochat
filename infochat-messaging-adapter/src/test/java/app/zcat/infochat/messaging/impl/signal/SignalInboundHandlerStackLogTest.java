package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the D37-safe inbound-handler stack rendering (M1-358 acceptance
 * item 3): the inbound-handler catches in {@code SignalJsonRpcClient}
 * log a throwing handler's class and stack (class/method/file/line) so a
 * Provider handler bug is localizable from the log, while the exception
 * MESSAGE — which may carry inbound chat-mode body bytes — is suppressed.
 */
class SignalInboundHandlerStackLogTest {

    private static final String SECRET_BODY = "secret-inbound-chat-body-42";
    private static final String SECRET_TOP = "secret-top-body-1";
    private static final String SECRET_MID = "secret-mid-body-2";
    private static final String SECRET_ROOT = "secret-root-body-3";

    @Test
    void stackRenderingCarriesClassAndFramesButNotTheMessage() {
        String rendered = SignalJsonRpcClient.stackWithoutMessage(boom());

        assertTrue(rendered.contains("java.lang.IllegalStateException"),
                "the exception class must be logged so the failure kind is visible");
        assertTrue(rendered.contains("at ") && rendered.contains("boom"),
                "the stack frames (class/method/file/line) must be logged for localization");
        assertFalse(rendered.contains(SECRET_BODY),
                "the exception message may carry inbound body bytes and must be suppressed (D37)");
    }

    @Test
    void stackRenderingIncludesCauseChainFramesButNoMessages() {
        Throwable root = new IllegalArgumentException(SECRET_ROOT);
        Throwable mid = new IllegalStateException(SECRET_MID, root);
        Throwable top = new RuntimeException(SECRET_TOP, mid);

        String rendered = SignalJsonRpcClient.stackWithoutMessage(top);

        assertTrue(rendered.contains("java.lang.RuntimeException")
                        && rendered.contains("java.lang.IllegalStateException")
                        && rendered.contains("java.lang.IllegalArgumentException"),
                "every level's class name must be rendered so a wrapped cause is diagnosable");
        assertEquals(2, occurrences(rendered, "Caused by:"),
                "one 'Caused by:' marker per cause level (2 causes below the top)");
        assertTrue(occurrences(rendered, "\n\tat ") >= 3,
                "each level contributes its own stack frames");
        assertFalse(rendered.contains(SECRET_TOP)
                        || rendered.contains(SECRET_MID)
                        || rendered.contains(SECRET_ROOT),
                "no level's message may leak — D37 suppression applies to the whole chain");
    }

    @Test
    void stackRenderingTerminatesOnCyclicCause() {
        Throwable a = new IllegalStateException(SECRET_TOP);
        Throwable b = new IllegalArgumentException(SECRET_MID);
        a.initCause(b);
        b.initCause(a); // A -> B -> A cycle

        String rendered = SignalJsonRpcClient.stackWithoutMessage(a);

        assertTrue(rendered.contains("java.lang.IllegalStateException")
                        && rendered.contains("java.lang.IllegalArgumentException"),
                "both distinct nodes of the cycle are rendered once");
        assertEquals(1, occurrences(rendered, "Caused by:"),
                "the cycle visits each node once, then terminates (no infinite loop)");
        assertFalse(rendered.contains(SECRET_TOP) || rendered.contains(SECRET_MID),
                "messages stay suppressed even on the cyclic path");
    }

    @Test
    void stackRenderingDepthCapsCauseChain() {
        // 8-deep acyclic chain; the cap is 5 levels, so top + 4 causes render
        // (4 "Caused by:" markers), then a truncation marker; deeper levels omitted.
        Throwable chain = new RuntimeException("depth-sentinel-7");
        for (int i = 6; i >= 0; i--) {
            chain = new RuntimeException("depth-sentinel-" + i, chain);
        }

        String rendered = SignalJsonRpcClient.stackWithoutMessage(chain);

        assertEquals(4, occurrences(rendered, "Caused by:"),
                "at most 5 levels render (top + 4 causes) before the depth cap");
        assertTrue(rendered.contains("cause chain truncated at depth 5"),
                "truncation is explicit, not silent");
        for (int i = 0; i < 8; i++) {
            assertFalse(rendered.contains("depth-sentinel-" + i),
                    "no level's message may leak, rendered or truncated");
        }
    }

    private static IllegalStateException boom() {
        return new IllegalStateException(SECRET_BODY);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i != -1;
                i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
