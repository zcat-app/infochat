package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.TransportClassificationContractTest;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Signal leg of the shared {@link TransportClassificationContractTest}. */
class SignalTransportClassificationTest extends TransportClassificationContractTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long WAIT_MS = 2_000;

    @Override
    protected MessagingException interruptedAwaitingAck() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                AtomicReference<MessagingException> caught = new AtomicReference<>();
                Thread sender = senderAwaitingAck(client, caught);
                // The request is on the wire and the fake never answers, so
                // the sender parks awaiting its ack — interrupt it there.
                fake.nextOutbound(WAIT_MS);
                sender.interrupt();
                sender.join(WAIT_MS);
                MessagingException e = caught.get();
                assertNotNull(e, "interrupted send must throw MessagingException");
                return e;
            } finally {
                client.disconnect();
            }
        }
    }

    @Override
    protected MessagingException closedBeforeAck() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT);
            client.connect();
            AtomicReference<MessagingException> caught = new AtomicReference<>();
            Thread sender = senderAwaitingAck(client, caught);
            fake.nextOutbound(WAIT_MS);
            // Close the transport locally with the ack still outstanding.
            client.disconnect();
            sender.join(WAIT_MS);
            MessagingException e = caught.get();
            assertNotNull(e, "closed-before-ack send must throw MessagingException");
            return e;
        }
    }

    private static Thread senderAwaitingAck(SignalJsonRpcClient client,
                                            AtomicReference<MessagingException> caught) {
        Thread sender = new Thread(() -> {
            try {
                client.send(new OutboundMessage(
                        new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                        "msg", Instant.now(), "contract-1"));
            } catch (MessagingException e) {
                caught.set(e);
            }
        }, "signal-contract-sender");
        sender.start();
        return sender;
    }
}
