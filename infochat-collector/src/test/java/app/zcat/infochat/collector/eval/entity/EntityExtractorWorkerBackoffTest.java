package app.zcat.infochat.collector.eval.entity;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sleep-before-retry backoff on
 * {@link EntityExtractorWorker}'s UNREACHABLE retry arm: when
 * {@code provider.generate} throws (the rate-limited 429/503 shape),
 * the single retry must wait at least the configured
 * {@code infochat.llm.retry-backoff-ms}. The SCHEMA_VIOLATING retry
 * arm is out of band and keeps its immediate re-issue (no sleep call
 * exists on that path).
 *
 * <p>The row is constructed directly (not seeded): the failure-release
 * path's {@code entity_done} UPDATE matches zero rows, which is
 * harmless, so the stub call count + elapsed time are the observable
 * surface.
 */
@QuarkusTest
class EntityExtractorWorkerBackoffTest {

    @Inject
    EntityExtractorWorker entityExtractorWorker;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    LlmProvider llmProvider;

    @ConfigProperty(name = "infochat.llm.retry-backoff-ms")
    long backoffMs;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() {
        stub().reset();
    }

    /**
     * The double-unreachable run below takes the failure-release path,
     * which fires the {@code ThrottledAdminNotifier} on
     * {@link EntityExtractorWorker#ERROR_CLASS_ENTITY_EXTRACTION_FAILURE}.
     * The notifier's state is DB-persistent across tests in the shared
     * Quarkus instance — delete this test's row so sibling tests
     * observing the same key start from a clean slate (same per-key
     * cleanup as {@code ReEvaluationJobScheduledPathIT}).
     */
    @AfterEach
    void cleanupNotifierState() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, EntityExtractorWorker.ERROR_CLASS_ENTITY_EXTRACTION_FAILURE);
            ps.executeUpdate();
        }
    }

    @Test
    void unreachableRetrySleepsConfiguredBackoffBeforeSingleRetry() {
        stub().failAll();
        EntityExtractorWorker.PostRow row = new EntityExtractorWorker.PostRow(
            UUID.randomUUID(), Instant.parse("2026-06-07T12:00:00Z"),
            "backoff-test title", "backoff-test body");

        long startNanos = System.nanoTime();
        entityExtractorWorker.processOne(row);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(2, stub().callCount(),
            "the unreachable arm must retry exactly once");
        assertTrue(elapsedMs >= backoffMs,
            "expected >= " + backoffMs + " ms backoff between attempt 1 and attempt 2; elapsed "
                + elapsedMs + " ms");
    }
}
