---
id: M1-171
title: "BanCommandHandler stale M1-041-deferral javadoc"
status: done
created: 2026-06-05
last_updated: 2026-06-05
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 1
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any non-comment change to BanCommandHandler (code, SQL constants, tests)
  - javadoc edits in any other file (the 2026-06-05 re-eval grep found this is the
    only main-source file still claiming direct audit_log writes / a deferred
    M1-041 consolidation)
acceptance:
  - "BanCommandHandler class javadoc no longer claims the handler writes audit rows directly to audit_log nor that the M1-041 AuditLogWriter consolidation is deferred; it states that audit rows go through the shared infochat-core AuditLogWriter (matching the auditLogWriter.write call the class actually makes)"
  - "the shared-request-id sentence (BAN + INVITE_REVOKE correlation as the spec's canonical correlated-rows shape) is preserved"
  - "grep -rn 'consolidation is deferred' infochat-provider/src/main/java infochat-collector/src/main/java returns no matches"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §What lives in design notes
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 13
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-171: BanCommandHandler stale M1-041-deferral javadoc

## Context

The `BanCommandHandler` class javadoc still says "The handler writes audit rows
directly to `audit_log` (the M1-036 / M1-039 pattern). The M1-041 AuditLogWriter
consolidation is deferred." — but M1-041 was reopened and landed: the class
injects `AuditLogWriter` and calls `auditLogWriter.write(conn, row)`; the single
`INSERT INTO audit_log` site lives in `infochat-core`. The javadoc contradicts
the code one screen below. Found during the 2026-06-05 Tier-D re-evaluation of
`docs/plan/audit/parallelization.md` §Excluded (AUDIT-INSERT-DUP); it survived
the M1-158 stale-comment sweep because that ticket's finding list predated the
M1-041 reopen.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. Pure comment edit — single file, no behavior change.

## Notes

- Per CLAUDE.md §Commit prefixes this edits a source-file javadoc, so it stays a
  ticket rather than a `process:` commit (same reasoning as M1-158).
