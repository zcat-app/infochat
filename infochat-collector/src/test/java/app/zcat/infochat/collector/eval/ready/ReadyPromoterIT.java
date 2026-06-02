package app.zcat.infochat.collector.eval.ready;

import app.zcat.infochat.collector.eval.embedding.EmbeddingMetadataDao;
import app.zcat.infochat.collector.eval.embedding.EmbeddingMetadataStartupGuard;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link ReadyPromoter} — the Stage-5 RAW → READY
 * transition plus the first {@code pg_notify('new_post', ...)} emit
 * in the codebase.
 *
 * <h2>JDBC LISTEN fixture</h2>
 *
 * <p>The happy-path scenario opens a dedicated JDBC connection,
 * issues {@code LISTEN new_post}, drives the promoter, then polls
 * {@code PGConnection.getNotifications()} with a bounded wait — same
 * shape as {@code NewPostListenerIT}'s fixture in the Provider
 * module. Real Postgres NOTIFY end-to-end, not an in-process mock.
 *
 * <h2>Startup model identity guard</h2>
 *
 * <p>The fifth scenario exercises both the fail-fast and the
 * allow-model-change paths of {@link EmbeddingMetadataStartupGuard}
 * by invoking the package-private {@link
 * EmbeddingMetadataStartupGuard#evaluate} method directly with
 * hand-crafted stored vs configured pairs. The production
 * @PostConstruct delegates to the same method, so the contract under
 * test is identical; the IT avoids needing a separate Quarkus boot
 * per scenario.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReadyPromoterIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T11:00:00Z");
    private static final String UID_PREFIX = "ready-it/";

    @Inject
    DataSource dataSource;

    @Inject
    ReadyPromoter readyPromoter;

    @Inject
    EmbeddingMetadataStartupGuard startupGuard;

    @Inject
    EmbeddingMetadataDao metadataDao;

    @BeforeEach
    void reset() throws Exception {
        // Field writes on a CDI proxy do not reach the contextual
        // instance — same pattern as Stage2WorkerIT.releaseOnStage2Failure.
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = () -> {};
        clearItPosts();
    }

    @AfterEach
    void clearAfter() throws Exception {
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = () -> {};
        clearItPosts();
    }

    // ---------- 1. happy path — UPDATE + NOTIFY ----------

    @Test
    @Order(1)
    void happyPathTransitionsRawToReadyAndEmitsNotify() throws Exception {
        SeededPost post = seedPickupReadyPost("happy");

        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_post");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);

            readyPromoter.promoteOne(post.id, post.fetchedAt);

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "at least one NOTIFY new_post must arrive");
            assertEquals(1, notifications.length, "exactly one NOTIFY per promotion");
            PGNotification n = notifications[0];
            assertEquals("new_post", n.getName());
            String payload = n.getParameter();
            assertTrue(payload.contains("\"post_id\":\"" + post.id + "\""),
                "payload must carry the post_id field: " + payload);
            assertTrue(payload.matches(".*\"ready_at\"\\s*:\\s*\"[^\"]+\".*"),
                "payload must carry an ISO-8601 ready_at field: " + payload);
        }

        PostSnapshot snap = readPost(post.id);
        assertEquals("READY", snap.status);
        assertNotNull(snap.readyAt, "ready_at must be set after promotion");
        assertNotNull(snap.statusChangedAt, "status_changed_at must be set after promotion");
    }

    // ---------- 2. same-transaction rule — failure rolls back UPDATE and NOTIFY ----------

    @Test
    @Order(2)
    void sameTransactionRollsBackBothUpdateAndNotify() throws Exception {
        SeededPost post = seedPickupReadyPost("rollback");
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = () -> {
            throw new RuntimeException("simulated failure between UPDATE and NOTIFY");
        };

        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_post");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);

            // Drive the production entry point: onTick() self-invokes
            // promoteOne — the exact call shape whose missing
            // transaction this ticket fixes. onTick swallows the hook's
            // RuntimeException (logs + moves to the next post), so there
            // is nothing to assertThrows on; the atomicity claim is
            // proven by the post staying RAW and no NOTIFY arriving.
            readyPromoter.onTick();

            // Give Postgres a brief window to deliver any phantom
            // NOTIFY that might have escaped the rollback. The
            // correctness invariant is that NO NOTIFY arrives —
            // getNotifications returns either null or an empty
            // array depending on the driver path, both meaning
            // "nothing delivered".
            PGNotification[] notifications = pg.getNotifications(500);
            assertTrue(notifications == null || notifications.length == 0,
                "no NOTIFY may be observable when the explicit transaction rolled back; got: "
                    + java.util.Arrays.toString(notifications));
        }

        PostSnapshot snap = readPost(post.id);
        assertEquals("RAW", snap.status,
            "UPDATE must roll back: status stays RAW after the simulated mid-transaction failure");
    }

    // ---------- 3. quarantined exclusion ----------

    @Test
    @Order(3)
    void quarantinedPostIsNotPromoted() throws Exception {
        SeededPost post = seedQuarantinedPost("quarantined");

        List<ReadyPromoter.PromotionCandidate> pending = readyPromoter.enumeratePending(10);
        boolean foundQuarantined = pending.stream().anyMatch(c -> c.id().equals(post.id));
        assertFalse(foundQuarantined,
            "QUARANTINED post must be excluded from pickup; pending: " + pending);

        // Sanity: even invoking promoteOne directly is a no-op
        // because the WHERE status='RAW' predicate matches zero
        // rows on a quarantined post.
        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_post");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);
            readyPromoter.promoteOne(post.id, post.fetchedAt);
            PGNotification[] notifications = pg.getNotifications(500);
            assertTrue(notifications == null || notifications.length == 0,
                "no NOTIFY for a QUARANTINED post — the UPDATE matched zero rows; got: "
                    + java.util.Arrays.toString(notifications));
        }
        PostSnapshot snap = readPost(post.id);
        assertEquals("QUARANTINED", snap.status, "status must stay QUARANTINED");
    }

    // ---------- 4. stage2_failed release path — RAW + stage2_failed=true IS promoted ----------

    @Test
    @Order(4)
    void stage2FailedReleasePathIsPromotedToReady() throws Exception {
        SeededPost post = seedStage2FailedReleasePost("stage2-failed");

        readyPromoter.promoteOne(post.id, post.fetchedAt);

        PostSnapshot snap = readPost(post.id);
        assertEquals("READY", snap.status,
            "stage2_failed=true with status='RAW' must STILL be promoted (infra-failure release path)");
        assertNotNull(snap.readyAt);
    }

    // ---------- 5. startup model identity guard — fail-fast AND allow-model-change paths ----------

    @Test
    @Order(5)
    void startupModelIdentityGuardFailsOnMismatchAndRotatesOnOverride() throws Exception {
        // Capture the seed row so we can restore it after the test
        // (other ITs depend on the canonical 'nomic-embed-text'/768
        // singleton).
        EmbeddingMetadataDao.Metadata original = metadataDao.readSingleton().orElseThrow(
            () -> new IllegalStateException("V11 should have seeded embedding_metadata"));
        try {
            metadataDao.updateSingleton("alpha", 768);
            Optional<EmbeddingMetadataDao.Metadata> storedAlpha = metadataDao.readSingleton();

            // Fail-fast path: configured=(beta, 768), allow=false.
            // Exception message MUST mention both 'alpha' and 'beta'
            // per the spec's "descriptive error referencing the
            // re-embed procedure".
            EmbeddingMetadataStartupGuard.EmbeddingModelMismatchException ex = assertThrows(
                EmbeddingMetadataStartupGuard.EmbeddingModelMismatchException.class,
                () -> startupGuard.evaluate(storedAlpha, "beta", 768, false),
                "mismatch with allow=false must throw");
            assertTrue(ex.getMessage().contains("alpha"),
                "error must name the stored model 'alpha': " + ex.getMessage());
            assertTrue(ex.getMessage().contains("beta"),
                "error must name the configured model 'beta': " + ex.getMessage());

            // Stored row must be unchanged after the failure.
            EmbeddingMetadataDao.Metadata afterFail = metadataDao.readSingleton().orElseThrow();
            assertEquals("alpha", afterFail.modelIdentifier(),
                "fail-fast path must NOT rotate embedding_metadata");

            // Allow-model-change path: same mismatch, allow=true.
            // The guard rotates the singleton AND returns normally.
            startupGuard.evaluate(storedAlpha, "beta", 768, true);
            EmbeddingMetadataDao.Metadata afterOverride = metadataDao.readSingleton().orElseThrow();
            assertEquals("beta", afterOverride.modelIdentifier(),
                "allow-model-change path must rotate model_identifier");
            assertEquals(768, afterOverride.dimension(),
                "allow-model-change path must apply the configured dimension");
        } finally {
            metadataDao.updateSingleton(original.modelIdentifier(), original.dimension());
        }
    }

    // ---------- helpers ----------

    private SeededPost seedPickupReadyPost(String slug) throws Exception {
        return seedPost(slug, "RAW", /* stage1Done */ true, /* stage1Flagged */ false,
            /* stage2Done */ false, /* stage2Failed */ false,
            /* taggerDone */ true, /* entityDone */ true, /* embeddingDone */ true);
    }

    private SeededPost seedQuarantinedPost(String slug) throws Exception {
        return seedPost(slug, "QUARANTINED", true, true, true, false, true, true, true);
    }

    private SeededPost seedStage2FailedReleasePost(String slug) throws Exception {
        // The release-on-stage2-failure=true path leaves the post
        // status='RAW' with stage2_failed=true so it flows through
        // the rest of the pipeline like a BENIGN post.
        return seedPost(slug, "RAW", true, true, false, true, true, true, true);
    }

    private SeededPost seedPost(String slug, String status,
                                 boolean stage1Done, boolean stage1Flagged,
                                 boolean stage2Done, boolean stage2Failed,
                                 boolean taggerDone, boolean entityDone,
                                 boolean embeddingDone) throws Exception {
        UUID sourceId = seedRssSource(slug);
        String uid = UID_PREFIX + slug;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed, "
                     + "  tagger_done, entity_done, embedding_done, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, "
                     + "  ?, ?, ?, ?, "
                     + "  ?, ?, ?, FALSE, '{}'"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "ready-it-upstream-" + slug);
            ps.setString(4, "Ready IT title " + slug);
            ps.setString(5, "Ready IT body " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setString(7, status);
            ps.setBoolean(8, stage1Done);
            ps.setBoolean(9, stage1Flagged);
            ps.setBoolean(10, stage2Done);
            ps.setBoolean(11, stage2Failed);
            ps.setBoolean(12, taggerDone);
            ps.setBoolean(13, entityDone);
            ps.setBoolean(14, embeddingDone);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT must yield an id");
                UUID id = (UUID) rs.getObject(1);
                Instant fetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(id, uid, fetchedAt);
            }
        }
    }

    private UUID seedRssSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://ready-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Ready IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearItPosts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private PostSnapshot readPost(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, ready_at, status_changed_at FROM post WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                Instant readyAt = rs.getTimestamp("ready_at") == null
                    ? null : rs.getTimestamp("ready_at").toInstant();
                Instant statusChangedAt = rs.getTimestamp("status_changed_at") == null
                    ? null : rs.getTimestamp("status_changed_at").toInstant();
                return new PostSnapshot(rs.getString("status"), readyAt, statusChangedAt);
            }
        }
    }

    /**
     * Poll {@code getNotifications} until at least {@code minimum}
     * notifications arrive OR the bounded wait elapses. Returns the
     * accumulated array (possibly more than {@code minimum} elements)
     * or fails the test on timeout.
     */
    private PGNotification[] awaitNotifications(PGConnection pg, int minimum) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        List<PGNotification> collected = new ArrayList<>();
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    collected.add(n);
                }
                if (collected.size() >= minimum) {
                    return collected.toArray(new PGNotification[0]);
                }
            }
        }
        return collected.isEmpty() ? null : collected.toArray(new PGNotification[0]);
    }

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }

    private record PostSnapshot(String status, Instant readyAt, Instant statusChangedAt) {
    }
}
