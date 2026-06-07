package app.zcat.infochat.collector.eval.tagger;

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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sleep-before-retry backoff on {@link TaggerWorker}'s
 * LLM-unreachable retry arm: when {@code provider.generate} throws (the
 * rate-limited 429/503 shape), the single same-prompt retry must wait
 * at least the configured {@code infochat.llm.retry-backoff-ms}. The
 * schema-garbage / zero-valid retry arms are out of band and keep
 * their immediate re-issue (no sleep call exists on those paths).
 *
 * <p>The row is constructed directly (not seeded): the bootstrap
 * fallback's cursor UPDATE matches zero rows, which is harmless, so
 * the stub call count + elapsed time are the observable surface.
 */
@QuarkusTest
class TaggerWorkerBackoffTest {

    @Inject
    TaggerWorker taggerWorker;

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
     * The double-unreachable run below takes the bootstrap-fallback
     * path, which fires the {@code ThrottledAdminNotifier} on
     * {@link TaggerWorker#ERROR_CLASS_TAGGER_FALLBACK}. The notifier's
     * state is DB-persistent across tests in the shared Quarkus
     * instance, and {@code TaggerWorkerTest} asserts EMPTY state for
     * the same key — delete this test's row so it observes a clean
     * slate (same per-key cleanup as
     * {@code ReEvaluationJobScheduledPathIT}).
     */
    @AfterEach
    void cleanupNotifierState() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
            ps.executeUpdate();
        }
    }

    @Test
    void unreachableRetrySleepsConfiguredBackoffBeforeSingleRetry() {
        stub().failAll();
        TaggerWorker.PostRow row = new TaggerWorker.PostRow(
            UUID.randomUUID(), Instant.parse("2026-06-07T12:00:00Z"),
            "backoff-test title", "backoff-test body", List.of("bootstrap-tag"));

        long startNanos = System.nanoTime();
        taggerWorker.processOne(row);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(2, stub().callCount(),
            "the unreachable arm must retry exactly once");
        assertTrue(elapsedMs >= backoffMs,
            "expected >= " + backoffMs + " ms backoff between attempt 1 and attempt 2; elapsed "
                + elapsedMs + " ms");
    }
}
