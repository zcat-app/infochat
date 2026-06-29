package app.zcat.infochat.messaging.impl.simplex;


import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates SimpleX group-scope candidates into {@link InboundMessage}
 * dispatches on the registered {@link MessagingAdapter.InboundHandler}.
 * Pure dispatch — no I/O, no threading; the
 * {@link SimpleXWebSocketClient} listener thread invokes
 * {@link #onGroupCandidate} for every group-scope {@code newChatItem}
 * frame the codec surfaces as {@link SimpleXMessageCodec.GroupCandidate}.
 *
 * <p>Bot mention is the trust anchor for group mode per
 * {@code docs/spec/messaging.md} §Required SPI surface — Receive: a
 * group message reaches Provider only when a {@code mentions{}} entry's
 * {@code memberId} byte-equals the bot's own per-group {@code memberId}
 * (carried on the candidate from {@code chatInfo.groupInfo.membership},
 * decision D51). {@link SimpleXMentionParser#botMentioned} owns the
 * comparison; display-name matching is never used. The decision is
 * made exactly once, here — no other code path in this adapter
 * delivers a group-scope {@link InboundMessage}, so the silent-drop
 * path for un-mentioned group messages is the only escape from this
 * class. A quote-reply to a bot message that carries no mention payload
 * is therefore NOT delivered (it has no matching mention memberId), even
 * though simplex would set {@code meta.userMention} on it.</p>
 *
 * <p>Membership-event surface: SimpleX's WebSocket bot API does not
 * expose a native user-joined / user-left signal in the surface
 * inspected during M1-103, so {@link SimpleXAdapter#capabilities()}
 * declares {@code supportsMembershipEvents = false}. Provider falls
 * back to permanent-delivery-failure cleanup per
 * {@code docs/spec/messaging.md} §Failure handling. If a future
 * simplex-chat release exposes such a signal, both the capability
 * flag and this handler grow a parallel membership-dispatch path.</p>
 */
final class SimpleXGroupHandler {

    private final MessagingAdapter.InboundHandler inboundHandler;

    /**
     * @param inboundHandler the downstream callback that receives
     *                       mentioned group messages. Production wires
     *                       this to {@link SimpleXAdapter#onInbound},
     *                       which re-reads Provider's volatile
     *                       inbound-handler field on each dispatch so a
     *                       {@link MessagingAdapter#setInboundHandler}
     *                       call after {@link SimpleXAdapter#start}
     *                       still routes correctly. Never null.
     */
    SimpleXGroupHandler(MessagingAdapter.InboundHandler inboundHandler) {
        this.inboundHandler = inboundHandler;
    }

    /**
     * Decide whether one group-scope candidate becomes a delivered
     * {@link InboundMessage} or is silently dropped. The mention check
     * is the only gate — security_relevant: the silent path MUST stay
     * silent so an attacker spamming a group cannot generate log
     * pressure that reveals the bot is reading their messages.
     */
    void onGroupCandidate(SimpleXMessageCodec.GroupCandidate gc) {
        if (!SimpleXMentionParser.botMentioned(gc.mentionMemberIds(), gc.botMemberId())) {
            return;
        }
        Instant now = Instant.now();
        Identity sender = new Identity(gc.senderContactId(), gc.senderDisplayName(), now);
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Group(gc.adapterGroupId()),
                stripBotMentions(gc),
                now,
                gc.adapterMessageId());
        inboundHandler.onMessage(msg);
    }

    /**
     * Remove the bot's own mention span(s) from the text before
     * delivery, per {@code docs/spec/messaging.md} §Required SPI surface
     * ("the mention is stripped before delivery"). Anchored to the
     * protocol mention segments the codec located inside the text —
     * never display-name text search, so a body that merely contains
     * the bot's name as plain text is left intact. Only spans tagged with
     * the bot's {@code memberId} are removed, so co-mentions of other
     * members survive. The codec's reconstruction guard
     * ({@link SimpleXMessageCodec.GroupCandidate}) means {@code mentionSpans}
     * may be empty on a degenerate frame even though recognition fired;
     * the text is then delivered unstripped — no span data exists that
     * could locate the mention honestly.
     */
    private String stripBotMentions(SimpleXMessageCodec.GroupCandidate gc) {
        String text = gc.text();
        List<SimpleXMessageCodec.MentionSpan> botSpans = new ArrayList<>();
        for (SimpleXMessageCodec.MentionSpan span : gc.mentionSpans()) {
            if (span.memberId().equals(gc.botMemberId())) {
                botSpans.add(span);
            }
        }
        if (botSpans.isEmpty()) {
            return text;
        }
        // Delete right-to-left so earlier spans' offsets stay valid.
        botSpans.sort((a, b) -> Integer.compare(b.start(), a.start()));
        StringBuilder stripped = new StringBuilder(text);
        for (SimpleXMessageCodec.MentionSpan span : botSpans) {
            stripped.delete(span.start(), span.start() + span.length());
            // Whitespace normalization at the removal junction: a
            // mid-text mention leaves the spaces on both of its sides
            // adjacent — collapse them to one so "hey @bot do x"
            // becomes "hey do x", not "hey  do x".
            int junction = span.start();
            if (junction > 0 && junction < stripped.length()
                    && Character.isWhitespace(stripped.charAt(junction - 1))
                    && Character.isWhitespace(stripped.charAt(junction))) {
                stripped.deleteCharAt(junction);
            }
        }
        return stripped.toString().strip();
    }
}
