package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M1-800: Signal sendAttachment over the fake-daemon harness (D74, design §6.2.4) — the classified unreadable-path failure, the attach-by-path wire form, and the send-response completion contract.
 */
class SignalAdapterAttachmentTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUEUE_WAIT_MS = 2_000;
    private static final String RECIPIENT = "aabbccdd-1111-2222-3333-444455556666";

    @TempDir
    Path spoolDir;

    @Test
    void unreadableAttachmentPathFailsClassifiedPermanent() {
        // Acceptance item 6: an unreadable path at send time fails as a
        // classified MessagingException — never an escaping IOException,
        // never a silent skip. The guard runs before any transport touch.
        SignalAdapter adapter = new SignalAdapter();
        OutboundAttachment missing = attachment(spoolDir.resolve("missing.png"));
        MessagingException ex = assertThrows(MessagingException.class,
                () -> adapter.sendAttachment(missing));
        assertEquals(FailureCategory.PERMANENT, ex.category(),
                "a missing spool file can never succeed on retry");
    }

    @Test
    void sendAttachmentAttachesByPathAndCompletesOnSendResponse() throws Exception {
        Path spoolFile = Files.writeString(spoolDir.resolve("img.png"), "payload-bytes");
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                SignalAdapter adapter = new SignalAdapter();
                adapter.attachClient(client);

                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread sender = runSender(adapter, attachment(spoolFile), failure);

                JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", req.getString("method"));
                JsonObject params = req.getJsonObject("params");
                assertEquals("+15551111111", params.getString("account"));
                assertEquals(RECIPIENT, params.getJsonArray("recipient").getString(0));
                assertEquals(spoolFile.toString(),
                        params.getJsonArray("attachments").getString(0),
                        "signal-cli attaches by file path — the payload bytes"
                                + " never cross the JSON-RPC channel");
                assertEquals("", params.getString("message"),
                        "an attachment send carries no text body");

                // The send response IS the completion signal: signal-cli
                // answers only after the CDN upload finishes, so the
                // response releases the blocked caller.
                fake.respondSuccess(req.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000020000L).build());
                sender.join(QUEUE_WAIT_MS);
                assertNull(failure.get(),
                        "a successful send response completes sendAttachment: " + failure.get());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void sendAttachmentDaemonErrorFailsClassified() throws Exception {
        Path spoolFile = Files.writeString(spoolDir.resolve("img.png"), "payload-bytes");
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                SignalAdapter adapter = new SignalAdapter();
                adapter.attachClient(client);

                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread sender = runSender(adapter, attachment(spoolFile), failure);

                JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
                // A non-(-32603) daemon error classifies PERMANENT per the
                // existing JSON-RPC classification — an attachment the
                // transport rejected is not retried into a loop.
                fake.respondError(req.getString("id"), -32602, "attachment rejected");
                sender.join(QUEUE_WAIT_MS);
                MessagingException ex = assertInstanceOf(MessagingException.class, failure.get(),
                        "a daemon error must surface as a classified MessagingException");
                assertEquals(FailureCategory.PERMANENT, ex.category());
            } finally {
                client.disconnect();
            }
        }
    }

    private static OutboundAttachment attachment(Path path) {
        return new OutboundAttachment(new ScopeRef.Dm(RECIPIENT),
                path.toString(), "image/png", path.getFileName().toString(), "corr-att", null);
    }

    private static Thread runSender(SignalAdapter adapter, OutboundAttachment attachment,
                                    AtomicReference<Exception> failure) {
        Thread t = Thread.ofVirtual().name("attachment-sender").start(() -> {
            try {
                adapter.sendAttachment(attachment);
            } catch (Exception e) {
                failure.set(e);
            }
        });
        return t;
    }
}
