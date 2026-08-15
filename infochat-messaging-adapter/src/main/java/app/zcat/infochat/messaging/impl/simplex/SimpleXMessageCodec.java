package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.Utf8;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * JSON codec for the simplex-chat WebSocket bot API. Pure functions; no I/O,
 * no state. Wire shapes live-pinned per-capture (inline provenance notes);
 * v7.0.0 re-verification + the drifted-encode fix: M1-839.
 * The simplex-chat envelope shape (verbatim from
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
        String cmd = "/_send " + target + " json " + composedMessageArray(text);
        return envelope(corrId, cmd);
    }

    /**
     * File-send form of the id-addressed {@code /_send} (D74, design §6.2.4): one composed message whose {@code filePath} names the spool file beside a file-typed msgContent; MIME / display name are not wire bytes here.
     */
    static String encodeSendFileCommand(String corrId,
                                        ScopeRef scope,
                                        String filePath,
                                        String mimeType,
                                        String displayFileName) throws MessagingException {
        String target = targetSelector(scope);
        String cmd = "/_send " + target + " json " + fileComposedMessageArray(filePath);
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
                + " json " + updatedMessageContent(text);
        return envelope(corrId, cmd);
    }

    /**
     * Encode the group-join command the adapter issues to accept a received
     * invitation (M1-515). {@code adapterGroupId} is echoed into the command
     * string, so it is queue-address-validated here at the encode boundary —
     * the same discipline as {@link #targetSelector} — and a malformed id
     * fails PERMANENT rather than reaching the wire. Live-confirmed against
     * simplex-chat v6.5.4.1: {@code /_join #<groupId>} returns
     * {@code userAcceptedGroupSent} and drives the bot's membership
     * invited→connected (followed by an async {@code userJoinedGroup}); the
     * adapter sends it fire-and-forget (no chat-item handle to return).
     */
    static String encodeJoinGroupCommand(String corrId, String adapterGroupId)
            throws MessagingException {
        requireValidQueueAddressId(adapterGroupId, "adapterGroupId");
        return envelope(corrId, "/_join #" + adapterGroupId);
    }

    /**
     * Encode the self-address query ({@code /show_address}) whose
     * {@code userContactLink} response carries the bot's own shareable
     * contact link. Takes no ids from any caller, so there is nothing to
     * queue-address-validate — the command string is a constant.
     */
    static String encodeShowAddressCommand(String corrId) {
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

    /**
     * The {@code {"msgContent":{"type":"text","text":…}}} composed-message
     * wrapper, shared by the {@code /_send} array element and the
     * {@code /_update item} single object.
     */
    private static ObjectNode composedMessage(String text) {
        ObjectNode msgContent = MAPPER.createObjectNode();
        msgContent.put("type", "text");
        msgContent.put("text", text);
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("msgContent", msgContent);
        return wrapper;
    }

    /**
     * The {@code /_send} message-content payload: a JSON ARRAY of composed
     * messages. simplex-chat v6.5.4.1 requires the array form
     * ({@code /_send @<id> json [{"msgContent":…}]}) and rejects a bare object
     * with {@code chatCmdError commandError "Failed reading: empty"}
     * (live-confirmed, M1-510). v1 sends one message per command, so the array
     * carries exactly one element; outbound chunking (§6.3.4) still emits one
     * {@code /_send} per chunk. The {@code /_update item} edit path uses the
     * single-object {@link #updatedMessageContent} form — an edit targets
     * exactly one existing item, so there is no composed-message list.
     */
    private static String composedMessageArray(String text) {
        ArrayNode payload = MAPPER.createArrayNode();
        payload.add(composedMessage(text));
        return payload.toString();
    }

    /** The {@code /_update item} edit payload: the single-object {@code UpdatedMessage}
     * — {@code msgContent} plus a REQUIRED empty {@code mentions} map (M1-839). */
    private static String updatedMessageContent(String text) {
        ObjectNode payload = composedMessage(text);
        payload.putObject("mentions");
        return payload.toString();
    }

    /** The {@code /_send} attachment payload: a one-element array whose composed message carries {@code filePath} plus a file-typed msgContent (the array form the text path documents). */
    private static String fileComposedMessageArray(String filePath) {
        ObjectNode msgContent = MAPPER.createObjectNode();
        msgContent.put("type", "file");
        msgContent.put("text", "");
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("filePath", filePath);
        wrapper.set("msgContent", msgContent);
        ArrayNode payload = MAPPER.createArrayNode();
        payload.add(wrapper);
        return payload.toString();
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
     * mention-recognition decision (which depends on the bot's per-group
     * memberId, runtime state not visible to a pure-static codec); it
     * exposes the raw mention {@code memberId}s and the bot's own per-group
     * {@code memberId} and lets {@link SimpleXGroupHandler} compare them by
     * byte-equality (D51). Non-{@code direct} and non-{@code group}
     * chatTypes still surface as {@link Ignored}.</p>
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
            case "receivedGroupInvitation" -> decodeReceivedGroupInvitation(resp);
            case "sentMessage", "apiSendMessageResponse" -> decodeSendAck(corrId, resp);
            case "userContactLink" -> decodeUserContactLink(corrId, resp);
            case "chatCmdError", "chatItemUpdateError" -> decodeError(corrId, resp);
            // File-send completion/failure events (D74, design §6.2.4): only
            // sndFileCompleteXFTP releases the waiting sendAttachment; every other
            // completion on our chat item (legacy tag, standalone) fails it PERMANENT.
            case "sndFileCompleteXFTP" -> decodeFileSendCompletion(resp);
            case "sndFileError", "sndFileCancelled", "sndFileComplete",
                 "sndStandaloneFileComplete" -> decodeFileSendFailure(resp);
            case "sndFileStart", "sndFileProgressXFTP", "sndFileRedirectStartXFTP",
                 "sndFileWarning" ->
                    new Ignored("sndFile-transfer-in-progress");
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
     * Decode a {@code receivedGroupInvitation} async event (live v6.5.4.1,
     * M1-515) into a {@link ReceivedGroupInvitation}. The bot has been invited
     * to a group but has not yet joined ({@code membership.memberStatus} is
     * {@code "invited"}); Provider decides whether to auto-join based on the
     * inviter's registration state (the adapter queries no DB, D10).
     *
     * <p>Two fields are extracted from the live frame
     * ({@code resp.groupInfo.{groupId, membership.invitedBy.byContactId}}):</p>
     * <ul>
     *   <li>{@code adapterGroupId} — echoed into {@code /_join #<groupId>}
     *       ({@link #encodeJoinGroupCommand}), so it is rejected here unless it
     *       matches the queue-address charset, the same decode-boundary
     *       discipline the group-inbound path applies to its groupId (a frame
     *       that fails can never carry an injected command fragment, §6.4.4).</li>
     *   <li>{@code inviterContactId} — the inviter's connection contact id,
     *       used ONLY in Provider's parameterized registered-inviter lookup,
     *       never echoed into a command. It is read only when
     *       {@code invitedBy.type == "contact"}: a non-contact inviter
     *       ({@code "member"}/{@code "unknown"}, e.g. a pre-contact host)
     *       carries no contact id Provider can resolve, so the invitation is
     *       dropped fail-closed and never auto-joined (redteam vector 3 — the
     *       gate cannot be bypassed to make the bot join arbitrary groups).</li>
     * </ul>
     */
    private static DecodedFrame decodeReceivedGroupInvitation(JsonNode resp) {
        JsonNode groupInfo = resp.get("groupInfo");
        if (groupInfo == null) {
            return new Ignored("groupInvitation-without-groupInfo");
        }
        String adapterGroupId = optText(groupInfo, "groupId");
        if (adapterGroupId == null) {
            return new Ignored("groupInvitation-without-groupId");
        }
        if (!isValidQueueAddressId(adapterGroupId)) {
            return new Ignored("groupInvitation-invalid-groupId");
        }
        JsonNode membership = groupInfo.get("membership");
        if (membership == null) {
            return new Ignored("groupInvitation-without-membership");
        }
        JsonNode invitedBy = membership.get("invitedBy");
        if (invitedBy == null) {
            return new Ignored("groupInvitation-without-invitedBy");
        }
        if (!"contact".equals(optText(invitedBy, "type"))) {
            return new Ignored("groupInvitation-inviter-not-contact");
        }
        String inviterContactId = optText(invitedBy, "byContactId");
        if (inviterContactId == null) {
            return new Ignored("groupInvitation-without-inviter");
        }
        // Same id validator the inbound-contact path applies: a malformed
        // value is dropped fail-closed rather than reaching the Provider gate.
        if (!isValidQueueAddressId(inviterContactId)) {
            return new Ignored("groupInvitation-invalid-inviter");
        }
        return new ReceivedGroupInvitation(adapterGroupId, inviterContactId);
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
        // The chat-type discriminator is chatInfo.type on the live v6.5.4.1
        // wire (NOT chatInfo.chatType, the hand-rolled-fixture fiction that
        // dropped 100% of real inbound as newChatItem-without-chatType, M1-510).
        String chatType = optText(chatInfo, "type");
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
        // localDisplayName is simplex-chat's locally-resolved handle for the
        // contact ("admin_1" on the live v6.5.4.1 frame); contact.profile.
        // displayName is the sender's self-asserted profile name and is never
        // read here. displayName is informational only — identity is always the
        // connection contactId (D10) — but the local handle is the stable one.
        String displayName = optText(contact, "localDisplayName");
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
     * Decode a {@code newChatItem} frame whose {@code chatInfo.type}
     * is {@code "group"}. Surfaces the mention {@code memberId}s and the
     * bot's own per-group {@code memberId} (from
     * {@code chatInfo.groupInfo.membership.memberId}) so
     * {@link SimpleXGroupHandler} can recognise a bot @mention by
     * byte-equality of a mention's {@code memberId} against the bot's own
     * — the v6.5.4.1 mention payload carries no queue address (decision
     * D51).
     *
     * <p>Same trust-boundary discipline as the direct-message branch for
     * the ids that flow downstream: adapterGroupId and sender contact id
     * are validated by {@link #isValidQueueAddressId} before landing in a
     * {@link GroupCandidate}, so an attacker-controlled id cannot piggyback
     * a forged command verb. Mention {@code memberId}s and the bot
     * {@code memberId} are NOT validated against that character set — they
     * are a different namespace (base64 group member ids, with padding) and
     * are only ever compared against each other, never echoed into an
     * outbound command or the delivered {@link InboundMessage}.</p>
     *
     * <p>Frame-path conventions used here (simplex-chat WebSocket bot
     * API):</p>
     * <ul>
     *   <li>{@code chatInfo.groupInfo.groupId} → adapterGroupId</li>
     *   <li>{@code chatInfo.groupInfo.membership.memberId} → the bot's own
     *       per-group memberId, the recognition anchor compared against the
     *       mention memberIds. A frame lacking it is dropped — recognition
     *       is impossible without the bot's own id (fail-closed)</li>
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
     *   <li>{@code chatItem.mentions{}} (display name → {@code memberId})
     *       and {@code chatItem.formattedText[*]} where {@code format.type
     *       == "mention"} → the mention memberIds and the spans to strip
     *       (see {@link #extractGroupMentions})</li>
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
        // The bot's own per-group memberId is the mention-recognition anchor
        // (D51): a bot @mention is a mentions{} entry whose memberId byte-equals
        // this value. It comes from the local trusted simplex (the current
        // user's membership in this group), not from the peer, and is never
        // echoed downstream — so no queue-address validation. Absent → drop:
        // recognition is impossible without it (fail-closed, never deliver an
        // unrecognised group message).
        JsonNode membership = groupInfo.get("membership");
        if (membership == null) {
            return new Ignored("newChatItem-group-without-membership");
        }
        String botMemberId = optText(membership, "memberId");
        if (botMemberId == null) {
            return new Ignored("newChatItem-group-without-membership-memberId");
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
            GroupMentions groupMentions = extractGroupMentions(
                    itemBody.get("mentions"), itemBody.get("formattedText"), text);
            return new GroupCandidate(
                    adapterGroupId,
                    senderContactId,
                    senderDisplayName,
                    text,
                    groupMentions.memberIds(),
                    botMemberId,
                    groupMentions.spans(),
                    adapterMessageId);
        });
    }

    /**
     * Produce the two mention products the group path needs from the v6.5.4.1
     * frame: the mention {@code memberId}s (recognition) and the mention
     * {@code spans} tagged by memberId (stripping). Their sources are
     * independent, which is why recognition survives a failed span
     * reconstruction (design §6.4.4):
     *
     * <ul>
     *   <li><strong>memberIds</strong> come from the top-level {@code mentions{}}
     *       object — keyed by member display name, each value carrying the
     *       per-group {@code memberId} simplex resolved the @mention to
     *       (simplex {@code CIMention.memberId}). They are compared only against
     *       the bot's own {@code membership.memberId}; never echoed downstream,
     *       so they carry no injection risk and are NOT run through the
     *       queue-address validator (memberIds are a different, padded-base64
     *       namespace).</li>
     *   <li><strong>spans</strong> come from {@code formattedText}: each
     *       {@code format.type == "mention"} segment's [start, length) offset
     *       into {@code text}, tagged with the {@code memberId} its
     *       {@code format.memberName} resolves to via {@code mentions{}}. The
     *       memberId tag lets {@link SimpleXGroupHandler} strip ONLY the bot's
     *       own mention and leave co-mentions of other members intact.</li>
     * </ul>
     *
     * <p>The spans are trusted only when {@code formattedText} reconstructs
     * {@code text} exactly — every segment matches {@code text} at its computed
     * position AND the segments cover the text fully. A frame that fails the
     * guard (degenerate or hostile — the wire is untrusted) yields an empty span
     * list: no protocol span can be located and the handler delivers the text
     * unstripped. The memberIds are unaffected by the span guard (they come from
     * {@code mentions{}}, not the walk), so recognition still fires.</p>
     */
    private static GroupMentions extractGroupMentions(@Nullable JsonNode mentions,
                                                      @Nullable JsonNode formattedText,
                                                      String text) {
        List<String> memberIds = new ArrayList<>();
        if (mentions != null && mentions.isObject()) {
            for (Iterator<String> names = mentions.fieldNames(); names.hasNext(); ) {
                String memberId = optText(mentions.get(names.next()), "memberId");
                if (memberId != null) {
                    memberIds.add(memberId);
                }
            }
        }
        List<MentionSpan> spans = new ArrayList<>();
        boolean spansValid = false;
        if (formattedText != null && formattedText.isArray()) {
            spansValid = true;
            int offset = 0;
            for (JsonNode element : formattedText) {
                String segmentText = optText(element, "text");
                // First divergence between the segment concatenation and text
                // voids every span — no offset can be trusted past it.
                if (segmentText == null
                        || !text.regionMatches(offset, segmentText, 0, segmentText.length())) {
                    spansValid = false;
                    break;
                }
                JsonNode format = element.get("format");
                if (format != null && format.isObject()
                        && "mention".equals(optText(format, "type"))) {
                    String memberName = optText(format, "memberName");
                    JsonNode mentioned = (memberName == null || mentions == null)
                            ? null : mentions.get(memberName);
                    String memberId = mentioned == null ? null : optText(mentioned, "memberId");
                    if (memberId != null) {
                        spans.add(new MentionSpan(memberId, offset, segmentText.length()));
                    }
                }
                offset += segmentText.length();
            }
            if (offset != text.length()) {
                spansValid = false;
            }
        }
        return new GroupMentions(List.copyOf(memberIds),
                spansValid ? List.copyOf(spans) : List.of());
    }

    /**
     * The two products of {@link #extractGroupMentions}: the mention
     * {@code memberIds} (collected from {@code mentions{}} for recognition) and
     * the memberId-tagged mention {@code spans} (populated only when the
     * reconstruction guard holds). Both lists are already immutable copies.
     */
    private record GroupMentions(List<String> memberIds, List<MentionSpan> spans) {
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
     * {@code meta.itemId} when present, else a deterministic fallback derived
     * from the frame's stable identifying fields. Mirrors SignalMessageCodec
     * deriving its id from the message timestamp — adapterMessageId is the
     * stable correlation key ({@link InboundMessage} javadoc: retry
     * correlation, audit cross-reference), so two decodes of the same
     * itemId-less frame MUST yield the same id; {@code System.nanoTime()}
     * defeated that. The fallback path is rare (simplex-chat supplies
     * {@code meta.itemId} on real frames) and the id is never persisted across
     * instances, so a content hash that two itemId-less frames with identical
     * stable fields could share is acceptable. The fields are joined with a
     * space — absent from the queue-address charset that the leading
     * contactId/groupId fields are validated against — so the boundary before
     * the trailing free-text field stays unambiguous.
     */
    private static String adapterMessageId(JsonNode itemBody, String... stableFields) {
        String itemId = optText(itemBody.path("meta"), "itemId");
        if (itemId != null) {
            return itemId;
        }
        return "simplex-" + Integer.toHexString(String.join(" ", stableFields).hashCode());
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
     * <p>Untrusted wire data: every step fails to {@code null} (the sole
     * caller, {@link SimpleXAdapter#canonicalizeContactId}, returns the value
     * unchanged so the {@code isWellFormedContactId} gate makes the
     * accept/reject decision), and the extracted id must pass
     * {@link #isValidQueueAddressId} before it is surfaced — the same gate
     * every other wire-sourced id passes (design §6.4.4).</p>
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

    /**
     * Decode the {@code userContactLink} response to a {@code /show_address}
     * query (live v6.5.4.1: the link lives at
     * {@code resp.contactLink.connLinkContact.{connShortLink,connFullLink}}).
     * The short link is preferred — it is the human-shareable form the
     * operator tooling treats as the bot address — with the full link as the
     * fallback for addresses created without short-link support.
     *
     * <p>A corrId-bearing frame that lacks a link decodes to a
     * {@link CommandError} rather than {@link Ignored}: an Ignored frame
     * would strand the caller's pending future until its full ack timeout,
     * while the error completes it in seconds. The detail is a fixed
     * sentinel — never frame bytes; the link value is display-only and must
     * not reach a log through {@code CommandError.detail()} (D37).</p>
     */
    private static DecodedFrame decodeUserContactLink(@Nullable String corrId, JsonNode resp) {
        if (corrId == null) {
            // Only ever a response to our own query; without a corrId there
            // is no pending future to complete, so it is not actionable.
            return new Ignored("userContactLink-without-corrId");
        }
        JsonNode connLink = resp.path("contactLink").path("connLinkContact");
        String link = firstTextual(connLink.get("connShortLink"), connLink.get("connFullLink"));
        if (link == null) {
            return new CommandError(corrId, FailureCategory.PERMANENT,
                    "userContactLink-without-link");
        }
        return new ContactAddress(corrId, link);
    }

    private static DecodedFrame decodeSendAck(@Nullable String corrId, JsonNode resp) {
        // Known simplex-chat shapes only: the chat-item id lives either
        // directly on the response, on a chatItems container object, or — on
        // the v6.5.4.1 plural newChatItems send result — inside the first
        // element of the chatItems ARRAY as chatItems[0].chatItem.meta.itemId
        // (the AChatItem shape, same meta.itemId location as a received item;
        // live-confirmed, M1-510). Reading the known fields — not a
        // breadth-first key search over every child object — keeps
        // attacker-influenced envelope content (echoed inbound bytes) from
        // being picked up as a forged chatItemId out of an unrelated nested
        // object. path()/path(int) yield MissingNode (never null) for the
        // shapes that don't apply, so each candidate is shape-safe.
        //
        // The id is read with firstScalarText (textual OR numeric): on the live
        // v6.5.4.1 wire meta.itemId is a JSON NUMBER (e.g. 21), so a
        // textual-only read would miss it and drop the ack as
        // send-ack-without-chatItemId (M1-510). A numeric id cannot carry a
        // command terminator, and any id later pasted into an edit command is
        // re-gated by isValidQueueAddressId at encode time, so widening to
        // numeric does not loosen the injection boundary.
        String chatItemId = firstScalarText(
                resp.get("itemId"),
                resp.get("chatItemId"),
                resp.path("chatItems").get("itemId"),
                resp.path("chatItems").get("chatItemId"),
                resp.path("chatItems").path(0).path("chatItem").path("meta").get("itemId"),
                resp.path("chatItems").path(0).path("chatItem").get("chatItemId"));
        if (chatItemId == null) {
            return new Ignored("send-ack-without-chatItemId");
        }
        return new SendAck(corrId == null ? "" : corrId, chatItemId);
    }

    /**
     * The {@code sndFileCompleteXFTP} completion event (design §6.2.4) — the only tag that releases the send; the chat-item id is read at the same known AChatItem {@code meta.itemId} place as the plural ack.
     */
    private static DecodedFrame decodeFileSendCompletion(JsonNode resp) {
        String chatItemId = fileEventChatItemId(resp);
        if (chatItemId == null) {
            return new Ignored("file-completion-without-chatItemId");
        }
        return new FileSendComplete(chatItemId);
    }

    /**
     * A failed / cancelled transfer — and, per design §6.2.4, any non-XFTP completion on our chat item — carrying a chat item; the free-form {@code errorMessage} is deliberately not decoded (it can carry transport prose — security.md §User content in exceptions).
     */
    private static DecodedFrame decodeFileSendFailure(JsonNode resp) {
        String chatItemId = fileEventChatItemId(resp);
        if (chatItemId == null) {
            return new Ignored("file-failure-without-chatItem");
        }
        return new FileSendFailed(chatItemId);
    }

    private static @Nullable String fileEventChatItemId(JsonNode resp) {
        return firstScalarText(
                resp.path("chatItem").path("chatItem").path("meta").get("itemId"));
    }

    private static DecodedFrame decodeError(@Nullable String corrId, JsonNode resp) {
        // Same known-field rule as decodeSendAck. On the live v6.5.4.1 wire the
        // discriminating tag is the .type of a nested error object — both
        // {chatError:{errorType:{type:"commandError",…}}} and
        // {chatError:{storeError:{type:"groupAlreadyJoined"}}} occur
        // (live-confirmed, M1-510). Only the enum-like .type is read, never the
        // sibling free-form "message" field, so echoed user prose ("Failed
        // reading: empty") cannot leak into CommandError.detail() or the logs
        // (security.md §User content in exceptions). The trailing top-level
        // candidates remain for shapes that surface the tag directly.
        String errorTag = firstTextual(
                resp.path("chatError").path("errorType").get("type"),
                resp.path("chatError").path("storeError").get("type"),
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

    /**
     * First textual-OR-numeric node among the candidates, rendered via
     * {@code asText()}, or null. Distinct from {@link #firstTextual}: a
     * simplex-chat chat-item id ({@code meta.itemId}) arrives as a JSON number
     * on the live v6.5.4.1 wire, so the send-ack extractor must accept a numeric
     * node and stringify it. Used only by {@link #decodeSendAck}; error-tag
     * decoding stays textual-only (tags are enum-like strings).
     */
    private static @Nullable String firstScalarText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && (candidate.isTextual() || candidate.isNumber())) {
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
            permits Inbound, GroupCandidate, ReceivedGroupInvitation, OversizeDropped,
                    SendAck, ContactAddress, CommandError, FileSendComplete,
                    FileSendFailed, Ignored {
    }

    /** A direct-message inbound that should be delivered to Provider. */
    record Inbound(InboundMessage message) implements DecodedFrame {
    }

    /**
     * A group-scope {@code newChatItem} surfaced verbatim from the wire.
     * Mention recognition (whether this candidate is delivered to
     * Provider) happens in {@link SimpleXGroupHandler}: a bot @mention is
     * a {@code mentionMemberIds} entry that byte-equals {@code botMemberId}
     * (the bot's own per-group memberId, read from
     * {@code chatInfo.groupInfo.membership.memberId}). The codec stays
     * pure-static; memberIds are compared against each other only, never
     * echoed downstream (decision D51).
     *
     * <p>{@code mentionSpans} carries each mention segment's position
     * inside {@code text}, tagged with the memberId it belongs to (see
     * {@link #extractGroupMentions} for the reconstruction guard) so the
     * handler can strip the bot's own mention before delivery. It may be
     * empty even when {@code mentionMemberIds} is not — recognition (from
     * {@code mentions{}}) and stripping (from {@code formattedText}) have
     * different trust requirements.</p>
     */
    record GroupCandidate(
            String adapterGroupId,
            String senderContactId,
            @Nullable String senderDisplayName,
            String text,
            List<String> mentionMemberIds,
            String botMemberId,
            List<MentionSpan> mentionSpans,
            String adapterMessageId) implements DecodedFrame {
    }

    /**
     * One mention segment's location inside a {@link GroupCandidate}'s
     * text: {@code memberId} is the group member the segment mentions,
     * {@code start} is the UTF-16 offset of the segment, {@code length}
     * its extent. Produced only when the formatted-text segments
     * reconstruct the message text exactly.
     */
    record MentionSpan(String memberId, int start, int length) {
    }

    /**
     * A group invitation the bot received but has not yet joined (the
     * {@code receivedGroupInvitation} async event, M1-515). {@code adapterGroupId}
     * is the queue-address-validated group id to echo into {@code /_join};
     * {@code inviterContactId} is the inviter's connection contact id Provider
     * resolves to a registered user (the auto-join gate, D47). The codec stays
     * pure-static and makes no accept decision — Provider gates and instructs
     * the join via {@link MessagingAdapter}.
     */
    record ReceivedGroupInvitation(String adapterGroupId, String inviterContactId)
            implements DecodedFrame {
    }

    /** Response to a command we previously issued, carrying the chat-item id. */
    record SendAck(String corrId, String chatItemId) implements DecodedFrame {
    }

    /**
     * Response to a {@code /show_address} query, carrying the bot's own
     * shareable contact link. Display-only (D37): the value completes the
     * pending command future and must never flow into a log line.
     */
    record ContactAddress(String corrId, String contactLink) implements DecodedFrame {
    }

    /** Error response to a command, with the categorised failure. */
    record CommandError(String corrId,
                        FailureCategory category,
                        String detail) implements DecodedFrame {
    }

    /** Async XFTP file-send completion (D74, design §6.2.4): the spool file is safe to release; correlated by the ack's chat-item id. */
    record FileSendComplete(String chatItemId) implements DecodedFrame {
    }

    /** Async file-transfer failure / cancellation — or a non-XFTP completion (design §6.2.4) — carrying a chat item; the WS client fixes the category when it raises the SPI exception. */
    record FileSendFailed(String chatItemId) implements DecodedFrame {
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
