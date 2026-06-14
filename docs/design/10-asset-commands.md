# 10 — Asset commands

Companion to `docs/spec/commands.md` §"Asset commands" and decision D39.                                                                                                                                                                              
Spec commitment: per-asset top-level commands (`/zcash`, `/monero`) with
sub-verbs that select a data source. This file holds the implementation                                                                                                                                                                               
shape — class layout, table DDL, bootstrap file schema, reply layout,
ToS notes, and what's deliberately deferred.

## 10.1 v1 scope

Enabled assets and sub-verbs in v1:

| Asset | `coingecko` | `kraken` | `bitfinex` | Notes |                                                                                                                                                                                               
  |---|---|---|---|---|                                                                                                                                                                                                                                 
| `/zcash` (ZEC) | yes | yes | yes | Default sub-verb: `coingecko` |                                                                                                                                                                                  
| `/monero` (XMR) | yes | yes | yes | Default sub-verb: `coingecko` |                                                                                                                                                                                 

Asymmetric availability is permitted. Adding `/bitcoin` later would                                                                                                                                                                                   
likely enable `coingecko` + `kraken` + `bitfinex` + `coinbase` +
`binance`; the asymmetry is encoded in `bootstrap-assets.json`, not in                                                                                                                                                                                
code.

**Out of v1:** any data source that requires an API key or auth token                                                                                                                                                                                 
(KuCoin, Gemini for most endpoints, CoinGecko Pro). Adding them needs                                                                                                                                                                                 
the operator-secret SPI surface, which is its own decision.

**Out of v1:** the websocket "live" mode. See §10.9.

## 10.2 Class layout

  ```                                                  
  infochat-collector                                                                                                                                                                                                                                    
  └── assets/                                                                                                                                                                                                                                           
      ├── AssetSnapshotFetcher           // @Scheduled bean: drives AssetDataSource impls                                                                                                                                                                                 
      ├── source/                                                                                                                                                                                                                                       
      │   ├── AssetDataSource            // SPI: fetch one snapshot for (asset, vs)                                                                                                                                                                     
      │   ├── CoingeckoSnapshotSource    // impl        
      │   ├── KrakenSnapshotSource       // impl       
      │   └── BitfinexSnapshotSource     // impl        
      └── store/                                        
          └── PriceSnapshotStore         // writes price_snapshot rows
                                                                                                                                                                                                                                                        
  infochat-provider                                     
  └── commands/                                                                                                                                                                                                                                         
      └── asset/                                                                                                                                                                                                                                        
          ├── AssetCommandRouter         // dispatches /zcash, /monero, …                                                                                                                                                                               
          ├── AssetHandler               // base, common sub-verbs                                                                                                                                                                                      
          ├── ZcashHandler               // adds Zcash-specific verbs (future)                                                                                                                                                                          
          ├── MoneroHandler              // adds Monero-specific verbs (future)                                                                                                                                                                         
          ├── AssetReplyRenderer         // plain-text rendering, attribution                                                                                                                                                                           
          └── AssetSnapshotReader        // reads price_snapshot, scope-filtered                                                                                                                                                                        
  ```                                                   

`AssetDataSource` SPI sketch:

  ```java                                                                                                                                                                                                                                               
  public interface AssetDataSource {                                                                                                                                                                                                                    
      String id();                                 // "coingecko", "kraken", …                                                                                                                                                                          
      Set<String> supportedAssets();               // {"zcash","monero",…}                                                                                                                                                                              
      Set<String> supportedQuoteCurrencies(String asset);                                                                                                                                                                                               
      PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException;                                                                                                                                                                       
      String attributionUrl(String asset, String vs);   
  }                                                                                                                                                                                                                                                     
  ```                                                   

`PriceSnapshot` is a value object (immutable Java record). It is **not**                                                                                                                                                                              
a `Post`. It never enters the eval pipeline.

## 10.3 Storage

A new collector-owned table, outside the post pipeline:

  ```sql                                                                                                                                                                                                                                                
  CREATE TABLE price_snapshot (                        
      id              BIGSERIAL PRIMARY KEY,                                                                                                                                                                                                            
      asset           TEXT NOT NULL,    -- "zcash", "monero"                                                                                                                                                                                            
      source          TEXT NOT NULL,    -- "coingecko", "kraken", "bitfinex"                                                                                                                                                                            
      vs_currency     TEXT NOT NULL,    -- "usd", "btc", "czk"                                                                                                                                                                                          
      price           NUMERIC(24,12) NOT NULL,         
      volume_24h      NUMERIC(28,8),                                                                                                                                                                                                                    
      high_24h        NUMERIC(24,12),                   
      low_24h         NUMERIC(24,12),                  
      change_1h_pct   NUMERIC(8,4),                                                                                                                                                                                                                     
      change_24h_pct  NUMERIC(8,4),                     
      change_7d_pct   NUMERIC(8,4),                                                                                                                                                                                                                     
      captured_at     TIMESTAMPTZ NOT NULL,                                                                                                                                                                                                             
      fetched_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),                                                                                                                                                                                               
      raw_payload     JSONB                                                                                                                                                                                                                             
  );                                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  CREATE INDEX idx_price_snapshot_lookup                                                                                                                                                                                                                
      ON price_snapshot (asset, source, vs_currency, captured_at DESC);                                                                                                                                                                                 
  ```                                    

Reads serve the **latest** row per `(asset, source, vs_currency)`. The
table is append-only; old rows expire by partition drop on the same                                                                                                                                                                                   
TTL convention as posts (D33). 30-day retention is enough for
historical-curiosity questions ("show me ZEC's 7d high last week") and                                                                                                                                                                                
keeps the table bounded.

Nullable numeric columns let degraded fetches store partial data
(e.g. an exchange that omits 7d Δ%). The renderer skips missing fields                                                                                                                                                                                
in the reply — it does not invent zeros.

## price_snapshot dedup & notify decisions

Two divergences between the spec contract and the landed plumbing,
each resolved with its own verdict.

### Dedup invariant — verdict: restore `UNIQUE (asset, sub_verb, captured_at)`

The landed DDL declares `PRIMARY KEY (id, captured_at)` (Postgres
requires the partition key in every PK/UNIQUE on a partitioned table;
the surrogate `id` keeps the PK narrow) but carries no replacement
`UNIQUE` for the spec mandate "one row per
`(asset, sub_verb, captured_at)`" (`schema.md` §Operational — Price
snapshot). Resolution: a successor migration adds the `UNIQUE`; the
spec sentence stands unamended.

Rationale:

- The latest-snapshot read is
  `WHERE asset = ? AND sub_verb = ? AND vs_currency = ?
  ORDER BY captured_at DESC LIMIT 1` — no tiebreaker beyond
  `captured_at`. Were two rows able to share the largest `captured_at`
  for a pair with divergent `price`, the read would be
  nondeterministic, violating the deterministic-SQL-retrieval
  convention. Blessing the surrogate PK instead would require showing
  duplicates cannot perturb this read; the absent tiebreaker means
  exactly the opposite.
- The constraint is legal on the partitioned parent because
  `captured_at` is the partition key.
- The spec triple (without `vs_currency`) is compatible with every
  writer: each fetcher poll produces exactly one snapshot per
  `(asset, sub_verb)`, quoted in `asset_config.default_quote_currency`,
  so two same-instant rows differing only in `vs_currency` cannot
  legitimately occur. (A multi-currency fetcher would need a spec
  amendment first — the spec sentence pins the triple.)
- Writer behaviour on conflict: the store INSERTs with
  `ON CONFLICT (asset, sub_verb, captured_at) DO NOTHING` — the table
  is INSERT-only by spec ("no updates"), so a duplicate write is
  dropped, never updated.

### `new_price_snapshot` channel — removed

No NOTIFY channel exists for price snapshots. The Collector's
`price_snapshot` writer emits no NOTIFY; the Provider reads the latest
row for an `(asset, sub_verb)` triple with a single SQL query on each
command invocation, and the table read is the sole correctness path.

An earlier revision kept the producer as a best-effort seam for a
future in-process cache. That seam was dropped: the emit had no
consumer (the cache layer it was meant to serve was never built), so a
producerless channel was dead machinery. The closed NOTIFY list is now
`new_post` and `quarantine_review` (`spec/architecture.md`
§Inter-service communication).

## 10.4 Refresh & cache strategy

Each `(asset, source, vs_currency)` triple is one `AssetDataSource`
fetch per asset-fetch tick. Per                                                                                                                                                                                   
profile:

| Profile | Refresh interval | Notes |                                                                                                                                                                                                                
  |---|---|---|                                                                                                                                                                                                                                         
| `laptop` | 60s | Aggressive enough for dev feel |                                                                                                                                                                                                   
| `vps` | 90s | Default production cadence |                                                                                                                                                                                                          
| `pi` | 300s | Lower load, lower polling |                                                                                                                                                                                                           
| `remote-llm` | 90s | Same as `vps` |                    

The user-facing reply always reads the **latest** `price_snapshot` row
for that triple. There is no per-request fetch — `/zcash kraken` is a
single SQL read. Cache age is computed at reply time as
`now() - captured_at`.

If the latest snapshot is older than `2 * refresh_interval`, the reply                                                                                                                                                                                
includes a `stale` marker:

  ```                                                                                                                                                                                                                                                   
  Zcash (kraken)  ⚠ stale                                                                                                                                                                                                                               
    $42.18  ·  0.000651 BTC                                                                                                                                                                                                                             
    ...                                                                                                                                                                                                                                                 
    as of 14:32 UTC, cached 412s (refresh interval 90s)                                                                                                                                                                                                 
    source: kraken.com/prices/zec-usd-zcash-price-chart 
  ```                                                                                                                                                                                                                                                   

This is honest about freshness without breaking the command — a                                                                                                                                                                                       
flapping exchange does not block the user.

## 10.5 Reply layout

### Per-source field availability

Exchanges do not expose a 7-day delta in their public ticker endpoints.
CoinGecko does not expose an intra-day open price so a meaningful
"today Δ%" is not available there. The renderer uses only what the
source actually provides — it never invents zeros or estimates.

| Field            | coingecko | kraken | bitfinex |
|------------------|-----------|--------|----------|
| price (USD)      | ✅        | ✅     | ✅       |
| price (BTC)      | ✅        | ❌     | ❌       |
| high\_24h        | ✅        | ✅     | ✅       |
| low\_24h         | ✅        | ✅     | ✅       |
| change\_1h\_pct  | ✅        | ❌     | ❌       |
| change\_24h\_pct | ✅        | ❌     | ❌       |
| change\_7d\_pct  | ✅        | ❌     | ❌       |
| volume\_24h      | ✅        | ✅     | ✅       |

### Default reply examples (plain text, per D30)

**coingecko** — shows 1h and 24h deltas plus the day spread:

  ```
  Zcash (coingecko)
    $42.18  ·  0.000651 BTC
    1h:    +0.3%
    24h:   −2.4%  (high $43.91 / low $41.07)
    as of 14:32 UTC, cached 41s
    source: coingecko.com/en/coins/zcash
  ```

**kraken / bitfinex** — no delta available; shows day spread only:

  ```
  Zcash (kraken)
    $42.15  ·  0.000650 BTC
    24h:   high $43.88 / low $41.02
    as of 14:32 UTC, cached 38s
    source: kraken.com/prices/zec-usd-zcash-price-chart
  ```

### Rendering rules

- Header line: `<DisplayName> (<source>)` followed by an optional
  ` ⚠ stale` marker. The data-source name is always lowercase to match
  sub-verb input.
- Price line: quote-currency price first, then BTC-denominated price
  for crypto-vs-crypto context (privacy-coin audience anchors on BTC).
  Skipped if `--vs btc` (would be redundant) or if the source did not
  return a BTC-denominated price.
- Delta lines (coingecko only): 1h first, then 24h. Sign-bearing U+2212
  minus (not ASCII hyphen) for negative values. Omitted entirely for
  exchange sub-verbs since they do not provide delta.
- Spread line: `24h: high $X / low $Y`. For coingecko the spread is
  appended to the 24h delta line as a parenthetical. For exchange
  sub-verbs it stands alone as the only 24h line.
- Any field absent from the snapshot row is silently omitted — the
  renderer never invents zeros.
- Capture/cache line: capture timestamp in UTC, cache age in seconds.
- Source URL on its own line, bare per D30.

Verbose form (`/zcash --verbose`) adds:
- `volume 24h: $XXM`
- All other quote-currency snapshots cached for that asset (so a user
  can compare USD/EUR/CZK in one reply).

## 10.6 `bootstrap-assets.json` schema

  ```json                                                                                                                                                                                                                                               
  {                                                                                                                                                                                                                                                     
    "default_vs": "usd",                                                                                                                                                                                                                                
    "assets": [                                                                                                                                                                                                                                         
      {                                                
        "id": "zcash",                                                                                                                                                                                                                                  
        "display_name": "Zcash",           
        "ticker": "ZEC",                                                                                                                                                                                                                                
        "default_sub_verb": "coingecko",               
        "sub_verbs": [                                  
          { "id": "coingecko", "external_id": "zcash" },                                                                                                                                                                                                
          { "id": "kraken",    "external_id": "ZECUSD" },
          { "id": "bitfinex",  "external_id": "tZECUSD" }                                                                                                                                                                                               
        ],                                              
        "supported_vs": ["usd", "eur", "czk", "btc"]                                                                                                                                                                                                    
      },                                               
      {                                                                                                                                                                                                                                                 
        "id": "monero",                                
        "display_name": "Monero",                                                                                                                                                                                                                       
        "ticker": "XMR",                                                                                                                                                                                                                                
        "default_sub_verb": "coingecko",                                                                                                                                                                                                                
        "sub_verbs": [                                                                                                                                                                                                                                  
          { "id": "coingecko", "external_id": "monero" },
          { "id": "kraken",    "external_id": "XMRUSD" },                                                                                                                                                                                               
          { "id": "bitfinex",  "external_id": "tXMRUSD" }
        ],                                              
        "supported_vs": ["usd", "eur", "czk", "btc"]                                                                                                                                                                                                    
      }                                  
    ]                                                                                                                                                                                                                                                   
  }                                                     
  ```                                                                                                                                                                                                                                                   

Loader is idempotent on `(asset_id)`. Removing an asset from the file                                                                                                                                                                                 
on a redeploy does **not** drop the asset; it must be soft-disabled in
a separate runbook step (mirrors source bootstrap behavior).

Path is configured via `infochat.bootstrap.assets-file`; absent path                                                                                                                                                                                  
or absent file → asset commands disabled, `/help` does not list them.

## 10.7 ToS attribution per source

| Source | Free-tier rules | Required attribution |                                                                                                                                                                                                   
  |---|---|---|                                                                                                                                                                                                                                         
| CoinGecko | Free tier permits non-commercial use with attribution; commercial use requires API key. | "Powered by CoinGecko" + URL `coingecko.com/en/coins/<id>` |                                                                                  
| Kraken | Public market-data endpoints; no auth; ToS limits redistribution but informational use is fine. | URL `kraken.com/prices/<asset>-usd-<asset>-price-chart` |                                                                                
| Bitfinex | Public market-data endpoints; no auth. | URL `bitfinex.com/t/<TICKER>:<QUOTE>` |                                                                                                                                                         

Operator deploying commercially is on the hook for any commercial-tier                                                                                                                                                                                
agreement (CoinGecko Pro, etc.). The bot's reply attribution is                                                                                                                                                                                       
necessary but not sufficient for commercial compliance — note this in
the operator runbook.

## 10.8 Friendly errors

Mirrors the tag-argument error shape (commands.md §Friendly errors):

  ```                                                                                                                                                                                                                                                   
  /zcash krakn                                                                                                                                                                                                                                          
  Unknown sub-verb krakn for /zcash. Did you mean: kraken?                                                                                                                                                                                              
  Available: coingecko, kraken, bitfinex.                                                                                                                                                                                                               
  Usage: /zcash [sub-verb] [--vs <currency>]           
  ```                                                                                                                                                                                                                                                   

  ```                                                                                                                                                                                                                                                   
  /monero binance                                      
  Sub-verb binance is not enabled for /monero. (XMR is not listed on
  Binance.)                                                                                                                                                                                                                                             
  Available: coingecko, kraken, bitfinex.                                                                                                                                                                                                               
  ```                                                                                                                                                                                                                                                   

  ```                                    
  /zcash --vs jpy                                      
  Quote currency jpy is not enabled for /zcash. Did you mean: czk?
  Available: usd, eur, czk, btc.                                                                                                                                                                                                                        
  ```                                                   

## 10.8b Asset feed recovery (operator-side)

Unlike sources, which have `/source-enable` for admin-driven recovery
from `failed`, asset feeds in v1 have **no chat-command equivalent**.
When an `asset_config` row crosses the consecutive-failure threshold
and flips to `status = 'failed'`, recovery is operator-side:

  ```sql
  UPDATE asset_config
     SET status = 'active', consecutive_failures = 0
   WHERE asset = 'zcash' AND sub_verb = 'kraken';
  ```

A chat-command equivalent (e.g. `/asset-enable <asset> <sub-verb>`)
is a v2 candidate; v1 accepts the operator-side gap because asset
feeds are operator-curated and the failure surface is small.

## 10.9 Deferred to v2

- **Live ticker mode.** Websocket-driven, in-place edits. Needs a
  `TickerStream` SPI, a "background subscription" cross-cutting                                                                                                                                                                                       
  concept, message-edit capability gating, and integration with
  `/stop`. Not a one-line addition — its own decision row when ready.
- **On-chain verbs.** `/zcash blocknumber`, `/zcash supply`,
  `/zcash shielded`, `/monero hashrate`, `/monero ringsize`,                                                                                                                                                                                          
  `/zcash halving`. Each needs an explorer-adapter SPI and per-asset
  rendering. The `AssetHandler` base class already supports                                                                                                                                                                                           
  asset-specific verbs at the class level; the SPI for explorer data
  is what's missing.
- **Historical queries.** `/zcash -w 7d` to plot or summarize the                                                                                                                                                                                     
  last week. Easy to retrofit (the `price_snapshot` table is                                                                                                                                                                                          
  append-only with timestamps), deferred for v1 scope.
- **Auth-gated exchanges.** KuCoin, Gemini for most endpoints,                                                                                                                                                                                        
  CoinGecko Pro. Needs the operator-secret SPI.
- **Alerts / thresholds.** "ping me if ZEC drops below $30." Stateful                                                                                                                                                                                 
  per-user, needs a scheduler integration.

## 10.10 Rate limiting

Asset commands are **cheap**: one SQL read, no LLM, no fetch in the                                                                                                                                                                                   
hot path. They share the parser-only command bucket                                                                                                                                                                                                   
(`security.md` §Rate limiting). They do **not** consume the                                                                                                                                                                                           
LLM-triggering bucket.

The fetcher side has its own per-source budget (already part of the                                                                                                                                                                                   
`Fetcher` SPI's tick scheduling) — a misbehaving exchange does not
get hammered by user load, only by the tick interval.

## 10.11 Verification

Spec-level commitments to verify (verification.md will own the                                                                                                                                                                                        
phrasing; this is the design-side checklist):

- `/zcash` and `/monero` are dispatched only when the operator                                                                                                                                                                                        
  has loaded `bootstrap-assets.json`; absent file → unknown command.
- Per-asset sub-verb allowlist is enforced: `/monero binance`                                                                                                                                                                                         
  errors even if `binance` is enabled for some other asset.
- Reply always includes header attribution + bare source URL.
- Stale marker appears when `now - captured_at > 2 * refresh_interval`.
- Banned users hit the ban check before any asset command dispatches.
- No asset-command code path calls the LLM.
- No asset-command code path writes to `posts`, `quarantine`, or any                                                                                                                                                                                  
  ingest-pipeline table.          