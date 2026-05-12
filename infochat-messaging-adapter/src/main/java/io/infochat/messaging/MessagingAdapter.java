package io.infochat.messaging;

/**
 * Transport contract between Provider and a messaging backend
 * (SimpleX, Signal, in-memory test harness, future protocols). One
 * Provider may host any non-empty subset of the production adapters
 * simultaneously (decision D46) — each adapter instance is a CDI bean
 * with an adapter-type qualifier, and Provider's routing layer
 * dispatches by inbound adapter.
 *
 * <p>The v1 SPI surface here is the load-bearing minimum from
 * {@code docs/spec/messaging.md} §Required SPI surface: a capability
 * accessor, send / update / finalize, and inbound-handler
 * registration. The full spec obligation is broader and listed under
 * <em>Future-surface</em> below; each concrete adapter ticket
 * (SimpleX, Signal, InMemory) evolves the SPI additively as it threads
 * its impl needs through.</p>
 *
 * <h2>Future-surface (spec-required, not yet method-shape-frozen)</h2>
 * The following methods are spec-required for every adapter but the
 * Java signature is intentionally deferred to the first impl ticket
 * that needs it, so the parameter shapes can be informed by a real
 * transport rather than guessed:
 * <ul>
 *   <li><strong>Identity assertion.</strong> Translates inbound wire
 *       messages to a stable, cryptographically-anchored contact id.
 *       Per-adapter identity shape lives in design notes; the
 *       SPI commitment lands with the first concrete adapter.</li>
 *   <li><strong>Typing indicator.</strong>
 *       {@code setTyping(scope, bool)}; optional per spec, drives
 *       typing pulses around long-running requests.</li>
 *   <li><strong>Membership events.</strong>
 *       {@code user_joined_group} / {@code user_left_group}; optional
 *       per spec, gated by the {@code supportsMembershipEvents}
 *       capability flag.</li>
 *   <li><strong>Transport-layer inbound size cap.</strong> Adapter-side
 *       drop of oversize inbound messages before delivery to Provider;
 *       the application-level chat-mode body cap is a second
 *       defense.</li>
 * </ul>
 */
public interface MessagingAdapter {

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
     * Send a new message to the given scope.
     *
     * @param scope the destination scope (DM contact id or group id);
     *              never null.
     * @param body  the message body in plain text per decision D30;
     *              never null.
     * @return an opaque {@link MessageHandle} the caller can pass to
     *         {@link #update} / {@link #finalize}. Never null. The
     *         handle is valid only within this adapter, in-process —
     *         see {@link MessageHandle} for the full invariant list.
     */
    MessageHandle send(String scope, String body);

    /**
     * Replace the visible body of a previously-sent message.
     * Adapters whose underlying protocol does not support edits
     * ({@link CapabilityFlags#supportsMessageEdit} false) signal "not
     * supported" — the exact failure shape is impl-defined; the
     * progress notifier falls back gracefully.
     *
     * @param handle the handle returned by {@link #send}; never null.
     * @param body   the new body text; never null.
     */
    void update(MessageHandle handle, String body);

    /**
     * Mark the message as final and apply the closing body. For
     * adapters with edit support this is one last
     * {@link #update}-shaped write; for others it is the only
     * {@link #send} that ever happens. Always called in a try/finally
     * so placeholders are never left dangling.
     *
     * @param handle the handle returned by {@link #send}; never null.
     * @param body   the final body text; never null.
     */
    void finalize(MessageHandle handle, String body);

    /**
     * Register the callback Provider uses to receive inbound
     * messages. Provider sets exactly one handler per adapter
     * instance at startup; replacing a handler is undefined for v1.
     * The handler receives one inbound message per call —
     * {@code (scope, contactId, body)}. Group messages are delivered
     * only when the bot is {@code @mentioned}; mention-recognition is
     * anchored to the bot's per-adapter cryptographic contact id per
     * spec §Required SPI surface — Receive (decision D10).
     *
     * @param handler the inbound-message callback; never null.
     */
    void setInboundHandler(InboundHandler handler);

    /**
     * Functional callback Provider registers with each
     * {@link MessagingAdapter}. Pure SPI; concrete dispatching to
     * command handlers / chat mode lives in Provider.
     */
    @FunctionalInterface
    interface InboundHandler {
        /**
         * Handle one inbound message.
         *
         * @param scope     the inbound scope (DM contact id or group
         *                  id); never null.
         * @param contactId the cryptographically-anchored contact id
         *                  of the sender; never null.
         * @param body      the message body (mention stripped); never
         *                  null.
         */
        void handle(String scope, String contactId, String body);
    }
}
