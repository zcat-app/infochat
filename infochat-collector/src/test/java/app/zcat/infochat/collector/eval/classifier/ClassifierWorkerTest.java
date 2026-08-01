package app.zcat.infochat.collector.eval.classifier;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test (no DB, no CDI) for {@link ClassifierWorker#parseClassification}
 * — the parse / closed-enum filter / cap / {@code unknown}-mutual-exclusion
 * rules. The end-to-end {@code processOne} surfaces (persistence + the
 * fallback-to-{@code {unknown}} write on schema-violation / LLM-unreachable)
 * are driven against a real DB in {@link ClassifierWorkerIT}.
 *
 * <p>The worker is constructed directly and its {@code @PostConstruct}
 * {@code init()} run by hand so the parse path's {@code ObjectMapper} and
 * the classpath-loaded prompt are available; the DB / router / notifier
 * collaborators are never touched by {@code parseClassification}.
 */
class ClassifierWorkerTest {

    private final ClassifierWorker worker = newInitializedWorker();

    private static ClassifierWorker newInitializedWorker() {
        ClassifierWorker w = new ClassifierWorker();
        w.maxConcurrency = 1;
        w.init();
        return w;
    }

    @Test
    void parse_keepsSubstantiveInOrder_dropsOutOfEnum() {
        ClassificationResult result = worker.parseClassification(
            "{\"classification\":[\"factual\",\"frobnicate\",\"technical\"]}");
        assertEquals(List.of("factual", "technical"), require(result).labels(),
            "substantive labels kept in emission order; out-of-enum 'frobnicate' dropped");
    }

    @Test
    void parse_capsSubstantiveAtThree_inEmissionOrder() {
        // Five distinct substantive labels — one more than the cap of 3.
        ClassificationResult result = worker.parseClassification(
            "{\"classification\":[\"factual\",\"opinion\",\"technical\",\"urgent\",\"ongoing\"]}");
        assertEquals(List.of("factual", "opinion", "technical"), require(result).labels(),
            "at most MAX_LABELS_PER_POST (3) substantive labels, first by emission order");
    }

    @Test
    void parse_dropsUnknownWhenSubstantivePresent() {
        ClassificationResult result = worker.parseClassification(
            "{\"classification\":[\"factual\",\"unknown\"]}");
        assertEquals(List.of("factual"), require(result).labels(),
            "unknown is never combined with a substantive label");
    }

    @Test
    void parse_personalIsSubstantive() {
        // M1-727: personal joins the substantive set — a reply carrying it
        // survives the membership filter instead of being dropped as
        // out-of-enum (which would silently resolve to [unknown]).
        assertEquals(List.of("personal"),
            require(worker.parseClassification("{\"classification\":[\"personal\"]}")).labels(),
            "personal survives the membership filter");
        // It combines with other substantive labels.
        assertEquals(List.of("personal", "opinion"),
            require(worker.parseClassification(
                "{\"classification\":[\"personal\",\"opinion\"]}")).labels(),
            "personal combines with other substantive labels");
        // It counts toward the 1–3 substantive cap like any other label.
        assertEquals(List.of("personal", "factual", "opinion"),
            require(worker.parseClassification(
                "{\"classification\":[\"personal\",\"factual\",\"opinion\",\"technical\"]}")).labels(),
            "personal obeys the 1–3 substantive cap, first by emission order");
        // The unknown mutual-exclusion rule applies to it unchanged.
        assertEquals(List.of("personal"),
            require(worker.parseClassification(
                "{\"classification\":[\"personal\",\"unknown\"]}")).labels(),
            "unknown is never combined with personal either");
    }

    @Test
    void parse_emptyAfterFilterResolvesToUnknown() {
        // Model returned only an out-of-enum label → empty substantive set.
        assertEquals(List.of("unknown"),
            require(worker.parseClassification("{\"classification\":[\"frobnicate\"]}")).labels());
        // Model returned an explicit empty array.
        assertEquals(List.of("unknown"),
            require(worker.parseClassification("{\"classification\":[]}")).labels());
        // Model chose the fallback label itself.
        assertEquals(List.of("unknown"),
            require(worker.parseClassification("{\"classification\":[\"unknown\"]}")).labels());
    }

    @Test
    void parse_schemaViolatingReturnsNull() {
        assertNull(worker.parseClassification("not json at all"),
            "non-JSON reply is schema-violating");
        assertNull(worker.parseClassification("{\"labels\":[\"factual\"]}"),
            "object without a 'classification' key is schema-violating");
        assertNull(worker.parseClassification("{\"classification\":\"factual\"}"),
            "'classification' that is not an array is schema-violating");
        assertNull(worker.parseClassification(""),
            "empty reply is schema-violating");
    }

    @Test
    void parse_fencedJsonObjectRecovered() {
        // A valid object wrapped in a ```json markdown code fence (the
        // DeepSeek shape from M1-586) must be recovered, not treated as
        // schema-violating.
        ClassificationResult result = worker.parseClassification(
            "```json\n{\"classification\":[\"opinion\"]}\n```");
        assertEquals(List.of("opinion"), require(result).labels());
    }

    @Test
    void renderPrompt_wrapsTitleInsideDelimiter() {
        // D21 remediation (redteam finding): the untrusted post title must sit
        // INSIDE the per-call {{id}} delimiter, not before the opener, so a
        // feed-controlled title cannot reach the model as un-delimited
        // instructions.
        String rendered = worker.renderPrompt("DELIM-TOKEN-1",
            new ClassifierWorker.PostRow(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Instant.EPOCH, "EVIL TITLE INJECTION", "post body text"));
        // The prompt PREAMBLE also names the delimiter tokens when it explains
        // the wrapper format, so target the ACTUAL content wrapper (the last
        // occurrence) rather than the preamble's explanatory mention.
        int opener = rendered.lastIndexOf("<<<UNTRUSTED_CONTENT id=\"DELIM-TOKEN-1\">>>");
        int closer = rendered.lastIndexOf("<<<END id=\"DELIM-TOKEN-1\">>>");
        int title = rendered.indexOf("EVIL TITLE INJECTION");
        assertTrue(opener >= 0, "the delimiter opener must be present");
        assertTrue(closer > opener, "the delimiter closer must follow the opener");
        assertTrue(title > opener && title < closer,
            "the untrusted title must sit INSIDE the {{id}} delimiter block (D21), "
                + "not before the opener where it would read as instructions");
    }

    private static ClassificationResult require(@Nullable ClassificationResult result) {
        return Objects.requireNonNull(result, "parse must succeed (non-null result)");
    }
}
