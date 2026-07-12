package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link SimpleXAdapter}'s end-to-end group-mention routing across
 * {@code start()} and a supervised restart. {@code start()} builds the
 * group-candidate handler via the direct {@link SimpleXAdapter#buildGroupHandler}
 * lifecycle hook — the former {@code /show_address} self-address derivation was
 * removed in M1-518, so {@code start()} issues no identity query. Mention
 * recognition (D51) is by the per-group {@code memberId}: a {@code mentions{}}
 * entry whose memberId equals the frame's {@code groupInfo.membership.memberId}
 * is delivered. {@code start()} runs for real against a {@link FakeSimpleXProcess}
 * with a stay-alive wrapper script standing in for the simplex-chat binary;
 * routing is asserted behaviorally by pushing group frames through the fake's
 * wire path, so no test-only accessor exists.
 */
@DisabledOnOs(OS.WINDOWS)
class SimpleXAdapterIdentityDerivationTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    // BOT_MEMBER_ID is the bot's own per-group memberId (the D51 mention-routing
    // anchor, carried in each frame's groupInfo.membership); OTHER_MEMBER_ID is a
    // non-bot mention that must drop.
    private static final String BOT_MEMBER_ID = "WE1sRTBSZlVvMS9WYXdFcQ==";
    private static final String OTHER_MEMBER_ID = "T3RoZXJNZW1iZXJGb3JUZXN0MTI=";

    @TempDir
    Path tempDir;

    @Test
    void routesGroupMentionByMemberIdAfterStart() throws Exception {
        // start() builds the group handler (no /show_address derivation, M1-518)
        // and group mention routing works end-to-end on the memberId model (D51):
        // a mention whose memberId equals the frame's groupInfo.membership.memberId
        // is delivered; a non-matching mention is dropped. FIFO dispatch order
        // (single inbound-dispatch thread) means a wrongly-delivered control would
        // arrive BEFORE the matching mention, so the first delivery being the match
        // proves the drop without a timing-fragile sleep.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(delivered::add);
            try {
                adapter.start();
                // start() connects the WS client asynchronously; await the
                // completed handshake before pushing frames, or sendFrame
                // races the connect and throws "client has not connected
                // yet" under full-suite load (M1-615). Same guard as the
                // restart test below.
                fake.awaitClient(WAIT);

                fake.sendFrame(groupMentionFrame(BOT_MEMBER_ID, OTHER_MEMBER_ID, "route-item-1"));
                fake.sendFrame(groupMentionFrame(BOT_MEMBER_ID, BOT_MEMBER_ID, "route-item-2"));

                InboundMessage first = delivered.poll(5_000, TimeUnit.MILLISECONDS);
                assertNotNull(first,
                        "a mention matching the bot's membership memberId must be delivered");
                assertEquals("route-item-2", first.adapterMessageId(),
                        "the FIRST delivery must be the matching mention — the control"
                                + " mention preceding it on the wire must have been dropped");
                assertInstanceOf(ScopeRef.Group.class, first.scope(),
                        "post-start() group routing delivers the matched mention as group scope");
                assertNull(delivered.poll(400, TimeUnit.MILLISECONDS),
                        "the non-matching control mention must never surface");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void groupMentionRoutingSurvivesRestart() throws Exception {
        // The mention-routing wire path (codec -> handler -> delivered) is
        // re-established through the production reconnect path after a supervised
        // restart. Routing is by memberId and the handler is rebuilt by
        // buildGroupHandler() (no re-derivation, M1-518), so this pins that group
        // mentions still route after the WS client and group handler are rebuilt.
        // Choreography mirrors SimpleXReconnectTest: a one-shot flag-file child
        // dies on the test's signal and the supervisor performs exactly one
        // production restart.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(delivered::add);
            SimpleXSubprocess sub = newOneShotSubprocess();
            sub.start();
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                adapter.buildGroupHandler();

                fake.sendFrame(groupMentionFrame(BOT_MEMBER_ID, BOT_MEMBER_ID, "pre-restart-item"));
                InboundMessage preRestart = delivered.poll(5_000, TimeUnit.MILLISECONDS);
                assertNotNull(preRestart, "a memberId mention routes before the restart");

                // Supervised restart through the production reconnect path.
                int generationBeforeKill = fake.clientGeneration();
                fake.killClientConnection();
                Files.createFile(tempDir.resolve("die-flag"));
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(10));

                // The reconnect thread rebuilds the group handler asynchronously
                // after the fresh handshake; frames sent before the rebuild are
                // dropped (no handler yet), so poll until one routes.
                InboundMessage postRestart = awaitMentionDelivery(
                        fake, delivered, "post-restart-item-", 10_000);
                assertNotNull(postRestart,
                        "a memberId mention routes again after the supervised restart");
            } finally {
                adapter.close();
            }
        }
    }

    // NOTE: the former derivedAnchorIndependentOfAdminConfig test was deleted in
    // M1-514. It asserted that admin-key rotation could not move the bot's
    // queue-address mention anchor — but with D51 memberId recognition there is
    // no queue-address mention anchor, so the property is now structural (the
    // adapter has no admin input AND no derived-address routing). Authorized in
    // the M1-514 ticket §Out-of-scope.

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

    /**
     * Poll for the asynchronous post-restart handler rebuild: push bot-memberId
     * mentions until one is delivered or the deadline passes. Mentions pushed
     * before the reconnect thread rebuilds the group handler are dropped, so each
     * iteration sends a fresh frame.
     */
    private static @org.jspecify.annotations.Nullable InboundMessage awaitMentionDelivery(
            FakeSimpleXProcess fake,
            LinkedBlockingQueue<InboundMessage> delivered,
            String itemIdPrefix,
            long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            fake.sendFrame(groupMentionFrame(
                    BOT_MEMBER_ID, BOT_MEMBER_ID, itemIdPrefix + sequence++));
            InboundMessage msg = delivered.poll(300, TimeUnit.MILLISECONDS);
            if (msg != null) {
                return msg;
            }
        }
        return null;
    }

    /**
     * A group {@code newChatItem} frame where the bot's own per-group memberId is
     * {@code botMemberId} (in {@code groupInfo.membership}) and the single
     * {@code @bot} mention resolves to {@code mentionMemberId} (in the top-level
     * {@code mentions{}}). Delivered iff the two are byte-equal (D51 recognition).
     */
    private static String groupMentionFrame(String botMemberId, String mentionMemberId,
                                            String itemId) {
        return """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "group",
                        "groupInfo": {
                          "groupId": "GroupQueueAddressForDerivationTest000000006",
                          "membership": {"memberId": "%s"}
                        }
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
                        "mentions": {"bot": {"memberId": "%s"}},
                        "formattedText": [
                          {"text": "@bot", "format": {"type": "mention", "memberName": "bot"}},
                          {"text": " ping"}
                        ]
                      }
                    }
                  }
                }
                """.formatted(botMemberId, itemId, mentionMemberId);
    }
}
