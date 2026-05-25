# Session handoff — Tier 2 Group F: groups (actor seam widening + group support + periodic digests)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T2-F ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- M1 is functionally complete: 82 done, 6 deferred, 0 pending.
  T2-A (onboarding/auth), T2-B (DM commands), T2-C (translation),
  T2-D (chat-mode), T2-E (privacy), T2-G infrastructure (M1-058
  throttled admin notifier), and T2-H (asset commands) are all done.
- STATUS.md: pending=0, in-progress=0, done=82, deferred=6,
  total=88. The full history is `git log --grep "^M1-"`.
- Branch is main, otherwise clean.
- Last allocated ticket ID: M1-068. Next free: M1-069.
- Last Flyway migration: V19__summary_anchor.sql. Next free: V20.
- Deferred tickets NOT in T2-F's path: M1-019 (API-key stdout
  redaction), M1-020 (exception sanitization), M1-021 (unspecified),
  M1-031 (unspecified), M1-034 (decomposed → M1-034a), M1-042
  (unspecified).

## What T2-F creates

T2-F lands three capabilities:

1. **Actor seam widening** — the `CommandHandler.handle(ScopeRef, String)`
   SPI currently carries NO inbound caller identity for group scope.
   `ScopeRef.Group(String adapterGroupId)` has no contact ID field.
   Every handler short-circuits group scope to `ERROR_*_GROUP_NOT_IN_V1`
   or `ERROR_*_GROUP_ADMIN_ONLY`. T2-F widens ScopeRef (or introduces
   a context object) so handlers can distinguish group admin from
   regular group member. The 20+ group short-circuits across all
   handlers are then replaced with real logic.

2. **Group membership infrastructure** — `groups` table, `group_membership`
   table, first-mention auto-promote, `/promote`, `/demote`,
   `/group-timezone`, group SPI on InMemoryAdapter (membership events),
   bot-removed-from-group and user-left-group lifecycle.

3. **Periodic group digests** — summary_cache table, staggered digest
   scheduler (morning + evening per group timezone), degraded fallback,
   missed-slot skip, `/retry --digest`, subscription-version-keyed cache.

## Key seams in the current code

### ScopeRef (the actor-seam gap)

Location: `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ScopeRef.java`

```java
public sealed interface ScopeRef {
    record Dm(String contactId) implements ScopeRef {}
    record Group(String adapterGroupId) implements ScopeRef {}
}
```

`ScopeRef.Group` carries only the adapter-level group ID. The comment
says: "Group-scope dispatch is deferred to T2-F; v1 ships this case
for type completeness."

### CommandHandler SPI

Location: `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandHandler.java`

```java
public interface CommandHandler {
    String name();
    OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText);
}
```

### InboundRouter dispatch (group path)

Location: `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java`

- Step 3 (lines ~365-385): `AutoRegisterService.resolveOrRegisterGroup()`
  inserts a `users` row with `registration_state='group_only'` on first
  group @mention. No `group_membership` row is created.
- Dispatch (lines ~485-502): slash commands loop CDI-discovered
  `CommandHandler` instances; chat-mode goes to
  `ChatAgent.handle(actorId, scopeKind, scopeId, normalized)`.
- Chat-mode already carries the actor ID separately via
  `ChatAgent.handle()`; only the command path is blocked.

### Handler group-scope short-circuit pattern

Every handler follows this pattern (representative: AddSourceCommandHandler):

```java
if (scope instanceof ScopeRef.Group) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY));
}
```

Handlers using this pattern (20+):
- AddSourceCommandHandler → ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY
- GrantAdminCommandHandler → ERROR_GROUP_ADMIN_NOT_IN_V1
- RevokeAdminCommandHandler → ERROR_GROUP_ADMIN_NOT_IN_V1
- SaveCommandHandler → ERROR_SAVE_GROUP_NOT_IN_V1
- SavedCommandHandler → ERROR_SAVED_GROUP_NOT_IN_V1
- UnsaveCommandHandler → ERROR_UNSAVE_GROUP_NOT_IN_V1
- FollowTagCommandHandler → ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY
- UnfollowTagCommandHandler → ERROR_UNFOLLOW_TAG_GROUP_ADMIN_ONLY
- LangCommandHandler → ERROR_LANG_GROUP_ADMIN_NOT_IN_V1
- SourceDisableCommandHandler → ERROR_SOURCE_DISABLE_GROUP_ADMIN_ONLY
- SourceEnableCommandHandler → ERROR_SOURCE_ENABLE_GROUP_ADMIN_ONLY
- RemoveSourceCommandHandler → ERROR_REMOVE_SOURCE_GROUP_ADMIN_ONLY
- ForgetCommandHandler → (group is handled)
- ExportCommandHandler → (group is handled)
- StopCommandHandler → (works in group via in-memory registry)

### BundleLoader (migration debt)

Location: `infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java`

Two signatures:
```java
public String get(@NonNull String key);                       // 1-arg, always returns en
public String get(@NonNull String key, @NonNull String langCode); // 2-arg, per-scope
```

Only `LangCommandHandler` uses the 2-arg form today. The wholesale
migration of all 168 call sites to the 2-arg per-scope accessor is
T2-F's responsibility (digests are the load-bearing translation case;
chat-mode used T2-D's own `ChatAgent` path which already resolves
scope language internally).

### SummaryAnchorRepository

Location: `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/SummaryAnchorRepository.java`

- Handles personal anchors (`command_kind='personal'`, `user_id IS NOT NULL`).
- Digest anchors (`command_kind='digest'`, `user_id IS NULL`) — schema
  exists (V19 partial unique index) but repository code is personal-only.
- `/retry --digest` routing is T2-F (M1-065's `/retry` reads personal
  anchors only).

### Group-related schema state

**Does NOT exist yet** (T2-F creates):
- `groups` table — `(adapter, upstream_group_id)` natural key, `removed_at`,
  `timezone` (defaults to UTC)
- `group_membership` table — `(group_id, user_id, is_group_admin)`,
  `removed_at`
- `summary_cache` table — keyed by `(group, slot, tag_subscription_version,
  source_subscription_version)` with TTL
- `one_admin_per_group` partial unique index

**Already exists** (wired in M1):
- V19: `summary_anchor` with digest partial unique index
  `WHERE user_id IS NULL`
- V7: `scope_preferences` with `tag_mode`, `language`,
  `tag_subscription_version`, `source_subscription_version`
- V18: `chat_session`, `chat_message`, `chat_memory` all keyed by
  `(user_id, scope_kind, scope_id)` — already group-scope-ready
- `AutoRegisterService.resolveOrRegisterGroup()` auto-registers
  group-only users on first @mention

### InMemoryAdapter group support

Location: `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/inmemory/InMemoryAdapter.java`

Currently DM-only. T2-F extends it with:
- Group primitives (create group, add member, remove member)
- @mention payload generation
- Membership event emission (`userJoined`, `userLeft`, `botRemoved`,
  `groupDeleted`)

### ThrottledAdminNotifier (infrastructure ready)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/ThrottledAdminNotifier.java`

Already exists from M1-058 (T2-G infrastructure that landed early).
Signature: `notifyOnce(String key, String errorClass, String message) → NotifyOutcome`.
Backed by V16 `admin_notification_state` table. T2-F's digest scheduler
uses the same notifier for `digest_slot_missed` notifications.

## Spec sections T2-F cites

- `docs/spec/commands.md` §Periodic group digests (line 1074+)
- `docs/spec/commands.md` §Operator note: group-admin race (line 1054)
- `docs/spec/commands.md` §Per-scope tag preferences (for tag_mode in groups)
- `docs/spec/commands.md` §Source management (group-admin gate)
- `docs/spec/commands.md` §Conversation control — `/group-timezone`
- `docs/spec/commands.md` §Conversation control — `/retry --digest`
- `docs/spec/commands.md` §Discovery — `/help` (group filtering)
- `docs/spec/schema.md` §Identity and access (Groups, Group membership)
- `docs/spec/schema.md` §Operational — Summary cache
- `docs/spec/schema.md` §Invariants — 3 (one_admin_per_group)
- `docs/spec/security.md` §Authorization model — step 3 (group auto-register)
- `docs/spec/security.md` §Authorization model — Auto-promote race protection
- `docs/spec/messaging.md` §Identity and groups (line 226)
- `docs/spec/messaging.md` §Failure handling (bot removed, user left)
- `docs/spec/deployment.md` §Topology (per-group timezone)
- `docs/spec/architecture.md` §Hardware profiles (summary worker count)
- `docs/spec/llm.md` §Per-task routing rules — summarizer language-aware

## Implementation plan mapping

T2-F maps to **Milestone 3** (Group support) + **Milestone 4** (Periodic
group digests) in the implementation plan (§4 of
`docs/plan/implementation-plan.md`). Read those milestone sections for
full acceptance criteria and G/W/T scenarios — they are the source of
truth for T2-F's acceptance items.

## Recommended ticket split

Two tickets per the session-grouping-plan.md, but the actual file
count will likely push toward umbrella+subs. Evaluate at authoring
time:

  **T2-F.1** — Actor seam widening + group membership schema +
  InMemoryAdapter group SPI + `/promote` + `/demote` +
  `/group-timezone` + first-mention auto-promote + unwinding all
  group short-circuits across handlers. This is the load-bearing
  structural change; everything else depends on it.

  **T2-F.2** — Periodic digest scheduler + summary_cache table +
  staggered slot windows + degraded fallback + missed-slot skip +
  `/retry --digest` routing + subscription-version-keyed cache +
  zero-eligible-posts digest. Depends on T2-F.1 (needs group
  infrastructure and the actor seam to know which group to fire
  digests for).

The file count for T2-F.1 is likely 20+:
- ScopeRef widening (1 file)
- CommandHandler SPI change or context object (1 file)
- V20 migration (groups + group_membership + partial unique index) (1)
- GroupRegistry / GroupAutoPromote service (2)
- InboundRouter group dispatch rewiring (1)
- InMemoryAdapter group extension (1)
- MembershipEventConsumer SPI (1)
- /promote handler (1)
- /demote handler (1)
- /group-timezone handler (1)
- Bundle keys (1)
- 6+ test files
- 2+ ITs
≈ 20 files

If this exceeds the 12-file budget, use umbrella+subs per M1-008 /
M1-044 / M1-055 precedent:
- T2-F.1a: V20 migration + ScopeRef widening + CommandHandler SPI +
  GroupRegistry (schema + types foundation)
- T2-F.1b: InMemoryAdapter group SPI + MembershipEventConsumer
- T2-F.1c: InboundRouter rewiring + handler group short-circuit
  unwinding + /promote + /demote + /group-timezone
- T2-F.1d: Umbrella integration IT (group lifecycle end-to-end)

## Verify at authoring time (do not trust brief's values if main moved)

  - Next free ticket ID: `ls docs/plan/m1/tickets/ | sort -V | tail`
  - Next free Flyway integer: `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail`
  - ScopeRef location: `find . -name "ScopeRef.java" -path "*/main/*"`
  - CommandHandler signature: `grep -n "handle" infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandHandler.java`
  - Group short-circuit count: `grep -rn "GROUP_NOT_IN_V1\|GROUP_ADMIN_ONLY" infochat-provider/src/main/java/ | wc -l`
  - InMemoryAdapter path: `find . -name "InMemoryAdapter.java" -path "*/main/*"`
  - summary_anchor digest index: `grep -n "digest" infochat-core/src/main/resources/db/migration/V19*.sql`
  - BundleLoader 1-arg call sites: `grep -rn "bundleLoader.get(" infochat-provider/src/main/java/ | grep -v "langCode\|, " | wc -l`

## Design-vs-spec drift notes

1. The implementation plan §Milestone 3 lists `/save` and `/saved`
   under group support (A2). But M1-052 already shipped /save /saved
   /unsave in DM-only mode with group short-circuits. T2-F's role is
   to UNWRAP those short-circuits — the handler logic is done, only
   the permission gate changes.

2. The spec's `/retry --digest` (§Conversation control) requires
   per-group serialization (at most one in flight per group). M1-065
   shipped `/retry` for personal anchors only. T2-F adds the
   `--digest` variant with the serialization constraint.

3. BundleLoader migration — spec does not mandate when the 168 call
   sites move to per-scope resolution. T2-F is the natural home
   because digest replies must respect per-group language. However,
   the full migration can be phased: digests use 2-arg explicitly;
   other handlers can stay 1-arg (returning en) until a future
   ticket. The spec is satisfied as long as digests land in the
   correct language.

4. `groups.timezone` — the spec defaults to UTC and allows per-group
   override via `/group-timezone`. The operator-configured global
   morning/evening slot center hours are interpreted in each group's
   timezone. No per-group override of the SLOT itself (only when
   to wake up in local time).

## Dependencies and ordering

- T2-F does NOT depend on T2-G (quarantine). They are independent
  work streams.
- T2-F.2 (digests) depends on T2-F.1 (group infra).
- T2-F.1 is the largest structural change remaining in M1. It
  touches the SPI surface, router, all handlers, and introduces
  new schema. Plan for high complexity.

## Task

Author the T2-F ticket files in `docs/plan/m1/tickets/`. Follow
the ticket template at `docs/process/ticket-template.md`. Each
ticket must have correct frontmatter (id, title, status: pending,
complexity, risk, spec_refs, files_budget, files_scope, out_of_scope,
blocked_by, acceptance). Use the M1-044 umbrella pattern if file
counts exceed 12.

After authoring, run `scripts/lint-ticket.py` on each new ticket
file and fix any errors. Do NOT run `/m1-tick start` — only author.
```
