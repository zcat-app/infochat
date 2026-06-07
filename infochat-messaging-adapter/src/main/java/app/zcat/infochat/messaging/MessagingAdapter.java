package app.zcat.infochat.messaging;


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
 * messages, {@link #send} / {@link #update} / {@link #finalizeMessage}
 * for outbound replies, {@link #setTyping} for the typing-indicator
 * pulse, and {@link #setInboundHandler} for Provider to register its
 * inbound dispatch callback. Transport lifecycle is {@link #start()} /
 * {@link #stop()} — no-op defaults so transportless adapters (the
 * in-memory test double) are unaffected.</p>
 *
 * <p>Group-membership probing ({@code groupExists}) stays deferred to
 * the groups milestone (T2-F) — speculative SPI surface for
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
    Identity assertIdentity(InboundMessage msg);

    /**
     * Send a new message to the given scope.
     *
     * @param msg the outbound message to deliver; never null.
     * @return an opaque {@link MessageHandle} the caller can pass to
     *         {@link #update} / {@link #finalizeMessage}. Never null. The
     *         handle is valid only within this adapter, in-process —
     *         see {@link MessageHandle} for the full invariant list.
     * @throws MessagingException on transport failure; the exception's
     *         {@link MessagingException#category()} reports whether the
     *         caller should retry (TRANSIENT) or abort (PERMANENT).
     */
    MessageHandle send(OutboundMessage msg) throws MessagingException;

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
    void update(MessageHandle handle, String body) throws MessagingException;

    /**
     * Mark the message as final and apply the closing body. For
     * adapters with edit support this is one last
     * {@link #update}-shaped write; for others it is the only
     * {@link #send} that ever happens. Always called in a try/finally
     * so placeholders are never left dangling. After
     * {@code finalizeMessage} any further {@link #update} on the same
     * handle MUST throw a {@link MessagingException} with category
     * {@link FailureCategory#PERMANENT}.
     *
     * <p>Named {@code finalizeMessage} (not {@code finalize}) so the
     * SPI method cannot be confused with — or overload —
     * {@link Object#finalize()}.</p>
     *
     * @param handle the handle returned by {@link #send}; never null.
     * @param body   the final body text; never null.
     * @throws MessagingException on transport failure or on attempting
     *         to mutate an already-finalized handle.
     */
    void finalizeMessage(MessageHandle handle, String body) throws MessagingException;

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
    void setTyping(ScopeRef scope, boolean typing);

    /**
     * Register the callback Provider uses to receive inbound
     * messages. Provider sets exactly one handler per adapter
     * instance at startup; replacing a handler is undefined for v1.
     *
     * @param handler the inbound-message callback; never null.
     */
    void setInboundHandler(InboundHandler handler);

    /**
     * Register the callback Provider uses to receive membership
     * events. Parallel to {@link #setInboundHandler}: Provider sets
     * exactly one handler per adapter instance at startup; replacing a
     * handler is undefined for v1. Adapters that support membership
     * events override this to store the handler and dispatch events by
     * invoking {@link MembershipHandler#onEvent} directly — the same
     * direct-invocation shape the inbound path uses; there is
     * deliberately no interface-level dispatch method, so every
     * adapter delivers through this one registered-handler path
     * (D47 requires uniform membership semantics across adapters).
     * Default is no-op so adapters without group support are
     * unaffected.
     *
     * @param handler the membership-event callback; never null.
     */
    default void setMembershipEventHandler(MembershipHandler handler) {
        // No-op — overridden by adapters that fire membership events.
    }

    /**
     * Start the adapter's transport (spawn the backing subprocess,
     * open the wire connection, probe readiness). Called once by
     * Provider's startup driver after handler registration; a failure
     * here is isolated per adapter (one adapter's failed start must
     * not prevent the others from starting). Default is no-op so
     * transportless adapters (the in-memory test double) are
     * unaffected.
     *
     * @throws MessagingException on transport startup failure.
     */
    default void start() throws MessagingException {
        // No-op — overridden by adapters with a transport to bring up.
    }

    /**
     * Stop the adapter's transport (close the wire connection, tear
     * down the backing subprocess). Counterpart to {@link #start()};
     * called by Provider's shutdown path. Idempotent: stopping an
     * adapter that never started (or already stopped) is a no-op.
     * Default is no-op so transportless adapters are unaffected.
     */
    default void stop() {
        // No-op — overridden by adapters with a transport to tear down.
    }

    /**
     * Functional callback Provider registers with each
     * {@link MessagingAdapter}. Pure SPI; concrete dispatching to
     * command handlers / chat mode lives in Provider.
     *
     * <p>Threading contract: the handler may block and may send
     * replies synchronously from {@link #onMessage} (including calls
     * back into the same adapter's {@link MessagingAdapter#send}
     * path). Adapters MUST NOT invoke it on the thread that reads
     * the transport socket — a handler blocked awaiting its reply's
     * ack would deadlock against the reader thread that has to
     * deliver that ack.</p>
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
        void onMessage(InboundMessage msg);
    }

    /**
     * Functional callback Provider registers with each
     * {@link MessagingAdapter} to receive group membership events.
     */
    @FunctionalInterface
    interface MembershipHandler {
        /**
         * Handle one membership event.
         *
         * @param event the membership event; never null.
         */
        void onEvent(MembershipEvent event);
    }
}
