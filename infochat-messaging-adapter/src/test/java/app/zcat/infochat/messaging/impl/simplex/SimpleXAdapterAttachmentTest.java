package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * M1-800: SimpleX sendAttachment over the fake-process harness (D74, design §6.2.4) — the classified unreadable-path failure, the blocking XFTP-completion contract, and the adapter's no-retention property.
 */
class SimpleXAdapterAttachmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);

    @TempDir
    Path spoolDir;

    @TempDir
    Path dataDir;

    @Test
    void unreadableAttachmentPathFailsClassifiedPermanent() throws Exception {
        // Acceptance item 6: an unreadable path at send time fails as a classified
        // MessagingException — never an escaping IOException, never a silent skip;
        // the guard runs before any transport touch.
        SimpleXAdapter adapter = new SimpleXAdapter();
        OutboundAttachment missing = attachment(spoolDir.resolve("missing.png"));
        MessagingException ex = assertThrows(MessagingException.class,
                () -> adapter.sendAttachment(missing));
        assertEquals(FailureCategory.PERMANENT, ex.category(),
                "a missing spool file can never succeed on retry");
    }

    @Test
    void sendAttachmentBlocksOnCompletionEventThenReturns() throws Exception {
        Path spoolFile = Files.writeString(spoolDir.resolve("img.png"), "payload-bytes");
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, dataDir);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                Thread acker = Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                        fake.sendFrame(SimpleXTestHarness.ackFrame(corrId, 1));
                        // Withhold the completion event: the ack alone must
                        // NOT release sendAttachment — the XFTP upload
                        // finishes asynchronously past it (design §6.2.4).
                        Thread.sleep(500);
                        fake.sendFrame(fileCompletionFrame("item-1"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                AtomicBoolean returned = new AtomicBoolean();
                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread sender = Thread.ofVirtual().start(() -> {
                    try {
                        adapter.sendAttachment(attachment(spoolFile));
                        returned.set(true);
                    } catch (Exception e) {
                        failure.set(e);
                    }
                });
                Thread.sleep(200);
                assertFalse(returned.get(),
                        "sendAttachment must keep blocking past the /_send ack,"
                                + " on the XFTP completion event");
                sender.join(WAIT.toMillis());
                assertTrue(returned.get(),
                        "the sndFileCompleteXFTP event releases sendAttachment: " + failure.get());
                acker.join(WAIT.toMillis());
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void fileTransferFailureEventFailsClassifiedPermanent() throws Exception {
        Path spoolFile = Files.writeString(spoolDir.resolve("img.png"), "payload-bytes");
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, dataDir);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                Thread acker = Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                        fake.sendFrame(SimpleXTestHarness.ackFrame(corrId, 1));
                        fake.sendFrame(fileFailureFrame("item-1"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                MessagingException ex = assertThrows(MessagingException.class,
                        () -> adapter.sendAttachment(attachment(spoolFile)));
                assertEquals(FailureCategory.PERMANENT, ex.category(),
                        "the transport reported the transfer failed; the event"
                                + " carries no machine-readable cause, so the"
                                + " spec's default-to-permanent rule applies");
                acker.join(WAIT.toMillis());
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void nonXftpFileCompletionFailsClassifiedPermanent() throws Exception {
        Path spoolFile = Files.writeString(spoolDir.resolve("img.png"), "payload-bytes");
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, dataDir);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                Thread acker = Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                        fake.sendFrame(SimpleXTestHarness.ackFrame(corrId, 1));
                        fake.sendFrame(nonXftpCompletionFrame("item-1"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                MessagingException ex = assertThrows(MessagingException.class,
                        () -> adapter.sendAttachment(attachment(spoolFile)));
                assertEquals(FailureCategory.PERMANENT, ex.category(),
                        "only sndFileCompleteXFTP is covered by the verified delivery"
                                + " record; any other completion on our chat item is a"
                                + " PERMANENT failure, never a release (design §6.2.4)");
                acker.join(WAIT.toMillis());
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void standaloneFileCompletionFailsClassifiedPermanent() throws Exception {
        Path spoolFile = Files.writeString(spoolDir.resolve("img.png"), "payload-bytes");
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, dataDir);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                Thread acker = Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                        fake.sendFrame(SimpleXTestHarness.ackFrame(corrId, 1));
                        fake.sendFrame(standaloneCompletionFrame("item-1"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                long startNanos = System.nanoTime();
                MessagingException ex = assertThrows(MessagingException.class,
                        () -> adapter.sendAttachment(attachment(spoolFile)));
                assertEquals(FailureCategory.PERMANENT, ex.category(),
                        "the contact-not-ready standalone degradation is PERMANENT,"
                                + " not a 5-minute timeout TRANSIENT (design §6.2.4)");
                assertTrue(Duration.ofNanos(System.nanoTime() - startNanos).compareTo(WAIT) < 0,
                        "the failure arrived well inside this test's WAIT — the ignored"
                                + " 5-minute completion-timeout path would have outlasted it");
                acker.join(WAIT.toMillis());
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void adapterRetainsNoCopyOfThePayload() throws Exception {
        // Acceptance item 7: the payload crosses as a PATH only — after a successful
        // sendAttachment the spool dir holds exactly the original file (no adapter-side
        // copy) and the adapter data-dir gained nothing.
        Path spoolFile = Files.writeString(spoolDir.resolve("img.png"), "payload-bytes");
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, dataDir);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                Thread acker = Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                        assertTrue(envelope.contains(spoolFile.toString()),
                                "the wire form carries the path, never the bytes");
                        fake.sendFrame(SimpleXTestHarness.ackFrame(corrId, 1));
                        fake.sendFrame(fileCompletionFrame("item-1"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                adapter.sendAttachment(attachment(spoolFile));
                acker.join(WAIT.toMillis());

                try (Stream<Path> spool = Files.list(spoolDir)) {
                    assertEquals(1, spool.count(),
                            "the adapter created no copy of the payload");
                }
                assertTrue(Files.exists(spoolFile),
                        "the spool file itself is Provider's to reclaim, not the adapter's");
                try (Stream<Path> data = Files.list(dataDir)) {
                    assertEquals(0, data.count(),
                            "the adapter data-dir gained no payload copy");
                }
            } finally {
                adapter.close();
            }
        }
    }

    private static OutboundAttachment attachment(Path path) {
        return new OutboundAttachment(new ScopeRef.Dm("contact-abc"),
                path.toString(), "image/png", path.getFileName().toString(), "corr-att");
    }

    private static String fileCompletionFrame(String chatItemId) {
        return """
                {
                  "resp": {
                    "type": "sndFileCompleteXFTP",
                    "chatItem": {
                      "chatInfo": {"type": "direct"},
                      "chatItem": {"meta": {"itemId": "%s"}}
                    },
                    "fileTransferMeta": {"fileId": 1}
                  }
                }
                """.formatted(chatItemId);
    }

    private static String nonXftpCompletionFrame(String chatItemId) {
        return """
                {
                  "resp": {
                    "type": "sndFileComplete",
                    "chatItem": {
                      "chatInfo": {"type": "direct"},
                      "chatItem": {"meta": {"itemId": "%s"}}
                    },
                    "fileTransferMeta": {"fileId": 1}
                  }
                }
                """.formatted(chatItemId);
    }

    private static String standaloneCompletionFrame(String chatItemId) {
        return """
                {
                  "resp": {
                    "type": "sndStandaloneFileComplete",
                    "chatItem": {
                      "chatInfo": {"type": "direct"},
                      "chatItem": {"meta": {"itemId": "%s"}}
                    },
                    "fileTransferMeta": {"fileId": 1}
                  }
                }
                """.formatted(chatItemId);
    }

    private static String fileFailureFrame(String chatItemId) {
        return """
                {
                  "resp": {
                    "type": "sndFileError",
                    "chatItem": {
                      "chatInfo": {"type": "direct"},
                      "chatItem": {"meta": {"itemId": "%s"}}
                    },
                    "fileTransferMeta": {"fileId": 1},
                    "errorMessage": "transfer failed"
                  }
                }
                """.formatted(chatItemId);
    }
}
