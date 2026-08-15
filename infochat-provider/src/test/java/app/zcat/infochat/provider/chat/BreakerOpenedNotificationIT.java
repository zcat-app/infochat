package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerOpenedEvent;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boundary-siting proof (engineering-rules §8): the fired CDI event reaches the real observer
 * and persists an admin_notification_state row through the real notifier and Testcontainers DB —
 * the unit test's recording double cannot observe the wiring or the row. */
@QuarkusTest
class BreakerOpenedNotificationIT {

    @Inject
    Event<LlmCircuitBreakerOpenedEvent> breakerOpenedEvent;

    @Inject
    LlmCircuitBreakerRegistry breakerRegistry;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.embeddings.base-url")
    String embeddingsBaseUrl;

    @Test
    void firedEventPersistsAdminNotificationRow() {
        String endpoint = "http://breaker-opened-it-" + System.nanoTime() + ".test:11434/v1";
        breakerOpenedEvent.fire(new LlmCircuitBreakerOpenedEvent("EMBEDDINGS", endpoint, false));

        Optional<AdminNotificationRecord> row = throttledAdminNotifier.getState(
            "llm-breaker-open:EMBEDDINGS:" + endpoint);
        assertTrue(row.isPresent(),
            "the observer persisted an admin_notification_state row for the fired event");
        assertEquals("llm-breaker-open", row.get().errorClass());
        assertEquals(1, row.get().notificationCount(),
            "one emission within the window: the row records exactly one notification");
    }

    @Test
    void trippingTheInjectedRegistryPersistsAdminNotificationRow() {
        // The production wire end-to-end: the CDI constructor's Event::fire sink,
        // not a hand-fired event — a no-op sink at that seam fails this test.
        breakerRegistry.recordUnreachableForEmbeddings();
        breakerRegistry.recordUnreachableForEmbeddings();
        breakerRegistry.recordUnreachableForEmbeddings();
        try {
            Optional<AdminNotificationRecord> row = throttledAdminNotifier.getState(
                "llm-breaker-open:EMBEDDINGS:" + embeddingsBaseUrl);
            assertTrue(row.isPresent(),
                "the tripped registry fired through the production wire into the observer");
            assertEquals("llm-breaker-open", row.get().errorClass());
        } finally {
            // The registry is @ApplicationScoped and shared across the suite's
            // ITs: close the breaker so no later test inherits an OPEN endpoint.
            breakerRegistry.recordReachableForEmbeddings();
        }
    }
}
