package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Instant;

/**
 * Translates SimpleX group-scope candidates into {@link InboundMessage}
 * dispatches on the registered {@link MessagingAdapter.InboundHandler}.
 * Pure dispatch — no I/O, no threading; the
 * {@link SimpleXWebSocketClient} listener thread invokes
 * {@link #onGroupCandidate} for every group-scope {@code newChatItem}
 * frame the codec surfaces as {@link SimpleXMessageCodec.GroupCandidate}.
 *
 * <p>Bot mention is the D10 trust anchor for group mode per
 * {@code docs/spec/messaging.md} §Required SPI surface — Receive: a
 * group message reaches Provider only when the frame's mention list
 * carries a queue address that byte-equals the bot's per-adapter queue
 * address. {@link SimpleXMentionParser#botMentioned} owns the
 * comparison; display-name matching is never used. The decision is
 * made exactly once, here — no other code path in this adapter
 * delivers a group-scope {@link InboundMessage}, so the silent-drop
 * path for un-mentioned group messages is the only escape from this
 * class.</p>
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

    private final SimpleXIdentity botIdentity;
    private final MessagingAdapter.InboundHandler inboundHandler;

    /**
     * @param botIdentity    the bot's per-adapter SimpleX identity; its
     *                       queue address is the D10 trust anchor for
     *                       mention recognition. Never null.
     * @param inboundHandler the downstream callback that receives
     *                       mentioned group messages. Production wires
     *                       this to {@link SimpleXAdapter#onInbound},
     *                       which re-reads Provider's volatile
     *                       inbound-handler field on each dispatch so a
     *                       {@link MessagingAdapter#setInboundHandler}
     *                       call after {@link SimpleXAdapter#start}
     *                       still routes correctly. Never null.
     */
    SimpleXGroupHandler(@NonNull SimpleXIdentity botIdentity,
                        MessagingAdapter.@NonNull InboundHandler inboundHandler) {
        this.botIdentity = botIdentity;
        this.inboundHandler = inboundHandler;
    }

    /**
     * Decide whether one group-scope candidate becomes a delivered
     * {@link InboundMessage} or is silently dropped. The mention check
     * is the only gate — security_relevant: the silent path MUST stay
     * silent so an attacker spamming a group cannot generate log
     * pressure that reveals the bot is reading their messages.
     */
    void onGroupCandidate(SimpleXMessageCodec.@NonNull GroupCandidate gc) {
        if (!SimpleXMentionParser.botMentioned(gc.mentionQueueAddresses(),
                botIdentity.queueAddress())) {
            return;
        }
        Instant now = Instant.now();
        Identity sender = new Identity(gc.senderContactId(), gc.senderDisplayName(), now);
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Group(gc.adapterGroupId()),
                gc.text(),
                now,
                gc.adapterMessageId());
        inboundHandler.onMessage(msg);
    }
}
