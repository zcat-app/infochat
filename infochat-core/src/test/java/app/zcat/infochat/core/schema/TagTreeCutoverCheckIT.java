package app.zcat.infochat.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Drives prod/scripts/tag-tree-cutover.sh against the Testcontainers DB; the migrate
 *  step re-executes V84 (TagTreeMigrationIT mechanics). The preflight inventories every
 *  name V84 cannot map; the apply executes the operator's rulings file. */
@EnabledOnOs(OS.LINUX)
class TagTreeCutoverCheckIT extends PostgresSchemaTestBase {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T13:00:00Z");

    // ---------- the converted reproduction (acceptance 1) ----------

    @Test
    void dynamicUnmappedNamesFailThePreflight(@TempDir Path tmp) throws Exception {
        seedTagRow("ai-image");
        seedPost("dyn-post", List.of("ai", "ai-image", "video"));
        seedSource("dyn-src", List.of("cybersecurity", "ai-image"));
        seedSource("dyn-src-key", List.of("development"));
        seedFollowOnTagName(UUID.randomUUID(), "ai-image");
        Path fixture = writeBootstrapFixture(tmp, "ai", "Development");

        ScriptResult r = runScript(tmp, fixture, "preflight");

        assertEquals(1, r.exit(), "preflight must exit 1 on unmapped names; got:\n" + r.out());
        assertTrue(r.out().contains("tag: ai-image (1)"), "must name the tag surface: " + r.out());
        assertTrue(r.out().contains("post.tags: ai-image (1), video (1)"),
                "must name the post.tags surface with counts: " + r.out());
        assertTrue(r.out().contains("source.bootstrap_tags: ai-image (1)"),
                "must name the source.bootstrap_tags surface: " + r.out());
        assertTrue(r.out().contains("scope_tag: ai-image (1)"),
                "must name the scope_tag surface: " + r.out());
        assertTrue(r.out().contains("file: Development (1)"), "must name the file surface: " + r.out());
        assertFalse(r.out().contains("ai ("), "the known leaf ai must not be flagged: " + r.out());
        assertFalse(r.out().contains("cybersecurity"), "a known leaf must not be flagged: " + r.out());
        assertFalse(r.out().contains("development (1)"),
                "a DB-side mapping key must not be flagged (only the file side flags it): " + r.out());

        Path mapFile = mapFile(tmp);
        assertTrue(Files.exists(mapFile), "the first RED must write the rulings skeleton");
        String skeleton = Files.readString(mapFile);
        assertTrue(skeleton.contains(
                "# ai-image — post.tags (1), scope_tag (1), source.bootstrap_tags (1), tag (1)"),
                "one commented placeholder per unknown name with per-surface counts:\n" + skeleton);
        assertTrue(skeleton.contains("# ai-image: drop"), "the placeholder is commented:\n" + skeleton);
        assertTrue(skeleton.contains("# development — file (1) — raw file form(s): Development"),
                "the file-side finding keys on the normalized form, quoting the raw form:\n" + skeleton);
        assertTrue(skeleton.lines().anyMatch(l -> l.equals("video: drop")),
                "the standing disposal ruling is pre-filled ACTIVE:\n" + skeleton);
        assertTrue(r.out().contains(mapFile.toString()), "the RED output names the rulings-file path");

        String edited = skeleton + "# operator note: ruling under review\n";
        Files.writeString(mapFile, edited);
        ScriptResult r2 = runScript(tmp, fixture, "preflight");
        assertEquals(1, r2.exit(), "the second preflight is still RED");
        assertEquals(edited, Files.readString(mapFile),
                "the skeleton never clobbers an operator edit");
    }

    // ---------- generalized preflight: leftovers + skeleton (acceptance 2) ----------

    @Test
    void leftoverOccurrencesFailThePreflight(@TempDir Path tmp) throws Exception {
        seedTagRow("nostr");
        seedPost("lf-post", List.of("ai", "video"));
        seedSource("lf-src", List.of("cybersecurity", "nostr"));
        seedFollowOnTagName(UUID.randomUUID(), "nostr");
        Path fixture = writeBootstrapFixture(tmp, "nostr");

        ScriptResult r = runScript(tmp, fixture, "preflight");

        assertEquals(1, r.exit(), "preflight must exit 1 on leftovers; got:\n" + r.out());
        assertTrue(r.out().contains("tag: nostr (1)"), "must name the tag surface: " + r.out());
        assertTrue(r.out().contains("post.tags: video (1)"), "must name the post.tags surface: " + r.out());
        assertTrue(r.out().contains("source.bootstrap_tags: nostr (1)"),
                "must name the source.bootstrap_tags surface: " + r.out());
        assertTrue(r.out().contains("scope_tag: nostr (1)"), "must name the scope_tag surface: " + r.out());
        assertTrue(r.out().contains("file: nostr (1)"), "must name the file surface: " + r.out());

        Path mapFile = mapFile(tmp);
        assertTrue(Files.exists(mapFile), "the first RED must write the rulings skeleton");
        String skeleton = Files.readString(mapFile);
        assertTrue(skeleton.lines().anyMatch(l -> l.equals("nostr: drop")),
                "the standing ruling pre-fills an ACTIVE nostr drop line:\n" + skeleton);
        assertTrue(skeleton.lines().anyMatch(l -> l.equals("video: drop")),
                "the standing ruling pre-fills an ACTIVE video drop line:\n" + skeleton);
        assertTrue(r.out().contains(mapFile.toString()), "the RED output names the rulings-file path");
    }

    // ---------- rulings-file apply: exactness + failure modes (acceptance 3) ----------

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
        writeRulings(tmp, "nostr: drop", "video: drop");

        ScriptResult dry = runScript(tmp, fixture, "apply", "--dry-run");
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

        ScriptResult clean = runScript(tmp, fixture, "apply");
        assertEquals(0, clean.exit(), "apply must succeed: " + clean.out());
        assertTrue(clean.out().contains("scope_tag removed: 1"), clean.out());
        assertTrue(clean.out().contains("tag removed: 1"), clean.out());
        assertTrue(clean.out().contains("post.tags rewritten: 1"), clean.out());
        assertTrue(clean.out().contains("source.bootstrap_tags rewritten: 1"), clean.out());
        assertTrue(clean.out().contains("nostr: drop"), "the consumed lines print for retirement: " + clean.out());
        assertTrue(clean.out().contains("video: drop"), clean.out());

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
        assertEquals(aiRowBefore, tagRowSnapshot("ai"), "apply must leave the ai bystander byte-identical");

        // The staleness guard: the unretired rulings file refuses the consumed lines.
        ScriptResult second = runScript(tmp, fixture, "apply");
        assertEquals(2, second.exit(), "the unretired second run must refuse the stale lines: " + second.out());
        assertTrue(second.out().contains("nostr: drop"), "the refusal names the stale line: " + second.out());
        assertEquals(1, scalarInt("SELECT count(*) FROM tag WHERE name = 'ai'"),
                "the refusal changes nothing");

        writeRulings(tmp);
        ScriptResult retired = runScript(tmp, fixture, "apply");
        assertEquals(0, retired.exit(), "the post-retirement run is a clean no-op: " + retired.out());
        assertTrue(retired.out().contains("scope_tag removed: 0"), retired.out());
        assertTrue(retired.out().contains("tag removed: 0"), retired.out());
        assertTrue(retired.out().contains("post.tags rewritten: 0"), retired.out());
        assertTrue(retired.out().contains("source.bootstrap_tags rewritten: 0"), retired.out());
    }

    // ---------- the full rehearsal (acceptance 4) ----------

    @Test
    void cutoverRehearsalPassesPostflight(@TempDir Path tmp) throws Exception {
        seedTagRow("nostr");
        seedTagRow("ai");
        seedPost("rh-post", List.of("ai", "video"));
        seedSource("rh-src", List.of("ai", "nostr"));
        seedFollowOnTagName(UUID.randomUUID(), "nostr");
        seedFollowOnTagName(UUID.randomUUID(), "ai");
        Path fixture = writeBootstrapFixture(tmp, "ai", "world");

        // The first RED writes the skeleton; its ACTIVE standing-ruling drops are
        // the rulings the rehearsal applies (nostr/video are the only unknowns).
        assertEquals(1, runScript(tmp, fixture, "preflight").exit(), "the rehearsal preflight must start RED");
        assertEquals(0, runScript(tmp, fixture, "apply").exit(), "the rehearsal apply must succeed");

        ScriptResult preGreen = runScript(tmp, fixture, "preflight");
        assertEquals(0, preGreen.exit(), "preflight must be GREEN after the apply:\n" + preGreen.out());
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

    // ---------- the runtime-file failure mode (acceptance 5) ----------

    @Test
    void runtimeBootstrapFileNamesOnlyTreeNodes(@TempDir Path tmp) throws Exception {
        // The generalized file check flags ANY non-leaf — 'Development' normalizes
        // to a V84 mapping key, which is not a seeded leaf.
        Path retired = writeBootstrapFixture(tmp, "cybersecurity", "Development");
        ScriptResult pre = runScript(tmp, retired, "preflight");
        assertEquals(1, pre.exit(), "a file tags[] non-leaf must RED the preflight:\n" + pre.out());
        assertTrue(pre.out().contains("file: Development (1)"), pre.out());

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

    // ---------- mirror completeness: GREEN preflight ⇒ the real V84 executes clean ----------

    @Test
    void preflightGreenStateExecutesV84Cleanly(@TempDir Path tmp) throws Exception {
        // One edge name per known-set class: a mapping key as a tag row AND as array
        // elements ('news' is legal in arrays ONLY as the key), an operator coinage
        // colliding with a to-be-seeded leaf name, the identity leaves.
        seedTagRow("claude");
        seedTagRow("news");
        seedTagRow("sport");
        seedParentedOperatorLeaf("football", "sport");
        seedTagRow("ai");
        seedTagRow("crypto");
        seedTagRow("research");
        seedPost("green-post", List.of("news", "java"));
        seedSource("green-src", List.of("malware", "ai"));
        Path fixture = writeBootstrapFixture(tmp, "ai", "world");

        ScriptResult pre = runScript(tmp, fixture, "preflight");
        assertEquals(0, pre.exit(), "every known-set class must pass the preflight:\n" + pre.out());
        assertTrue(pre.out().contains("preflight: clean"), pre.out());
        assertTrue(Files.notExists(mapFile(tmp)), "a GREEN preflight writes no skeleton");

        runV84();

        ScriptResult post = runScript(tmp, fixture, "postflight");
        assertEquals(0, post.exit(), "postflight must be GREEN after the migrate step:\n" + post.out());

        ScriptResult after = runScript(tmp, fixture, "preflight");
        assertEquals(0, after.exit(), "a post-V84 DB must be silent-clean (no false positives):\n" + after.out());
        assertTrue(after.out().contains("preflight: clean"), after.out());
    }

    // ---------- the rulings-file apply mechanics (re-point / rename / dedup / staleness) ----------

    @Test
    void applyAppliesTheRulingsFile(@TempDir Path tmp) throws Exception {
        // Phase 1 — collision re-point, drop, order-preserving dedup, dry-run purity,
        // bystander byte-identity, counts + consumed lines, the staleness guard.
        seedTagRow("ai");
        seedTagRow("ai-image");
        seedPost("ap-post", List.of("ai", "ai-image"));
        seedSource("ap-src", List.of("ai-image", "video"));
        UUID scopeBoth = UUID.randomUUID();
        seedFollowOnTagName(scopeBoth, "ai-image");
        seedFollowOnTagName(scopeBoth, "ai");
        Path fixture = writeBootstrapFixture(tmp, "ai");
        writeRulings(tmp, "ai-image: ai", "video: drop");
        String aiRowBefore = tagRowSnapshot("ai");

        ScriptResult dry = runScript(tmp, fixture, "apply", "--dry-run");
        assertEquals(0, dry.exit(), "dry-run must succeed: " + dry.out());
        assertTrue(dry.out().contains("plan: ai-image -> ai"), dry.out());
        assertTrue(dry.out().contains("plan: video -> drop"), dry.out());
        assertTrue(dry.out().contains("dry-run: scope_tag rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: tag rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: post.tags rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: source.bootstrap_tags rows: 1"), dry.out());
        assertEquals(1, runScript(tmp, fixture, "preflight").exit(), "dry-run must change nothing");
        assertEquals(List.of("ai", "ai-image"), postTagsArray("ap-post"), "dry-run must not rewrite the array");
        assertEquals(aiRowBefore, tagRowSnapshot("ai"), "dry-run must leave the ai bystander byte-identical");

        ScriptResult real = runScript(tmp, fixture, "apply");
        assertEquals(0, real.exit(), "apply must succeed: " + real.out());
        assertTrue(real.out().contains("scope_tag re-pointed: 0"),
                "the scope follows both names — ON CONFLICT DO NOTHING, no PK violation: " + real.out());
        assertTrue(real.out().contains("scope_tag removed: 1"), real.out());
        assertTrue(real.out().contains("tag removed: 1"), real.out());
        assertTrue(real.out().contains("post.tags rewritten: 1"), real.out());
        assertTrue(real.out().contains("source.bootstrap_tags rewritten: 1"), real.out());
        assertTrue(real.out().contains("ai-image: ai"), "the consumed lines print for retirement: " + real.out());
        assertTrue(real.out().contains("video: drop"), real.out());

        assertEquals(Set.of("ai"), scopeTagNames(scopeBoth),
                "the scope re-points at ai exactly once (no duplicate follow)");
        assertEquals(List.of("ai"), postTagsArray("ap-post"),
                "order-preserving dedup: {ai, ai-image} rewrites to exactly {ai}");
        assertEquals(List.of("ai"), sourceBootstrapTagsArray("ap-src"),
                "the map lands and the drop is removed: {ai-image, video} -> {ai}");
        assertEquals(0, scalarInt("SELECT count(*) FROM tag WHERE name = 'ai-image'"),
                "the retired tag row is gone");
        assertEquals(aiRowBefore, tagRowSnapshot("ai"), "the ai bystander stays byte-identical");

        ScriptResult stale = runScript(tmp, fixture, "apply");
        assertEquals(2, stale.exit(), "the unretired rulings file must refuse the consumed lines: " + stale.out());
        assertTrue(stale.out().contains("ai-image: ai"), "the refusal names the stale line: " + stale.out());
        assertTrue(stale.out().contains("stale"), stale.out());
        assertEquals(List.of("ai"), postTagsArray("ap-post"), "the refusal changes nothing");

        writeRulings(tmp);
        ScriptResult retired = runScript(tmp, fixture, "apply");
        assertEquals(0, retired.exit(), "after retirement the apply is a clean no-op: " + retired.out());
        assertTrue(retired.out().contains("tag removed: 0"), retired.out());

        // Phase 2 — a map onto a leaf whose row is absent pre-migrate refuses at
        // validation with the alternatives named (zero mutation); the re-rule to an
        // identity leaf applies and the post-migrate postflight stays GREEN.
        seedTagRow("ml-ops");
        seedPost("ap-post2", List.of("ml-ops"));
        UUID scopeMap = UUID.randomUUID();
        seedFollowOnTagName(scopeMap, "ml-ops");
        writeRulings(tmp, "ml-ops: software-development");

        ScriptResult refused = runScript(tmp, fixture, "apply");
        assertEquals(2, refused.exit(), "the absent-target ruling must refuse: " + refused.out());
        assertTrue(refused.out().contains("ml-ops: software-development"),
                "the refusal names the line: " + refused.out());
        assertTrue(refused.out().contains("does not exist"), refused.out());
        assertTrue(refused.out().contains("ml-ops: drop"), "the refusal names the alternatives: " + refused.out());
        assertEquals(List.of("ml-ops"), postTagsArray("ap-post2"), "the refusal changes nothing");
        assertEquals(Set.of("ml-ops"), scopeTagNames(scopeMap), "the refusal changes nothing");

        writeRulings(tmp, "ml-ops: ai");
        ScriptResult reruled = runScript(tmp, fixture, "apply");
        assertEquals(0, reruled.exit(), "the re-rule to an identity leaf applies: " + reruled.out());
        assertEquals(Set.of("ai"), scopeTagNames(scopeMap), "the follow re-points at the identity leaf");
        assertEquals(List.of("ai"), postTagsArray("ap-post2"), "the array element maps to the identity leaf");
        assertEquals(0, scalarInt("SELECT count(*) FROM tag WHERE name = 'ml-ops'"),
                "the retired tag row is gone");

        runV84();
        ScriptResult post = runScript(tmp, fixture, "postflight");
        assertEquals(0, post.exit(), "the post-migrate postflight stays GREEN:\n" + post.out());
        assertTrue(post.out().contains("GREEN: tag tree seeded: 9 tops, 53 leaves"), post.out());
        assertTrue(post.out().contains("GREEN: fallback-marked leaves: exactly 8"), post.out());
    }

    // ---------- rulings-file validation: every invalid shape refuses with zero mutation ----------

    @Test
    void applyRefusesAnInvalidRulingsFile(@TempDir Path tmp) throws Exception {
        seedTagRow("ai");
        seedTagRow("ai-image");
        seedPost("inv-post", List.of("ai-image"));
        seedSource("inv-src", List.of("video"));
        Path fixture = writeBootstrapFixture(tmp, "ai");
        String aiRowBefore = tagRowSnapshot("ai");

        assertRulingsRefused(tmp, fixture, aiRowBefore, "has no ruling",
                "# nothing ruled yet\n");
        assertRulingsRefused(tmp, fixture, aiRowBefore, "duplicate ruling for 'ai-image'",
                "ai-image: drop\nai-image: ai\nvideo: drop\n");
        assertRulingsRefused(tmp, fixture, aiRowBefore, "ghost: drop",
                "ai-image: drop\nvideo: drop\nghost: drop\n");
        assertRulingsRefused(tmp, fixture, aiRowBefore, "malformed rulings line 1: ai-image drop",
                "ai-image drop\nvideo: drop\n");
        assertRulingsRefused(tmp, fixture, aiRowBefore, "malformed rulings line 3: *: drop",
                "ai-image: drop\nvideo: drop\n*: drop\n");
        assertRulingsRefused(tmp, fixture, aiRowBefore, "no seeded tree leaf",
                "ai-image: sport\nvideo: drop\n");
        assertRulingsRefused(tmp, fixture, aiRowBefore, "does not exist",
                "ai-image: software-development\nvideo: drop\n");
        assertRulingsRefused(tmp, fixture, aiRowBefore, "standing ruling is disposal",
                "ai-image: drop\nvideo: ai\n");
    }

    /** One invalid rulings-file shape: exit 2 naming the line/name, zero mutation. */
    private void assertRulingsRefused(Path tmp, Path fixture, String aiRowBefore,
            String expectedMessage, String rulingsContent) throws Exception {
        Files.writeString(mapFile(tmp), rulingsContent);
        ScriptResult r = runScript(tmp, fixture, "apply");
        assertEquals(2, r.exit(), "an invalid rulings file must refuse with exit 2: " + r.out());
        assertTrue(r.out().contains(expectedMessage), "the refusal must name the line/name: " + r.out());
        assertEquals(1, scalarInt("SELECT count(*) FROM tag WHERE name = 'ai-image'"),
                "zero mutation — the unknown row survives: " + r.out());
        assertEquals(List.of("ai-image"), postTagsArray("inv-post"), "zero mutation — the array survives");
        assertEquals(aiRowBefore, tagRowSnapshot("ai"), "zero mutation — the bystander is byte-identical");
        assertEquals(1, runScript(tmp, fixture, "preflight").exit(), "preflight is still RED");
    }

    // ---------- helpers ----------

    /** One script invocation plus its exit code and merged stdout/stderr. */
    private record ScriptResult(int exit, String out) {
    }

    /** The rulings-file seam for this test run (CUTOVER_MAP_FILE). */
    private Path mapFile(Path tmp) {
        return tmp.resolve("tag-cutover-map.txt");
    }

    /** Active ruling lines, one per element; no arguments writes an empty file. */
    private void writeRulings(Path tmp, String... lines) throws Exception {
        Files.writeString(mapFile(tmp), String.join("\n", lines) + (lines.length > 0 ? "\n" : ""));
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
        pb.environment().put("CUTOVER_MAP_FILE", mapFile(tmp).toString());
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

    /** An operator coinage of a to-be-seeded leaf name on a V82+ DB: the tree columns are set. */
    private void seedParentedOperatorLeaf(String name, String parent) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tag (name, display, source_origin, node_kind, parent_name, fallback) "
                                + "VALUES (?, 'OPERATOR ROW', 'user', 'leaf', ?, FALSE) "
                                + "ON CONFLICT (name) DO NOTHING")) {
            ps.setString(1, name);
            ps.setString(2, parent);
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
        return new LinkedHashSet<>(postTagsArray(slug));
    }

    /** The raw post.tags array in stored order (the dedup-discriminating assertion). */
    private List<String> postTagsArray(String slug) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT p.tags FROM post p JOIN source s ON s.id = p.source_id "
                                + "WHERE s.identifier = ?")) {
            ps.setString(1, "https://cutover.example.test/" + slug + "/feed.xml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post must exist: " + slug);
                return Arrays.asList((String[]) rs.getArray(1).getArray());
            }
        }
    }

    private Set<String> sourceBootstrapTags(String slug) throws Exception {
        return new LinkedHashSet<>(sourceBootstrapTagsArray(slug));
    }

    /** The raw source.bootstrap_tags array in stored order. */
    private List<String> sourceBootstrapTagsArray(String slug) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT bootstrap_tags FROM source WHERE identifier = ?")) {
            ps.setString(1, "https://cutover.example.test/" + slug + "/feed.xml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "source must exist: " + slug);
                return Arrays.asList((String[]) rs.getArray(1).getArray());
            }
        }
    }

    /** The tag names a scope follows. */
    private Set<String> scopeTagNames(UUID scopeId) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT t.name FROM scope_tag st JOIN tag t ON t.id = st.tag_id "
                                + "WHERE st.scope_id = ?")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> names = new LinkedHashSet<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                return names;
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
