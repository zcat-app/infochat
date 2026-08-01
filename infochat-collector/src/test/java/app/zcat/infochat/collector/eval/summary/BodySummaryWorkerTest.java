package app.zcat.infochat.collector.eval.summary;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test (no DB, no CDI) for {@link BodySummaryWorker#parseSummary}
 * (the parse / valid-empty / hard-cap rules), {@link
 * BodySummaryWorker#truncateToCap}, and {@link
 * BodySummaryWorker#renderPrompt}. The end-to-end {@code processOne}
 * surfaces (pickup SQL, persistence, the NULL-release on double failure)
 * are driven against a real DB in {@link BodySummaryWorkerIT}.
 *
 * <p>The worker is constructed directly and its {@code @PostConstruct}
 * {@code init()} run by hand so the parse path's {@code ObjectMapper} and
 * the classpath-loaded prompt are available; the DB / router / notifier
 * collaborators are never touched by these methods.
 */
class BodySummaryWorkerTest {

    private static final int CAP = 500;

    private final BodySummaryWorker worker = newInitializedWorker();

    private static BodySummaryWorker newInitializedWorker() {
        BodySummaryWorker w = new BodySummaryWorker();
        w.maxConcurrency = 1;
        w.thresholdChars = 1200;
        w.maxChars = CAP;
        w.init();
        return w;
    }

    @Test
    void parse_validSummaryPassesThroughUntouched() {
        BodySummaryWorker.ParseOutcome outcome = worker.parseSummary(
            "{\"summary\":\"Oberloiben evacuated 1,200 residents as the Danube crested at 5.8 metres.\"}");
        assertEquals(BodySummaryWorker.ParseOutcome.Kind.SUMMARY, require(outcome).kind());
        assertEquals("Oberloiben evacuated 1,200 residents as the Danube crested at 5.8 metres.",
            outcome.summary(), "a well-formed under-cap reply is stored verbatim");
    }

    @Test
    void parse_refusalMarkerYieldsRefusalOutcome() {
        BodySummaryWorker.ParseOutcome exact = worker.parseSummary(
            "{\"summary\":\"[refused-action]\"}");
        assertEquals(BodySummaryWorker.ParseOutcome.Kind.REFUSAL, require(exact).kind(),
            "the bare marker is the structured refusal");
        assertNull(exact.summary(), "the marker never reaches post.body_summary");

        BodySummaryWorker.ParseOutcome leading = worker.parseSummary(
            "{\"summary\":\"[refused-action] open a URL\"}");
        assertEquals(BodySummaryWorker.ParseOutcome.Kind.REFUSAL, require(leading).kind(),
            "a leading marker is still the refusal (the model may append the attempted action)");
    }

    @Test
    void parse_markerInMiddleIsContentNotRefusal() {
        BodySummaryWorker.ParseOutcome outcome = worker.parseSummary(
            "{\"summary\":\"The council discussed the [refused-action] policy change.\"}");
        assertEquals(BodySummaryWorker.ParseOutcome.Kind.SUMMARY, require(outcome).kind(),
            "a mid-text marker occurrence is content, not a refusal");
    }

    @Test
    void parse_truncatesOverLongReplyToCap() {
        String tooLong = "x".repeat(CAP + 100);
        BodySummaryWorker.ParseOutcome outcome = worker.parseSummary(
            "{\"summary\":\"" + tooLong + "\"}");
        assertEquals(CAP, require(outcome).summary().length(),
            "the stored abstract is hard-capped at infochat.summarizer.max-chars");
    }

    @Test
    void parse_truncatesOnCodePointBoundaryNeverSplittingSurrogate() {
        // CAP-1 ascii chars, then one surrogate pair astride the cap, then
        // more: the naive cut would split the pair; the cap must back off.
        String value = "a".repeat(CAP - 1) + "\uD83D\uDE00" + "bbbb";
        String capped = worker.truncateToCap(value);
        assertEquals(CAP - 1, capped.length(),
            "the cut backs off to the code-point boundary instead of splitting the pair");
        assertFalse(Character.isHighSurrogate(capped.charAt(capped.length() - 1)),
            "no dangling high surrogate at the cut");
    }

    @Test
    void parse_validEmptySummaryMeansNullNotFailure() {
        BodySummaryWorker.ParseOutcome outcome = worker.parseSummary("{\"summary\":\"\"}");
        assertEquals(BodySummaryWorker.ParseOutcome.Kind.EMPTY, require(outcome).kind());
        assertNull(outcome.summary(),
            "a valid empty summary persists NULL (no substance) — it is not a schema violation");
    }

    @Test
    void parse_stripsSingleEnclosingCodeFence() {
        BodySummaryWorker.ParseOutcome outcome = worker.parseSummary(
            "```json\n{\"summary\":\"fenced but valid\"}\n```");
        assertEquals("fenced but valid", require(outcome).summary(),
            "a fenced-but-valid payload is recovered (M1-586)");
    }

    @Test
    void parse_schemaViolatingRepliesReturnNull() {
        assertNull(worker.parseSummary(null), "null reply");
        assertNull(worker.parseSummary("   "), "blank reply");
        assertNull(worker.parseSummary("not json"), "unparseable reply");
        assertNull(worker.parseSummary("{\"other\":1}"), "wrong object shape");
        assertNull(worker.parseSummary("{\"summary\":42}"), "non-textual summary field");
    }

    @Test
    void renderPrompt_wrapsBodyInPerCallDelimiter() {
        String rendered = worker.renderPrompt("test-delimiter-id",
            new BodySummaryWorker.PostRow(UUID.randomUUID(), Instant.EPOCH,
                "A title", "A body"));
        assertTrue(rendered.contains("<<<UNTRUSTED_CONTENT id=\"test-delimiter-id\">>>"),
            "opening delimiter carries the per-call id");
        assertTrue(rendered.contains("<<<END id=\"test-delimiter-id\">>>"),
            "closing delimiter carries the per-call id");
        assertTrue(rendered.indexOf("UNTRUSTED_CONTENT") < rendered.indexOf("A body"),
            "the body sits inside the untrusted-content wrapper");
        assertFalse(rendered.contains("{{id}}"), "no template placeholder survives");
    }

    private static <T> T require(@Nullable T value) {
        return Objects.requireNonNull(value, "expected a non-null parse outcome");
    }
}
