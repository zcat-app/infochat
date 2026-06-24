---
id: M1-444
title: "fix: make ReEvaluationJob's tick time an injectable Clock so the candidate-scan window (and its IT) are deterministic instead of wall-clock-dependent"
status: done
created: 2026-06-24
last_updated: 2026-06-24
clarity_check:
  date: 2026-06-24
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobScheduledPathIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The other ~44 `= now()` / now()-in-decision-logic call sites across the other 22 production files. Those are swept to the same injectable-Clock pattern in a separate follow-up ticket (filed per the 2026-06-24 design discussion). Fix ONLY ReEvaluationJob here so this ticket stays the small, reviewable reference implementation the sweep and the new coding-style rule point at."
  - "The candidate-scan window semantics themselves: the floor stays `instant - (retention + slack)` with retention=30d, slack=2d. This ticket changes WHERE the current instant comes from (injected Clock vs SQL now()), not WHAT the window is. Production behaviour is preserved because the production Clock reads real time."
  - "The system-wide DB-clock audit-timestamp convention. ReEvaluationJob will briefly write its own timestamps from the app Clock while the other 22 files still write SQL now(); the divergence is sub-second under NTP and is reconciled by the sweep ticket. Do not 'fix' any other file here."
  - "ReEvaluationJobWindowTest and ReEvaluationJobTest: they stay green unchanged under the real production Clock (clock.instant() ≈ Instant.now()). Do not edit them; if either goes red, the refactor changed behaviour and must be corrected, not the test."
acceptance:
  - "ReEvaluationJob obtains the current instant from an injected java.time.Clock (the app-wide `@Produces @ApplicationScoped Clock` in ThrottledAdminNotifier that DigestScheduler already consumes). Each query and each write transaction samples one instant from that clock (`clock.instant()`) and binds it as a parameter; where a single statement writes two timestamps (`status_changed_at` + `last_reeval_at`) the same sampled instant binds both so they stay equal exactly as SQL `transaction_timestamp()` made them. After this change `ReEvaluationJob.java` contains zero `now()` and zero `Instant.now()` — every current-time use derives from the injected Clock (a reviewer can verify by grepping the file)."
  - "The read floors (candidate-scan, cooldown gate, depth COUNT) and every write derive from the same injected Clock, so no decision-critical value is written by one clock and compared against another — the app-vs-DB split, explicitly NOT a half-baked partial fix. The public method signatures (onTick / enumerateCandidates / processOne / checkNeedsReviewDepth / countNeedsReviewWithinScanWindow) are UNCHANGED — only private write-helper signatures gain an `Instant` parameter — so the out-of-scope ReEvaluation tests that call them directly (WindowTest, CooldownTest, ReEvaluationJobTest, ReEvalVerdictNotifyIT, InfraFailureFanOutIT) stay untouched and green."
  - "ReEvaluationJobScheduledPathIT overrides the injected Clock to a FIXED instant and keeps a FIXED FETCHED_AT positioned inside the 32-day window relative to that instant, so `capExhaustedRowReachesNeedsReviewThroughScheduledTick` and `unknownEntryPostWithInterimInjectionRollStaysEnumerated` pass deterministically on any wall-clock run date. No `Instant.now()` in the test."
  - "Production behaviour is unchanged: with the real Clock the window floor and all transitions are identical to before. This is a determinism/testability refactor, not a behaviour change."
  - "mvn -B clean verify from the repo root exits 0 (collector module green; this is the only known red on main as of 2026-06-24, so this ticket unblocks the full-suite gate for all subsequent tickets)."
test_plan:
  modifies:
    - "infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java (inject Clock; capture one instant per tick; replace every now()/Instant.now() with a bind parameter derived from it)"
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobScheduledPathIT.java (pin the injected Clock to a fixed instant; keep a fixed FETCHED_AT inside the window)"
  preserves:
    - all other tests currently green on main, including ReEvaluationJobWindowTest and ReEvaluationJobTest
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 198
      removed: 106
escalations:
  - date: 2026-06-24
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation scope change. The fix the user chose (make the
      candidate-scan time reference an injectable Clock parameter) requires
      editing ReEvaluationJob.java, which the original test-only files_scope and
      out_of_scope explicitly forbade ("about to touch a path outside
      files_scope" immediate-escalation trigger). User directed the re-scope in
      the 2026-06-24 design discussion; resolved via refine.
revisions:
  - date: 2026-06-24
    reason: "refine after budget-breach: re-scope from test-only fixture de-rot to an injectable-Clock refactor of ReEvaluationJob (user-directed, 2026-06-24 design discussion: SQL now()/Instant.now() in decision logic is a latent-bug smell; the correct fix is an injectable time parameter, done fully so the job is not split across two clocks)."
    snapshot:
      title: "test: de-rot the ReEvaluationJobScheduledPathIT scan-window fixture so the in-window post never ages out"
      files_budget: 2
      files_scope:
        - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobScheduledPathIT.java
      complexity: low
      risk: low
      security_relevant: false
      out_of_scope_summary: "test-only; ReEvaluationJob prod code declared correct and untouchable; no suite-wide sweep"
      acceptance_summary: "replace the fixed FETCHED_AT with a now()-relative Instant.now() so the in-window post never ages out"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-444: make ReEvaluationJob's tick time an injectable Clock

## Context

`ReEvaluationJobScheduledPathIT` went red on 2026-06-24: it seeds a post at a
fixed `FETCHED_AT = 2026-05-23T09:00:00Z` and expects `onTick()` to transition it
to `NEEDS_REVIEW`, but the job's candidate-scan floor `fetched_at >= now() -
(retention 30d + slack 2d)` = `now() - 32d` first crossed that seed on
2026-06-24, so the post aged out of enumeration and stayed `QUARANTINED`.

The root cause is not the fixture date — it is that the job reads the current
instant from SQL `now()` *inside its own decision logic*, so the test cannot pin
time without a wall-clock-relative hack. The originally-filed fix (swap the fixed
date for `Instant.now()`) makes the test green but keeps the smell: suite
determinism still rides on real wall-clock. Per the 2026-06-24 design discussion,
the correct fix is to make the job's time reference an explicit injected
`java.time.Clock` — the pattern `DigestScheduler`, `RateCapBucket`, and
`ThrottledAdminNotifier` already use (the app-wide producer is
`ThrottledAdminNotifier.systemUtcClock()`, `@Produces @ApplicationScoped`). Then
production reads real time and the test pins a fixed instant.

This ticket fixes ONLY `ReEvaluationJob`. The system-wide sweep of the other
~44 `now()` decision sites, the coding-style rule, and the reviewer update are
tracked separately (this conversation's plan, items 2–4).

## Why fully, not half

Moving only the read the test cares about (the scan floor) while leaving the
cooldown gate and the timestamp writes on SQL `now()` would split the job across
two clocks — a value written by one and compared by the other. That is the exact
skew-bug class to avoid. So the whole tick path moves to the one captured instant
together: the scan floor, the cooldown gate, the depth COUNT floor, and the
`status_changed_at` / `last_reeval_at` / `updated_at` writes. Acceptance is
checkable as "zero `now()` / `Instant.now()` left in `ReEvaluationJob.java`."

## Notes

- Capture one instant per `onTick()` (`clock.instant()`) and thread it down; do
  NOT call `clock.instant()` separately per statement (that reintroduces
  intra-tick skew, a smaller version of the same bug).
- Override the Clock in the IT to a fixed instant (`@InjectMock Clock` with a
  stubbed `instant()`, or a test `Clock.fixed(...)` alternative). Keep
  `FETCHED_AT` fixed and inside the 32-day window relative to that instant.
- `ReEvaluationJobWindowTest` and `ReEvaluationJobTest` (not in scope) stay green
  unchanged: with the real production Clock, `clock.instant() ≈ Instant.now()`,
  so the window test's `Instant.now()` in-window seed and fixed below-floor seed
  keep their relationship to the floor.
- Do NOT change the window semantics (retention + slack) — only the source of the
  instant. Production behaviour must be byte-for-byte preserved.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-444-derot-reeval-scheduled-path-scan-window.md
```
