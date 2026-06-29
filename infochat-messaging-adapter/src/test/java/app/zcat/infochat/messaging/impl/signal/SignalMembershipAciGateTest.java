package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;


import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.MembershipEvent;

/**
 * Pins the canonical-UUID gate on the Signal membership member-delta
 * parser ({@code SignalGroupHandler.aciFromArrayEntry}). The daemon
 * stream is an adapter-inbound trust boundary; a member-delta entry that
 * is not a canonical UUID must be dropped at decode rather than becoming
 * a {@code MembershipEvent} contactId — identical to the gate the DM
 * ({@code extractDm}) and group-message ({@code handleReceive}) paths
 * already apply, so a non-canonical {@code memberLeft} entry cannot
 * mutate {@code group_membership} state Provider can never reconcile
 * against a real {@code users.contact_id}.
 */
class SignalMembershipAciGateTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    @Test
    void nonCanonicalStringMemberLeftDispatchesNoEvent() {
        List<MembershipEvent> delivered = dispatch("""
                {
                  "envelope": {
                    "dataMessage": {
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberLeft": ["not-a-canonical-uuid"]
                      }
                    }
                  }
                }
                """);

        assertTrue(delivered.isEmpty(),
                "a bare non-UUID memberLeft string must be dropped at decode, not dispatched");
    }

    @Test
    void nonCanonicalObjectMemberJoinedDispatchesNoEvent() {
        List<MembershipEvent> delivered = dispatch("""
                {
                  "envelope": {
                    "dataMessage": {
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberJoined": [{"uuid": "not-a-canonical-uuid"}]
                      }
                    }
                  }
                }
                """);

        assertTrue(delivered.isEmpty(),
                "a memberJoined object whose uuid is non-canonical must be dropped at decode, not dispatched");
    }

    @Test
    void canonicalMemberJoinedDispatchesUserJoinedWithLowerCasedContactId() {
        // Uppercase canonical UUID proves the gate emits the lower-cased
        // canonical join-key form only after passing isAcceptableAci.
        List<MembershipEvent> delivered = dispatch("""
                {
                  "envelope": {
                    "dataMessage": {
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberJoined": ["AABBCCDD-1111-2222-3333-444455556666"]
                      }
                    }
                  }
                }
                """);

        assertEquals(1, delivered.size(),
                "a canonical-UUID memberJoined entry must dispatch exactly one event");
        MembershipEvent.UserJoined joined = assertInstanceOf(
                MembershipEvent.UserJoined.class, delivered.get(0));
        assertEquals(GROUP_V2_ID, joined.adapterGroupId());
        assertEquals("aabbccdd-1111-2222-3333-444455556666", joined.contactId(),
                "joined ACI must be canonicalized to lowercase");
    }

    private static List<MembershipEvent> dispatch(String envelopeJson) {
        SignalAdapter adapter = new SignalAdapter(
                "/usr/bin/signal-cli",
                "/tmp/signal-data",
                "+15551234567",
                new InetSocketAddress("127.0.0.1", 0));
        adapter.adoptBotAci(BOT_ACI);
        List<MembershipEvent> delivered = new ArrayList<>();
        adapter.setMembershipEventHandler(delivered::add);
        adapter.groupHandler().handleReceive(parse(envelopeJson));
        return delivered;
    }

}
