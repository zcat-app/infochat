package app.zcat.infochat.messaging;


import java.time.Duration;

/**
 * Static description of what a {@link MessagingAdapter} supports —
 * the "Capability flags (minimum set)" enumerated in
 * {@code docs/spec/messaging.md} §Capability flags and shaped per
 * {@code docs/design/06-messaging.md} §6.2. The record is closed:
 * each transport declares one immutable {@code CapabilityFlags}
 * instance and Provider's startup-fail-fast validation (rejection of
 * {@code supportsMarkdownLinks == true}) can inspect it without a
 * virtual call.
 *
 * <p>Trust level is intentionally NOT a capability flag — it is an
 * adapter-instance property accessed via the
 * {@link AdapterTrustLevel}-returning method on
 * {@link MessagingAdapter}, so a single adapter implementation
 * (notably the in-memory test double) can ship two trust postures
 * from the same class without two parallel capability records. The
 * M1-007c nested {@code TrustLevel} enum was removed in this
 * evolution; see {@link AdapterTrustLevel}.</p>
 *
 * <p>Adding a new flag is a spec amendment. Provider treats an unknown
 * flag as "not supported" by default; flags here represent the v1
 * floor.</p>
 *
 * @param supportsMentionByContactId true when the protocol carries an
 *                                   {@code @mention} anchored to the
 *                                   mentioned user's cryptographic
 *                                   contact id. Required-true for any
 *                                   adapter that exposes group mode in
 *                                   v1; display-name string matching is
 *                                   never an acceptable fallback.
 * @param supportsMembershipEvents   true when the adapter exposes
 *                                   native user-joined / user-left
 *                                   signals. When false, Provider
 *                                   relies on permanent-delivery-
 *                                   failure-driven cleanup and does NOT
 *                                   synthesise membership events from
 *                                   inactivity.
 * @param supportsCodeFormatting     true when single-backtick spans
 *                                   render as monospace; false renders
 *                                   the raw backticks. URLs are always
 *                                   rendered bare (decision D30) — this
 *                                   flag does NOT widen to markdown
 *                                   links. v1 has no consumer that
 *                                   branches on this flag (no renderer
 *                                   reads it yet); it is retained because
 *                                   it is spec-pinned (CLAUDE.md §Key
 *                                   conventions) and a v2 richer-rendering
 *                                   path will consume it.
 * @param supportsMarkdownLinks      MUST be false for every v1 adapter.
 *                                   Provider validates this at adapter
 *                                   registration (startup) and fails
 *                                   fast on a true declaration.
 * @param maxInboundMessageBytes     transport-layer first-defense cap
 *                                   on inbound message size per design
 *                                   §6.2.2. Tighter than the
 *                                   application-level chat-mode body
 *                                   cap; messages over this size are
 *                                   dropped by the adapter before
 *                                   delivery to Provider.
 * @param maxSendsPerSecond          rate: token-bucket cap on
 *                                   {@link MessagingAdapter#send}
 *                                   calls per second averaged over a
 *                                   one-second window. Production
 *                                   adapters pace outbound transmits to
 *                                   this via {@link OutboundRateLimiter}
 *                                   (design §6.3.6). Outbound concurrency
 *                                   is bounded separately by the
 *                                   transport's one-outstanding-send
 *                                   rule, not by a capability field.
 * @param supportsMessageEdit        true when the adapter can edit a
 *                                   previously-sent message; drives
 *                                   in-place progress updates.
 * @param supportsTypingIndicator    true when typing-on/off pulses are
 *                                   available.
 * @param minEditInterval            adapter-imposed floor between
 *                                   edits on the same message; the
 *                                   progress notifier honors
 *                                   {@code max(adapterMin, systemFloor)}.
 *                                   {@link Duration#ZERO} when the
 *                                   transport imposes no floor (e.g.,
 *                                   the in-memory test double).
 */
public record CapabilityFlags(
        boolean supportsMentionByContactId,
        boolean supportsMembershipEvents,
        boolean supportsCodeFormatting,
        boolean supportsMarkdownLinks,
        int maxInboundMessageBytes,
        int maxSendsPerSecond,
        boolean supportsMessageEdit,
        boolean supportsTypingIndicator,
        Duration minEditInterval) {
}
