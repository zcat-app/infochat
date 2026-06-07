---
id: M1-181
title: "quarantine_review listener correctness cluster"
status: pending
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
acceptance:
  - "Per docs/spec/architecture.md §Inter-service communication — \"the high-water mark advances both fields **in the same DB transaction** as the side effect it triggers, making processing idempotent.\" — for an actionable quarantine_review event, the cursor advance and the admin-notification persistence commit atomically: a named IT forces the notification write to fail and asserts the cursor did not advance past the event (today advanceCursor and fireAdminNotification run on separate connections and an SQLException in the latter is swallowed after the former committed)"
  - "Per docs/spec/architecture.md §Inter-service communication — \"The receiving side reads the row from its base table for the actual data — NOTIFY is purely the wake-up signal.\" — the listener decides actionability from the current row state read from the base table (quarantine_review_view / post), not from the payload's new_status: a named IT delivers a forged payload whose new_status contradicts the row and asserts behavior follows the row"
  - "Per docs/spec/security.md §Failure handling — \"**Admin notifications** are coalesced per `(channel, error_class)` for a short window so an outage produces one summary message, not 200 individual alerts.\" — PENDING and NEEDS_REVIEW events coalesce under distinct error classes: a named test asserts a NEEDS_REVIEW notification is not suppressed by the throttle window a recent PENDING notification opened (today both share the constant key quarantine-review-actionable)"
  - "After the LISTEN connection drops and is re-established, quarantine_review events that fired during the gap are caught up — cursor advanced and actionable ones notified — a named IT mirroring NewPostListenerReconcileOnReconnectIT (today ensureListenConnection re-LISTENs without catch-up)"
  - "An actionable event that arrives after a newer event has already advanced the cursor still reaches the admin notifier (throttling may coalesce it, but it is not silently dropped by the CAS-advance gate) — a named test delivers two events out of timestamp order and asserts the older actionable one notifies"
  - "QuarantineReviewReconciler's startup catch-up routes actionable missed events through the same handling path as the live listener (mirroring NewPostReconciler, which invokes its handler — making QuarantineReviewListener's javadoc claim that the reconciler invokes handleEvent true): a named IT seeds an actionable quarantine_review event beyond the stored cursor before startup and asserts catch-up both advances the cursor and produces the admin notification (throttling may coalesce it, not drop it)"
  - "Per docs/spec/architecture.md §Inter-service communication — \"**Consumer behavior:** the Provider drives the throttled admin notifier (`security.md` §Failure handling) on `PENDING` inserts and on `→ NEEDS_REVIEW` transitions — these are the two transitions that require admin attention.\" — a re-eval cap-exhaustion event produces exactly ONE admin notification across both services: a named IT asserts the Collector's notifyOnce(re-eval-cap-exhaustion) and the Provider's NEEDS_REVIEW NOTIFY handling no longer both page for the same transition (resolved direction: the Collector's notifyOnce call is the side removed; the Provider's page is the keeper)"
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
reviews: []
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
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
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
