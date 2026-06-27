package app.zcat.infochat.messaging.impl.simplex;

/**
 * The bot's SimpleX identity — its queue address, the cryptographically
 * anchored identifier SimpleX surfaces for the bot's account (decision
 * D32, {@code docs/spec/messaging.md} §Per-adapter trust level and
 * identity). The queue address is the bot's stable {@code contact_id}
 * on SimpleX and the D10 trust anchor for this adapter. Derived at
 * adapter startup by querying the running simplex-chat for the bot's
 * own address ({@code SimpleXAdapter#deriveAndAdoptIdentity}) — never
 * an operator-typed property.
 *
 * @param queueAddress the bot's SimpleX queue address; never null.
 */
public record SimpleXIdentity(String queueAddress) {

    /**
     * Minimum character length of a well-formed SimpleX queue address.
     * A queue address is a URL-safe base64 encoding of the SMP recipient
     * queue id ({@code docs/design/06-messaging.md} §6.4.4: the id formats
     * are "URL-safe base64 (the SimpleX queue address itself)"). That id
     * is a fixed 24-byte value, whose URL-safe-base64 form is 32
     * characters, so this floor admits every real derived queue address.
     * The prior 43-char value assumed a 32-byte id and so rejected every
     * real 32-char address, leaving the adapter unable to start (M1-504).
     *
     * <p>The floor still rejects a short mistyped operator value — a
     * human-readable slug below 32 chars. It does NOT reject a 36-char
     * Signal ACI pasted into the SimpleX slot: the ACI is longer than the
     * 32-char real address, so no floor that admits the real address can
     * exclude it (M1-504 refine — an accepted, minor M1-208 fail-fast
     * regression documented in the ticket Notes).</p>
     */
    private static final int MIN_QUEUE_ADDRESS_LENGTH = 32;

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
