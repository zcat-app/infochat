package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.impl.simplex.LiveSimpleXClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live-e2e Phase 4b (HANDOFF §4b-3, M1-546): the real-SimpleX {@link ConversationBackend}.
 * Each scenario contact token (the {@code send dm <contactId> ...} address) is
 * bound to a live host-side client identity ({@link LiveSimpleXClient}) plus the
 * bot's contact id as recorded in THAT client's DB — SimpleX contact ids are
 * per-connection, so "the bot" has a different id in every client (D10). The
 * binding interprets the transport-agnostic address tokens; {@link Scenario} and
 * {@link ScenarioRunner} stay adapter-free (M1-539 acceptance item 4).
 *
 * <p>GROUP steps ({@code send group <groupId> <senderContactId> <text>}): the
 * sender token selects the client binding, and the group token is the group's
 * local display name, resolved to that client's own group id via a corrId
 * {@code /groups} query (per-client DBs — LiveAdmin and LiveUser hold different
 * ids for the same group; cached per (sender, group)). The bot only reacts to
 * D51 STRUCTURED mentions — plain-text "@Name" is silently dropped — so a group
 * text opening with the literal {@code @bot } token composes the harness-side
 * mention envelope targeting the bot's per-group member (resolved via
 * {@code /members} by the bot's contact id, cached) and substitutes the bot's
 * real display name; any other group text is a plain codec-encoded send. The
 * convention lives here so the grammar and runner stay unmodified.</p>
 *
 * <p>Reply observation: the bot's replies arrive as asynchronous inbound events
 * on the sending client's own WS connection, decoded by the production codec.
 * {@link #awaitReply} genuinely polls, and unions plain inbound (DM + group)
 * bodies with item-edit FINALIZED bodies ({@code chatItemUpdated}, harness-parsed)
 * — progress-notified replies (/summary, chat, digest) deliver their final body
 * via an edit, mirroring {@code InMemoryConversationBackend}'s two-channel union.
 * Watermarks bound the wait to replies produced after this step's send, matching
 * the InMemory backend's per-step contract.</p>
 */
public final class SimpleXConversationBackend implements ConversationBackend {

    /** One scenario contact token's live binding. */
    public record ClientBinding(LiveSimpleXClient client, String botContactId) {}

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    /** Scenario group texts opening with this token request a D51 structured mention of the bot. */
    private static final String MENTION_PREFIX = "@bot ";

    private final Map<String, ClientBinding> bindingsByToken;
    // Fixture-query results are stable for a run — cache per "<senderToken>:<groupToken>".
    private final Map<String, String> groupIdCache = new HashMap<>();
    private final Map<String, LiveSimpleXClient.GroupMember> botMemberCache = new HashMap<>();

    // Per-step state (single-threaded runner contract, same as InMemory backend):
    // which client the last send used and both reply-channel watermarks at send time.
    private ClientBinding currentBinding;
    private int receivedWatermark;
    private int finalizedWatermark;

    public SimpleXConversationBackend(Map<String, ClientBinding> bindingsByToken) {
        this.bindingsByToken = Map.copyOf(bindingsByToken);
    }

    @Override
    public void send(Scenario.Send send) {
        switch (send.scope()) {
            case DM -> {
                String token = send.addresses().get(0);
                ClientBinding binding = requireBinding(token);
                markStep(binding);
                try {
                    binding.client().sendDm(binding.botContactId(), send.text());
                } catch (MessagingException e) {
                    throw new IllegalStateException(
                            "live SimpleX send failed for scenario contact '" + token + "'", e);
                }
            }
            case GROUP -> {
                String groupToken = send.addresses().get(0);
                String senderToken = send.addresses().get(1);
                ClientBinding binding = requireBinding(senderToken);
                markStep(binding);
                try {
                    String groupId = groupId(binding, senderToken, groupToken);
                    if (send.text().startsWith(MENTION_PREFIX)) {
                        LiveSimpleXClient.GroupMember bot = botMember(binding, senderToken, groupToken);
                        String text = "@" + bot.displayName() + " "
                                + send.text().substring(MENTION_PREFIX.length());
                        binding.client().sendGroupMention(groupId, text, bot);
                    } else {
                        binding.client().sendGroup(groupId, send.text());
                    }
                } catch (MessagingException e) {
                    throw new IllegalStateException("live SimpleX group send failed for scenario group '"
                            + groupToken + "' from '" + senderToken + "'", e);
                } catch (Exception e) {
                    throw new IllegalStateException("live SimpleX group/member resolution failed for '"
                            + groupToken + "' via '" + senderToken + "'", e);
                }
            }
        }
    }

    @Override
    public Optional<String> awaitReply(Scenario.Expect expect) {
        long deadlineNanos = System.nanoTime() + expect.timeout().toNanos();
        while (true) {
            for (String reply : repliesSinceWatermarks()) {
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

    /** Bodies from both reply channels produced after the current step's watermarks, in order. */
    private List<String> repliesSinceWatermarks() {
        List<String> replies = new ArrayList<>(
                currentBinding.client().receivedSince(receivedWatermark));
        replies.addAll(currentBinding.client().finalizedSince(finalizedWatermark));
        return replies;
    }

    private ClientBinding requireBinding(String token) {
        ClientBinding binding = bindingsByToken.get(token);
        if (binding == null) {
            throw new IllegalArgumentException("no live client bound for scenario contact '"
                    + token + "' (bound: " + bindingsByToken.keySet() + ")");
        }
        return binding;
    }

    private void markStep(ClientBinding binding) {
        this.currentBinding = binding;
        this.receivedWatermark = binding.client().receivedCount();
        this.finalizedWatermark = binding.client().finalizedCount();
    }

    private String groupId(ClientBinding binding, String senderToken, String groupToken)
            throws Exception {
        String key = senderToken + ":" + groupToken;
        String cached = groupIdCache.get(key);
        if (cached != null) {
            return cached;
        }
        String resolved = binding.client().resolveGroupId(groupToken);
        groupIdCache.put(key, resolved);
        return resolved;
    }

    private LiveSimpleXClient.GroupMember botMember(ClientBinding binding, String senderToken,
                                                    String groupToken) throws Exception {
        String key = senderToken + ":" + groupToken;
        LiveSimpleXClient.GroupMember cached = botMemberCache.get(key);
        if (cached != null) {
            return cached;
        }
        LiveSimpleXClient.GroupMember resolved =
                binding.client().resolveGroupMember(groupToken, binding.botContactId());
        botMemberCache.put(key, resolved);
        return resolved;
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
