package io.infochat.messaging;

/**
 * Identity-assertion strength of a {@link MessagingAdapter} instance.
 * Per {@code docs/design/06-messaging.md} §6.2 trust level is an
 * adapter-instance property, accessed via
 * {@link MessagingAdapter#trustLevel()}, NOT a static capability flag —
 * the SimpleX and Signal adapters are HIGH; the in-memory test double
 * defaults to LOW and lets tests opt into HIGH explicitly via a
 * test-only secondary constructor.
 *
 * <p>Provider rejects {@link #LOW}-trust identity assertions for
 * admin-bearing paths unless the operator has explicitly opted in
 * (per {@code docs/spec/messaging.md} §Per-adapter trust level and
 * identity). Encoding the default LOW makes accidental privilege
 * escalation through a test harness impossible by default.</p>
 */
public enum AdapterTrustLevel {

    /** Cryptographically-anchored contact id (SimpleX queue address, Signal ACI). */
    HIGH,

    /** Weaker identity assertion; operator (or test) must explicitly opt in. */
    LOW
}
