---
id: M1-484
title: "Asset fetcher ignores SPI supported-asset/quote gate; dedup readBigDecimal"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 7
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Adding new asset commands or sources; only the existing fetch gate and the duplicated parse helper are touched."
acceptance:
  - >-
    AssetSnapshotFetcher consults the SPI's supported-asset / supported-quote-
    currency set before calling fetchSnapshot (currently tickOnePair calls
    fetchSnapshot directly, AssetSnapshotFetcher.java:164-176), so an
    asset/quote a source does not support is not misrouted through the D42
    upstream-health ladder (which would wrongly mark a healthy source degraded on
    a configuration mismatch). The SPI's supportedAssets/supportedQuoteCurrencies
    methods, currently dead in production, become the gate.
  - >-
    The readBigDecimal helper, currently triplicated byte-for-byte across
    CoingeckoSnapshotSource, KrakenSnapshotSource, and BitfinexSnapshotSource,
    exists in exactly one shared location consumed by all three.
  - >-
    A test asserts a configured asset/quote unsupported by a source is rejected
    at the gate (not routed to fetchSnapshot / the health ladder), and the shared
    readBigDecimal parses the same inputs identically.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcherSupportGateTest.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D42
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-484: Asset fetcher ignores SPI supported-asset/quote gate; dedup readBigDecimal

## Context

From `/deep-code-review full` (2026-06-27), report
`02-main-infochat-collector-00.md` findings F2 and F3 (verified at source).
**F2:** `AssetSnapshotFetcher.tickOnePair` calls `fetchSnapshot` directly
without consulting the SPI's supported-asset/quote gate; the SPI's
`supportedAssets`/`supportedQuoteCurrencies` are referenced only by the impls
themselves (dead in production), contradicting the SPI javadoc ("the fetcher
MUST NOT call fetchSnapshot for an asset absent from this set"). A
config/asset mismatch is then misrouted through the D42 upstream-health ladder
and can wrongly degrade a healthy source. **F3:** `readBigDecimal` is identical
13-line copy across the three snapshot sources.

## Acceptance

See frontmatter. Honor the SPI support gate before `fetchSnapshot`, and collapse
`readBigDecimal` to one shared helper; cover both.

## Out-of-scope

See frontmatter. No new assets/sources.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 02#F2 + 02#F3.
- The harmful misrouting is reachable under operator misconfig (an asset/quote
  in config absent from the source's supported set); the gate makes it a clean
  rejection instead.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-484-*.md
```
