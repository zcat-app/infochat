package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.impl.simplex.LiveSimpleXClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live XFTP file-send re-verification (M1-840, design §6.2.4): drives a real
 * file send through the bundled binary with the production encoder and
 * asserts the event {@code sendAttachment} blocks on is the event emitted.
 * Standalone two-identity harness — a second connection against prod would
 * steal the adapter's async frames (one shared output queue per chat server).
 * Layer 4, opt-in: {@code -Dit.test=LiveSimpleXFileSendIT -Dinfochat.live.simplex=true}.
 */
@EnabledIfSystemProperty(named = "infochat.live.simplex", matches = "true",
        disabledReason = "live SimpleX file-send runs only on the host, against the bundled binary")
class LiveSimpleXFileSendIT {

    /** Public-relay handshake budget (M1-841's two-identity probe completed well inside this). */
    private static final Duration HANDSHAKE = Duration.ofSeconds(180);
    /** Real-upload budget for a small file through the public XFTP servers. */
    private static final Duration TRANSFER = Duration.ofSeconds(120);

    private static final String SENDER_DISPLAY_NAME = "m1840-sender";
    private static final String RECIPIENT_DISPLAY_NAME = "m1840-recipient";
    /** Distinct from the bot's 5225, RoundTrip's 5226, and any concurrent capture probe. */
    private static final int SENDER_WS_PORT = Integer.getInteger("infochat.live.simplex.file-send.sender-port", 15240);
    private static final int RECIPIENT_WS_PORT = Integer.getInteger("infochat.live.simplex.file-send.recipient-port", 15241);

    /** 1 GiB (the verified SimpleX ceiling) — the refusal arm probes one byte above it. */
    private static final long ONE_GIB = 1_073_741_824L;

    @TempDir
    Path workDir;

    @Test
    void fileSendReleasesOnlyOnSndFileCompleteXFTP() throws Exception {
        Path clientsDir = clientsDir();
        String binary = clientsDir.resolve("bin/simplex-chat").toString();
        assertTrue(Files.isRegularFile(Path.of(binary)), "simplex-chat binary not found: " + binary);
        // P8: the evidence must name its binary — a stale extraction silently
        // re-verifies the wrong version.
        String version = readVersionBanner(binary);
        assertTrue(version.contains("v7.0.0"), "host binary is not the v7.0.0 build: " + version);

        Path senderDir = Files.createDirectories(workDir.resolve("sender"));
        Path recipientDir = Files.createDirectories(workDir.resolve("recipient"));
        provisionIdentity(binary, senderDir, SENDER_DISPLAY_NAME);
        provisionIdentity(binary, recipientDir, RECIPIENT_DISPLAY_NAME);

        try (LiveSimpleXClient sender = new LiveSimpleXClient(
                binary, senderDir.toString(), SENDER_WS_PORT, "sender");
             LiveSimpleXClient recipient = new LiveSimpleXClient(
                     binary, recipientDir.toString(), RECIPIENT_WS_PORT, "recipient")) {
            sender.start();
            recipient.start();

            String contactId = connectSenderToRecipient(sender, recipient);
            Path payload = writeSmallPayload();

            // THE contract: the production encoder's exact bytes, acked with meta.itemId.
            String chatItemId = sender.sendFile(contactId, payload.toString(),
                    "image/png", payload.getFileName().toString());
            assertNotNull(chatItemId, "the /_send ack carried no meta.itemId");

            // The release event: what actually releases must be the event the
            // adapter blocks on (sndFileCompleteXFTP carrying the acked item).
            String releaseType = sender.awaitFileCompletion(chatItemId, TRANSFER);
            assertEquals("sndFileCompleteXFTP", releaseType,
                    "the release event drifted from the adapter's blocking contract");

            List<LiveSimpleXClient.FileEvent> events = sender.fileEvents();
            System.out.println("[m1-840] file events: " + events);
            assertTrue(events.stream().anyMatch(e -> "sndFileProgressXFTP".equals(e.type())),
                    "no upload progress observed — the send never reached XFTP");
            assertEquals(1, events.stream()
                            .filter(e -> chatItemId.equals(e.chatItemId()))
                            .filter(e -> "sndFileCompleteXFTP".equals(e.type())).count(),
                    "exactly one completion event for the acked chat item");
            assertFalse(events.stream().anyMatch(e -> chatItemId.equals(e.chatItemId()) && List.of(
                            "sndStandaloneFileComplete", "sndFileComplete", "sndFileError", "sndFileCancelled")
                            .contains(e.type())),
                    "a degradation/failure completion fired on a ready contact — the"
                            + " not-ready or failure path shadowed the verified release");

            refusalArms(sender, contactId);
        }
    }

    /** The failed-send classifications on real v7 error frames; the ceiling arm doubles as the empirical re-measurement. */
    private void refusalArms(LiveSimpleXClient sender, String contactId) throws Exception {
        Path overCeiling = workDir.resolve("over-ceiling.bin");
        sparseFile(overCeiling, ONE_GIB + 1);
        MessagingException overSize = assertThrows(MessagingException.class, () ->
                sender.sendFile(contactId, overCeiling.toString(), "application/octet-stream", "over.bin"));
        assertEquals(FailureCategory.PERMANENT, overSize.category(),
                "the over-ceiling refusal must classify PERMANENT");
        assertTrue(overSize.getMessage().contains("fileSize"),
                "expected the fileSize refusal tag, got: " + overSize.getMessage());

        Path missing = workDir.resolve("does-not-exist.bin");
        MessagingException notFound = assertThrows(MessagingException.class, () ->
                sender.sendFile(contactId, missing.toString(), "application/octet-stream", "missing.bin"));
        assertEquals(FailureCategory.PERMANENT, notFound.category(),
                "the missing-file refusal must classify PERMANENT");
        assertTrue(notFound.getMessage().contains("fileNotFound"),
                "expected the fileNotFound refusal tag, got: " + notFound.getMessage());
    }

    /** Handshake via the recipient's address over the public relays; returns the sender's contact id for the recipient. */
    private static String connectSenderToRecipient(LiveSimpleXClient sender, LiveSimpleXClient recipient)
            throws Exception {
        recipient.query("/ad");
        // Deterministic acceptance: /ad's default address settings leave
        // auto-accept off, which stalls the handshake on a pending request.
        recipient.query("/auto_accept on");
        JsonNode address = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            address = recipient.query("/show_address");
            if (address.toString().contains("connShortLink")
                    || address.toString().contains("connFullLink")) {
                break;
            }
            Thread.sleep(1000);
        }
        String link = LiveSimpleXClient.contactLink(address);
        sender.query("/connect " + link);
        try {
            sender.awaitFrameType("contactConnected", HANDSHAKE);
            recipient.awaitFrameType("contactConnected", HANDSHAKE);
        } catch (IllegalStateException e) {
            // Both sides' type inventories: which stage stalled (invitation
            // sent vs. request received vs. accept missing).
            System.err.println("[m1-840] sender types: " + sender.observedRespTypes());
            System.err.println("[m1-840] recipient types: " + recipient.observedRespTypes());
            throw e;
        }
        return sender.resolveContactId(RECIPIENT_DISPLAY_NAME);
    }

    /** One-shot non-interactive profile creation (6b shape); output discarded (D37), non-zero exit fails the IT. */
    private static void provisionIdentity(String binary, Path dataDir, String displayName)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                binary, "-d", dataDir.resolve("simplex_v1").toString(),
                "-y", "--create-bot-display-name", displayName, "--create-bot-allow-files",
                "-t", "1", "-e", "/contacts")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertEquals(0, process.waitFor(), "identity provisioning failed for " + displayName);
    }

    private static String readVersionBanner(String binary) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(binary, "--version").start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "--version failed");
        System.out.println("[m1-840] host binary: " + output.strip());
        return output;
    }

    private Path writeSmallPayload() throws IOException {
        byte[] png = new byte[199];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        Path payload = workDir.resolve("file-send-probe.png");
        Files.write(payload, png);
        return payload;
    }

    /** Sparse over-ceiling file — instant on disk; the CLI refuses it before any upload starts. */
    private static void sparseFile(Path file, long size) throws IOException {
        try (var randomAccessFile = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            randomAccessFile.setLength(size);
        }
    }

    private static Path clientsDir() {
        String configured = System.getProperty(
                "infochat.live.simplex.clients-dir", "../prod/runtime/simplex-clients");
        Path dir = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(dir), "clients dir not found: " + dir
                + " (set -Dinfochat.live.simplex.clients-dir)");
        return dir;
    }
}
