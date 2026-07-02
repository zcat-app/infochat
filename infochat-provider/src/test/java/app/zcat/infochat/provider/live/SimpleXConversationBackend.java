package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.impl.simplex.LiveSimpleXClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Live-e2e Phase 4b (HANDOFF §4b-2): the real-SimpleX {@link ConversationBackend}.
 * Each scenario contact token (the {@code send dm <contactId> ...} address) is
 * bound to a live host-side client identity ({@link LiveSimpleXClient}) plus the
 * bot's contact id as recorded in THAT client's DB — SimpleX contact ids are
 * per-connection, so "the bot" has a different id in every client (D10). The
 * binding interprets the transport-agnostic address tokens; {@link Scenario} and
 * {@link ScenarioRunner} stay adapter-free (M1-539 acceptance item 4).
 *
 * <p>Reply observation: the bot's replies arrive as asynchronous inbound events
 * on the sending client's own WS connection, decoded by the production codec.
 * {@link #awaitReply} genuinely polls (unlike the InMemory backend where the
 * reply is already present) — this is the async wait the poll-until-timeout SPI
 * shape exists for. Watermarks bound the wait to replies produced after this
 * step's send, matching the InMemory backend's per-step contract.</p>
 *
 * <p>GROUP scope is not bound yet: group scenarios are Phase 4b-3, which needs
 * bot+client group membership fixtures this backend does not manage. A group
 * step fails loudly rather than pretending.</p>
 *
 * <p>Known limit (Phase 4b-4 concern, not 4b-2): progress-notified commands
 * deliver their final body via an item EDIT ({@code live=off} finalize), which
 * the codec surfaces to the bot as an ack, not as a new inbound item — so this
 * backend currently observes plain replies only. The 4b-2 validation scenario
 * uses short-command replies, which are plain sends.</p>
 */
public final class SimpleXConversationBackend implements ConversationBackend {

    /** One scenario contact token's live binding. */
    public record ClientBinding(LiveSimpleXClient client, String botContactId) {}

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final Map<String, ClientBinding> bindingsByToken;

    // Per-step state (single-threaded runner contract, same as InMemory backend):
    // which client the last send used and its reply watermark at send time.
    private ClientBinding currentBinding;
    private int receivedWatermark;

    public SimpleXConversationBackend(Map<String, ClientBinding> bindingsByToken) {
        this.bindingsByToken = Map.copyOf(bindingsByToken);
    }

    @Override
    public void send(Scenario.Send send) {
        switch (send.scope()) {
            case DM -> {
                String token = send.addresses().get(0);
                ClientBinding binding = bindingsByToken.get(token);
                if (binding == null) {
                    throw new IllegalArgumentException("no live client bound for scenario contact '"
                            + token + "' (bound: " + bindingsByToken.keySet() + ")");
                }
                this.currentBinding = binding;
                this.receivedWatermark = binding.client().receivedCount();
                try {
                    binding.client().sendDm(binding.botContactId(), send.text());
                } catch (MessagingException e) {
                    throw new IllegalStateException(
                            "live SimpleX send failed for scenario contact '" + token + "'", e);
                }
            }
            case GROUP -> throw new UnsupportedOperationException(
                    "group scenarios are Phase 4b-3; the live SimpleX backend binds DM only so far");
        }
    }

    @Override
    public Optional<String> awaitReply(Scenario.Expect expect) {
        long deadlineNanos = System.nanoTime() + expect.timeout().toNanos();
        while (true) {
            for (String reply : currentBinding.client().receivedSince(receivedWatermark)) {
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

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting a live scenario reply", e);
        }
    }
}
