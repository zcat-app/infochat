---
id: M1-209
title: "Remove hand-written @NonNull made redundant by D48 null-marked packages"
status: done
created: 2026-06-07
last_updated: 2026-06-07
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 200
files_scope:
  - infochat-core/src
  - infochat-ssrf/src
  - infochat-llm-adapter/src
  - infochat-messaging-adapter/src
  - infochat-collector/src
  - infochat-provider/src
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - every @Nullable annotation — those carry the actual contract under D48 and stay exactly where they are
  - package-info.java null-marking declarations and the NullAway/Error Prone build configuration — the enforcement machinery is M1-164's landed work, untouched
  - any behavioral change whatsoever — in a null-marked package a bare reference type already means non-null, so removal is semantically a no-op; if removing an annotation changes any NullAway verdict, that site is escalated, not "fixed"
  - javadoc prose mentioning nullability — only the annotation token and its now-unused imports go
acceptance:
  - "Zero occurrences of the @NonNull annotation remain under any module's src tree: a repo-wide grep for '@NonNull' across */src returns no matches (draft-time count: 171 files / 1006 occurrences in */src/main, 189 files / 1070 occurrences including test sources)"
  - "Every import of org.jspecify.annotations.NonNull that the removal orphans is also removed (no unused imports introduced)"
  - "mvn -B clean verify from the repo root exits 0 — NullAway at ERROR severity across every module is the proof that every removal was a semantic no-op (the package default already said non-null)"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D48
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
      files: 192
      added: 816
      removed: 1015
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-209: Remove hand-written @NonNull made redundant by D48 null-marked packages

## Context

Unified finding X1 (`UNIFIED.md` §2, cross-cutting): every
`app.zcat.infochat` package is null-marked (NullAway
`AnnotatedPackages`, decision D48), so a bare reference type already
means "never null" and CLAUDE.md §Method parameter contracts says
"`@NonNull` is no longer written by hand" — yet 1006 hand-written
`@NonNull` across 171 main-source files (re-verified 2026-06-07;
1070/189 including test sources) predate the D48 onboarding and now
restate the package default.

Mechanical sweep. The build is the verifier: NullAway runs at ERROR
severity in every module, so any removal that was NOT a no-op fails
`mvn verify`.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T29 under `deep-code-review/v2/` (X1;
  consensus finding — opus-48, opus-47, kimi-folder all counted it).
- **Scheduling constraint (operational, deliberately NOT blocked_by —
  a whole-repo sweep blocked on every ticket would deadlock):** this
  sweep textually conflicts with every in-flight worktree. Start it
  when the in-flight set is minimal; any branch that forks before this
  lands must expect a rebase whose conflicts are resolution-trivial
  (the sweep only deletes annotation tokens and imports).
- Per-module draft-time file counts (main sources): provider 83,
  collector 37, messaging-adapter 28, core 11, llm-adapter 9, ssrf 3.
- Budget rationale: 189 affected files verified by grep + headroom,
  per the M1-164f undercount precedent (javac's 100-finding cap made
  the first NullAway estimate too low; this count is grep-based, not
  compiler-based, so the headroom is small).
