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
        assertEquals(16_384, caps.maxInboundMessageBytes());
        assertEquals(5, caps.maxSendsPerSecond(), "design §6.5.2: conservative 5/s");
        assertTrue(caps.supportsMessageEdit());
        assertTrue(caps.supportsTypingIndicator());
        assertEquals(Duration.ofMillis(600), caps.minEditInterval(),
                "design §6.5.2: 600ms coalescing floor, matching SimpleX");
    }
}
