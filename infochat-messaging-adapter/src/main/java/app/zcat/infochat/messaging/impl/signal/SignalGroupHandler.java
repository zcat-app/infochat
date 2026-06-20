package app.zcat.infochat.messaging.impl.signal;

import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates signal-cli group-scope notifications into the
 * {@link MessagingAdapter} SPI's inbound and membership-event surfaces.
 * Pure dispatch — no I/O, no threading; the {@link SignalJsonRpcClient}
 * reader is the upstream that drives this class through its
 * group-notification route ({@code SignalAdapter.attachClient} wires
 * it). The handler is testable standalone by feeding JSON envelopes
 * directly into {@link #handleReceive}.
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
    private final AdapterMetrics metrics;

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
     * @param metrics           the adapter metrics emission point for the
     *                          §6.3.10 oversize-drop counter; never null
     *                          (the adapter passes its bound instance, or
     *                          a noop for unit tests).
     */
    SignalGroupHandler(String botAci,
                       MessagingAdapter.@Nullable InboundHandler inboundHandler,
                       MessagingAdapter.@Nullable MembershipHandler membershipHandler,
                       AdapterMetrics metrics) {
        this.botAci = botAci.toLowerCase(Locale.ROOT);
        this.inboundHandler = inboundHandler;
        this.membershipHandler = membershipHandler;
        this.metrics = metrics;
    }

    /**
     * Translate one signal-cli {@code receive} notification carrying a
     * group v2 dataMessage. Branches on shape:
     * <ul>
     *   <li>{@code memberJoined} / {@code memberLeft} present →
     *       dispatched as {@link MembershipEvent.UserJoined} /
     *       {@link MembershipEvent.UserLeft}.</li>
     *   <li>independently, if dataMessage has a {@code message} body →
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
    void handleReceive(JsonObject receiveParams) {
        // instanceof doubles as the null-check and the type-check (the
        // codec's discipline, SignalMessageCodec.decode/extractDm): the
        // daemon stream is a trust boundary, so a present-but-wrong-typed
        // envelope/dataMessage/groupV2 (untrusted Signal-peer wire data)
        // collapses into the same drop branch as an absent field rather
        // than throwing CCE out of the typed getJsonObject accessor —
        // making the boundary guard intrinsic here, not resting on the
        // incidental catch(RuntimeException) in dispatchGroupNotification.
        if (!(receiveParams.get("envelope") instanceof JsonObject envelope)) {
            return;
        }
        if (!(envelope.get("dataMessage") instanceof JsonObject dataMessage)) {
            return;
        }
        if (!(dataMessage.get("groupV2") instanceof JsonObject groupV2)) {
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
        dispatchMembership(groupId, arrayOrNull(groupV2, "memberJoined"), true);
        dispatchMembership(groupId, arrayOrNull(groupV2, "memberLeft"), false);
        // Fall through to the message branch rather than returning after a
        // membership delta. memberJoined/Left and the chat `message` body are
        // independent dataMessage fields, and nothing in this handler, the
        // codec, or the protocol forces them to be mutually exclusive — at an
        // untrusted-peer boundary, an early return would silently drop a
        // bot-mention co-delivered with a delta. The message branch is a no-op
        // for a delta-only notification (no `message` → early drop below), so
        // well-behaved traffic is unaffected. (M1-408)

        // Inbound group-message branch — requires sender ACI + body +
        // bot-mention. Anything missing → silent drop per spec.
        String sourceUuid = envelope.getString("sourceUuid", null);
        if (sourceUuid == null || !SignalMessageCodec.isAcceptableAci(sourceUuid)) {
            // v1 accepts only canonical-UUID ACIs as join keys; a group
            // sender whose identity cannot be asserted is dropped at
            // decode, mirroring the DM path (SignalMessageCodec.extractDm).
            return;
        }
        String body = dataMessage.getString("message", null);
        if (body == null || body.isEmpty()) {
            return;
        }
        // Computed once for the whole group inbound path: the daemon-supplied
        // timestamp feeds both the oversize-drop diagnostic below and the
        // accepted-frame id, and usableTimestamp is a pure function of the
        // unchanging (envelope, dataMessage) pair.
        Long timestamp = SignalMessageCodec.usableTimestamp(envelope, dataMessage);
        if (SignalMessageCodec.exceedsInboundByteCap(body)) {
            // §6.3.10 transport size-cap shed, mirroring the DM path and
            // SimpleX — the coarse envelope-line cap does not bound the
            // decoded body. Silent at the boundary (no reply) but observable:
            // count + WARN with the redacted sender (D37) and adapterMessageId.
            // The cap CHECK is unchanged; only the silent drop becomes visible.
            metrics.inboundDropped(SignalConfig.ADAPTER_NAME,
                    new ScopeRef.Group(groupId), AdapterMetrics.DropReason.OVERSIZE);
            LOG.warnf("inbound dropped — exceeds %d-byte size cap; from %s adapterMessageId %s",
                    SignalMessageCodec.MAX_INBOUND_TEXT_BYTES,
                    SignalMessageCodec.redactContactId(sourceUuid.toLowerCase(Locale.ROOT)),
                    timestamp == null ? "signal-unknown" : "signal-" + timestamp);
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
        if (timestamp == null) {
            // The daemon stream is a trust boundary: a present-but-null,
            // non-numeric, or fractional/out-of-range timestamp must drop
            // the frame, not throw out of handleReceive. The codec's DM
            // path guards the same untrusted field identically.
            return;
        }
        String senderAci = sourceUuid.toLowerCase(Locale.ROOT);
        // displayName is informational only (D10: never authoritative);
        // signal-cli surfaces the sender's profile name as the envelope's
        // sourceName, absent on profile-less senders → null displayName.
        String sourceName = envelope.getString("sourceName", null);
        Identity sender = new Identity(senderAci, sourceName, Instant.now());
        InboundMessage inbound = new InboundMessage(
                sender,
                new ScopeRef.Group(groupId),
                stripBotMentions(dataMessage, body),
                Instant.ofEpochMilli(timestamp),
                "signal-" + timestamp);
        handler.onMessage(inbound);
    }

    /**
     * Remove the bot's own mention span(s) from the body before delivery,
     * per {@code docs/spec/messaging.md} §Required SPI surface ("the
     * mention is stripped before delivery"). Anchored to the same
     * protocol mention entries the D10 gate reads — never display-name
     * text search, so a body that merely contains the bot's name as
     * plain text is left intact. Span {@code start}/{@code length} are
     * UTF-16 code-unit offsets into the body (Signal protocol
     * convention, matching Java string indexing). The entries are
     * untrusted wire data: a non-bot, malformed, or out-of-range span
     * is skipped rather than risking a corrupted delivery.
     */
    private String stripBotMentions(JsonObject dataMessage, String body) {
        JsonArray mentions = dataMessage.getJsonArray("mentions");
        // botMentioned gated this path, so mentions is present; each
        // entry's span fields are still validated individually.
        List<int[]> spans = new ArrayList<>();
        for (JsonValue entry : mentions) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject mention = (JsonObject) entry;
            String uuid = mention.getString("uuid", null);
            if (uuid == null || !botAci.equals(uuid.toLowerCase(Locale.ROOT))) {
                continue;
            }
            int start = mention.getInt("start", -1);
            int length = mention.getInt("length", -1);
            // (long) widening before the sum: start and length are
            // attacker-controlled wire ints, so a 32-bit start + length
            // can wrap negative (e.g. start=Integer.MAX_VALUE, length=1)
            // and slip past a ">" guard, then throw out of
            // StringBuilder.delete and silently drop the whole message.
            if (start < 0 || length <= 0 || (long) start + (long) length > body.length()) {
                continue;
            }
            spans.add(new int[] {start, start + length});
        }
        if (spans.isEmpty()) {
            return body;
        }
        // Coalesce overlapping/adjacent spans BEFORE stripping. Each
        // (start, length) pair is untrusted Signal-peer wire data: two
        // overlapping bot-uuid spans (e.g. {start=5,length=10} and
        // {start=8,length=10}) deleted sequentially would let the second
        // StringBuilder.delete clamp against the already-shortened buffer
        // and silently mutilate the body. Sorting ascending and merging
        // into disjoint intervals (span start <= running end extends the
        // interval) makes the strip a single contiguous, well-defined,
        // idempotent operation for any overlap shape a hostile peer can
        // author. Non-overlapping spans keep a gap, so they stay separate
        // intervals and the prior single-mention behavior is unchanged.
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (!merged.isEmpty() && span[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(new int[] {span[0], span[1]});
            }
        }
        // Delete right-to-left so earlier intervals' offsets stay valid.
        StringBuilder stripped = new StringBuilder(body);
        for (int i = merged.size() - 1; i >= 0; i--) {
            int[] span = merged.get(i);
            stripped.delete(span[0], span[1]);
            // Whitespace normalization at the removal junction: a
            // mid-text mention leaves the spaces on both of its sides
            // adjacent — collapse them to one so "hey @bot do x"
            // becomes "hey do x", not "hey  do x".
            int junction = span[0];
            if (junction > 0 && junction < stripped.length()
                    && Character.isWhitespace(stripped.charAt(junction - 1))
                    && Character.isWhitespace(stripped.charAt(junction))) {
                stripped.deleteCharAt(junction);
            }
        }
        return stripped.toString().strip();
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

    private static @Nullable JsonArray arrayOrNull(JsonObject object, String name) {
        // instanceof doubles as the null-check and the type-check (the
        // codec's discipline): a present-but-wrong-typed memberJoined /
        // memberLeft field (untrusted wire data) collapses into the same
        // 'absent -> not usable' branch as a missing one rather than
        // throwing CCE out of the typed getJsonArray accessor.
        return object.get(name) instanceof JsonArray array ? array : null;
    }

    private static @Nullable String aciFromArrayEntry(JsonValue entry) {
        // signal-cli's member-delta arrays surface either bare UUID
        // strings or full {uuid, ...} objects depending on version;
        // accept both shapes so a future signal-cli upgrade does not
        // silently drop the event.
        String raw = switch (entry.getValueType()) {
            case STRING -> ((JsonString) entry).getString();
            case OBJECT -> ((JsonObject) entry).getString("uuid", null);
            default -> null;
        };
        // Gate the member-delta ACI exactly as the DM (extractDm) and
        // group-message (handleReceive) paths do: an entry that is not a
        // canonical UUID is dropped at decode rather than becoming a
        // (adapter, contact_id) join key. Provider keys group_membership
        // row operations on that tuple, so a non-canonical memberLeft
        // entry could otherwise mutate authorization-adjacent state it
        // can never reconcile against a real users.contact_id.
        if (raw == null || !SignalMessageCodec.isAcceptableAci(raw)) {
            return null;
        }
        // isAcceptableAci lower-cases internally for the match; emit the
        // lower-cased canonical form only after the gate passes, matching
        // the other inbound paths' join-key shape.
        return raw.toLowerCase(Locale.ROOT);
    }
}
