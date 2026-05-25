---
id: M1-079c
title: /promote + /demote + /group-timezone + auto-promote + group dispatch
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
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 1641
      removed: 14
clarity_check:
  date: 2026-05-25
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: medium is under-calibrated for a ticket that implements the group-admin promote/demote lifecycle (is_group_admin writes, authorization enforcement). Consider bumping to risk: high."
  blockers: []
blocked_by:
  - M1-079a
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupAutoPromoteService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/DemoteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupAutoPromoteServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/PromoteCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DemoteCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandlerTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-079c.md
out_of_scope:
  - infochat-messaging-adapter/** (except consuming it) — InMemoryAdapter extension is M1-079b
  - infochat-core/src/main/resources/db/migration/** — no migration in this ticket (schema is M1-079a)
  - any handler group short-circuit unwinding — M1-079d/e
  - any modification to SaveCommandHandler, SavedCommandHandler, UnsaveCommandHandler — M1-079e
  - any modification to AddSourceCommandHandler, RemoveSourceCommandHandler, SourceEnableCommandHandler, SourceDisableCommandHandler, FollowTagCommandHandler, UnfollowTagCommandHandler, LangCommandHandler — M1-079d
  - any modification to GrantAdminCommandHandler, RevokeAdminCommandHandler — M1-079e
  - any periodic digest logic — M1-080 territory
  - any modification to any pre-existing test in infochat-provider/src/test/ or infochat-messaging-adapter/src/test/
  - M1-079 umbrella's GroupLifecycleIT.java — the umbrella ticket owns the end-to-end IT
acceptance:
  - "GroupAutoPromoteService.tryAutoPromote(long groupId, long userId) attempts INSERT into group_membership with is_group_admin=true; ON CONFLICT DO NOTHING against the partial unique index; returns boolean indicating whether the promote succeeded (true) or the slot was already occupied (false)"
  - GroupAutoPromoteServiceTest.tryAutoPromote_succeedsWhenNoAdminExists passes
  - GroupAutoPromoteServiceTest.tryAutoPromote_returnsFalseWhenAdminSlotOccupied passes
  - GroupAutoPromoteServiceTest.tryAutoPromote_skipsBannedUser passes
  - GroupAutoPromoteServiceTest.tryAutoPromote_skipsProbationUser passes
  - "InboundRouter step 3 (group auto-register) now also calls GroupAutoPromoteService.tryAutoPromote when the group has zero active admins AND the sender is registered, non-banned, and non-probation — per security.md §Authorization model auto-promote eligibility"
  - "PromoteCommandHandler implements CommandHandler; name() returns 'promote'; handle() requires bot-admin caller (via InboundContext), validates target has active group_membership row, rejects banned targets with friendly error, demotes existing admin and promotes target in one transaction"
  - PromoteCommandHandlerTest.promote_succeedsForBotAdminWithValidTarget passes
  - PromoteCommandHandlerTest.promote_rejectsNonBotAdmin passes
  - PromoteCommandHandlerTest.promote_rejectsBannedTarget passes
  - PromoteCommandHandlerTest.promote_rejectsTargetNotInGroup passes
  - PromoteCommandHandlerTest.promote_demotesExistingAdminInSameTransaction passes
  - "DemoteCommandHandler implements CommandHandler; name() returns 'demote'; handle() requires bot-admin caller, validates target is current group admin, clears is_group_admin"
  - DemoteCommandHandlerTest.demote_succeedsForBotAdminWithValidTarget passes
  - DemoteCommandHandlerTest.demote_rejectsNonBotAdmin passes
  - DemoteCommandHandlerTest.demote_rejectsTargetNotCurrentAdmin passes
  - "GroupTimezoneCommandHandler implements CommandHandler; name() returns 'group-timezone'; handle() requires group scope + (group-admin or bot-admin) caller, validates IANA zone name, updates groups.timezone column, audit-logs before effect"
  - GroupTimezoneCommandHandlerTest.groupTimezone_setsValidTimezone passes
  - GroupTimezoneCommandHandlerTest.groupTimezone_rejectsInvalidZone passes
  - GroupTimezoneCommandHandlerTest.groupTimezone_rejectsNonAdminCaller passes
  - GroupTimezoneCommandHandlerTest.groupTimezone_rejectsDmScope passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupAutoPromoteServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/PromoteCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DemoteCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Authorization model
  - docs/spec/schema.md §Identity and access
decision_refs:
  - D9
  - D16
redteam_findings:
  - date: 2026-05-25
    category: PERM-ESCAL
    severity: high
    promise: |
      Banned and probation users are ineligible for group-admin promotion.
    gap: |
      PromoteCommandHandler checks target.isBanned but does not check
      probation_until. A bot admin can /promote a probation user.
    repro: |
      Bot admin /promote X while X is in probation → X becomes group admin.
    suggested_fix_class: missing-auth-check
  - date: 2026-05-25
    category: AUDIT-EVASION
    severity: medium
    promise: |
      Audit-log the intent for security-sensitive privilege-granting paths.
    gap: |
      GroupAutoPromoteService.tryAutoPromote writes no audit row when
      auto-promote succeeds. The elevation is invisible in audit_log.
    repro: |
      User auto-promoted in a new group; no audit_log row records the event.
    suggested_fix_class: audit-log-coverage
  - date: 2026-05-25
    category: PERM-ESCAL
    severity: medium
    promise: |
      Banned-user check gates all application-level DB writes (step 4).
    gap: |
      InboundRouter's step 3 cont. fires ensureGroupMembership for ALL
      known users (including banned) BEFORE the ban check at step 4.
    repro: |
      Banned user X messages a group → gets a membership row before step 4 stops them.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-05-25
    verdict: FINDINGS
    base: "0d81d46^"
    head: "0d81d46"
    verdict_file: docs/plan/m1/redteam/M1-079c-2026-05-25.md
    findings_count: 3
    out_of_model_count: 1
    note: |
      1 high (missing probation check on /promote target), 2 medium
      (auto-promote audit gap, membership write before ban check).
      Ticket is done; remediation lands as a new ticket.
---

# M1-079c: /promote + /demote + /group-timezone + auto-promote + group dispatch

## Context

This ticket ships the three new group-admin commands (`/promote`,
`/demote`, `/group-timezone`), the `GroupAutoPromoteService` that
implements the first-mention auto-promote path, and the InboundRouter
wiring that calls auto-promote at step 3. Together these establish the
group-admin lifecycle that all downstream handler unwinding depends on
— without a promote path, the admin-gated handlers (M1-079d) have no
way to designate an admin.

The spec contract is `docs/spec/security.md` §Authorization model
(one group admin per group, first-mention auto-promote, `/promote`
swaps admin in one transaction) + `docs/spec/commands.md`
§Conversation control (`/group-timezone`).

## Acceptance

1. `GroupAutoPromoteService.tryAutoPromote` uses
   INSERT...ON CONFLICT DO NOTHING against the partial unique index.
   Returns true on success, false when the slot is occupied. Skips
   banned and probation users (they are ineligible per spec).
2. InboundRouter's step 3 calls auto-promote when the group has zero
   active admins and the sender is eligible.
3. `/promote <contact>` — bot-admin-only, group scope, swaps the
   existing admin in one transaction, rejects banned targets and
   targets without active membership.
4. `/demote <contact>` — bot-admin-only, group scope, clears
   `is_group_admin` on the target.
5. `/group-timezone <tz>` — group scope, group-admin or bot-admin,
   validates IANA zone, updates `groups.timezone`, audit-logs.
6. All handler tests pass; `mvn verify` is green.

## Out-of-scope

- Handler group short-circuit unwinding (M1-079d/e) — this ticket
  adds the admin lifecycle commands; those tickets consume it.
- Periodic digest scheduling (M1-080) — depends on `/group-timezone`
  but is a separate ticket chain.
- Membership event handling (Provider-side consumption of UserLeft,
  BotRemoved clearing `removed_at`) — that wiring can land in
  M1-079c if file budget allows but is NOT an acceptance item; the
  umbrella IT (M1-079) pins it if needed.
- InMemoryAdapter extension (M1-079b) — consumed but not modified.
- Any pre-existing test modification.

## Notes

- `/promote` demotes the existing group admin in the same transaction
  as promoting the new one. This is a single UPDATE + INSERT (or
  UPDATE of both rows) guarded by the partial unique index — the
  index guarantees at most one admin at any point.
- The auto-promote path only fires when the group has ZERO active
  admins. It does NOT fire on the same message that auto-registers
  the user (that user starts in probation and is ineligible). The
  next non-banned, non-probation @mention from any member fills
  the slot.
- `/group-timezone` accepts any `java.time.ZoneId`-parseable string.
  Invalid zone names produce a friendly error with fuzzy suggestions
  (spec says "fuzzy suggestions over the IANA tzdb names").
- Audit-before-effect pattern: the audit row is written BEFORE the
  timezone UPDATE, matching the precedent in /ban, /unban, /invite.
- `/promote` and `/demote` both check that the target has an active
  `group_membership` row (`removed_at IS NULL`). A departed member
  cannot be promoted.
