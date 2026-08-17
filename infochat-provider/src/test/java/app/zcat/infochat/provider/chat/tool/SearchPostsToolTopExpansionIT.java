package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tree-aware tag expansion in searchPosts (M1-867): requested TOPs resolve to subtree leaves. */
@QuarkusTest
class SearchPostsToolTopExpansionIT {

    private static final String UID_PREFIX = "spte-";
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("5e600001-0001-4000-8000-000000000001");
    private static final UUID SOURCE_ID = UUID.fromString("5e600002-0002-4000-8000-000000000002");
    private static final UUID OUT_OF_WORLD_SOURCE_ID = UUID.fromString("5e600003-0003-4000-8000-000000000003");
    private static final String USER_CONTACT = "spte-user";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject SearchPostsTool tool;
    @Inject CancellationService cancellationService;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM scope_tag WHERE scope_id = '" + USER_ID + "'");
            exec(conn, "DELETE FROM scope_preferences WHERE scope_id = '" + USER_ID + "'");
            exec(conn, "DELETE FROM source_subscription WHERE source_id = '" + SOURCE_ID + "'");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + UID_PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE id = '" + SOURCE_ID + "'");
            exec(conn, "DELETE FROM source WHERE id = '" + OUT_OF_WORLD_SOURCE_ID + "'");
            exec(conn, "DELETE FROM users WHERE id = '" + USER_ID + "'");
            seedTestData(conn);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        // The reconciler-style exact-count ITs that run later in the same JVM
        // scan ALL now-stamped READY rows: nothing may outlive this class.
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM source_subscription WHERE source_id = '" + SOURCE_ID + "'");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + UID_PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE id = '" + SOURCE_ID + "'");
            exec(conn, "DELETE FROM source WHERE id = '" + OUT_OF_WORLD_SOURCE_ID + "'");
            exec(conn, "DELETE FROM users WHERE id = '" + USER_ID + "'");
        }
    }

    @Test
    void topNameExpandsToSubtreeLeavesForTheFilter() throws Exception {
        seedReadyPost("ai1", "AI Story", "ai");
        seedReadyPost("cyb1", "Cyber Story", "cybersecurity");
        seedReadyPost("fut1", "Football Story", "football");

        String result = tool.execute(USER_ID, "dm", USER_ID, Map.of("tags", List.of("tech")));

        assertTrue(result.contains("AI Story"), "tech expands to ai leaf");
        assertTrue(result.contains("Cyber Story"), "tech expands to cybersecurity leaf");
        assertTrue(!result.contains("Football Story"), "sport leaf is not under tech");
    }

    @Test
    void expandedSearchNeverSurfacesOutOfWorldOrNonReadyPosts() throws Exception {
        seedReadyPost("ai1", "In World AI", "ai");
        seedReadyPost("quar1", "Quarantined AI", "ai", "QUARANTINED");
        // A READY post on a source the scope cannot see (never subscribed,
        // 'user' origin — not the bootstrap corpus): the D59 world predicate
        // must hold through the expansion.
        seedReadyPostOn(OUT_OF_WORLD_SOURCE_ID, "oow1", "Out Of World AI", "ai");

        String result = tool.execute(USER_ID, "dm", USER_ID, Map.of("tags", List.of("tech")));

        assertTrue(result.contains("In World AI"), "in-world READY post surfaces");
        assertTrue(!result.contains("Quarantined AI"), "non-READY post never surfaces");
        assertTrue(!result.contains("Out Of World AI"),
                "an out-of-world READY post never surfaces through the expansion");
    }

    @Test
    void unknownTagStillRejectsTheWholeCall() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(USER_ID, "dm", USER_ID, Map.of("tags", List.of("not-a-tag"))),
                "an unknown name still rejects with Unknown tag");
    }

    private void seedTestData(Connection conn) throws Exception {
        exec(conn,
                "INSERT INTO source (id, kind, identifier, display_name, category, status)"
                        + " VALUES (?, 'rss', 'http://spte.example.com/feed', 'SPTE Source', 'news', 'active')"
                        + " ON CONFLICT (kind, identifier) DO UPDATE SET id = EXCLUDED.id",
                SOURCE_ID);
        // The out-of-world source: never subscribed, and the default
        // source_origin='user' keeps it out of the bootstrap corpus.
        exec(conn,
                "INSERT INTO source (id, kind, identifier, display_name, category, status)"
                        + " VALUES (?, 'rss', 'http://spte-oow.example.com/feed', 'SPTE OOW Source', 'news', 'active')"
                        + " ON CONFLICT (kind, identifier) DO UPDATE SET id = EXCLUDED.id",
                OUT_OF_WORLD_SOURCE_ID);
        exec(conn,
                "INSERT INTO users (id, adapter, contact_id, display_name, registration_state)"
                        + " VALUES (?, 'inmemory', ?, 'SPTE User', 'vouched')"
                        + " ON CONFLICT (adapter, contact_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, is_banned = FALSE",
                USER_ID, USER_CONTACT);
        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('dm', ?, ?) ON CONFLICT DO NOTHING",
                USER_ID, SOURCE_ID);
    }

    private void seedReadyPost(String slug, String title, String tag) throws Exception {
        seedReadyPost(slug, title, tag, "READY");
    }

    private void seedReadyPost(String slug, String title, String tag, String status) throws Exception {
        seedReadyPostOn(SOURCE_ID, slug, title, tag, status);
    }

    private void seedReadyPostOn(UUID sourceId, String slug, String title, String tag) throws Exception {
        seedReadyPostOn(sourceId, slug, title, tag, "READY");
    }

    private void seedReadyPostOn(UUID sourceId, String slug, String title, String tag, String status) throws Exception {
        Instant at = Instant.now().minusSeconds(120);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (id, uid, source_id, title, body, status,"
                             + " published_at, fetched_at, ready_at, tags,"
                             + " stage1_done, stage2_done, tagger_done, embedding_done,"
                             + " upstream_identifier)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ARRAY[?]::TEXT[],"
                             + " TRUE, TRUE, TRUE, TRUE, ?)")) {
            ps.setObject(1, UUID.nameUUIDFromBytes(("spte-" + slug).getBytes()));
            ps.setString(2, UID_PREFIX + slug);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
            ps.setString(5, title + " body.");
            ps.setString(6, status);
            ps.setTimestamp(7, Timestamp.from(at));
            ps.setTimestamp(8, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(9, status.equals("READY") ? Timestamp.from(at) : Timestamp.from(at.plusSeconds(99999)));
            ps.setString(10, tag);
            ps.setString(11, UID_PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
