---
id: M1-008
title: MVP schema umbrella — per-(user, scope) isolation IT
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
      files: 3
      added: 386
      removed: 10
blocked_by:
  - M1-008a
  - M1-008b
  - M1-008c
files_budget: 2
files_scope:
  - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change under infochat-core/src/main/resources/db/migration/ (the three subtickets M1-008a/b/c shipped V5/V6/V7; this umbrella consumes them and does NOT add a new migration)
  - any change to the V5/V6/V7 migrations authored by the subtickets (those commits are FROZEN at the umbrella round per docs/process/workflow.md §M1 workflow — never amend a passed commit; if the IT exposes a defect, file a follow-up ticket against the affected subticket's module, do NOT amend any subticket commit)
  - any change under infochat-core/pom.xml (the test infrastructure — Testcontainers + Postgres JDBC + Flyway core + maven-failsafe-plugin — was authored by M1-008a and is reused here; re-touching the POM here would be scope drift)
  - any new schema-level test under infochat-core/src/test/java/io/infochat/core/schema/*Test.java (per-row schema constraints are the subtickets' verification surface; this umbrella adds the cross-cutting *IT class only)
  - any saved_post / chat_memory / chat_session / summary_anchor table or its tests (the D13 carve-out half of the per-(user, scope) isolation invariant requires saved_post which lands in a separate later T1-D ticket; this umbrella exercises the per-scope half of the invariant only — see Big-picture notes for the carve-out follow-up)
  - any application-tier code (no Java entity, no repository, no service, no DAO, no command handler — the IT exercises raw JDBC against the migrated schema)
  - any new Quarkus extension (the IT is plain JUnit 5 + JDBC, not @QuarkusTest)
  - any modification to M1-003 @QuarkusTest stubs, M1-007 cross-module AllSpisLoadIT, M1-007a/b/c SPI smoke tests, M1-008a identity/audit *Test files, M1-008b source/tag *Test files, or M1-008c joins/post *Test files (those continue to pass unchanged; modifying any would be a test-integrity violation per engineering-rules-verbatim.md §8)
acceptance:
  - "infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java exists and contains at least one @Test annotation (grep -E '@Test' returns at least one match)"
  - "The IT extends or otherwise reuses the PostgresSchemaTestBase helper authored in M1-008a (grep -E 'PostgresSchemaTestBase' returns at least one match) — no new Testcontainers / Flyway boot logic is added by the umbrella"
  - "The file is named with the *IT suffix (PerScopeIsolationIT.java) and is picked up by maven-failsafe-plugin under mvn verify — grep -rE 'PerScopeIsolationIT' infochat-core/target/failsafe-reports returns at least one match after the build (the failsafe wiring was authored by M1-008a; this umbrella's IT is the first consumer)"
  - "The IT seeds, in setup or per-test fixture data, at least two users (A, B) — grep -E 'INSERT\\s+INTO\\s+users' or equivalent JDBC PreparedStatement against `INSERT INTO users(...)` returns at least two SQL statements OR the test logic invokes a helper method `insertUser(...)` at least twice"
  - "The IT seeds at least two scopes (one DM keyed by users.id, one group:G keyed by groups.id) — grep -E 'INSERT\\s+INTO\\s+groups' or equivalent returns at least one match (for the group scope; DM scope_id reuses the user's id per the scope-discriminator convention)"
  - "The IT writes per-scope rows into at least one of source_subscription, scope_tag, scope_preferences across BOTH scopes for BOTH users (i.e., four discriminator combinations: (A, DM), (A, group:G), (B, DM), (B, group:G)) — the test asserts the four combinations exist by counting rows after seeding"
  - "The IT asserts: SELECT * FROM source_subscription WHERE scope_kind='dm' AND scope_id = $A_id returns ONLY rows seeded under (A, DM) — no rows from any other discriminator combination leak in (Invariant 1, schema-level per-(user, scope) isolation)"
  - "The IT asserts: SELECT * FROM source_subscription WHERE scope_kind='group' AND scope_id = $group_id returns ONLY rows seeded under (*, group:G) — no DM rows leak in; rows for BOTH A's group seeds AND B's group seeds appear (group scope is shared per docs/spec/schema.md §Sources and tags — Source subscription: 'group scope is shared')"
  - "The IT asserts the same isolation property against scope_tag and scope_preferences (the second and third per-scope join tables introduced by M1-008c) — three tables, three isolation assertions, each scoped by (scope_kind, scope_id)"
  - "The IT asserts that an INSERT into any per-scope join table with scope_kind = NULL raises a NOT-NULL violation (regression guard against the schema-level enforcement degrading)"
  - "The IT writes a post row (via INSERT INTO post and reads it back through the per-scope join chain `source_subscription JOIN post ON post.source_id = source_subscription.source_id WHERE source_subscription.scope_kind = $kind AND source_subscription.scope_id = $id`) and asserts the per-scope-filtered SELECT returns ONLY posts whose source is subscribed in the queried scope"
  - "The IT executes against the Testcontainers Postgres provisioned by PostgresSchemaTestBase and against the migrated schema after Flyway has applied V1..V7 (verify by asserting `SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1` returns at least '7' or equivalent semver token — the test sanity-checks the migration cursor before running its assertions)"
  - "mvn -B clean verify from the repo root exits 0. PerScopeIsolationIT runs under failsafe; failsafe reports record at least one test executed AND no failures (grep -rE '<testsuite[^>]*failures=\"0\"' infochat-core/target/failsafe-reports returns at least one match for PerScopeIsolationIT)"
  - "Every prior test continues to pass: M1-003 @QuarkusTest stubs (Collector + Provider), M1-007 cross-module AllSpisLoadIT, M1-007a/b/c per-module SPI smoke tests, M1-008a identity/audit *Test files, M1-008b source/tag *Test files, M1-008c joins/post *Test files. The test count in surefire-reports + failsafe-reports is monotonically larger than before this commit"
test_plan:
  adds:
    - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java (one *IT-named class with one or more @Test methods that seed two users, two scopes, per-scope rows across the four discriminator combinations, and assert no cross-scope leak; uses PostgresSchemaTestBase for the Connection)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - all M1-008a *Test.java classes (V5 identity/audit trigger and append-only tests)
    - all M1-008b *Test.java classes (V6 source/tag catalogue tests)
    - all M1-008c *Test.java classes (V7 per-scope join + post partitioning tests)
spec_refs:
  - docs/spec/schema.md §Invariants
  - docs/spec/schema.md §Per-user state (scope-independent)
  - docs/spec/schema.md §Per-scope state
  - docs/spec/security.md §Trust boundaries
  - docs/design/02-schema.md §2.2.3 source_subscription
  - docs/design/02-schema.md §2.2.4 scope_tag
  - docs/design/02-schema.md §2.2.5 scope_preferences
decision_refs:
  - D13
---

# M1-008: MVP schema umbrella — per-(user, scope) isolation IT

## Context

Umbrella commit for the M1-008 group (per `docs/process/workflow.md`
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-008a, M1-008b, and M1-008c each shipped a slice of the MVP
schema as its own reviewable commit on `main`:
- M1-008a — V5: identity / audit / last-admin trigger (§2.1).
- M1-008b — V6: sources and tags catalogues (§2.2.1, §2.2.2).
- M1-008c — V7: per-scope joins (§2.2.3..§2.2.5) and the
  partitioned `post` table (§2.3.1).

Each subticket's per-row tests verify its own slice. This
umbrella commit verifies the **cross-cutting** property the
subtickets cannot verify in isolation: that
`docs/spec/schema.md` §Invariants — Invariant 1 (per-(user, scope)
isolation) holds across the per-scope join surface. The IT seeds
two users (A, B) in two scopes (DM and group:G), inserts per-scope
rows across the four discriminator combinations, and asserts that
a scope-filtered SELECT against `source_subscription`, `scope_tag`,
and `scope_preferences` returns ONLY rows in the queried scope —
no cross-scope leak.

Invariant 1 is the keystone of the authorization model. A
cross-scope leak undoes `/forget`, `/save`, and chat-memory
privacy guarantees in one shot: a buggy command handler that
omits the scope predicate would let one group's preferences leak
into another's digest, or let DM history surface in a group
reply. The schema-level enforcement is the `(scope_kind, scope_id)`
leading PK columns on every per-scope join (M1-008c's commitment);
the application-tier enforcement is every query against per-scope
state filtering on both columns. This umbrella's IT walks the
schema-level half end-to-end against a real Postgres so a future
refactor that drops a column or relaxes a constraint surfaces as
a noisy test failure rather than a silent leak.

The whole-topic verification is meaningfully different from any
single subticket's per-row tests. A subticket-only verification
would miss two failure modes: (a) a schema-level bug that lets a
NULL `scope_kind` slip through one of the join tables (the
subticket tests assert per-table; the cross-table walk asserts
the property across all three at once), and (b) a join-shape bug
that lets a `source_subscription` row in one scope match the
posts of a different scope through the `post.source_id` FK. The
umbrella commit catches both because it joins the surface
end-to-end. Shipping this cross-table assertion as its own
reviewable unit is exactly the umbrella + subticket idiom's
reason to exist.

`security_relevant: true` — Invariant 1 is the keystone of the
authorization model. Threat-actor review should look at this
ticket's diff alongside M1-008a/b/c's schema for the
"could a cross-scope read leak past the IT's assertion set?"
question. The IT's coverage is the schema layer's claim; the
application-tier query review is later (per-command handler).

## Definition of Done

- A single plain-JUnit `@Test` class lives at
  `infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java`.
- The class extends (or delegates to) `PostgresSchemaTestBase`
  from M1-008a so the Testcontainers Postgres is reused. No new
  container is spun up.
- The `*IT` suffix matches Maven Failsafe's convention; the
  failsafe wiring authored by M1-008a runs the IT under
  `mvn verify`.
- Test setup seeds:
  - User A and User B (two rows in `users`).
  - Group G (one row in `groups`).
  - The DM scope for each user uses the user's own id as the
    `scope_id` (per the `(scope_kind, scope_id)` convention —
    DM scope is keyed by `users.id`, group scope is keyed by
    `groups.id`).
  - One source row (so the join tables have an FK target).
  - One tag row (so `scope_tag` has an FK target).
  - Per-scope rows across the four discriminator combinations:
    - `(scope_kind='dm', scope_id=A.id, ...)` rows in
      `source_subscription`, `scope_tag`, and
      `scope_preferences`.
    - `(scope_kind='dm', scope_id=B.id, ...)` rows in the same
      three tables.
    - `(scope_kind='group', scope_id=G.id, ...)` rows in the
      same three tables (group rows are shared by both A and B
      since they are both members of G; the join itself does
      not carry user attribution — that property is part of
      Invariant 1's "group scope is shared" clause per
      `docs/spec/schema.md` §Sources and tags).
- Assertions:
  - For each of `source_subscription`, `scope_tag`,
    `scope_preferences`:
    - `SELECT * WHERE scope_kind='dm' AND scope_id=A.id`
      returns ONLY the rows seeded under (A, DM). No B rows,
      no group rows.
    - `SELECT * WHERE scope_kind='dm' AND scope_id=B.id`
      returns ONLY the rows seeded under (B, DM). No A rows,
      no group rows.
    - `SELECT * WHERE scope_kind='group' AND scope_id=G.id`
      returns the group-scope rows. No DM rows.
  - An INSERT into any per-scope join with `scope_kind = NULL`
    raises a NOT-NULL violation (regression guard).
  - The post-via-join chain works: a post inserted against a
    source that is subscribed by (A, DM) is reachable via
    `source_subscription JOIN post ON
    post.source_id = source_subscription.source_id WHERE
    source_subscription.scope_kind='dm' AND
    source_subscription.scope_id=A.id` and is NOT reachable
    via the equivalent join filtered on (B, DM).
- A migration-cursor sanity check runs first:
  `SELECT MAX(version) FROM flyway_schema_history` returns at
  least `'7'` (or the equivalent Flyway version representation).
  This guards against the case where Flyway didn't apply V5/V6/V7
  for any reason (e.g., a misconfigured profile in the test).
- `mvn -B clean verify` from the repo root exits 0. Every prior
  test continues to pass; the new IT executes under Failsafe
  and passes.

## Implementation notes

- **Plain JUnit 5, not @QuarkusTest.** Same rationale as the
  three subtickets: `infochat-core` is a plain library jar (no
  Quarkus extensions in production scope per M1-007a), and the
  IT's job is to exercise the SCHEMA, not Quarkus wiring. Plain
  JDBC + Testcontainers Postgres is the smaller, faster
  invocation surface. `@QuarkusTest` would pull in Quarkus
  bootstrap cost for no benefit.
- **One IT, in infochat-core.** The schema lives in
  `infochat-core` (M1-017 relocated the Flyway migrations
  there). The per-(user, scope) isolation invariant is a
  schema-level property, and the IT must exercise rows
  spanning identity + sources + per-scope joins + posts — all
  in the same module. Placing the IT in Provider would drag
  in extra runtime context (Provider's CDI graph, its Quarkus
  extensions) for no test value. Placing it in Collector
  would do the same.
- **Reuse `PostgresSchemaTestBase`.** M1-008a authored the
  base; M1-008b and M1-008c already reuse it. The umbrella
  IT joins the pattern. No new Testcontainers boot logic
  here.
- **One `@Test` method or several — implementer's choice.** A
  single method that walks every assertion is acceptable; so
  is splitting into per-table methods. The shape that reads
  better wins. The handoff does not pin the method count.
- **Group scope is shared.** Per `docs/spec/schema.md`
  §Sources and tags — Source subscription: "DM scope is per
  user; group scope is shared." The IT reflects this: the
  group-scope rows are seeded once (not per-user) and the
  group-scope SELECT returns the shared rows regardless of
  which user issued the query. The IT does NOT assert
  "User A's group rows differ from User B's group rows" — by
  design, they don't.
- **`source_id` FK target.** The seed source row is created
  in the test's setup. After the test's main assertions, the
  source row is left in place; the next test's `TRUNCATE`
  (or transactional rollback) cleans it up. The base helper
  is responsible for the reset between tests.
- **No application-tier code.** The IT issues raw JDBC
  `PreparedStatement` calls against the JDBC `Connection`
  the base helper provides. No Hibernate, no Panache, no
  Quarkus extensions. The point of the IT is to exercise the
  schema; introducing an entity layer would couple the IT to
  a future application-tier change and obscure what the IT
  is actually proving.
- **The migration-cursor sanity check is the first assertion.**
  Before any seeding, the IT reads `flyway_schema_history`
  and asserts the max version is at least 7. This catches a
  miscompiled test profile that would otherwise silently
  succeed because the assertions run against an empty schema
  and the SELECTs return zero rows — passing because there's
  no data to leak. A version-7 schema with zero rows is
  different from no schema at all; the cursor check is the
  fast canary.
- **`PerScopeIsolationIT` is the first failsafe-run test in
  `infochat-core`.** M1-008a's `maven-failsafe-plugin`
  wiring was authored anticipating this consumer. If
  failsafe is misconfigured (e.g., the include pattern is
  wrong), the IT won't execute under `mvn verify` and the
  acceptance grep against `failsafe-reports` will fail.
  That is the right failure mode — surface the wiring
  problem at the umbrella commit's review.
- **No fixture file required.** The `files_budget: 2` says
  "the IT class + at most one test-resources fixture." The
  test seeds rows via JDBC directly; there is no need for a
  separate `.sql` or `.json` fixture file. The budget allows
  one if the implementer prefers (e.g., a `init-fixture.sql`
  that does the seed in a single statement and a Java helper
  that runs it), but the JDBC-only shape is the simpler
  default. **Keep the budget honest** — if you don't add a
  fixture, the diff touches 1 file and the budget is
  conservatively allocated.

## Big-picture notes

- **The subticket commits are FROZEN at the umbrella round.**
  M1-008a, M1-008b, and M1-008c each landed as their own
  reviewable commit on `main` before this umbrella becomes
  runnable. If this ticket's IT exposes a defect in one of
  the subticket outputs (e.g., a missing CHECK constraint, a
  forgotten GRANT, a partial index that doesn't cover the
  right predicate), the fix is a NEW ticket against the
  affected module — never an amendment to the subticket
  commit. The "never amend a passed commit" invariant in
  `CLAUDE.md` §M1 workflow applies here verbatim.
- **The D13 carve-out (`saved_post` per-user-global) is NOT
  in this IT.** Per `docs/spec/schema.md` §Per-user state
  (scope-independent), `saved_post` is the one documented
  carve-out from Invariant 1: a save made in DM is visible
  in every group the user is in. The D13 half of the
  invariant requires `saved_post`, which lands in a separate
  later T1-D ticket (not in M1-008a/b/c). Once `saved_post`
  ships, file a follow-up ticket that ADDS a `saved_post`
  carve-out assertion to this IT (or as a sibling IT) —
  do NOT amend this umbrella's commit. The umbrella's
  Invariant 1 commitment is the per-scope half; the D13
  half follows when the schema for it exists.
- **The umbrella unblocks downstream impl tickets.** Once
  M1-008 ships, T1-B (bootstrap loader + RSS Fetcher), T1-C
  (outbox + LISTEN/NOTIFY), T1-D (eval pipeline + tagger +
  embedding), T1-E (adapter + router), and T1-F (first
  commands) all become runnable. They each depend on the
  schema being in place; the umbrella's IT proves the
  per-scope half of the authorization model is structurally
  sound before the application layer binds to it.
- **What the IT does NOT prove.** It does not prove that the
  application layer correctly threads the scope discriminator
  through every query (that property is the per-command
  handler's responsibility and is tested by command-level
  tests in later tickets), that the LLM tool surface respects
  the scope filter (that property is the LLM-router ticket's
  job), or that the messaging adapter correctly resolves
  `(scope_kind, scope_id)` from an inbound event (that's
  T1-E's job). It proves only that the schema-level
  enforcement (PK columns, FK behaviors, CHECK constraints,
  partition routing) is structurally sound. That is a small
  but load-bearing claim — when it fails, the authorization
  model is broken at the storage layer.
- **The threat-actor review pass.** This ticket is
  `security_relevant: true` and pairs naturally with
  M1-008a's `security_relevant: true` (the last-admin
  trigger) and M1-008c's `security_relevant: true` (the
  partition declaration). A milestone-boundary `/redteam`
  after the umbrella commit covers the schema layer's
  attack surface: cross-scope leak, admin-empty deployment,
  retention-horizon bypass via missing PARTITION BY,
  audit-log mutation via missing trigger guard, FK behavior
  on soft-delete. The umbrella commit is the natural
  trigger point because it ships the cross-cutting
  invariant assertion.

## Out-of-scope expansion

- **Changes to any Flyway migration.** The three subtickets
  shipped V5, V6, V7. This umbrella adds no migration and
  modifies none.
- **Changes under `infochat-core/pom.xml`.** M1-008a
  authored the test-scope deps + the failsafe wiring; this
  umbrella reuses both. Re-touching the POM here would be
  scope drift.
- **New schema-level `*Test.java` files.** Per-row schema
  constraints are the subtickets' verification surface; the
  umbrella adds the cross-cutting `*IT` class only.
- **The `saved_post` D13 carve-out half of Invariant 1.**
  Requires `saved_post` which is a later T1-D ticket. File
  a follow-up to ADD a `saved_post` carve-out test once the
  table exists; do NOT amend this umbrella commit.
- **Any application-tier code.** No Java entity classes, no
  repositories, no services, no DAOs, no command handlers.
  The IT is raw JDBC against the migrated schema.
- **Changes to M1-003 stubs or any of the M1-007 / M1-008a/b/c
  tests.** Those continue to pass unchanged. Modifying any
  of them would be a test-integrity violation per
  `engineering-rules-verbatim.md` §8.
- **Quarkus extension additions.** The IT is plain JUnit 5
  + JDBC, not `@QuarkusTest`. No new `quarkus-*` dependency.

## Authorized test changes

- (none — this umbrella adds one new test class in
  `infochat-core` and modifies no pre-existing tests.)

## Alternatives considered

- **Make the IT a `@QuarkusTest` so it boots the Quarkus
  context.** Rejected: spinning up the Quarkus context to
  exercise raw JDBC is overkill, slows the test, and could
  mask a schema issue behind a slower (but unrelated)
  Quarkus startup failure. Plain JUnit + Testcontainers
  isolates the assertion. Same rationale as M1-007's
  `AllSpisLoadIT` choice.
- **Inline the cross-scope assertions into each subticket's
  per-row tests and skip the umbrella.** Rejected: the
  per-row tests can only see their own table's classpath.
  Cross-table isolation is exactly the property a per-table
  test cannot prove. The umbrella exists for this property.
- **Use Quarkus DevServices for Postgres instead of
  Testcontainers directly.** Rejected: DevServices requires
  the Quarkus context, which this IT does not need. The
  Testcontainers JUnit extension does the same lifecycle
  management without the Quarkus dependency.
- **Make the IT a parameterized test with one parameter set
  per scope discriminator.** Acceptable but not required. The
  scope discriminators are only two; the unrolled shape reads
  cleaner. Either choice meets acceptance.
- **Add the D13 `saved_post` carve-out assertion to this IT
  (anticipating the future table).** Rejected: the
  `saved_post` table does not yet exist. Asserting against
  a future table would either skip the assertion (silent
  miss) or fail the IT (block this umbrella indefinitely).
  The carve-out half of the invariant lands when the table
  does.
- **Author a second IT alongside this one for the per-user
  state (saved_post) carve-out.** Rejected for the same
  reason — the table does not exist yet. The follow-up IT
  is filed when the table ships.
- **Move the migration-cursor sanity check into
  `PostgresSchemaTestBase` so every IT inherits it.**
  Acceptable but out of this umbrella's scope. M1-008a's
  base could be enriched in a later ticket; this umbrella's
  diff stays at the locked 2-file budget.
- **Make the test transactional and roll back at the end
  instead of TRUNCATE-resetting.** Acceptable; the base
  helper's reset strategy is the implementer's choice. The
  IT's assertions are valid either way.
