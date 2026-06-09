package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Direct unit coverage for {@link Redactor#redact(String)} and the
 * package-private {@link Redactor.InterruptibleCharSequence}. The
 * filter-level catalogue coverage lives in {@code RedactingLogFilterTest};
 * this class pins the two behaviours M1-250 changed without touching what
 * Redactor masks:
 * <ul>
 *   <li>T13 — the single-pass scan must produce byte-identical output, so
 *       these cases assert exact equality (not just "key absent").</li>
 *   <li>T14 — the sampled wall-clock check must keep a long input
 *       interruptible.</li>
 * </ul>
 */
class RedactorTest {

    // --- T13: single-pass scan is byte-identical ---

    @Test
    void redactsAnthropicKeyByteIdentical() {
        assertEquals("key=" + Redactor.REDACTED,
                Redactor.redact("key=sk-ant-api03-aBcDeFgHiJkLmNoPqRsT"));
    }

    @Test
    void redactsOpenAiKeyByteIdentical() {
        assertEquals("Authorization: Bearer " + Redactor.REDACTED,
                Redactor.redact("Authorization: Bearer sk-ABCDEFGHIJ1234567890abcdefgh"));
    }

    @Test
    void redactsGenericKeywordValueByteIdenticalPreservingPrefix() {
        // The generic catch-all is the only pattern with a capturing
        // group; its "$1"-preserving replacement must survive the
        // find()-less single pass.
        assertEquals("api_key=" + Redactor.REDACTED,
                Redactor.redact("api_key=" + "a".repeat(40)));
    }

    @Test
    void nonKeyStringPassesThroughUnchanged() {
        String safe = "INFO fetched 42 posts from RSS feed https://example.com/rss";
        assertEquals(safe, Redactor.redact(safe));
    }

    @Test
    void emptyStringPassesThroughUnchanged() {
        assertEquals("", Redactor.redact(""));
    }

    @Test
    void overBoundSeparatorRunNotRedacted() {
        // The deliberate {0,64} cliff: 65 separators exceed the bound, so
        // the value stays adjacent-only and is left intact.
        String input = "api_key" + " ".repeat(65) + "a".repeat(40);
        assertEquals(input, Redactor.redact(input));
    }

    // --- T14: sampled clock check keeps a long input interruptible ---

    @Test
    void longInputWithExpiredBudgetReturnsTimeoutSentinel() {
        // A long input scanned under an already-elapsed budget must be
        // interrupted and fail closed rather than scanned to the end.
        String longInput = "api_key" + "a".repeat(Redactor.InterruptibleCharSequence.CLOCK_CHECK_INTERVAL * 8);
        assertEquals(Redactor.TIMEOUT_SENTINEL, Redactor.redact(longInput, 0L));
    }

    @Test
    void expiredDeadlineInterruptsWithinFirstSamplingWindow() {
        // Even though the clock is only sampled every CLOCK_CHECK_INTERVAL
        // chars, a long input whose deadline has already passed is NOT
        // read to the end — interruption fires within the first window.
        String longInput = "a".repeat(Redactor.InterruptibleCharSequence.CLOCK_CHECK_INTERVAL * 8);
        var seq = new Redactor.InterruptibleCharSequence(longInput, System.nanoTime() - 1);
        int lastIndexRead = -1;
        try {
            for (int i = 0; i < longInput.length(); i++) {
                seq.charAt(i);
                lastIndexRead = i;
            }
            fail("expected RegexInterruptedException before the whole input was read");
        } catch (Redactor.RegexInterruptedException expected) {
            assertTrue(lastIndexRead < Redactor.InterruptibleCharSequence.CLOCK_CHECK_INTERVAL,
                    "must interrupt within the first sampling window; last index read was " + lastIndexRead);
        }
    }

    @Test
    void futureDeadlineReadsAcrossSamplingWindowsWithoutInterrupting() {
        // Sampling must not spuriously interrupt: with the deadline far in
        // the future, every char across several sampling windows is read
        // and returned faithfully.
        int length = Redactor.InterruptibleCharSequence.CLOCK_CHECK_INTERVAL * 3 + 7;
        StringBuilder source = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            source.append((char) ('a' + (i % 26)));
        }
        String text = source.toString();
        var seq = new Redactor.InterruptibleCharSequence(text, System.nanoTime() + 60_000_000_000L);
        for (int i = 0; i < length; i++) {
            assertEquals(text.charAt(i), seq.charAt(i), "char at index " + i + " must pass through");
        }
    }

    @Test
    void expiredDeadlineThrowsOnFirstChar() {
        var seq = new Redactor.InterruptibleCharSequence("abc", System.nanoTime() - 1);
        assertThrows(Redactor.RegexInterruptedException.class, () -> seq.charAt(0));
    }
}
