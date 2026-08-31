package app.zcat.infochat.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the shared LIKE-metacharacter escaping contract: each of the three branches
 *  singly, all three in one value, and unchanged passthrough — weakening any fails. */
class LikeEscaperTest {

    @Test
    void metacharacterFreeTextPassesThroughUnchanged() {
        assertEquals("qwen3", LikeEscaper.escapeLike("qwen3"));
        assertEquals("czech-republic", LikeEscaper.escapeLike("czech-republic"));
    }

    @Test
    void percentGainsAPrecedingBackslash() {
        assertEquals("qw\\%n", LikeEscaper.escapeLike("qw%n"));
    }

    @Test
    void underscoreGainsAPrecedingBackslash() {
        assertEquals("a\\_b", LikeEscaper.escapeLike("a_b"));
    }

    @Test
    void backslashGainsAPrecedingBackslash() {
        assertEquals("a\\\\b", LikeEscaper.escapeLike("a\\b"));
    }

    @Test
    void everyMetacharacterInOneValueIsEscaped() {
        assertEquals("a\\%b\\_c\\\\d", LikeEscaper.escapeLike("a%b_c\\d"));
    }
}
