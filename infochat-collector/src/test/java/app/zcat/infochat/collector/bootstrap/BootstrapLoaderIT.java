package app.zcat.infochat.collector.bootstrap;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @SeedDataSource
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
            // Tree names (M1-866): ai, software-development, cybersecurity.
            try (ResultSet rs = st.executeQuery(
                "SELECT name FROM tag WHERE source_origin = 'bootstrap' ORDER BY name")) {
                java.util.List<String> names = new java.util.ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                assertTrue(names.contains("ai"), "tags must include 'ai'; got: " + names);
                assertTrue(names.contains("software-development"),
                    "tags must include 'software-development'; got: " + names);
                assertTrue(names.contains("cybersecurity"),
                    "tags must include 'cybersecurity'; got: " + names);
            }

            // (a) Every loader-written source row is bootstrap-origin
            // (D59, M1-621) — the implicit-public-corpus discriminator.
            try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM source WHERE source_origin = 'bootstrap'")) {
                rs.next();
                assertEquals(3, rs.getInt(1),
                    "the loader marks every row source_origin='bootstrap'");
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
                 "UPDATE source SET deleted_at = now(), display_name = ?, category = ?, "
                     + "source_origin = 'user' "
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
                 "SELECT display_name, category, deleted_at, source_origin FROM source "
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
                assertEquals("user", rs.getString("source_origin"),
                    "the deleted_at IS NULL gate must block the origin promote too — "
                        + "a soft-deleted row's origin stays untouched");
            }
        }
    }

    @Test
    @Order(4)
    void loaderPromotesUserOriginRowBackToBootstrap() throws Exception {
        // A source the operator lists in bootstrap-sources.json is public
        // by operator intent (D59, M1-621): if the same (kind, identifier)
        // was previously /add-source'd as a private custom ('user'), the
        // loader's ON CONFLICT branch promotes it to 'bootstrap'. Uses the
        // bluesky fixture row — the rss row was soft-deleted by @Order(3).
        String blueskyIdentifier =
            "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=example.dev";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET source_origin = 'user' "
                     + "WHERE kind = 'bluesky' AND identifier = ?")) {
            ps.setString(1, blueskyIdentifier);
            assertEquals(1, ps.executeUpdate(), "fixture bluesky row must exist");
        }

        loader.runLoad();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT source_origin FROM source "
                     + "WHERE kind = 'bluesky' AND identifier = ?")) {
            ps.setString(1, blueskyIdentifier);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("bootstrap", rs.getString(1),
                    "re-listing a 'user' row in the bootstrap file must promote it");
            }
        }
    }

    @Test
    @Order(5)
    void invalidTagInBootstrapJsonFailsFast(@TempDir Path tempDir) throws IOException {
        Path fixture = tempDir.resolve("invalid-tags.json");
        Files.writeString(fixture, """
            [{"kind":"rss","identifier":"https://example.com/feed",\
            "name":"X","category":"news","tags":["machine learning"]}]
            """);

        BootstrapLoader unwrapped = ClientProxy.unwrap(loader);
        String original = unwrapped.sourcesFilePath;
        try {
            unwrapped.sourcesFilePath = fixture.toString();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loader.runLoad());
            assertTrue(ex.getMessage().contains("machine learning"),
                "exception message must name the invalid tag; got: " + ex.getMessage());
        } finally {
            unwrapped.sourcesFilePath = original;
        }
    }

    @Test
    @Order(6)
    void nonNodeTagInBootstrapJsonFailsFast(@TempDir Path tempDir) throws IOException {
        // M1-866 node gate: a valid-character-class name that is not an
        // existing tag-tree node fails startup the same way, naming the
        // offender — the growth gate that keeps the v1 vendor tail out.
        Path fixture = tempDir.resolve("non-node-tags.json");
        Files.writeString(fixture, """
            [{"kind":"rss","identifier":"https://example.com/feed",\
            "name":"X","category":"news","tags":["kimiai2"]}]
            """);

        BootstrapLoader unwrapped = ClientProxy.unwrap(loader);
        String original = unwrapped.sourcesFilePath;
        try {
            unwrapped.sourcesFilePath = fixture.toString();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loader.runLoad());
            assertTrue(ex.getMessage().contains("kimiai2"),
                "exception message must name the non-node tag; got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("tag-tree node"),
                "exception message must state the node-membership reason; got: " + ex.getMessage());
        } finally {
            unwrapped.sourcesFilePath = original;
        }
    }
}
