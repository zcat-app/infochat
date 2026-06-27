---
id: M1-466
title: Per-host outbound pacing in FetchScheduler
status: done
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerHostPacingIT.java
  - docs/design/01-architecture.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  # Single global pacing interval in v1 — no per-host / per-kind override.
  # Stream kinds (nostr) — not in the polled fetch loop; untouched.
  # No change to per-kind interval gating or the D42 failure ladder.
  # No true sub-heartbeat async/delayed dispatch — the synchronous tick model stays.
  # NOT a bootstrap-sources.json data edit (removing/staggering xcancel feeds is a
  # separate operator action, not this feature).
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/**
  - prod/runtime/**
  - prod/config/**
acceptance:
  - FetchSchedulerHostPacingIT.sameHostDueSourcesPaceAcrossHeartbeats passes
  - FetchSchedulerHostPacingIT.distinctHostDueSourcesAllDispatchInOneHeartbeat passes
  - A new tunable infochat.fetch.host-min-interval (Quarkus duration, or the `off`
    sentinel to disable — mirroring infochat.linking.interval) gates the minimum
    time between outbound fetch requests to the same host; the decision reads the
    injected Clock (FetchScheduler's existing clock), not an inline now()
  - FetchScheduler dispatches at most one request per host per host-min-interval
    window; a due source whose host was requested within the window is deferred to
    a later heartbeat and retried on subsequent heartbeats until its host frees —
    its fetch is delayed within the kind's cycle, never dropped nor postponed a
    whole kind-interval
  - Due sources on distinct hosts are unaffected — a heartbeat whose due sources are
    all on different hosts dispatches them all
  - All pre-existing FetchScheduler ITs stay green (collector test profile sets
    infochat.fetch.host-min-interval=off)
  - mvn verify is green
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerHostPacingIT.java
  modifies:
    - infochat-collector/src/main/resources/application.properties
    - infochat-collector/src/test/resources/application.properties
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 461
      removed: 41
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-27
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-466.md
---

# M1-466: Per-host outbound pacing in FetchScheduler

## Context

When a kind is due, `FetchScheduler` enumerates **all** active sources of
that kind and ticks each in one pass (`docs/design/01-architecture.md`
§1.3.1) — a burst. Several sources commonly share one host: the ~22 nitter
feeds are all `rss.xcancel.com`, which rate-limits the burst and returns
`403 Forbidden` (an HTML body the RSS parser then rejects as
`RssFeedParseException`), tripping each source to `status='failed'` after the
D42 ladder. The rate limit is a property of the **host**, not the kind, so the
correct fix paces requests per host: never send more than one request to a
given host within a configured window, spreading a crowded host's sources over
several heartbeats while leaving sources on other hosts untouched. Keying the
throttle on host (not kind) also stays correct if a second Nitter mirror is
added later — each host gets its own independent budget.

## Acceptance

- New tunable `infochat.fetch.host-min-interval` — a Quarkus duration, or the
  `off` sentinel to disable, mirroring the existing `infochat.linking.interval`
  convention. Prod default: a sane politeness value (e.g. `20s`). The collector
  **test profile** (`src/test/resources/application.properties`) sets it `off`
  so existing ITs keep their current single-tick behavior.
- The pacing decision (has this host's window elapsed?) is read from the
  **injected `Clock`** FetchScheduler already holds (`clock.instant()`), per the
  §"Injectable time in decision logic" rule — `FetchSchedulerClockIT` is the
  reference for the fixed-clock test shape.
- `FetchScheduler` dispatches **at most one request per host per
  host-min-interval window**. A due source whose host was requested within the
  window is **deferred** to a later heartbeat and **retried on subsequent
  heartbeats** until its host frees — its fetch is *delayed within the kind's
  fetch cycle*, never dropped and never postponed a whole kind-interval.
- Due sources on **distinct hosts are unaffected**: a heartbeat whose due
  sources are all on different hosts dispatches them all in that heartbeat.
- `FetchSchedulerHostPacingIT.sameHostDueSourcesPaceAcrossHeartbeats` — K active
  sources sharing one host, all due; with a fixed Clock advanced between
  `onTick` calls, they dispatch one-per-window across successive heartbeats and
  all are eventually fetched.
- `FetchSchedulerHostPacingIT.distinctHostDueSourcesAllDispatchInOneHeartbeat` —
  pacing does not throttle different hosts.
- All pre-existing FetchScheduler ITs stay green.
- `mvn verify` is green.

## Out-of-scope

- **One global pacing interval** in v1 — no per-host or per-kind override knob.
  If that's wanted later it's a follow-up, negotiated via `escalate → refine`.
- **Stream kinds (nostr)** never enter the polled fetch loop, so they are not
  touched; `infochat-collector/.../stream/**` is fenced off.
- **Per-kind interval gating and the D42 failure ladder** are unchanged — this
  ticket only paces *within* a due cycle; it does not change *when* a kind
  becomes due or how failures escalate.
- **No true sub-heartbeat dispatch.** The scheduler stays synchronous and
  heartbeat-driven; pacing is heartbeat-quantized (see §Notes). Async/delayed
  per-request timers are explicitly not in scope.
- **No bootstrap-sources.json edit.** Removing or thinning the xcancel feeds is a
  separate operator data action, not this feature.
- If any pre-existing FetchScheduler IT must change, it is a test-integrity
  matter — name it and the new expectation here; the intended approach (test
  profile `off`) is designed to avoid touching them at all.

## Notes

- **Mechanism direction (for the plan-writer):** an in-memory
  `Map<String, Instant>` of host → earliest-next-allowed-request, updated as each
  source dispatches; host extracted from the source `identifier` via
  `java.net.URI(identifier).getHost()` (all polled kinds carry URL identifiers).
- **The hard part — deferred-source re-evaluation.** Today `lastTickByKind`
  marks a kind not-due for a full interval after one `onTick`, which would
  *strand* a deferred source until the next kind-interval. The implementer must
  keep a kind eligible for dispatch on the next heartbeat while it still has
  undispatched due sources this cycle. Two candidate shapes to weigh: (a) move
  dueness to **per-source** (`now - last_fetch_at >= interval`) so deferral is
  naturally retried, or (b) keep per-kind cycle gating plus an in-memory
  **pending-source set** drained across heartbeats. This is the core design
  decision the plan should settle before coding.
- **Heartbeat quantization (a real bound to document, not fight).** All sources
  in one `onTick` share a single `now`, so the practical granularity is ≤1
  dispatch per host *per heartbeat* when the window ≤ heartbeat; the window only
  spans multiple heartbeats when set larger than the heartbeat. With the 1m
  heartbeat, a crowded host drains at ~1/min — which is exactly the
  burst-avoidance we want. True 15s spacing would need delayed async dispatch,
  which §Out-of-scope excludes.
- **Config convention:** mirror `infochat.linking.interval=off` /
  `infochat.reeval.poll-interval=off` (collector test profile) for the disable
  sentinel and the `@ConfigProperty` parsing shape.
- Relevant design: `docs/design/01-architecture.md` §1.3.1 (polled Fetcher →
  outbox pipeline) — add a short paragraph on per-host pacing there. Adjacent
  code/reference: `FetchScheduler.onTick` / `enumerateActiveSourcesByKinds` /
  `tickOnce`; `FetchSchedulerClockIT` for the injected-clock test pattern.
