---
id: M1-008c
title: Joins, scope preferences, posts (§2.2.3..§2.2.5 + §2.3)
status: done
created: 2026-05-13
last_updated: 2026-05-14
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
      spec_conformance: PASS
    diff_stats:
      files: 8
      added: 1141
      removed: 11
clarity_check:
  date: 2026-05-14
  verdict: PASS
  warnings: []
  blockers: []
escalations:
  - date: 2026-05-14
    reason: premise-fail
    reviewer_verdict_excerpt: |
      Pre-implementation developer-flagged inconsistency between an
      acceptance grep and the Definition of Done:

      Acceptance item 3 expects
        grep -E 'PRIMARY KEY\s*\(\s*scope_kind\s*,\s*scope_id\s*,'
      to return "at least three matches across the three join tables".

      The regex requires a comma after `scope_id`, i.e. a third PK
      column. The Definition of Done specifies scope_preferences PK
      as `(scope_kind, scope_id)` — a two-column PK that does NOT
      match this regex. Only source_subscription's
      `(scope_kind, scope_id, source_id)` and scope_tag's
      `(scope_kind, scope_id, tag_id)` produce matches, for a total
      of 2 — never 3.

      docs/design/02-schema.md §2.2.5 confirms scope_preferences PK
      is two-column. The grep cannot be satisfied without violating
      the canonical schema. Fix: either weaken the count to "at
      least two", or relax the regex to allow `[,)]` after scope_id
      so the two-column PK on scope_preferences also matches.
revisions:
  - date: 2026-05-14
    reason: premise-fail
    notes: |
      Acceptance item 3 was an aggregate-count assertion that
      conflicted with the Definition of Done.

      OLD form (aggregate-count across heterogeneous tables;
      unsatisfiable against DoD):
        "Every per-scope join carries a (scope_kind, scope_id, *)
         primary key — grep -E 'PRIMARY KEY\s*\(\s*scope_kind\s*,
         \s*scope_id\s*,' returns at least three matches across the
         three join tables (this is Invariant 1's schema-level
         enforcement — no per-scope row can exist without both
         discriminator and id)"

      Problem: the regex requires a comma after `scope_id` (i.e.,
      a third PK column). The DoD specifies scope_preferences PK
      as (scope_kind, scope_id) — 2 columns, no trailing comma.
      The grep can return at most 2 matches given the DoD; "at
      least 3" was unsatisfiable.

      NEW form (per-element pattern; three independently falsifiable
      assertions, each pinning one table's exact PK shape; see items
      3a/3b/3c in the acceptance list below):
        - source_subscription PK (scope_kind, scope_id, source_id)
        - scope_tag           PK (scope_kind, scope_id, tag_id)
        - scope_preferences   PK (scope_kind, scope_id)

      The per-element pattern is the recommended structural form per
      docs/process/ticket-template.md §acceptance authoring rule and
      docs/process/clarity-prompt.md §10 ACCEPTANCE-VS-DOD-CONSISTENT
      (both landed in commit 6d9cd4f on main as upstream prevention
      for the bug class this ticket surfaced). The refine swaps the
      aggregate item for the three per-element items in place; no
      other acceptance criteria change.
blocked_by:
  - M1-008a
  - M1-008b
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration/V7__joins_post.sql
  - infochat-core/src/test/java/io/infochat/core/schema/SourceSubscriptionTableTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/ScopeTagTableTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/ScopePreferencesTableTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/PostPartitioningTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/SoftDeletedSourceFkTest.java
complexity: high
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java (the M1-008 umbrella's cross-cutting per-(user, scope) isolation IT — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention; this subticket's tests cover per-row schema constraints, the cross-table invariant lives in the umbrella)
  - any change under infochat-core/src/main/resources/db/migration/V5__*.sql or V6__*.sql (the identity/audit schema is M1-008a's V5; the catalogue schema is M1-008b's V6; this subticket adds V7 only and does NOT modify V5 or V6)
  - any modification to V1..V6 migrations already on disk (those are frozen)
  - any partition pruner, retention sweep, scheduled DROP-PARTITION job (Invariant 6 commits to TTL-by-partition-drop but the actual pruner schedule is design-tier and lives in a later T1-D / T1-E ticket; this subticket creates the parent table + ONE initial partition, not the cadence)
  - any LISTEN/NOTIFY trigger on post or post.ready_at (the new_post NOTIFY channel and its trigger live in T1-C; this subticket's post table carries no NOTIFY trigger, only the ready_at column the trigger will later read)
  - any post_reference (cross-source link graph), post_embedding (pgvector), or quarantine table (those are T1-D's territory — Stage 2 evaluator + tagger + embedding pipeline + quarantine workflow)
  - any pgvector extension installation or vector column (Invariant 6 mentions post_embedding alongside post in the partitioned-derivatives list, but the embedding table is T1-D's territory; this subticket's post table has no embedding column)
  - any Java entity class, repository, service, DAO, or Fetcher impl (NO application code — Flyway migration + per-table GRANTs + SQL-level smoke tests ONLY)
  - any /summary, /saved, /save, /unsave, /follow-tag, /unfollow-tag, /add-source, /remove-source command handler (Provider-side command handlers are later T1-F tickets)
  - any saved_post table (decision D13's per-user-global save lives in a separate T1-D ticket alongside the rest of §2.6 per-user state; this subticket lands the per-scope state in §2.2.3..§2.2.5 and the post table in §2.3.1)
  - any chat_memory, chat_session, summary_anchor table (those are T1-D/T1-F's territory — chat agent persistence)
  - any change to infochat-core/pom.xml (the test-scope Testcontainers + Flyway + Postgres deps and the maven-failsafe-plugin wiring were authored by M1-008a; reusing them is the explicit design)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V7__joins_post.sql exists and contains CREATE TABLE statements for source_subscription, scope_tag, scope_preferences, and post (grep -E 'CREATE TABLE\\s+(source_subscription|scope_tag|scope_preferences|post)\\b' V7 returns exactly four matches)"
  - "Every join-table row carries a scope discriminator: source_subscription, scope_tag, and scope_preferences each declare scope_kind TEXT NOT NULL with a CHECK constraint over ('dm','group') — grep -E \"scope_kind\\s+TEXT\\s+NOT\\s+NULL\" returns at least three matches AND grep -E \"scope_kind\\s+IN\\s*\\(\\s*'dm'\\s*,\\s*'group'\\s*\\)\" returns at least three matches"
  - "source_subscription enforces Invariant 1 with PRIMARY KEY (scope_kind, scope_id, source_id) — grep -E 'PRIMARY KEY\\s*\\(\\s*scope_kind\\s*,\\s*scope_id\\s*,\\s*source_id\\s*\\)' returns at least one match in V7 (Invariant 1 schema-level enforcement plus dedup of the (scope, source) pair)"
  - "scope_tag enforces Invariant 1 with PRIMARY KEY (scope_kind, scope_id, tag_id) — grep -E 'PRIMARY KEY\\s*\\(\\s*scope_kind\\s*,\\s*scope_id\\s*,\\s*tag_id\\s*\\)' returns at least one match in V7 (Invariant 1 schema-level enforcement plus dedup of the (scope, tag) pair)"
  - "scope_preferences enforces Invariant 1 with PRIMARY KEY (scope_kind, scope_id) — grep -E 'PRIMARY KEY\\s*\\(\\s*scope_kind\\s*,\\s*scope_id\\s*\\)' returns at least one match in V7 (Invariant 1 schema-level enforcement; the 2-column PK is intentional — one settings row per scope, no third PK column needed, see Implementation notes)"
  - "source_subscription declares the FK to source(id) — grep -E 'REFERENCES\\s+source\\s*\\(\\s*id\\s*\\)' returns at least one match in V7 — and explicitly does NOT declare ON DELETE CASCADE on source_id (per docs/design/02-schema.md §2.2.3 — soft-delete is the only path; cascade on hard-delete is the operator's manual problem); grep -E 'source_id\\s+UUID.*REFERENCES\\s+source.*ON\\s+DELETE\\s+CASCADE' returns ZERO matches"
  - "scope_tag declares the FK to tag(id) — grep -E 'tag_id\\s+UUID\\s+NOT\\s+NULL\\s+REFERENCES\\s+tag' returns at least one match in V7"
  - "scope_preferences declares the language default ('en'), the digest_enabled default (TRUE), the tag_mode CHECK over ('ALL','EXPLICIT'), and the two BIGINT subscription-version counters — grep -E \"language\\s+TEXT\\s+NOT\\s+NULL\\s+DEFAULT\\s+'en'\" returns at least one match AND grep -E \"tag_mode\\s+IN\\s*\\(\\s*'ALL'\\s*,\\s*'EXPLICIT'\\s*\\)\" returns at least one match AND grep -cE '(tag_subscription_version|source_subscription_version)\\s+BIGINT\\s+NOT\\s+NULL\\s+DEFAULT\\s+0' returns 2"
  - "V7 declares post as a partitioned table on fetched_at per Invariant 6 — grep -E 'CREATE TABLE\\s+post\\s*\\(' returns at least one match AND grep -E 'PARTITION\\s+BY\\s+RANGE\\s*\\(\\s*fetched_at\\s*\\)' returns at least one match"
  - "V7 creates at least one initial partition of post so the schema is queryable on day one — grep -E 'CREATE TABLE\\s+post_[0-9]+\\s+PARTITION OF\\s+post' returns at least one match (the partition cadence + pruner schedule are out of scope here; one bootstrap partition is the schema-level commitment)"
  - "post declares the spec-required status CHECK over the four-value set ('RAW','READY','QUARANTINED','NEEDS_REVIEW') — grep -E \"status\\s+IN\\s*\\(\\s*'RAW'\\s*,\\s*'READY'\\s*,\\s*'QUARANTINED'\\s*,\\s*'NEEDS_REVIEW'\\s*\\)\" returns at least one match (Invariant 5: no 'EVALUATING' status — in-flight evaluation is RAW + per-stage flags)"
  - "post declares the per-stage durable cursor flags from Invariant 5 — grep -cE '(stage1_done|stage2_done|tagger_done|embedding_done|stage1_flagged|stage2_failed|tagger_fallback)\\s+BOOLEAN\\s+NOT\\s+NULL' returns 7"
  - "post declares both the (id, fetched_at) PRIMARY KEY (partition key must be part of the PK in Postgres) and the (uid, fetched_at) UNIQUE — grep -E 'PRIMARY KEY\\s*\\(\\s*id\\s*,\\s*fetched_at\\s*\\)' returns at least one match AND grep -E 'UNIQUE\\s*\\(\\s*uid\\s*,\\s*fetched_at\\s*\\)' returns at least one match"
  - "post declares the GIN index on the tags TEXT[] column — grep -E 'CREATE INDEX\\s+\\w+\\s+ON\\s+post\\s+USING\\s+gin\\s*\\(\\s*tags\\s*\\)' returns at least one match"
  - "V7 grants are aligned with docs/spec/security.md §DB roles — grep -E 'GRANT\\s+SELECT(\\s*,\\s*\\w+)*\\s+ON\\s+(source_subscription|scope_tag|scope_preferences)\\s+TO\\s+infochat_provider' returns at least three matches (Provider writes per-scope joins; one per table) AND grep -E 'GRANT\\s+SELECT(\\s*,\\s*\\w+)*\\s+ON\\s+post\\s+TO\\s+infochat_collector' returns at least one match (Collector writes posts) AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+post\\s+TO\\s+infochat_provider' returns at least one match (Provider reads posts for /summary)"
  - "SourceSubscriptionTableTest.java asserts that inserting a row without scope_kind raises a NOT-NULL violation; inserting a row with scope_kind='other' raises a CHECK violation; inserting two rows with the same (scope_kind, scope_id, source_id) raises a PK violation; the (scope_kind, scope_id) compound is part of the PK and a row CANNOT exist without both"
  - "ScopeTagTableTest.java asserts the same scope_kind discriminator constraints as SourceSubscriptionTableTest plus the FK to tag(id) (an insert with an unknown tag_id raises an FK violation)"
  - "ScopePreferencesTableTest.java asserts: the language column defaults to 'en'; tag_mode rejects an unknown value with a CHECK violation; the two BIGINT version counters default to 0 and accept monotonic UPDATEs (the counter increment pattern the application layer will use)"
  - "PostPartitioningTest.java asserts: an INSERT into post with a fetched_at falling inside the seeded partition's range succeeds AND a follow-up SELECT FROM post_<NNNN> (the partition's relation name) returns the same row (verifies the parent is correctly declared PARTITION BY RANGE and the initial partition routes); an INSERT with a fetched_at OUTSIDE every existing partition's range raises an SQLException whose message indicates no partition found (Postgres's default behavior on a partitioned table with no fallback)"
  - "SoftDeletedSourceFkTest.java asserts Invariant 4 at the schema layer: after UPDATE source SET deleted_at = now() on a source row, a subsequent INSERT INTO post (source_id = $1, …) for an in-flight ingest run STILL succeeds (the FK references source.id, which the soft-delete does NOT clear — only the application-layer scheduler's WHERE deleted_at IS NULL filter stops new fetches; in-flight writes against an already-fetched batch still resolve their FK)"
  - "Every new *Test.java extends or otherwise reuses the PostgresSchemaTestBase helper authored in M1-008a (grep -E 'PostgresSchemaTestBase' returns at least one match in each test file) — no new Testcontainers / Flyway boot logic is added here"
  - "mvn -B -pl infochat-core -am test exits 0; surefire reports for infochat-core show at least one test executed per the five new test classes (grep -rE 'Tests run: [1-9]' infochat-core/target/surefire-reports returns at least five new matches across the five test classes added by this ticket)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003, M1-007, M1-007a/b/c, M1-008a, and M1-008b tests continue to pass alongside the new V7 schema"
test_plan:
  adds:
    - infochat-core/src/test/java/io/infochat/core/schema/SourceSubscriptionTableTest.java (scope_kind NOT NULL + CHECK; PK uniqueness; FK to source)
    - infochat-core/src/test/java/io/infochat/core/schema/ScopeTagTableTest.java (scope_kind NOT NULL + CHECK; FK to tag)
    - infochat-core/src/test/java/io/infochat/core/schema/ScopePreferencesTableTest.java (language default; tag_mode CHECK; version counters default and increment)
    - infochat-core/src/test/java/io/infochat/core/schema/PostPartitioningTest.java (partition routing happy path + missing-partition error)
    - infochat-core/src/test/java/io/infochat/core/schema/SoftDeletedSourceFkTest.java (in-flight post INSERT against a soft-deleted source FK resolves; Invariant 4 at the schema layer)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - all M1-008a *Test.java classes (V5 identity/audit trigger and append-only tests)
    - all M1-008b *Test.java classes (V6 source/tag catalogue tests)
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Per-user state (scope-independent)
  - docs/spec/schema.md §Per-scope state
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §DB roles
  - docs/design/02-schema.md §2.2.3 source_subscription
  - docs/design/02-schema.md §2.2.4 scope_tag
  - docs/design/02-schema.md §2.2.5 scope_preferences
  - docs/design/02-schema.md §2.3 Posts (ingest)
  - docs/design/02-schema.md §2.3.1 post
decision_refs:
  - D5
  - D13
  - D33
  - D38
---

# M1-008c: Joins, scope preferences, posts (§2.2.3..§2.2.5 + §2.3)

## Context

Third subticket of the M1-008 umbrella (per `docs/process/workflow.md`
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-008a landed §2.1 identity/audit; M1-008b landed §2.2.1/§2.2.2
the source and tag catalogues. This subticket lands the per-scope
joins from §2.2.3..§2.2.5 (`source_subscription`, `scope_tag`,
`scope_preferences`) and the `§2.3.1 post` partitioned table that
holds every fetched + evaluated post row. The M1-008 umbrella's
per-(user, scope) isolation IT then walks the join surface
end-to-end to verify Invariant 1 across the three slices.

This is the **per-scope state** subticket. Every join table here
carries the `(scope_kind, scope_id)` discriminator that
`docs/spec/schema.md` §Invariants — Invariant 1 commits to. The
schema-level enforcement of Invariant 1 lives in the (scope_kind,
scope_id, *) primary key on each per-scope join: a row cannot
exist without both discriminator and id, so an accidental scope-
agnostic INSERT (e.g., from a buggy command handler that forgot
to thread the scope) fails at the storage layer.

This is also the post-table subticket. `post` is partitioned by
`fetched_at` per Invariant 6 (TTL by partition drop, not row
delete) and per decision D33 (profile-driven retention horizon).
The migration creates the parent table plus ONE initial partition
so the schema is queryable on day one; the partition cadence
(daily? weekly?) and the pruner schedule are design-tier and live
in a later T1-D / T1-E ticket. The schema commitment here is the
parent's `PARTITION BY RANGE (fetched_at)` declaration and one
bootstrap partition; the lifecycle around the cadence is later.

`security_relevant: true` because `post` is partitioned and
TTL-aged per Invariants 5 + 6 — getting the partition lifecycle
wrong silently leaks data past its retention horizon. The schema
commitment in this ticket is the parent's PARTITION BY and one
initial partition; the actual cadence + pruner are later, but
even the schema declaration carries security weight (a missed
PARTITION BY clause means the future pruner has no partitions
to drop, and the table grows unbounded). The threat-actor review
should look at the partition declaration and the FK behavior
under soft-delete.

This is a **schema-only** ticket. No Java entity classes, no
Fetcher impl, no `/summary` handler, no NOTIFY trigger on post.
Those land in T1-B, T1-C, T1-D, T1-E, T1-F.

## Definition of Done

- A new Flyway migration
  `infochat-core/src/main/resources/db/migration/V7__joins_post.sql`
  creates, in one transactional migration:
  - `source_subscription` per `docs/design/02-schema.md` §2.2.3:
    - `scope_kind TEXT NOT NULL` with CHECK `('dm','group')`,
      `scope_id UUID NOT NULL`,
      `source_id UUID NOT NULL REFERENCES source(id)` (FK to
      M1-008b's V6 source table; **no `ON DELETE CASCADE`** —
      soft-delete is the only path per Invariant 4).
    - `added_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
      `added_by UUID REFERENCES users(id) ON DELETE SET NULL`
      (FK to M1-008a's V5 users table).
    - `PRIMARY KEY (scope_kind, scope_id, source_id)` — Invariant
      1's schema-level enforcement plus dedup.
    - Index `idx_source_sub_source ON source_subscription(source_id)`
      for the reverse-lookup "who is subscribed to this source?"
      query the Collector's fan-out path uses.
  - `scope_tag` per `docs/design/02-schema.md` §2.2.4:
    - `scope_kind TEXT NOT NULL` with CHECK `('dm','group')`,
      `scope_id UUID NOT NULL`,
      `tag_id UUID NOT NULL REFERENCES tag(id)` (FK to M1-008b's
      V6 tag table).
    - `PRIMARY KEY (scope_kind, scope_id, tag_id)` — Invariant 1
      plus dedup on `/follow-tag`.
  - `scope_preferences` per `docs/design/02-schema.md` §2.2.5:
    - `scope_kind TEXT NOT NULL` with CHECK `('dm','group')`,
      `scope_id UUID NOT NULL`,
      `language TEXT NOT NULL DEFAULT 'en'` (ISO 639-1),
      `timezone TEXT` (per-scope override, NULL = inherit),
      `digest_enabled BOOLEAN NOT NULL DEFAULT TRUE`,
      `tag_mode TEXT NOT NULL DEFAULT 'ALL'` with CHECK
      `('ALL','EXPLICIT')` per `docs/spec/commands.md`
      §Per-scope tag preferences.
    - `tag_subscription_version BIGINT NOT NULL DEFAULT 0`,
      `source_subscription_version BIGINT NOT NULL DEFAULT 0`
      — the monotonic counters that fold into the digest cache
      key per `docs/design/02-schema.md` §2.2.5.
    - `PRIMARY KEY (scope_kind, scope_id)`.
  - `post` per `docs/design/02-schema.md` §2.3.1:
    - All spec-mandated columns: `id UUID NOT NULL DEFAULT
      gen_random_uuid()`, `uid TEXT NOT NULL`, `source_id UUID
      NOT NULL REFERENCES source(id)`, `upstream_identifier
      TEXT`, `url TEXT`, `title TEXT NOT NULL`, `body TEXT`,
      `body_summary TEXT`, `author TEXT`, `published_at
      TIMESTAMPTZ`, `fetched_at TIMESTAMPTZ NOT NULL DEFAULT
      now()`, `ready_at TIMESTAMPTZ`, `status_changed_at
      TIMESTAMPTZ NOT NULL DEFAULT now()`, `last_linked_at
      TIMESTAMPTZ`.
    - `status TEXT NOT NULL DEFAULT 'RAW'` with CHECK
      `('RAW','READY','QUARANTINED','NEEDS_REVIEW')` — Invariant
      5: NO `'EVALUATING'` status; in-flight evaluation is the
      `RAW` status plus the per-stage flag bitmap below.
    - Per-stage durable cursor flags per Invariant 5:
      `stage1_done`, `stage2_done`, `tagger_done`,
      `embedding_done`, `stage1_flagged`, `stage2_failed`,
      `tagger_fallback` — all `BOOLEAN NOT NULL DEFAULT FALSE`.
    - `tags TEXT[] NOT NULL DEFAULT '{}'` — inline tag array
      (the Tier-1 vocabulary names that apply to this post;
      the application-tier tagger validates each element
      against the `tag` catalogue at INSERT time).
    - `social_score INT`, `likes INT`, `reposts INT` for the
      Tier-2 surfacing path (used later by T1-D's tagger and
      surfacing logic; the columns exist so the schema is
      future-shape ready).
    - `PRIMARY KEY (id, fetched_at)` — Postgres requires the
      partition key to be part of the PK on a partitioned
      table.
    - `UNIQUE (uid, fetched_at)` — per-window dedup (cross-
      window dedup is the fetcher's responsibility per
      `docs/spec/schema.md` §UID derivation).
    - `UNIQUE (source_id, upstream_identifier, fetched_at)` —
      belt-and-suspenders for sources that supply stable
      upstream identifiers.
    - `PARTITION BY RANGE (fetched_at)` — Invariant 6.
  - **At least one initial partition** of `post`:
    `CREATE TABLE post_YYYYMM PARTITION OF post FOR VALUES
    FROM (...) TO (...)` — a single bootstrap partition covering
    the current month (or a sufficient range — the exact cadence
    is out of scope; the schema-level commitment is "at least
    one queryable partition exists on day one"). The cadence
    decision lives in a later T1 ticket that wires the
    partition-creation scheduler.
  - The full index set per `docs/design/02-schema.md` §2.3.1:
    `idx_post_status_fetched`, `idx_post_source`,
    `idx_post_published`, `idx_post_ready_at` (partial WHERE
    `status = 'READY'`), `idx_post_link_cursor` (partial),
    `idx_post_tags_gin` (GIN on `tags TEXT[]`),
    `idx_post_status_changed` (partial WHERE `status =
    'NEEDS_REVIEW'`).
  - **Per-table GRANTs** aligned with `docs/spec/security.md` §DB
    roles:
    - `source_subscription`, `scope_tag`, `scope_preferences`:
      `SELECT, INSERT, UPDATE, DELETE` to `infochat_provider`
      (Provider writes user-state mutations on `/add-source`,
      `/remove-source`, `/follow-tag`, `/unfollow-tag`,
      `/lang`, etc.); `SELECT` to `infochat_collector` (read-
      only — the Collector reads `source_subscription` to fan
      out posts on ingest).
    - `post`: `SELECT, INSERT, UPDATE` to `infochat_collector`
      (Collector writes posts and transitions their status
      through the eval pipeline). `SELECT` to
      `infochat_provider` (Provider reads posts to render
      `/summary` and `/saved`). Neither role gets `DELETE` on
      `post` — Invariant 6 commits TTL to partition drop, not
      row delete. The partition-drop privilege belongs to
      `infochat_admin` (or to a future scheduled-job role; out
      of scope here).
    - Neither table is reachable by `infochat_listen` (its
      role is LISTEN/NOTIFY only).
- Five new SQL-level test classes under
  `infochat-core/src/test/java/io/infochat/core/schema/`:
  - `SourceSubscriptionTableTest.java` — scope_kind NOT NULL +
    CHECK; PK uniqueness; FK to source; soft-deleted-source
    interaction (covered also by `SoftDeletedSourceFkTest`).
  - `ScopeTagTableTest.java` — scope_kind NOT NULL + CHECK; FK
    to tag (unknown `tag_id` raises FK violation).
  - `ScopePreferencesTableTest.java` — language default; tag_mode
    CHECK; subscription-version counters default to 0 and
    accept monotonic UPDATEs.
  - `PostPartitioningTest.java` — INSERT into post routes to the
    initial partition (verify by reading the partition table
    directly via its name); INSERT outside every partition's
    range raises a "no partition" SQLException.
  - `SoftDeletedSourceFkTest.java` — Invariant 4 at the schema
    layer: after UPDATE source SET deleted_at = now(), a
    subsequent INSERT INTO post (source_id = ..., ...) for an
    in-flight ingest run STILL succeeds (the FK references
    source.id and the soft-delete does not clear the id).
- `mvn -B clean verify` from the repo root exits 0. All M1-003,
  M1-007, M1-007a/b/c, M1-008a, M1-008b tests continue to pass;
  the five new test classes execute against the Testcontainers
  Postgres provisioned by M1-008a's `PostgresSchemaTestBase`
  and pass.

## Implementation notes

- **One migration file, one transaction.** Like V5 and V6, V7 is
  a single Flyway migration. The four `CREATE TABLE` statements
  plus the initial partition declaration plus the indices plus
  the GRANTs all apply atomically.
- **Migration version is V7.** M1-008a (V5) and M1-008b (V6)
  land first per the `blocked_by` chain. The schema-level
  dependency: V7's FKs reference V5's `users(id)` and V6's
  `source(id)` and `tag(id)`. If V5 or V6 isn't applied, V7
  errors at apply time.
- **Initial partition declaration.** Postgres requires AT LEAST
  ONE partition for a partitioned table to be useful — an
  INSERT into a partitioned table with no matching partition
  raises an error. Pick a bootstrap range that covers the
  near future (e.g., the current calendar month, or a 30-day
  window starting `date_trunc('day', now())`). The exact
  range is impl-choice; the schema commitment is "at least
  one queryable partition exists on day one." Naming
  convention: `post_YYYYMM` (e.g., `post_202605`). The
  partition cadence (monthly? daily? rolling weekly?) and the
  pruner schedule are out of scope; a later T1 ticket wires
  the scheduler that creates the next partition before it is
  needed and drops the oldest past the retention horizon.
- **No fallback partition.** Postgres supports a DEFAULT
  partition for rows that match no other partition. **Don't
  declare one.** A fallback partition is exactly the property
  Invariant 6 forbids: data that fell into the default would
  never be aged out by partition drop (the default partition
  is by construction the "everything else" bucket, and
  dropping it would lose data). Without a default, an INSERT
  outside every partition's range fails noisily — the right
  failure mode, surfaced to the application layer's scheduler
  which is then responsible for creating the next partition.
  The `PostPartitioningTest` asserts on this exact failure
  mode.
- **The post.uid column is TEXT, not bytea or UUID.** Per
  `docs/spec/schema.md` §UID derivation, the UID is
  `sha256(...)` lower-case hex-encoded — a fixed 64-character
  hex string. Storing it as TEXT keeps the value human-
  readable in psql and avoids hex-encoding it for every
  comparison; the small storage overhead (64 bytes plus
  per-row varlena header vs 32 bytes for bytea) is acceptable
  on a partitioned table that drops by cadence.
- **`tags TEXT[]` is inline.** Per `docs/design/02-schema.md`
  §2.3.2, tags are stored inline on `post.tags` rather than
  via a join table. The rationale: the parent is partitioned,
  and a partitioned M2M join (post_tag, also partitioned)
  would multiply complexity for no query advantage. The GIN
  index on `tags` (`idx_post_tags_gin`) keeps the tag-filtered
  summary query plan fast.
- **No NOTIFY trigger on post.** The `new_post` NOTIFY channel
  is T1-C's territory. This ticket creates the `ready_at`
  column the trigger will later read; the trigger itself is
  not authored here.
- **No `post_reference`, `post_embedding`, or `quarantine`
  table.** Those are T1-D's territory (Stage 2 evaluator +
  embedding pipeline + quarantine workflow). The `post`
  table here has no `embedding` column and no FK to a
  quarantine row.
- **No `saved_post` table.** The per-user-global save table
  (decision D13) lives alongside `chat_memory`,
  `chat_session`, and `summary_anchor` in a separate T1-D
  ticket. This subticket's scope is the per-scope joins and
  `post` only; `saved_post` is the §2.6.1 per-user-state
  carve-out and lands separately.
- **Soft-deleted source FK behavior.** `source_subscription`
  and `post` both have FKs to `source(id)`. Neither declares
  `ON DELETE CASCADE`. The Invariant 4 commitment is that
  `source` is never hard-deleted by either service role
  (M1-008b's V6 revokes `DELETE ON source` from both); the
  Admin role can hard-delete, but cascade-on-hard-delete is
  the operator's manual problem (`docs/design/02-schema.md`
  §2.2.3 — "cascade on hard-delete is the operator's manual
  problem"). The FK survives soft-delete because soft-delete
  is just an UPDATE on `source.deleted_at`, not a row
  removal; the `id` is unchanged.
- **`SoftDeletedSourceFkTest` is non-obvious.** The test
  exists because a casual reader might assume "if a source
  is soft-deleted, posts against it should be rejected." The
  schema deliberately does NOT reject — the in-flight ingest
  batch that was fetched before the soft-delete still
  resolves its FK and writes its posts. The
  application-tier scheduler's `WHERE deleted_at IS NULL`
  filter is what stops NEW fetches; in-flight writes are
  allowed by design. The test documents this property as a
  regression guard.
- **Subscription-version counters default to 0, increment
  monotonically.** Per `docs/design/02-schema.md` §2.2.5, the
  application layer increments these counters in the SAME
  transaction as a `/follow-tag` / `/unfollow-tag` /
  `/add-source` / `/remove-source` mutation. The digest
  cache key folds them in so a subscription change yields a
  fresh cache miss without an explicit invalidation pass.
  The schema commitment here is the column shape; the
  increment is application-tier. `ScopePreferencesTableTest`
  asserts that an UPDATE that increments the counter
  succeeds (it does not assert that an application path
  increments correctly — that's the application-tier ticket).
- **Reuse `PostgresSchemaTestBase` from M1-008a.** Same as
  M1-008b — the base spins up the Testcontainers Postgres
  once per JVM, applies migrations through V7 (Flyway
  applies V1..V7 in order), and provides a Connection
  factory. The five new test files extend (or delegate to)
  the base.

## Big-picture notes

- **Invariant 1 at the schema layer.** Per
  `docs/spec/schema.md` §Invariants — Invariant 1: "Every row
  that holds user state carries a scope discriminator (`'dm'`
  or `'group'`) and a scope id (or equivalent FKs)." This
  subticket's three per-scope join tables each have
  `(scope_kind, scope_id)` as the leading two columns of
  their primary key. The PK enforces both NOT NULL (PK
  columns can never be NULL) and uniqueness; a row cannot
  exist without both discriminator and id. This is the
  schema-level half of the invariant; the application-tier
  half (every query against per-scope state filters on both)
  is enforced by the M1-008 umbrella's per-(user, scope)
  isolation IT walking the surface end-to-end.
- **The carve-out (D13's `saved_post` exemption) lives in a
  later ticket, NOT here.** Per `docs/spec/schema.md`
  §Per-user state (scope-independent), `saved_post` is the
  one documented carve-out from Invariant 1 — it carries a
  user id only, no scope discriminator. This subticket
  deliberately does not introduce `saved_post`. The
  umbrella IT exercises the D13 carve-out by seeding a
  `saved_post` row and asserting it is visible from any
  scope; for the umbrella IT to do that, `saved_post` must
  also land before the umbrella runs. The `saved_post`
  table is a small follow-up T1-D ticket; the M1-008
  umbrella's `blocked_by` includes it if planning surfaces
  it before the umbrella runs. **If the `saved_post`
  table has not landed yet when the umbrella reviewer
  reviews this subticket, that is fine** — this subticket's
  scope is the per-scope joins and `post`; the carve-out is
  not in scope here. The umbrella's pre-flight clarity
  check will surface the `saved_post` dependency for the
  umbrella ticket itself.
- **Partition cadence and the pruner are deliberately out of
  scope.** The schema commits to PARTITION BY RANGE; the
  cadence (daily? monthly?) and the pruner (drop oldest past
  retention horizon, create next partition ahead of need)
  are operational concerns that land with the T1-D / T1-E
  scheduler. Decoupling the schema declaration from the
  cadence lets the cadence be tuned per profile without a
  migration touch.
- **post.ready_at carries security weight.** Per
  `docs/spec/architecture.md` §Inter-service communication
  (loaded by other subtickets, not strictly required here),
  the Provider's `LISTEN/NOTIFY` reconciler tails the
  `new_post` channel and uses `ready_at` as the cursor.
  Getting `ready_at` wrong (e.g., setting it before Stage 2
  completes) would surface posts that have not cleared the
  ingest security pipeline. This subticket's commitment is
  the COLUMN; the column is set by the application-tier
  status-transition path in a later ticket. The schema
  contributes by declaring `ready_at TIMESTAMPTZ` (nullable;
  NULL while not READY).
- **Subticket isolation against M1-008a and M1-008b.** V7
  reads the FKs to `users(id)`, `source(id)`, and `tag(id)`
  but does not modify V5 or V6. The test files in this
  subticket exercise V7 surfaces only.
- **Why `security_relevant: true`.** Partition declaration
  bugs are silent leaks: a missed PARTITION BY clause means
  the future pruner has nothing to drop and posts accumulate
  past their retention horizon, breaking decision D33's
  privacy commitment. A missed scope discriminator on a
  per-scope join would let a buggy command handler write
  scope-agnostic rows that leak across users at read time.
  The threat-actor review should look at: (a) the
  PARTITION BY declaration on `post`, (b) the FK behaviors
  under soft-delete, (c) the absence of `ON DELETE CASCADE`
  on `source_subscription.source_id` and `post.source_id`
  (Invariant 4), (d) the partial unique indices and the
  PRIMARY KEY columns on the per-scope joins.

## Out-of-scope expansion

- **The M1-008 umbrella's per-(user, scope) isolation IT
  (`infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java`)**
  is reserved for the umbrella commit. This subticket's
  tests cover per-row constraints (PK, FK, CHECK, partition
  routing); the cross-table invariant lives in the
  umbrella.
- **V5 (identity/audit) and V6 (sources/tags) migrations.**
  M1-008a owns V5; M1-008b owns V6. This subticket adds V7
  only and does NOT modify V5 or V6.
- **The partition cadence + pruner.** Operational concern;
  later T1 ticket.
- **post_reference, post_embedding, quarantine tables.**
  T1-D's territory.
- **saved_post, chat_memory, chat_session, summary_anchor
  tables.** Separate later T1 ticket (per-user state
  carve-out for D13's saved-post and §2.6.1 chat persistence).
- **LISTEN/NOTIFY on new_post.** T1-C's territory.
- **Java entity classes / repositories / services / DAOs /
  Fetcher impls.** None.
- **Provider startup logic against post.** None here.
- **Application-tier status-transition logic
  (RAW→READY→QUARANTINED→NEEDS_REVIEW).** The schema declares
  the CHECK over the four-value set; the transitions are
  enforced by the application-tier evaluator (T1-D).
- **The partition-creation scheduler.** Out of scope. The
  schema creates ONE initial partition; future partitions
  are the scheduler's job.
- **Modifications to `infochat-core/pom.xml`.** M1-008a
  authored the test infrastructure; this subticket reuses
  it.

## Authorized test changes

- (none — this subticket adds five new test files in
  `infochat-core` and modifies no pre-existing tests. All
  prior tests continue to pass unchanged.)

## Alternatives considered

- **Add a DEFAULT partition to `post` so unranged INSERTs
  don't fail.** Rejected: the default partition would
  silently accumulate rows that match no other partition,
  and Invariant 6 commits TTL to partition drop — a
  default partition cannot be dropped without losing data.
  The application-tier scheduler is responsible for creating
  the next partition before it is needed; a missed
  scheduling step should surface as a noisy INSERT failure,
  not a silent accumulation.
- **Replace `tags TEXT[]` with a partitioned `post_tag` join
  table.** Rejected: the parent is partitioned by
  `fetched_at`; a partitioned join would have to share the
  partitioning key (otherwise the JOIN's partition-pruning
  doesn't work) and would multiply per-row complexity for no
  query advantage. The GIN index on `tags` already gives the
  tag-filtered summary query a fast plan.
- **Use a daily partition cadence as part of this migration
  (create the next 30 partitions ahead).** Rejected: the
  cadence + the pruner are operational concerns. Hard-coding
  30 daily partitions in a schema migration would couple the
  migration to a specific cadence; the scheduler ticket
  picks the cadence per profile.
- **Add `ON DELETE CASCADE` on `source_id` FKs.** Rejected:
  Invariant 4 commits to soft-delete only. Hard-delete is the
  Admin role's manual escape hatch; cascade on hard-delete is
  the operator's problem (per `docs/design/02-schema.md`
  §2.2.3). Adding CASCADE here would let a future
  Admin-role DELETE silently fan out across the partition
  tree, which is exactly the property the operator-side
  escape hatch is meant NOT to have.
- **Replace `(scope_kind TEXT, scope_id UUID)` with a polymorphic
  reference (e.g., separate `dm_user_id` and `group_id`
  columns with a CHECK enforcing exactly one non-null).**
  Rejected: the (kind, id) discriminator pattern keeps the
  per-scope queries uniform (every per-scope SELECT has the
  same WHERE shape) and matches the spec's vocabulary. The
  two-column shape would force per-table-specific SELECT
  logic and bloat the per-scope join indices.
- **Make `tags TEXT[]` into a `tag_id UUID[]` referencing
  `tag(id)`.** Rejected: Postgres doesn't enforce element-
  level FK constraints on arrays. The current shape (tag
  names inline, validated against the vocabulary by the
  application tagger before INSERT) is the v1 commitment;
  the tagger is the closure-enforcer.
- **Run partition routing tests via @QuarkusTest with
  Quarkus DevServices for Postgres.** Rejected: same as
  M1-008a — `infochat-core` is a plain library jar; the
  Testcontainers Postgres + plain JDBC tests are simpler
  and faster.

## Implementation outline (M1-008c, generated by Plan subagent on 2026-05-14)

### Files to touch (6 of 8)
- create: `infochat-core/src/main/resources/db/migration/V7__joins_post.sql` — single transactional Flyway migration creating `source_subscription`, `scope_tag`, `scope_preferences`, parent `post` (PARTITION BY RANGE), one initial partition, all design-§2.3.1 indexes, and the per-table GRANT block.
- create: `infochat-core/src/test/java/io/infochat/core/schema/SourceSubscriptionTableTest.java` — covers `scope_kind` CHECK, NOT NULL columns, compound PK dedup, FK to `source(id)` without ON DELETE CASCADE, reverse-lookup index `idx_source_sub_source` exists.
- create: `infochat-core/src/test/java/io/infochat/core/schema/ScopeTagTableTest.java` — covers `scope_kind` CHECK, compound PK dedup on `(scope_kind, scope_id, tag_id)`, FK violation on unknown `tag_id`.
- create: `infochat-core/src/test/java/io/infochat/core/schema/ScopePreferencesTableTest.java` — covers `language` DEFAULT 'en', `tag_mode` CHECK closes `('ALL','EXPLICIT')`, both version counters DEFAULT 0, monotonic UPDATE round-trips, PK on `(scope_kind, scope_id)`.
- create: `infochat-core/src/test/java/io/infochat/core/schema/PostPartitioningTest.java` — covers partition routing happy-path INSERT lands in the bootstrap partition (verifiable via `tableoid::regclass`), INSERT outside the bootstrap range raises SQLException, `status` CHECK closes the four-value set, the seven per-stage BOOLEAN NOT NULL flags default FALSE, `tags TEXT[]` defaults `'{}'`, GIN index `idx_post_tags_gin` exists, PK is `(id, fetched_at)`, UNIQUE on `(uid, fetched_at)` raises 23505 on duplicate.
- create: `infochat-core/src/test/java/io/infochat/core/schema/SoftDeletedSourceFkTest.java` — covers the non-obvious Invariant 4 case: after `UPDATE source SET deleted_at = now()`, a subsequent `INSERT INTO post (source_id, …)` still succeeds because the FK is to `source(id)` and soft-delete leaves the PK row intact. Mirror assertion for `source_subscription` insert against a soft-deleted source.

Files used: 1 migration + 5 test files = 6 of the 8-file budget. Two-file headroom remains; no surplus, no escalation required on `files_budget`.

### Tests
- add: `infochat-core/src/test/java/io/infochat/core/schema/SourceSubscriptionTableTest.java` — covers acceptance items "scope_kind TEXT NOT NULL with CHECK", "PRIMARY KEY (scope_kind, scope_id, *) on the three join tables", "source_subscription FK to source(id) without ON DELETE CASCADE", "NOT NULL, CHECK, PK violation, compound PK requirement", and "Every new *Test.java reuses PostgresSchemaTestBase from M1-008a".
- add: `infochat-core/src/test/java/io/infochat/core/schema/ScopeTagTableTest.java` — covers "scope_tag FK to tag(id)", the `scope_kind` CHECK + PK acceptance items, and "ScopeTagTableTest: same scope_kind + FK violation on unknown tag_id".
- add: `infochat-core/src/test/java/io/infochat/core/schema/ScopePreferencesTableTest.java` — covers "scope_preferences: language default 'en', tag_mode CHECK ('ALL','EXPLICIT'), two BIGINT version counters default 0" and "language default, tag_mode CHECK, version counters default 0 and monotonic UPDATE".
- add: `infochat-core/src/test/java/io/infochat/core/schema/PostPartitioningTest.java` — covers "post as PARTITION BY RANGE (fetched_at)", "at least one initial partition CREATE TABLE post_NNNNNN PARTITION OF post", "post status CHECK over ('RAW','READY','QUARANTINED','NEEDS_REVIEW')", "7 per-stage BOOLEAN NOT NULL flags", "PRIMARY KEY (id, fetched_at) and UNIQUE (uid, fetched_at)", "GIN index on post(tags)", and "partition routing happy path; INSERT outside range raises SQLException".
- add: `infochat-core/src/test/java/io/infochat/core/schema/SoftDeletedSourceFkTest.java` — covers Invariant 4 acceptance item "post INSERT against soft-deleted source still succeeds".

No pre-existing tests are modified. The ticket's "Authorized test changes" section says "(none — adds five new test files in infochat-core; no pre-existing tests modified.)" — this matches the plan exactly. No authorization gap.

`PostgresSchemaTestBase.truncateAll()` currently TRUNCATEs only the V5 identity tables. M1-008b's pattern (e.g., `SourceTableTest`) avoids the gap by using **per-test-unique identifiers** so independent test methods can't collide. New tests in this ticket MUST follow that pattern: each test method picks a unique `scope_id` (a fresh UUID literal per method) and unique `source.identifier` / `tag.name` strings when bootstrapping referenced rows. Do NOT modify `PostgresSchemaTestBase` — it's outside `files_scope` (the helper's evolution is owned by the umbrella M1-008 commit, not this subticket) and the per-test-unique-identifier convention M1-008b set is the established alternative.

### Cross-cutting concerns
- **Invariant 1 (per-(user, scope) isolation).** Three of the four new tables are per-scope. The `(scope_kind, scope_id, *)` PRIMARY KEY is the schema-level enforcement: a row cannot exist without both the discriminator and the id, so an accidentally scope-agnostic INSERT fails at the storage layer. Tests must include a PK-violation case (insert two rows with identical `(scope_kind, scope_id, tag_id)` or `(scope_kind, scope_id, source_id)` and assert SQLState 23505).
- **Invariant 4 (soft-delete only for sources).** `source_subscription.source_id` and `post.source_id` both FK to `source(id)` **without** `ON DELETE CASCADE`. The `SoftDeletedSourceFkTest` is the schema-level proof that in-flight ingest still resolves the FK after `UPDATE source SET deleted_at = now()` (because soft-delete is just an UPDATE, the PK row stays). Verify in the migration that no `ON DELETE CASCADE` clause appears on either FK; `ON DELETE SET NULL` on `added_by → users(id)` is correct.
- **Invariant 5 (no `'EVALUATING'` status).** The `post.status` CHECK must close to `('RAW','READY','QUARANTINED','NEEDS_REVIEW')` — four values, no fifth. The seven per-stage `BOOLEAN NOT NULL DEFAULT FALSE` flags are the durable cursor: `stage1_done, stage2_done, tagger_done, embedding_done, stage1_flagged, stage2_failed, tagger_fallback`. Test the count and the NOT NULL.
- **Invariant 6 (TTL by partitioning).** `PARTITION BY RANGE (fetched_at)` plus **one** initial partition (named `post_YYYYMM` — pick the current calendar month, e.g., `post_202605`). No `DEFAULT` partition. The schema commitment is "the parent is partitionable" plus "day-one inserts route somewhere." The pruner cadence is later territory.
- **Security §DB roles.** The GRANT block mirrors V6's structure exactly — per-table GRANTs at the bottom of the migration. Per-scope joins: `SELECT, INSERT, UPDATE, DELETE` to `infochat_provider`, `SELECT` to `infochat_collector`. `post`: `SELECT, INSERT, UPDATE` to `infochat_collector`, `SELECT` to `infochat_provider`; neither role gets `DELETE` on `post`. Neither table is reachable by `infochat_listen`.
- **Migration atomicity.** Single Flyway transaction; partial failure rolls back cleanly. Match V5/V6's "one file, one transaction" pattern. No `\connect` lines, no `COMMIT;` mid-file.

### Implementation order
1. **Author `V7__joins_post.sql`** end-to-end (all four tables + initial partition + indexes + GRANTs). Doing the migration first is mandatory: `PostgresSchemaTestBase.POSTGRES.start()` plus Flyway-migrate runs at static-init time on the first test class. If the migration is missing or fails, every new test class fails at JVM startup with a Flyway error before any assertion runs.
2. **Author `SourceSubscriptionTableTest.java`** first among the test files. It exercises the simplest table and tests the FK-to-`source` shape that `SoftDeletedSourceFkTest` later builds on; getting this right first means the FK quirks are localized.
3. **Author `ScopeTagTableTest.java`** next. Similar shape to `SourceSubscriptionTableTest` (compound PK, scope_kind CHECK, FK to a V6 catalogue table), so it benefits from the patterns already settled in step 2.
4. **Author `ScopePreferencesTableTest.java`** next. Different shape — no FK to a catalogue table, but DEFAULTs and a monotonic-counter UPDATE that tests the spec's "version counters fold into the digest cache key" commitment. Self-contained.
5. **Author `PostPartitioningTest.java`** next. Most complex test: partition routing happy-path (insert lands in `post_YYYYMM`, verify via `tableoid::regclass`), out-of-range insert raises SQLException, status CHECK, seven per-stage flags, `(id, fetched_at)` PK, `(uid, fetched_at)` UNIQUE, GIN index existence. Doing this after the simpler tables means the helper patterns are already in muscle memory.
6. **Author `SoftDeletedSourceFkTest.java`** last. Exercises the cross-table invariant: bootstrap a source row, soft-delete it, then INSERT into both `source_subscription` and `post` and assert both succeed. Requires patterns from steps 2 and 5 to already exist.
7. **Run `mvn -B -pl infochat-core -am test`** and verify all five new tests pass plus all M1-008a/b tests still pass.
8. **Run `mvn -B clean verify`** from the repo root and verify exit 0.

A wrong order — e.g., writing tests before the migration, or starting with `SoftDeletedSourceFkTest` before the simpler tables — produces broken intermediate states where the static-init Flyway-migrate fails for every test class in the package.

### Risks
- **Initial partition date selection / naming pattern.** The acceptance grep expects `post_NNNNNN` (six digits = `post_YYYYMM`, monthly). If the developer picks daily (`post_YYYYMMDD`, eight digits), the grep fails. Stick to `post_YYYYMM` for the initial partition. Escalation if ambiguous: **refine**.
- **Initial partition range edge case.** Test methods that use `now()` for `fetched_at` could land outside the bootstrap partition when CI runs near a month boundary. Mitigation: `PostPartitioningTest` explicitly threads `fetched_at = '2026-05-15 12:00:00+00'::timestamptz` (a known timestamp inside the bootstrap range) rather than relying on `DEFAULT now()`. Escalation if it surfaces as a real problem: **refine**.
- **`PostgresSchemaTestBase.truncateAll()` doesn't TRUNCATE the new tables.** Per-test isolation depends on per-test-unique identifiers (M1-008b's convention). If any new test reuses an identifier between methods, intermittent flakes appear. Mitigation: use fresh UUIDs / unique string suffixes in every test method. Not an escalation — a discipline call on test authoring.
- **`tableoid::regclass` for routing verification.** Simpler than `pg_partition_tree`. Recommend for `PostPartitioningTest`.
- **`security_relevant: true` flag.** The post partition declaration carries security weight. The threat-actor review (separate `/redteam` skill) will look at this and at FK soft-delete behavior. No mitigation needed at implementation time beyond getting the schema right.

No risk warrants `decompose`, `defer`, or `spec-amend`. The single candidate for `refine` (partition-naming pattern) is already resolved by the implementation-notes language. The ticket is implementable as written within the `round_cap: 2` budget.

### Out-of-scope (echoed from ticket)
- `infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java` — umbrella commit.
- Any change under V5__*.sql or V6__*.sql — frozen.
- Any modification to V1..V6 migrations — frozen.
- Any partition pruner, retention sweep, scheduled DROP-PARTITION job — later T1-D / T1-E ticket.
- Any LISTEN/NOTIFY trigger on `post` or `post.ready_at` — T1-C's territory.
- Any `post_reference`, `post_embedding`, or `quarantine` table — T1-D's territory.
- Any `pgvector` extension installation or `vector` column.
- Any Java entity class, repository, service, DAO, or Fetcher impl.
- Any /summary, /saved, /save, /unsave, /follow-tag, /unfollow-tag, /add-source, /remove-source command handler.
- Any `saved_post` table.
- Any `chat_memory`, `chat_session`, `summary_anchor` table.
- Any change to `infochat-core/pom.xml`.
