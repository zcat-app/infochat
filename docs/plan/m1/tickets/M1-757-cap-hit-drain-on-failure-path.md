---
id: M1-757
title: "Drain the pagination cap-hit flag on the tick failure path"
status: pending
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
    THE TRUNCATION FLAG'S DRAIN, added by M1-753. `consumeTruncation()`
    is ALREADY called on both the success and the failure path
    (`FetchScheduler` fetch try-block and its `catch (Exception e)`).
    This ticket brings the older sibling up to that standard; it does not
    revisit the newer one.
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
already consumes the truncation flag, and a new IT case must prove the
absence of cross-source leakage by asserting on the SECOND source's
state — the only place the leak is observable.

## Out-of-scope

See the YAML `out_of_scope:` list. The two worth restating: do not touch
`recordTick`'s streak semantics (spec-anchored, deliberately preserved by
M1-753), and do not refactor the two flags into one structure. The fix is
one call plus its rationale comment.

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
is one line.

- Adjacent code: `FetchScheduler` fetch try/catch (the truncation drain
  immediately above is the pattern to match, including its comment
  explaining why the failure path drains).
- Origin: `docs/plan/m1/redteam/M1-753-2026-08-03-r2.md` OUT-OF-MODEL
  item 2.
