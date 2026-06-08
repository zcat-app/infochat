package app.zcat.infochat.messaging.impl.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-208: {@link SignalIdentity#isWellFormed} is the per-adapter parse
 * validator the bootstrap-admin gate ({@code AdapterRegistry} gate 7b)
 * invokes for {@code infochat.adapters.signal.admin}. A Signal ACI is a
 * canonical lowercase UUID; a value that is not one must be rejected so a
 * mistyped operator id fails Provider startup fast rather than seeding an
 * admin row no real contact can claim (docs/spec/deployment.md
 * §Operator inputs item 2).
 */
class SignalIdentityTest {

    @Test
    void wellFormedAcceptsCanonicalUuidAci() {
        assertTrue(SignalIdentity.isWellFormed("00000000-0000-0000-0000-000000000001"),
                "a canonical lowercase UUID ACI must be accepted");
        assertTrue(SignalIdentity.isWellFormed("a1b2c3d4-e5f6-7890-abcd-ef0123456789"),
                "a mixed-hex canonical UUID ACI must be accepted");
    }

    @Test
    void wellFormedRejectsNonUuidAci() {
        assertFalse(SignalIdentity.isWellFormed("not-a-valid-aci"),
                "a non-UUID string must be rejected");
        assertFalse(SignalIdentity.isWellFormed(""),
                "a blank value must be rejected");
        assertFalse(SignalIdentity.isWellFormed("0-0-0-0-0"),
                "a non-canonical short UUID form must be rejected (round-trip differs)");
    }
}
