---
id: M1-973
title: "Weather structured source: collector pipeline + getWeather tool"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  GetWeatherToolIT#dispatchReturnsLatestObservationForConfiguredLocation
  (to-be-written; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/websearch-grounding-lane.md; converted at
  /tick start: written first, run RED — dispatch of "getWeather"
  returns the typed "Error: Unknown tool: getWeather" because the
  closed allowlist holds eight names (ChatToolRegistry.java:18-27,
  mirrored by docs/spec/security.md:328-335), and no weather machinery
  exists anywhere: grep -rn 'weather\\|Weather\\|open-meteo\\|OpenMeteo'
  over infochat-provider/src/main, infochat-collector/src/main and
  infochat-core/src/main/resources/db/migration returns NO match
  (verified 2026-09-01). The wrong behavior it states: the motivating
  query class "what is current weather in Bangkok?" is unservable by
  any typed path — the agent would send it to the web lane (M1-972)
  whose snippets cannot carry a timestamped observation, or answer from
  stale memory).
analysis_ref: docs/plan/m1/tick-analysis/websearch-grounding-lane.md
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V88__weather_observation.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/weather/WeatherObservation.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/weather/WeatherConfig.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/weather/source/OpenMeteoWeatherSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/weather/WeatherObservationStore.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/weather/WeatherSnapshotFetcher.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetWeatherTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolCatalog.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/weather/source/OpenMeteoWeatherSourceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetWeatherToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetWeatherToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/spec/security.md
  - docs/spec/architecture.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The web-grounding lane in ANY form (analysis P18) — this ticket is
    the SEPARATE structured-source family's first member: no
    websearch/fusion/trigger/notice change; no dependency on
    M1-969..972; weather is routed AWAY from web search by design
    (BINDING user direction).
  - >-
    ANY change to the asset pipeline's semantics — AssetSnapshotFetcher,
    AssetDataSource, PriceSnapshot(Store), AssetSnapshotReader,
    AssetRegistry, AssetHandler and their suites are the PATTERN being
    mirrored, not surfaces to edit; overloading price_snapshot or
    asset_config with weather is rejected (analysis O7: the price SPI
    and freshness contract are price-shaped).
  - >-
    Natural-language geocoding, arbitrary-location on-demand fetches,
    and location inference (analysis P16): locations are
    operator-configured exact keys; the tool resolves them exactly
    (getPrice's exact-key rule) and its unknown-location error lists
    the configured set. An UNCONFIGURED location is not silently
    unanswerable: once the P20 fallback ladder lands (M1-972), such a
    turn may be answered as web-grounded via the fallback — the
    catalog description states that degrade path honestly instead of
    implying the question is out of reach. A geocode lane (Nominatim)
    and per-ask provider-side fetches are separate future family
    members.
  - >-
    A /weather COMMAND, forecasts/air-quality fields beyond the first
    increment's snapshot shape, multi-day series, and alerts — the
    first-increment snapshot is current-conditions only; extensions
    grow the SAME typed shape via their own tickets.
  - >-
    Provenance-notice wiring for getWeather (the getPrice rule
    verbatim): weather is not feed-post grounding — a weather-grounded
    reply keeps the not-feed-grounded notice; POST_CORPUS_TOOLS
    (ChatAgent.java:1418-1419) is NOT touched and no ChatAgent
    production change is expected (the tool loop is generic over
    names).
  - >-
    The web lane's spec posture (M1-969) — getWeather's typed output is
    K1-compliant as-is; its ONLY spec cost is the closed-allowlist row
    addition, which rides this diff (the M1-931 precedent), plus the
    §DB-roles sentence for the new table's grants.
acceptance:
  - "REPRODUCTION closed: GetWeatherToolIT.dispatchReturnsLatestObservationForConfiguredLocation passes — seeds the configured-location registry (one location: key, label, attribution URL) plus two weather_observation rows (distinct captured_at) via JDBC, dispatches {\"location\":\"<key>\"} through a REAL ChatToolDispatcher, and asserts the Success JSON carries the NEWER row's fields, \"stale\":false, and the configured attribution URL verbatim (cited bare). Non-vacuity: a mutation returning the older row, hiding absent numerics behind zeros, or dropping/mis-naming any asserted field fails."
  - "FRESHNESS CONTRACT (the getPrice rule, commands.md §Asset commands' stale-with-age posture): GetWeatherToolIT.staleObservationIsServedWithAgeDisclosed passes — the app Clock pinned via QuarkusMock.installMockForType(Clock.fixed(...)) (engineering-rules §9) so the seeded row's age exceeds infochat.weather.freshness-window; the result STILL carries the observation AND \"stale\":true AND \"age_seconds\" equal to pinnedNow − captured_at; a mutation hiding the stale row or dropping stale/age fails. NO-DATA SHAPE: GetWeatherToolIT.locationWithNoRowReturnsTypedNoDataError — a configured location with ZERO rows returns a ToolResult.ValidationError naming the location (never a Success with invented values)."
  - "RESOLUTION FAILURE MODES: GetWeatherToolTest.unknownLocationErrorListsConfiguredLocations passes — (plain JUnit; populated config via the test seam, reader stubbed at readLatest) the typed self-correctable error enumerating the configured keys (the helpLookup {command:null} / getPrice kin); GetWeatherToolTest.absentConfigEveryLocationResolvesUnknown passes — no bootstrap file → every location resolves unknown and the error says so; GetWeatherToolTest.resolutionIsBlindToCallerIdentity passes — no per-user/scope input reaches resolution (deployment-global operator config — the getPrice no-world-predicate rule)."
  - "FOLD BOUNDARY SITING (assertion-adequacy §8; the M1-648/M1-931 lesson): ChatAgentTest.getWeatherResultRidesBackWrappedWithPostToolInstruction passes — a scripted text-transport turn (TOOL_CALL: getWeather {\"location\": …}) with the tool stubbed asserts llmProvider.lastUserPrompt carries 'Tool result for getWeather', the UNTRUSTED_CONTENT wrapper, and POST_TOOL_RESULT_INSTRUCTION verbatim, AND the delivered reply carries no protocol fragment."
  - "COLLECTOR SOURCE (analysis P14/P15; the AssetDataSource contract): OpenMeteoWeatherSourceTest — against a local com.sun.net.httpserver harness (the collector fetcher-test idiom) the source issues ONE read-only GET through SsrfGuardedHttpClient (asserted: the single pinned-host constant, latitude/longitude as the only configured parameters, no key header for the keyless API — the implementer verifies the endpoint/field names against the live API at start and records it in the commit, analysis P14); the typed parse maps the fixture's fields, treats absent numerics as null (never invented), and surfaces any failure (SSRF rejection, non-2xx, malformed body, missing field) as the typed FetchException shape the fetcher counts — never a runtime escape."
  - "GRANTS + MIGRATION (D34 mirror): V88__weather_observation.sql creates the table keyed (location_key, captured_at) with the snapshot columns, and grants Collector INSERT-only / Provider SELECT-only (the price_snapshot posture, security.md §DB roles); the migration suite passes and a grant test asserts the Provider role cannot INSERT (the existing migration-test idiom); spec §DB roles gains the one-sentence record (probe: the amended §DB-roles paragraph names weather_observation's INSERT-only/SELECT-only split)."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; the M1-931 list verbatim shape): (a) ChatToolRegistryTest.registryContainsExactlySpecTools — expected set gains \"getWeather\" (the eight existing names unchanged); (b) ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing — gains assertArgs(\"getWeather\", List.of(\"location:string\")); (c) ChatAgentTest.renderedInstructionTableIsByteIdentical — APPENDS the getWeather line quoted verbatim in the Approach; the eight existing lines stay byte-identical; (d) the wire-declaration count assertion becomes 9. Probe: git diff over src/test names exactly these four hunks plus the added test files/tests."
  - "ALLOWLIST PARITY lands both sides in one diff (security.md:313-316 'closed at spec level'): ChatToolAllowlistSpecParityTest.registryMatchesMarkedSpecTable passes WITH the getWeather row added inside the tool-allowlist markers — the row's Inputs column is `location: string` (configured location name, length-capped), Output column the typed snapshot `{location, label, source, temperature_c, apparent_temperature_c, humidity_pct, wind_speed_kmh, precipitation_mm, weather_code, captured_at, age_seconds, stale, source_url}` or a typed no-data validation error; Notes carry the getPrice-kin rules (operator-configured deployment-global — no (user, scope) world filter, never feeds the feed-post provenance count; stale served with age disclosed within the operator's max-staleness bound — the bounded freshness contract M1-974 lands for getPrice, and this row states the SAME contract so the two rows cannot drift; absent numerics null; source_url the operator attribution cited bare; not post data). Probe: grep -n 'getWeather' docs/spec/security.md returns the row inside the markers."
  - "ARCHITECTURE RECORD: docs/spec/architecture.md §Ingest SPIs gains the weather lane's one-line record (a polled, per-host-cadence structured source writing directly to its own snapshot table, never entering the post outbox or Stage 1/2 — the asset-SPI posture); probe: grep -n 'weather' docs/spec/architecture.md returns the §Ingest-SPIs mention."
  - "DESIGN LEDGER: docs/design/05-llm-and-embeddings.md §5.4.6 records the getWeather catalog line and a prompt-byte ledger entry (≤ ~60 words riding the never-droppable instruction scaffolding, absorbed by the budget headroom — the M1-931 posture); probe: grep -n 'getWeather' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mentions."
  - "CONFIG PARITY (M1-708 discipline): the new keys (infochat.weather.freshness-window on the provider; the collector's fetch cadence and the bootstrap-file path key) land in application.properties AND the docs that name them in the SAME diff — probe: every infochat.weather.* key the code reads exists in the properties files."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetWeatherToolIT.java
      — dispatchReturnsLatestObservationForConfiguredLocation (the
      reproduction), staleObservationIsServedWithAgeDisclosed,
      locationWithNoRowReturnsTypedNoDataError.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetWeatherToolTest.java
      — resolution failure modes (plain JUnit; no container).
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/weather/source/OpenMeteoWeatherSourceTest.java
      — the harness-backed source contract (typed parse, null-never-
      invented, typed failure).
    - ChatAgentTest.getWeatherResultRidesBackWrappedWithPostToolInstruction
      (fold boundary pin).
  modifies:
    - >-
      ChatToolRegistryTest.java — expected set gains getWeather
      (§8-authorized, item 7a).
    - >-
      ChatToolCatalogTest.java — assertArgs getWeather line
      (§8-authorized, item 7b).
    - >-
      ChatAgentTest.java — byte pin appends the getWeather line;
      wire-declaration count 8→9 (§8-authorized, items 7c/7d).
  preserves:
    - >-
      all tests currently green on main — explicitly
      ChatToolAllowlistSpecParityTest (auto-covers the new row),
      ChatToolDispatcherTest (loops registry names; the package-private
      test constructor unchanged), ChatPromptBudgetTest (the scaffolding
      pin includes the ninth line automatically), ChatAgentProvenanceTest
      (getWeather never joins POST_CORPUS_TOOLS), and every asset suite
      (AssetHandlerIT, AssetSnapshotReader* — the pipeline is mirrored,
      not touched).
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §DB roles
  - docs/spec/commands.md §Asset commands
  - docs/spec/commands.md §Chat mode
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D30
  - D34
  - D39
---

# M1-973: Weather structured source — collector pipeline + getWeather tool

## Context

The motivating query class "what is current weather in Bangkok?" is a
fresh point-data question, and the user's researched routing (BINDING)
sends it to the structured live-data-source family — the
getPrice/AssetDataSource pattern — NOT to web search: a typed snapshot
with `captured_at`/`age`/`stale`, operator-configured, fetched
collector-side from a keyless API, read provider-side by a deterministic
tool. No typed output discipline is bent: K1 is satisfied as-is (the
brief's claim, verified against the getPrice row
`security.md:335`); the only spec cost is the closed-allowlist row
addition, which rides this diff exactly as M1-931's did. This ticket is
the family's first member and is INDEPENDENT of the web lane
(M1-968..972). Shared analysis: `analysis_ref:` (this ticket carries
P3, P14, P15, P16, P18).

## Root cause

Verified absence: no weather/open-meteo symbol in any main source or
migration (grep, 2026-09-01); the allowlist holds eight names
(`ChatToolRegistry.java:18-27`); the pattern to mirror exists end to end
— collector SPI + store + fetcher
(`AssetDataSource.java`, `PriceSnapshotStore`, `AssetSnapshotFetcher`),
provider reader + tool (`AssetSnapshotReader`, `GetPriceTool.java:38-94`),
grants (`security.md` §DB roles: Collector INSERT-only, Provider
SELECT-only on `price_snapshot`), operator bootstrap config
(`bootstrap-assets.json`, deployment-side), migration slot V88 (latest
is V87). The architecture slot is the asset-SPI posture verbatim:
polled, per-host cadence, snapshots written directly to a collector-
owned table, never entering the post outbox or Stage 1/2
(`commands.md` §Asset commands "Data is not posts").

## Pitfalls

Carried from the analysis: P3 (every pinned surface moves — the four
§8-authorized modifications enumerated verbatim, M1-931's list), P14
(Open-Meteo's endpoint/field names are session-unverified: ONE pinned
constant + implementer verification at start; keylessness asserted by
the no-key-header test), P15 (typed-snapshot discipline: D19 SQL row
choice, stale-with-age within the bounded freshness contract,
null-never-invented, no world predicate, no provenance-count
contribution, INSERT/SELECT grant split, collector fetch through
SsrfGuardedHttpClient), P16 (configured exact-key locations; honest
catalog text that STATES the web-fallback degrade path rather than
implying unanswerable-without-configuration; no geocoding), P18 (no
web-lane file or posture touched), P20 (the fallback ladder is
agent-layer only — this ticket wires NOTHING for it; the unknown-location
typed error is simply a no-data outcome the M1-972 layer may observe,
and the row's wording cross-references the bounded freshness contract
M1-974 lands). Also: config parity (M1-708) and the D30 bare-URL
attribution rule (the AssetDataSource contract-test precedent pins no
`](` in the URL).

## Approach

Derived from `spec_refs:` — §Asset commands owns the structured-source
posture being instantiated (D39's rules carried to a second data
class); §Prompt-injection defenses owns the closed table the row joins;
§DB roles owns the grant split; §Ingest SPIs owns the collector-side
lane record; §Determinism boundary keeps the tool LLM-free (SQL row
choice, no model call); §Chat mode unchanged except the row's ride.

- **Files to touch:** `files_scope` (one migration, five collector
  files, four provider files, two properties files, six test files,
  three docs).
- **Pre-decided shapes (implementation is execution):**
  1. **Migration V88** `weather_observation`: `(location_key text,
     captured_at timestamptz, temperature_c numeric,
     apparent_temperature_c numeric, humidity_pct numeric,
     wind_speed_kmh numeric, precipitation_mm numeric, weather_code
     int, PRIMARY KEY (location_key, captured_at))`; grants: Collector
     INSERT-only, Provider SELECT-only (the `price_snapshot` posture).
  2. **Collector:** `WeatherObservation` (typed record, null-able
     numerics); `WeatherConfig` (loads the deployment-side
     bootstrap-weather.json: locations {key, label, latitude,
     longitude, attribution_url}; absent file → empty config, the lane
     idles); `OpenMeteoWeatherSource` (ONE pinned-host constant,
     read-only GET via `SsrfGuardedHttpClient`, lat/lon the only
     parameters, typed FetchException on every failure — the
     `AssetDataSource` contract shape); `WeatherObservationStore`
     (INSERT, dedup posture mirroring `PriceSnapshotStore`);
     `WeatherSnapshotFetcher` (per-host cadence schedule mirroring
     `AssetSnapshotFetcher`, failure-counter ladder hookup).
  3. **Provider:** `GetWeatherTool` — exact-key location resolution
     against the loaded config (unknown → typed error listing the
     configured keys), latest-row read (ORDER BY captured_at DESC
     LIMIT 1 — D19), `age_seconds` from the injected `Clock`,
     `stale` from `infochat.weather.freshness-window`, absent numerics
     `null`, `source_url` the configured attribution cited bare;
     registry + catalog + dispatcher wiring (the ninth name) with the
     M1-931 §8 pin updates.
  4. **Catalog description** (semantic elements the byte pin asserts):
     current weather for the operator-configured locations, with
     capture time and age; use it for weather questions about
     configured places and never state weather from memory; a stale
     result is presented as old data with its capture time; an unknown
     location lists the configured places AND may still be answered
     from the web fallback lane when that lane is available (stated
     plainly; no geocode promise).
  5. **Docs:** the spec row (item 8), §DB roles sentence, §Ingest SPIs
     line, design-05 ledger — with the user's wording approval at
     implementation for every `docs/spec/**` edit (§12).
- **Steps, in implementation order:** (1) migration + grants RED in the
  migration suite; (2) collector source/store/fetcher + harness tests;
  (3) provider tool + wiring + the four §8 pin updates; (4) ITs with
  pinned Clock; (5) docs; (6) full `mvn verify`.
- **Controls to preserve (§10):** the asset pipeline and its suites
  untouched (mirrored, not edited); the dispatcher boundary (length
  caps before any SQL — `location` rides the existing string cap);
  POST_CORPUS_TOOLS unchanged (no provenance wiring); the byte-pinned
  instruction table's eight existing lines byte-identical;
  `SsrfGuardedHttpClient` a dependency, never modified.
- **Pitfall→mitigation:** P3→item 7's enumerated §8 list + diff probe;
  P14→item 5's single-constant + verification-at-start record;
  P15→items 1/2/3 (grants, typed parse, stale contract, no world
  predicate); P16→item 4's honest catalog text + the unknown-location
  error; P18→the diff names no websearch file.

## Definition of done

The reproduction, freshness, no-data, and resolution drives pass; the
collector source contract holds against the harness (typed failures,
null-never-invented, keyless); V88 lands with the INSERT/SELECT grant
split and its grant test; the four §8-authorized pin updates land; the
spec row joins the table inside the markers with parity green; the
architecture and design records land; config parity holds; every
pre-existing suite (explicitly all asset suites) passes unchanged;
`mvn verify` green from the repo root.

## Verification

- P3 → item 7's probe (a fifth test edit fails the fence).
- P14 → item 5's harness assertions (single GET, no key header, pinned
  constant) + the commit's recorded live-API verification.
- P15 → items 1 (grant test: a Provider-role INSERT fails), 2 (typed
  failure surface), 3 (stale/no-data/nulls, no world predicate — the
  fixture is deployment-global and asserts the same answer regardless
  of scope), 6 (parity row), 8 (fold boundary siting past the tool
  seam).
- P16 → `unknownLocationErrorListsConfiguredLocations` + the catalog
  text's semantic pins (a geocode-implying wording fails the
  honest-scope marker; so does a wording implying an unconfigured
  location is unanswerable — the web-fallback degrade sentence is a
  pinned semantic element).
- P18 → reviewer diff fence: no websearch file, no M1-969..972 spec
  text.
- P20 → observation-only interplay, verified by the same diff fence:
  git diff names no ChatAgent/websearch hunk and no tool file that
  consumes snippet bytes (the unknown-location typed error rides the
  tool's EXISTING no-data shape — nothing is wired for the fallback
  here); the bounded-contract cross-reference is carried by the
  spec-row item's probe.
- FAILURE-MODE coverage → items 2-5 feed hostile/edge input (stale row,
  no row, unknown location, absent config, SSRF rejection, malformed
  body, over-cap input) to this diff's own production code and assert
  the protected behaviors.

## Out-of-scope

Named in `out_of_scope`: the web lane; the asset pipeline's semantics;
geocoding and arbitrary-location fetches; a /weather command,
forecasts, air quality, series, alerts; provenance wiring; the web
lane's spec posture. FOUR pre-existing test artifacts are modified,
each §8-authorized in acceptance item 7 with the new expected behavior
stated in plain language; every other pre-existing suite must pass
unmodified.

## Census

Class-scoped: every guard that enumerates the closed tool set must
gain the ninth name in the same diff or one side silently drifts — a
class of pinned surfaces, disposed per the M1-940 census style.
Re-runnable enumeration: `grep -n '^   | \`' docs/spec/security.md`
over the tool-allowlist markers, plus the four test-side pins. Rows
(states verified at draft time, 2026-09-01):

- security.md:328 `searchPosts` → DISPOSED, byte-identical.
- security.md:329 `semanticSearch` → DISPOSED, byte-identical.
- security.md:330 `getPost` / :331 `getReferences` / :332
  `recallMemory` / :333 `listSaves` / :334 `helpLookup` → DISPOSED,
  byte-identical.
- security.md:335 `getPrice` → DISPOSED, byte-identical — the kin
  this ticket mirrors (the M1-931 row).
- NEW `getWeather` row → **FIX** (acceptance item 8;
  `ChatToolAllowlistSpecParityTest` auto-covers the row).
- `ChatToolRegistryTest.registryContainsExactlySpecTools` → **FIX**
  (item 7a).
- `ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing` →
  **FIX** (item 7b).
- `ChatAgentTest.renderedInstructionTableIsByteIdentical` → **FIX**
  (item 7c — one appended line; the eight existing lines
  byte-identical).
- `ChatAgentTest` wire-declaration count pin → **FIX** (item 7d,
  8→9).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-973-weather-structured-source.md
```
