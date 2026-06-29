package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link SimpleXAdapter#start()}'s bot-queue-address derivation: the
 * D10 mention-recognition anchor originates from the running simplex-chat
 * (the {@code /show_address} self-address query over the adapter's own
 * WebSocket), never from operator config. {@code start()} runs for real
 * against a {@link FakeSimpleXProcess} answering the query, with a
 * stay-alive wrapper script standing in for the simplex-chat binary (it
 * ignores the adapter's argument list and sleeps, so the supervisor sees a
 * healthy child and the tests are free of restart churn); the anchor is
 * then asserted behaviorally by pushing group-mention frames through the
 * fake's wire path, so no test-only accessor exists — mirrors
 * {@code SignalAdapterIdentityDerivationTest}.
 *
 * <p>The codec round-trip cases for the new wire surface (the encoder
 * envelope and the {@code userContactLink} decode, including the
 * bare-queue-id extraction contract) live here too: the contact-link
 * fixtures pin that extraction yields the same identifier the operator
 * used to extract manually — the exact frame shape is modeled in the
 * fixtures like every other simplex-chat frame, and drift fails loudly at
 * {@code start()}.</p>
 */
@DisabledOnOs(OS.WINDOWS)
class SimpleXAdapterIdentityDerivationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(2);

    // Well-formed queue addresses (URL-safe base64 charset, >= 32 chars).
    // QUEUE_A/QUEUE_B are the bot's derived-and-adopted anchor, so they use
    // the real 32-char wire width (24-byte recipient queue id, M1-504) and
    // pass the isWellFormed length floor at adoption; CONTROL_ID is only a
    // non-matching control mention, never adopted, so its length is incidental.
    private static final String QUEUE_A = "BotQueueAddrShowAddrDerived0001A";
    private static final String QUEUE_B = "BotQueueAddrRederivedRestart002B";
    private static final String CONTROL_ID = "ControlMentionNotTheBotsQueueAddr0000000003C";

    @TempDir
    Path tempDir;

    @Test
    void startDerivesQueueAddressFromShowMyAddress() throws Exception {
        // Acceptance item 1: start() derives the anchor by querying the
        // running simplex-chat — and item 4: the derived value feeds the
        // D10 anchor. The committed delivered/dropped assertion pair pins
        // item 4: a mention whose memberRef equals the bare queue id
        // extracted from the served contact link is delivered; a control
        // id is dropped. FIFO dispatch order (single inbound-dispatch
        // thread) means a wrongly-delivered control would arrive BEFORE
        // the matching mention, so the first delivery being the matching
        // one proves the drop without a timing-fragile sleep.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            Thread responder = startShowAddressResponder(
                    fake, new AtomicReference<>(contactLink(QUEUE_A)));
            SimpleXAdapter adapter = newAdapter(fake);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(delivered::add);
            try {
                adapter.start();

                fake.sendFrame(groupMentionFrame(CONTROL_ID, "derive-item-1"));
                fake.sendFrame(groupMentionFrame(QUEUE_A, "derive-item-2"));

                InboundMessage first = delivered.poll(5_000, TimeUnit.MILLISECONDS);
                assertNotNull(first,
                        "a mention of the derived queue address must be delivered");
                assertEquals("derive-item-2", first.adapterMessageId(),
                        "the FIRST delivery must be the matching mention — the control"
                                + " mention preceding it on the wire must have been dropped");
                assertInstanceOf(ScopeRef.Group.class, first.scope(),
                        "post-start() group routing must compare against the derived value");
                assertNull(delivered.poll(400, TimeUnit.MILLISECONDS),
                        "the control mention must never surface");
            } finally {
                responder.interrupt();
                adapter.close();
            }
        }
    }

    @Test
    void restartRederivesAnchor() throws Exception {
        // Acceptance item 2: the anchor is re-established on subprocess
        // restart through the production reconnect path. The fake serves a
        // DIFFERENT contact link after the supervised restart; post-restart
        // group routing must match the newly derived id and no longer the
        // old one. Choreography mirrors SimpleXReconnectTest: a one-shot
        // flag-file child dies on the test's signal and the supervisor
        // performs exactly one production restart.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            AtomicReference<String> servedLink = new AtomicReference<>(contactLink(QUEUE_A));
            Thread responder = startShowAddressResponder(fake, servedLink);
            SimpleXAdapter adapter = newAdapter(fake);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(delivered::add);
            SimpleXSubprocess sub = newOneShotSubprocess();
            sub.start();
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                adapter.deriveAndAdoptIdentity();

                fake.sendFrame(groupMentionFrame(QUEUE_A, "pre-restart-item"));
                InboundMessage preRestart = delivered.poll(5_000, TimeUnit.MILLISECONDS);
                assertNotNull(preRestart, "the initially derived anchor must route mentions");

                // Supervised restart; the responder now serves link B.
                servedLink.set(contactLink(QUEUE_B));
                int generationBeforeKill = fake.clientGeneration();
                fake.killClientConnection();
                Files.createFile(tempDir.resolve("die-flag"));
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(10));

                // The reconnect thread re-derives asynchronously after the
                // fresh handshake; poll with B-mentions until the flipped
                // anchor routes one (frames sent before the flip are
                // dropped by the no-longer-matching/old anchor).
                InboundMessage postRestart = awaitMentionDelivery(
                        fake, delivered, QUEUE_B, 10_000);
                assertNotNull(postRestart,
                        "post-restart group routing must match the re-derived anchor");

                fake.sendFrame(groupMentionFrame(QUEUE_A, "stale-anchor-item"));
                assertNull(delivered.poll(400, TimeUnit.MILLISECONDS),
                        "the pre-restart anchor must no longer route mentions");
            } finally {
                responder.interrupt();
                adapter.close();
            }
        }
    }

    @Test
    void derivedAnchorIndependentOfAdminConfig() throws Exception {
        // Acceptance item 3 (decoupling invariant): admin-key rotation
        // cannot move the bot's D10 anchor. Post-derivation the decoupling
        // is structural — SimpleXAdapter has no admin-sourced input on its
        // construction or start() path at all
        // (infochat.adapters.simplex.admin is consumed by AdapterRegistry's
        // bootstrap gate and never reaches the adapter) — so this test pins
        // the regression direction: across two runs whose bootstrap-admin
        // stand-in differs, the anchor tracks the fake-served link both
        // times and an admin-valued mention never matches.
        List<String> rotatedAdminAddresses = List.of(
                "RotatedBootstrapAdminQueueAddrA0000000000004",
                "RotatedBootstrapAdminQueueAddrB0000000000005");
        for (String adminAddress : rotatedAdminAddresses) {
            try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
                fake.start();
                Thread responder = startShowAddressResponder(
                        fake, new AtomicReference<>(contactLink(QUEUE_A)));
                SimpleXAdapter adapter = newAdapter(fake);
                LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
                adapter.setInboundHandler(delivered::add);
                try {
                    adapter.start();

                    fake.sendFrame(groupMentionFrame(adminAddress, "admin-mention-item"));
                    fake.sendFrame(groupMentionFrame(QUEUE_A, "bot-mention-item"));

                    InboundMessage first = delivered.poll(5_000, TimeUnit.MILLISECONDS);
                    assertNotNull(first,
                            "the anchor must track the fake-served link regardless of"
                                    + " the admin value");
                    assertEquals("bot-mention-item", first.adapterMessageId(),
                            "a mention of the bootstrap admin's queue address must never"
                                    + " match the bot anchor");
                    assertNull(delivered.poll(400, TimeUnit.MILLISECONDS),
                            "no second delivery — the admin mention was dropped");
                } finally {
                    responder.interrupt();
                    adapter.close();
                }
            }
        }
    }

    // -- codec round-trips for the new wire surface ---------------------------

    @Test
    void encodeShowMyAddressCommandCarriesCorrIdAndCommand() throws Exception {
        JsonNode root = MAPPER.readTree(
                SimpleXMessageCodec.encodeShowMyAddressCommand("corr-42"));
        assertEquals("corr-42", root.get("corrId").asText());
        assertEquals("/show_address", root.get("cmd").asText());
    }

    @Test
    void decodeUserContactLinkExtractsBareQueueId() {
        // The extraction contract: the full contact link embeds the
        // percent-encoded SMP queue URI; the bare queue id — the same
        // identifier the operator used to extract manually — is the path
        // segment after the server authority.
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(
                userContactLinkFrame("corr-7", contactLink(QUEUE_A)));
        SimpleXMessageCodec.SelfAddress selfAddress =
                assertInstanceOf(SimpleXMessageCodec.SelfAddress.class, decoded);
        assertEquals("corr-7", selfAddress.corrId());
        assertEquals(QUEUE_A, selfAddress.queueAddressId(),
                "extraction must yield the bare queue id embedded in the smp param");
    }

    @Test
    void decodeUserContactLinkWithoutLinkFailsPromptly() {
        // A CommandError (not Ignored) so the pending future fails with the
        // named cause instead of stalling start() for the full ack timeout.
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(
                "{\"corrId\":\"corr-8\",\"resp\":{\"type\":\"userContactLink\"}}");
        SimpleXMessageCodec.CommandError error =
                assertInstanceOf(SimpleXMessageCodec.CommandError.class, decoded);
        assertEquals("corr-8", error.corrId());
        assertEquals(FailureCategory.PERMANENT, error.category());
        assertEquals("self-address-without-contact-link", error.detail(),
                "fixed sentinel only — no envelope bytes in the detail");
    }

    @Test
    void decodeUserContactLinkWithMalformedLinkFailsPromptly() {
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(
                userContactLinkFrame("corr-9", "https://example.org/not-a-simplex-link"));
        SimpleXMessageCodec.CommandError error =
                assertInstanceOf(SimpleXMessageCodec.CommandError.class, decoded);
        assertEquals(FailureCategory.PERMANENT, error.category());
        assertEquals("self-address-extraction-failed", error.detail(),
                "fixed sentinel only — the link bytes never reach the detail");
    }

    @Test
    void decodeUserContactLinkWithoutCorrIdIsIgnored() {
        // An async userContactLink event nobody requested cannot complete a
        // pending future; it is dropped like every other unrequested variant.
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(
                "{\"resp\":{\"type\":\"userContactLink\"}}");
        assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded);
    }

    // -- choreography helpers --------------------------------------------------

    /**
     * Adapter wired to the fake's WebSocket port via the production 3-arg
     * constructor. The binary is the stay-alive wrapper script, so
     * {@code cfg.validate()} passes and the supervised child outlives the
     * test without restart churn.
     */
    private SimpleXAdapter newAdapter(FakeSimpleXProcess fake) throws IOException {
        SimpleXConfig cfg = new SimpleXConfig(
                stayAliveBinary().toString(), tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused here */ });
    }

    /** Executable stand-in for the simplex-chat binary: ignores args, stays alive. */
    private Path stayAliveBinary() throws IOException {
        Path script = tempDir.resolve("fake-simplex-chat");
        if (!Files.exists(script)) {
            Files.writeString(script, "#!/bin/sh\nexec sleep 300\n");
            if (!script.toFile().setExecutable(true)) {
                throw new IllegalStateException(
                        "could not mark the stand-in binary executable: " + script);
            }
        }
        return script;
    }

    /**
     * Supervised child that dies exactly once, on the test's signal — the
     * SimpleXReconnectTest one-shot choreography: the first launch waits
     * for the DIE flag file and exits; the respawned child sees the
     * RESTARTED flag and sleeps (stable).
     */
    private SimpleXSubprocess newOneShotSubprocess() {
        Path die = tempDir.resolve("die-flag");
        Path restarted = tempDir.resolve("restarted-flag");
        String script = "if [ -f " + restarted + " ]; then exec sleep 30; fi; "
                + "while [ ! -f " + die + " ]; do sleep 0.05; done; "
                + "touch " + restarted + "; exit 0";
        return new SimpleXSubprocess(
                List.of("/bin/sh", "-c", script),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                /* crashCap */ 5,
                msg -> { /* unused */ },
                new Random(0L));
    }

    private static Thread startShowAddressResponder(FakeSimpleXProcess fake,
                                                    AtomicReference<String> servedLink) {
        return SimpleXSelfAddressFixture.startShowAddressResponder(fake, servedLink::get);
    }

    /**
     * Poll for the asynchronous post-restart anchor flip: push mentions of
     * {@code memberRef} until one is delivered or the deadline passes.
     * Mentions pushed before the reconnect thread adopts the new anchor are
     * dropped, so each iteration sends a fresh frame.
     */
    private static @org.jspecify.annotations.Nullable InboundMessage awaitMentionDelivery(
            FakeSimpleXProcess fake,
            LinkedBlockingQueue<InboundMessage> delivered,
            String memberRef,
            long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            fake.sendFrame(groupMentionFrame(memberRef, "post-restart-item-" + sequence++));
            InboundMessage msg = delivered.poll(300, TimeUnit.MILLISECONDS);
            if (msg != null) {
                return msg;
            }
        }
        return null;
    }

    private static String contactLink(String queueAddressId) {
        return SimpleXSelfAddressFixture.contactLink(queueAddressId);
    }

    private static String userContactLinkFrame(String corrId, String fullLink) {
        return SimpleXSelfAddressFixture.userContactLinkFrame(corrId, fullLink);
    }

    private static String groupMentionFrame(String memberRef, String itemId) {
        return """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "group",
                        "groupInfo": {"groupId": "GroupQueueAddressForDerivationTest000000006"}
                      },
                      "chatItem": {
                        "meta": {"itemId": "%s"},
                        "chatDir": {
                          "groupMember": {
                            "memberContactId": "SenderMemberContactQueueAddress000000000007",
                            "localDisplayName": "Alice"
                          }
                        },
                        "content": {"msgContent": {"type": "text", "text": "@bot ping"}},
                        "formattedText": [
                          {"text": "@bot", "format": {"type": "mention", "memberRef": "%s"}},
                          {"text": " ping"}
                        ]
                      }
                    }
                  }
                }
                """.formatted(itemId, memberRef);
    }
}
