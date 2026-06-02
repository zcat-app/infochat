---
id: M1-126
title: "Asset-command extensibility (operator-config driven) + Locale.ROOT"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - InboundRouter.handleSlash beyond the minimal asset-dispatch fallback branch, if the chosen option touches it
  - AssetRegistry / bootstrap-assets loading — unchanged
  - the price-snapshot schema (covered by the M1-161 investigate-skeleton)
acceptance:
  - "A third asset added to bootstrap-assets.json (e.g. litecoin) is dispatchable as /litecoin without a new hardcoded CommandHandler — covered by a test that registers a third asset and asserts the slash dispatcher routes it to the asset handler"
  - "The probation gate and the dispatcher agree: an asset the gate's AssetCommandFamilyOracle accepts is actually dispatchable (no 'pass the gate then Unknown command' path)"
  - "Asset-command token lowercasing uses Locale.ROOT (not the JVM default locale)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
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
      files: 7
      added: 122
      removed: 68
escalations:
  - date: 2026-06-02
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalated at start, before any review round. Chosen design
      (Option A: asset-dispatch fallback in InboundRouter.handleSlash that
      consults AssetCommandFamilyOracle before UNKNOWN_COMMAND_REPLY) must
      edit InboundRouter.java, which lay outside the original files_scope
      (command/asset only). out_of_scope already carved in the minimal
      fallback branch; files_scope was widened to match.
revisions:
  - date: 2026-06-02
    reason: budget-breach refine — add InboundRouter.java to files_scope
    snapshot:
      files_budget: 5
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: Locale.ROOT lowercasing is verifiable by inspection only; consider a named test using a locale-sensitive character (e.g. Turkish İ) so the criterion is checkable from test output."
    - "FILES-BUDGET-PLAUSIBLE: budget of 5 is plausible but tight if implementation takes option B (@Produces list) and adds a dedicated locale-fix test alongside the dispatch test."
    - "SELF-CONTAINED-CHECK: the body defers the option A vs. option B implementation choice to the implementer; a mid-round design decision is expected — document it in the commit message per the Better-alternatives rule."
  blockers: []
---

# M1-126: Asset-command extensibility (operator-config driven) + Locale.ROOT

## Context

`AssetCommandRouter.java:24-55` declares two static inner `CommandHandler` beans
returning `"zcash"` / `"monero"`. A third asset in `bootstrap-assets.json` loads
into `asset_config`/`price_snapshot` but has no `CommandHandler`, so `/litecoin`
→ "Unknown command" — even though `AssetCommandFamilyOracle.isAssetCommand`
returns true, so a probation user passes the gate then gets Unknown command.
`commands.md` §Asset commands commits to operator-config-driven extensibility
"without a new top-level command per verb." Bundled: `AssetHandler.java:156,160`
lowercases tokens with the JVM-default locale instead of `Locale.ROOT`.

## Acceptance

See frontmatter. Either a router fallback that consults
`AssetCommandFamilyOracle` before `UNKNOWN_COMMAND_REPLY` (deletes
`AssetCommandRouter`), or a `@Produces` per-asset `CommandHandler` list that
iterates the registered assets. Decide which during implementation; the
acceptance is "third asset is dispatchable without code change."

## Out-of-scope

See frontmatter. The price-snapshot schema divergence is a separate
investigate-skeleton (M1-161).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A6 (ASSET-ROUTER, Critical, GROUNDED) +
  C-ASSET-LOCALE; `opus-47-full-handout.md` §F-MAINT-04, F-MAINT-52;
  `opus-47-only-handout.md` §TP5, M27.
- Option A (opus-47): router fallback, deletes the file. Option B (opus-47-full):
  `@Produces` list — survives runtime `AssetRegistry.refresh()` only if the producer
  is re-evaluated; weigh that.
