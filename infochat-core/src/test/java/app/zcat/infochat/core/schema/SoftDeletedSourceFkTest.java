package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant 4 at the schema layer.
 *
 * <p>The FKs {@code source_subscription.source_id → source(id)} and
 * {@code post.source_id → source(id)} intentionally carry NO
 * {@code ON DELETE CASCADE}. After {@code UPDATE source SET
 * deleted_at = now()} (the only soft-delete path; service roles do
 * not hold DELETE on source), the source row's {@code id} is
 * unchanged, so any in-flight ingest run that fetched a batch BEFORE
 * the soft-delete completed must still be able to write its post
 * rows. The application-tier scheduler's
 * {@code WHERE deleted_at IS NULL} filter is what stops NEW fetches;
 * in-flight writes are not rejected.
 *
 * <p>A casual reader might assume "if a source is soft-deleted, all
 * writes against it should be rejected." The schema deliberately
 * does NOT enforce that — this test documents the property as a
 * regression guard.
 */
class SoftDeletedSourceFkTest extends PostgresSchemaTestBase {

    private static final String IN_RANGE_FETCHED_AT = "2026-05-15 12:00:00+00";

    @Test
    void postInsertAgainstSoftDeletedSourceStillSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);
            softDeleteSource(c, sourceId);

            String uid = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO post (uid, source_id, title, fetched_at) "
                            + "VALUES (?, ?::uuid, ?, ?::timestamptz)")) {
                stmt.setString(1, uid);
                stmt.setString(2, sourceId);
                stmt.setString(3, "in-flight post against soft-deleted source");
                stmt.setString(4, IN_RANGE_FETCHED_AT);
                assertEquals(1, stmt.executeUpdate(),
                        "expected post INSERT against soft-deleted source to succeed");
            }

            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT source_id FROM post WHERE uid = ?")) {
                sel.setString(1, uid);
                try (ResultSet rs = sel.executeQuery()) {
                    assertTrue(rs.next(), "expected one row");
                    assertNotNull(rs.getString("source_id"),
                            "source_id should round-trip even after the source is soft-deleted");
                }
            }
        }
    }

    @Test
    void subscriptionInsertAgainstSoftDeletedSourceStillSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);
            softDeleteSource(c, sourceId);

            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES ('dm', ?::uuid, ?::uuid)")) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, sourceId);
                assertEquals(1, stmt.executeUpdate(),
                        "expected source_subscription INSERT against soft-deleted source to succeed");
            }
        }
    }

    private static String insertSource(Connection c) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            stmt.setString(1, "rss");
            stmt.setString(2, "https://example.com/soft-deleted-" + UUID.randomUUID());
            stmt.setString(3, "Soft-deleted source");
            stmt.setString(4, "news");
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void softDeleteSource(Connection c, String sourceId) throws SQLException {
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE source SET deleted_at = now() WHERE id = ?::uuid")) {
            upd.setString(1, sourceId);
            upd.executeUpdate();
        }
    }
}
