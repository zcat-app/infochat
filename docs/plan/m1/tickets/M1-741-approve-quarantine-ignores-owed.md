---
id: M1-741
title: "approve_quarantine ignores an owed Stage 2 verdict"
status: done
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
files_budget: 10
files_scope:
  - infochat-core/src/main/resources/db/migration/V69__approve_quarantine_verdict_owed_guard.sql
  - infochat-collector/src/test/java/app/zcat/infochat/collector/notify/QuarantineProcedureNotifyIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/notify/ApproveQuarantinePhantomNotifyIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/QuarantineAuditBeforeEffectIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/resources/inbound-reflection-error-baseline.txt
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    `reject_quarantine` — it never publishes (no READY write, no
    `new_post` NOTIFY). Its mid-flight race was the M1-739 dedup
    TOCTOU, closed there by the FOR UPDATE serialization; nothing
    remains to guard on the reject side.
  - >-
    The per-verdict stage2 row shape and its partial unique index —
    M1-742 owns both. This ticket's guard predates and is independent
    of that change.
  - >-
    Any change to the eval pipeline (`Stage2VerdictHandler`,
    `Stage1Worker`, `Stage2Worker`, `Stage1Pipeline`) — the guard
    lives entirely in the DB function and its error surfacing.
  - >-
    `quarantine_review_view` and the `quarantine` schema (V10) — the
    guard needs no new column; the predicate reads existing `post`
    flags.
acceptance:
  - >-
    Premise re-verified at start: `approve_quarantine` (V21, as
    amended by V48 / V50 / V53) sets `post.status='READY'`
    unconditionally with no check of the post's eval state
    (`infochat-core/src/main/resources/db/migration/V21__quarantine_admin.sql:60-65`
    on the pre-M1-741 base). If a guard has since appeared, re-scope
    before implementing.
  - >-
    A new migration replaces `approve_quarantine` so it raises a
    clear exception and performs NO write (no row transition, no post
    UPDATE, no audit row, no `new_post` NOTIFY) when the row's post
    carries no recorded Stage 2 judgment and neither the operator nor
    cap exhaustion has already decided its fate:
    `stage1_flagged = TRUE AND stage2_verdict IS NULL AND
    status <> 'NEEDS_REVIEW' AND (status = 'QUARANTINED' OR
    stage2_failed = FALSE)`.
    - `stage2_verdict IS NULL` — not the originally drafted
      `stage2_done = FALSE` — is load-bearing (round-1 redteam,
      `docs/plan/m1/redteam/M1-741-2026-08-01.md`): the infra-failure
      path sets `stage2_done = TRUE, stage2_failed = TRUE` with no
      verdict, so a `stage2_done`-keyed predicate silently passes
      re-eval-queue posts, and approve's `stage2_failed = FALSE` clear
      would permanently drop them from re-evaluation unjudged.
    - The `(status = 'QUARANTINED' OR stage2_failed = FALSE)` disjunct
      covers BOTH unjudged bitmaps (round-2 redteam): the
      first-pass-in-flight state is `status = 'RAW',
      stage2_failed = FALSE` (Stage 1 leaves flagged posts RAW so
      Stage 2 can judge — a QUARANTINED-only conjunct never fires
      there), and the fail-closed re-eval-queue state is
      `status = 'QUARANTINED', stage2_failed = TRUE`.
    - Two unjudged states stay approvable by design: cap-exhausted
      NEEDS_REVIEW posts (re-eval gave up; the admin's review IS the
      judgment) and fail-open released posts (RAW,
      `stage2_failed = TRUE` — the operator's posture already released
      the content with redactions; lifting them is the documented
      admin-approve lifecycle, V41-pinned in
      `ReEvalVerdictNotifyIT.adminApprovedReleasedPostIsNeverReEnumeratedOrReHidden`).
    Pinned by named tests driving the procedure directly (in
    `QuarantineProcedureNotifyIT`), including the RAW in-flight case and
    the QUARANTINED infra-failure case.
  - >-
    The legitimate approve paths keep working, pinned by named tests:
    a watchdog/fail-closed QUARANTINED post (`stage1_flagged = FALSE`)
    approves normally; a post with the verdict recorded
    (`stage2_verdict IS NOT NULL`, whether first-pass or re-eval)
    approves normally; a cap-exhausted NEEDS_REVIEW post with no
    recorded verdict (`stage2_verdict IS NULL`,
    `status = 'NEEDS_REVIEW'`) approves normally — re-eval has
    exhausted its attempts, so the admin's review is the judgment;
    and the fail-open released infra-failure post (status RAW, no
    recorded verdict) stays approvable per the V41 pin in
    `ReEvalVerdictNotifyIT`.
  - >-
    The guard's refusal reaches the admin as a clean localized command
    error via the existing `mapStoredProcError` path in
    `QuarantineCommandHandler` — pinned by a named test; extending the
    mapping with a new bundle key is in scope if the current mapper
    cannot distinguish the guard's exception.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - QuarantineProcedureNotifyIT.approveRaisesWhenStage2VerdictOwed
    - QuarantineProcedureNotifyIT.approveRaisesWhenInfraFailureReEvalOwed
    - QuarantineProcedureNotifyIT.approveSucceedsForWatchdogQuarantinedPost
    - QuarantineProcedureNotifyIT.approveSucceedsWhenStage2VerdictRecorded
    - QuarantineProcedureNotifyIT.approveSucceedsForCapExhaustedNeedsReviewPost
    - QuarantineProcedureNotifyIT.approveSucceedsForFailOpenReleasedPost
    - QuarantineCommandHandlerTest.approve_verdictOwed_mapsToVerdictOwedNotInternal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 947
      removed: 49
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-01
    category: INFO-LEAK
    severity: medium
    promise: |
      "Redactions are lifted only by `/quarantine approve`... An admin
      reviewing the quarantine row is the only path that restores the
      original span" (docs/spec/security.md §Quarantine workflow), and "a
      degraded judge must never auto-release" (§Failure handling). The
      ticket's own premise is that approve must not release a post whose
      Stage 2 verdict is still owed.
    gap: |
      The guard predicate is stage1_flagged = TRUE AND stage2_done =
      FALSE, but the Stage 2 infra-failure path sets stage2_done = TRUE,
      stage2_failed = TRUE with the verdict unrecorded
      (Stage2VerdictHandler.applyInfraFailure lines 218-236). A
      QUARANTINED post in the re-eval queue (release-on-stage2-failure=
      false) PASSES the guard; approve then clears stage2_failed, so the
      post permanently exits re-evaluation unjudged — original span
      restored, new_post NOTIFY fired, no re-hide can ever fire. The V69
      header comment's claim that stage2_failed posts are refused is
      factually wrong about the flag state.
    repro: |
      (1) Fail-closed profile (release-on-stage2-failure=false). (2)
      Adversary content trips a Stage 1 regex → PENDING quarantine row.
      (3) Judge outage → infra failure → QUARANTINED, stage2_done=TRUE,
      stage2_failed=TRUE, no verdict, queued for re-eval. (4) Admin runs
      /quarantine approve on the indistinguishable PENDING row. (5)
      Guard passes; span restored, READY, NOTIFY fires, stage2_failed
      cleared — never judged, permanently user-visible.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-08-01
    category: INFO-LEAK
    severity: medium
    promise: |
      "Stage 2 is the actual security boundary" (docs/spec/security.md
      §Ingest pipeline) and §Failure handling: "a degraded judge must
      never auto-release". The guard must refuse an approve while the
      first-pass Stage 2 verdict is in flight.
    gap: |
      The round-1 remediation's predicate added `status = 'QUARANTINED'`,
      but a Stage-1-flagged post owed its first-pass verdict is RAW, not
      QUARANTINED (Stage1Pipeline: "post.status stays 'RAW'... The ONLY
      exceptions are the watchdog and sanitizer-exception fail-closed
      paths"; Stage1Worker documents the bitmap). The guard never fired
      on the production in-flight state, and the pin tests seeded the
      impossible QUARANTINED bitmap, so the suite was green while the
      guard was dead where it mattered most. A stranded verdict (crash
      before commit) would leave the approved post permanently released
      unjudged.
    repro: |
      (1) Adversary content trips a Stage 1 regex; post stays RAW,
      PENDING quarantine row written, judge call queued. (2) Admin runs
      /quarantine approve. (3) Guard's status='QUARANTINED' conjunct is
      false on the RAW post; approve restores the span, sets READY,
      fires new_post NOTIFY. (4a) A later INJECTION verdict re-hides,
      but the announcement already happened. (4b) If the verdict never
      lands, no recovery path re-judges the READY post.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-08-01
    verdict: FINDINGS
    base: 76de249dc0df56f600758d99c3d61caf51df012b
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-741-2026-08-01.md
    findings_count: 1
    out_of_model_count: 0
    note: |
      1 medium INFO-LEAK: the guard misses the infra-failure/re-eval
      class (stage2_done=TRUE, stage2_failed=TRUE, no verdict). Run
      halted ahead of review per run.md step 4; awaiting
      /m1-tick escalate M1-741 redteam-finding.
  - date: 2026-08-01
    verdict: FINDINGS
    base: 76de249dc0df56f600758d99c3d61caf51df012b
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-741-2026-08-01-r2.md
    findings_count: 1
    out_of_model_count: 0
    note: |
      Round-2 re-audit of the round-1 remediation. Round-1 finding
      confirmed closed; new medium INFO-LEAK: the `status =
      'QUARANTINED'` conjunct never fires on the production
      first-pass-in-flight state (Stage 1 leaves flagged posts RAW) and
      the pin tests seeded the impossible QUARANTINED bitmap. Remediated
      with the full `(status = 'QUARANTINED' OR stage2_failed = FALSE)`
      disjunct and RAW-seeded tests; round-3 re-audit follows.
  - date: 2026-08-01
    verdict: CLEAN
    base: 76de249dc0df56f600758d99c3d61caf51df012b
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-741-2026-08-01-r3.md
    out_of_model_count: 0
    note: |
      Round-3 re-audit of the final predicate. Both prior findings
      verified closed; CLEAN. This is the audit covering the diff that
      goes to review.
clarity_check:
  date: 2026-08-01
  verdict: WARN
  warnings:
    - >-
      Self-check found the original files_scope incomplete:
      QuarantineAuditBeforeEffectIT seeds stage1_flagged=TRUE /
      stage2_done=FALSE and approves (fixture must move to
      stage2_done=TRUE), and a clean refusal message needs a new
      bundle key (BundleKeys + en/cs.properties). User approved the
      scope+budget refine (files_budget 6 -> 8) via the blocking
      start-time question; scope updated accordingly.
  blockers: []
escalation_reason:
---

# M1-741: approve_quarantine ignores an owed Stage 2 verdict

## Context

Surfaced as an out-of-model item in both M1-739 redteam rounds
(`docs/plan/m1/redteam/M1-739-2026-08-01.md`, `-r2.md`):
`approve_quarantine` sets `post.status='READY'` and fires the
`new_post` NOTIFY unconditionally. An admin working the review queue
can approve a Stage 1 row while the post's first-pass Stage 2 verdict
is still in flight — the window is seconds normally and stretches
under semaphore queue-wait + retry backoff (`Stage2Worker`). The post
is then READY and announced to users until the verdict transaction
commits and re-hides it: judge-condemned content is user-visible for
the remainder of the window, against the intent of
docs/spec/security.md §Failure handling ("a degraded judge must never
auto-release" — here a trusted admin releases it unknowingly, before
the judgment exists). M1-739 made the END state self-healing (the
verdict re-hides and the post now always carries a PENDING queue row),
so the residual is exactly the transient exposure. `stage1_flagged` is
`BOOLEAN NOT NULL DEFAULT FALSE` (V7__joins_post.sql:156) and only the
regex path sets it TRUE (`Stage1Pipeline.updatePostBodyAndFlags`); the
watchdog / match-overflow / sanitizer fail-closed paths never set it,
so gating on `stage1_flagged = TRUE` blocks nothing legitimate. The
verdict-owed half of the predicate keys on `stage2_verdict IS NULL`
(V22, nullable; V36 CHECK-constrained to the closed verdict set):
every judgment-recording path (first-pass BENIGN, INJECTION / MALWARE /
UNKNOWN, and re-eval verdicts) writes `stage2_verdict`, while the
infra-failure path leaves it NULL with `stage2_done = TRUE,
stage2_failed = TRUE` (`Stage2VerdictHandler.applyInfraFailure`) —
so `stage2_verdict IS NULL` means precisely "no Stage 2 judgment has
ever been recorded". The two unjudged bitmaps differ in
`status`/`stage2_failed`: the first-pass-in-flight state is RAW with
`stage2_failed = FALSE` (Stage 1 leaves flagged posts RAW so Stage 2
can judge — `Stage1Pipeline`), the fail-closed re-eval-queue state is
QUARANTINED with `stage2_failed = TRUE`; the guard's
`(status = 'QUARANTINED' OR stage2_failed = FALSE)` disjunct covers
both. (The pre-refine draft keyed on `stage2_done = FALSE` — round-1
redteam showed it silently passes the infra-failure class; the first
refine keyed on `status = 'QUARANTINED'` alone — round-2 showed it
never fires on the RAW in-flight state. See `redteam_findings:`.)
Cap-exhausted posts
(`status = 'NEEDS_REVIEW'`, verdict still NULL) are exempt — re-eval
has given up, so the admin's review is the judgment — as are fail-open
released posts (status RAW, `release-on-stage2-failure=true`): the
content is already user-visible with redactions, and lifting them via
admin approve is the documented lifecycle (V41-pinned).

## Acceptance

See the YAML `acceptance:` list. In prose: amend `approve_quarantine`
in a new migration to refuse (clear exception, zero writes) when the
row's post is still owed a Stage 2 verdict; keep watchdog-quarantined
and verdict-recorded posts approvable; surface the refusal to the
admin as a clean localized error.

## Out-of-scope

`reject_quarantine` (never publishes; its race closed in M1-739), the
per-verdict stage2 row work (M1-742), any eval-pipeline change, and
any schema/view change — the guard reads existing `post` flags only.

## Notes

- The migration number in `files_scope` is indicative — V69 is next
  free at drafting (2026-08-01), but M1-740 is in flight and other
  migration tickets may land first; renumber at implementation.
- Alternatives considered: a Java-side pre-check in
  `QuarantineCommandHandler.handleApprove` — rejected: the function is
  the atomic boundary (row lock + transition + post UPDATE in one
  transaction); a handler pre-check would reintroduce a
  check-then-act window between the handler's read and the function's
  write.
- The stage2 row M1-739 inserts in the race case is unaffected:
  approving THAT row happens on a `stage2_done = TRUE` post, which the
  guard does not block.
- `mapStoredProcError` already maps stored-procedure exceptions to
  localized admin replies; the guard's exception should ride that path
  (new bundle key only if the mapper cannot distinguish it).
