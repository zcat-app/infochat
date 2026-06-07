package app.zcat.infochat.messaging.impl.simplex;

/**
 * The bot's SimpleX identity — its queue address, the cryptographically
 * anchored identifier SimpleX surfaces for the bot's account (decision
 * D32, {@code docs/spec/messaging.md} §Per-adapter trust level and
 * identity). The queue address is the bot's stable {@code contact_id}
 * on SimpleX and the D10 trust anchor for this adapter. Sourced from
 * operator config ({@code infochat.adapters.simplex.bot-queue-address})
 * by Provider-side wiring.
 *
 * @param queueAddress the bot's SimpleX queue address; never null.
 */
public record SimpleXIdentity(String queueAddress) {
}
