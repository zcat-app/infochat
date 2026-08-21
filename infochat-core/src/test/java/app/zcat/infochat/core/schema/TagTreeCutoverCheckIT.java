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
        assertFalse(skeleton.lines().anyMatch(l -> l.startsWith("# development")),
                "a V84 mapping key gets NO placeholder/ruling line — it converts deterministically:\n"
                        + skeleton);
        assertTrue(skeleton.contains("# V84 mapping keys seen: development")
                        && skeleton.contains("takes NO ruling"),
                "the informational comment names the key and the deterministic conversion:\n" + skeleton);
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

    // ---------- D-13: file-only mapping-key rulings refuse loud, zero mutation ----------

    @Test
    void fileOnlyMappingKeyRulingsRefuseLoudWithZeroMutation(@TempDir Path tmp) throws Exception {
        // The C1-rehearsal trap shape: every seeded name is V84-mappable key data, so the
        // DB inventory is clean and the file fixture is what flags the key names.
        seedTagRow("development");
        seedTagRow("java");
        seedPost("key-post", List.of("openai", "ai", "development"));
        seedSource("key-src", List.of("development"));
        Path fixture = writeBootstrapFixture(tmp, "Development", "Java", "AI");
        String developmentRowBefore = tagRowSnapshot("development");
        String javaRowBefore = tagRowSnapshot("java");
        writeRulings(tmp, "development: drop", "java: drop");

        ScriptResult refused = runScript(tmp, fixture, "apply");
        assertEquals(2, refused.exit(), "a ruling naming a V84 mapping key must refuse: " + refused.out());
        assertTrue(refused.out().contains("mapping key 'development'"),
                "the refusal names the key ruling: " + refused.out());
        assertTrue(refused.out().contains("line 1"), "the refusal names the line: " + refused.out());
        assertTrue(refused.out().contains("reconcile-file"),
                "the refusal names the deterministic paths (V84 DB-side, reconcile-file file-side): "
                        + refused.out());

        ScriptResult dryRefused = runScript(tmp, fixture, "apply", "--dry-run");
        assertEquals(2, dryRefused.exit(), "--dry-run refuses identically: " + dryRefused.out());
        assertTrue(dryRefused.out().contains("mapping key 'development'"), dryRefused.out());

        assertEquals(developmentRowBefore, tagRowSnapshot("development"),
                "zero mutation — the development tag row survives byte-identical");
        assertEquals(javaRowBefore, tagRowSnapshot("java"),
                "zero mutation — the java tag row survives byte-identical");
        assertEquals(List.of("openai", "ai", "development"), postTagsArray("key-post"),
                "zero mutation — the witness post array is byte-identical");
        assertEquals(List.of("development"), sourceBootstrapTagsArray("key-src"),
                "zero mutation — the witness source array is byte-identical");
        assertEquals(1, runScript(tmp, fixture, "preflight").exit(), "preflight stays RED");
    }

    @Test
    void applyConsumedPrintoutKeepsLinesReconcileStillNeeds(@TempDir Path tmp) throws Exception {
        seedTagRow("ai-image");
        seedTagRow("ai");
        seedTagRow("video");
        seedPost("retire-only-post", List.of("ai", "ai-image", "video"));
        seedFollowOnTagName(UUID.randomUUID(), "ai-image");
        writeRulings(tmp, "ai-image: ai", "video: drop");

        Path fileWithoutAiImage = writeBootstrapFixture(tmp, "AI");
        ScriptResult fileOnlyRetire = runScript(tmp, fileWithoutAiImage, "apply");
        assertEquals(0, fileOnlyRetire.exit(), "the apply without AI-Image must succeed: " + fileOnlyRetire.out());
        String retireOnlySection = fileOnlyRetire.out().substring(fileOnlyRetire.out().indexOf("retire now"));
        assertTrue(retireOnlySection.contains("ai-image: ai"),
                "a DB-only consumed ruling must be marked retire-now: " + fileOnlyRetire.out());
        assertTrue(retireOnlySection.contains("video: drop"), fileOnlyRetire.out());

        seedTagRow("ai-image");
        seedTagRow("video");
        seedPost("shared-ruling-post", List.of("ai", "ai-image", "video"));
        seedFollowOnTagName(UUID.randomUUID(), "ai-image");
        Path fixture = writeBootstrapFixture(tmp, "AI-Image");

        ScriptResult applied = runScript(tmp, fixture, "apply");
        assertEquals(0, applied.exit(), "apply must succeed: " + applied.out());
        assertTrue(applied.out().contains("consumed rulings"), applied.out());
        assertTrue(applied.out().contains("keep for reconcile-file"), applied.out());
        String keepSection = applied.out().substring(applied.out().indexOf("keep for reconcile-file"),
                applied.out().indexOf("retire now"));
        assertTrue(keepSection.contains("ai-image: ai"),
                "the still-needed ruling must be in the keep group: " + applied.out());
        String retireSection = applied.out().substring(applied.out().indexOf("retire now"));
        assertFalse(retireSection.contains("ai-image: ai"),
                "apply must keep the ruling needed by reconcile-file: " + applied.out());
        assertTrue(retireSection.contains("video: drop"),
                "the DB-only ruling must be in the retire-now group: " + applied.out());
        assertTrue(applied.out().contains("ai-image: ai"),
                "the consumed ruling must remain visible in the classified printout: " + applied.out());

        writeRulings(tmp, "ai-image: ai");
        ScriptResult reconciled = runScript(tmp, fixture, "reconcile-file");
        assertEquals(0, reconciled.exit(), "reconcile-file must consume the kept ruling: " + reconciled.out());
        assertTrue(reconciled.out().contains("ai-image: ai"), reconciled.out());

        writeRulings(tmp);
        ScriptResult applyNoOp = runScript(tmp, fixture, "apply");
        assertEquals(0, applyNoOp.exit(), "retired rulings file must apply as a clean no-op: " + applyNoOp.out());
        ScriptResult reconcileNoOp = runScript(tmp, fixture, "reconcile-file");
        assertEquals(0, reconcileNoOp.exit(),
                "retired rulings file must reconcile as a clean no-op: " + reconcileNoOp.out());
        assertTrue(reconcileNoOp.out().contains("no changes"), reconcileNoOp.out());
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

    // ---------- apply coverage = DB unknowns only; file-only rulings never execute ----------

    @Test
    void applyDemandsRulingsForDbUnknownsOnly(@TempDir Path tmp) throws Exception {
        seedTagRow("ai");
        seedTagRow("ai-image");
        seedPost("dbu-post", List.of("ai", "ai-image"));
        UUID scopeAiImage = UUID.randomUUID();
        seedFollowOnTagName(scopeAiImage, "ai-image");
        // V84-mappable key elements the apply must never touch (V84 maps them itself).
        seedPost("dbu-witness", List.of("openai", "ai", "development"));
        seedSource("dbu-src", List.of("development"));
        Path fixture = writeBootstrapFixture(tmp, "Development", "Java", "AI-Image");
        writeRulings(tmp, "ai-image: ai");

        ScriptResult dry = runScript(tmp, fixture, "apply", "--dry-run");
        assertEquals(0, dry.exit(), "no ruling is demanded for the file-only key names: " + dry.out());
        assertFalse(dry.out().contains("has no ruling"), dry.out());
        assertTrue(dry.out().contains("plan: ai-image -> ai"),
                "the plan lists exactly the executed ruling: " + dry.out());
        assertFalse(dry.out().contains("plan: development"),
                "a file-only key name is never a plan line: " + dry.out());
        assertFalse(dry.out().contains("plan: java"), dry.out());
        assertTrue(dry.out().contains("dry-run: scope_tag rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: tag rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: post.tags rows: 1"), dry.out());
        assertTrue(dry.out().contains("dry-run: source.bootstrap_tags rows: 0"), dry.out());

        ScriptResult real = runScript(tmp, fixture, "apply");
        assertEquals(0, real.exit(), "apply succeeds with rulings for the DB unknowns only: " + real.out());
        assertTrue(real.out().contains("ai-image: ai"), "the consumed name prints: " + real.out());
        assertEquals(List.of("ai"), postTagsArray("dbu-post"), "the executed ruling maps the element");
        assertEquals(Set.of("ai"), scopeTagNames(scopeAiImage), "the follow re-points at ai");
        assertEquals(0, scalarInt("SELECT count(*) FROM tag WHERE name = 'ai-image'"),
                "the retired tag row is gone");
        assertEquals(List.of("openai", "ai", "development"), postTagsArray("dbu-witness"),
                "the V84-mappable witness post is byte-identical");
        assertEquals(List.of("development"), sourceBootstrapTagsArray("dbu-src"),
                "the V84-mappable witness source is byte-identical");

        // The staleness/extras guard still runs against the union inventory (P2).
        writeRulings(tmp, "ai-image: ai", "ghost: drop");
        ScriptResult stale = runScript(tmp, fixture, "apply");
        assertEquals(2, stale.exit(), "a ruling naming no current inventory still refuses: " + stale.out());
        assertTrue(stale.out().contains("ghost: drop"), "the refusal names the stale line: " + stale.out());
        assertEquals(List.of("openai", "ai", "development"), postTagsArray("dbu-witness"),
                "the refusal changes nothing");

        // A file-only non-key ruling is tolerated, never executed, never retired by apply.
        writeRulings(tmp, "ai-image: ai");
        ScriptResult tolerated = runScript(tmp, fixture, "apply");
        assertEquals(0, tolerated.exit(), "the file-only ruling is tolerated: " + tolerated.out());
        assertTrue(tolerated.out().contains("tag removed: 0"), "nothing executes: " + tolerated.out());
        assertTrue(tolerated.out().contains("post.tags rewritten: 0"), tolerated.out());
        String consumedSection = tolerated.out().substring(tolerated.out().indexOf("consumed rulings"));
        assertFalse(consumedSection.contains("ai-image"),
                "the file-only ruling is never printed under the retire-now instruction: " + tolerated.out());
        assertTrue(tolerated.out().contains("'ai-image: ai' has no DB-side occurrences")
                        && tolerated.out().contains("reconcile-file's business"),
                "the tolerated ruling is reported as reconcile-file's business: " + tolerated.out());

        ScriptResult dryAgain = runScript(tmp, fixture, "apply", "--dry-run");
        assertEquals(0, dryAgain.exit(), dryAgain.out());
        assertFalse(dryAgain.out().contains("plan: ai-image"),
                "no plan line for a file-only ruling: " + dryAgain.out());
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

    @Test
    void postflightFileCheckIsLeafOnly(@TempDir Path tmp) throws Exception {
        runV84();

        Path topNamed = writeBootstrapFixture(tmp, "ai", "news");
        ScriptResult topRed = runScript(tmp, topNamed, "postflight");
        assertEquals(1, topRed.exit(), "a top-node file tag must RED the postflight:\n" + topRed.out());
        assertTrue(topRed.out().contains("RED: bootstrap-sources.json tags[]"), topRed.out());
        assertTrue(topRed.out().contains("news"), topRed.out());
        assertTrue(topRed.out().contains("source-eligible leaf tag-tree node"), topRed.out());

        Path leafNamed = writeBootstrapFixture(tmp, "ai", "world");
        ScriptResult leafGreen = runScript(tmp, leafNamed, "postflight");
        assertEquals(0, leafGreen.exit(), "all-leaf file tags must GREEN the postflight:\n" + leafGreen.out());
        assertTrue(leafGreen.out().contains("GREEN: bootstrap-sources.json tags[] all name tag-tree nodes"),
                leafGreen.out());

        // A rename keeps the census rows (9 tops / 53 leaves / 8 fallback) intact while
        // putting a mirror-unknown leaf name on the live DB: only the live-query
        // predicate GREENs this leg — the IS_LEAF mirror REDs it (P4 mutation trap).
        renameSeededLeaf("football", "rugby");
        Path operatorLeafNamed = writeBootstrapFixture(tmp, "ai", "rugby");
        ScriptResult operatorLeafGreen = runScript(tmp, operatorLeafNamed, "postflight");
        assertEquals(0, operatorLeafGreen.exit(),
                "a live operator leaf must GREEN the postflight:\n" + operatorLeafGreen.out());
        assertTrue(operatorLeafGreen.out().contains("GREEN: bootstrap-sources.json tags[] all name tag-tree nodes"),
                operatorLeafGreen.out());
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

    // ---------- reconcile-file: the D-2 runtime-file conversion (acceptance 1) ----------

    @Test
    void reconcileFileConvertsLegacySourceTags(@TempDir Path tmp) throws Exception {
        Path fixture = writeBootstrapFixture(tmp, "Development", "Java", "AI-Image", "Video", "AI");
        String original = Files.readString(fixture);

        writeRulings(tmp);
        ScriptResult refused = runScript(tmp, fixture, "reconcile-file");
        assertEquals(2, refused.exit(), "an unruled unknown must refuse with exit 2: " + refused.out());
        assertTrue(refused.out().contains("unknown name 'ai-image' has no ruling"),
                "the refusal names the uncovered name: " + refused.out());
        assertEquals(original, Files.readString(fixture),
                "the refusal must leave the runtime file byte-identical");

        writeRulings(tmp, "ai-image: ai");
        ScriptResult dry = runScript(tmp, fixture, "reconcile-file", "--dry-run");
        assertEquals(0, dry.exit(), "dry-run must succeed: " + dry.out());
        assertTrue(dry.out().contains("Development -> software-development"),
                "a V84 mapping key converts to its leaf: " + dry.out());
        assertTrue(dry.out().contains("Java -> software-development"), dry.out());
        assertTrue(dry.out().contains("Video -> drop"), "the ruled disposal: " + dry.out());
        assertTrue(dry.out().contains("AI -> ai"), "a normalized leaf is kept: " + dry.out());
        assertTrue(dry.out().contains("AI-Image -> ai"), "the operator's written ruling: " + dry.out());
        assertEquals(original, Files.readString(fixture),
                "dry-run must leave the runtime file byte-identical");
    }

    // ---------- reconcile-file: deterministic apply + idempotence (acceptance 2) ----------

    @Test
    void reconcileFileApplyIsDeterministicIdempotentAndBytePreserving(@TempDir Path tmp) throws Exception {
        runV84();
        Path fixture = writeBootstrapFixture(tmp, "Development", "Java", "AI-Image", "Video", "AI",
                "ML-Ops");
        String original = Files.readString(fixture);
        writeRulings(tmp, "ai-image: ai", "ml-ops: drop");

        ScriptResult real = runScript(tmp, fixture, "reconcile-file");
        assertEquals(0, real.exit(), "the real run must succeed: " + real.out());
        assertTrue(real.out().contains("Development -> software-development"),
                "the real run prints the conversion table: " + real.out());
        assertTrue(real.out().contains("ML-Ops -> drop"), "the drop ruling's table line: " + real.out());
        assertTrue(real.out().contains("consumed rulings"), real.out());
        assertTrue(real.out().contains("ai-image: ai"), "the consumed line prints for retirement: " + real.out());
        assertTrue(real.out().contains("ml-ops: drop"),
                "the drop ruling prints as consumed for retirement: " + real.out());

        String rewritten = Files.readString(fixture);
        String expected = original.replace(
                "\"tags\": [\"Development\", \"Java\", \"AI-Image\", \"Video\", \"AI\", \"ML-Ops\"]",
                "\"tags\": [\"software-development\", \"ai\"]");
        assertEquals(expected, rewritten,
                "exactly the tags[] span changes — mapping dedup, order-preserved, normalized forms,"
                        + " the drop-ruled element removed; every non-tags byte survives");

        ScriptResult post = runScript(tmp, fixture, "postflight");
        assertEquals(0, post.exit(), "the postflight file predicate must be GREEN:\n" + post.out());
        assertTrue(post.out().contains("GREEN: bootstrap-sources.json tags[] all name tag-tree nodes"),
                post.out());

        ScriptResult stale = runScript(tmp, fixture, "reconcile-file");
        assertEquals(2, stale.exit(), "the unretired re-run must refuse the consumed line: " + stale.out());
        assertTrue(stale.out().contains("ai-image: ai"), "the refusal names the stale line: " + stale.out());
        assertEquals(rewritten, Files.readString(fixture), "the refusal changes nothing");

        writeRulings(tmp);
        ScriptResult retired = runScript(tmp, fixture, "reconcile-file");
        assertEquals(0, retired.exit(), "the post-retirement run is a clean no-op: " + retired.out());
        assertTrue(retired.out().contains("no changes"), retired.out());
        assertEquals(rewritten, Files.readString(fixture), "the no-op changes nothing");
    }

    // ---------- reconcile-file: invalid rulings + unreadable spans (acceptance 3) ----------

    @Test
    void reconcileFileRefusesInvalidRulingsAndUnreadableSpans(@TempDir Path tmp) throws Exception {
        Path fixture = writeBootstrapFixture(tmp, "AI-Image");
        String original = Files.readString(fixture);

        assertReconcileRefused(tmp, fixture, original, "duplicate ruling for 'ai-image'",
                "ai-image: ai\nai-image: drop\n");
        assertReconcileRefused(tmp, fixture, original, "ghost: drop",
                "ai-image: ai\nghost: drop\n");
        assertReconcileRefused(tmp, fixture, original, "malformed rulings line 1: ai-image drop",
                "ai-image drop\n");
        assertReconcileRefused(tmp, fixture, original, "malformed rulings line 2: *: drop",
                "ai-image: ai\n*: drop\n");
        assertReconcileRefused(tmp, fixture, original, "no seeded tree leaf",
                "ai-image: sport\n");

        Path multi = tmp.resolve("bootstrap-sources-multi.json");
        Files.writeString(multi, "[\n  {\n    \"kind\": \"rss\",\n"
                + "    \"identifier\": \"https://cutover.example.test/multi/feed.xml\",\n"
                + "    \"name\": \"Multi-span source\",\n"
                + "    \"category\": \"news\",\n"
                + "    \"tags\": [\n      \"ai\"\n    ]\n  }\n]\n");
        ScriptResult span = runScript(tmp, multi, "reconcile-file");
        assertEquals(2, span.exit(), "an unreadable span must fail loud, never unseen: " + span.out());
        assertTrue(span.out().contains("cannot read every \"tags\""), span.out());
    }

    @Test
    void prettyPrintedRuntimeFileFailsWithTheRealRemedy(@TempDir Path tmp) throws Exception {
        Path fixture = writePrettyBootstrapFixture(tmp);

        runV84();
        ScriptResult post = runScript(tmp, fixture, "postflight");
        ScriptResult reconcile = runScript(tmp, fixture, "reconcile-file", "--dry-run");

        for (ScriptResult refused : List.of(post, reconcile)) {
            assertEquals(2, refused.exit(), "a pretty-printed tags span must fail loud: " + refused.out());
            assertTrue(refused.out().contains("cannot read every \"tags\""), refused.out());
            assertTrue(refused.out().contains("restore the one-array-per-line shape"), refused.out());
            assertTrue(refused.out().contains("every \"tags\": [...] span on a single line"), refused.out());
            assertFalse(refused.out().contains("install jq"), refused.out());
        }
    }

    /** One invalid rulings-file shape: exit 2 naming the line, the runtime file untouched. */
    private void assertReconcileRefused(Path tmp, Path fixture, String original,
            String expectedMessage, String rulingsContent) throws Exception {
        Files.writeString(mapFile(tmp), rulingsContent);
        ScriptResult r = runScript(tmp, fixture, "reconcile-file");
        assertEquals(2, r.exit(), "the invalid shape must refuse with exit 2: " + r.out());
        assertTrue(r.out().contains(expectedMessage), "the refusal must name the line: " + r.out());
        assertEquals(original, Files.readString(fixture), "the runtime file stays untouched: " + r.out());
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

    /** A valid bootstrap-sources.json fixture whose tags[] span defeats the cutover parser. */
    private Path writePrettyBootstrapFixture(Path tmp) throws Exception {
        Path fixture = tmp.resolve("bootstrap-sources-pretty.json");
        Files.writeString(fixture,
                "[\n  {\n    \"kind\": \"rss\",\n"
                        + "    \"identifier\": \"https://cutover.example.test/pretty/feed.xml\",\n"
                        + "    \"name\": \"Pretty Cutover IT source\",\n"
                        + "    \"category\": \"news\",\n"
                        + "    \"tags\": [\n      \"ai\",\n      \"world\"\n    ]\n  }\n]\n");
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

    /** An operator RENAME of a seeded leaf to a mirror-unknown name: the row keeps
     *  node_kind/parent/fallback, so the postflight census rows are untouched while the
     *  live leaf set gains a name the script's frozen IS_LEAF mirror does not know. */
    private void renameSeededLeaf(String from, String to) throws Exception {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement("UPDATE tag SET name = ? WHERE name = ?")) {
            ps.setString(1, to);
            ps.setString(2, from);
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
