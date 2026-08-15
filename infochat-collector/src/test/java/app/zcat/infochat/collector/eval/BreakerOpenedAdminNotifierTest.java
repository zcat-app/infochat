package app.zcat.infochat.collector.eval;

import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerOpenedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Collector twin of the provider's BreakerOpenedAdminNotifierTest — pins the identical
 * key/message contract (M1-834 P7); the shared DB-row throttle coalesces the two
 * services' emissions under one key. */
class BreakerOpenedAdminNotifierTest {

    private RecordingNotifier notifier;
    private BreakerOpenedAdminNotifier observer;

    @BeforeEach
    void setUp() {
        notifier = new RecordingNotifier();
        observer = new BreakerOpenedAdminNotifier(notifier);
    }

    @Test
    void notifierReceivesCoalescingKeyNamingKindAndEndpoint() {
        observer.onBreakerOpened(new LlmCircuitBreakerOpenedEvent(
            "EMBEDDINGS", "http://embed.test:11434/v1", false));
        assertEquals(1, notifier.notifications.size());
        RecordingNotifier.Notification call = notifier.notifications.get(0);
        assertEquals("llm-breaker-open:EMBEDDINGS:http://embed.test:11434/v1", call.key(),
            "per-endpoint coalescing key: constant error_class, kind, redacted endpoint");
        assertEquals("llm-breaker-open", call.errorClass(),
            "error_class stays the constant scrape token");
        assertTrue(call.message().contains("http://embed.test:11434/v1"),
            "the message names the endpoint");
        assertTrue(call.message().contains("EMBEDDINGS"), "the message names the SPI");
        assertTrue(call.message().contains("error.chat.unavailable"),
            "the message names the user-facing degrade");
    }

    @Test
    void userinfoInEndpointIsRedactedFromKeyAndMessage() {
        observer.onBreakerOpened(new LlmCircuitBreakerOpenedEvent(
            "LLM", "https://user:pass@example-host:11434/v1", true));
        assertEquals(1, notifier.notifications.size());
        RecordingNotifier.Notification call = notifier.notifications.get(0);
        assertFalse(call.key().contains("user:pass"), "the credential never reaches the key");
        assertFalse(call.message().contains("user:pass"), "the credential never reaches the message");
        assertEquals("llm-breaker-open:LLM:https://***@example-host:11434/v1", call.key());
        assertTrue(call.message().contains("https://***@example-host:11434/v1"));
    }

    static final class RecordingNotifier extends ThrottledAdminNotifier {

        record Notification(String key, String errorClass, String message) {}

        final List<Notification> notifications = new ArrayList<>();

        @Override
        public NotifyOutcome notifyOnce(String key, String errorClass, String message) {
            notifications.add(new Notification(key, errorClass, message));
            return NotifyOutcome.EMITTED;
        }
    }
}
