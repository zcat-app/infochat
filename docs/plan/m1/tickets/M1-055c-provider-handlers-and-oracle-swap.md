---
id: M1-055c
title: /zcash + /monero handlers + reply renderer + AssetCommandFamilyOracle impl swap + /help context-awareness
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by:
  - M1-055a
  - M1-055b
files_budget: 13
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandFamilyOracleTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §Asset commands + §Slow-start tier + §Capability flags + design §10.5 reply layout are complete on main HEAD; this ticket implements them
  - any change to M1-055a's BootstrapAssetsParser / BootstrapAssetsEntry / BootstrapAssetsLoader / V14__asset_config.sql — that commit is FROZEN; this ticket consumes `asset_config` rows as input
  - any change to M1-055b's AssetSnapshotFetcher / AssetDataSource / AssetDataSource impls / PriceSnapshotStore / V15__price_snapshot.sql — that commit is FROZEN; this ticket consumes `price_snapshot` rows as input
  - any change to AssetCommandFamilyOracle's PUBLIC INTERFACE — the M1-045 seam's signature `boolean isAssetCommand(@NonNull String slashCommand)` is held stable; only the method body changes
  - any change to CommandPermissions.java — M1-045's consumer of AssetCommandFamilyOracle is unaffected by the impl swap (verified at infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java line 59 + line 62 — the field type and constructor signature stay identical)
  - any change to InboundRouter.java — the two handlers register as new CommandHandler beans and are picked up by Instance<CommandHandler> iteration at InboundRouter.handleSlash lines 559-568 (verified at brief-authoring time)
  - any change to ConfirmStateService — asset commands are non-destructive; no confirm gate applies
  - any change under infochat-core/src/main/resources/db/migration/ — both schema changes are M1-055a's and M1-055b's commits; this ticket adds no migration
  - any TranslationProvider integration — asset replies ship English-only via the bundle keys this ticket authors
  - any /summary / /save / /saved / /quarantine integration — spec §Asset commands explicitly excludes asset snapshots from those surfaces
  - any /asset-enable / /asset-disable / /list-assets admin command — v1 ships operator-side enable/disable only per design §10.8b
  - any new asset beyond /zcash + /monero — v1 ships exactly those two per design §10.1
  - any auth-gated exchange or websocket "live" mode — v2 per design §10.9
  - any LLM call from the asset handler path — spec §Asset commands explicitly excludes asset snapshots from Stage 1/2 / tagging / embedding; the handler is a single SQL read
  - any branch on `supportsCodeFormatting` in the asset reply renderer — per design §10.5 the layout is plain text with bare URLs (D30); the brief names the flag specifically so a future session does not mistakenly add a richer rendering branch
  - any change to AuditAction.java — the handler path writes no audit rows
  - any test outside the three test files in files_scope — every pre-existing provider test continues to pass unchanged
acceptance:
  - "AssetCommandFamilyOracle.java's public interface remains unchanged: still `public class AssetCommandFamilyOracle`, still `@ApplicationScoped`, still exposes `public boolean isAssetCommand(@NonNull String slashCommand)` with the same signature. Only the method body changes plus an added constructor / field for the registry. Verify: `grep -E 'public\\s+boolean\\s+isAssetCommand\\s*\\(@NonNull\\s+String\\s+slashCommand\\)' AssetCommandFamilyOracle.java` returns ≥1 match"
  - "AssetCommandFamilyOracle's swapped body consults the loaded asset registry: it returns true iff `slashCommand` (case-sensitive) is in the set of enabled asset names loaded from `asset_config WHERE enabled = true`. Implementation may be (a) an in-memory `Set<String>` populated by the AssetRegistry's @Startup loader, OR (b) a per-call SELECT against `asset_config` — author's call. The interface MUST match the new contract: `isAssetCommand(\"zcash\")` returns true when an enabled `zcash` row exists in `asset_config`, false otherwise. Verify: AssetCommandFamilyOracleTest has @Test methods whose names contain `enabledAssetReturnsTrue` AND `disabledAssetReturnsFalse` AND `unknownAssetReturnsFalse` AND `caseSensitiveMatch` — `grep -iE 'void\\s+\\w*(enabledAssetReturnsTrue|disabledAssetReturnsFalse|unknownAssetReturnsFalse|caseSensitiveMatch)\\w*\\s*\\(' AssetCommandFamilyOracleTest.java` returns ≥4 matches"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetRegistry.java exists, is `@ApplicationScoped`, and reads `asset_config` (SELECT-only per Provider DB role) to populate a per-asset registry the handlers and oracle consult. The bean has an @Observes StartupEvent (or @Startup) handler that runs at Provider boot and refreshes the registry. The Provider role does NOT have INSERT/UPDATE on `asset_config` per V14's GRANTs; the registry is read-only at this layer. Verify: `grep -E '@ApplicationScoped' AssetRegistry.java` returns ≥1 match AND `grep -E 'SELECT.*FROM\\s+asset_config' AssetRegistry.java` returns ≥1 match AND `grep -E '@Observes\\s+StartupEvent|@Startup' AssetRegistry.java` returns ≥1 match AND `grep -E 'INSERT\\s+INTO\\s+asset_config|UPDATE\\s+asset_config' AssetRegistry.java` returns ZERO matches"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java exists, is `@ApplicationScoped`, and dispatches by asset name to `AssetHandler` instances. Either: (option A) ONE AssetCommandRouter bean implementing `CommandHandler` with `name()` returning a sentinel and dispatching internally — rejected because InboundRouter.handleSlash matches on `handler.name()` exact match (line 562); OR (option B) TWO `CommandHandler` beans (one per asset) whose `name()` returns `\"zcash\"` and `\"monero\"` respectively, each delegating to a shared `AssetHandler` base — the chosen shape. Verify: TWO `@ApplicationScoped` beans exist (Zcash + Monero command handlers) — either as inner static classes within AssetCommandRouter.java OR as separate package-private top-level classes (author's call; the M1-049 test-pyramid rule against inner test classes does NOT apply to production code). `grep -E 'public\\s+String\\s+name\\(\\)' AssetCommandRouter.java` returns ≥2 matches OR `grep -lE '@ApplicationScoped' infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/` lists at least one bean class per asset"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java exists as the shared base (or sole dispatcher — author's call). It parses the sub-verb and `--vs` argument, validates them against `AssetRegistry`, looks up the latest snapshot via `AssetSnapshotReader`, and hands the result to `AssetReplyRenderer`. The handler path makes ZERO LLM calls and reads ONLY `asset_config` + `price_snapshot` (no `posts`, no `quarantine`, no `audit_log` read). Verify: AssetHandlerTest has a @Test method whose name contains `noLlmCall` (case-insensitive) AND the test injects a fail-loud LLM SPI mock that throws on any method call; the test asserts a `/zcash <sub-verb>` happy-path invocation completes without the mock throwing. `grep -iE 'void\\s+\\w*noLlmCall\\w*\\s*\\(' AssetHandlerTest.java` returns ≥1 match"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java exists, is `@ApplicationScoped`, and exposes a public method that reads the latest `price_snapshot` row for `(asset, sub_verb, vs_currency)` using the V15 lookup index. The reader reads `asset_config.last_success_at` AND the per-host refresh interval (via @ConfigProperty) to compute the stale-marker threshold `2 * refresh_interval` per design §10.4. Verify: `grep -E '@ApplicationScoped' AssetSnapshotReader.java` returns ≥1 match AND `grep -E 'SELECT.*FROM\\s+price_snapshot' AssetSnapshotReader.java` returns ≥1 match AND `grep -E 'ORDER\\s+BY\\s+captured_at\\s+DESC' AssetSnapshotReader.java` returns ≥1 match AND `grep -E 'LIMIT\\s+1' AssetSnapshotReader.java` returns ≥1 match"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java exists, is `@ApplicationScoped`, and produces plain-text reply bodies per design §10.5: header line `<DisplayName> (<source>)` + optional ` ⚠ stale` marker; price line; optional delta lines (coingecko only — exchange sub-verbs omit); 24h spread line; capture timestamp + cache age line; attribution URL bare on its own line. Absent snapshot fields are silently omitted (renderer never invents zeros). The renderer DOES NOT branch on `supportsCodeFormatting` per design §10.5 (plain text, bare URLs is the universal layout per D30). Verify: `grep -E '@ApplicationScoped' AssetReplyRenderer.java` returns ≥1 match AND `grep -E 'supportsCodeFormatting|supportsMarkdownLinks' AssetReplyRenderer.java` returns ZERO matches (the renderer must not consult capability flags)"
  - "AssetReplyRendererTest has a @Test method whose name contains `coingeckoLayout` (case-insensitive) that asserts the rendered body for a coingecko snapshot with every optional field populated matches the design §10.5 example shape: header + price-with-btc + 1h delta + 24h delta + 24h spread + capture/cache line + bare attribution URL. The test verifies NO markdown link syntax: `assertFalse(rendered.matches(\"(?s).*\\\\[.*\\\\]\\\\(http.*\"))` (or equivalent). Verify: `grep -iE 'void\\s+\\w*coingeckoLayout\\w*\\s*\\(' AssetReplyRendererTest.java` returns ≥1 match"
  - "AssetReplyRendererTest has a @Test method whose name contains `exchangeAsymmetricFields` (case-insensitive) that asserts the rendered body for a Kraken or Bitfinex snapshot omits delta lines (per design §10.5 table: exchanges do not provide change_1h/24h/7d_pct) but still includes the 24h spread line. The assertion pins the spec commitment 'the renderer omits absent fields — does not invent zeros'. Verify: `grep -iE 'void\\s+\\w*exchangeAsymmetricFields\\w*\\s*\\(' AssetReplyRendererTest.java` returns ≥1 match"
  - "AssetReplyRendererTest has a @Test method whose name contains `staleMarker` (case-insensitive) that asserts a snapshot whose `captured_at` is older than `2 * refresh_interval` for the source produces a reply whose header line contains the ` ⚠ stale` marker per design §10.4. The test seeds a snapshot with `captured_at = NOW() - 5 * refresh_interval` and asserts the marker fires. Verify: `grep -iE 'void\\s+\\w*staleMarker\\w*\\s*\\(' AssetReplyRendererTest.java` returns ≥1 match"
  - "AssetHandlerTest has a @Test method whose name contains `bareInvocationDefaultSubVerb` (case-insensitive) that asserts bare `/zcash` (no sub-verb argument) resolves to the per-asset `default_sub_verb` from `asset_config` where `is_default = true` AND returns the rendered snapshot reply for that sub-verb. Verify: `grep -iE 'void\\s+\\w*bareInvocationDefaultSubVerb\\w*\\s*\\(' AssetHandlerTest.java` returns ≥1 match"
  - "AssetHandlerTest has a @Test method whose name contains `bareInvocationAbsentDefault` (case-insensitive) that asserts bare `/zcash` when NO row carries `is_default = true` for `zcash` returns the friendly 'not configured' error (per spec §Asset commands — bare `/zcash` paragraph: 'when no row carries `is_default = true` for the asset, bare `/zcash` returns the same friendly \"not configured\" error as an unknown sub-verb'). The reply body matches the `error.asset.not_configured` bundle value. Verify: `grep -iE 'void\\s+\\w*bareInvocationAbsentDefault\\w*\\s*\\(' AssetHandlerTest.java` returns ≥1 match"
  - "AssetHandlerTest has a @Test method whose name contains `bareInvocationDefaultButDisabled` (case-insensitive) that asserts bare `/zcash` when the default-flagged row has `enabled = false` (runtime defense-in-depth case per spec §Asset commands — Default-but-disabled fallback) returns the friendly 'default sub-verb is currently disabled; pass an explicit sub-verb' error with the asset's enabled sub-verbs listed. The reply body matches the `error.asset.default_disabled` bundle value. Verify: `grep -iE 'void\\s+\\w*bareInvocationDefaultButDisabled\\w*\\s*\\(' AssetHandlerTest.java` returns ≥1 match"
  - "AssetHandlerTest has a @Test method whose name contains `unknownSubVerbFuzzy` (case-insensitive) that asserts `/zcash krakn` returns the friendly fuzzy-suggestion error matching the design §10.8 shape: 'Unknown sub-verb krakn for /zcash. Did you mean: kraken? Available: coingecko, kraken, bitfinex.'. The reply body matches the `error.asset.unknown_sub_verb` bundle key interpolated with the fuzzy suggestion. Verify: `grep -iE 'void\\s+\\w*unknownSubVerbFuzzy\\w*\\s*\\(' AssetHandlerTest.java` returns ≥1 match"
  - "AssetHandlerTest has a @Test method whose name contains `subVerbNotEnabledForAsset` (case-insensitive) that asserts `/monero binance` (when `binance` is not in `monero`'s enabled sub-verb set per spec §Asset commands — '`/monero` Same shape as `/zcash`. The enabled sub-verb set is **not** the same: exchanges that do not list XMR (Binance, Coinbase, Gemini) are not exposed for `/monero`') returns the friendly 'sub-verb not enabled for this asset' error per design §10.8. The reply body matches the `error.asset.sub_verb_not_enabled` bundle key. Verify: `grep -iE 'void\\s+\\w*subVerbNotEnabledForAsset\\w*\\s*\\(' AssetHandlerTest.java` returns ≥1 match"
  - "AssetHandlerTest has a @Test method whose name contains `unsupportedQuoteCurrency` (case-insensitive) that asserts `/zcash --vs jpy` (when `jpy` is not in zcash's `supported_vs` allowlist) returns the friendly fuzzy-suggestion error matching the design §10.8 shape: 'Quote currency jpy is not enabled for /zcash. Did you mean: czk? Available: usd, eur, czk, btc.' The reply body matches the `error.asset.unsupported_quote_currency` bundle key. Verify: `grep -iE 'void\\s+\\w*unsupportedQuoteCurrency\\w*\\s*\\(' AssetHandlerTest.java` returns ≥1 match"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java is extended so that `/help` lists only enabled assets per spec §Asset commands — `/help` is context-aware: 'Only operator-enabled assets appear in `/help`; only enabled sub-verbs appear in per-command help.' The extension reads from `AssetRegistry` (NOT directly from `asset_config` — keeps the read centralized). When no assets are configured (absent `bootstrap-assets.json` case), `/help` does not list any. Verify: `grep -E 'AssetRegistry|assetRegistry' HelpCommandHandler.java` returns ≥1 match"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java gains constants for every new bundle key: `REPLY_ASSET_HEADER`, `REPLY_ASSET_PRICE_LINE`, `REPLY_ASSET_DELTA_1H`, `REPLY_ASSET_DELTA_24H`, `REPLY_ASSET_SPREAD`, `REPLY_ASSET_CAPTURE_LINE`, `REPLY_ASSET_STALE_MARKER`, `ERROR_ASSET_NOT_CONFIGURED`, `ERROR_ASSET_DEFAULT_DISABLED`, `ERROR_ASSET_UNKNOWN_SUB_VERB`, `ERROR_ASSET_SUB_VERB_NOT_ENABLED`, `ERROR_ASSET_UNSUPPORTED_QUOTE_CURRENCY`. Concrete constant names may differ from this list (author's call on naming consistency with M1-035c / M1-036 / M1-044c precedent) but the count and surface MUST cover the rendering + friendly-error surface. Verify: `grep -E 'public\\s+static\\s+final\\s+String\\s+(REPLY_ASSET_|ERROR_ASSET_)' BundleKeys.java` returns ≥12 matches"
  - "infochat-provider/src/main/resources/bundles/en.properties gains one entry per BundleKeys.java constant added. The text values are NOT empty strings; each carries the user-facing reply or friendly-error wording per design §10.5 / §10.8. The BundleLoaderTest reflection check (M1-035c precedent) MUST stay green — every constant on BundleKeys.java has a corresponding entry in en.properties. Verify: `grep -cE '^reply\\.asset\\.|^error\\.asset\\.' en.properties` returns ≥12"
  - "AssetCommandFamilyOracleTest is plain JUnit per the M1-049 test pyramid (no `@QuarkusTest`); it exercises the swapped `isAssetCommand` method against a hand-constructed AssetRegistry (or DataSource) seeded with known enabled / disabled / unknown assets. The test exercises both signature-stability (same method shape as before the swap) and behavior (registry-driven verdicts). Verify: AssetCommandFamilyOracleTest does NOT carry `@QuarkusTest` — `grep -E '@QuarkusTest' AssetCommandFamilyOracleTest.java` returns ZERO matches"
  - "CommandPermissions.java is NOT modified by this ticket — the M1-045 consumer of `AssetCommandFamilyOracle.isAssetCommand(slashCommand)` continues to compile and pass its existing tests (CommandPermissionsTest). After this ticket's swap a probation user invoking `/zcash` is allowed because `commandPermissions.isAllowedDuringProbation(\"/zcash <sub-verb>\")` now returns true (the asset-command family carve-out fires via the swapped oracle). Verify: `git diff main -- infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java` shows ZERO changes (the verification is reviewer-side; the acceptance pins the spec-load-bearing invariant by reference to the FROZEN file)"
  - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerIT.java exists as a Provider-internal IT (separate from the umbrella's cross-Collector IT). It is `@QuarkusTest`, `*IT`-suffixed for failsafe, and exercises the bare `/zcash` → seeded `price_snapshot` row → rendered reply path via the in-memory adapter (test-time deployment shape per `docs/spec/deployment.md` §Deployment scenarios; no SimpleX or Signal in IT). The IT seeds the `price_snapshot` row directly via JDBC (no fetcher tick — that's the umbrella's IT). Verify: `grep -E '@QuarkusTest' AssetHandlerIT.java` returns ≥1 match AND the file name ends in `IT.java` so the failsafe plugin picks it up"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-045's CommandPermissionsTest (asserting probation behavior), M1-035c's HelpCommandHandlerTest (whose assertion shape extends but does not regress), M1-035c's BundleLoaderTest (reflection check passes with the new constants), every M1-055a + M1-055b test, every M1-008..M1-054 test currently green on main"
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetRegistry.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandFamilyOracleTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerIT.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
  preserves:
    - all tests currently green on main
    - every test added by M1-055a and M1-055b
    - M1-045's CommandPermissionsTest (probation interaction with the swapped oracle)
    - M1-035c's HelpCommandHandlerTest, BundleLoaderTest, AutoRegisterServiceTest
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/schema.md §Operational
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Slow-start tier
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/deployment.md §Deployment scenarios
decision_refs:
  - D30
  - D34
  - D39
  - D45
---

# M1-055c: /zcash + /monero handlers + reply renderer + AssetCommandFamilyOracle impl swap + /help context-awareness

## Context

This subticket lands the **Provider-side command surface**
half of the T2-H asset-commands vertical (M1-055 umbrella):

1. `AssetRegistry` is a `@ApplicationScoped` CDI bean that
   reads `asset_config` (Provider `SELECT`-only per V14
   GRANTs) at @Startup and caches the per-asset surface the
   handlers + oracle + /help integration consult: enabled
   sub-verbs, default sub-verb, supported quote currencies,
   per-source attribution URL prefix, refresh interval.
2. `AssetCommandRouter` + per-asset `CommandHandler` beans
   (`name() == "zcash"` and `name() == "monero"`) register
   into InboundRouter's `Instance<CommandHandler>` iteration
   without any router edit (verified at brief-authoring time
   that InboundRouter.handleSlash iterates and matches
   exactly).
3. `AssetHandler` (shared base or sole dispatcher) parses
   `[sub-verb] [--vs <currency>]`, validates against
   `AssetRegistry`, looks up the latest snapshot via
   `AssetSnapshotReader`, and hands the result to
   `AssetReplyRenderer`. The handler path makes ZERO LLM
   calls and reads ONLY `asset_config` + `price_snapshot`
   (no `posts`, no `quarantine`, no `audit_log`).
4. `AssetSnapshotReader` is a `@ApplicationScoped` bean that
   reads the latest `price_snapshot` row for `(asset,
   sub_verb, vs_currency)` via the V15 lookup index. It
   also reads the per-host refresh interval (from
   `@ConfigProperty`, the same property M1-055b's fetcher
   reads) to compute the stale-marker threshold
   `2 * refresh_interval` per design §10.4.
5. `AssetReplyRenderer` produces plain-text reply bodies per
   design §10.5: header + price line + optional delta lines
   + 24h spread + capture/cache line + bare attribution URL
   on its own line. The renderer DOES NOT branch on
   `supportsCodeFormatting` — plain text + bare URLs per
   D30 is the universal layout. The renderer omits absent
   snapshot fields silently — never invents zeros.
6. `AssetCommandFamilyOracle` impl swap: the M1-045 seam's
   `false`-returning body is replaced with a registry
   lookup (in-memory cache populated by `AssetRegistry` —
   author's choice on cache vs. per-call SELECT, but the
   acceptance pins the registry-driven verdict). The
   **interface is held stable** — every consumer
   (CommandPermissions) continues to call the same method
   against the injected bean without modification.
7. `HelpCommandHandler` extension: only enabled assets and
   only enabled sub-verbs appear in `/help` output per
   spec §Asset commands — `/help` is context-aware. When
   no assets are configured (absent `bootstrap-assets.json`
   case), `/help` does not list any. The extension reads
   from `AssetRegistry`, not directly from `asset_config`
   — single read path.
8. Bundle keys for the asset reply layout (`reply.asset.*`)
   and friendly errors (`error.asset.*`) land in
   `BundleKeys.java` + `en.properties`. The
   `BundleLoaderTest` reflection check (M1-035c precedent)
   verifies every constant has a matching properties
   entry.

`security_relevant: true` because (a) the bare-invocation /
default-row resolution is spec-load-bearing — silent
fallback would mask operator misconfiguration; and (b) the
AssetCommandFamilyOracle swap is a slow-start probation
permission commitment — a regression would either widen the
allowlist (probation users running commands they should
not) or narrow it (probation users blocked from operator-
configured asset commands). The probation interaction is
tested in M1-045's existing CommandPermissionsTest, which
continues to pass after the oracle's body changes — the
test asserts the spec contract at the
`CommandPermissions.isAllowedDuringProbation(...)` boundary,
which is unaffected by the oracle's body changing from
`return false` to `return registry.contains(...)`.
`migration_touch: false` — V14 and V15 are M1-055a's and
M1-055b's commits.

## Definition of Done

- The five new classes under
  `infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/`
  exist with the shape described above.
- `AssetCommandFamilyOracle.java` body is swapped from
  `return false` to a registry-driven verdict; the
  interface is unchanged.
- `HelpCommandHandler.java` is extended to list only
  enabled assets and sub-verbs from the registry.
- `BundleKeys.java` and `en.properties` gain ≥12 new
  entries for the asset reply layout + friendly errors.
- Three test classes (`AssetHandlerTest`,
  `AssetReplyRendererTest`,
  `AssetCommandFamilyOracleTest`) cover the happy path,
  asymmetric-field rendering, stale marker, bare
  invocation (with-default / absent-default / disabled-
  default), unknown sub-verb, sub-verb not enabled for
  asset, unsupported quote currency, no-LLM-call invariant,
  and the oracle's registry-driven verdict shape.
- `mvn -B clean verify` exits 0.

## Notes

- **Two handlers, not one router-bean.** InboundRouter
  matches on exact `handler.name()` per line 562 (verified
  at brief-authoring time). A single dispatcher bean would
  need a sentinel `name()` and a hidden dispatch step —
  rejected. The two beans land as `@ApplicationScoped` with
  `name() == "zcash"` and `name() == "monero"`, each
  delegating to the shared `AssetHandler` base.
- **AssetCommandFamilyOracle impl swap is one constructor
  injection + one method body change.** The new body reads
  `assetRegistry.containsEnabledAsset(slashCommand)` (or
  equivalent). The interface signature MUST stay
  identical; M1-045's CommandPermissions is held FROZEN by
  the acceptance.
- **No InboundRouter edits.** Handlers register as new
  CommandHandler beans and are picked up by
  Instance<CommandHandler> iteration at handleSlash lines
  559-568 (verified at brief-authoring time). The
  reviewer's NEGATIVE-SPACE-CHECK will note
  `InboundRouter.java` is NOT in this ticket's
  files_scope by design — confirming intentional.
- **No ConfirmStateService edits.** Asset commands are
  non-destructive per spec; no confirm gate applies.
- **The renderer does not consult capability flags.** Per
  design §10.5 the layout is plain text with bare URLs
  per D30. A future session may be tempted to add a richer
  Markdown branch for adapters where
  `supportsCodeFormatting = true`; the acceptance pins
  ZERO matches for `supportsCodeFormatting` /
  `supportsMarkdownLinks` in the renderer so the
  temptation is mechanically blocked.
- **Stale-marker computation.** The reader passes the
  snapshot's `captured_at` to the renderer along with the
  per-host `refresh_interval`; the renderer computes
  `now - captured_at > 2 * refresh_interval` and prepends
  ` ⚠ stale` to the header line per design §10.4. The
  `refresh_interval` lookup reads the same
  `@ConfigProperty` keys M1-055b's fetcher writes
  (`infochat.assets.refresh.<host>`).
- **Audit-row policy.** Asset reads + handler dispatches
  are NOT audit-logged per spec — the audit-log table is
  for privileged user actions and operator boot events,
  not for read-mostly bulk-derived rows. No new
  AuditAction entry.

## Big-picture notes

- **The oracle swap unblocks the M1-045 probation
  interaction.** Before this ticket, a probation user
  typing `/zcash` is blocked because
  `AssetCommandFamilyOracle.isAssetCommand("zcash")`
  returns false. After this ticket, the oracle reads the
  registry and returns true for any enabled asset; the
  CommandPermissions' probation carve-out fires;
  M1-045's existing CommandPermissionsTest continues to
  pass; the umbrella's IT pins the end-to-end
  interaction.
- **After this ticket merges + M1-055a + M1-055b merge,
  the umbrella becomes Runnable.** The umbrella's IT
  exercises the full vertical with a fake AssetDataSource
  bean; the three subtickets land first.
- **Parallel-development collision with T2-B (M1-052 /
  M1-053 / M1-054).** BundleKeys.java + en.properties
  are shared seams. M1-052 / M1-053 / M1-054 each append
  their own bundle keys; this ticket also appends. The
  second-merging ticket rebases en.properties and
  BundleKeys.java (append-only). The BundleLoaderTest
  reflection check (M1-035c) enforces alignment.

## Out-of-scope expansion

- **Fetcher impl, AssetDataSource SPI, PriceSnapshotStore,
  V15 migration.** M1-055b territory.
- **Bootstrap parser, V14 migration, default-row check.**
  M1-055a territory.
- **`/asset-enable` / `/asset-disable` / `/list-assets`
  admin commands.** v1 ships operator-side enable/disable
  only per design §10.8b.
- **Auth-gated exchanges, websocket "live" mode, on-chain
  verbs, historical queries, alerts/thresholds.** v2 per
  design §10.9.
- **TranslationProvider integration.** T2-C; asset
  replies ship English-only.
- **Markdown / rich rendering for asset replies.** Plain
  text + bare URLs is the universal layout per D30 + spec
  §Capability flags `supportsMarkdownLinks=false` for
  every v1 adapter.

## Authorized test changes

- (none — this ticket adds new tests and modifies no
  pre-existing tests.)

## Alternatives considered

- **Put the AssetCommandFamilyOracle impl swap in the
  umbrella ticket.** Rejected — the swap is one method
  body change + one constructor field, and it lives
  cohesively next to AssetRegistry + the handlers in
  this ticket. Putting it in the umbrella would force the
  umbrella to depend on a sibling-ticket file edit that
  is logically Provider-side. The brief's umbrella
  description (line 195) mentions "the
  AssetCommandFamilyOracle impl swap" but the brief's
  own per-ticket framing for T2-H.c (line 271, line 546)
  places it here. Push-back per CLAUDE.md §Push back
  when simpler exists: putting it here is simpler. The
  umbrella's IT exercises the swapped behavior because
  this ticket lands FIRST per the umbrella+subs
  convention.
- **Single dispatcher bean keyed by asset name.**
  Rejected — InboundRouter matches on exact
  `handler.name()` at line 562; a dispatcher's
  `name()` would need a sentinel and the dispatch step
  would be hidden behind that sentinel. Two beans is
  the cleaner shape and matches M1-035c / M1-036 /
  M1-037 / M1-044c per-command precedent.
- **Read `asset_config` directly in the handlers instead
  of going through AssetRegistry.** Rejected — multiple
  read paths into `asset_config` (handlers + oracle +
  /help) would each duplicate the SELECT and the
  enabled-set logic. A single AssetRegistry centralizes
  the read; the per-call cost is a hash lookup in the
  cached set.
- **Compute the stale-marker threshold per (asset,
  sub_verb) instead of per host.** Rejected — spec
  §Asset commands and design §10.4 commit to per-host
  intervals; the threshold is `2 * refresh_interval` for
  the host. Per-pair thresholds would conflict with the
  per-host scheduling M1-055b implements.
- **Branch the reply renderer on
  `supportsCodeFormatting` to render Markdown when the
  adapter supports it.** Rejected — D30 commits to bare
  URLs and plain text universally. Spec §Capability
  flags pins `supportsMarkdownLinks = false` for every
  v1 adapter at registration. Widening the render
  surface is a spec amendment, not a config choice.
