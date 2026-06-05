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
