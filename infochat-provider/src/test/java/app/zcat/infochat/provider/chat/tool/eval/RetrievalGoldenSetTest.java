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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            "topical", 16, "cross-lingual", 12);

    private static final Set<String> LANGS = Set.of("en", "cs", "es", "ru", "tr");
    private static final Set<String> NON_EN = Set.of("cs", "es", "ru", "tr");
    private static final Set<String> KNOWN_CHECK_BLOCKS = Set.of("retrieval");
    private static final int MAX_EXPECTED_UIDS = 16;

    private static final String TECH_FINGERPRINT =
            "ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;"
            + "uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727";

    // The fam replica's pinned end state (M1-948 run record,
    // .bench/retrieval-eval/fam-replica/pin-read-1.txt — reads byte-identical);
    // a tech pin on a fam record is the wrong-world collision.
    private static final String FAM_REPLICA_FINGERPRINT =
            "ready=<redacted>;max_ready_at=<replica-pin>;"
            + "uid_sha256=<replica-uid-pin>";

    // Per-world validator parameters (M1-949): tech carries the exact
    // pre-existing constants (byte-identical tech behavior); fam adds its
    // own vocabulary/floors/cap and the cs-only xling requirement.
    private record World(String resource, Set<String> classes,
            Map<String, Integer> floors, int capLo, int capHi,
            Set<String> xlingRequiredLangs, String xlingLangNote,
            Map<String, Integer> xlingMinRowsPerLang, int xlingMinNeeds,
            String fingerprint, String fingerprintToken, String capNote) { }

    private static final World TECH = new World(GOLDEN_SET, KNOWN_CLASSES,
            CLASS_FLOORS, 57, 66, NON_EN,
            "every information need appears in all four of cs/es/ru/tr",
            Map.of(), 0, TECH_FINGERPRINT, null,
            "M1-942 re-derived cap");

    private static final Set<String> FAM_CLASSES = Set.of(
            "temporal-today", "temporal-2h", "temporal-24h",
            "topical", "cross-lingual");

    private static final Map<String, Integer> FAM_CLASS_FLOORS = Map.of(
            "temporal-today", 5, "temporal-2h", 5, "temporal-24h", 4,
            "topical", 16, "cross-lingual", 16);

    private static final World FAM = new World(
            "/retrieval-eval/golden-set-fam.jsonl", FAM_CLASSES, FAM_CLASS_FLOORS,
            46, 55, Set.of("cs"),
            "cs is the fam world's required xling language per real usage;"
            + " es/ru/tr rows are optional",
            Map.of("cs", 12), 4, FAM_REPLICA_FINGERPRINT,
            "wrong-world-fingerprint",
            "M1-949 re-derived cap (floors sum 46, cap = floors + 9,"
            + " the M1-942 headroom precedent)");


    // Adjudicated row identities (2026-08-27, .bench/retrieval-eval/ +
    // .scratch/adjudication-report-20260827.md) pinned by the corrections leg.
    private static final String ZCASH_NEWSLETTER =
            "d0b2d19d5926cf338f73b64d0ef3b0ac887ccc5b2cc3f4f9e0f8a2d9c036bb38";
    private static final String BENCZECHMARK =
            "a795dcd7b8fa27e722312feafdfb347d8653ac3dc53f4472b517c3d19c1dc526";
    private static final String MUSTANG_PANDA =
            "d7c3ba941a444b955cd49a8b453eca3336f91164a58bd3e882780d905801c9fb";
    private static final String CAVERN_C2 =
            "4a4c022f7840340c05425b3bce10c02c08bdd925588be0d3de2b4a22ba8c2de9";
    private static final String GLM_5_3 =
            "08106a093512cd0ad4ec3a0c6712dccf69f59a26c3d1b7630e8df2ca9f2573e6";
    private static final String HELGOLAND_BITE =
            "8503304d3eae2b16b8c74c791e9734529ea92a3cd188a05a19d4610edb9c4674";

    // ---- loading ----

    private static List<JsonNode> load() throws Exception {
        return load(TECH);
    }

    private static List<JsonNode> load(World world) throws Exception {
        try (InputStream in = RetrievalGoldenSetTest.class
                .getResourceAsStream(world.resource())) {
            if (in == null) {
                throw new IllegalStateException(world.resource() + " not on classpath");
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
        validateSchema(TECH, records);
    }

    private static void validateSchema(World world, List<JsonNode> records) {
        Set<String> ids = new HashSet<>();
        for (JsonNode r : records) {
            String id = text(r, "id");
            assertTrue(id != null && !id.isBlank(), "schema: record without id");
            assertTrue(ids.add(id), "duplicate-id: " + id);
            String cls = text(r, "class");
            assertTrue(cls != null && world.classes().contains(cls),
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
        validateFloors(TECH, records);
    }

    private static void validateFloors(World world, List<JsonNode> records) {
        Map<String, Long> counts = records.stream().collect(Collectors.groupingBy(
                r -> text(r, "class"), Collectors.counting()));
        assertEquals(world.classes(), counts.keySet(),
                "class coverage: every known class must appear");
        counts.forEach((cls, n) -> assertTrue(n >= world.floors().get(cls),
                "class-below-floor: " + cls + " has " + n
                + ", floor is " + world.floors().get(cls)));
        long total = records.size();
        assertTrue(total >= world.capLo() && total <= world.capHi(),
                "set size " + total + " outside " + world.capLo() + "-" + world.capHi()
                + " over ACTIVE records (floors sum to " + world.capLo() + "; "
                + world.capNote() + ")");
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
        validateXling(TECH, records);
    }

    private static void validateXling(World world, List<JsonNode> records) {
        Map<String, JsonNode> byId = records.stream().collect(
                HashMap::new,
                (m, r) -> m.put(text(r, "id"), r),
                HashMap::putAll);
        Map<String, Set<String>> langsPerNeed = new TreeMap<>();
        Map<String, Long> rowsPerLang = new TreeMap<>();
        for (JsonNode r : records) {
            if (!"cross-lingual".equals(text(r, "class"))) {
                continue;
            }
            String lang = text(r, "scope_lang");
            assertTrue(NON_EN.contains(lang),
                    "xling-row-in-english-scope: " + text(r, "id"));
            rowsPerLang.merge(lang, 1L, Long::sum);
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
        langsPerNeed.forEach((need, langs) -> assertTrue(
                langs.containsAll(world.xlingRequiredLangs()),
                "xling-need-missing-languages: " + need + " covers " + langs
                + " — must cover at least " + world.xlingRequiredLangs()
                + " (" + world.xlingLangNote() + ")"));
        world.xlingMinRowsPerLang().forEach((lang, min) -> assertTrue(
                rowsPerLang.getOrDefault(lang, 0L) >= min,
                "xling-lang-below-floor: " + lang + " has "
                + rowsPerLang.getOrDefault(lang, 0L) + " xling rows, floor is "
                + min));
        assertTrue(langsPerNeed.size() >= world.xlingMinNeeds(),
                "xling-needs-below-floor: " + langsPerNeed.size()
                + " distinct needs, floor is " + world.xlingMinNeeds());
    }

    private static void validateFingerprint(World world, List<JsonNode> records) {
        for (JsonNode r : records) {
            String fp = r.path("labeled_against").path("db_fingerprint").asText();
            if (world.fingerprintToken() == null) {
                assertEquals(world.fingerprint(), fp,
                        "record " + text(r, "id") + " carries a different fingerprint"
                        + " — mixed-fingerprint sets are drift, not a set (P7)");
            } else {
                assertEquals(world.fingerprint(), fp,
                        world.fingerprintToken() + ": record " + text(r, "id")
                        + " pins " + fp + " — this set pins " + world.fingerprint());
            }
        }
    }

    private static Set<String> uidSet(JsonNode record) {
        Set<String> uids = new TreeSet<>();
        record.path("expected").path("retrieval").path("relevant_uids")
                .forEach(u -> uids.add(u.asText()));
        return uids;
    }

    private static void validateAll(List<JsonNode> records) {
        validateAll(TECH, records);
    }

    private static void validateAll(World world, List<JsonNode> records) {
        validateSchema(world, records);
        List<JsonNode> active = records.stream()
                .filter(r -> !r.path("replaced_by").isTextual()).toList();
        validateFloors(world, active);
        validateRationales(active);
        validateNoneExpected(active);
        validateXling(world, active);
    }

    private static String text(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null ? null : v.asText();
    }

    private static List<JsonNode> corrupted(java.util.function.Consumer<List<ObjectNode>> mutation)
            throws Exception {
        return corrupted(TECH, mutation);
    }

    private static List<JsonNode> corrupted(World world,
            java.util.function.Consumer<List<ObjectNode>> mutation)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> records = new ArrayList<>();
        for (JsonNode r : load(world)) {
            records.add((ObjectNode) mapper.readTree(mapper.writeValueAsString(r)));
        }
        mutation.accept(records);
        return new ArrayList<>(records);
    }

    // ---- happy path over the committed file ----

    @Test
    void classCoverageMeetsFloors() throws Exception {
        List<JsonNode> records = load();
        // Retired records carry the audit trail, not coverage: floors and
        // the total cap count ACTIVE records only (M1-942).
        List<JsonNode> active = records.stream()
                .filter(r -> !r.path("replaced_by").isTextual()).toList();
        validateFloors(active);
        Map<String, List<Integer>> sizesPerClass = new TreeMap<>();
        for (JsonNode r : active) {
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
        validateFingerprint(TECH, load());
    }

    @Test
    void adjudicatedCorrectionsPresent() throws Exception {
        List<JsonNode> records = load();
        Map<String, JsonNode> byId = records.stream().collect(
                HashMap::new, (m, r) -> m.put(text(r, "id"), r), HashMap::putAll);
        long successors = records.stream().filter(r -> r.path("supersedes").isTextual()).count();
        long retired = records.stream().filter(r -> r.path("replaced_by").isTextual()).count();
        assertEquals(18, successors, "the 2026-08-27 adjudication lands 18 supersedes pairs");
        assertEquals(18, retired, "18 retired targets (4 entity-location + 6 topical relabels"
                + " + 8 xling cascade)");
        assertEquals(77, records.size(), "file grows 51 -> 77 lines (18 successors + 8 extensions)");
        assertEquals(59, records.stream()
                .filter(r -> !r.path("replaced_by").isTextual()).count(),
                "59 active records after corrections + extension");

        // el-2: the Zcash newsletter (body keyword "prague" = a summit venue)
        // is dropped; the Czech benchmark story is the whole set.
        assertEquals(Set.of(BENCZECHMARK), uidSet(byId.get("el-2b")),
                "el-2 successor keeps only the Czech-story row");
        assertEquals("el-2", byId.get("el-2b").path("supersedes").asText());

        // el-4: both Kaspersky-as-source accidents out; Dahua/Manic stay.
        Set<String> el4 = uidSet(byId.get("el-4b"));
        assertEquals(Set.of(
                "9ee2f18f9c7034e7872cc314952724b1d6c9e36f82bb44adc899646cb55920e7",
                "d1d3fa2509b538aec4274a6d5ccaeb103f2e03dcedb9f2ae2c1960fc95aa51c1",
                "f8e7ea663d61b2de27679cf12c56037e66200a1777058dc6e5fa2f54ca8de3a9"),
                el4, "el-4 successor = Russian-OAuth + Dahua + Manic");
        assertFalse(el4.contains(MUSTANG_PANDA) || el4.contains(CAVERN_C2),
                "the Kaspersky-attribution rows are dropped");

        // el-5: the GLM-5.3 story joins the four keeps.
        Set<String> el5 = uidSet(byId.get("el-5b"));
        assertTrue(el5.contains(GLM_5_3), "the on-topic GLM-5.3 row is added");
        assertEquals(5, el5.size(), "el-5 successor = 4 keeps + GLM-5.3");

        // el-3: the weakest Ukraine row (Helgoland Bite) is dropped.
        Set<String> el3 = uidSet(byId.get("el-3b"));
        assertFalse(el3.contains(HELGOLAND_BITE), "Helgoland Bite is dropped");
        assertEquals(6, el3.size(), "el-3 successor keeps the other six rows");

        // Six topical relabels to the full adjudicated sets (two-direction
        // pooling, 16-cap selection; sizes are the derived end state).
        Map<String, Integer> topicalSizes = Map.of(
                "top-ai-b", 16, "top-cyber-b", 16, "top-ml-b", 16,
                "top-med-b", 12, "top-bio-b", 10, "top-robot-b", 10);
        topicalSizes.forEach((id, size) -> {
            JsonNode succ = byId.get(id);
            assertTrue(succ != null, id + " successor record exists");
            assertEquals("topical", text(succ, "class"));
            assertEquals(id.substring(0, id.length() - 2), succ.path("supersedes").asText(),
                    id + " supersedes its snapshot predecessor");
            assertEquals(size, uidSet(succ).size(), id + " full adjudicated set size");
        });
        Set<String> aiSet = uidSet(byId.get("top-ai-b"));
        assertFalse(aiSet.contains("2e4aa59c5eeb477e5daf160b993615db2545ce3901df1e148b9914dc9a7101cc"),
                "top-ai drops the adjudicated Fragments digest");
        assertTrue(aiSet.contains("015b1714fccc0d458b99ac0d7c063b0084d1098bbb044e24bb7ead348c1111a9"),
                "top-ai keeps its in-window labeled row");
        assertTrue(aiSet.contains(
                "c57e5eb3ff3bf36184a78a564e6db479d847e9e82bfaf50d20d3cad792d51466"),
                "an adjudicated returned-window row joins top-ai");
        assertFalse(aiSet.contains("bdc57d37ea0f2863476e72682089870cd21f2b4c64f56658c10104b8eb1e5632")
                && aiSet.contains("043e2c0cd5e945a676e92408da23ac8337ede4be0468af9d1c13121b032d59ef"),
                "the 16-cap cut drops the two oldest out-of-window keeps");
        Set<String> cyberSet = uidSet(byId.get("top-cyber-b"));
        assertFalse(cyberSet.contains("192c67089412577eb6d05639c5160f5c89250c64ca099f07b6509241e011ef37"),
                "top-cyber drops the adjudicated Baeldung howto");
        assertTrue(cyberSet.contains(
                "b65113d27f1228f87a0d2b86569b73633580d9e3f5f8eb25ebe9e17d64e0594a"),
                "an adjudicated returned-window row joins top-cyber");
        assertFalse(cyberSet.contains("91d043173e054a0996afd73f89133660828f9d935d4283d9e4ab416bbb69fd84"),
                "the 16-cap cut drops the oldest out-of-window keep");

        // Carried keeps pinned verbatim against the retired in-file records
        // (r1 rework): a prefix-variant fabrication in any successor's keep
        // must flip this leg red, not just resize a set.
        assertTrue(uidSet(byId.get("top-med-b")).containsAll(uidSet(byId.get("top-med"))),
                "top-med-b carries every retired top-med keep verbatim");
        assertTrue(uidSet(byId.get("top-bio-b")).containsAll(uidSet(byId.get("top-bio"))),
                "top-bio-b carries every retired top-bio keep verbatim");
        assertTrue(uidSet(byId.get("top-robot-b")).containsAll(uidSet(byId.get("top-robot"))),
                "top-robot-b carries every retired top-robot keep verbatim");
        Set<String> mlKeeps = uidSet(byId.get("top-ml"));
        mlKeeps.remove("f836627485d40d1775c1aaa60b61b7d198029071e40d62cd99f3192afa3a2d9c");
        mlKeeps.remove("cc109b5aa3efe3bec9ef093633693b7b4ef4eab7ff53f93f35a52ef93abe270f");
        assertTrue(uidSet(byId.get("top-ml-b")).containsAll(mlKeeps),
                "top-ml-b carries every non-cut retired top-ml keep verbatim");
        assertFalse(uidSet(byId.get("top-ml-b")).contains("f836627485d40d1775c1aaa60b61b7d198029071e40d62cd99f3192afa3a2d9c")
                || uidSet(byId.get("top-ml-b")).contains("cc109b5aa3efe3bec9ef093633693b7b4ef4eab7ff53f93f35a52ef93abe270f"),
                "the two 16-cap-cut top-ml keeps stay dropped");

        // The xling cascade: 8 successors inherit the corrected active
        // sibling sets verbatim and name the corrected sibling in notes.
        for (String base : List.of("xl-ai-cs", "xl-ai-es", "xl-ai-ru", "xl-ai-tr",
                "xl-cyber-cs", "xl-cyber-es", "xl-cyber-ru", "xl-cyber-tr")) {
            JsonNode succ = byId.get(base + "-b");
            assertTrue(succ != null, base + " cascade successor exists");
            assertEquals(base, succ.path("supersedes").asText());
            String sibling = "xl-ai".equals(base.substring(0, 5)) ? "top-ai-b" : "top-cyber-b";
            assertEquals(uidSet(byId.get(sibling)), uidSet(succ),
                    base + "-b inherits the corrected sibling set verbatim");
            assertTrue(succ.path("notes").asText("").contains(sibling),
                    base + "-b notes name the corrected active sibling");
        }

        // The extension: eight NEW topical needs, active topical n = 16.
        List<String> extension = List.of("top-quantum", "top-space", "top-climate",
                "top-chips", "top-physics", "top-drones", "top-misinfo", "top-gaming");
        for (String id : extension) {
            JsonNode r = byId.get(id);
            assertTrue(r != null, id + " extension record exists");
            assertEquals("topical", text(r, "class"));
            assertTrue(r.path("supersedes").isNull() && !r.path("replaced_by").isTextual(),
                    id + " is an original record, not a correction");
            int size = uidSet(r).size();
            assertTrue(size >= 1 && size <= 16, id + " set within 1..16");
        }
        long activeTopical = records.stream()
                .filter(r -> "topical".equals(text(r, "class")))
                .filter(r -> !r.path("replaced_by").isTextual()).count();
        assertEquals(16, activeTopical, "topical class extended 8 -> 16 active needs");
    }

    @Test
    void validatorAcceptsHonestShapes() throws Exception {
        // The adjudicated topical populations exceed the old authoring cap:
        // a 16-uid set must pass schema validation; 17 must still fail.
        List<JsonNode> sixteen = corrupted(rs -> padExpectedTo(rs.get(0), 16));
        validateSchema(sixteen);
        List<JsonNode> seventeen = corrupted(rs -> padExpectedTo(rs.get(0), 17));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(seventeen));
        assertTrue(e.getMessage().contains("label cap"), e.getMessage());

        // The corrected end state — 59 active records (18 supersedes pairs
        // are net-zero, 8 extensions add) — must pass the re-derived cap.
        List<JsonNode> padded = corrupted(rs -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                long active = rs.stream()
                        .filter(r -> !r.path("replaced_by").isTextual()).count();
                JsonNode seed = rs.stream()
                        .filter(r -> "topical".equals(r.get("class").asText())
                                && !r.path("replaced_by").isTextual()).findFirst().orElseThrow();
                for (int i = 0; active + i < 59; i++) {
                    ObjectNode clone = (ObjectNode) mapper.readTree(
                            mapper.writeValueAsString(seed));
                    clone.remove("replaced_by");
                    clone.remove("supersedes");
                    clone.remove("notes");
                    clone.put("id", "honest-shape-" + i);
                    rs.add(clone);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertEquals(59, padded.stream()
                .filter(r -> !r.path("replaced_by").isTextual()).count());
        validateAll(padded);
    }

    private static void padExpectedTo(ObjectNode record, int target) {
        ArrayNode uids = (ArrayNode) record.get("expected").get("retrieval")
                .get("relevant_uids");
        while (uids.size() < target) {
            uids.add("0".repeat(64));
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
        List<JsonNode> records = corrupted(rs -> padExpectedTo(rs.get(0), 17));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(records));
        assertTrue(e.getMessage().contains("label cap"), e.getMessage());
    }

    @Test
    void failureModeRetiredRecordDoubleCounts() throws Exception {
        // The active-only filter is load-bearing (M1-928 r2 observation):
        // retiring a temporal-today record whose successor is of a DIFFERENT
        // class must drop the class below its floor.
        List<JsonNode> records = corrupted(rs -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode tt1 = rs.stream()
                        .filter(r -> "tt-1".equals(r.get("id").asText())).findFirst().orElseThrow();
                ObjectNode successor = (ObjectNode) mapper.readTree(
                        mapper.writeValueAsString(tt1));
                successor.put("id", "tt-1b");
                successor.put("supersedes", "tt-1");
                successor.put("class", "price");
                ((ObjectNode) successor.get("expected").get("retrieval")).remove("relevant_uids");
                ((ObjectNode) successor.get("expected").get("retrieval")).put("none_expected", true);
                ((ObjectNode) successor.get("expected").get("retrieval")).put("rationale",
                        "pooled placeholder rationale long enough for none-expected shape");
                ((ObjectNode) rs.stream()
                        .filter(r -> "tt-1".equals(r.get("id").asText())).findFirst().orElseThrow())
                        .put("replaced_by", "tt-1b");
                rs.add(successor);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateAll(records));
        assertTrue(e.getMessage().contains("class-below-floor"), e.getMessage());
    }

    // ---- fam world (M1-949): the broad-distribution leg over the isolated
    // replica — labels pinned to the REPLICA fingerprint, the two-world
    // instrument's second answer key ----

    @Test
    void famSetMeetsWorldFloors() throws Exception {
        List<JsonNode> records = load(FAM);
        List<JsonNode> active = records.stream()
                .filter(r -> !r.path("replaced_by").isTextual()).toList();
        validateAll(FAM, records);
        Map<String, List<Integer>> sizesPerClass = new TreeMap<>();
        for (JsonNode r : active) {
            int size = r.path("expected").path("retrieval").path("relevant_uids").size();
            sizesPerClass.computeIfAbsent(text(r, "class"), k -> new ArrayList<>()).add(size);
        }
        System.out.println("fam golden-set per-class label-set sizes: " + sizesPerClass);
    }

    @Test
    void famRecordsCarryTheReplicaFingerprint() throws Exception {
        validateFingerprint(FAM, load(FAM));
    }

    @Test
    void famRationaleAndPoolingFieldsPresent() throws Exception {
        validateRationales(load(FAM));
    }

    @Test
    void famFailureModeWrongWorldFingerprint() throws Exception {
        // A fam record carrying the TECH world's pin is the wrong-world
        // collision the world-keyed fingerprint leg must reject by name.
        List<JsonNode> records = corrupted(FAM, rs -> ((ObjectNode) rs.get(0)
                .get("labeled_against")).put("db_fingerprint", TECH_FINGERPRINT));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateFingerprint(FAM, records));
        assertTrue(e.getMessage().contains("wrong-world-fingerprint"), e.getMessage());
    }

    @Test
    void famFailureModeOversizedExpectedSet() throws Exception {
        List<JsonNode> records = corrupted(FAM, rs -> padExpectedTo(rs.get(0), 17));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateSchema(FAM, records));
        assertTrue(e.getMessage().contains("label cap"), e.getMessage());
    }

    @Test
    void famFailureModeXlingSetDriftsFromSibling() throws Exception {
        List<JsonNode> records = corrupted(FAM, rs -> rs.stream()
                .filter(r -> "cross-lingual".equals(r.get("class").asText()))
                .findFirst().ifPresent(r -> {
                    // a uid guaranteed foreign to the row's set (several fam
                    // needs share rows, so scan for one outside this set)
                    Set<String> own = uidSet(r);
                    String foreign = rs.stream()
                            .flatMap(x -> java.util.stream.StreamSupport.stream(
                                    x.path("expected").path("retrieval")
                                            .path("relevant_uids").spliterator(), false))
                            .map(JsonNode::asText)
                            .filter(u -> !own.contains(u)).findFirst().orElseThrow();
                    ((ArrayNode) r.get("expected").get("retrieval")
                            .get("relevant_uids")).set(0, foreign);
                }));
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateXling(FAM, records));
        assertTrue(e.getMessage().contains("xling-set-drift"), e.getMessage());
    }

    @Test
    void famFailureModeRetiredRecordDoubleCounts() throws Exception {
        // Active-only floors: retiring a temporal-today fam record whose
        // successor is of a DIFFERENT class must drop the class below its
        // floor — the retired row must never count toward coverage.
        List<JsonNode> records = corrupted(FAM, rs -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode tt = rs.stream()
                        .filter(r -> "temporal-today".equals(r.get("class").asText()))
                        .filter(r -> !r.path("replaced_by").isTextual())
                        .findFirst().orElseThrow();
                ObjectNode successor = (ObjectNode) mapper.readTree(
                        mapper.writeValueAsString(tt));
                successor.put("id", "fam-tt-corrupt-b");
                successor.put("supersedes", tt.get("id").asText());
                successor.put("class", "topical");
                ((ObjectNode) rs.stream()
                        .filter(r -> tt.get("id").asText().equals(r.get("id").asText()))
                        .findFirst().orElseThrow())
                        .put("replaced_by", "fam-tt-corrupt-b");
                rs.add(successor);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        AssertionError e = assertThrows(AssertionError.class,
                () -> validateAll(FAM, records));
        assertTrue(e.getMessage().contains("class-below-floor"), e.getMessage());
    }
}
