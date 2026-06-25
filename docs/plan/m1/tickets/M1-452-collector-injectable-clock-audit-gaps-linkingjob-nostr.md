---
id: M1-452
title: "Complete the collector's injectable-Clock migration: LinkingJob window cutoffs + NostrStreamSource since-cursor SQL floor (audit-missed sites)"
status: done
created: 2026-06-25
last_updated: 2026-06-25
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobClockIT.java
complexity: medium
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - "The 5 partition-scan workers (M1-448), PartitionPruner / DigestRetryService / FetchScheduler (M1-449), the security-timing trio (M1-447), and ReEvaluationJob (M1-444) — all already converted. Do NOT re-touch them."
  - "Any change to the linking lookback / semantic-window SIZES or the Nostr since-cursor partition-pruning depth. This is a determinism refactor: under the production Clock.systemUTC() (which equals the DB now() the floor uses today) behaviour is preserved. Changing a window size is a separate ticket."
  - "NostrStreamSource's connection state machine, dedup, relay health, or any time read OTHER than the maxPublishedAtEpochSeconds(...) since-cursor partition-pruning floor — those already use the injected Clock field and are correct."
  - "Re-classifying or rewriting docs/plan/m1/now-clock-audit.md. The audit was the M1-447 deliverable; this ticket fixes the two sites it overlooked, it does not re-audit the corpus."
acceptance:
  - "LinkingJob obtains its current instant from an injected java.time.Clock (ThrottledAdminNotifier.systemUtcClock(); field initialised `= Clock.systemUTC()`, CDI overrides at runtime), sampled ONCE per job invocation and bound into all four decision-gating window cutoffs currently reading inline Instant.now() (LinkingJob.java:170 driving-set scan, :220 entity-candidate window + dedup, :289 lookback cutoff, :290 semantic-candidate window). No inline Instant.now() remains in LinkingJob: `grep -n 'Instant.now()' infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java` returns no match."
  - "A new deterministic IT LinkingJobClockIT pins the Clock via QuarkusMock.installMockForType(Clock.fixed(...), Clock.class) and asserts a post sitting exactly on one window boundary is included/excluded according to the injected instant (so the window is provably pinnable, closing the date-boundary time-bomb gap)."
  - "NostrStreamSource's since-cursor partition-pruning floor binds an app-clock instant as a query parameter (e.g. `fetched_at >= ?` with `Timestamp.from(clock.instant().minus(interval))`) using the Clock field the class ALREADY injects, instead of the in-SQL `now() - ?::INTERVAL` at NostrStreamSource.java:608. No in-SQL now() remains in that query: `grep -n 'now()' infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java` returns no match. The existing NostrStreamSource / Nostr since-cursor tests stay green (the floor is correctness-neutral pruning, so no new test is required for this site)."
  - "The new test is ADDITIVE; this ticket modifies the assertions of NO pre-existing test (no test_plan.modifies). Under the production Clock.systemUTC() every existing collector test stays green and behaviour is byte-for-byte preserved."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "LinkingJobClockIT.java — pins Clock.fixed(...) and asserts a post on a linking window boundary is included/excluded per the injected instant; proves all four LinkingJob window cutoffs are decided against the injected Clock."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 227
      removed: 18
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-06-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-452: Collector injectable-Clock audit gaps (LinkingJob + NostrStreamSource floor)

## Context

The M1-447 → M1-448 → M1-449 → M1-450 sweep moved the collector's decision-logic
time reads onto the injected `java.time.Clock`. A deep code review on 2026-06-25
found **two collector sites the M1-447 classification audit
(`docs/plan/m1/now-clock-audit.md`) missed**:

- **`LinkingJob`** — the audit lists `LinkingJob` nowhere (not in the (A) table,
  the DEFERRED list, or the already-correct list). It is the **only un-pinnable
  time-window scanner left in the collector**. Four decision-gating window cutoffs
  read wall-clock `Instant.now()` and feed SQL range filters:
  `:170` (`fetched_at >= ?` driving-set scan), `:220` (entity-candidate window +
  `created_at > ?` dedup), `:289` lookback cutoff, `:290` semantic-candidate
  window. None of `LinkingJobIT` / `LinkingJobTest` / `LinkingJobSemanticProbeIT`
  pins time, so the windows age out on a date boundary — the same time-bomb class
  as M1-398 / M1-400 / M1-444.
- **`NostrStreamSource`** — the audit lists it under "already-correct (injected
  Clock, NOT re-touched)." The class **does** inject a `Clock` field (`:337`), but
  its `maxPublishedAtEpochSeconds(...)` since-cursor partition-pruning floor at
  `:608` still uses in-SQL `now() - ?::INTERVAL` — an app-vs-DB split inside an
  otherwise-converted component. This floor is correctness-neutral (it only chooses
  which partitions to scan; a missed row can only lower the cursor and is dropped
  by dedup, with an unbounded fallback for stale sources), so it is low severity —
  but it is exactly the residual split §9 says must not exist.

Both sites are §9 category-(A) decision-logic time reads that escaped the audit;
this ticket closes them. Grouped into one collector-scoped, independently-reviewable
diff to avoid a separate one-line ticket for the Nostr floor.

## Acceptance

See the YAML `acceptance:` list. In short: `LinkingJob` reads its four window
cutoffs from the injected `Clock` (sampled once per invocation) and gains a
fixed-`Clock` boundary IT; the `NostrStreamSource` floor binds an app-clock
parameter via the already-injected `Clock` field instead of in-SQL `now()`; full
suite green; behaviour byte-for-byte preserved under `Clock.systemUTC()`.

## Out-of-scope

See the YAML `out_of_scope:` list. The already-converted workers / scheduler /
pruner / trio / `ReEvaluationJob` are not re-touched. No window-size change. Only
the `NostrStreamSource` since-cursor floor is touched in that class; its state
machine and other (already-correct) Clock reads are left alone. The audit doc is
not re-written.

## Notes

- Reference implementation: M1-448 (partition-scan workers, the SQL
  `now() - interval` → bound-cutoff conversion pattern) and M1-444
  (`ReEvaluationJob`). Pattern: `@Inject Clock clock = Clock.systemUTC();`, sample
  `clock.instant()` once per query/transaction, bind it as a parameter; pin a fixed
  `Clock` in the IT via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`.
- The Clock producer is `ThrottledAdminNotifier.systemUtcClock()`.
- `NostrStreamSource` already holds the injected `Clock` (`@Inject Clock clock`,
  `:337`); the floor conversion reuses that field — no new injection point.
- Sample the LinkingJob instant ONCE per invocation and reuse it across the four
  cutoffs so a single job pass cannot straddle two instants (intra-method skew).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-452-collector-injectable-clock-audit-gaps-linkingjob-nostr.md
```
