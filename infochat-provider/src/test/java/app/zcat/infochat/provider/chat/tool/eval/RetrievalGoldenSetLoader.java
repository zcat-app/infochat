package app.zcat.infochat.provider.chat.tool.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

// Golden-set loading seam (M1-943): parses the committed JSONL, skipping
// RETIRED records (textual replaced_by; pairing integrity stays with
// RetrievalGoldenSetTest) and hashing the bytes for the manifest pin.
final class RetrievalGoldenSetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record GoldenRow(String id, String clazz, String query, String scopeLang, boolean noneExpected,
                     List<String> expectedUids, String labeledFingerprint) {
        RetrievalEvalScorer.GoldenRecord toScorerRecord() {
            return new RetrievalEvalScorer.GoldenRecord(id, query, clazz, scopeLang,
                    noneExpected, expectedUids);
        }
    }

    record GoldenSet(List<GoldenRow> activeRows, int retiredCount, String contentSha256) {
        GoldenSet {
            activeRows = List.copyOf(activeRows);
        }
    }

    static GoldenSet load(byte[] content) {
        List<GoldenRow> active = new ArrayList<>();
        int retired = 0;
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n;
            try {
                n = MAPPER.readTree(line);
            } catch (IOException e) {
                throw new IllegalStateException("unparseable golden-set line: " + line, e);
            }
            if (n.path("replaced_by").isTextual()) {
                retired++;
                continue;
            }
            JsonNode retrieval = n.path("expected").path("retrieval");
            active.add(new GoldenRow(
                    n.path("id").asText(),
                    n.path("class").asText(),
                    n.path("query").asText(),
                    n.path("scope_lang").asText(),
                    retrieval.path("none_expected").asBoolean(false),
                    retrieval.has("relevant_uids")
                            ? listOfStrings(retrieval.get("relevant_uids")) : List.of(),
                    n.path("labeled_against").path("db_fingerprint").asText(null)));
        }
        if (active.isEmpty()) {
            throw new IllegalStateException("golden set has no active records");
        }
        return new GoldenSet(active, retired, sha256Hex(content));
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static List<String> listOfStrings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(n.asText());
        }
        return out;
    }
}
