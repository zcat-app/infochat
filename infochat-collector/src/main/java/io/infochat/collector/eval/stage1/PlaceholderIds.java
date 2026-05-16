package io.infochat.collector.eval.stage1;

import java.security.SecureRandom;

/**
 * Generates the Stage 1 redaction placeholder marker
 * {@code [REDACTED:<id>]} where {@code <id>} is base32 of 16 random
 * bytes — 26 characters in {@code [A-Z2-7]}.
 *
 * <h2>Per-row randomization is load-bearing</h2>
 * <p>Per {@code docs/spec/security.md} §Ingest pipeline:
 * <blockquote>
 * "the per-row {@code <id>} randomization is what stops attackers
 * from pre-crafting a fake placeholder that would survive the
 * Stage 1 {@code <<<UNTRUSTED>>>} marker strip."
 * </blockquote>
 * Every call to {@link #next()} draws fresh bytes from
 * {@link SecureRandom}. There is no cached id, no per-process or
 * per-post seed reuse. If the id were process-startup-fixed, an
 * attacker who learned the value (e.g., from a leaked log) could
 * embed {@code [REDACTED:<known_id>]} in a feed body and Stage 1's
 * delimiter strip would NOT remove it (the strip targets
 * {@code <<<UNTRUSTED>>>} markers, not the placeholder shape). On
 * the Stage-2 judge call the attacker-injected placeholder would
 * masquerade as legitimate redacted content, undermining the
 * prompt-injection-aware wrapper guarantee from
 * {@code docs/spec/llm.md} §Prompt-injection-aware prompt shape.
 *
 * <h2>Encoding</h2>
 * <p>16 random bytes ⇒ 128 bits of entropy. Base32 (RFC 4648, no
 * padding) encodes 5 bits per char, so 128 bits ⇒ 26 chars (the
 * last char carries 3 effective bits + 2 trailing padding bits).
 * Hex would carry the same entropy in 32 chars; base32 is more
 * compact and the alphabet ({@code A-Z2-7}) avoids visual ambiguity.
 *
 * <h2>The {@code [REDACTED:} prefix and trailing {@code ]}</h2>
 * <p>Those bytes are byte-identical across the implementation per
 * {@code docs/spec/security.md} §Ingest pipeline (the LLM Stage-2
 * judge prompt-shape relies on the literal marker). The
 * randomization lives ENTIRELY in the {@code <id>} payload.
 */
public final class PlaceholderIds {

    /**
     * RFC 4648 base32 alphabet (the only alphabet where the
     * placeholder regex {@code ^\[REDACTED:[A-Z2-7]{26}\]$} is the
     * accepted shape per the ticket acceptance items).
     */
    private static final char[] BASE32_ALPHABET = {
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
        'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
        'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
        'Y', 'Z', '2', '3', '4', '5', '6', '7'
    };

    /**
     * 16 bytes is the spec-committed entropy width — 128 bits.
     * Documented at {@code docs/design/04-security.md} §4.2 step 4.
     */
    static final int RANDOM_BYTES = 16;

    /**
     * Length of the base32 representation of {@link #RANDOM_BYTES}
     * bytes without padding: {@code ceil(16 * 8 / 5) = 26} chars.
     */
    static final int ENCODED_LENGTH = 26;

    /**
     * One {@link SecureRandom} per class load. SecureRandom is
     * thread-safe in the JDK SecureRandomImpl, so a static instance
     * is the right shape for the Stage 1 worker thread (and for any
     * future concurrent invocation).
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlaceholderIds() {
        // utility class
    }

    /**
     * Returns a fresh placeholder id — a 26-character base32 string.
     * Every invocation draws fresh bytes from
     * {@link SecureRandom#nextBytes(byte[])}; no caching anywhere
     * in the call path. The id is the {@code <id>} payload in
     * {@code [REDACTED:<id>]}; pair it with {@link #marker(String)}
     * to produce the full marker.
     */
    public static String next() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /**
     * Wraps an id in the spec-committed {@code [REDACTED:<id>]}
     * marker shape. The prefix and suffix are byte-identical across
     * the implementation per {@code docs/spec/security.md} §Ingest
     * pipeline; centralizing the literal here means there is one
     * source of truth.
     */
    public static String marker(String id) {
        return "[REDACTED:" + id + "]";
    }

    /**
     * Convenience: {@code marker(next())} — fresh id wrapped in the
     * marker shape. Used in tests and any caller that doesn't need
     * the raw id separately.
     */
    public static String nextPlaceholder() {
        return marker(next());
    }

    /**
     * Encode 16 bytes as 26 base32 chars (RFC 4648, no padding).
     * Inline implementation rather than pulling
     * {@link java.util.Base64} (Base64 alphabet differs) or a
     * third-party base32 library (no third-party crypto/encoding
     * dependency is justified for 30 lines of bit-shuffling).
     *
     * <p>Algorithm: iterate over the bit stream in 5-bit chunks.
     * The 16-byte input has 128 bits; 128 / 5 = 25.6, so the 26th
     * char carries the trailing 3 bits left-padded with zeros.
     */
    private static String encodeBase32(byte[] bytes) {
        char[] out = new char[ENCODED_LENGTH];
        int bitBuffer = 0;
        int bitsInBuffer = 0;
        int outIndex = 0;
        for (byte b : bytes) {
            bitBuffer = (bitBuffer << 8) | (b & 0xff);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                int chunk = (bitBuffer >>> bitsInBuffer) & 0x1f;
                out[outIndex++] = BASE32_ALPHABET[chunk];
            }
        }
        // Final partial chunk: 3 leftover bits, left-pad with zeros
        // to a full 5-bit index.
        if (bitsInBuffer > 0) {
            int chunk = (bitBuffer << (5 - bitsInBuffer)) & 0x1f;
            out[outIndex++] = BASE32_ALPHABET[chunk];
        }
        return new String(out);
    }
}
