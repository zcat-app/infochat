---
id: M1-016
title: Enforce NOLOGIN on application roles
status: pending
created: 2026-05-12
last_updated: 2026-05-12
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/resources/db/migration/**
  - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - any edit to `V2__roles.sql` (Flyway tracks migrations by filename+checksum; V2 has been applied on every DB the test has run against — modifying it in place is an integrity violation. The fix is a new migration, not an edit)
  - any GRANT statement (per-table GRANTs ride with the M1-008 schema umbrella; this ticket only enforces a role attribute)
  - any `CREATE ROLE` statement (the three principals already exist after V2; ALTER is the right verb)
  - any `LOGIN` attribute on `infochat_admin` (the spec leaves that to the named-datasource wiring ticket; this ticket enforces NOLOGIN uniformly across all three, matching V2's intent)
  - the `heartbeat` table or any change to M1-009's surface (independent ticket)
  - the `audit_log_view`, `approve_quarantine`, `reject_quarantine` (M1-008 umbrella territory)
  - any change to `quarkus.datasource.username` / `password` in either module's `application.properties` (bootstrap `infochat` superuser remains the connecting role until the named-datasource wiring ticket)
  - any Java code under `src/main/java/` (this ticket is migration-only plus one test extension)
  - any docker-compose.yml edit
acceptance:
  - "exactly one new Flyway migration file exists under `infochat-collector/src/main/resources/db/migration/` whose name matches `V[0-9]+__*.sql` and was not present at M1-006's HEAD (run `git diff --name-only 0af8f9ff7320c2b4e6e7a2810edd3c9e289a6423..HEAD -- infochat-collector/src/main/resources/db/migration/` and confirm exactly one added entry)"
  - "the new migration contains `ALTER ROLE infochat_collector NOLOGIN` (grep -E 'ALTER ROLE infochat_collector[[:space:]]+NOLOGIN' returns at least one match)"
  - "the new migration contains `ALTER ROLE infochat_provider NOLOGIN` (grep -E 'ALTER ROLE infochat_provider[[:space:]]+NOLOGIN' returns at least one match)"
  - "the new migration contains `ALTER ROLE infochat_admin NOLOGIN` (grep -E 'ALTER ROLE infochat_admin[[:space:]]+NOLOGIN' returns at least one match)"
  - "the new migration contains no `CREATE ROLE`, no `GRANT`, no `CREATE TABLE`, no `CREATE VIEW`, no `CREATE FUNCTION`, no `CREATE PROCEDURE` (grep -E '^[[:space:]]*(CREATE ROLE|GRANT|CREATE TABLE|CREATE VIEW|CREATE FUNCTION|CREATE PROCEDURE)' returns zero matches — this ticket is ALTER-only)"
  - "`DbRoleMatrixIT.v2CreatesThreeRolePrincipals` (or a new sibling test method) asserts `rolcanlogin = false` for each of the three principals — the JDBC query reads `SELECT rolname, rolcanlogin FROM pg_roles WHERE rolname IN (?, ?, ?)` and the assertion fails if any returned `rolcanlogin` is `true`"
  - "mvn -B verify from the repo root exits 0; M1-006's `v2CreatesThreeRolePrincipals` presence assertion still passes; M1-003 and M1-005 tests still pass"
  - "after `mvn -pl infochat-collector test`, grep -rE 'version \"[0-9]+ - .+\"' infochat-collector/target/surefire-reports/ returns at least one match for the new V<NN> migration (Flyway log line confirming the new migration was applied)"
test_plan:
  adds:
    # none — this ticket extends DbRoleMatrixIT in place rather than adding a new test class.
    # The presence-check method is preserved (modulo the rename if the implementer renames it);
    # a NOLOGIN assertion either extends the existing method or sits beside it.
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-collector/src/test/java/io/infochat/collector/config/InfochatProfileTest.java (M1-005)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-005)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/config/InfochatProfileTest.java (M1-005)
spec_refs:
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D34

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
remediates: M1-006
---

# M1-016: Enforce NOLOGIN on application roles

## Context

M1-006 landed three application role principals (`infochat_collector`,
`infochat_provider`, `infochat_admin`) via `V2__roles.sql`. Each
`CREATE ROLE` is guarded by a `pg_roles`-existence check so re-running
on a DB where the role already exists is a no-op rather than a hard
failure — the right idempotency shape for a migration that may meet a
pre-seeded DB.

The hardening gap surfaced by the M1-006 `/redteam` pass (CLEAN
verdict; observation under OUT-OF-MODEL): the `IF NOT EXISTS` guard
**only creates the role when it is absent**. If a role with the same
name *already exists* with `LOGIN` and a password (set by an operator
script, a prior failed install attempt, or an attacker with prior DB
write access — out-of-model per §Threat model, but a defense-in-depth
gap nonetheless), V2 silently accepts the wrong attribute set. The
spec's §DB roles invariant ("a SQL-injection bug in the Provider
cannot delete posts, mutate price snapshots, alter quarantine entries,
read unredacted audit rows, or read raw quarantine originals") leans
on the three principals being NOLOGIN until the named-datasource
wiring ticket re-evaluates per role. A pre-seeded `LOGIN` role would
let a future misconfiguration trivially bypass that wiring.

The companion gap: `DbRoleMatrixIT` asserts only the *presence* of
the three principals via `SELECT rolname FROM pg_roles WHERE rolname
IN (...)`. A regression that creates one of the principals with
`LOGIN` would not be caught by the test.

This ticket lands both halves of the remediation in one new migration
plus one in-place test extension. It is the structural fix that the
named-datasource wiring ticket can rely on later (`rolcanlogin =
false` is now a checked invariant, not a coincidence).

## Definition of Done

- A new Flyway migration exists under
  `infochat-collector/src/main/resources/db/migration/` with the next
  unused `V<NN>` integer. The migration contains three `ALTER ROLE
  … NOLOGIN` statements — one per principal — and nothing else
  structural (no `CREATE ROLE`, no `GRANT`, no `CREATE TABLE`, no
  view/function/procedure).
- `DbRoleMatrixIT` is extended in place to query `rolcanlogin`
  alongside `rolname` and assert that all three principals have
  `rolcanlogin = false`. The existing presence assertion is
  preserved (the V2 invariant is unchanged: the three principals
  must exist).
- `mvn -B verify` from the repo root exits 0; every test that was
  green on the M1-006 HEAD is still green.

## Implementation notes

- **`ALTER ROLE … NOLOGIN` is idempotent.** Unlike `CREATE ROLE`,
  no existence guard is needed — running `ALTER ROLE infochat_collector
  NOLOGIN` against a role that is already `NOLOGIN` is a successful
  no-op. The migration is three back-to-back statements:
  ```sql
  ALTER ROLE infochat_collector NOLOGIN;
  ALTER ROLE infochat_provider  NOLOGIN;
  ALTER ROLE infochat_admin     NOLOGIN;
  ```
- **Migration filename.** Use the next unused `V<NN>` integer at start
  time. M1-006 used V2; M1-009 reserves V3 for the heartbeat table.
  If M1-009 has landed on `main` before this ticket starts, the next
  slot is V4; if it has not, the next slot is V3 (Flyway tolerates
  out-of-order migrations applied to a fresh DB, but the conservative
  choice if M1-009 is still pending is to coordinate with the user
  before claiming V3 to avoid a collision when M1-009 lands).
  Suggested filename: `V<NN>__nologin.sql`.
- **Comment block at the top of the new migration.** Name the
  remediation in two lines (which `/redteam` finding, why ALTER not
  CREATE) so a reader of the migration directory understands the
  pairing with V2 without round-tripping to this ticket. One example:
  ```sql
  -- V<NN>: enforce NOLOGIN on the three application roles.
  --
  -- V2__roles.sql creates each role guarded by a pg_roles existence
  -- check, so a pre-seeded role with LOGIN would survive V2 with the
  -- wrong attribute set. ALTER ROLE is idempotent on the attribute,
  -- so this migration flips any pre-existing LOGIN to NOLOGIN and
  -- is a no-op for the common case where V2 created the role with
  -- the correct attribute.
  ```
- **Test extension shape.** The simplest in-place edit replaces the
  existing single-column `SELECT rolname FROM pg_roles WHERE rolname
  IN (?, ?, ?)` with a two-column `SELECT rolname, rolcanlogin FROM
  pg_roles WHERE rolname IN (?, ?, ?)` and asserts both the set of
  returned names and that every returned `rolcanlogin` is `false`.
  Keep the method name (`v2CreatesThreeRolePrincipals`) **or** rename
  it (e.g. `applicationRolesAreCreatedAndNologin`) — either is
  fine; the reviewer's negative-space check is keyed on the file, not
  the method name. If renamed, the Javadoc should still mention V2 as
  the principal-creation source and the new V<NN> migration as the
  NOLOGIN-enforcement source.
- **Why `pg_roles.rolcanlogin` and not `pg_authid.rolpassword`.**
  `pg_authid` requires superuser to read; `pg_roles` is a public view
  that exposes `rolcanlogin` without elevated rights. The test
  connects as the bootstrap `infochat` superuser per M1-006's wiring,
  but `pg_roles` keeps the test portable if the named-datasource
  wiring ticket later switches the test connection to a non-superuser
  role.

## Big-picture notes

- **This ticket does not amend M1-006's commit.** M1-006 is `done`
  and merged on `main`; per the M1 workflow ("Never amend a passed
  commit"), the fix lands as a new ticket with `remediates: M1-006`,
  not as an edit to the original commit. The new migration sits
  beside V2 rather than replacing it.
- **The named-datasource wiring ticket inherits a verified
  invariant.** That future ticket re-evaluates whether
  `infochat_admin` (operator psql path) and `infochat_collector` /
  `infochat_provider` (Quarkus JDBC connect path) should switch to
  `LOGIN`. With this ticket landed, the wiring ticket's diff
  explicitly opts each role into LOGIN — there is no "is this role
  already LOGIN by accident?" question. The test extension here is
  the structural check that backs that invariant.
- **No threat-actor re-pass required.** The M1-006 `/redteam`
  verdict was CLEAN; this ticket addresses an OUT-OF-MODEL
  observation, not a finding. `security_relevant: true` is set
  because the role-matrix surface is the §DB roles authorization
  boundary; if the implementer accidentally adds a `GRANT` or
  `CREATE ROLE` here (forbidden by acceptance criteria), a redteam
  pass would catch it. But on the diff this ticket *intends* to
  produce, no new threat surface is opened.
- **Migration ordering with M1-009.** M1-009 reserves V3 for the
  `heartbeat` table. This ticket's migration uses **the next unused
  V slot at start time**. If M1-009 is still pending when this ticket
  starts and the implementer is unsure whether to claim V3 or V4,
  surface that to the user before writing the file — a small
  coordination cost compared to renaming the migration after a V3
  collision.

## Out-of-scope expansion

- **Editing V2 in place.** Flyway tracks applied migrations by
  filename + checksum; V2 has been applied on every DB the M1-006
  test has run against. Editing V2 now would change the checksum and
  cause Flyway to refuse to start. The fix is a new migration.
- **Per-table GRANTs.** None are added or modified here. Those land
  with the M1-008 schema umbrella subtickets, attached to each
  table's CREATE migration.
- **Setting `infochat_admin` to `LOGIN`.** The spec leaves the
  admin-role login attribute to the named-datasource wiring ticket.
  This ticket enforces NOLOGIN uniformly across all three — matching
  V2's intent — and the wiring ticket flips whichever role(s) need
  LOGIN at that point.
- **`application.properties` JDBC username changes.** The bootstrap
  `infochat` superuser remains the connecting role for both services
  until the named-datasource wiring ticket. Comment markers added in
  M1-006 are not touched here.
- **`ALTER DEFAULT PRIVILEGES`.** Still deferred to the
  named-datasource wiring ticket — same rationale as M1-006's
  Implementation notes.
- **A new test class.** This ticket extends `DbRoleMatrixIT` in
  place rather than adding a sibling test class. The reviewer
  treats this as an authorized in-place edit (see Authorized test
  changes below).

## Authorized test changes

- `infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java`
  is modified in place. The existing assertion that the three
  principals exist is preserved (M1-006 invariant). A new assertion
  on `rolcanlogin = false` is added. If the implementer renames the
  test method to better reflect the combined assertions, that
  rename is also authorized. No other test file is touched.

## Alternatives considered

- **Re-issue `CREATE ROLE infochat_collector NOLOGIN` (etc.)
  without the IF NOT EXISTS guard, accepting the duplicate-role
  failure.** Rejected: makes the new migration non-idempotent
  against operators who have legitimately re-run V2 manually
  (e.g. in a recovery scenario). `ALTER ROLE` is the idiomatic
  Postgres verb for changing role attributes after creation.
- **Use a Flyway *repeatable* migration (`R__roles.sql`) that
  runs every checksum-change to re-enforce NOLOGIN.** Rejected:
  repeatable migrations are for objects that mutate over a
  project's lifetime (views, functions, seed data). Role
  attributes are configured once; a versioned migration is the
  right shape and matches V2's pattern.
- **Add the `ALTER ROLE` statements inside the existing
  `DO $$ … END $$` block in V2 by editing the file.** Rejected:
  V2 is applied; editing changes the checksum and breaks Flyway
  start.
- **Use `pg_authid.rolpassword IS NULL` rather than
  `pg_roles.rolcanlogin = false`.** Rejected: `pg_authid`
  requires superuser to read; `rolcanlogin` is the right column
  on the public `pg_roles` view. NULL password and NOLOGIN are
  separate attributes — a role can be NOLOGIN with a stored
  password from a prior `WITH PASSWORD` clause; the spec wants
  NOLOGIN (the login-attempt gate), not "no password set".
- **Block on M1-009 so the V number is deterministic at ticket
  authoring time.** Rejected: the two tickets are otherwise
  independent and forcing serialization adds latency for no real
  benefit. The implementer picks the next unused V at start time
  and the acceptance criteria are V-number agnostic (they grep on
  content, not filename).
