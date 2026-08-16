package app.zcat.infochat.messaging.impl.simplex;

import app.zcat.infochat.messaging.ScopeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live production-dispatch re-verification (M1-855, design §6.2.4): a real file send AND the finalize edit through the production SimpleXWebSocketClient decode+dispatch against the bundled v7 binary. Layer 4, opt-in: -Dinfochat.live.simplex=true.
 */
@EnabledIfSystemProperty(named = "infochat.live.simplex", matches = "true",
        disabledReason = "live production-dispatch runs only on the host, against the bundled binary")
class LiveSimpleXProductionDispatchIT {

    /** Public-relay handshake budget (M1-840's harness completed well inside this). */
    private static final Duration HANDSHAKE = Duration.ofSeconds(180);
    /** Real-upload budget for a small file through the public XFTP servers. */
    private static final Duration TRANSFER = Duration.ofSeconds(120);
    /** The production ACK_TIMEOUT the live capture watched starve. */
    private static final Duration ACK = Duration.ofSeconds(30);

    private static final String SENDER_DISPLAY_NAME = "m1855-pd-sender";
    private static final String RECIPIENT_DISPLAY_NAME = "m1855-pd-recipient";
    private static final int SENDER_WS_PORT = Integer.getInteger("infochat.live.simplex.prod-dispatch.sender-port", 15242);
    private static final int RECIPIENT_WS_PORT = Integer.getInteger("infochat.live.simplex.prod-dispatch.recipient-port", 15243);

    @TempDir
    Path workDir;

    @Test
    void productionDispatchReleasesFileSendAndEditAck() throws Exception {
        Path clientsDir = clientsDir();
        String binary = clientsDir.resolve("bin/simplex-chat").toString();
        assertTrue(Files.isRegularFile(Path.of(binary)), "simplex-chat binary not found: " + binary);
        String version = readVersionBanner(binary);
        assertTrue(version.contains("v7.0.0"), "host binary is not the v7.0.0 build: " + version);

        Path senderDir = Files.createDirectories(workDir.resolve("sender"));
        Path recipientDir = Files.createDirectories(workDir.resolve("recipient"));
        provisionIdentity(binary, senderDir, SENDER_DISPLAY_NAME);
        provisionIdentity(binary, recipientDir, RECIPIENT_DISPLAY_NAME);

        try (LiveSimpleXClient recipient = new LiveSimpleXClient(
                binary, recipientDir.toString(), RECIPIENT_WS_PORT, "recipient")) {
            recipient.start();
            String contactId;
            try (LiveSimpleXClient sender = new LiveSimpleXClient(
                    binary, senderDir.toString(), SENDER_WS_PORT, "sender")) {
                sender.start();
                contactId = handshake(sender, recipient);
            }
            // The sender identity outlives its harness client: restart the
            // subprocess bare and connect the PRODUCTION client as its sole
            // controller — the position the live adapter sits in.
            Process senderProcess = bareSubprocess(binary, senderDir, SENDER_WS_PORT);
            try {
                awaitPort(SENDER_WS_PORT, Duration.ofSeconds(20));
                SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                        URI.create("ws://127.0.0.1:" + SENDER_WS_PORT),
                        java.net.http.HttpClient.newHttpClient(),
                        inbound -> { }, gc -> { });
                client.start();
                try {
                    ScopeRef scope = new ScopeRef.Dm(contactId);
                    Path payload = writeSmallPayload();

                    String sendCorr = "m1855-pd-send-1";
                    String sendEnvelope = SimpleXMessageCodec.encodeSendFileCommand(
                            sendCorr, scope, payload.toString(), "image/png",
                            payload.getFileName().toString(), null);
                    String chatItemId = client.sendCommand(sendCorr, sendEnvelope, ACK);
                    assertNotNull(chatItemId,
                            "the production dispatch must map the v7 newChatItems file ack");

                    client.awaitFileCompletion(chatItemId, TRANSFER);

                    String editCorr = "m1855-pd-edit-1";
                    String editEnvelope = SimpleXMessageCodec.encodeFinalizeCommand(
                            editCorr, chatItemId, scope, "final body");
                    assertNotNull(client.sendCommand(editCorr, editEnvelope, ACK),
                            "the production dispatch must map the v7 chatItemUpdated edit ack");

                    String retryCorr = "m1855-pd-edit-2";
                    String retryEnvelope = SimpleXMessageCodec.encodeFinalizeCommand(
                            retryCorr, chatItemId, scope, "final body");
                    assertNotNull(client.sendCommand(retryCorr, retryEnvelope, ACK),
                            "the identical-content re-edit (chatItemNotChanged) must ack too");
                } finally {
                    client.close();
                }
            } finally {
                senderProcess.destroy();
                if (!senderProcess.waitFor(5, TimeUnit.SECONDS)) {
                    senderProcess.destroyForcibly();
                }
            }
        }
    }

    /** Handshake via the recipient's address over the public relays; returns the recipient's contact id in the sender's DB. */
    private static String handshake(LiveSimpleXClient sender, LiveSimpleXClient recipient)
            throws Exception {
        recipient.query("/ad");
        recipient.query("/auto_accept on");
        com.fasterxml.jackson.databind.JsonNode address = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            address = recipient.query("/show_address");
            if (address.toString().contains("connShortLink")
                    || address.toString().contains("connFullLink")) {
                break;
            }
            Thread.sleep(1000);
        }
        sender.query("/connect " + LiveSimpleXClient.contactLink(address));
        sender.awaitFrameType("contactConnected", HANDSHAKE);
        recipient.awaitFrameType("contactConnected", HANDSHAKE);
        return sender.resolveContactId(RECIPIENT_DISPLAY_NAME);
    }

    /** The adapter's own launch shape (SimpleXSubprocess.commandFor): bare binary on the existing identity. */
    private static Process bareSubprocess(String binary, Path dataDir, int wsPort) throws IOException {
        Process process = new ProcessBuilder(
                binary, "-d", dataDir.resolve("simplex_v1").toString(),
                "-p", Integer.toString(wsPort))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return process;
    }

    private static void awaitPort(int port, Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException e) {
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("simplex-chat WS API on port " + port + " not reachable");
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
        assertTrue(process.waitFor() == 0, "identity provisioning failed for " + displayName);
    }

    private static String readVersionBanner(String binary) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(binary, "--version").start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor() == 0, "--version failed");
        System.out.println("[m1-855] host binary: " + output.strip());
        return output;
    }

    private Path writeSmallPayload() throws IOException {
        byte[] png = new byte[199];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        Path payload = workDir.resolve("prod-dispatch-probe.png");
        Files.write(payload, png);
        return payload;
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
