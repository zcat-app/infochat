package io.infochat.messaging;

/**
 * Closed enumeration of stage events the {@link ProgressNotifier}
 * publishes during a long-running handler ({@code /summary}, periodic
 * digest, chat agent). The seven values match
 * {@code docs/spec/messaging.md} §Progress notifications verbatim.
 *
 * <p>This enum is intentionally closed. The notifier renders each
 * stage via a deterministic localization-bundle key (decision D43);
 * adding an eighth value without a matching bundle key would either
 * crash at runtime or silently produce an empty user-visible string.
 * Both failure modes are bad — extending this enum is a spec amendment
 * that pulls a bundle key alongside it.</p>
 */
public enum ProgressStage {
    /** Handler accepted the request; placeholder message sent. */
    STARTED,
    /** Deterministic SQL retrieval (post fetch, filter) in progress. */
    RETRIEVING,
    /** LLM call (cluster summary, chat reply, digest header) in progress. */
    GENERATING,
    /** Translating bot prose to the scope's language. */
    TRANSLATING,
    /** Closing out (formatting, finalize edit). */
    FINALIZING,
    /** Terminal success state. */
    COMPLETED,
    /** Terminal failure state. */
    FAILED
}
