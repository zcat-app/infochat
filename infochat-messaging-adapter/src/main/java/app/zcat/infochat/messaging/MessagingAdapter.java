package app.zcat.infochat.messaging;

import org.jspecify.annotations.NonNull;

/**
 * Transport contract between Provider and a messaging backend
 * (SimpleX, Signal, in-memory test harness, future protocols). One
 * Provider may host any non-empty subset of the production adapters
 * simultaneously (decision D46) — each adapter instance is a CDI bean
 * with an adapter-type qualifier, and Provider's routing layer
 * dispatches by inbound adapter.
 *
 * <p>The v1 SPI surface is shaped per
 * {@code docs/spec/messaging.md} §Required SPI surface and
 * {@code docs/design/06-messaging.md} §6.2. The minimum surface every
 * adapter implements: an adapter-selection {@link #name()}, an
 * instance-level {@link #trustLevel()}, a {@link #capabilities()}
 * accessor, a strongly-typed {@link #assertIdentity} for inbound
 * messages, {@link #send} / {@link #update} / {@link #finalize} for
 * outbound replies, {@link #setTyping} for the typing-indicator
 * pulse, and {@link #setInboundHandler} for Provider to register its
 * inbound dispatch callback.</p>
 *
 * <p>Lifecycle methods ({@code start(InboundHandler)} / {@code stop()})
 * and group-membership probing ({@code groupExists}) are deferred to
 * the first concrete adapter (SimpleX / Signal) and to the groups
 * milestone (T2-F) respectively — speculative SPI surface for
 * non-existent callers would violate the engineering rules'
 * "no defensive code for impossible scenarios" corollary against
 * speculative API.</p>
 */
public interface MessagingAdapter {

    /**
     * Stable, adapter-selection name (e.g. {@code "simplex"},
     * {@code "signal"}, {@code "inmemory"}). Provider's adapter
     * registry uses this string to match the configured
     * {@code infochat.adapters} list against bean instances; the
     * cross-adapter isolation invariant from
     * {@code docs/spec/messaging.md} §Per-adapter trust level uses
     * {@code (adapter, contact_id)} as the join key, so the literal
     * here MUST match the registry's expected key.
     *
     * @return the adapter's stable selection name; never null.
     */
    String name();

    /**
     * Returns the adapter's static capability description. The
     * returned record is immutable and may be cached by callers.
     * Provider validates {@code supportsMarkdownLinks == false} at
     * adapter registration (startup) and fails fast on a true
     * declaration — a per-message check would miss adapters that
     * silently upgrade.
     *
     * @return the adapter's capability flags; never null.
     */
    CapabilityFlags capabilities();

    /**
     * The adapter instance's trust level — {@link AdapterTrustLevel#HIGH}
     * for cryptographically-anchored identities (SimpleX, Signal),
     * {@link AdapterTrustLevel#LOW} for weaker assertions (the
     * in-memory test double's default). Provider rejects
     * {@code LOW}-trust assertions for admin-bearing paths unless the
     * operator explicitly opts in.
     *
     * @return the adapter's trust level; never null.
     */
    AdapterTrustLevel trustLevel();

    /**
     * Strongly-typed identity assertion for one inbound message. The
     * returned {@link Identity}'s {@code contactId} is the
     * authorization-bearing identifier (decision D10) — implementations
     * MUST NOT trust {@code displayName}.
     *
     * @param msg the inbound message; never null.
     * @return the asserted sender identity; never null.
     */
    Identity assertIdentity(@NonNull InboundMessage msg);

    /**
     * Send a new message to the given scope.
     *
     * @param msg the outbound message to deliver; never null.
     * @return an opaque {@link MessageHandle} the caller can pass to
     *         {@link #update} / {@link #finalize}. Never null. The
     *         handle is valid only within this adapter, in-process —
     *         see {@link MessageHandle} for the full invariant list.
     * @throws MessagingException on transport failure; the exception's
     *         {@link MessagingException#category()} reports whether the
     *         caller should retry (TRANSIENT) or abort (PERMANENT).
     */
    MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException;

    /**
     * Replace the visible body of a previously-sent message.
     * Adapters whose underlying protocol does not support edits
     * ({@link CapabilityFlags#supportsMessageEdit} false) signal "not
     * supported" — the exact failure shape is impl-defined; the
     * progress notifier falls back gracefully.
     *
     * @param handle the handle returned by {@link #send}; never null.
     * @param body   the new body text; never null.
     * @throws MessagingException on transport failure.
     */
    void update(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException;

    /**
     * Mark the message as final and apply the closing body. For
     * adapters with edit support this is one last
     * {@link #update}-shaped write; for others it is the only
     * {@link #send} that ever happens. Always called in a try/finally
     * so placeholders are never left dangling. After {@code finalize}
     * any further {@link #update} on the same handle MUST throw a
     * {@link MessagingException} with category
     * {@link FailureCategory#PERMANENT}.
     *
     * @param handle the handle returned by {@link #send}; never null.
     * @param body   the final body text; never null.
     * @throws MessagingException on transport failure or on attempting
     *         to mutate an already-finalized handle.
     */
    void finalize(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException;

    /**
     * Show or clear the typing indicator for a scope. No-op for
     * adapters with {@link CapabilityFlags#supportsTypingIndicator}
     * false. Not declared {@code throws MessagingException} because
     * typing pulses are best-effort UI hints — a transport failure
     * here is silently absorbed by the adapter.
     *
     * @param scope  the scope to toggle typing on; never null.
     * @param typing true to start, false to stop.
     */
    void setTyping(@NonNull ScopeRef scope, boolean typing);

    /**
     * Register the callback Provider uses to receive inbound
     * messages. Provider sets exactly one handler per adapter
     * instance at startup; replacing a handler is undefined for v1.
     *
     * @param handler the inbound-message callback; never null.
     */
    void setInboundHandler(@NonNull InboundHandler handler);

    /**
     * Functional callback Provider registers with each
     * {@link MessagingAdapter}. Pure SPI; concrete dispatching to
     * command handlers / chat mode lives in Provider.
     *
     * <p>v1 ships only the message-delivery shape; the group
     * membership-event callbacks
     * ({@code onUserJoinedGroup} / {@code onUserLeftGroup}) from
     * {@code docs/design/06-messaging.md} §6.2 are deferred to T2-F
     * with the rest of group-scope dispatch.</p>
     */
    @FunctionalInterface
    interface InboundHandler {
        /**
         * Handle one inbound message.
         *
         * @param msg the inbound message; never null. Its
         *            {@link InboundMessage#sender() sender},
         *            {@link InboundMessage#scope() scope}, and
         *            mention-stripped {@link InboundMessage#text() text}
         *            are all populated by the adapter.
         */
        void onMessage(@NonNull InboundMessage msg);
    }
}
