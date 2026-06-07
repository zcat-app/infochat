package app.zcat.infochat.messaging.impl.signal;


import java.nio.file.Path;

/**
 * The bot's Signal identity — its ACI (Account Credential Identifier),
 * the UUID cryptographically bound to the Signal identity keys and
 * surfaced by signal-cli as {@code mentionUuid}. The ACI is the bot's
 * stable {@code contact_id} on Signal (it does not change when the
 * phone number does), making it the D10 trust anchor for this adapter.
 *
 * @param aci the bot's ACI; never null.
 */
public record SignalIdentity(String aci) {

    /**
     * Resolve the bot's ACI from the signal-cli data directory as a pure
     * function over that directory's on-disk account state. The
     * signal-cli account-state format (and the JSON dependency needed to
     * parse it) are owned by M1-107; this skeleton only declares the
     * entry point.
     *
     * @param dataDir the signal-cli data directory; never null.
     * @return the resolved {@link SignalIdentity}.
     */
    public static SignalIdentity resolve(Path dataDir) {
        throw new UnsupportedOperationException(
                "resolving the bot ACI from signal-cli account state is implemented in M1-107");
    }
}
