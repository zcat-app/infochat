package app.zcat.infochat.core.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 helper. {@link #hex(byte[])} computes the SHA-256 digest of
 * the input bytes and returns the lower-case hex encoding.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(byte[] input) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JRE-mandated digest; unreachable.
            throw new IllegalStateException("SHA-256 unavailable in this JRE", e);
        }
        return HexFormat.of().formatHex(md.digest(input));
    }
}
