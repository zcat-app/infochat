---
id: M1-164
title: "Adopt NullAway + Error Prone for §7a enforcement (umbrella)"
status: done
created: 2026-06-03
last_updated: 2026-06-05
blocked_by:
  - M1-164a
  - M1-164b
  - M1-164c
  - M1-164d
  - M1-164e
  - M1-164f
files_budget: 6
files_scope:
  - CLAUDE.md
  - docs/process/engineering-rules-verbatim.md
  - docs/process/reviewer-prompt.md
  - scripts/lint-contracts.py
  - scripts/lint-contracts-baseline.txt
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - per-module onboarding (each module is its own subticket, M1-164a..f)
  - Tier-1 Error Prone check promotions (M1-165, blocked on this umbrella)
  - any production code behavior change
acceptance:
  - "scripts/lint-contracts.py and scripts/lint-contracts-baseline.txt are removed (git rm); no remaining reference to either in build config or docs"
  - "CLAUDE.md §\"Method parameter contracts\", docs/process/engineering-rules-verbatim.md §7a, and the reviewer-prompt PARAMETER-CONTRACT-CHECK in docs/process/reviewer-prompt.md are rewritten to describe the non-null-by-default + NullAway model per D48: non-null is the package default, only @Nullable is written, the build (NullAway:ERROR) is the enforcement mechanism, and the reviewer no longer hand-checks annotation presence"
  - "mvn -B clean verify from the repo root exits 0 with NullAway:ERROR active across every module and the regex lint removed"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs:
  - D48
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
      added: 34
      removed: 384
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

# M1-164: Adopt NullAway + Error Prone for §7a enforcement (umbrella)

## Context

Decision D48 moves §7a parameter-contract / null-safety enforcement from the
author-side regex lint (`scripts/lint-contracts.py`) to NullAway (built on Error
Prone) wired into the Maven build, under a non-null-by-default model. This is
the umbrella for that adoption: the build wiring and per-module onboarding live
in subtickets M1-164a..f; this ticket is the **closer** — it runs only after
all modules are onboarded (`blocked_by` them) and performs the final doc flip +
lint retirement + repo-wide green gate. There is no separate JUnit integration
test: the whole-build `mvn verify` green with `NullAway:ERROR` active across
every module IS the integration assertion.

## Acceptance

See frontmatter. Retire the regex lint and its baseline; rewrite the three
engineering docs to the non-null-default + NullAway model; confirm the full
repo-wide build is green with NullAway gating.

## Out-of-scope

See frontmatter. The per-module onboarding (plugin activation + `@Nullable`
annotation + finding fixes) is done in the subtickets; this ticket must not
pre-empt them. The Tier-1 Error Prone check promotions are M1-165.

## Notes

- Rollout map (foundational module first; subtickets b–f depend only on the
  parent-pom wiring in `a`, so they are mutually independent and may run in any
  order — foundational-first is a signal-quality preference, not a hard dep):
  - `M1-164a` — parent-pom build wiring + onboard `infochat-core` + record D48
  - `M1-164b` — `infochat-ssrf`
  - `M1-164c` — `infochat-llm-adapter`
  - `M1-164d` — `infochat-messaging-adapter`
  - `M1-164e` — `infochat-collector`
  - `M1-164f` — `infochat-provider`
- The §7a doc rewrite is deliberately the LAST step (this umbrella). During the
  rollout, onboarded modules are gated by the build (NullAway) while not-yet-
  onboarded modules still rely on the reviewer's PARAMETER-CONTRACT-CHECK — the
  two coexist harmlessly until the flip.
- Validated toolchain (JDK 25 spike): error_prone_core 2.42.0, nullaway
  0.10.25, jspecify 1.0.0; `-XDcompilePolicy=simple`, `--should-stop=ifError=FLOW`,
  the `jdk.compiler` `--add-exports`/`--add-opens` set, `<fork>true</fork>`.
