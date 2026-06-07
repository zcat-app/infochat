package app.zcat.infochat.collector.stream.nostr;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECPoint;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * BIP-340 Schnorr signature verifier for Nostr events. Stateless and
 * thread-safe — a single shared instance services every relay of every source.
 *
 * <p>Bouncy Castle 1.80 does not expose a public BIP-340 verifier; this class
 * hand-rolls the BIP-340 framing (x-only pubkey lift, tagged-hash challenge,
 * {@code R = s·G + (n−e)·P} reconstruction, R-at-infinity / odd-y rejection)
 * on top of BC's secp256k1 curve parameters and {@link ECPoint} math.</p>
 *
 * <p>The high-level {@link #verify(NostrEvent)} entry first recomputes the
 * NIP-01 canonical id from the event fields and compares to the relay-supplied
 * id, then BIP-340-verifies the signature against (id, pubkey). Both checks
 * must pass.</p>
 *
 * <p>The NIP-01 canonical serializer is hand-rolled rather than Jackson-driven:
 * the id check IS the security boundary, and a future Jackson upgrade or stray
 * {@code MAPPER.configure(...)} could silently shift escape behavior. A manual
 * serializer that only knows the six canonical fields is correct by
 * construction and dependency-stable.</p>
 */
public final class NostrEventVerifier {

    private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
    private static final BigInteger P = CURVE.getCurve().getField().getCharacteristic();
    private static final BigInteger N = CURVE.getN();
    private static final ECPoint G = CURVE.getG();

    // BIP-340 tagged-hash prefix for the challenge: SHA256(tag) || SHA256(tag).
    // Precomputed so verify() never re-hashes the literal "BIP0340/challenge".
    private static final byte[] CHALLENGE_TAG_PREFIX = doubleTagHash("BIP0340/challenge");

    /**
     * Verify a relay-supplied Nostr event end-to-end: id integrity + signature.
     * Returns true only when the relay-supplied {@code id} equals the SHA-256
     * of the NIP-01 canonical serialization AND the BIP-340 signature validates
     * against {@code (id, pubkey)}. Both gates are required — the id check
     * prevents a relay from substituting content under a valid signature for a
     * different id, and the signature check prevents an unsigned event from
     * passing once its content matches its id.
     *
     * <p>Returns false (rather than throwing) for every form of malformed
     * input — short hex, non-hex chars, null fields, off-curve pubkey, etc.
     * The caller cannot meaningfully distinguish "malformed" from "wrong sig"
     * at the trust boundary; both mean "drop, increment failed-sig counter".</p>
     */
    public boolean verify(NostrEvent event) {
        if (event.id() == null || event.pubkey() == null || event.sig() == null
                || event.content() == null || event.tags() == null) {
            return false;
        }
        byte[] claimedId = decodeHex(event.id(), 32);
        byte[] pubkey = decodeHex(event.pubkey(), 32);
        byte[] sig = decodeHex(event.sig(), 64);
        if (claimedId == null || pubkey == null || sig == null) {
            return false;
        }
        byte[] canonical;
        try {
            canonical = nip01Canonical(event);
        } catch (RuntimeException e) {
            return false;
        }
        byte[] computedId = sha256(canonical);
        if (!constantTimeEquals(computedId, claimedId)) {
            return false;
        }
        return verifySchnorr(pubkey, computedId, sig);
    }

    /**
     * BIP-340 verify on raw bytes. Package-private so the BIP-340 spec
     * test-vectors (which deal in {@code (pubkey32, msg32, sig64)} triples)
     * can exercise the algorithm directly without faking a full
     * {@link NostrEvent}.
     */
    boolean verifySchnorr(byte[] pubkey32, byte[] msg32, byte[] sig64) {
        if (pubkey32.length != 32 || msg32.length != 32 || sig64.length != 64) {
            return false;
        }
        BigInteger px = new BigInteger(1, pubkey32);
        if (px.compareTo(P) >= 0) {
            return false;
        }
        ECPoint pubPoint = liftX(px);
        if (pubPoint == null) {
            return false;
        }
        byte[] rBytes = new byte[32];
        byte[] sBytes = new byte[32];
        System.arraycopy(sig64, 0, rBytes, 0, 32);
        System.arraycopy(sig64, 32, sBytes, 0, 32);
        BigInteger r = new BigInteger(1, rBytes);
        BigInteger s = new BigInteger(1, sBytes);
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) {
            return false;
        }

        // e = int(SHA256(tag_prefix || r || px || m)) mod n
        byte[] eInput = new byte[CHALLENGE_TAG_PREFIX.length + 96];
        System.arraycopy(CHALLENGE_TAG_PREFIX, 0, eInput, 0, CHALLENGE_TAG_PREFIX.length);
        int off = CHALLENGE_TAG_PREFIX.length;
        System.arraycopy(rBytes, 0, eInput, off, 32);
        off += 32;
        System.arraycopy(pubkey32, 0, eInput, off, 32);
        off += 32;
        System.arraycopy(msg32, 0, eInput, off, 32);
        BigInteger e = new BigInteger(1, sha256(eInput)).mod(N);

        // R = s*G - e*P, computed as s*G + (n - e)*P to keep all operands
        // in the positive scalar range expected by ECPoint.multiply.
        ECPoint sG = G.multiply(s);
        ECPoint negEP = pubPoint.multiply(N.subtract(e));
        ECPoint reconstructedR = sG.add(negEP).normalize();
        if (reconstructedR.isInfinity()) {
            return false;
        }
        // BIP-340 demands has_even_y(R) AND x(R) == r.
        if (reconstructedR.getYCoord().toBigInteger().testBit(0)) {
            return false;
        }
        return reconstructedR.getXCoord().toBigInteger().equals(r);
    }

    /**
     * BIP-340 lift_x: returns the even-y point on secp256k1 with x-coord
     * {@code x}, or null if no such point exists (i.e. {@code x^3 + 7 mod p}
     * is not a quadratic residue, or {@code x ≥ p}).
     */
    private static @Nullable ECPoint liftX(BigInteger x) {
        if (x.compareTo(P) >= 0) {
            return null;
        }
        BigInteger ySquared = x.modPow(BigInteger.valueOf(3), P)
                .add(BigInteger.valueOf(7))
                .mod(P);
        // p = 3 mod 4 for secp256k1, so the square root (when one exists)
        // is y = c^((p+1)/4) mod p.
        BigInteger y = ySquared.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.modPow(BigInteger.TWO, P).equals(ySquared)) {
            return null;
        }
        if (y.testBit(0)) {
            y = P.subtract(y);
        }
        try {
            return CURVE.getCurve().createPoint(x, y);
        } catch (IllegalArgumentException notOnCurve) {
            return null;
        }
    }

    /**
     * NIP-01 canonical event serialization:
     * {@code [0, pubkey, created_at, kind, tags, content]} as UTF-8 JSON
     * with no whitespace and the NIP-01 escape set (only quote, backslash,
     * the C0 shorthand escapes for newline / carriage-return / tab / backspace
     * / form-feed, and a four-hex unicode escape for other 0x00..0x1F controls).
     */
    static byte[] nip01Canonical(NostrEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("[0,\"");
        sb.append(event.pubkey());
        sb.append("\",");
        sb.append(event.createdAt());
        sb.append(',');
        sb.append(event.kind());
        sb.append(',');
        appendTags(sb, event.tags());
        sb.append(',');
        appendJsonString(sb, event.content());
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendTags(StringBuilder sb, List<List<String>> tags) {
        sb.append('[');
        boolean firstTag = true;
        for (List<String> tag : tags) {
            if (!firstTag) {
                sb.append(',');
            }
            firstTag = false;
            sb.append('[');
            boolean firstField = true;
            for (String field : tag) {
                if (!firstField) {
                    sb.append(',');
                }
                firstField = false;
                appendJsonString(sb, field);
            }
            sb.append(']');
        }
        sb.append(']');
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            int nibble = (c >> shift) & 0xF;
                            sb.append((char) (nibble < 10 ? '0' + nibble : 'a' + nibble - 10));
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static byte @Nullable [] decodeHex(String hex, int expectedBytes) {
        if (hex.length() != expectedBytes * 2) {
            return null;
        }
        byte[] out = new byte[expectedBytes];
        for (int i = 0; i < expectedBytes; i++) {
            int hi = hexNibble(hex.charAt(2 * i));
            int lo = hexNibble(hex.charAt(2 * i + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexNibble(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * Constant-time byte-array equality. The id check operates on attacker-
     * supplied bytes against a deterministically computed value; a timing
     * leak there would let a hostile relay probe SHA-256 output structure.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] doubleTagHash(String tag) {
        byte[] th = sha256(tag.getBytes(StandardCharsets.US_ASCII));
        byte[] out = new byte[th.length * 2];
        System.arraycopy(th, 0, out, 0, th.length);
        System.arraycopy(th, 0, out, th.length, th.length);
        return out;
    }
}
