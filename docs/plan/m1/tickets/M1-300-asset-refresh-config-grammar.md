---
id: M1-300
title: "Asset-refresh config: one grammar (Duration) across both services"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 10
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/config/ConfigDefaultsConvergenceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The refresh cadence values themselves (90s base; per-profile values per design §10.4) — only the binding grammar converges.
  - The asset fetch/read logic.
acceptance:
  - "Both services bind infochat.assets.refresh.* with one grammar: Duration (\"90s\") — today Collector binds Duration against \"90s\" values while Provider binds long against \"90\" (verified 2026-06-11: collector application.properties:224-226 vs provider :141-142, AssetSnapshotReader @ConfigProperty long vs AssetSnapshotFetcher Duration), so no single operator override string is valid in both services, breaking the 'override is one property change' guarantee; after the fix one -Dinfochat.assets.refresh.<host>=<duration> string works in both."
  - "ConfigDefaultsConvergenceTest is strengthened to compare the SHIPPED per-profile values of the shared infochat.assets.refresh.* keys across the two services' property files (today it only asserts absence of inline defaults, so this grammar drift was invisible to the build); the test fails if the two services ship different values or grammars for a shared key."
  - "The three @SuppressWarnings(\"unused\") fail-fast Duration bindings the opus-48 report called dead are resolved INSIDE this ticket (report §6.6: they are documented deliberate fail-fast bindings, not dead code): keep them with their comment intact, or replace them with an explicit startup validation — record the choice in the commit message."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/config/ConfigDefaultsConvergenceTest.java
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
      files: 5
      added: 111
      removed: 44
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-300: Asset-refresh config: one grammar (Duration) across both services

## Context

Deep-review v5 verified **U-29** (HIGH per opus-48; report treats as
MEDIUM-HIGH; unique find) (`deep-code-review/v5/UNIFIED-REPORT.md` §3;
source `opus-48/01-architecture.md#F1` — gitignored; all load-bearing facts
inlined and re-verified 2026-06-11 against both property files and both
binding classes).

The same operator-facing key family is bound as two incompatible grammars:
`Duration`/"90s" in Collector, `long`(seconds)/"90" in Provider. An
operator overriding the documented `-Dinfochat.assets.refresh.<host>=…`
breaks whichever service got the other grammar. Canonical direction:
`Duration` everywhere (the richer grammar, already the collector's).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- The convergence-test strengthening is the durable half of the fix — the
  drift existed because the build could not see cross-service value/grammar
  divergence on shared keys.
- Coordination: M1-292 touches AssetSnapshotFetcher (log hygiene) —
  different region; check worktrees at start.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-300-*.md
```
