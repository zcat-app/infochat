package app.zcat.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rollback contract on {@link NewPostHandler#handle}: the method is
 * {@code @Transactional(rollbackOn = SQLException.class)}, so a
 * {@link SQLException} raised after an in-transaction write must roll that
 * write back rather than commit a partial transaction (M1-487). Without the
 * {@code rollbackOn}, Jakarta's default {@code @Transactional} COMMITS on a
 * thrown checked exception — the latent gap this ticket closes.
 *
 * <p><b>Why a hand-rolled DAO stub, not a Postgres trigger.</b> The sibling
 * {@link QuarantineReviewCursorNotifyAtomicityIT} forces a write to fail via a
 * trigger, but that proves single-transaction-ness, not the {@code rollbackOn}
 * decision: any DB-level error already aborts the whole Postgres transaction,
 * so a trigger-induced failure rolls back under the DEFAULT annotation too
 * (commit-of-an-aborted-transaction degrades to rollback). To isolate the
 * interceptor's commit-vs-rollback choice we need a write that SUCCEEDS at the
 * DB and leaves the transaction healthy, followed by a Java-thrown
 * {@code SQLException} the database never sees. {@code handle}'s body has only
 * one write today ({@code advanceCursor}) and the T1-F pre-cursor write is out
 * of scope, so the test stubs the DAO to perform a real enlisted cursor write
 * and then throw. (Mockito is intentionally absent from the Provider
 * classpath, so the stub is a hand-rolled {@link ProviderStateDao} subclass
 * installed via {@link QuarkusMock}.)
 *
 * <p><b>The enlisted write must share the handler's transaction.</b> The stub
 * issues its UPDATE through the SAME default (service-role) {@link DataSource}
 * {@code NewPostHandler} injects, on the same thread, inside the
 * {@code @Transactional} boundary {@code handle} opened — so the connection
 * enlists in that transaction and follows its commit/rollback fate (the
 * documented invariant on {@link ProviderStateDao#advanceCursor}). The
 * {@code @SeedDataSource} (owner-role) connection is used only for committed
 * seeding and verification reads, never for the in-transaction write.
 *
 * <p>{@link #enlistedWriteCommitsWhenHandlerReturnsNormally()} is the positive
 * control: it proves the stubbed write is real and enlisted (it persists on a
 * clean commit), so the rollback assertion in
 * {@link #sqlExceptionAfterEnlistedWriteRollsBackTheTransaction()} cannot pass
 * vacuously against a write that never happened.
 */
@QuarkusTest
class NewPostHandlerRollbackIT {

    private static final String TEST_UID_PREFIX = "handler-rollback-it/";
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");
    private static final Instant READY_AT = Instant.parse("2026-05-15T18:00:00Z");

    @Inject
    @SeedDataSource
    DataSource seedDataSource;

    /**
     * The default (service-role) datasource {@code NewPostHandler} itself
     * injects. The stub's enlisted write goes through THIS so it joins the
     * handler's transaction; a write through {@code @SeedDataSource} (a
     * separate owner-role pool) would commit independently and defeat the test.
     */
    @Inject
    DataSource serviceDataSource;

    @Inject
    NewPostHandler newPostHandler;

    private UUID testSourceId;

    @BeforeEach
    void setUp() throws Exception {
        clearAllItPosts();
        resetNewPostCursor();
        testSourceId = ensureTestSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearTestPosts();
    }

    @Test
    void sqlExceptionAfterEnlistedWriteRollsBackTheTransaction() throws Exception {
        SeededRow seeded = seedReadyRow();
        QuarkusMock.installMockForType(
                new EnlistedWriteThenFailDao(serviceDataSource, seeded.id(), seeded.readyAt(), true),
                ProviderStateDao.class);

        assertThrows(SQLException.class,
                () -> newPostHandler.handle(seeded.id(), seeded.readyAt()),
                "the SQLException thrown after the enlisted cursor write must propagate "
                        + "out of handle, not be swallowed");

        assertEquals("", readCursorLowId(),
                "rollbackOn=SQLException must undo the in-transaction cursor write; a "
                        + "non-empty cursor_low_id here means the @Transactional regressed to "
                        + "Jakarta's default commit-on-checked-exception semantics and committed "
                        + "a partial transaction");
    }

    @Test
    void enlistedWriteCommitsWhenHandlerReturnsNormally() throws Exception {
        SeededRow seeded = seedReadyRow();
        QuarkusMock.installMockForType(
                new EnlistedWriteThenFailDao(serviceDataSource, seeded.id(), seeded.readyAt(), false),
                ProviderStateDao.class);

        assertTrue(newPostHandler.handle(seeded.id(), seeded.readyAt()),
                "handle must return the DAO's advanced=true on the happy path");

        assertEquals(seeded.id().toString(), readCursorLowId(),
                "the enlisted cursor write must persist when the transaction commits — this "
                        + "positive control proves the rollback test's write is real and enlisted, "
                        + "not a silent no-op that would make the rollback assertion pass vacuously");
    }

    // ---- Stub ----

    /**
     * Stands in for {@link ProviderStateDao} under {@code NewPostHandler}.
     * Its {@code advanceCursor} performs a real cursor UPDATE through the
     * injected default (service-role) datasource — so the write enlists in the
     * caller's JTA transaction — then optionally throws a {@link SQLException}
     * to drive the {@code rollbackOn} path. The inherited {@code dataSource}
     * field is never used (advanceCursor is fully overridden and readCursor is
     * never called), so leaving it unset is safe.
     */
    private static final class EnlistedWriteThenFailDao extends ProviderStateDao {
        private final DataSource serviceDataSource;
        private final UUID postId;
        private final Instant readyAt;
        private final boolean throwAfterWrite;

        EnlistedWriteThenFailDao(DataSource serviceDataSource, UUID postId, Instant readyAt,
                                 boolean throwAfterWrite) {
            this.serviceDataSource = serviceDataSource;
            this.postId = postId;
            this.readyAt = readyAt;
            this.throwAfterWrite = throwAfterWrite;
        }

        @Override
        public boolean advanceCursor(String channel, Instant newHigh, String newKind, String newId)
                throws SQLException {
            try (Connection conn = serviceDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE provider_state "
                         + "   SET cursor_high = ?, "
                         + "       cursor_low_kind = ?, "
                         + "       cursor_low_id = ?, "
                         + "       updated_at = now() "
                         + " WHERE channel = ?")) {
                ps.setTimestamp(1, Timestamp.from(readyAt));
                ps.setString(2, NewPostHandler.CURSOR_LOW_KIND_POST);
                ps.setString(3, postId.toString());
                ps.setString(4, NewPostHandler.CHANNEL_NEW_POST);
                ps.executeUpdate();
            }
            if (throwAfterWrite) {
                throw new SQLException(
                        "forced post-write failure to exercise rollbackOn=SQLException");
            }
            return true;
        }
    }

    // ---- Helpers ----

    private String readCursorLowId() throws Exception {
        try (Connection conn = seedDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT cursor_low_id FROM provider_state WHERE channel = ?")) {
            ps.setString(1, NewPostHandler.CHANNEL_NEW_POST);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the new_post provider_state row must exist");
                return rs.getString("cursor_low_id");
            }
        }
    }

    private SeededRow seedReadyRow() throws Exception {
        try (Connection conn = seedDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, status, fetched_at, ready_at, "
                     + "upstream_identifier) "
                     + "VALUES (?, ?, ?, 'READY', ?, ?, ?) RETURNING id")) {
            String uid = TEST_UID_PREFIX + UUID.randomUUID();
            ps.setString(1, uid);
            ps.setObject(2, testSourceId);
            ps.setString(3, "handler-rollback-it post");
            ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(5, Timestamp.from(READY_AT));
            ps.setString(6, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT … RETURNING must yield the new id");
                return new SeededRow(rs.getObject("id", UUID.class), READY_AT);
            }
        }
    }

    private void clearTestPosts() throws Exception {
        try (Connection conn = seedDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, TEST_UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private void clearAllItPosts() throws Exception {
        try (Connection conn = seedDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE '%-it/%'")) {
            ps.executeUpdate();
        }
    }

    private void resetNewPostCursor() throws Exception {
        try (Connection conn = seedDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE provider_state "
                     + "   SET cursor_high = 'epoch'::TIMESTAMPTZ, "
                     + "       cursor_low_kind = '', "
                     + "       cursor_low_id = '', "
                     + "       updated_at = now() "
                     + " WHERE channel = 'new_post'")) {
            ps.executeUpdate();
        }
    }

    private UUID ensureTestSource() throws Exception {
        try (Connection conn = seedDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category) "
                     + "VALUES ('rss', 'handler-rollback-it://test', 'handler-rollback-it', 'news') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE "
                     + "SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test source upsert must yield an id");
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private record SeededRow(UUID id, Instant readyAt) {}
}
