package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;


import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;

/**
 * The SPI commits to exactly one membership dispatch shape: an adapter
 * delivers events by invoking the {@code MembershipHandler} registered
 * via {@code setMembershipEventHandler} directly — there is no
 * interface-level dispatch method (the {@code onMembershipEvent}
 * default was removed when the two shapes were unified). This test
 * pins the surviving shape for both membership-capable adapters.
 *
 * <p>Lives in the signal impl package because the Signal half drives
 * the package-private {@code SignalAdapter.groupHandler()} seam.</p>
 */
class MembershipDispatchShapeTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    @Test
    void inMemoryAdapterDeliversMembershipThroughRegisteredHandler() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        List<MembershipEvent> delivered = new ArrayList<>();
        adapter.setMembershipEventHandler(delivered::add);
        adapter.createGroup("grp-1");
        adapter.addMember("grp-1", "contact-alice");

        adapter.removeMember("grp-1", "contact-alice");

        assertEquals(1, delivered.size(),
                "InMemoryAdapter must deliver the event to the registered MembershipHandler");
        MembershipEvent.UserLeft left = assertInstanceOf(
                MembershipEvent.UserLeft.class, delivered.get(0));
        assertEquals("grp-1", left.adapterGroupId());
        assertEquals("contact-alice", left.contactId());
    }

    @Test
    void signalAdapterDeliversMembershipThroughRegisteredHandler() {
        SignalAdapter adapter = new SignalAdapter(
                "/usr/bin/signal-cli",
                "/tmp/signal-data",
                "+15551234567",
                new InetSocketAddress("127.0.0.1", 0));
        adapter.adoptBotAci(BOT_ACI);
        List<MembershipEvent> delivered = new ArrayList<>();
        adapter.setMembershipEventHandler(delivered::add);

        adapter.groupHandler().handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000006000,
                    "dataMessage": {
                      "timestamp": 1700000006000,
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberLeft": ["CCDDEEFF-3333-4444-5555-666677778888"]
                      }
                    }
                  }
                }
                """));

        assertEquals(1, delivered.size(),
                "SignalAdapter's group handler must deliver the event to the registered MembershipHandler");
        MembershipEvent.UserLeft left = assertInstanceOf(
                MembershipEvent.UserLeft.class, delivered.get(0));
        assertEquals(GROUP_V2_ID, left.adapterGroupId());
        assertEquals("ccddeeff-3333-4444-5555-666677778888", left.contactId(),
                "left ACI must be canonicalized to lowercase");
    }

}
