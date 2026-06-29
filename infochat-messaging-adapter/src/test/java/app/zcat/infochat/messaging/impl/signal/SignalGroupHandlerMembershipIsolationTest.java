package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * Per-event isolation in {@code SignalGroupHandler}'s membership
 * dispatch loop: when the handler callback throws for the first of two
 * {@code memberLeft} entries in the same group update, the second
 * entry must still be dispatched — the loop must not abort mid-array.
 */
class SignalGroupHandlerMembershipIsolationTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";
    private static final String FIRST_ACI = "aaaa1111-2222-3333-4444-555566667777";
    private static final String SECOND_ACI = "bbbb1111-2222-3333-4444-555566667777";

    @Test
    void firstEventFailureDoesNotDropSiblingMemberLeftEntries() {
        ThrowOnFirstEventMembership membership = new ThrowOnFirstEventMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, null, membership, AdapterMetrics.noop());

        JsonObject params = parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000004000,
                    "dataMessage": {
                      "timestamp": 1700000004000,
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberLeft": ["%s", "%s"]
                      }
                    }
                  }
                }
                """.formatted(FIRST_ACI.toUpperCase(java.util.Locale.ROOT), SECOND_ACI));

        handler.handleReceive(params);

        assertEquals(2, membership.events.size(),
                "both memberLeft entries must be dispatched despite the first one throwing");
        MembershipEvent.UserLeft first = assertInstanceOf(
                MembershipEvent.UserLeft.class, membership.events.get(0));
        assertEquals(FIRST_ACI, first.contactId(),
                "first event must carry the first (lowercased) ACI");
        MembershipEvent.UserLeft second = assertInstanceOf(
                MembershipEvent.UserLeft.class, membership.events.get(1));
        assertEquals(SECOND_ACI, second.contactId(),
                "second event must carry the second ACI");
        assertEquals(GROUP_V2_ID, second.adapterGroupId());
    }

    private static final class ThrowOnFirstEventMembership
            implements MessagingAdapter.MembershipHandler {
        final List<MembershipEvent> events = new ArrayList<>();

        @Override
        public void onEvent(MembershipEvent event) {
            events.add(event);
            if (events.size() == 1) {
                throw new IllegalStateException(
                        "simulated provider-side dispatch failure for the first event");
            }
        }
    }
}
