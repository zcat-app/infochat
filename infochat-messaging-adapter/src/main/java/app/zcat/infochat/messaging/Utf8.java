package app.zcat.infochat.messaging;

/**
 * The single source of UTF-8 byte-length arithmetic for the messaging
 * surface. Both the codec inbound caps ({@code SignalMessageCodec},
 * {@code SimpleXMessageCodec}) and the Provider's
 * {@code InboundRouter}/{@code AdapterMetrics} compute "UTF-8 byte
 * length" through these two methods, so a body's measured size cannot
 * drift between the cap that rejects it and the metric that records it.
 *
 * <p>Both methods walk the string allocation-free — no
 * {@code getBytes(UTF_8)} copy — because the inbound call sites sit at
 * (or ahead of) the adapter ingest boundary, where a hostile flood must
 * not buy a byte-array allocation per message.</p>
 *
 * <p>Surrogate handling: a Java high surrogate is the lead half of a
 * supplementary code point, which encodes to 4 UTF-8 bytes; the matching
 * low surrogate is consumed by the {@code i++} skip so the pair is
 * counted once. Any other char ≥ U+0800 (including a lone/unpaired
 * surrogate) counts as 3 — the same accounting both prior hand-copies
 * used, preserved verbatim here.</p>
 */
public final class Utf8 {

    private Utf8() {
    }

    /** UTF-8 byte length of {@code s}, walked without allocating a byte[]. */
    public static int byteLength(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x7F) {
                count += 1;
            } else if (c <= 0x7FF) {
                count += 2;
            } else if (Character.isHighSurrogate(c)) {
                count += 4;
                i++;
            } else {
                count += 3;
            }
        }
        return count;
    }

    /**
     * True when {@code s}'s UTF-8 byte length exceeds {@code limit}.
     * Returns as soon as the running count passes {@code limit} (early
     * exit), so an oversize hostile body is walked to ~{@code limit}
     * rather than its full attacker-chosen length.
     */
    public static boolean exceedsByteLength(String s, int limit) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x7F) {
                count += 1;
            } else if (c <= 0x7FF) {
                count += 2;
            } else if (Character.isHighSurrogate(c)) {
                count += 4;
                i++;
            } else {
                count += 3;
            }
            if (count > limit) {
                return true;
            }
        }
        return false;
    }
}
