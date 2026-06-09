package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.TransportClassificationContractTest;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** SimpleX leg of the shared {@link TransportClassificationContractTest}. */
class SimpleXTransportClassificationTest extends TransportClassificationContractTest {

    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);

    @Override
    protected MessagingException interruptedAwaitingAck() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(), HttpClient.newHttpClient(),
                    msg -> { /* unused */ }, gc -> { /* unused */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                AtomicReference<MessagingException> caught = new AtomicReference<>();
                Thread sender = senderAwaitingAck(client, caught);
                // The command frame is on the wire and the fake never acks,
                // so the sender parks awaiting its ack — interrupt it there.
                fake.awaitFrame(WAIT);
                sender.interrupt();
                sender.join(WAIT.toMillis());
                MessagingException e = caught.get();
                assertNotNull(e, "interrupted sendCommand must throw MessagingException");
                return e;
            } finally {
                client.close();
            }
        }
    }

    @Override
    protected MessagingException closedBeforeAck() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(), HttpClient.newHttpClient(),
                    msg -> { /* unused */ }, gc -> { /* unused */ });
            client.start();
            fake.awaitClient(WAIT);
            AtomicReference<MessagingException> caught = new AtomicReference<>();
            Thread sender = senderAwaitingAck(client, caught);
            fake.awaitFrame(WAIT);
            // Close the transport locally with the ack still outstanding.
            client.close();
            sender.join(WAIT.toMillis());
            MessagingException e = caught.get();
            assertNotNull(e, "closed-before-ack sendCommand must throw MessagingException");
            return e;
        }
    }

    private static Thread senderAwaitingAck(SimpleXWebSocketClient client,
                                            AtomicReference<MessagingException> caught) {
        Thread sender = new Thread(() -> {
            try {
                client.sendCommand("contract-1",
                        SimpleXMessageCodec.encodeSendCommand(
                                "contract-1", new ScopeRef.Dm("peer-queue-addr"), "msg"),
                        ACK_TIMEOUT);
            } catch (MessagingException e) {
                caught.set(e);
            }
        }, "simplex-contract-sender");
        sender.start();
        return sender;
    }
}
