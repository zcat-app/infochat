package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U-19: the D42 per-source failure ladder counts ONLY
 * {@code fetcher.fetch()} failures. After the split, a tick whose
 * fetch succeeds but whose persist/enqueue throws (a Collector-side
 * DB/channel fault) must NOT increment the source's ladder — otherwise
 * one partition/DB fault would flip every active source to terminal
 * {@code 'failed'}, each needing a manual {@code /source-enable}.
 *
 * <p>Mechanism: a mock {@link RssFetcher} returns one
 * {@link NormalizedPost} (fetch succeeds), and a mock
 * {@link PostPersister} throws on {@code persist} (the documented
 * {@code IllegalStateException} on JDBC failure). The test asserts the
 * ladder counter and status are untouched, the fetch-ladder
 * notification never fires, and a distinct persist-failure
 * notification does.
 */
@QuarkusTest
class FetchSchedulerPersistFailureIT {

    private static final String PREFIX = "m1-295-persist-it-";
    private static final String LADDER_KEY_PREFIX = "fetch_failure_ladder:";
    private static final String PERSIST_KEY_PREFIX = "fetch_persist_failure:";

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @BeforeEach
    void setupAndCleanup() throws Exception {
        // Fetch succeeds (returns one post); persist throws.
        QuarkusMock.installMockForType(new OnefPostFetcher(), RssFetcher.class,
            new FetcherKind.Literal("rss"));
        QuarkusMock.installMockForType(new ThrowingPostPersister(), PostPersister.class);

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM admin_notification_state WHERE notification_key LIKE ?")) {
                ps.setString(1, "%" + PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM source WHERE identifier LIKE ?")) {
                ps.setString(1, "https://example.com/" + PREFIX + "%");
                ps.executeUpdate();
            }
        }
    }

    @Test
    void persistFailureLeavesLadderUntouched() throws Exception {
        UUID sourceId = seedActiveSource("ladderUntouched");
        FetchScheduler.SourceRow row = new FetchScheduler.SourceRow(
            sourceId, "https://example.com/" + PREFIX + "ladderUntouched", 1L, "rss");

        // Many ticks: each fetch succeeds, each persist throws. If the
        // persist failure fed the ladder this would cross the threshold;
        // the assertions below prove it does not.
        for (int i = 0; i < 8; i++) {
            fetchScheduler.tickOnce(row);
        }

        assertEquals(0, readConsecutiveFailures(sourceId),
            "a persist/enqueue failure must NOT increment the D42 ladder counter");
        assertEquals("active", readStatus(sourceId),
            "a persist/enqueue failure must NOT flip the source to status='failed'");

        Optional<AdminNotificationRecord> ladder =
            throttledAdminNotifier.getState(LADDER_KEY_PREFIX + sourceId);
        assertTrue(ladder.isEmpty(),
            "the fetch-ladder notification must NOT fire for a persist/enqueue failure");

        Optional<AdminNotificationRecord> persist =
            throttledAdminNotifier.getState(PERSIST_KEY_PREFIX + sourceId);
        assertTrue(persist.isPresent(),
            "a persist/enqueue failure must surface its own admin notification");
        assertEquals("fetch_persist_failure", persist.get().errorClass(),
            "the persist-failure notification must carry its own error_class");
    }

    private UUID seedActiveSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active', 0) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String readStatus(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int readConsecutiveFailures(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT consecutive_failures FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Fetch succeeds, returning exactly one post so persist is reached. */
    private static final class OnefPostFetcher extends RssFetcher {
        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            return List.of(new NormalizedPost(
                dispatchKey,
                "m1-295-persist-upstream",
                "Persist IT post",
                "body",
                "https://example.com/post",
                Instant.parse("2026-06-11T09:00:00Z"),
                Instant.parse("2026-06-11T09:00:00Z"),
                Map.of()));
        }
    }

    /** Persist always throws — the documented JDBC-failure exception. */
    private static final class ThrowingPostPersister extends PostPersister {
        @Override
        public Optional<PostPersister.PersistedPostKey> persist(UUID sourceUuid, NormalizedPost normalized) {
            throw new IllegalStateException(
                "test-injected persist/enqueue DB failure for source " + sourceUuid);
        }
    }
}
