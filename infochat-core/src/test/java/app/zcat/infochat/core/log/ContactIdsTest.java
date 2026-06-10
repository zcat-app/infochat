package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per acceptance items (a)–(e) of M1-038, re-pinned by M1-281 to the
 * design shape. The redaction shape is pinned to
 * docs/spec/security.md §Secrets handling and
 * docs/design/04-security.md §4.11: 6-char prefix + ellipsis + 4-char
 * suffix, inputs of 10 characters or fewer fully masked — identical
 * to the SQL mirror {@code redact_contact_id} (V31); cross-engine
 * parity lives in {@link ContactIdsSqlParityIT}. Null keeps a fixed
 * sentinel so a grep audit can distinguish a null input from a
 * redacted id.
 */
class ContactIdsTest {

    private static final String LONG_TYPICAL_CONTACT_ID =
            "alice-simplex-queue-1234567890abcdef-fingerprint";

    /** Acceptance (a): null input returns a fixed sentinel without throwing. */
    @Test
    void nullReturnsNullSentinel() {
        assertEquals(ContactIds.NULL_SENTINEL, ContactIds.redact(null));
    }

    /** Acceptance (b): empty input collapses to the bare ellipsis. */
    @Test
    void emptyReturnsFullMask() {
        assertEquals(ContactIds.ELLIPSIS, ContactIds.redact(""));
    }

    /**
     * Acceptance (c): inputs at-or-below {@code prefix + suffix} length
     * collapse to the bare ellipsis without exposing the original. The
     * rule is "expose strictly less than the whole id" — at 10
     * characters, prefix(6) + suffix(4) would tile the entire id, so
     * {@link ContactIds#MIN_REDACTABLE_LENGTH} sits just above that
     * boundary.
     */
    @Test
    void shortInputFullyMaskedWithoutExposingOriginal() {
        String shortId = "alice-q123"; // 10 chars
        String redacted = ContactIds.redact(shortId);
        assertEquals(ContactIds.ELLIPSIS, redacted);
        assertFalse(redacted.contains(shortId),
                "short-input redaction must not contain the original; got " + redacted);
    }

    /**
     * Acceptance (c) edge case: input length equal to {@code prefix +
     * suffix} (10 characters for the design cutoffs) is still short —
     * the "expose less than the whole id" rule forbids exposing 10
     * out of 10 characters.
     */
    @Test
    void exactlyPrefixPlusSuffixLengthFullyMasked() {
        String shortId = "abcdefghij"; // 10 chars
        assertEquals(10, ContactIds.PREFIX_LENGTH + ContactIds.SUFFIX_LENGTH);
        assertEquals(ContactIds.ELLIPSIS, ContactIds.redact(shortId));
    }

    /**
     * Acceptance (d): a redactable-length input returns the prefix +
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
     * {@code 11 - 6 - 4 = 1} character — strictly less than the
     * whole id, per the design's threshold choice (V31 comment:
     * length 10 would tile the whole value).
     */
    @Test
    void minimumRedactableLengthYieldsRedactedForm() {
        String input = "0123456789A"; // 11 chars exactly
        assertEquals(ContactIds.MIN_REDACTABLE_LENGTH, input.length());
        String redacted = ContactIds.redact(input);
        assertEquals("012345" + ContactIds.ELLIPSIS + "789A", redacted);
    }
}
