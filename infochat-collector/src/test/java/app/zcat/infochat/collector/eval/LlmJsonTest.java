package app.zcat.infochat.collector.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit cases for {@link LlmJson#stripCodeFence}: the fenced-payload
 * recovery that lets the eval-pipeline JSON parsers accept a provider's
 * markdown-code-fenced reply (M1-586), and the passthrough guarantees
 * that keep a non-fenced or truncated reply byte-for-byte unchanged so
 * the D22 degrade path stays intact.
 */
class LlmJsonTest {

    @Test
    void fencedJsonArray_returnsInnerArray() {
        String fenced = "```json\n[{\"text\":\"CISA\",\"type\":\"org\"}]\n```";
        assertEquals("[{\"text\":\"CISA\",\"type\":\"org\"}]", LlmJson.stripCodeFence(fenced));
    }

    @Test
    void fencedJsonObject_returnsInnerObject() {
        String fenced = "```json\n{\"tags\":[\"security\",\"news\"]}\n```";
        assertEquals("{\"tags\":[\"security\",\"news\"]}", LlmJson.stripCodeFence(fenced));
    }

    @Test
    void bareFenceWithoutLanguageToken_returnsInner() {
        String fenced = "```\n[1,2,3]\n```";
        assertEquals("[1,2,3]", LlmJson.stripCodeFence(fenced));
    }

    @Test
    void uppercaseLanguageToken_returnsInner() {
        String fenced = "```JSON\n{\"tags\":[]}\n```";
        assertEquals("{\"tags\":[]}", LlmJson.stripCodeFence(fenced));
    }

    @Test
    void surroundingWhitespace_isTolerated() {
        String fenced = "  \n```json\n[]\n```  \n";
        assertEquals("[]", LlmJson.stripCodeFence(fenced));
    }

    @Test
    void noFence_returnedUnchanged() {
        String bare = "[{\"text\":\"CISA\",\"type\":\"org\"}]";
        assertEquals(bare, LlmJson.stripCodeFence(bare));
    }

    @Test
    void backticksMidContent_returnedUnchanged() {
        // Backticks appear inside the payload but the reply is not
        // fence-wrapped, so it must pass through untouched.
        String withBackticks = "[{\"text\":\"use ```code``` here\",\"type\":\"product\"}]";
        assertEquals(withBackticks, LlmJson.stripCodeFence(withBackticks));
    }

    @Test
    void openerWithoutClosingFence_returnedUnchanged() {
        // A truncated reply (opener, no matching closer) is left
        // unchanged so it still fails the downstream parse → null,
        // preserving the D22 degrade path.
        String truncated = "```json\n[{\"text\":\"CISA\",\"type\":\"org\"}]";
        assertEquals(truncated, LlmJson.stripCodeFence(truncated));
    }
}
