---
id: M1-739
title: "First-pass Stage 2 verdicts on row-less posts bypass the admin review queue"
status: done
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
files_budget: 13
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2Worker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Worker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/FirstPassStage2RowBenignCloseIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/QuarantinePendingNotifyIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2BenignNotifyScopeIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2FirstPassQuarantineRowIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictPersistenceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/testsupport/ScanWindowFixtureGuardTest.java
  - docs/plan/m1/scan-window-fixture-census.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The re-evaluation re-hide path's BEHAVIOR (`ReEvaluationJob`,
    `ReEvaluationJobTest`) — M1-738 (86ffcd5f) owns it, including the
    stage2-row BENIGN-close symmetry. This ticket is the first-pass
    twin of that fix. Pure rename propagation of
    `QuarantineDao.insertReEvalRow` → `insertStage2Row` across both
    files (call sites plus comment/javadoc references, zero behavioral
    change to the re-hide path) is IN scope per the ticket Notes'
    rename authorization — the rename is impossible without touching
    the caller.
  - >-
    `Stage2VerdictHandler.closeStage1QuarantineRowsAndEmit`'s
    stage1-only predicate: at first-pass BENIGN time no stage2 row can
    exist yet (the only stage2 writers are this ticket's insert, which
    fires on non-BENIGN first-pass, and M1-738's re-hide insert),
    so the predicate needs no change. Re-eval BENIGN closes stage2
    rows via M1-738's widened `ReEvaluationJob.closeQuarantineRows`.
  - >-
    Any change to `quarantine_review_view` or the `quarantine` schema
    (V10) — same reasoning as M1-738: the gap is the missing ROW.
  - >-
    infochat-provider/** — the `/quarantine list` read side is
    correct.
acceptance:
  - >-
    Premise re-verified at start: `Stage2VerdictHandler.applyQuarantineVerdict`
    (INJECTION / MALWARE / UNKNOWN first-pass) updates the post to
    QUARANTINED without inserting any quarantine row, so a post with no
    Stage 1 rows never enters `quarantine_review_view`. If a row insert
    has since appeared on that path, re-scope before implementing.
  - >-
    A named test passes: a post with NO quarantine rows receiving a
    first-pass INJECTION (or MALWARE / UNKNOWN) verdict gets a PENDING
    `flagged_by='stage2'` quarantine row inserted in the same
    transaction as the post UPDATE, so it appears in
    `quarantine_review_view` and the `/quarantine list` admin queue,
    with the quarantine-review NOTIFY the Stage 1 insert emits.
  - >-
    No duplicate row: a first-pass non-BENIGN verdict on a post that
    already carries PENDING Stage 1 rows inserts nothing (those rows
    already place the post in the queue) — pinned by a named test.
  - >-
    The UNKNOWN-first-pass interplay is covered: a first-pass UNKNOWN
    post (row inserted here) that a later re-eval rolls BENIGN has the
    row closed by M1-738's widened `ReEvaluationJob.closeQuarantineRows`
    — pinned by a named test or by extending an existing re-eval IT.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-01
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: FAIL
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 1004
      removed: 49
  - round: 2
    date: 2026-08-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 1037
      removed: 53
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-01
    category: AUDIT-EVASION
    severity: low
    promise: |
      "Every Stage 1 or Stage 2 hit creates a quarantine row ... and a
      review status ∈ {PENDING, BENIGN_CLOSED, APPROVED, REJECTED}" and
      "A Stage 2 INJECTION, MALWARE, or UNKNOWN verdict hides the entire
      post (QUARANTINED status); the quarantine row stays PENDING
      (subject to admin review...)" (docs/spec/security.md §Quarantine
      workflow) — i.e. a QUARANTINED post always carries a PENDING row
      that keeps it present in the admin review queue.
    gap: |
      The new dedup predicate is a time-of-check whose answer can go
      stale before commit: applyQuarantineVerdict runs the EXISTS at
      READ COMMITTED with no lock on the quarantine rows, and
      reject_quarantine locks only the quarantine row (never the post),
      so a concurrent admin reject can commit between the check and the
      verdict commit — post QUARANTINED with zero PENDING rows,
      invisible to the queue, and not in either re-eval class
      (INJECTION/MALWARE), so nothing recovers it. Not
      adversary-steerable (requires a coincident trusted-admin reject),
      hence low.
    repro: |
      1) Stage-1 rows commit PENDING, judge call in flight. 2) Admin
      rejects the Stage 1 rows; the reject commits in the window after
      the verdict tx's SELECT EXISTS returned true and before its
      commit. 3) Verdict tx commits INJECTION: post QUARANTINED, no
      stage2 row inserted, all rows REJECTED. 4) /quarantine list shows
      nothing; no re-eval class picks the post up.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-08-01
    verdict: FINDINGS
    base: af41ca821fcfcad6f9f4ebc817521db6a9dfe62f
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-739-2026-08-01.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Round 1 of the /m1-tick run redteam gate (ahead of review). One
      low TOCTOU finding on the dedup EXISTS (admin-reject race window);
      escalated for user decision (fix in branch vs accept residual).
      Out-of-model: pre-existing approve_quarantine READY-over-in-flight-
      verdict race, surfaced for a possible future base-code ticket.
  - date: 2026-08-01
    verdict: CLEAN
    base: af41ca821fcfcad6f9f4ebc817521db6a9dfe62f
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-739-2026-08-01-r2.md
    out_of_model_count: 2
    note: |
      Round 2 re-audit of the remediated diff (FOR UPDATE serialization
      fix + race IT): the round-1 low TOCTOU finding is genuinely
      closed. Out-of-model advisories: duplicate-verdict phantom insert
      (over-reports only; possible hardening ticket) and the carried-
      over pre-existing approve_quarantine race (base-code ticket).
clarity_check:
  date: 2026-08-01
  verdict: PASS
  warnings:
    - "self-check: premise re-verified at start (acceptance item 1) — applyQuarantineVerdict writes no quarantine row; only Stage1Pipeline calls QuarantineDao.insert, ReEvaluationJob calls insertReEvalRow"
    - "self-check: judged-body sourcing TBD at implementation — prefer in-transaction SELECT of post.body/post.uid if it matches the judge input, else thread body through Stage2Worker per ticket Notes"
  blockers: []
escalation_reason:
---

# M1-739: First-pass Stage 2 verdicts on row-less posts bypass the admin review queue

## Context

Follow-up to M1-738 (2026-08-01). M1-738 closed the re-evaluation
half of this gap: a post re-hidden to QUARANTINED with no quarantine
row now gets one. The FIRST-PASS half remains open and is arguably
the more common path: `Stage2VerdictHandler.applyQuarantineVerdict`
flips a post to QUARANTINED on a first-pass INJECTION / MALWARE /
UNKNOWN verdict without inserting a quarantine row (grep confirmed
2026-07-31: only `Stage1Pipeline` calls `QuarantineDao.insert`). A
post the regex Stage 1 cleared but the LLM judge catches sits
QUARANTINED yet invisible to `/quarantine list`, contradicting
docs/spec/security.md §Quarantine workflow's "Every Stage 1 or
Stage 2 hit creates a quarantine row" and "stays QUARANTINED until
admin review" — and V10's header already promises "a future Stage-2
row per LLM-judge non-BENIGN verdict (flagged_by = 'stage2')".
Surfaced during M1-738's start self-check and recorded in its
out_of_scope; the user deferred it here on 2026-07-31.

## Acceptance

See the YAML `acceptance:` list. In prose: mirror M1-738 on the
first-pass path — insert a whole-body `flagged_by='stage2'` PENDING
row (with NOTIFY, same transaction as the post UPDATE) when a
first-pass non-BENIGN verdict hits a post with no PENDING quarantine
row; insert nothing when one exists.

## Notes

- Reuse `QuarantineDao.insertReEvalRow` (M1-738) if its shape fits —
  it is the stage2 whole-body insert — rather than adding a third
  write shape; renaming/generalizing it (e.g. `insertStage2Row`) is
  in scope for `QuarantineDao.java`. The first-pass path must source
  the judged body: check what `Stage2Worker` already passes to
  `Stage2VerdictHandler.apply` and thread the body through if needed.
- The dedup predicate is "no PENDING row for the post", not "no row
  at all" — same reasoning as M1-738 (a BENIGN_CLOSED row is closed
  history; a fresh non-BENIGN judgment needs a fresh review row).
- **Design note (from M1-738's redteam out-of-model, adjudicated
  2026-08-01):** `span_end` on the stage2 whole-body rows is a Java
  UTF-16 code-unit count, while any future DB-side span splice
  (`approve_quarantine`, T2-G) would count Postgres characters.
  Harmless while spans are whole-body and no DB-side splice exists,
  and Stage 1 rows carry the same UTF-16 convention — do NOT
  "fix" the unit row-by-row. Whichever ticket lands the DB-side
  splice must solve the unit question for ALL rows uniformly.
- `rule_id` convention from M1-738: `reeval_<verdict>`. Pick the
  first-pass analogue (e.g. `stage2_<verdict>`) and keep it stable
  for admin grouping.
