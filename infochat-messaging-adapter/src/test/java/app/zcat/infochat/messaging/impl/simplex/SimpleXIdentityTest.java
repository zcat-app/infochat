package app.zcat.infochat.messaging.impl.simplex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-208: {@link SimpleXIdentity#isWellFormed} is the per-adapter parse
 * validator the bootstrap-admin gate ({@code AdapterRegistry} gate 7b)
 * invokes for {@code infochat.adapters.simplex.admin}. A SimpleX queue
 * address is a URL-safe base64 encoding of a cryptographic queue id; a
 * short slug below the length floor is rejected so a mistyped operator id
 * fails Provider startup fast (docs/spec/deployment.md §Operator inputs
 * item 2). A 36-char Signal ACI is no longer caught by the floor (M1-504
 * refine — it is longer than the 32-char real address; see that test).
 */
class SimpleXIdentityTest {

    // A 32-char URL-safe base64 value — the real wire width of a SimpleX
    // queue address (24-byte recipient queue id, M1-504).
    private static final String WELL_FORMED =
            "SimplexQueueAddrRealLength00001A";

    @Test
    void wellFormedAcceptsQueueAddress() {
        assertTrue(SimpleXIdentity.isWellFormed(WELL_FORMED),
                "a URL-safe base64 queue address of cryptographic length must be accepted");
    }

    @Test
    void wellFormedAcceptsRealLength32CharQueueAddress() {
        // M1-504: a real derived SimpleX queue address is a 24-byte recipient
        // queue id = exactly 32 URL-safe-base64 chars. The prior 43-char floor
        // rejected every real address and kept the adapter from starting. Pin
        // the real width here so a future floor regression fails loudly.
        assertEquals(32, WELL_FORMED.length(),
                "the fixture must be the real 32-char queue-address width");
        assertTrue(SimpleXIdentity.isWellFormed(WELL_FORMED),
                "a real-length 32-char queue address must be accepted");
    }

    @Test
    void wellFormedRejectsShortSlug() {
        assertFalse(SimpleXIdentity.isWellFormed("simplex-test-bootstrap-admin"),
                "a short human-readable slug must be rejected (below the queue-address length floor)");
        assertFalse(SimpleXIdentity.isWellFormed(""),
                "a blank value must be rejected");
    }

    @Test
    void wellFormedNoLongerRejectsSignalAciByLength() {
        // M1-504 refine (2026-06-27, accepted regression): a 36-char Signal
        // ACI UUID is LONGER than the 32-char real queue address, so the
        // length floor that admits the real address cannot exclude the ACI,
        // and a UUID's chars (digits and '-') all pass the queue-address
        // charset. A Signal ACI pasted into the SimpleX admin slot therefore
        // now passes this format gate (a minor M1-208 fail-fast loss, see the
        // ticket Notes). Characterization test: pins the known behavior so an
        // accidental change is caught and the gap stays documented.
        assertTrue(SimpleXIdentity.isWellFormed("00000000-0000-0000-0000-000000000001"),
                "a 36-char Signal ACI is no longer rejected by the length floor (M1-504)");
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
