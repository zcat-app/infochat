---
id: M1-111
title: "Remove group_only registration path + simplify /vouch + V28 migration"
status: pending
created: 2026-05-27
last_updated: 2026-05-28
blocked_by:
  - M1-110
files_budget: 17
files_scope:
  - infochat-core/src/main/resources/db/migration/V28__d47_remove_group_only.sql
  - infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AutoRegisterService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-core/src/main/java/** — no SPI/Java changes; only the new V28 migration under db/migration/
  - V26 and earlier migrations — frozen (V26 is M1-110's territory; V27 is M1-093's claim)
  - GroupApprovalService or any new service — M1-112
  - /approve-group, /reject-group, /list-groups — M1-113
  - per-group rate caps — M1-112
  - GroupAutoPromoteService — not modified (D47 priority logic is M1-112)
acceptance:
  - "V28__d47_remove_group_only.sql executes `UPDATE users SET registration_state = 'invited' WHERE registration_state = 'group_only'` BEFORE altering the CHECK constraint. The UPDATE precedes the ALTER so the CHECK addition does not fail on lingering rows"
  - "V28 inserts an audit_log entry recording the bulk transition: action='D47_REGISTRATION_STATE_CONSOLIDATION', details_json carries the count of affected rows. The INSERT is conditional — if the UPDATE affected zero rows (fresh DB or test DB), no audit_log row is written"
  - "V28 alters the `users_registration_state_chk` CHECK constraint to `registration_state IN ('preban','invited','vouched')` — the 'group_only' value is removed. The existing constraint name is obtained by reading V5__identity_audit.sql (no other version touches it)"
  - "If M1-093 has already taken V27 in main when this ticket lands, V28 is the correct slot. If M1-093 has not landed, the migration is V27 and the file path in files_scope is adjusted accordingly. The reviewer accepts either V27 or V28 provided the file is the immediate successor to the highest existing migration on main"
  - "InboundRouter step 3 is replaced: a group @mention from an unregistered contact (no users row, or registration_state='preban') produces a silent drop — no reply, no DB write, no registration. Verify: grep -E 'resolveOrRegisterGroup|REGISTRATION_STATE_GROUP_ONLY' InboundRouter.java returns ZERO matches"
  - "InboundRouter step 4.7 / the DM-gate carve-out for group_only is removed. Verify: grep -iE 'group.only.*dm|dm.*gate.*group' InboundRouter.java returns ZERO matches"
  - "AutoRegisterService is deleted entirely. After InboundRouter's step-3 group auto-register call is removed it has ZERO remaining production callers: the only caller of resolveOrRegisterGroup is InboundRouter, and the @deprecated DM-side resolveOrRegister pass-through merely delegates to resolveOrRegisterGroup and has no callers of its own. Verify: the file infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AutoRegisterService.java does not exist. Consequence (authorized test changes): every test double that `extends AutoRegisterService` — InboundRouterProbationOrderingTest, InboundRouterConfirmCancelTest, InboundRouterContactIdRedactionTest, InboundRouterIntakeOrderingTest, InboundRouterNormalizeTest — must drop that supertype (replace with a local stub or inline fake) so the test module compiles"
  - "VouchCommandHandler no longer changes registration_state. The UPDATE sets only probation_until=NULL. Verify: grep -E 'group_only|vouched|registration_state' VouchCommandHandler.java returns ZERO matches for group_only and vouched state transitions"
  - "VouchCommandHandler is a no-op with friendly reply when the user is already past probation (probation_until IS NULL OR probation_until < NOW())"
  - "VouchCommandHandlerTest covers: (a) non-admin → error.admin_only; (b) unknown contact → error.contact_not_registered; (c) user in probation → probation_until cleared, reply.vouch.success; (d) user already past probation → no-op reply.vouch.noop; grep -E '@Test' VouchCommandHandlerTest.java returns ≥4 matches"
  - "InboundRouterIntakeOrderingTest: scenario (g) for DM-gate-for-group_only is removed or replaced with the D47 silent-drop scenario for unregistered group contacts. grep -iE 'groupOnlyDmGate|group_only' InboundRouterIntakeOrderingTest.java returns ZERO matches"
  - "All test files that previously seeded registration_state='group_only' are updated to use 'invited' or to test the new silent-drop behavior instead. The CHECK constraint after V28 will reject 'group_only' at INSERT, so any remaining fixture that seeds it will fail with a SQL CHECK violation — this is the implicit verification that the migration was applied"
  - "No remaining production-code references to the registration-state 'group_only' as a literal string or as a constant. Verify: grep -rE \"'group_only'|REGISTRATION_STATE_GROUP_ONLY\" infochat-provider/src/main/ returns ZERO matches. NOTE: the unrelated bundle key BundleKeys.ERROR_RETRY_DIGEST_GROUP_ONLY (value \"error.retry.digest_group_only\" — a digest-command scope error meaning \"digest retry only works in a group\", NOT the registration state) and its sole use in RetryCommandHandler.handleDigestRetry are out of scope and MUST NOT be renamed. The grep pattern is deliberately narrowed to REGISTRATION_STATE_GROUP_ONLY so it does not false-match the DIGEST_GROUP_ONLY constant"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
  preserves:
    - all tests currently green on main (after adapting group_only references)
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Invite-code registration
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/schema.md §Identity and access
decision_refs:
  - D44
  - D45
  - D47
reviews: {}
escalations:
  - date: 2026-05-28
    reason: outline-fail
    reviewer_verdict_excerpt: |
      OUTLINE FAILED — plan-writer: pre-existing green test
      InboundRouterProbationOrderingTest.java hard-depends on the two behaviors
      this ticket removes (the resolveOrRegisterGroup step-3 call-list assertion
      at line 205 and the step-4.7 group_only DM-gate scenario (f) at lines
      217-254; group_only seeds at lines 228, 368). The file is absent from
      files_scope, test_plan.modifies, and files_budget — modifying it is
      unauthorized and pushes the file count past files_budget: 14. No
      implementable outline exists within the ticket as written.
revisions:
  - date: 2026-05-28
    reason: outline-fail rework — add 3 AutoRegisterService-subclass test doubles to files_scope + test_plan.modifies, raise files_budget, narrow acceptance item 13 grep (false-matched unrelated DIGEST_GROUP_ONLY), resolve acceptance item 7 to delete-entirely
    prior_values: |
      files_budget: 14
      files_scope: (did not list InboundRouterProbationOrderingTest,
        InboundRouterConfirmCancelTest, InboundRouterContactIdRedactionTest)
      acceptance[7]: "AutoRegisterService.resolveOrRegisterGroup is removed or the
        entire class is deleted. If the class is retained for the DM-side
        resolveOrRegister call (compatibility), the group path is gone. Verify:
        grep -E 'resolveOrRegisterGroup' AutoRegisterService.java returns ZERO
        matches (or the file does not exist)"
      acceptance[13]: "No remaining production-code references to 'group_only' as a
        literal string or as a constant. Verify: grep -rE \"'group_only'|GROUP_ONLY\"
        infochat-provider/src/main/ returns ZERO matches"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-111: Remove group_only registration path + simplify /vouch + V28 migration

## Context

D47 removes the `group_only` registration state and the group
auto-registration path. This ticket bundles three coupled concerns
that must land atomically:

1. **V28 migration** (`UPDATE users group_only→invited`, audit_log
   row, ALTER `users_registration_state_chk` to remove 'group_only')
   — moved here from M1-110 because the CHECK alteration breaks
   existing code/test consumers that still write 'group_only'.
2. **Production code update** — InboundRouter, AutoRegisterService,
   VouchCommandHandler stop reading/writing `group_only`.
3. **Test fixture migration** — every existing test that seeds
   `registration_state='group_only'` is updated to 'invited' or
   restructured to test the new silent-drop behavior.

These three changes are inseparable: landing the migration without
(2)+(3) leaves the worktree red (consumers violate the new CHECK);
landing (2)+(3) without the migration leaves the schema accepting
a value no code writes. M1-110 owns the additive `groups`-table
columns separately because that work is genuinely independent.

`complexity: high` because 17 files are affected, the InboundRouter
step 3 replacement must maintain the silent-drop invariant, and the
migration + test-fixture coordination spans modules. `round_cap: 3`
for margin.

`security_relevant: true` — the silent-drop for unregistered group
contacts is a security boundary (D47 gate #1).

`migration_touch: true` — V28 is owned by this ticket.

## Acceptance

See frontmatter.

## Out-of-scope

- V26 (groups.approval_status + activated_by additive) — M1-110.
- V27 (post_reference DDL) — M1-093.
- GroupApprovalService / step 3.5 wiring — M1-112.
- New admin commands — M1-113.

## Notes

- **Migration ordering inside V28.** The `UPDATE users` must precede
  the `ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT` to avoid a
  CHECK violation during the migration. Both statements run in the
  same migration script; Flyway wraps each script in a transaction
  by default on PostgreSQL, so the script is atomic.
- **V5 reference.** V5__identity_audit.sql is listed in files_scope
  because the V28 author needs to read it to confirm the existing
  CHECK constraint name (`users_registration_state_chk`). V5 itself
  is NOT modified by this ticket — it is read-only reference.
- **V27 vs V28 contingency.** M1-093 has claimed V27 in its ticket
  but has not landed yet. Whichever of M1-093 and M1-111 lands first
  takes V27; the second takes V28. The migration's content does not
  depend on the version number — the implementer reads the highest
  existing migration on main at start time and picks the next slot.
- **Audit row is conditional.** The `INSERT INTO audit_log` runs
  only if the `UPDATE` affected at least one row. On a fresh DB
  (CI / dev), zero rows have `group_only`, so the migration emits
  no audit row. Use a CTE form like
  `WITH updated AS (UPDATE ... RETURNING 1) INSERT INTO audit_log
   ... SELECT ... FROM updated HAVING COUNT(*) > 0` — exact SQL
  shape is the implementer's call, but no audit row on zero-effect
  is the spec promise.
- **AutoRegisterService fate (resolved at refine).** Ground truth:
  the only production caller of `resolveOrRegisterGroup` is
  InboundRouter step 3, and the `@deprecated` DM-side
  `resolveOrRegister` only delegates to `resolveOrRegisterGroup`
  with no callers of its own. Once step 3 is removed the whole
  class is dead, so it is deleted entirely (acceptance item 7).
  Five existing test doubles `extends AutoRegisterService`
  (InboundRouterProbationOrderingTest, InboundRouterConfirmCancelTest,
  InboundRouterContactIdRedactionTest, InboundRouterIntakeOrderingTest,
  InboundRouterNormalizeTest); each must drop that supertype so the
  test module compiles. All five are in files_scope.
- **Test fixture migration.** Many existing tests seed users with
  `registration_state='group_only'`. After V28 removes the CHECK
  value, these fixtures must change to 'invited'. The tests
  themselves may need logic changes (e.g., the DM-gate test
  scenario is replaced with a D47 silent-drop scenario). No new
  IT for the migration itself — the migration's correctness is
  implicitly verified by the existing tests passing (the CHECK
  rejects 'group_only' at INSERT, so any uncorrected fixture
  fails loudly).
- **Bundle keys.** Remove or update bundle keys related to the old
  group-first-mention welcome and the DM-gate-for-group_only reply
  if they become orphaned. Add no new keys — new keys are M1-112's
  scope.
