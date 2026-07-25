package app.zcat.infochat.collector.outbox;

import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-693 unit test for {@link PostPersister#normalizeTitle(String)}: the
 * ingest write-path normalizes {@code post.title} once so every consumer
 * (/summary, the digest, searchPosts, the chat tools) sees the same value.
 * Covers the length-cap (over-cap truncated, at-cap untouched), the
 * blank-headline case (null and whitespace-only), and surrogate-pair safety
 * at the cut boundary. No database — the package-private helper is a pure
 * function over its input.
 */
class PostPersisterTest {

    private static final int CAP = IngestTextNormalizer.TITLE_MAX_LENGTH;
    private static final String ELLIPSIS = "\u2026";

    @Test
    void overCapTitleStoredTruncatedWithEllipsis() {
        String over = "a".repeat(CAP + 50);
        String result = PostPersister.normalizeTitle(over);
        assertTrue(result.length() <= CAP,
            "over-cap title must be stored at <= cap, got " + result.length());
        assertTrue(result.endsWith(ELLIPSIS),
            "a truncated title must end with the ellipsis marker");
        assertEquals("a".repeat(CAP - 1), result.substring(0, CAP - 1),
            "the first (cap - 1) chars are preserved, then the ellipsis");
    }

    @Test
    void atCapTitleStoredByteIdentical() {
        String atCap = "b".repeat(CAP);
        assertEquals(atCap, PostPersister.normalizeTitle(atCap),
            "a title at or under the cap is stored byte-identical (no ellipsis)");
    }

    @Test
    void nullTitleBecomesUntitledPlaceholder() {
        assertEquals("untitled", PostPersister.normalizeTitle(null),
            "a null title no longer produces a blank headline downstream");
    }

    @Test
    void whitespaceOnlyTitleBecomesUntitledPlaceholder() {
        assertEquals("untitled", PostPersister.normalizeTitle("   \t  "),
            "a whitespace-only title no longer produces a blank headline");
    }

    @Test
    void surrogatePairAtCutBoundaryIsNotSplit() {
        // A supplementary codepoint is two UTF-16 chars (high + low
        // surrogate). Place the pair so its high surrogate lands exactly
        // at the cut boundary (index cap - 2): (cap - 2) 'a's, then the
        // pair, then padding so the title exceeds the cap. A naive
        // char-based substring(0, cap - 1) would keep the high surrogate
        // and drop its low partner, orphaning half a codepoint; the
        // surrogate-aware cut backs off one so the pair is excluded whole.
        String supplementary = new String(Character.toChars(0x1F600));
        String title = "a".repeat(CAP - 2) + supplementary + "b".repeat(10);
        String result = PostPersister.normalizeTitle(title);
        assertFalse(result.contains(supplementary),
            "the surrogate pair must not survive into the truncated title");
        assertEquals("a".repeat(CAP - 2) + ELLIPSIS, result,
            "the cut backs off one so the surrogate pair is excluded, not split");
    }
}
