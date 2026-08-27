package app.zcat.infochat.provider.chat.tool.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Schema/coverage/rationale/freeze validation over the committed golden set
// (fixture ticket of the retrieval-eval family); pure JUnit, no DB — running
// the labels is the harness ticket's job (M1-929 resolves that reference).
// Corrections RETIRE the target in-file (replaced_by) and supersede it.
class RetrievalGoldenSetTest {

    private static final String GOLDEN_SET = "/retrieval-eval/golden-set.jsonl";

    private static final Set<String> KNOWN_CLASSES = Set.of(
            "temporal-today", "temporal-2h", "temporal-24h",
            "entity-location", "entity-project", "price",
            "topical", "cross-lingual");

    private static final Map<String, Integer> CLASS_FLOORS = Map.of(
            "temporal-today", 5, "temporal-2h", 5, "temporal-24h", 4,
            "entity-location", 5, "entity-project", 5, "price", 5,
            "topical", 8, "cross-lingual", 12);

    private static final Set<String> LANGS = Set.of("en", "cs", "es", "ru", "tr");
    private static final Set<String> NON_EN = Set.of("cs", "es", "ru", "tr");
    private static final Set<String> KNOWN_CHECK_BLOCKS = Set.of("retrieval");
    private static final int MAX_EXPECTED_UIDS = 8;

    // ---- loading ----

    private static List<JsonNode> load() throws Exception {
        try (InputStream in = RetrievalGoldenSetTest.class
                .getResourceAsStream(GOLDEN_SET)) {
            if (in == null) {
                throw new IllegalStateException(GOLDEN_SET + " not on classpath");
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(content);
        }
    }

    private static List<JsonNode> parse(String jsonl) throws Exception {
        List<JsonNode> records = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            records.add(mapper.readTree(line));
        }
        return records;
    }

    // ---- validators (shared by happy path and corrupted-copy legs) ----

    private static void validateSchema(List<JsonNode> records) {
        Set<String> ids = new HashSet<>();
        for (JsonNode r : records) {
            String id = text(r, "id");
            assertTrue(id != null && !id.isBlank(), "schema: record without id");
            assertTrue(ids.add(id), "duplicate-id: " + id);
            String cls = text(r, "class");
            assertTrue(cls != null && KNOWN_CLASSES.contains(cls),
                    "unknown-class: " + cls);
            assertTrue(text(r, "query") != null && !text(r, "query").isBlank(),
                    "schema: " + id + " without query");
            String lang = text(r, "scope_lang");
            assertTrue(lang != null && LANGS.contains(lang),
                    "schema: " + id + " scope_lang outside the enabled set: " + lang);
            assertTrue(text(r, "labeled_at") != null,
                    "schema: " + id + " without labeled_at");
            JsonNode fingerprint = r.path("labeled_against").path("db_fingerprint");
            assertTrue(fingerprint.isTextual() && !fingerprint.asText().isBlank(),
                    "schema: " + id + " without labeled_against.db_fingerprint");
            JsonNode supersedes = r.get("supersedes");
            if (supersedes != null && !supersedes.isNull()) {
                assertTrue(supersedes.isTextual(),
                        "supersedes-not-textual: " + id);
                String target = supersedes.asText();
                JsonNode targetNode = records.stream()
                        .filter(x -> target.equals(text(x, "id"))).findFirst().orElse(null);
                assertTrue(targetNode != null,
                        "supersedes-absent-target: " + id + " -> " + target);
                assertTrue(id.equals(targetNode.path("replaced_by").asText(null)),
                        "supersedes-target-still-present: " + id + " -> " + target
                        + " (the target must be retired: replaced_by naming "
                        + id + " — an unretired target is an in-place-edit collision)");
            }
            JsonNode replacedBy = r.get("replaced_by");
            if (replacedBy != null && !replacedBy.isNull()) {
                assertTrue(replacedBy.isTextual(),
                        "replaced-by-not-textual: " + id);
                String superseder = replacedBy.asText();
                JsonNode supersederNode = records.stream()
                        .filter(x -> superseder.equals(text(x, "id"))).findFirst().orElse(null);
                assertTrue(supersederNode != null,
                        "replaced-by-absent: " + id + " -> " + superseder);
                assertTrue(id.equals(supersederNode.path("supersedes").asText(null)),
                        "replaced-by-unmatched: " + id + " -> " + superseder
                        + " (the superseder's supersedes must point back at " + id + ")");
            }
            JsonNode expected = r.get("expected");
            assertTrue(expected != null && expected.isObject(),
                    "schema: " + id + " without expected object");
            Set<String> blockNames = new HashSet<>();
            expected.fieldNames().forEachRemaining(blockNames::add);
            assertTrue(KNOWN_CHECK_BLOCKS.containsAll(blockNames),
                    "expected-not-check-keyed: " + id + " carries block(s) "
                    + blockNames + " outside " + KNOWN_CHECK_BLOCKS);
            JsonNode retrieval = expected.get("retrieval");
            assertTrue(retrieval != null && retrieval.isObject(),
                    "schema: " + id + " without expected.retrieval block");
            JsonNode uids = retrieval.get("relevant_uids");
            JsonNode noneExpected = retrieval.get("none_expected");
            assertTrue((uids == null) != (noneExpected == null),
                    "schema: " + id + " must carry exactly one of"
                    + " relevant_uids / none_expected");
            if (uids != null) {
                assertTrue(uids.isArray(), "schema: " + id + " relevant_uids not an array");
                assertTrue(uids.size() >= 1,
                        "schema: " + id + " empty relevant_uids (use none_expected)");
                assertTrue(uids.size() <= MAX_EXPECTED_UIDS,
                        "schema: " + id + " expected set above the "
                        + MAX_EXPECTED_UIDS + "-uid label cap (|E|>k ceiling, P5)");
                for (JsonNode uid : uids) {
                    assertTrue(uid.isTextual() && uid.asText().matches("[0-9a-f]{64}"),
                            "schema: " + id + " malformed uid " + uid);
                }
            }
        }
    }

    private static void validateFloors(List<JsonNode> records) {
        Map<String, Long> counts = records.stream().collect(Collectors.groupingBy(
                r -> text(r, "class"), Collectors.counting()));
        assertEquals(KNOWN_CLASSES, counts.keySet(),
                "class coverage: every known class must appear");
        counts.forEach((cls, n) -> assertTrue(n >= CLASS_FLOORS.get(cls),
                "class-below-floor: " + cls + " has " + n
                + ", floor is " + CLASS_FLOORS.get(cls)));
        long total = records.size();
        assertTrue(total >= 49 && total <= 56,
                "set size " + total + " outside 49-56 (floors sum to 49; P5 cap)");
    }

    private static void validateRationales(List<JsonNode> records) {
        for (JsonNode r : records) {
            String rationale = r.path("expected").path("retrieval").path("rationale").asText("");
            assertTrue(!rationale.isBlank(),
                    "missing-rationale: " + text(r, "id"));
            String lower = rationale.toLowerCase();
            assertTrue(lower.contains("sql") || lower.contains("adjudicat")
                            || lower.contains("pooled"),
                    "rationale-without-derivation: " + text(r, "id")
                    + " must name its derivation (P4 pooling discipline)");
        }
    }

    private static void validateNoneExpected(List<JsonNode> records) {
        for (JsonNode r : records) {
            JsonNode retrieval = r.path("expected").path("retrieval");
            if (retrieval.has("none_expected") && retrieval.get("none_expected").asBoolean()) {
                assertTrue(retrieval.path("rationale").asText("").length() >= 30,
                        "none-expected-without-rationale: " + text(r, "id")
                        + " (empty-window rows state WHY no corpus post satisfies"
                        + " the query — P12)");
                assertTrue(!retrieval.has("relevant_uids"),
                        "none-expected-with-uids: " + text(r, "id"));
            }
        }
    }

    private static void validateXling(List<JsonNode> records) {
        Map<String, JsonNode> byId = records.stream().collect(
                HashMap::new,
                (m, r) -> m.put(text(r, "id"), r),
                HashMap::putAll);
        Map<String, Set<String>> langsPerNeed = new TreeMap<>();
        for (JsonNode r : records) {
            if (!"cross-lingual".equals(text(r, "class"))) {
                continue;
            }
            String lang = text(r, "scope_lang");
            assertTrue(NON_EN.contains(lang),
                    "xling-row-in-english-scope: " + text(r, "id"));
            String notes = r.path("notes").asText("");
            String sibling = Arrays.stream(notes.split("\\s+"))
                    .filter(byId::containsKey).findFirst().orElse(null);
            assertTrue(sibling != null,
                    "xling-row-without-sibling: " + text(r, "id")
                    + " notes must name its English sibling record id");
            JsonNode sib = byId.get(sibling);
            assertEquals("en", text(sib, "scope_lang"),
                    "xling-sibling-not-english: " + sibling);
            assertTrue(!"cross-lingual".equals(text(sib, "class")),
                    "xling-sibling-is-xling: " + sibling);
            assertEquals(uidSet(sib), uidSet(r),
                    "xling-set-drift: " + text(r, "id")
                    + " does not match sibling " + sibling
                    + " (xling labels inherit the English need's set verbatim)");
            assertEquals(r.path("expected").path("retrieval").has("none_expected"),
                    sib.path("expected").path("retrieval").has("none_expected"),
                    "xling-set-drift: " + text(r, "id")
                    + " none_expected shape differs from sibling " + sibling);
            langsPerNeed.computeIfAbsent(sibling, k -> new TreeSet<>()).add(lang);
        }
        langsPerNeed.forEach((need, langs) -> assertEquals(NON_EN, langs,
                "xling-need-missing-languages: " + need + " covers " + langs
                + " — every information need appears in all four of cs/es/ru/tr"));
    }

    private static Set<String> uidSet(JsonNode record) {
        Set<String> uids = new TreeSet<>();
        record.path("expected").path("retrieval").path("relevant_uids")
                .forEach(u -> uids.add(u.asText()));
        return uids;
    }

    private static void validateAll(List<JsonNode> records) {
        validateSchema(records);
        List<JsonNode> active = records.stream()
                .filter(r -> !r.path("replaced_by").isTextual()).toList();
        validateFloors(active);
        validateRationales(active);
        validateNoneExpected(active);
        validateXling(active);
    }

    private static String text(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null ? null : v.asText();
    }

    private static List<JsonNode> corrupted(java.util.function.Consumer<List<ObjectNode>> mutation)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> records = new ArrayList<>();
        for (JsonNode r : load()) {
            records.add((ObjectNode) mapper.readTree(mapper.writeValueAsString(r)));
        }
        mutation.accept(records);
        return new ArrayList<>(records);
    }

    // ---- happy path over the committed file ----

    @Test
    void classCoverageMeetsFloors() throws Exception {
        List<JsonNode> records = load();
        validateFloors(records);
        Map<String, List<Integer>> sizesPerClass = new TreeMap<>();
        for (JsonNode r : records) {
            int size = r.path("expected").path("retrieval").path("relevant_uids").size();
            sizesPerClass.computeIfAbsent(text(r, "class"), k -> new ArrayList<>()).add(size);
        }
        System.out.println("golden-set per-class label-set sizes: " + sizesPerClass);
    }

    @Test
    void schemaRejectsMalformedRecords() throws Exception {
        validateSchema(load());
    }

    @Test
    void rationaleAndPoolingFieldsPresent() throws Exception {
        validateRationales(load());
    }

    @Test
    void noneExpectedRowsCarryRationale() throws Exception {
        validateNoneExpected(load());
    }

    @Test
    void xlingRowsCarryNeedAnchor() throws Exception {
        validateXling(load());
    }

    @Test
    void fingerprintPinnedOnEveryRecord() throws Exception {
        for (JsonNode r : load()) {
            String fp = r.path("labeled_against").path("db_fingerprint").asText();
            assertEquals("ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;"
                    + "uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727",
                    fp, "record " + text(r, "id") + " carries a different fingerprint"
                    + " — mixed-fingerprint sets are drift, not a set (P7)");
        }
    }

    // ---- FAILURE-MODE: the validator must catch each corruption class ----

    @Test
    void failureModeMissingRationale() throws Exception {
        List<JsonNode> records = corrupted(rs -> ((ObjectNode) rs.get(0).get("expected")
                .get("retrieval")).remove("rationale"));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateRationales(records));
        assertTrue(e.getMessage().contains("missing-rationale"), e.getMessage());
    }

    @Test
    void failureModeUnknownClass() throws Exception {
        List<JsonNode> records = corrupted(rs -> rs.get(0).put("class", "vibes"));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("unknown-class"), e.getMessage());
    }

    @Test
    void failureModeClassBelowFloor() throws Exception {
        // Drop one temporal-today record: 4 remain against a floor of 5.
        List<JsonNode> records = corrupted(rs -> rs.removeIf(
                r -> "tt-1".equals(r.get("id").asText())));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateFloors(records));
        assertTrue(e.getMessage().contains("class-below-floor"), e.getMessage());
    }

    @Test
    void failureModeSupersedesAbsentTarget() throws Exception {
        List<JsonNode> records = corrupted(rs -> rs.get(0).put("supersedes", "no-such-id"));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("supersedes-absent-target"), e.getMessage());
    }

    @Test
    void failureModeSupersedesTargetStillValidates() throws Exception {
        // A live record still present while another claims to supersede it is
        // the in-place-edit collision the freeze forbids (track-a discipline).
        List<JsonNode> records = corrupted(rs -> rs.get(0).put("supersedes", "tt-2"));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("supersedes-target-still-present"),
                e.getMessage());
    }

    @Test
    void failureModeSupersedesRetiredTargetPasses() throws Exception {
        // The legal correction shape: the target stays in-file, RETIRED via
        // replaced_by, and the successor carries its lineage — validateAll
        // must accept it (floors/rationales/xling run over active records).
        List<JsonNode> records = corrupted(rs -> {
            ObjectMapper mapper = new ObjectMapper();
            try {
                ObjectNode successor = (ObjectNode) mapper.readTree(
                        mapper.writeValueAsString(rs.get(1)));
                successor.put("id", "tt-2b");
                successor.put("supersedes", "tt-2");
                rs.get(1).put("replaced_by", "tt-2b");
                rs.add(successor);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        validateAll(records);
    }

    @Test
    void failureModeNonTextualSupersedesRejected() throws Exception {
        List<JsonNode> records = corrupted(rs -> rs.get(0).put("supersedes", true));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("supersedes-not-textual"), e.getMessage());
    }

    @Test
    void failureModeExpectedNotKeyedByCheckName() throws Exception {
        List<JsonNode> records = corrupted(rs -> {
            ObjectNode expected = (ObjectNode) rs.get(0).get("expected");
            JsonNode retrieval = expected.remove("retrieval");
            expected.set("retrieval_check_oops", retrieval);
        });
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("expected-not-check-keyed"), e.getMessage());
    }

    @Test
    void failureModeDuplicateId() throws Exception {
        List<JsonNode> records = corrupted(rs -> {
            ObjectMapper mapper = new ObjectMapper();
            try {
                rs.add((ObjectNode) mapper.readTree(mapper.writeValueAsString(rs.get(0))));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("duplicate-id"), e.getMessage());
    }

    @Test
    void failureModeNoneExpectedWithoutRationale() throws Exception {
        List<JsonNode> records = corrupted(rs -> {
            for (ObjectNode r : rs) {
                if ("price".equals(r.get("class").asText())) {
                    ((ObjectNode) r.get("expected").get("retrieval")).remove("rationale");
                    break;
                }
            }
        });
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateNoneExpected(records));
        assertTrue(e.getMessage().contains("none-expected-without-rationale"),
                e.getMessage());
    }

    @Test
    void failureModeXlingSiblingMissing() throws Exception {
        List<JsonNode> records = corrupted(rs -> rs.stream()
                .filter(r -> "cross-lingual".equals(r.get("class").asText()))
                .findFirst().ifPresent(r -> r.put("notes", "English sibling: nope")));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateXling(records));
        assertTrue(e.getMessage().contains("xling-row-without-sibling"), e.getMessage());
    }

    @Test
    void failureModeXlingSetDriftsFromSibling() throws Exception {
        List<JsonNode> records = corrupted(rs -> rs.stream()
                .filter(r -> "cross-lingual".equals(r.get("class").asText()))
                .findFirst().ifPresent(r -> ((ArrayNode) r.get("expected")
                        .get("retrieval").get("relevant_uids")).set(0,
                                rs.get(2).get("expected").get("retrieval")
                                        .get("relevant_uids").get(0))));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateXling(records));
        assertTrue(e.getMessage().contains("xling-set-drift"), e.getMessage());
    }

    @Test
    void failureModeOversizedExpectedSet() throws Exception {
        List<JsonNode> records = corrupted(rs -> {
            ObjectNode r = rs.get(0);
            ArrayNode uids = (ArrayNode) r.get("expected").get("retrieval")
                    .get("relevant_uids");
            while (uids.size() < 9) {
                uids.add("0".repeat(64));
            }
        });
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("label cap"), e.getMessage());
    }
}
