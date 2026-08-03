---
id: M1-757
title: "Drain the pagination cap-hit flag on the tick failure path"
status: done
created: 2026-08-03
last_updated: 2026-08-03
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerSaturationIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE TRUNCATION FLAG'S DRAIN: its BEHAVIOUR is not revisited — the
    success-path consume, the per-occurrence reporting policy, and the
    consume-and-discard on failure all stay byte-unchanged in effect.
    The REFINED scope (redteam-finding rework, 2026-08-03) does relocate
    the failure-path drain of BOTH flags to the top of the catch block,
    ahead of the fallible failure-recording sub-path: the M1-753 drain
    sat at the tail and shared the same residual leak window the M1-757
    finding identified, so one restructure closes it for both. Moving
    the call earlier changes nothing observable — the failure path
    discards the value either way.
  - >-
    `recordTick`'s STREAK-RESET SEMANTICS and the pagination-saturation
    notification policy generally. The streak reset on a non-saturated
    tick is spec-anchored behaviour
    (`PaginationSaturationTracker` §Transition semantics) that M1-753
    deliberately left byte-unchanged. This ticket changes WHEN THE FLAG IS
    CLEARED, never how a consumed flag is interpreted.
  - >-
    The RSS truncation path, `RssFeedParser`, and the Bluesky/Reddit
    response parsers. The defect is entirely in the scheduler's per-tick
    flag lifetime; no parser changes.
  - >-
    REFACTORING the two flags into a single object, enum, or map. That is
    a tempting tidy-up and it is not this ticket: it would touch
    `PaginationSaturationTracker`'s public surface and both signalling
    fetchers, and the asymmetry being fixed is one missing call, not a
    structural problem.
  - >-
    The D42 failure ladder, `recordFailure`, and the
    `fetch_failure_ladder` notification, all of which share the same
    `catch` block. The added drain must not alter their behaviour or
    ordering.
acceptance:
  - >-
    `FetchScheduler`'s fetch-failure path consumes-and-clears the
    pagination cap-hit flag, so a fetcher that calls
    `PaginationSaturationTracker.signalCapHit()` and then throws cannot
    leak the flag onto the next source ticked on the same dispatch
    thread. The scheduler ticks sources sequentially on one heartbeat
    thread, and the flag is a static ThreadLocal that only a consume
    clears, so an undrained flag is read as the NEXT source's cap hit.
  - >-
    The drain runs at the TOP of the catch block, AHEAD of the fallible
    failure-recording sub-path (`logFetchFailure`,
    `sourceRepository.recordFailure`, the throttled notification,
    `recordTick`) — an unchecked exception from any of those must not be
    able to skip the drain and leave the flag set until the next
    heartbeat (redteam-finding rework, 2026-08-03). The truncation drain
    moves to the same point for the same reason; both consumed values
    are discarded on the failure path, so the reordering is
    behaviour-neutral.
  - >-
    `FetchSchedulerSaturationIT.capHitFlagDoesNotLeakToTheNextSourceWhenFetchThrows`
    passes. It must tick a source whose fetcher signals a cap hit and
    then throws, then tick a DIFFERENT source whose fetcher signals
    nothing, and assert the second source's `capHitCount` is 0 and no
    `fetch_saturation:<second uuid>` notification exists. Asserting only
    that the first source behaves correctly would not detect the leak —
    the leak is by definition visible only on the following tick.
  - >-
    The existing saturation behaviour is unchanged: the three
    pre-existing `FetchSchedulerSaturationIT` cases (counter increment,
    threshold-crossing fires exactly once, non-saturated tick resets the
    streak) and the three M1-753 truncation cases stay green WITHOUT
    modification. Editing any of them to accommodate the new drain would
    be a test-integrity violation, not a fix.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerSaturationIT.java
  preserves:
    - >-
      The six existing `FetchSchedulerSaturationIT` cases — three
      pagination-saturation (M1-216) and three truncation (M1-753) —
      unmodified.
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 258
      removed: 28
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-03
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Failure handling — Fetcher failure: "Fetcher
      failure (HTTP error, connection timeout, feed parse failure on an
      HTTP-shaped source) → retry on the next scheduled tick (decision
      D42). ... a throttled admin notification is sent with the error class
      and source id. Other sources are unaffected." Combined with the
      PaginationSaturationTracker contract (docs/spec/architecture.md §Ingest
      SPIs): the per-source pagination-cap saturation counter and the
      `fetch_saturation` notification are attributed to the source that
      actually saturated. The diff's own added comment re-asserts the
      invariant unconditionally: "a failed tick records no saturation" and
      the drain "is what makes the flag's one-tick lifetime a property of
      the scheduler."
    gap: |
      The M1-757 drain is placed at the TAIL of the tick-failure catch
      block (FetchScheduler.java:556, 562), AFTER the fallible statements:
      logFetchFailure (line 522), sourceRepository.recordFailure + the
      throttled notifyOnce (lines 527-544 — the inner try/catch catches
      only SQLException), and saturationTracker.recordTick (line 548). An
      unchecked (non-SQLException) exception from any of those escapes the
      outer catch at line 514, skipping BOTH drains — the CAP_HIT ThreadLocal
      stays set on the @Scheduled heartbeat thread (onTick at line 250 has
      no outer guard around drainPending→tickOnce, so the whole heartbeat
      aborts). The next heartbeat's first fetch on that thread reads
      capHit=true at line 510 and recordTick(wrongUuid, true) at line 649
      attributes the signal to an innocent source. The same residual window
      applies to the truncation drain (line 556), so the diff inherits the
      shape rather than introduces it — but the added comment claims the
      property unconditionally, and the promise ("Other sources are
      unaffected", "a failed tick records no saturation") is therefore not
      fully delivered: a failed tick CAN still leak saturation onto the next
      source when the failure-recording path itself fails.
    repro: |
      (1) A source's fetcher raises the cap-hit signal (as
      BlueskyFetcher.java:107 / RedditFetcher.java:113 do on a capped-out
      page loop) and then throws; (2) the catch block runs logFetchFailure
      and recordFailure; recordFailure throws an unchecked exception (any
      repository defect — NPE, IllegalStateException — or a non-SQLException
      driver error), which the inner `catch (SQLException)` at line 540 does
      not swallow; (3) the exception propagates out of tickOnce, skipping
      recordTick(false), consumeTruncation() and consumeCapHit() — the
      CAP_HIT flag survives on the heartbeat thread; (4) the next heartbeat
      ticks a DIFFERENT source on that thread; line 510 consumes the stale
      flag as that source's own cap hit; recordTick inflates its cumulative
      counter and streak, and at the streak threshold a `fetch_saturation`
      admin notification fires naming the innocent source's uuid (with a
      persisted admin_notification_state row) — and the in-memory
      capHitTotals pollution is permanent for the process lifetime. The
      system should not have allowed a failed tick to attribute saturation
      to any source; the failure-isolation promise requires the drain to run
      regardless of what the failure-recording sub-path does.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-08-03
    verdict: FINDINGS
    base: 3737750c
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-757-2026-08-03.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Low-severity INFO-LEAK: the drain sits at the tail of the failure
      catch block, after the fallible failure-recording sub-path; an
      unchecked exception there escapes the outer catch and leaves both
      thread-local flags set until the next heartbeat, which could
      attribute saturation to an innocent source. The shape is inherited
      from the M1-753 truncation drain (the same window exists there).
      One out-of-model item: thread-confined signal channel would silently
      drop a cap-hit from a hypothetical async fetcher (advisory only).
      Ticket halted at the run redteam gate for user escalation decision.
  - date: 2026-08-03
    verdict: CLEAN
    base: c4b21b52
    head: working-tree
    verdict_file: docs/plan/m1/redteam-multi/M1-757-r2-2026-08-03/cross-examination.md
    out_of_model_count: 2
    note: |
      Multi-auditor re-audit (codex + opencode, headless) of the
      remediation diff; both auditors returned CLEAN, cross-examination
      found 0 clusters. The r1 finding is confirmed closed: both
      failure-path drains now run at the top of the catch block ahead of
      the fallible failure-recording sub-path. Two advisory out-of-model
      items (async-fetcher signal loss; failure-recording sub-path
      robustness) — both pre-existing/not-adversary-reachable;
      disposition.md recommends no follow-up tickets.
clarity_check:
  date: 2026-08-03
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-757: drain the cap-hit flag on the tick failure path

## Context

Surfaced as an OUT-OF-MODEL observation by the M1-753 re-audit
(`docs/plan/m1/redteam/M1-753-2026-08-03-r2.md`), which is advisory by
construction — it is not a finding against M1-753 and was not folded into
it.

`PaginationSaturationTracker` hands two signals from a fetcher to the
scheduler over static `ThreadLocal` flags, because the `Fetcher` SPI
returns only a post list. Each flag's contract is that its lifetime is
exactly one tick. `FetchScheduler` ticks many sources sequentially on one
heartbeat thread, so a flag left set at the end of a tick is read as the
NEXT source's signal and would notify against the wrong source uuid.

M1-753 added the truncation flag and drained it on BOTH exits — the
success path and the `catch (Exception e)` path — with an explicit
rationale that draining on failure is what makes the one-tick lifetime a
property of the scheduler rather than a coincidence of which fetchers
happen to throw. The older cap-hit flag never got that treatment: it is
consumed only on the success path.

**This is currently unreachable, and the ticket does not claim otherwise.**
Both signalling fetchers raise the flag with only a `return` between the
raise and the scheduler's consume — `BlueskyFetcher:107` and
`RedditFetcher:113` — so no exception can be thrown in the window. The
defect is that the invariant is undefended, not that it is presently
violated. What changed is that its sibling is now defended, so the
asymmetry is live in the code and invites the reasonable-but-wrong
inference that the cap-hit flag is drained too.

## Census

The class is "tick-scoped out-of-band signal flags, and whether each is
drained on both scheduler exits". Enumerated mechanically, re-runnable:

```
grep -n "ThreadLocal" infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/PaginationSaturationTracker.java
```

| Site | Drained on success | Drained on failure | Disposition |
|---|---|---|---|
| `PaginationSaturationTracker.CAP_HIT` | yes | **no** | **fix** |
| `PaginationSaturationTracker.TRUNCATED` | yes | yes (M1-753) | already correct — out-of-scope |

Two sites, one defect. If the grep ever returns a third flag, it needs a
row here before this ticket is started.

## Acceptance

See the YAML `acceptance:` list. In prose: the scheduler's fetch-failure
`catch` block must consume-and-clear the cap-hit flag exactly as it
already consumes the truncation flag — both drains at the TOP of the
block, ahead of the fallible failure-recording sub-path, so an exception
from that sub-path cannot skip them — and a new IT case must prove the
absence of cross-source leakage by asserting on the SECOND source's
state — the only place the leak is observable.

## Out-of-scope

See the YAML `out_of_scope:` list. The two worth restating: do not touch
`recordTick`'s streak semantics (spec-anchored, deliberately preserved by
M1-753), and do not refactor the two flags into one structure. The fix is
a restructure of the catch block — both failure-path drains move to its
top — plus its rationale comment.

## Notes

**A reviewer may reasonably ask whether this is defensive code for an
impossible scenario**, which `CLAUDE.md` §"No defensive code" forbids.
The argument that it is not: the drain is not a null-check between two
internal callers, it is the enforcement of a stated lifetime invariant at
the component that owns it. Today's unreachability is contingent on the
control flow of two unrelated fetcher classes, not on anything the
scheduler guarantees; any future fetcher that validates its parsed result
after the page loop — exactly what `NitterFetcher` already does for its
whitelist-placeholder stub — reopens the window silently. M1-753 made
this same argument for the truncation flag and the reviewer APPROVEd it,
so accepting it here is consistency rather than a new precedent. Recorded
here so the decision is weighed rather than rediscovered mid-review; if
the reviewer disagrees, the honest resolution is `escalate → abandon`,
not a weakened test.

`security_relevant: true` because the failure mode is a mis-attributed
admin notification — an operator-facing signal naming the wrong source —
and because the item originates from a red-team audit. The change itself
is a restructure of one catch block.

- Adjacent code: `FetchScheduler` fetch try/catch (the truncation drain
  is the sibling; the refined fix moves both failure-path drains to the
  top of the catch block, per the redteam finding persisted at
  `docs/plan/m1/redteam/M1-757-2026-08-03.md`).
- Origin: `docs/plan/m1/redteam/M1-753-2026-08-03-r2.md` OUT-OF-MODEL
  item 2; reworked per the M1-757 redteam finding (INFO-LEAK / low,
  2026-08-03).
