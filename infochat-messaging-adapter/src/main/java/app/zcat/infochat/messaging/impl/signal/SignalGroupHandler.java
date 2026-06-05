package app.zcat.infochat.messaging.impl.signal;

import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.time.Instant;
import java.util.Locale;

/**
 * Translates signal-cli group-scope notifications into the
 * {@link MessagingAdapter} SPI's inbound and membership-event surfaces.
 * Pure dispatch — no I/O, no threading; the {@link SignalJsonRpcClient}
 * reader (M1-107) is the upstream that drives this class once the
 * production wiring lands (M1-109 integration). The handler is testable
 * standalone by feeding JSON envelopes directly into
 * {@link #handleReceive}.
 *
 * <p>Bot mention is the D10 trust anchor for group mode per
 * {@code docs/spec/messaging.md} §Required SPI surface — Receive: a
 * group message reaches Provider only when the dataMessage's
 * {@code mentions} array carries an entry whose {@code uuid} ACI
 * byte-equals (under canonical lowercase) the bot's per-adapter ACI.
 * {@link SignalMentionParser#botMentioned} owns the comparison;
 * display-name matching is never used.</p>
 *
 * <p>The stable per-group id is Signal's group v2 base64 identifier
 * (surfaced by signal-cli as {@code envelope.dataMessage.groupV2.id})
 * — see {@code docs/spec/messaging.md} §Identity and groups, which
 * requires "a stable per-group id (cryptographic where possible)".
 * The base64 form is opaque to Provider and survives signal-cli
 * restarts.</p>
 *
 * <p>Membership-event surface: Signal exposes member-joined / member-left
 * natively in group update events, so {@code supportsMembershipEvents}
 * is true (declared on {@link SignalAdapter}). signal-cli surfaces the
 * delta as {@code memberJoined} / {@code memberLeft} ACI arrays inside
 * the dataMessage's {@code groupV2} object on update notifications; the
 * handler maps each ACI to one {@link MembershipEvent.UserJoined} /
 * {@link MembershipEvent.UserLeft} dispatched through the registered
 * {@link MessagingAdapter.MembershipHandler}.</p>
 */
final class SignalGroupHandler {

    private static final Logger LOG = Logger.getLogger(SignalGroupHandler.class);

    private final String botAci;
    private final MessagingAdapter.@Nullable InboundHandler inboundHandler;
    private final MessagingAdapter.@Nullable MembershipHandler membershipHandler;

    /**
     * @param botAci            the bot's per-adapter ACI (UUID string)
     *                          used as the D10 trust anchor for mention
     *                          recognition; never null.
     * @param inboundHandler    Provider's inbound-message callback;
     *                          null means group inbound messages are
     *                          dropped (early-boot wiring case — the
     *                          warning logs identify it).
     * @param membershipHandler Provider's membership-event callback;
     *                          null means membership events are dropped
     *                          (early-boot wiring case).
     */
    SignalGroupHandler(@NonNull String botAci,
                       MessagingAdapter.@Nullable InboundHandler inboundHandler,
                       MessagingAdapter.@Nullable MembershipHandler membershipHandler) {
        this.botAci = botAci.toLowerCase(Locale.ROOT);
        this.inboundHandler = inboundHandler;
        this.membershipHandler = membershipHandler;
    }

    /**
     * Translate one signal-cli {@code receive} notification carrying a
     * group v2 dataMessage. Branches on shape:
     * <ul>
     *   <li>{@code memberJoined} / {@code memberLeft} present →
     *       dispatched as {@link MembershipEvent.UserJoined} /
     *       {@link MembershipEvent.UserLeft}.</li>
     *   <li>otherwise, dataMessage has a {@code message} body →
     *       mention-checked; if the bot is ACI-mentioned, dispatched
     *       as an {@link InboundMessage} with {@link ScopeRef.Group}.
     *       Group messages without a bot mention are silently dropped
     *       (spec rule: "Group messages arrive only when the bot is
     *       @mentioned").</li>
     * </ul>
     * Envelopes with neither shape (typing-only, receipts, DMs, group
     * updates carrying neither member delta nor body) return without
     * dispatch.
     *
     * @param receiveParams the {@code params} object from a JSON-RPC
     *                      {@code receive} notification; never null.
     */
    void handleReceive(@NonNull JsonObject receiveParams) {
        JsonObject envelope = receiveParams.getJsonObject("envelope");
        if (envelope == null) {
            return;
        }
        JsonObject dataMessage = envelope.getJsonObject("dataMessage");
        if (dataMessage == null) {
            return;
        }
        JsonObject groupV2 = dataMessage.getJsonObject("groupV2");
        if (groupV2 == null) {
            // DM scope or non-group notification — owned by
            // SignalMessageCodec.extractDm via SignalJsonRpcClient.
            return;
        }
        String groupId = groupV2.getString("id", null);
        if (groupId == null) {
            // groupV2 stanza without its base64 id is malformed —
            // cannot anchor a stable per-group scope.
            return;
        }

        // Membership update branch — dispatch one event per ACI in the
        // memberJoined / memberLeft arrays. Both arrays may be present
        // on the same update (a mod swap, an admin reshuffle); the spec
        // models them as independent per-user events.
        boolean dispatchedMembership =
                dispatchMembership(groupId, groupV2.getJsonArray("memberJoined"), true)
                | dispatchMembership(groupId, groupV2.getJsonArray("memberLeft"), false);
        if (dispatchedMembership) {
            return;
        }

        // Inbound group-message branch — requires sender ACI + body +
        // bot-mention. Anything missing → silent drop per spec.
        String sourceUuid = envelope.getString("sourceUuid", null);
        if (sourceUuid == null) {
            return;
        }
        String body = dataMessage.getString("message", null);
        if (body == null || body.isEmpty()) {
            return;
        }
        if (!SignalMentionParser.botMentioned(dataMessage, botAci)) {
            // Spec-required silent drop — only @mentions of the bot
            // (anchored by ACI) cross the adapter boundary.
            return;
        }
        MessagingAdapter.InboundHandler handler = inboundHandler;
        if (handler == null) {
            LOG.debugf("inbound Signal group message dropped — no InboundHandler set");
            return;
        }
        long timestamp = envelope.containsKey("timestamp")
                ? envelope.getJsonNumber("timestamp").longValueExact()
                : dataMessage.getJsonNumber("timestamp").longValueExact();
        String senderAci = sourceUuid.toLowerCase(Locale.ROOT);
        Identity sender = new Identity(senderAci, null, Instant.now());
        InboundMessage inbound = new InboundMessage(
                sender,
                new ScopeRef.Group(groupId),
                body,
                Instant.ofEpochMilli(timestamp),
                "signal-" + timestamp);
        handler.onMessage(inbound);
    }

    private boolean dispatchMembership(String groupId, @Nullable JsonArray acis, boolean joined) {
        if (acis == null || acis.isEmpty()) {
            return false;
        }
        MessagingAdapter.MembershipHandler handler = membershipHandler;
        if (handler == null) {
            LOG.debugf("Signal membership event dropped — no MembershipHandler set");
            return true;
        }
        for (JsonValue entry : acis) {
            String aci = aciFromArrayEntry(entry);
            if (aci == null) {
                continue;
            }
            MembershipEvent event = joined
                    ? new MembershipEvent.UserJoined(groupId, aci)
                    : new MembershipEvent.UserLeft(groupId, aci);
            try {
                handler.onEvent(event);
            } catch (RuntimeException e) {
                // Per-event isolation: one failing event must not drop
                // the sibling entries in the same member-delta array.
                // Class name only (the SafeLog pattern — this module has
                // no infochat-core dependency): no ACI (it is a contact
                // id), no exception message, no throwable object may
                // reach the log.
                LOG.errorf("Signal membership event dispatch failed group=%s exception=%s",
                        groupId, e.getClass().getName());
            }
        }
        return true;
    }

    private static @Nullable String aciFromArrayEntry(JsonValue entry) {
        // signal-cli's member-delta arrays surface either bare UUID
        // strings or full {uuid, ...} objects depending on version;
        // accept both shapes so a future signal-cli upgrade does not
        // silently drop the event.
        return switch (entry.getValueType()) {
            case STRING -> ((JsonString) entry).getString().toLowerCase(Locale.ROOT);
            case OBJECT -> {
                String uuid = ((JsonObject) entry).getString("uuid", null);
                yield uuid == null ? null : uuid.toLowerCase(Locale.ROOT);
            }
            default -> null;
        };
    }
}
