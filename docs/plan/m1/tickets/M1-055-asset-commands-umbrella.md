---
id: M1-055
title: Asset commands umbrella — /zcash + /monero + bootstrap-assets + asset_config + price_snapshot roundtrip IT
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by:
  - M1-055a
  - M1-055b
  - M1-055c
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandsRoundtripIT.java
  - infochat-provider/src/test/resources/bootstrap-assets-it.json
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §Asset commands + §Operational asset_config + §Operational price_snapshot are complete on main HEAD; this umbrella is test-only
  - any change to M1-055a's bootstrap parser, BootstrapAssetsEntry record, V<N>__asset_config.sql migration, default-row consistency check, or Collector @Startup loader — that commit is FROZEN at its review round
  - any change to M1-055b's AssetDataSource SPI, per-host AssetDataSource impls, AssetSnapshotFetcher, PriceSnapshotStore, V<N+1>__price_snapshot.sql migration, per-host tick cadence, NOTIFY emission, or per-source consecutive-failure counter logic — that commit is FROZEN at its review round
  - any change to M1-055c's AssetCommandRouter, AssetHandler, AssetReplyRenderer, AssetSnapshotReader, AssetCommandFamilyOracle impl swap, /help context-awareness extension, or bundle key additions — that commit is FROZEN at its review round
  - any change under infochat-core/src/main/resources/db/migration/ — both migrations are M1-055a's and M1-055b's commits; this umbrella adds no schema change
  - any new bundle key in BundleKeys.java or bundles/en.properties — M1-055c authors the asset reply layout and friendly-error keys; the IT consumes them via BundleLoader
  - any modification to AssetCommandFamilyOracle's public interface — the M1-045 seam's signature is held stable; M1-055c only replaces the method body
  - any change to CommandPermissions — M1-045's consumer of AssetCommandFamilyOracle.isAssetCommand is unaffected by the impl swap
  - any change to InboundRouter — handlers register as new CommandHandler beans and are picked up by Instance<CommandHandler> iteration
  - any change to ConfirmStateService — asset commands are non-destructive; no confirm gate applies
  - any change to TranslationProvider integration — asset replies ship English-only via the bundle keys M1-055c authored
  - any /summary / /save / /saved / /quarantine integration — spec §Asset commands explicitly excludes asset snapshots from those surfaces
  - any v2 surface — websocket "live" mode, on-chain verbs, historical queries, auth-gated exchanges, alerts/thresholds (all listed in docs/design/10-asset-commands.md §10.9)
  - any new asset beyond /zcash + /monero — v1 ships exactly those two per design §10.1; adding /bitcoin or similar is a spec-amend plus a bootstrap-assets.json entry, not a code change
  - any modification to any pre-existing test in infochat-provider/src/test/, infochat-collector/src/test/, infochat-messaging-adapter/src/test/, or infochat-core/src/test/ — every prior test continues to pass unchanged
acceptance:
  - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandsRoundtripIT.java exists, ends with the `*IT` suffix so maven-failsafe-plugin runs it under `mvn verify` (the M1-008a-authored failsafe wiring already includes the provider module pattern), and contains at least one `@Test` annotation. Verify: `grep -E '@Test' AssetCommandsRoundtripIT.java` returns ≥1 match"
  - "The IT is a `@QuarkusTest` (NOT plain JUnit — it needs the full CDI graph: AdapterRegistry + InboundRouter + AssetCommandRouter + AssetHandler + AssetReplyRenderer + AssetSnapshotReader + AssetCommandFamilyOracle + BootstrapAssetsParser + PriceSnapshotStore + a fake AssetDataSource bean replacing the per-host production impls + BundleLoader + the InMemoryAdapter bean). Verify: `grep -E '@QuarkusTest' AssetCommandsRoundtripIT.java` returns ≥1 match"
  - "The IT activates an inline `@TestProfile(...)` whose `getConfigOverrides()` sets `infochat.adapters=inmemory`, `infochat.adapters.inmemory.allow-low-trust=true`, and `infochat.bootstrap.assets-file=<path-to-infochat-provider/src/test/resources/bootstrap-assets-it.json>` so the Collector @Startup loader picks up the test bootstrap. Verify: `grep -E 'infochat\\.bootstrap\\.assets-file|allow-low-trust' AssetCommandsRoundtripIT.java` returns ≥2 matches"
  - "infochat-provider/src/test/resources/bootstrap-assets-it.json exists and matches the schema in docs/design/10-asset-commands.md §10.6 with the two v1 assets (zcash + monero) and at least one sub-verb each. Verify: `grep -E '\"id\"\\s*:\\s*\"zcash\"' bootstrap-assets-it.json` returns ≥1 match AND `grep -E '\"id\"\\s*:\\s*\"monero\"' bootstrap-assets-it.json` returns ≥1 match"
  - "Step (a) — bootstrap-load → asset_config row write: after Quarkus startup the IT asserts `SELECT COUNT(*) FROM asset_config WHERE asset = 'zcash' AND enabled = true` returns ≥1 AND `SELECT COUNT(*) FROM asset_config WHERE asset = 'monero' AND enabled = true` returns ≥1. The asserts pin M1-055a's Collector @Startup loader populating the table from the test bootstrap file"
  - "Step (b) — fetcher tick → price_snapshot row INSERT → NOTIFY emit: the IT installs a fake `AssetDataSource` bean (CDI `@Alternative @Priority(1)` or an `@IfBuildProfile(\"test\")` impl that wins over the real per-host impls) returning a deterministic `PriceSnapshot` for the seeded `(asset, sub_verb)` pair, then triggers one fetcher tick (either by waiting for the scheduled tick or by invoking the M1-055b-exposed test seam — author's call). After the tick: `SELECT COUNT(*) FROM price_snapshot WHERE asset = 'zcash'` returns ≥1; a Provider-side listener on the `new_price_snapshot` LISTEN channel (assertion shape mirrors the M1-028 ListenNotifyIT pattern) received a payload containing `'zcash'` AND the source name; the latest snapshot row's `price` matches the fake's emitted value. The IT asserts all three"
  - "Step (c) — Provider `/zcash <sub-verb>` reply contains the attribution URL bare per D30: `adapter.deliverDm(\"u-1\", \"/zcash <sub-verb>\")` (concrete sub-verb is author's call; pin one configured in the IT's bootstrap-assets-it.json) produces exactly ONE outbound message whose body contains (1) the asset display name + sub-verb header per the design §10.5 layout, (2) the attribution URL as a bare token (no markdown link syntax — verify the body does NOT match `[.*\\]\\(http`), (3) the capture timestamp from the snapshot row, and (4) a cache-age line. The IT asserts all four. Seed `u-1` as a fully-registered non-probation user via direct JDBC INSERT into `users`"
  - "Step (d) — AssetCommandFamilyOracle.isAssetCommand reflects loaded registry: after bootstrap-load the IT asserts `assetCommandFamilyOracle.isAssetCommand(\"zcash\")` returns true, `assetCommandFamilyOracle.isAssetCommand(\"monero\")` returns true, and `assetCommandFamilyOracle.isAssetCommand(\"bitcoin\")` returns false (unknown asset). Inject the bean via `@Inject AssetCommandFamilyOracle oracle` on the IT class"
  - "Step (e) — probation user can invoke an asset command (M1-045 interaction): the IT seeds a second user `u-2` with `registration_state='invited'` AND `probation_until = NOW() + INTERVAL '1 hour'` (probation in effect). `adapter.deliverDm(\"u-2\", \"/zcash <sub-verb>\")` produces ONE outbound message whose body matches the same asset-reply shape from step (c) — NOT the probation-blocked friendly error. The assertion pins spec §Slow-start tier's asset-command carve-out interacting correctly with M1-055c's oracle impl swap"
  - "Step (f) — banned user hits the ban check before any asset command dispatches (per design §10.11 verification checklist): the IT seeds a third user `u-3` with `is_banned = true`. `adapter.deliverDm(\"u-3\", \"/zcash <sub-verb>\")` produces ONE outbound message whose body equals the `error.ban.fixed` bundle value; `SELECT COUNT(*)` against any new price_snapshot read in audit/diagnostic logs is zero — verified by the absence of a row in `audit_log` with `action='SLASH_DISPATCH'` AND `actor_contact_id='u-3'` since the ban check short-circuits before the dispatch step writes its audit row. The IT asserts both"
  - "Step (g) — no LLM call on any asset-command path: the IT injects a fail-loud LLM SPI mock (throws on any method call). The asset-command paths exercised in steps (c), (e), and (f) complete without the mock throwing. The mock's invocation counter is zero at the end of the test"
  - "mvn -B clean verify from the repo root exits 0; AssetCommandsRoundtripIT runs under failsafe; failsafe reports record at least one test executed AND no failures. Verify: `grep -rE 'AssetCommandsRoundtripIT' infochat-provider/target/failsafe-reports` returns at least one match AND `grep -rE '<testsuite[^>]*failures=\"0\"' infochat-provider/target/failsafe-reports` returns at least one match for AssetCommandsRoundtripIT"
  - "Every prior test continues to pass: M1-008..M1-051 + M1-052..M1-054 (T2-B siblings) + every M1-055a / M1-055b / M1-055c subticket test"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandsRoundtripIT.java
    - infochat-provider/src/test/resources/bootstrap-assets-it.json
  preserves:
    - every test currently green on main
    - every test added by M1-055a, M1-055b, and M1-055c
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/schema.md §Operational
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Slow-start tier
  - docs/spec/messaging.md §Capability flags (minimum set)
decision_refs:
  - D30
  - D33
  - D34
  - D39
  - D42
  - D45
  - D46
---

# M1-055: Asset commands umbrella — /zcash + /monero + bootstrap-assets + asset_config + price_snapshot roundtrip IT

## Context

Umbrella commit for the M1-055 group (per
`docs/process/workflow.md` §Ticket-ID placeholder convention —
the umbrella + subticket idiom). M1-055a, M1-055b, and M1-055c
each ship a slice of the T2-H asset-commands vertical as its
own reviewable commit on `main`:

- **M1-055a** — `bootstrap-assets.json` parser +
  `BootstrapAssetsEntry` record + the `asset_config` Flyway
  migration (next-free `V<N>__asset_config.sql`) + the
  default-row consistency check (`is_default = true AND
  enabled = false` rejected at Collector startup with a fatal
  log message) + the soft-disable behavior (an entry present
  in a prior bootstrap and absent from the latest bootstrap is
  set to `enabled = false`, never hard-deleted) + the
  Collector-side `@Startup` loader that upserts rows from the
  JSON file into `asset_config`.
- **M1-055b** — `AssetDataSource` SPI under
  `infochat-collector/.../assets/source/` + one impl per
  supported public-endpoint host (per design §10.2 — count is
  the implementing author's call based on the v1 sub-verb set:
  CoinGecko, Kraken, Bitfinex) + `AssetSnapshotFetcher`
  (polled `Fetcher` per the M1-007a SPI) + `PriceSnapshotStore`
  (writes directly to `price_snapshot` and emits
  `NOTIFY new_price_snapshot` with `(asset, source)` payload)
  + the next-free `V<N+1>__price_snapshot.sql` Flyway migration
  + per-host tick cadence (one interval per supported source
  host, NOT per-`(asset, sub_verb)`, profile-driven values per
  design §10.4) + per-source consecutive-failure counter logic
  driving `asset_config.status = 'failed'` on threshold breach
  (D42's HTTP-shaped failure-counter model).
- **M1-055c** — Provider-side asset command surface:
  `AssetCommandRouter` + handler beans for `/zcash` and
  `/monero` (each implements `CommandHandler` so InboundRouter
  picks them up via `Instance<CommandHandler>` iteration —
  no router edit) + `AssetReplyRenderer` (plain text, bare URL
  per D30, asymmetric-field rendering per design §10.5) +
  `AssetSnapshotReader` (single SQL read from `price_snapshot`
  + an `asset_config` lookup for sub-verb validity and
  freshness window) + the `AssetCommandFamilyOracle` impl
  swap (replaces the M1-045 seam's `false`-returning body with
  a registry lookup; the interface is held stable so
  `CommandPermissions` is untouched) + the Provider-side
  `@Startup` registry-populator bean that reads `asset_config`
  (SELECT-only) + `/help` context-awareness extension (only
  operator-enabled assets and sub-verbs appear) + the bundle
  keys for the asset reply layout and friendly errors
  (`reply.asset.header`, `reply.asset.price_line`, etc., under
  `infochat-provider/src/main/resources/bundles/en.properties`
  + matching constants on `BundleKeys.java`).

Each subticket's per-class tests verify its own slice. This
umbrella verifies the **cross-cutting** property the subtickets
cannot verify in isolation: **the full asset-commands vertical
delivers a `/zcash` reply with capture timestamp + cache age +
bare attribution URL after a bootstrap-load → fetcher-tick →
INSERT → NOTIFY roundtrip, and the M1-045 probation interaction
holds end-to-end through the InMemoryAdapter**.

The whole-topic verification is meaningfully different from any
single subticket's unit-level assertions:

- M1-055a's per-class tests assert isolated bootstrap-parser
  shape, default-row consistency check verdicts, soft-disable
  semantics on a Testcontainers Postgres — but no fetcher
  runs, no snapshot row is INSERTed, no Provider command
  dispatches.
- M1-055b's per-class tests assert isolated fetcher behavior
  against fake `AssetDataSource` impls, PriceSnapshotStore
  INSERT + NOTIFY emission, per-host tick cadence scheduling
  — but no Provider command-side resolution runs.
- M1-055c's per-class tests assert isolated handler parsing,
  reply renderer output shape, oracle registry lookup — but
  the snapshot row under test is hand-seeded via JDBC, not
  produced by an actual fetcher tick.

None of those asserts the full vertical — bootstrap-load
through fetcher tick through Provider reply with attribution —
against the real CDI graph via the real adapter. The IT walks
every link and asserts the user-observable spec contract.
Shipping the cross-class assertion as its own reviewable unit
is exactly the umbrella + subticket idiom's reason to exist.

`security_relevant: true` — the IT pins spec commitments from
§Asset commands ("Mandatory attribution", "Stale-data honesty",
"Provider/Collector contract" — Provider `SELECT`-only on
`price_snapshot` and `asset_config`), §DB roles (least-privilege
DB-role split), and §Slow-start tier (asset-command carve-out
during probation). A regression in any step would be a
security defect — a missing attribution URL violates a ToS
commitment, a probation user blocked from `/zcash` would
silently widen the slow-start blocklist, a Provider write to
`price_snapshot` would violate the DB-role split. The IT is
the milestone-boundary attestation that the seam holds.

## Definition of Done

- A single `@QuarkusTest` `*IT`-named class lives at
  `infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandsRoundtripIT.java`.
- The `*IT` suffix matches maven-failsafe-plugin's convention;
  the failsafe wiring authored by M1-008a runs the IT under
  `mvn verify` from the repo root.
- A test bootstrap fixture
  `infochat-provider/src/test/resources/bootstrap-assets-it.json`
  carries `/zcash` + `/monero` with at least one configured
  sub-verb each (concrete sub-verb names are author's call;
  must align with the IT's fake `AssetDataSource` bean).
- The IT activates an inline test profile pointing
  `infochat.bootstrap.assets-file` at the fixture and enabling
  the InMemoryAdapter low-trust opt-in.
- One or more `@Test` methods drive the seven-step roundtrip
  (steps (a) through (g) per the acceptance items above).
- `mvn -B clean verify` exits 0; every prior test continues to
  pass.

## Notes

- **`@QuarkusTest`, not plain JUnit.** This IT needs the full
  CDI graph the subtickets' classes wire into. Plain JUnit
  would defeat the IT's purpose.
- **The IT installs a fake `AssetDataSource` bean.** The real
  per-host impls (CoinGecko, Kraken, Bitfinex) make outbound
  HTTP calls; the IT must not. A CDI `@Alternative @Priority`
  or `@IfBuildProfile("test")` bean wins selection and emits
  deterministic snapshots. The implementing author chooses the
  exact mechanism so long as the fake (a) is selected over
  the real impls under the test profile, (b) supports at least
  one configured sub-verb from the fixture, and (c) emits a
  snapshot whose attribution URL is observable in the reply.
- **The IT seeds users via raw JDBC at setup**, not via the
  `@Startup` bootstrap-admin bean or M1-044's invite-consume
  path. A direct INSERT under the `infochat_provider` GRANT
  (`INSERT ON users`) is the simplest seam; the three test
  users (`u-1` full-access, `u-2` probation, `u-3` banned)
  are each one row.
- **Test-fixture reset.** The InMemoryAdapter's `sentMessages()`
  queue accumulates across `@Test` methods; call
  `adapter.reset()` in `@BeforeEach` plus a per-test truncate
  of `users`, `asset_config`, `price_snapshot`, and `audit_log`
  (or a transactional rollback wrapper). The Collector @Startup
  loader re-populates `asset_config` from the fixture on
  startup, so a truncate between tests followed by the loader
  re-running (or a per-test re-invocation of the loader's
  public method) restores the seeded rows.
- **NOTIFY listener shape.** The Provider-side listener
  assertion in step (b) mirrors the M1-028 ListenNotifyIT
  pattern: the IT subscribes to `new_price_snapshot` via a
  raw `PGConnection.getNotifications(...)` call on a separate
  DataSource (the production Provider role's LISTEN/NOTIFY
  grant is exercised by M1-009's heartbeat — verified in
  `docs/spec/security.md` §DB roles) and asserts the payload
  string contains the expected `(asset, source)` shape.
- **Stale-marker behavior is M1-055c's scope, not the
  umbrella's.** The IT's snapshot row is fresh (cache age <<
  `2 * refresh_interval`), so no `⚠ stale` marker appears.
  The stale-marker case is tested in M1-055c's
  AssetReplyRendererTest (per that ticket's acceptance shape).
- **Friendly errors are M1-055c's scope, not the umbrella's.**
  The IT exercises the happy path + the probation interaction
  + the ban interaction. Unknown sub-verb, sub-verb not
  enabled for this asset, and unsupported `--vs` errors are
  tested in M1-055c's handler tests.
- **Bundle key resolution.** The IT reads expected reply text
  via `BundleLoader.get(BundleKeys.REPLY_ASSET_HEADER, ...)`
  rather than baking literals into the test (the M1-035
  precedent). The constants are added in M1-055c.

## Big-picture notes

- **The subticket commits are FROZEN at the umbrella round.**
  M1-055a, M1-055b, and M1-055c each land as their own
  reviewable commit on `main` before this umbrella becomes
  runnable. If this IT exposes a defect in one of the
  subticket outputs, the fix is a NEW ticket against the
  affected module — never an amendment to the subticket
  commit. The "never amend a passed commit" invariant in
  `CLAUDE.md` §M1 workflow applies verbatim.
- **The umbrella unblocks nothing in M1.** T2-H is the last
  Tier 2 group; the next milestone moves to Tier 3 (adapters
  and breadth: SimpleX, Signal, polled fetchers for non-asset
  sources, Nostr StreamSource, Anthropic LLM).
- **Parallel-development collision with T2-B.** M1-052 also
  claims a next-free `V<N>__*.sql` migration (currently
  `V14__saved_post.sql` per that ticket's frontmatter).
  Whichever ticket merges first claims its V<N>; the second
  rebases its migration filename(s) and any V<N> references
  in tests. M1-055a + M1-055b each carry a migration; both
  use the same race-resolution language (re-run
  `ls infochat-core/src/main/resources/db/migration/ | sort
  -V | tail` at `/m1-tick start`). This umbrella adds no
  migration so it is unaffected.

## Out-of-scope expansion

- **Changes to any subticket file.** The three subticket
  commits are frozen.
- **Changes under `infochat-core/src/main/resources/db/migration/`.**
  M1-055a's and M1-055b's migrations are the only schema
  changes in this group; this umbrella adds no migration.
- **Changes to any pre-existing test.** Modifying any of them
  would be a test-integrity violation per
  `engineering-rules-verbatim.md` §8.
- **Stale-marker / friendly-error / unknown-sub-verb /
  unsupported-`--vs` paths.** All M1-055c territory; the
  umbrella's IT exercises the happy path + probation + ban
  interactions only.
- **Auth-gated exchanges + websocket "live" mode.** v2.
- **Bare `/zcash` (default-sub-verb resolution).** M1-055c
  territory; the umbrella's IT exercises an explicit
  sub-verb invocation only.
- **TranslationProvider exercise.** T2-C; the IT asserts
  English bundle entries.
- **`/save` / `/saved` / `/summary` against an asset
  snapshot.** Spec §Asset commands explicitly excludes
  snapshots from those surfaces — the IT does not exercise
  them.

## Authorized test changes

- (none — this umbrella adds one new test class and one new
  test-resources fixture in `infochat-provider`, and modifies
  no pre-existing tests.)

## Alternatives considered

- **Make the IT a plain `@JUnitTest` and assemble the CDI
  graph by hand.** Rejected — same reasoning as M1-044's
  umbrella IT. Manual assembly would either duplicate
  production wiring (and rot when the subtickets evolve) or
  skip pieces (and not actually prove the roundtrip).
- **Inline the cross-cutting assertion into M1-055c's
  handler tests and skip the umbrella.** Rejected — M1-055c
  sees only its own slice (handlers + renderer + oracle).
  The full vertical from bootstrap-load through fetcher tick
  through Provider reply is exactly the property a
  per-handler test cannot prove without driving the
  Collector @Startup loader and a real fetcher tick.
- **Drop the umbrella; rely on each subticket's tests plus
  manual verification.** Rejected — milestone-boundary
  attestation belongs in CI, not in a manual checklist. The
  IT runs under `mvn verify` so a regression surfaces at PR
  time.
