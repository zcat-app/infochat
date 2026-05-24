---
id: M1-055b
title: Asset fetchers (per public-endpoint host) + price_snapshot store + per-host tick cadence + NOTIFY emit
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by:
  - M1-055a
  - M1-058
files_budget: 12
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/AssetDataSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/CoingeckoSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/BitfinexSnapshotSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/PriceSnapshot.java
  - infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql
  - infochat-collector/src/main/resources/application.properties
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
  - any change to M1-058's ThrottledAdminNotifier or admin_notification_state migration — M1-058 is FROZEN before this ticket starts; this ticket consumes the notifier CDI bean as input
  - any Provider command handler / reply renderer / AssetSnapshotReader / oracle impl swap — M1-055c territory
  - any new bundle key — M1-055c territory
  - any auth-gated exchange (KuCoin, Gemini for most endpoints, CoinGecko Pro) — v1 ships public-endpoint-only sources per spec §Asset commands — Public endpoints only in v1
  - any `/asset-enable` admin command — operator-side recovery only in v1 per docs/design/10-asset-commands.md §10.8b
  - any websocket "live" mode — v2 per design §10.9
  - any change to SsrfGuardedHttpClient.java — every fetcher consumes the shared library per spec §SSRF and outbound connections; this ticket does NOT modify it
  - any change to RssFetcher.java or the FetchScheduler — the asset fetcher is a sibling scheduled component, not an extension of the post-ingest fetchers; RssFetcher's own D42 wiring is also deferred (FetchScheduler.java:58-66) and remains so — adopting M1-058's notifier from the RSS path is a separate follow-up ticket
  - any change to the OutboxRehydrator, NewPostHandler, NewPostListener, or any post-pipeline class — asset snapshots are NOT posts (spec §Asset commands — Data is not posts)
  - any change to PostEvalPipeline or any Stage 1/2 component — the asset Fetchers write directly to `price_snapshot`; no eval, no tagging, no embedding
  - any change to AuditAction.java — this ticket writes no audit rows (asset reads + fetcher INSERTs are not audit-logged per spec; the bootstrap-load audit row is M1-055a's responsibility)
  - any change to the V12 invite_code_attempt table, V13 llm_output_sanitized_action table, V14 asset_config, V15 saved_post, V16 admin_notification_state (M1-058), or any other pre-existing migration — the V<next-free>__price_snapshot.sql migration this ticket adds is the only schema change
  - any change to existing %<profile> blocks in application.properties for keys this ticket does NOT introduce — this ticket adds ONLY the `infochat.assets.refresh.<host>` and `infochat.assets.failure-threshold` keys + their per-profile blocks; every other key in application.properties is untouched
  - any test outside the three test files in files_scope — every pre-existing collector and provider test continues to pass unchanged
acceptance:
  - "infochat-core/src/main/resources/db/migration/V<next-free>__price_snapshot.sql exists and applies cleanly on a fresh DB. (Migration filename MUST be the next-free `V<N>__price_snapshot.sql` integer at the moment of `/m1-tick start` — at M1-055b refine time the next-free is V17 because V14=asset_config, V15=saved_post, V16=admin_notification_state (M1-058); if other migrations land between refine and start, re-run `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail` and rename accordingly. Substitute V<NN> for V17 in all greps below if the number shifts.) Verify: the migration file exists under `infochat-core/src/main/resources/db/migration/` matching glob `V*__price_snapshot.sql` AND `mvn -pl infochat-core flyway:migrate` exits 0 against a fresh DB"
  - "V17 (or its rebased equivalent) creates the `price_snapshot` table matching docs/spec/schema.md §Operational — Price snapshot + docs/design/10-asset-commands.md §10.3 DDL: columns `(id BIGSERIAL, asset TEXT NOT NULL, sub_verb TEXT NOT NULL, vs_currency TEXT NOT NULL, price NUMERIC(24,12) NOT NULL, volume_24h NUMERIC(28,8), high_24h NUMERIC(24,12), low_24h NUMERIC(24,12), change_1h_pct NUMERIC(8,4), change_24h_pct NUMERIC(8,4), change_7d_pct NUMERIC(8,4), captured_at TIMESTAMPTZ NOT NULL, fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), source_url TEXT, raw_payload JSONB, PRIMARY KEY (id, captured_at))`. The composite `PRIMARY KEY (id, captured_at)` is required because Postgres requires the partition key to participate in every PRIMARY KEY / UNIQUE constraint on a partitioned table (mirror of V7__joins_post.sql:163 `PRIMARY KEY (id, fetched_at)`). Verify: `grep -E 'CREATE TABLE price_snapshot' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'PRIMARY KEY\\s*\\(\\s*id\\s*,\\s*captured_at\\s*\\)' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'asset\\s+TEXT\\s+NOT NULL' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'captured_at\\s+TIMESTAMPTZ\\s+NOT NULL' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'raw_payload\\s+JSONB' V*__price_snapshot.sql` returns ≥1 match"
  - "V<next-free> creates the latest-snapshot lookup index per design §10.3: `CREATE INDEX idx_price_snapshot_lookup ON price_snapshot (asset, sub_verb, vs_currency, captured_at DESC);`. Verify: `grep -E 'CREATE INDEX idx_price_snapshot_lookup' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'captured_at DESC' V*__price_snapshot.sql` returns ≥1 match"
  - "V<next-free> declares `price_snapshot` partitioned on `captured_at` (RANGE partition per spec §Operational — Price snapshot 'Partitioned on captured_at, aged out by partition drop'), with at least one initial partition covering the current month (`price_snapshot_p<yyyymm>` shape per V7__joins_post.sql:170 `post_202605` precedent). The partition-drop retention mechanism is operator-driven per spec invariant 6; this ticket establishes the partition shape, not the rotator. Verify: `grep -E 'PARTITION BY RANGE \\(captured_at\\)' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'CREATE TABLE price_snapshot_p' V*__price_snapshot.sql` returns ≥1 match"
  - "V<next-free> carries the V7-style per-role GRANT split (per spec §DB roles — Collector `INSERT/UPDATE/SELECT`, Provider `SELECT`-only on `price_snapshot`): `GRANT SELECT, INSERT, UPDATE ON price_snapshot TO infochat_collector;` AND `GRANT SELECT ON price_snapshot TO infochat_provider;`. DELETE is intentionally NOT granted to either role (rows expire via partition drop per spec invariant 6; only the Admin role can DROP). Grants on the parent propagate to partitions per V7__joins_post.sql:226-227 precedent (no explicit per-partition GRANT statements needed). Verify: `grep -E 'GRANT\\s+SELECT,\\s+INSERT,\\s+UPDATE\\s+ON\\s+price_snapshot\\s+TO\\s+infochat_collector' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'GRANT\\s+SELECT\\s+ON\\s+price_snapshot\\s+TO\\s+infochat_provider' V*__price_snapshot.sql` returns ≥1 match AND `grep -E 'GRANT[^;]*DELETE[^;]*ON\\s+price_snapshot' V*__price_snapshot.sql` returns ZERO matches"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/PriceSnapshot.java exists as an immutable Java record carrying the fields the renderer needs per design §10.5 per-source field availability table: `(asset, sub_verb, vs_currency, price, volume_24h, high_24h, low_24h, change_1h_pct, change_24h_pct, change_7d_pct, capturedAt Instant, sourceUrl, rawPayload String)`. Optional numeric fields are boxed types (Long, Double, BigDecimal — author's call; non-boxed primitives forbidden because per-source asymmetric availability means absent fields are real, not zero). Verify: `grep -E 'public\\s+record\\s+PriceSnapshot' PriceSnapshot.java` returns ≥1 match"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/AssetDataSource.java exists as a public interface with the SPI per design §10.2: `String id();`, `Set<String> supportedAssets();`, `Set<String> supportedQuoteCurrencies(String asset);`, `PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException;`, `String attributionUrl(String asset, String vs);`. The `FetchException` lives as a nested checked exception inside `AssetDataSource.java` (keeps SPI files_scope at 1 file). Verify: `grep -E 'public\\s+interface\\s+AssetDataSource' AssetDataSource.java` returns ≥1 match AND `grep -E 'fetchSnapshot.*asset.*vs' AssetDataSource.java` returns ≥1 match AND `grep -E 'class\\s+FetchException' AssetDataSource.java` returns ≥1 match"
  - "Three concrete `AssetDataSource` impls exist as `@ApplicationScoped` CDI beans under `.../assets/source/`: `CoingeckoSnapshotSource`, `KrakenSnapshotSource`, `BitfinexSnapshotSource` (per the v1 sub-verb set in design §10.1). Each impl's `id()` returns the lowercase host-derived identifier matching the bootstrap-assets.json `sub_verbs[].id` values. Verify: `grep -lE '@ApplicationScoped' CoingeckoSnapshotSource.java KrakenSnapshotSource.java BitfinexSnapshotSource.java` returns 3 file paths AND each file has `grep -E 'implements\\s+AssetDataSource' <file>` ≥1 match"
  - "Each `AssetDataSource` impl performs outbound HTTP through `SsrfGuardedHttpClient` (the shared library used by RssFetcher per spec §SSRF and outbound connections). No bespoke HTTP client construction. Verify: `grep -lE 'SsrfGuardedHttpClient' CoingeckoSnapshotSource.java KrakenSnapshotSource.java BitfinexSnapshotSource.java` returns 3 file paths AND `grep -E 'new\\s+(HttpClient|OkHttpClient|URLConnection)' CoingeckoSnapshotSource.java KrakenSnapshotSource.java BitfinexSnapshotSource.java` returns ZERO matches"
  - "Each `AssetDataSource` impl's `attributionUrl(asset, vs)` returns the per-source URL per docs/design/10-asset-commands.md §10.7 ToS attribution table — CoinGecko: `coingecko.com/en/coins/<asset>`; Kraken: `kraken.com/prices/<asset>-usd-<asset>-price-chart`; Bitfinex: `bitfinex.com/t/<TICKER>:<QUOTE>` (the ticker mapping reads from `BootstrapAssetsEntry.ticker` per design §10.6). The attribution string is bare (no markdown link syntax per D30) — verify the impls' returned strings do NOT contain `[` followed by `](` (checked in AssetDataSourceContractTest, acceptance item 21)"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java exists, is `@ApplicationScoped`, and exposes a public tick entry point invoked by Quarkus scheduler. The tick schedules **per-host** (one interval per data-source host: one for coingecko, one for kraken, one for bitfinex), NOT per-(asset, sub_verb) per spec §Asset commands — Polled, cached, refreshed on a tick. Per-host interval values are profile-driven via `@ConfigProperty` keys `infochat.assets.refresh.coingecko`, `infochat.assets.refresh.kraken`, `infochat.assets.refresh.bitfinex` (Duration). Per FetchScheduler.java:95-100 convention (codified codebase rule for profile-driven properties), the `@ConfigProperty` declarations carry NO inline `defaultValue` — defaults live in `application.properties` profile blocks per design §10.4 (laptop 60s, vps 90s, pi 300s, remote-llm 90s). Verify: `grep -E '@ApplicationScoped' AssetSnapshotFetcher.java` returns ≥1 match AND `grep -E '@Scheduled' AssetSnapshotFetcher.java` returns ≥3 matches (one per host) AND `grep -E '@ConfigProperty\\(name\\s*=\\s*\"infochat\\.assets\\.refresh' AssetSnapshotFetcher.java` returns ≥3 matches AND `grep -E '@ConfigProperty\\([^)]*defaultValue[^)]*infochat\\.assets\\.refresh' AssetSnapshotFetcher.java` returns ZERO matches (no inline defaults on profile-driven keys)"
  - "infochat-collector/src/main/resources/application.properties gains the base + per-profile blocks for the three refresh-interval keys per design §10.4. Specifically: base `infochat.assets.refresh.coingecko=90s`, `infochat.assets.refresh.kraken=90s`, `infochat.assets.refresh.bitfinex=90s` (test-time fallback — Quarkus' `test` profile does not inherit `%<profile>` namespaces, so the base value MUST be set for @QuarkusTest to boot, mirroring the heartbeat.interval / stage1.regex-timeout-ms precedent at application.properties:72 / :213; `90s` matches the `vps`/`remote-llm` default — a conservative middle ground for tests) AND the four per-profile overrides `%laptop.infochat.assets.refresh.<host>=60s`, `%vps.infochat.assets.refresh.<host>=90s`, `%pi.infochat.assets.refresh.<host>=300s`, `%remote-llm.infochat.assets.refresh.<host>=90s` for each of the three hosts (12 per-profile lines total). Verify: `grep -E '^infochat\\.assets\\.refresh\\.(coingecko|kraken|bitfinex)=90s' application.properties` returns 3 matches AND `grep -E '^%(laptop|vps|pi|remote-llm)\\.infochat\\.assets\\.refresh\\.' application.properties` returns 12 matches"
  - "AssetSnapshotFetcher reads enabled `(asset, sub_verb)` pairs from `asset_config` (status='active', enabled=true) and invokes the corresponding `AssetDataSource.fetchSnapshot(...)` for each pair belonging to the tick's host. Each successful fetch is handed to `PriceSnapshotStore.store(snapshot)`. The fetcher does NOT bypass the per-host tick interval — calling the tick before the interval elapses is a no-op for that host. Verify: AssetSnapshotFetcherTest contains a `@Test` method whose name contains `perHostScheduling` (case-insensitive) AND the method body asserts that only the matching-host `AssetDataSource.fetchSnapshot` is invoked on a per-host tick (other hosts' data sources are NOT called). Implementation may use Mockito (`verify(mock, never())`) OR a hand-rolled fake (per the codebase's preference for extracted test doubles over inner-class fakes — assert a per-source call counter is zero). Verify: `grep -iE 'void\\s+\\w*perHostScheduling\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match AND `grep -iE 'never\\(\\)|times\\s*\\(\\s*0|isEmpty\\(\\)|assertEquals\\s*\\(\\s*0' AssetSnapshotFetcherTest.java` returns ≥1 match (the negative — non-host sources are NOT called per tick — expressed via any standard assertion shape)"
  - "AssetSnapshotFetcher tracks per-`(asset, sub_verb)` consecutive failure counts in `asset_config.consecutive_failures` per D42 (HTTP-shaped source failure-counter model): each successful fetch resets the column to 0 and updates `last_success_at = NOW()`; each FetchException increments the column by 1 and updates `last_failure_at = NOW()`. On threshold-crossing (configurable via `@ConfigProperty(name = \"infochat.assets.failure-threshold\", defaultValue = \"5\")` — inline default is permitted for this single-global-default property per the codebase's split convention; FetchScheduler.java:95-100 only forbids inline defaults on profile-driven properties), the fetcher UPDATEs `asset_config.status = 'failed'` and emits a single throttled admin notification via the `ThrottledAdminNotifier` CDI bean provided by M1-058 (`notifier.notifyOnce(key, errorClass, message)` where `key = \"asset-source-failed:\" + asset + \":\" + sub_verb`). Recovery from `failed` is operator-side per design §10.8b. Verify: AssetSnapshotFetcherTest contains a `@Test` method whose name contains `failureCounterThresholdBreach` (case-insensitive) AND the method body asserts BOTH (a) `asset_config.status` flips to `'failed'` after the configured threshold consecutive failures AND (b) `ThrottledAdminNotifier.notifyOnce` is invoked exactly once for that source. Verify: `grep -iE 'void\\s+\\w*failureCounterThresholdBreach\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match AND `grep -iE \"['\\\"]failed['\\\"]\" AssetSnapshotFetcherTest.java` returns ≥1 match AND `grep -iE 'notifyOnce|verify\\s*\\([^)]*notifier' AssetSnapshotFetcherTest.java` returns ≥1 match"
  - "AssetSnapshotFetcherTest contains a `@Test` method whose name contains `happyPathStoresSnapshot` (case-insensitive) that asserts a tick with a fake `AssetDataSource` returning a deterministic `PriceSnapshot` produces exactly one `price_snapshot` row with the expected `(asset, sub_verb, captured_at, price)` AND resets `consecutive_failures` to 0 AND populates `last_success_at`. Verify: `grep -iE 'void\\s+\\w*happyPathStoresSnapshot\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match AND `grep -iE 'consecutive_failures|last_success_at' AssetSnapshotFetcherTest.java` returns ≥1 match in the file (assertion shape pinned)"
  - "AssetSnapshotFetcherTest contains a `@Test` method whose name contains `fetchExceptionIncrementsCounter` (case-insensitive) that asserts a tick whose fake `AssetDataSource` throws `FetchException` does NOT INSERT a `price_snapshot` row, increments `asset_config.consecutive_failures` by 1, AND updates `asset_config.last_failure_at`. Verify: `grep -iE 'void\\s+\\w*fetchExceptionIncrementsCounter\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match AND `grep -iE 'last_failure_at|consecutive_failures.*\\+\\s*1|consecutive_failures.*= ?1' AssetSnapshotFetcherTest.java` returns ≥1 match"
  - "AssetSnapshotFetcherTest contains a `@Test` method whose name contains `disabledRowSkipped` (case-insensitive) that asserts a tick does NOT call `fetchSnapshot` for an `(asset, sub_verb)` row whose `enabled = false` AND does NOT INSERT a `price_snapshot` row for that pair. Verify: `grep -iE 'void\\s+\\w*disabledRowSkipped\\w*\\s*\\(' AssetSnapshotFetcherTest.java` returns ≥1 match AND `grep -iE 'never\\(\\)|times\\s*\\(\\s*0|isEmpty\\(\\)|assertEquals\\s*\\(\\s*0' AssetSnapshotFetcherTest.java` returns ≥1 match (the negative assertion expressed via any standard shape — see acceptance item for perHostScheduling)"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java exists, is `@ApplicationScoped`, and exposes a public `store(PriceSnapshot)` method that INSERTs one row into `price_snapshot` AND emits `NOTIFY new_price_snapshot, <payload>` in the SAME transaction. Payload is the spec-committed `(asset, source)` JSON shape per spec §Asset commands — Provider/Collector contract: `{\"asset\":\"<asset>\",\"source\":\"<sub_verb>\"}`. The INSERT bypasses the post outbox (no Stage 1/2, no quarantine path) per spec §Asset commands — Data is not posts. Verify: `grep -E '@ApplicationScoped' PriceSnapshotStore.java` returns ≥1 match AND `grep -E 'INSERT\\s+INTO\\s+price_snapshot' PriceSnapshotStore.java` returns ≥1 match AND `grep -E 'NOTIFY\\s+new_price_snapshot|pg_notify\\s*\\(\\s*''new_price_snapshot' PriceSnapshotStore.java` returns ≥1 match"
  - "PriceSnapshotStoreTest contains a `@Test` method whose name contains `insertEmitsNotify` (case-insensitive) that asserts a `store(snapshot)` call (a) writes exactly one row to `price_snapshot` AND (b) a LISTEN'ing connection on `new_price_snapshot` receives a payload containing the asset name AND the source name (assertion shape mirrors the M1-027 NewPostListenerTest pattern). Verify: `grep -iE 'void\\s+\\w*insertEmitsNotify\\w*\\s*\\(' PriceSnapshotStoreTest.java` returns ≥1 match AND `grep -iE 'getNotifications|PGNotification|LISTEN' PriceSnapshotStoreTest.java` returns ≥1 match"
  - "PriceSnapshotStoreTest contains a `@Test` method whose name contains `appendsToCurrentPartition` (case-insensitive) that asserts a `store(snapshot)` with a `captured_at` in the current month lands in the correct partition (a SELECT against the partition table by name returns the row; INSERT against the parent table is the public API). Verify: `grep -iE 'void\\s+\\w*appendsToCurrentPartition\\w*\\s*\\(' PriceSnapshotStoreTest.java` returns ≥1 match AND `grep -iE 'price_snapshot_p[0-9]{6}|partition' PriceSnapshotStoreTest.java` returns ≥1 match"
  - "PriceSnapshotStoreTest contains a `@Test` method whose name contains `transactionRollbackSuppressesNotify` (case-insensitive) that asserts a rolled-back transaction containing a `store(...)` call does NOT emit the NOTIFY to a listening connection (Postgres semantics: NOTIFY fires at COMMIT, not at INSERT — verifies the spec's atomicity guarantee on the snapshot/NOTIFY pair). Verify: `grep -iE 'void\\s+\\w*transactionRollbackSuppressesNotify\\w*\\s*\\(' PriceSnapshotStoreTest.java` returns ≥1 match AND `grep -iE 'rollback|setAutoCommit\\s*\\(\\s*false' PriceSnapshotStoreTest.java` returns ≥1 match"
  - "AssetDataSourceContractTest is a plain JUnit `@ParameterizedTest` (or sibling parameterized shape) that runs the same contract checks against all three impls instantiated with `new` + a permissive `SsrfGuardedHttpClient` (no CDI required — the contract checks the static surface only): (1) `supportedAssets()` is non-empty; (2) `supportedQuoteCurrencies('zcash')` is non-empty for sources that list zcash; (3) `attributionUrl(asset, vs)` returns a non-empty string starting with `http` and NOT containing `](` (D30 bare-URL invariant). The test does NOT make outbound HTTP — integration tests against real endpoints belong in a separate ITs run (this ticket adds none). Verify: `grep -E '@ParameterizedTest|@MethodSource|@ValueSource' AssetDataSourceContractTest.java` returns ≥1 match AND `grep -iE 'startsWith|http' AssetDataSourceContractTest.java` returns ≥1 match (asserts the http-prefix invariant in any standard shape) AND `wc -l AssetDataSourceContractTest.java` reports a file count under 200 lines"
  - "No pre-existing tests are modified by this ticket. Verify: `git diff main --stat -- 'infochat-*/src/test/**'` shows ONLY the three new test files (AssetSnapshotFetcherTest, PriceSnapshotStoreTest, AssetDataSourceContractTest) as added; zero modifications to other test files"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: all M1-007a Fetcher SPI tests, M1-023 RssFetcher tests, M1-027/028 NOTIFY tests, every M1-055a test, every M1-052..M1-054 test, every M1-056..M1-058 test, plus every other M1-008..M1-051 test currently green on main"
test_plan:
  adds:
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/AssetDataSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/CoingeckoSnapshotSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/BitfinexSnapshotSource.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/PriceSnapshot.java
    - infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStoreTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/source/AssetDataSourceContractTest.java
  modifies:
    - infochat-collector/src/main/resources/application.properties
  preserves:
    - all tests currently green on main
    - every test added by M1-055a
    - every test added by M1-058 (ThrottledAdminNotifier suite)
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/schema.md §Operational
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D22
  - D30
  - D33
  - D34
  - D39
  - D42
escalations:
  - date: 2026-05-24
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A — clarity pre-flight FAIL (no reviewer round).
      Blockers from target/m1-tick-clarity-M1-055b.txt:
        1. spec_refs entry "docs/spec/decisions.md D42" resolves to ANCHOR-NOT-FOUND.
           decisions.md stores entries as inline prose, not ATX headings;
           no heading containing "d42" exists. D42 belongs in decision_refs:,
           where it already appears. Mirrors M1-058 clarity-fail precedent
           (M1-058 hit identical blocker with D22 + D42 on 2026-05-24).
revisions:
  - date: 2026-05-24
    reason: refine after clarity-fail (drop the lone D-only spec_refs entry
      `docs/spec/decisions.md D42` that cannot resolve to an ATX heading;
      commands.md §Asset commands already in spec_refs inlines the D42
      per-source failure-counter contract; decision_refs preserves the
      D42 linkage. Mirrors M1-058's same-day refine.)
    snapshot:
      spec_refs:
        - docs/spec/commands.md §Asset commands
        - docs/spec/schema.md §Operational
        - docs/spec/security.md §DB roles
        - docs/spec/security.md §SSRF and outbound connections
        - docs/spec/decisions.md D42
---

# M1-055b: Asset fetchers (per public-endpoint host) + price_snapshot store + per-host tick cadence + NOTIFY emit

## Context

This subticket lands the **Collector-side fetch and store**
half of the T2-H asset-commands vertical (M1-055 umbrella):

1. The `V<next-free>__price_snapshot.sql` Flyway migration creates the
   `price_snapshot` table partitioned on `captured_at`
   (RANGE), with the spec-committed columns + composite
   `PRIMARY KEY (id, captured_at)` (Postgres partition-key
   participation requirement, V7 precedent) + per-role GRANT
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
   `fetchSnapshot(asset, vs)`, `attributionUrl(asset, vs)`,
   plus a nested checked `FetchException`. Three concrete
   impls (`CoingeckoSnapshotSource`, `KrakenSnapshotSource`,
   `BitfinexSnapshotSource`) cover the v1 sub-verb set per
   design §10.1.
4. Every concrete impl performs outbound HTTP through the
   shared `SsrfGuardedHttpClient` per spec §SSRF and
   outbound connections — the same library the existing
   RssFetcher uses. No bespoke HTTP client construction.
5. `AssetSnapshotFetcher` is a `@ApplicationScoped` CDI bean
   whose tick is **per-host** (three `@Scheduled` methods, one
   per data source: coingecko, kraken, bitfinex), NOT
   per-`(asset, sub_verb)` per spec §Asset commands — Polled,
   cached, refreshed on a tick. Each per-host tick reads the
   enabled `(asset, sub_verb)` rows from `asset_config` whose
   `sub_verb` matches the host and invokes the corresponding
   `AssetDataSource.fetchSnapshot(...)` for each pair.
   Per-host intervals are profile-driven (laptop/vps/pi/
   remote-llm per design §10.4); the `@ConfigProperty`
   declarations in source carry NO inline `defaultValue`
   (codebase convention for profile-driven properties per
   FetchScheduler.java:95-100), and the per-profile blocks
   live in `application.properties` alongside the existing
   heartbeat/concurrency profile keys.
6. Per-source consecutive-failure logic per D42: each
   FetchException increments `asset_config.consecutive_failures`
   and updates `last_failure_at`; each success resets the
   counter and updates `last_success_at`. On threshold
   breach the row's `status` flips to `failed` and a single
   throttled admin notification fires via the
   **`ThrottledAdminNotifier` CDI bean from M1-058** — the
   T2-G shared infrastructure that has been anticipated by
   multiple eval-pipeline callsites (Stage1Pipeline,
   Stage2VerdictHandler, TaggerWorker, EmbeddingWorker all
   carry "future T2-G throttled admin notifier" comments) and
   landed for the first time in M1-058. Recovery from `failed`
   is operator-side per design §10.8b (no chat-command
   equivalent in v1).
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
the threshold, stop, and fire exactly ONE admin notification
(not one per tick). `migration_touch: true` because V17
(or its rebased equivalent) is the canonical schema change.

## Definition of Done

- `V<next-free>__price_snapshot.sql` (whatever the next-free
  integer is at `/m1-tick start` time — V17 at refine time,
  may shift if other migrations land first) creates the
  partitioned `price_snapshot` table with the spec-committed
  columns, composite PK, latest-snapshot index, and per-role
  GRANTs.
- `application.properties` gains the base + 12 per-profile
  lines for the three `infochat.assets.refresh.<host>` keys
  (4 profiles × 3 hosts).
- The seven new classes under
  `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/`
  exist with the shape described above.
- The fetcher INJECTs the `ThrottledAdminNotifier` from
  M1-058 — no inline throttling state, no bespoke notifier.
- The three test classes exercise the fetcher tick behavior,
  the PriceSnapshotStore INSERT + NOTIFY semantics, and the
  AssetDataSource contract uniformly across impls.
- `mvn -B clean verify` exits 0.

## Notes

- **Per-host tick, not per-pair tick.** Spec §Asset commands
  is explicit: "All `kraken` snapshots across every enabled
  asset share one tick cadence; same for `coingecko` and
  `bitfinex`." Per-pair scheduling would multiply outbound
  traffic by N and break the upstream rate-limit budget.
- **Profile-driven intervals live in application.properties,
  not in @ConfigProperty defaultValue.** The codebase's
  codified rule for profile-driven properties is at
  FetchScheduler.java:95-100 and application.properties:72.
  Inline `defaultValue` is only permitted for single-global-
  default properties (e.g. the `infochat.assets.failure-
  threshold` key in this ticket); profile-driven keys must
  declare per-profile blocks in `application.properties`.
- **NOTIFY payload format.** Spec §Asset commands —
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
  "5"` (inline default permitted — single-global-default key).
  Operator can override.
- **ThrottledAdminNotifier is M1-058's infrastructure.** This
  ticket consumes it via `@Inject`. The notifier's API,
  throttle-window semantics, DB-backed throttle state
  (`admin_notification_state` table per schema.md:510), and
  test scaffolding are M1-058's responsibility. M1-055b does
  NOT modify or extend the notifier; it picks the appropriate
  key (`"asset-source-failed:" + asset + ":" + sub_verb`),
  the error class, and the message, and calls `notifyOnce(...)`.
- **Migration race.** V14 is M1-055a's asset_config; V15 is
  M1-052's saved_post; V16 is M1-058's
  admin_notification_state (assumed lands first per
  `blocked_by`). V17 is this ticket's price_snapshot — but
  may shift if other migrations land between M1-058 and
  M1-055b. Re-run `ls infochat-core/src/main/resources/db/
  migration/ | sort -V | tail` at `/m1-tick start` and
  rename the migration file accordingly. Substitute V<NN>
  for V17 in all acceptance greps if the number shifts.

## Big-picture notes

- **No Provider code in this ticket.** The Provider has
  SELECT on `price_snapshot` from V17 (or rebased equivalent)
  onward, but no Provider code reads it until M1-055c lands.
- **No `/asset-enable` admin command.** Design §10.8b is
  explicit: v1 ships operator-side recovery only via raw
  SQL UPDATE; a chat-command equivalent is v2.
- **RssFetcher does not adopt M1-058's notifier in this
  ticket.** FetchScheduler.java:58-66 already defers
  RssFetcher's D42 wiring to T2-B; M1-055b touches asset
  fetchers only. A separate follow-up ticket retrofits
  RssFetcher to consume M1-058's notifier; that is NOT this
  ticket's territory.
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
- **ThrottledAdminNotifier implementation.** M1-058
  territory. This ticket consumes the bean, does not modify it.
- **RssFetcher adopting M1-058's notifier.** Separate
  follow-up ticket. RssFetcher's D42 wiring remains deferred.
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

- **Build the ThrottledAdminNotifier inside M1-055b
  (files_budget 11 → 12 or 13).** Rejected — burying notifier
  infrastructure inside an asset-fetcher ticket means the
  notifier API gets shaped by one consumer's needs, while at
  least four other call-sites (Stage1Pipeline,
  Stage2VerdictHandler, TaggerWorker, EmbeddingWorker) already
  carry "future T2-G throttled admin notifier" comments and
  will need to adopt the same bean. M1-058 builds the notifier
  as its own ticket with its own acceptance and review.
- **Inline a `Map<String,Instant>` throttle in
  AssetSnapshotFetcher (option from plan-writer round 1).**
  Rejected — same reason as above plus the spec commits to
  DB-backed throttle state (`schema.md:510` Admin
  notification state — backing store for the throttled admin
  notifier (D22)). In-memory throttling is a v1 shortcut from
  the spec that the centralized M1-058 notifier avoids.
- **Drop the failure-counter / threshold / notifier from
  M1-055b entirely (scope-cut to data plumbing only).**
  Rejected — D42 + D22 + schema.md:510 + security.md +
  architecture.md:178-179 all commit to per-source
  failure-counter + throttled admin notification. Dropping
  them would require a spec amendment, not a refine.
- **One Scheduled tick per `(asset, sub_verb)`.** Rejected
  — spec §Asset commands is explicit that the tick is
  per-host. Per-pair scheduling would break the upstream
  rate-limit budget and complicate the throttled admin
  notifier's per-source state.
- **Write `price_snapshot` rows via the outbox.** Rejected
  — spec §Asset commands is explicit: "Data is not posts.
  Snapshots are stored in a collector-owned table outside
  the post pipeline. They never go through Stage 1/2,
  tagging, entity extraction, or embedding."
- **Use HTTP HEAD or a lightweight ping in the contract
  test to verify per-host endpoints are reachable.**
  Rejected — the contract test asserts the static
  surface; outbound HTTP is brittle, slow, and an
  availability dependency the CI run does not own.
  Real-endpoint smoke testing is operator-side.
