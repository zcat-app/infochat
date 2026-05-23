---
id: M1-044
title: Invite-code intake umbrella — invite/ban/unban DM-gate roundtrip IT
status: done
created: 2026-05-20
last_updated: 2026-05-23
blocked_by:
  - M1-044a
  - M1-044b
  - M1-044c
clarity_check:
  date: 2026-05-23
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-05-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 490
      removed: 11
redteam_findings: []
redteam_audits:
  - date: 2026-05-23
    verdict: CLEAN
    base: main
    head: m1/M1-044-invite-intake-umbrella (working tree — pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-044-2026-05-23.md
    findings_count: 0
    out_of_model_count: 2
    note: |
      Pre-commit audit against the branch's working tree. CLEAN.
      The diff is purely a test-only addition (one new IT class);
      no production-source touches. Two OUT-OF-MODEL advisories
      noted in the verdict file: (1) trigger-disable in test
      fixture cleanup requires elevated DB privileges the
      production Provider role does not hold; (2) audit_log DELETE
      LIKE prefix is unique to this IT, no cross-test collision.
      Both are advisory; no remediation ticket required.
files_budget: 1
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteIntakeRoundtripIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §Authorization model + §User ban + §Invite-code registration are complete on main HEAD; this umbrella is test-only
  - any change to M1-044a's intake-step services (RateCapBucket, InviteCodeConsumer, BanCheck, AutoRegisterService rename-and-narrow, V12 brute-force counter migration) — that commit is FROZEN at its review round
  - any change to M1-044b's InboundRouter intake splice or step 7 DM-gate carve-out — that commit is FROZEN at its review round
  - any change to M1-044c's admin command handlers (/ban, /unban, /invite create/list/revoke) — that commit is FROZEN at its review round
  - any change under infochat-core/src/main/resources/db/migration/ — the V12 migration is M1-044a's commit; this umbrella does NOT add or alter migrations
  - any change to the M1-038 ContactIds.redact helper or the M1-040 InboundContext bean — both are consumed unchanged
  - any /vouch handler exercise — T2-A.2 (M1-045) territory; the umbrella's IT does not reach a /vouch graduation
  - any /grant-admin or /revoke-admin handler exercise — T2-A.3 (M1-046) territory
  - any /promote / /demote handler exercise — T2-F territory
  - any chat-mode behavior — T2-D territory; the IT covers only slash-command paths through the intake
  - any TranslationProvider exercise — T2-C territory; the IT asserts reply text against the English bundle keys
  - any group-scope behavior — DM scope only; group-scope onboarding is part of step 3 but is exercised by T2-F's eventual IT (the umbrella's IT does NOT drive a group `@mention`)
  - any modification to any pre-existing test in `infochat-provider/src/test/`, `infochat-core/src/test/`, or `infochat-messaging-adapter/src/test/` — every prior test continues to pass unchanged
acceptance:
  - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteIntakeRoundtripIT.java exists, is named with the `*IT` suffix so maven-failsafe-plugin runs it under mvn verify (the M1-008a-authored failsafe wiring already includes the provider module pattern), and contains at least one `@Test` annotation. Verify: `grep -E '@Test' InviteIntakeRoundtripIT.java` returns ≥1 match"
  - "The IT is a `@QuarkusTest` (NOT plain JUnit — it needs the full CDI graph: AdapterRegistry + InboundRouter + RateCapBucket + InviteCodeConsumer + BanCheck + AutoRegisterService + Ban/Unban/InviteCommandHandler + BundleLoader + the InMemoryAdapter bean). Verify: `grep -E '@QuarkusTest' InviteIntakeRoundtripIT.java` returns ≥1 match"
  - "The IT activates a test profile via an inline `@TestProfile(...)` whose `getConfigOverrides()` returns `Map.of(\"infochat.adapters\", \"inmemory\", \"infochat.adapters.inmemory.allow-low-trust\", \"true\")` so the registry's gate 5 (LOW-trust opt-in) passes — the same inline-profile shape M1-035's AdapterRouterIT.MvpProfile uses (infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java:227–233). No separate test-resources properties file. Verify: `grep -E 'allow-low-trust|infochat\\.adapters' InviteIntakeRoundtripIT.java` returns ≥1 match"
  - "Step (a) — unknown-DM-without-invite gate: `adapter.deliverDm(\"u-1\", \"random text not a uuid\")` produces exactly ONE outbound message whose body matches the `error.invite.required` bundle value (the fixed `Access requires an invitation.` reply); `SELECT COUNT(*) FROM users WHERE adapter='inmemory' AND contact_id='u-1'` returns 0 (no row written); `SELECT COUNT(*) FROM audit_log WHERE action='INVITE_CONSUME'` is unchanged. The IT asserts all three."
  - "Step (b) — bot admin /invite create --contact: seeded bot-admin (`is_admin=true`, `registration_state='vouched'`) issues `/invite create --adapter inmemory --contact u-1`; the reply contains the new code's UUID literal; `SELECT COUNT(*) FROM invite_code WHERE adapter='inmemory' AND expected_contact_id='u-1' AND status='PENDING'` returns 1; `SELECT COUNT(*) FROM audit_log WHERE action='INVITE_CREATE'` increments by 1. The IT asserts all three."
  - "Step (c) — invite-consume roundtrip: `adapter.deliverDm(\"u-1\", \"<the-code-from-step-b>\")` produces a welcome reply (body equals the `reply.welcome.dm_fresh` bundle value or matches the spec's DM-fresh welcome wording); `SELECT registration_state, probation_until FROM users WHERE adapter='inmemory' AND contact_id='u-1'` returns ONE row with `registration_state='invited'` AND `probation_until IS NOT NULL` (probation begins per D45); `SELECT status FROM invite_code WHERE code='<the-code>'` returns `'USED'`; `SELECT COUNT(*) FROM audit_log WHERE action='INVITE_CONSUME'` increments by 1. The IT asserts all four."
  - "Step (d) — pre-ban against unknown contact creates `preban` row + revokes pending invites: bot-admin issues `/ban u-2 --reason \"spam\"` against an unregistered contact AND a separate PENDING invite previously minted for `u-2` exists; after the ban: `SELECT registration_state, is_banned FROM users WHERE adapter='inmemory' AND contact_id='u-2'` returns ONE row with `registration_state='preban'` AND `is_banned=true`; the pre-existing PENDING `invite_code` row for `u-2` shows `status='REVOKED'`; `SELECT COUNT(*) FROM audit_log WHERE action='BAN' AND target_contact_id='u-2'` increments by 1. The IT asserts all three."
  - "Step (e) — pre-ban → /unban deletes the row via the V5 `delete_preban_user` stored procedure: bot-admin issues `/unban u-2`; the reply matches the `reply.unban.preban_deleted` bundle value (literal mentions \"pre-ban-only row removed\" + \"fresh invite required\"); `SELECT COUNT(*) FROM users WHERE adapter='inmemory' AND contact_id='u-2'` returns 0 (row deleted); `SELECT COUNT(*) FROM audit_log WHERE action='UNBAN_PREBAN_DELETE' AND target_contact_id='u-2'` returns 1 (the procedure writes audit-before-effect per V5). The IT asserts all three."
  - "Step (f) — post-unban DM still requires a fresh invite: `adapter.deliverDm(\"u-2\", \"any body\")` produces exactly ONE outbound message whose body matches `error.invite.required` (the fixed reply); `SELECT COUNT(*) FROM users WHERE adapter='inmemory' AND contact_id='u-2'` is still 0. The IT asserts both — verifying that pre-ban → /unban does NOT silently bypass the invite gate."
  - "Step (g) — banned registered user receives the fixed ban reply on subsequent DM: an `invited`, non-probation user `u-3` exists AND `is_banned=true` is set on their row directly via SQL (no /ban handler call needed — the IT pins the intake-side ban check, not the /ban command); `adapter.deliverDm(\"u-3\", \"/help\")` produces exactly ONE outbound message whose body matches `error.ban.fixed` bundle value (the `Your access has been revoked.` literal); no `/help` reply, no LLM call, no further DB write beyond the ban-check SELECT. The IT asserts the outbound body equals the bundle value AND that the InMemoryAdapter's `sentMessages()` list grew by exactly 1."
  - "mvn -B clean verify from the repo root exits 0; InviteIntakeRoundtripIT runs under failsafe; failsafe reports record at least one test executed AND no failures. Verify: `grep -rE 'InviteIntakeRoundtripIT' infochat-provider/target/failsafe-reports` returns at least one match AND `grep -rE '<testsuite[^>]*failures=\"0\"' infochat-provider/target/failsafe-reports` returns at least one match for InviteIntakeRoundtripIT"
  - "Every prior test continues to pass: M1-003 @QuarkusTest stubs, M1-007/007a/b/c SPI smoke tests, M1-008/008a/b/c schema + identity tests, M1-022..M1-026 ingest-source tests, M1-027/028 outbox/NOTIFY tests, M1-032/033/034a/034b eval-pipeline tests, M1-035 AdapterRouterIT, M1-035a InMemoryAdapterTest, M1-035b AdapterRegistryTest / StartupGatesTest / InboundRouterTest / InboundRouterNormalizeTest, M1-035c HelpCommandHandlerTest / AutoRegisterServiceTest / BundleLoaderTest, M1-036 AddSourceCommandHandler*Tests, M1-037 Summary*Tests, M1-038 InboundRouterContactIdRedactionTest, M1-039 AddSourceBanCheckOrderingTest / AddSourceContactIdRedactionTest, M1-040 SummaryProseInjectionTest / Summary*AdapterScopeIT / AddSourceAdapterScopeIT, M1-043 SummaryRefusalMarkerTest (when M1-043 lands), plus every M1-044a / M1-044b / M1-044c subticket test"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteIntakeRoundtripIT.java
  preserves:
    - every test currently green on main
    - every test added by M1-044a, M1-044b, and M1-044c
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §User ban
  - docs/spec/security.md §Invite-code registration
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/commands.md §Onboarding
decision_refs:
  - D9
  - D44
  - D45
  - D46
---

# M1-044: Invite-code intake umbrella — invite/ban/unban DM-gate roundtrip IT

## Context

Umbrella commit for the M1-044 group (per
`docs/process/workflow.md` §Ticket-ID placeholder convention —
the umbrella + subticket idiom). M1-044a, M1-044b, and M1-044c
each ship a slice of the T2-A.1 onboarding/auth surface as its
own reviewable commit on `main`:

- **M1-044a** — intake-step **services** + V12 brute-force
  counter migration:
  - `RateCapBucket` (in-memory token bucket keyed by
    `(adapter, contact_id)` — step 1.5 of the authorization
    order).
  - `InviteCodeConsumer` (race-safe conditional UPDATE consume
    + per-`(adapter, contact_id)` brute-force counter
    increment + threshold-breach audit row — step 2 of the
    authorization order).
  - `BanCheck` (small lookup service the router invokes at
    step 4 — `SELECT is_banned FROM users WHERE adapter = ?
    AND contact_id = ?`).
  - `AutoRegisterService` **rename-and-narrow**: the
    auto-register-on-first-DM logic from M1-035c is removed
    (DM unknown contacts now route through the invite gate);
    the auto-register-on-first-group-`@mention` behavior is
    kept and extended to write
    `registration_state = 'group_only'` per spec §Authorization
    model step 3. The class stays under the same package and
    keeps the same name; method body / signature is what
    changes.
  - V12 Flyway migration creates the
    `invite_code_attempt` counter table backing the
    per-`(adapter, contact_id)` brute-force limit
    (§Invite-code registration — "Brute-force rate limit").
- **M1-044b** — InboundRouter **intake-step splice** + step 7
  DM-gate carve-out. Wires the M1-044a services into
  `InboundRouter.onMessage` in the exact spec-numbered order:
  step 1 (identity, already on disk) → 1.5 (rate cap) → 1.7
  (normalize, already on disk) → 2 (invite gate) → 3 (group
  auto-register, via the narrowed AutoRegisterService) → 4
  (ban check) → 6 (parse, already on disk) → 7 (permission +
  DM-gate carve-out for `registration_state='group_only'`).
  Ships the new fixed-reply bundle keys (`error.invite.required`,
  `error.ban.fixed`, `reply.welcome.dm_fresh`,
  `reply.welcome.group_first_mention`) and the per-profile
  rate-cap config keys in `application.properties`.
- **M1-044c** — admin command **handlers**: `/ban`, `/unban`,
  `/invite create --adapter <name> {--contact <id> | --open}`,
  `/invite list`, `/invite revoke`. Each writes its audit row
  audit-before-effect, consumes the M1-040 `InboundContext`
  for the inbound-adapter scope (except `/invite create`
  which takes an explicit `--adapter` flag per the
  spec's cross-adapter carve-out), and emits its bundle-keyed
  fixed replies. `/unban` against a `preban` row delegates to
  the V5 `delete_preban_user` stored procedure (already on
  disk) and surfaces the side-effect disclosure per §User ban.
  Pre-ban revokes any pending invites for the same
  `(adapter, contact_id)` in the same transaction as the ban,
  per spec.

Each subticket's per-class tests verify its own slice. This
umbrella verifies the **cross-cutting** property the subtickets
cannot verify in isolation: **the full intake step ordering
delivers the spec's invite/ban/unban DM-gate semantics
end-to-end through the InMemoryAdapter**. The IT seeds a
bot-admin row, drives the full invite-create → unknown-DM-without-code
→ unknown-DM-with-code → invite-consume → ban-against-unknown
→ unban-deletes-preban → fresh-invite-required-on-next-DM path,
and asserts every spec commitment along the way.

The whole-topic verification is meaningfully different from any
single subticket's unit-level assertions:

- M1-044a's per-service tests assert isolated bucket math,
  conditional-UPDATE returning-row count, the brute-force
  counter increment / threshold breach, the narrowed
  AutoRegisterService's group-only INSERT — each in isolation,
  against a Testcontainers Postgres.
- M1-044b's InboundRouterIntakeOrderingTest asserts step
  ordering by driving onMessage with synthetic
  `InboundMessage`s and recording which service was called when
  — but the dispatcher is a test fake, not the real handlers.
- M1-044c's per-handler tests assert each admin command's
  parsing, audit-write, and reply-shape — each isolated, no
  intake-side intake gate exercised, no InboundRouter dispatch.

None of those asserts the full intake-step ordering against the
real handlers via the real adapter. The IT walks every link and
asserts the user-observable spec contract. Shipping the
cross-class assertion as its own reviewable unit is exactly the
umbrella + subticket idiom's reason to exist.

`security_relevant: true` — every IT step pins a spec
commitment from §Authorization model + §User ban +
§Invite-code registration. A regression in any step would be a
security defect (an unknown contact reaching the LLM, a banned
user receiving a `/help` reply, a pre-ban row surviving
`/unban` and silently bypassing the invite gate). The IT is
the milestone-boundary attestation that the seam holds.

## Definition of Done

- A single `@QuarkusTest` `*IT`-named class lives at
  `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteIntakeRoundtripIT.java`.
- The `*IT` suffix matches maven-failsafe-plugin's convention;
  the failsafe wiring authored by M1-008a runs the IT under
  `mvn verify` from the repo root.
- The IT activates a test profile setting
  `infochat.adapters=inmemory` and
  `infochat.adapters.inmemory.allow-low-trust=true` (same shape
  as M1-035's AdapterRouterIT).
- The IT seeds a bot-admin row (`is_admin=true`,
  `registration_state='vouched'`) via raw JDBC at `@BeforeEach`
  (or once per class).
- One or more `@Test` methods drive the seven-step roundtrip:
  - (a) unknown DM without invite → `error.invite.required`,
    no row.
  - (b) bot-admin `/invite create --adapter inmemory --contact u-1`
    → PENDING row + reply contains the code + `INVITE_CREATE`
    audit row.
  - (c) `adapter.deliverDm("u-1", "<the-code>")` → welcome
    reply + `users` row inserted with `registration_state='invited'`
    + `probation_until` set + invite row transitions to `USED`
    + `INVITE_CONSUME` audit row.
  - (d) bot-admin `/ban u-2` against an unregistered contact
    (with a pre-existing PENDING invite for `u-2`) → row
    minted with `registration_state='preban'` + `is_banned=true`
    + the PENDING invite for `u-2` transitions to `REVOKED` in
    the same transaction + `BAN` audit row.
  - (e) bot-admin `/unban u-2` → row deleted via
    `delete_preban_user` + reply matches
    `reply.unban.preban_deleted` (mentions "pre-ban-only row
    removed" and "fresh invite required") +
    `UNBAN_PREBAN_DELETE` audit row.
  - (f) post-unban DM from `u-2` → fixed `error.invite.required`
    reply, no row.
  - (g) banned registered user (`is_banned=true` set directly
    via SQL on a non-preban row) sending `/help` → fixed
    `error.ban.fixed` reply, no `/help` reply, no LLM call.
- `mvn -B clean verify` exits 0; every prior test continues to
  pass.

## Implementation notes

- **`@QuarkusTest`, not plain JUnit.** This IT needs the full
  CDI graph the subtickets' classes wire into. Plain JUnit
  would defeat the IT's purpose.
- **The IT seeds bot-admin via raw JDBC at setup**, not via the
  `@Startup` bootstrap-admin bean (deferred per the T1-E
  handoff). A direct INSERT under the `infochat_provider`
  GRANT (`INSERT ON users`) is the simplest seam; the row
  carries `is_admin=true`, `registration_state='vouched'`,
  `probation_until=NULL`.
- **The IT seeds the `u-3` banned-registered scenario by
  setting `is_banned=true` via direct UPDATE on the row
  produced in step (c)'s invite-consume**, not by calling
  `/ban` from a separate inbound. This decouples step (g)'s
  intake-side ban check from the /ban handler under test in
  step (d); the IT pins each property independently.
- **Welcome-message bundle keys.** The exact wording lives in
  design notes (`docs/design/03-commands.md` §3.11 Welcome
  messages). M1-044b authors the bundle key entries from those
  literals; the IT reads the bundle via BundleLoader rather
  than baking the text into the test (M1-035 precedent).
- **Audit-row asserts use the `audit_log` table directly**, not
  the redacted `audit_log_view`. The IT runs under the
  `infochat_provider` role which has `INSERT` on `audit_log`
  but no direct `SELECT`; the test code can read via the same
  test-scope DataSource that other ITs use. (If the test-scope
  role differs from the production role in a way that makes
  this awkward, the IT may read via `audit_log_view` and adjust
  the assertion shape accordingly.)
- **Test-fixture reset.** The InMemoryAdapter's
  `sentMessages()` queue accumulates across `@Test` methods;
  call `adapter.reset()` in `@BeforeEach` plus a per-test
  truncate of `users`, `invite_code`, `invite_code_attempt`,
  and `audit_log` (or a transactional rollback wrapper).
- **The IT does NOT exercise** rate-cap dropping (step 1.5
  silent drop is asserted in M1-044a's RateCapBucketTest),
  brute-force threshold breach (asserted in M1-044a's
  InviteCodeConsumerTest), or the per-adapter open-cap /
  global contact-cap enforcement (asserted in M1-044c's
  InviteCommandHandlerTest). The umbrella's IT is the
  happy-path + ban-path attestation; cap / threshold / drop
  paths are subticket-level.

## Big-picture notes

- **The subticket commits are FROZEN at the umbrella round.**
  M1-044a, M1-044b, and M1-044c each land as their own
  reviewable commit on `main` before this umbrella becomes
  runnable. If this IT exposes a defect in one of the
  subticket outputs, the fix is a NEW ticket against the
  affected module — never an amendment to the subticket
  commit. The "never amend a passed commit" invariant in
  `CLAUDE.md` §M1 workflow applies verbatim.
- **The umbrella unblocks M1-045 and M1-046.** Both downstream
  tickets reference `M1-044` (the umbrella ID) in their
  `blocked_by`. M1-045 (slow-start + /vouch) extends the
  InboundRouter intake to add step 5 (probation check) and
  ships /vouch as a parallel admin handler. M1-046
  (/grant-admin + /revoke-admin) ships two more admin handlers
  that consume the V5 last-admin-protection trigger.
- **The umbrella IT is forward-compatible with T2-A.2 and
  T2-A.3.** M1-045 will author its own IT that extends the
  flow with probation→/vouch→full-access; M1-046 will author
  its own IT for /grant-admin per-adapter scoping + global
  last-admin counter. The umbrella does NOT pre-empt those —
  it stops at "registered, with probation_until set" rather
  than driving the probation graduation path.

## Out-of-scope expansion

- **Changes to any subticket file.** The three subticket
  commits are frozen.
- **Changes under `infochat-core/src/main/resources/db/migration/`.**
  M1-044a's V12 migration is the only migration in this
  group; this umbrella adds no schema change.
- **Changes to any pre-existing test.** Modifying any of them
  would be a test-integrity violation per
  `engineering-rules-verbatim.md` §8.
- **Group `@mention` dispatch.** Deferred to T2-F. The IT
  exercises DM scope only.
- **Slow-start / /vouch / probation exercise.** All M1-045
  territory. The IT asserts `probation_until IS NOT NULL`
  after invite-consume but does NOT drive any probation-gated
  request (the IT's /help in step (g) is from a
  non-probation user; M1-045 lands the probation-gate
  enforcement that would change the result on a probation
  user).
- **/grant-admin / /revoke-admin / /promote / /demote.** All
  M1-046 / T2-F territory.
- **TranslationProvider exercise.** T2-C. The IT asserts the
  English bundle entries.
- **Quarantine path.** T2-G.
- **Bootstrap-admin @Startup exercise.** Deferred per the T1-E
  handoff. The IT seeds the bot-admin row directly via JDBC.

## Authorized test changes

- (none — this umbrella adds one new test class in
  `infochat-provider` and modifies no pre-existing tests.)

## Alternatives considered

- **Make the IT a plain `@JUnitTest` and assemble the CDI
  graph by hand.** Rejected — same reasoning as M1-035's IT.
  Manual assembly would either duplicate production wiring
  (and rot when the subtickets evolve) or skip pieces (and
  not actually prove the roundtrip).
- **Inline the cross-cutting assertion into M1-044c's handler
  tests and skip the umbrella.** Rejected — M1-044c sees only
  its own slice. The full intake-step ordering through the
  real services is exactly the property a per-handler test
  cannot prove.
- **Extend the IT through /vouch + probation graduation.**
  Rejected — those depend on M1-045's CommandPermissions and
  ProbationCheck, which don't exist when this umbrella runs.
  Asserting against a non-existent code path would either
  skip the assertion or block this umbrella indefinitely.
  M1-045 lands its own sibling IT.
- **Drive step (g) by calling /ban from a separate inbound and
  then sending /help.** Rejected — couples two scenarios. The
  IT pins each property independently: step (d) covers /ban's
  audit + preban-row write; step (g) covers the intake-side
  ban check against an already-flagged row. Decoupling makes
  a future regression easier to localize.
