package app.zcat.infochat.provider.chat.tool.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure metric math for the retrieval eval: capped+raw Recall@16, MRR,
 * none_expected over-return, and lexical-only share over the tool's own
 * JSON emission. No CDI, no DB — CI-covered by RetrievalEvalScorerTest;
 * the live runner only feeds it captured output. Metric contracts (docs/
 * plan/m1/tick-analysis/golden-set-retrieval-eval.md P5/P12): recall is
 * capped at min(|E|,16) because |E| &gt; k makes full recall mechanically
 * impossible; none_expected rows never enter a recall or MRR denominator.
 */
public final class RetrievalEvalScorer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The recall cap: the production default result count (infochat.chat.semantic-limit). */
    static final int K = 16;

    /** One golden-set record, reduced to what scoring needs. */
    public record GoldenRecord(String id, String query, String clazz, String scopeLang,
                               boolean noneExpected, List<String> expectedUids) {
        public GoldenRecord {
            expectedUids = List.copyOf(expectedUids);
        }
    }

    /** One returned row of the tool's emission; similarity null = lexical-only arm. */
    public record ToolRow(String uid, Double similarity, String readyAt) {}

    /** Metric slice for a class (or the run overall, name "overall"). */
    public record Slice(String name, int n, int noneExpectedN,
                        Double cappedRecall, Double rawRecall, Double mrr,
                        Double meanLabelSize, Double overReturnMeanCount,
                        Double overReturnMedianAgeHours, double lexicalOnlyShare) {}

    public record Scores(Slice overall, List<Slice> byClass) {}

    private RetrievalEvalScorer() {
    }

    /**
     * Parse a SemanticSearchTool emission: a JSON array of
     * {@code {uid,title,url,ready_at,similarity}} rows (similarity null =
     * lexical-only row; ready_at ISO-8601 or null).
     */
    public static List<ToolRow> parseToolJson(String json) {
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                throw new IllegalStateException("tool emission is not a JSON array: " + json);
            }
            List<ToolRow> rows = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                rows.add(new ToolRow(
                        n.path("uid").asText(),
                        n.hasNonNull("similarity") ? n.get("similarity").asDouble() : null,
                        n.hasNonNull("ready_at") ? n.get("ready_at").asText() : null));
            }
            return rows;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("unparseable tool emission: " + json, e);
        }
    }

    /**
     * Score records against their returned rows. {@code worldNow} is the
     * fingerprint's max ready_at — the pinned corpus "now" the temporal
     * labels were authored against (P7), never the wall clock.
     */
    public static Scores score(List<GoldenRecord> records,
                               Map<String, List<ToolRow>> results,
                               Instant worldNow) {
        Slice overall = slice("overall", records, results, worldNow);
        Map<String, Slice> byClass = new LinkedHashMap<>();
        for (GoldenRecord r : records) {
            byClass.computeIfAbsent(r.clazz(), c ->
                    slice(c, records.stream().filter(x -> c.equals(x.clazz())).toList(),
                            results, worldNow));
        }
        return new Scores(overall, new ArrayList<>(byClass.values()));
    }

    private static Slice slice(String name, List<GoldenRecord> records,
                               Map<String, List<ToolRow>> results, Instant worldNow) {
        int noneExpectedN = 0;
        double recallSum = 0;
        double cappedSum = 0;
        double mrrSum = 0;
        int recallN = 0;
        int labelSizeSum = 0;
        int overCountSum = 0;
        List<Double> agesHours = new ArrayList<>();
        long returnedTotal = 0;
        long lexicalNull = 0;
        for (GoldenRecord r : records) {
            List<ToolRow> rows = results.getOrDefault(r.id(), List.of());
            returnedTotal += rows.size();
            lexicalNull += rows.stream().filter(x -> x.similarity() == null).count();
            if (r.noneExpected()) {
                noneExpectedN++;
                overCountSum += rows.size();
                for (ToolRow row : rows) {
                    if (row.readyAt() != null) {
                        agesHours.add(Duration.between(Instant.parse(row.readyAt()), worldNow)
                                .toMillis() / 3600000.0);
                    }
                }
                continue;
            }
            if (r.expectedUids().isEmpty()) {
                throw new IllegalArgumentException(
                        "record " + r.id() + " has no expected uids and no none_expected flag");
            }
            recallN++;
            labelSizeSum += r.expectedUids().size();
            int hits = 0;
            int firstRank = 0;
            for (int i = 0; i < rows.size(); i++) {
                if (r.expectedUids().contains(rows.get(i).uid())) {
                    hits++;
                    if (firstRank == 0) {
                        firstRank = i + 1;
                    }
                }
            }
            recallSum += (double) hits / r.expectedUids().size();
            cappedSum += (double) hits / Math.min(r.expectedUids().size(), K);
            mrrSum += firstRank > 0 ? 1.0 / firstRank : 0.0;
        }
        return new Slice(name, records.size(), noneExpectedN,
                recallN > 0 ? cappedSum / recallN : null,
                recallN > 0 ? recallSum / recallN : null,
                recallN > 0 ? mrrSum / recallN : null,
                recallN > 0 ? (double) labelSizeSum / recallN : null,
                noneExpectedN > 0 ? (double) overCountSum / noneExpectedN : null,
                agesHours.isEmpty() ? null : median(agesHours),
                returnedTotal == 0 ? 0.0 : (double) lexicalNull / returnedTotal);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
