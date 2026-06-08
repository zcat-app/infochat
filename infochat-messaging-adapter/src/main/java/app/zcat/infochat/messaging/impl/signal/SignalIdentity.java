package app.zcat.infochat.messaging.impl.signal;

import java.util.UUID;

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

    /**
     * Whether {@code aci} is a well-formed Signal ACI — a canonical
     * lowercase UUID string (the ACI is the UUID Signal binds to its
     * identity keys, surfaced by {@code signal-cli} as
     * {@code mentionUuid}; {@code docs/design/06-messaging.md} §6.9).
     * Invoked registry-side ({@code AdapterRegistry} bootstrap-admin
     * parse gate) to reject an operator-mistyped
     * {@code infochat.adapters.signal.admin} value at startup before it
     * seeds an admin row no real contact can ever claim — the fail-fast
     * promise in {@code docs/spec/deployment.md} §Operator inputs item 2.
     *
     * <p>Round-trips through {@link UUID#fromString} and back to the
     * canonical string so a non-canonical form (uppercase, braces,
     * truncated groups) or a non-UUID value is rejected rather than
     * silently coerced.</p>
     */
    public static boolean isWellFormed(String aci) {
        try {
            return UUID.fromString(aci).toString().equals(aci);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
