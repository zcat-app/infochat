package app.zcat.infochat.core.log;

/**
 * Contact-id redaction for non-audit operator logs, per
 * {@code docs/spec/security.md} §Secrets handling: "Contact IDs are
 * logged in redacted form (prefix + ellipsis + suffix) outside the
 * audit log."
 *
 * <p>The helper is intentionally tiny and one-shape: a single static
 * {@link #redact(String)} method that callers wrap around any
 * contact-id-shaped value before handing it to SLF4J. The shape
 * matches the spec's prefix + ellipsis + suffix wording; the exact
 * cutoffs (8 leading + literal {@code "..."} + 4 trailing for
 * inputs ≥ 16 characters) are an implementer choice that the spec
 * leaves open.</p>
 *
 * <h2>Why not one generic redactor</h2>
 *
 * <p>v1 has three distinct redaction surfaces that the threat model
 * keeps separate by design:</p>
 * <ul>
 *   <li><b>API-key catalogue</b> — regex-driven, console-handler
 *       filter, fail-closed on timeout. Lives in the deferred
 *       M1-019 work. Inputs are arbitrary blobs scanned for known
 *       key shapes.</li>
 *   <li><b>Exception messages</b> — dropped wholesale by SafeLog
 *       (the deferred M1-020 wrapper). Inputs are exception text.</li>
 *   <li><b>Contact ids</b> — this helper. Inputs are known to be
 *       contact-id-shaped (decision D10 stable cryptographic ids),
 *       so the redaction is a deterministic substring shape rather
 *       than a pattern match.</li>
 * </ul>
 *
 * <p>Conflating all three into a single class would force every
 * call site to pass a "redaction kind" enum and would push the
 * three threat-model surfaces into one place where a regression in
 * one would silently affect the others. Three small specialised
 * helpers keep each surface auditable on its own.</p>
 *
 * <h2>Sentinels</h2>
 *
 * <ul>
 *   <li>{@code null} → {@link #NULL_SENTINEL} so an SLF4J pattern
 *       does not emit the literal string {@code "null"} (which a
 *       grep audit would not distinguish from a redacted id).</li>
 *   <li>An input strictly shorter than {@link #MIN_REDACTABLE_LENGTH}
 *       → {@link #SHORT_SENTINEL}. The minimum length is set so the
 *       prefix and suffix together expose strictly less than the
 *       whole id; for shorter inputs the safe choice is to expose
 *       nothing at all rather than nearly the whole id.</li>
 * </ul>
 */
public final class ContactIds {

    /** Leading characters of the redacted form for ≥ 16-character inputs. */
    static final int PREFIX_LENGTH = 8;

    /** Trailing characters of the redacted form for ≥ 16-character inputs. */
    static final int SUFFIX_LENGTH = 4;

    /**
     * Inputs shorter than this length are not redactable: exposing
     * 12 of 13 characters would defeat the redaction. The threshold
     * is set high enough that {@code length - (prefix + suffix)} is
     * at least 4 hidden characters for every redactable input.
     */
    static final int MIN_REDACTABLE_LENGTH = 16;

    /** Sentinel returned for {@code null} input. */
    public static final String NULL_SENTINEL = "<null>";

    /** Sentinel returned for empty or strictly-too-short input. */
    public static final String SHORT_SENTINEL = "<short>";

    /** Literal ellipsis the redacted form interpolates between prefix and suffix. */
    public static final String ELLIPSIS = "...";

    private ContactIds() {}

    /**
     * Redact a contact-id-shaped string for non-audit logging.
     *
     * @param id the contact id; may be null or empty.
     * @return the redacted form: {@link #NULL_SENTINEL} for null,
     *         {@link #SHORT_SENTINEL} for inputs shorter than
     *         {@link #MIN_REDACTABLE_LENGTH}, otherwise
     *         {@code id.substring(0, PREFIX_LENGTH) + ELLIPSIS +
     *         id.substring(id.length() - SUFFIX_LENGTH)}.
     */
    public static String redact(String id) {
        if (id == null) {
            return NULL_SENTINEL;
        }
        if (id.length() < MIN_REDACTABLE_LENGTH) {
            return SHORT_SENTINEL;
        }
        return id.substring(0, PREFIX_LENGTH)
                + ELLIPSIS
                + id.substring(id.length() - SUFFIX_LENGTH);
    }
}
