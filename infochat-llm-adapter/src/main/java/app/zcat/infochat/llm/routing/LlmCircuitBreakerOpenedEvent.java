package app.zcat.infochat.llm.routing;

/** One endpoint's breaker transitioned to OPEN: the CLOSED→OPEN trip or a failed
 * HALF-OPEN probe's re-open. The service modules observe it and raise the throttled
 * admin notification (docs/spec/security.md §Failure handling). */
public record LlmCircuitBreakerOpenedEvent(
    // The registry-private transport-kind enum's name() — "LLM" or "EMBEDDINGS".
    String transportKind,
    // The resolved base-url as configured; may carry userinfo, consumers redact.
    String endpoint,
    // True for a failed probe's re-open, false for the initial trip.
    boolean probeReopen) {
}
