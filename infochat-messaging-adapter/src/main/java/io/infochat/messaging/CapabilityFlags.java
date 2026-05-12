package io.infochat.messaging;

import java.time.Duration;

/**
 * Static description of what a {@link MessagingAdapter} supports —
 * the "Capability flags (minimum set)" enumerated in
 * {@code docs/spec/messaging.md} §Capability flags. The record shape is
 * closed deliberately: each transport declares one immutable
 * {@code CapabilityFlags} instance and Provider's startup-fail-fast
 * validation (rejection of {@code supportsMarkdownLinks == true}) can
 * inspect it without a virtual call.
 *
 * <p>Adding a new flag is a spec amendment. Provider treats an unknown
 * flag as "not supported" by default; flags here represent the v1
 * floor.</p>
 *
 * @param trustLevel                cryptographically-anchored identity
 *                                  ({@link TrustLevel#HIGH}) or weaker
 *                                  ({@link TrustLevel#LOW}). Provider
 *                                  rejects {@code LOW} identity
 *                                  assertions unless the operator opts
 *                                  in.
 * @param supportsCodeFormatting    true when code spans render as
 *                                  monospace; false renders raw
 *                                  backticks. URLs are always rendered
 *                                  bare (decision D30) — this flag does
 *                                  NOT widen to markdown links.
 * @param supportsMarkdownLinks     MUST be false for every v1 adapter.
 *                                  Provider validates this at adapter
 *                                  registration (startup) and fails
 *                                  fast on a true declaration.
 * @param supportsMessageEdit       true when the adapter can edit a
 *                                  previously sent message; drives
 *                                  in-place progress updates.
 * @param minEditInterval           adapter-imposed floor between edits
 *                                  on the same message. The progress
 *                                  notifier honors
 *                                  {@code max(adapterMin, systemFloor)}.
 * @param supportsTypingIndicator   true when typing-on/off pulses are
 *                                  available.
 * @param supportsMentionByContactId true when the protocol carries an
 *                                  {@code @mention} anchored to the
 *                                  mentioned user's cryptographic
 *                                  contact id. Required-true for any
 *                                  adapter that exposes group mode in
 *                                  v1; display-name string matching is
 *                                  never an acceptable fallback.
 * @param supportsMembershipEvents  true when the adapter exposes native
 *                                  user-joined / user-left signals.
 *                                  When false, Provider relies on
 *                                  permanent-delivery-failure-driven
 *                                  cleanup and does NOT synthesise
 *                                  membership events from inactivity.
 */
public record CapabilityFlags(
        TrustLevel trustLevel,
        boolean supportsCodeFormatting,
        boolean supportsMarkdownLinks,
        boolean supportsMessageEdit,
        Duration minEditInterval,
        boolean supportsTypingIndicator,
        boolean supportsMentionByContactId,
        boolean supportsMembershipEvents) {

    /**
     * Identity-assertion strength for a messaging transport. Nested
     * inside {@link CapabilityFlags} to keep the SPI module's file
     * count to one record + one enum here; a future spec amendment
     * that widens the trust dimension can promote {@code TrustLevel}
     * to a top-level type without breaking the {@code trustLevel}
     * component name.
     */
    public enum TrustLevel {
        /** Cryptographically-anchored contact id (SimpleX queue address, Signal ACI). */
        HIGH,
        /** Weaker identity assertion; operator must explicitly opt in. */
        LOW
    }
}
