package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per acceptance items (a)–(e) of M1-038. The redaction shape is
 * pinned to docs/spec/security.md §Secrets handling: prefix +
 * ellipsis + suffix, with fixed sentinels for null / short inputs
 * so a grep audit can distinguish redacted output from raw output.
 */
class ContactIdsTest {

    private static final String LONG_TYPICAL_CONTACT_ID =
            "alice-simplex-queue-1234567890abcdef-fingerprint";

    /** Acceptance (a): null input returns a fixed sentinel without throwing. */
    @Test
    void nullReturnsNullSentinel() {
        assertEquals(ContactIds.NULL_SENTINEL, ContactIds.redact(null));
    }

    /** Acceptance (b): empty input returns a fixed sentinel. */
    @Test
    void emptyReturnsShortSentinel() {
        assertEquals(ContactIds.SHORT_SENTINEL, ContactIds.redact(""));
    }

    /**
     * Acceptance (c): inputs at-or-below {@code prefix + suffix} length
     * collapse to the short sentinel without exposing the original. The
     * spec rule is "expose strictly less than the whole id" — for
     * 12-character input, prefix(8) + suffix(4) = 12 would expose the
     * entire id, so {@link ContactIds#MIN_REDACTABLE_LENGTH} is set
     * above that boundary.
     */
    @Test
    void shortInputReturnsShortSentinelWithoutExposingOriginal() {
        String shortId = "alice-q1234";
        String redacted = ContactIds.redact(shortId);
        assertEquals(ContactIds.SHORT_SENTINEL, redacted);
        assertFalse(redacted.contains(shortId),
                "short-input redaction must not contain the original; got " + redacted);
    }

    /**
     * Acceptance (c) edge case: input length equal to {@code prefix +
     * suffix} (12 characters for the v1 cutoffs) is still short — the
     * spec's "expose less than the whole id" rule forbids exposing 12
     * out of 12 characters.
     */
    @Test
    void exactlyPrefixPlusSuffixLengthReturnsShortSentinel() {
        String shortId = "abcdefghijkl"; // 12 chars
        assertEquals(12, ContactIds.PREFIX_LENGTH + ContactIds.SUFFIX_LENGTH);
        assertEquals(ContactIds.SHORT_SENTINEL, ContactIds.redact(shortId));
    }

    /**
     * Acceptance (d): a 16+-character input returns the prefix +
     * ellipsis + suffix form, and neither half exposes the full id.
     */
    @Test
    void typicalInputReturnsPrefixEllipsisSuffix() {
        String redacted = ContactIds.redact(LONG_TYPICAL_CONTACT_ID);
        assertNotNull(redacted);
        assertFalse(redacted.equals(LONG_TYPICAL_CONTACT_ID),
                "typical-input redaction must differ from the original");
        assertFalse(redacted.contains(LONG_TYPICAL_CONTACT_ID),
                "redaction must not contain the full original id");
        assertTrue(redacted.startsWith(LONG_TYPICAL_CONTACT_ID.substring(0, ContactIds.PREFIX_LENGTH)),
                "redaction must start with the leading " + ContactIds.PREFIX_LENGTH
                        + " chars; got " + redacted);
        String expectedSuffix = LONG_TYPICAL_CONTACT_ID.substring(
                LONG_TYPICAL_CONTACT_ID.length() - ContactIds.SUFFIX_LENGTH);
        assertTrue(redacted.endsWith(expectedSuffix),
                "redaction must end with the trailing " + ContactIds.SUFFIX_LENGTH
                        + " chars; got " + redacted);
    }

    /** Acceptance (e): the returned string contains the ellipsis literal. */
    @Test
    void typicalInputContainsEllipsisLiteral() {
        String redacted = ContactIds.redact(LONG_TYPICAL_CONTACT_ID);
        assertTrue(redacted.contains(ContactIds.ELLIPSIS),
                "typical-input redaction must contain the ellipsis literal `" + ContactIds.ELLIPSIS
                        + "`; got " + redacted);
    }

    /**
     * Boundary at exactly {@link ContactIds#MIN_REDACTABLE_LENGTH}:
     * the input is the minimum redactable length, so the prefix +
     * ellipsis + suffix form applies. The hidden middle has
     * {@code 16 - 8 - 4 = 4} characters — strictly less than the
     * whole id.
     */
    @Test
    void minimumRedactableLengthYieldsRedactedForm() {
        String input = "0123456789ABCDEF"; // 16 chars exactly
        assertEquals(ContactIds.MIN_REDACTABLE_LENGTH, input.length());
        String redacted = ContactIds.redact(input);
        assertEquals("01234567" + ContactIds.ELLIPSIS + "CDEF", redacted);
    }
}
