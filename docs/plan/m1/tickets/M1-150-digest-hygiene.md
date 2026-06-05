---
id: M1-150
title: "Digest hygiene (concurrency guard, timezone WARN, broad-catch narrow)"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the approval_status filter (covered by M1-129)
acceptance:
  - "DigestWorker has a same-group in-flight guard (map keyed groupId+slotKind) and the sentinel+audit writes span one transaction, so a tick overrun cannot duplicate audit rows or overlap same-group processing"
  - "DigestScheduler.parseTimezone WARNs once on an unparseable/null timezone (narrowed catch to DateTimeException) instead of silently skipping every tick"
  - "DigestWorker.execute narrows its broad catch (Exception) to SQLException | MessagingException so programming errors are not suppressed"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
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
      files: 7
      added: 322
      removed: 22
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-150: Digest hygiene

## Context

Three digest-path hygiene gaps: (B-DIGEST-CONCURRENCY) the audit INSERT commits
before the sentinel cache insert with no spanning tx, so a crash duplicates
audit rows and a tick overrun overlaps same-group processing;
(C-DIGEST-TZLOG) `parseTimezone` returns null on bad input and the group is
skipped silently every tick; (C-DIGESTWORKER-CATCH) `execute` catches generic
`Exception`, suppressing programming errors.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The approval filter is M1-129 (different concern, same module).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-DIGEST-CONCURRENCY,
  §C-DIGEST-TZLOG, §C-DIGESTWORKER-CATCH; `opus-47-full-handout.md` §F-PERF-13, F-MAINT-58/60/61.
