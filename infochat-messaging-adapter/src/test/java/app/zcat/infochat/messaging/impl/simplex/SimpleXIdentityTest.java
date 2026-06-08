package app.zcat.infochat.messaging.impl.simplex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-208: {@link SimpleXIdentity#isWellFormed} is the per-adapter parse
 * validator the bootstrap-admin gate ({@code AdapterRegistry} gate 7b)
 * invokes for {@code infochat.adapters.simplex.admin}. A SimpleX queue
 * address is a URL-safe base64 encoding of a cryptographic queue id; a
 * short slug or a Signal ACI pasted into the SimpleX slot must be
 * rejected so a mistyped operator id fails Provider startup fast
 * (docs/spec/deployment.md §Operator inputs item 2).
 */
class SimpleXIdentityTest {

    // A 44-char URL-safe base64 value — the shape of a real queue address.
    private static final String WELL_FORMED =
            "SimplexBootstrapAdminQueueAddr0000000000000A";

    @Test
    void wellFormedAcceptsQueueAddress() {
        assertTrue(SimpleXIdentity.isWellFormed(WELL_FORMED),
                "a URL-safe base64 queue address of cryptographic length must be accepted");
    }

    @Test
    void wellFormedRejectsShortSlug() {
        assertFalse(SimpleXIdentity.isWellFormed("simplex-test-bootstrap-admin"),
                "a short human-readable slug must be rejected (below the queue-address length floor)");
        assertFalse(SimpleXIdentity.isWellFormed(""),
                "a blank value must be rejected");
    }

    @Test
    void wellFormedRejectsSignalAci() {
        // Cross-adapter: a Signal ACI (36-char UUID) is not a SimpleX queue
        // address — deployment.md §Operator inputs: "SimpleX contact ids are
        // not Signal ACI/UUIDs".
        assertFalse(SimpleXIdentity.isWellFormed("00000000-0000-0000-0000-000000000001"),
                "a Signal ACI must be rejected by the SimpleX validator");
    }

    @Test
    void wellFormedRejectsForbiddenCharacters() {
        // Reuses the codec charset, which excludes whitespace and the
        // simplex-chat command terminators (@, #, space); the value is well
        // over the length floor so the charset is what rejects it.
        assertFalse(SimpleXIdentity.isWellFormed(
                        "Simplex Bootstrap Admin Queue Addr With Spaces 00A"),
                "a value with whitespace must be rejected by the queue-address charset");
    }
}
