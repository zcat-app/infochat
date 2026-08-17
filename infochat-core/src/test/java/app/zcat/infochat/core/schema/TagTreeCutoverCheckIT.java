package app.zcat.infochat.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The M1-880 reproduction: drives prod/scripts/tag-tree-cutover.sh against the
 *  Testcontainers DB; the migrate step re-executes V84 (TagTreeMigrationIT mechanics). */
@EnabledOnOs(OS.LINUX)
class TagTreeCutoverCheckIT extends PostgresSchemaTestBase {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T13:00:00Z");

    // ---------- the converted reproduction (acceptance 1) ----------

    @Test
    void leftoverOccurrencesFailThePreflight(@TempDir Path tmp) throws Exception {
        seedTagRow("nostr");
        seedPost("lf-post", List.of("ai", "video"));
        seedSource("lf-src", List.of("cybersecurity", "nostr"));
        seedFollowOnTagName(UUID.randomUUID(), "nostr");
        Path fixture = writeBootstrapFixture(tmp, "nostr");

        ScriptResult r = runScript(tmp, fixture, "preflight");

        assertEquals(1, r.exit(), "preflight must exit 1 on leftovers; got:\n" + r.out());
        assertTrue(r.out().contains("tag: nostr"), "must name the tag surface: " + r.out());
        assertTrue(r.out().contains("post.tags: video"), "must name the post.tags surface: " + r.out());
        assertTrue(r.out().contains("source.bootstrap_tags: nostr"),
                "must name the source.bootstrap_tags surface: " + r.out());
        assertTrue(r.out().contains("scope_tag: nostr"), "must name the scope_tag surface: " + r.out());
        assertTrue(r.out().contains("file: nostr"), "must name the file surface: " + r.out());
    }

    // ---------- cleanup exactness + failure mode (acceptance 2) ----------

    @Test
    void cleanupRemovesExactlyNostrAndVideo(@TempDir Path tmp) throws Exception {
        seedTagRow("nostr");
        seedTagRow("ai");
        seedPost("cl-post", List.of("ai", "video"));
        seedSource("cl-src", List.of("ai", "nostr"));
        seedFollowOnTagName(UUID.randomUUID(), "nostr");
        UUID aiScope = UUID.randomUUID();
        seedFollowOnTagName(aiScope, "ai");
        Path fixture = writeBootstrapFixture(tmp, "ai");
        String aiRowBefore = tagRowSnapshot("ai");

        ScriptResult dry = runScript(tmp, fixture, "cleanup", "--dry-run");
        assertEquals(0, dry.exit(), "dry-run must succeed: " + dry.out());
        assertTrue(dry.out().contains("dry-run: scope_tag rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: tag rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: post.tags rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: source.bootstrap_tags rows: 1"), dry.out());

        // The would-be destructive run must be side-effect-free: leftovers
        // still present, the bystander rows byte-identical.
        assertEquals(1, runScript(tmp, fixture, "preflight").exit(), "dry-run must change nothing");
        assertEquals(1, scalarInt("SELECT count(*) FROM tag WHERE name = 'nostr'"));
        assertEquals(1, scalarInt("SELECT count(*) FROM scope_tag st JOIN tag t ON t.id = st.tag_id WHERE t.name = 'nostr'"));
        assertEquals(Set.of("ai", "video"), postTags("cl-post"));
        assertEquals(Set.of("ai", "nostr"), sourceBootstrapTags("cl-src"));
        assertEquals(aiRowBefore, tagRowSnapshot("ai"), "dry-run must leave the ai bystander byte-identical");

        ScriptResult clean = runScript(tmp, fixture, "cleanup");
        assertEquals(0, clean.exit(), "cleanup must succeed: " + clean.out());
        assertTrue(clean.out().contains("scope_tag removed: 1"), clean.out());
        assertTrue(clean.out().contains("tag removed: 1"), clean.out());
        assertTrue(clean.out().contains("post.tags rewritten: 1"), clean.out());
        assertTrue(clean.out().contains("source.bootstrap_tags rewritten: 1"), clean.out());

        assertEquals(0, scalarInt("SELECT count(*) FROM tag WHERE name IN ('nostr','video')"),
                "no nostr/video tag row may survive");
        assertEquals(Set.of("ai"), postTags("cl-post"), "post.tags keeps exactly the bystander elements");
        assertEquals(Set.of("ai"), sourceBootstrapTags("cl-src"),
                "source.bootstrap_tags keeps exactly the bystander elements");
        assertEquals(0, scalarInt(
                "SELECT count(*) FROM scope_tag st JOIN tag t ON t.id = st.tag_id WHERE t.name IN ('nostr','video')"),
                "no scope_tag row may reference a removed tag");
        assertEquals(1, scalarInt("SELECT count(*) FROM scope_tag st JOIN tag t ON t.id = st.tag_id WHERE t.name = 'ai'"),
                "the ai scope_tag bystander must survive");
        assertEquals(aiRowBefore, tagRowSnapshot("ai"), "cleanup must leave the ai bystander byte-identical");

        ScriptResult second = runScript(tmp, fixture, "cleanup");
        assertEquals(0, second.exit(), "the second run must succeed: " + second.out());
        assertTrue(second.out().contains("scope_tag removed: 0"), second.out());
        assertTrue(second.out().contains("tag removed: 0"), second.out());
        assertTrue(second.out().contains("post.tags rewritten: 0"), second.out());
        assertTrue(second.out().contains("source.bootstrap_tags rewritten: 0"), second.out());
    }

    // ---------- the full rehearsal (acceptance 3) ----------

    @Test
    void cutoverRehearsalPassesPostflight(@TempDir Path tmp) throws Exception {
        seedTagRow("nostr");
        seedTagRow("ai");
        seedPost("rh-post", List.of("ai", "video"));
        seedSource("rh-src", List.of("ai", "nostr"));
        seedFollowOnTagName(UUID.randomUUID(), "nostr");
        seedFollowOnTagName(UUID.randomUUID(), "ai");
        Path fixture = writeBootstrapFixture(tmp, "ai", "world");

        assertEquals(1, runScript(tmp, fixture, "preflight").exit(), "the rehearsal preflight must start RED");
        assertEquals(0, runScript(tmp, fixture, "cleanup").exit(), "the rehearsal cleanup must succeed");

        ScriptResult preGreen = runScript(tmp, fixture, "preflight");
        assertEquals(0, preGreen.exit(), "preflight must be GREEN after cleanup:\n" + preGreen.out());
        assertTrue(preGreen.out().contains("preflight: clean"), preGreen.out());

        runV84();

        ScriptResult post = runScript(tmp, fixture, "postflight");
        assertEquals(0, post.exit(), "postflight must be GREEN after the migrate step:\n" + post.out());
        assertTrue(post.out().contains("GREEN: flyway_schema_history: version 84 applied with success"), post.out());
        assertTrue(post.out().contains("GREEN: tag tree seeded: 9 tops, 53 leaves"), post.out());
        assertTrue(post.out().contains("GREEN: fallback-marked leaves: exactly 8"), post.out());
        assertTrue(post.out().contains("GREEN: zero nostr/video leftovers"), post.out());
        assertTrue(post.out().contains("GREEN: every post.tags element names a tag node"), post.out());
        assertTrue(post.out().contains("GREEN: every source.bootstrap_tags element names a tag node"), post.out());
        assertTrue(post.out().contains("GREEN: zero scope_tag orphans"), post.out());
        assertTrue(post.out().contains("GREEN: bootstrap-sources.json tags[] all name tag-tree nodes"), post.out());
    }

    // ---------- the runtime-file failure mode (acceptance 4) ----------

    @Test
    void runtimeBootstrapFileNamesOnlyTreeNodes(@TempDir Path tmp) throws Exception {
        Path retired = writeBootstrapFixture(tmp, "cybersecurity", "nostr");
        ScriptResult pre = runScript(tmp, retired, "preflight");
        assertEquals(1, pre.exit(), "a file tags[] entry of nostr must RED the preflight:\n" + pre.out());
        assertTrue(pre.out().contains("file: nostr"), pre.out());

        runV84();

        Path nonNode = writeBootstrapFixture(tmp, "ai", "claude");
        ScriptResult postRed = runScript(tmp, nonNode, "postflight");
        assertEquals(1, postRed.exit(), "a non-node file tag must RED the postflight:\n" + postRed.out());
        assertTrue(postRed.out().contains("RED: bootstrap-sources.json tags[]"), postRed.out());
        assertTrue(postRed.out().contains("claude"), postRed.out());

        Path treeNamed = writeBootstrapFixture(tmp, "ai", "world");
        ScriptResult postGreen = runScript(tmp, treeNamed, "postflight");
        assertEquals(0, postGreen.exit(), "the tree-named fixture must be GREEN:\n" + postGreen.out());
        assertTrue(postGreen.out().contains("GREEN: bootstrap-sources.json tags[] all name tag-tree nodes"),
                postGreen.out());
    }

    // ---------- helpers ----------

    /** One script invocation plus its exit code and merged stdout/stderr. */
    private record ScriptResult(int exit, String out) {
    }

    private ScriptResult runScript(Path tmp, Path fixture, String... args) throws Exception {
        Path script = repoRoot().resolve("prod/scripts/tag-tree-cutover.sh");
        assertTrue(Files.exists(script), "the cutover script must exist: " + script);
        Path wrapper = writePsqlWrapper(tmp);
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Files.writeString(runtime.resolve("secrets.env"), "INFOCHAT_DB_PASSWORD=dummy-test-password\n");

        List<String> cmd = new java.util.ArrayList<>(List.of("bash", script.toString()));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("CUTOVER_PSQL", wrapper.toString());
        pb.environment().put("CUTOVER_BOOTSTRAP_FILE", fixture.toString());
        pb.environment().put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        pb.environment().put("CUTOVER_PGHOST", "127.0.0.1");
        pb.environment().put("CUTOVER_PGDB", "infochat_test");
        pb.environment().put("CUTOVER_PGUSER", "infochat");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("cutover script hung (>120s); output so far:\n" + out);
        }
        return new ScriptResult(p.exitValue(), out);
    }

    /** The CUTOVER_PSQL seam: exec psql inside the Testcontainers container. */
    private Path writePsqlWrapper(Path tmp) throws Exception {
        Path wrapper = tmp.resolve("cutover-psql-wrapper.sh");
        Files.writeString(wrapper, "#!/bin/sh\n"
                + "exec docker exec -i -e PGPASSWORD=infochat " + POSTGRES.getContainerId()
                + " psql \"$@\"\n");
        wrapper.toFile().setExecutable(true);
        return wrapper;
    }

    /** A bootstrap-sources.json fixture in the wizard template's one-array-per-line shape. */
    private Path writeBootstrapFixture(Path tmp, String... tags) throws Exception {
        String tagList = Arrays.stream(tags)
                .map(t -> "\"" + t + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        Path fixture = tmp.resolve("bootstrap-sources-" + Integer.toHexString(Arrays.hashCode(tags)) + ".json");
        Files.writeString(fixture,
                "[\n  {\n    \"kind\": \"rss\",\n"
                        + "    \"identifier\": \"https://cutover.example.test/feed.xml\",\n"
                        + "    \"name\": \"Cutover IT source\",\n"
                        + "    \"category\": \"news\",\n"
                        + "    \"tags\": [" + tagList + "]\n  }\n]\n");
        return fixture;
    }

    /** Executes V84's own statements (minus the boot-applied ALTER) in one transaction (the TagTreeMigrationIT mechanics). */
    private void runV84() throws Exception {
        String sql;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("db/migration/V84__tag_tree_seed_and_migration.sql")) {
            assertTrue(in != null, "V84 must be on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String stripped = sql.replaceAll("(?s)ALTER TABLE tag ADD COLUMN fallback[^;]*;", "");
        try (Connection conn = newConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute(stripped);
            }
            conn.commit();
        }
    }

    private void seedTagRow(String name) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tag (name, display, source_origin) VALUES (?, ?, 'bootstrap') "
                                + "ON CONFLICT (name) DO NOTHING")) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void seedPost(String slug, List<String> tags) throws Exception {
        UUID sourceId;
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                                + "VALUES ('rss', ?, ?, 'news', '{}') RETURNING id")) {
            ps.setString(1, "https://cutover.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Cutover IT " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                sourceId = (UUID) rs.getObject(1);
            }
        }
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO post ("
                                + "  id, uid, source_id, upstream_identifier, title, body,"
                                + "  fetched_at, status,"
                                + "  stage1_done, stage2_done, tagger_done, embedding_done,"
                                + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                                + ") VALUES ("
                                + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RAW',"
                                + "  TRUE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, ?"
                                + ")")) {
            ps.setString(1, "cutover-" + slug + "-uid");
            ps.setObject(2, sourceId);
            ps.setString(3, "cutover-" + slug + "-upstream");
            ps.setString(4, "Cutover IT post " + slug);
            ps.setString(5, "body");
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setArray(7, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            ps.executeUpdate();
        }
    }

    private void seedSource(String slug, List<String> bootstrapTags) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                                + "VALUES ('rss', ?, ?, 'news', ?)")) {
            ps.setString(1, "https://cutover.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Cutover IT " + slug);
            ps.setArray(3, conn.createArrayOf("TEXT", bootstrapTags.toArray(new String[0])));
            ps.executeUpdate();
        }
    }

    private void seedFollowOnTagName(UUID scopeId, String tagName) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                                + "SELECT 'dm', ?, id FROM tag WHERE name = ? "
                                + "ON CONFLICT (scope_kind, scope_id, tag_id) DO NOTHING")) {
            ps.setObject(1, scopeId);
            ps.setString(2, tagName);
            ps.executeUpdate();
        }
    }

    private Set<String> postTags(String slug) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT p.tags FROM post p JOIN source s ON s.id = p.source_id "
                                + "WHERE s.identifier = ?")) {
            ps.setString(1, "https://cutover.example.test/" + slug + "/feed.xml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post must exist: " + slug);
                return new LinkedHashSet<>(Arrays.asList((String[]) rs.getArray(1).getArray()));
            }
        }
    }

    private Set<String> sourceBootstrapTags(String slug) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT bootstrap_tags FROM source WHERE identifier = ?")) {
            ps.setString(1, "https://cutover.example.test/" + slug + "/feed.xml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "source must exist: " + slug);
                return new LinkedHashSet<>(Arrays.asList((String[]) rs.getArray(1).getArray()));
            }
        }
    }

    /** The bystander identity columns, for the byte-identical assertions. */
    private String tagRowSnapshot(String name) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT display, source_origin, node_kind, parent_name, fallback FROM tag WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "tag row must exist: " + name);
                return rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getString(4) + "|" + rs.getBoolean(5);
            }
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection conn = newConnection(); Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "expected one row: " + sql);
            return rs.getInt(1);
        }
    }

    /** Walk up from the module CWD to the repo root (the dir with docker-compose.yml). */
    private Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("docker-compose.yml"))) {
                return p;
            }
        }
        throw new IllegalStateException("docker-compose.yml not found walking up from " + dir);
    }
}
