package app.zcat.infochat.provider.chat.tool.eval;

import app.zcat.infochat.provider.chat.tool.SemanticSearchTool;
import app.zcat.infochat.provider.chat.tool.eval.RetrievalGoldenSetLoader.GoldenRow;
import app.zcat.infochat.provider.translation.QueryTranslationCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Operator-run anchor-leg characterization (M1-945): three arms per xling row ride the PRODUCTION tool; fences run UNDER it.
 * <pre>{@code
 ./mvnw verify -pl infochat-provider \
   -Dit.test=RetrievalEvalCharacterizationIT -Dfailsafe.failIfNoSpecifiedTests=false \
   -Dretrieval.eval.tag.excluded= \
   -Deval.db.url=jdbc:postgresql://127.0.0.1:15432/infochat \
   -Deval.db.username=infochat_provider -Deval.db.password=&lt;secret&gt; \
   -Deval.owner.db.password=&lt;owner-secret&gt; \
   -Deval.embeddings.base-url=http://127.0.0.1:&lt;embed-port&gt;/v1 \
   -Deval.llm.base-url=http://127.0.0.1:&lt;gen-port&gt;/v1
 }</pre>
 * Same gating as RetrievalEvalRunnerIT; artifacts under characterization/&lt;ts&gt;/; run twice (record restates).
 */
@QuarkusTest
@Tag("retrieval-eval")
@TestProfile(RetrievalEvalRunnerIT.EvalProfile.class)
class RetrievalEvalCharacterizationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Authored canonical phrasings (P8): committed fixtures, never translator output — D58 (d). */
    private static final Map<String, String> CANONICAL_BY_NEED = Map.of(
            "top-ai-b", "latest artificial intelligence news",
            "top-cyber-b", "latest cybersecurity news",
            "top-crypto", "latest cryptocurrency news");

    private static final String SCOPE_KIND = "dm";

    record ArmOutcome(List<RetrievalEvalScorer.ToolRow> rows, String dispatchedQuery,
                      String anchoredText, String cacheState, double translatorCallDelta) {
        List<String> uids() {
            return rows.stream().map(RetrievalEvalScorer.ToolRow::uid).toList();
        }
    }

    record Pair(String xlingId, String siblingId, String lang, GoldenRow xling, GoldenRow sibling) {}

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
    void threeArmsRideTheProductionTool() throws Exception {
        RetrievalEvalRunnerIT.assertEndpointsAreReal(embeddingsBaseUrl, llmBaseUrl);
        RetrievalEvalRunnerIT.assertNoTestStubsSelected(embeddingProviders, llmProviders);
        Path resultsDir = RetrievalEvalRunnerIT.resolveResultsDir("characterization");
        byte[] goldenBytes = goldenSetBytes();
        var goldenSet = RetrievalGoldenSetLoader.load(goldenBytes);
        List<Pair> pairs = pairsWithSiblings(goldenSet, goldenBytes);
        assertEquals(12, pairs.size(), "the frozen set carries 12 active cross-lingual rows");

        RetrievalEvalRunnerIT.assertEvalScopesSeeded(dataSource);
        Map<String, Map<String, ArmOutcome>> invocations = new LinkedHashMap<>();
        List<String> fingerprints = new ArrayList<>();
        Set<String> fallbackRecords = new java.util.LinkedHashSet<>();
        for (int invocation = 1; invocation <= 2; invocation++) {
            fingerprints.add(RetrievalEvalRunnerIT.dbFingerprint(dataSource));
            Map<String, ArmOutcome> outcomes = new LinkedHashMap<>();
            for (Pair pair : pairs) {
                outcomes.put(legKey(pair, "sibling"), dispatchEnScoped(
                        pair.sibling().query(), "sibling " + pair.siblingId()));
                outcomes.put(legKey(pair, "A"), dispatchAnchored(
                        pair.xling(), pair.lang(), fallbackRecords));
                outcomes.put(legKey(pair, "B"), dispatchEnScoped(
                        CANONICAL_BY_NEED.get(pair.siblingId()), "canonical " + pair.siblingId()));
                outcomes.put(legKey(pair, "C"), dispatchEnScoped(
                        pair.xling().query(), "raw-source " + pair.xlingId()));
            }
            invocations.put("invocation" + invocation, outcomes);
        }
        fingerprints.add(RetrievalEvalRunnerIT.dbFingerprint(dataSource));

        boolean labelMatch = RetrievalEvalRunnerIT.labelFingerprintMatches(
                pairRows(pairs), fingerprints.get(0));
        writeArtifacts(resultsDir, goldenSet, pairs, invocations, fingerprints, labelMatch);

        // Fences UNDER the characterization (M1-945 controls): the runner's
        // named refusals, shared statics, identical messages.
        RetrievalEvalRunnerIT.assertNoInterPassDrift(fingerprints.get(0), fingerprints.get(1));
        RetrievalEvalRunnerIT.assertNoInterPassDrift(fingerprints.get(1), fingerprints.get(2));
        RetrievalEvalRunnerIT.assertLabelFingerprintMatch(labelMatch, fingerprints.get(0));
        assertDoubleInvocationDeterminism(pairs, invocations);
        assertScopeHygiene(pairs, invocations);
        RetrievalEvalRunnerIT.assertZeroTranslatorFallbacks(fallbackRecords);
    }

    /** Fingerprint failure mode (P4/P9): mismatched labels refuse — the named refusal, never a number. */
    @Test
    void fingerprintRefusalFeedsMismatchedLabelAndRefuses() throws Exception {
        RetrievalEvalRunnerIT.assertEndpointsAreReal(embeddingsBaseUrl, llmBaseUrl);
        byte[] goldenBytes = goldenSetBytes();
        var goldenSet = RetrievalGoldenSetLoader.load(goldenBytes);
        List<Pair> pairs = pairsWithSiblings(goldenSet, goldenBytes);
        List<GoldenRow> rows = pairRows(pairs);
        GoldenRow honest = rows.get(0);
        GoldenRow corrupted = new GoldenRow(honest.id(), honest.clazz(), honest.query(),
                honest.scopeLang(), honest.noneExpected(), honest.expectedUids(),
                "ready=1;max_ready_at=1970-01-01 00:00:00.000000+00;uid_sha256=drift");
        List<GoldenRow> hostile = new ArrayList<>(rows);
        hostile.set(0, corrupted);
        String runFingerprint = RetrievalEvalRunnerIT.dbFingerprint(dataSource);

        assertFalse(RetrievalEvalRunnerIT.labelFingerprintMatches(hostile, runFingerprint),
                "one drifted label is enough to refuse the whole run");
        AssertionError refused = assertThrows(AssertionError.class,
                () -> RetrievalEvalRunnerIT.assertLabelFingerprintMatch(
                        RetrievalEvalRunnerIT.labelFingerprintMatches(hostile, runFingerprint),
                        runFingerprint));
        assertTrue(refused.getMessage().contains("scores refused"),
                "the refusal is named, never a silently degraded score: " + refused.getMessage());
    }

    // ---- double-invocation determinism leg ----

    private void assertDoubleInvocationDeterminism(List<Pair> pairs,
            Map<String, Map<String, ArmOutcome>> invocations) {
        Map<String, ArmOutcome> first = invocations.get("invocation1");
        Map<String, ArmOutcome> second = invocations.get("invocation2");
        Map<String, List<String>> uids1 = new LinkedHashMap<>();
        Map<String, List<String>> uids2 = new LinkedHashMap<>();
        for (String key : first.keySet()) {
            uids1.put(key, first.get(key).uids());
            uids2.put(key, second.get(key).uids());
        }
        RetrievalEvalRunnerIT.assertDeterminismIdentity(uids1, uids2);
        for (Pair pair : pairs) {
            String key = legKey(pair, "A");
            assertEquals(first.get(key).anchoredText(), second.get(key).anchoredText(),
                    "anchored text must be byte-identical across invocations for " + pair.xlingId());
        }
    }

    // ---- scope/cache hygiene leg (M1-945 P7) ----

    private void assertScopeHygiene(List<Pair> pairs,
            Map<String, Map<String, ArmOutcome>> invocations) {
        Map<String, ArmOutcome> first = invocations.get("invocation1");
        Map<String, ArmOutcome> second = invocations.get("invocation2");
        List<String> offenders = new ArrayList<>();
        for (Pair pair : pairs) {
            for (String arm : List.of("sibling", "B", "C")) {
                String key = legKey(pair, arm);
                // en-scoped legs are strict no-ops (D58); zero translator
                // delta proves no QueryTranslationCache entry was consulted.
                if (first.get(key).translatorCallDelta() != 0
                        || second.get(key).translatorCallDelta() != 0) {
                    offenders.add(key);
                }
            }
            String armA = legKey(pair, "A");
            assertEquals(1.0, first.get(armA).translatorCallDelta(),
                    "invocation 1 arm A takes exactly one fresh translator call (fresh boot) — "
                            + pair.xlingId());
            assertEquals(0.0, second.get(armA).translatorCallDelta(),
                    "invocation 2 arm A must be a cache hit, never a second roll — "
                            + pair.xlingId());
            assertEquals("hit", second.get(armA).cacheState(),
                    "invocation 2 arm A cache state — " + pair.xlingId());
        }
        RetrievalEvalRunnerIT.assertEnLegsIssuedZeroTranslatorCalls(offenders);
    }

    // ---- dispatch legs ----

    private ArmOutcome dispatchEnScoped(String query, String what) throws Exception {
        if (query == null || query.isBlank()) {
            fail("en-scoped leg dispatched without a query: " + what);
        }
        return dispatch(RetrievalEvalRunnerIT.EVAL_SCOPES.get("en"), query, "na-en-noop", null);
    }

    private ArmOutcome dispatchAnchored(GoldenRow row, String lang, Set<String> fallbackRecords)
            throws Exception {
        UUID scopeId = RetrievalEvalRunnerIT.EVAL_SCOPES.get(lang);
        var cachedBefore = anchorCache.get(row.query(), lang, SCOPE_KIND, scopeId);
        if (cachedBefore.isPresent()) {
            return dispatch(scopeId, row.query(), "hit", cachedBefore.orElseThrow());
        }
        ArmOutcome outcome = dispatch(scopeId, row.query(), null, null);
        var cachedAfter = anchorCache.get(row.query(), lang, SCOPE_KIND, scopeId);
        if (cachedAfter.isPresent()) {
            return new ArmOutcome(outcome.rows(), outcome.dispatchedQuery(),
                    cachedAfter.orElseThrow(), "miss-then-cached", outcome.translatorCallDelta());
        }
        fallbackRecords.add(row.id());
        return new ArmOutcome(outcome.rows(), outcome.dispatchedQuery(),
                null, "fallback", outcome.translatorCallDelta());
    }

    /** Every leg rides the PRODUCTION tool dispatch; the emission shape is asserted per dispatch. */
    private ArmOutcome dispatch(UUID scopeId, String query, String cacheState, String anchoredText)
            throws Exception {
        double callsBefore = RetrievalEvalRunnerIT.translatorCallCount(registry);
        String json = tool.execute(RetrievalEvalRunnerIT.EVAL_USER_ID, SCOPE_KIND, scopeId,
                Map.of("query", query));
        double delta = RetrievalEvalRunnerIT.translatorCallCount(registry) - callsBefore;
        assertEmissionShape(json);
        return new ArmOutcome(RetrievalEvalScorer.parseToolJson(json), query,
                anchoredText, cacheState, delta);
    }

    /** P6: rows come from the tool, asserted by the tool's own emission shape. */
    private static void assertEmissionShape(String json) {
        JsonNode arr;
        try {
            arr = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("unparseable tool emission: " + json, e);
        }
        assertTrue(arr.isArray(), "tool emission is a JSON array");
        Set<String> expectedFields = Set.of("uid", "title", "url", "ready_at", "similarity");
        for (JsonNode row : arr) {
            Set<String> fields = new HashSet<>();
            row.fieldNames().forEachRemaining(fields::add);
            assertEquals(expectedFields, fields, "every emitted row carries exactly the tool's shape");
            assertTrue(row.path("uid").asText().length() > 0, "uid is always present");
            assertTrue(row.path("similarity").isNull() || row.path("similarity").isNumber(),
                    "similarity is a number, or null on lexical-only rows");
        }
    }

    // ---- golden-set pairing ----

    /** xling rows paired with the ACTIVE English sibling their notes name (token match, M1-942 legs). */
    private static List<Pair> pairsWithSiblings(RetrievalGoldenSetLoader.GoldenSet goldenSet,
                                                byte[] goldenBytes) throws IOException {
        Map<String, String> notesById = new LinkedHashMap<>();
        Set<String> activeIds = new HashSet<>();
        for (String line : new String(goldenBytes, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = MAPPER.readTree(line);
            if (!n.path("replaced_by").isTextual()) {
                activeIds.add(n.path("id").asText());
                notesById.put(n.path("id").asText(), n.path("notes").asText(""));
            }
        }
        Map<String, GoldenRow> byId = new LinkedHashMap<>();
        goldenSet.activeRows().forEach(r -> byId.put(r.id(), r));
        List<Pair> pairs = new ArrayList<>();
        for (GoldenRow row : goldenSet.activeRows()) {
            if (!"cross-lingual".equals(row.clazz())) {
                continue;
            }
            String sibling = java.util.Arrays.stream(notesById.get(row.id()).split("\\s+"))
                    .filter(activeIds::contains).findFirst().orElse(null);
            assertTrue(sibling != null,
                    "active xling row " + row.id() + " must name an active English sibling");
            GoldenRow siblingRow = byId.get(sibling);
            assertEquals("en", siblingRow.scopeLang(), "the named sibling is an en row: " + sibling);
            assertTrue(CANONICAL_BY_NEED.containsKey(sibling),
                    "no authored canonical phrasing for need " + sibling + " — add the fixture, "
                            + "never generate one (M1-945 P8)");
            pairs.add(new Pair(row.id(), sibling, row.scopeLang(), row, siblingRow));
        }
        return pairs;
    }

    private static List<GoldenRow> pairRows(List<Pair> pairs) {
        Map<String, GoldenRow> byId = new LinkedHashMap<>();
        for (Pair pair : pairs) {
            byId.put(pair.xlingId(), pair.xling());
            byId.put(pair.siblingId(), pair.sibling());
        }
        return new ArrayList<>(byId.values());
    }

    private static String legKey(Pair pair, String arm) {
        return pair.xlingId() + "#" + arm;
    }

    // ---- artifacts (operator-local; separate from scored-run results) ----

    private void writeArtifacts(Path dir, RetrievalGoldenSetLoader.GoldenSet goldenSet,
                                List<Pair> pairs,
                                Map<String, Map<String, ArmOutcome>> invocations,
                                List<String> fingerprints, boolean labelMatch) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_type", "anchor-leg-characterization");
        manifest.put("ts", Instant.now().toString());
        manifest.put("git_commit", RetrievalEvalRunnerIT.gitCommit());
        manifest.put("db_fingerprint_invocation1", fingerprints.get(0));
        manifest.put("db_fingerprint_after_invocation1", fingerprints.get(1));
        manifest.put("db_fingerprint_after_invocation2", fingerprints.get(2));
        manifest.put("label_fingerprint_match", labelMatch);
        manifest.put("golden_set_sha256", goldenSet.contentSha256());
        manifest.put("golden_set_active_records", goldenSet.activeRows().size());
        manifest.put("golden_set_retired_records", goldenSet.retiredCount());
        manifest.put("canonical_by_need", CANONICAL_BY_NEED);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("semantic_threshold", threshold);
        config.put("semantic_limit", limit);
        config.put("input_max_length", inputMaxLength);
        config.put("embeddings_base_url", embeddingsBaseUrl);
        config.put("embeddings_model", embeddingsModel);
        config.put("llm_default_base_url", llmBaseUrl);
        config.put("translator_model", translatorModel);
        manifest.put("config", config);
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("manifest.json").toFile(), manifest);

        Map<String, ArmOutcome> first = invocations.get("invocation1");
        Map<String, ArmOutcome> second = invocations.get("invocation2");
        try (var out = Files.newBufferedWriter(dir.resolve("pairs.jsonl"))) {
            for (int invocation = 1; invocation <= 2; invocation++) {
                Map<String, ArmOutcome> outcomes = invocation == 1 ? first : second;
                for (Pair pair : pairs) {
                    List<String> siblingUids = outcomes.get(legKey(pair, "sibling")).uids();
                    for (String arm : List.of("sibling", "A", "B", "C")) {
                        ArmOutcome o = outcomes.get(legKey(pair, arm));
                        List<String> expected = pair.xling().expectedUids();
                        Map<String, Object> line = new LinkedHashMap<>();
                        line.put("invocation", invocation);
                        line.put("pair", pair.xlingId());
                        line.put("need", pair.siblingId());
                        line.put("lang", pair.lang());
                        line.put("arm", arm);
                        line.put("query", o.dispatchedQuery());
                        line.put("anchored_text", o.anchoredText());
                        line.put("cache_state", o.cacheState());
                        line.put("translator_call_delta", o.translatorCallDelta());
                        line.put("returned", o.uids());
                        com.fasterxml.jackson.databind.node.ArrayNode similarity =
                                MAPPER.createArrayNode();
                        for (RetrievalEvalScorer.ToolRow row : o.rows()) {
                            similarity.add(row.similarity() == null
                                    ? NullNode.getInstance()
                                    : com.fasterxml.jackson.databind.node.DoubleNode
                                            .valueOf(row.similarity()));
                        }
                        line.put("similarity", similarity);
                        line.put("hit_ranks", AnchorLegCharacterizer.hitRanks(o.uids(), expected));
                        line.put("raw_recall", AnchorLegCharacterizer.rawRecall(o.uids(), expected));
                        line.put("window_overlap_vs_sibling",
                                AnchorLegCharacterizer.windowOverlap(o.uids(), siblingUids));
                        out.write(MAPPER.writeValueAsString(line));
                        out.write('\n');
                    }
                }
            }
        }
    }

    private byte[] goldenSetBytes() throws IOException {
        // Tech-pinned: the characterization fixtures are tech rows (M1-945).
        String resource = RetrievalEvalWorlds.tech().resource();
        try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not on the test classpath (M1-928)");
            }
            return in.readAllBytes();
        }
    }
}
