package app.zcat.infochat.provider.chat.tool.eval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metric contracts of {@link RetrievalEvalScorer} over canned tool JSON
 * in the exact SemanticSearchTool emission shape (acceptance items 3-4),
 * including the |E|&gt;k ceiling and the none_expected over-return legs
 * a silent mutation must fail.
 */
class RetrievalEvalScorerTest {

    private static final Instant WORLD_NOW = Instant.parse("2026-08-24T16:00:00Z");

    private static RetrievalEvalScorer.GoldenRecord record(String id, String clazz,
                                                           List<String> expected) {
        return new RetrievalEvalScorer.GoldenRecord(id, "q-" + id, clazz, "en",
                false, expected);
    }

    private static String row(String uid, String readyAt, Double similarity) {
        return "{\"uid\":\"" + uid + "\",\"title\":\"t\",\"url\":\"https://x\","
                + "\"ready_at\":" + (readyAt == null ? "null" : "\"" + readyAt + "\"")
                + ",\"similarity\":" + (similarity == null ? "null" : similarity) + "}";
    }

    private static String json(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }

    @Test
    void cappedRecallAndMrrOverCannedToolJson() {
        var r1 = record("r1", "classA",
                List.of("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8"));
        var r2 = record("r2", "classA", List.of("e9"));
        var rB = new RetrievalEvalScorer.GoldenRecord("rB", "q-rB", "classB", "cs", true,
                List.<String>of());

        String r1Json = json(
                row("junk1", "2026-08-24T15:00:00Z", 0.9),
                row("e1", "2026-08-24T14:30:00Z", 0.85),
                row("junk2", "2026-08-24T14:00:00Z", null),
                row("e2", "2026-08-24T13:00:00Z", 0.8),
                row("e3", "2026-08-24T12:00:00Z", null));
        String r2Json = json(
                row("junk3", "2026-08-24T11:00:00Z", null),
                row("junk4", "2026-08-24T10:00:00Z", 0.7));
        StringBuilder twelve = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                twelve.append(',');
            }
            twelve.append(row("b" + i, i < 6 ? "2026-08-24T08:00:00Z"
                            : "2026-08-24T14:00:00Z",
                    i % 2 == 0 ? null : 0.6));
        }
        twelve.append(']');

        var results = Map.of(
                "r1", RetrievalEvalScorer.parseToolJson(r1Json),
                "r2", RetrievalEvalScorer.parseToolJson(r2Json),
                "rB", RetrievalEvalScorer.parseToolJson(twelve.toString()));

        var scores = RetrievalEvalScorer.score(List.of(r1, r2, rB), results, WORLD_NOW);

        var overall = scores.overall();
        assertEquals(3, overall.n());
        assertEquals(1, overall.noneExpectedN());
        // r1: 3 of 8 in both caps; r2: 0 of 1 — raw == capped (|E| <= 16 both).
        assertEquals(0.1875, overall.rawRecall(), 1e-9);
        assertEquals(0.1875, overall.cappedRecall(), 1e-9);
        // r1 first hit at rank 2 -> 0.5; r2 none -> 0; mean over 2 = 0.25.
        assertEquals(0.25, overall.mrr(), 1e-9);
        assertEquals(4.5, overall.meanLabelSize(), 1e-9);
        // 12 returned for the none_expected row.
        assertEquals(12.0, overall.overReturnMeanCount(), 1e-9);
        // ages: six 8h + six 2h -> median 5.0h.
        assertEquals(5.0, overall.overReturnMedianAgeHours(), 1e-9);
        // 19 returned rows overall, 9 with null similarity.
        assertEquals(9.0 / 19.0, overall.lexicalOnlyShare(), 1e-9);

        var a = scores.byClass().stream()
                .filter(s -> s.name().equals("classA")).findFirst().orElseThrow();
        assertEquals(2, a.n());
        assertEquals(0, a.noneExpectedN());
        assertEquals(0.1875, a.cappedRecall(), 1e-9);
        assertEquals(0.25, a.mrr(), 1e-9);
        assertNull(a.overReturnMeanCount());

        var b = scores.byClass().stream()
                .filter(s -> s.name().equals("classB")).findFirst().orElseThrow();
        assertEquals(1, b.n());
        assertEquals(1, b.noneExpectedN());
        assertNull(b.cappedRecall());
        assertNull(b.rawRecall());
        assertNull(b.mrr());
        // Pooling the none_expected row into recall would shift every number above.
        assertEquals(12.0, b.overReturnMeanCount(), 1e-9);
        assertEquals(5.0, b.overReturnMedianAgeHours(), 1e-9);
    }

    @Test
    void capsRecallAtTheRunsEffectiveLimitNotTheHardcodedDefault() {
        var expected = new java.util.ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            expected.add("wide" + i);
        }
        var r = record("wide", "wideClass", List.copyOf(expected));

        String[] rows = new String[32];
        for (int i = 0; i < 32; i++) {
            rows[i] = i < 20
                    ? row("wide" + i, "2026-08-24T15:00:00Z", 0.9)
                    : row("junk" + i, "2026-08-24T15:00:00Z", 0.5);
        }

        var scores = RetrievalEvalScorer.score(List.of(r),
                Map.of("wide", RetrievalEvalScorer.parseToolJson(json(rows))),
                WORLD_NOW, 32);

        // All 20 expected returned; cap at the run's limit: min(20, 32) = 20.
        assertEquals(1.0, scores.overall().cappedRecall(), 1e-9);
        assertEquals(1.0, scores.overall().rawRecall(), 1e-9);
    }

    @Test
    void overloadAtDefaultLimitReproducesStaticPins() {
        var expected = new java.util.ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            expected.add("big" + i);
        }
        var r = record("big", "bigClass", List.copyOf(expected));

        String[] rows = new String[16];
        for (int i = 0; i < 16; i++) {
            rows[i] = row("big" + i, "2026-08-24T15:00:00Z", 0.9);
        }

        var scores = RetrievalEvalScorer.score(List.of(r),
                Map.of("big", RetrievalEvalScorer.parseToolJson(json(rows))),
                WORLD_NOW, RetrievalEvalScorer.K);

        assertEquals(1.0, scores.overall().cappedRecall(), 1e-9);
        assertEquals(0.8, scores.overall().rawRecall(), 1e-9);
        assertEquals(20.0, scores.overall().meanLabelSize(), 1e-9);
        assertEquals(1.0, scores.overall().mrr(), 1e-9);
    }

    @Test
    void expectedBeyondCapReportsCappedAndRaw() {
        var expected = new java.util.ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            expected.add("big" + i);
        }
        var r = record("big", "bigClass", List.copyOf(expected));

        String[] rows = new String[16];
        for (int i = 0; i < 16; i++) {
            rows[i] = row("big" + i, "2026-08-24T15:00:00Z", 0.9);
        }

        var scores = RetrievalEvalScorer.score(List.of(r),
                Map.of("big", RetrievalEvalScorer.parseToolJson(json(rows))), WORLD_NOW);

        assertEquals(1.0, scores.overall().cappedRecall(), 1e-9);
        assertEquals(0.8, scores.overall().rawRecall(), 1e-9);
        assertEquals(20.0, scores.overall().meanLabelSize(), 1e-9);
        assertEquals(1.0, scores.overall().mrr(), 1e-9);
    }

    @Test
    void noExpectedUidReturnedContributesZeroMrr() {
        var r = record("miss", "classM", List.of("gone"));
        var scores = RetrievalEvalScorer.score(List.of(r),
                Map.of("miss", RetrievalEvalScorer.parseToolJson(
                        json(row("other", "2026-08-24T15:00:00Z", 0.9)))), WORLD_NOW);
        assertEquals(0.0, scores.overall().mrr(), 1e-9);
        assertEquals(0.0, scores.overall().rawRecall(), 1e-9);
    }

    @Test
    void emptyExpectedWithoutFlagIsRejected() {
        var bad = new RetrievalEvalScorer.GoldenRecord("bad", "q-bad", "c", "en", false,
                List.<String>of());
        var ex = assertThrows(IllegalArgumentException.class,
                () -> RetrievalEvalScorer.score(List.of(bad), Map.of(), WORLD_NOW));
        assertTrue(ex.getMessage().contains("bad"));
    }

    @Test
    void toolJsonParsingFollowsEmissionShape() {
        var rows = RetrievalEvalScorer.parseToolJson(json(
                row("u1", "2026-08-24T15:00:00Z", 0.5),
                row("u2", null, null)));
        assertEquals(2, rows.size());
        assertEquals("u1", rows.get(0).uid());
        assertEquals(0.5, rows.get(0).similarity());
        assertEquals("2026-08-24T15:00:00Z", rows.get(0).readyAt());
        assertNull(rows.get(1).similarity());
        assertNull(rows.get(1).readyAt());
    }
}
