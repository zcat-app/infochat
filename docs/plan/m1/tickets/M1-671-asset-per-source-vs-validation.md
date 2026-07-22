---
id: M1-671
title: "Validate --vs against the quote currency each pair actually fetches"
status: pending
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetPerSourceCurrencyTest.java
  - USER_GUIDE.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    FETCHING additional quote currencies. Today `asset_config` is keyed
    `(asset, sub_verb)` with a single `default_quote_currency NOT NULL`, and
    `AssetSnapshotFetcher.tickOnePair` fetches exactly that one value, so at
    most ONE currency per pair can ever exist in `price_snapshot`. Supporting
    a SET of currencies is a feature, not this bug fix: it needs an
    `asset_config` model change (migration), a fetcher loop, and an upstream
    rate-limit analysis the class javadoc explicitly guards ("Per-pair
    scheduling would multiply outbound traffic by N and break the upstream
    rate-limit budget") — CoinGecko can batch several `vs_currencies` in one
    call, but Kraken/Bitfinex need one request per quote symbol. File that
    separately if the capability is wanted; this ticket makes the ADVERTISED
    surface match the FETCHED reality.
  - >-
    Adding per-`sub_verb` `supported_vs` to `bootstrap-assets.json`. The
    original draft of this ticket proposed that; it is deliberately dropped
    (see Notes) because it would still promise currencies no pair fetches
    (e.g. coingecko+czk) and would create a THIRD declaration site to drift.
  - >-
    The Collector's `*SnapshotSource.SUPPORTED_VS` constants. They correctly
    describe each upstream's capability and stay as-is; they are simply not
    the right source of truth for what a user can ask for today.
  - "The doc-cleanup findings F1-F5 — tracked in M1-670 (doc-only)."
  - "Non-asset commands."
acceptance:
  - >-
    `/zcash <sub-verb> --vs <currency>` is validated against the SELECTED
    pair's `asset_config.default_quote_currency` — the only currency that pair
    can have data for. `AssetRegistry` already loads that column per
    `(asset, sub_verb)` (AssetRegistry.java:154-164 `SELECT … ,
    default_quote_currency FROM asset_config`), so the value is in hand at the
    validation site; no new config channel and no DB change.
  - >-
    AssetHandlerTest.rejectsUnavailableQuoteCurrency passes — `/zcash bitfinex
    --vs czk` returns ERROR_ASSET_UNSUPPORTED_QUOTE_CURRENCY naming the
    available currency (usd), NOT the generic
    "No price data available … The fetcher may not have run." reply.
  - >-
    AssetPerSourceCurrencyTest.rejectsSupportedByUpstreamButNeverFetched passes
    — `/zcash coingecko --vs czk` is ALSO rejected with the same friendly
    error. This is the case the original per-source design would have let
    through: czk is in `CoingeckoSnapshotSource.SUPPORTED_VS`, yet no czk row
    can exist, so accepting it only defers the misleading reply. Symmetric for
    `/monero coingecko --vs czk`.
  - >-
    A request for the pair's actual currency still succeeds end-to-end —
    `/zcash bitfinex --vs usd`, `/monero kraken --vs usd`, and bare `/zcash`
    (default sub-verb, no `--vs`) all reach the snapshot lookup and render a
    price card. No false rejection, no regression.
  - >-
    The "No price data available … The fetcher may not have run." reply is
    reached ONLY for a genuine cold-start/no-row condition on an AVAILABLE
    currency, never as the response to a currency mismatch — a currency the
    deployment cannot serve is now refused at the command boundary with an
    actionable message.
  - >-
    USER_GUIDE.md:333-335 is corrected: it currently tells users "Supported
    currencies differ per source: Coingecko accepts usd, eur, czk, btc; Kraken
    accepts usd, eur, btc; Bitfinex accepts usd, btc", which describes upstream
    CAPABILITY and reads as a user-facing promise. It must state what the
    deployment actually serves (the configured quote currency per pair, usd in
    the shipped `bootstrap-assets.json`) and may mention the per-source
    capability only as future/out-of-scope context.
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - "infochat-provider/.../command/asset/AssetPerSourceCurrencyTest.java"
    - "infochat-provider/.../command/asset/AssetHandlerTest.java (rejectsUnavailableQuoteCurrency)"
  preserves:
    - "infochat-provider/.../command/asset/AssetHandlerTest.java (existing cases)"
    - "infochat-provider/.../command/asset/AssetCommandsRoundtripIT.java"
    - "infochat-provider/.../command/asset/AssetSnapshotReaderTest.java"
    - "infochat-collector/.../assets/AssetSnapshotFetcherSupportGateTest.java"
spec_refs:
  - "docs/spec/commands.md §Asset commands"
decision_refs:
  - "D33"
  - "D34"
---

# M1-671: Validate --vs against the quote currency each pair actually fetches

## Context

Two findings, one root cause, one fix site.

**F6 (release audit, `.scratch/release-audit.md`).** `USER_GUIDE.md:333-335`
tells users "Supported currencies differ per source: Coingecko accepts usd,
eur, czk, btc; Kraken accepts usd, eur, btc; Bitfinex accepts usd, btc." Only
the COLLECTOR enforces those sets (`CoingeckoSnapshotSource.java:56`,
`KrakenSnapshotSource.java:60`, `BitfinexSnapshotSource.java:55`, gated in
`AssetSnapshotFetcher.tickOnePair`). The Provider validates `--vs` against the
ASSET-level `supported_vs` from `bootstrap-assets.json` (`["usd","eur","czk",
"btc"]` for both assets), so `/zcash bitfinex --vs czk` is accepted and falls
through to a generic no-data reply.

**Live-test finding (2026-07-22 15:45, isolated test instance).** The same
generic reply appears for combinations the upstream DOES support, which the
per-source fix alone would not catch:

```
/zcash bitfinex  --vs czk → No price data available for /zcash bitfinex yet.
                            The fetcher may not have run.
/zcash coingecko --vs czk → No price data available for /zcash coingecko yet.
                            The fetcher may not have run.
/zcash bitfinex            → renders a normal price card (fetcher IS healthy)
```

The message is doubly wrong: the fetcher had run seconds earlier
(`asset_config.last_success_at` current; `price_snapshot` held 794 rows per
pair, newest 15:45:25), and it can never produce czk.

**Root cause (verified in schema + code).** `asset_config` is keyed
`(asset, sub_verb)` with a single `default_quote_currency NOT NULL` — `usd` on
all six shipped rows. `AssetSnapshotFetcher.tickOnePair` fetches exactly
`row.defaultQuoteCurrency()`. `AssetSnapshotReader` selects
`WHERE asset = ? AND sub_verb = ? AND vs_currency = ?`, and only
`infochat_collector` holds INSERT on `price_snapshot` (V17), so there is no
on-demand fetch. Therefore **no non-default quote currency can ever have data**,
for any source. `--vs` is advertised in `/help` and USER_GUIDE but is
non-functional for every value except the configured default.

So the defect is not "the Provider checks the wrong capability list" — it is
"the Provider checks a CAPABILITY list when it should check AVAILABILITY".

## Acceptance

See the YAML `acceptance:` list. The behavioral contract: a `--vs` value the
selected pair cannot serve is refused at the command boundary with the existing
`ERROR_ASSET_UNSUPPORTED_QUOTE_CURRENCY` reply naming what IS available; the
available currency still renders a price card; the "fetcher may not have run"
message is reserved for a genuine missing-row condition; and the USER_GUIDE
promise is brought back in line with what the deployment serves.

## Out-of-scope

See the YAML `out_of_scope:` list. Chiefly: this ticket does not make the
Collector fetch more currencies. That capability needs an `asset_config` model
change plus a rate-limit budget analysis, and is a feature ticket.

## Notes

- **Why this supersedes the original per-source design.** The first draft of
  this ticket proposed adding a per-`sub_verb` `supported_vs` to
  `bootstrap-assets.json` and validating against the SOURCE's set. That fixes
  `/zcash bitfinex --vs czk` but explicitly accepts `/monero coingecko --vs
  czk` ("proceed to the snapshot lookup — no false rejection"), which still
  ends in the misleading no-data reply, so the acceptance criteria would go
  green while the user-visible bug survived. It also added a third place where
  currency support is declared (JSON config, Collector constants, and the DB
  column that actually governs fetching) — the drift risk that draft's own
  "Drift awareness" note flagged. Validating against
  `asset_config.default_quote_currency` uses the one value that provably
  determines whether data can exist, needs no new config, and is strictly
  smaller.
- **Alternatives considered:** (a) fetch every `supported_vs` currency —
  rejected here as a feature with migration + rate-limit cost (recorded in
  `out_of_scope` so it can be filed deliberately); (b) convert USD→other at
  read time using an FX rate — rejected: introduces an FX data source, a second
  attribution/ToS surface, and rounding/staleness semantics the spec does not
  define.
- **Adjacent code:** `AssetHandler.java:128-142` (the `--vs` resolution block
  to change), `AssetRegistry.java:154-164` (already SELECTs
  `default_quote_currency` into the per-pair record — no change expected there;
  if the value is not currently surfaced on the record the handler reads, that
  plumbing is in scope). `asset_config` stays read-only on the Provider (V14
  GRANTs) and no Flyway migration is added.
- The `error.asset.unsupported_quote_currency` bundle key already formats a
  suggestion plus an available-list (`en.properties:545`), so no new
  localization key — and therefore no D43 cs-twin work — is required.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-671-asset-per-source-vs-validation.md
```
