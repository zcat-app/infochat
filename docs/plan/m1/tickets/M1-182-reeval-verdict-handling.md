---
id: M1-182
title: "Re-evaluation verdict handling: re-hide, NOTIFYs, pipeline"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the Provider-side quarantine_review CONSUMER (cursor/notify atomicity, payload trust, throttle keys, reconnect catch-up) — that is M1-181's; both tickets touch quarantine NOTIFY semantics, so coordinate rather than serialize
  - ReadyPromoter's gating logic and TaggerWorker's RAW-only pick — they are the normal pipeline this ticket routes back through, not targets; if the adjudicated direction requires loosening either, escalate rather than widening silently
  - the re-eval cap-exhaustion double admin notification (M1-181 leg 6)
  - /quarantine approve / reject stored-procedure paths
  - re-eval cadence, attempt-cap values, candidate enumeration predicates (except as the re-hide fix requires status transitions the predicate must keep excluding correctly)
acceptance:
  - "Per docs/spec/security.md §Re-evaluation job — \"`INJECTION`, `MALWARE`, or `UNKNOWN` on either class → post stays `QUARANTINED`, the `stage2_failed` flag is **preserved** (or set, if the prior verdict was UNKNOWN) alongside the new verdict, and the attempt counter increments.\" — a released Stage-2-infra-failure post (status READY, stage2_failed = TRUE) that receives a non-BENIGN re-eval verdict is no longer user-visible: a named IT asserts the post's status is QUARANTINED afterwards, with the verdict recorded and the attempt counter incremented (today the non-BENIGN branch only increments the counter and the post stays READY for the full attempt budget)"
  - "The re-hide transition is announced on the quarantine_review channel per docs/spec/architecture.md §Inter-service communication — \"`quarantine_review` — fires on quarantine state-machine transitions reachable by Provider (`PENDING` insert, `BENIGN_CLOSED`, `APPROVED`, `REJECTED`) and on a `post.status → NEEDS_REVIEW` transition\" — the named IT asserts a NOTIFY is emitted in the same transaction as the re-hide"
  - "A BENIGN re-eval verdict that closes quarantine rows (PENDING → BENIGN_CLOSED) emits a quarantine_review NOTIFY for that transition, in the same transaction — a named IT asserts the emission (today applyBenignReEval closes rows with no emit, while transitionToNeedsReview does emit)"
  - "Per docs/spec/security.md §Re-evaluation job — \"`BENIGN` on an UNKNOWN post → post transitions `QUARANTINED → READY` with Stage 1 redactions retained and the quarantine row transitions `PENDING → BENIGN_CLOSED`\" — after an UNKNOWN→BENIGN re-eval, a named IT asserts the post ends READY with non-empty tags, entity extraction and embedding completed, and a new_post NOTIFY emitted (today promoteToReady sets READY directly: ReadyPromoter — the only pg_notify('new_post') emit — never runs, TaggerWorker picks only status='RAW', so tags stay '{}' forever and no new_post fires)"
  - "A first-pass BENIGN verdict emits quarantine_review NOTIFYs only for the quarantine rows transitioned by THIS verdict, not for the post's previously-closed rows — a named test seeds a pre-existing BENIGN_CLOSED row and asserts no duplicate NOTIFY for it (today emitQuarantineNotifyForClosedRows SELECTs all BENIGN_CLOSED rows of the post)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
  - docs/spec/architecture.md §Inter-service communication
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-182: Re-evaluation verdict handling: re-hide, NOTIFYs, pipeline

## Context

Four verified defects in the Collector's re-evaluation verdict handling
(unified findings K3, K4, K5 — `deep-code-review/v2/UNIFIED.md` §2):

1. **Re-hide gap (K3, high-sec).** ReEvaluationJob's non-BENIGN branch only
   calls `incrementAttemptCounter` (ReEvaluationJob.java:124-126). The
   candidate enumeration includes `stage2_failed = TRUE AND status !=
   'NEEDS_REVIEW'` — i.e. posts released READY after a Stage 2 infra
   failure. A released post the judge now classifies INJECTION or MALWARE
   stays user-visible for the full attempt budget. The spec says non-BENIGN
   → the post is QUARANTINED.
2. **BENIGN-close NOTIFY gap (K4a).** `applyBenignReEval` closes quarantine
   rows with no `quarantine_review` emit; the channel contract says it fires
   on BENIGN_CLOSED transitions. (`transitionToNeedsReview` emits correctly
   — the asymmetry is the tell.)
3. **UNKNOWN-promote pipeline skip (K4b).** `promoteToReady` (ReEvaluationJob
   promoteToReady, `UPDATE post SET status = 'READY' …`) bypasses
   ReadyPromoter — the only `pg_notify('new_post')` emit in the codebase —
   and the tagger/entity/embedding stages (TaggerWorker picks only
   `status = 'RAW'`). The promoted post has `tags = '{}'` forever, is
   invisible to tag-filtered retrieval, and Providers are never notified.
4. **Closed-rows re-emit (K5, med-perf).** Stage2VerdictHandler's
   `emitQuarantineNotifyForClosedRows` SELECTs **all** BENIGN_CLOSED rows of
   the post instead of only the rows this verdict closed, re-emitting
   NOTIFYs for rows closed long ago on every subsequent BENIGN verdict.

Narrowing vs the audit: the spec's `RE_EVAL_RELEASED` audit row and the
throttled `re-eval-released` admin notification already exist in
`applyBenignReEval` — those legs are NOT gaps and are not in scope.

## Acceptance

See frontmatter — spec sentences transcribed verbatim, each paired with a
named IT pinning the observable consequence (status transitions, emitted
NOTIFYs, populated tags), not the routing mechanics.

## Out-of-scope

See frontmatter. ReEvaluationJobTest / ReEvaluationJobScheduledPathIT pin
the current counter-only and direct-READY behaviors; this ticket is
AUTHORIZED to update them to the new contract, preserving their cap-math
and INFRA_FAILURE-skip assertions.

## Notes

- Source: `UNIFIED.md` §3 T6 under `deep-code-review/v2/` (opus-48 coll F1,
  opus-47 coll F1/F2, kimi-folder coll F3).
- **Adjudicated direction (binding):** the UNKNOWN-promote fix routes the
  post back through the tagger/entity/embedding pipeline (e.g. status RAW
  with stage flags set so the normal workers and ReadyPromoter finish the
  job) rather than enriching the direct-READY path — the audit disproved
  direct-READY (it orphans tags and new_post). The acceptance items pin
  the consequences; if routing through the pipeline proves impossible
  without loosening ReadyPromoter/TaggerWorker, escalate.
- For leg 4, RETURNING the rows actually transitioned (instead of a
  follow-up SELECT) is the suggested mechanism (opus-47) — Tier B,
  implementer's call.
- The re-hide (leg 1) needs a NOTIFY shape for READY→QUARANTINED; the
  channel contract covers it via the quarantine-row transition the re-hide
  writes (PENDING insert or row update), not via a new channel — adding a
  channel would be a spec amendment, which this ticket must not do.
