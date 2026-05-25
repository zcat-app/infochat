---
id: M1-079d
title: Admin-gated handler group unwinding (source/tag/lang)
status: done
created: 2026-05-25
last_updated: 2026-05-25
reviews:
  - round: 1
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 10
      added: 647
      removed: 152
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
blocked_by:
  - M1-079a
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceDisableCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/LangCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AdminGatedGroupScopeTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-messaging-adapter/** — adapter layer is M1-079b
  - infochat-core/src/main/resources/db/migration/** — no migration
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java — M1-079e
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java — M1-079e
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnsaveCommandHandler.java — M1-079e
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java — M1-079e
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java — M1-079e
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java — M1-079c
  - any new command handler — M1-079c handles /promote, /demote, /group-timezone
  - any periodic digest logic — M1-080 territory
  - M1-079 umbrella's GroupLifecycleIT.java
  - any modification to pre-existing tests NOT listed in files_scope
acceptance:
  - "AddSourceCommandHandler: group scope with group-admin caller proceeds to add-source logic; group scope with non-admin caller returns the group-admin-required friendly error (same BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY key, but now it fires based on a real membership check, not a blanket short-circuit)"
  - "RemoveSourceCommandHandler: group scope with group-admin caller proceeds; non-admin returns group-admin-required error"
  - "SourceEnableCommandHandler: group scope with group-admin caller proceeds; non-admin returns group-admin-required error"
  - "SourceDisableCommandHandler: group scope with group-admin caller proceeds; non-admin returns group-admin-required error"
  - "FollowTagCommandHandler: group scope with group-admin caller proceeds to follow-tag logic; non-admin returns group-admin-required error"
  - "UnfollowTagCommandHandler: group scope with group-admin caller proceeds; non-admin returns group-admin-required error"
  - "LangCommandHandler: group scope with group-admin caller proceeds to set per-scope language; non-admin returns group-admin-required error"
  - "All seven handlers obtain the sender identity from InboundContext.senderContactId() and the group id from ScopeRef.Group.adapterGroupId(), then query GroupMembershipRepository.isGroupAdmin(senderId, groupId)"
  - AdminGatedGroupScopeTest.addSource_allowsGroupAdmin passes
  - AdminGatedGroupScopeTest.addSource_rejectsNonAdmin passes
  - AdminGatedGroupScopeTest.followTag_allowsGroupAdmin passes
  - AdminGatedGroupScopeTest.followTag_rejectsNonAdmin passes
  - AdminGatedGroupScopeTest.lang_allowsGroupAdmin passes
  - AdminGatedGroupScopeTest.lang_rejectsNonAdmin passes
  - "Existing DM-scope test methods on each modified test file continue to pass unchanged (DM scope behavior is unaffected by the group unwinding)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AdminGatedGroupScopeTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandHandlerTest.java
  preserves:
    - all DM-scope test methods in modified test files
    - all tests currently green on main that are NOT in the modifies list
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Authorization model
decision_refs:
  - D9
redteam_findings:
  - date: 2026-05-25
    category: PERM-ESCAL
    severity: high
    promise: |
      "Group-admin (or bot admin acting in the group)" — bot-admin >= group-admin tier.
    gap: |
      AddSourceCommandHandler.java:139, FollowTagCommandHandler.java:149,
      LangCommandHandler.java:126, UnfollowTagCommandHandler.java:163 — gate checks
      only isGroupAdmin with no bot-admin fallback.
    repro: |
      Bot admin (is_admin=true) who is NOT group_admin sends /follow-tag in group →
      denied. Spec promises bot admins can execute group-admin-tier commands in groups.
    suggested_fix_class: missing-auth-check
  - date: 2026-05-25
    category: PERM-ESCAL
    severity: medium
    promise: |
      "Bot-admin only: /remove-source, /source-enable, /source-disable" — executable
      by bot admin regardless of scope.
    gap: |
      SourceDisableCommandHandler.java:103, SourceEnableCommandHandler.java:131,
      RemoveSourceCommandHandler.java:118 — isGroupAdmin gate fires before bot-admin check.
    repro: |
      Bot admin (is_admin=true, is_group_admin=false) sends /source-disable in group →
      ERROR_GROUP_ADMIN_NOT_IN_V1 before reaching the bot-admin gate.
    suggested_fix_class: missing-auth-check
  - date: 2026-05-25
    category: AUDIT-EVASION
    severity: medium
    promise: |
      "Authorization evaluation order: ... 8. Audit-log the intent."
    gap: |
      FollowTag, UnfollowTag, Lang handlers now execute privileged group-scope mutations
      with no audit_log rows. The code path was previously dead (blanket reject).
    repro: |
      Group admin sends /follow-tag sensitive-topic → modifies group scope_tag → no
      audit_log row. Later investigation cannot attribute the change.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-25
    verdict: FINDINGS
    base: dc9a04422a05f92e960efa7e0e23d125eba0fa60
    head: m1/M1-079d-admin-gated-handler-group-unwi
    verdict_file: docs/plan/m1/redteam/M1-079d-2026-05-25.md
    findings_count: 3
    out_of_model_count: 1
    note: |
      3 findings (1 high PERM-ESCAL, 2 medium). Bot-admin fallback missing in
      group-admin gate, and newly-live group-scope tag/lang mutations lack audit.
      Disposition: user decides whether to fix on this branch before merge or
      file a remediation ticket.
---

# M1-079d: Admin-gated handler group unwinding (source/tag/lang)

## Context

Seven command handlers currently short-circuit all group-scope
invocations with a blanket error (ERROR_*_GROUP_ADMIN_ONLY or
ERROR_*_GROUP_ADMIN_NOT_IN_V1). With M1-079a's
`GroupMembershipRepository.isGroupAdmin()` now available and
`InboundContext.senderContactId()` already carrying the actor
identity, these handlers can replace the blanket short-circuit with a
real group-admin permission check: admin callers proceed, non-admin
callers get the group-admin-required error.

The behavioral change per handler is small (3-5 lines replaced), but
it touches 7 production files + their tests, so the total file count
warrants its own ticket.

## Acceptance

Each of the seven handlers replaces:
```java
if (scope instanceof ScopeRef.Group) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_...));
}
```
with a real group-admin check via `GroupMembershipRepository` +
`InboundContext.senderContactId()`. Admin callers proceed to the
handler's main logic; non-admin callers receive the same bundle-keyed
error reply (the key may stay the same or be updated to a unified
group-admin-required key — implementer's call).

DM-scope behavior is completely unchanged.

## Out-of-scope

- /save, /saved, /unsave — those become any-member-accessible in
  M1-079e (different permission gate).
- /grant-admin, /revoke-admin — those stay DM-only in M1-079e.
- Any new command handler.
- InboundRouter changes.
- Periodic digest logic.
- The umbrella IT (M1-079).

## Authorized test changes

The following test files are modified to update group-scope test
methods from asserting a blanket error to asserting the new
admin-gated behavior (admin proceeds, non-admin gets error):

- `AddSourceCommandHandlerTest.java` — group-scope method now tests
  with InboundContext set to a group admin → expects success path.
- `FollowTagCommandHandlerTest.java` — same pattern.
- `UnfollowTagCommandHandlerTest.java` — same pattern.
- `LangCommandHandlerTest.java` — same pattern.

DM-scope test methods in these files are NOT modified and continue to
pass with unchanged assertions.

## Notes

- The handlers that already inject `InboundContext` (FollowTag,
  UnfollowTag, SourceEnable) only need the addition of
  `GroupMembershipRepository` injection. Handlers that don't yet
  inject `InboundContext` (AddSource) need both.
- `AdminGatedGroupScopeTest` is a focused test class that exercises
  the admin-vs-non-admin gate on representative handlers
  (AddSource, FollowTag, Lang) in one place. It complements the
  per-handler test modifications by providing a single assertion
  point for the shared permission pattern.
- The existing ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY key can be reused
  (it already says the right thing); the ERROR_*_NOT_IN_V1 keys can
  be retired or mapped to the same runtime message. Implementer
  decides based on bundle simplicity.
