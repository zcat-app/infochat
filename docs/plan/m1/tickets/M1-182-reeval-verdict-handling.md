---
id: M1-182
title: "Re-evaluation verdict handling: re-hide, NOTIFYs, pipeline"
status: done
created: 2026-06-07
last_updated: 2026-06-07
escalations:
  - date: 2026-06-07
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      Round-2 review: MANUAL. The only failing check is the round-2
      must-shrink (SCOPE-DRIFT-CHECK: FAIL — files 10 > 7, added
      1462 > 822, removed 90 > 57), and it cannot be resolved by another
      rework round: round 1 was APPROVE with zero REWORK items, so no
      prior-round REWORK citation can authorize the growth; the growth is
      entirely the implementation of acceptance legs 6-9 added by the
      post-APPROVE redteam refine (commit bf97a9a). Shrinking would drop
      acceptance-mandated tests or the V41 migration, failing
      ACCEPTANCE-CHECK. Every other check is PASS (one informational
      SPEC-CONFORMANCE WARN, same as round 1); the diff is otherwise
      commit-ready.
  - date: 2026-06-07
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Round-1 review: APPROVE (all checks PASS). Escalation trigger is the
      post-APPROVE pre-commit red-team audit (docs/plan/m1/redteam/
      M1-182-2026-06-07.md): FINDINGS — 2 high (INJECTION: verdict-blind
      re-eval enumeration auto-releases first-pass INJECTION/MALWARE posts
      via the new requeue path; AUDIT-EVASION: approve_quarantine never
      clears stage2_failed, so the new re-hide silently reverses audited
      admin approvals), 1 medium (AUDIT-EVASION: RE_EVAL_RELEASED
      prior_verdict mislabeled / fires while post stays hidden). All three
      sit on surfaces this ticket declared out_of_scope; no regression in
      the diff itself.
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-182.md
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - the Provider-side quarantine_review CONSUMER (cursor/notify atomicity, payload trust, throttle keys, reconnect catch-up) — that is M1-181's; both tickets touch quarantine NOTIFY semantics, so coordinate rather than serialize
  - ReadyPromoter's gating logic and TaggerWorker's RAW-only pick — they are the normal pipeline this ticket routes back through, not targets; if the adjudicated direction requires loosening either, escalate rather than widening silently
  - the re-eval cap-exhaustion double admin notification (M1-181 leg 6)
  - the /quarantine reject path, and every approve_quarantine behavior other than the stage2_failed clear (span restore, APPROVE_QUARANTINE audit row, new_post NOTIFY emission stay exactly as V25 defines them)
  - re-eval cadence and attempt-cap values (the enumeration predicate itself is now IN scope — leg 6 — but only for the first-pass-verdict exclusion; no batch-size/ordering/cap changes)
acceptance:
  - "Per docs/spec/security.md §Re-evaluation job — \"`INJECTION`, `MALWARE`, or `UNKNOWN` on either class → post stays `QUARANTINED`, the `stage2_failed` flag is **preserved** (or set, if the prior verdict was UNKNOWN) alongside the new verdict, and the attempt counter increments.\" — a released Stage-2-infra-failure post (status READY, stage2_failed = TRUE) that receives a non-BENIGN re-eval verdict is no longer user-visible: a named IT asserts the post's status is QUARANTINED afterwards, with the verdict recorded and the attempt counter incremented (today the non-BENIGN branch only increments the counter and the post stays READY for the full attempt budget)"
  - "The re-hide transition is announced on the quarantine_review channel per docs/spec/architecture.md §Inter-service communication — \"`quarantine_review` — fires on quarantine state-machine transitions reachable by Provider (`PENDING` insert, `BENIGN_CLOSED`, `APPROVED`, `REJECTED`) and on a `post.status → NEEDS_REVIEW` transition\" — the named IT asserts a NOTIFY is emitted in the same transaction as the re-hide"
  - "A BENIGN re-eval verdict that closes quarantine rows (PENDING → BENIGN_CLOSED) emits a quarantine_review NOTIFY for that transition, in the same transaction — a named IT asserts the emission (today applyBenignReEval closes rows with no emit, while transitionToNeedsReview does emit)"
  - "Per docs/spec/security.md §Re-evaluation job — \"`BENIGN` on an UNKNOWN post → post transitions `QUARANTINED → READY` with Stage 1 redactions retained and the quarantine row transitions `PENDING → BENIGN_CLOSED`\" — after an UNKNOWN→BENIGN re-eval, a named IT asserts the post ends READY with non-empty tags, entity extraction and embedding completed, and a new_post NOTIFY emitted (today promoteToReady sets READY directly: ReadyPromoter — the only pg_notify('new_post') emit — never runs, TaggerWorker picks only status='RAW', so tags stay '{}' forever and no new_post fires)"
  - "A first-pass BENIGN verdict emits quarantine_review NOTIFYs only for the quarantine rows transitioned by THIS verdict, not for the post's previously-closed rows — a named test seeds a pre-existing BENIGN_CLOSED row and asserts no duplicate NOTIFY for it (today emitQuarantineNotifyForClosedRows SELECTs all BENIGN_CLOSED rows of the post)"
  - "Per docs/spec/security.md §Failure handling — \"**Stage 2 verdict** of `INJECTION`, `MALWARE`, or `UNKNOWN` → post stays `QUARANTINED` until admin review.\" — and §Re-evaluation job, whose queue feeds are infra-failure and UNKNOWN posts ONLY: a first-pass INJECTION or MALWARE post never enters the re-evaluation queue — a named test seeds status='QUARANTINED', stage2_done=TRUE, stage2_failed=FALSE, stage2_verdict='INJECTION', re_eval_attempts=0, runs a tick, and asserts the post is never enumerated (judge not invoked for it; status and counter unchanged); a second named test asserts an UNKNOWN-entry post that recorded INJECTION on an interim roll (re_eval_attempts > 0) IS still enumerated, so cap exhaustion → NEEDS_REVIEW stays reachable per \"the attempt counter increments\" (redteam 2026-06-07 finding 1)"
  - "Per docs/spec/security.md §Quarantine workflow — \"**Redactions are lifted only by `/quarantine approve`.** ... An admin reviewing the quarantine row is the only path that restores the original span.\" — admin approval is terminal over re-evaluation: migration V41 amends approve_quarantine to clear stage2_failed, and a named IT approves a released (READY, stage2_failed=TRUE) post via the stored procedure, runs a re-eval tick with a non-BENIGN judge stub queued, and asserts the post stays READY (never re-enumerated, never re-hidden) (redteam 2026-06-07 finding 2)"
  - "Per docs/spec/security.md §Re-evaluation job — \"**The transition is audit-logged** as `RE_EVAL_RELEASED` with ... `details_json={ prior_verdict, new_verdict='BENIGN', attempt }`\" — prior_verdict reflects the recorded stage2_verdict, not the post class: a named test drives an UNKNOWN-entry post through an INJECTION re-eval roll then a BENIGN roll and asserts the RE_EVAL_RELEASED row carries prior_verdict='INJECTION' (today priorVerdict derives from stage2_failed alone) (redteam 2026-06-07 finding 3a)"
  - "RE_EVAL_RELEASED records only actual releases: a BENIGN re-eval on a QUARANTINED Stage-2-infra-failure post requeues it through the normal pipeline (status RAW, stage2_failed cleared, quarantine BENIGN_CLOSED) with exactly ONE RE_EVAL_RELEASED row across the whole flow — a named test asserts the requeue and the single audit row (today clearStage2Failed leaves the post QUARANTINED while the audit row reports a release that never happened) (redteam 2026-06-07 finding 3b)"
  - "mvn -B clean verify from the repo root exits 0 (Flyway ITs prove V41 applies on a fresh DB)"
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
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/architecture.md §Inter-service communication
decision_refs: []
revisions:
  - date: 2026-06-07
    reason: redteam-finding refine (round 1, post-APPROVE) — absorb the three
      audit findings into scope; snapshot of the pre-refine contract fields
      follows (full pre-refine file was never committed; the branch's refine
      commit is its first durable form, so this snapshot is the audit trail)
    prior_files_budget: 8
    prior_migration_touch: false
    prior_risk: medium
    prior_files_scope:
      - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
      - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
      - infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java
      - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
      - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
    prior_out_of_scope:
      - the Provider-side quarantine_review CONSUMER (cursor/notify atomicity, payload trust, throttle keys, reconnect catch-up) — that is M1-181's; both tickets touch quarantine NOTIFY semantics, so coordinate rather than serialize
      - ReadyPromoter's gating logic and TaggerWorker's RAW-only pick — they are the normal pipeline this ticket routes back through, not targets; if the adjudicated direction requires loosening either, escalate rather than widening silently
      - the re-eval cap-exhaustion double admin notification (M1-181 leg 6)
      - /quarantine approve / reject stored-procedure paths
      - re-eval cadence, attempt-cap values, candidate enumeration predicates (except as the re-hide fix requires status transitions the predicate must keep excluding correctly)
    prior_acceptance_items: 6 (legs 1-5 + mvn verify; retained verbatim as items 1-5 and the final item of the refined list)
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
      files: 7
      added: 822
      removed: 57
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
      files: 10
      added: 1462
      removed: 90
  - round: 2
    date: 2026-06-07
    verdict: OVERRIDE-APPROVE
    checks:
      # carried through from the overridden MANUAL verdict; the FAIL
      # remains as the reviewer reported it — the verdict alone carries
      # the override.
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 1462
      removed: 90
    override_ref: 0
overrides:
  - date: 2026-06-07
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — Must-shrink only. Round 2 grew along ALL
      THREE dimensions vs round 1 (files 10 > 7, lines added 1462 > 822,
      lines removed 90 > 57), and the rule's sole exception does not
      apply: round 1's verdict was APPROVE with zero REWORK items, so
      there is no prior-round REWORK item the developer could cite to
      authorize growth (engineering-rules-verbatim.md §8 Round-N
      must-shrink).
    user_justification: |
      The growth is mandated, not divergent rework: the post-APPROVE
      redteam refine (commit bf97a9a, escalations[1]) added acceptance
      legs 6-9 — migration V41 plus four named test legs — so the round-2
      diff necessarily exceeds round 1 along every dimension. The
      reviewer confirms the growth is fully accounted for by legs 6-9
      and every other check is PASS. Same resolution as the M1-131
      precedent: a redteam refine on an APPROVEd in-flight diff always
      trips must-shrink; override, don't shrink.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-07
    category: INJECTION
    severity: high
    promise: |
      "Stage 2 verdict of INJECTION, MALWARE, or UNKNOWN → post stays QUARANTINED
      until admin review." + the re-eval queue is promised for infra-failure and
      UNKNOWN posts only (docs/spec/security.md §Failure handling, §Re-evaluation job).
    gap: |
      enumerateCandidates branch 2 (status='QUARANTINED' AND stage2_done AND NOT
      stage2_failed) never checks stage2_verdict, so first-pass INJECTION/MALWARE
      posts are re-rolled up to unknownCap; the new requeue path auto-releases them
      on any single BENIGN flip with no admin review. Pre-existing predicate
      (plan-outline Risk 5); the diff's requeue is the concrete release vector.
    repro: |
      Borderline-crafted feed post → Stage 2 INJECTION → QUARANTINED → re-rolled
      every tick → first BENIGN flip requeues to RAW → pipeline → READY + new_post.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-06-07
    category: AUDIT-EVASION
    severity: high
    promise: |
      "Redactions are lifted only by /quarantine approve. … An admin reviewing the
      quarantine row is the only path that restores the original span."
      (docs/spec/security.md §Quarantine workflow) — admin review is terminal authority.
    gap: |
      approve_quarantine (V25) never clears stage2_failed, so an approved
      infra-failure post stays in re-eval enumeration; the new reHideToQuarantined
      flips the admin-approved READY post back to QUARANTINED on the next non-BENIGN
      roll — unaudited, and unannounced (its quarantine rows are APPROVED, not
      PENDING, so reAnnouncePendingQuarantineRows matches nothing).
    repro: |
      Outage-released post → /quarantine approve (span restored, audited) → next
      re-eval tick judges the restored span → non-BENIGN → silent re-hide reversing
      the admin decision with zero audit trail.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-07
    category: AUDIT-EVASION
    severity: medium
    promise: |
      "The transition is audit-logged as RE_EVAL_RELEASED with details_json=
      { prior_verdict, new_verdict='BENIGN', attempt }" (docs/spec/security.md
      §Re-evaluation job).
    gap: |
      (a) priorVerdict derives from stage2_failed alone, never the recorded
      stage2_verdict — an INJECTION-then-BENIGN release is logged prior_verdict=
      'UNKNOWN', masking the hostile-flip signal. (b) the clearStage2Failed branch
      writes RE_EVAL_RELEASED + admin notification even when the post remains
      QUARANTINED (release-on-stage2-failure=false profile, or post-re-hide),
      producing a duplicate "release" audit row for a release that never happened.
    repro: |
      UNKNOWN → re-eval INJECTION (recorded) → re-eval BENIGN → audit says
      prior_verdict='UNKNOWN'. Separately: quarantined infra post re-evals BENIGN
      while staying hidden, yet a "released" audit row + notification fire.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-06-07
    verdict: FINDINGS
    base: ea9dc72 (fork point)
    head: working tree (pre-commit, post-APPROVE round 1)
    verdict_file: docs/plan/m1/redteam/M1-182-2026-06-07.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      2 high + 1 medium. All three are pre-existing surfaces this ticket declared
      out_of_scope (enumeration predicates; approve/reject stored procedures;
      RE_EVAL_RELEASED audit legs) made concretely reachable by the diff's correct
      spec-mandated transitions. No regression in the diff itself. Out-of-model:
      the stage2_failed "(or set …)" spec parenthetical divergence (also flagged
      by the round-1 reviewer as SPEC-CONFORMANCE WARN → spec amendment candidate)
      and a ready_at-non-NULL hardening note. Disposition: user decision via
      /m1-tick escalate M1-182 redteam-finding or remediation tickets.
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

## Round 1 redteam refine (2026-06-07)

Legs 1-5 are implemented and round-1 APPROVEd (diff lives on the branch
working tree). The pre-commit red-team audit
(`docs/plan/m1/redteam/M1-182-2026-06-07.md`) surfaced three findings on
surfaces the original contract declared out_of_scope; this refine absorbs
them as legs 6-9. Direction notes:

- **Leg 6 (finding 1, enumeration).** Suggested mechanism (Tier B,
  implementer's call): extend branch 2 of `enumerateCandidates` with
  `AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0)` — the
  queue-entry class is distinguishable without a migration because
  Stage2VerdictHandler always records the first-pass verdict, and interim
  re-eval rolls (which legs 1-5 now also record into `stage2_verdict`)
  are recognizable by the non-zero counter. First-pass INJECTION/MALWARE
  posts then sit QUARANTINED with PENDING rows for admin review, exactly
  as §Failure handling promises.
- **Leg 7 (finding 2, approve).** Migration **V41** (V39 = M1-189,
  V40 = M1-190, both pending — re-sweep all worktrees for V*.sql before
  creating the file): `CREATE OR REPLACE` of V25's `approve_quarantine`
  adding the `stage2_failed = FALSE` write. Nothing else in the procedure
  changes (out_of_scope pins the rest verbatim).
- **Leg 8 (finding 3a, prior_verdict).** `ReEvalCandidate` gains the
  recorded `stage2_verdict`; the enumeration SELECT extends by one column.
  Construction-site sweep done at refine time: 3 sites
  (ReEvaluationJob.enumerateCandidates, ReEvaluationJobTest.candidateFor,
  ReEvalVerdictNotifyIT.candidateFor) — all inside files_scope.
- **Leg 9 (finding 3b, audit accuracy).** Adjudicated direction: the
  infra-BENIGN branch requeues to RAW when the post is QUARANTINED
  (mirroring the UNKNOWN branch — the post now has a clean verdict and
  §Re-evaluation job says it \"continues through tagger and embedding if
  those stages had not already run\"); the existing clear-flag-only
  behavior remains for RAW posts (already in the pipeline, audit row
  legitimate). This also makes the BENIGN-after-re-hide path converge in
  one roll instead of two.
- **Test-seed authorization (extends the original grant):** UNKNOWN-class
  seeds across the reeval test dir (including ReEvaluationJobScheduledPathIT)
  must set `stage2_verdict='UNKNOWN'` to match the state
  Stage2VerdictHandler actually writes — leg 6's predicate keys on it.
  This ticket is AUTHORIZED to update those seeds, preserving the cap-math
  and INFRA_FAILURE-skip assertions as before.
- Out-of-model audit items deliberately NOT absorbed: the `stage2_failed`
  \"(or set ...)\" spec parenthetical (reviewer SPEC-CONFORMANCE WARN, same
  item) is a spec amendment, not code — tracked outside this ticket. The
  `ready_at`-non-NULL hardening note is advisory only.
