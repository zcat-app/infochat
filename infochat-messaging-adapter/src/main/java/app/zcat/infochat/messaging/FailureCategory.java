package app.zcat.infochat.messaging;

/**
 * Categorisation of {@link MessagingException} failures per
 * {@code docs/spec/messaging.md} §Failure handling. Every
 * {@link MessagingAdapter#send}, {@link MessagingAdapter#update}, and
 * {@link MessagingAdapter#finalizeMessage} failure raised by an adapter is
 * tagged with one of these two categories at throw site.
 *
 * <p>An adapter that cannot tell the two apart MUST default to
 * {@link #PERMANENT} — silently looping a permanent failure is a
 * worse failure mode than aborting an occasionally-transient one. The
 * default is encoded as a throw-site discipline (no zero-arg
 * {@link MessagingException} constructor) so the choice is forced at
 * the source rather than re-derived by callers.</p>
 *
 * <p><b>Transport-state classification matrix.</b> A transport state
 * that can arise in more than one adapter classifies identically in
 * every adapter — one rule, asserted against both production adapters
 * by a shared contract test:</p>
 *
 * <ul>
 *   <li><b>Interrupted while awaiting a send/update/finalize ack</b> →
 *       {@link #TRANSIENT}. The interrupt is a local thread-lifecycle
 *       event (caller cancellation or shutdown), not a verdict on the
 *       transport or the message; the connection is presumed healthy.</li>
 *   <li><b>Connection closed while a call awaits its ack</b>
 *       (closed-before-ack) → {@link #PERMANENT}. Retrying against a
 *       closed connection cannot succeed; the transport must be rebuilt
 *       (supervised restart or {@code start()}) before a retry could
 *       matter.</li>
 *   <li><b>Response/ack timeout on a live connection</b> →
 *       {@link #TRANSIENT}.</li>
 *   <li><b>Write/transmit failure on the wire</b> → {@link #TRANSIENT}
 *       — the supervised restart recovers the connection.</li>
 *   <li><b>Transport error response whose error code is missing or
 *       unparseable</b> → {@link #PERMANENT} (the default-to-PERMANENT
 *       rule above: a transient cause that cannot be proven is treated
 *       as permanent).</li>
 * </ul>
 */
public enum FailureCategory {

    /**
     * Network timeout, TCP/TLS reset, transport rate-limit response,
     * transport "try again later" / 5xx-style signal, ephemeral
     * signing-server unavailability. Retried per the policy in
     * {@code docs/spec/messaging.md} §Failure handling.
     */
    TRANSIENT,

    /**
     * User has blocked the bot, group no longer exists or the bot is
     * no longer a member, recipient identity has been rotated/revoked,
     * message rejected as a policy violation by the transport. Aborts
     * the affected reply, never retried.
     */
    PERMANENT
}
