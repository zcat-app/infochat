---
id: M1-055a
title: bootstrap-assets.json parser + asset_config table + default-row consistency check + Collector @Startup loader
status: done
created: 2026-05-24
last_updated: 2026-05-24
blocked_by: []
clarity_check:
  date: 2026-05-24
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-055a.md
reviews:
  - round: 1
    date: 2026-05-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 1247
      removed: 10
redteam_findings: []
redteam_audits:
  - date: 2026-05-24
    verdict: CLEAN
    base: ce2a5f8^
    head: ce2a5f8
    verdict_file: docs/plan/m1/redteam/M1-055a-2026-05-24.md
    out_of_model_count: 2
    note: |
      Operator-supplied JSON is trusted per spec; this diff lands only
      operator-trusted bootstrap input + a least-privilege DB schema
      (V14 grants Provider SELECT-only on asset_config; DELETE REVOKEd
      from all service roles). No auth/authz/ban/LLM-tool-call surface
      touched. Two OUT-OF-MODEL advisories on operator-trusted input
      (jsonEscape() control-char coverage; kraken attribution URL
      character-set validation) noted but require no remediation —
      they fall outside the documented threat surface. No follow-up
      ticket warranted.
files_budget: 7
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsEntry.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java
  - infochat-core/src/main/resources/db/migration/V14__asset_config.sql
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsParserTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoaderTest.java
  - infochat-collector/src/test/resources/bootstrap/bootstrap-assets-fixture.json
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - any change to the spec — §Asset commands + §Operational asset_config are complete on main HEAD; this ticket implements them
  - any fetcher impl — M1-055b territory (AssetDataSource SPI, per-host impls, AssetSnapshotFetcher, PriceSnapshotStore)
  - any Provider command handler — M1-055c territory
  - any AssetCommandFamilyOracle impl swap — M1-055c territory (the M1-045 seam stays returning false until M1-055c's commit lands)
  - any new bundle key — M1-055c authors the asset reply layout and friendly-error keys
  - any change to AuditAction.java — the `BOOTSTRAP_ASSET_LOAD` verb already exists in the enum at brief-authoring time (verified at infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java line 50); this ticket consumes it
  - any change to BootstrapSourcesParser.java or BootstrapSourcesEntry.java — the existing source bootstrap classes are consumed as precedent, not modified
  - any change to BootstrapLoader.java (the existing source bootstrap loader) — this ticket adds a sibling asset loader as its own class, not an extension of the sources loader
  - any test of the runtime default-but-disabled friendly-error fallback — that path is M1-055c's defense-in-depth (Provider-side); this ticket's default-row check is bootstrap-time only
  - any modification to the V5 migration's audit_log table, GRANTs, or per-table privilege block — V14 only creates `asset_config` plus its GRANTs
  - any test outside the two test files in files_scope — every pre-existing collector test continues to pass unchanged
acceptance:
  - "infochat-core/src/main/resources/db/migration/V14__asset_config.sql exists and applies cleanly on a fresh DB. (Migration filename MUST be the next-free `V<N>__asset_config.sql` integer at the moment of `/m1-tick start` — M1-052 may have consumed V14 before this ticket lands; re-run `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail` and rename the file plus all V14 references in tests/scripts if needed.) Verify: the migration file exists under `infochat-core/src/main/resources/db/migration/` matching glob `V*__asset_config.sql`"
  - "V14 creates the `asset_config` table with columns matching docs/spec/schema.md §Operational — Asset config: `(asset TEXT NOT NULL, sub_verb TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true, default_quote_currency TEXT NOT NULL, attribution_url TEXT NOT NULL, consecutive_failures INT NOT NULL DEFAULT 0, last_success_at TIMESTAMPTZ, last_failure_at TIMESTAMPTZ, is_default BOOLEAN NOT NULL DEFAULT false, status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'failed', 'disabled')), PRIMARY KEY (asset, sub_verb))`. Verify: `grep -E 'CREATE TABLE asset_config' V14__asset_config.sql` returns ≥1 match AND `grep -E 'PRIMARY KEY \\(asset, sub_verb\\)' V14__asset_config.sql` returns ≥1 match AND `grep -E 'CHECK \\(status IN' V14__asset_config.sql` returns ≥1 match"
  - "V14 creates a partial unique index enforcing at-most-one `is_default = true` row per `asset` (per spec §Operational — Asset config): `CREATE UNIQUE INDEX uq_asset_config_default ON asset_config (asset) WHERE is_default = true`. Verify: `grep -E 'CREATE UNIQUE INDEX uq_asset_config_default' V14__asset_config.sql` returns ≥1 match AND `grep -E 'WHERE is_default = true' V14__asset_config.sql` returns ≥1 match"
  - "V14 carries the V5-style per-role GRANT split (per spec §DB roles — Collector `INSERT/UPDATE/SELECT`, Provider `SELECT`-only): `GRANT SELECT, INSERT, UPDATE ON asset_config TO infochat_collector;` AND `GRANT SELECT ON asset_config TO infochat_provider;`. DELETE is intentionally NOT granted to either role (soft-disable is the lifecycle path; hard-delete is operator-side only). Verify: `grep -E 'GRANT\\s+SELECT,\\s+INSERT,\\s+UPDATE\\s+ON\\s+asset_config\\s+TO\\s+infochat_collector' V14__asset_config.sql` returns ≥1 match AND `grep -E 'GRANT\\s+SELECT\\s+ON\\s+asset_config\\s+TO\\s+infochat_provider' V14__asset_config.sql` returns ≥1 match AND `grep -E 'GRANT[^;]*DELETE[^;]*ON\\s+asset_config' V14__asset_config.sql` returns ZERO matches"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsEntry.java exists as a Java record carrying the per-asset fields from docs/design/10-asset-commands.md §10.6 (`id`, `display_name`, `ticker`, `default_sub_verb`, `sub_verbs` as a list of `(id, external_id)` pairs, `supported_vs` as a list of quote-currency strings). The record may declare a nested record for the per-sub-verb pair. Verify: `grep -E 'public\\s+record\\s+BootstrapAssetsEntry' BootstrapAssetsEntry.java` returns ≥1 match"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsParser.java exists and exposes a public method that parses an `InputStream` or `Path` into a list of `BootstrapAssetsEntry` records. Strict-by-default: Jackson `FAIL_ON_UNKNOWN_PROPERTIES = true` (mirroring BootstrapSourcesParser line 22). Verify: `grep -E 'FAIL_ON_UNKNOWN_PROPERTIES' BootstrapAssetsParser.java` returns ≥1 match AND `grep -E 'public\\s+(List|java\\.util\\.List)' BootstrapAssetsParser.java` returns ≥1 match"
  - "BootstrapAssetsParserTest has a @Test method whose name contains `parsesValidFixture` (case-insensitive) that asserts the parser deserializes `bootstrap-assets-fixture.json` (under test-resources) into the expected list of `BootstrapAssetsEntry` records — at least one entry for `zcash` and one for `monero`, each with their `default_sub_verb` and `sub_verbs` populated. Verify: `grep -iE 'void\\s+\\w*parsesValidFixture\\w*\\s*\\(' BootstrapAssetsParserTest.java` returns ≥1 match"
  - "BootstrapAssetsParserTest has a @Test method whose name contains `rejectsUnknownField` (case-insensitive) that asserts the parser throws (or returns an error result) when the JSON contains an unknown top-level field — pins the strict-by-default invariant. Verify: `grep -iE 'void\\s+\\w*rejectsUnknownField\\w*\\s*\\(' BootstrapAssetsParserTest.java` returns ≥1 match"
  - "BootstrapAssetsParserTest has a @Test method whose name contains `rejectsMissingDefaultSubVerb` (case-insensitive) that asserts the parser rejects an entry whose `default_sub_verb` value is absent from the entry's `sub_verbs` list (operator typo would silently break bare `/zcash` otherwise). Verify: `grep -iE 'void\\s+\\w*rejectsMissingDefaultSubVerb\\w*\\s*\\(' BootstrapAssetsParserTest.java` returns ≥1 match"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java exists, is `@ApplicationScoped`, and exposes a public method that upserts a list of `BootstrapAssetsEntry` records into `asset_config`. Idempotent on the `(asset, sub_verb)` PK: a re-run with the same entries does NOT create duplicate rows. Entries present in a prior load but absent from the latest load are set to `enabled = false` (soft-disable per spec §Asset commands — Enable/disable lifecycle). Verify: `grep -E '@ApplicationScoped' BootstrapAssetsLoader.java` returns ≥1 match AND `grep -E 'INSERT\\s+INTO\\s+asset_config|INTO\\s+asset_config' BootstrapAssetsLoader.java` returns ≥1 match AND `grep -E 'ON\\s+CONFLICT' BootstrapAssetsLoader.java` returns ≥1 match"
  - "BootstrapAssetsLoader has an @Observes StartupEvent handler (or @Startup-annotated bean method) that reads `infochat.bootstrap.assets-file` via @ConfigProperty (Optional<Path>), invokes the parser when the file is configured and present, and runs the loader. Absent file or absent path → the loader does NOT throw; it logs an INFO-level message and skips the load (asset commands disabled per spec §Asset commands — `/help` does not list them). Verify: `grep -E '@Observes\\s+StartupEvent|@Startup' BootstrapAssetsLoader.java` returns ≥1 match AND `grep -E 'infochat\\.bootstrap\\.assets-file' BootstrapAssetsLoader.java` returns ≥1 match"
  - "BootstrapAssetsLoader emits a `BOOTSTRAP_ASSET_LOAD` audit row (existing enum constant at AuditAction line 50) on every successful load, recording in the `details_json` the count of entries upserted, the count soft-disabled, and the bootstrap file path. Audit-before-effect per spec Invariant 7. Verify: `grep -E 'BOOTSTRAP_ASSET_LOAD' BootstrapAssetsLoader.java` returns ≥1 match"
  - "BootstrapAssetsLoader enforces the default-row consistency check per spec §Operational — Asset config: an entry with `default_sub_verb = X` whose corresponding `(asset, X)` row would carry `enabled = false` is rejected at Collector startup with a fatal log message naming the `(asset, sub_verb)` pair AND a thrown exception that aborts the @Startup chain. The check fires AT BOOTSTRAP TIME before any INSERT runs — the partial unique index on `is_default` cannot itself catch this (the index enforces at-most-one default, not default-implies-enabled). Verify: BootstrapAssetsLoaderTest has a @Test method whose name contains `rejectsDefaultButDisabled` (case-insensitive) AND `grep -iE 'void\\s+\\w*rejectsDefaultButDisabled\\w*\\s*\\(' BootstrapAssetsLoaderTest.java` returns ≥1 match"
  - "BootstrapAssetsLoaderTest has a @Test method whose name contains `freshInsert` (case-insensitive) that asserts the loader against a fresh DB (no prior `asset_config` rows) inserts one row per `(asset, sub_verb)` pair from the fixture, with `enabled = true`, `is_default` set per the entry's `default_sub_verb`, and `status = 'active'`. Verify: `grep -iE 'void\\s+\\w*freshInsert\\w*\\s*\\(' BootstrapAssetsLoaderTest.java` returns ≥1 match"
  - "BootstrapAssetsLoaderTest has a @Test method whose name contains `idempotent` (case-insensitive) that asserts a second loader run with the same entries does NOT change row count and does NOT reset `consecutive_failures` / `last_success_at` / `last_failure_at` (those columns are fetcher-managed; the loader's UPDATE must NOT clobber them). The seeded scenario sets `consecutive_failures = 3` between loads and asserts it survives. Verify: `grep -iE 'void\\s+\\w*idempotent\\w*\\s*\\(' BootstrapAssetsLoaderTest.java` returns ≥1 match"
  - "BootstrapAssetsLoaderTest has a @Test method whose name contains `softDisable` (case-insensitive) that asserts an entry present in a prior bootstrap and absent from the latest bootstrap is set to `enabled = false`, NOT hard-deleted. The seeded scenario loads two entries, then re-loads with only one, and asserts the absent entry's row still exists with `enabled = false` AND any historical row in `price_snapshot` referencing that `(asset, sub_verb)` would remain queryable (verified by the row's continued presence in `asset_config`; M1-055b owns the `price_snapshot` table). Verify: `grep -iE 'void\\s+\\w*softDisable\\w*\\s*\\(' BootstrapAssetsLoaderTest.java` returns ≥1 match"
  - "BootstrapAssetsLoaderTest has a @Test method whose name contains `absentFileDisablesCommands` (case-insensitive) that asserts the @Startup path with no `infochat.bootstrap.assets-file` configured (or pointing at a non-existent path) does NOT throw and does NOT INSERT any rows — `SELECT COUNT(*) FROM asset_config` returns 0. The asset commands are operator-optional per spec §Asset commands — Enable/disable lifecycle. Verify: `grep -iE 'void\\s+\\w*absentFileDisablesCommands\\w*\\s*\\(' BootstrapAssetsLoaderTest.java` returns ≥1 match"
  - "infochat-collector/src/test/resources/bootstrap/bootstrap-assets-fixture.json exists and matches the schema in docs/design/10-asset-commands.md §10.6 with at least `zcash` and `monero` plus their default sub-verbs and per-asset `supported_vs` lists. Verify: the file exists AND `grep -E '\"id\"\\s*:\\s*\"zcash\"' bootstrap-assets-fixture.json` returns ≥1 match AND `grep -E '\"id\"\\s*:\\s*\"monero\"' bootstrap-assets-fixture.json` returns ≥1 match"
  - "BootstrapAssetsParser is a system-boundary validator: an oversize JSON input (e.g. 100MB nested array) does NOT exhaust the heap. The parser reads through Jackson's streaming `JsonParser` or honors Jackson's default depth limit. This acceptance is satisfied by either (a) explicit max-depth / max-size config in the parser, OR (b) reliance on Jackson defaults which already bound nested depth and array length — author's call. Verify: BootstrapAssetsParserTest has a @Test method whose name contains `rejectsOversizeInput` (case-insensitive) that asserts the parser does NOT silently accept a deeply-nested input crafted to blow the stack (the test uses a small fixture with absurd nesting; assertion is that the parser throws rather than OOMs). `grep -iE 'void\\s+\\w*rejectsOversizeInput\\w*\\s*\\(' BootstrapAssetsParserTest.java` returns ≥1 match"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-007a Fetcher SPI tests, M1-022/023 RSS / Bluesky fetcher tests, M1-026/027/028 ingest tests, all collector-side tests, plus every M1-044a..M1-051 + M1-052..M1-054 test currently green on main"
test_plan:
  adds:
    - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsParser.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsEntry.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java
    - infochat-core/src/main/resources/db/migration/V14__asset_config.sql
    - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsParserTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoaderTest.java
    - infochat-collector/src/test/resources/bootstrap/bootstrap-assets-fixture.json
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/schema.md §Operational
  - docs/spec/security.md §DB roles
decision_refs:
  - D33
  - D34
  - D39
---

# M1-055a: bootstrap-assets.json parser + asset_config table + default-row consistency check + Collector @Startup loader

## Context

This subticket lands the **bootstrap input + DB schema** half of
the T2-H asset-commands vertical (M1-055 umbrella):

1. The `V14__asset_config.sql` Flyway migration creates the
   `asset_config` table with the spec-committed columns +
   primary key + status CHECK constraint + partial unique index
   enforcing at-most-one `is_default = true` row per `asset`.
   The migration carries the V5-style per-role GRANT block:
   Collector `INSERT/UPDATE/SELECT`, Provider `SELECT`-only,
   no DELETE for either (soft-disable is the lifecycle path).
2. `BootstrapAssetsParser` + `BootstrapAssetsEntry` mirror the
   existing `BootstrapSourcesParser` + `BootstrapSourcesEntry`
   pattern under `infochat-collector/.../bootstrap/` — strict
   Jackson, unknown-field rejection, post-parse semantic
   validation (`default_sub_verb` must be present in the
   entry's `sub_verbs` list).
3. `BootstrapAssetsLoader` is a `@ApplicationScoped` CDI bean
   with an `@Observes StartupEvent` (or `@Startup`-annotated
   method) that reads the `infochat.bootstrap.assets-file`
   property (Optional<Path>), invokes the parser when
   configured and present, and upserts the parsed entries into
   `asset_config`. Absent path / absent file → asset commands
   disabled, no INSERT runs.
4. The loader's default-row consistency check rejects at
   Collector startup any entry whose `default_sub_verb` would
   correspond to a row carrying `is_default = true AND
   enabled = false` (the partial unique index enforces
   at-most-one default, not default-implies-enabled; this is
   the spec §Operational — Asset config "default-row
   consistency" rule made executable at boot).
5. The loader's soft-disable behavior: entries present in a
   prior load and absent from the latest load are set to
   `enabled = false`, never hard-deleted. Historical
   `price_snapshot` rows referencing the disabled
   `(asset, sub_verb)` remain queryable for audit (M1-055b
   owns the `price_snapshot` table; this ticket ensures the
   FK target survives).
6. Every successful load writes a single `BOOTSTRAP_ASSET_LOAD`
   audit row (the verb already exists in the `AuditAction`
   enum at `infochat-core/.../AuditAction.java` line 50) with
   per-load counts in `details_json` (entries upserted,
   entries soft-disabled, bootstrap file path).

`security_relevant: true` because the parser is a **system
boundary** (operator-supplied JSON). Oversize / deeply-nested
input must fail safely rather than OOMing the Collector. The
DB-role split in the migration is also a least-privilege
commitment: Provider `SELECT`-only on `asset_config` means a
SQL-injection bug in the Provider cannot disable an asset
command or move the default flag. `migration_touch: true`
because V14 is the canonical schema change.

## Definition of Done

- `V14__asset_config.sql` (or next-free `V<N>` if M1-052 has
  consumed V14 first) creates `asset_config` with the
  spec-committed shape + the partial unique index + the
  per-role GRANTs.
- `BootstrapAssetsParser`, `BootstrapAssetsEntry`, and
  `BootstrapAssetsLoader` exist under
  `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/`
  with the shape described above.
- `BootstrapAssetsParserTest` covers happy-path parsing,
  unknown-field rejection, missing-default-sub-verb
  rejection, and oversize-input rejection.
- `BootstrapAssetsLoaderTest` covers fresh-insert, idempotent
  re-run, soft-disable on absent-from-bootstrap,
  reject-default-but-disabled, and absent-file no-op.
- A test fixture
  `infochat-collector/src/test/resources/bootstrap/bootstrap-assets-fixture.json`
  provides the canonical v1 `zcash` + `monero` shape per
  design §10.6.
- `mvn -B clean verify` exits 0.

## Notes

- **Mirror the existing `BootstrapSourcesParser` pattern.**
  Verified at brief-authoring time: the source-bootstrap
  classes exist under the same package and set the precedent
  for strict Jackson + post-parse semantic validation +
  idempotent upsert by `(kind, identifier)`. The new
  parser/entry/loader trio follows the same naming and
  semantics with `(asset, sub_verb)` as the PK.
- **Default-row consistency at boot, not runtime.** The check
  is bootstrap-time only: if an operator edits the JSON to
  set `default_sub_verb` for an asset to a sub-verb that is
  also disabled (e.g. through a removed `sub_verbs` entry
  that survives in the DB as `enabled = false`), startup
  fails fast with a fatal log line and a thrown exception.
  The Provider-side defense-in-depth fallback ("default
  sub-verb is currently disabled; pass an explicit sub-verb")
  is M1-055c territory — it covers the case where the
  invariant is broken at runtime by some other path.
- **AuditAction.BOOTSTRAP_ASSET_LOAD already exists.**
  Verified at brief-authoring time. No new enum entries
  needed — the loader writes through the existing
  AuditLogWriter (M1-041) with the existing verb.
- **DataSource selection.** The Collector connects under
  the `infochat_collector` role, which has `INSERT/UPDATE`
  on `asset_config` per the V14 GRANTs. The loader uses
  the default `@Inject DataSource` — same shape as the
  existing source bootstrap loader.
- **Migration race with M1-052.** M1-052
  (`saved_post`) and M1-053 / M1-054 (T2-B siblings) plus
  M1-055b each claim a next-free `V<N>__*.sql`. Whichever
  ticket MERGES first claims its V<N>; the second rebases.
  At brief-authoring time V13 was the last applied
  migration, and M1-052 claims V14 in its frontmatter.
  This ticket's migration MUST be renamed if M1-052 has
  merged first — re-run `ls
  infochat-core/src/main/resources/db/migration/ | sort -V`
  at `/m1-tick start` and rename the file plus any V14
  references in tests.
- **Soft-disable semantics.** The loader UPDATEs
  `enabled = false` on absent entries; it MUST NOT touch
  `consecutive_failures`, `last_success_at`, or
  `last_failure_at` (those are fetcher-managed by
  M1-055b). The `idempotent` test pins this.

## Big-picture notes

- **No fetcher / handler in this ticket.** This ticket
  lands schema + bootstrap input only. The fetcher side
  (M1-055b) and the Provider command side (M1-055c) land
  in their own commits. After this ticket merges,
  `asset_config` is populated at Collector startup but
  nothing reads from it yet (Provider has SELECT, but no
  Provider code reads it until M1-055c lands).
- **The AssetCommandFamilyOracle stays returning false
  until M1-055c.** M1-045's seam is unchanged by this
  ticket. The Provider has SELECT on `asset_config` from
  V14 onward, but the oracle does not consume it until
  M1-055c's impl swap.

## Out-of-scope expansion

- **Per-source consecutive-failure logic.** M1-055b
  territory. This ticket creates the columns; M1-055b
  writes to them.
- **Provider-side runtime fallback** for the
  default-but-disabled case. M1-055c territory.
- **Bundle keys / friendly errors.** M1-055c territory.
- **Test of the `Provider role cannot UPDATE
  asset_config`** invariant. The GRANT block in V14
  pins this at the DB layer; the per-role split is
  asserted in M1-008/M1-008b's role tests. This ticket
  does NOT add a Provider-role test for `asset_config`
  specifically (would be redundant with the existing
  role-split assertions; the new GRANT lines are visible
  in V14 and the reviewer's diff inspection covers them).

## Authorized test changes

- (none — this ticket adds new tests and modifies no
  pre-existing tests.)

## Alternatives considered

- **Single V<N> migration creating both `asset_config`
  and `price_snapshot`.** Rejected — M1-055b owns
  `price_snapshot` and its partitioning machinery. Two
  migrations cleanly separate the two subtickets'
  scopes; the V<N> / V<N+1> numbering reflects the
  dependency (M1-055b's table FKs to `asset_config`).
- **Extend `BootstrapSourcesParser` to also handle
  assets.** Rejected — sources and assets have
  different schemas, different post-parse validation
  rules, and different downstream consumers. A shared
  base class would couple two evolving surfaces; two
  parallel classes mirror the spec's separation
  (§Sources and tags vs §Operational asset_config).
- **Skip the default-row consistency check at boot;
  rely on the partial unique index alone.** Rejected —
  the index enforces at-most-one default, not
  default-implies-enabled. An operator typo (default
  pointing at a disabled sub-verb) would silently break
  bare `/zcash` at runtime with the Provider-side
  fallback firing on every invocation. Fail-fast at
  boot makes the misconfiguration visible immediately.
