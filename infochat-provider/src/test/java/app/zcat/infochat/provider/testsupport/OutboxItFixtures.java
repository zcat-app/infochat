package app.zcat.infochat.provider.testsupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared DB-fixture helpers for the provider outbox ITs (NewPost* and
 * QuarantineReview* listeners/reconcilers). Each former copy kept its own
 * inline JDBC for these three operations; the SQL now lives here once, with the
 * only per-test difference — the isolation source's identifier — passed as an
 * argument. The DataSource is a parameter (rather than a field) so the one IT
 * that injects it under a different field name reuses the same body.
 */
public final class OutboxItFixtures {

    private OutboxItFixtures() {
    }

    /** Delete every IT-seeded post (uid namespaced with {@code -it/}). */
    public static void clearAllItPosts(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE '%-it/%'")) {
            ps.executeUpdate();
        }
    }

    /** Reset the {@code new_post} provider_state cursor to the epoch floor. */
    public static void resetNewPostCursor(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
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

    /**
     * Upsert an {@code rss} test source by {@code (kind, identifier)} and return
     * its id. The identifier/display name are the test's isolation namespace, so
     * each caller passes its own.
     */
    public static UUID ensureTestSource(DataSource dataSource, String identifier,
                                        String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category) "
                     + "VALUES ('rss', ?, ?, 'news') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE "
                     + "SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test source upsert must yield an id");
                return rs.getObject("id", UUID.class);
            }
        }
    }
}
