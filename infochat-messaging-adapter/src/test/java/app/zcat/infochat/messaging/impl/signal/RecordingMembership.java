package app.zcat.infochat.messaging.impl.signal;

import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.MessagingAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link MessagingAdapter.MembershipHandler} double shared across the
 * Signal group tests: captures every delivered {@link MembershipEvent} in
 * arrival order for assertion.
 */
final class RecordingMembership implements MessagingAdapter.MembershipHandler {
    final List<MembershipEvent> events = new ArrayList<>();

    @Override
    public void onEvent(MembershipEvent event) {
        events.add(event);
    }
}
