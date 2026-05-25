package app.zcat.infochat.messaging.impl.inmemory;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAdapterGroupTest {

    private InMemoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new InMemoryAdapter();
    }

    @Test
    void createGroup_registersGroup() {
        adapter.createGroup("grp-1");

        assertTrue(adapter.hasGroup("grp-1"));
    }

    @Test
    void addMember_addsToGroup() {
        adapter.createGroup("grp-1");

        adapter.addMember("grp-1", "contact-alice");
        adapter.addMember("grp-1", "contact-bob");

        assertEquals(2, adapter.groupMembers("grp-1").size());
        assertTrue(adapter.groupMembers("grp-1").contains("contact-alice"));
        assertTrue(adapter.groupMembers("grp-1").contains("contact-bob"));
    }

    @Test
    void deliverGroupMention_deliversInboundMessageWithGroupScope() {
        adapter.createGroup("grp-1");
        adapter.addMember("grp-1", "contact-alice");

        List<InboundMessage> received = new ArrayList<>();
        adapter.setInboundHandler(received::add);

        adapter.deliverGroupMention("grp-1", "contact-alice", "hello group");

        assertEquals(1, received.size());
        InboundMessage msg = received.getFirst();
        assertInstanceOf(ScopeRef.Group.class, msg.scope());
        assertEquals("grp-1", ((ScopeRef.Group) msg.scope()).adapterGroupId());
        assertEquals("contact-alice", msg.sender().contactId());
        assertEquals("hello group", msg.text());
    }

    @Test
    void removeMember_firesUserLeftEvent() {
        adapter.createGroup("grp-1");
        adapter.addMember("grp-1", "contact-alice");

        adapter.removeMember("grp-1", "contact-alice");

        List<MembershipEvent> events = adapter.membershipEvents();
        assertEquals(1, events.size());
        assertInstanceOf(MembershipEvent.UserLeft.class, events.getFirst());
        MembershipEvent.UserLeft left = (MembershipEvent.UserLeft) events.getFirst();
        assertEquals("grp-1", left.adapterGroupId());
        assertEquals("contact-alice", left.contactId());
    }

    @Test
    void removeBot_firesBotRemovedEvent() {
        adapter.createGroup("grp-1");

        adapter.removeBot("grp-1");

        List<MembershipEvent> events = adapter.membershipEvents();
        assertEquals(1, events.size());
        assertInstanceOf(MembershipEvent.BotRemoved.class, events.getFirst());
        assertEquals("grp-1", events.getFirst().adapterGroupId());
    }
}
