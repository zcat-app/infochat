package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The InMemory {@link ConversationBackend}: drives {@link InMemoryAdapter} in-JVM.
 * This is the ONLY class in the package that imports adapter/messaging types — the
 * seam that keeps {@link Scenario}, {@link ConversationBackend} and
 * {@link ScenarioRunner} transport-agnostic so a Phase-4b SimpleX binding is a
 * drop-in (M1-539 acceptance item 4).
 *
 * <p>Reply observation must union the adapter's TWO reply channels: short commands
 * land a single body on {@link InMemoryAdapter#sentMessages()}, but progress-notified
 * commands ({@code /summary}, chat, digest) deliver a placeholder send plus the real
 * body via {@code finalizeMessage()} on {@link InMemoryAdapter#finalizedBodies()}.
 * Matching only the sent channel would match the placeholder, not the summary — so
 * {@link #awaitReply} tests the predicate against both channels, restricted to
 * replies produced after the current step's {@link #send} via a per-step watermark.
 *
 * <p>{@code InMemoryAdapter} delivery is synchronous, so a reply is already present
 * when {@code awaitReply} runs and the first poll matches; the poll-until-timeout
 * shape is what a live async backend needs, and keeping it here means the same
 * {@link Scenario} runs unchanged on either.
 */
public final class InMemoryConversationBackend implements ConversationBackend {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final InMemoryAdapter adapter;

    // Per-step watermark: sizes of both reply channels captured just before delivery,
    // so awaitReply considers only replies this step produced (single-threaded runner).
    private int sentWatermark;
    private int finalizedWatermark;

    public InMemoryConversationBackend(InMemoryAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void send(Scenario.Send send) {
        this.sentWatermark = adapter.sentMessages().size();
        this.finalizedWatermark = adapter.finalizedBodies().size();
        switch (send.scope()) {
            case DM -> adapter.deliverDm(send.addresses().get(0), send.text());
            case GROUP -> {
                String groupId = send.addresses().get(0);
                String senderContactId = send.addresses().get(1);
                // Live transports have the group + membership before a mention arrives;
                // InMemory needs them registered first. Idempotent so repeated group
                // steps in one scenario are safe.
                if (!adapter.hasGroup(groupId)) {
                    adapter.createGroup(groupId);
                }
                if (!adapter.groupMembers(groupId).contains(senderContactId)) {
                    adapter.addMember(groupId, senderContactId);
                }
                adapter.deliverGroupMention(groupId, senderContactId, send.text());
            }
        }
    }

    @Override
    public Optional<String> awaitReply(Scenario.Expect expect) {
        long deadlineNanos = System.nanoTime() + expect.timeout().toNanos();
        while (true) {
            for (String reply : repliesSinceWatermark()) {
                if (expect.matches(reply)) {
                    return Optional.of(reply);
                }
            }
            if (System.nanoTime() >= deadlineNanos) {
                return Optional.empty();
            }
            sleep();
        }
    }

    /** Bodies from both reply channels produced after the current step's watermark, in order. */
    private List<String> repliesSinceWatermark() {
        List<String> replies = new ArrayList<>();
        List<OutboundMessage> sent = adapter.sentMessages();
        for (int i = sentWatermark; i < sent.size(); i++) {
            replies.add(sent.get(i).text());
        }
        List<String> finalized = adapter.finalizedBodies();
        for (int i = finalizedWatermark; i < finalized.size(); i++) {
            replies.add(finalized.get(i));
        }
        return replies;
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting a scenario reply", e);
        }
    }
}
