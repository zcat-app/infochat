package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.Utf8;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * JSON codec for the simplex-chat WebSocket bot API. Pure functions; no I/O,
 * no state. The simplex-chat envelope shape (verbatim from
 * {@code docs/design/06-messaging.md} §6.4.5) is JSON wrapping a SimpleX
 * command string:
 *
 * <pre>{@code
 *   {"corrId":"<id>","cmd":"<simplex-command-string>"}    // outbound
 *   {"corrId":"<id>","resp":{"type":"<typeName>",...}}    // command response
 *   {"resp":{"type":"<typeName>",...}}                    // async event
 * }</pre>
 *
 * <p>The simplex-chat command string itself follows the {@code /_send @<id> ...},
 * {@code /_update item @<id> <chatItemId> live=<on|off> json ...},
 * {@code /_set_contact_typing @<id> on|off} forms from design §6.4.5. The
 * exact byte-level interaction with a live simplex-chat is verified in the
 * Provider-side integration ticket (M1-105 production-IT, M1-109); this
 * codec exercises the envelope shape via round-trip tests against
 * {@code FakeSimpleXProcess}.</p>
 *
 * <p><strong>Failure classification.</strong> Per
 * {@code docs/spec/messaging.md} §Failure handling: an adapter that cannot
 * tell transient from permanent MUST default to {@link
 * FailureCategory#PERMANENT}. {@link #classifyError(String)} maps the
 * SimpleX error tags this codec recognises; everything unknown falls
 * through to PERMANENT (silently looping a permanent failure is worse than
 * aborting an occasionally-transient one).</p>
 */
final class SimpleXMessageCodec {

    /**
     * Adapter-local cap on the outbound text we paste into the command
     * envelope (4 KB per design §6.4.2). {@link SimpleXAdapter#send} splits
     * over-cap texts into chunks that each fit this ceiling (design §6.3.4
     * outbound chunking); the check here is a defensive second wall at the
     * codec — the narrowest internal boundary that still sees the raw text
     * — and is what keeps the never-chunked edit path capped.
     */
    static final int MAX_OUTBOUND_TEXT_BYTES = 4_000;

    /**
     * Inbound text cap (UTF-8 bytes) enforced at decode time on the
     * {@code text} field of every {@code newChatItem} frame, and the single
     * source of the {@code maxInboundMessageBytes} capability
     * {@link app.zcat.infochat.messaging.CapabilityFlags#maxInboundMessageBytes()}
     * that {@code SimpleXAdapter} advertises — the capability reads this
     * constant directly, so the decode-time enforcement and the advertised
     * SPI value cannot drift. v1 fixes the value at 16 KiB per
     * {@code docs/design/06-messaging.md} §6.2.2 / §6.4.4; it is the ceiling
     * the Provider's downstream budgets (LLM tokens, Stage 1 watchdog) plan
     * against, enforced here at the inbound trust boundary instead of relying
     * on the 1 MiB WebSocket frame ceiling.
     */
    static final int MAX_INBOUND_TEXT_BYTES = 16_384;

    /**
     * Queue-address character set per {@code docs/design/06-messaging.md}
     * §6.4.4. URL-safe base64 ∪ decimal: admits both SimpleX queue addresses
     * and simplex-chat DB row ids; rejects whitespace, newlines, and the
     * simplex-chat command terminators (' ', '@', '#') so an
     * attacker-controlled {@code contactId} / {@code adapterGroupId} /
     * {@code chatItemId} cannot piggyback a forged command verb into an
     * outbound command string.
     */
    private static final Pattern QUEUE_ADDRESS_CHARSET = Pattern.compile("^[A-Za-z0-9_=.-]+$");

    // FAIL_ON_UNKNOWN_PROPERTIES off so a future simplex-chat field
    // additions on the same envelope do not break the v1 decoder. The
    // failure mode we DO want is "frame is not JSON at all" or "frame
    // is missing a required field" — both still throw via the explicit
    // null-checks in decode() below.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SimpleXMessageCodec() {
        // Pure-static codec.
    }

    // --- Outbound encoding ---------------------------------------------------

    /**
     * Encode a fresh outbound message as a {@code /_send} command envelope.
     * The {@code corrId} is the adapter-chosen correlation id used to pair
     * the response carrying the new {@code chatItemId} back to the caller.
     *
     * @throws MessagingException ({@link FailureCategory#PERMANENT}) if
     *         {@code text} exceeds {@link #MAX_OUTBOUND_TEXT_BYTES} — the
     *         {@link SimpleXAdapter#send} chunking should have prevented
     *         this; the check is a system-boundary defense.
     */
    static String encodeSendCommand(String corrId,
                                             ScopeRef scope,
                                             String text) throws MessagingException {
        requireWithinCap(text);
        String target = targetSelector(scope);
        String cmd = "/_send " + target + " json " + textContent(text);
        return envelope(corrId, cmd);
    }

    /**
     * Encode an in-place edit of a previously-sent message. {@code live=on}
     * for the progress-notifier update sequence; {@code finalize} (the
     * terminal edit) uses {@code live=off} via
     * {@link #encodeFinalizeCommand}.
     */
    static String encodeUpdateCommand(String corrId,
                                               String chatItemId,
                                               ScopeRef scope,
                                               String text) throws MessagingException {
        return encodeEdit(corrId, chatItemId, scope, text, /* live */ true);
    }

    /** Encode the terminal edit ({@code live=off}). */
    static String encodeFinalizeCommand(String corrId,
                                                 String chatItemId,
                                                 ScopeRef scope,
                                                 String text) throws MessagingException {
        return encodeEdit(corrId, chatItemId, scope, text, /* live */ false);
    }

    private static String encodeEdit(String corrId, String chatItemId, ScopeRef scope,
                                     String text, boolean live) throws MessagingException {
        requireWithinCap(text);
        // chatItemId is round-tripped from simplex-chat in a SendAck and equally
        // untrusted; reject it before pasting it into a command verb. The decode
        // path already gates inbound contactId / adapterGroupId; the encode-time
        // assertion here is the defense-in-depth half of design §6.4.4.
        requireValidQueueAddressId(chatItemId, "chatItemId");
        String target = targetSelector(scope);
        String cmd = "/_update item " + target + " " + chatItemId
                + " live=" + (live ? "on" : "off")
                + " json " + textContent(text);
        return envelope(corrId, cmd);
    }

    /**
     * Encode the self-address query ({@code ShowMyAddress}): the source of
     * the bot's own queue address — the D10 trust anchor — derived at
     * {@link SimpleXAdapter#start()} instead of being operator-typed. The
     * user-level {@code /show_address} form targets the active user, so no
     * userId round-trip is needed (the API-level {@code /_show_address
     * <userId>} would require a prior {@code /user} query); both map to the
     * same {@code userContactLink} response.
     */
    static String encodeShowMyAddressCommand(String corrId) {
        return envelope(corrId, "/show_address");
    }

    private static String targetSelector(ScopeRef scope) throws MessagingException {
        return switch (scope) {
            case ScopeRef.Dm dm -> {
                requireValidQueueAddressId(dm.contactId(), "contactId");
                yield "@" + dm.contactId();
            }
            case ScopeRef.Group g -> {
                requireValidQueueAddressId(g.adapterGroupId(), "adapterGroupId");
                yield "#" + g.adapterGroupId();
            }
        };
    }

    /**
     * Reject any id that does not match the queue-address character set
     * (design §6.4.4). Encode-time use throws {@link MessagingException}
     * with {@link FailureCategory#PERMANENT} so the fault reaches the
     * send/update/finalize SPI contract through the two-category retry
     * model rather than escaping as an unchecked exception; decode-time
     * callers use the lower-level {@link #isValidQueueAddressId} predicate
     * and convert a failure into an {@link Ignored} frame.
     */
    private static void requireValidQueueAddressId(String id, String fieldName) throws MessagingException {
        if (!isValidQueueAddressId(id)) {
            // Do NOT log the raw id — it may contain injected newlines that
            // would split the log line and create a false-positive
            // command-injection footprint in stdout-scraping operators.
            throw new MessagingException(FailureCategory.PERMANENT,
                    fieldName + " fails queue-address validator (design §6.4.4); length="
                            + id.length());
        }
    }

    static boolean isValidQueueAddressId(String id) {
        return !id.isEmpty() && QUEUE_ADDRESS_CHARSET.matcher(id).matches();
    }

    private static String textContent(String text) {
        ObjectNode msgContent = MAPPER.createObjectNode();
        msgContent.put("type", "text");
        msgContent.put("text", text);
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("msgContent", msgContent);
        return wrapper.toString();
    }

    private static String envelope(String corrId, String cmd) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("corrId", corrId);
        root.put("cmd", cmd);
        return root.toString();
    }

    private static void requireWithinCap(String text) throws MessagingException {
        // Allocation-free early-exit boolean decision via the module's single
        // UTF-8 length source (Utf8); the exact byte count is computed only on
        // the rejection branch, which is off the success hot path.
        if (Utf8.exceedsByteLength(text, MAX_OUTBOUND_TEXT_BYTES)) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "outbound text " + Utf8.byteLength(text) + " bytes exceeds adapter cap "
                            + MAX_OUTBOUND_TEXT_BYTES);
        }
    }

    // --- Inbound decoding ---------------------------------------------------

    /**
     * Parse a single text frame received from simplex-chat. Returns one of
     * the {@link DecodedFrame} variants; never null. A frame that is not
     * valid JSON or is missing the {@code resp} envelope throws
     * {@link MalformedFrameException} so the WS-client layer can log + drop
     * without tearing the connection down (parallel to NostrMessage.parse's
     * MalformedFrameException discipline).
     *
     * <p>Group-scope inbound {@code newChatItem} frames decode as
     * {@link GroupCandidate}. The codec deliberately does not make the
     * mention-recognition decision (which depends on the bot's queue
     * address, runtime state not visible to a pure-static codec); it
     * exposes the raw mention list and lets {@link SimpleXGroupHandler}
     * compare against {@link SimpleXIdentity#queueAddress()}. Non-{@code
     * direct} and non-{@code group} chatTypes still surface as
     * {@link Ignored}.</p>
     */
    static DecodedFrame decode(String frame) {
        JsonNode root;
        try {
            root = MAPPER.readTree(frame);
        } catch (JsonProcessingException e) {
            // Fixed message only — Jackson's getOriginalMessage() embeds
            // byte fragments from the offending frame (security.md
            // §User content in exceptions: exception messages emitted
            // via the application logger MUST NOT contain user-authored
            // prose). The structural fix is to keep the bytes out of
            // the exception in the first place; the WS-client dispatch
            // site logs the fixed message and drops the frame.
            throw new MalformedFrameException("frame is not JSON");
        }
        JsonNode resp = root.get("resp");
        if (resp == null || !resp.isObject()) {
            throw new MalformedFrameException("frame missing 'resp' object");
        }
        JsonNode typeNode = resp.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw new MalformedFrameException("frame missing 'resp.type'");
        }
        String corrId = optText(root, "corrId");
        String type = typeNode.asText();
        return switch (type) {
            case "newChatItem" -> decodeNewChatItem(resp);
            case "newChatItems" -> decodeNewChatItems(corrId, resp);
            case "sentMessage", "apiSendMessageResponse" -> decodeSendAck(corrId, resp);
            case "userContactLink" -> decodeSelfAddress(corrId, resp);
            case "chatCmdError", "chatItemUpdateError" -> decodeError(corrId, resp);
            // Fixed sentinel — same rule as the chatType-non-direct branch
            // below. The top-level resp.type is attacker-influenceable
            // through the inbound frame and the Ignored.reason() value
            // flows into the WS-client's DEBUG log via dispatch()
            // (security.md §User content in exceptions / §User-content
            // logging: at any log level). The actual type value is
            // never needed downstream — the dispatch decision is the
            // same for every unrecognized variant.
            default -> new Ignored("unknown-resp-type");
        };
    }

    private static DecodedFrame decodeNewChatItem(JsonNode resp) {
        JsonNode chatItem = resp.get("chatItem");
        if (chatItem == null || !chatItem.isObject()) {
            return new Ignored("newChatItem-without-chatItem");
        }
        return decodeChatItemEntry(chatItem);
    }

    /**
     * Decode the batched plural {@code newChatItems} event. simplex-chat
     * v6.5.4 delivers BOTH the result of our own {@code /_send} AND a freshly
     * RECEIVED message through this one event type; they are told apart by
     * {@code corrId}. A send result carries the {@code corrId} of the command
     * it answers; a received-message async event has none. The {@code corrId}
     * is stamped by the local simplex-chat process on responses it pairs to
     * our commands — the remote peer never controls the bot-API envelope — so
     * its presence is a trusted local-process signal, not attacker-influenced
     * (D10 trust boundary). corrId present → {@link #decodeSendAck}; corrId
     * absent → decode the received item as inbound.
     *
     * <p>Before M1-508 the plural type was routed unconditionally to
     * {@link #decodeSendAck}, so on v6.5.4 — which delivers every received DM
     * as {@code newChatItems} — 100% of inbound was discarded as
     * {@code send-ack-without-chatItemId} and never reached the router.</p>
     *
     * <p><strong>First-only (v1).</strong> A received {@code newChatItems}
     * async event carries exactly one item on v6.5.4, and {@link #decode}
     * returns a single {@link DecodedFrame} whose consumer
     * ({@code SimpleXWebSocketClient.dispatch}) handles one frame per call.
     * If a future version batches more than one received item in a single
     * async event, only the first is decoded and delivered; the rest are not.
     * Delivering all would require widening the codec→client single-frame
     * contract, out of this ticket's scope.</p>
     */
    private static DecodedFrame decodeNewChatItems(@Nullable String corrId, JsonNode resp) {
        if (corrId != null) {
            return decodeSendAck(corrId, resp);
        }
        JsonNode chatItems = resp.get("chatItems");
        if (chatItems == null || !chatItems.isArray() || chatItems.isEmpty()) {
            return new Ignored("newChatItems-without-items");
        }
        return decodeChatItemEntry(chatItems.get(0));
    }

    /**
     * Decode one {@code AChatItem} entry — the {@code {chatInfo, chatItem}}
     * object that is {@code resp.chatItem} on a singular {@code newChatItem}
     * frame and each element of {@code resp.chatItems[]} on a plural
     * {@code newChatItems} frame. The identity rules (D10: contact_id is the
     * connection-based id, never the advertised contactLink) live here and are
     * shared by both frame shapes, so the singular and plural paths cannot
     * drift apart.
     */
    private static DecodedFrame decodeChatItemEntry(JsonNode chatItem) {
        JsonNode chatInfo = chatItem.get("chatInfo");
        if (chatInfo == null) {
            return new Ignored("newChatItem-without-chatInfo");
        }
        String chatType = optText(chatInfo, "chatType");
        if (chatType == null) {
            return new Ignored("newChatItem-without-chatType");
        }
        // chatType == "group" routes to the dedicated group-frame
        // decoder (M1-104). chatType == "direct" falls through to the
        // existing direct-message path. Any other chatType is dropped
        // with a fixed sentinel: the variant carries no bytes from
        // chatType because that field is attacker-influenceable through
        // the inbound frame and the Ignored.reason() value flows into
        // the WS-client's DEBUG log (security.md §User content in
        // exceptions). Discriminating on chatType beyond the
        // {group, direct, other} split is not needed in v1 — the
        // dropping decision is the same for every other value.
        if ("group".equals(chatType)) {
            return decodeGroupNewChatItem(chatInfo, chatItem);
        }
        if (!"direct".equals(chatType)) {
            return new Ignored("newChatItem-non-direct");
        }
        JsonNode contact = chatInfo.get("contact");
        if (contact == null) {
            return new Ignored("newChatItem-direct-without-contact");
        }
        String contactId = optText(contact, "contactId");
        if (contactId == null) {
            return new Ignored("newChatItem-without-contactId");
        }
        // Adapter-inbound trust boundary (docs/spec/security.md §Trust
        // boundaries): reject any contactId that doesn't match the
        // queue-address character set BEFORE constructing an InboundMessage,
        // so the value can never be echoed into an outbound command (design
        // §6.4.4).
        if (!isValidQueueAddressId(contactId)) {
            return new Ignored("newChatItem-invalid-contactId");
        }
        String displayName = optText(contact, "displayName");
        JsonNode itemBody = chatItem.get("chatItem");
        if (itemBody == null) {
            return new Ignored("newChatItem-without-inner-chatItem");
        }
        return decodeMsgContentText(itemBody, "newChatItem", text -> {
            String adapterMessageId = adapterMessageId(itemBody, contactId, text);
            // Enforce the SPI-declared inbound cap at the parse boundary so the
            // Provider's downstream budgets (LLM tokens, Stage 1 watchdog) plan
            // against a real ceiling rather than the 1 MiB WebSocket frame
            // ceiling. UTF-8 byte length, not Java char length — the cap is a
            // wire-level budget. Surfaced as OversizeDropped (not Ignored) so the
            // consumer raises adapter.inbound.dropped{reason=oversize} + the
            // §6.3.10 WARN with the sender and adapterMessageId in hand; the cap
            // CHECK here is unchanged, only the drop's observability.
            if (Utf8.exceedsByteLength(text, MAX_INBOUND_TEXT_BYTES)) {
                return new OversizeDropped(new ScopeRef.Dm(contactId), contactId, adapterMessageId);
            }
            Identity sender = new Identity(contactId, displayName, Instant.now());
            InboundMessage msg = new InboundMessage(
                    sender,
                    new ScopeRef.Dm(contactId),
                    text,
                    Instant.now(),
                    adapterMessageId);
            return new Inbound(msg);
        });
    }

    /**
     * Decode a {@code newChatItem} frame whose {@code chatInfo.chatType}
     * is {@code "group"}. Surfaces the raw mention list (queue addresses
     * only — display names are intentionally not extracted because
     * display-name matching is never sufficient for the D10 mention
     * rule) so {@link SimpleXGroupHandler} can apply byte-equality
     * against the bot's per-adapter queue address.
     *
     * <p>Same trust-boundary discipline as the direct-message branch:
     * adapterGroupId, sender contact id, and each mention's queue
     * address are validated by {@link #isValidQueueAddressId} before
     * landing in a {@link GroupCandidate}, so an attacker-controlled id
     * cannot piggyback a forged command verb downstream. An invalid
     * adapterGroupId or sender contact id drops the whole frame; an
     * invalid mention entry is skipped individually (other valid
     * mentions in the same frame remain).</p>
     *
     * <p>Frame-path conventions used here (simplex-chat WebSocket bot
     * API):</p>
     * <ul>
     *   <li>{@code chatInfo.groupInfo.groupId} → adapterGroupId</li>
     *   <li>{@code chatItem.chatDir.groupMember.memberContactId} →
     *       sender contact id. Frames lacking {@code memberContactId}
     *       are dropped as {@code Ignored("newChatItem-group-without-
     *       sender")} — the D10 trust anchor requires a stable,
     *       cryptographically-anchored account id, and the per-group
     *       {@code memberId} counter does not meet that bar (a
     *       globally-banned user surfacing only via {@code memberId}
     *       would evade the ban check; two pre-contact members in
     *       different groups can share the same {@code memberId} and
     *       collide on Provider's {@code (adapter, contact_id)} join
     *       key)</li>
     *   <li>{@code chatItem.chatDir.groupMember.localDisplayName} →
     *       optional displayName (informational only; never
     *       authoritative for mentions)</li>
     *   <li>{@code chatItem.formattedText[*]} where {@code format.type
     *       == "mention"} → mention queue addresses extracted from
     *       {@code format.memberRef}</li>
     * </ul>
     */
    private static DecodedFrame decodeGroupNewChatItem(JsonNode chatInfo, JsonNode outerChatItem) {
        JsonNode groupInfo = chatInfo.get("groupInfo");
        if (groupInfo == null) {
            return new Ignored("newChatItem-group-without-groupInfo");
        }
        String adapterGroupId = optText(groupInfo, "groupId");
        if (adapterGroupId == null) {
            return new Ignored("newChatItem-group-without-groupId");
        }
        if (!isValidQueueAddressId(adapterGroupId)) {
            return new Ignored("newChatItem-group-invalid-groupId");
        }
        JsonNode itemBody = outerChatItem.get("chatItem");
        if (itemBody == null) {
            return new Ignored("newChatItem-group-without-inner-chatItem");
        }
        JsonNode chatDir = itemBody.get("chatDir");
        if (chatDir == null) {
            return new Ignored("newChatItem-group-without-chatDir");
        }
        JsonNode groupMember = chatDir.get("groupMember");
        if (groupMember == null) {
            return new Ignored("newChatItem-group-without-groupMember");
        }
        // D10 trust anchor: Identity.contactId MUST be a stable,
        // cryptographically-anchored account id. The per-group
        // memberId counter (which simplex-chat surfaces for not-yet-
        // bidirectionally-contacted members) does NOT meet that bar
        // — using it as a fallback would let a globally-banned user
        // evade the ban check via a group message that surfaces only
        // memberId, and would cross-contaminate state when two
        // pre-contact members in different groups share the same
        // counter value. Drop the frame instead; the Provider falls
        // back to silent-ignore for unknown contacts in groups.
        String senderContactId = optText(groupMember, "memberContactId");
        if (senderContactId == null) {
            return new Ignored("newChatItem-group-without-sender");
        }
        if (!isValidQueueAddressId(senderContactId)) {
            return new Ignored("newChatItem-group-invalid-sender");
        }
        String senderDisplayName = optText(groupMember, "localDisplayName");
        return decodeMsgContentText(itemBody, "newChatItem-group", text -> {
            String adapterMessageId =
                    adapterMessageId(itemBody, adapterGroupId, senderContactId, text);
            // Same transport cap as the DM path; surfaced as OversizeDropped
            // (scope = group) so the consumer raises the §6.3.10 counter + WARN.
            // The cap CHECK is unchanged — only the silent drop becomes observable.
            if (Utf8.exceedsByteLength(text, MAX_INBOUND_TEXT_BYTES)) {
                return new OversizeDropped(
                        new ScopeRef.Group(adapterGroupId), senderContactId, adapterMessageId);
            }
            GroupMentions groupMentions = extractGroupMentions(itemBody.get("formattedText"), text);
            return new GroupCandidate(
                    adapterGroupId,
                    senderContactId,
                    senderDisplayName,
                    text,
                    groupMentions.addresses(),
                    groupMentions.spans(),
                    adapterMessageId);
        });
    }

    /**
     * Walk the untrusted {@code formattedText} array ONCE and produce both
     * mention products the group path needs: the mention queue addresses
     * (every entry whose {@code format.type} is {@code "mention"} and whose
     * {@code format.memberRef} passes the queue-address validator) and the
     * mention spans (each mention segment's [start, length) offset into
     * {@code text}, so {@link SimpleXGroupHandler} can strip the bot's own
     * mention before delivery). A null or non-array input yields both lists
     * empty — the spec rule "no mentions → cannot mention the bot" is honored
     * downstream. memberRef values failing the validator are skipped (a peer
     * cannot smuggle a forged command verb through the mention list).
     *
     * <p><strong>Addresses and spans have independent fates.</strong>
     * simplex-chat's {@code formattedText} decomposes {@code msgContent.text}:
     * on real frames the concatenation of the segments' {@code text} fields
     * equals the full text, which makes each segment's cumulative offset a
     * valid index into it. The spans are trusted only when that reconstruction
     * holds — every segment matches {@code text} at its computed position AND
     * the segments cover the text exactly. A frame that fails the guard
     * (degenerate or hostile — the wire is untrusted) yields an empty span
     * list: no protocol span can be located and the handler delivers the text
     * unstripped. Addresses are collected REGARDLESS of the span guard, so D10
     * recognition survives a failed span reconstruction (the
     * {@code SimpleXMessageCodec.java} address-survives-failed-span invariant,
     * design §6.4.4). {@code spansValid} latches false on the first divergence
     * and stops both span collection and the offset advance, while address
     * collection ignores it and continues to the end of the array.</p>
     */
    private static GroupMentions extractGroupMentions(@Nullable JsonNode formattedText,
                                                      String text) {
        if (formattedText == null || !formattedText.isArray()) {
            return new GroupMentions(List.of(), List.of());
        }
        List<String> mentions = new ArrayList<>();
        List<MentionSpan> spans = new ArrayList<>();
        boolean spansValid = true;
        int offset = 0;
        for (JsonNode element : formattedText) {
            String segmentText = optText(element, "text");
            // First divergence between the segment concatenation and text voids
            // every span (the guard latches), but NOT the addresses below.
            if (spansValid && (segmentText == null
                    || !text.regionMatches(offset, segmentText, 0, segmentText.length()))) {
                spansValid = false;
            }
            JsonNode format = element.get("format");
            if (format != null && format.isObject()
                    && "mention".equals(optText(format, "type"))) {
                String memberRef = optText(format, "memberRef");
                if (memberRef != null && isValidQueueAddressId(memberRef)) {
                    mentions.add(memberRef);
                    // segmentText is non-null whenever spansValid still holds
                    // (the guard above latches false on a null segment); the
                    // explicit re-check satisfies the nullness contract.
                    if (spansValid && segmentText != null) {
                        spans.add(new MentionSpan(memberRef, offset, segmentText.length()));
                    }
                }
            }
            if (spansValid && segmentText != null) {
                offset += segmentText.length();
            }
        }
        if (offset != text.length()) {
            spansValid = false;
        }
        return new GroupMentions(List.copyOf(mentions),
                spansValid ? List.copyOf(spans) : List.of());
    }

    /**
     * The two independent products of a single {@link #extractGroupMentions}
     * walk: the mention queue {@code addresses} (collected for every valid
     * mention) and the mention {@code spans} (populated only when the
     * reconstruction guard holds). Both lists are already immutable copies.
     */
    private record GroupMentions(List<String> addresses, List<MentionSpan> spans) {
    }

    /**
     * Shared {@code chatItem → content → msgContent → text} guard ladder for
     * both {@code newChatItem} decoders. Walks the three nested fields,
     * returning the matching {@code Ignored} ({@code reasonPrefix + "-without-
     * content" | "-without-msgContent" | "-without-text"}) for the first
     * absent one, or handing the validated text to {@code onText} for the
     * variant-specific tail (DM builds {@link Inbound}, group builds
     * {@link GroupCandidate}). One ladder so the DM and group paths cannot
     * drift independently; the {@code reasonPrefix} keeps each path's distinct
     * Ignored reasons ({@code "newChatItem-"} vs {@code "newChatItem-group-"}).
     */
    private static DecodedFrame decodeMsgContentText(JsonNode itemBody, String reasonPrefix,
            Function<String, DecodedFrame> onText) {
        JsonNode content = itemBody.get("content");
        if (content == null) {
            return new Ignored(reasonPrefix + "-without-content");
        }
        JsonNode msgContent = content.get("msgContent");
        if (msgContent == null) {
            return new Ignored(reasonPrefix + "-without-msgContent");
        }
        String text = optText(msgContent, "text");
        if (text == null) {
            return new Ignored(reasonPrefix + "-without-text");
        }
        return onText.apply(text);
    }

    /**
     * adapterMessageId for a {@code newChatItem} inner body: the wire
     * {@code itemId} when present, else a deterministic fallback derived from
     * the frame's stable identifying fields. Mirrors SignalMessageCodec
     * deriving its id from the message timestamp — adapterMessageId is the
     * stable correlation key ({@link InboundMessage} javadoc: retry
     * correlation, audit cross-reference), so two decodes of the same
     * itemId-less frame MUST yield the same id; {@code System.nanoTime()}
     * defeated that. The fallback path is rare (simplex-chat supplies
     * {@code itemId} on real frames) and the id is never persisted across
     * instances, so a content hash that two itemId-less frames with identical
     * stable fields could share is acceptable. The fields are joined with a
     * space — absent from the queue-address charset that the leading
     * contactId/groupId fields are validated against — so the boundary before
     * the trailing free-text field stays unambiguous.
     */
    private static String adapterMessageId(JsonNode itemBody, String... stableFields) {
        String itemId = optText(itemBody, "itemId");
        if (itemId != null) {
            return itemId;
        }
        return "simplex-" + Integer.toHexString(String.join(" ", stableFields).hashCode());
    }

    /**
     * Decode the {@code userContactLink} response to the self-address query
     * ({@link #encodeShowMyAddressCommand}): extract the bot's bare queue
     * address id from the returned contact link. The simplex-chat shape is
     * {@code resp.contactLink.connLinkContact.connFullLink} — the FULL link
     * is read (never {@code connShortLink}: the short form carries a server
     * link-id, not the queue id).
     *
     * <p>Extraction or validation failure maps to a {@link CommandError}
     * with a fixed sentinel detail rather than {@link Ignored}: the pending
     * future then fails promptly with the named cause instead of stalling
     * the caller for the full ack timeout toward a vague TRANSIENT. The
     * sentinel carries no link bytes (D37: the contact link embeds the
     * queue address, a sensitive identifier; security.md §User content in
     * exceptions). PERMANENT because a re-issued query returns the same
     * undecodable shape — wire-contract drift is fixed by code, not
     * retries.</p>
     *
     * <p>A {@code userContactLink} frame without a {@code corrId} is an
     * async event nobody requested — ignored like every other unrequested
     * variant.</p>
     */
    private static DecodedFrame decodeSelfAddress(@Nullable String corrId, JsonNode resp) {
        if (corrId == null) {
            return new Ignored("self-address-without-corrId");
        }
        String fullLink = optText(resp.path("contactLink").path("connLinkContact"),
                "connFullLink");
        if (fullLink == null) {
            return new CommandError(corrId, FailureCategory.PERMANENT,
                    "self-address-without-contact-link");
        }
        String queueAddressId = extractQueueAddressId(fullLink);
        if (queueAddressId == null) {
            return new CommandError(corrId, FailureCategory.PERMANENT,
                    "self-address-extraction-failed");
        }
        return new SelfAddress(corrId, queueAddressId);
    }

    /**
     * Extract the bare queue address id from a SimpleX contact link — the
     * same identifier an operator extracts manually from their address. A
     * contact link embeds the percent-encoded SMP queue URI as the
     * {@code smp} query parameter of its fragment
     * ({@code simplex:/contact#/?v=…&smp=smp%3A%2F%2F<keyhash>%40<host>%2F<queueId>…});
     * decoded, the queue id is the path segment after the server authority:
     * {@code smp://<keyhash>@<host>[,<host2>]/<queueId>[#…]}. Hostnames
     * never contain {@code '/'}, so the first slash after {@code '@'}
     * starts the id; {@code '#'}, {@code '/'} or {@code '?'} ends it.
     *
     * <p>Untrusted wire data: every step fails to {@code null} (the caller
     * maps it to the sentinel {@link CommandError}), and the extracted id
     * must pass {@link #isValidQueueAddressId} before it is surfaced — the
     * same gate every other wire-sourced id passes (design §6.4.4).</p>
     *
     * <p>Package-private (not {@code private}) so
     * {@link SimpleXAdapter#canonicalizeContactId} reuses this one
     * extractor for the operator-supplied admin link rather than
     * duplicating the link grammar — the single source of extraction
     * truth (the §Context drift argument, M1-465).</p>
     */
    static @Nullable String extractQueueAddressId(String contactLink) {
        // The smp param key must be preceded by '?' or '&' so a raw '='
        // inside another param's value (e.g. base64 padding in dh=) can
        // never be misread as the key boundary.
        int paramIdx = contactLink.indexOf("smp=");
        while (paramIdx > 0
                && contactLink.charAt(paramIdx - 1) != '?'
                && contactLink.charAt(paramIdx - 1) != '&') {
            paramIdx = contactLink.indexOf("smp=", paramIdx + 4);
        }
        if (paramIdx <= 0) {
            return null;
        }
        int valueStart = paramIdx + 4;
        int valueEnd = contactLink.indexOf('&', valueStart);
        if (valueEnd < 0) {
            valueEnd = contactLink.length();
        }
        String smpUri;
        try {
            smpUri = java.net.URLDecoder.decode(
                    contactLink.substring(valueStart, valueEnd),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!smpUri.startsWith("smp://")) {
            return null;
        }
        int atIdx = smpUri.indexOf('@');
        if (atIdx < 0) {
            return null;
        }
        int slashIdx = smpUri.indexOf('/', atIdx + 1);
        if (slashIdx < 0) {
            return null;
        }
        int idStart = slashIdx + 1;
        int idEnd = idStart;
        while (idEnd < smpUri.length()
                && smpUri.charAt(idEnd) != '#'
                && smpUri.charAt(idEnd) != '/'
                && smpUri.charAt(idEnd) != '?') {
            idEnd++;
        }
        String id = smpUri.substring(idStart, idEnd);
        return isValidQueueAddressId(id) ? id : null;
    }

    private static DecodedFrame decodeSendAck(@Nullable String corrId, JsonNode resp) {
        // Known simplex-chat shapes only: the chat-item id lives either
        // directly on the response, on a chatItems container object, or — on
        // the v6.5.4 plural newChatItems send result — inside the first
        // element of the chatItems ARRAY as chatItems[0].chatItem.itemId
        // (the AChatItem shape, same as a received item). Reading the known
        // fields — not a breadth-first key search over every child object —
        // keeps attacker-influenced envelope content (echoed inbound bytes)
        // from being picked up as a forged chatItemId out of an unrelated
        // nested object. path()/path(int) yield MissingNode (never null) for
        // the shapes that don't apply, so each candidate is shape-safe.
        String chatItemId = firstTextual(
                resp.get("itemId"),
                resp.get("chatItemId"),
                resp.path("chatItems").get("itemId"),
                resp.path("chatItems").get("chatItemId"),
                resp.path("chatItems").path(0).path("chatItem").get("itemId"),
                resp.path("chatItems").path(0).path("chatItem").get("chatItemId"));
        if (chatItemId == null) {
            return new Ignored("send-ack-without-chatItemId");
        }
        return new SendAck(corrId == null ? "" : corrId, chatItemId);
    }

    private static DecodedFrame decodeError(@Nullable String corrId, JsonNode resp) {
        // Same known-field rule as decodeSendAck: the discriminating tag
        // is either a textual chatError / errorType / error directly on
        // the response, or nested as {chatError: {errorType: "..."}}.
        String errorTag = firstTextual(
                resp.get("chatError"),
                resp.path("chatError").get("errorType"),
                resp.get("errorType"),
                resp.get("error"));
        FailureCategory category = classifyError(errorTag == null ? "" : errorTag);
        // An UNRECOGNIZED envelope is reduced to a fixed sentinel — the prior
        // resp.toString() fallback dumped the whole error envelope (which
        // simplex-chat may populate with bytes echoed back from the
        // offending inbound, e.g. user message body fragments) into
        // CommandError.detail(), which then flowed into both the
        // WS-client DEBUG log at failPending() and the MessagingException
        // message text returned to the adapter caller. security.md
        // §User-content logging is "at any log level"; §User content
        // in exceptions covers the MessagingException path.
        //
        // A RECOGNIZED errorTag is still forwarded verbatim into
        // CommandError.detail(): this is safe ONLY on the assumption that
        // simplex-chat error tags are a bounded, enum-like discriminator
        // vocabulary, not free-form user prose. If a future tag carries
        // echoed inbound bytes, that assumption breaks and the recognized
        // branch becomes a leak path — narrow or sanitize it then. The
        // dropped envelope content is not needed by the Provider: the
        // FailureCategory is the routing signal and the corrId is the
        // correlation key.
        return new CommandError(corrId == null ? "" : corrId,
                category,
                errorTag == null ? "unrecognized-error-envelope" : errorTag);
    }

    private static @Nullable String optText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * First textual node among the given candidates, or null. A candidate
     * may be null at runtime — these are {@code JsonNode.get(...)} results
     * that return null for an absent field — so each is null-checked before
     * use even though the varargs array itself is never null.
     */
    private static @Nullable String firstTextual(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isTextual()) {
                return candidate.asText();
            }
        }
        return null;
    }

    // --- Failure classification ---------------------------------------------

    /**
     * simplex-chat error tags v1 buckets as {@link FailureCategory#TRANSIENT}
     * — network resets, idle closes, rate limits, "try again" signals.
     * Stored lowercased; {@link #classifyError} folds the wire tag before the
     * membership test. This is the recognised transient vocabulary surfaced
     * by the M1-105 / M1-109 integration work; any tag outside it is
     * fail-closed to PERMANENT (the spec's "cannot tell → permanent"
     * default), so the set is extended only when a new tag is observed to be
     * genuinely transient.
     */
    private static final Set<String> TRANSIENT_ERROR_TAGS = Set.of(
            "rcvratelimit",
            "tryagainlater",
            "networkerror",
            "connectiontimeout");

    /**
     * Bucket a simplex-chat error tag into {@link FailureCategory#TRANSIENT}
     * or {@link FailureCategory#PERMANENT} per {@code docs/spec/messaging.md}
     * §Failure handling and {@code docs/design/06-messaging.md} §6.4.7.
     *
     * <p>Membership is an exact include-list ({@link #TRANSIENT_ERROR_TAGS},
     * matched case-insensitively), deliberately NOT substring matching: a
     * permanent tag whose name merely contains a transient-looking fragment
     * (e.g. an unknown tag containing "temporary") would otherwise be
     * promoted to TRANSIENT and retried forever, inverting the spec's
     * fail-closed default. Everything not on the list — unknown, unmatched,
     * or empty — classifies PERMANENT, per {@code docs/spec/messaging.md}
     * §Failure handling: "An adapter that cannot tell the two apart MUST
     * default to permanent."</p>
     */
    static FailureCategory classifyError(String errorTag) {
        return TRANSIENT_ERROR_TAGS.contains(errorTag.toLowerCase(java.util.Locale.ROOT))
                ? FailureCategory.TRANSIENT
                : FailureCategory.PERMANENT;
    }

    // --- Sealed decoded-frame surface ---------------------------------------

    /** Sealed hierarchy of frame variants returned by {@link #decode}. */
    sealed interface DecodedFrame
            permits Inbound, GroupCandidate, OversizeDropped, SendAck, SelfAddress, CommandError, Ignored {
    }

    /** A direct-message inbound that should be delivered to Provider. */
    record Inbound(InboundMessage message) implements DecodedFrame {
    }

    /**
     * A group-scope {@code newChatItem} surfaced verbatim from the wire.
     * Mention recognition (whether this candidate is delivered to
     * Provider) happens in {@link SimpleXGroupHandler}; the codec stays
     * pure-static and has no view of the bot's per-adapter queue
     * address. The {@code mentionQueueAddresses} list carries queue
     * addresses extracted from the simplex-chat formatted-text mention
     * format, validated through {@link #isValidQueueAddressId}.
     *
     * <p>{@code mentionSpans} carries each mention segment's position
     * inside {@code text} (see {@link #extractGroupMentions} for the
     * reconstruction guard) so the handler can strip the bot's own
     * mention before delivery. It may be empty even when
     * {@code mentionQueueAddresses} is not — recognition and stripping
     * have different trust requirements.</p>
     */
    record GroupCandidate(
            String adapterGroupId,
            String senderContactId,
            @Nullable String senderDisplayName,
            String text,
            List<String> mentionQueueAddresses,
            List<MentionSpan> mentionSpans,
            String adapterMessageId) implements DecodedFrame {
    }

    /**
     * One mention segment's location inside a {@link GroupCandidate}'s
     * text: {@code start} is the UTF-16 offset of the segment, {@code
     * length} its extent. Produced only when the formatted-text segments
     * reconstruct the message text exactly.
     */
    record MentionSpan(String queueAddress, int start, int length) {
    }

    /** Response to a command we previously issued, carrying the chat-item id. */
    record SendAck(String corrId, String chatItemId) implements DecodedFrame {
    }

    /**
     * Response to the self-address query ({@link #encodeShowMyAddressCommand}),
     * carrying the bot's bare queue address id extracted from the returned
     * contact link and already validated through
     * {@link #isValidQueueAddressId}. The cryptographic-length floor
     * ({@link SimpleXIdentity#isWellFormed}) is applied at adoption in
     * {@code SimpleXAdapter}, the same split every other wire id uses.
     */
    record SelfAddress(String corrId, String queueAddressId) implements DecodedFrame {
    }

    /** Error response to a command, with the categorised failure. */
    record CommandError(String corrId,
                        FailureCategory category,
                        String detail) implements DecodedFrame {
    }

    /** A frame we recognised but do not handle in this milestone. */
    record Ignored(String reason) implements DecodedFrame {
    }

    /**
     * An inbound dropped at the decode boundary for exceeding the
     * transport size cap (design §6.3.10). Distinct from {@link Ignored}
     * so the consumer ({@link SimpleXWebSocketClient}) can raise the
     * {@code adapter.inbound.dropped{reason=oversize}} counter and the
     * §6.3.10 WARN with the attribution in hand: the decoded {@code scope}
     * (dm/group, for {@code scope_kind}), the raw {@code senderContactId}
     * (redacted at the log site, never logged raw — D37), and the
     * {@code adapterMessageId}. The decode-time cap CHECK is unchanged;
     * this variant only makes the existing silent drop observable.
     */
    record OversizeDropped(ScopeRef scope, String senderContactId, String adapterMessageId)
            implements DecodedFrame {
    }

    /**
     * Thrown when a received frame cannot be parsed as a SimpleX envelope.
     * Unchecked so the WS listener can catch at one boundary and log + drop;
     * mirrors {@code NostrMessage.MalformedFrameException}.
     */
    static final class MalformedFrameException extends RuntimeException {
        MalformedFrameException(String message) {
            super(message);
        }
    }
}
