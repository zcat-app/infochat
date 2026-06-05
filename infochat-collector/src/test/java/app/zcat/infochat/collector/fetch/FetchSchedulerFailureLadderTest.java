package app.zcat.infochat.collector.fetch;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Timestamp-shape coverage for {@link SourceRepository}'s two UPDATE
 * paths. Pairs with {@link FetchSchedulerFailureLadderIT} — the IT
 * drives the scheduler end-to-end against a mock Fetcher; these
 * tests pin the per-method timestamp semantics directly.
 *
 * <p>Test isolation: every fixture row carries the
 * {@code m1-094-test-} prefix; {@link #cleanup()} deletes only rows
 * matching that prefix so other tests in the same Quarkus JVM
 * instance are unaffected.</p>
 */
@QuarkusTest
class FetchSchedulerFailureLadderTest {

    private static final String PREFIX = "m1-094-test-";

    @Inject
    SourceRepository sourceRepository;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM source WHERE identifier LIKE ?")) {
            ps.setString(1, "https://example.com/" + PREFIX + "%");
            ps.executeUpdate();
        }
    }

    @Test
    void lastFetchAtUpdatedOnEveryTick() throws Exception {
        UUID successId = seedActiveSource("lastFetch-success");
        UUID failureId = seedActiveSource("lastFetch-failure");

        // Both rows boot with last_fetch_at = NULL (V6 default).
        assertNull(readLastFetchAt(successId),
            "seed precondition: last_fetch_at starts NULL");
        assertNull(readLastFetchAt(failureId),
            "seed precondition: last_fetch_at starts NULL");

        sourceRepository.recordSuccess(successId);
        sourceRepository.recordFailure(failureId, 5);

        assertNotNull(readLastFetchAt(successId),
            "recordSuccess must set last_fetch_at");
        assertNotNull(readLastFetchAt(failureId),
            "recordFailure must set last_fetch_at (D42: every tick, success or failure)");
    }

    @Test
    void lastSuccessAtUpdatedOnlyOnSuccess() throws Exception {
        UUID successId = seedActiveSource("lastSuccess-success");
        UUID failureId = seedActiveSourceWithLastSuccessAt(
            "lastSuccess-failure",
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC));

        // Capture the seeded last_success_at so the post-failure
        // assertion compares against a known stamp rather than
        // depending on "non-null" alone.
        OffsetDateTime seededLastSuccess = readLastSuccessAt(failureId);
        assertNotNull(seededLastSuccess,
            "seed precondition: failure-side row has a seeded last_success_at");

        sourceRepository.recordSuccess(successId);
        sourceRepository.recordFailure(failureId, 5);

        assertNotNull(readLastSuccessAt(successId),
            "recordSuccess must set last_success_at");

        OffsetDateTime postFailureLastSuccess = readLastSuccessAt(failureId);
        assertEquals(seededLastSuccess.toInstant(), postFailureLastSuccess.toInstant(),
            "recordFailure must NOT touch last_success_at "
                + "(D42: success-only timestamp)");
    }

    private UUID seedActiveSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedActiveSourceWithLastSuccessAt(String slug, OffsetDateTime lastSuccessAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, last_success_at) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active', ?) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setObject(3, lastSuccessAt);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private OffsetDateTime readLastFetchAt(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT last_fetch_at FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class);
            }
        }
    }

    private OffsetDateTime readLastSuccessAt(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT last_success_at FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class);
            }
        }
    }
}
