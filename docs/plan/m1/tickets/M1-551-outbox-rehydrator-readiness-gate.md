---
id: M1-551
title: OutboxRehydrator waits for eval-queue downstream readiness (F-live-3)
status: pending
created: 2026-07-03
last_updated: 2026-07-04
reopens:
  - date: 2026-07-04
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: [M1-553]
    reason: M1-553 (wiring-test fix) is done, unblocking verify; mid-drain
      SRMSG00034 evidence (2 occurrences) now filed in the ticket body for
      the design decision at review.
escalations:
  - date: 2026-07-03
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation developer finding: acceptance item 4
      prescribes "a Mockito stub for EvalQueueProducer", but Mockito is
      not on the collector test classpath and no module in the repo
      declares it (verified via dependency:list and repo-wide grep).
      Satisfying the acceptance literally requires adding a test-scope
      dependency to infochat-collector/pom.xml — a 4th touched file
      against files_budget: 3.
  - date: 2026-07-03
    reason: verify-blocked-by-main-regression
    reviewer_verdict_excerpt: |
      N/A — round-1 full-suite verify (target/m1-tick-test-M1-551-r1.log)
      is red in infochat-llm-adapter: 5 failures (LlamacppWiringTest x4,
      RemoteLlmWiringTest x1). Root cause is M1-550's four new 4-llm.sh
      prompt_timing reads exhausting the tests' scripted stdin (EOF ->
      exit 1 under set -e). Pre-existing on main: M1-550 merged under the
      inert-diff rule without a verify run, and the module DAG builds
      llm-adapter before collector, so M1-551's collector-only diff
      cannot be implicated. Implementation itself is complete and
      unit-green (checkpoint commit on the branch). User chose defer
      onto M1-553 (the wiring-test fix).
revisions:
  - date: 2026-07-04
    reason: design extension at reopen — user chose the per-emit readiness
      gate ("Extend: per-emit gate", 2026-07-04) after two mid-drain
      SRMSG00034 occurrences falsified the "has requests once" premise
      (see §Mid-drain flake evidence)
    snapshot:
      status: pending
      acceptance_item_2_at_snapshot: |
        OutboxRehydrator.rehydrate() gates its FIRST emit on downstream
        readiness — the gate runs only after the first non-empty chunk
        loads (an empty backlog never polls), and polls
        hasDownstreamRequests() up to readiness-max-attempts (default 100)
        times with a fixed readiness-poll-millis (default 100) sleep
        between attempts.
      design_premise_at_snapshot: |
        Once the subscriber is active its virtual-thread dispatch requests
        continuously, so "has requests once" is a faithful readiness
        signal for the whole drain.
  - date: 2026-07-03
    reason: budget-breach rework (user picked refine, hand-rolled-stub variant)
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      acceptance_item_4_at_snapshot: |
        New unit tests (field-injection seam — OutboxRehydrator's
        package-private @Inject fields are assignable without CDI, with a
        Mockito stub for EvalQueueProducer) cover, proceeds once
        hasDownstreamRequests flips true after k polls; attempts exhausted
        throws IllegalStateException whose message names both config keys; an
        empty backlog never calls hasDownstreamRequests.
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
  - OutboxRehydrator.rehydrate() gates EVERY emit on downstream
    readiness: before each emit, when hasDownstreamRequests() is false,
    it polls hasDownstreamRequests() up to
    infochat.collector.outbox.readiness-max-attempts (default 100) times
    with a fixed
    infochat.collector.outbox.readiness-poll-millis (default 100) sleep
    between attempts. An empty backlog performs no emits and therefore
    no polls. The loop is attempt-counted, never a wall-clock
    comparison, so the injectable-Clock rule is untriggered.
  - Exhausting the attempts (on any emit) throws IllegalStateException
    naming the eval-queue channel and both config keys — startup still
    fails loudly, but with an actionable message instead of the opaque
    SRMSG00034.
  - New unit tests (field-injection seam — OutboxRehydrator's
    package-private @Inject fields are assignable without CDI, with a
    hand-rolled stub for EvalQueueProducer — no new test dependency;
    the repo's test-double convention is hand-rolled) cover: proceeds once
    hasDownstreamRequests flips true after k polls; a mid-drain stall
    (readiness false again at a later emit) is waited out by the same
    bounded poll; attempts exhausted throws IllegalStateException whose
    message names both config keys; an empty backlog never calls
    hasDownstreamRequests.
  - OutboxRehydratorIT, OutboxRehydratorPaginationIT, and
    Stage1WorkerEmitterThreadIT are preserved unchanged and green.
  - mvn verify is green.
test_plan:
  adds:
    - "OutboxRehydratorReadinessTest: emit proceeds after readiness flips
      true on the k-th poll (k < max-attempts)"
    - "OutboxRehydratorReadinessTest: a mid-drain stall (readiness drops
      false before a later emit) is waited out by the same bounded poll"
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

Gate EVERY emit of the rehydration drain on subscriber readiness via the
MicroProfile `Emitter.hasRequests()` API: before each emit, if the
subscriber has no outstanding requests, poll in a bounded attempt-counted
loop. The healthy-path cost is one cheap readiness check per emit (true →
zero sleeps); a stalled consumer is waited out instead of overflowing the
128-item default buffer.

Originally the gate ran only before the FIRST emit, justified by "once
the subscriber is active its virtual-thread dispatch requests
continuously" — falsified 2026-07-03/04 by two mid-drain SRMSG00034
occurrences under full-suite host load (§Mid-drain flake evidence);
extended to per-emit at reopen (user decision 2026-07-04).

Rejected alternatives:

- **Unbounded/larger buffer** — trades the crash for unbounded startup
  memory; the pagination design exists to prevent exactly that.
- **Priority reordering** — the subscription completes asynchronously on a
  non-startup thread; no `@Priority` value orders against it.
- **Catch-and-retry on the overflow exception** — SmallRye throws a plain
  `IllegalStateException`; classifying it by message text is brittle, and
  the buffer already holds 128 items by the time it fires. Polling
  `hasRequests()` before the first emit is precise and API-supported.

Defaults 100 attempts × 100 ms cap the wait at ~10 s per stalled emit —
orders of magnitude above the observed race window (subscription completes
within the same startup second) while still failing a genuinely-broken
wiring loudly.

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

## Mid-drain flake evidence — MUST be surfaced at reopen, before review

The first-emit readiness gate this ticket implements does NOT fully close
F-live-3. Two independent full-suite runs have shown `SRMSG00034:
Insufficient downstream requests` firing MID-DRAIN — at test-method time,
with the subscriber long live — which a gate that polls
`hasDownstreamRequests()` only before the FIRST emit cannot prevent:

1. **2026-07-03**, M1-553 round-1 verify, on unmodified `main` collector
   code: `OutboxRehydratorPaginationIT.
   rehydratesLargeRawBacklogWithoutUnboundedListAllocation` errored with
   SRMSG00034 thrown from `EvalQueueProducer.emit` (2017-row backlog; the
   consumer lagged the tight emit loop under full-suite host load). Red
   log preserved at `.scratch/m1-tick-test-M1-553-r1-flake-attempt1.log`.
2. **2026-07-04**, M1-552 round-1 verify, again on unmodified `main`
   collector code (the M1-552 diff touches only infochat-provider + docs):
   the same test errored with the same SRMSG00034 drain-backpressure
   failure. The red log was overwritten by the green re-run at the same
   round path, so the record is the failure diagnosis from that session
   (failing test + SRMSG00034 message confirmed from the failsafe report
   before the re-run); the re-run passed with zero code changes.

Design implication: "has requests once" was justified by "the
virtual-thread consumer requests continuously" — true at startup pace,
but a saturated host can stall the consumer long enough for the 128-item
default buffer to fill mid-drain. At reopen, before implementation
review: either extend the design (escalate → refine; e.g. per-emit
readiness or bounded retry on overflow) or explicitly accept the residual
mid-drain flake and file a follow-up ticket for it. The ticket must not
merge implying the first-emit gate fully closes F-live-3.

**Resolution (2026-07-04, reopen):** the user chose to extend the design —
the gate now runs per emit (refine recorded under `revisions:`, design and
acceptance updated above). The accept-residual-flake path was declined;
no follow-up ticket is needed.
