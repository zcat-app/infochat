---
id: M1-046
title: /grant-admin + /revoke-admin (per-adapter scope, global last-admin counter)
status: done
created: 2026-05-20
last_updated: 2026-05-24
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
      files: 10
      added: 2049
      removed: 11
redteam_findings:
  - date: 2026-05-24
    category: PERM-ESCAL
    severity: medium
    promise: |
      "Authorization → execution. Permission checks run in deterministic
      Java." (§Trust boundaries §3) + "Last-admin protection (bot admin
      only). ... Enforced at the trigger layer, not just the command
      layer, so a buggy command cannot bypass it." (§Authorization
      model). The handler-layer admin check is the spec's deterministic-
      Java permission gate; the trigger only enforces the last-admin
      floor, not the general "actor must still be admin at execution
      time" property.
    gap: |
      GrantAdminCommandHandler reads the actor row via lookupUser()
      OUTSIDE the executeGrant transaction (line 113 admin gate; line
      167 executeGrant tx). RevokeAdminCommandHandler has the identical
      shape. The actor row is never re-read and never row-locked. The
      VouchCommandHandler precedent (M1-045 redteam-fix round 2) closed
      the same TOCTOU with SELECT ... FOR UPDATE on the actor row
      INSIDE the transaction; the new handlers do not adopt that
      pattern. The V5 trg_last_admin_protection_update trigger only
      checks post-update qualifying-admin count, not the actor's
      current admin state, so it does not close this gap.
    repro: |
      Admin A and admin B are both online. B sends /revoke-admin A at
      T0. A — racing the demote — sends /grant-admin <accomplice> at
      T0+ε. A's handler reads is_admin=true (no row lock). B's UPDATE
      commits: A.is_admin=false. A's UPDATE commits: <accomplice>.is_admin
      =true. The trigger does not fire because B remains is_admin=true.
      A is now demoted but successfully promoted an accomplice AFTER
      their own demotion landed.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-05-24
    verdict: FINDINGS
    base: main
    head: m1/M1-046-grant-admin-revoke-admin
    verdict_file: docs/plan/m1/redteam/M1-046-2026-05-24.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Audited on the per-ticket branch (post-commit, pre-merge) at the
      user's explicit request. One medium PERM-ESCAL finding on the
      actor-admin-check TOCTOU window between the handler's lookupUser
      and the executeGrant/executeRevoke transaction; the VouchCommandHandler
      M1-045 precedent (SELECT ... FOR UPDATE on the actor row inside the
      tx) is the established repo fix. Two OUT-OF-MODEL observations
      (missing in-handler ban defense-in-depth; parseTargetContact does
      not run the fenced-code carve-out) are advisory.
      DISPOSITION: PERM-ESCAL finding fixed in-flow (commit soft-reset to
      in-progress; both handlers re-shaped to lookupActorForUpdate INSIDE
      the executeGrant/executeRevoke transaction, mirroring
      VouchCommandHandler). OUT-OF-MODEL #1 (banned-actor defense-in-
      depth) deferred — adding the in-handler is_banned check would
      pre-empt RevokeAdminCommandHandlerTest scenario (e)'s trigger-fire
      coverage (a banned-admin actor is the only path that drives the
      sole-qualifying-admin target through the handler, exercising the
      V5 trg_last_admin_protection_update catch). The threat-actor noted
      "currently unreachable per intake-side gate" — this is a
      consistency/pattern note vs M1-039 rather than an exploitable
      gap, and CLAUDE.md §"No defensive code for impossible scenarios"
      argues against adding the check just for pattern symmetry. The
      RevokeAdminCommandHandler javadoc records the trigger-catch as
      load-bearing for that single-qualifying-admin edge case.
      OUT-OF-MODEL #2 (homoglyph carve-out) deferred as benign per the
      threat-actor's own note: intake step 1.7 normalizes semantically
      before the handler runs.
clarity_check:
  date: 2026-05-24
  verdict: WARN
  warnings:
    - "acceptance item [6] (scenario (d)): wording is internally contradictory — first sentence describes the self-revoke path, second sentence describes the two-admin same-adapter path. Only the two-admin path satisfies the stated assertions (V5 trigger raises last_admin_protection; no REVOKE_ADMIN audit row). Implementer commits to the two-admin same-adapter shape as the sole description of test method (d)."
  blockers: []
blocked_by:
  - M1-044
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantRevokeAdminScopingIT.java
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §Authorization model (last-admin protection + per-adapter scope) + §Per-adapter admin threat profile + §Admin (commands.md) are the source of truth
  - any change to the V5 last-admin-protection triggers (`trg_last_admin_protection_update`, `trg_last_admin_protection_delete`) — those are V5's commit and consumed unchanged via the trigger raising on UPDATE/DELETE
  - any new migration — V5 already lands the trigger; no schema change in this ticket
  - any change to M1-044a/b/c services and handlers — consumed unchanged
  - any change to M1-045's CommandPermissions, ProbationCheck, VouchCommandHandler — consumed unchanged
  - any /ban / /unban / /invite / /vouch handler — those land in M1-044c and M1-045
  - any /promote / /demote handler — T2-F territory (group context only)
  - the M1-044 umbrella IT — M1-044 is FROZEN
  - any cross-adapter `/grant-admin` capability — spec says explicitly `/grant-admin` is inbound-adapter-scoped; the M1-040 InboundContext.adapterName() is the SOLE adapter source
  - any bootstrap-admin @Startup bean exercise — deferred per the T1-E handoff; this ticket assumes admins exist (test seeds them via direct INSERT)
  - any audit-log writer consolidation — M1-041 territory; the handlers write directly to audit_log
  - any TranslationProvider exercise — T2-C; new bundle entries are English only
  - any test outside the seven files in files_scope — every M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040/M1-044*/M1-045 test stays green unchanged
acceptance:
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java implements `CommandHandler` with `name() == \"grant-admin\"`. The handler: (1) requires `users.is_admin = true` on the caller — non-admin returns `error.admin_only`; (2) parses one positional `<contact>` argument; (3) returns `error.contact_not_registered` if no `users` row exists for `(inboundContext.adapterName(), target_contact_id)` — the lookup is inbound-adapter-scoped per spec §Authorization model; (4) returns `error.grant_admin.banned_target` if the target row's `is_banned=true` (granting admin to a banned user would be incoherent); (5) returns `error.grant_admin.already_admin` if the target row already has `is_admin=true` (no-op friendly reply); (6) on the happy path: `UPDATE users SET is_admin = TRUE WHERE id = ?` AND writes the `GRANT_ADMIN` audit row audit-before-effect with `target_kind='user'`, `target_id=<target.id::text>`, `target_contact_id=<target.contactId>`, `actor_adapter=inboundContext.adapterName()`, `details_json={\"target_adapter\": \"<inbound>\"}`; (7) on success returns `reply.grant_admin.success`. Verify: `grep -E 'inboundContext\\.adapterName' GrantAdminCommandHandler.java` returns ≥1 match (the per-adapter scope source) AND `grep -E 'UPDATE\\s+users\\s+SET\\s+is_admin\\s*=\\s*TRUE' GrantAdminCommandHandler.java` returns ≥1 match AND `grep -E 'GRANT_ADMIN' GrantAdminCommandHandler.java` returns ≥1 match"
  - "GrantAdminCommandHandlerTest covers, against a Testcontainers Postgres seeded with V1..V12 migrations: (a) non-admin caller receives `error.admin_only`; (b) admin caller against an unknown contact receives `error.contact_not_registered`; (c) admin caller against a `is_banned=true` target receives `error.grant_admin.banned_target`; (d) admin caller against an `is_admin=true` target receives `error.grant_admin.already_admin` (no-op, no audit row); (e) admin caller against a non-admin, non-banned target on the inbound adapter → row updates to `is_admin=true`, `GRANT_ADMIN` audit row written; (f) the SAME inbound adapter scoping holds when two `users` rows share a `contact_id` across adapters — seeding `(simplex, alice)` AND `(signal, alice)`, then issuing `/grant-admin alice` from a SimpleX inbound, asserts ONLY the SimpleX row gains `is_admin=true` AND the Signal row is unchanged. `grep -E '@Test' GrantAdminCommandHandlerTest.java` returns ≥6 matches"
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java implements `CommandHandler` with `name() == \"revoke-admin\"`. The handler: (1) requires `users.is_admin = true` on the caller — non-admin returns `error.admin_only`; (2) parses one positional `<contact>` argument; (3) rejects self-revoke (`actor.id == target.id`) with `error.revoke_admin.cannot_revoke_self` (the handler is the first-line UX; the V5 trigger is the last-line defense per spec §Authorization model `Enforced at the trigger layer, not just the command layer`); (4) returns `error.contact_not_registered` if no `users` row exists for `(inboundContext.adapterName(), target_contact_id)`; (5) returns `error.revoke_admin.not_admin` if the target's `is_admin=false` (no-op friendly reply); (6) on the happy path: `UPDATE users SET is_admin = FALSE WHERE id = ?` — the V5 `trg_last_admin_protection_update` trigger raises `SQLException` containing the literal `last_admin_protection` when the UPDATE would leave the deployment with zero `is_admin=TRUE AND is_banned=FALSE` rows; the handler catches that exception and surfaces `error.revoke_admin.last_admin`; (7) writes the `REVOKE_ADMIN` audit row audit-before-effect (BEFORE the UPDATE, inside the same transaction — if the trigger raises, the audit row's INSERT rolls back too); (8) on success returns `reply.revoke_admin.success`. Verify: `grep -E 'inboundContext\\.adapterName' RevokeAdminCommandHandler.java` returns ≥1 match AND `grep -E 'UPDATE\\s+users\\s+SET\\s+is_admin\\s*=\\s*FALSE' RevokeAdminCommandHandler.java` returns ≥1 match AND `grep -E 'cannot_revoke_self|self.revoke|actor.*==.*target' RevokeAdminCommandHandler.java` returns ≥1 match AND `grep -E 'last_admin_protection' RevokeAdminCommandHandler.java` returns ≥1 match (the trigger exception match)"
  - "RevokeAdminCommandHandlerTest covers: (a) non-admin caller receives `error.admin_only`; (b) admin self-revoke rejected with `error.revoke_admin.cannot_revoke_self` (the handler short-circuits BEFORE the SQL — the trigger is the LAST line of defense, not the only one); (c) admin caller against an unknown contact receives `error.contact_not_registered`; (d) admin caller against a `is_admin=false` target receives `error.revoke_admin.not_admin` (no-op); (e) the trigger-fire path — seeded with TWO admin rows: a `banned-admin` caller (`is_admin=true, is_banned=true` — bypasses intake by virtue of the unit test calling the handler directly) AND a `sole-active-admin` target (`is_admin=true, is_banned=false`). Pre-update, exactly ONE row qualifies as `is_admin=TRUE AND is_banned=FALSE` (the target). The handler accepts the caller (handler's caller-admin check counts any `is_admin=true`, not just unbanned), the self-revoke guard does NOT fire (caller.id ≠ target.id), the UPDATE attempts to flip the target's `is_admin` to false, V5's `trg_last_admin_protection_update` raises (post-update qualifying count would be 0), the handler catches the SQLException matching `last_admin_protection` and surfaces `error.revoke_admin.last_admin`. The target's `users` row is unchanged; no REVOKE_ADMIN audit row exists (the audit INSERT rolled back inside the same transaction); (f) admin caller revoking ONE of TWO admins (multi-admin deployment, one on each adapter) → trigger passes (global count check sees one remaining admin), the targeted row's `is_admin=true→false`, REVOKE_ADMIN audit row written, the OTHER adapter's admin row is UNCHANGED. `grep -E '@Test' RevokeAdminCommandHandlerTest.java` returns ≥6 matches"
  - "GrantRevokeAdminScopingIT is a `@QuarkusTest` `*IT`-named class that exercises the per-adapter scoping AND the global last-admin counter end-to-end against the fully-wired Provider stack. The IT drives messages through the ONE registered InMemoryAdapter (name='inmemory'); cross-adapter scoping is exercised by seeding `users` rows on a second virtual adapter name (`'signal-mock'`) via direct INSERT — the rows exist in the table but no MessagingAdapter bean is registered under that name. (The AdapterRegistry's gate-2 + gate-5 rules forbid two `MessagingAdapter` beans under different names today; T3-A lands the real SimpleX/Signal beans. The substantive contract — that the handler's `(adapter, contact_id)` SELECT bounds blast radius — is exercised identically because the handler reads `InboundContext.adapterName()`, which is `'inmemory'` for every inbound the IT drives.) The IT seeds at @BeforeEach a baseline of TWO admin rows: `(adapter='inmemory', contact_id='alice', is_admin=true)` AND `(adapter='signal-mock', contact_id='alice', is_admin=true)`. Scenarios: (a) `/grant-admin bob` from the inmemory inbound (caller is a separately-seeded inmemory admin) adds `(inmemory, bob)` as admin AND any `(signal-mock, bob)` row is unchanged; (b) `/revoke-admin alice` from the inmemory inbound (the caller is a DIFFERENT inmemory admin, not alice herself) flips `(inmemory, alice).is_admin=false`, the V5 trigger passes because the signal-mock alice row plus the caller still qualify, the `(signal-mock, alice)` row is UNCHANGED; (c) `/revoke-admin <signal-only-target>` from the inmemory inbound — where `<signal-only-target>` is seeded ONLY on signal-mock and not on inmemory — is REJECTED with `error.contact_not_registered` (the per-adapter scoped SELECT misses on inmemory). `grep -E '@Test' GrantRevokeAdminScopingIT.java` returns ≥3 matches"
  - "GrantRevokeAdminScopingIT scenario (d) — multi-admin same-adapter chained-revoke regression. A fresh test method seeds THREE admins on `inmemory` (`admin-1`, `admin-2`, `admin-3`, all `is_admin=true is_banned=false`) and NO admins on any other adapter (the scenario explicitly clears any baseline signal-mock admin rows so the global count starts at exactly the three seeded). The IT first issues `/revoke-admin admin-2` from the inmemory inbound with `admin-1` as the caller — succeeds (global admin count goes 3 → 2), `admin-2.is_admin` flips to `false`, REVOKE_ADMIN audit row written. THEN the IT issues `/revoke-admin admin-1` from the inmemory inbound with `admin-3` as the caller — succeeds (count goes 2 → 1, V5 trigger passes because one admin remains), `admin-1.is_admin` flips to `false`, REVOKE_ADMIN audit row written. This pins the regression that multi-admin same-adapter chained revokes do NOT spuriously fire the last-admin trigger until count would drop below 1. The trigger-fire path (count would go 1 → 0) is NOT exercisable via end-to-end /revoke-admin in the IT — the only caller-shape that can reach the trigger is `caller.is_admin=true AND caller.is_banned=true` targeting the sole active admin, and a banned caller is blocked at the M1-044b intake-side ban gate. The trigger-fire path is therefore covered by the unit test's scenario (e), which calls the handler directly and bypasses intake; the IT carries only the multi-admin-success regression. `grep -E '@Test' GrantRevokeAdminScopingIT.java` returns ≥4 matches total (scenarios a, b, c, d)"
  - "Per the M1-040 InboundContext pattern: BOTH handlers consume `@Inject InboundContext` AND every users-table SELECT filters on `(adapter, contact_id)`. There is NO cross-adapter lookup. Verify: `grep -E 'SELECT.*FROM\\s+users\\s+WHERE\\s+adapter\\s*=\\s*\\?\\s+AND\\s+contact_id\\s*=\\s*\\?' GrantAdminCommandHandler.java RevokeAdminCommandHandler.java` returns ≥1 match per file (the per-adapter scoped SELECT)"
  - "Per the M1-038 / M1-039 pattern: every contact-id-bearing exception message (IllegalStateException construction paths) interpolates the contact id via ContactIds.redact. Verify: `grep -E 'ContactIds\\.redact' GrantAdminCommandHandler.java RevokeAdminCommandHandler.java` returns ≥1 match per file"
  - "BundleKeys.java adds: ERROR_GRANT_ADMIN_BANNED_TARGET, ERROR_GRANT_ADMIN_ALREADY_ADMIN, REPLY_GRANT_ADMIN_SUCCESS, ERROR_REVOKE_ADMIN_CANNOT_REVOKE_SELF, ERROR_REVOKE_ADMIN_NOT_ADMIN, ERROR_REVOKE_ADMIN_LAST_ADMIN, REPLY_REVOKE_ADMIN_SUCCESS, ERROR_GROUP_ADMIN_NOT_IN_V1 (the shared group-scope-reject key consumed by both handlers per item [10]). Bundles/en.properties adds the entries. The error.revoke_admin.last_admin entry's value is operator-friendly (mentions the global last-admin invariant + a hint that another admin must be granted first). Verify: `grep -E '^error\\.grant_admin\\.banned_target\\s*=' bundles/en.properties` returns 1 match AND `grep -E '^error\\.revoke_admin\\.cannot_revoke_self\\s*=' bundles/en.properties` returns 1 match AND `grep -E '^error\\.revoke_admin\\.last_admin\\s*=' bundles/en.properties` returns 1 match. The BundleLoaderTest reflective bundle-completeness assertion catches any missing key at test time"
  - "Both handlers are DM-only in v1 (the ScopeRef.Group SPI does not carry the actor's contact id; T2-F lands the widening). Group-scope invocation returns `error.group_admin_not_in_v1` per the M1-044c precedent. Verify: `grep -E 'ScopeRef\\.Group|group.scope' GrantAdminCommandHandler.java RevokeAdminCommandHandler.java` returns ≥1 match per file (the group-scope short-circuit branch)"
  - "Both handlers reject probation users at the handler layer (DEFENSE IN DEPTH — M1-045's intake-side gate is the primary defense; an admin is by definition not a probation user, but the handler-side check guards against future changes that might decouple probation from is_admin). The check is `if (probationCheck.inProbation(actor.id())) return bundleLoader.get(ERROR_PROBATION_BLOCKED)` — same pattern as M1-039's in-handler ban check that survives M1-044b's intake-side ban gate. Verify: `grep -E 'probationCheck\\.inProbation|ProbationCheck' GrantAdminCommandHandler.java RevokeAdminCommandHandler.java` returns ≥1 match per file"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040/M1-043 tests, M1-044a/b/c subticket tests, M1-044 umbrella IT, M1-045 CommandPermissionsTest / ProbationCheckTest / VouchCommandHandlerTest / InboundRouterProbationOrderingTest"
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantRevokeAdminScopingIT.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
  preserves:
    - all tests currently green on main
    - every M1-044*/M1-045 test
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Per-adapter admin threat profile
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/schema.md §Identity and access
decision_refs:
  - D9
  - D46
---

# M1-046: /grant-admin + /revoke-admin (per-adapter scope, global last-admin counter)

## Context

T2-A.3 — the third of three Tier-2.A onboarding/auth tickets.
Lands the two bot-admin commands that mutate
`users.is_admin`:

- `/grant-admin <contact>` — flip `is_admin=true` on the
  inbound-adapter-scoped `(adapter, contact_id)` row.
  Rejects unknown contacts (the spec's Unknown-contact rule)
  and banned targets.
- `/revoke-admin <contact>` — flip `is_admin=true → false`
  on the inbound-adapter-scoped row. The V5
  `trg_last_admin_protection_update` trigger is the
  last-line defense against zero-admin states; the handler
  catches the trigger's exception and surfaces a friendly
  reply.

The spec's two non-obvious commitments this ticket pins
end-to-end:

1. **Per-adapter scoping.** The `<contact>` argument resolves
   against the **inbound** adapter (M1-040
   `InboundContext.adapterName()`). A bot admin on SimpleX
   cannot grant admin on Signal without running the command
   from Signal. This bounds the blast radius of a
   single-adapter compromise.
2. **Global last-admin counter.** `SELECT count(*) FROM users
   WHERE is_admin = true AND is_banned = false` is the
   global counter; the V5 trigger reads it on every
   `UPDATE users SET is_admin = false` and raises when the
   count would drop below 1. So a deployment with admins on
   two adapters may revoke one (the other remains); a
   single-admin deployment cannot revoke that admin.

The V5 schema already lands the trigger (no migration in
this ticket — `migration_touch: false`).

`complexity: medium` — small, focused; `risk: high` because
the handlers gate a global privilege grant.

`security_relevant: true`.

## Definition of Done

- `GrantAdminCommandHandler` (`/grant-admin`):
  inbound-adapter-scoped contact resolution + banned-target
  reject + already-admin no-op + UPDATE +
  audit-before-effect.
- `RevokeAdminCommandHandler` (`/revoke-admin`):
  inbound-adapter-scoped + self-revoke guard +
  contact-not-registered + already-not-admin no-op + UPDATE
  + V5-trigger-aware error path + audit-before-effect.
- New bundle keys + entries land in `BundleKeys.java` +
  `bundles/en.properties`.
- Per-handler unit tests against a Testcontainers Postgres
  exercise every acceptance scenario.
- GrantRevokeAdminScopingIT (a `@QuarkusTest` `*IT`) exercises
  the per-adapter scope + global last-admin counter
  end-to-end across two adapter names.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **Inbound-adapter resolution.** Both handlers read the
  adapter from `InboundContext.adapterName()` — the M1-040
  request-scoped bean. The contact-id lookup is the M1-040
  spec-aligned per-(adapter, contact_id) SELECT. No
  `--adapter` flag on either command; cross-adapter grant /
  revoke requires running the command from the target
  adapter.
- **Audit-row shape.** Both handlers write to `audit_log`
  directly (the M1-036 / M1-039 / M1-044c pattern). The
  M1-041 AuditLogWriter consolidation is deferred. Audit row
  shape:
  - `actor_user_id = caller.id`
  - `actor_contact_id = caller.contactId`
  - `actor_adapter = inboundContext.adapterName()`
  - `action = 'GRANT_ADMIN' | 'REVOKE_ADMIN'`
  - `target_kind = 'user'`
  - `target_id = target.id::text`
  - `target_contact_id = target.contactId`
  - `scope_id = NULL` (DM scope; the spec scopes admin
    commands to DM)
  - `request_id = UUID.randomUUID().toString()`
  - `details_json = {"target_adapter": "<inbound>"}`
- **Self-revoke guard.** The handler short-circuits BEFORE
  the SQL when `actor.id == target.id`. The V5 trigger has
  no signal of which connection issued the UPDATE (per
  M1-008a red-team finding), so the trigger is NOT the
  load-bearing self-revoke guard — the handler is. Pin this
  with an explicit test that asserts the handler check
  fires WITHOUT the SQL running (verify by counting `audit_log`
  rows AND `users` row mutations).
- **Last-admin trigger catch.** The V5 trigger raises
  `SQLException` with the literal `last_admin_protection: cannot leave the
  deployment with zero bot admins` in its message
  (V5 line 110). The handler catches `SQLException` AND
  matches on the `last_admin_protection` substring to
  surface the friendly `error.revoke_admin.last_admin`
  reply. Any other SQLException is treated as an internal
  error (rethrow as IllegalStateException with redacted
  message — the M1-039 pattern).
- **Audit-before-effect transactionally.** The audit INSERT
  and the users UPDATE run in one transaction with
  autoCommit=false. If the trigger raises, the audit INSERT
  is rolled back too — the audit log carries no row for the
  failed attempt. The spec's "audit-before-effect" rule is
  about the AUDIT ROW preceding the side effect WITHIN THE
  SAME TRANSACTION — not about audit rows surviving
  trigger-raised rollbacks. (A separate operator-visible
  signal — a Micrometer counter `last_admin_protection_blocks_total`
  — could record blocked attempts; out of scope for this
  ticket. Future ticket may add.)
- **Probation guard.** Both handlers consume M1-045's
  `ProbationCheck` and short-circuit with `error.probation.blocked`
  when `inProbation(actor.id)` returns true. This is
  defense-in-depth — M1-045's intake-side step 5 catches the
  same case earlier — but the in-handler check survives
  future changes that might decouple admin-tier from
  probation. (M1-039's in-handler ban check is the
  precedent: even after M1-044b lands the intake-side ban
  gate, the handler-side check remains.)
- **DM-only.** Both handlers refuse to run in group scope
  with `error.group_admin_not_in_v1` (the M1-044c precedent).
  T2-F lands the SPI widening that lets group-scope admin
  commands work.
- **GrantRevokeAdminScopingIT shape.** A `@QuarkusTest` that:
  - Configures `infochat.adapters=inmemory-a,inmemory-b` (two
    InMemoryAdapter beans with distinct names — the M1-035a
    SPI supports multiple registered adapters as long as both
    declare `allow-low-trust=true`).
  - Seeds `(inmemory-a, alice)` and `(inmemory-b, alice)` as
    bot admins via direct INSERT.
  - Seeds `(inmemory-a, actor-1)` as a third bot admin (the
    actual /revoke-admin caller — so alice is not herself the
    self-revoking actor).
  - Drives /grant-admin and /revoke-admin via
    `adapter.deliverDm` on the appropriate InMemoryAdapter
    instance.
  - Asserts per-(adapter, contact_id) row state via direct
    SELECT.

## Big-picture notes

- **Per-adapter scoping is the ONLY blast-radius bound
  available in v1.** The threat model assumes a compromised
  admin on adapter A can name any contact on adapter A. The
  per-adapter scope ensures they cannot pivot to adapter B
  without compromising a B admin's chat session
  independently. This ticket's GrantRevokeAdminScopingIT
  pins the bound.
- **Global last-admin protection prevents per-adapter
  scoping from being weaponized.** Without the global
  counter, an attacker who compromised the only admin on
  adapter A could revoke all admins on adapter A (including
  themselves on their way out — though the self-revoke guard
  would catch that specific case) and leave the deployment
  with a Signal admin still, but the SimpleX adapter
  effectively admin-less. The global counter ensures the
  deployment is never reduced to zero admins via /revoke-admin.
- **The V5 trigger is the last-line defense.** This ticket's
  handler is the first-line UX (friendly reply, no stack
  trace) but the trigger is the load-bearing security
  invariant. A future buggy command surface that calls
  `UPDATE users SET is_admin = false` directly would still
  hit the trigger.
- **/ban + last-admin protection.** M1-044c's BanCommandHandler
  also catches the V5 trigger's exception (banning the last
  admin would also leave zero admins per V5's
  `OR (OLD.is_banned = FALSE AND NEW.is_banned = TRUE AND
  OLD.is_admin = TRUE)` clause). The pattern is the same;
  this ticket's RevokeAdminCommandHandler uses the same catch.

## Out-of-scope expansion

- **M1-044/M1-045.** Consumed unchanged.
- **/ban + /unban + /invite.** M1-044c's commit.
- **/vouch.** M1-045's commit.
- **/promote + /demote.** T2-F (group scope).
- **/quarantine.** T2-G.
- **Cross-adapter grant.** Forbidden by spec.
- **The V5 last-admin trigger.** Already on disk; consumed
  via the SQLException catch shape.
- **AuditLogWriter consolidation.** M1-041.
- **The bootstrap-admin @Startup bean.** Deferred per the
  T1-E handoff; the IT seeds admin rows directly via INSERT.
- **Group-scope dispatch.** Refused with a friendly reply;
  T2-F lands the widening.
- **Translation.** T2-C; new entries are English only.

## Authorized test changes

- (none — this ticket adds five new test files and modifies
  no pre-existing test.)

## Alternatives considered

- **Implement /grant-admin and /revoke-admin in one
  `AdminTierCommandHandler` class with subcommand dispatch
  (mirroring /invite's create/list/revoke shape).** Rejected
  — the two commands are top-level slash commands per spec
  (`/grant-admin` and `/revoke-admin`, not `/admin grant`
  / `/admin revoke`). Two handlers with the appropriate
  `name()` is spec-correct.
- **Use a stored procedure for /grant-admin and /revoke-admin
  (mirroring V5's `delete_preban_user`).** Rejected — the
  Provider role already has `UPDATE` on `users` for these
  columns (V5 GRANT block), and the application-side audit
  row + transaction wrap delivers the same audit-before-
  effect guarantee. A stored procedure would add a Flyway
  migration with no audit-isolation benefit.
- **Add a Micrometer counter for blocked last-admin attempts.**
  Considered — operator-visible signal for admin-account
  compromise attempts. Deferred to a follow-up ticket; the
  audit log already records successful /revoke-admin actions,
  and a blocked attempt (trigger raise + rollback) leaves no
  audit row. The counter would expose blocked attempts
  out-of-band. Not load-bearing for this ticket's spec
  contract.
- **Allow /grant-admin to take an explicit `--adapter <name>`
  flag (mirroring /invite create).** Rejected — spec
  explicitly says /grant-admin is inbound-adapter-scoped.
  The cross-adapter carve-out for /invite create exists
  because invites grant NO elevated access; admin grants do
  grant elevated access, and the threat model assumes
  per-adapter blast-radius bounding.
