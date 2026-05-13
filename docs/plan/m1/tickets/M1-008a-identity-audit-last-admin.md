---
id: M1-008a
title: Identity, audit, last-admin trigger (§2.1)
status: done
created: 2026-05-13
last_updated: 2026-05-13
clarity_check:
  date: 2026-05-13
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-05-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 1146
      removed: 9
redteam_findings:
  - date: 2026-05-13
    category: AUDIT-EVASION
    severity: high
    promise: |
      docs/spec/security.md §DB roles / Invariant 7 ("audit-before-effect"):
      "Writes the UNBAN_PREBAN_DELETE audit row BEFORE the DELETE
      (audit-before-effect, Invariant 7)." Spec §User ban commits to the
      pre-ban deletion being audit-logged.
    gap: |
      infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:485-498
      — delete_preban_user writes the audit row via
      INSERT INTO audit_log ... SELECT p_actor_id, a.contact_id, a.adapter, ...
      FROM users u JOIN users a ON a.id = p_actor_id WHERE u.id = p_user_id.
      When p_actor_id does not match any row in users, the JOIN returns zero
      rows, the INSERT inserts zero rows (no FK violation, no error), and the
      subsequent DELETE FROM users WHERE id = p_user_id AND
      registration_state = 'preban' proceeds unconditionally. The preban user
      is deleted with no audit trail.
    repro: |
      A caller (Provider role, which has EXECUTE on the procedure) invokes
      CALL delete_preban_user('<valid-preban-uuid>', '<nonexistent-actor-uuid>').
      Result: the preban row is removed; SELECT count(*) FROM audit_log WHERE
      action='UNBAN_PREBAN_DELETE' is unchanged. Anyone with the procedure
      EXECUTE bit can launder preban deletions with no audit signal.
    suggested_fix_class: audit-log-coverage
  - date: 2026-05-13
    category: PERM-ESCAL
    severity: high
    promise: |
      docs/spec/security.md §Authorization model: "Last-admin protection
      (bot admin only). Cannot revoke the only bot admin's is_admin, **cannot
      ban the only bot admin, cannot ban self**. ... Enforced at the trigger
      layer, not just the command layer, so a buggy command cannot bypass it."
    gap: |
      infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:199-219
      — the trg_last_admin_protection_update trigger fires only on
      (OLD.is_admin = TRUE AND NEW.is_admin = FALSE) or
      (OLD.is_banned = FALSE AND NEW.is_banned = TRUE AND OLD.is_admin = TRUE)
      and verifies "remaining non-banned admins exist". It has no signal of
      *which* connection / actor issued the UPDATE, so it cannot enforce the
      spec's "cannot ban self" rule at the trigger layer. A bot admin in a
      multi-admin deployment can UPDATE users SET is_banned = TRUE WHERE
      id = <self> and the trigger will permit it as long as another admin
      remains.
    repro: |
      Two bot admins exist (Alice, Bob). Alice's command path has a bug or is
      compromised at the application layer, issuing UPDATE users SET is_banned
      = TRUE WHERE id = '<alice>'. The trigger sees one remaining admin (Bob),
      so it does not raise. Alice has now banned herself, contradicting the
      spec's "cannot ban self" promise that is explicitly meant to be enforced
      at the trigger layer.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-13
    category: PERM-ESCAL
    severity: medium
    promise: |
      docs/spec/security.md §User ban: "Bot admin can /ban <contact> /
      /unban <contact>." Spec §Trust boundaries (3): "Permission checks run in
      deterministic Java." The §DB roles split is "least-privilege" so "a
      SQL-injection bug in the Provider cannot delete posts ... or read raw
      quarantine originals," with the Provider's only DELETE-on-users path
      being the audit-attached delete_preban_user carve-out.
    gap: |
      infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:473-503
      — delete_preban_user is SECURITY DEFINER, granted EXECUTE to
      infochat_provider, and performs no check that p_actor_id corresponds to
      a user with is_admin = TRUE. The procedure trusts the caller to pass a
      bona-fide bot-admin UUID. A Provider-side SQL injection (or any code
      path that reaches the procedure with attacker-controlled p_actor_id) can
      delete arbitrary preban rows attributed to any UUID the attacker
      chooses, bypassing the spec's "permission checks run in deterministic
      Java" trust boundary.
    repro: |
      A Provider-tier code path takes p_actor_id from a less-trusted source
      (or a SQLi in any Provider query reaches CALL delete_preban_user(...)).
      Attacker calls CALL delete_preban_user('<some-preban-uuid>', '<any-non-
      admin-user-uuid>'). The procedure deletes the row and writes (or, per
      the AUDIT-EVASION finding, omits) an audit row attributing the deletion
      to a non-admin user. The hardened DB-role boundary is weakened: SECURITY
      DEFINER widens the Provider's privilege beyond the GRANT matrix without
      the procedure body re-establishing the missing authorization check.
    suggested_fix_class: missing-auth-check
  - date: 2026-05-13
    category: PERM-ESCAL
    severity: medium
    promise: |
      docs/spec/security.md §DB roles: the role split is least-privilege;
      SECURITY DEFINER procedures are the only path that elevates privilege.
      The spec frames delete_preban_user as a narrow carve-out, not a general
      elevation surface.
    gap: |
      infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:473-500
      — delete_preban_user is declared SECURITY DEFINER but has no
      SET search_path = public, pg_catalog (or equivalent) in its definition.
      The classic SECURITY DEFINER attack: an attacker who can create objects
      in any schema earlier in the caller's search_path can shadow users,
      audit_log, or built-in functions referenced inside the procedure,
      hijacking the procedure's elevated execution.
    repro: |
      Future migration or operator action grants CREATE on public (or any
      schema) to infochat_provider (a plausible misconfiguration; spec only
      enumerates table-level grants). Attacker creates public.audit_log as a
      view/table they can read/write, then issues CALL delete_preban_user(...);
      the SECURITY DEFINER body's unqualified audit_log reference resolves to
      the attacker's shadow object. The "audit row written before delete"
      invariant is silently violated.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-13
    category: AUDIT-EVASION
    severity: medium
    promise: |
      docs/spec/security.md §DB roles: Provider role has INSERT-only on
      audit_log; "audit log records *intent* (command name, actor, scope,
      target)". The intent is that audit rows faithfully attribute actions to
      their originator.
    gap: |
      infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:354-368
      — audit_log allows actor_user_id, actor_contact_id, actor_adapter to be
      set freely on INSERT, with no consistency check (e.g. that
      (actor_user_id, actor_adapter, actor_contact_id) matches a real users
      row, or that the connecting role corresponds to the claimed actor).
      Combined with the GRANT INSERT to both Provider and Collector roles, any
      code path with an audit-INSERT capability can mint audit rows that name
      an arbitrary admin as actor for an arbitrary action — including actions
      like GRANT_ADMIN, BAN, UNBAN_PREBAN_DELETE that the role itself has no
      other capability to perform.
    repro: |
      Compromised or buggy Collector path issues
      INSERT INTO audit_log (actor_user_id, action, target_kind, target_id)
      VALUES ('<bot-admin-uuid>', 'GRANT_ADMIN', 'user', '<attacker-uuid>').
      No GRANT exists on users for the Collector to actually grant admin, but
      the audit record now suggests a bot admin did so. An operator's /audit
      review sees a falsified action attributed to a real admin.
    suggested_fix_class: trust-boundary-tightening
blocked_by:
  - M1-005
  - M1-006
  - M1-017
files_budget: 8
files_scope:
  - infochat-core/pom.xml
  - infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
  - infochat-core/src/test/java/io/infochat/core/schema/PostgresSchemaTestBase.java
  - infochat-core/src/test/java/io/infochat/core/schema/LastAdminTriggerTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/LastAdminConcurrentRevocationTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/AuditLogAppendOnlyTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/GroupAdminUniqueIndexTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/DeletePrebanUserTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java (the M1-008 umbrella's cross-cutting per-(user, scope) isolation IT — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention; this subticket asserts trigger correctness only, the cross-table invariant lives there)
  - any change under infochat-core/src/main/resources/db/migration/V6__*.sql or V7__*.sql (the sources/tags catalogues + joins/post tables ship in M1-008b / M1-008c respectively; this subticket is identity + audit only)
  - any Java entity class, Hibernate / Panache mapping, repository, service, or DAO (NO application code — Flyway migrations + per-table GRANTs + trigger bodies + SQL-level tests ONLY; the entity layer lands in later T1-B/C/D/E/F tickets)
  - any LISTEN/NOTIFY trigger or channel wiring (the outbox NOTIFY plumbing is T1-C / a later ticket; audit_log carries no NOTIFY in v1)
  - any Provider startup logic that consumes the schema (the @Startup admin-bootstrap bean per docs/spec/deployment.md §Bootstrap behavior is a separate Provider-side ticket; this subticket creates the table the bootstrapper will later write to)
  - any audit-log row writer or redaction-hook Java code (the redactor lives at the application layer; the closed catalogue of regex shapes per docs/spec/security.md §Secrets handling is implemented in the audit-write path, not in the schema migration)
  - any signal-cli / SimpleX-CLI / inmemory adapter-side identity wiring (the adapter integration that resolves contact_id → users row lives in the T1-E messaging tickets)
  - any retention sweep / pruner / GC schedule against audit_log or users (admin-driven retention runs under the infochat_admin role and is operator-side per docs/spec/security.md §DB roles; the schema commitment here is the append-only trigger guard plus the role grants)
  - any change to the V1..V4 migrations already on disk (M1-005, M1-006, M1-009, M1-016 own those; this subticket adds V5 only)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V5__identity_audit.sql exists and contains CREATE TABLE statements for all of: users, groups, group_membership, invite_code, audit_log (grep -E 'CREATE TABLE\\s+(users|groups|group_membership|invite_code|audit_log)' V5 returns exactly five matches)"
  - "V5 declares the (adapter, contact_id) UNIQUE on users — grep -E 'UNIQUE\\s*\\(\\s*adapter\\s*,\\s*contact_id\\s*\\)' returns at least one match (decision D46)"
  - "V5 declares the registration_state CHECK constraint with the closed four-value set — grep -E \"registration_state\\s+IN\\s*\\(\\s*'preban'\\s*,\\s*'group_only'\\s*,\\s*'invited'\\s*,\\s*'vouched'\\s*\\)\" returns at least one match (decisions D44, D45)"
  - "V5 declares the partial unique index enforcing Invariant 3 (at most one group admin per group) — grep -E 'CREATE UNIQUE INDEX\\s+\\w+\\s+ON\\s+group_membership\\s*\\(group_id\\)\\s+WHERE\\s+is_group_admin' returns at least one match"
  - "V5 declares the last-admin protection trigger function and binds it to BOTH the BEFORE UPDATE and BEFORE DELETE paths on users — grep -E 'CREATE TRIGGER\\s+\\w+\\s+BEFORE\\s+(UPDATE|DELETE)\\s+ON\\s+users' returns exactly two matches"
  - "V5's last-admin trigger function body contains LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE (the serialization requirement of Invariant 2) — grep -E 'LOCK\\s+TABLE\\s+users\\s+IN\\s+SHARE\\s+ROW\\s+EXCLUSIVE\\s+MODE' returns at least one match"
  - "V5 declares the audit_log append-only trigger that REJECTs any UPDATE or DELETE on audit_log (Invariant 10) — grep -E 'CREATE TRIGGER\\s+\\w+\\s+BEFORE\\s+(UPDATE|DELETE)\\s+ON\\s+audit_log' returns exactly two matches AND the function body contains a RAISE EXCEPTION line (grep -E 'RAISE EXCEPTION' returns at least one match in V5)"
  - "V5 declares the audit_log.target_kind CHECK constraint with the nine-value spec-closed set — grep -E \"target_kind\\s+IN\\s*\\(\\s*'user'\\s*,\\s*'group'\\s*,\\s*'source'\\s*,\\s*'post'\\s*,\\s*'invite'\\s*,\\s*'quarantine'\\s*,\\s*'asset'\\s*,\\s*'memory'\\s*,\\s*'system'\\s*\\)\" returns at least one match"
  - "V5 declares the closed set of audit_log.action verbs by emitting them in the migration's per-verb commentary (one comment line per verb, grep-checkable) — grep -cE '^\\-\\-\\s+(BOOTSTRAP_ADMIN|BOOTSTRAP_SOURCE_LOAD|BOOTSTRAP_ASSET_LOAD|GRANT_ADMIN|REVOKE_ADMIN|BAN|UNBAN|UNBAN_PREBAN_DELETE|VOUCH|INVITE_CREATE|INVITE_REVOKE|INVITE_CONSUME|PROMOTE_GROUP_ADMIN|DEMOTE_GROUP_ADMIN|ADD_SOURCE|REMOVE_SOURCE|SOURCE_ENABLE|SOURCE_DISABLE|APPROVE_QUARANTINE|REJECT_QUARANTINE|FORGET|SET_LANG|SET_TIMEZONE)\\b' V5 returns >= 23 (the count in docs/design/02-schema.md §2.1.8)"
  - "V5 declares the delete_preban_user(UUID, UUID) stored procedure and grants EXECUTE to infochat_provider — grep -E 'CREATE\\s+(OR REPLACE\\s+)?PROCEDURE\\s+delete_preban_user' returns at least one match AND grep -E 'GRANT\\s+EXECUTE\\s+ON\\s+PROCEDURE\\s+delete_preban_user' returns at least one match"
  - "V5 declares the audit_log_view and grants SELECT on it to infochat_provider (NOT on audit_log) — grep -E 'CREATE\\s+(OR REPLACE\\s+)?VIEW\\s+audit_log_view' returns at least one match AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+audit_log_view\\s+TO\\s+infochat_provider' returns at least one match"
  - "V5 grants are aligned with docs/spec/security.md §DB roles — grep -E 'GRANT\\s+INSERT(\\s*,\\s*\\w+)*\\s+ON\\s+audit_log\\s+TO\\s+infochat_(collector|provider)' returns at least two matches (one per role; INSERT-only on the raw audit_log table) AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+audit_log\\s+TO\\s+infochat_provider' returns ZERO matches (the Provider's read path is the view, not the raw table)"
  - "infochat-core/pom.xml adds test-scope dependencies on Testcontainers Postgres, the Postgres JDBC driver, and Flyway core (grep -E '<artifactId>(testcontainers|postgresql|flyway-core|flyway-database-postgresql)</artifactId>' infochat-core/pom.xml returns at least three matches across the listed artifacts)"
  - "infochat-core/pom.xml configures maven-failsafe-plugin so *IT tests under infochat-core run during mvn verify (grep -E '<artifactId>maven-failsafe-plugin</artifactId>' returns at least one match in infochat-core/pom.xml) — this is the wiring stub the M1-008 umbrella IT depends on; this subticket authors it so the umbrella's diff stays at the locked 2-file budget"
  - "LastAdminTriggerTest.java exercises the single-transaction revoke path: a transaction that sets is_admin=FALSE on the only is_admin=TRUE row raises an exception containing the literal substring 'last_admin_protection' (grep -E 'last_admin_protection' in the test file returns at least one match AND the test asserts on the raised exception message)"
  - "LastAdminConcurrentRevocationTest.java opens TWO JDBC Connections against the testcontainers Postgres, seeds exactly two is_admin=TRUE rows, then runs two concurrent transactions each revoking a different admin, and asserts that EXACTLY ONE commits while the other raises 'last_admin_protection' (grep -E 'Connection\\s+\\w+1|Connection\\s+\\w+2|getConnection' returns at least two matches across the file; the test asserts the final SELECT COUNT(*) WHERE is_admin = TRUE AND is_banned = FALSE is 1, not 0)"
  - "AuditLogAppendOnlyTest.java asserts that an UPDATE or DELETE against audit_log raises an exception (the Invariant-10 trigger guard fires) — grep -E 'UPDATE\\s+audit_log|DELETE\\s+FROM\\s+audit_log' returns at least one match in the test, and the test asserts the SQLException message contains 'append-only'"
  - "GroupAdminUniqueIndexTest.java asserts that inserting a SECOND is_group_admin=TRUE row for the same group raises a unique-violation exception (the partial unique index from Invariant 3 fires)"
  - "DeletePrebanUserTest.java exercises the carve-out: the CALL delete_preban_user($id, $actor) on a row with registration_state='preban' succeeds AND writes an audit_log row with action='UNBAN_PREBAN_DELETE'; calling it against a non-preban row raises an exception with the literal substring 'is not in preban state'"
  - "mvn -B -pl infochat-core -am test exits 0; surefire reports for infochat-core show at least one test executed per the five new *Test.java files (grep -rE 'Tests run: [1-9]' infochat-core/target/surefire-reports returns at least five matches across the test classes)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003 @QuarkusTest stubs, M1-007a IngestSpisLoadTest, and every M1-007 cross-module test continue to pass alongside the new V5 schema and its trigger tests"
test_plan:
  adds:
    - infochat-core/src/test/java/io/infochat/core/schema/PostgresSchemaTestBase.java (Testcontainers + Flyway boot helper; provides a Connection-yielding fixture the schema-level tests share, so each test class doesn't re-spin its own container)
    - infochat-core/src/test/java/io/infochat/core/schema/LastAdminTriggerTest.java (single-tx revoke + single-tx ban-the-only-admin paths; both must raise the trigger exception)
    - infochat-core/src/test/java/io/infochat/core/schema/LastAdminConcurrentRevocationTest.java (two-connection race: seed two admin rows, both transactions revoke different rows, exactly one commits per Invariant 2 serialization)
    - infochat-core/src/test/java/io/infochat/core/schema/AuditLogAppendOnlyTest.java (UPDATE and DELETE against audit_log both raise the Invariant-10 trigger guard)
    - infochat-core/src/test/java/io/infochat/core/schema/GroupAdminUniqueIndexTest.java (Invariant 3 partial unique index rejects a second is_group_admin row for the same group)
    - infochat-core/src/test/java/io/infochat/core/schema/DeletePrebanUserTest.java (carve-out happy path + non-preban rejection; verifies audit-before-effect by reading the audit_log row written by the procedure)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
spec_refs:
  - docs/spec/schema.md §Identity and access
  - docs/spec/schema.md §Operational
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Trust boundaries
  - docs/design/02-schema.md §2.1 Identity & access
  - docs/design/02-schema.md §2.1.1 users
  - docs/design/02-schema.md §2.1.2 Last-admin protection trigger
  - docs/design/02-schema.md §2.1.3 groups
  - docs/design/02-schema.md §2.1.4 group_membership
  - docs/design/02-schema.md §2.1.5 invite_code
  - docs/design/02-schema.md §2.1.6 delete_preban_user
  - docs/design/02-schema.md §2.1.7 audit_log
  - docs/design/02-schema.md §2.1.8 audit_log.action enum
  - docs/design/02-schema.md §2.1.9 audit_log_view
decision_refs:
  - D44
  - D45
  - D46
---

# M1-008a: Identity, audit, last-admin trigger (§2.1)

## Context

First subticket of the M1-008 umbrella (per `docs/process/workflow.md`
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-008 splits "land the MVP schema" into three substantively-disjoint
slices plus a whole-topic per-(user, scope) isolation IT on the
umbrella. This subticket lands the foundational `§2.1 Identity &
access` slice: the `users` row, the `groups` / `group_membership`
join with the at-most-one-group-admin partial unique index
(Invariant 3), the `invite_code` table that backs the DM invite
gate (decision D44), the `delete_preban_user` stored procedure that
implements the single Invariant 2 carve-out, the `audit_log` table
with its append-only Invariant 10 guard, the closed `audit_log.action`
verb set, and the `audit_log_view` redacted Provider read path.
M1-008b adds sources/tags; M1-008c adds joins + post; M1-008's
umbrella ships the cross-cutting isolation IT.

This is the load-bearing slice for the bot's authorization model.
**Invariant 2's last-admin protection trigger** is the only barrier
against an "admin-empty deployment" — a misconfigured `/revoke-admin`
or `/ban` against the last admin would leave the deployment
unrecoverable without operator-side `psql`. The trigger MUST be
trigger-layer (not just command-layer) per `docs/spec/schema.md`
§Invariants — Invariant 2, AND it MUST serialize concurrent
revocations via `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` or
equivalent (Invariant 2's serialization clause). A naive `SELECT
COUNT(*) WHERE is_admin = true` under READ COMMITTED would allow
two concurrent transactions to both observe a pre-state of 2 and
both succeed, leaving zero admins.

This is a **schema-only** ticket. No Java entity classes, no
repositories, no services, no Provider startup logic. The
application layer lands in later Tier-1 tickets (`/grant-admin`,
`/ban`, the bootstrap-admin `@Startup` bean, the invite-code
intake path, the audit-log writer with its redaction hook). The
schema commitment here is what the application layer will write
to once it exists.

## Definition of Done

- A new Flyway migration
  `infochat-core/src/main/resources/db/migration/V5__identity_audit.sql`
  creates, in one transactional migration:
  - `users` with the (adapter, contact_id) UNIQUE per decision D46,
    the `registration_state` CHECK constraint over the closed
    four-value set per `docs/spec/schema.md` §Identity and access
    (Registration-state transitions), `probation_until` per decision
    D45, and the partial indices on `is_admin` and `is_banned`.
  - `groups` with the (adapter, upstream_group_id) UNIQUE, `timezone`
    default `'UTC'`, and the `removed_at` soft-clear column.
  - `group_membership` with the partial unique index
    `one_admin_per_group ON group_membership(group_id) WHERE
    is_group_admin = TRUE` (Invariant 3), the `removed_at` soft-clear
    column, and the `trg_clear_group_admin_on_remove` trigger that
    clears `is_group_admin` in the same transaction that sets
    `removed_at` (frees the partial-unique-index slot).
  - `invite_code` per decision D44 with the `invite_type` /
    `expected_contact_id` iff-CHECK, the `status` CHECK over the
    three-value set, and the partial index
    `idx_invite_code_pending(adapter, code) WHERE status = 'PENDING'`
    that backs the conditional-UPDATE consume.
  - The last-admin protection trigger function `trg_last_admin_protection_update`
    and a sibling `trg_last_admin_protection_delete`, BOTH containing
    `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` at the top of
    the function body. The UPDATE trigger is bound to `BEFORE
    UPDATE ON users`; the DELETE trigger to `BEFORE DELETE ON users`.
    Each function counts `is_admin = TRUE AND is_banned = FALSE
    AND id <> NEW.id` (or `OLD.id` on the DELETE path) and raises
    an exception when the remaining count would fall below 1.
    Exception messages contain the literal substring
    `last_admin_protection` so tests can assert on it.
  - The `delete_preban_user(p_user_id UUID, p_actor_id UUID)`
    stored procedure with `LANGUAGE plpgsql`, `SECURITY DEFINER`,
    that (a) reads `registration_state` `FOR UPDATE`, (b) raises an
    exception containing `is not in preban state` when the row is
    not in `preban`, (c) writes an `audit_log` row with
    `action = 'UNBAN_PREBAN_DELETE'` (audit-before-effect, Invariant
    7), and (d) issues the `DELETE FROM users WHERE id = $1 AND
    registration_state = 'preban'`. `REVOKE ALL` from `PUBLIC`;
    `GRANT EXECUTE` to `infochat_provider`.
  - `audit_log` with the spec-closed `target_kind` CHECK over the
    nine values, the five indices listed in `docs/design/02-schema.md`
    §2.1.7, and the append-only trigger function
    `trg_audit_log_append_only` bound to BOTH `BEFORE UPDATE` and
    `BEFORE DELETE` paths. The function body raises
    `audit_log is append-only` (literal substring `append-only`).
  - A per-verb commentary block in the migration listing **every**
    action verb from `docs/design/02-schema.md` §2.1.8 as a SQL
    line-comment (`-- VERB_NAME`). This is grep-checkable and
    documents the closed enum at the migration layer; the CHECK
    constraint enforcement of `audit_log.action` is the
    application layer's job (the verb set is open-ended for v2
    additions; the schema does not pin it).
  - The `audit_log_view` view per `docs/design/02-schema.md` §2.1.9,
    using placeholder calls to `redact_contact_id(...)` and
    `redact_secrets_jsonb(...)` — the redactor function bodies are
    out of this ticket (they belong to the audit-write path's
    redaction-hook ticket) but the view DDL exists so the Provider
    role's `GRANT SELECT ON audit_log_view` resolves. **The
    redactor functions MUST be declared with stub bodies** (e.g.,
    `RETURN input;`) so the view is valid; the application-layer
    redactor ticket will `CREATE OR REPLACE FUNCTION` with the real
    body. This is the schema commitment that keeps the v1 grant
    matrix in `docs/spec/security.md` §DB roles structurally
    valid from day one.
  - **Per-table GRANTs** aligned with `docs/spec/security.md` §DB
    roles:
    - `users`, `groups`, `group_membership`: `SELECT, INSERT,
      UPDATE` to `infochat_provider` (Provider writes user state);
      `SELECT` to `infochat_collector` (read-only for ingest
      decisions). `DELETE` is **revoked** from both
      (per §DB roles — "DELETE on users is revoked from
      infochat_collector and infochat_provider"; the
      `delete_preban_user` stored proc is the single permitted
      DELETE path).
    - `invite_code`: `SELECT, INSERT, UPDATE` to
      `infochat_provider` (invite issuance + consume); no
      Collector access (Collector never touches invite codes).
    - `audit_log`: `INSERT` to both `infochat_provider` AND
      `infochat_collector` (both services emit audit rows). NO
      `SELECT` on the raw table for the Provider — that path is
      the view. `UPDATE` and `DELETE` are revoked from both
      service roles (Invariant 10).
    - `audit_log_view`: `SELECT` to `infochat_provider` only.
- `infochat-core/pom.xml` gains test-scope dependencies on:
  - `org.testcontainers:testcontainers`,
    `org.testcontainers:postgresql`, and
    `org.testcontainers:junit-jupiter` so the schema tests can
    spin up a real Postgres 16+ container.
  - `org.postgresql:postgresql` (test scope) for JDBC.
  - `org.flywaydb:flyway-core` plus
    `org.flywaydb:flyway-database-postgresql` (test scope) so the
    test helper can apply migrations to the container.
  - The `maven-failsafe-plugin` is wired so `*IT.java` files run
    under `mvn verify` (the M1-008 umbrella's
    `PerScopeIsolationIT` depends on this wiring; this subticket
    authors the wiring as part of its own pom.xml edit so the
    umbrella's diff stays at the locked 2-file budget).
- Five new SQL-level test classes under
  `infochat-core/src/test/java/io/infochat/core/schema/`:
  - `PostgresSchemaTestBase.java` — shared Testcontainers
    Postgres + Flyway-apply helper (`@Container` lifecycle, a
    `Connection` factory, a `truncateAll` reset between tests).
    Used by every schema-level test in this ticket and by the
    umbrella IT.
  - `LastAdminTriggerTest.java` — single-tx revoke and single-tx
    ban-the-only-admin paths; both must raise the trigger
    exception with `last_admin_protection` in the message.
  - `LastAdminConcurrentRevocationTest.java` — two-JDBC-connection
    race: seed exactly two admins, two transactions concurrently
    revoke different rows, exactly one commits per the
    SHARE ROW EXCLUSIVE serialization. The final
    `SELECT COUNT(*) WHERE is_admin = TRUE AND is_banned = FALSE`
    is asserted to be 1 (not 0 — that would be the bug Invariant
    2's serialization clause forbids).
  - `AuditLogAppendOnlyTest.java` — `UPDATE audit_log` and
    `DELETE FROM audit_log` both raise the Invariant-10 trigger
    guard; the SQLException message contains `append-only`.
  - `GroupAdminUniqueIndexTest.java` — inserting a second
    `is_group_admin = TRUE` row for the same group raises a
    unique-violation exception (Invariant 3's partial unique
    index fires).
  - `DeletePrebanUserTest.java` — the procedure deletes a
    `registration_state = 'preban'` row and writes the
    `UNBAN_PREBAN_DELETE` audit row in the same transaction;
    against a non-preban row it raises with `is not in preban
    state`.
- `mvn -B clean verify` from the repo root exits 0. All M1-003,
  M1-007, M1-007a/b/c tests continue to pass; the five new test
  classes execute against a Testcontainers Postgres and pass.

## Implementation notes

- **One migration file, one transaction.** Flyway runs each
  migration in a single transaction by default; the entire `V5`
  body — tables, triggers, views, GRANTs — applies atomically.
  Splitting it across `V5a__users.sql` / `V5b__groups.sql` etc. is
  rejected: the migrations are versioned by integer, and a
  cross-file partial failure leaves the schema in a half-applied
  state Flyway has no way to recover from. Keep it one file.
- **Migration version is V5.** V1..V4 already live on disk under
  `infochat-core/src/main/resources/db/migration/`:
  `V1__init.sql` (M1-005), `V2__roles.sql` (M1-006),
  `V3__heartbeat.sql` (M1-009), `V4__nologin.sql` (M1-016). The
  next free integer is V5. M1-008b will take V6 and M1-008c will
  take V7; do not collapse those ranges.
- **Triggers are the load-bearing safety surface.** The last-admin
  trigger function MUST `LOCK TABLE users IN SHARE ROW EXCLUSIVE
  MODE` at the top of its body — not after the count, not
  conditionally, not as a comment. The lock comes first so
  concurrent transactions serialize at the `LOCK` call and only
  then proceed to the count. The lock mode is `SHARE ROW
  EXCLUSIVE`: it blocks other writers (the property we need —
  another `/revoke-admin` transaction must wait) while still
  permitting concurrent `SELECT` (Provider's read paths against
  `users` must not stall during a revoke). `ACCESS EXCLUSIVE`
  would over-lock; `ROW EXCLUSIVE` (the default for `UPDATE`)
  under-locks and is exactly the unsafe case Invariant 2 forbids.
- **The DELETE trigger is defense-in-depth.** A `preban` row never
  has `is_admin = true` (bootstrap-seeded admins are `vouched`
  per `docs/spec/deployment.md` §Bootstrap behavior; pre-ban rows
  are minted by `/ban <unknown>` and never carry the admin flag),
  so the DELETE-path count check always passes for the
  `delete_preban_user` carve-out. But: an operator running raw
  SQL under the Admin role concurrent with a `/revoke-admin`
  could still trip Invariant 2 if the DELETE trigger weren't
  serializing on the same lock. The DELETE trigger exists so the
  carve-out plus operator-psql DELETE paths participate in the
  same serialization window.
- **The `delete_preban_user` procedure is `SECURITY DEFINER`.**
  The Provider role's grant matrix per `docs/spec/security.md` §DB
  roles **revokes** `DELETE ON users` — only the Admin role has
  raw DELETE. The procedure runs with the rights of its definer
  (Admin) so the Provider can invoke the carve-out path without
  carrying raw DELETE privilege. The Provider role gets `EXECUTE`
  on the procedure, nothing more.
- **The audit_log_view's redactor functions are stubs.** The view
  DDL references `redact_contact_id(text)` and
  `redact_secrets_jsonb(jsonb)`. The real implementations carry
  the closed regex catalogue from `docs/spec/security.md`
  §Secrets handling and live in a later application-tier ticket
  (the audit-write redactor hook). For this subticket, declare
  both functions with stub bodies (`RETURN input;` for the text
  variant, `RETURN input;` for the jsonb variant) and `CREATE OR
  REPLACE FUNCTION` so the application-tier ticket can supersede
  them without a schema migration. This keeps the v1 grant
  matrix structurally valid (the view is grantable, the Provider
  role can `SELECT` against it) from day one without pulling the
  redactor catalogue into this ticket's scope.
- **Per-table GRANTs land in the migration that creates the
  table.** M1-006 created the three roles (`infochat_collector`,
  `infochat_provider`, `infochat_admin`) but explicitly excluded
  per-table GRANTs from its scope (M1-006 §out_of_scope). Each
  schema-introducing subticket of M1-008 carries its own GRANTs
  in the same migration that creates the tables; this keeps the
  grant decisions colocated with the table DDL rather than
  buried in a separate "grants" migration.
- **No NOTIFY triggers here.** The outbox NOTIFY plumbing (a
  `new_post` trigger on `post`, etc.) is T1-C's territory.
  `audit_log` itself carries no NOTIFY in v1 — the Provider's
  `/audit` reads through the view on demand.
- **Testcontainers Postgres image.** Use the `pgvector/pgvector`
  image (or `postgres:16-alpine` with a `CREATE EXTENSION` step
  in the test helper) — `pgvector` is not exercised by V5 itself
  but later migrations (M1-008c's `post` partitioning and a
  later Tier-1 ticket's `post_embedding`) will need it, and
  baselining the test container on a pgvector-capable image now
  avoids a re-baseline later. The exact tag is impl-choice;
  document it inline in the `PostgresSchemaTestBase`.
- **Test container reuse.** `PostgresSchemaTestBase` should start
  ONE container per JVM (`@Container static`) and reset state
  between tests via `TRUNCATE` over the test's tables, not via
  re-spin. Re-spinning per test class would multiply the test
  suite's wall-clock cost by the number of test classes; with
  containers reused, each new class costs only the cost of a
  fresh `TRUNCATE`.
- **Concurrency test method.** Use `java.util.concurrent.Executors`
  + `CountDownLatch` so the two transactions reach `LOCK TABLE`
  simultaneously rather than racing through driver-side
  scheduling. Both threads call `Connection.setAutoCommit(false)`,
  perform their `UPDATE`, then sit on the latch before issuing
  `COMMIT`. After the latch releases, exactly one transaction
  commits cleanly; the other's commit raises an
  `SQLException` whose message contains
  `last_admin_protection`. The test asserts on the message and
  on the final `SELECT COUNT(*)`.
- **The `audit_log.action` verb catalogue is documented at the
  migration layer.** Per `docs/design/02-schema.md` §2.1.8 the
  v1 verb set is closed; extending it is a design-note edit.
  Encoding the set as a SQL `CHECK` constraint on the column
  would make a v2 extension a migration-touch operation rather
  than a code change, which is the wrong trade-off (the verb
  set is open-ended for v2 additions; the schema's commitment
  is the TABLE shape, not the verb closure). Instead, list the
  verbs as line comments (`-- VERB_NAME`) in the migration so
  grep can keep the documentation honest. The application-layer
  audit-write path is responsible for emitting only the closed
  set.
- **No `CHECK` constraint on `audit_log.action` itself.** Per the
  above point, the verb set is documented but not constrained
  at the schema layer. The matching test corpus that keeps the
  application path honest lives in the audit-write ticket
  (later — out of scope here).
- **Audit-before-effect inside `delete_preban_user`.** The
  procedure body MUST issue the `INSERT INTO audit_log` BEFORE
  the `DELETE FROM users`, in the same transaction. Reversing
  the order (DELETE then INSERT) would lose audit-before-effect
  on a transaction-aborted DELETE — the failure mode Invariant
  7 exists to prevent.
- **No application-tier audit-write helper.** The procedure
  contains the audit row INSERT inline. The Java-side audit
  writer (with redaction-hook, request-id propagation, etc.)
  lives in a later ticket; the procedure-internal INSERT is the
  single audit-write path for the carve-out and does not depend
  on the Java writer.

## Big-picture notes

- **This subticket is the keystone for the authorization model.**
  Every privileged command (`/grant-admin`, `/revoke-admin`,
  `/ban`, `/unban`, `/promote`, `/demote`, `/source-disable`,
  `/quarantine approve` / `reject`, `/forget`, `/lang`, etc.)
  reads `users.is_admin`, writes to `audit_log`, or — for the
  group-admin paths — reads `group_membership.is_group_admin`.
  A defect here propagates to every privileged path. That is
  why this ticket is `complexity: high`, `risk: high`,
  `round_cap: 3` (per `CLAUDE.md` §M1 workflow — the high
  bands authorize the third round), and `security_relevant:
  true` (threat-actor should review the diff after APPROVE).
- **Concurrent revocation is the non-obvious failure mode.** A
  single-transaction `/revoke-admin` against the last admin is
  the easy case; the trigger raises and the operator gets a
  friendly error. The dangerous case is the race: two admins
  on different devices both running `/revoke-admin` against the
  other at almost the same instant. Without serialization both
  transactions read the pre-state of two admins, both decide
  the count would remain at 1, both commit, and the deployment
  has zero admins. `LOCK TABLE users IN SHARE ROW EXCLUSIVE
  MODE` at the top of the trigger function serializes those
  two transactions at the lock acquisition; the second
  transaction sees the first's pending change after the lock
  is released and correctly fails the count check.
  `LastAdminConcurrentRevocationTest` is the regression test
  for this exact scenario.
- **The `audit_log_view` stubs are temporary scaffolding.** The
  real redactor functions land in the audit-write ticket. Until
  they do, the stubs return the input unchanged. A reader who
  greps for `redact_contact_id` after this subticket lands will
  find both the view reference and the stub function; they
  should NOT add the regex catalogue here. The next ticket that
  touches the redactor path is the audit-write hook, and it
  supersedes the stubs in one focused diff.
- **Subticket isolation against M1-008b and M1-008c.** This
  subticket touches only V5 and the identity/audit test files.
  M1-008b adds V6 (sources/tags) and its own test files;
  M1-008c adds V7 (joins/post) and its own test files. The
  `files_scope` lists are disjoint by construction. The
  umbrella M1-008's `blocked_by: [M1-008a, M1-008b, M1-008c]`
  enforces the all-three-merged-before-umbrella ordering.
- **The umbrella's per-(user, scope) isolation IT depends on
  this ticket's `users` and `groups` tables.** Specifically the
  IT seeds two users (A, B), two scopes (DM and group:G), and
  walks the per-scope joins from M1-008c. Without this
  subticket's tables, the IT cannot insert its fixtures. The
  M1-008 umbrella's `blocked_by` enforces this.
- **The `infochat_listen` role from M1-006 is not granted any
  identity-table privilege here.** Its role per
  `docs/spec/security.md` §DB roles is `LISTEN/NOTIFY` only.
  Granting `SELECT` would broaden it beyond spec.

## Out-of-scope expansion

- **The M1-008 umbrella's per-(user, scope) isolation IT
  (`infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java`)**
  is reserved for the umbrella commit. The umbrella + subticket
  idiom (`docs/process/workflow.md` §Ticket-ID placeholder
  convention) exists so cross-cutting verification ships as its
  own reviewable unit. Pre-empting it here — for example by
  writing a test against V5 that also seeds rows in the V7
  joins — would erase the umbrella's reason to exist.
- **The V6 (sources/tags) and V7 (joins/post) migrations.**
  M1-008b and M1-008c. This subticket's V5 file does not
  forward-reference `source`, `post`, `source_subscription`,
  or any of the M1-008b/c table names.
- **Any Java entity class, repository, service, DAO, or
  application-layer audit-write helper.** The schema is the
  commitment. The application layer (the audit-write redaction
  hook, the bootstrap-admin `@Startup` bean, the invite-code
  intake parser, the `/grant-admin` / `/revoke-admin` handlers,
  the `/ban` / `/unban` command paths) lands in later
  Tier-1/Tier-3 tickets.
- **LISTEN/NOTIFY plumbing.** T1-C's territory. `audit_log` has
  no NOTIFY trigger in v1; `users` / `groups` / `group_membership`
  don't either.
- **Provider startup logic.** The `@Startup` admin-bootstrap
  bean (per `docs/spec/deployment.md` §Bootstrap behavior) lives
  on the Provider side and is a separate later ticket. This
  subticket creates the table the bootstrapper will later
  write to.
- **Audit-log row writer and the redactor catalogue.** The
  audit-write path with the closed regex shape catalogue
  (`docs/spec/security.md` §Secrets handling) is an
  application-layer ticket. This subticket declares stub
  redactor functions so the view DDL resolves; the real
  catalogue lands later.
- **Retention sweep / pruner / GC against audit_log or users.**
  Admin-driven retention runs under `infochat_admin` and is
  operator-side per `docs/spec/security.md` §DB roles. The
  schema commitment is the append-only trigger guard plus the
  role grants; there is no scheduled cleanup in v1.
- **Adapter integration code.** The path that resolves
  `(adapter, contact_id)` from an inbound adapter event to a
  `users` row lives in the T1-E messaging tickets.
- **Modifications to V1..V4.** Those migrations are owned by
  M1-005, M1-006, M1-009, M1-016 and are frozen. This subticket
  adds V5 only.

## Authorized test changes

- (none — this subticket adds six new test files in
  `infochat-core` and modifies no pre-existing tests. The five
  M1-003 / M1-007 / M1-007a/b/c tests continue to pass
  unchanged.)

## Alternatives considered

- **Encode `audit_log.action` as a Postgres ENUM type.**
  Tempting because the verb set is closed today. Rejected:
  Postgres ENUMs require a migration to extend; the verb set
  is intentionally open-ended for v2 additions (per
  `docs/design/02-schema.md` §2.1.8 — "extending the catalogue
  is a design-note edit"). A TEXT column with the verbs
  documented as line comments lets the application layer add
  a verb in a code-only change. The application's audit-write
  helper is the closure-enforcer.
- **Add a `CHECK (action IN (...))` constraint over the verb
  set.** Same objection as the ENUM. Rejected.
- **Implement the redactor catalogue here so the view's
  redaction is real on day one.** Rejected: the redactor
  regexes live in `docs/spec/security.md` §Secrets handling
  and the corresponding `docs/design/04-security.md` §4.10;
  bundling them into the schema migration would force this
  subticket to carry the full secrets-redaction test corpus,
  ballooning the diff well past `files_budget: 8`. The stub
  redactor functions keep the view structurally valid; the
  audit-write hook ticket supersedes the bodies.
- **Use a single-row LOCK (`SELECT ... FOR UPDATE`) instead of
  `LOCK TABLE`.** Acceptable per `docs/spec/schema.md`
  §Invariants — Invariant 2 ("Acceptable implementations: take
  a table-level lock on `users` covering the admin rows for
  the duration of the trigger body ... or read the count under
  `SELECT … FOR UPDATE` against the admin rows"). The design
  notes' chosen implementation is `LOCK TABLE users IN SHARE
  ROW EXCLUSIVE MODE` because it is easier to reason about
  (one lock, one mode, one place) and the contention window is
  tiny (only privileged-mutation paths touch `users` with an
  UPDATE/DELETE). A row-level FOR-UPDATE implementation would
  need careful ordering across rows to avoid a deadlock when
  two transactions revoke each other; the table-level lock
  sidesteps the ordering problem. Either choice meets the
  invariant; we pick the simpler one.
- **Run the migration tests as `@QuarkusTest`.** Rejected:
  `infochat-core` is a plain library jar (no Quarkus
  extensions in production scope per M1-007a's §Definition of
  Done). Adding test-scope `quarkus-junit5` here would pull in
  the Quarkus ecosystem just to launch a Testcontainers
  Postgres, which plain JUnit 5 + the Testcontainers JUnit
  extension can do directly. The plain-JUnit shape keeps the
  test invocation simple (`mvn -pl infochat-core test`) and
  the test wall-clock fast (no Quarkus bootstrap).
- **Split V5 into V5a__users.sql, V5b__groups.sql,
  V5c__invite.sql, V5d__audit.sql.** Rejected: Flyway versions
  are integers; an interleaved split would either re-base
  V6/V7 or use sub-version suffixes (which Flyway supports but
  the project hasn't adopted). One V5 file under one
  transaction is the simpler, atomic shape; a half-applied
  V5 on partial failure is exactly the failure mode Flyway's
  per-migration transaction is designed to prevent.
- **Use `pgTAP` for the SQL-level tests.** Rejected: pgTAP is
  not currently in the test stack and adding it would require
  bundling the pgTAP extension into the test Postgres image
  plus a pgTAP test runner. Plain JDBC tests from JUnit are
  enough for the assertions this ticket needs (exception
  message substrings, post-condition COUNT queries, two-
  connection races). The handoff explicitly authorizes this
  trade-off.

## Implementation outline (M1-008a, generated by Plan subagent on 2026-05-13)

### Files to touch (8 of 8)
- modify: `infochat-core/pom.xml` — add test-scope dependencies (Testcontainers core + postgresql + junit-jupiter, postgresql JDBC driver, flyway-core + flyway-database-postgresql) and wire `maven-failsafe-plugin` so `*IT.java` runs under `mvn verify` (mirrors the collector POM's failsafe block).
- create: `infochat-core/src/main/resources/db/migration/V5__identity_audit.sql` — one transactional migration: `users`, `groups`, `group_membership` (+ partial-unique index + admin-clear-on-remove trigger), `invite_code`, `delete_preban_user` procedure, `audit_log` (+ closed `target_kind` CHECK + per-verb commentary), append-only trigger (UPDATE + DELETE), last-admin trigger (UPDATE + DELETE) with `LOCK TABLE … IN SHARE ROW EXCLUSIVE MODE`, `audit_log_view` with stub `redact_contact_id` / `redact_secrets_jsonb`, all per-table GRANTs to `infochat_collector` / `infochat_provider` aligned with security.md §DB roles.
- create: `infochat-core/src/test/java/io/infochat/core/schema/PostgresSchemaTestBase.java` — shared JUnit 5 base; one `@Container static` Testcontainers `pgvector/pgvector:pg16` Postgres started once per JVM; helper to open new JDBC `Connection`s as the bootstrap superuser; `@BeforeEach` truncates the V5 tables to reset state between tests; runs Flyway against the container's JDBC URL pointing at `src/main/resources/db/migration`.
- create: `infochat-core/src/test/java/io/infochat/core/schema/LastAdminTriggerTest.java` — exercises single-transaction revoke of the only admin row.
- create: `infochat-core/src/test/java/io/infochat/core/schema/LastAdminConcurrentRevocationTest.java` — two `Connection`s + `CountDownLatch`; asserts exactly one transaction commits and final `COUNT(*) WHERE is_admin AND NOT is_banned = 1`.
- create: `infochat-core/src/test/java/io/infochat/core/schema/AuditLogAppendOnlyTest.java` — UPDATE and DELETE against `audit_log` both raise SQL exceptions containing `append-only`.
- create: `infochat-core/src/test/java/io/infochat/core/schema/GroupAdminUniqueIndexTest.java` — second `is_group_admin = TRUE` row for the same group raises unique-violation.
- create: `infochat-core/src/test/java/io/infochat/core/schema/DeletePrebanUserTest.java` — happy-path CALL on `preban` row writes `UNBAN_PREBAN_DELETE` audit row and deletes; CALL on non-`preban` row raises with substring `is not in preban state`.

Files-budget check: 8 of 8. At ceiling — no margin. Any extra file (test base split, helper SQL fixture, etc.) breaches budget and must escalate via `refine` BEFORE implementation.

### Tests
- add: `PostgresSchemaTestBase.java` — covers acceptance "mvn -B -pl infochat-core -am test exits 0" and "mvn -B clean verify from the repo root exits 0" by providing the shared container; no test cases of its own.
- add: `LastAdminTriggerTest.java` — covers acceptance items "trigger function body contains LOCK TABLE … SHARE ROW EXCLUSIVE MODE" (asserted indirectly via observed serialization) and "single-transaction revoke path raises an exception containing 'last_admin_protection'". Test cases:
  - `revokingTheOnlyAdminRaisesLastAdminProtection` — seed one admin row, UPDATE is_admin = FALSE, assert SQLException message contains `last_admin_protection`.
  - `banningTheOnlyAdminRaisesLastAdminProtection` — seed one admin row, UPDATE is_banned = TRUE, assert SQLException message contains `last_admin_protection`.
  - `revokingOneOfTwoAdminsSucceeds` — seed two admin rows, UPDATE one to is_admin = FALSE, assert COUNT(*) admins = 1, no exception.
- add: `LastAdminConcurrentRevocationTest.java` — covers acceptance "TWO JDBC Connections … concurrent transactions each revoking a different admin … EXACTLY ONE commits while the other raises 'last_admin_protection'". Test case:
  - `concurrentRevocationOfTwoAdminsSerializesViaLockTable` — seed two admin rows, two threads each open a Connection (autoCommit=false), each UPDATE one row to is_admin = FALSE, both reach a `CountDownLatch.await()` after the UPDATE, latch fires, both attempt COMMIT, assert exactly one COMMIT succeeds and the other raises with `last_admin_protection`; assert final `COUNT(*) WHERE is_admin = TRUE AND is_banned = FALSE` = 1.
- add: `AuditLogAppendOnlyTest.java` — covers acceptance "UPDATE or DELETE against audit_log raises an exception" with substring `append-only`. Test cases:
  - `updateOnAuditLogRaisesAppendOnly` — INSERT an audit row, attempt UPDATE, assert SQLException contains `append-only`.
  - `deleteOnAuditLogRaisesAppendOnly` — INSERT an audit row, attempt DELETE, assert SQLException contains `append-only`.
- add: `GroupAdminUniqueIndexTest.java` — covers acceptance "second is_group_admin=TRUE row for the same group raises a unique-violation". Test case:
  - `secondGroupAdminRaisesUniqueViolation` — seed group + two users + first membership with is_group_admin = TRUE; INSERT second membership with is_group_admin = TRUE for same group; assert SQLException is a unique-violation (`SQLState '23505'`).
- add: `DeletePrebanUserTest.java` — covers acceptance "CALL delete_preban_user … on 'preban' succeeds AND writes audit_log row with action='UNBAN_PREBAN_DELETE'; calling against non-preban raises with 'is not in preban state'". Test cases:
  - `callOnPrebanRowDeletesUserAndWritesAudit` — INSERT a `preban` user + an actor admin; CALL `delete_preban_user($id, $actor)`; assert user row is gone, `audit_log` carries one row with action = `UNBAN_PREBAN_DELETE` and `target_id = $id`.
  - `callOnRegisteredRowRaisesNotInPrebanState` — INSERT a `vouched` user, attempt CALL, assert SQLException message contains `is not in preban state`.

The ticket modifies no pre-existing tests. The "Authorized test changes" body section correctly reads "(none)". Test-modification authorization rule satisfied.

### Cross-cutting concerns

- **Invariant 1 (per-(user, scope) isolation).** `audit_log.scope_id` is nullable so admin actions in DM stay scope-less. Identity/group/audit tables themselves are scope-less keystones (no scope discriminator column); the per-(user, scope) isolation invariant attaches to user-state tables landing in M1-008b/c. The umbrella's `PerScopeIsolationIT.java` will read FROM `users` and `groups`, so column names declared here are load-bearing for that IT.
- **Invariant 2 (last-admin protection).** Lock mode MUST be `SHARE ROW EXCLUSIVE` exactly (acceptance grep is strict). `LOCK TABLE` MUST sit at the top of the trigger function body, before the `SELECT count(*)` that decides the verdict. Counting MUST be global across adapters (no `WHERE adapter = …` filter) per security.md §Authorization model and decision D46.
- **Invariant 3 (one group admin per group).** Encoded as the partial unique index `one_admin_per_group ON group_membership(group_id) WHERE is_group_admin = TRUE`. The migration MUST also declare `trg_clear_group_admin_on_remove` (per design §2.1.4) so the user-departure soft-clear lifecycle frees the slot.
- **Invariant 7 (audit-before-effect).** `delete_preban_user` MUST INSERT the audit row BEFORE the DELETE FROM users.
- **Invariant 10 (audit_log append-only).** Defense-in-depth lives at two layers — (1) GRANT matrix gives INSERT-only to both service roles, (2) the trigger guard. The ticket explicitly forbids any GRANT of SELECT on raw `audit_log` to `infochat_provider` (Provider read path is the view).
- **D44 (invite-code DM gate).** `invite_type`/`expected_contact_id` iff-CHECK + the three-value `status` CHECK + the `idx_invite_code_pending(adapter, code) WHERE status = 'PENDING'` partial index are load-bearing for the future `INVITE_CONSUME` race-safe UPDATE.
- **D45 (slow-start probation).** `probation_until TIMESTAMPTZ NULL` on `users`.
- **D46 ((adapter, contact_id) per-row uniqueness).** UNIQUE constraint on `users(adapter, contact_id)` AND on `groups(adapter, upstream_group_id)`. The same human on two adapters is two rows.
- **Trust boundary (security.md §Trust boundaries).** This migration is the application-side schema commitment. The `infochat_admin` role retains the right to disable/drop the append-only trigger for operator-controlled retention — design §2.1.7 names that explicitly.
- **redact_contact_id / redact_secrets_jsonb stubs.** Both functions declared `CREATE OR REPLACE FUNCTION … RETURN input;` so the view compiles AND so the application-tier ticket can supersede them without a schema migration. The view's `SELECT` list must call them on `actor_contact_id`, `target_contact_id`, and `details_json` exactly as design §2.1.9 shows.

### Implementation order

1. **Modify `infochat-core/pom.xml`** — add the four test-scope dependencies and the failsafe plugin block first. Reason: every new test file fails to compile until the deps are in place; getting POM right unblocks all five test classes simultaneously.
2. **Author `V5__identity_audit.sql`** — write the migration top-to-bottom in spec order: `users` → last-admin trigger functions + bindings → `groups` → `group_membership` + partial unique index + admin-clear trigger → `invite_code` + indices → `audit_log` + indices → append-only trigger function + UPDATE + DELETE bindings → per-verb commentary block (23 `-- VERB` lines) → `redact_*` stubs → `audit_log_view` → `delete_preban_user` procedure → all GRANTs (table-by-table). Reason: triggers must follow their tables; the view must follow the redactor stubs; the procedure references `audit_log` so it goes after `audit_log` is created.
3. **Create `PostgresSchemaTestBase.java`** — establish the shared container fixture before any concrete test class needs it.
4. **Create `AuditLogAppendOnlyTest.java`** then **`GroupAdminUniqueIndexTest.java`** — the simplest single-connection schema tests. Reason: cheapest to debug; if these fail the trigger/index didn't land at all.
5. **Create `LastAdminTriggerTest.java`** — single-transaction revoke + ban + happy-path-2-admins. Reason: validates the trigger body's exception path before the concurrent test relies on it.
6. **Create `DeletePrebanUserTest.java`** — depends on both `audit_log` and the last-admin DELETE trigger being correct.
7. **Create `LastAdminConcurrentRevocationTest.java`** — the two-connection race. Hardest to debug; only run after the single-transaction path is green.
8. **Full `mvn -B clean verify` from repo root** — confirms no regression in M1-003 / M1-007 / M1-007a/b/c tests.

Wrong-order traps:
- Writing the migration before the POM changes leaves Flyway untestable from `infochat-core`.
- Writing the concurrent test before the single-transaction test confuses a missing `LOCK TABLE` with a missing exception.
- Authoring `delete_preban_user` before `audit_log` in the migration produces a forward-reference failure.

### Risks

- **Files budget at the ceiling (8 of 8).** Zero slack. If implementation reveals a needed helper, the developer cannot add it without breaching budget. Mitigation: keep all seed code inline in each test class, accepting some duplication. — escalation if exceeded: refine.
- **Testcontainers image choice.** Base class MUST use `pgvector/pgvector:pg16` for consistency with the rest of the stack. — risk only.
- **Quarkus DevServices vs. raw Testcontainers.** `infochat-core` is a plain library JAR. Cannot use `@QuarkusTest` + DevServices (would break library-JAR invariant of M1-001). Solution: raw Testcontainers `@Container` static field. Reviewer must understand this divergence. — risk only.
- **`audit_log_view` redactor stubs may be flagged as defensive code.** They aren't — they're a schema commitment (the view must compile so GRANT SELECT can land in the same migration). — risk only.
- **Per-verb commentary fragility.** Acceptance counts 23 grep matches against a regex of 23 anchor-bounded verb names. Mitigation: copy the verb list directly from design §2.1.8. — risk only.
- **`infochat_listen` role not granted anything here.** Correct per big-picture note; reviewer must not flag the absence. — risk only.
- **`audit_log` `SELECT` to `infochat_collector`.** Both roles INSERT-only. Make sure NO `SELECT ON audit_log TO infochat_collector` clause leaks in. — risk only.

### Out-of-scope (echoed from ticket)
- `infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java` (umbrella commit)
- any change under `infochat-core/src/main/resources/db/migration/V6__*.sql` or `V7__*.sql`
- any Java entity class, Hibernate / Panache mapping, repository, service, or DAO
- any LISTEN/NOTIFY trigger or channel wiring
- any Provider startup logic that consumes the schema (the `@Startup` admin-bootstrap bean)
- any audit-log row writer or redaction-hook Java code
- any signal-cli / SimpleX-CLI / inmemory adapter-side identity wiring
- any retention sweep / pruner / GC schedule against `audit_log` or `users`
- any change to V1..V4 migrations already on disk
