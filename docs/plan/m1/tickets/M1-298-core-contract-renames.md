---
id: M1-298
title: "Core contracts: dispatchKey rename, TargetKind enum, nullable-UUID bind helper"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 64
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest
  - infochat-core/src/main/java/app/zcat/infochat/core/audit
  - infochat-core/src/test/java/app/zcat/infochat/core
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any behaviour change — all three items are mechanical contract clarifications; bytes on the wire, SQL, and audit rows are identical (the enum serializes to the same strings).
  - The audit_log schema and V5 CHECK constraint — the enum mirrors it, the constraint does not change.
  - NormalizedPost fields other than the rename.
acceptance:
  - "U-55: NormalizedPost.sourceId is renamed dispatchKey to match its own contract (its javadoc: 'it is NOT the source.id UUID' — the rest of the SPI calls this value dispatchKey); FULL call-site sweep including tests and test doubles (grep all construction sites before finalizing — the recorded call-site rule from M1-160/M1-175); no compile reference to the old name survives."
  - "U-57: AuditRow.targetKind's free String becomes a TargetKind enum whose values mirror the closed V5 CHECK set exactly (action is already an enum; targetKind is the straggler, ~46 files: 43 .targetKind( call-site files plus RedactionHook.java itself plus 2 positional new AuditRow sites in DigestScheduler and RetryCommandHandler); a named test pins enum-values ↔ CHECK-set parity by reading the migration or the information_schema constraint."
  - "U-72 rider: AuditLogWriter's open-coded UUID-bind ladder collapses to a setNullableUuid helper (opus-47/02#F3); audit rows written before/after are byte-identical (existing audit tests stay green unmodified)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 54
      added: 255
      removed: 99
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-12
    reason: budget-breach refine — verified call-site sweep showed union ≈59
      files vs budget 24; widened files_budget and corrected the U-57
      fan-out estimate
    snapshot:
      files_budget: 24
      acceptance_u57_excerpt: "targetKind is the straggler, ~25 call sites"
escalations:
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-implementation call-site sweep: U-57 targetKind enum fan-out
      is 43 files calling .targetKind( plus RedactionHook.java itself plus
      2 positional new AuditRow(...) sites (DigestScheduler,
      RetryCommandHandler) plus the new enum and parity test ≈ 48 files;
      U-55 dispatchKey rename touches 11 more (record accessor sites only);
      union ≈ 59 files vs files_budget: 24)
clarity_check:
  date: 2026-06-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-298: Core contracts: dispatchKey rename, TargetKind enum, nullable-UUID bind helper

## Context

Deep-review v5 verified **U-55** (MEDIUM, 3-model), **U-57** (LOW, unique
opus-48), plus the AuditLogWriter bind-ladder rider from U-72
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; sources `fable-5/02#F3`,
`mimo/02#F1`, `gpt-55#L-09`, `opus-48/02#F2`, `opus-47/02#F3` — gitignored;
all load-bearing facts inlined; verified 2026-06-11: NormalizedPost:44
`long sourceId` with the :16 javadoc disclaimer; AuditLogWriter:107
`ps.setString(5, redacted.targetKind())`).

Both headline items are "the type system should say what the contract
says": a field named against its own javadoc, and a closed string set the
DB CHECKs but Java passes free-form.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Call-site sweep is the whole risk** (recorded rule: M1-175 missed 8
  sites, M1-160 missed 12 test files). Budget (64, widened from 24 by the
  2026-06-12 budget-breach refine after the verified sweep found U-57 ≈48
  files + U-55 ≈11 files ≈ 59 union) carries the fan-out:
  grep `sourceId` within NormalizedPost-consuming code (collector fetchers,
  stream sources, persister, tests) and `targetKind` across all modules
  including raw test INSERTs, before finalizing the diff plan.
- files_scope is module-wide on collector/provider because the two renames
  fan out; the reviewer's negative-space check still applies item-wise —
  every touched file must trace to one of the three items.
- NullAway note: the new enum parameter is non-null by package default;
  any genuinely-nullable targetKind call site must surface as a design
  question, not a @Nullable shrug.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-298-*.md
```
