package app.zcat.infochat.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The single source of the D37 contact-id log-hygiene primitive for the
 * messaging surface. A contact id (a SimpleX queue address or a Signal ACI)
 * is a sensitive identifier and is never logged raw; every drop/overflow
 * WARN logs this non-reversible short token instead.
 *
 * <p>Both transports route through this one helper — {@code SimpleXWebSocketClient}
 * and the Signal drop sites (via {@code SignalMessageCodec}) — so the two
 * cannot silently diverge into redacting differently (the failure the
 * duplicated byte-for-byte copies left un-enforced, M1-472). This mirrors the
 * sibling {@link Utf8} helper, which was introduced to eliminate the same
 * cross-package duplication.
 *
 * <p>The token is {@code "contact#"} followed by the first 4 bytes of
 * {@code SHA-256(contactId)} as lowercase hex — a stable, non-reversible
 * 8-hex-char tag, so a repeat flooder stays correlatable across WARN lines
 * without the raw id ever appearing.
 */
public final class ContactIdRedactor {

    private ContactIdRedactor() {
    }

    /**
     * Non-reversible short token for {@code contactId}, safe to log under D37.
     * Deterministic: the same id always yields the same {@code "contact#<8 hex>"}
     * token.
     */
    public static String redact(String contactId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(contactId.getBytes(StandardCharsets.UTF_8));
            return "contact#" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandated algorithm; its absence cannot happen.
            throw new AssertionError(e);
        }
    }
}
