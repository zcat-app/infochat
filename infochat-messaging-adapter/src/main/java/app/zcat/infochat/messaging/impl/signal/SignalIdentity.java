package app.zcat.infochat.messaging.impl.signal;

/**
 * The bot's Signal identity — its ACI (Account Credential Identifier),
 * the UUID cryptographically bound to the Signal identity keys and
 * surfaced by signal-cli as {@code mentionUuid}. The ACI is the bot's
 * stable {@code contact_id} on Signal (it does not change when the
 * phone number does), making it the D10 trust anchor for this adapter.
 * Sourced from operator config
 * ({@code infochat.adapters.signal.bot-aci}) by Provider-side wiring.
 *
 * @param aci the bot's ACI; never null.
 */
public record SignalIdentity(String aci) {
}
