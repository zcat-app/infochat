package app.zcat.infochat.collector.bootstrap;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link BootstrapLoader} against a Quarkus
 * DevServices Postgres. Inherits the
 * {@code infochat.bootstrap.sources-file} pointing at
 * {@code bootstrap-sources-fixture.json} from this module's
 * {@code src/test/resources/application.properties}, so the
 * {@code @Startup} bean loads real bytes during Quarkus boot.
 *
 * <p>Method order is fixed (post-startup state → re-run idempotency →
 * soft-delete-skip) because the three cases share DB state and each
 * step's assertions depend on the previous step's writes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootstrapLoaderIT {

    @Inject
    DataSource dataSource;

    @Inject
    BootstrapLoader loader;

    @Test
    @Order(1)
    void firstRunWritesSourcesTagsAuditAndMeta() throws Exception {
        assertNotNull(dataSource);

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            // (a) Exactly 3 source rows from the fixture.
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM source")) {
                rs.next();
                assertEquals(3, rs.getInt(1),
                    "fixture has 3 entries; source table must carry 3 rows");
            }

            // (a) tag table carries the union of fixture tags.
            // Normalized to lowercase: ai, development, java, nostr, security.
            try (ResultSet rs = st.executeQuery(
                "SELECT name FROM tag WHERE source_origin = 'bootstrap' ORDER BY name")) {
                java.util.List<String> names = new java.util.ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                assertTrue(names.contains("ai"), "tags must include 'ai'; got: " + names);
                assertTrue(names.contains("development"), "tags must include 'development'; got: " + names);
                assertTrue(names.contains("nostr"), "tags must include 'nostr'; got: " + names);
            }

            // (a) Exactly one BOOTSTRAP_SOURCE_LOAD audit row from the
            // startup run.
            try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM audit_log WHERE action = 'BOOTSTRAP_SOURCE_LOAD'")) {
                rs.next();
                assertEquals(1, rs.getInt(1),
                    "exactly one audit row after the @PostConstruct startup load");
            }

            // (a) bootstrap_meta is populated with id = 1 and the load
            // summary.
            try (ResultSet rs = st.executeQuery(
                "SELECT id, last_entry_count, last_loaded_sha256 FROM bootstrap_meta")) {
                assertTrue(rs.next(), "bootstrap_meta must carry one row");
                assertEquals(1, rs.getInt("id"));
                assertEquals(3, rs.getInt("last_entry_count"));
                assertEquals(64, rs.getString("last_loaded_sha256").length(),
                    "SHA-256 hex digest is 64 lower-case characters");
            }
        }
    }

    @Test
    @Order(2)
    void rerunIsSourceRowNoOpButAppendsAuditRow() throws Exception {
        // Capture pre-rerun state so we can assert the source columns
        // are byte-identical post-rerun (idempotent contract).
        String rssIdentifier = "https://www.example.com/news/feed.xml";
        String preName, preCategory;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT display_name, category FROM source WHERE kind = ? AND identifier = ?")) {
            ps.setString(1, "rss");
            ps.setString(2, rssIdentifier);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "fixture rss row must exist before rerun");
                preName = rs.getString("display_name");
                preCategory = rs.getString("category");
            }
        }

        loader.runLoad();

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            // (b) source columns identical post-rerun.
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT display_name, category FROM source WHERE kind = ? AND identifier = ?")) {
                ps.setString(1, "rss");
                ps.setString(2, rssIdentifier);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(preName, rs.getString("display_name"),
                        "idempotent re-run must not modify display_name");
                    assertEquals(preCategory, rs.getString("category"),
                        "idempotent re-run must not modify category");
                }
            }

            // source row count still 3.
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM source")) {
                rs.next();
                assertEquals(3, rs.getInt(1),
                    "re-run must not multiply source rows");
            }

            // (b) audit_log appends a NEW row per run — count is now 2.
            try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM audit_log WHERE action = 'BOOTSTRAP_SOURCE_LOAD'")) {
                rs.next();
                assertEquals(2, rs.getInt(1),
                    "second loader invocation must append a second audit row");
            }
        }
    }

    @Test
    @Order(3)
    void softDeletedRowIsNotResurrectedByLoader() throws Exception {
        // Soft-delete the rss row and overwrite its display_name with a
        // tombstone value. The loader's next run hits ON CONFLICT for
        // (rss, <identifier>); the UPDATE branch's
        // WHERE source.deleted_at IS NULL gate skips the row. Operator
        // intent (the /remove-source soft-delete) survives the
        // bootstrap re-run.
        String rssIdentifier = "https://www.example.com/news/feed.xml";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET deleted_at = now(), display_name = ?, category = ? "
                     + "WHERE kind = ? AND identifier = ?")) {
            ps.setString(1, "tombstone-marker");
            ps.setString(2, "tombstone-category");
            ps.setString(3, "rss");
            ps.setString(4, rssIdentifier);
            ps.executeUpdate();
        }

        loader.runLoad();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT display_name, category, deleted_at FROM source "
                     + "WHERE kind = ? AND identifier = ?")) {
            ps.setString(1, "rss");
            ps.setString(2, rssIdentifier);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("tombstone-marker", rs.getString("display_name"),
                    "soft-deleted source row must NOT be overwritten by the loader");
                assertEquals("tombstone-category", rs.getString("category"),
                    "soft-deleted source row's category must remain operator-intent");
                assertNotNull(rs.getTimestamp("deleted_at"),
                    "deleted_at must remain set after the loader's no-op pass");
            }
        }
    }
}
