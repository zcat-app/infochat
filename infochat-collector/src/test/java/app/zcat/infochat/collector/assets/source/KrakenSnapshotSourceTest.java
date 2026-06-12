package app.zcat.infochat.collector.assets.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test for {@link KrakenSnapshotSource}'s upstream-byte
 * hygiene. Kraken's public ticker returns an {@code "error"} array on
 * failure; its raw text is untrusted upstream bytes, so it is
 * control-stripped and truncated before it can land in exception text that
 * may reach WARN logs or admin notifications. The production endpoint URL
 * is hardcoded, so the error path cannot be driven through a loopback HTTP
 * fixture — this pins the redaction helper directly.
 */
class KrakenSnapshotSourceTest {

    @Test
    void stripAndTruncateRemovesControlCharacters() {
        // CR/LF would forge a second log line; ESC (0x1B) and the C1
        // single-byte CSI (0x9B) would open an ANSI escape sequence in an
        // operator's terminal. All must be replaced before the bytes reach
        // a log sink.
        String payload = "[\"EAPI:Rate limit \u001b[31m exceeded\r\n"
            + "\u009bforged ADMIN-NOTIFY line\u0001\"]";
        String result = KrakenSnapshotSource.stripAndTruncate(payload);

        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            boolean control = c < 0x20 || (c >= 0x7F && c <= 0x9F);
            assertFalse(control,
                "no control character may survive at index " + i + ": " + (int) c);
        }
        assertTrue(result.contains("EAPI:Rate limit"),
            "the non-control text must survive the strip; got: " + result);
    }

    @Test
    void stripAndTruncateCapsOverlongUpstreamBytes() {
        String payload = "[\"" + "x".repeat(500) + "\"]";
        String result = KrakenSnapshotSource.stripAndTruncate(payload);

        assertEquals(201, result.length(),
            "an over-cap body must truncate to the 200-char cap plus the ellipsis marker");
        assertTrue(result.endsWith("…"),
            "a truncated body must carry the ellipsis marker; got: " + result);
    }

    @Test
    void stripAndTruncateLeavesShortCleanBodyUnchanged() {
        String payload = "[\"EQuery:Unknown asset pair\"]";
        assertEquals(payload, KrakenSnapshotSource.stripAndTruncate(payload),
            "a short body with no control characters must pass through untouched");
    }
}
