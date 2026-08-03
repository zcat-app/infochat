---
id: M1-752
title: "A source parked in status='failed' is dark forever and, after one notification, silently — decide whether D42's no-automatic-recovery stance survives"
status: done
created: 2026-08-02
last_updated: 2026-08-03
blocked_by: []
files_budget: 5
files_scope:
  - docs/spec/decisions.md
  - docs/spec/architecture.md
  - docs/spec/schema.md
  - docs/spec/security.md
  - docs/plan/m1/tickets/M1-754-parked-source-reprobe-ladder.md
complexity: medium
risk: low
round_cap: 2
security_relevant: true
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
      files: 12
      added: 1335
      removed: 25
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-03
    category: DOS
    severity: medium
    promise: |
      security.md §Failure handling UNKNOWN auto-disable park is
      "excluded from D42's automatic re-probe rung"; D42 amended:
      "re-probe eligibility is decided on that reason, never on bare
      status='failed'".
    gap: |
      The exclusion rests on one mutable column with no writer
      precedence: M1-754 requires only that each writer records its
      reason "in the same statement", not the same condition. The D42
      ladder's UPDATE guards only the status flip (CASE on threshold +
      status='active'); every other SET term fires unconditionally, so
      the symmetric park_reason='fetch-failure' term would RELABEL a
      row parked seconds earlier by PerSourceUnknownTracker, making a
      security park re-probe-eligible. M1-754's exclusion tests seed a
      static row and cannot catch a reason written after the park.
    repro: |
      Adversary posts UNKNOWN-rate content while making their endpoint
      hang: fetch tick enumerates the source while active, blocks;
      tracker parks with reason unknown-rate during the hang; the hung
      fetch times out and its unconditional SET rewrites the reason to
      fetch-failure; re-probe later restores the feed with no admin
      action. Timing is adversary-steerable.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-08-03
    category: DOS
    severity: medium
    promise: |
      D42 amended: eligibility decided on the recorded reason, never
      bare status='failed'; schema.md: UNKNOWN-rate and cycle-cap parks
      never recover automatically.
    gap: |
      No value defined for rows ALREADY parked when the discriminator
      ships, and no fail-closed rule for absent/unknown reason. The
      amendment's own evidence (11 healthy parked sources) pressures
      the implementer to backfill park_reason='fetch-failure' onto all
      existing failed rows — exactly the bare-status behaviour the row
      forbids — which would lift a pre-existing UNKNOWN-rate or
      cycle-cap security park.
    repro: |
      A source parked today by the UNKNOWN-rate control sits with no
      reason column. M1-754's migration backfills fetch-failure (or the
      predicate is written as IS DISTINCT FROM 'unknown-rate'); the row
      becomes re-probe-eligible on the first ladder tick and the
      quarantine-exhaustion defense is lifted with no admin action.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-08-03
    category: AUDIT-EVASION
    severity: medium
    promise: |
      schema.md invariant 7 audit-before-effect; security.md
      §Re-evaluation job precedent: system-actor state transitions are
      audit-logged (RE_EVAL_RELEASED, actor='re_eval_job').
    gap: |
      The amendment adds a NON-admin writer of failed→active and
      commits only to a throttled RECOVERED notification. Until this
      diff every failed→active transition was audit-logged
      (SOURCE_ENABLE with actor). Notifications are coalesced/lossy and
      non-durable; audit_log is append-only and is what /audit reads.
      The replacement path drops the control — a §10
      controls-don't-travel instance in the spec itself.
    repro: |
      Adversary feed alternates fail-N-times / answer-one-probe;
      each restore re-opens ingest. An operator investigating later
      finds no audit row for any transition; RECOVERED notifications
      were coalesced or throttled away.
    suggested_fix_class: audit-log-coverage
  - date: 2026-08-03
    category: INFO-LEAK
    severity: medium
    promise: |
      schema.md: scheduler selects status='active' AND deleted_at IS
      NULL; security.md §SSRF: every outbound Collector connection runs
      through the fail-closed allowlist (D20).
    gap: |
      The new off-schedule re-probe selection enumerates none of the
      guards the path it parallels carries: M1-754's recorded predicate
      has no deleted_at IS NULL (every existing enumeration carries it)
      and no statement that probes go through the shared SSRF
      allowlist. The human equivalent treats reviving a soft-deleted
      source as higher-risk (confirm-gated + SOURCE_ENABLE_INTENT).
    repro: |
      Admin /remove-source's a hostile feed (soft delete; status stays
      'failed' — orthogonal columns). The re-probe ladder selects it on
      reason alone, keeps polling the deliberately-cut-off endpoint on
      the backoff schedule, and on the first 200 flips it back to
      active. Off-allowlist implementation would also probe re-pointed
      DNS (169.254.169.254) without rebind checks.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-08-03
    verdict: FINDINGS
    base: 137c203a11bc2467debeff5c2c91470ff9cbb698
    head: working tree (uncommitted branch)
    verdict_file: docs/plan/m1/redteam/M1-752-2026-08-03.md
    findings_count: 4
    out_of_model_count: 1
    disposition: |
      Falsified first (user directive). Findings 1, 2 and 3 survived
      verification against the cited code; finding 4 survived NARROWED
      — its deleted_at half is real (all three FetchScheduler
      enumerations carry `deleted_at IS NULL`; the M1-754 predicate
      omitted it), but its SSRF half was FALSIFIED: each Fetcher
      constructs its own SsrfGuardedHttpClient internally, so any
      caller of the Fetcher SPI inherits the allowlist and there is no
      bypass to specify against. All surviving items remediated in this
      same diff (D42 properties (a)-(d), the audit-row commitment, and
      five new M1-754 acceptance items + named tests). Re-audit owed
      against the remediated diff before review.
  - date: 2026-08-03
    verdict: FINDINGS
    round: 2
    verdict_file: docs/plan/m1/redteam/M1-752-2026-08-03-r2.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      The r1 fix inverted: both manual-only park writers are guarded on
      status='active', so a row parked fetch-failure first could never
      be upgraded to unknown-rate and kept the one re-probe-eligible
      reason; plus restore refilled the cap counter (flap-forever).
      Fixed by making D42 property (b) bidirectional and gating the cap
      refill on a sustained-success window.
  - date: 2026-08-03
    verdict: FINDINGS
    round: 3
    verdict_file: docs/plan/m1/redteam/M1-752-2026-08-03-r3.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      TOCTOU: every eligibility predicate sat on the selection side with
      a network probe before an unguarded restore write. Plus a stale
      security.md sentence calling /source-enable "the same recovery
      path used for HTTP-failure sources". Fixed by D42 property (e)
      (compare-and-swap restore) and rewriting the sentence.
  - date: 2026-08-03
    verdict: FINDINGS
    round: 4
    verdict_file: docs/plan/m1/redteam/M1-752-2026-08-03-r4.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      V31's column-scoped Provider GRANT UPDATE (5 columns, identity
      columns revoked) would have to widen for /source-enable to reset
      the new columns, and nothing constrained the widening — the
      shortest green-build fix is a blanket grant that hands a Provider
      SQL-injection foothold the ability to repoint a D7-trusted source.
      Plus the probe's payload was not gated on the CAS result. Fixed in
      security.md §DB roles (closed enumeration, blanket grant
      forbidden) + M1-754 acceptance/out_of_scope/files_scope, and a D42
      payload-gating clause.
  - date: 2026-08-03
    verdict: CLEAN
    round: 5
    verdict_file: docs/plan/m1/redteam/M1-752-2026-08-03-r5.md
    out_of_model_count: 2
    note: |
      Terminal round. All prior findings verified closed against the
      text present. Two out-of-model observations for the user, neither
      a threat-model gap: the re-probe path does not inherit the active
      path's per-host pacing (FetchScheduler.hostNextAllowed) and
      co-parked sources become probe-due together; and the recurring
      parked-set signal has no stated size bound or paging. Both are
      decisions to take in M1-754, not defects in this amendment.
    note_r1: |
      All four findings are spec-strengthening items on the amended
      text itself (writer precedence / fail-closed backfill /
      audit-log coverage of the automatic failed→active transition /
      deleted_at + SSRF guards on the new selection path), fixable in
      this diff's own files plus M1-754's acceptance. Out-of-model:
      lapsed-domain takeover of parked sources (v1 re-validates
      ownership on no path; delta is loss of a human checkpoint).
clarity_check:
  date: 2026-08-03
  verdict: WARN
  warnings:
    - >-
      lint-ticket.py: PASS (0 blockers, 0 warnings).
    - >-
      Self-check, ticket-vs-code truth: every claim verified. `status =
      'active'` at FetchScheduler:456/:717/:751; the "recovery is
      operator-driven via /source-enable" javadoc at :102; the
      crossing-tick-only `notifyOnce` keyed on the source UUID at :94-96;
      `infochat.fetch.failure-threshold=5` at
      collector application.properties:198.
    - >-
      Self-check, ticket-vs-code truth: ONE ticket claim is wrong, and it
      inverts option A's stated cost. The body says A makes "D38's
      parallel per-relay contract arguably drift unless it moves too".
      The reverse holds. D38's ladder (architecture.md:273-299,
      RelayHealthTracker) has THREE rungs — per-relay cooldown with
      AUTOMATIC retry and a RECOVERED transition, then all-relays-bad
      notification plus its recovery counterpart, then an absolute cycle
      cap to a terminal `failed` needing /source-enable. D42 has only
      rungs 1 and 3. So D42's own sentence, "This row is the Fetcher
      mirror of D38's per-relay degradation commitment ... so HTTP-shaped
      and stream-shaped sources have parallel failure-isolation
      contracts", is FALSE as shipped. Option A closes that drift;
      option B would leave D42 asserting a mirror it does not have.
    - >-
      Blocking AskUserQuestion raised (2 questions). Q1 D42 outcome →
      user chose OPTION A (amend, bounded re-probe). Q2 the files_scope
      contradiction → user chose refine-then-start; applied as commit
      `M1-752: refine ticket spec (budget-breach)`.
    - >-
      Implementation-time discovery (2026-08-03): THREE mechanisms write
      `source.status='failed'`, not one — the D42 HTTP ladder
      (SourceRepository via FetchScheduler:520), the Stage-2 UNKNOWN-rate
      auto-disable (PerSourceUnknownTracker:148, the quarantine-exhaustion
      defense of security.md §Per-source UNKNOWN auto-disable), and the
      D38 all-relays-bad cycle-cap terminal state
      (NostrStreamSource:558). The status column records no park reason,
      so an undiscriminated re-probe over `status='failed'` would put an
      adversary-controlled feed back on a timer and defeat a documented
      security control. Second blocking AskUserQuestion → user chose to
      widen files_scope to docs/spec/security.md (sync-check: :1364
      "admin must explicitly re-enable" and :1536 "the same terminal
      status" are falsified by the amendment) and to flip
      security_relevant: true (redteam gate runs on this diff).
  blockers: []
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

## Outcome (2026-08-03)

**Option A adopted — D42 amended** to add a bounded automatic re-probe
rung (exponential backoff, restore + RECOVERED notification on first
success, absolute cap → terminally parked, `/source-enable` only). The
recurring parked-set signal ships alongside it, per acceptance item 3 —
it is what keeps terminally-parked and manual-only sources visible.

Two corrections to this ticket's own analysis, found at implementation:

1. **Option A's D38 cost was inverted.** D38's ladder always had an
   automatic-retry rung (cooldown → reconnect → RECOVERED) plus a
   terminal cycle cap; D42 had only the terminal rung. The "Fetcher
   mirror of D38" sentence in D42 was false as written — the amendment
   *closes* that drift rather than opening one.
2. **`status='failed'` has three writers, not one** — the D42 ladder,
   the Stage 2 UNKNOWN-rate auto-disable
   (`PerSourceUnknownTracker:148`, a quarantine-exhaustion security
   control), and D38's cycle cap (`NostrStreamSource:558`) — and the
   column records no reason. An undiscriminated re-probe over
   `status='failed'` would put an adversary-controlled feed back on a
   timer. The amendment therefore requires a park-reason discriminator
   and scopes re-probe eligibility to fetch-failure parks only; the
   sync-check pass reached `security.md` (§Failure handling bullet and
   the UNKNOWN auto-disable paragraph) on the same grounds.

Losing option's cost, recorded per acceptance item 2: option B kept
recovery manual and depended entirely on an operator acting on a
recurring notification — the 27-day dark window happened WITH a
(one-shot) notification in place, which is direct evidence against
that dependence on this deployment.

**Follow-up implementation ticket: M1-754**
(`M1-754-parked-source-reprobe-ladder.md`) — re-probe ladder,
park-reason discriminator on all three writers, recurring parked-set
signal, `/source-enable` reset semantics. Blocked on this ticket.
