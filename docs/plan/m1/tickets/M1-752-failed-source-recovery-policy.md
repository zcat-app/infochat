---
id: M1-752
title: "A source parked in status='failed' is dark forever and, after one notification, silently — decide whether D42's no-automatic-recovery stance survives"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by: []
files_budget: 2
files_scope:
  - docs/spec/decisions.md
  - docs/spec/architecture.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    WRITING THE IMPLEMENTATION. This ticket settles the policy and amends
    (or explicitly reaffirms) D42; the code change is the follow-up ticket
    it is expected to file, gated normally. A diff touching
    `FetchScheduler`, `SourceRepository` or `application.properties` has
    left scope.
  - >-
    The parse-cap defect (M1-753). `Open AI` was one of the 16 parked
    sources but its failure is a parser rejection, not a transient
    upstream one — no recovery rung of any shape would have helped it, and
    conflating the two hides that.
  - >-
    The two sources with transcription-broken YouTube channel ids and the
    two behind a captcha wall. Those were operator-data defects and are
    already corrected in the prod runtime bootstrap; they are cited here
    only to establish that the 16 were NOT a homogeneous set.
  - >-
    `asset_config.consecutive_failures` (`architecture.md:173`) and the
    Nostr per-relay ladder (D38). They mirror this ladder's shape and may
    deserve the same treatment, but each has its own cadence and blast
    radius. Widening to them is how this becomes three tickets in one.
  - >-
    Changing `infochat.fetch.failure-threshold` (5). Raising the threshold
    delays the cliff; it does not add a way back off it, which is the
    thing under discussion.
acceptance:
  - >-
    D42 (`decisions.md:59`) is either AMENDED to permit a bounded
    automatic re-probe, or explicitly REAFFIRMED with the operational
    consequence written down. Today it reads "The source returns to
    `status='active'` only via an admin command (an explicit re-enable; no
    automatic recovery)" — so the obvious fix is currently spec-forbidden,
    and no implementation ticket can be written until this row moves or is
    knowingly kept.
  - >-
    THE DECISION IS MADE EITHER WAY AND THE LOSING OPTION'S COST IS
    RECORDED. Reaffirming D42 is a legitimate outcome; what is not
    legitimate is leaving the row untouched and the operational gap
    undescribed. The two candidates are stated below and the ticket must
    pick one, not enumerate both and stop.
  - >-
    Whichever way it lands, the ONGOING-SIGNAL gap is addressed
    separately from the RECOVERY gap. They are independent: today the
    admin notification fires once, on the crossing tick only, keyed on the
    source UUID (`FetchScheduler:94-96`), so even under manual-recovery
    policy an operator who misses one message has no later prompt. A
    policy that keeps recovery manual MUST pair it with a recurring
    signal, or manual recovery is a fiction.
  - >-
    `schema.md:261-264` is checked for consistency with the outcome. It
    currently describes `failed → active` as set by `/source-enable` "or by
    a successful manual probe" — compatible with D42 as written, but it
    is the row that has to change if automatic re-probe is adopted.
  - >-
    The follow-up implementation ticket is filed, with a files_scope, and
    referenced from this one. This ticket closes on the decision, not on
    the behaviour changing.
test_plan:
  adds: []
  preserves:
    - >-
      No code changes, so no test changes. The follow-up ticket carries the
      test plan.
    - all tests currently green on main
spec_refs:
  - docs/spec/decisions.md §Decisions log
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/schema.md §Sources and tags
decision_refs:
  - D42
  - D38
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-752: a parked source never comes back, and nothing says so twice

## Context

Found while answering an operator question about the prod deployment on
2026-08-02, not from a test failure — which is the point: **nothing in the
system was reporting this.**

**16 of 79 live sources (20% of the corpus) were sitting in
`status='failed'`.** The oldest had been dark since 2026-07-06 — 27 days.
The group scope's retrieval world silently contained a fifth fewer sources
than the operator believed, and no surface anywhere said so.

The mechanism is working as designed. `FetchScheduler` enumerates
`WHERE status = 'active'` at `:456`, `:717` and `:751`, so a parked source
is never scheduled again. Its own javadoc (`:98-107`) is explicit:
*"recovery is operator-driven via `/source-enable`"*. D42 backs that:
*"no automatic recovery"*.

The problem is not the design's intent — one bad feed must not stall the
pipeline, and that part works. The problem is what actually parks sources
in practice.

## The 16 were not 16 broken feeds

Probed each endpoint from inside the collector container:

| class | n | evidence |
|---|---|---|
| transient upstream, endpoint healthy | 11 | HTTP 200 on probe while parked |
| parser rejection, endpoint healthy | 1 | see M1-753 |
| wrong channel id in bootstrap data | 2 | HTTP 404, never once succeeded |
| captcha wall / bot block | 2 | HTTP 403 site-wide, both User-Agents |

**Eleven of sixteen were healthy at the moment they were parked-and-forgotten.**
Ten of the thirteen YouTube sources carried a `last_success_at` roughly
three hours before the failure that killed them, same day. They parked in
staggered batches — 07-06, 07-08, 07-14, 07-28, 07-29, 07-31 — which is
the signature of recurring upstream throttling, not of feeds going away.

Confirmed by the recovery: after an operator reset (`status='active'`,
`consecutive_failures=0`), four of them fetched successfully within four
minutes, zero failures.

So the failure ladder's terminal state is reached, in the common case, by a
condition that has already cleared by the time anyone looks. Five
consecutive failures at a 30-minute YouTube poll interval is a 2.5-hour
outage window; upstream throttling routinely outlasts it and routinely
ends.

## Why this needs a decision before code

The natural fix — re-probe parked sources on a backoff and restore on
success — **is forbidden by D42 as currently written**. That row is a
cross-cutting decision, so it moves by amendment, not by a code ticket
quietly doing something else. Hence this ticket produces the decision and
files the implementation.

Two candidates, and the ticket must choose:

**A — amend D42 to add a bounded re-probe rung.** A parked source is
retried on exponential backoff (hours, then days, capped), restored to
`active` on the first success, and left parked otherwise. Fixes the actual
failure mode. Costs: a second scheduling path, a new way for a genuinely
dead source to consume fetch budget forever, and D38's parallel per-relay
contract arguably drifts unless it moves too.

**B — reaffirm D42, fix the signal instead.** Recovery stays manual, but
the deployment gains a recurring operator-visible statement of the parked
set — the missing half of `notifyOnce`. Materially simpler, no new
scheduling path, spec unchanged except for recording the consequence.
Costs: 20% of the corpus still goes dark on a transient blip and stays
dark until a human acts, which on this deployment took 27 days.

The honest case for B is that it is small and cannot regress ingest. The
honest case for A is that B's success depends entirely on an operator
reliably acting on a notification, and the 27-day gap is evidence about
how well that works here.

**These are not exclusive** — A without a recurring signal still leaves a
permanently-dead source invisible, which is why the acceptance criteria
require the signal question to be answered under either outcome.

## Prior art in-repo

`ThrottledAdminNotifier.notifyOnce` already exists and is already keyed on
the source UUID, so option B is mostly a scheduled reader over
`status='failed'` rather than new notification machinery. D38's per-relay
degradation ladder is the sibling contract and is the thing that would
need to stay coherent under A.
