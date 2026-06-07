---
id: M1-181
title: "quarantine_review listener correctness cluster"
status: done
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-181.md
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewReconciler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the EMISSION side of quarantine_review NOTIFYs (BENIGN-close emission gap, UNKNOWN-promote pipeline routing, RETURNING-based closed-row emission) — that is M1-182's; this ticket fixes the Provider-side consumer and the one duplicated Collector-side admin page, and both tickets touch quarantine NOTIFY semantics, so coordinate rather than serialize
  - NewPostListener / NewPostReconciler — they are the correct pattern this ticket mirrors, not a target
  - provider_state schema / ProviderStateDao CAS semantics — the cursor mechanism itself is spec-compliant; this ticket changes when the listener invokes it relative to side effects
  - admin notification rendering/transport (the WARN-log stub is a separate audit finding, UNIFIED.md gpt S4)
  - a live-dispatch retry mechanism for a transiently-failed handleEvent transaction (redteam 2026-06-07 Finding 2, severity low — a newer event advancing the cursor strands the failed event behind both keyset scans); disposition tracked in docs/plan/m1/redteam/M1-181-2026-06-07.md, not fixed here
acceptance:
  - "Per docs/spec/architecture.md §Inter-service communication — \"the high-water mark advances both fields **in the same DB transaction** as the side effect it triggers, making processing idempotent.\" — for an actionable quarantine_review event, the cursor advance and the admin-notification persistence commit atomically: a named IT forces the notification write to fail and asserts the cursor did not advance past the event (today advanceCursor and fireAdminNotification run on separate connections and an SQLException in the latter is swallowed after the former committed)"
  - "Per docs/spec/architecture.md §Inter-service communication — \"The receiving side reads the row from its base table for the actual data — NOTIFY is purely the wake-up signal.\" — the listener decides actionability from the current row state read from the base table (quarantine_review_view / post), not from the payload's new_status: a named IT delivers a forged payload whose new_status contradicts the row and asserts behavior follows the row"
  - "Per docs/spec/security.md §Failure handling — \"**Admin notifications** are coalesced per `(channel, error_class)` for a short window so an outage produces one summary message, not 200 individual alerts.\" — PENDING and NEEDS_REVIEW events coalesce under distinct error classes: a named test asserts a NEEDS_REVIEW notification is not suppressed by the throttle window a recent PENDING notification opened (today both share the constant key quarantine-review-actionable)"
  - "After the LISTEN connection drops and is re-established, quarantine_review events that fired during the gap are caught up — cursor advanced and actionable ones notified — a named IT mirroring NewPostListenerReconcileOnReconnectIT (today ensureListenConnection re-LISTENs without catch-up)"
  - "An actionable event that arrives after a newer event has already advanced the cursor still reaches the admin notifier (throttling may coalesce it, but it is not silently dropped by the CAS-advance gate) — a named test delivers two events out of timestamp order and asserts the older actionable one notifies"
  - "QuarantineReviewReconciler's startup catch-up routes actionable missed events through the same handling path as the live listener (mirroring NewPostReconciler, which invokes its handler — making QuarantineReviewListener's javadoc claim that the reconciler invokes handleEvent true): a named IT seeds an actionable quarantine_review event beyond the stored cursor before startup and asserts catch-up both advances the cursor and produces the admin notification (throttling may coalesce it, not drop it)"
  - "Per docs/spec/architecture.md §Inter-service communication — \"**Consumer behavior:** the Provider drives the throttled admin notifier (`security.md` §Failure handling) on `PENDING` inserts and on `→ NEEDS_REVIEW` transitions — these are the two transitions that require admin attention.\" — a re-eval cap-exhaustion event produces exactly ONE admin notification across both services: a named IT asserts the Collector's notifyOnce(re-eval-cap-exhaustion) and the Provider's NEEDS_REVIEW NOTIFY handling no longer both page for the same transition (resolved direction: the Collector's notifyOnce call is the side removed; the Provider's page is the keeper)"
  - "Per docs/spec/security.md §Re-evaluation job — \"After cap exhaustion the post transitions to `NEEDS_REVIEW` … and the admin notifier fires.\" — QuarantineReviewReconciler.runCatchUp scans BOTH event kinds against one cursor snapshot taken at catch-up start, so the quarantine phase advancing the shared cursor cannot move the post scan's baseline past older post events: a named IT seeds, beyond the stored cursor, a NEEDS_REVIEW post event at T1 and a quarantine event at T2 > T1, runs catch-up, and asserts the quarantine_review.needs_review notification exists for the post event (redteam 2026-06-07 Finding 1: today the post-scan baseline is re-read after the quarantine phase, permanently skipping post events older than the newest quarantine event)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 828
      removed: 209
  - round: 2
    date: 2026-06-07
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 1075
      removed: 221
  - round: 2
    date: 2026-06-07
    verdict: OVERRIDE-APPROVE
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 1075
      removed: 221
    override_ref: 0
revisions:
  - date: 2026-06-07
    reason: clarity-fail rework — authorize collector re-eval test changes (TEST-CHANGES-AUTHORIZED blocker), make acceptance item 6 testable (reconciler-notifies direction), resolve leg-6 keeper to the Provider side
    prior_values: |
      acceptance[5]: "QuarantineReviewListener's class javadoc and
        QuarantineReviewReconciler's behavior agree: today the javadoc claims
        the reconciler invokes handleEvent during startup catch-up while the
        reconciler only calls advanceCursor directly"
      acceptance[6]: ended at "...no longer both page for the same
        transition" (no resolved-direction clause)
      body §Notes leg-6: left the keeper choice to the implementer ("If the
        implementer judges the Collector-side page the safer keeper, dropping
        the Provider side instead also satisfies the exactly-one acceptance
        item; pick one and say why in the commit message.")
      body §Out-of-scope: authorized only QuarantineReviewListenerTest; no
        authorization for collector re-eval test changes
  - date: 2026-06-07
    reason: redteam-finding rework — add catch-up single-cursor-snapshot acceptance item (redteam Finding 1, medium); declare the live-dispatch retry (Finding 2, low) explicitly out of scope
    prior_values: |
      acceptance: 8 items ending at "mvn -B clean verify from the repo root
        exits 0" — no catch-up cursor-baseline item
      out_of_scope: 4 entries — no entry for the live-dispatch retry
        (redteam Finding 2)
overrides:
  - date: 2026-06-07
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — must-shrink: round 2 grew along ALL THREE
      dimensions vs round 1 (files 12 > 11, lines added 1075 > 828, lines
      removed 221 > 209). Even applying the lifecycle-path exemption
      charitably, the implementation file count is 9 vs 9 (equal, not
      smaller) and both line dimensions still grew, so the rule's "smaller
      along at least one dimension" is not met. The exception clause cannot
      apply: round 1's verdict was APPROVE with zero REWORK items.
    user_justification: |
      Must-shrink is unsatisfiable by construction here, not violated by
      divergent rework: round 1 was APPROVED, then acceptance item 8 was
      added between rounds via the formal redteam-finding escalation
      (2026-06-07, Finding 1 medium), mandating new code and a new named IT
      on top of the approved diff. The round-2 diff is a strict superset of
      round 1 by mandate of the revised ticket itself. The reviewer's own
      verdict confirms every substantive check passes and "nothing in the
      diff itself warrants rework". Matches the M1-131 precedent: in-branch
      redteam fix on an approved ticket trips must-shrink mechanically;
      resolution is override, not shrinking.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-07
    category: AUDIT-EVASION
    severity: medium
    promise: |
      "After cap exhaustion the post transitions to `NEEDS_REVIEW` (per
      `schema.md` §Posts and derivatives) and the admin notifier fires." and
      "**Throttled NEEDS_REVIEW notifications.** Admin notifications for
      `NEEDS_REVIEW` transitions are coalesced per `(channel, error_class)`
      over a profile-driven window so a Stage-2 outage that exhausts retries
      on hundreds of posts produces one summary notification, not hundreds"
      (docs/spec/security.md §Re-evaluation job). The diff moved the firing of
      this notifier from the Collector (removed at
      `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:184-192`)
      to the Provider's quarantine_review consumer, whose own contract claims
      "A Provider outage therefore cannot silently swallow a PENDING /
      NEEDS_REVIEW page: throttling may coalesce it, never drop it"
      (`QuarantineReviewReconciler.java:33-35`).
    gap: |
      The reconciler's two-phase catch-up permanently skips NEEDS_REVIEW post
      events that are older than the newest quarantine event in the missed
      window. `runCatchUp`
      (`infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewReconciler.java:99-112`)
      runs the quarantine scan first, which advances the shared channel cursor
      via `handleEvent` to the newest quarantine `updated_at`; it then
      re-reads the cursor (lines 102-109) and starts the post scan from that
      advanced position. `POST_SCAN_SQL` (lines 55-60) only selects rows with
      `(status_changed_at, 'post', id) > (cursorHigh, …)`, so a `NEEDS_REVIEW`
      post whose `status_changed_at` precedes the newest quarantine event is
      never read, never routed through `handleEvent`, and never paged. Because
      the post stays `NEEDS_REVIEW` indefinitely while the cursor only moves
      forward, every future catch-up skips it too — the page is lost
      permanently, not deferred. `handleEvent`'s "notify even when the cursor
      CAS no-ops" design (`QuarantineReviewListener.java:143-150,166-177`)
      cannot help, because the row is excluded from the scan before it ever
      reaches `handleEvent`. Quarantine events (every Stage 1 hit creates one)
      are frequent, so the interleaving "NEEDS_REVIEW post older than some
      quarantine event in the same missed window" is the common case after any
      Provider blip or restart, and the Collector-side guaranteed page that
      previously covered this transition was deleted in the same diff.
    repro: |
      (1) Attacker-controlled feed publishes content the Stage 2 judge labels
      UNKNOWN; re-eval attempts exhaust the (lower) UNKNOWN cap and the
      Collector transitions post P to NEEDS_REVIEW at T1, emitting the
      quarantine_review NOTIFY. (2) The Provider's LISTEN connection is
      down/blipped at T1 (routine transient PG disconnect), so the NOTIFY is
      lost. (3) Any other ingest content triggers a Stage 1 hit, creating a
      quarantine row Q with updated_at T2 > T1. (4) Provider reconnects;
      catch-up phase 1 handles Q and advances the cursor to T2; phase 2 scans
      posts `> T2` and never sees P. (5) No `quarantine_review.needs_review`
      page ever fires for P, now or on any later catch-up — the spec-promised
      "admin notifier fires" on cap exhaustion never happens for P, and admin
      review of the hidden hostile content is delayed until the absolute
      NEEDS_REVIEW depth alert threshold or a manual `/quarantine list`.
    suggested_fix_class: other
  - date: 2026-06-07
    category: AUDIT-EVASION
    severity: low
    promise: |
      Same §Re-evaluation job commitment ("the admin notifier fires") plus the
      diff's own atomicity contract that a failed notification write must not
      lose the page ("a failed notification write rolls back the cursor
      advance above instead of being swallowed after it committed",
      `QuarantineReviewListener.java:170-173`; "throttling may coalesce it,
      never drop it", `QuarantineReviewReconciler.java:34-35`).
    gap: |
      On the live path, a `SQLException` inside `handleEvent` is caught in
      `dispatch`
      (`infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:259-267`),
      logged, and dropped — the rollback correctly leaves the cursor behind
      the event, but nothing schedules a retry: catch-up runs only at startup
      (`QuarantineReviewReconciler.java:73-81`) and after a reconnect
      (`QuarantineReviewListener.java:304-313`), and a per-transaction failure
      (lock timeout, pool exhaustion, the trigger class the new IT itself
      exercises) does not close the LISTEN connection, so no reconnect occurs.
      If any newer event then arrives and advances the cursor past the failed
      event, both keyset scans (`QuarantineReviewReconciler.java:49-60`)
      exclude it forever — the actionable page is permanently lost. The
      pre-diff code paged from the Collector synchronously with a degraded-DB
      fallback inside `ThrottledAdminNotifier.notifyOnce` (the swallowing
      fallback retained at
      `infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:212-217`
      is deliberately bypassed by the new transactional overload at lines
      233-241); the replacement has this unrecovered-failure window.
    repro: |
      (1) Quarantine row Q transitions to PENDING at T1; NOTIFY delivered.
      (2) The Provider's `handleEvent` transaction for Q fails transiently
      (e.g., momentary connection-pool exhaustion during a load spike) —
      dispatch logs "handler failed" and drops; cursor stays behind T1. (3) A
      second quarantine event at T2 is delivered and handled successfully,
      advancing the cursor to T2. (4) Q at T1 is now behind the cursor; no
      future catch-up scan can reach it, and no live retry exists. The
      `quarantine_review.pending` page for Q never fires (or, if no newer
      event arrives, is deferred indefinitely until an unrelated reconnect).
    suggested_fix_class: other
redteam_audits:
  - date: 2026-06-07
    verdict: FINDINGS
    base: ee640c3 (main tip == branch tip m1/M1-181-quarantinereview-listener-corr)
    head: working tree (uncommitted implementation, post-APPROVE round 1)
    verdict_file: docs/plan/m1/redteam/M1-181-2026-06-07.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Pre-commit --in-progress audit after round-1 APPROVE. Both findings are
      AUDIT-EVASION gaps in the "exactly one admin page per actionable
      transition, throttled never dropped" promise this diff centralizes on
      the Provider (the Collector-side guaranteed cap-exhaustion page was
      removed by this diff per the exactly-one-page acceptance item). Medium:
      two-phase catch-up re-reads the cursor between the quarantine and post
      scans, permanently excluding NEEDS_REVIEW post events older than the
      newest quarantine event in the same missed window. Low: live-path
      transient SQLException has no retry hook; a newer event advancing the
      cursor strands the failed event behind both keyset scans. Out-of-model
      (advisory): shutdown blocked on the listener monitor during a large
      catch-up; reconciler boot-time scan hard-fails the Provider on a
      transient DB error. Disposition pending user decision.
escalations:
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL
      test_plan.modifies lists two directories of pre-existing tests; the body
      authorizes one pre-existing test class (QuarantineReviewListenerTest) but
      no authorization is given for modifications to the collector re-eval test
      package (infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval).
      Acceptance item 7 implies changes to ReEvaluationJob that will require
      updating its tests, but the body never names the affected test class(es)
      nor states the new expected behavior that replaces the old assertions.
  - date: 2026-06-07
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS (2 findings: 1 medium, 1 low — both
      AUDIT-EVASION; 2 out-of-model advisories). Medium: reconciler's
      two-phase catch-up re-reads the cursor between the quarantine scan and
      the post scan, so a NEEDS_REVIEW post event older than the newest
      quarantine event in the same missed window is permanently excluded —
      the admin page is lost forever, not coalesced. Low: a transient
      SQLException in handleEvent on the live path rolls back correctly but
      has no retry hook; a newer event advancing the cursor strands the
      failed event behind both keyset scans. Full verbatim entries in
      redteam_findings: and docs/plan/m1/redteam/M1-181-2026-06-07.md.
  - date: 2026-06-07
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      VERDICT: MANUAL
      SCOPE-DRIFT-CHECK: FAIL — must-shrink: round 2 grew along ALL THREE
      dimensions vs round 1 (12>11 files, 1075>828 added, 221>209 removed);
      even after the lifecycle-path exemption both line dimensions still
      grew. The exception clause cannot apply: round 1 was APPROVE with
      zero REWORK items. The growth itself is fully traceable, not scope
      creep: every new hunk implements acceptance item 8, which was added
      between rounds via the formal redteam-finding escalation. The round-2
      diff is a strict superset of round 1 by mandate of the revised ticket
      itself — "smaller along at least one dimension" is unsatisfiable
      without deleting either approved round-1 work or the
      escalation-mandated fix. Per §6 (never trade rules against each
      other) it escalates rather than picking a rule to violate. Every
      other check passes; nothing in the diff itself warrants rework.
---

# M1-181: quarantine_review listener correctness cluster

## Context

Five verified defects cluster in the Provider's `quarantine_review` consumer
(QuarantineReviewListener / QuarantineReviewReconciler), plus one duplicated
admin page on the Collector side (unified findings P16 ×5 + P17,
`deep-code-review/v2/UNIFIED.md` §2):

1. **Split-transaction cursor/notify.** `handleEvent` advances the cursor on
   one connection, then `fireAdminNotification` opens another; an
   SQLException in the latter is caught and logged — the notification is
   lost forever while the cursor says it was handled. The spec requires the
   side effect and the high-water-mark advance in the same DB transaction.
2. **Payload trust.** `new_status` is regex-extracted from the NOTIFY
   payload and drives `isActionable` and the message text; the row is only
   consulted for its timestamp (`lookupEventTime`). NOTIFY is spec'd as a
   pure wake-up signal.
3. **Single throttle key.** `ADMIN_NOTIFY_KEY = "quarantine-review-actionable"`
   is constant; a PENDING page opens a throttle window that suppresses a
   following NEEDS_REVIEW page (the error_class column varies, the key does
   not). The inline UPSERT also re-implements ThrottledAdminNotifier.
4. **No reconnect catch-up.** `ensureListenConnection` re-LISTENs only;
   NewPostListener calls `reconcileAfterReconnect()` after re-LISTEN.
5. **CAS-gated notify + javadoc drift.** Notification fires only when
   `advanceCursor` returns true, so an actionable event arriving after a
   newer event is silently dropped; and the class javadoc claims the
   reconciler invokes `handleEvent` while it actually calls `advanceCursor`
   directly (its own javadoc documents not notifying).
6. **Double page on cap exhaustion (P17).** ReEvaluationJob:165-168 fires
   `notifyOnce(re-eval-cap-exhaustion)` AND emits the NEEDS_REVIEW NOTIFY
   the Provider pages on — two admin notifications for one event.

## Acceptance

See frontmatter — each spec sentence is transcribed verbatim and paired with
a named test.

## Out-of-scope

See frontmatter. M1-182 owns the NOTIFY emission side; the two tickets
overlap on quarantine NOTIFY semantics and should land with awareness of
each other, but neither blocks the other. QuarantineReviewListenerTest pins
current behavior (payload-driven actionability, split-connection flow); this
ticket is AUTHORIZED to rewrite it to the new contract.

On the Collector side, `ReEvaluationJobScheduledPathIT` and
`ReEvaluationJobTest` are AUTHORIZED to change their cap-exhaustion
admin-notification assertions: the assertion that
`notifyOnce(re-eval-cap-exhaustion)` fires
(`ReEvaluationJobScheduledPathIT.capExhaustedRowReachesNeedsReviewThroughScheduledTick`,
and any `ReEvaluationJobTest` assertion on that error class) is replaced by
an assertion that the Collector does NOT page for cap exhaustion, while the
NEEDS_REVIEW status transition and the quarantine NOTIFY emission remain
asserted. The other `notifyOnce` call sites in ReEvaluationJob
(`re-eval-released`, NEEDS_REVIEW queue depth) keep their assertions
unchanged.

## Notes

- Source: `UNIFIED.md` §3 T5 under `deep-code-review/v2/` (opus-47 A-F2/F3,
  P-F7; opus-48 A-F2/F3; kimi-folder A-F2).
- On leg 6 the direction is RESOLVED (refine 2026-06-07): the Collector's
  notifyOnce(re-eval-cap-exhaustion) is the duplicate to drop. Rationale:
  acceptance item 3 mandates a working Provider NEEDS_REVIEW page (it must
  escape a PENDING throttle window), so the Provider side cannot be the one
  removed without gutting items 1–5; and the Provider-liveness concern that
  previously favored the Collector page is addressed by item 6's direction —
  startup catch-up now notifies actionable missed events (mirroring
  NewPostReconciler), closing the Provider-down page-loss window.
- The NewPostListener/NewPostHandler pair is the documented-correct pattern
  for legs 1 and 4 (same-transaction handling; reconcile-on-reconnect).

## Suggested direction (unverified hypothesis)

Mirror the NewPostHandler @Transactional pattern for cursor+notify; re-read
row status via quarantine_review_view (the redacted Provider view); replace
the inline UPSERT with the shared core ThrottledAdminNotifier keyed per
error class (proposed by the opus-47 and opus-48 runs).

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
