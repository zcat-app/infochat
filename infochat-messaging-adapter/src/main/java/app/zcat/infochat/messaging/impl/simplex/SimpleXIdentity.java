package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import java.nio.file.Path;

/**
 * The bot's SimpleX identity — its queue address, the cryptographically
 * anchored identifier SimpleX surfaces for the bot's account (decision
 * D32, {@code docs/spec/messaging.md} §Per-adapter trust level and
 * identity). The queue address is the bot's stable {@code contact_id}
 * on SimpleX and the D10 trust anchor for this adapter.
 *
 * @param queueAddress the bot's SimpleX queue address; never null.
 */
public record SimpleXIdentity(@NonNull String queueAddress) {

    /**
     * Resolve the bot's queue address from the simplex-chat data
     * directory as a pure function over that directory's on-disk
     * identity material. The simplex-chat data-directory format (and
     * the parser needed to extract the queue address) are owned by
     * M1-103; this skeleton only declares the entry point.
     *
     * @param dataDir the simplex-chat data directory; never null.
     * @return the resolved {@link SimpleXIdentity}.
     */
    public static SimpleXIdentity resolve(@NonNull Path dataDir) {
        throw new UnsupportedOperationException(
                "resolving the bot queue address from simplex-chat data is implemented in M1-103");
    }
}
