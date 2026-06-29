package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the {@code post.upstream_identifier} NOT NULL constraint added by
 * V54 (deep-review 19#F1; M1-517).
 *
 * <p>docs/spec/schema.md §UID derivation mandates a non-null
 * {@code upstream_identifier} for every post: every Fetcher/StreamSource MUST
 * produce one and ID-less items are rejected at the Fetcher boundary. Before
 * V54 the column was nullable, so the storage layer did not back the spec
 * contract. This tripwire asserts the constraint is live — an insert with an
 * explicit NULL {@code upstream_identifier} is rejected by Postgres with
 * SQLState 23502 (not_null_violation), not silently stored.
 */
class UpstreamIdentifierNotNullIT extends PostgresSchemaTestBase {

    // Within V30's provisioned partition horizon (< '2026-08-01').
    private static final String IN_RANGE_FETCHED_AT = "2026-05-15 12:00:00+00";

    @Test
    void postInsertWithNullUpstreamIdentifierIsRejected() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);

            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO post (uid, source_id, upstream_identifier, title, fetched_at) "
                            + "VALUES (?, ?::uuid, ?, ?, ?::timestamptz)")) {
                stmt.setString(1, UUID.randomUUID().toString().replace("-", ""));
                stmt.setString(2, sourceId);
                stmt.setNull(3, Types.VARCHAR);
                stmt.setString(4, "post with null upstream_identifier");
                stmt.setString(5, IN_RANGE_FETCHED_AT);

                SQLException ex = assertThrows(SQLException.class, stmt::executeUpdate,
                        "INSERT with a NULL upstream_identifier must be rejected");
                assertEquals("23502", ex.getSQLState(),
                        "rejection must be a not_null_violation (23502)");
            }
        }
    }

    private static String insertSource(Connection c) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            stmt.setString(1, "rss");
            stmt.setString(2, "https://example.com/uid-not-null-" + UUID.randomUUID());
            stmt.setString(3, "Upstream-identifier NOT NULL source");
            stmt.setString(4, "news");
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
