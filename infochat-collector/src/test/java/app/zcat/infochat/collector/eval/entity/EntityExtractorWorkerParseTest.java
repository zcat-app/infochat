package app.zcat.infochat.collector.eval.entity;

import app.zcat.infochat.collector.eval.entity.EntityExtractionResult.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused unit tests for {@link EntityExtractorWorker#parseEntities} — the
 * reply-shape parsing surface, exercised directly without Quarkus or the
 * DB. Complements the {@code @QuarkusTest} {@link EntityExtractorWorkerTest}
 * (which drives the end-to-end persist/flag path through
 * {@code processOne}); these pin the M1-613 wrapping-object leniency
 * alongside the pre-existing bare-array / code-fence / vocabulary-filter /
 * normalize / dedup guarantees at the parser level, and the D22
 * fail-safe-to-null property for genuinely schema-violating replies.
 */
class EntityExtractorWorkerParseTest {

    private EntityExtractorWorker worker;

    @BeforeEach
    void setUp() {
        // parseEntities needs only the ObjectMapper that init() builds; the
        // injected collaborators (DataSource, LlmRouter, ...) are untouched
        // on the pure parse path, so a hand-constructed instance suffices.
        worker = new EntityExtractorWorker();
        worker.maxConcurrency = 1;
        worker.init();
    }

    // ---------- bare array (pre-existing behavior, preserved) ----------

    @Test
    void parsesBareArray() {
        EntityExtractionResult result = worker.parseEntities(
            "[{\"text\":\"OpenSSL\",\"type\":\"product\"}]");
        assertNotNull(result);
        assertEquals(List.of(new Entity("openssl", "product")), result.entities());
    }

    @Test
    void emptyBareArrayParsesToEmptyResult() {
        EntityExtractionResult result = worker.parseEntities("[]");
        assertNotNull(result, "an empty array is a valid zero-entity reply, not a parse failure");
        assertEquals(List.of(), result.entities());
    }

    // ---------- M1-613: wrapping-object leniency ----------

    @Test
    void parseAcceptsWrappedEntitiesObject() {
        // DeepSeek v4-flash's dominant deviation: the array wrapped in an
        // {"entities":[...]} object. Before M1-613 this returned null, so
        // ~85% of posts lost their entity coverage.
        EntityExtractionResult result = worker.parseEntities(
            "{\"entities\":[{\"text\":\"CISA\",\"type\":\"org\"},"
                + "{\"text\":\"CVE-2024-1234\",\"type\":\"cve\"}]}");
        assertNotNull(result, "a single-array-valued {\"entities\":[...]} object must be unwrapped");
        assertEquals(
            List.of(new Entity("cisa", "org"), new Entity("cve-2024-1234", "cve")),
            result.entities());
    }

    @Test
    void parseUnwrapsSingleArrayValuedObjectWithNonEntitiesKey() {
        // Any single array-valued field is unwrapped, not only `entities`.
        EntityExtractionResult result = worker.parseEntities(
            "{\"result\":[{\"text\":\"Acme Corp\",\"type\":\"org\"}]}");
        assertNotNull(result);
        assertEquals(List.of(new Entity("acme corp", "org")), result.entities());
    }

    @Test
    void parsePrefersEntitiesKeyWhenMultipleArraysPresent() {
        // Two array-valued fields, but the `entities` key disambiguates.
        EntityExtractionResult result = worker.parseEntities(
            "{\"entities\":[{\"text\":\"OpenSSL\",\"type\":\"product\"}],"
                + "\"notes\":[\"ignored\"]}");
        assertNotNull(result);
        assertEquals(List.of(new Entity("openssl", "product")), result.entities());
    }

    @Test
    void parseUnwrapsFencedWrappedObject() {
        // The realistic combined DeepSeek shape: an {"entities":[...]} object
        // inside a ```json fence (M1-586 fence strip then M1-613 unwrap).
        EntityExtractionResult result = worker.parseEntities(
            "```json\n{\"entities\":[{\"text\":\"CISA\",\"type\":\"org\"}]}\n```");
        assertNotNull(result);
        assertEquals(List.of(new Entity("cisa", "org")), result.entities());
    }

    // ---------- D22 fail-safe: genuine garbage still returns null ----------

    @Test
    void objectWithNoArrayFieldReturnsNull() {
        // A non-array object with no entities key is genuinely schema-
        // violating → null → D22 release-without-entities, unchanged.
        assertNull(worker.parseEntities("{\"count\":3,\"status\":\"ok\"}"));
    }

    @Test
    void objectWithMultipleArraysAndNoEntitiesKeyReturnsNull() {
        // Ambiguous: two array-valued fields and no `entities` key to pick →
        // fail safe rather than guess which array holds the entities.
        assertNull(worker.parseEntities(
            "{\"foo\":[{\"text\":\"X\",\"type\":\"org\"}],"
                + "\"bar\":[{\"text\":\"Y\",\"type\":\"org\"}]}"));
    }

    @Test
    void scalarReplyReturnsNull() {
        assertNull(worker.parseEntities("\"no entities here\""));
        assertNull(worker.parseEntities("42"));
    }

    @Test
    void nonJsonReplyReturnsNull() {
        assertNull(worker.parseEntities("I could not find any named entities."));
    }

    @Test
    void nullAndEmptyReturnNull() {
        assertNull(worker.parseEntities(null));
        assertNull(worker.parseEntities(""));
        assertNull(worker.parseEntities("   "));
    }

    // ---------- preserved element-level behavior through the parser ----------

    @Test
    void outOfVocabTypeDroppedInsideWrappedObject() {
        // Vocabulary filtering still applies after unwrapping.
        EntityExtractionResult result = worker.parseEntities(
            "{\"entities\":[{\"text\":\"Real Product\",\"type\":\"product\"},"
                + "{\"text\":\"Some Threat\",\"type\":\"malware\"}]}");
        assertNotNull(result);
        assertEquals(List.of(new Entity("real product", "product")), result.entities());
    }

    @Test
    void entityTextNormalizedAndDuplicatesCollapsed() {
        // Normalization (lower-case + strip) and (text,type) dedup still run.
        EntityExtractionResult result = worker.parseEntities(
            "[{\"text\":\"  OpenSSL  \",\"type\":\"product\"},"
                + "{\"text\":\"openssl\",\"type\":\"product\"}]");
        assertNotNull(result);
        assertEquals(List.of(new Entity("openssl", "product")), result.entities());
    }
}
