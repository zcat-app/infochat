---
id: M1-460
title: "Move LlmRateCap's per-user rate-limit window onto the injected Clock (audit-missed §9 site)"
status: done
created: 2026-06-26
last_updated: 2026-06-26
clarity_check:
  date: 2026-06-26
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-06-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 118
      removed: 9
redteam_findings: []
redteam_audits:
  - date: 2026-06-26
    verdict: CLEAN
    base: main
    head: m1/M1-460-llmratecap-injectable-clock
    verdict_file: docs/plan/m1/redteam/M1-460-2026-06-26.md
    out_of_model_count: 1
    note: |
      Clean audit of the clock-seam refactor (--in-progress, branch tip before
      commit). Pure time-source swap (System.currentTimeMillis -> injected
      clock.millis) at the tryAcquire window decision and the scheduled
      eviction; production behaviour byte-for-byte preserved. One advisory
      out-of-model item only asks the user to confirm the package-private
      field-injected Clock seam stays the accepted project-wide pattern (it
      mirrors RateCapBucket and ~18 other provider components) — no follow-up
      ticket warranted.
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/LlmRateCap.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/LlmRateCapClockTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The other now()/currentTimeMillis() sites already classified and either converted or deferred by the M1-447 → M1-454 injectable-Clock sweep — this ticket converts ONLY LlmRateCap, which that grep-on-.now() sweep missed because it reads time as a long via System.currentTimeMillis()."
  - "Any behavioural change to the 60 s window, the per-minute cap, or the eviction cadence. This is a determinism refactor: under the production Clock.systemUTC() behaviour is byte-for-byte preserved (the M1-447 rule)."
  - "InboundRouter.formatTimeUntilUnlock (Instant.now() in a probation-remaining DISPLAY string) — that is a category-(C) formatting site, §9-exempt, and M1-451 deliberately left it on Instant.now() when it moved the probation decision gates. Not touched here."
  - "InFlightTracker and RateCapBucket — RateCapBucket is the already-correct reference pattern (injected Clock, clock.millis()); it is read for guidance only, not modified."
acceptance:
  - "LlmRateCap.tryAcquire reads the current instant from an injected java.time.Clock (the app-wide @Produces Clock from ThrottledAdminNotifier.systemUtcClock(); CDI-default initializer Clock clock = Clock.systemUTC() so hand-constructed test instances stay non-null), never System.currentTimeMillis(). The sliding-window decision (windowStart = now - 60_000, the prune loop, the cap comparison) is computed against the injected clock."
  - "The scheduled eviction path (evictIdleEntries) also samples the injected clock; LlmRateCap contains no remaining System.currentTimeMillis() call. The component reads one clock end-to-end — no app-vs-DB or wall-vs-injected split (the M1-447 / M1-444 single-clock rule)."
  - "A new deterministic test pins the Clock via QuarkusMock.installMockForType(Clock.fixed(...), Clock.class) (or constructs the bean with a fixed Clock) and asserts the per-user window decision at a fixed instant: capPerMinute acquires succeed, the next is rejected, and after advancing the pinned clock past 60 s a further acquire succeeds — all without depending on the wall clock."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "LlmRateCapClockTest — pins Clock.fixed(...) and asserts the sliding-window acquire/reject boundary and the post-60s refill are decided against the injected instant, not the wall clock."
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs: []
---

# M1-460: Move LlmRateCap's per-user rate-limit window onto the injected Clock

## Context

The injectable-Clock sweep (M1-447 and its follow-ups M1-448, M1-450, M1-451,
M1-452, M1-454) converted every decision-logic time site the classification
audit found. That audit was keyed on Java `*.now()` and SQL `now()`.
`LlmRateCap` reads time as `System.currentTimeMillis()` (a `long`), so it never
matched the audit's grep and was never classified — yet it is squarely an (A)
decision-logic site: a per-user sliding-window rate cap, exactly the
"rate-limit windows" case the §9 rule names.

`LlmRateCap.tryAcquire` (`infochat-provider/src/main/java/app/zcat/infochat/provider/chat/LlmRateCap.java`,
the `long now = System.currentTimeMillis()` at the top of the synchronized
block) computes `windowStart = now - 60_000`, prunes timestamps older than the
window, then gates on `size >= capPerMinute`. Because the instant is ambient,
the window decision cannot be pinned in a test without a wall-clock-relative
fixture hack — the same time-bomb class that M1-398 / M1-400 / M1-444 each
fixed one-off. The scheduled `evictIdleEntries()` no-arg method reads
`System.currentTimeMillis()` too (it already delegates to a package-private
`evictIdleEntries(long)` seam, so eviction is test-reachable today, but the
primary `tryAcquire` gate is not).

The fix is mechanical and the correct shape already lives next door in the same
module: `RateCapBucket` holds `private Clock clock = Clock.systemUTC();` and
reads `clock.millis()` everywhere. `LlmRateCap` should mirror it.

`security_relevant: true` because this cap is the per-user cost bound on
LLM-triggering operations (chat replies, on-demand `/summary`, `/retry`
re-rolls) per `docs/spec/security.md` §Rate limiting; an unpinnable cap is a
correctness/test-determinism risk on a security-relevant budget.

## Acceptance

See the YAML `acceptance:` list. In short: add a CDI-default injected `Clock`
to `LlmRateCap`, replace both `System.currentTimeMillis()` reads (the
`tryAcquire` window decision and the scheduled eviction) with `clock.millis()`,
add one fixed-Clock test for the window boundary, full suite green. Behaviour
under `Clock.systemUTC()` is byte-for-byte preserved.

## Out-of-scope

See the YAML `out_of_scope:` list. Notably: no window/cap/cadence behaviour
change; `RateCapBucket` is the reference pattern, read not modified; and the
§9-exempt `InboundRouter.formatTimeUntilUnlock` display site that M1-451
deliberately left on `Instant.now()` is NOT touched.

## Notes

- Reference implementation in the same module: `RateCapBucket` (injected
  `Clock clock = Clock.systemUTC()`, `clock.millis()` in `tryAcquireFrom` and
  the eviction sweep) and the M1-444 `ReEvaluationJob` pattern referenced by
  M1-447.
- The Clock producer is `ThrottledAdminNotifier.systemUtcClock()`
  (`@Produces @ApplicationScoped`, returns `Clock.systemUTC()`); test seam
  `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`.
- Keep the single-clock discipline: every time read inside `LlmRateCap` moves
  to the injected clock together — do not leave eviction on
  `System.currentTimeMillis()` while `tryAcquire` moves to the clock, which
  would reintroduce a wall-vs-injected split.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-460-llmratecap-injectable-clock.md
```
