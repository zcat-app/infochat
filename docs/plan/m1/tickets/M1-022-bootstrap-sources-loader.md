---
id: M1-022
title: Bootstrap-sources loader (Collector @Startup + bootstrap_meta)
status: pending
created: 2026-05-14
last_updated: 2026-05-14
blocked_by:
  - M1-008a
  - M1-008b
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration/V8__bootstrap_meta.sql
  - infochat-collector/src/main/java/io/infochat/collector/bootstrap/BootstrapSourcesEntry.java
  - infochat-collector/src/main/java/io/infochat/collector/bootstrap/BootstrapSourcesParser.java
  - infochat-collector/src/main/java/io/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapSourcesParserTest.java
  - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapLoaderIT.java
  - infochat-collector/src/test/resources/bootstrap/bootstrap-sources-fixture.json
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - any /add-source, /remove-source, /source-enable, /source-disable, /list-sources command handler (Provider-side command handlers are T1-F territory; this ticket writes to source from the Collector's bootstrap path only)
  - any infochat-ssrf module work or wiring (per the Option A two-ticket carve-out documented in Implementation notes — infochat-ssrf is a separate ticket authored before the FetchScheduler lands in T1-C; the bootstrap loader makes no outbound network calls so the carve-out does not affect this ticket)
  - any bootstrap-assets.json loader code (asset commands are Tier-2 T2-H per decision D39 — out of scope here even though docs/design/01-architecture.md §1.4.2 steps 6-8 enumerate the asset path on the same @Startup bean; THIS ticket loads sources only and the asset half lands in its own later ticket)
  - any FetchScheduler / per-kind tick logic / per-source cadence wiring (T1-C territory at @Priority(400); the bootstrap loader sits at @Priority(200) and exits before the scheduler starts)
  - any OutboxRehydrator, LISTEN/NOTIFY trigger, or provider_state work (T1-C)
  - any RSS / Bluesky / Nitter / Reddit / YouTube / Odysee Fetcher implementation (each fetcher impl is its own T1-B / T3-B / T3-C ticket binding to the M1-007a Fetcher SPI; this ticket writes source rows the fetchers will later read)
  - any NostrStreamSource implementation or StreamSourceSupervisor wiring (T1-B / T3-C territory at @Priority(450))
  - any change under infochat-core/src/main/resources/db/migration/V1__*.sql through V7__*.sql (frozen; this ticket adds V8 only)
  - any Java entity class, Hibernate / Panache mapping, repository, service, or DAO for source / tag / audit_log / bootstrap_meta (raw JDBC INSERT / UPDATE / UPSERT only — the entity layer for /list-sources etc. lands with the T1-F Provider-side handlers)
  - any tag-normalization helper, NFC / Locale.ROOT lower-case path, or character-class validation in Java (the schema CHECK constraint on tag.name from M1-008b enforces the regex at the storage layer; the application-side normalizer lands with the first ingest path that writes tags — T1-D's tagger)
  - any redaction-hook Java code or audit-log writer abstraction (the loader writes the BOOTSTRAP_SOURCE_LOAD audit row inline; the abstracted audit-write redaction hook is a later ticket)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V8__bootstrap_meta.sql exists and creates the bootstrap_meta table per docs/design/02-schema.md §2.9.5 — grep -E 'CREATE TABLE\\s+bootstrap_meta' V8 returns at least one match"
  - "V8 declares the single-row guard PRIMARY KEY DEFAULT 1 CHECK (id = 1) per §2.9.5 — grep -E 'id\\s+SMALLINT\\s+PRIMARY\\s+KEY\\s+DEFAULT\\s+1' returns at least one match AND grep -E 'CHECK\\s*\\(\\s*id\\s*=\\s*1\\s*\\)' returns at least one match"
  - "V8 declares the four non-key columns per §2.9.5 — grep -E 'last_loaded_sha256\\s+TEXT\\s+NOT NULL' returns at least one match AND grep -E 'last_loaded_at\\s+TIMESTAMPTZ\\s+NOT NULL' returns at least one match AND grep -E 'last_entry_count\\s+INT\\s+NOT NULL' returns at least one match AND grep -E 'last_loader_version\\s+TEXT\\s+NOT NULL' returns at least one match"
  - "V8 grants bootstrap_meta privileges aligned with docs/spec/security.md §DB roles — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s*,\\s*UPDATE\\s+ON\\s+bootstrap_meta\\s+TO\\s+infochat_collector' returns at least one match (the loader writes) AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+bootstrap_meta\\s+TO\\s+infochat_provider' returns at least one match (the admin /status read path)"
  - "V8 revokes DELETE on bootstrap_meta from both service roles (the single-row guard's complement; the row is never deleted, only UPSERTed) — grep -E 'REVOKE\\s+DELETE\\s+ON\\s+bootstrap_meta\\s+FROM\\s+(infochat_collector|infochat_provider|PUBLIC)' returns at least one match"
  - "BootstrapSourcesEntry.java is a Java record with the six fields enumerated in docs/design/07-deployment.md §7.6.1 — grep -E 'public record BootstrapSourcesEntry' returns at least one match AND grep -E '\\bkind\\b|\\bidentifier\\b|\\bname\\b|\\bcategory\\b|\\btags\\b|\\bconfig\\b' BootstrapSourcesEntry.java returns at least six distinct matches across the six field names"
  - "BootstrapSourcesParser.java rejects an unknown top-level field by failing parse (Jackson's FAIL_ON_UNKNOWN_PROPERTIES set explicitly OR the equivalent reader configuration) — grep -E 'FAIL_ON_UNKNOWN_PROPERTIES|failOnUnknownProperties' BootstrapSourcesParser.java returns at least one match"
  - "BootstrapSourcesParser.java canonicalizes Nostr identifiers (sorted JSON object keys, compact whitespace) BEFORE upsert per docs/spec/architecture.md §Ingest SPIs Source identity — grep -E 'canonicaliz|sortKeys|SORT_PROPERTIES_ALPHABETICALLY|SerializationFeature\\.ORDER_MAP_ENTRIES_BY_KEYS' BootstrapSourcesParser.java returns at least one match AND the parser test asserts that two semantically-identical Nostr filter specs (key-order swapped) produce identical canonical identifier strings"
  - "BootstrapSourcesParser.java rejects rss/bluesky/nitter/reddit/youtube/odysee entries whose config is non-null per docs/design/07-deployment.md §7.6.1 Per-kind config shape — the parser test asserts that an entry with kind='rss' and a non-null config object raises a parse-rejection exception (BootstrapSourcesParserTest grep returns at least one match for 'config.*non-null|HTTP-shaped' in test source)"
  - "BootstrapLoader.java is a Quarkus @Startup bean at @Priority(200) per docs/design/01-architecture.md §1.4.3 Collector table — grep -E '@Startup' BootstrapLoader.java returns at least one match AND grep -E '@Priority\\s*\\(\\s*200\\s*\\)' BootstrapLoader.java returns at least one match"
  - "BootstrapLoader.java reads the configured file path from the property infochat.bootstrap.sources-file with default 'bootstrap-sources.json' per the spec/CLAUDE.md §Bootstrap admin & sources convention — grep -E 'infochat\\.bootstrap\\.sources-file' BootstrapLoader.java returns at least one match AND grep -E '\"bootstrap-sources\\.json\"' BootstrapLoader.java returns at least one match (the default literal)"
  - "BootstrapLoader.java computes a SHA-256 of the file BEFORE parsing and writes the hex digest into the BOOTSTRAP_SOURCE_LOAD audit row's details_json and into bootstrap_meta.last_loaded_sha256 — grep -E 'MessageDigest\\.getInstance\\(\"SHA-256\"\\)|sha-?256|SHA-256' BootstrapLoader.java returns at least one match AND grep -E '\"BOOTSTRAP_SOURCE_LOAD\"' BootstrapLoader.java returns at least one match"
  - "BootstrapLoader.java performs the source upsert in a single JDBC transaction with autoCommit=false — grep -E 'setAutoCommit\\s*\\(\\s*false\\s*\\)' BootstrapLoader.java returns at least one match AND the file contains exactly one outermost try / catch block around the transactional body that rolls back on failure (grep -E 'rollback\\s*\\(' BootstrapLoader.java returns at least one match)"
  - "BootstrapLoader.java uses ON CONFLICT (kind, identifier) DO UPDATE for the source upsert path per docs/design/01-architecture.md §1.4.2 step 4 — grep -E 'ON CONFLICT\\s*\\(\\s*kind\\s*,\\s*identifier\\s*\\)\\s+DO UPDATE' BootstrapLoader.java returns at least one match"
  - "BootstrapLoader.java SKIPs source rows where the existing row has deleted_at IS NOT NULL per docs/design/02-schema.md §2.2.1 Soft-delete semantics ('the bootstrap loader skips rows where deleted_at IS NOT NULL') — grep -E 'deleted_at\\s+IS\\s+NOT\\s+NULL|WHERE\\s+source\\.deleted_at|deleted_at IS NULL' BootstrapLoader.java returns at least one match in a context that affects the upsert path (BootstrapLoaderIT asserts the skip behavior by seeding a deleted_at-non-null row pre-load and confirming the row's name/category/bootstrap_tags are unchanged post-load)"
  - "BootstrapLoader.java unions tags across all entries and upserts into tag with source_origin = 'bootstrap' — grep -E \"source_origin\\s*[,)]|'bootstrap'\" BootstrapLoader.java returns at least one match AND grep -E 'ON CONFLICT\\s*\\(\\s*name\\s*\\)' BootstrapLoader.java returns at least one match"
  - "BootstrapLoader.java upserts the single bootstrap_meta row with ON CONFLICT (id) DO UPDATE per the §2.9.5 single-row guard — grep -E 'INSERT\\s+INTO\\s+bootstrap_meta' BootstrapLoader.java returns at least one match AND grep -E 'ON CONFLICT\\s*\\(\\s*id\\s*\\)\\s+DO UPDATE' BootstrapLoader.java returns at least one match"
  - "BootstrapLoader.java logs a one-line INFO summary after success containing 'BootstrapLoader' and the loaded entry count — grep -E 'BootstrapLoader.*loaded|loaded.*sources' BootstrapLoader.java returns at least one match in a context that calls a logger at INFO level"
  - "BootstrapSourcesParserTest.java is a plain JUnit 5 test (no @QuarkusTest) and asserts: (a) a valid two-entry fixture parses to two BootstrapSourcesEntry records; (b) an unknown top-level field triggers a parse-rejection exception; (c) a tags array of length 0 triggers a parse-rejection exception (docs/design/07-deployment.md §7.6.1 tags: 'yes, ≥1'); (d) two Nostr entries with key-order-swapped JSON filter specs canonicalize to the same identifier string; (e) an rss entry with a non-null config object is rejected — grep -E '@Test' BootstrapSourcesParserTest.java returns at least five matches"
  - "BootstrapLoaderIT.java is a @QuarkusTest integration test that spins up a Postgres via @QuarkusTestResource (Testcontainers DevServices is acceptable) and asserts: (a) loading a 3-entry fixture writes exactly 3 source rows + the union-of-tags into tag + exactly one BOOTSTRAP_SOURCE_LOAD audit_log row + a populated bootstrap_meta row; (b) re-running the loader against the same fixture is a source-row no-op (the source rows' name / category / bootstrap_tags are identical pre- and post-second-run) but writes a SECOND audit_log row (each run logs); (c) a fixture entry whose (kind, identifier) matches an existing row with deleted_at IS NOT NULL leaves that row's name/category unchanged (operator intent honored) — grep -E '@Test' BootstrapLoaderIT.java returns at least three matches"
  - "infochat-collector/src/test/resources/bootstrap/bootstrap-sources-fixture.json is a valid JSON array with at least 3 entries (one rss kind, one bluesky kind, one nostr kind with a config.relays array of ≥1 wss:// URL) — `jq '. | length' fixture.json` returns ≥3 AND `jq '[.[] | .kind] | sort | unique'` includes 'rss' AND 'nostr'"
  - "mvn -B -pl infochat-collector -am test exits 0; mvn -B -pl infochat-collector -am verify exits 0; surefire reports show BootstrapSourcesParserTest executed (grep -rE 'Tests run: [1-9]' infochat-collector/target/surefire-reports returns at least one new match for BootstrapSourcesParserTest); failsafe reports show BootstrapLoaderIT executed (grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least one new match for BootstrapLoaderIT)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003, M1-007, M1-007a/b/c, M1-008, M1-008a/b/c, and M1-009 tests continue to pass alongside the new V8 migration and the bootstrap loader code"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapSourcesParserTest.java (plain-JUnit unit test exercising parser-level shape rules: schema validation, unknown-field rejection, ≥1-tag rule, Nostr canonicalization, per-kind config rejection on HTTP-shaped sources)
    - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapLoaderIT.java (@QuarkusTest integration test against a Testcontainers Postgres: idempotent re-run, audit-log append, deleted_at-skip behavior, bootstrap_meta population)
    - infochat-collector/src/test/resources/bootstrap/bootstrap-sources-fixture.json (a 3-entry fixture covering rss + bluesky + nostr; the parser test and the loader IT share this fixture)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V8 must apply cleanly alongside V1..V7)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 / M1-017)
    - infochat-collector/src/test/java/io/infochat/collector/startup/InstanceLockGuardIT.java (M1-009)
    - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java (M1-009)
    - all M1-008a / M1-008b / M1-008c *Test.java classes (schema tests)
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/architecture.md §Architectural principles
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/schema.md §Sources and tags
  - docs/spec/security.md §DB roles
  - docs/design/01-architecture.md §1.4.2 Bootstrap loader
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering and single-instance enforcement
  - docs/design/02-schema.md §2.2.1 source
  - docs/design/02-schema.md §2.2.2 tag
  - docs/design/02-schema.md §2.9.5 bootstrap_meta
  - docs/design/07-deployment.md §7.6 Bootstrap files
  - docs/design/07-deployment.md §7.6.1 bootstrap-sources.json
decision_refs:
  - D7
  - D38
  - D42
---

# M1-022: Bootstrap-sources loader (Collector @Startup + bootstrap_meta)

## Context

First of two T1-B ingest-sources tickets (the second is M1-023, the
RSS Fetcher impl). The Collector's bootstrap path is the single point
at which the operator-supplied `bootstrap-sources.json` is read,
canonicalized, and idempotently merged into the schema's `source` and
`tag` catalogues authored in M1-008b. Today neither catalogue has any
writer; every later Tier-1 ticket that reads from them (the
FetchScheduler in T1-C, the tagger in T1-D, the `/list-sources` and
`/follow-tag` command handlers in T1-F) assumes the bootstrap path
has already populated them. This ticket is that path.

The loader is a Quarkus `@Startup` bean at `@Priority(200)` per
`docs/design/01-architecture.md` §1.4.3 — it runs **after Flyway**
(`@Priority(100)`, ambient) and **before** `OutboxRehydrator`
(`@Priority(300)`), `FetchScheduler` (`@Priority(400)`), and
`StreamSourceSupervisor` (`@Priority(450)`). The ordering is
load-bearing: the scheduler cannot start polling sources that the
loader has not yet written, and the rehydrator's "what's left in
`RAW`/`EVALUATING`" sweep is meaningfully bounded only after every
configured source is in the schema.

A new Flyway migration `V8__bootstrap_meta.sql` adds the operational
helper table per `docs/design/02-schema.md` §2.9.5 — a single-row
record of "what bootstrap config did we last successfully load"
(SHA-256 of the file, timestamp, entry count, loader version). The
authoritative audit trail is `audit_log` (action
`BOOTSTRAP_SOURCE_LOAD`, per `docs/design/02-schema.md` §2.1.8 verb
catalogue, written by this loader); `bootstrap_meta` is the cheap
current-state read the admin `/status` view consults to answer
"are all instances running the same bootstrap config?" without
scanning audit history.

This is the first Collector code that writes catalogue rows. The
matching admin-side read path (`/list-sources`, `/status`) is T1-F
territory; the schema commitment here is enough for those handlers
to attach to once they exist.

## Definition of Done

- A new Flyway migration
  `infochat-core/src/main/resources/db/migration/V8__bootstrap_meta.sql`
  creates the `bootstrap_meta` table per `docs/design/02-schema.md`
  §2.9.5:
  - `id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1)` — the
    single-row guard. The `DEFAULT 1` and the `CHECK (id = 1)`
    together force every INSERT to land on the single canonical row;
    the upsert path is `ON CONFLICT (id) DO UPDATE`.
  - `last_loaded_sha256 TEXT NOT NULL` — hex digest of the
    bootstrap-sources.json file's bytes (lower-case, 64 hex chars).
  - `last_loaded_at TIMESTAMPTZ NOT NULL`.
  - `last_entry_count INT NOT NULL`.
  - `last_loader_version TEXT NOT NULL` — pinned at INSERT/UPDATE
    time from `@ConfigProperty(name = "quarkus.application.version")`
    or an equivalent build-info-source constant. Document the choice
    in the JDoc on `BootstrapLoader#resolveLoaderVersion`.
  - **Per-table GRANTs** aligned with `docs/spec/security.md` §DB
    roles:
    - `GRANT SELECT, INSERT, UPDATE ON bootstrap_meta TO
      infochat_collector` — the loader writes.
    - `GRANT SELECT ON bootstrap_meta TO infochat_provider` — the
      admin `/status` read path (T1-F).
    - `REVOKE DELETE ON bootstrap_meta FROM infochat_collector,
      infochat_provider, PUBLIC` — the row is never deleted, only
      UPSERTed; defense-in-depth complement of the single-row
      `CHECK (id = 1)`.
- `BootstrapSourcesEntry.java` is a Java record matching the JSON
  schema in `docs/design/07-deployment.md` §7.6.1 with six fields:
  `String kind`, `String identifier`, `String name`,
  `String category`, `List<String> tags`,
  `Map<String, Object> config` (nullable). Jackson's record-binding
  with `@JsonCreator`-equivalent default works; the parser sets
  `FAIL_ON_UNKNOWN_PROPERTIES = true` so unknown top-level fields
  are rejected.
- `BootstrapSourcesParser.java` parses the JSON file via Jackson
  configured with:
  - `FAIL_ON_UNKNOWN_PROPERTIES = true` (strict schema).
  - `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS = true` for the
    Nostr canonicalization path (re-serializes the parsed filter
    spec into a key-sorted, compact-whitespace canonical form).
  - Post-parse semantic validation:
    - `tags` array MUST be non-empty per
      `docs/design/07-deployment.md` §7.6.1 ("tags: yes, ≥1").
    - For `kind ∈ {rss, bluesky, nitter, reddit, youtube, odysee}`,
      `config` MUST be null or omitted — non-null raises a
      parse-rejection per the Per-kind config shape table.
    - For `kind = nostr`, `config.relays` MUST be a non-empty array
      of `wss://`-prefixed strings — per `docs/design/07-deployment.md`
      §7.6.1 Per-kind config shape.
    - For `kind = nostr`, the `identifier` JSON object is
      canonicalized: re-serialized with lexicographically-sorted keys
      and compact whitespace, per
      `docs/spec/architecture.md` §Ingest SPIs Source identity. The
      canonical string becomes the stored `identifier`.
- `BootstrapLoader.java` is a Quarkus `@Startup` bean at
  `@Priority(200)` per `docs/design/01-architecture.md` §1.4.3.
  Steps, in this exact order, in a single JDBC transaction with
  `autoCommit = false`:
  1. Read the file path from `@ConfigProperty(name =
     "infochat.bootstrap.sources-file", defaultValue =
     "bootstrap-sources.json")`. An absolute or working-dir-relative
     path resolves as-is.
  2. Read the file's bytes; compute SHA-256 (lower-case hex digest)
     **before** parsing. The digest goes into both the audit row's
     `details_json` and the `bootstrap_meta.last_loaded_sha256`
     column.
  3. Call `BootstrapSourcesParser.parse(bytes)` → `List<
     BootstrapSourcesEntry>`. Any parse / validation failure
     propagates as a startup failure (Quarkus default — the service
     refuses to start, per `docs/design/01-architecture.md` §1.4.3).
  4. Open the transactional body:
     - For each entry, upsert into `source` using `INSERT INTO source
       (kind, identifier, display_name, category, bootstrap_tags,
       config) VALUES (?, ?, ?, ?, ?, ?::JSONB) ON CONFLICT (kind,
       identifier) DO UPDATE SET display_name = EXCLUDED.display_name,
       category = EXCLUDED.category, bootstrap_tags =
       EXCLUDED.bootstrap_tags, config = EXCLUDED.config WHERE
       source.deleted_at IS NULL`. The `WHERE source.deleted_at IS
       NULL` predicate on the UPDATE branch enforces the
       `docs/design/02-schema.md` §2.2.1 "skip rows where deleted_at
       IS NOT NULL" rule — a soft-deleted row stays soft-deleted
       even if the operator re-lists it in the file; admin uses
       `/remove-source` / `/add-source` for that lifecycle, not the
       bootstrap path.
     - Union `tags` across all entries and upsert into `tag`:
       `INSERT INTO tag (name, display, source_origin) VALUES (?,
       ?, 'bootstrap') ON CONFLICT (name) DO NOTHING`. The
       application-layer tag normalizer (T1-D) does the NFC +
       lower-case pass; the schema CHECK on `tag.name` from
       M1-008b's V6 catches an un-normalized value. **In v1 the
       loader passes the original-cased tag string as `display` and
       a `Locale.ROOT.toLowerCase()`-of-NFC-normalized form as
       `name`** — the normalization step lives inline in the loader
       because the dedicated tag normalizer doesn't exist yet; T1-D
       will refactor to a shared helper. Document this inline
       carve-out in the loader's JDoc.
     - INSERT one `audit_log` row with `action =
       'BOOTSTRAP_SOURCE_LOAD'`, `target_kind = 'system'`,
       `target_id` set to the SHA-256 hex digest (so the audit
       trail is keyed by file-content version), and `details_json`
       carrying the resolved file path and entry count. Per
       `docs/design/02-schema.md` §2.1.8 the verb is
       `BOOTSTRAP_SOURCE_LOAD`; per §2.1.7 the row is INSERT-only
       and the append-only trigger enforces this.
     - Upsert `bootstrap_meta`: `INSERT INTO bootstrap_meta (id,
       last_loaded_sha256, last_loaded_at, last_entry_count,
       last_loader_version) VALUES (1, ?, now(), ?, ?) ON
       CONFLICT (id) DO UPDATE SET last_loaded_sha256 =
       EXCLUDED.last_loaded_sha256, last_loaded_at =
       EXCLUDED.last_loaded_at, last_entry_count =
       EXCLUDED.last_entry_count, last_loader_version =
       EXCLUDED.last_loader_version`.
     - `connection.commit()`. On any SQLException in the
       transactional body, `connection.rollback()` and re-throw —
       the @Startup bean's failure aborts service start per
       Quarkus default and §1.4.3.
  5. Log a single INFO-level summary line:
     `BootstrapLoader: loaded <N> sources from <path> (sha=<hex>).`
- One unit test:
  `infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapSourcesParserTest.java`
  — plain JUnit 5 (no `@QuarkusTest`), exercises parser-level shape
  rules against the shared fixture and against tiny inline JSON
  documents.
- One integration test:
  `infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapLoaderIT.java`
  — `@QuarkusTest` against a Postgres provided by Quarkus
  DevServices (or `@QuarkusTestResource` Testcontainers). Asserts
  the idempotent re-run, the audit-log append-per-run, the
  deleted_at-skip behavior, and the `bootstrap_meta` UPSERT.
- The fixture
  `infochat-collector/src/test/resources/bootstrap/bootstrap-sources-fixture.json`
  has at least three entries: one rss, one bluesky, one nostr (with
  a `config.relays` array of at least one `wss://` URL). The parser
  test and the loader IT share this fixture so any future schema
  change ripples to both at once.
- `mvn -B clean verify` from the repo root exits 0. All prior tests
  continue to pass alongside the new V8 migration and the loader
  code.

## Implementation notes

- **Option A (two-ticket) for T1-B.** This authoring session deliberately
  ships T1-B as two tickets — M1-022 (this one) and M1-023 (the RSS
  Fetcher). The `infochat-ssrf` shared module per
  `docs/design/01-architecture.md` §1.2 and `docs/spec/security.md`
  §SSRF and outbound connections is NOT introduced in this group; it
  will be authored as a separate ticket before the FetchScheduler
  lands in T1-C. **This ticket's loader makes no outbound network
  calls** (it reads a local file and writes to Postgres), so the
  Option A carve-out has no effect on this ticket; the carve-out is
  load-bearing only for M1-023 and is documented in that ticket's
  Big-picture notes.
- **Migration version is V8.** V1..V7 already live on disk per
  M1-005, M1-006, M1-009, M1-016, M1-017, and M1-008a/b/c. If a
  later authoring session lands M1-021's `V8__identity_audit_redteam_remediation.sql`
  before this ticket starts, slide this ticket's migration to V9 —
  re-grep the migration directory at `/m1-tick start` time and pick
  the next free integer. The slug `bootstrap_meta` is the invariant;
  the numeric prefix is allocated mechanically.
- **One migration file, one transaction.** Flyway runs each
  migration in a single transaction by default; the `CREATE TABLE`
  and the four GRANT/REVOKE statements apply atomically. No V8a /
  V8b split.
- **The `BOOTSTRAP_SOURCE_LOAD` verb already exists.** M1-008a's V5
  migration emitted `-- BOOTSTRAP_SOURCE_LOAD` as one of the 23 per-verb
  commentary lines, and the audit_log table itself imposes no CHECK
  on `action` (the verb set is open-ended for v2 extensions). This
  loader is the first writer to use the verb; no migration change is
  needed to introduce it. **Caveat**:
  `docs/design/07-deployment.md` §7.6.1 step 4 still reads
  "BOOTSTRAP_SOURCES" — that's design-doc drift relative to the
  authoritative §2.1.8 verb catalogue; the canonical verb is
  `BOOTSTRAP_SOURCE_LOAD` and this ticket uses it. A follow-up
  `spec:` commit can reconcile the design note.
- **No `audit_log` actor.** The loader runs at Collector startup
  before any user has acted; the audit row's `actor_user_id`,
  `actor_contact_id`, and `actor_adapter` columns are nullable and
  this loader leaves them NULL (the row's `action` and `target_id`
  carry the load-bearing information). This is the operator-side
  bootstrap intent recorded at the audit boundary, distinct from
  user-driven actions where the actor columns ARE load-bearing.
- **Raw JDBC, not Panache.** This ticket adds no entity classes.
  The loader opens a `DataSource`-injected connection
  (`@Inject DataSource dataSource;`), issues `PreparedStatement`s,
  manages its own transaction. The reasons:
  1. The schema's `source` row carries columns this ticket doesn't
     write (`added_by`, `added_at`, `last_fetch_at`,
     `last_success_at`, `consecutive_failures`, `deleted_at`,
     `deleted_by`) — a Panache entity would have to surface them
     all and we'd add lifecycle helpers that the rest of the
     application doesn't yet use.
  2. The ON CONFLICT upsert syntax is Postgres-specific and is
     clearer at the JDBC layer than via JPQL/HQL.
  3. The Provider-side T1-F handlers will likely introduce the
     entity layer in a focused diff when `/list-sources` and
     `/add-source` need it; pre-empting that decision here is scope
     drift.
- **`@ConfigProperty(defaultValue = "bootstrap-sources.json")`.** The
  default makes a no-config first-run resolve to the working
  directory's `bootstrap-sources.json`. Operators with an absolute
  path set `infochat.bootstrap.sources-file = /etc/infochat/...`.
  The path is resolved with `Paths.get(value)` so both forms work.
- **Nostr canonicalization belongs to the parser, not the loader.**
  By the time the loader sees a `BootstrapSourcesEntry`, the
  `identifier` is already canonical. This keeps the canonicalization
  rule in one testable place (the parser) and means the SQL upsert
  path doesn't need to know the canonicalization exists.
- **Jackson configuration.** Use the Quarkus-managed `ObjectMapper`
  via `@Inject` rather than constructing one locally — that mapper
  inherits the project-wide configuration and is the canonical
  point at which the strictness toggles can be tightened
  later. For the `FAIL_ON_UNKNOWN_PROPERTIES` toggle, use a
  per-parse `objectMapper.readerFor(BootstrapSourcesEntry[].class)
  .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` so the
  project-wide mapper is not mutated.
- **Idempotency contract.** Re-running the loader against an
  unchanged file:
  - Is a no-op for `source` rows (every column UPDATE writes the
    identical value).
  - Is a no-op for `tag` rows (`ON CONFLICT (name) DO NOTHING`).
  - Writes a NEW `audit_log` row (each run records its audit
    trail; this is the operator's evidence that the loader ran).
  - Updates `bootstrap_meta` (the `last_loaded_at` timestamp moves
    forward; the SHA / entry count are unchanged).
  The integration test asserts this exact shape.
- **The "skip-on-soft-delete" rule lives in the UPDATE branch's
  WHERE clause.** A simpler `ON CONFLICT (kind, identifier) DO
  UPDATE SET ... WHERE source.deleted_at IS NULL` keeps the rule at
  the SQL layer and avoids a Java-side pre-scan. The INSERT branch
  cannot fire when a row with the same `(kind, identifier)` already
  exists (the UNIQUE constraint), so the soft-deleted row stays
  soft-deleted with its old columns intact.
- **Tag normalization caveat (T1-D follow-up).** The inline
  `Locale.ROOT.toLowerCase()`-of-NFC-normalized pass in this
  loader is a temporary placement. T1-D's tagger pipeline will
  introduce a shared `TagNormalizer` helper used by both the
  bootstrap-loader path and the tagger; this ticket leaves a
  one-line comment `// TODO(T1-D): move to TagNormalizer helper`
  at the call site.
- **Quarkus DevServices vs. raw Testcontainers for the IT.** The
  M1-008a `PostgresSchemaTestBase` uses raw Testcontainers (the
  module is a plain library jar). `infochat-collector` is a
  Quarkus app, so the IT can lean on Quarkus DevServices'
  pgvector image — exactly what `FlywayMigrationIT` already does.
  The IT extends or follows the same pattern.

## Big-picture notes

- **The loader is the only writer to `source` in v1.** Bot-admin
  commands `/add-source` / `/source-enable` / `/source-disable` /
  `/remove-source` land on the Provider side in T1-F. Per the
  grant matrix in M1-008b, the Provider's path to `source` is
  `GRANT SELECT` only — the Provider does NOT carry
  `INSERT/UPDATE` on `source`. Provider-initiated writes route
  through a future handoff path (a stored procedure or a
  NOTIFY-based request to the Collector); the shape is a later
  spec / design decision and is NOT in scope here. This ticket
  cements the Collector-write commitment; the future Provider
  handoff is the rest of the picture.
- **The audit-row writer is inline.** A shared Java-side audit
  writer with redaction hook and request-id propagation is a
  later (T1-D / T1-E / T1-F) ticket. This loader runs at startup
  with no user context and no secrets to redact; inlining the
  INSERT keeps the loader's own dependency footprint tiny. When
  the audit writer abstraction lands, it supersedes the inline
  INSERT here in a focused diff.
- **`bootstrap_meta` is design-only.** Per
  `docs/design/02-schema.md` §2.9.5 the spec is silent on this
  table — it is an operational helper. The admin `/status` read
  path that consumes it lives in T1-F. The loader writes it
  unconditionally so the `/status` handler has a non-null
  current-state to surface from day one.
- **Subticket isolation against M1-023.** This ticket touches the
  `bootstrap/` package only (`io.infochat.collector.bootstrap`)
  plus one migration file and one test fixture. M1-023 touches
  the `fetcher/rss/` package
  (`io.infochat.collector.fetcher.rss`) plus its own test
  fixtures. The two `files_scope` lists are disjoint — both
  tickets are runnable in parallel once they have started.
- **The FetchScheduler's @Priority(400) reads from `source`** —
  specifically the `idx_source_status` partial index from
  M1-008b's V6 (`status` WHERE `deleted_at IS NULL`). The
  bootstrap loader at @Priority(200) MUST have run before the
  scheduler starts, or the scheduler sees an empty source table
  and the deployment runs with no ingest. The startup-bean
  ordering enforces this; the IT verifies the loader's exit
  leaves a non-empty source table for the rest of the boot
  sequence.
- **No NOTIFY trigger on `bootstrap_meta`.** The admin `/status`
  read is pulled on demand; there is no event-driven notification
  on bootstrap reload (operators see the change in the next
  `/status` invocation or by SHA-comparing across hosts).
  Adding NOTIFY here would be speculative.
- **D38 source-identity discipline.** The loader is the first
  writer to enforce the `(kind, identifier)` unique key with
  Nostr canonicalization in place. A regression that drops the
  canonicalization here would cause every relay-list edit in a
  Nostr entry to create a duplicate `source` row at the next
  bootstrap reload — the exact failure mode D38 exists to
  prevent. The parser test's Nostr-canonicalization assertion is
  the regression guard.

## Out-of-scope expansion

- **Provider-side `/add-source` / `/remove-source` / `/source-enable`
  / `/source-disable` / `/list-sources` command handlers.** All
  T1-F territory. The Provider's grant on `source` is `SELECT`
  only; the write path is a future handoff. This loader is the
  Collector-side write path for the bootstrap intent only.
- **`infochat-ssrf` module work.** Per the Option A carve-out
  documented in Implementation notes, infochat-ssrf is a
  separate ticket authored before T1-C. This loader makes no
  outbound network calls (file I/O + Postgres only); the carve-out
  has no effect here.
- **`bootstrap-assets.json` loader code.** Per
  `docs/design/01-architecture.md` §1.4.2 steps 6-8 the same
  `@Startup` bean would also load `bootstrap-assets.json`. The
  asset commands are Tier-2 (T2-H, decision D39), out of M1's
  vertical-slice. This ticket loads the sources half only; the
  asset half lands when the asset-commands work begins (its own
  ticket, its own migration if `asset_config` doesn't already
  exist, its own audit verb).
- **FetchScheduler, OutboxRehydrator, LISTEN/NOTIFY, provider_state,
  per-source cadence tuning.** All T1-C. The bootstrap loader
  exits before any of those beans start.
- **Concrete `Fetcher` / `StreamSource` implementations.** Each
  fetcher / stream impl is its own ticket. RssFetcher is M1-023;
  Bluesky / Nitter / Reddit / YouTube / Odysee are Tier-3 T3-B;
  NostrStreamSource is Tier-3 T3-C. This loader writes the
  `source` row those impls will later read.
- **Java entity classes / repositories / services / DAOs for
  source / tag / audit_log / bootstrap_meta.** None here. Raw
  JDBC inside the loader; the entity layer lands with the
  Provider-side handlers in T1-F.
- **Tag normalization helper.** Inline in this loader for now;
  T1-D extracts the shared `TagNormalizer` helper.
- **Audit-log writer abstraction with redaction hook.** Later
  ticket. The inline audit INSERT here writes no secrets — the
  details_json carries only the file path and entry count —
  so there is no redaction surface to abstract today.
- **Asset Fetcher dispatch / `price_snapshot` writes.** Tier-2.
- **Modifications to V1..V7 migrations.** Frozen. This ticket
  adds V8 only.

## Authorized test changes

- (none — this ticket adds two new test files plus one fixture in
  `infochat-collector` and modifies no pre-existing tests. All
  M1-003 / M1-007 / M1-008 / M1-009 / M1-017 tests continue to
  pass unchanged.)

## Alternatives considered

- **Bundle source + asset bootstrap into one ticket.** Rejected:
  the asset path requires the `asset_config` schema (Tier-2 work,
  decision D39) plus the price-snapshot dispatch surface; pulling
  it in here would double the ticket's scope and force a
  Tier-2 schema migration alongside the source loader. The
  `@Startup` bean's design naturally splits — step 1-5 is the
  sources half, step 6-8 is the assets half; this ticket implements
  the sources half cleanly and the assets half lands as a focused
  follow-up when Tier-2 starts.
- **Write source rows from a Provider-side command handler instead
  of a Collector @Startup bean.** Rejected: the operator-input
  `bootstrap-sources.json` is the Collector's startup input per
  `docs/design/01-architecture.md` §1.4.2 and
  `docs/spec/deployment.md` §Operator inputs. Putting the loader
  on the Provider would invert the dependency (Provider must run
  before Collector — wrong) and would require the Provider role to
  carry `INSERT/UPDATE` on `source`, breaking the grant matrix.
- **Validate the file via a JSON Schema document
  (`bootstrap-sources.schema.json`) rather than ad-hoc parser
  validation.** Rejected for v1: the schema is small (six fields,
  one per-kind variant), the per-kind config rule is hard to
  express in pure JSON Schema, and adding a Schema validator
  dependency is more than the ticket warrants. The parser's
  inline validation covers the same surface in less code. v2
  candidate.
- **Use Quarkus's `@Scheduled(every = "...")` to re-poll the file
  for changes.** Rejected: per
  `docs/spec/architecture.md` §Ingest SPIs Source identity,
  "config mutation in v1 is restart-only" — operators reload
  bootstrap config by restarting the Collector. A polling
  reloader would create concurrent-write races against
  `/add-source` and would violate the spec commitment.
- **Make the loader transactional but allow partial success
  (commit per entry).** Rejected: a partial-success loader on
  failure leaves the source table in a half-loaded state with
  the audit row never written; on retry the failed entries
  re-attempt against an inconsistent ground truth. Single
  transaction is the simpler, observably-correct shape.
- **Compute the SHA-256 over a canonicalized in-memory shape
  (parsed entries) rather than the raw file bytes.** Rejected:
  the cross-host convergence comparison the operator runs against
  `bootstrap_meta.last_loaded_sha256` is "do my Collectors see the
  same file?" — a content-of-file digest answers that question
  directly. A parsed-shape digest would mask whitespace /
  comment / ordering differences that operators sometimes care
  about (the file is the source of truth, not the parsed form).
- **Co-author `infochat-ssrf` in this group (Option B, three
  tickets).** Considered and rejected: the operator's authoring
  handoff defaults to two tickets and the bootstrap loader makes
  no outbound network calls, so deferring `infochat-ssrf` to its
  own ticket has no effect on this ticket's correctness.
  See Implementation notes — the carve-out is documented inline.
