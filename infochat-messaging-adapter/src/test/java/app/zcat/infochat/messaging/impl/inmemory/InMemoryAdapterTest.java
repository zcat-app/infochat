package app.zcat.infochat.messaging.impl.inmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for {@link InMemoryAdapter}. Covers the SPI surface
 * the M1-035 umbrella's whole-topic IT (Provider-side, deferred to the
 * umbrella commit) cannot assert in isolation: identity stability
 * across multiple inbound messages, the send → update → finalize
 * history shape, finalize exclusivity, typing-event ordering, and the
 * trust-level constructor defaulting.
 *
 * <p>Capability-flag values (supportsCodeFormatting=true,
 * supportsMarkdownLinks=false, supportsMentionByContactId=true,
 * supportsMembershipEvents=true) are asserted alongside the trust-level
 * constructor test rather than each in their own @Test method — the
 * assertions cost one line each and grouping them keeps the test class
 * focused on behavior rather than capability declarations.</p>
 */
class InMemoryAdapterTest {

    @Test
    void identityIsStableAcrossMultipleDeliveriesForSameContact() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        List<Identity> seen = new ArrayList<>();
        adapter.setInboundHandler(msg -> seen.add(msg.sender()));

        adapter.deliverDm("alice", "hello");
        adapter.deliverDm("alice", "hello again");
        adapter.deliverDm("bob", "hi");

        assertEquals(3, seen.size());
        assertSame(seen.get(0), seen.get(1),
                "Same contactId must resolve to the same Identity instance across messages");
        assertEquals("alice", seen.get(0).contactId());
        assertEquals("bob", seen.get(2).contactId());
    }

    @Test
    void sendUpdateFinalizeProducesExpectedHistory() throws MessagingException {
        InMemoryAdapter adapter = new InMemoryAdapter();
        OutboundMessage outbound = new OutboundMessage(
                new ScopeRef.Dm("alice"), "first body", Instant.now(), "corr-1");

        MessageHandle handle = adapter.send(outbound);
        adapter.update(handle, "second body");
        adapter.update(handle, "third body");
        adapter.finalizeMessage(handle, "final body");

        List<InMemoryAdapter.UpdateEvent> history = adapter.updateHistory(handle);
        assertEquals(4, history.size());
        assertEquals("first body", history.get(0).body());
        assertFalse(history.get(0).isFinal());
        assertEquals("second body", history.get(1).body());
        assertEquals("third body", history.get(2).body());
        assertEquals("final body", history.get(3).body());
        assertTrue(history.get(3).isFinal(),
                "Last history entry must be marked final");

        assertEquals(1, adapter.sentMessages().size());
        assertEquals(outbound, adapter.sentMessages().get(0));
    }

    @Test
    void updateAfterFinalizeOnSameHandleThrowsPermanent() throws MessagingException {
        InMemoryAdapter adapter = new InMemoryAdapter();
        MessageHandle handle = adapter.send(new OutboundMessage(
                new ScopeRef.Dm("alice"), "body", Instant.now(), "corr-1"));
        adapter.finalizeMessage(handle, "done");

        MessagingException update = assertThrows(MessagingException.class,
                () -> adapter.update(handle, "too late"));
        assertEquals(FailureCategory.PERMANENT, update.category());

        // finalize is also forbidden after finalize on the same handle.
        MessagingException refinalize = assertThrows(MessagingException.class,
                () -> adapter.finalizeMessage(handle, "really done"));
        assertEquals(FailureCategory.PERMANENT, refinalize.category());
    }

    @Test
    void setTypingTogglesAreRecordedInOrder() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        ScopeRef scope = new ScopeRef.Dm("alice");

        adapter.setTyping(scope, true);
        adapter.setTyping(scope, false);
        adapter.setTyping(scope, true);

        List<InMemoryAdapter.TypingEvent> events = adapter.typingEventHistory();
        assertEquals(3, events.size());
        assertTrue(events.get(0).typing());
        assertFalse(events.get(1).typing());
        assertTrue(events.get(2).typing());
        assertEquals(scope, events.get(0).scope());
    }

    @Test
    void defaultTrustLevelIsLowAndHighConstructorFlipsIt() {
        InMemoryAdapter defaultAdapter = new InMemoryAdapter();
        InMemoryAdapter highAdapter = new InMemoryAdapter(AdapterTrustLevel.HIGH);

        assertEquals(AdapterTrustLevel.LOW, defaultAdapter.trustLevel());
        assertEquals(AdapterTrustLevel.HIGH, highAdapter.trustLevel());

        // Capability declarations the umbrella locks in (MVP §4 readability;
        // §6.2.1 markdown-link gate; §6.6 group-test coverage rationale).
        assertTrue(defaultAdapter.capabilities().supportsCodeFormatting());
        assertFalse(defaultAdapter.capabilities().supportsMarkdownLinks());
        assertTrue(defaultAdapter.capabilities().supportsMentionByContactId());
        assertTrue(defaultAdapter.capabilities().supportsMembershipEvents());
    }

    @Test
    void resetClearsSentAndHistory() throws MessagingException {
        InMemoryAdapter adapter = new InMemoryAdapter();
        adapter.setInboundHandler(msg -> {});
        adapter.deliverDm("alice", "hi");
        adapter.send(new OutboundMessage(
                new ScopeRef.Dm("alice"), "body", Instant.now(), "corr-1"));
        adapter.setTyping(new ScopeRef.Dm("alice"), true);

        adapter.reset();

        assertEquals(0, adapter.sentMessages().size());
        assertEquals(0, adapter.typingEventHistory().size());
    }

    @Test
    void sendAttachmentRecordsThePayloadTuple() throws MessagingException {
        InMemoryAdapter adapter = new InMemoryAdapter();
        OutboundAttachment attachment = new OutboundAttachment(
                new ScopeRef.Dm("alice"),
                "/tmp/test-image.png",
                "image/png",
                "test-image.png",
                "corr-att-1");

        // Completion signal per the SPI javadoc: the call returns on
        // delivery completion; a classified failure would throw instead.
        adapter.sendAttachment(attachment);

        List<OutboundAttachment> recorded = adapter.sentAttachments();
        assertEquals(1, recorded.size());
        assertEquals(attachment, recorded.get(0));
    }
}
