---
id: M1-044a
title: Intake-step services — rate cap, invite consumer, ban check, brute-force migration
status: pending
created: 2026-05-20
last_updated: 2026-05-20
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AutoRegisterService.java
  - infochat-core/src/main/resources/db/migration/V12__invite_code_attempt.sql
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BanCheckTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - any change to the spec — §Authorization model + §User ban + §Invite-code registration are complete on main HEAD; this ticket implements them
  - any change to InboundRouter.java — the intake-step splice is M1-044b's commit; this ticket lands the services in isolation, with no router wiring
  - any new admin command handler — /ban, /unban, /invite create/list/revoke are M1-044c's commits
  - any /vouch handler — M1-045 territory
  - any /grant-admin / /revoke-admin handler — M1-046 territory
  - the umbrella IT at infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteIntakeRoundtripIT.java — M1-044 (umbrella) territory
  - any change to BundleKeys.java or bundles/en.properties — M1-044b authors the fixed-reply bundle keys for the intake splice; this ticket does NOT add user-visible reply text (the services' return values are typed records, not bundle-keyed strings)
  - any change to application.properties — M1-044b ships the rate-cap profile config; this ticket's RateCapBucket uses constructor defaults overridable via @ConfigProperty with `defaultValue`
  - any change to InboundContext.java — the M1-040 bean is consumed unchanged via @Inject
  - any change to ContactIds.redact — the M1-038 helper is consumed unchanged
  - any modification to the V5 invite_code table, delete_preban_user procedure, or last-admin protection trigger — those are V5's commit and are consumed as-is
  - any change to AutoRegisterService PUBLIC method signature beyond what the rename-and-narrow strictly requires — callers (currently only InboundRouter.onMessage line 235 today) must continue to compile until M1-044b updates the call site
  - any test outside the four files in files_scope — M1-035c's HelpCommandHandlerTest, BundleLoaderTest, M1-035b's InboundRouterTest / NormalizeTest, M1-036/M1-037 handler tests, M1-038/M1-039/M1-040 hardening tests all stay green unchanged
acceptance:
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java exists, is `@ApplicationScoped`, and exposes a public method `boolean tryAcquire(String adapter, String contactId)` that returns true on under-cap and false on over-cap. Verify: `grep -E '@ApplicationScoped' RateCapBucket.java` returns ≥1 match AND `grep -E 'public\\s+boolean\\s+tryAcquire' RateCapBucket.java` returns ≥1 match. The bucket holds in-memory state keyed by `(adapter, contactId)`; no JDBC, no `DataSource` injection — confirmed by `grep -E 'DataSource|java\\.sql' RateCapBucket.java` returning ZERO matches"
  - "RateCapBucket reads its cap value via `@ConfigProperty(name = \"infochat.rate-cap.inbound-per-minute\", defaultValue = \"60\")` so M1-044b's per-profile property edits land cleanly. Verify: `grep -E 'infochat\\.rate-cap\\.inbound-per-minute' RateCapBucket.java` returns ≥1 match"
  - "RateCapBucketTest covers: (a) first N inbounds under the cap return true; (b) the (N+1)-th inbound from the same (adapter, contactId) returns false (the cap fires); (c) inbounds from a different (adapter, contactId) tuple are independently bucketed (asserted by interleaving two contact IDs and seeing each respect its own cap); (d) the bucket refills after the window elapses (advance the clock via a test-injectable clock seam or @Inject Clock, asserting the (N+1)-th call returns true again after the refill interval). `grep -E '@Test' RateCapBucketTest.java` returns ≥3 matches"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java exists, is `@ApplicationScoped`, and exposes a public method returning a typed result (sealed interface or record-based enum) over `{Accepted(UUID userId), Rejected, BruteForceThresholdBreached}` for a single consume attempt. Verify: `grep -E '@ApplicationScoped' InviteCodeConsumer.java` returns ≥1 match"
  - "InviteCodeConsumer's consume SQL is the spec-committed race-safe conditional UPDATE per schema.md §Identity and access — Invite code: `UPDATE invite_code SET status = 'USED', used_at = NOW(), used_by_contact_id = ? WHERE code = ? AND status = 'PENDING' AND (expires_at IS NULL OR expires_at > NOW()) AND adapter = ? AND (invite_type = 'OPEN_ADAPTER' OR expected_contact_id = ?) RETURNING id`. Verify: `grep -E 'UPDATE\\s+invite_code\\s+SET\\s+status\\s*=\\s*''USED''' InviteCodeConsumer.java` returns ≥1 match AND `grep -E 'RETURNING\\s+id' InviteCodeConsumer.java` returns ≥1 match"
  - "InviteCodeConsumer increments the per-`(adapter, contact_id)` brute-force counter on every Rejected outcome AND emits a single `INVITE_CONSUME` audit row on every Accepted outcome (audit-before-effect per Invariant 7 — the audit INSERT runs in the same transaction as the conditional UPDATE so a transaction roll-back leaves no audit row for a failed consume). Verify: `grep -E 'INSERT\\s+INTO\\s+invite_code_attempt' InviteCodeConsumer.java` returns ≥1 match AND `grep -E 'INSERT\\s+INTO\\s+audit_log' InviteCodeConsumer.java` returns ≥1 match"
  - "InviteCodeConsumer checks the per-`(adapter, contact_id)` brute-force counter BEFORE the conditional UPDATE and short-circuits with the BruteForceThresholdBreached outcome when the counter ≥ threshold within the window. The threshold value reads via `@ConfigProperty(name = \"infochat.invite.brute-force-threshold\", defaultValue = \"10\")` and the window via `@ConfigProperty(name = \"infochat.invite.brute-force-window\", defaultValue = \"1h\")` (Duration). When the threshold is breached, a single `INVITE_BRUTE_FORCE_BREACH` audit row is written (the brute-force breach audit row per spec §Invite-code registration). Verify: `grep -E 'infochat\\.invite\\.brute-force-threshold|infochat\\.invite\\.brute-force-window' InviteCodeConsumer.java` returns ≥2 matches. Note: the verb `INVITE_BRUTE_FORCE_BREACH` is NOT yet in the V5 audit catalogue; this ticket adds it as a comment-only entry in the V12 migration (audit catalogue is open-ended per V5 comments) and as a constant in InviteCodeConsumer"
  - "infochat-core/src/main/resources/db/migration/V12__invite_code_attempt.sql exists and applies cleanly on a fresh DB. The migration creates a table `invite_code_attempt` with columns `(adapter TEXT NOT NULL, contact_id TEXT NOT NULL, attempted_at TIMESTAMPTZ NOT NULL DEFAULT now())` and an index `idx_invite_code_attempt_lookup ON invite_code_attempt(adapter, contact_id, attempted_at DESC)` to back the window-bounded COUNT(*) query. Verify: `grep -E 'CREATE TABLE invite_code_attempt' V12__invite_code_attempt.sql` returns ≥1 match AND `grep -E 'CREATE INDEX idx_invite_code_attempt_lookup' V12__invite_code_attempt.sql` returns ≥1 match. The migration carries the V5-style per-table GRANT: `GRANT SELECT, INSERT ON invite_code_attempt TO infochat_provider;` — verify: `grep -E 'GRANT\\s+SELECT,\\s+INSERT\\s+ON\\s+invite_code_attempt\\s+TO\\s+infochat_provider' V12__invite_code_attempt.sql` returns ≥1 match. DELETE on the table is intentionally NOT granted (rows accumulate; operator-side TRUNCATE under the admin role is the only purge path, mirroring the audit-log append-only treatment for safety)"
  - "V12 adds the `INVITE_BRUTE_FORCE_BREACH` audit verb as a single line comment under the audit_log closed verb catalogue (mirroring V5's `-- VERB` documentation style at V5:276-298). Verify: `grep -E '^-- INVITE_BRUTE_FORCE_BREACH$' V12__invite_code_attempt.sql` returns ≥1 match"
  - "InviteCodeConsumerTest covers: (a) Accepted on a valid PENDING CONTACT_BOUND code with matching (adapter, contact_id) — the row transitions to USED, `used_by_contact_id` populated, `INVITE_CONSUME` audit row written; (b) Accepted on a valid PENDING OPEN_ADAPTER code with no contact binding — the row transitions to USED, audit row written; (c) Rejected on a code already USED (idempotency — second consume returns Rejected, no second audit row); (d) Rejected on a code with `expires_at < NOW()` (boundary: the inclusive `NOW() >= expires_at` rule from spec §Invite-code registration — pin with a code at `expires_at = NOW() - 1s` and assert Rejected); (e) Rejected on a CONTACT_BOUND code whose `expected_contact_id` differs from the consume's `contact_id` (cross-contact isolation); (f) Rejected on a code bound to adapter A consumed from adapter B (cross-adapter isolation per §Invite-code registration); (g) brute-force threshold breach after N consecutive Rejected attempts from the same `(adapter, contact_id)` within the window — the (N+1)-th call returns `BruteForceThresholdBreached`, no UPDATE runs, the `INVITE_BRUTE_FORCE_BREACH` audit row is written exactly once for that breach event (NOT once per over-threshold attempt). `grep -E '@Test' InviteCodeConsumerTest.java` returns ≥7 matches"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java exists, is `@ApplicationScoped`, and exposes a public method `boolean isBanned(String adapter, String contactId)` that returns true iff a `users` row exists for `(adapter, contact_id)` with `is_banned = true`. Verify: `grep -E '@ApplicationScoped' BanCheck.java` returns ≥1 match AND `grep -E 'public\\s+boolean\\s+isBanned' BanCheck.java` returns ≥1 match AND `grep -E 'SELECT\\s+is_banned\\s+FROM\\s+users\\s+WHERE\\s+adapter\\s*=\\s*\\?\\s+AND\\s+contact_id\\s*=\\s*\\?' BanCheck.java` returns ≥1 match"
  - "BanCheckTest covers: (a) returns true for a seeded `is_banned=true` row on (adapter, contact_id); (b) returns false for a seeded `is_banned=false` row on the same (adapter, contact_id); (c) returns false for an unknown (adapter, contact_id) — fail-closed shape: an absent row is not banned, so the path falls through to step 2's invite gate. `grep -E '@Test' BanCheckTest.java` returns ≥3 matches"
  - "AutoRegisterService is rename-and-narrowed: the UPSERT INSERT changes to write `registration_state = 'group_only'` (NOT `'invited'`); a new public method shape distinguishes the group `@mention` entry point from the (now-forbidden) DM-unknown entry point. Verify: `grep -E '\"group_only\"' AutoRegisterService.java` returns ≥1 match AND `grep -E '\"invited\"' AutoRegisterService.java` returns ZERO matches (the DM-pathway-registered `'invited'` write is removed — `'invited'` writes now happen ONLY through M1-044b's InviteCodeConsumer-success path). The class name and package stay the same; the public method signature MAY change but every existing test in scope is updated to the new shape"
  - "AutoRegisterServiceTest is updated to assert the narrowed behavior: (a) calling the group-registration path with a fresh `(adapter, contact_id)` inserts a row with `registration_state='group_only'` AND `probation_until = NOW() + slow_start_window` (the probation duration value reads via @ConfigProperty `infochat.probation.duration` with `defaultValue = \"24h\"` per `docs/design/03-commands.md` §3.3 laptop profile); (b) the group path is idempotent — a second call for the same (adapter, contact_id) does NOT insert a second row and does NOT modify the existing row's registration_state or probation_until. `grep -E '@Test' AutoRegisterServiceTest.java` returns ≥2 matches"
  - "M1-035d's existing AutoRegisterService production wiring at InboundRouter.onMessage line 235 (`autoRegisterService.resolveOrRegister(msg.sender(), adapterName);`) continues to compile under the narrowed signature. If the public method signature changes, this ticket includes a one-line stub or compatibility shape that preserves the InboundRouter caller's compile until M1-044b lands the proper intake-splice replacement. The reviewer's NEGATIVE-SPACE-CHECK will note InboundRouter.java is NOT in this ticket's files_scope by design — confirming intentional"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-035c's HelpCommandHandlerTest, BundleLoaderTest, M1-035b's InboundRouterTest / StartupGatesTest / InboundRouterNormalizeTest, M1-035d's wiring tests, M1-036's AddSourceCommandHandler tests, M1-037's /summary tests, M1-038/M1-039/M1-040 hardening tests, M1-043's refusal-marker test"
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java
    - infochat-core/src/main/resources/db/migration/V12__invite_code_attempt.sql
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BanCheckTest.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AutoRegisterService.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java
  preserves:
    - all other tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §User ban
  - docs/spec/security.md §Invite-code registration
  - docs/spec/security.md §Rate limiting
  - docs/spec/schema.md §Identity and access
decision_refs:
  - D11
  - D44
  - D45
  - D46
---

# M1-044a: Intake-step services — rate cap, invite consumer, ban check, brute-force migration

## Context

T2-A.1 (invite-gated DM access + intake-step splice + admin
commands) is split across an umbrella M1-044 + three subtickets
(M1-044a / b / c). This subticket lands the **services** that the
M1-044b InboundRouter splice will call at authorization steps 1.5
(rate cap), 2 (invite consume), and 4 (ban check), plus the V12
Flyway migration that backs the brute-force counter at step 2,
plus the rename-and-narrow of M1-035c's `AutoRegisterService`
(the MVP-legacy DM-auto-register path is removed; the group
`@mention` path is preserved and extended to write
`registration_state = 'group_only'` per spec §Authorization
model step 3).

The services land in isolation in this ticket — no
`InboundRouter.onMessage` edit, no admin command handlers. The
production wire-up is M1-044b's responsibility. Tests exercise
each service against a Testcontainers Postgres (the project's
existing IT pattern), proving the behavior in isolation before
the dispatcher consumes it.

Why this is `complexity: high` and `risk: high`: the consume
SQL is race-safe by design (single conditional UPDATE returning
a row count), the brute-force counter must agree on a window
across concurrent attempts (the counter table is the
serialization point), and `AutoRegisterService` previously wrote
`'invited'` for every first DM (the M1-035c MVP-legacy
violation of D44) so the narrow-down has subtle test-fixture
implications. The round_cap is 3 to accommodate the
likely-to-fail-first race-safety + audit-coverage acceptance
items (M1-008a's red-team caught analogous defects).

`security_relevant: true` — every service is a load-bearing
authorization gate per §Authorization model.

`migration_touch: true` — V12 lands the `invite_code_attempt`
counter table; this flag serializes parallel `/m1-tick start`
globally.

## Definition of Done

- `RateCapBucket` is an in-memory token bucket
  `@ApplicationScoped` bean keyed by `(adapter, contact_id)`,
  exposing `boolean tryAcquire(adapter, contactId)`. The cap
  value reads via `@ConfigProperty` so M1-044b's per-profile
  property edits land cleanly. The bucket has a refill window;
  the test pins the cap + refill behavior across two contact
  IDs in parallel.
- `InviteCodeConsumer` is an `@ApplicationScoped` bean exposing
  a single `consume(adapter, contactId, candidateCode)` entry
  point. It runs the spec-committed race-safe conditional
  UPDATE, increments the per-`(adapter, contact_id)`
  brute-force counter on Rejected outcomes, short-circuits with
  `BruteForceThresholdBreached` when the counter ≥ threshold,
  writes the `INVITE_CONSUME` audit row on Accepted outcomes,
  writes the `INVITE_BRUTE_FORCE_BREACH` audit row exactly once
  per breach event.
- `BanCheck` is a small `@ApplicationScoped` bean exposing
  `boolean isBanned(adapter, contactId)`. The SQL is a
  one-shot `SELECT is_banned FROM users WHERE adapter = ? AND
  contact_id = ?`. An absent row returns `false` (fail-closed:
  intake routes to step 2's invite gate).
- V12 Flyway migration creates the `invite_code_attempt`
  counter table + the `(adapter, contact_id, attempted_at DESC)`
  index + the V5-style GRANTs.
- V12 documents the new `INVITE_BRUTE_FORCE_BREACH` audit verb
  as a per-line comment alongside the V5 verb catalogue.
- `AutoRegisterService` is rename-and-narrowed: the DM-side
  upsert is removed (DM-unknown contacts route through
  M1-044b's invite gate now); the group `@mention` path stays
  and writes `registration_state = 'group_only'` and
  `probation_until = NOW() + slow_start_window`.
- Per-service unit tests (`*Test.java`, not `*IT.java`) cover
  the acceptance items in isolation, against a Testcontainers
  Postgres for the services that need it.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **`RateCapBucket` shape.** A `ConcurrentHashMap<Key, Bucket>`
  keyed by `(adapter, contactId)` with a `Bucket` carrying a
  `long lastRefillEpochMillis` and an `AtomicInteger tokens`.
  `tryAcquire` synchronizes on the bucket reference, refills
  by `min(cap, tokens + elapsedRefillUnits)` based on
  `Duration` since `lastRefillEpochMillis`, and decrements on
  acquire. The refill unit is `cap / refillWindowMinutes`
  tokens per minute (operator-tunable via `@ConfigProperty`).
  An eviction sweep removes idle buckets whose `tokens == cap`
  AND `lastRefillEpochMillis < NOW() - evictionThreshold` to
  bound memory; a Quarkus `@Scheduled` method on the bean
  runs the sweep every N minutes (N is a `@ConfigProperty`).
- **Clock injection.** RateCapBucket takes a `@Inject Clock`
  so RateCapBucketTest can advance time without `Thread.sleep`.
  Use `Clock.systemUTC()` as the production CDI producer; the
  test profile produces a `TestClock` the test mutates. The
  shape mirrors M1-035b's startup-gate timing.
- **`InviteCodeConsumer` SQL.** Single transaction holds:
  - `SELECT count(*) FROM invite_code_attempt WHERE adapter = ?
    AND contact_id = ? AND attempted_at > NOW() - <window>` —
    the rate-limit gate.
  - The conditional UPDATE quoted in acceptance item 5.
  - If `RETURNING id` returned a row → `INSERT INTO users (...)
    VALUES (?, ?, ?, ?, 'invited', NOW() + <slow_start_window>)
    ON CONFLICT (adapter, contact_id) DO NOTHING` (the
    ON-CONFLICT is defense-in-depth; the consume path is
    unknown-contact only by construction) + `INSERT INTO
    audit_log (... action='INVITE_CONSUME', target_kind='user', ...)`.
  - If `RETURNING id` returned zero rows → `INSERT INTO
    invite_code_attempt (adapter, contact_id) VALUES (?, ?)`
    (one row per failed attempt; the index supports the
    window-bounded count above).
  - If the over-threshold short-circuit fired → `INSERT INTO
    audit_log (... action='INVITE_BRUTE_FORCE_BREACH',
    target_kind='user', target_id=NULL, ...)` exactly once.
    The "exactly once per breach event" rule is implemented as
    a sentinel: the bean keeps an in-memory `ConcurrentHashSet<Key>`
    of `(adapter, contact_id)` tuples that have ALREADY had a
    breach audit row written within the current window; the
    set is pruned alongside the bucket eviction sweep. (The
    spec says "an audit row records the threshold breach,"
    singular per breach, not per over-threshold attempt — the
    rate-limited drop counter, in contrast, increments on
    every attempt.)
- **The drop counter `invite_drop_total`** (per `docs/design/04-security.md`
  §4.5 "The limit prevents a patient brute-force search ... The
  drop counter (`invite_drop_total`) increments on every
  invalid attempt regardless of rate-limit state") is a
  Micrometer counter Quarkus registers via
  `MeterRegistry.counter("invite_drop_total")`. This ticket
  registers and increments it; an acceptance grep confirms.
  (Optional: if Micrometer integration is heavier than
  expected, defer the Prometheus counter wiring to a follow-up
  ticket and write a one-line comment naming the deferred
  counter — `// TODO M1-NNN: register `invite_drop_total`
  Micrometer counter`.)
- **`BanCheck` shape.** One `DataSource` injection, one
  prepared statement, one boolean result. Fail-closed return
  on absent row (`false`, meaning "not banned" — step 2's
  invite gate fires on the same absent-row condition).
- **V12 migration.** Mirror V5's per-table GRANT block; no
  application-level state outside the table itself. The
  table's only producers are `InviteCodeConsumer` (INSERT on
  Rejected outcomes) and the operator-side `infochat_admin`
  for TRUNCATE during sustained-attack incident response. The
  index supports `SELECT count(*) ... WHERE adapter = ? AND
  contact_id = ? AND attempted_at > NOW() - INTERVAL '<window>'`.
- **`AutoRegisterService` rename-and-narrow.** The class stays
  at the same package and same class name to minimize churn
  (the M1-035c precedent named it for the group + DM path; v1
  narrows it to the group-only path without renaming the type).
  Public method shape: rename `resolveOrRegister(Identity,
  String)` → `resolveOrRegisterGroup(Identity, String)` OR
  keep the same method name but change behavior to refuse to
  fire when the scope shape is not group-ish (handler-friendly
  contract: the method is now only meaningful from the group
  `@mention` path). Decide between rename and behavior-change
  during implementation; both shapes meet acceptance item 13
  as long as the `'invited'` write disappears and `'group_only'`
  + `probation_until` are written.
  - **InboundRouter compat.** The current InboundRouter.onMessage
    call site at line 235 is `autoRegisterService.resolveOrRegister(msg.sender(),
    adapterName);` — this ticket leaves InboundRouter.java
    OUT of files_scope; the call site MUST still compile under
    the narrowed signature so the build is green at M1-044a's
    commit (M1-044b removes that call entirely and replaces
    with the new intake splice). Simplest path: keep the
    `resolveOrRegister(Identity, String)` method signature; let
    its body either no-op (if the new contract says "group
    only" and InboundRouter calls it from the DM-pathway
    line 235) or write 'group_only' unconditionally for now
    (acceptable interim if the InboundRouter call site is in
    the DM-pathway only — M1-044b removes the call site
    before any production deployment).
  - The simplest viable shape: add a NEW method
    `resolveOrRegisterGroup(Identity, String)` and keep
    `resolveOrRegister(Identity, String)` as a deprecated
    pass-through that calls the new method (writing 'group_only').
    The deprecation is one annotation; M1-044b removes the
    call site and a follow-up ticket (or M1-044b itself) deletes
    the deprecated method. Document the interim choice in the
    body's "Implementation notes" of M1-044a.
- **Audit-write helper.** This ticket writes audit rows
  directly from InviteCodeConsumer via `INSERT INTO audit_log
  (...)` — same shape as M1-036's `/add-source` AUDIT verb
  write and M1-039's writes. The M1-041 (deferred)
  AuditLogWriter consolidation comes later. Each INSERT
  carries: `actor_user_id` (NULL for invite-consume on a fresh
  unknown contact; populated for the threshold breach if the
  consumer can resolve a `users.id` for the throwaway
  unknown-contact — typically NULL), `actor_contact_id`,
  `actor_adapter`, `action`, `target_kind='user'`, `target_id`
  (the new `users.id` for INVITE_CONSUME; NULL for breach),
  `target_contact_id`, `scope_id` (NULL — DM scope),
  `request_id` (NULL; the per-request-id wire-up is M1-044b's
  intake splice).
- **No new bundle keys here.** The services return typed
  records / enums; M1-044b maps each outcome to the
  bundle-keyed fixed reply at the splice point.

## Big-picture notes

- **The services are dead until M1-044b wires them.** That is
  expected — the M1-035 / M1-008 umbrella + subticket idiom
  works exactly this way. The per-service tests exercise each
  bean in isolation; the M1-044 umbrella's IT exercises the
  wired-up roundtrip.
- **The V12 migration is the only Flyway change in T2-A.**
  M1-044b and M1-044c add no migrations; M1-045 and M1-046
  also add no migrations (M1-046 consumes the V5 last-admin
  trigger as-is).
- **The brute-force counter design.** A separate table (not a
  counter column on `users` or `invite_code`) because the
  spec scopes the counter to `(adapter, contact_id)` — which
  for unknown contacts has no `users` row to attach to. The
  window-bounded `SELECT count(*) WHERE attempted_at > NOW() -
  INTERVAL '<window>'` is the natural shape; the
  `attempted_at DESC` index supports it. Rows accumulate
  forever in v1 (no TTL); the operator-side TRUNCATE under
  the admin role is the incident-response purge path. A future
  ticket may add a Flyway `pg_cron`-based retention sweep if
  the table size becomes a concern.
- **The drop counter vs the brute-force counter.** Two
  different counters with two different lifetimes:
  - `invite_drop_total` (Micrometer) increments on every
    Rejected outcome including over-threshold ones —
    operator-visible flood signal, in-memory only.
  - `invite_code_attempt` (DB table) increments on every
    Rejected outcome up to threshold; the threshold check
    reads its row count — security-visible patient-brute-force
    signal, durable.

## Out-of-scope expansion

- **InboundRouter.onMessage intake splice.** M1-044b
  territory. This ticket leaves the router file untouched.
- **Admin command handlers.** /ban, /unban, /invite create/list/revoke
  are M1-044c's commit. /vouch is M1-045; /grant-admin and
  /revoke-admin are M1-046.
- **The umbrella's roundtrip IT.** M1-044's commit.
- **Bundle keys for fixed replies.** M1-044b authors them.
- **Per-profile rate-cap and probation-duration property
  values.** M1-044b authors the per-profile shape in
  application.properties; this ticket's services use sensible
  `defaultValue` strings on `@ConfigProperty`.
- **Probation graduation / /vouch.** M1-045 territory.
- **Last-admin trigger.** Already on disk at V5; M1-046
  consumes it.
- **Step 7 DM-gate carve-out for `group_only` users.**
  M1-044b territory (it lands at the permission step in
  InboundRouter).

## Authorized test changes

- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java` — M1-035c's existing test asserts the MVP-legacy DM auto-register behavior (writing `'invited'` to a fresh `(adapter, contact_id)`). This ticket narrows the service to group-only and writes `'group_only'`; the test's existing assertions are updated to reflect the new contract. The reason this is an authorized modification rather than a test-integrity violation: M1-035c's test asserted the MVP-legacy DM behavior that the spec D44 §Invite-code registration explicitly forbids. The previous assertion is provably-wrong-against-spec, not a regression target. The test is rewritten to assert the spec-correct group-only path.

## Alternatives considered

- **Land the rate-cap bucket as a `@Scheduled` Quarkus job
  with no in-memory state, persisting bucket state to a
  Redis-like store.** Rejected — v1 has no Redis dependency
  and the in-memory + ConcurrentHashMap shape meets the
  spec's "per-`(adapter, contact_id)` rate cap" without
  durability ambitions. Buckets reset on Provider restart;
  the cap is a flood-bound, not a security boundary.
- **Land the brute-force counter as an in-memory
  ConcurrentMap rather than a DB table.** Rejected — the
  threshold breach must write an audit row; the audit log is
  the durable signal a future operator audit consults. The
  in-memory counter would lose state across restarts and an
  attacker who flooded just before a deploy would get a fresh
  budget on the next process. DB-backed state with index-sup-
  ported window-bounded count is the same shape as
  `audit_log` itself.
- **Land the consume audit row via a stored procedure (mirroring
  V5's `delete_preban_user`).** Rejected for v1 — the audit
  row + UPDATE + counter increment all run in the
  application-side transaction; the Provider role already has
  the necessary GRANTs (`INSERT` on `audit_log`, `UPDATE` on
  `invite_code`, `INSERT` on `invite_code_attempt`). A stored
  procedure would buy nothing in audit isolation (the role
  matrix already constrains the writes) and would couple this
  ticket to a Flyway migration that defines the procedure.
- **Skip the rename-and-narrow and let `AutoRegisterService`
  also write `'group_only'` for DM unknowns.** Rejected — D44
  forbids it. The DM-unknown path MUST route through the
  invite gate; writing any `users` row on a DM-unknown
  inbound contradicts §Authorization model step 2 "No
  registration, no LLM, no DB write beyond the drop counter."
