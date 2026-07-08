package app.zcat.infochat.collector.eval.ready;

import app.zcat.infochat.collector.eval.embedding.EmbeddingMetadataDao;
import app.zcat.infochat.collector.eval.embedding.EmbeddingMetadataStartupGuard;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>The fifth scenario exercises the adopt-on-first-boot,
 * fail-fast-with-vectors, allow-model-change, and identity-match paths
 * of {@link EmbeddingMetadataStartupGuard} by invoking the
 * package-visible {@link EmbeddingMetadataStartupGuard#evaluate} method
 * directly with hand-crafted stored vs configured pairs and an explicit
 * post_embedding-emptiness signal. The production @PostConstruct
 * delegates to the same method, so the contract under test is
 * identical; the IT avoids needing a separate Quarkus boot per
 * scenario.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReadyPromoterIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T11:00:00Z");
    private static final String UID_PREFIX = "ready-it/";

    @Inject
    @SeedDataSource
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
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = conn -> {};
        clearItPosts();
    }

    @AfterEach
    void clearAfter() throws Exception {
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = conn -> {};
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
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = conn -> {
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

    // ---------- 5. startup model identity guard — adopt-on-first-boot, refuse-with-vectors, override-rotate, match-noop ----------

    @Test
    @Order(5)
    void startupModelIdentityGuardAdoptsOnFirstBootButRefusesOnceVectorsExist() throws Exception {
        // Capture the seed row so we can restore it after the test
        // (other ITs depend on the canonical 'nomic-embed-text'/768
        // singleton).
        EmbeddingMetadataDao.Metadata original = metadataDao.readSingleton().orElseThrow(
            () -> new IllegalStateException("V11 should have seeded embedding_metadata"));
        try {
            metadataDao.updateSingleton("alpha", 768);
            Optional<EmbeddingMetadataDao.Metadata> storedAlpha = metadataDao.readSingleton();

            // (a) First boot — post_embedding empty + mismatch → ADOPT.
            // The guard rotates the singleton to the configured identity
            // and returns normally (no re-embed required; nothing is
            // embedded yet). This is the M1-443 fix: a llama.cpp / remote
            // backend whose configured identity differs from V11's seeded
            // Ollama default can now start a fresh DB.
            startupGuard.evaluate(storedAlpha, "beta", 768, false, /* hasEmbeddings */ false);
            EmbeddingMetadataDao.Metadata afterAdopt = metadataDao.readSingleton().orElseThrow();
            assertEquals("beta", afterAdopt.modelIdentifier(),
                "first-boot adopt must rotate model_identifier to the configured value");
            assertEquals(768, afterAdopt.dimension(),
                "first-boot adopt must apply the configured dimension");

            // Reset the singleton to 'alpha' for the with-vectors cases.
            metadataDao.updateSingleton("alpha", 768);
            Optional<EmbeddingMetadataDao.Metadata> storedAlphaAgain = metadataDao.readSingleton();

            // (b) Vectors exist + mismatch + allow=false → fatal. The
            // exception message MUST name both 'alpha' and 'beta' per the
            // spec's "descriptive error referencing the re-embed
            // procedure". This is the original protection, now scoped to
            // the case real vectors exist.
            EmbeddingMetadataStartupGuard.EmbeddingModelMismatchException ex = assertThrows(
                EmbeddingMetadataStartupGuard.EmbeddingModelMismatchException.class,
                () -> startupGuard.evaluate(storedAlphaAgain, "beta", 768, false, /* hasEmbeddings */ true),
                "mismatch with existing vectors and allow=false must throw");
            assertTrue(ex.getMessage().contains("alpha"),
                "error must name the stored model 'alpha': " + ex.getMessage());
            assertTrue(ex.getMessage().contains("beta"),
                "error must name the configured model 'beta': " + ex.getMessage());

            // Stored row must be unchanged after the failure.
            EmbeddingMetadataDao.Metadata afterFail = metadataDao.readSingleton().orElseThrow();
            assertEquals("alpha", afterFail.modelIdentifier(),
                "fail-fast path must NOT rotate embedding_metadata");

            // (c) Vectors exist + mismatch + allow=true → rotate + WARN.
            // The guard rotates the singleton AND returns normally.
            startupGuard.evaluate(storedAlphaAgain, "beta", 768, true, /* hasEmbeddings */ true);
            EmbeddingMetadataDao.Metadata afterOverride = metadataDao.readSingleton().orElseThrow();
            assertEquals("beta", afterOverride.modelIdentifier(),
                "allow-model-change path must rotate model_identifier");
            assertEquals(768, afterOverride.dimension(),
                "allow-model-change path must apply the configured dimension");

            // (d) Identity match → no-op (no throw, no rotation), whether
            // or not vectors exist.
            Optional<EmbeddingMetadataDao.Metadata> storedBeta = metadataDao.readSingleton();
            startupGuard.evaluate(storedBeta, "beta", 768, false, /* hasEmbeddings */ true);
            EmbeddingMetadataDao.Metadata afterNoop = metadataDao.readSingleton().orElseThrow();
            assertEquals("beta", afterNoop.modelIdentifier(),
                "identity match must be a no-op (no rotation)");
            assertEquals(768, afterNoop.dimension(),
                "identity match must leave the dimension unchanged");

            // Empty-singleton gating (acceptance item 2 parenthetical):
            // the absent-row fatal branch is now gated on vectors
            // existing. Pass Optional.empty() directly — no fixture
            // teardown needed since neither sub-case rotates the row.
            //   - no vectors → permit startup (the row is recorded on
            //     first use; nothing to protect).
            startupGuard.evaluate(Optional.empty(), "beta", 768, false, /* hasEmbeddings */ false);
            //   - vectors exist → fatal (hand-cleaned DB: stored vectors
            //     of unknown identity).
            assertThrows(EmbeddingMetadataStartupGuard.EmbeddingModelMismatchException.class,
                () -> startupGuard.evaluate(Optional.empty(), "beta", 768, false, /* hasEmbeddings */ true),
                "empty singleton with existing vectors must stay fatal");
        } finally {
            metadataDao.updateSingleton(original.modelIdentifier(), original.dimension());
        }
    }

    // ---------- 6. forced poller overlap — claim-by-update admits exactly one ----------

    @Test
    @Order(6)
    void forcedOverlap_onlyOnePromotionClaimsTheRawRow() throws Exception {
        // M1-202 item 6: under forced overlap, an overrunning tick cannot be
        // overlapped by the next tick double-picking the same work item. The
        // ReadyPromoter is the work-claiming picker exercised here: its
        // UPDATE ... WHERE status='RAW' is an atomic claim-by-update, so two
        // concurrent promotions of the same RAW post admit exactly one — the
        // second's UPDATE matches zero rows once the winner commits status=READY.
        // (The scheduler-level concurrentExecution=SKIP added to every in-scope
        // @Scheduled job is the primary policy; this proves the claim is also
        // overlap-safe at the SQL level for at least one picker.)
        SeededPost post = seedPickupReadyPost("overlap");

        AtomicInteger claims = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        // The hook runs ONLY on the winning path — after the UPDATE matched a
        // RAW row, before NOTIFY/commit. Holding the transaction open briefly
        // guarantees the second tick is genuinely blocked on the row lock when
        // the winner commits, forcing real overlap rather than incidental
        // serialization. It is invoked once per successful claim, so the count
        // is the number of ticks that actually picked the post.
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = conn -> {
            claims.incrementAndGet();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable tick = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            readyPromoter.promoteOne(post.id, post.fetchedAt);
        };
        Thread first = new Thread(tick, "overlap-tick-1");
        Thread second = new Thread(tick, "overlap-tick-2");
        first.start();
        second.start();
        release.countDown();
        first.join();
        second.join();

        assertEquals(1, claims.get(),
            "exactly one overlapping promotion may claim the RAW row; "
                + "a second claim would mean the picker double-picked under overlap");
        PostSnapshot snap = readPost(post.id);
        assertEquals("READY", snap.status, "the winning claim must leave the post READY");
    }

    // ---------- 7. single clock — ready_at is assigned by the database ----------

    @Test
    @Order(7)
    void promotedReadyAtIsAssignedByTheDatabaseClock() throws Exception {
        SeededPost post = seedPickupReadyPost("db-clock");

        // Observe transaction_timestamp() on the promoting connection
        // while its transaction is still open: now() inside the UPDATE
        // and this SELECT both evaluate to the transaction's start
        // time, so a DB-assigned ready_at must equal the observation
        // EXACTLY at microsecond precision. A JVM-assigned ready_at
        // (Instant.now()) samples a different clock read and cannot
        // collide with the transaction timestamp.
        //
        // The seam is shared with the LIVE scheduler, whose ticks may
        // concurrently promote leftover fixture rows from other ITs in
        // this JVM. The hook therefore reads OUR row's in-transaction
        // ready_at and records the timestamp only when it is non-null —
        // i.e. only inside the transaction that promoted our post —
        // with first-set-wins so a later stray tick (which sees the
        // committed non-null ready_at) cannot overwrite it.
        AtomicReference<Instant> dbTransactionTimestamp = new AtomicReference<>();
        ClientProxy.unwrap(readyPromoter).afterUpdateHook = conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT transaction_timestamp(), ready_at FROM post WHERE id = ?")) {
                ps.setObject(1, post.id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getTimestamp(2) != null) {
                        dbTransactionTimestamp.compareAndSet(null, rs.getTimestamp(1).toInstant());
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("failed to observe transaction_timestamp()", e);
            }
        };

        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_post");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);

            readyPromoter.promoteOne(post.id, post.fetchedAt);

            PostSnapshot snap = readPost(post.id);
            assertEquals("READY", snap.status);
            assertNotNull(dbTransactionTimestamp.get(),
                "the seam must have observed the promoting transaction's timestamp");
            assertEquals(dbTransactionTimestamp.get(), snap.readyAt,
                "ready_at must equal the DB transaction timestamp observed inside the promoting "
                    + "transaction — i.e. produced by the database clock, not Instant.now()");
            assertEquals(snap.readyAt, snap.statusChangedAt,
                "status_changed_at must carry the same DB-assigned timestamp");

            // The NOTIFY payload is built from the UPDATE's RETURNING
            // value, so it must carry the same DB-assigned instant —
            // the Provider-side handler's existence check compares
            // payload.ready_at against the stored column for equality.
            // Poll for OUR post's notification specifically: concurrent
            // scheduler ticks promoting stray fixture rows emit NOTIFYs
            // on the same channel.
            String payload = awaitNotificationForPost(pg, post.id);
            assertNotNull(payload, "the promotion must emit a NOTIFY for the promoted post");
            assertTrue(payload.contains("\"ready_at\":\"" + snap.readyAt.toString() + "\""),
                "payload ready_at must equal the DB-assigned column value: " + payload);
        }
    }

    // ---------- 8. classifier_done gate (M1-597) ----------

    @Test
    @Order(8)
    void classifierNotDone_isNotPromotedUntilClassifierDone() throws Exception {
        // The M1-597 gate: a post that has passed every other stage but has
        // classifier_done=FALSE must NOT be picked up for promotion — else it
        // would reach READY before classification and the classifier's own
        // status='RAW' pickup would exclude it forever, leaving it {unknown}.
        // The gate lives in enumeratePending (not promoteOne, whose WHERE is
        // only status='RAW'), so this asserts via the pickup, like the
        // quarantined-exclusion scenario.
        //
        // Pin the injected Clock so the shared FETCHED_AT (2026-05-16) is
        // inside the scan-window floor — the OTHER scenarios drive promoteOne
        // directly and so are floor-independent, but this one exercises
        // enumeratePending, whose fetched_at floor reads the injected Clock.
        QuarkusMock.installMockForType(
            Clock.fixed(Instant.parse("2026-05-17T00:00:00Z"), ZoneOffset.UTC), Clock.class);
        SeededPost notClassified = seedPost("not-classified", "RAW",
            /* stage1Done */ true, /* stage1Flagged */ false,
            /* stage2Done */ false, /* stage2Failed */ false,
            /* taggerDone */ true, /* entityDone */ true, /* embeddingDone */ true,
            /* classifierDone */ false);

        List<ReadyPromoter.PromotionCandidate> pendingBefore = readyPromoter.enumeratePending(10);
        assertFalse(pendingBefore.stream().anyMatch(c -> c.id().equals(notClassified.id())),
            "a post with classifier_done=FALSE (all other stages done) must NOT be picked up");

        // Flip only classifier_done → the same post now qualifies.
        setClassifierDone(notClassified.id());
        List<ReadyPromoter.PromotionCandidate> pendingAfter = readyPromoter.enumeratePending(10);
        assertTrue(pendingAfter.stream().anyMatch(c -> c.id().equals(notClassified.id())),
            "once classifier_done=TRUE (all other stages done) the post is promotable");
    }

    // ---------- helpers ----------

    private void setClassifierDone(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET classifier_done = TRUE WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    private SeededPost seedPickupReadyPost(String slug) throws Exception {
        return seedPost(slug, "RAW", /* stage1Done */ true, /* stage1Flagged */ false,
            /* stage2Done */ false, /* stage2Failed */ false,
            /* taggerDone */ true, /* entityDone */ true, /* embeddingDone */ true,
            /* classifierDone */ true);
    }

    private SeededPost seedQuarantinedPost(String slug) throws Exception {
        return seedPost(slug, "QUARANTINED", true, true, true, false, true, true, true, true);
    }

    private SeededPost seedStage2FailedReleasePost(String slug) throws Exception {
        // The release-on-stage2-failure=true path leaves the post
        // status='RAW' with stage2_failed=true so it flows through
        // the rest of the pipeline like a BENIGN post.
        return seedPost(slug, "RAW", true, true, false, true, true, true, true, true);
    }

    private SeededPost seedPost(String slug, String status,
                                 boolean stage1Done, boolean stage1Flagged,
                                 boolean stage2Done, boolean stage2Failed,
                                 boolean taggerDone, boolean entityDone,
                                 boolean embeddingDone, boolean classifierDone) throws Exception {
        UUID sourceId = seedRssSource(slug);
        String uid = UID_PREFIX + slug;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed, "
                     + "  tagger_done, entity_done, embedding_done, classifier_done, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, "
                     + "  ?, ?, ?, ?, "
                     + "  ?, ?, ?, ?, FALSE, '{}'"
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
            ps.setBoolean(15, classifierDone);
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

    /**
     * Poll {@code getNotifications} until a notification whose payload
     * carries the given post id arrives OR the bounded wait elapses.
     * Returns that notification's payload, or {@code null} on timeout.
     */
    private String awaitNotificationForPost(PGConnection pg, UUID postId) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        String marker = "\"post_id\":\"" + postId + "\"";
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    if (n.getParameter().contains(marker)) {
                        return n.getParameter();
                    }
                }
            }
        }
        return null;
    }

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }

    private record PostSnapshot(String status, Instant readyAt, Instant statusChangedAt) {
    }
}
