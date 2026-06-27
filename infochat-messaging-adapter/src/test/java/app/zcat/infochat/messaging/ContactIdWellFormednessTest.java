package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.messaging.impl.signal.SignalAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter;

import org.junit.jupiter.api.Test;

/**
 * Per-adapter contract for {@link MessagingAdapter#isWellFormedContactId}:
 * each adapter answers for its own transport's contact-id format, and the
 * answers match the identity validators the adapters delegate to
 * ({@code SignalIdentity} / {@code SimpleXIdentity}) — the shared SPI
 * surface Provider dispatches through when validating
 * {@code infochat.adapters.<name>.admin} values at startup
 * ({@code docs/spec/deployment.md} §Operator inputs item 2).
 *
 * <p>Drives the no-arg (capability-only / unstarted) adapter constructors —
 * the format probe is pure (no transport, no persistence), so an unstarted
 * adapter must answer it.</p>
 */
class ContactIdWellFormednessTest {

    @Test
    void signalAcceptsCanonicalLowercaseUuidAci() {
        assertTrue(new SignalAdapter()
                        .isWellFormedContactId("00000000-0000-0000-0000-000000000001"),
                "a canonical lowercase UUID is a well-formed Signal ACI");
    }

    @Test
    void signalRejectsNonUuidAndNonCanonicalUuidForms() {
        SignalAdapter signal = new SignalAdapter();
        assertFalse(signal.isWellFormedContactId("not-a-valid-aci"),
                "a non-UUID value is not a well-formed Signal ACI");
        assertFalse(signal.isWellFormedContactId("00000000-0000-0000-0000-00000000000A"),
                "an uppercase (non-canonical) UUID form must be rejected, not coerced");
    }

    @Test
    void simplexAcceptsUrlSafeBase64QueueAddressOfCryptographicLength() {
        assertTrue(new SimpleXAdapter()
                        .isWellFormedContactId("SimplexBootstrapAdmin00000000001"),
                "a 32-char URL-safe-base64 value (the real queue-address width) is well-formed");
    }

    @Test
    void simplexRejectsShortSlugsAndNonBase64Characters() {
        SimpleXAdapter simplex = new SimpleXAdapter();
        assertFalse(simplex.isWellFormedContactId("short-mistyped-slug"),
                "a value below the cryptographic length floor must be rejected");
        assertFalse(simplex.isWellFormedContactId("Simplex+QueueAddr00000000000001A"),
                "a non-URL-safe character ('+') must be rejected even at valid length");
    }

    @Test
    void inMemoryAcceptsFreeFormContactIds() {
        InMemoryAdapter inMemory = new InMemoryAdapter();
        assertTrue(inMemory.isWellFormedContactId("test-bootstrap-contact"),
                "the in-memory adapter's contact-id format is free-form by documented contract");
        assertTrue(inMemory.isWellFormedContactId("any string at all"),
                "the in-memory adapter accepts arbitrary test-authored ids");
    }
}
