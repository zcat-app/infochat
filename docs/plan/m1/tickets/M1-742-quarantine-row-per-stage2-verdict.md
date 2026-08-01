---
id: M1-742
title: "A quarantine row per non-BENIGN Stage 2 verdict"
status: done
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2FirstPassQuarantineRowIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictPersistenceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/QuarantinePendingNotifyIT.java
  - infochat-core/src/main/resources/db/migration/V70__quarantine_one_pending_stage2_row.sql
  - ADMIN_GUIDE.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The `approve_quarantine` owed-verdict guard — M1-741 owns it.
    This ticket's rows land on `stage2_done = TRUE` posts, which that
    guard does not block.
  - >-
    `quarantine_review_view` and the `quarantine` schema shape (V10) —
    the view already projects `flagged_by='stage2'` rows; only the new
    partial unique index is added.
  - >-
    Stage 1 row shapes and `Stage1Pipeline` — the stage1 write path is
    unchanged.
  - >-
    Any per-post grouping change to `/quarantine list`
    (`infochat-provider/**`) — the per-row presentation stays; the
    review convention is documented, not re-tooled.
acceptance:
  - >-
    `Stage2VerdictHandler.applyQuarantineVerdict` inserts the
    whole-body `flagged_by='stage2'` PENDING row on EVERY first-pass
    non-BENIGN verdict — M1-739's dedup predicate AND its FOR UPDATE
    lock are removed (with no check there is nothing to race; the
    insert becomes unconditional). The `stage2_<verdict>` rule_id
    convention stays. Pinned by a named test: a post WITH PENDING
    Stage 1 rows receiving a first-pass INJECTION verdict gets the
    stage2 row in addition (stage1 row untouched PENDING).
  - >-
    Authorized test retargets (new expected behavior, named here per
    the test-modification rule): (a)
    `Stage2FirstPassQuarantineRowIT.malwareWithPendingStage1RowInsertsNothing`
    is replaced by the item-1 test (the dedup it pinned is removed);
    (b) `QuarantinePendingNotifyIT`'s "an unsafe verdict must not
    re-fire PENDING" test now asserts an unsafe verdict fires exactly
    ONE PENDING NOTIFY — the stage2 row's — not zero.
    (c) `Stage2FirstPassQuarantineRowIT.rejectCommittingBeforeTheCheckCannotSuppressInsert`
    drops its lock-blocking assertion (the `verdictDone.await(2s)`
    must-not-commit check) — the FOR UPDATE lock it pinned is removed
    by item 1; the admin-reject interleaving and the final-state pin
    (the stage2 row is inserted even when a reject races the verdict)
    stay.
    (d) `Stage2VerdictPersistenceIT.duplicatePendingStage2RowFailsOnUniqueIndex`
    (added under item 3 before the redteam-finding refine) is
    retargeted per item 5: the handler-level throw becomes a
    swallow-and-log, so the unique-index pin moves to the DAO level
    and the handler level gains a benign-duplicate pin.
  - >-
    A new migration adds a partial unique index guaranteeing at most
    one PENDING `flagged_by='stage2'` row per post (closes the
    duplicate-verdict phantom insert from redteam
    `docs/plan/m1/redteam/M1-739-2026-08-01-r2.md`: a concurrent
    duplicate Stage 2 evaluation fails its insert instead of
    double-listing the post). Pinned by a named test.
  - >-
    Redteam-finding-2 remediation (`docs/plan/m1/redteam/M1-742-2026-08-01.md`,
    low): the stage2-row INSERT's unique violation
    (`idx_quarantine_one_pending_stage2_row`, SQLState 23505) is
    classified in `applyQuarantineVerdict` as the benign duplicate it
    is — a concurrent duplicate evaluation already committed the
    verdict AND the row, so the loser logs at INFO and commits (its
    post UPDATE replays the same values) instead of escaping
    `apply()` as an unclassified `IllegalStateException`. The escape
    route misclassifies a judged-hostile post down the "stage2 never
    ran" path (stale-RAW re-enqueue → INFRA_FAILURE application →
    spurious `stage2_failed=true` + a bounded re-judge cycle on an
    already-QUARANTINED post; the fail-open branch never touches
    `post.status`, so no release results — the fix removes the
    misclassification, not a visibility hole). Any NON-23505 failure
    still propagates. Pinned by named tests: (i) two direct
    `QuarantineDao.insertStage2Row` calls for one post — the second
    throws, SQLState 23505 (the item-3 index pin, DAO level); (ii)
    two sequential `apply()` INJECTION verdicts for one post — both
    return normally, exactly one PENDING stage2 row exists, the post
    is QUARANTINED with the verdict recorded (the handler-level
    benign-duplicate pin).
  - >-
    `ADMIN_GUIDE.md` documents the per-post review convention: a
    Stage-2-flagged post shows its Stage 1 rows plus one whole-body
    stage2 row (the verdict record, `rule_id = stage2_<verdict>`);
    approving the stage2 row forces READY without restoring spans (its
    placeholder is never woven into `post.body`), so a post's rows are
    reviewed together.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Quarantine workflow
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
      files: 10
      added: 602
      removed: 134
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-01
    category: DOS
    severity: medium
    promise: |
      "A per-post attempt counter bounds retries" (security.md
      §Re-evaluation job) and, for re-eval verdicts: "`INJECTION`,
      `MALWARE`, or `UNKNOWN` on either class → post stays
      `QUARANTINED`, the `stage2_failed` flag is preserved ... and the
      attempt counter increments. After cap exhaustion the post
      transitions to `NEEDS_REVIEW` ... and the admin notifier fires."
    gap: |
      V70 adds a partial unique index allowing at most one PENDING
      flagged_by='stage2' row per post, while the same diff makes the
      first-pass non-BENIGN verdict insert that row UNCONDITIONALLY.
      The new pin Stage2VerdictPersistenceIT.duplicatePendingStage2RowFailsOnUniqueIndex
      proves a colliding insert throws IllegalStateException, rolling
      back the ENTIRE verdict transaction — the same transaction that
      carries the post UPDATE and, on the re-eval path, the
      attempt-counter increment. A rolled-back transaction cannot
      increment the attempt counter, so the bound the spec promises
      never engages: no cap exhaustion, no NEEDS_REVIEW transition, no
      admin notification — while every re-eval tick spends a judge LLM
      call.
    repro: |
      Attacker publishes borderline content the judge first scores
      UNKNOWN; under M1-742 the first-pass verdict ALWAYS leaves a
      PENDING stage2 row. The post enters the re-eval queue. On re-eval
      the judge returns INJECTION; the re-hide/verdict insert collides
      with idx_quarantine_one_pending_stage2_row; the transaction
      rolls back; the attempt counter stays at 0. Every subsequent
      re-eval tick repeats the LLM call and the rollback — unbounded
      judge spend per attacker post until a human reviews the
      first-pass row, with no NEEDS_REVIEW escalation ever firing.
    suggested_fix_class: other
  - date: 2026-08-01
    category: INJECTION
    severity: low
    promise: |
      "Stage 2 infrastructure failure ... → release as `READY` with
      the Stage 1 redactions retained" is the fail-OPEN default —
      bounded by "a degraded judge must never auto-release"
      (security.md §Failure handling); trust boundary 4: "No post
      becomes user-visible without passing the layered ingest checks."
    gap: |
      The V70 unique index is a deterministic guard on the security
      pipeline, and its violation escapes Stage2VerdictHandler.apply()
      as an unclassified IllegalStateException AFTER the judge has
      already returned a real INJECTION/MALWARE verdict. Nothing in
      the diff classifies this exception at the caller: if it lands in
      the Stage 2 infrastructure-failure path, the spec's fail-open
      default releases the post the judge just judged hostile —
      inverting the guard's purpose.
    repro: |
      Two Stage 2 evaluations of the same post run concurrently. Both
      judge the attacker's post INJECTION. Evaluator A commits
      QUARANTINED + the stage2 row. Evaluator B's insert hits the
      unique index and throws out of apply(). If B's failure handling
      classifies the throw as a Stage 2 infrastructure failure, the
      fail-open path marks the post READY with Stage 1 redactions
      retained — overwriting A's QUARANTINED and publishing content
      the judge explicitly classified INJECTION.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-08-01
    verdict: FINDINGS
    base: ef4d04c8d35abb82f6fcbee19b9dd0f418fcb623
    head: working-tree (m1/M1-742-quarantine-row-per-stage2-verdict, --in-progress gate)
    verdict_file: docs/plan/m1/redteam/M1-742-2026-08-01.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Pre-review gate audit (medium DOS on the re-eval/V70-index
      interaction, low INJECTION on unclassified unique-violation
      escaping apply()). Surfaced for user decision via
      /m1-tick escalate M1-742 redteam-finding.
  - date: 2026-08-01
    verdict: FINDINGS
    base: ef4d04c8d35abb82f6fcbee19b9dd0f418fcb623
    head: working-tree (m1/M1-742-quarantine-row-per-stage2-verdict, post-remediation re-audit)
    verdict_file: docs/plan/m1/redteam/M1-742-2026-08-01-r2.md
    findings_count: 1
    out_of_model_count: 0
    note: |
      Re-audit after the finding-2 remediation. Finding 2 confirmed
      closed by the adversary. Finding 1 re-reported under a new
      mechanism ("every re-eval verdict enters applyQuarantineVerdict")
      — falsified: ReEvaluationJob never calls
      Stage2VerdictHandler.apply(); re-eval dispatch is
      applyNonBenignReEval's own writes with the stage2-row insert
      double-guarded (hidden && reAnnounced==0).
clarity_check:
  date: 2026-08-01
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-742: A quarantine row per non-BENIGN Stage 2 verdict

## Context

Follow-up to M1-739 (2026-08-01, user decision to file). M1-739 landed
the dedup-guarded half: a first-pass non-BENIGN verdict inserts the
stage2 whole-body row only when NO PENDING row exists. V10's header
(`infochat-core/src/main/resources/db/migration/V10__quarantine.sql:4-7`)
promised the unconditional shape — "a future Stage-2 row per LLM-judge
non-BENIGN verdict (flagged_by = 'stage2', landed by M1-033)" — and
docs/spec/security.md §Quarantine workflow says "Every Stage 1 or
Stage 2 hit creates a quarantine row". This ticket delivers the
contract literally: every non-BENIGN verdict leaves its own
review-trail row carrying the exact body the judge saw — the only
retention-independent primary record of the judged original (post
partitions drop on the retention horizon; `quarantine` is
unpartitioned and survives by design; reconstruction from
`post.body` + stage1 spans dies with the partition). It also deletes a
race class instead of managing it: with no dedup predicate there is no
time-of-check, so M1-739's FOR UPDATE serialization becomes
unnecessary — concurrent paths can only over-report (admin-visible
duplicates, further bounded by the new unique index), never
under-report (a hidden QUARANTINED post).

## Acceptance

See the YAML `acceptance:` list. In prose: make the stage2 row
unconditional on first-pass non-BENIGN verdicts (retargeting the two
M1-739-era tests that pin the dedup), add a partial unique index
bounding duplicates to one PENDING stage2 row per post, and document
the per-post review convention in ADMIN_GUIDE.md.

## Out-of-scope

The `approve_quarantine` guard (M1-741), any view/schema-shape change,
Stage 1's write path, and any provider-side queue re-tooling — the
per-row list presentation stays as-is.

## Notes

- The migration number in `files_scope` is indicative — V70 assumes
  M1-741 takes V69; renumber at implementation (M1-740 is also in
  flight).
- The `span_end` UTF-16 code-unit convention noted in M1-739's ticket
  applies unchanged: harmless while spans are whole-body and no
  DB-side span splice exists; do NOT "fix" the unit row-by-row here.
- The M1-739 redteam rounds (`docs/plan/m1/redteam/M1-739-2026-08-01*.md`)
  are the analysis base: round 1's TOCTOU finding motivated the dedup
  lock this ticket removes, and round 2's out-of-model duplicate-verdict
  phantom insert is what the unique index closes.
- Intermediate-state note for the admin guide: approving rows one at a
  time can publish a partially-redacted post mid-review — that
  semantics predates this ticket (multi-row Stage 1 posts behave the
  same) and is documented, not changed, here.
- Redteam disposition (2026-08-01, user decision — disposition-only
  refine): the r1+r2 audits (`docs/plan/m1/redteam/M1-742-2026-08-01*.md`)
  returned one actionable finding and one false positive.
  - Finding 2 (low, INJECTION — unclassified 23505 escaping `apply()`)
    was remediated in-branch (acceptance item 5) and confirmed closed
    by the r2 re-audit.
  - Finding 1 (medium, DOS — "V70 rolls back the re-eval attempt
    counter forever") is a FALSE POSITIVE under both mechanisms the
    adversary proposed. r1 mechanism ("the re-eval insert collides"):
    the re-eval insert is double-guarded — it fires only under
    `hidden && reAnnounced == 0`
    (`ReEvaluationJob.applyNonBenignReEval`), and the UNKNOWN class is
    always already-QUARANTINED, so `reHideToQuarantined` is a no-op
    and no insert is ever issued. r2 mechanism ("every re-eval verdict
    enters `applyQuarantineVerdict`"): `ReEvaluationJob` never calls
    `Stage2VerdictHandler.apply()` — its only Stage 2 contact is
    `stage2Worker.judgeBody()`, which applies no side effects; re-eval
    dispatch is `applyNonBenignReEval`'s own writes. The attempt
    counter therefore increments and commits every tick and cap
    exhaustion → NEEDS_REVIEW engages as specced. No code change
    warranted.
