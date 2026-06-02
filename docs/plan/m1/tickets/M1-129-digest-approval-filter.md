---
id: M1-129
title: "DigestScheduler approval_status filter + negative-case fixture"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - DigestWorker concurrency / timezone hygiene (covered by M1-150)
  - the digest schedule cadence or content
acceptance:
  - "DigestScheduler's group query filters approval_status = 'approved' (in addition to removed_at IS NULL), so pending/rejected groups receive no digest"
  - "DigestRoundtripIT gains a pending fixture group and asserts no delivery to it (closing the test-seeds-only-the-passing-path gap)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 71
      removed: 20
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-129: DigestScheduler approval_status filter + negative-case fixture

## Context

`DigestScheduler.java:175` runs `SELECT id, timezone FROM groups WHERE
removed_at IS NULL` with no `approval_status='approved'` filter, so pending and
rejected groups receive periodic digests. The roundtrip IT seeds only approved
groups, so no fixture exposes the gap. Two convergent reporters, both High.

## Acceptance

See frontmatter. Add `AND approval_status='approved'`; add a pending fixture
group asserting no delivery.

## Out-of-scope

See frontmatter. The `DigestRoundtripIT` edit adds a negative-case fixture and
assertion — it does not weaken any existing assertion.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A14 (DIGEST-APPROVAL, High, GROUNDED);
  `opus-47-full-handout.md` §F-MAINT-06.
