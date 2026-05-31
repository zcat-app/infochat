package app.zcat.infochat.collector.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-042 item 2: verifies {@link OutboxRehydrator} processes a RAW
 * backlog larger than {@code rehydrate-page-size} without ever
 * allocating an in-memory list of every RAW key at once. Seeds N RAW
 * posts where {@code N > pageSize} (default {@code N = pageSize * 4 + 17})
 * via a single bulk {@code INSERT … SELECT generate_series(…)} so the
 * seed itself does not exercise the rehydrator's pagination — the
 * seed runs as one round trip regardless of N.
 *
 * <p>Two load-bearing assertions:
 * <ol>
 *   <li><b>Completeness.</b> {@link OutboxRehydrator#rehydrate()}
 *       returns at least N, and every seeded post id appears in the
 *       drained {@link TestEvalQueueConsumer} snapshot.</li>
 *   <li><b>Memory-bound.</b> {@link OutboxRehydrator#lastObservedMaxChunkSize}
 *       is &le; the configured {@code rehydrate-page-size}, proving
 *       no single in-flight chunk grew past the configured page
 *       size. Pre-M1-042, this counter would have been the entire
 *       RAW set size — the unbounded-{@code ArrayList} OUT-OF-MODEL
 *       gap that M1-028's redteam flagged.</li>
 * </ol>
 *
 * <p>The test does NOT assert the rehydrator's return value equals
 * exactly N: other ITs in the same Quarkus container (PostPersisterIT,
 * FetchSchedulerIT, OutboxRehydratorIT) write {@code 'RAW'} posts
 * that may still be live in the table at the moment this test runs.
 * The assertion is the inclusion direction (seeded ⊆ processed) plus
 * the chunk-size cap — the rehydrator's job is to process the entire
 * RAW set under the page-size invariant, not to process exactly this
 * test's seed in isolation.
 */
@QuarkusTest
class OutboxRehydratorPaginationIT {

    @Inject
    DataSource dataSource;

    @Inject
    OutboxRehydrator rehydrator;

    @Inject
    TestEvalQueueConsumer consumer;

    @ConfigProperty(name = OutboxRehydrator.CONFIG_KEY_PAGE_SIZE, defaultValue = "500")
    int pageSize;

    @Test
    void rehydratesLargeRawBacklogWithoutUnboundedListAllocation() throws Exception {
        // Drain whatever prior tests / @PostConstruct fires left in
        // the consumer; the post-rehydrate drain must capture only
        // this test's emissions plus any concurrent stragglers.
        consumer.drain();

        UUID sourceUuid = seedRssSource(
            "https://pagination-it.example.test/feed.xml",
            "Pagination IT source");

        // N strictly > pageSize so the rehydrator must paginate. The
        // "+17" tail (not a multiple of pageSize) covers the residual-
        // chunk branch in OutboxRehydrator.rehydrate() — the loop must
        // terminate on the short tail without an extra empty probe.
        final int seedCount = pageSize * 4 + 17;
        Set<UUID> seededIds = bulkInsertRawPosts(sourceUuid, seedCount);
        assertEquals(seedCount, seededIds.size(),
            "bulk seed must produce " + seedCount + " distinct post ids");

        int processed = rehydrator.rehydrate();

        // (a) memory-bound: no single chunk exceeded the page size.
        // This is the load-bearing assertion that proves the
        // unbounded-List allocation was eliminated — pre-M1-042 the
        // counter would have been seedCount + whatever else was
        // RAW in the table (potentially in the thousands or millions
        // in a production stall scenario).
        assertTrue(
            rehydrator.lastObservedMaxChunkSize() <= pageSize,
            "max observed chunk " + rehydrator.lastObservedMaxChunkSize()
                + " must be <= configured page size " + pageSize
                + "; unbounded-List allocation regression");
        // The chunk size must also be > 0 unless the entire RAW set
        // was empty — if it's zero AND we just seeded seedCount rows,
        // the rehydrator quietly skipped them, which is a different
        // bug than unbounded-List.
        assertTrue(
            rehydrator.lastObservedMaxChunkSize() > 0,
            "max chunk must be > 0 when seedCount > 0 was seeded; "
                + "got zero — rehydrator skipped the RAW set entirely");

        // (b) completeness: every seeded id was emitted. The drain
        // happens AFTER rehydrate returns; the eval-queue is an
        // in-memory SmallRye channel so emission is synchronous from
        // the producer's perspective, but the consumer dispatch is
        // posted to a worker — give it a beat to deliver.
        awaitConsumerCovers(seededIds);
        List<PostPersister.PersistedPostKey> drained = consumer.drain();
        Set<UUID> drainedIds = drained.stream()
            .map(PostPersister.PersistedPostKey::id)
            .collect(Collectors.toSet());

        Set<UUID> missing = new HashSet<>(seededIds);
        missing.removeAll(drainedIds);
        assertTrue(missing.isEmpty(),
            "rehydrator must emit every seeded RAW id; missing="
                + missing.size() + " of " + seedCount);

        // (c) the rehydrator's return value (its own count of
        // re-enqueued posts) must be >= seedCount; concurrent ITs
        // may have added more RAW rows, so equality is not asserted.
        assertTrue(
            processed >= seedCount,
            "rehydrator processed=" + processed + " must be >= seeded="
                + seedCount);
    }

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

    /**
     * Seed {@code count} RAW posts in a single round trip via
     * {@code INSERT … SELECT generate_series(…)}. Returns the set of
     * generated row ids. Each row gets a distinct {@code uid} and
     * {@code upstream_identifier} ({@code 'pagit-' || i}) so the
     * UNIQUE {@code (source_id, upstream_identifier, fetched_at)}
     * and UNIQUE {@code (uid, fetched_at)} constraints do not fire,
     * and a strictly-increasing {@code fetched_at} (one millisecond
     * apart) so the rehydrator's {@code ORDER BY (fetched_at, id)}
     * has a well-defined keyset cursor.
     */
    private Set<UUID> bulkInsertRawPosts(UUID sourceUuid, int count) throws Exception {
        final String sql =
            "INSERT INTO post ("
                + "  id, uid, source_id, upstream_identifier, url, title, body, "
                + "  author, published_at, fetched_at, status, "
                + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                + ") SELECT "
                + "  gen_random_uuid(),"
                + "  'pagit-' || i::text,"
                + "  ?::uuid,"
                + "  'urn:pagit:' || i::text,"
                + "  NULL,"
                + "  'pagit title ' || i::text,"
                + "  NULL,"
                + "  NULL,"
                + "  NULL,"
                + "  TIMESTAMPTZ '2026-05-31 00:00:00+00' + (i * INTERVAL '1 millisecond'),"
                + "  'RAW',"
                + "  FALSE, FALSE, FALSE, FALSE,"
                + "  FALSE, FALSE, FALSE,"
                + "  '{}' "
                + "FROM generate_series(1, ?) AS s(i) "
                + "RETURNING id";
        Set<UUID> ids = new HashSet<>(count);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, sourceUuid);
            ps.setInt(2, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add((UUID) rs.getObject(1));
                }
            }
        }
        return ids;
    }

    /**
     * Block until the consumer's received list covers every seeded
     * id, or 10 seconds elapse. The eval-queue dispatch is async at
     * the consumer side; the producer's {@code emitter.send} returns
     * immediately while SmallRye posts the message to a worker.
     */
    private void awaitConsumerCovers(Set<UUID> seededIds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            // Cheap peek: count > seeded.size() means at least
            // seedCount messages have flowed through, even if some
            // are not ours.
            if (consumer.size() >= seededIds.size()) {
                // One more brief sleep so any trailing emissions
                // also land before the drain.
                Thread.sleep(50);
                return;
            }
            Thread.sleep(25);
        }
    }
}
