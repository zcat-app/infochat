package app.zcat.infochat.provider.chat.tool.eval;

import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.provider.chat.tool.SemanticSearchTool;
import app.zcat.infochat.provider.chat.tool.TemporalExpressionParser;
import app.zcat.infochat.provider.chat.tool.eval.RetrievalGoldenSetLoader.GoldenRow;
import app.zcat.infochat.provider.translation.QueryTranslationCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Operator-run retrieval eval (M1-929): executes the world-selected golden
 * set (RetrievalEvalWorlds, M1-950) through the PRODUCTION
 * SemanticSearchTool bean on the operator-named test DB.
 * <pre>{@code
./mvnw verify -pl infochat-provider \
  -Dit.test=RetrievalEvalRunnerIT -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dretrieval.eval.tag.excluded= \
  -Deval.db.url=jdbc:postgresql://127.0.0.1:15432/infochat \
  -Deval.db.username=infochat_provider -Deval.db.password=&lt;secret&gt; \
  -Deval.owner.db.password=&lt;owner-secret&gt; \
  -Deval.embeddings.base-url=http://127.0.0.1:&lt;embed-port&gt;/v1 \
  -Deval.llm.base-url=http://127.0.0.1:&lt;gen-port&gt;/v1
}</pre>
 * World selection (M1-950): -Deval.world=tech (default; the frozen set,
 * byte-identical to the pre-M1-950 behavior) or fam (the fam replica set
 * via the replica's eval.db.url); an unknown name refuses, never falls back.
 * Window arm (M1-957): en rows ride the PRODUCTION temporal parse at the
 * world's pinned now (one Clock.fixed instant drives the parse and the
 * tool's cutoff alike); parse misses and non-en rows dispatch unchanged.
 * Never in the default suite: @Tag("retrieval-eval") + the POM's failsafe
 * excludedGroups (engineering-rules §8/§5); stack bring-up and the
 * residual-divergence disclosure live in .bench/retrieval-eval/README.md.
 * Self-checks abort, never silently degrade: DB-fingerprint drift between
 * passes or vs the labels (D19/P7 — reported, not scored); double-run
 * uid-list identity; en records issue zero translator calls (D58); a
 * non-zero xling fallback count aborts scoring (M1-859 rule, P8).
 * Artifacts land under .bench/retrieval-eval/&lt;world leaf&gt;/&lt;ts&gt;/ (manifest,
 * queries, scores). No DB writes at all: the query-anchor cache is
 * in-memory (Caffeine) and the tool path performs no DML.
 */
@QuarkusTest
@Tag("retrieval-eval")
@TestProfile(RetrievalEvalRunnerIT.EvalProfile.class)
class RetrievalEvalRunnerIT {

    /** The %test sentinel (M1-644/M1-650); a profile override that lost to
     * it means no real endpoint is wired. */
    private static final String TEST_SENTINEL = "http://localhost:9";

    /**
     * The five eval scopes; scripts/eval-scopes-seed.sql is the source of
     * truth and the boot assertion below fails the run on any drift.
     * Package-private: RetrievalEvalCharacterizationIT dispatches the
     * same scopes (M1-945).
     */
    static final Map<String, UUID> EVAL_SCOPES = Map.of(
            "en", UUID.fromString("99a41442-61e2-4c48-962d-26092c3995a7"),
            "cs", UUID.fromString("1213f0bd-723c-41ff-8d3e-89aaaf00dca4"),
            "es", UUID.fromString("f568a11b-ca60-436a-832d-ec24a55bfe88"),
            "ru", UUID.fromString("d7fb2b75-29e0-46ff-93cb-93fa055d953e"),
            "tr", UUID.fromString("5e2578ce-c5c6-4bc3-9b66-e392802090b8"));

    /** armToolConnection registers an in-memory cancellation handle only; no users row is read. */
    static final UUID EVAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000929");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Renders max ready_at exactly as the labels' fingerprint strings do (Postgres microsecond text form, UTC). */
    private static final DateTimeFormatter FINGERPRINT_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS+00").withZone(ZoneOffset.UTC);

    /**
     * Boots the production wiring against the operator-named stack under
     * the {@code eval} config profile: devservices off, JDBC/endpoints
     * from eval.* system properties, the boot-critical %test keys
     * reconstructed as overrides (adapters, digest scheduler, owner DS).
     * The %eval bean exclusion in the test application.properties removes
     * the M1-644 stubs from this boot's re-augmentation; runStart asserts
     * they are really gone.
     */
    public static class EvalProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "eval";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("quarkus.datasource.devservices.enabled", "false");
            c.put("quarkus.datasource.db-kind", "postgresql");
            c.put("quarkus.datasource.jdbc.url", "${eval.db.url}");
            c.put("quarkus.datasource.username", "${eval.db.username}");
            c.put("quarkus.datasource.password", "${eval.db.password}");
            c.put("quarkus.datasource.owner.db-kind", "postgresql");
            c.put("quarkus.datasource.owner.devservices.enabled", "false");
            c.put("quarkus.datasource.owner.jdbc.url", "${quarkus.datasource.jdbc.url}");
            c.put("quarkus.datasource.owner.username", "${eval.owner.db.username:infochat}");
            c.put("quarkus.datasource.owner.password", "${eval.owner.db.password:infochat-dev}");
            c.put("quarkus.flyway.owner.migrate-at-start", "false");
            c.put("infochat.adapters", "inmemory");
            c.put("infochat.adapters.inmemory.allow-low-trust", "true");
            c.put("infochat.adapters.inmemory.admin", "test-bootstrap-contact");
            c.put("infochat.digest.tick-interval", "off");
            c.put("infochat.embeddings.base-url", "${eval.embeddings.base-url}");
            c.put("infochat.embeddings.model", "${eval.embeddings.model:nomic-embed-text}");
            c.put("infochat.llm.default.base-url", "${eval.llm.base-url}");
            return c;
        }
    }

    record QueryOutcome(List<RetrievalEvalScorer.ToolRow> rows, String cacheState, String anchoredText,
                        double translatorCallDelta, String window) {}

    @Inject
    SemanticSearchTool tool;
    @Inject
    QueryTranslationCache anchorCache;
    @Inject
    MeterRegistry registry;
    @Inject
    DataSource dataSource;
    @Inject
    jakarta.enterprise.inject.Instance<app.zcat.infochat.llm.EmbeddingProvider> embeddingProviders;
    @Inject
    jakarta.enterprise.inject.Instance<app.zcat.infochat.llm.LlmProvider> llmProviders;
    @ConfigProperty(name = "infochat.chat.semantic-threshold")
    double threshold;
    @ConfigProperty(name = "infochat.chat.semantic-limit")
    int limit;
    @ConfigProperty(name = "infochat.chat.tool.input-max-length", defaultValue = "500")
    int inputMaxLength;
    @ConfigProperty(name = "infochat.embeddings.base-url")
    String embeddingsBaseUrl;
    @ConfigProperty(name = "infochat.embeddings.model")
    String embeddingsModel;
    @ConfigProperty(name = "infochat.llm.default.base-url")
    String llmBaseUrl;
    @ConfigProperty(name = "infochat.llm.translator.model")
    String translatorModel;

    @Test
    void runGoldenSetThroughProductionTool() throws Exception {
        assertEndpointsAreReal(embeddingsBaseUrl, llmBaseUrl);
        assertNoTestStubsSelected(embeddingProviders, llmProviders);
        // Resolved once; the resource read, the results leaf, and the
        // manifest world keys all ride this single world selection.
        RetrievalEvalWorlds.World world = RetrievalEvalWorlds.resolve();
        Path resultsDir = resolveResultsDir(world.resultsLeaf());
        byte[] goldenBytes = goldenSetBytes(world);
        RetrievalGoldenSetLoader.GoldenSet goldenSet = RetrievalGoldenSetLoader.load(goldenBytes);
        List<GoldenRow> golden = goldenSet.activeRows();

        Map<String, String> scopeLangs = assertEvalScopesSeeded(dataSource);
        String fingerprint1 = dbFingerprint(dataSource);
        // One pinned instant drives BOTH the arm's parse and the tool's
        // ready_at cutoff (llm.md §Determinism boundary; §9) — a wall
        // clock would empty every short window on this frozen world.
        Instant worldNow = worldMaxReadyAt(fingerprint1);
        QuarkusMock.installMockForType(Clock.fixed(worldNow, ZoneOffset.UTC), Clock.class);
        Map<String, QueryOutcome> pass1 = new LinkedHashMap<>();
        // Deduplicated across the two determinism passes: it names RECORDS,
        // not executions (per-pass state lives in queries.jsonl's cache field).
        java.util.Set<String> fallbackRecords = new java.util.LinkedHashSet<>();
        for (GoldenRow row : golden) {
            pass1.put(row.id(), executeGolden(row, scopeLangs, fallbackRecords, worldNow));
        }
        String fingerprint2 = dbFingerprint(dataSource);
        Map<String, QueryOutcome> pass2 = new LinkedHashMap<>();
        for (GoldenRow row : golden) {
            pass2.put(row.id(), executeGolden(row, scopeLangs, fallbackRecords, worldNow));
        }

        boolean labelMatch = golden.stream()
                .allMatch(r -> r.labeledFingerprint().equals(fingerprint1));
        writeArtifacts(resultsDir, world, goldenSet, pass1, pass2, fingerprint1, fingerprint2,
                labelMatch, fallbackRecords, worldNow);

        assertNoInterPassDrift(fingerprint1, fingerprint2);
        assertLabelFingerprintMatch(labelMatch, fingerprint1);
        Map<String, List<String>> uids1ByRecord = new LinkedHashMap<>();
        Map<String, List<String>> uids2ByRecord = new LinkedHashMap<>();
        for (GoldenRow row : golden) {
            uids1ByRecord.put(row.id(), pass1.get(row.id()).rows().stream()
                    .map(RetrievalEvalScorer.ToolRow::uid).toList());
            uids2ByRecord.put(row.id(), pass2.get(row.id()).rows().stream()
                    .map(RetrievalEvalScorer.ToolRow::uid).toList());
        }
        assertDeterminismIdentity(uids1ByRecord, uids2ByRecord);
        List<String> enCalled = new ArrayList<>();
        for (GoldenRow row : golden) {
            if ("en".equals(row.scopeLang())
                    && (pass1.get(row.id()).translatorCallDelta() != 0
                    || pass2.get(row.id()).translatorCallDelta() != 0)) {
                enCalled.add(row.id());
            }
        }
        assertEnLegsIssuedZeroTranslatorCalls(enCalled);
        assertZeroTranslatorFallbacks(fallbackRecords);

        RetrievalEvalScorer.Scores scores = RetrievalEvalScorer.score(
                golden.stream().map(GoldenRow::toScorerRecord).toList(),
                pass1.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().rows())),
                worldNow, limit);
        writeScores(resultsDir, scores, worldNow, fingerprint1);
    }

    private QueryOutcome executeGolden(GoldenRow row, Map<String, String> scopeLangs,
                                       java.util.Set<String> fallbackRecords, Instant worldNow)
            throws Exception {
        UUID scopeId = EVAL_SCOPES.get(row.scopeLang());
        boolean xling = !"en".equals(row.scopeLang());
        var cachedBefore = xling
                ? anchorCache.get(row.query(), row.scopeLang(), "dm", scopeId)
                : java.util.Optional.<String>empty();
        // The window arm mirrors the dispatch layer (commands.md §Chat
        // mode, D19): parse-GATED through the PRODUCTION parser, so a
        // miss or a non-en row keeps exactly the pre-arm dispatch.
        String window = null;
        Map<String, Object> args = Map.of("query", row.query());
        if (!xling) {
            var parsed = TemporalExpressionParser.parse(row.query(), ZoneOffset.UTC, worldNow);
            if (parsed.isPresent()) {
                window = parsed.get().window().toString();
                args = Map.of("query", row.query(), "_window", window);
            }
        }
        double callsBefore = translatorCallCount();
        String json = tool.execute(EVAL_USER_ID, "dm", scopeId, args);
        double delta = translatorCallCount() - callsBefore;
        List<RetrievalEvalScorer.ToolRow> rows = RetrievalEvalScorer.parseToolJson(json);
        String cacheState;
        String anchored = null;
        if (!xling) {
            cacheState = "na-en-noop";
        } else if (cachedBefore.isPresent()) {
            cacheState = "hit";
            anchored = cachedBefore.orElseThrow();
        } else {
            var cachedAfter = anchorCache.get(row.query(), row.scopeLang(), "dm", scopeId);
            if (cachedAfter.isPresent()) {
                cacheState = "miss-then-cached";
                anchored = cachedAfter.orElseThrow();
            } else {
                cacheState = "fallback";
                fallbackRecords.add(row.id());
            }
        }
        return new QueryOutcome(rows, cacheState, anchored, delta, window);
    }

    private double translatorCallCount() {
        return translatorCallCount(registry);
    }

    // ---- Shared bring-up guards, fences, and helpers (M1-945): the
    // characterization IT rides the same boot checks and the same named
    // refusals — never a private copy that could drift.

    static void assertEndpointsAreReal(String embeddingsBaseUrl, String llmBaseUrl) {
        if (TEST_SENTINEL.equals(embeddingsBaseUrl) || TEST_SENTINEL.equals(llmBaseUrl)) {
            fail("eval profile did not win over the %test sentinel endpoints — pass "
                    + "-Deval.embeddings.base-url / -Deval.llm.base-url (see the class javadoc)");
        }
    }

    static void assertNoTestStubsSelected(
            jakarta.enterprise.inject.Instance<app.zcat.infochat.llm.EmbeddingProvider> embeddingProviders,
            jakarta.enterprise.inject.Instance<app.zcat.infochat.llm.LlmProvider> llmProviders) {
        if (embeddingProviders.stream()
                .anyMatch(b -> b instanceof app.zcat.infochat.provider.testing.StubEmbeddingProvider)
                || llmProviders.stream()
                .anyMatch(b -> b instanceof app.zcat.infochat.provider.testing.TestLlmProvider)) {
            fail("test-classpath stubs are still selected — the %eval profile's "
                    + "quarkus.arc.exclude-types entry did not reach this boot's "
                    + "augmentation (see the provider test application.properties)");
        }
    }

    static void assertNoInterPassDrift(String fingerprint1, String fingerprint2) {
        if (!fingerprint1.equals(fingerprint2)) {
            fail("DB fingerprint drift between passes (pass1=" + fingerprint1
                    + " pass2=" + fingerprint2 + ") — corpus moved mid-run; NOT scored, "
                    + "re-run on a frozen stack (D19/P7)");
        }
    }

    static boolean labelFingerprintMatches(List<GoldenRow> golden, String runFingerprint) {
        return golden.stream().allMatch(r -> r.labeledFingerprint().equals(runFingerprint));
    }

    static void assertLabelFingerprintMatch(boolean labelMatch, String fingerprint1) {
        if (!labelMatch) {
            fail("DB fingerprint drift against the labels (run=" + fingerprint1
                    + ") — scores refused; re-baseline requires a supersedes relabel "
                    + "(M1-928 freeze, P7)");
        }
    }

    static void assertDeterminismIdentity(Map<String, List<String>> uids1, Map<String, List<String>> uids2) {
        List<String> divergent = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : uids1.entrySet()) {
            if (!e.getValue().equals(uids2.get(e.getKey()))) {
                divergent.add(e.getKey());
            }
        }
        if (!divergent.isEmpty()) {
            fail("determinism self-check failed — pass1/pass2 uid lists differ for "
                    + divergent + " on an unchanged fingerprint (D19)");
        }
    }

    static void assertEnLegsIssuedZeroTranslatorCalls(List<String> offenders) {
        if (!offenders.isEmpty()) {
            fail("en-scope records issued translator calls (" + offenders
                    + ") — the en leg must be a strict no-op (D58)");
        }
    }

    static void assertZeroTranslatorFallbacks(java.util.Set<String> fallbackRecords) {
        if (!fallbackRecords.isEmpty()) {
            fail("translator fallback on " + fallbackRecords.size() + " cross-lingual record(s): "
                    + fallbackRecords + " — scoring aborted, never silently degraded "
                    + "(M1-859 counted-fallback rule, P8)");
        }
    }

    static double translatorCallCount(MeterRegistry registry) {
        return registry.find("llm.calls.total")
                .tags("task", ModelTask.TRANSLATOR.keySegment())
                .counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private byte[] goldenSetBytes(RetrievalEvalWorlds.World world) throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(world.resource())) {
            if (in == null) {
                throw new IllegalStateException(world.resource() + " not on the test classpath (M1-928)");
            }
            return in.readAllBytes();
        }
    }

    /** Fail the run unless every eval scope's declared language matches the seed script's map. */
    static Map<String, String> assertEvalScopesSeeded(DataSource dataSource) throws Exception {
        Map<String, String> langs = new TreeMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT language FROM scope_preferences WHERE scope_kind = 'dm' AND scope_id = ?")) {
            for (Map.Entry<String, UUID> e : EVAL_SCOPES.entrySet()) {
                ps.setObject(1, e.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        fail("eval scope " + e.getKey() + " (" + e.getValue()
                                + ") is not seeded — run scripts/eval-scopes-seed.sql "
                                + "against the target DB (M1-928)");
                    }
                    langs.put(e.getKey(), rs.getString("language"));
                }
            }
        }
        for (Map.Entry<String, String> e : langs.entrySet()) {
            if (!e.getKey().equals(e.getValue())) {
                fail("eval scope language mismatch: scope " + e.getKey() + " declares "
                        + e.getValue() + " — EVAL_SCOPES must match scripts/eval-scopes-seed.sql");
            }
        }
        return langs;
    }

    record Fingerprint(long readyCount, Instant maxReadyAt, String uidSha256) {
        String render() {
            return "ready=" + readyCount + ";max_ready_at=" + FINGERPRINT_INSTANT.format(maxReadyAt)
                    + ";uid_sha256=" + uidSha256;
        }
    }

    /**
     * World fingerprint over the D59 world shared by the eval scopes (zero
     * subscriptions/exclusions: live, non-excluded bootstrap sources).
     * Mirrors SearchPostsTool.worldPredicateSql, which is package-private
     * and cannot be reused from this package; a drift between the two
     * surfaces as label-fingerprint drift, never silently.
     */
    private static final String WORLD_WHERE =
            "p.status = 'READY' AND (EXISTS (SELECT 1 FROM source s_w"
                    + " WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'"
                    + " AND s_w.deleted_at IS NULL"
                    + " AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w"
                    + " WHERE e_w.scope_kind = ? AND e_w.scope_id = ? AND e_w.source_id = s_w.id))"
                    + " OR p.source_id IN (SELECT source_id FROM source_subscription"
                    + " WHERE scope_kind = ? AND scope_id = ?))";

    static String dbFingerprint(DataSource dataSource) throws Exception {
        UUID scope = EVAL_SCOPES.get("en");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long count = 0;
        Instant maxReadyAt = null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement countPs = conn.prepareStatement(
                     "SELECT count(*), max(ready_at) FROM post p WHERE " + WORLD_WHERE);
             PreparedStatement uidPs = conn.prepareStatement(
                     "SELECT uid, ready_at FROM post p WHERE " + WORLD_WHERE + " ORDER BY uid")) {
            for (PreparedStatement ps : List.of(countPs, uidPs)) {
                ps.setString(1, "dm");
                ps.setObject(2, scope);
                ps.setString(3, "dm");
                ps.setObject(4, scope);
            }
            try (ResultSet rs = countPs.executeQuery()) {
                rs.next();
                count = rs.getLong(1);
                maxReadyAt = rs.getTimestamp(2).toInstant();
            }
            try (ResultSet rs = uidPs.executeQuery()) {
                while (rs.next()) {
                    sha.update(rs.getString("uid").getBytes(StandardCharsets.UTF_8));
                    if (rs.getTimestamp("ready_at").toInstant().isAfter(maxReadyAt)) {
                        maxReadyAt = rs.getTimestamp("ready_at").toInstant();
                    }
                }
            }
        }
        return new Fingerprint(count, maxReadyAt,
                HexFormat.of().formatHex(sha.digest())).render();
    }

    // The manifest's world_embedding_coverage: READY-world posts with a
    // post_embedding row vs total over the RUN's DB — a PIN of the run's
    // state, not an invariant (analysis P6; M1-952 registers the rule).
    static Map<String, Object> worldEmbeddingCoverage(DataSource dataSource) throws Exception {
        UUID scope = EVAL_SCOPES.get("en");
        long total;
        long withEmbedding;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*), count(*) FILTER (WHERE EXISTS"
                             + " (SELECT 1 FROM post_embedding pe WHERE pe.post_id = p.id))"
                             + " FROM post p WHERE " + WORLD_WHERE)) {
            ps.setString(1, "dm");
            ps.setObject(2, scope);
            ps.setString(3, "dm");
            ps.setObject(4, scope);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
                withEmbedding = rs.getLong(2);
            }
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("with_embedding", withEmbedding);
        coverage.put("total", total);
        return coverage;
    }

    private static Instant worldMaxReadyAt(String fingerprint) {
        String rendered = fingerprint.substring(fingerprint.indexOf("max_ready_at=") + 13,
                fingerprint.indexOf(";uid_sha256="));
        return Instant.parse(rendered.replace(" ", "T").replaceFirst("\\+00$", "Z"));
    }

    static Path resolveResultsDir(String leaf) throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found above " + System.getProperty("user.dir"));
        }
        Path results = dir.resolve(".bench/retrieval-eval/" + leaf + "/"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()));
        Files.createDirectories(results);
        return results;
    }

    private void writeArtifacts(Path dir, RetrievalEvalWorlds.World world,
                                RetrievalGoldenSetLoader.GoldenSet goldenSet,
                                Map<String, QueryOutcome> pass1, Map<String, QueryOutcome> pass2,
                                String fingerprint1, String fingerprint2,
                                boolean labelMatch, java.util.Set<String> fallbackRecords,
                                Instant worldNow) throws Exception {
        List<GoldenRow> golden = goldenSet.activeRows();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("ts", Instant.now().toString());
        manifest.put("git_commit", gitCommit());
        manifest.put("world", world.name());
        manifest.put("golden_set_resource", world.resource());
        manifest.put("db_fingerprint_pass1", fingerprint1);
        manifest.put("db_fingerprint_pass2", fingerprint2);
        manifest.put("label_fingerprint_match", labelMatch);
        manifest.put("translator_fallback_records", fallbackRecords);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("semantic_threshold", threshold);
        config.put("semantic_limit", limit);
        config.put("input_max_length", inputMaxLength);
        config.put("embeddings_base_url", embeddingsBaseUrl);
        config.put("embeddings_model", embeddingsModel);
        config.put("llm_default_base_url", llmBaseUrl);
        config.put("translator_model", translatorModel);
        manifest.put("config", config);
        manifest.put("golden_set_records", golden.size());
        manifest.put("golden_set_sha256", goldenSet.contentSha256());
        manifest.put("golden_set_active_records", golden.size());
        manifest.put("golden_set_retired_records", goldenSet.retiredCount());
        manifest.put("world_embedding_coverage", worldEmbeddingCoverage(dataSource));
        manifest.put("window_arm", true);
        manifest.put("window_zone", ZoneOffset.UTC.getId());
        manifest.put("world_now", worldNow.toString());
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("manifest.json").toFile(), manifest);

        try (var out = Files.newBufferedWriter(dir.resolve("queries.jsonl"))) {
            for (int pass = 1; pass <= 2; pass++) {
                for (GoldenRow row : golden) {
                    QueryOutcome o = pass == 1 ? pass1.get(row.id()) : pass2.get(row.id());
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("pass", pass);
                    line.put("id", row.id());
                    line.put("class", row.clazz());
                    line.put("scope_lang", row.scopeLang());
                    line.put("cache", o.cacheState());
                    line.put("anchored_text", o.anchoredText());
                    line.put("window", o.window());
                    line.put("translator_call_delta", o.translatorCallDelta());
                    line.put("returned", o.rows().stream()
                            .map(RetrievalEvalScorer.ToolRow::uid).toList());
                    com.fasterxml.jackson.databind.node.ArrayNode similarity =
                            MAPPER.createArrayNode();
                    for (RetrievalEvalScorer.ToolRow toolRow : o.rows()) {
                        similarity.add(toolRow.similarity() == null
                                ? NullNode.getInstance()
                                : com.fasterxml.jackson.databind.node.DoubleNode
                                        .valueOf(toolRow.similarity()));
                    }
                    line.put("similarity", similarity);
                    line.put("ready_at", o.rows().stream()
                            .map(RetrievalEvalScorer.ToolRow::readyAt).toList());
                    out.write(MAPPER.writeValueAsString(line));
                    out.write('\n');
                }
            }
        }
    }

    private void writeScores(Path dir, RetrievalEvalScorer.Scores scores,
                             Instant worldNow, String fingerprint) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db_fingerprint", fingerprint);
        out.put("world_now", worldNow.toString());
        out.put("scores", scores);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("scores.json").toFile(), out);
    }

    static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
