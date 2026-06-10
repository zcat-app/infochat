package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pin {@link ChatMemoryPreFetcher#extractKeywords}'s token shape: the
 * punctuation strip keeps letters of ANY script, so the diacritic
 * keywords of a non-en scope (D43 ships en+cs) survive extraction and
 * memory pre-fetch keeps working for a cs scope, while ASCII
 * punctuation-stripping and short-token dropping stay unchanged.
 */
class ChatMemoryPreFetcherTest {

    @Test
    void extractKeywordsPreservesCzechDiacriticKeywords() {
        List<String> keywords = ChatMemoryPreFetcher.extractKeywords(
                "Včera jsem četl článek o šifrování!");
        assertEquals(List.of("včera", "jsem", "četl", "článek", "šifrování"), keywords,
                "Czech keywords (including their diacritic letters) must survive extraction");
    }

    @Test
    void extractKeywordsStillStripsPunctuationAndShortAsciiTokens() {
        List<String> keywords = ChatMemoryPreFetcher.extractKeywords(
                "Tell me about bitcoin, ok? It is up 5%.");
        assertEquals(List.of("tell", "about", "bitcoin"), keywords,
                "punctuation must be stripped and tokens shorter than 3 chars dropped");
    }
}
