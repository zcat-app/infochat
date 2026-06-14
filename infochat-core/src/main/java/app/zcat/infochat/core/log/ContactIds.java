package app.zcat.infochat.core.log;

import org.jspecify.annotations.Nullable;

/**
 * Contact-id redaction for non-audit operator logs, per
 * {@code docs/spec/security.md} §Secrets handling: "Contact IDs are
 * logged in redacted form (prefix + ellipsis + suffix) outside the
 * audit log."
 *
 * <p>The helper is intentionally tiny and one-shape: a single static
 * {@link #redact(String)} method that callers wrap around any
 * contact-id-shaped value before handing it to SLF4J. The exact
 * cutoffs (6 leading + {@code "…"} + 4 trailing; inputs of 10
 * characters or fewer fully masked) are fixed by
 * {@code docs/design/04-security.md} §4.11 and MUST stay identical
 * to the SQL mirror {@code redact_contact_id} (V31) so the same
 * contact id redacts to the same string regardless of which layer
 * logged it — {@code ContactIdsSqlParityIT} pins the parity.</p>
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
 *       grep audit would not distinguish from a redacted id). The
 *       SQL mirror instead passes NULL through (audit rows without
 *       an actor/target contact id store NULL); the divergence is
 *       deliberate — NULL never renders into a log line on the SQL
 *       side, so parity applies to the string domain only.</li>
 *   <li>An input strictly shorter than {@link #MIN_REDACTABLE_LENGTH}
 *       → the bare {@link #ELLIPSIS}, fully masked, exactly as the
 *       SQL mirror does. At length 10 a 6-char prefix and 4-char
 *       suffix tile the whole value leaving no character hidden, and
 *       shorter ids make the halves overlap — the safe choice is to
 *       expose nothing at all.</li>
 * </ul>
 */
public final class ContactIds {

    /** Leading characters of the redacted form for redactable inputs. */
    static final int PREFIX_LENGTH = 6;

    /** Trailing characters of the redacted form for redactable inputs. */
    static final int SUFFIX_LENGTH = 4;

    /**
     * Inputs shorter than this length are not redactable — the prefix
     * and suffix would tile (length 10) or overlap (shorter) the whole
     * id — and collapse to the bare {@link #ELLIPSIS}. Mirrors the
     * {@code char_length(input) <= 10} arm of {@code redact_contact_id}.
     */
    static final int MIN_REDACTABLE_LENGTH = 11;

    /** Sentinel returned for {@code null} input. */
    public static final String NULL_SENTINEL = "<null>";

    /**
     * Literal ellipsis (U+2026, matching the SQL mirror — not three
     * ASCII dots) interpolated between prefix and suffix, and returned
     * bare for inputs too short to redact.
     */
    public static final String ELLIPSIS = "…";

    private ContactIds() {}

    /**
     * Redact a contact-id-shaped string for non-audit logging.
     *
     * <p><b>SQL-parity contract.</b> This method counts UTF-16 units
     * ({@code length()}/{@code substring()}) whereas the SQL mirror
     * {@code redact_contact_id} (V31) counts code points
     * ({@code char_length}/{@code left}/{@code right}); the two layers
     * agree only for BMP inputs, where one {@code char} is exactly one
     * code point. Contact ids are ASCII/BMP-shaped cryptographic
     * identifiers (decision D10), so the inputs never carry a
     * supplementary-plane code point and the two never diverge in
     * practice. A future non-BMP id alphabet would break the parity
     * that {@code ContactIdsSqlParityIT} pins — fix both layers
     * together rather than silently relying on UTF-16 counting here.
     *
     * @param id the contact id; may be null or empty.
     * @return the redacted form: {@link #NULL_SENTINEL} for null,
     *         the bare {@link #ELLIPSIS} for inputs shorter than
     *         {@link #MIN_REDACTABLE_LENGTH}, otherwise
     *         {@code id.substring(0, PREFIX_LENGTH) + ELLIPSIS +
     *         id.substring(id.length() - SUFFIX_LENGTH)}.
     */
    public static String redact(@Nullable String id) {
        if (id == null) {
            return NULL_SENTINEL;
        }
        if (id.length() < MIN_REDACTABLE_LENGTH) {
            return ELLIPSIS;
        }
        return id.substring(0, PREFIX_LENGTH)
                + ELLIPSIS
                + id.substring(id.length() - SUFFIX_LENGTH);
    }
}
