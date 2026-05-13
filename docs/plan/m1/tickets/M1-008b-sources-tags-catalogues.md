---
id: M1-008b
title: Sources and tags catalogues (§2.2.1, §2.2.2)
status: done
created: 2026-05-13
last_updated: 2026-05-14
clarity_check:
  date: 2026-05-14
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-05-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 368
      removed: 13
blocked_by:
  - M1-005
  - M1-006
  - M1-017
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration/V6__sources_tags.sql
  - infochat-core/src/test/java/io/infochat/core/schema/SourceTableTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/TagTableTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java (the M1-008 umbrella's cross-cutting per-(user, scope) isolation IT — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention; this subticket asserts per-row schema constraints on the source and tag catalogues only)
  - any change under infochat-core/src/main/resources/db/migration/V5__*.sql or V7__*.sql (the identity/audit schema lives in M1-008a's V5 and the joins/post schema lives in M1-008c's V7; this subticket adds V6 only)
  - any modification to V1..V5 migrations already on disk or authored by M1-008a (those are frozen)
  - any Java entity class, repository, service, DAO, or bootstrap-loader code (NO application code — Flyway migration + per-table GRANTs + SQL-level smoke tests ONLY; the bootstrap-sources loader and the Tag normalization helper land in later T1-B tickets)
  - any RSS / Bluesky / Nostr / Reddit / YouTube Fetcher implementation (each fetcher impl is its own T1-B ticket binding to the M1-007a Fetcher SPI; the schema commitment here is the source row those impls will read at scheduling time)
  - any /add-source, /remove-source, /list-sources, /source-enable, /source-disable, /follow-tag, /unfollow-tag command handler (Provider-side command handlers are later T1-F tickets)
  - any tag normalization helper, NFC/Locale.ROOT lower-case path, or character-class regex validation in Java (the schema CHECK constraint enforces the regex at the storage layer; the application-side normalizer per docs/spec/schema.md §Sources and tags — Tag — Stored form is a separate Java helper that lands with the first ingest path that writes tags)
  - any pgvector / embedding / cross-source linking surface (those are T1-D's territory)
  - any retention / pruner / vocabulary GC against the tag table (per docs/spec/schema.md §Sources and tags — Vocabulary lifecycle, the v1 commitment is append-only; v2 candidate /vocab prune)
  - any change to infochat-core/pom.xml (the test-scope Testcontainers + Flyway + Postgres deps and the maven-failsafe-plugin wiring were authored by M1-008a; reusing them is the explicit design and re-touching the POM here would be redundant scope drift)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V6__sources_tags.sql exists and contains CREATE TABLE statements for source and tag (grep -E 'CREATE TABLE\\s+(source|tag)\\b' V6 returns exactly two matches)"
  - "V6 declares the (kind, identifier) UNIQUE on source per decision D38 — grep -E 'UNIQUE\\s*\\(\\s*kind\\s*,\\s*identifier\\s*\\)' returns at least one match"
  - "V6 declares the status CHECK constraint on source over the three-value set per docs/spec/schema.md §Sources and tags — grep -E \"status\\s+IN\\s*\\(\\s*'active'\\s*,\\s*'failed'\\s*,\\s*'disabled'\\s*\\)\" returns at least one match"
  - "V6 declares the soft-delete column source.deleted_at (Invariant 4) — grep -E 'deleted_at\\s+TIMESTAMPTZ' returns at least one match in V6"
  - "V6 declares the partial activity index on source — grep -E 'CREATE INDEX\\s+\\w+\\s+ON\\s+source\\s*\\(\\s*status\\s*\\)\\s+WHERE\\s+deleted_at\\s+IS\\s+NULL' returns at least one match"
  - "V6 declares the tag.name UNIQUE and the normalized-form CHECK constraint per docs/spec/schema.md §Sources and tags — Tag — grep -E 'UNIQUE.*name' returns at least one match AND grep -E \"name\\s+~\\s+'\\^\\[a-z0-9\\]\\[a-z0-9-\\]\\{0,47\\}\\$'\" returns at least one match"
  - "V6 declares tag.source_origin CHECK over the two-value set — grep -E \"source_origin\\s+IN\\s*\\(\\s*'bootstrap'\\s*,\\s*'user'\\s*\\)\" returns at least one match"
  - "V6 grants are aligned with docs/design/04-security.md §infochat_collector / §infochat_provider — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s*,\\s*UPDATE\\s+ON\\s+source\\s+TO\\s+infochat_collector' returns at least one match (Collector writes source via the bootstrap loader's INSERT path and the fetcher's UPDATE path) AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+source\\s+TO\\s+infochat_provider' returns at least one match (Provider reads source for /list-sources and join queries); the same paired grants exist for tag (Collector: SELECT/INSERT/UPDATE; Provider: SELECT)"
  - "V6 revokes DELETE on source from both service roles (Invariant 4) — grep -E 'REVOKE\\s+DELETE\\s+ON\\s+source\\s+FROM\\s+(infochat_collector|infochat_provider|PUBLIC)' returns at least one match"
  - "SourceTableTest.java exercises the (kind, identifier) UNIQUE constraint: a second INSERT with the same (kind, identifier) pair raises a unique-violation SQLException; the soft-delete column round-trips (an UPDATE that sets deleted_at = now() then a SELECT returns a non-null timestamp); the status CHECK rejects an unknown status value with a CHECK-violation SQLException"
  - "TagTableTest.java exercises the tag.name regex CHECK: inserting `'Hello'` (uppercase H) raises a CHECK-violation SQLException; inserting `'-leading'` (leading hyphen) raises a CHECK-violation SQLException; inserting a 49-character name raises a CHECK-violation SQLException; a valid name (e.g., `'news'`) inserts cleanly; the source_origin CHECK rejects a third value (e.g., `'imported'`) with a CHECK-violation"
  - "Both new *Test.java classes extend or otherwise reuse the PostgresSchemaTestBase helper authored in M1-008a (grep -E 'PostgresSchemaTestBase' returns at least one match in each test file) — no new Testcontainers / Flyway boot logic is added here"
  - "mvn -B -pl infochat-core -am test exits 0; surefire reports for infochat-core show at least one test executed per the two new test classes (grep -rE 'Tests run: [1-9]' infochat-core/target/surefire-reports returns at least two new matches across SourceTableTest and TagTableTest)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003, M1-007, M1-007a/b/c, and M1-008a tests continue to pass alongside the new V6 schema"
test_plan:
  adds:
    - infochat-core/src/test/java/io/infochat/core/schema/SourceTableTest.java (unique-key + soft-delete + status-CHECK assertions over the source table)
    - infochat-core/src/test/java/io/infochat/core/schema/TagTableTest.java (normalized-form CHECK + source_origin CHECK assertions over the tag table)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - all M1-008a *Test.java classes (the V5 trigger and audit-log tests)
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §DB roles
  - docs/design/02-schema.md §2.2 Sources & tags
  - docs/design/02-schema.md §2.2.1 source
  - docs/design/02-schema.md §2.2.2 tag
decision_refs:
  - D5
  - D7
  - D38
  - D42
---

# M1-008b: Sources and tags catalogues (§2.2.1, §2.2.2)

## Context

Second subticket of the M1-008 umbrella (per `docs/process/workflow.md`
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-008a landed the `§2.1 Identity & access` slice. This subticket
lands the `§2.2.1 source` and `§2.2.2 tag` catalogue tables: the
global per-feed source row (keyed by `(kind, identifier)` per
decision D38, soft-deletable per Invariant 4, with the three-state
status machine `active | failed | disabled`), and the Tier-1
controlled-vocabulary `tag` row (decision D5) whose `name` column
is constrained at the schema layer to the normalized form per
`docs/spec/schema.md` §Sources and tags — Tag — Stored form.
M1-008c will add the per-scope join tables and the partitioned
`post` table; the M1-008 umbrella's per-(user, scope) isolation IT
exercises all three slices end-to-end.

These two tables are **catalogue** tables: they hold global, shared
rows that every scope reads from and only privileged paths
(bootstrap loader, `/add-source --tags`, `/source-enable`,
`/source-disable`) write to. There is no per-user state here; the
per-(user, scope) isolation invariant does not apply to source or
tag rows. As a consequence this subticket is `security_relevant:
false` — no authorization surface and no user-content rows
(`security_relevant: true` is reserved for tickets touching
identity, audit, or content carrying user prose).

This is a **schema-only** ticket. No bootstrap-loader code, no
`/add-source` handler, no tag-normalization helper, no Fetcher
impl. Those land in T1-B (bootstrap sources + RSS Fetcher) and
T1-F (first commands). The schema commitment here is the table
shape the bootstrap loader and Tier-1 commands will write to.

## Definition of Done

- A new Flyway migration
  `infochat-core/src/main/resources/db/migration/V6__sources_tags.sql`
  creates, in one transactional migration:
  - `source` per `docs/design/02-schema.md` §2.2.1, including:
    - `id UUID PK`, `kind TEXT NOT NULL`, `identifier TEXT NOT
      NULL`, `config JSONB NOT NULL DEFAULT '{}'`, `display_name
      TEXT NOT NULL`, `category TEXT NOT NULL`, `bootstrap_tags
      TEXT[] NOT NULL DEFAULT '{}'`.
    - `status TEXT NOT NULL DEFAULT 'active'` with the closed-set
      CHECK `('active','failed','disabled')` per the spec status
      machine.
    - `added_by UUID REFERENCES users(id) ON DELETE SET NULL`,
      `added_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
      `last_fetch_at TIMESTAMPTZ`, `last_success_at TIMESTAMPTZ`,
      `consecutive_failures INT NOT NULL DEFAULT 0` (decision
      D42's per-source failure-counter model).
    - Soft-delete columns: `deleted_at TIMESTAMPTZ`,
      `deleted_by UUID REFERENCES users(id) ON DELETE SET NULL`
      (Invariant 4: `source` is never hard-deleted).
    - `UNIQUE (kind, identifier)` per decision D38 — the upsert
      key the bootstrap loader and `/add-source` both target.
    - Partial activity index
      `CREATE INDEX idx_source_status ON source(status) WHERE
      deleted_at IS NULL` — the index the fetcher / stream
      worker scheduler uses to select active rows.
  - `tag` per `docs/design/02-schema.md` §2.2.2, including:
    - `id UUID PK`, `name TEXT NOT NULL UNIQUE` (the
      normalized form), `display TEXT NOT NULL` (the original
      casing for output).
    - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
      `created_by UUID REFERENCES users(id)`.
    - `source_origin TEXT NOT NULL DEFAULT 'bootstrap'` with the
      closed-set CHECK `('bootstrap','user')` — distinguishes
      vocabulary entries seeded by the bootstrap loader from
      those introduced by `/add-source --tags`.
    - The normalized-form CHECK:
      `CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,47}$')` — enforces
      the storage shape from `docs/spec/schema.md` §Sources and
      tags — Tag — Stored form at the schema layer. Two values
      that hash differently before normalization (NFC/NFKC
      homoglyph variants, mixed case) cannot both occupy a
      tag row because the application-tier normalizer collapses
      them before INSERT; this CHECK is the second line of
      defense against an unnormalized write.
  - **Per-table GRANTs** aligned with `docs/spec/security.md` §DB
    roles, as enumerated in `docs/design/04-security.md` §`infochat_collector`
    (lines 625-626) and §`infochat_provider` (lines 633-634). The grants
    must reflect that:
    - `source` is **Collector-write**: `GRANT SELECT, INSERT, UPDATE
      ON source TO infochat_collector` — the Collector runs the
      bootstrap loader's idempotent upsert path (INSERT) per
      `docs/spec/deployment.md` §Bootstrap behavior, and the fetcher
      writes `status`, `last_fetch_at`, `last_success_at`,
      `consecutive_failures` on every polling cycle (UPDATE) per
      decision D42. The Provider gets `GRANT SELECT ON source TO
      infochat_provider` only — read-side of joins for command
      handlers (`/list-sources`, `/get-sources`); Provider-side
      writes to `source` from `/add-source`, `/remove-source`,
      `/source-enable`, `/source-disable` route through a future
      handoff path (stored procedure, NOTIFY-based request to
      Collector, or a later spec-amend) and are NOT in scope for
      this ticket — the schema commitment is the GRANT shape
      enumerated above.
    - `tag` is **Collector-write**: `GRANT SELECT, INSERT, UPDATE
      ON tag TO infochat_collector` — `tag` is listed as an
      ingest-owned table in `docs/design/04-security.md` line 625;
      the tagger writes new vocabulary entries during ingest, and
      the bootstrap loader seeds the initial vocabulary on
      Collector startup. The Provider gets `GRANT SELECT ON tag
      TO infochat_provider` only — read-side for command handlers
      (`/get-tags`, `/follow-tag` validation); like `source`, any
      Provider-initiated writes to `tag` (e.g. `/add-source
      --tags` minting a new vocabulary entry) route through a
      future handoff path and are out of scope here.
    - `DELETE ON source` is **REVOKEd** from both service roles
      (Invariant 4 — soft-delete only; `infochat_admin` is the
      sole DELETE path).
    - Neither table is reachable by `infochat_listen` (its role
      is LISTEN/NOTIFY only).
- Two new SQL-level test classes under
  `infochat-core/src/test/java/io/infochat/core/schema/`:
  - `SourceTableTest.java` — exercises (a) the
    `(kind, identifier)` UNIQUE constraint (a second insert of
    `('rss', 'https://example.com/feed')` raises a
    unique-violation SQLException), (b) the soft-delete column
    round-trips (a row with `deleted_at = now()` is selectable
    and the timestamp is non-null), (c) the `status` CHECK
    rejects an unknown value (e.g., `'paused'`) with a
    CHECK-violation SQLException, (d) the partial activity
    index exists (read `pg_indexes` and assert the index DDL is
    present).
  - `TagTableTest.java` — exercises the `name` CHECK across
    representative invalid inputs: uppercase (`'News'`),
    leading hyphen (`'-news'`), too-long (49 characters),
    special characters (`'news!'`, `'news space'`). A valid
    name (`'news'`) inserts cleanly. The `source_origin`
    CHECK rejects an out-of-set value (`'imported'`) with a
    CHECK-violation. Note: trailing hyphens (e.g. `'news-'`)
    are PERMITTED by the regex `^[a-z0-9][a-z0-9-]{0,47}$`
    because the second character class `[a-z0-9-]` includes
    `-` with no terminal-alphanumeric anchor. This deviates
    from the prose at `docs/spec/commands.md` §Surface
    conventions ("internal hyphens"); the prose-vs-regex gap
    is a separate spec-quality concern out of scope here.
- `mvn -B clean verify` from the repo root exits 0. All prior
  tests (M1-003, M1-007, M1-007a/b/c, M1-008a) continue to pass;
  the two new test classes execute against the Testcontainers
  Postgres provisioned by M1-008a's `PostgresSchemaTestBase` and
  pass.

## Implementation notes

- **One migration file, one transaction.** Like V5 in M1-008a, V6
  is a single Flyway migration applying atomically. No
  V6a/V6b split.
- **Migration version is V6.** M1-008a's V5 lands first; M1-008c
  takes V7. The dependency graph in this subticket's
  `blocked_by` does NOT include M1-008a because the two
  migrations are independent at the SQL layer (V6 does not
  reference V5's tables except via the optional `added_by`,
  `deleted_by`, `created_by` FKs to `users(id)`). If
  M1-008a hasn't merged before this subticket runs, those FKs
  fail to resolve and the migration errors at apply time. The
  `blocked_by` lists M1-005 / M1-006 / M1-017 (the migration
  infrastructure) but not M1-008a; however the M1-008 umbrella
  blocks on all three subtickets, so execution-order safety is
  enforced at the umbrella level. **Practical note: if you
  start M1-008b before M1-008a is done, the FK references will
  fail at Flyway-apply time** — wait for M1-008a's commit, or
  develop M1-008b in isolation and merge in order. Either is
  fine; the schema-level dependency is real but the ticket-
  level `blocked_by` deliberately mirrors the dependency graph
  per `docs/process/workflow.md` (subtickets of the same
  umbrella are not required to block on each other since the
  umbrella blocks on all of them).
- **The status CHECK is the schema-level commitment.** The
  application layer is responsible for the state transitions
  per `docs/spec/schema.md` §Sources and tags — Status state
  machine (`active → failed` by the worker, `failed → active`
  by `/source-enable`, etc.); the schema's job is to reject
  out-of-set values. Encoding the transitions in a TRIGGER
  would be over-engineering for v1; the transition logic is
  application-tier in design.
- **Tag normalization is application-tier, but the schema
  validates the result.** The Java-side normalizer (NFC,
  `Locale.ROOT.toLowerCase()`, character-class filter) lives
  with the first ingest path that writes tags (T1-D's tagger,
  most likely). The CHECK constraint here is the second line
  of defense: even if a future code path forgets to call the
  normalizer, the INSERT fails at the storage layer rather
  than silently corrupting the vocabulary.
- **`bootstrap_tags` is `TEXT[]`, not a join table.** Decision
  D22 (tagger fallback): when the LLM tagger fails after
  retries, the post is tagged with the source's
  `bootstrap_tags`. A scalar array is the simpler shape for
  this since the only operations on it are read-the-whole-set
  (the fallback path) and update-via-replace (`/add-source
  --tags` and the bootstrap loader). No join table is needed.
  The `tag.name` regex constraint applies element-wise via the
  application-tier normalizer; the schema does NOT validate
  array elements against the regex (Postgres doesn't support
  element-level CHECK constraints on arrays in v1, and adding
  a per-element trigger would slow every UPDATE). The
  invariant that `bootstrap_tags` elements are normalized
  values is application-tier.
- **`source_origin` is a closed two-value enum, but stored as
  TEXT with CHECK.** Same rationale as M1-008a's audit_log
  verb set: TEXT-with-CHECK is faster to extend in v2 than a
  Postgres ENUM type. Today the closed set is
  `('bootstrap','user')`; if v2 adds a `'plugin'` origin, the
  migration is a one-line `ALTER`.
- **The bootstrap loader writes source rows during Collector
  startup.** Per `docs/spec/deployment.md` §Bootstrap behavior
  and decision D38, the loader is idempotent: it upserts by
  `(kind, identifier)`. The UNIQUE constraint here is what
  makes the upsert race-safe — concurrent loader starts can
  both attempt the INSERT, exactly one wins, and the loser
  falls back to the UPDATE path. The loader code itself
  lands in T1-B; this subticket's schema is what it writes
  to.
- **No NOTIFY trigger on `source`.** The `/add-source` happy path
  does not need a wake-up signal — the fetcher's scheduler
  polls the source table on its own cadence (T1-B). If a
  later ticket wires a `source_changed` NOTIFY channel, the
  trigger is additive on top of this schema.
- **Reuse `PostgresSchemaTestBase` from M1-008a.** The base
  class spins up the Testcontainers Postgres once per JVM,
  applies Flyway migrations, and provides a Connection
  factory. SourceTableTest and TagTableTest extend (or
  delegate to) it; they do not re-spin their own container.
  This keeps the test wall-clock fast.

## Big-picture notes

- **Source rows are global state per decision D7.** A row added
  via `/add-source` is visible to every group the bot is in,
  not just the calling scope. This is the design — feed
  curation is a deployment-wide concern, not a per-scope one —
  and it is documented for users in `docs/spec/security.md`
  §Source URL visibility. The schema reflects this: `source`
  has no scope discriminator. The per-scope subscription join
  lives in M1-008c (`source_subscription`).
- **Soft-delete is the only delete path for sources** (Invariant
  4). The Provider role does not carry `DELETE` on `source`;
  only the Admin role does, and the operator-side path is the
  escape hatch documented in `docs/spec/security.md` §What's
  intentionally NOT in v1 — "Boundless growth of soft-deleted
  source rows." This subticket's GRANT block must `REVOKE
  DELETE` from both service roles. M1-006 created the roles
  but did not bind per-table privileges; M1-008b binds the
  source-specific grants.
- **The tag vocabulary is append-only in v1.** Per
  `docs/spec/schema.md` §Sources and tags — Vocabulary
  lifecycle, nothing removes a tag row in v1. `/follow-tag`
  on a tag whose only contributing source was long ago
  removed produces no posts at digest time (the digest query
  intersects the vocabulary against subscribed-source
  `bootstrap_tags`, so a stale vocabulary row with no current
  contributor matches no posts). This subticket's schema
  intentionally adds no DELETE trigger on `tag`; v2's
  `/vocab prune` would add one or use the Admin role.
- **The `tag` table's normalization CHECK is the spec
  realization of the post-normalization Stored form.** Spec
  §Tag — Stored form names the regex
  `^[a-z0-9][a-z0-9-]{0,47}$` and the NFC + lower-case
  normalization steps. The schema CHECK enforces the regex;
  the application-side normalizer (T1-D / T1-F) does the
  NFC + lower-case pass before INSERT. Together they uphold
  the "two values that hash differently before normalization
  collapse to a single row" property the spec requires.
- **Subticket isolation against M1-008a and M1-008c.** V6 does
  not reference V5's `audit_log`, `invite_code`, or trigger
  functions, and does not reference V7's `source_subscription`,
  `scope_tag`, `scope_preferences`, or `post` tables. The
  test files in this subticket exercise V6 surfaces only;
  they do not seed audit rows or per-scope joins. The
  `files_scope` lists are disjoint with M1-008a and M1-008c.
- **Sources are what later T1-B tickets bind to.** The
  bootstrap-sources loader, the RSS Fetcher impl, and the
  stream-source (Bluesky / Nostr) impls all start with a
  `source` row and a `Fetcher` SPI (M1-007a). This subticket
  is the schema half of that handshake.

## Out-of-scope expansion

- **The M1-008 umbrella's per-(user, scope) isolation IT
  (`infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java`)**
  is reserved for the umbrella commit per the umbrella +
  subticket idiom. This subticket's tests assert per-row
  schema constraints on the catalogue tables; cross-table
  isolation is the umbrella's verification surface.
- **The V5 identity/audit schema and the V7 joins/post
  schema.** M1-008a owns V5; M1-008c owns V7. This subticket
  adds V6 only and does not modify V1..V5.
- **Java entity classes / repositories / services / DAOs.**
  None. The schema is the commitment; the application layer
  binds in later tickets.
- **The bootstrap-sources loader (`bootstrap-sources.json`
  parsing, the idempotent upsert).** T1-B's territory. The
  schema's UNIQUE constraint here is what makes the loader's
  upsert race-safe; the loader code itself is later.
- **Concrete Fetcher implementations.** Each fetcher
  (RssFetcher, BlueskyFetcher, NostrStreamSource, etc.) is
  its own T1-B / T1-C ticket. The schema commitment is the
  `source` row those fetchers will read at scheduling time.
- **`/add-source`, `/remove-source`, `/list-sources`,
  `/source-enable`, `/source-disable` command handlers.**
  Provider-side handlers; later T1-F tickets.
- **`/follow-tag`, `/unfollow-tag` command handlers and the
  `scope_tag` join table.** The handlers are T1-F; the
  `scope_tag` table itself is M1-008c.
- **The Java-side tag normalizer.** Lands with the first
  ingest path that writes tags (T1-D's tagger pipeline). The
  schema CHECK here is the storage-layer enforcement; the
  Java helper that does NFC + Locale.ROOT lower-case lives
  separately.
- **Retention / pruner / vocabulary GC against the tag
  table.** Per spec, append-only in v1. v2 candidate
  (`/vocab prune`) — not in M1.
- **Modifications to `infochat-core/pom.xml`.** M1-008a
  authored the test-scope Testcontainers + Postgres + Flyway
  deps plus the maven-failsafe-plugin wiring. This
  subticket reuses those; re-touching the POM here would be
  redundant scope drift.

## Authorized test changes

- (none — this subticket adds two new test files in
  `infochat-core` and modifies no pre-existing tests. All
  prior tests continue to pass unchanged.)

## Alternatives considered

- **Move tag normalization into a Postgres trigger.** Rejected:
  the normalization step is NFC + `Locale.ROOT.toLowerCase()`
  + a character-class filter. Postgres has `lower()` but it
  is locale-sensitive, and there is no portable NFC
  primitive without the `unaccent` extension or a plpgsql
  helper. Keeping normalization in Java (one canonical
  helper, one canonical character set) avoids a per-row
  trigger overhead and keeps the normalization rules
  reviewable in one place. The schema CHECK is the
  belt-and-suspenders that catches a missed normalizer call.
- **Replace `bootstrap_tags TEXT[]` with a `source_tag` join
  table.** Rejected: the only operations are read-the-whole-
  set and update-via-replace; a join table multiplies the
  per-source overhead without any query benefit. Element-
  level normalization is application-tier either way.
- **Add a `CHECK` on each `bootstrap_tags` element via a
  custom domain or DDL trigger.** Rejected: Postgres v1
  doesn't support per-array-element CHECK natively, and a
  per-row trigger that iterates the array on every UPDATE
  would be slow for sources with many fallback tags. The
  application-tier normalizer call is the v1 commitment.
- **Encode the `source.status` state machine as a per-row
  trigger that rejects illegal transitions.** Rejected: the
  state transitions are not fully expressible in a single
  trigger (some transitions are command-driven, some are
  worker-driven; the trigger would need to inspect
  `current_setting('infochat.actor')` or similar). The
  schema CHECK rejects out-of-set values; the state-machine
  enforcement is application-tier.
- **Use a Postgres ENUM type for `source.status` and
  `tag.source_origin`.** Rejected for the same reason
  M1-008a rejects ENUMs for `audit_log.action`: extending an
  ENUM requires a migration; TEXT + CHECK is faster to
  extend in v2.
- **Put `tag.display` and `tag.name` into one column with a
  case-insensitive collation.** Rejected: case-insensitive
  collations don't exist by default in Postgres (`citext`
  requires the extension) and even with `citext` the
  display-form casing is lost on INSERT. Two columns —
  normalized `name` for lookup, original `display` for
  output — keep both properties cheap.
