package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins {@link ContactIdRedactor} as the single source of the D37 contact-id
 * log-hygiene token. Both transports — {@code SimpleXWebSocketClient} (calling
 * {@code ContactIdRedactor.redact} directly) and the Signal drop sites (via
 * {@code SignalMessageCodec.redactContactId}, a one-line delegate) — now route
 * through this one helper, so an identical contact id necessarily redacts to an
 * identical token. The duplication this consolidated (M1-472) previously left
 * that cross-transport identity un-enforced; this test pins the exact token
 * form both emit against an independent SHA-256 reference.
 */
class ContactIdRedactorTest {

    /** The token form both transports must emit, computed independently of the helper. */
    private static String referenceToken(String contactId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(contactId.getBytes(StandardCharsets.UTF_8));
            return "contact#" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "smp://abcd1234@relay.example.com",   // SimpleX queue-address shape
        "8f1d6b2e-0a3c-4f5e-9b7a-1c2d3e4f5061", // Signal ACI shape
        "",                                    // degenerate empty id
        "δοκιμή→contact",                       // multi-byte UTF-8
    })
    void redactMatchesIndependentReferenceForBothTransportShapes(String contactId) {
        // ContactIdRedactor.redact is the exact computation both transports run,
        // so matching the reference pins the token every drop site emits.
        assertEquals(referenceToken(contactId), ContactIdRedactor.redact(contactId),
                "redact must equal the SHA-256 first-4-bytes-hex reference token");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "smp://abcd1234@relay.example.com",
        "8f1d6b2e-0a3c-4f5e-9b7a-1c2d3e4f5061",
    })
    void tokenFormIsContactHashPrefixWithEightHexChars(String contactId) {
        assertTrue(ContactIdRedactor.redact(contactId).matches("contact#[0-9a-f]{8}"),
                "token must be \"contact#\" followed by exactly 8 lowercase hex chars");
    }

    @Test
    void redactionIsDeterministicForRepeatedInput() {
        String contactId = "8f1d6b2e-0a3c-4f5e-9b7a-1c2d3e4f5061";
        assertEquals(ContactIdRedactor.redact(contactId), ContactIdRedactor.redact(contactId),
                "the same contact id must always redact to the same token");
    }

    @Test
    void distinctContactIdsRedactToDistinctTokens() {
        // Not a collision proof — just that the helper actually discriminates
        // the two transport-shaped sample ids rather than returning a constant.
        assertNotEquals(
                ContactIdRedactor.redact("smp://abcd1234@relay.example.com"),
                ContactIdRedactor.redact("8f1d6b2e-0a3c-4f5e-9b7a-1c2d3e4f5061"));
    }
}
