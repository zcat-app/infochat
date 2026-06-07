package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.ScopeRef;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
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
        assertEquals(5, caps.maxSendsPerSecond(), "design §6.4.2: at most 5/s averaged");
        assertTrue(caps.supportsMessageEdit(), "SimpleX supports message edits");
        assertFalse(caps.supportsTypingIndicator(), "design §6.4.2: SimpleX has no first-class typing indicator");
        assertEquals(Duration.ofMillis(600), caps.minEditInterval(), "design §6.4.2: conservative 600ms floor");
    }

    @Test
    void setTypingIsNoOpAndIssuesNoTransportCommand() throws Exception {
        // supportsTypingIndicator is false, so the SPI contract makes
        // setTyping a no-op — even a wired adapter must not issue an
        // apiSetContactTyping-shaped command. Wire a recording socket
        // through the real client (the reflection idiom from
        // SimpleXWebSocketClientTest) so any transmitted frame is
        // observable.
        SimpleXAdapter adapter = new SimpleXAdapter();
        SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                URI.create("ws://127.0.0.1:1"),
                HttpClient.newHttpClient(),
                msg -> { /* unused */ },
                gc -> { /* unused */ });
        OneOutstandingWebSocket recorder = new OneOutstandingWebSocket(
                0, OneOutstandingWebSocket.CollisionMode.ASYNC_FAILED_FUTURE);
        Field wsField = SimpleXWebSocketClient.class.getDeclaredField("webSocket");
        wsField.setAccessible(true);
        wsField.set(client, recorder);
        Field clientField = SimpleXAdapter.class.getDeclaredField("webSocket");
        clientField.setAccessible(true);
        clientField.set(adapter, client);

        adapter.setTyping(new ScopeRef.Dm("recipient-queue"), true);
        adapter.setTyping(new ScopeRef.Dm("recipient-queue"), false);

        assertEquals(0, recorder.acceptedCount(),
                "setTyping must issue no transport command when supportsTypingIndicator=false");
    }
}
