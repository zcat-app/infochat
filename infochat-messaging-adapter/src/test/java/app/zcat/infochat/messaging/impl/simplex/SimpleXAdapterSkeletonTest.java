package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SimpleXAdapterSkeletonTest {

    @Test
    void capabilitiesAreCorrect() {
        SimpleXAdapter adapter = new SimpleXAdapter();

        assertEquals("simplex", adapter.name());
        assertEquals(AdapterTrustLevel.HIGH, adapter.trustLevel());

        CapabilityFlags caps = adapter.capabilities();
        assertTrue(caps.supportsMentionByContactId(), "SimpleX mentions by queue address");
        assertFalse(caps.supportsMembershipEvents(),
                "skeleton declares false; M1-104 flips this when group support lands");
        assertFalse(caps.supportsCodeFormatting());
        assertFalse(caps.supportsMarkdownLinks(), "v1 adapters MUST declare supportsMarkdownLinks=false");
        assertFalse(caps.supportsMultilineCode());
        assertFalse(caps.supportsAttachments());
        assertFalse(caps.supportsThreading());
        assertEquals(2_000, caps.maxMessageBytes());
        assertEquals(16_384, caps.maxInboundMessageBytes());
        assertEquals(4, caps.maxInflightSends());
        assertEquals(8, caps.maxSendsPerSecond());
        assertTrue(caps.supportsMessageEdit(), "SimpleX supports message edits");
        assertFalse(caps.supportsTypingIndicator(), "design §6.4.2: SimpleX has no first-class typing indicator");
        assertEquals(Duration.ZERO, caps.minEditInterval());
    }
}
