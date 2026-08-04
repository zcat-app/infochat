---
id: M1-767
title: "Aggregate system LLM budget for the unmetered scheduled digest"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 6
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE PER-USER AND PER-GROUP CAPS. `LlmRateCap` (M1-183) and the D47
    per-group sub-bucket (M1-222) both ship and both work. This ticket adds
    the missing THIRD meter above them; it does not re-tune, re-scope or
    refactor either. Their call sites on the user-initiated route
    (`RetryCommandHandler`, `DigestRetryService`) stay byte-identical.
  - >-
    THE DIGEST RENDER'S SHAPE. Cluster selection, category count
    (`DigestCategorizer.maxCategories`), `categoryItemCap`, prose
    generation and the roll-up structure are not changed. This ticket adds
    a meter around the generative calls; it does not reduce how many the
    render makes. Making the render cheaper is a different ticket with a
    different risk profile.
  - >-
    M1-763's CANCELLATION PATH and M1-764's transport-interrupt test. The
    slot-window timeout and the interrupt-driven no-op spend are the
    TEMPORAL bound and stay exactly as they are. This ticket supplies the
    VOLUME bound they were never meant to be.
  - >-
    ASSET COMMANDS AND THE INGEST PIPELINE. `price_snapshot` calls are not
    LLM calls; Stage 1 / Stage 2 / tagging / embedding have their own
    per-task concurrency semaphores and are metered by feed volume, not by
    a user or a schedule. Widening this budget to cover ingest would make
    a collector backlog able to starve the provider — an availability
    trade the ticket must not make.
  - >-
    ANY CHANGE TO `docs/spec/security.md` §Rate limiting's WORDING. The
    sentence already promises this control; the ticket implements the
    promise rather than amending it. If implementation reveals the
    promised semantics are wrong, that is a `spec-amend` escalation, not
    an inline edit.
acceptance:
  - >-
    A system-wide LLM call meter exists and is drawn by the SCHEDULED
    digest route (`DigestScheduler` -> `DigestWorker.executeSlot` ->
    `DigestRenderer`), which today draws no bucket at all. The unit is
    LLM CALLS over a rolling window, not tokens or currency — the codebase
    has no token accounting and inventing one here would balloon the
    ticket. Window and ceiling are operator-configurable properties with
    defaults derived from the per-slot render cost measured in acceptance
    item 4, not guessed.
  - >-
    Breaching the budget DEGRADES the digest rather than failing it: the
    render falls back to its existing non-generative path (the same
    degraded renderer the slot-window timeout already uses) and the digest
    still goes out. A breach must never drop a scheduled digest silently,
    and must never throw into the scheduler.
  - >-
    A breach emits an operator signal exactly once per window, through the
    existing `ThrottledAdminNotifier` — not one per suppressed call. An
    unbounded-spend control whose alarm is itself unbounded is the same
    bug one layer up.
  - >-
    MEASURE BEFORE CHOOSING THE DEFAULT. Record, in the ticket body, the
    generative call count of one `digest_mode=full` render at a realistic
    post volume, counted at the two live call sites
    (`DigestRenderer.appendClusterProse` /
    `summaryProseGenerator.generate`, and `CategoryRollupGenerator`). The
    default ceiling is a stated multiple of that number. A budget whose
    default trips on normal operation will be raised until it is
    meaningless.
  - >-
    A test proves the meter is drawn on the SCHEDULED route specifically.
    This is the leg with no other meter, and `DigestWorkerTest` asserts on
    a stub provider, so the test must assert on the budget's own counter,
    not on the provider.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  preserves:
    - >-
      The user-initiated `/retry --digest` route keeps drawing the
      per-user token and the D47 per-group sub-bucket in the existing
      order, with the existing refund-on-group-reject behaviour
      (`RetryCommandHandler`, `DigestRetryService`).
    - >-
      `DigestWorkerTest.execute_renderOverrunningWindow_stopsSpendingProviderCalls`
      (M1-763) and
      `HttpProviderSharedPipelineTest.interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed`
      (M1-764) — the temporal bound is unaffected by adding a volume bound.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs:
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
escalation_reason:
---

# M1-767: Aggregate system LLM budget for the unmetered scheduled digest

## Context

`docs/spec/security.md` §Rate limiting states, of the per-group LLM rate
under D47:

> Periodic digests do NOT count against user-initiated per-group LLM budget
> (they are system-initiated; the aggregate system LLM budget is the
> backstop for digest cost).

The exemption ships. The backstop does not. No aggregate, global, or
system-wide LLM spend control exists anywhere in the codebase — a search
across every module for such a control returns only *byte* budgets on
chat-tool JSON output (`SearchPostsTool`, `GetReferencesTool`,
`ListSavesTool`, `SemanticSearchTool`), which are response-size clamps and
have nothing to do with call volume.

The code says so itself. `DigestRenderer`'s javadoc (lines 125–137),
written in response to an unrelated red-team finding on 2026-08-04:

> TWO ROUTES reach this render, and they are metered differently
> [redteam 2026-08-04, low/DOS]. The SCHEDULED route (`DigestScheduler` →
> `DigestWorker.executeSlot`) has no user in the loop, so no per-user or
> per-group bucket is drawn and this budget is the only rate-limiting
> control that exists on it.

— where "this budget" is `infochat.save.translation-max-per-page`, which
the same javadoc says "bounds THIS leg only" and "is not a bound on the
render's translator cost as a whole", because `appendClusterProse` and
`CategoryRollupGenerator` "reach the same `ModelTask.TRANSLATOR` on the
same render with no per-render budget of their own".

So the scheduled digest is the one LLM-spending surface in the system with
no volume meter of any kind.

## What currently bounds it, and why that is not enough

Three partial bounds exist, and none of them is a spend meter:

| Bound | What it limits | Why it is not the backstop |
|---|---|---|
| `DigestCategorizer.maxCategories = 8` | roll-up calls per render | Structural. Says nothing about cluster-prose calls, which scale with admitted post volume. |
| Slot window + `renderFuture.cancel(true)` (M1-763) | how LONG one render may spend | Temporal, not volumetric. A render that stays inside its window is unbounded in cost. |
| `categoryItemCap` | items per category | Passed as `Integer.MAX_VALUE` on the `/summary --full` path (`DigestRenderer:228`). |

The M1-763 cancellation is doing the most work of the three, and it is a
stopwatch. Its efficacy also rests entirely on the transport contract
M1-764 pinned — which is why that ticket flagged this one.

## Why this is filed rather than folded into a digest ticket

Three tickets have now named this gap in `out_of_scope` and declined it —
M1-756, M1-758, M1-764. Each decline was individually correct: none of the
three was a rate-limiting ticket. But three declines and zero owners is how
a documented gap becomes a permanent one, so it gets its own ID.

Two independent red-team audits have reached it from different directions
(the M1-763/M1-764 digest-cancel line, and whichever audit prompted the
`DigestRenderer` javadoc above). The recurrence is the argument for filing.

## Why `security_relevant: true`

The control is a DOS/cost boundary named in the threat model but absent
from the code, on the one surface deliberately exempted from every other
bucket. A budget that fails open, throws into the scheduler, or alarms
once per suppressed call would each be worse than the status quo, and each
is the kind of defect the adversarial pass is good at finding.

## Notes

- Live generative call sites inside the render, as of this filing:
  `DigestRenderer.java:347` and `:359`
  (`summaryProseGenerator.generate`), and
  `CategoryRollupGenerator.java:195-196`.
- The existing meters this must NOT disturb: `LlmRateCap` (per-user,
  M1-183) and the D47 per-group sub-bucket (M1-222).
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-767-aggregate-system-llm-budget.md`
