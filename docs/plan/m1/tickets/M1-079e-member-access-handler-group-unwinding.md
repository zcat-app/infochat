---
id: M1-079e
title: Member-access handler group unwinding + DM-only gates
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by:
  - M1-079a
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnsaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-messaging-adapter/** — adapter layer is M1-079b
  - infochat-core/src/main/resources/db/migration/** — no migration
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java — M1-079d
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowTagCommandHandler.java — M1-079d
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java — M1-079d
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/LangCommandHandler.java — M1-079d
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java — M1-079c
  - any new command handler — M1-079c handles /promote, /demote, /group-timezone
  - any periodic digest logic — M1-080 territory
  - M1-079 umbrella's GroupLifecycleIT.java
  - any modification to pre-existing tests NOT listed in files_scope
acceptance:
  - "SaveCommandHandler: group scope short-circuit replaced — any active group member can invoke /save in group scope (per spec §Conversation control, /save operates per-user-globally; group scope just identifies the calling user, no admin gate needed); handler resolves actorId via InboundContext.senderContactId()"
  - "SavedCommandHandler: group scope short-circuit replaced — any active group member can invoke /saved in group scope"
  - "UnsaveCommandHandler: group scope short-circuit replaced — any active group member can invoke /unsave in group scope"
  - "GrantAdminCommandHandler: group scope short-circuit replaced with a DM-only gate — invoking /grant-admin in group scope returns a friendly 'this command is available in DM only' error (not the old NOT_IN_V1 message)"
  - "RevokeAdminCommandHandler: group scope short-circuit replaced with a DM-only gate — same DM-only friendly error as /grant-admin"
  - "New bundle key ERROR_COMMAND_DM_ONLY added to BundleKeys.java and en.properties for the DM-only gate"
  - SaveCommandHandlerTest.save_succeedsInGroupScope passes
  - SavedCommandHandlerTest.saved_succeedsInGroupScope passes
  - UnsaveCommandHandlerTest.unsave_succeedsInGroupScope passes
  - "Existing DM-scope test methods on each modified test file continue to pass unchanged"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java
  preserves:
    - all DM-scope test methods in modified test files
    - all tests currently green on main that are NOT in the modifies list
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Admin (bot admin)
decision_refs:
  - D9
  - D13
---

# M1-079e: Member-access handler group unwinding + DM-only gates

## Context

Five handlers have group-scope short-circuits that need different
treatment than the admin-gated handlers in M1-079d:

- `/save`, `/saved`, `/unsave` — become accessible to any active
  group member. Per spec (decision D13), saved posts are
  per-user-globally, so the group scope just identifies the caller;
  no admin gate is needed.
- `/grant-admin`, `/revoke-admin` — remain DM-only commands. The
  current NOT_IN_V1 error is replaced with a permanent DM-only
  friendly error. These are bot-admin commands scoped to the inbound
  adapter per spec §Admin; group context is not meaningful.

## Acceptance

1. `/save`, `/saved`, `/unsave` work in group scope for any active
   group member. The handler resolves the actor via
   `InboundContext.senderContactId()` and proceeds with existing
   per-user logic.
2. `/grant-admin`, `/revoke-admin` in group scope return a new
   DM-only friendly error (bundle key `ERROR_COMMAND_DM_ONLY`) rather
   than the old NOT_IN_V1 placeholder.
3. DM-scope behavior for all five handlers is completely unchanged.
4. All tests pass; `mvn verify` is green.

## Out-of-scope

- Admin-gated handler unwinding (M1-079d).
- Any new command handlers (M1-079c).
- InboundRouter changes (M1-079c).
- Periodic digest logic (M1-080).
- The umbrella IT (M1-079).

## Authorized test changes

The following test files are modified to update group-scope test
methods from asserting a blanket NOT_IN_V1 error to asserting the new
behavior:

- `SaveCommandHandlerTest.java` — group-scope method now sets
  InboundContext.senderContactId and asserts success.
- `SavedCommandHandlerTest.java` — same pattern.
- `UnsaveCommandHandlerTest.java` — same pattern.

DM-scope test methods in these files are NOT modified.

## Notes

- For /save, /saved, /unsave the group-scope change is minimal:
  remove the `if (scope instanceof ScopeRef.Group) return error;`
  guard. The handler already operates per-user (the user's id comes
  from a DB lookup keyed by adapter + contactId, which works in any
  scope). The only addition is reading senderContactId from
  InboundContext when scope is Group (DM scope uses
  ScopeRef.Dm.contactId() as before).
- For /grant-admin, /revoke-admin, the behavioral change is just
  replacing one error key with another — no new logic beyond the
  gate. The handlers remain functionally DM-only; the spec does not
  define a group-scope semantic for bot-admin commands.
- ERROR_COMMAND_DM_ONLY is reusable for any future command that is
  DM-only by design.
