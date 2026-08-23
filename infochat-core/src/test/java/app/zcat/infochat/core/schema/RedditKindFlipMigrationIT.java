package app.zcat.infochat.core.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards V86's one-time flip of reddit feeds registered kind='rss' to
 * kind='reddit', in place, with the identifier normalized to the form
 * RedditFetcher appends {@code /.rss} to (M1-915; analysis P12-P14).
 *
 * <p>Owns a private container instead of extending
 * {@link PostgresSchemaTestBase}: the flip proof needs a stop at V85, seeded
 * prod-shaped rss-kind reddit rows, and only then the migrate to head — the
 * two-phase path here IS the prod-shaped-DB proof.
 */
class RedditKindFlipMigrationIT {

    private static PostgreSQLContainer<?> postgres;

    /** Fixed seed time inside the provisioned post-partition horizon. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    private static String rssRedditSourceId;
    private static String variantSlashSuffixSourceId;
    private static String variantBareSubredditSourceId;
    private static String variantOldRedditSourceId;
    private static String variantReddItSourceId;
    private static String variantUppercaseSuffixSourceId;
    private static String keptNonRedditRssSourceId;
    private static String keptRedditHostWithoutSuffixSourceId;
    private static String softDeletedRssSourceId;
    private static String collidingRssSourceId;
    private static String collidingRedditTwinId;

    @BeforeAll
    static void migrateStepwise() throws SQLException {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(pgVectorImageName())
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("infochat_kind_flip_test")
                .withUsername("infochat")
                .withPassword("infochat");
        postgres.start();

        // Phase 1: stop at V85 (the pre-M1-915 head) and seed prod-shaped
        // rows; the collide pair predates V86 to prove the skip never
        // fails the boot.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("85"))
                .load()
                .migrate();

        rssRedditSourceId = insertSource("rss",
                "https://www.reddit.com/r/explainlikeimfive/hot/.rss", "bootstrap");
        variantSlashSuffixSourceId = insertSource("rss",
                "https://www.reddit.com/r/askscience/hot/.rss", "user");
        variantBareSubredditSourceId = insertSource("rss",
                "https://www.reddit.com/r/showerthoughts/.rss", "user");
        variantOldRedditSourceId = insertSource("rss",
                "https://old.reddit.com/r/java/hot/.rss/", "user");
        variantReddItSourceId = insertSource("rss",
                "https://redd.it/r/slavelabour/hot/.rss", "user");
        variantUppercaseSuffixSourceId = insertSource("rss",
                "https://www.reddit.com/r/todayilearned/hot/.RSS", "user");
        keptNonRedditRssSourceId = insertSource("rss",
                "https://example.com/feed.rss", "user");
        keptRedditHostWithoutSuffixSourceId = insertSource("rss",
                "https://www.reddit.com/r/unmatched", "user");
        softDeletedRssSourceId = insertSource("rss",
                "https://www.reddit.com/r/gone/hot/.rss", "user");
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "UPDATE source SET deleted_at = now() WHERE id = ?::uuid")) {
            stmt.setString(1, softDeletedRssSourceId);
            stmt.executeUpdate();
        }
        // P14 collision pair, present BEFORE V86 applies: the migration must
        // skip the rss row and complete the boot anyway.
        collidingRssSourceId = insertSource("rss",
                "https://www.reddit.com/r/collide/hot/.rss", "user");
        collidingRedditTwinId = insertSource("reddit",
                "https://www.reddit.com/r/collide/hot", "user");
        try (Connection c = newConnection()) {
            insertSubscription(c, "dm", UUID.randomUUID(), rssRedditSourceId);
            insertSubscription(c, "group", UUID.randomUUID(), rssRedditSourceId);
            insertExclusion(c, "dm", UUID.randomUUID(), rssRedditSourceId);
            insertPost(c, rssRedditSourceId, "rss-flip-post-a",
                    "https://www.reddit.com/r/explainlikeimfive/comments/aaa/first/",
                    "first seeded post");
            insertPost(c, rssRedditSourceId, "rss-flip-post-b",
                    "https://www.reddit.com/r/explainlikeimfive/comments/bbb/second/",
                    "second seeded post");
        }

        // Phase 2: migrate the remaining chain (V86+) over the seeded data.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void rssRedditRowsFlipInPlacePreservingIdentity() throws SQLException {
        String expectedIdentifier = "https://www.reddit.com/r/explainlikeimfive/hot";
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "SELECT id::text, kind, identifier, source_origin "
                             + "FROM source WHERE id = ?::uuid")) {
            stmt.setString(1, rssRedditSourceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "seeded rss-kind reddit row must survive the migration");
                assertEquals("reddit", rs.getString("kind"),
                        "kind must flip in place from 'rss' to 'reddit'");
                assertEquals(expectedIdentifier, rs.getString("identifier"),
                        "identifier must normalize by stripping the .rss path suffix");
                assertEquals(rssRedditSourceId, rs.getString(1),
                        "source_id is identity — it must NOT change");
                assertEquals("bootstrap", rs.getString("source_origin"),
                        "source_origin is D59-preserved — the UPDATE must not touch it");
            }
        }

        try (Connection c = newConnection()) {
            assertEquals(2, countRows(c, "source_subscription", rssRedditSourceId),
                    "subscriptions are keyed on source_id and must stay attached");
            assertEquals(1, countRows(c, "source_exclusion", rssRedditSourceId),
                    "exclusions are keyed on source_id and must stay attached");
            assertPostsByteIdentical(c);
        }
    }

    @Test
    void identifierVariantsFlipAndUntouchedClassesStay() throws SQLException {
        assertFlipped(variantSlashSuffixSourceId,
                "https://www.reddit.com/r/askscience/hot",
                "/r/<sub>/hot/.rss form flips to the bare listing URL");
        assertFlipped(variantBareSubredditSourceId,
                "https://www.reddit.com/r/showerthoughts",
                "/r/<sub>/.rss form loses the dangling slash too");
        assertFlipped(variantOldRedditSourceId,
                "https://old.reddit.com/r/java/hot",
                "old.reddit.com subdomain matches, trailing-slash .rss form flips");
        assertFlipped(variantReddItSourceId,
                "https://redd.it/r/slavelabour/hot",
                "redd.it host matches the stated pattern");
        assertFlipped(variantUppercaseSuffixSourceId,
                "https://www.reddit.com/r/todayilearned/hot",
                "an uppercase .RSS suffix is selected by the case-insensitive census "
                        + "and must normalize too — a case-sensitive strip would leave "
                        + "the suffix on a kind='reddit' row");

        try (Connection c = newConnection()) {
            assertRow(keptNonRedditRssSourceId, "rss", "https://example.com/feed.rss",
                    "non-reddit rss rows are left untouched");
            assertRow(keptRedditHostWithoutSuffixSourceId, "rss",
                    "https://www.reddit.com/r/unmatched",
                    "non-.rss reddit-host rss rows are left untouched");
            assertRow(softDeletedRssSourceId, "rss",
                    "https://www.reddit.com/r/gone/hot/.rss",
                    "a soft-deleted rss row is NOT flipped — admin-lifecycle territory");
            assertRow(collidingRssSourceId, "rss",
                    "https://www.reddit.com/r/collide/hot/.rss",
                    "the colliding rss row survives untouched (left for ops)");
            assertRow(collidingRedditTwinId, "reddit", "https://www.reddit.com/r/collide/hot",
                    "the pre-existing reddit twin is never merged or altered");
        }
    }

    @Test
    void collidingRowsAreSkippedAndReported() throws SQLException, IOException {
        // Re-runs the shipped V86 statement over THIS connection so its RAISE
        // NOTICE surfaces in the JDBC warning chain; flipped rows no longer
        // match the census, so the re-run doubles as an idempotence proof.
        String script = migrationScript();
        String skippedNotice = null;
        try (Connection c = newConnection(); Statement s = c.createStatement()) {
            s.execute(script);
            for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                if (w.getMessage().contains("SKIPPED")
                        && w.getMessage().contains("https://www.reddit.com/r/collide/hot")) {
                    skippedNotice = w.getMessage();
                }
            }
        }
        assertTrue(skippedNotice != null,
                "the collision must be reported by RAISE NOTICE naming the identifier");

        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "SELECT count(*) FROM source WHERE kind = 'reddit' "
                             + "AND identifier = 'https://www.reddit.com/r/collide/hot'")) {
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1),
                        "rows are never merged — exactly one reddit twin remains");
            }
        }
    }

    @Test
    void bootstrapUpsertConvergesOnFlippedRow() throws SQLException {
        // BootstrapLoader.java:177-184's exact upsert over the corrected
        // entry — the strings the corrected prod-host runtime bootstrap file
        // must declare. It must land ON the flipped row, not beside it (P13).
        String correctedIdentifier = "https://www.reddit.com/r/explainlikeimfive/hot";
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, config, source_origin, language) "
                             + "VALUES (?, ?, ?, ?, ?::TEXT[], ?::JSONB, 'bootstrap', ?) "
                             + "ON CONFLICT (kind, identifier) DO UPDATE "
                             + "SET display_name = EXCLUDED.display_name, "
                             + "    category = EXCLUDED.category, "
                             + "    bootstrap_tags = EXCLUDED.bootstrap_tags, "
                             + "    config = EXCLUDED.config, "
                             + "    source_origin = 'bootstrap', "
                             + "    language = EXCLUDED.language "
                             + "WHERE source.deleted_at IS NULL")) {
            ps.setString(1, "reddit");
            ps.setString(2, correctedIdentifier);
            ps.setString(3, "explainlikeimfive (Reddit)");
            ps.setString(4, "social");
            ps.setObject(5, new String[]{"science"});
            ps.setString(6, "{}");
            ps.setString(7, "en");
            ps.executeUpdate();

            try (PreparedStatement check = c.prepareStatement(
                    "SELECT id::text, source_origin FROM source "
                            + "WHERE kind = 'reddit' AND identifier = ?")) {
                check.setString(1, correctedIdentifier);
                try (ResultSet rs = check.executeQuery()) {
                    assertTrue(rs.next(), "the corrected entry resolves to a row");
                    assertEquals(rssRedditSourceId, rs.getString(1),
                            "the upsert updates the SAME row — no duplicate double-fetch row");
                    assertEquals("bootstrap", rs.getString("source_origin"));
                    assertTrue(!rs.next(), "exactly one row holds the corrected key");
                }
            }
        }
    }

    private static void assertFlipped(String sourceId, String expectedIdentifier,
                                      String message) throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "SELECT kind, identifier FROM source WHERE id = ?::uuid")) {
            stmt.setString(1, sourceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "row must exist: " + expectedIdentifier);
                assertEquals("reddit", rs.getString("kind"), message);
                assertEquals(expectedIdentifier, rs.getString("identifier"), message);
            }
        }
    }

    private static void assertRow(String sourceId, String expectedKind,
                                  String expectedIdentifier, String message) throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "SELECT kind, identifier FROM source WHERE id = ?::uuid")) {
            stmt.setString(1, sourceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "row must survive: " + expectedIdentifier);
                assertEquals(expectedKind, rs.getString("kind"), message);
                assertEquals(expectedIdentifier, rs.getString("identifier"), message);
            }
        }
    }

    private static void assertPostsByteIdentical(Connection c) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                     "SELECT uid, upstream_identifier FROM post WHERE source_id = ?::uuid "
                             + "ORDER BY uid")) {
            stmt.setString(1, rssRedditSourceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "post a must still be attached");
                assertEquals("rss-flip-post-a", rs.getString("uid"));
                assertEquals("https://www.reddit.com/r/explainlikeimfive/comments/aaa/first/",
                        rs.getString("upstream_identifier"),
                        "stored upstream_identifier is byte-identical — the one-time "
                                + "re-ingest is accepted but stored rows are never rewritten");
                assertTrue(rs.next(), "post b must still be attached");
                assertEquals("rss-flip-post-b", rs.getString("uid"));
                assertEquals("https://www.reddit.com/r/explainlikeimfive/comments/bbb/second/",
                        rs.getString("upstream_identifier"));
            }
        }
    }

    /** The shipped V86 statement text, read from the migration classpath. */
    private static String migrationScript() throws IOException {
        try (InputStream in = RedditKindFlipMigrationIT.class
                .getResourceAsStream("/db/migration/V86__reddit_kind_flip.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int countRows(Connection c, String table, String sourceId)
            throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE source_id = ?::uuid")) {
            stmt.setString(1, sourceId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String insertSource(String kind, String identifier, String origin)
            throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, source_origin) "
                             + "VALUES (?, ?, 'seeded reddit feed', 'social', ?) RETURNING id")) {
            stmt.setString(1, kind);
            stmt.setString(2, identifier);
            stmt.setString(3, origin);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insertSubscription(Connection c, String scopeKind, UUID scopeId,
                                           String sourceId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                        + "VALUES (?, ?, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setObject(2, scopeId);
            stmt.setString(3, sourceId);
            stmt.executeUpdate();
        }
    }

    private static void insertExclusion(Connection c, String scopeKind, UUID scopeId,
                                        String sourceId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source_exclusion (scope_kind, scope_id, source_id) "
                        + "VALUES (?, ?, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setObject(2, scopeId);
            stmt.setString(3, sourceId);
            stmt.executeUpdate();
        }
    }

    private static void insertPost(Connection c, String sourceId, String uid,
                                   String upstreamIdentifier, String title) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO post (uid, source_id, title, fetched_at, upstream_identifier) "
                        + "VALUES (?, ?::uuid, ?, ?, ?)")) {
            stmt.setString(1, uid);
            stmt.setString(2, sourceId);
            stmt.setString(3, title);
            stmt.setTimestamp(4, Timestamp.from(FETCHED_AT));
            stmt.setString(5, upstreamIdentifier);
            stmt.executeUpdate();
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Same single-pin image read as {@link PostgresSchemaTestBase}: both
     * containers must provably run the pgvector image (V1 declares the
     * extension).
     */
    private static String pgVectorImageName() {
        Properties props = new Properties();
        try (InputStream in =
                RedditKindFlipMigrationIT.class.getResourceAsStream("/application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return props.getProperty("quarkus.datasource.devservices.image-name");
    }
}
