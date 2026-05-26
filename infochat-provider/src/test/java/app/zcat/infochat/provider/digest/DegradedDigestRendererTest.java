package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.summary.EligiblePostQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DegradedDigestRendererTest {

    private final DegradedDigestRenderer renderer = new DegradedDigestRenderer();

    @Test
    void render_producesHeadlinesOnly() {
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "Bitcoin hits $100k", "TechCrunch", "https://tc.com/btc"),
                post("uid-2", "Ethereum update", "CoinDesk", "https://cd.com/eth"));

        String result = renderer.render(posts);

        assertTrue(result.contains("Bitcoin hits $100k"), "first headline present");
        assertTrue(result.contains("TechCrunch"), "first source attribution present");
        assertTrue(result.contains("https://tc.com/btc"), "first URL present (bare)");
        assertTrue(result.contains("Ethereum update"), "second headline present");
        assertTrue(result.contains("CoinDesk"), "second source attribution present");
        assertTrue(result.contains("https://cd.com/eth"), "second URL present (bare)");

        assertFalse(result.contains("["), "no markdown link syntax");
        assertFalse(result.contains("]("), "no markdown link syntax");

        // Verify structure: two blocks separated by blank line
        String[] blocks = result.split("\n\n");
        assertEquals(2, blocks.length, "two post blocks separated by blank line");
    }

    private static EligiblePostQuery.Post post(String uid, String title,
                                               String source, String url) {
        return new EligiblePostQuery.Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), source,
                title, url, "body", Instant.now(), List.of("crypto"));
    }
}
