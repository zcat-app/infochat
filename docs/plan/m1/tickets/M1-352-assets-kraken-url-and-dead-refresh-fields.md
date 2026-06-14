---
id: M1-352
title: "assets: Kraken attribution URL must honour the quote currency; drop dead AssetSnapshotFetcher refresh fields"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The CoinGecko and Bitfinex attribution URLs — only Kraken hardcodes -usd-; the others are not in scope.
  - The asset_config schema / attribution_url column model — the fix constructs the correct Kraken URL from (asset, vs); it does not restructure how URLs are stored.
  - The @Scheduled refresh cadence expressions on AssetSnapshotFetcher — they resolve the infochat.assets.refresh.<host> keys directly via Quarkus interpolation and are unchanged; only the unused backing fields are removed.
acceptance:
  - "KrakenSnapshotSource.attributionUrl(asset, vs) and BootstrapAssetsLoader's kraken case produce a URL that reflects the quote currency vs (e.g. the per-pair page) rather than the hardcoded -usd- slug; a non-USD invocation no longer links to the USD chart. The class javadoc claiming Kraken's scheme 'requires' -usd- is corrected."
  - "A test asserts the Kraken attribution URL for a non-USD vs (e.g. eur/btc) differs from the USD URL and contains the quote currency."
  - "The three @SuppressWarnings(\"unused\") @ConfigProperty Duration fields (coingeckoRefresh, krakenRefresh, bitfinexRefresh) are removed from AssetSnapshotFetcher; the @Scheduled cadence still resolves from application.properties unchanged, and the class-level javadoc paragraph justifying the dead fields is removed/trimmed."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets (Kraken vs-aware URL assertion)
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap (bootstrap Kraken URL assertion, if one pins -usd-)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-352: Kraken attribution URL + dead refresh fields

## Context

Two deep-review v6 findings on the collector asset path, grouped (same
subsystem):

- **opus-47 `06-module-infochat-collector.md` F7** (medium) — Kraken attribution
  URL ignores quote currency. **Verified 2026-06-14:**
  `KrakenSnapshotSource.attributionUrl` returns
  `String.format("https://www.kraken.com/prices/%s-usd-%s-price-chart", asset, asset)`
  (line 183) and `BootstrapAssetsLoader` hardcodes the same `-usd-` (lines
  349-350). A `/zcash --vs btc` reply links to the USD chart — the user cannot
  validate the reported price against the linked page (spec §Asset commands:
  "every reply names its data source").
- **opus-47 `06-module-infochat-collector.md` F8** (medium, SIMPLIFICATION) —
  `AssetSnapshotFetcher` carries three `@SuppressWarnings("unused")
  @ConfigProperty` `Duration` fields. **Verified 2026-06-14:** fields at
  lines 122-132; the `@Scheduled` expressions (lines 145/151/157) resolve the
  same property keys independently, so the fields are dead state the class
  javadoc itself flags as a ticket-tracking artifact.

opus-48's collector pass reported no findings, but these are mechanical and
independently verified above.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The refresh keys' source of truth is `application.properties` + the
  `@Scheduled` expressions; removing the injected fields changes no runtime
  behaviour.
