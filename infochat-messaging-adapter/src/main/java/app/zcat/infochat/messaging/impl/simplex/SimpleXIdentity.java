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

    /**
     * Minimum character length of a well-formed SimpleX queue address.
     * A queue address is a URL-safe base64 encoding of a cryptographic
     * queue identifier ({@code docs/design/06-messaging.md} §6.4.4: the
     * id formats are "URL-safe base64 (the SimpleX queue address
     * itself)"). The smallest such identifier is a 32-byte value, whose
     * base64 form is 43 characters; this floor rejects the kind of short
     * mistyped operator value the fail-fast sentence targets — a
     * human-readable slug, or a 36-character Signal ACI pasted into the
     * SimpleX slot — while admitting any real queue address.
     */
    private static final int MIN_QUEUE_ADDRESS_LENGTH = 43;

    /**
     * Whether {@code queueAddress} is a well-formed SimpleX queue
     * address. Invoked registry-side ({@code AdapterRegistry}
     * bootstrap-admin parse gate) to reject an operator-mistyped
     * {@code infochat.adapters.simplex.admin} value at startup before it
     * seeds an admin row no real contact can ever claim — the fail-fast
     * promise in {@code docs/spec/deployment.md} §Operator inputs item 2.
     *
     * <p>Reuses the adapter's existing queue-address character-set
     * validator ({@link SimpleXMessageCodec#isValidQueueAddressId})
     * rather than inventing a parallel grammar, and adds the
     * cryptographic-length floor ({@link #MIN_QUEUE_ADDRESS_LENGTH}) —
     * the character set alone admits short kebab-case slugs, which the
     * length floor is what rejects.</p>
     */
    public static boolean isWellFormed(String queueAddress) {
        return queueAddress.length() >= MIN_QUEUE_ADDRESS_LENGTH
                && SimpleXMessageCodec.isValidQueueAddressId(queueAddress);
    }
}
