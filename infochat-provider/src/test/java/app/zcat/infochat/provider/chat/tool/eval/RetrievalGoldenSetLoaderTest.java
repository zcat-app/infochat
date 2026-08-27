package app.zcat.infochat.provider.chat.tool.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Loader-seam legs of M1-943 (P5 retired-record skip, P6 identity hash):
// plain JUnit, no DB — the runner IT delegates to this loader, so
// executed and scored records are ACTIVE-only.
class RetrievalGoldenSetLoaderTest {

    @Test
    void skipsRetiredRecords() {
        String jsonl = String.join("\n",
                record("old-row", null, "new-row"),
                record("new-row", "old-row", null),
                record("keep-row", null, null));
        RetrievalGoldenSetLoader.GoldenSet loaded =
                RetrievalGoldenSetLoader.load(jsonl.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("new-row", "keep-row"),
                loaded.activeRows().stream()
                        .map(RetrievalGoldenSetLoader.GoldenRow::id).toList());
        assertEquals(1, loaded.retiredCount());
        RetrievalGoldenSetLoader.GoldenRow successor = loaded.activeRows().get(0);
        assertEquals("q new-row", successor.query());
        assertEquals("topical", successor.clazz());
        assertEquals("en", successor.scopeLang());
        assertFalse(successor.noneExpected());
        assertEquals(List.of("uid-new-row"), successor.expectedUids());
        assertEquals("fp", successor.labeledFingerprint());
    }

    // replaced_by whose successor is ABSENT from the file still retires the
    // row — pair integrity is RetrievalGoldenSetTest's job (M1-942).
    @Test
    void skipIsKeyedOnTheMarkerNotPairResolution() {
        String jsonl = String.join("\n",
                record("orphaned-old", null, "no-such-successor"),
                record("keep-row", null, null));
        RetrievalGoldenSetLoader.GoldenSet loaded =
                RetrievalGoldenSetLoader.load(jsonl.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("keep-row"),
                loaded.activeRows().stream()
                        .map(RetrievalGoldenSetLoader.GoldenRow::id).toList());
        assertEquals(1, loaded.retiredCount());
    }

    @Test
    void hashDiscriminatesOneByteAnswerKeyChange() {
        String content = record("keep-row", null, null);
        byte[] key = content.getBytes(StandardCharsets.UTF_8);
        byte[] oneByteChanged = content.replace("uid-keep-row", "uid-keep-rox")
                .getBytes(StandardCharsets.UTF_8);
        assertNotEquals(RetrievalGoldenSetLoader.sha256Hex(key),
                RetrievalGoldenSetLoader.sha256Hex(oneByteChanged));
        assertEquals(RetrievalGoldenSetLoader.sha256Hex(key),
                RetrievalGoldenSetLoader.sha256Hex(key.clone()));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RetrievalGoldenSetLoader.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void committedSetLoadsActiveOnly() throws Exception {
        byte[] content;
        try (InputStream in = RetrievalGoldenSetLoaderTest.class
                .getResourceAsStream("/retrieval-eval/golden-set.jsonl")) {
            assertTrue(in != null, "golden-set.jsonl not on classpath");
            content = in.readAllBytes();
        }
        RetrievalGoldenSetLoader.GoldenSet loaded = RetrievalGoldenSetLoader.load(content);
        ObjectMapper mapper = new ObjectMapper();
        List<String> retiredIds = new ArrayList<>();
        int lines = 0;
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            lines++;
            JsonNode n = mapper.readTree(line);
            if (n.path("replaced_by").isTextual()) {
                retiredIds.add(n.path("id").asText());
            }
        }
        Set<String> activeIds = loaded.activeRows().stream()
                .map(RetrievalGoldenSetLoader.GoldenRow::id).collect(Collectors.toSet());
        for (String retired : retiredIds) {
            assertFalse(activeIds.contains(retired), "retired record loaded: " + retired);
        }
        assertEquals(lines, loaded.activeRows().size() + loaded.retiredCount());
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        assertEquals(HexFormat.of().formatHex(sha.digest(content)), loaded.contentSha256());
    }

    private static String record(String id, String supersedes, String replacedBy) {
        return "{\"id\": \"" + id + "\", \"class\": \"topical\", \"query\": \"q " + id
                + "\", \"scope_lang\": \"en\", \"expected\": {\"retrieval\": {\"relevant_uids\": "
                + "[\"uid-" + id + "\"]}}, \"labeled_against\": {\"db_fingerprint\": \"fp\"}, "
                + "\"supersedes\": " + jsonValue(supersedes)
                + ", \"replaced_by\": " + jsonValue(replacedBy) + "}";
    }

    private static String jsonValue(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }
}
