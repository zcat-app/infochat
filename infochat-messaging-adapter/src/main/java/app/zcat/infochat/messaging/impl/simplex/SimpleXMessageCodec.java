package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
     * Adapter-local clock cap on the outbound text we paste into the command
     * envelope. The Provider-side capability flag enforces the protocol cap
     * (4 KB per design §6.4.2); this constant exists only as a defensive
     * second wall against a Provider that forgot to chunk — the codec is the
     * narrowest internal boundary that still sees the raw text.
     */
    static final int MAX_OUTBOUND_TEXT_BYTES = 4_000;

    /**
     * Inbound text cap (UTF-8 bytes) enforced at decode time on the
     * {@code text} field of every {@code newChatItem} frame. Mirrors the
     * SPI-level cap {@link app.zcat.infochat.messaging.CapabilityFlags#maxInboundMessageBytes()}
     * that {@code SimpleXAdapter} advertises — both values are
     * 16 KiB on the laptop profile per {@code docs/design/06-messaging.md}
     * §6.2.2 / §6.4.4. The codec MUST stay in lockstep with the SPI value:
     * the SPI value is what the Provider's downstream budgets (LLM tokens,
     * Stage 1 watchdog) plan against; this constant is the codec-local
     * enforcement that keeps the SPI promise honest at the inbound trust
     * boundary instead of relying on the 1 MiB WebSocket frame ceiling.
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
     *         Provider's capability-flag chunking should have prevented
     *         this; the check is a system-boundary defense.
     */
    static @NonNull String encodeSendCommand(@NonNull String corrId,
                                             @NonNull ScopeRef scope,
                                             @NonNull String text) throws MessagingException {
        requireWithinCap(text);
        String target = targetSelector(scope);
        String cmd = "/_send " + target + " json " + jsonString(textContent(text));
        return envelope(corrId, cmd);
    }

    /**
     * Encode an in-place edit of a previously-sent message. {@code live=on}
     * for the progress-notifier update sequence; {@code finalize} (the
     * terminal edit) uses {@code live=off} via
     * {@link #encodeFinalizeCommand}.
     */
    static @NonNull String encodeUpdateCommand(@NonNull String corrId,
                                               @NonNull String chatItemId,
                                               @NonNull ScopeRef scope,
                                               @NonNull String text) throws MessagingException {
        return encodeEdit(corrId, chatItemId, scope, text, /* live */ true);
    }

    /** Encode the terminal edit ({@code live=off}). */
    static @NonNull String encodeFinalizeCommand(@NonNull String corrId,
                                                 @NonNull String chatItemId,
                                                 @NonNull ScopeRef scope,
                                                 @NonNull String text) throws MessagingException {
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
                + " json " + jsonString(textContent(text));
        return envelope(corrId, cmd);
    }

    /**
     * Encode a typing on/off pulse. Per acceptance item 11 the adapter
     * exposes this even though design §6.4.2 calls SimpleX's first-class
     * typing surface absent: the ticket commits to issuing the
     * {@code apiSetContactTyping}-shaped command, and the SimpleX server
     * either acts on it or returns an error envelope the codec classifies
     * via {@link #classifyError(String)}.
     */
    static @NonNull String encodeTypingCommand(@NonNull String corrId,
                                               @NonNull ScopeRef scope,
                                               boolean typing) throws MessagingException {
        String target = targetSelector(scope);
        String cmd = "/_set_contact_typing " + target + " " + (typing ? "on" : "off");
        return envelope(corrId, cmd);
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

    static boolean isValidQueueAddressId(@NonNull String id) {
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

    private static String jsonString(String raw) {
        // raw is already a serialised JSON object; pass through as-is.
        return raw;
    }

    private static void requireWithinCap(String text) throws MessagingException {
        int byteLength = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (byteLength > MAX_OUTBOUND_TEXT_BYTES) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "outbound text " + byteLength + " bytes exceeds adapter cap "
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
    static @NonNull DecodedFrame decode(@NonNull String frame) {
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
            case "sentMessage", "apiSendMessageResponse", "newChatItems" -> decodeSendAck(corrId, resp);
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
        JsonNode content = itemBody.get("content");
        if (content == null) {
            return new Ignored("newChatItem-without-content");
        }
        JsonNode msgContent = content.get("msgContent");
        if (msgContent == null) {
            return new Ignored("newChatItem-without-msgContent");
        }
        String text = optText(msgContent, "text");
        if (text == null) {
            return new Ignored("newChatItem-without-text");
        }
        // Enforce the SPI-declared inbound cap at the parse boundary so the
        // Provider's downstream budgets (LLM tokens, Stage 1 watchdog) plan
        // against a real ceiling rather than the 1 MiB WebSocket frame
        // ceiling. UTF-8 byte length, not Java char length — the cap is a
        // wire-level budget.
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_INBOUND_TEXT_BYTES) {
            return new Ignored("newChatItem-text-exceeds-inbound-cap");
        }
        String adapterMessageId = optText(itemBody, "itemId");
        if (adapterMessageId == null) {
            adapterMessageId = "simplex-" + System.nanoTime();
        }
        Identity sender = new Identity(contactId, displayName, Instant.now());
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Dm(contactId),
                text,
                Instant.now(),
                adapterMessageId);
        return new Inbound(msg);
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
        JsonNode content = itemBody.get("content");
        if (content == null) {
            return new Ignored("newChatItem-group-without-content");
        }
        JsonNode msgContent = content.get("msgContent");
        if (msgContent == null) {
            return new Ignored("newChatItem-group-without-msgContent");
        }
        String text = optText(msgContent, "text");
        if (text == null) {
            return new Ignored("newChatItem-group-without-text");
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_INBOUND_TEXT_BYTES) {
            return new Ignored("newChatItem-group-text-exceeds-inbound-cap");
        }
        List<String> mentions = extractMentionQueueAddresses(itemBody.get("formattedText"));
        String adapterMessageId = optText(itemBody, "itemId");
        if (adapterMessageId == null) {
            adapterMessageId = "simplex-" + System.nanoTime();
        }
        return new GroupCandidate(
                adapterGroupId,
                senderContactId,
                senderDisplayName,
                text,
                List.copyOf(mentions),
                adapterMessageId);
    }

    /**
     * Walk the {@code formattedText} array and collect every entry whose
     * {@code format.type} is {@code "mention"}, returning the entries'
     * {@code format.memberRef} queue address strings. Entries whose
     * memberRef fails the queue-address validator are skipped (a peer
     * cannot smuggle a forged command verb through the mention list).
     * A null or non-array input returns the empty list — the spec rule
     * "no mentions → cannot mention the bot" is honored downstream.
     */
    private static List<String> extractMentionQueueAddresses(@Nullable JsonNode formattedText) {
        if (formattedText == null || !formattedText.isArray()) {
            return List.of();
        }
        List<String> mentions = new ArrayList<>();
        for (JsonNode element : formattedText) {
            JsonNode format = element.get("format");
            if (format == null || !format.isObject()) {
                continue;
            }
            if (!"mention".equals(optText(format, "type"))) {
                continue;
            }
            String memberRef = optText(format, "memberRef");
            if (memberRef == null || !isValidQueueAddressId(memberRef)) {
                continue;
            }
            mentions.add(memberRef);
        }
        return mentions;
    }

    private static DecodedFrame decodeSendAck(String corrId, JsonNode resp) {
        // Real simplex-chat returns one of several response shapes. Look for
        // a chatItemId anywhere reasonable; fall back to the bare envelope if
        // simplex-chat's response shape later varies.
        String chatItemId = findFirstString(resp, "itemId", "chatItemId");
        if (chatItemId == null) {
            return new Ignored("send-ack-without-chatItemId");
        }
        return new SendAck(corrId == null ? "" : corrId, chatItemId);
    }

    private static DecodedFrame decodeError(String corrId, JsonNode resp) {
        String errorTag = findFirstString(resp, "chatError", "errorType", "error");
        FailureCategory category = classifyError(errorTag == null ? "" : errorTag);
        // Fixed sentinel when no recognized error tag is found — the prior
        // resp.toString() fallback dumped the whole error envelope (which
        // simplex-chat may populate with bytes echoed back from the
        // offending inbound, e.g. user message body fragments) into
        // CommandError.detail(), which then flowed into both the
        // WS-client DEBUG log at failPending() and the MessagingException
        // message text returned to the adapter caller. security.md
        // §User-content logging is "at any log level"; §User content
        // in exceptions covers the MessagingException path. The
        // structural fix is to keep envelope bytes out of CommandError
        // in the first place. The dropped envelope content is not
        // needed by the Provider — the FailureCategory is the routing
        // signal and the corrId is the correlation key.
        return new CommandError(corrId == null ? "" : corrId,
                category,
                errorTag == null ? "unrecognized-error-envelope" : errorTag);
    }

    private static String optText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Depth-first search for a textual node with one of the given names. */
    private static String findFirstString(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode direct = node.get(name);
            if (direct != null && direct.isTextual()) {
                return direct.asText();
            }
        }
        // One-level descent: simplex-chat error envelopes nest the
        // discriminating tag under {chatError: {type: "..."}}.
        if (node.isObject()) {
            var fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                JsonNode child = node.get(fieldNames.next());
                if (child != null && child.isObject()) {
                    for (String name : names) {
                        JsonNode tagged = child.get(name);
                        if (tagged != null && tagged.isTextual()) {
                            return tagged.asText();
                        }
                    }
                }
            }
        }
        return null;
    }

    // --- Failure classification ---------------------------------------------

    /**
     * Bucket a simplex-chat error tag into {@link FailureCategory#TRANSIENT}
     * or {@link FailureCategory#PERMANENT} per {@code docs/spec/messaging.md}
     * §Failure handling and {@code docs/design/06-messaging.md} §6.4.7.
     * Unknown tags default to {@code PERMANENT} per the spec rule.
     */
    static @NonNull FailureCategory classifyError(@NonNull String errorTag) {
        String lower = errorTag.toLowerCase(java.util.Locale.ROOT);
        // Transient: anything that looks like a network reset, idle close,
        // rate limit, or "try again" signal. The spec wants these retried by
        // the Provider's uniform retry policy.
        if (lower.contains("ratelimit")
                || lower.contains("tryagain")
                || lower.contains("networkerror")
                || lower.contains("timeout")
                || lower.contains("temporary")
                || lower.contains("unavailable")
                || lower.contains("connectionerror")) {
            return FailureCategory.TRANSIENT;
        }
        // Permanent: blocked, gone, oversize, rotated identity, policy
        // violation. The full list lives in design §6.4.7; the tags below
        // are the ones M1-103 commits to bucketing — anything else falls
        // through to PERMANENT by the spec's "cannot tell → PERMANENT" rule.
        return FailureCategory.PERMANENT;
    }

    // --- Sealed decoded-frame surface ---------------------------------------

    /** Sealed hierarchy of frame variants returned by {@link #decode}. */
    sealed interface DecodedFrame permits Inbound, GroupCandidate, SendAck, CommandError, Ignored {
    }

    /** A direct-message inbound that should be delivered to Provider. */
    record Inbound(@NonNull InboundMessage message) implements DecodedFrame {
    }

    /**
     * A group-scope {@code newChatItem} surfaced verbatim from the wire.
     * Mention recognition (whether this candidate is delivered to
     * Provider) happens in {@link SimpleXGroupHandler}; the codec stays
     * pure-static and has no view of the bot's per-adapter queue
     * address. The {@code mentionQueueAddresses} list carries queue
     * addresses extracted from the simplex-chat formatted-text mention
     * format, validated through {@link #isValidQueueAddressId}.
     */
    record GroupCandidate(
            @NonNull String adapterGroupId,
            @NonNull String senderContactId,
            @Nullable String senderDisplayName,
            @NonNull String text,
            @NonNull List<String> mentionQueueAddresses,
            @NonNull String adapterMessageId) implements DecodedFrame {
    }

    /** Response to a command we previously issued, carrying the chat-item id. */
    record SendAck(@NonNull String corrId, @NonNull String chatItemId) implements DecodedFrame {
    }

    /** Error response to a command, with the categorised failure. */
    record CommandError(@NonNull String corrId,
                        @NonNull FailureCategory category,
                        @NonNull String detail) implements DecodedFrame {
    }

    /** A frame we recognised but do not handle in this milestone. */
    record Ignored(@NonNull String reason) implements DecodedFrame {
    }

    /**
     * Thrown when a received frame cannot be parsed as a SimpleX envelope.
     * Unchecked so the WS listener can catch at one boundary and log + drop;
     * mirrors {@code NostrMessage.MalformedFrameException}.
     */
    static final class MalformedFrameException extends RuntimeException {
        MalformedFrameException(@NonNull String message) {
            super(message);
        }
    }
}
