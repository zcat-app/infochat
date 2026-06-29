package app.zcat.infochat.collector.stream;

import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link app.zcat.infochat.core.ingest.StreamSource#stop()}
 * drain-to-outbox contract end-to-end: a source holding in-flight events
 * flushes them through the real outbox-writing {@code deliver} callback
 * ({@link PostPersister}) when the supervisor drains it, rather than
 * dropping them (architecture.md §Ingest SPIs, "Drain on shutdown").
 *
 * <p>The supervisor unit test ({@code StreamSourceSupervisorTest}) already
 * proves stop() flushes to an in-memory sink; this IT closes the loop by
 * wiring the production sink — {@code PostPersister.persist} → a
 * {@code post} row at {@code status='RAW'} — and asserting the rows are
 * absent before the drain and present after, so "drained, not dropped" is
 * verified against the durable outbox.</p>
 */
@QuarkusTest
class StreamSourceStopDrainIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PostPersister postPersister;

    @Inject
    StreamSourceSupervisor supervisor;

    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    @Test
    void stopDrainsInFlightEventsToOutboxRatherThanDropping() throws Exception {
        UUID sourceUuid = seedRssSource(
                "https://stop-drain-it.example.test/feed.xml", "Stop-drain IT source");

        // Two events sit buffered in the source; FakeStreamSource flushes
        // them through the deliver callback only when stop() runs.
        List<NormalizedPost> buffered = List.of(
                bufferedPost("urn:stop-drain-it:post:1"),
                bufferedPost("urn:stop-drain-it:post:2"));
        FakeStreamSource source = FakeStreamSource.flushingOnStop(buffered);

        StreamDispatchKey key = new StreamDispatchKey(515492L);
        // The production deliver callback: persist each delivered post to
        // the outbox at status='RAW' (mirrors NostrStreamSource's wiring).
        supervisor.register(key, "spec", source,
                post -> postPersister.persist(sourceUuid, post));
        try {
            assertTrue(source.startEntered.await(2, TimeUnit.SECONDS), "worker started in-container");

            // In-flight: buffered events are NOT in the outbox until stop()
            // flushes them, so "drained, not dropped" is non-vacuous.
            assertEquals(0, countRawPosts(sourceUuid), "events are still in-flight before drain");

            Map<StreamDispatchKey, Boolean> outcomes = supervisor.drainAll(Duration.ofSeconds(5));

            assertEquals(Boolean.TRUE, outcomes.get(key), "source flushed within the drain budget");
            assertEquals(2, countRawPosts(sourceUuid),
                    "both in-flight events drained to the outbox at status='RAW'");
            assertEquals(0L, supervisor.eventsLostOnShutdown(key), "a clean drain loses no events");
        } finally {
            // Remove the test registration so the @PreDestroy shutdown drain
            // does not re-run against it.
            supervisor.stop(key);
        }
    }

    private static NormalizedPost bufferedPost(String upstreamIdentifier) {
        return new NormalizedPost(
                1L, upstreamIdentifier, "Stop-drain title", "Stop-drain body",
                "https://stop-drain-it.example.test/posts/" + upstreamIdentifier,
                null, FETCHED_AT, Map.of());
    }

    /** Counts RAW post rows persisted for one source. */
    private int countRawPosts(UUID sourceUuid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM post WHERE source_id = ? AND status = 'RAW'")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Insert a fresh source row dedicated to this test; returns its UUID. */
    private UUID seedRssSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}') "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
