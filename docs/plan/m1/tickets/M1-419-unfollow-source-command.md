---
id: M1-419
title: Implement /unfollow-source per-scope unsubscribe command
status: pending
created: 2026-06-21
last_updated: 2026-06-21
blocked_by: []
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowSourceCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowSourceCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - USER_GUIDE.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  # The global source row must NEVER be touched — /unfollow-source deletes ONLY the
  # caller scope's source_subscription row (contrast /remove-source). Do not modify
  # the other source-command handlers.
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceDisableCommandHandler.java
  # /unfollow-source is a WRITE: it stays OUT of the probation closed-set
  # (CommandPermissions.ALLOWED). Do not add it there.
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java
  # source_subscription already exists in the schema — no migration.
  - infochat-collector/src/main/resources/db/migration/**
  # The LLM output sanitizer denylist ALREADY contains "/unfollow-source"
  # (LlmOutputSanitizer.java) — no change needed there.
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
acceptance:
  - UnfollowSourceCommandHandlerTest.dmCallerUnsubscribesOwnSubscriptionAndSourceRowSurvives passes
  - UnfollowSourceCommandHandlerTest.unfollowDeletesOnlyCallerScopeSubscriptionNotOtherScopes passes
    (per-scope isolation — another scope's subscription to the same source is untouched)
  - UnfollowSourceCommandHandlerTest.groupPlainMemberCannotUnfollowReturnsAdminOnlyError passes
  - UnfollowSourceCommandHandlerTest.groupAdminUnfollowsGroupSubscription passes
  - UnfollowSourceCommandHandlerTest.callerNotSubscribedReturnsNotSubscribedReply passes
    (caller scope has no subscription to that source id → friendly no-op reply, no audit write)
  - UnfollowSourceCommandHandlerTest.malformedOrUnknownIdReturnsError passes
    (mirror /remove-source — <id> parsed as a UUID; parse failure and unknown id both error)
  - UnfollowSourceCommandHandlerTest.successWritesAuditRowTaggedUnfollowSource passes
  - InboundRouter dispatches `/unfollow-source` to UnfollowSourceCommandHandler exactly once
    (mirror AddSourceCommandHandlerTest.inboundRouterDispatchesAddSourceToHandlerExactlyOnce)
  - HelpCommandHandlerTest asserts the catalogue includes `unfollow-source` at HelpTier.USER_OR_GROUP_ADMIN
    (shown to users in DM, to group admins in a group, never to a plain group member in a group)
  - A probation user invoking `/unfollow-source` receives `error.probation.blocked`
    (it is NOT in CommandPermissions.ALLOWED; verify via CommandPermissionsTest or the handler test)
  - mvn -pl infochat-provider -am verify is green (the `-am` is REQUIRED because this
    touches infochat-core AuditAction)
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnfollowSourceCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/spec/schema.md §Per-scope state
  - docs/spec/security.md §Slow-start tier
decision_refs:
reviews: []
revisions: []
escalations: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-419: Implement /unfollow-source per-scope unsubscribe command

## Context

`/unfollow-source <id>` is specified in `docs/spec/commands.md §Source management`
but has never been implemented. M1-053 (source-management admin commands) explicitly
deferred it: *"No /unfollow-source handler. That command is per-scope (not [admin])
... lands in a separate ... ticket"* (M1-053 lines 147, 451). It is currently
documented in `USER_GUIDE.md` and `ADMIN_GUIDE.md` and present in the
`LlmOutputSanitizer` denylist, but a user who types it gets "unknown command" and
the probation classifier's fail-closed path. This ticket implements it so a user
can actually unsubscribe a source for their own scope.

It is the inverse of the per-scope subscription that `/add-source` creates via
`SourceUpsertService.upsertSubscription(...)`: it deletes the caller scope's row
in `source_subscription` and **never touches the global source row** (contrast
`/remove-source`, which soft-deletes the source row and cascade-deletes ALL its
subscriptions). A source with no remaining subscribers is a valid state — it is
NOT auto-soft-deleted (spec §Source management).

## Behavioral contract (spec §Source management, "v1" permission note)

- `/unfollow-source <id>` — `<id>` parsed as a UUID exactly as `/remove-source`
  does (`RemoveSourceCommandHandler.parseSourceId`); parse failure → error reply.
- **DM:** deletes the caller's own subscription only
  (`source_subscription WHERE scope_kind='dm' AND scope_id=<callerUserId> AND source_id=<id>`).
- **Group:** **group admin or bot admin only** — a plain group member cannot
  unfollow a group subscription (spec is explicit: the "any member may unfollow
  what they added" exception is NOT in v1, it needs per-contributor ownership
  tracking that does not exist). Mirror the gate in `AddSourceCommandHandler`
  (group branch: `actor.isAdmin || groupMembershipRepository.isGroupAdmin(groupDbId, actor.id)`).
- Deletes ONLY the matching `(scope_kind, scope_id, source_id)` row — never the
  global source row, never another scope's subscription.
- Caller has no such subscription → friendly "not subscribed" reply, no audit row.
- On a real deletion, write an `audit_log` row tagged a NEW
  `AuditAction.UNFOLLOW_SOURCE` (mirror `RemoveSourceCommandHandler.insertAudit`
  via `AuditLogWriter` + the `RedactionHook.AuditRow` builder).
- Add a `HelpCommandHandler` CATALOGUE entry: `unfollow-source`,
  `HelpTier.USER_OR_GROUP_ADMIN`, new `BundleKeys.HELP_CMD_UNFOLLOW_SOURCE_SHORT`.
- `USER_GUIDE.md`: correct the worked-example id — the current `/unfollow-source 12`
  is wrong (`<id>` is a UUID, like every other source command); use a UUID example
  consistent with what `/list-sources` displays.

## Out-of-scope / invariants

- The global `source` row is untouched. `/unfollow-source` is purely a per-scope
  `source_subscription` delete.
- `/unfollow-source` is a WRITE → it stays OUT of `CommandPermissions.ALLOWED`
  (the probation closed-set is read-only + privacy/locale + `/stop`). Do not add it.
- No Flyway migration — `source_subscription` already exists.
- `LlmOutputSanitizer` already denylists `/unfollow-source` — no change.

## Notes

- **Security (`security_relevant: true`):** the core invariant is per-(user, scope)
  isolation — a caller MUST NOT be able to delete another scope's subscription, and
  a plain group member MUST NOT unfollow a group subscription. These are the two
  named acceptance tests (`unfollowDeletesOnlyCallerScopeSubscriptionNotOtherScopes`,
  `groupPlainMemberCannotUnfollowReturnsAdminOnlyError`). Warrants `/redteam` after
  approval.
- **Thin-SQL shape:** `RemoveSourceCommandHandlerTest` is `@QuarkusTest` "Thin-SQL
  Shape B" with inline SQL in the handler and `@SeedDataSource` fixtures prefixed
  for cleanup — mirror it (prefix fixtures `m1-419-`). The scoped delete is one
  statement: `DELETE FROM source_subscription WHERE scope_kind=? AND scope_id=? AND source_id=?`.
  No new repository class needed.
- **`AuditAction` is a core enum** (`infochat-core/.../audit/AuditAction.java`):
  adding `UNFOLLOW_SOURCE` is a cross-module change → run `mvn ... -am`. Check for
  any exhaustive `switch` over `AuditAction` (redaction/display) that the new
  constant would force you to extend; if one exists outside files_scope, escalate.
- Adjacent patterns to mirror: `AddSourceCommandHandler` (scope resolution +
  group-admin gate, lines 116-157), `RemoveSourceCommandHandler` (UUID parse,
  audit write, `@QuarkusTest` test), `SourceUpsertService.upsertSubscription`
  (the subscription row this command deletes).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-419-*.md
```
