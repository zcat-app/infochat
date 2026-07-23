package app.zcat.infochat.messaging;

import app.zcat.infochat.messaging.metrics.AdapterMetrics;

import java.util.Optional;

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
 * accessor, a {@link #isWellFormedContactId} format probe for
 * operator-configured contact ids, {@link #send} / {@link #update} /
 * {@link #finalizeMessage} for outbound replies, {@link #setTyping}
 * for the typing-indicator pulse, and {@link #setInboundHandler} for
 * Provider to register its inbound dispatch callback. Transport lifecycle is {@link #start()} /
 * {@link #stop()} — no-op defaults so transportless adapters (the
 * in-memory test double) are unaffected.</p>
 *
 * <p><b>Identity assertion (no separate SPI method).</b> There is no
 * standalone identity-assertion call on this interface. Each adapter
 * asserts the sender's cryptographically-anchored contact id at
 * wire-decode time — the point where the transport's verified identity
 * material lives (SimpleX queue address, Signal ACI) — and carries the
 * result as the {@link Identity} on every {@link InboundMessage} it
 * dispatches (see {@link InboundMessage#sender()}). The contact id is
 * the authorization-bearing identifier (decision D10); adapters MUST
 * NOT trust {@code displayName}. An adapter that cannot anchor the id
 * to a keypair MUST declare {@link #trustLevel()}
 * {@link AdapterTrustLevel#LOW}; Provider gates LOW-trust adapters at
 * registration (the operator opt-in), not per message. A message whose
 * identity cannot be asserted is dropped at decode, before dispatch —
 * the earliest boundary with the most context. See
 * {@code docs/spec/messaging.md} §Required SPI surface and
 * {@code docs/design/06-messaging.md} §6.2.</p>
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
     * Whether the given string is a well-formed contact id for this
     * adapter's transport — a pure format check (no network, no
     * persistence): canonical lowercase UUID for a Signal ACI,
     * URL-safe-base64 queue address of cryptographic length for
     * SimpleX, free-form for the in-memory test adapter. Provider
     * calls this to validate operator-configured contact ids
     * ({@code infochat.adapters.<name>.admin}) at startup, BEFORE any
     * row derived from the value is written — the fail-fast promise
     * in {@code docs/spec/deployment.md} §Operator inputs item 2
     * ("each value MUST be parseable by its own adapter").
     *
     * <p>Deliberately abstract, no permissive default: a default
     * returning true would let a future adapter silently skip
     * validation, which is exactly the gap this method closes.
     * An adapter whose contact-id format is genuinely free-form
     * states that explicitly by returning true.</p>
     *
     * @param contactId the candidate contact id; never null.
     * @return true iff the value is parseable as this adapter's
     *         contact-id format.
     */
    boolean isWellFormedContactId(String contactId);

    /**
     * Canonicalize an operator-supplied contact id to the exact byte
     * form this adapter reports for that contact's inbound messages, so
     * a value configured in a richer operator-facing form still
     * byte-matches inbound. Run on
     * {@code infochat.adapters.<name>.admin} BEFORE
     * {@link #isWellFormedContactId} and before the value seeds an admin
     * row, at both the registry parse gate and {@code AdminBootstrap}
     * (the same call, idempotent on an already-canonical value), so the
     * validated value and the seeded value cannot diverge.
     *
     * <p>The default returns {@code contactId} unchanged — correct for
     * adapters whose operator-facing form already IS the canonical
     * contact id (Signal ACI, the in-memory test adapter). Unlike
     * {@link #isWellFormedContactId} (deliberately abstract — a missing
     * impl would be skipped validation, a security gap), a missing
     * canonicalization is the identity transform, i.e. "the operator
     * must supply the bare id", which is exactly the safe behavior
     * before this method existed; so a permissive default is the safer,
     * smaller choice and keeps link-less adapters out of the override.
     * {@code SimpleXAdapter} overrides it so an operator can paste a
     * full SimpleX contact link.</p>
     *
     * @param contactId the operator-supplied contact id; never null.
     * @return the canonical contact id for this adapter; never null. A
     *         value that cannot be canonicalized is returned unchanged
     *         so {@link #isWellFormedContactId} makes the accept/reject
     *         decision.
     */
    default String canonicalizeContactId(String contactId) {
        return contactId;
    }

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
     * Register the handler that receives group invitations. Parallel to
     * {@link #setMembershipEventHandler}: an adapter that surfaces invitations
     * stores the handler and invokes {@link InvitationHandler#onInvitation}
     * directly (the same direct-invocation shape the inbound path uses). The
     * accept decision lives in Provider (the adapter queries no DB, D10) — a
     * registered-inviter invitation is accepted by Provider calling
     * {@link #joinGroup} back; an unregistered/banned inviter is simply not
     * joined. Default no-op so adapters without group-invitation support — the
     * in-memory test double and the Signal adapter (native membership events) —
     * are unaffected and need no change (M1-515).
     *
     * @param handler the invitation callback; never null.
     */
    default void setGroupInvitationHandler(InvitationHandler handler) {
        // No-op — overridden by adapters that surface group invitations.
    }

    /**
     * Join the group identified by {@code adapterGroupId}, accepting a pending
     * invitation surfaced through {@link #setGroupInvitationHandler}. Provider
     * calls this back only for an invitation from a registered, non-banned
     * inviter (the D47 registered-only auto-join gate). Default no-op so
     * adapters without a group-join transport — the in-memory test double and
     * the Signal adapter — are unaffected and need no change (M1-515).
     *
     * @param adapterGroupId the adapter-native id of the group to join.
     * @throws MessagingException on transport failure issuing the join.
     */
    default void joinGroup(String adapterGroupId) throws MessagingException {
        // No-op — overridden by adapters that can join a group by id.
    }

    /**
     * The bot's own shareable onboarding contact for this adapter — the
     * value a new person enters into their app to reach the bot (a SimpleX
     * contact URL, a Signal number). Fetched live where the transport can
     * be queried, so the value reflects the contact as it currently is,
     * not a boot-time snapshot ({@code docs/spec/messaging.md} §Required
     * SPI surface).
     *
     * <p>The returned value is display-only: it is surfaced once in a
     * command reply and MUST never be logged at any level or persisted to
     * any file (D37). Default is empty — "this adapter has no shareable
     * contact" — so adapters without one need no change.</p>
     *
     * @return the shareable contact, or empty when the adapter has none.
     * @throws MessagingException when a live transport query for the
     *         value fails or times out — a transient condition, distinct
     *         from the empty "unsupported" answer.
     */
    default Optional<String> connectContact() throws MessagingException {
        return Optional.empty();
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
     * Whether this adapter's transport supervisor has reached a terminal
     * failed state — the subprocess crash-restart loop exhausted its cap
     * and gave up. Polled by Provider's readiness probe: a started-then-
     * terminally-failed adapter must report not-ready, otherwise a
     * deployment stays "ready" with a permanently dead adapter (the
     * startup-only {@code AdapterConnectionState} snapshot never sees the
     * later failure). Default false so transportless adapters (the
     * in-memory test double) and adapters whose supervisor is still
     * running are reported live.
     *
     * @return true iff the transport supervisor has terminally failed.
     */
    default boolean supervisorTerminallyFailed() {
        return false;
    }

    /**
     * Cumulative count of inbound messages dropped because the adapter's
     * bounded inbound dispatch queue was full (the drop-newest overflow
     * policy). Exposed for operational observability — Provider surfaces
     * it on the readiness payload so a silently-overflowing queue is
     * visible without log scraping. Monotonic within a process; resets
     * only on restart. Default 0 for adapters without a bounded inbound
     * queue.
     *
     * @return the number of inbound messages dropped on queue overflow.
     */
    default long droppedInboundCount() {
        return 0L;
    }

    /**
     * Whether the adapter's transport is currently connected — the live
     * value behind the {@code adapter.connection.status} gauge
     * ({@code docs/design/06-messaging.md} §6.12; 1 connected, 0
     * disconnected). Unlike {@link #supervisorTerminallyFailed()} (the
     * gave-up latch), this reflects transient outages too: an adapter
     * mid-reconnect reports false. Default true so transportless
     * adapters (the in-memory test double) — which have no wire to lose
     * — always read connected. The default applies to transportless
     * adapters only: a transport adapter MUST override this method, or
     * a real disconnect silently reads as a false-green readiness
     * payload once this signal feeds the readiness check.
     *
     * @return true iff the transport is up and able to carry messages.
     */
    default boolean connected() {
        return true;
    }

    /**
     * Current depth of the adapter's bounded inbound dispatch queue —
     * the live value behind the {@code adapter.inbound.queue.size}
     * gauge ({@code docs/design/06-messaging.md} §6.12). Companion to
     * {@link #droppedInboundCount()}: that counter reports overflow
     * after the fact, this gauge shows the pressure building toward it.
     * Default 0 for adapters without a bounded inbound queue.
     *
     * @return the number of inbound deliveries queued awaiting dispatch.
     */
    default int inboundQueueDepth() {
        return 0;
    }

    /**
     * Hand the adapter the deployment's {@link AdapterMetrics} emission
     * point for its transport-internal signals — the ones only the
     * adapter can classify, like the §6.3.8/§6.4.5 edit-failure
     * fallback counters. Called once per activated adapter by
     * {@link AdapterMetrics#bindAdapter} during registration, the same
     * late-binding shape as {@link #setInboundHandler}. Default no-op
     * so adapters without transport-internal emissions are unaffected.
     *
     * @param metrics the deployment-wide emission point; never null.
     */
    default void bindMetrics(AdapterMetrics metrics) {
        // No-op — overridden by adapters with transport-internal emissions.
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

    /**
     * Functional callback Provider registers with each
     * {@link MessagingAdapter} to receive group invitations (M1-515).
     * Parallel to {@link MembershipHandler}.
     *
     * <p>Threading contract: like {@link InboundHandler}, the handler may
     * block — it reads the {@code users} table and may issue an outbound
     * {@link MessagingAdapter#joinGroup} — so adapters MUST NOT invoke it on
     * the thread that reads the transport socket.</p>
     */
    @FunctionalInterface
    interface InvitationHandler {
        /**
         * Handle one received group invitation.
         *
         * @param invitation the invitation; never null.
         */
        void onInvitation(GroupInvitation invitation);
    }

    /**
     * A group invitation surfaced by an adapter (M1-515).
     * {@code adapterGroupId} is the adapter-native id to {@link #joinGroup};
     * {@code inviterContactId} is the inviting user's adapter-native contact
     * id, which Provider resolves to a registered user for the D47 auto-join
     * gate. Immutable; the adapter makes no accept decision (D10 — that
     * decision lives in Provider).
     */
    record GroupInvitation(String adapterGroupId, String inviterContactId) {
    }
}
