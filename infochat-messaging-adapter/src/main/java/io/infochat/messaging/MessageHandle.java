package io.infochat.messaging;

/**
 * Opaque token returned by {@link MessagingAdapter#send} that lets the
 * same caller subsequently {@code update} or {@code finalize} the same
 * visible message. The contents are adapter-defined — for v1 a single
 * adapter-chosen string suffices, but the field shape is NOT part of
 * the SPI contract.
 *
 * <p>Invariants (verbatim from {@code docs/spec/messaging.md}
 * §Message handles):</p>
 * <ul>
 *   <li>Callers <strong>MUST NOT</strong> persist a handle to the
 *       database.</li>
 *   <li>Callers <strong>MUST NOT</strong> pass a handle between service
 *       instances.</li>
 *   <li>Callers <strong>MUST NOT</strong> inspect or rely on the
 *       contents of {@link #opaqueValue}; treat the record as a sealed
 *       token. The accessor exists because Java records expose all
 *       components; reaching through it defeats the invariant the type
 *       represents.</li>
 *   <li>A handle is valid only within the originating adapter,
 *       in-process. Holding it in memory for a single request's
 *       processing (placeholder → updates → finalize) is the intended
 *       use.</li>
 * </ul>
 *
 * <p>The opacity is enforced by review and convention, not the type
 * system — a determined caller can still call {@link #opaqueValue}.
 * This Javadoc is the single point of authority.</p>
 */
public record MessageHandle(String opaqueValue) {
}
