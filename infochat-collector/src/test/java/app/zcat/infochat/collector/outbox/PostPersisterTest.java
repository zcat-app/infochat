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
        assertEquals(IngestTextNormalizer.UNTITLED_TITLE, PostPersister.normalizeTitle(null),
            "a null title stores the shared sentinel, satisfying the V7 NOT NULL column");
    }

    @Test
    void whitespaceOnlyTitleBecomesUntitledPlaceholder() {
        assertEquals(IngestTextNormalizer.UNTITLED_TITLE, PostPersister.normalizeTitle("   \t  "),
            "a whitespace-only title stores the shared sentinel");
    }

    @Test
    void untitledSentinelIsWrittenByteExactSoDisplayCanRecogniseIt() {
        // The sentinel is a two-party contract: the display layer matches it
        // with String.equals to know the post is titleless. Any transformation
        // on the way out — a strip, a cap, a trailing space — would break that
        // match silently and leave the body fallback dead (M1-729). Asserting
        // the literal, not the constant, is deliberate: comparing the constant
        // to itself would pass no matter what the write path did to it.
        assertEquals("untitled", PostPersister.normalizeTitle(null),
            "the stored sentinel must be byte-exact for the display-side match");
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
