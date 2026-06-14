---
id: M1-340
title: "Asset freshness: decouple Provider staleness window from the Collector cadence keys"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
  - infochat-provider/src/main/resources/application.properties
  - docs/spec/commands.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
  - infochat-provider/src/test/java/app/zcat/infochat/provider/config/ConfigDefaultsConvergenceTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Collector's own infochat.assets.refresh.<host> fetch-cadence keys — they are legitimately Collector-owned (the @Scheduled asset tick runs at them); not removed. Only the Provider's DUPLICATE copy + its derivation of staleness from that copy is changed.
  - The AssetSnapshotFetcher / AssetDataSource fetch path — unchanged.
  - asset_config / price_snapshot schema — no migration; the freshness window is a Provider-owned property, not a new column.
acceptance:
  - "The Provider no longer derives its staleness threshold from a hand-duplicated copy of the Collector's infochat.assets.refresh.<host> cadence keys. AssetSnapshotReader compares snapshot.capturedAt against a single Provider-owned, profile-driven freshness window property (the spec already distinguishes a 'profile-driven freshness window' from the Collector's refresh interval — commands.md §Asset commands). The three infochat.assets.refresh.* @ConfigProperty fields and their per-profile property blocks are dropped from the Provider, removing the cross-module hand-sync."
  - "A one-sided operator override no longer desyncs the freshness contract: tightening the Collector's per-host cadence without mirroring it on the Provider can no longer leave the Provider computing staleness against a stale window (the Provider window is independent and profile-driven), eliminating the suppressed/false stale-warning failure mode."
  - "docs/spec/commands.md §Asset commands is amended so the freshness contract is expressed against the Provider-owned freshness window (and/or asset_config.last_success_at row age), not '2x the Collector cadence key', keeping spec and code aligned."
  - "A test pins the decoupling: a snapshot older than the Provider freshness window is reported stale and one within it fresh, using only the Provider-owned window — with no infochat.assets.refresh.* property present on the Provider side. Existing asset-snapshot reader tests stay green (updated to the new property)."
  - "ConfigDefaultsConvergenceTest (in infochat-provider/.../config/) pins the OLD invariant that M1-340 reverses for the Provider, so its two Provider-asset-cadence assertions are removed: assetsRefreshKeysHaveNoInlineDefaultInProvider() (reflects the three coingecko/kraken/bitfinexRefresh fields, which are dropped — was M1-210 item 4) and assetsRefreshKeysConvergeAcrossServices() (asserts Collector and Provider ship the same infochat.assets.refresh.* key set, which no longer holds once the Provider keys are removed — was M1-300 item 2), along with their now-unused helpers/imports (refreshKeys, the PROVIDER/COLLECTOR_PROPERTIES paths, the AssetSnapshotReader import). The unrelated profileLabelDefaultIsUnknownAcrossProviderBeans() test and the configDefault() helper it uses stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset (freshness-window basis)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/config/ConfigDefaultsConvergenceTest.java (remove the two Provider-asset-cadence assertions that pin the reversed invariant — see acceptance)
  preserves:
    - all tests currently green on main EXCEPT assetsRefreshKeysHaveNoInlineDefaultInProvider() and assetsRefreshKeysConvergeAcrossServices(), whose pinned invariant this ticket deliberately reverses for the Provider
spec_refs:
  - docs/spec/commands.md §Asset commands
decision_refs:
  - D39
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 156
      removed: 167
escalations:
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — files_scope breach surfaced during implementation grounding,
      before any code change. Acceptance items 1 and 4 require dropping the
      three infochat.assets.refresh.* @ConfigProperty fields AND removing the
      keys from the Provider's application.properties. But
      infochat-provider/src/test/java/app/zcat/infochat/provider/config/ConfigDefaultsConvergenceTest.java
      (outside files_scope; sibling of the in-scope command/asset test dir)
      pins the OLD invariant in two tests:
        - assetsRefreshKeysHaveNoInlineDefaultInProvider() reflects fields
          coingeckoRefresh/krakenRefresh/bitfinexRefresh -> NoSuchFieldException
          once dropped.
        - assetsRefreshKeysConvergeAcrossServices() asserts collector and
          provider ship the same infochat.assets.refresh.* key set -> fails
          once the Provider keys are removed.
      Satisfying acceptance item 5 (mvn verify exits 0) is impossible without
      modifying this out-of-scope test. files_scope must be widened (refine).
revisions:
  - date: 2026-06-14
    reason: |
      budget-breach refine (round 1). Widened scope to authorize removing the
      out-of-scope test that pins the invariant this ticket reverses.
    snapshot:
      files_budget: 4
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
        - infochat-provider/src/main/resources/application.properties
        - docs/spec/commands.md
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
      acceptance_count: 5
      test_plan_modifies:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset (freshness-window basis)
      test_plan_preserves:
        - all tests currently green on main
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-340: Asset freshness — decouple Provider staleness from Collector cadence

## Context

Deep-review v5.5 (opus-48, `01-architecture.md` F3) found that the
`infochat.assets.refresh.*` cadence keys are hand-duplicated across both services'
property files, and the Provider derives its staleness window from its own copy.
**Verified at source 2026-06-14:** the keys exist in both
`infochat-collector/.../application.properties` and
`infochat-provider/.../application.properties:149-151` (+ per-profile blocks),
and the Provider's copy even carries a comment admitting the hand-sync
(application.properties:142-145); `AssetSnapshotReader` reads
`infochat.assets.refresh.<host>` and computes `stale = age > interval*2`.

The key is semantically Collector-owned (the Collector's `@Scheduled` asset tick
runs at it). The Provider has no fetch loop; it reads the key only to reconstruct
a staleness threshold. An operator who tightens the Collector's cadence via the
documented `-Dinfochat.assets.refresh.<host>=...` override, but does not mirror it
on the Provider, leaves the Provider computing staleness against the old window —
suppressing legitimate stale warnings (or emitting false ones in the opposite
direction). The defect is invisible until the documented override is exercised.

The spec already names a distinct "profile-driven freshness window" separate from
the Collector's refresh interval, so wiring the Provider to its own window
(report Option A) both matches the spec and removes the hand-sync.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- This touches code + properties + spec together, so it is a ticket (not a pure
  `spec:` commit). The companion pure-doc asset-SPI corrections (commands.md
  "reuse the Fetcher SPI" sentence and design/10's "implements Fetcher" note) are
  doc-only and are handled as a `spec:` commit — see the review summary.
- Option A shifts the staleness threshold from "2× cadence" to an independent
  freshness-window value (arguably more correct, what the spec describes). Set a
  sensible per-profile default for the window.
