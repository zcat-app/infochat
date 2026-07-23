package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.messaging.impl.signal.SignalAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * CT5 — cross-adapter capability/classification contract. The forcing
 * function against the drift this module accumulated: the same semantic
 * state must classify identically across adapters, and each capability
 * flag must match the platform fact recorded in
 * {@code docs/design/06-messaging.md}. A future edit that re-diverges a
 * flag value or re-classifies a not-connected failure fails here rather
 * than silently shipping.
 *
 * <p>Drives the no-arg (capability-only / unstarted) adapter constructors
 * so no transport or subprocess setup is needed — the not-connected guard
 * is exactly the state an unstarted adapter is in.</p>
 */
class AdapterCapabilityContractTest {

    private static OutboundMessage outbound() {
        return new OutboundMessage(new ScopeRef.Dm("contact-x"), "body", Instant.now(), "corr");
    }

    /**
     * "Not connected" is one category — PERMANENT — across both transport
     * adapters. Before this contract, Signal classified the not-connected
     * send path as TRANSIENT while SimpleX classified it PERMANENT; the
     * reconciliation flips Signal to PERMANENT so the retry layer treats
     * the state identically regardless of which transport raised it.
     * InMemory has no transport-connection guard, so it is not part of the
     * not-connected contract (see {@link #reconciledCapabilityFlagsMatchDesign}
     * for the flag-value contract it does participate in).
     */
    @Test
    void notConnectedSendIsPermanentForEveryTransportAdapter() {
        for (MessagingAdapter adapter : List.of(new SimpleXAdapter(), new SignalAdapter())) {
            MessagingException ex = assertThrows(MessagingException.class,
                    () -> adapter.send(outbound()),
                    adapter.name() + ": unstarted send must throw MessagingException");
            assertEquals(FailureCategory.PERMANENT, ex.category(),
                    adapter.name() + ": not-connected send must classify PERMANENT");
        }
    }

    /**
     * The full unstarted outbound surface (update/finalize, not just send)
     * is PERMANENT on both transport adapters: no path on an unstarted
     * adapter leaks an uncategorised or TRANSIENT failure into the retry
     * layer. Signal reaches PERMANENT via its not-connected guard;
     * SimpleX reaches it via its unknown-handle guard (a dummy handle can
     * never be live on an unstarted adapter) — both are PERMANENT, which is
     * the cross-adapter contract this pins.
     */
    @Test
    void notConnectedUpdateAndFinalizeArePermanent() {
        MessageHandle handle = new MessageHandle("dummy-handle");
        for (MessagingAdapter adapter : List.of(new SimpleXAdapter(), new SignalAdapter())) {
            MessagingException updateEx = assertThrows(MessagingException.class,
                    () -> adapter.update(handle, "body"),
                    adapter.name() + ": unstarted update must throw MessagingException");
            assertEquals(FailureCategory.PERMANENT, updateEx.category(),
                    adapter.name() + ": unstarted update must classify PERMANENT");

            MessagingException finalizeEx = assertThrows(MessagingException.class,
                    () -> adapter.finalizeMessage(handle, "body"),
                    adapter.name() + ": unstarted finalize must throw MessagingException");
            assertEquals(FailureCategory.PERMANENT, finalizeEx.category(),
                    adapter.name() + ": unstarted finalize must classify PERMANENT");
        }
    }

    /**
     * {@link MessagingAdapter#connected()} defaults to {@code true} so
     * transportless adapters read connected without writing a
     * meaningless override. A transport adapter that forgets to
     * override it silently inherits that default instead — no compile
     * error, since the method is optional — and reports connected
     * regardless of the actual wire state. Reflection is the only way
     * to observe "did this class declare the method itself": asserting
     * on the runtime return value can't distinguish an honest {@code
     * true} from an inherited one.
     */
    @Test
    void transportAdaptersOverrideConnected() throws NoSuchMethodException {
        for (MessagingAdapter adapter : List.of(new SimpleXAdapter(), new SignalAdapter())) {
            Class<?> declaringClass = adapter.getClass().getMethod("connected").getDeclaringClass();
            assertNotEquals(MessagingAdapter.class, declaringClass,
                    adapter.name() + ": transport adapter must override connected()");
        }
    }

    /**
     * Each reconciled flag matches its design fact, and the two anchors
     * that were already correct stay put — locking the full cross-adapter
     * posture so a future edit cannot re-drift either the flipped flags or
     * the unchanged ones.
     */
    @Test
    void reconciledCapabilityFlagsMatchDesign() {
        CapabilityFlags simplex = new SimpleXAdapter().capabilities();
        CapabilityFlags signal = new SignalAdapter().capabilities();
        CapabilityFlags inMemory = new InMemoryAdapter().capabilities();

        // Reconciled (flipped to match design).
        assertFalse(simplex.supportsTypingIndicator(),
                "design §6.4.2: SimpleX has no first-class typing indicator");
        assertTrue(signal.supportsCodeFormatting(),
                "design §6.5.2: Signal renders monospace");
        assertTrue(inMemory.supportsCodeFormatting(),
                "design §6.6: in-memory adapter exercises the code-formatting render path");

        // Anchors (already matched design; pinned so they cannot re-drift).
        assertTrue(signal.supportsTypingIndicator(),
                "design §6.5.2: Signal supports typing");
        assertFalse(simplex.supportsCodeFormatting(),
                "design §6.4.2: SimpleX does not render code formatting");
    }
}
