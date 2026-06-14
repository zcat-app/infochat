package app.zcat.infochat.messaging.impl.signal;

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

    private static IllegalStateException boom() {
        return new IllegalStateException(SECRET_BODY);
    }
}
