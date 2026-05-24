---
id: M1-055b
title: Asset fetchers (per public-endpoint host) + price_snapshot store + per-host tick cadence + NOTIFY emit
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by:
  - M1-055a
files_budget: 11
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/AssetDataSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/CoingeckoSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/BitfinexSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/PriceSnapshot.java
  - infochat-core/src/main/resources/db/migration/V15__price_snapshot.sql
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStoreTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/source/AssetDataSourceContractTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - any change to the spec — §Asset commands + §Operational price_snapshot + §SSRF and outbound connections are complete on main HEAD; this ticket implements them
  - any change to M1-055a's BootstrapAssetsParser / BootstrapAssetsLoader / V14__asset_config.sql — that commit is FROZEN; this ticket consumes `asset_config` rows as input
  - any Provider command handler / reply renderer / AssetSnapshotReader / oracle impl swap — M1-055c territory
  - any new bundle key — M1-055c territory
  - any auth-gated exchange (KuCoin, Gemini for most endpoints, CoinGecko Pro) — v1 ships public-endpoint-only sources per spec §Asset commands — Public endpoints only in v1
  - any `/asset-enable` admin command — operator-side recovery only in v1 per docs/design/10-asset-commands.md §10.8b
  - any websocket "live" mode — v2 per design §10.9
  - any change to SsrfGuardedHttpClient.java — every fetcher consumes the shared library per spec §SSRF and outbound connections; this ticket does NOT modify it
  - any change to RssFetcher.java or the FetchScheduler — the asset fetcher is a sibling scheduled component, not an extension of the post-ingest fetchers
  - any change to the OutboxRehydrator, NewPostHandler, NewPostListener, or any post-pipeline class — asset snapshots are NOT posts (spec §Asset commands — Data is not posts)
  - any change to PostEvalPipeline or any Stage 1/2 component — the asset Fetchers write directly to `price_snapshot`; no eval, no tagging, no embedding
  - any change to AuditAction.java — this ticket writes no audit rows (asset reads + fetcher INSERTs are not audit-logged per spec; the bootstrap-load audit row is M1-055a's responsibility)
  - any change to the V12 invite_code_attempt table, V13 llm_output_sanitized_action table, or any pre-V14 migration — V15 is the only schema change in this ticket
  - any test outside the three test files in files_scope — every pre-existing collector and provider test continues to pass unchanged
acceptance:
  - "infochat-core/src/main/resources/db/migration/V15__price_snapshot.sql exists and applies cleanly on a fresh DB. (Migration filename MUST be the next-free `V<N>__price_snapshot.sql` integer at the moment of `/m1-tick start` — M1-055a / M1-052 / M1-053 / M1-054 may have shifted V14/V15 before this ticket lands; re-run `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail` and rename if needed.) Verify: the migration file exists under `infochat-core/src/main/resources/db/migration/` matching glob `V*__price_snapshot.sql`"
  - "V15 creates the `price_snapshot` table matching docs/spec/schema.md §Operational — Price snapshot + docs/design/10-asset-commands.md §10.3 DDL: columns `(id BIGSERIAL, asset TEXT NOT NULL, sub_verb TEXT NOT NULL, vs_currency TEXT NOT NULL, price NUMERIC(24,12) NOT NULL, volume_24h NUMERIC(28,8), high_24h NUMERIC(24,12), low_24h NUMERIC(24,12), change_1h_pct NUMERIC(8,4), change_24h_pct NUMERIC(8,4), change_7d_pct NUMERIC(8,4), captured_at TIMESTAMPTZ NOT NULL, fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), source_url TEXT, raw_payload JSONB)`. Verify: `grep -E 'CREATE TABLE price_snapshot' V15__price_snapshot.sql` returns ≥1 match AND `grep -E 'asset\\s+TEXT\\s+NOT NULL' V15__price_snapshot.sql` returns ≥1 match AND `grep -E 'captured_at\\s+TIMESTAMPTZ\\s+NOT NULL' V15__price_snapshot.sql` returns ≥1 match AND `grep -E 'raw_payload\\s+JSONB' V15__price_snapshot.sql` returns ≥1 match"
  - "V15 creates the latest-snapshot lookup index per design §10.3: `CREATE INDEX idx_price_snapshot_lookup ON price_snapshot (asset, sub_verb, vs_currency, captured_at DESC);`. Verify: `grep -E 'CREATE INDEX idx_price_snapshot_lookup' V15__price_snapshot.sql` returns ≥1 match AND `grep -E 'captured_at DESC' V15__price_snapshot.sql` returns ≥1 match"
  - "V15 declares `price_snapshot` partitioned on `captured_at` (RANGE partition per spec §Operational — Price snapshot 'Partitioned on captured_at, aged out by partition drop'), with at least one initial partition covering the current month. The partition-drop retention mechanism is operator-driven per spec invariant 6; this ticket establishes the partition shape, not the rotator. Verify: `grep -E 'PARTITION BY RANGE \\(captured_at\\)' V15__price_snapshot.sql` returns ≥1 match AND `grep -E 'CREATE TABLE price_snapshot_p' V15__price_snapshot.sql` returns ≥1 match (at least one initial partition table named with the conventional `price_snapshot_p<yyyymm>` shape)"
  - "V15 carries the V5-style per-role GRANT split (per spec §DB roles — Collector `INSERT/UPDATE/SELECT`, Provider `SELECT`-only on `price_snapshot`): `GRANT SELECT, INSERT, UPDATE ON price_snapshot TO infochat_collector;` AND `GRANT SELECT ON price_snapshot TO infochat_provider;`. DELETE is intentionally NOT granted to either role (rows expire via partition drop per spec invariant 6; only the Admin role can DROP). The GRANTs also apply to the initial partition table (declared via the V5-style partition-grant pattern). Verify: `grep -E 'GRANT\\s+SELECT,\\s+INSERT,\\s+UPDATE\\s+ON\\s+price_snapshot\\s+TO\\s+infochat_collector' V15__price_snapshot.sql` returns ≥1 match AND `grep -E 'GRANT\\s+SELECT\\s+ON\\s+price_snapshot\\s+TO\\s+infochat_provider' V15__price_snapshot.sql` returns ≥1 match AND `grep -E 'GRANT[^;]*DELETE[^;]*ON\\s+price_snapshot' V15__price_snapshot.sql` returns ZERO matches"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/PriceSnapshot.java exists as an immutable Java record carrying the fields the renderer needs per design §10.5 per-source field availability table: `(asset, sub_verb, vs_currency, price, volume_24h, high_24h, low_24h, change_1h_pct, change_24h_pct, change_7d_pct, capturedAt Instant, sourceUrl, rawPayload String)`. Optional numeric fields are boxed types (Long, Double, BigDecimal — author's call; non-boxed primitives forbidden because per-source asymmetric availability means absent fields are real, not zero). Verify: `grep -E 'public\\s+record\\s+PriceSnapshot' PriceSnapshot.java` returns ≥1 match"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/AssetDataSource.java exists as a public interface with the SPI per design §10.2: `String id();`, `Set<String> supportedAssets();`, `Set<String> supportedQuoteCurrencies(String asset);`, `PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException;`, `String attributionUrl(String asset, String vs);`. Verify: `grep -E 'public\\s+interface\\s+AssetDataSource' AssetDataSource.java` returns ≥1 match AND `grep -E 'fetchSnapshot.*asset.*vs' AssetDataSource.java` returns ≥1 match"
  - "Three concrete `AssetDataSource` impls exist as `@ApplicationScoped` CDI beans under `.../assets/source/`: `CoingeckoSnapshotSource`, `KrakenSnapshotSource`, `BitfinexSnapshotSource` (per the v1 sub-verb set in design §10.1). Each impl's `id()` returns the lowercase host-derived identifier matching the bootstrap-assets.json `sub_verbs[].id` values. Verify: `grep -lE '@ApplicationScoped' CoingeckoSnapshotSource.java KrakenSnapshotSource.java BitfinexSnapshotSource.java` returns 3 file paths AND each file has `grep -E 'implements\\s+AssetDataSource' <file>` ≥1 match"
  - "Each `AssetDataSource` impl performs outbound HTTP through `SsrfGuardedHttpClient` (the shared library used by RssFetcher per spec §SSRF and outbound connections). No bespoke HTTP client construction. Verify: `grep -lE 'SsrfGuardedHttpClient' CoingeckoSnapshotSource.java KrakenSnapshotSource.java BitfinexSnapshotSource.java` returns 3 file paths AND `grep -E 'new\\s+(HttpClient|OkHttpClient|URLConnection)' CoingeckoSnapshotSource.java KrakenSnapshotSource.java BitfinexSnapshotSource.java` returns ZERO matches"
  - "Each `AssetDataSource` impl's `attributionUrl(asset, vs)` returns the per-source URL per docs/design/10-asset-commands.md §10.7 ToS attribution table — CoinGecko: `coingecko.com/en/coins/<asset>`; Kraken: `kraken.com/prices/<asset>-usd-<asset>-price-chart`; Bitfinex: `bitfinex.com/t/<TICKER>:<QUOTE>` (the ticker mapping reads from `BootstrapAssetsEntry.ticker` per design §10.6). The attribution string is bare (no markdown link syntax per D30) — verify the impls' returned strings do NOT contain `[` followed by `](`"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java exists, is `@ApplicationScoped`, and exposes a public tick entry point invoked by Quarkus scheduler. The tick schedules **per-host** (one interval per data-source host: one for coingecko, one for kraken, one for bitfinex), NOT per-(asset, sub_verb) per spec §Asset commands — Polled, cached, refreshed on a tick. Per-host interval values are profile-driven via `@ConfigProperty` keys `infochat.assets.refresh.coingecko`, `infochat.assets.refresh.kraken`, `infochat.assets.refresh.bitfinex` (Duration; defaults per profile per design §10.4). Verify: `grep -E '@ApplicationScoped' AssetSnapshotFetcher.java` returns ≥1 match AND `grep -E '@Scheduled|@ConfigProperty' AssetSnapshotFetcher.java` returns ≥1 match AND `grep -E 'infochat\\.assets\\.refresh' AssetSnapshotFetcher.java` returns ≥3 matches (one per host)"
  - "AssetSnapshotFetcher reads enabled `(asset, sub_verb)` pairs from `asset_config` (status='active', enabled=true) and invokes the corresponding `AssetDataSource.fetchSnapshot(...)` for each pair belonging to the tick's host. Each successful fetch is handed to `PriceSnapshotStore.store(snapshot)`. The fetcher does NOT bypass the per-host tick interval — calling the tick before the interval elapses is a no-op for that host. Verify: AssetSnapshotFetcherTest has a @Test method whose name contains `perHostScheduling` (case-insensitive) AND `grep -iE 'void\\s+\\w*perHostScheduling\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match"
  - "AssetSnapshotFetcher tracks per-`(asset, sub_verb)` consecutive failure counts in `asset_config.consecutive_failures` per D42 (HTTP-shaped source failure-counter model): each successful fetch resets the column to 0 and updates `last_success_at = NOW()`; each FetchException increments the column by 1 and updates `last_failure_at = NOW()`. On threshold-crossing (configurable via `@ConfigProperty(name = \"infochat.assets.failure-threshold\", defaultValue = \"5\")`), the fetcher UPDATEs `asset_config.status = 'failed'` and emits a single throttled admin notification (via the M1-022-precedent throttled admin notifier; the throttle window is the existing per-source one). Recovery from `failed` is operator-side per design §10.8b. Verify: AssetSnapshotFetcherTest has a @Test method whose name contains `failureCounterThresholdBreach` (case-insensitive) AND `grep -iE 'void\\s+\\w*failureCounterThresholdBreach\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match"
  - "AssetSnapshotFetcherTest has a @Test method whose name contains `happyPathStoresSnapshot` (case-insensitive) that asserts a tick with a fake `AssetDataSource` returning a deterministic `PriceSnapshot` produces exactly one `price_snapshot` row with the expected `(asset, sub_verb, captured_at, price)` and resets `consecutive_failures` to 0. Verify: `grep -iE 'void\\s+\\w*happyPathStoresSnapshot\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match"
  - "AssetSnapshotFetcherTest has a @Test method whose name contains `fetchExceptionIncrementsCounter` (case-insensitive) that asserts a tick whose fake `AssetDataSource` throws `FetchException` does NOT INSERT a `price_snapshot` row, increments `asset_config.consecutive_failures` by 1, and updates `asset_config.last_failure_at`. Verify: `grep -iE 'void\\s+\\w*fetchExceptionIncrementsCounter\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match"
  - "AssetSnapshotFetcherTest has a @Test method whose name contains `disabledRowSkipped` (case-insensitive) that asserts a tick does NOT call `fetchSnapshot` for an `(asset, sub_verb)` row whose `enabled = false` AND does NOT INSERT a `price_snapshot` row for that pair. Verify: `grep -iE 'void\\s+\\w*disabledRowSkipped\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java exists, is `@ApplicationScoped`, and exposes a public `store(PriceSnapshot)` method that INSERTs one row into `price_snapshot` AND emits `NOTIFY new_price_snapshot, <payload>` in the SAME transaction. Payload is the spec-committed `(asset, source)` JSON shape per spec §Asset commands — Provider/Collector contract: `{\"asset\":\"<asset>\",\"source\":\"<sub_verb>\"}`. The INSERT bypasses the post outbox (no Stage 1/2, no quarantine path) per spec §Asset commands — Data is not posts. Verify: `grep -E '@ApplicationScoped' PriceSnapshotStore.java` returns ≥1 match AND `grep -E 'INSERT\\s+INTO\\s+price_snapshot' PriceSnapshotStore.java` returns ≥1 match AND `grep -E 'NOTIFY\\s+new_price_snapshot|pg_notify\\s*\\(\\s*''new_price_snapshot' PriceSnapshotStore.java` returns ≥1 match"
  - "PriceSnapshotStoreTest has a @Test method whose name contains `insertEmitsNotify` (case-insensitive) that asserts a `store(snapshot)` call (a) writes exactly one row to `price_snapshot` and (b) a LISTEN'ing connection on `new_price_snapshot` receives a payload containing the asset name AND the source name (assertion shape mirrors the M1-027 NewPostListenerTest pattern). Verify: `grep -iE 'void\\s+\\w*insertEmitsNotify\\w*\\s*\\(' PriceSnapshotStoreTest.java` returns ≥1 match"
  - "PriceSnapshotStoreTest has a @Test method whose name contains `appendsToCurrentPartition` (case-insensitive) that asserts a `store(snapshot)` with a `captured_at` in the current month lands in the correct partition (a SELECT against the partition table returns the row; INSERT against the parent table is the public API). Verify: `grep -iE 'void\\s+\\w*appendsToCurrentPartition\\w*\\s*\\(' PriceSnapshotStoreTest.java` returns ≥1 match"
  - "PriceSnapshotStoreTest has a @Test method whose name contains `transactionRollbackSuppressesNotify` (case-insensitive) that asserts a rolled-back transaction containing a `store(...)` call does NOT emit the NOTIFY to a listening connection (Postgres semantics: NOTIFY fires at COMMIT, not at INSERT — verifies the spec's atomicity guarantee on the snapshot/NOTIFY pair). Verify: `grep -iE 'void\\s+\\w*transactionRollbackSuppressesNotify\\w*\\s*\\(' PriceSnapshotStoreTest.java` returns ≥1 match"
  - "AssetDataSourceContractTest is a plain JUnit `@ParameterizedTest` (or sibling parameterized shape) that runs the same contract checks against all three impls: (1) `supportedAssets()` is non-empty; (2) `supportedQuoteCurrencies('zcash')` is non-empty; (3) `attributionUrl('zcash', 'usd')` returns a non-empty string that starts with `http` and does NOT contain `[` followed by `](`. The test does NOT make outbound HTTP — it asserts the static surface only; integration tests against real endpoints belong in a separate ITs run (this ticket adds none). Verify: `grep -E '@ParameterizedTest|@MethodSource|@ValueSource' AssetDataSourceContractTest.java` returns ≥1 match"
  - "Authorized test edit: AssetDataSourceContractTest's parameterized data set lists the three impls. No pre-existing tests are modified. Verify: `wc -l AssetDataSourceContractTest.java` reports a file count under 200 lines (a contract test, not a full coverage matrix)"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: all M1-007a Fetcher SPI tests, M1-022 RssFetcher tests, M1-027/028 NOTIFY tests, every M1-055a test, every M1-052..M1-054 test, plus every other M1-008..M1-051 test currently green on main"
test_plan:
  adds:
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/AssetDataSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/CoingeckoSnapshotSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/BitfinexSnapshotSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/PriceSnapshot.java
    - infochat-core/src/main/resources/db/migration/V15__price_snapshot.sql
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStoreTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/source/AssetDataSourceContractTest.java
  preserves:
    - all tests currently green on main
    - every test added by M1-055a
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/schema.md §Operational
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D30
  - D33
  - D34
  - D39
  - D42
---

# M1-055b: Asset fetchers (per public-endpoint host) + price_snapshot store + per-host tick cadence + NOTIFY emit

## Context

This subticket lands the **Collector-side fetch and store**
half of the T2-H asset-commands vertical (M1-055 umbrella):

1. The `V15__price_snapshot.sql` Flyway migration creates the
   `price_snapshot` table partitioned on `captured_at`
   (RANGE), with the spec-committed columns + per-role GRANT
   split (Collector `INSERT/UPDATE/SELECT`, Provider
   `SELECT`-only, no DELETE for either — retention is by
   partition drop per spec invariant 6, operator-driven). The
   latest-snapshot lookup index `(asset, sub_verb,
   vs_currency, captured_at DESC)` backs M1-055c's
   AssetSnapshotReader queries.
2. The `PriceSnapshot` record carries the design §10.5 fields
   with nullable boxed types for asymmetric per-source
   availability (e.g. Kraken does not expose 7-day delta).
3. `AssetDataSource` is the SPI per design §10.2 — `id()`,
   `supportedAssets()`, `supportedQuoteCurrencies(asset)`,
   `fetchSnapshot(asset, vs)`, `attributionUrl(asset, vs)`.
   Three concrete impls (`CoingeckoSnapshotSource`,
   `KrakenSnapshotSource`, `BitfinexSnapshotSource`) cover
   the v1 sub-verb set per design §10.1.
4. Every concrete impl performs outbound HTTP through the
   shared `SsrfGuardedHttpClient` per spec §SSRF and
   outbound connections — the same library the existing
   RssFetcher uses. No bespoke HTTP client construction.
5. `AssetSnapshotFetcher` is a `@ApplicationScoped` CDI bean
   whose tick is **per-host** (one Scheduled tick per data
   source: coingecko, kraken, bitfinex), NOT per-`(asset,
   sub_verb)` per spec §Asset commands — Polled, cached,
   refreshed on a tick. Each per-host tick reads the enabled
   `(asset, sub_verb)` rows from `asset_config` whose
   `sub_verb` matches the host and invokes the corresponding
   `AssetDataSource.fetchSnapshot(...)` for each pair.
6. Per-source consecutive-failure logic per D42: each
   FetchException increments `asset_config.consecutive_failures`
   and updates `last_failure_at`; each success resets the
   counter and updates `last_success_at`. On threshold
   breach the row's `status` flips to `failed` and a
   throttled admin notification fires (M1-022-precedent
   notifier). Recovery from `failed` is operator-side per
   design §10.8b (no chat-command equivalent in v1).
7. `PriceSnapshotStore` writes directly to `price_snapshot`
   (no outbox, no eval, no quarantine) and emits `NOTIFY
   new_price_snapshot` with the `(asset, source)` JSON
   payload per spec §Asset commands — Provider/Collector
   contract. The INSERT and NOTIFY are in the same
   transaction so a rollback suppresses the NOTIFY.

`security_relevant: true` because (a) outbound fetcher
traffic must respect SSRF policy (an attacker who can edit
`bootstrap-assets.json` could point a sub-verb's URL at
internal infrastructure if the SSRF library is bypassed); and
(b) the failed-source state machine is a safety invariant —
a runaway fetcher hammering a flapping exchange must trip
the threshold and stop, not page operator with one alert per
tick. `migration_touch: true` because V15 is the canonical
schema change.

## Definition of Done

- `V15__price_snapshot.sql` (or next-free `V<N>` after
  M1-055a's V14 and any T2-B-shifted migrations) creates the
  partitioned `price_snapshot` table with the spec-committed
  columns, latest-snapshot index, and per-role GRANTs.
- The seven new classes under
  `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/`
  (or `/asset/` to match design §10.2 — author's call on
  singular vs. plural; the package layout is design-tier
  per design §10.2) exist with the shape described above.
- The three test classes exercise the fetcher tick behavior,
  the PriceSnapshotStore INSERT + NOTIFY semantics, and the
  AssetDataSource contract uniformly across impls.
- `mvn -B clean verify` exits 0.

## Notes

- **Per-host tick, not per-pair tick.** Spec §Asset commands
  is explicit: "All `kraken` snapshots across every enabled
  asset share one tick cadence; same for `coingecko` and
  `bitfinex`. The per-host interval values are profile-driven
  and live in design notes." The fetcher MUST NOT schedule a
  separate tick per `(asset, sub_verb)`; that would multiply
  outbound traffic by N and break the upstream-host
  rate-limit budget.
- **Profile-driven intervals.** Per design §10.4: laptop
  60s, vps 90s, pi 300s, remote-llm 90s. Implementing author
  may choose to encode these as `@ConfigProperty` defaults
  per host (one property per host) and override per-profile
  via `application.properties`, OR ship a single
  config-property block per host with profile-tagged
  defaults. The acceptance pins the property-name shape but
  not the override mechanism.
- **NOTIFY payload format.** Spec § Asset commands —
  Provider/Collector contract: "`NOTIFY new_price_snapshot`
  with `(asset, source)` as the payload". The JSON shape
  `{"asset":"<asset>","source":"<sub_verb>"}` matches the
  M1-027 `new_post` channel's tagged-payload pattern. The
  Provider does NOT maintain a `provider_state` row for
  this channel (spec §Operational — Provider state:
  `new_price_snapshot — best-effort only; this channel does
  not maintain a provider_state row`); the
  cache-flush-on-reconnect is the correctness mechanism, not
  a high-water mark.
- **Failure-counter threshold default.** Spec is silent on
  the exact threshold; design §10.4 also does not pin it.
  This ticket sets `@ConfigProperty
  infochat.assets.failure-threshold` with `defaultValue =
  "5"` matching the M1-022 RssFetcher precedent. Operator
  can override.
- **Throttled admin notification.** The M1-022 RssFetcher
  ships a throttled admin notifier; this ticket reuses that
  notifier via its existing CDI bean. No new throttling
  state is added — the existing per-source throttle window
  applies to asset feeds too.
- **Migration race.** V14 is M1-055a's asset_config and
  M1-052's saved_post; V15 is this ticket's price_snapshot
  but may be V16 / V17 / ... if M1-053 / M1-054 / M1-055a
  also claim migrations and merge first. Re-run `ls
  infochat-core/src/main/resources/db/migration/ | sort -V
  | tail` at `/m1-tick start` and rename if needed.

## Big-picture notes

- **No Provider code in this ticket.** The Provider has
  SELECT on `price_snapshot` from V15 onward, but no
  Provider code reads it until M1-055c lands.
- **No `/asset-enable` admin command.** Design §10.8b is
  explicit: v1 ships operator-side recovery only via raw
  SQL UPDATE; a chat-command equivalent is v2.
- **The IT in the umbrella exercises this fetcher with a
  fake AssetDataSource.** Real per-host impls are exercised
  by the contract test (static surface only — no outbound
  HTTP) plus operator-side smoke tests against staging.
  Real-endpoint ITs are out of v1's automated test surface
  per spec §Asset commands — Public endpoints only (we do
  not pin upstream availability in CI).

## Out-of-scope expansion

- **Provider command handlers, reply renderer, oracle
  swap.** M1-055c territory.
- **AssetSnapshotReader.** M1-055c — it consumes the
  Provider `SELECT` GRANT this ticket establishes.
- **Bundle keys / friendly errors / `/help`
  context-awareness.** M1-055c territory.
- **Auth-gated exchanges.** v1 ships public-endpoint-only
  sources per spec §Asset commands — Public endpoints
  only in v1. KuCoin / Gemini / CoinGecko Pro require the
  operator-secret SPI (v2).
- **Websocket "live" mode.** v2 per design §10.9.
- **`/asset-enable` chat-command recovery.** v2 per design
  §10.8b.
- **A new audit verb.** Asset reads + fetcher INSERTs are
  not audit-logged per spec — the audit-log table is for
  privileged user actions and operator boot events, not
  for read-mostly bulk-derived rows. The
  `BOOTSTRAP_ASSET_LOAD` row is M1-055a's responsibility.
- **Real-endpoint integration tests.** Not in v1's
  automated test surface — the contract test pins the
  static surface; real-endpoint smoke testing is
  operator-side.

## Authorized test changes

- (none — this ticket adds new tests and modifies no
  pre-existing tests.)

## Alternatives considered

- **One Scheduled tick per `(asset, sub_verb)`.** Rejected
  — spec §Asset commands is explicit that the tick is
  per-host. Per-pair scheduling would break the upstream
  rate-limit budget and complicate the throttled admin
  notifier's per-source state.
- **Write `price_snapshot` rows via the outbox.** Rejected
  — spec §Asset commands is explicit: "Data is not posts.
  Snapshots are stored in a collector-owned table outside
  the post pipeline. They never go through Stage 1/2,
  tagging, entity extraction, or embedding". The outbox is
  the post-pipeline plumbing; asset snapshots bypass it
  by design.
- **Skip the per-source failure-counter; rely on Quarkus
  Scheduler error handling.** Rejected — D42 commits to
  the per-source consecutive-failure model with a
  threshold-driven `status = failed` flip and a throttled
  admin notification. The fetcher must implement this
  contract; the Scheduler's generic error handling is not
  enough to drive the operator visibility commitment.
- **Use HTTP HEAD or a lightweight ping in the contract
  test to verify per-host endpoints are reachable.**
  Rejected — the contract test asserts the static
  surface; outbound HTTP is brittle, slow, and an
  availability dependency the CI run does not own.
  Real-endpoint smoke testing is operator-side.
