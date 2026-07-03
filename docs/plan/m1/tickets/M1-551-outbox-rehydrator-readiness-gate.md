---
id: M1-551
title: OutboxRehydrator waits for eval-queue downstream readiness (F-live-3)
status: pending
created: 2026-07-03
last_updated: 2026-07-03
blocked_by: []
files_budget: 3
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "@OnOverflow strategy changes or an unbounded emitter buffer — an
    unbounded buffer defeats the memory-bounded pagination property
    OutboxRehydratorPaginationIT pins (the whole point of the 500-row
    keyset pages)"
  - mp.messaging.* configuration (none exists today; none is added)
  - the other eval-queue emit paths (FetchScheduler, NostrStreamSource,
    Kind6Handler, Stage1Worker.reEmitStaleRaw) — they all run post-startup
    when the subscriber is live; only the @PostConstruct rehydration races
    subscription wiring
  - Stage1Worker changes, including the "ArC container not initialized /
    wrong class loader" log noise — that is virtual-thread teardown fallout
    of the same failed boot, not a separate defect
  - reproducing the lost-race interleaving inside a @QuarkusTest — the
    subscriber wires up before test methods run, so the interleaving cannot
    be forced there; the unit-level gate tests are the proof (see test_plan)
acceptance:
  - EvalQueueProducer exposes a hasDownstreamRequests() method delegating
    to the injected Emitter's hasRequests().
  - OutboxRehydrator.rehydrate() gates its FIRST emit on downstream
    readiness — the gate runs only after the first non-empty chunk loads
    (an empty backlog never polls), and polls
    hasDownstreamRequests() up to
    infochat.collector.outbox.readiness-max-attempts (default 100) times
    with a fixed
    infochat.collector.outbox.readiness-poll-millis (default 100) sleep
    between attempts. The loop is attempt-counted, never a wall-clock
    comparison, so the injectable-Clock rule is untriggered.
  - Exhausting the attempts throws IllegalStateException naming the
    eval-queue channel and both config keys — startup still fails loudly,
    but with an actionable message instead of the opaque SRMSG00034.
  - New unit tests (field-injection seam — OutboxRehydrator's
    package-private @Inject fields are assignable without CDI, with a
    Mockito stub for EvalQueueProducer) cover, proceeds once
    hasDownstreamRequests flips true after k polls; attempts exhausted
    throws IllegalStateException whose message names both config keys; an
    empty backlog never calls hasDownstreamRequests.
  - OutboxRehydratorIT, OutboxRehydratorPaginationIT, and
    Stage1WorkerEmitterThreadIT are preserved unchanged and green.
  - mvn verify is green.
test_plan:
  adds:
    - "OutboxRehydratorReadinessTest: emit proceeds after readiness flips
      true on the k-th poll (k < max-attempts)"
    - "OutboxRehydratorReadinessTest: attempts exhausted → ISE naming
      readiness-max-attempts and readiness-poll-millis"
    - "OutboxRehydratorReadinessTest: empty RAW backlog performs zero
      readiness polls"
  preserves:
    - OutboxRehydratorIT (RAW/READY mix, ordering, re-run idempotency)
    - OutboxRehydratorPaginationIT (memory-bounded large-backlog paging)
    - Stage1WorkerEmitterThreadIT (virtual-thread dispatch hop)
spec_refs:
  - docs/spec/architecture.md §Pipelines
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D22
---

## Context

**F-live-3 (live host, 4 observations 2026-07-02..03):** restarting the
collector with a RAW backlog intermittently fails startup:
`OutboxRehydrator.onStartup` → `EvalQueueProducer.emit` →
`SRMSG00034: Insufficient downstream requests to emit item`, exit 1. The
immediate retry booted clean all 4 times; two restarts with a DRAINED
backlog (2026-07-03 evening) never failed.

**Root cause (code survey 2026-07-03):** `OutboxRehydrator` is
`@Startup @Priority(300)`; its `@PostConstruct` synchronously emits one key
per RAW row. The emitter (`@Channel("eval-queue") @Broadcast`, no
`@OnOverflow`, no `mp.messaging.*` config anywhere) gets SmallRye's default
bounded BUFFER (128 items), which throws SRMSG00034 when full with zero
outstanding downstream requests. `Stage1Worker`'s
`@Incoming("eval-queue") @RunOnVirtualThread` subscription is established
asynchronously during the same startup window:

- Race lost (subscription not yet active): zero requests, buffer fills at
  item ~129, the next emit throws out of `@PostConstruct`, boot fails.
- Race won: the virtual-thread consumer requests continuously; even a 3.7k
  backlog drains — hence retry-always-clean.
- Backlog < ~128 rows: cannot overflow regardless of the race — hence
  drained-backlog restarts never fail.

Severity: operational only, no data risk (outbox at-least-once — posts stay
RAW and the next boot re-rehydrates), but unattended collector restarts are
untrustworthy exactly after a stop under heavy ingest.

## Design

Gate the rehydrator's first emit on subscriber readiness via the
MicroProfile `Emitter.hasRequests()` API, polled in a bounded
attempt-counted loop. Once the subscriber is active its virtual-thread
dispatch requests continuously, so "has requests once" is a faithful
readiness signal for the whole drain.

Rejected alternatives:

- **Unbounded/larger buffer** — trades the crash for unbounded startup
  memory; the pagination design exists to prevent exactly that.
- **Priority reordering** — the subscription completes asynchronously on a
  non-startup thread; no `@Priority` value orders against it.
- **Catch-and-retry on the overflow exception** — SmallRye throws a plain
  `IllegalStateException`; classifying it by message text is brittle, and
  the buffer already holds 128 items by the time it fires. Polling
  `hasRequests()` before the first emit is precise and API-supported.

Defaults 100 attempts × 100 ms cap the wait at ~10 s — orders of magnitude
above the observed race window (subscription completes within the same
startup second) while still failing a genuinely-broken wiring loudly.

## Implementation anchors (surveyed 2026-07-03)

- `infochat-collector/.../outbox/OutboxRehydrator.java`: `@PostConstruct
  onStartup` (~l.161–164), rehydrate loop emitting per chunk (~l.188–213),
  `CONFIG_KEY_PAGE_SIZE` constant + `@ConfigProperty(defaultValue=...)`
  pattern to mirror for the two new keys (~l.137–146), package-private
  `@Inject` fields (the unit-test seam).
- `infochat-collector/.../outbox/EvalQueueProducer.java`: emitter field
  (~l.48–51), `emit` (~l.60–62) — `hasDownstreamRequests()` lands beside it.
- Existing tests: `OutboxRehydratorIT`, `OutboxRehydratorPaginationIT`,
  `Stage1WorkerEmitterThreadIT` (all preserved).

## Not security_relevant — justification

No trust boundary is touched: the change orders two internal startup
components of the Collector (no user-facing API). Failure mode remains
fail-fast at boot; no input parsing, no authorization, no data exposure.
