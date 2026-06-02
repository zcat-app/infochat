package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SignalAdapterSkeletonTest {

    @Test
    void capabilitiesAreCorrect() {
        SignalAdapter adapter = new SignalAdapter();

        assertEquals("signal", adapter.name());
        assertEquals(AdapterTrustLevel.HIGH, adapter.trustLevel());

        CapabilityFlags caps = adapter.capabilities();
        assertTrue(caps.supportsMentionByContactId(), "Signal mentionUuid = ACI");
        assertTrue(caps.supportsMembershipEvents(), "Signal exposes native membership events");
        assertTrue(caps.supportsCodeFormatting(), "design §6.5.2: Signal renders monospace");
        assertFalse(caps.supportsMarkdownLinks(), "v1 adapters MUST declare supportsMarkdownLinks=false");
        assertFalse(caps.supportsMultilineCode());
        assertFalse(caps.supportsAttachments());
        assertFalse(caps.supportsThreading());
        assertEquals(2_000, caps.maxMessageBytes());
        assertEquals(16_384, caps.maxInboundMessageBytes());
        assertEquals(4, caps.maxInflightSends());
        assertEquals(8, caps.maxSendsPerSecond());
        assertTrue(caps.supportsMessageEdit());
        assertTrue(caps.supportsTypingIndicator());
        assertEquals(Duration.ZERO, caps.minEditInterval());
    }
}
