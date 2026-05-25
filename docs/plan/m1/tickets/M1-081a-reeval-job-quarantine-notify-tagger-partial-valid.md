---
id: M1-081a
title: Re-eval job + quarantine NOTIFY + tagger partial-valid + TTL
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by:
  - M1-079a
files_budget: 12
files_scope:
  - infochat-core/src/main/resources/db/migration/V21__quarantine_admin.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJobTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-provider/** — Provider-side listener and commands are M1-081b
  - any change to EmbeddingMetadataStartupGuard or EmbeddingWorker — the model identity guard is already implemented; embedding is not T2-G scope
  - any change to the post.status CHECK constraint — NEEDS_REVIEW already exists in V7
  - any cross-source linking (D6 entity extraction, post_reference, last_linked_at) — standalone future ticket, not T2-G
  - any admin command handler (/quarantine list|approve|reject, /audit) — M1-081b
  - any modification to Stage2WorkerIT.java — existing test continues to pass unchanged
  - any modification to EmbeddingWorkerTest.java — existing test continues to pass unchanged
  - any modification to QuarantineDaoTest.java — existing test continues to pass unchanged
  - infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java — M1-081 umbrella
acceptance:
  - "Flyway migration V21__quarantine_admin.sql applies cleanly on a fresh DB"
  - "V21 ALTERs the post table to add column re_eval_attempts INTEGER NOT NULL DEFAULT 0"
  - "V21 creates stored procedure approve_quarantine(bigint, bigint) — transitions quarantine row PENDING→APPROVED (or BENIGN_CLOSED→APPROVED), restores original_html into the post body replacing the placeholder, writes QUARANTINE_APPROVE audit row with actor_id, fires NOTIFY new_post with the post's (ready_at, post_id) cursor"
  - "V21 creates stored procedure reject_quarantine(bigint, bigint) — transitions quarantine row to REJECTED (from PENDING or BENIGN_CLOSED), writes QUARANTINE_REJECT audit row with actor_id"
  - "V21 GRANTs EXECUTE on approve_quarantine and reject_quarantine to infochat_provider"
  - "V21 INSERTs provider_state row for channel 'quarantine_review' with empty first-boot sentinel cursors (ON CONFLICT (channel) DO NOTHING)"
  - "V21 GRANTs INSERT, UPDATE on admin_notification_state to infochat_provider (Provider needs write access for throttled admin notifications on quarantine_review events)"
  - ReEvaluationJobTest.infraFailureBenign_clearsFlag_retainsRedactions_closesQuarantine passes — a post with stage2_failed=true is re-submitted to Stage 2; verdict BENIGN clears stage2_failed, transitions quarantine PENDING→BENIGN_CLOSED, Stage 1 redactions remain in body, RE_EVAL_RELEASED audit row written with actor='re_eval_job' and details_json containing prior_verdict and new_verdict='BENIGN'
  - ReEvaluationJobTest.infraFailureCapExhaustion_transitionsToNeedsReview passes — repeated infra-failure re-eval attempts up to the per-post cap transition post to NEEDS_REVIEW; throttled admin notification fires coalesced per (channel, error_class)
  - ReEvaluationJobTest.unknownBenign_promotesToReady_closesQuarantine passes — an UNKNOWN-verdict QUARANTINED post re-submitted with the lower UNKNOWN cap; verdict BENIGN transitions post QUARANTINED→READY with Stage 1 redactions retained, quarantine PENDING→BENIGN_CLOSED, throttled admin notification fires
  - ReEvaluationJobTest.unknownCapExhaustion_transitionsToNeedsReview passes — UNKNOWN cap is lower than infra-failure cap; cap exhaustion transitions post to NEEDS_REVIEW; throttled admin notification fires
  - ReEvaluationJobTest.reEvalNonBenign_staysQuarantined_incrementsCounter passes — INJECTION/MALWARE/UNKNOWN on re-eval leaves post QUARANTINED, increments re_eval_attempts, stage2_failed preserved
  - ReEvaluationJobTest.needsReviewDepthAlert_firesWhenQueueExceedsThreshold passes — when the total NEEDS_REVIEW queue exceeds the profile-driven threshold, an absolute-depth alert fires independent of any per-source ratio
  - ReEvaluationJobTest.quarantineReviewNotify_emittedOnNeedsReviewTransition passes — post.status→NEEDS_REVIEW emits NOTIFY quarantine_review with tagged payload ('post', post_id, 'NEEDS_REVIEW')
  - PerSourceUnknownTrackerTest.unknownRateExceedsThreshold_disablesSource passes — a source whose Stage 2 UNKNOWN rate exceeds the profile-driven threshold over the rolling window has source.status flipped to 'failed'; throttled admin notification fires citing source id, observed rate, and threshold
  - PerSourceUnknownTrackerTest.autoDisable_inflightPostsContinueUnaffected passes — posts already in the outbox or re-evaluation queue continue through their current evaluation stage after source auto-disable
  - AdminReviewTtlJobTest.pendingPastTtl_rejectsAndTransitionsPost passes — a PENDING quarantine row aged past the admin-review TTL transitions to REJECTED; the attached NEEDS_REVIEW post transitions to QUARANTINED; the placeholder becomes permanent; no admin notification fires
  - AdminReviewTtlJobTest.benignClosedPastTtl_noTransition passes — a BENIGN_CLOSED row aged past the admin-review TTL stays BENIGN_CLOSED; no transition fires
  - AdminReviewTtlJobTest.quarantineReviewNotify_emittedOnTtlReject passes — TTL auto-reject emits NOTIFY quarantine_review with payload ('quarantine', quarantine_id, 'REJECTED')
  - TaggerWorkerTest.partialValidTags_keepsValidDropsInvalid_noFallback passes — a fake LLM emits a tag list of three valid + one out-of-vocab entries; the post is tagged with the three valid entries; bootstrap-tags fallback does NOT fire; per-post counter records '3 valid + 1 invalid'
  - TaggerWorkerTest.zeroValidTags_fallsBackToBootstrapTags passes — zero valid entries after normalization falls back to source.bootstrap_tags with tagger_fallback=true
  - "Stage2VerdictHandler emits NOTIFY quarantine_review with tagged payload ('quarantine', quarantine_id, new_status) on quarantine state-machine transitions (PENDING insert, PENDING→BENIGN_CLOSED)"
  - "mvn -B clean verify from the repo root exits 0"
  - "Every prior test continues to pass (Stage2WorkerIT, EmbeddingWorkerTest, QuarantineDaoTest)"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJobTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
  - docs/spec/security.md §Failure handling
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/verification.md §Schema
  - docs/spec/verification.md §LLM and embeddings
decision_refs:
  - D22
  - D34
  - D42

reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-081a: Re-eval job + quarantine NOTIFY + tagger partial-valid + TTL

## Context

Collector-side subticket of the T2-G quarantine admin workflow
(M1-081 umbrella). Implements the re-evaluation job that
re-submits `stage2_failed=true` and UNKNOWN-verdict posts to
Stage 2 with separate attempt caps, the per-source UNKNOWN
auto-disable, the absolute NEEDS_REVIEW depth alert, the
admin-review TTL auto-reject job, the `quarantine_review` NOTIFY
emit, and the tagger partial-valid fix. Also adds the V21
migration containing stored procedures (`approve_quarantine`,
`reject_quarantine`), Provider EXECUTE grants, and the
`provider_state` row for the `quarantine_review` channel.

`blocked_by: [M1-079a]` is a migration-ordering dependency only —
V21 requires V20 to exist. No functional dependency on group
schema.

Spec contracts: `security.md` §Re-evaluation job (full section),
`schema.md` §Invariants — 6 (admin-review TTL), `llm.md`
§Failure handling (tagger partial-valid), `architecture.md`
§Inter-service communication (`quarantine_review` channel).

## Acceptance

**V21 migration.** The migration adds the `re_eval_attempts`
column to `post`, creates both stored procedures with SECURITY
DEFINER (so Provider can call them without raw quarantine table
access), grants EXECUTE to `infochat_provider`, seeds the
`quarantine_review` `provider_state` row, and grants Provider
INSERT/UPDATE on `admin_notification_state` for throttled
notification writes.

**Re-evaluation job.** Two post classes feed the queue:
`stage2_failed=true` (infra-failure, released READY with
redactions) and UNKNOWN-verdict (QUARANTINED). They carry
separate, independent attempt caps — UNKNOWN's is the lower.
BENIGN on an infra-failure post clears `stage2_failed`, closes
the quarantine PENDING→BENIGN_CLOSED, retains Stage 1 redactions
(only `/quarantine approve` lifts them), writes `RE_EVAL_RELEASED`
audit with `actor='re_eval_job'`, and fires a throttled admin
notification coalesced per `(channel, 're_eval_released')`.
BENIGN on an UNKNOWN post transitions QUARANTINED→READY with the
same redaction-retention and audit semantics, and closes the
quarantine row. Non-BENIGN re-eval verdicts leave the post
QUARANTINED with the attempt counter incremented. Cap exhaustion
on either class transitions to NEEDS_REVIEW with a coalesced
throttled notification.

**Per-source UNKNOWN auto-disable.** When a source's UNKNOWN rate
exceeds the profile-driven threshold over the rolling window, the
source transitions to `status='failed'`; a throttled admin
notification fires citing source id, observed rate, and threshold.
In-flight posts from the disabled source continue through their
current evaluation stage unaffected.

**Absolute NEEDS_REVIEW depth alert.** When the total
NEEDS_REVIEW queue exceeds the profile-driven threshold, an
operator alert fires independent of any per-source ratio.

**Admin-review TTL auto-reject (Invariant 6).** A PENDING
quarantine row aged past the profile-driven TTL auto-transitions
to REJECTED; the attached NEEDS_REVIEW post transitions to
QUARANTINED; the placeholder becomes permanent; no admin
notification fires (the notifier already paged on NEEDS_REVIEW
entry). BENIGN_CLOSED rows are NOT subject to the TTL — they stay
BENIGN_CLOSED with no transition.

**quarantine_review NOTIFY emit.** Collector emits
`NOTIFY quarantine_review` with a tagged JSON payload
`(target_kind, target_id, new_status)` on quarantine state-machine
transitions (PENDING insert, BENIGN_CLOSED, APPROVED, REJECTED
via stored procs) and on `post.status → NEEDS_REVIEW`.
`target_kind ∈ {'quarantine', 'post'}` discriminates the event
family.

**Tagger partial-valid handling.** When some tags pass vocabulary
validation and others don't, the valid tags are kept and invalid
tags dropped — bootstrap fallback fires only when ZERO valid tags
survive. A per-post counter records "N valid + M invalid."

## Out-of-scope

- Provider-side listener, admin commands, and /audit — M1-081b.
- Embedding model identity guard — already implemented in
  `EmbeddingMetadataStartupGuard`.
- Cross-source linking (D6 entity extraction, `post_reference`,
  `last_linked_at`) — standalone future ticket.
- `post.status` CHECK constraint changes — `NEEDS_REVIEW` already
  exists in V7.
- Any modification to `Stage2WorkerIT.java`,
  `EmbeddingWorkerTest.java`, or `QuarantineDaoTest.java` — these
  existing tests continue to pass unchanged.
- The umbrella integration test `QuarantineWorkflowIT` — M1-081.

## Notes

- `ThrottledAdminNotifier` (M1-058) is a Collector CDI bean; the
  re-eval job, per-source tracker, and NOTIFY emit paths inject it
  directly. The tagger fallback path already logs a canonical
  `error_class` — wire it to `ThrottledAdminNotifier.notifyOnce`.
- The stored procedures use `SECURITY DEFINER` so the Provider
  calling `approve_quarantine(id, actor_id)` does not need SELECT
  on `quarantine.original_html`. The procedure reads the original
  span, restores it in the post body, transitions statuses, writes
  the audit row, and fires `NOTIFY new_post` — all atomically.
- `approve_quarantine` fires `NOTIFY new_post` (not
  `quarantine_review`) for the post, so the Provider's existing
  `NewPostListener` picks up the re-rendered body via the
  standard high-water-mark path.
- Re-eval cadence, attempt caps, UNKNOWN-rate threshold and
  window, NEEDS_REVIEW depth threshold, and admin-review TTL are
  all profile-driven — the exact values live in design notes. Tests
  assert the semantics (cap boundary, threshold crossing), not
  specific numbers.
- The `re_eval_attempts` column defaults to 0 for all existing
  posts. On each re-eval attempt (regardless of verdict), the
  counter increments. The job's pickup query is roughly:
  `WHERE (stage2_failed = true OR (status = 'QUARANTINED' AND
  stage2_done = true AND stage2_failed = false)) AND
  re_eval_attempts < cap`.
- Adjacent code patterns: `Stage2VerdictHandler` for quarantine
  state transitions; `ReadyPromoter` for status promotions;
  `ThrottledAdminNotifier` for notification wiring.
