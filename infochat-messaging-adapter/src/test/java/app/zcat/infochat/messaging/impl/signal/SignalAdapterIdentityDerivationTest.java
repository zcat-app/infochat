package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

/**
 * Pins {@link SignalAdapter#start()}'s bot-ACI derivation: the D10
 * mention-recognition anchor originates from the signal-cli account
 * store under the configured data dir ({@code SignalAccountStore}),
 * never from operator config. {@code start()} runs for real against a
 * {@link FakeSignalCli} endpoint with {@code /bin/sleep} standing in
 * for the signal-cli binary (the spawn succeeds, the TCP probe hits
 * the fake — the pattern {@code MultiAdapterProductionIT} documents at
 * its adapter factory); the anchor is then asserted behaviorally by
 * driving group-mention envelopes through the post-start
 * {@code groupHandler()}, so no test-only accessor exists.
 */
class SignalAdapterIdentityDerivationTest {

    private static final String ACCOUNT = "+15551111111";
    private static final String STORE_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    @TempDir
    Path dataDir;

    @Test
    void startDerivesBotAciFromIdentityStore() throws Exception {
        // The store carries the ACI UPPERCASE. SignalIdentity.isWellFormed
        // rejects non-canonical (uppercase) UUIDs, so start() succeeding at
        // all pins that the derived value is canonicalized to lowercase
        // BEFORE validation — and the delivered/dropped pair below pins
        // that the post-start() group-handler anchor equals the
        // identity-store value (acceptance items 1 + 3).
        SignalAccountStoreFixture.writeStore(
                dataDir, ACCOUNT, STORE_ACI.toUpperCase(Locale.ROOT));
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/bin/sleep", dataDir.toString(), ACCOUNT, fake.endpoint());
            List<InboundMessage> delivered = new ArrayList<>();
            adapter.setInboundHandler(delivered::add);
            try {
                adapter.start();

                // Control: a mention of a non-store UUID must be dropped.
                adapter.groupHandler().handleReceive(
                        groupMention("99990000-aaaa-bbbb-cccc-ddddeeeeffff", 1700000001000L));
                assertEquals(0, delivered.size(),
                        "a mention of a UUID other than the store ACI must not match the anchor");

                // A mention of the store ACI must be delivered.
                adapter.groupHandler().handleReceive(
                        groupMention(STORE_ACI, 1700000002000L));
                assertEquals(1, delivered.size(),
                        "a mention of the identity-store ACI must match the derived anchor");
                assertInstanceOf(ScopeRef.Group.class, delivered.get(0).scope());
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void derivedAnchorIndependentOfAdminConfig() throws Exception {
        // Decoupling invariant (acceptance item 2): admin-key rotation
        // cannot move the bot's D10 anchor. Post-derivation the decoupling
        // is structural — SignalAdapter has no admin-sourced input on its
        // construction or start() path at all (the Provider producer reads
        // only .binary/.data-dir/.account/.endpoint;
        // infochat.adapters.signal.admin is consumed by AdapterRegistry's
        // bootstrap gate and never reaches the adapter) — so this test
        // pins the regression direction: across two runs whose
        // bootstrap-admin stand-in differs, the anchor tracks the store
        // value both times and the admin-valued mention never matches.
        SignalAccountStoreFixture.writeStore(dataDir, ACCOUNT, STORE_ACI);
        List<String> rotatedAdminAcis = List.of(
                "00000000-0000-0000-0000-00000000000a",
                "00000000-0000-0000-0000-00000000000b");
        for (String adminAci : rotatedAdminAcis) {
            try (FakeSignalCli fake = new FakeSignalCli()) {
                SignalAdapter adapter = new SignalAdapter(
                        "/bin/sleep", dataDir.toString(), ACCOUNT, fake.endpoint());
                List<InboundMessage> delivered = new ArrayList<>();
                adapter.setInboundHandler(delivered::add);
                try {
                    adapter.start();

                    adapter.groupHandler().handleReceive(
                            groupMention(adminAci, 1700000003000L));
                    assertEquals(0, delivered.size(),
                            "a mention of the bootstrap admin's ACI must never match the bot anchor");

                    adapter.groupHandler().handleReceive(
                            groupMention(STORE_ACI, 1700000004000L));
                    assertEquals(1, delivered.size(),
                            "the anchor must track the identity store regardless of the admin value");
                } finally {
                    adapter.close();
                }
            }
        }
    }

    private static JsonObject groupMention(String mentionUuid, long timestamp) {
        // Mention span [0,4) covers the "@bot" prefix, mirroring the
        // protocol-consistent fixture shape in SignalGroupEndToEndTest.
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", "@bot ping")
                                .add("groupV2", Json.createObjectBuilder()
                                        .add("id", GROUP_V2_ID))
                                .add("mentions", Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder()
                                                .add("uuid", mentionUuid)
                                                .add("start", 0)
                                                .add("length", 4)))))
                .build();
    }
}
