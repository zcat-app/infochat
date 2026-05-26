---
id: M1-079
title: Group infrastructure umbrella — group lifecycle roundtrip IT
status: done
created: 2026-05-25
last_updated: 2026-05-26
reviews:
  - round: 1
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 306
      removed: 21
reopens:
  - date: 2026-05-26
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-084
    reason: M1-084 landed
escalations:
  - date: 2026-05-26
    reason: premise-fail
    reviewer_verdict_excerpt: |
      Two implementation gaps block acceptance step (h):
      (1) tryAutoPromote uses INSERT...ON CONFLICT DO NOTHING — only works
      for users without a group_membership PK row. u-1 already has one
      (created in step a, demoted in step f), so re-auto-promote silently
      returns false.
      (2) No MembershipEvent handler is wired — onMembershipEvent is a
      default no-op on MessagingAdapter; adapter.removeMember() fires the
      event into the void. group_membership.removed_at is never set.
clarity_check:
  date: 2026-05-26
  verdict: PASS
  warnings: []
  blockers: []
  round: 2
redteam_findings: []
redteam_audits:
  - date: 2026-05-26
    verdict: CLEAN
    base: main
    head: m1/M1-079-group-infrastructure-umbrella
    verdict_file: docs/plan/m1/redteam/M1-079-2026-05-26.md
    out_of_model_count: 0
    note: |
      Test-only diff. No production code, no new attack surfaces. Clean.
blocked_by:
  - M1-079a
  - M1-079b
  - M1-079c
  - M1-079d
  - M1-079e
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupLifecycleIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §Authorization model + §Identity and access + §Group membership are complete on main HEAD; this umbrella is test-only
  - any change to M1-079a's V20 migration or repositories — that commit is FROZEN at its review round
  - any change to M1-079b's InMemoryAdapter group SPI or MembershipEvent model — FROZEN
  - any change to M1-079c's /promote, /demote, /group-timezone handlers or GroupAutoPromoteService — FROZEN
  - any change to M1-079d's admin-gated handler unwinding — FROZEN
  - any change to M1-079e's member-access handler unwinding or DM-only gates — FROZEN
  - any change under infochat-core/src/main/resources/db/migration/ — M1-079a's V20 is the only migration; this umbrella adds no schema
  - any periodic digest logic — M1-080 territory
  - any modification to any pre-existing test in infochat-provider/src/test/, infochat-core/src/test/, or infochat-messaging-adapter/src/test/ — every prior test continues to pass unchanged
acceptance:
  - "infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupLifecycleIT.java exists, ends with *IT suffix so maven-failsafe-plugin runs it under mvn verify, and contains at least one @Test annotation"
  - "The IT is a @QuarkusTest with an inline @TestProfile setting infochat.adapters=inmemory and infochat.adapters.inmemory.allow-low-trust=true"
  - "Step (a) — first @mention auto-promote: adapter.deliverGroupMention(group1, u-1, '/help') registers u-1 in group and auto-promotes to group admin (u-1 is pre-registered, non-banned, non-probation); SELECT is_group_admin FROM group_membership WHERE ... returns true"
  - "Step (b) — second member join, no auto-promote: adapter.deliverGroupMention(group1, u-2, '/help') registers u-2 as regular member; is_group_admin is false (slot already occupied by u-1)"
  - "Step (c) — admin-gated command succeeds for admin: adapter.deliverGroupMention(group1, u-1, '/add-source ...') produces a non-error reply (the add-source logic runs, not the admin-gate rejection)"
  - "Step (d) — admin-gated command rejected for non-admin: adapter.deliverGroupMention(group1, u-2, '/add-source ...') produces the group-admin-required error reply"
  - "Step (e) — member-access command succeeds for any member: adapter.deliverGroupMention(group1, u-2, '/saved') produces a non-error reply (the saved logic runs)"
  - "Step (f) — /promote swaps admin: bot-admin issues /promote targeting u-2 in group1; u-2 becomes group admin, u-1 is demoted; subsequent admin-gated command from u-2 succeeds, from u-1 is rejected"
  - "Step (g) — /group-timezone updates timezone: group-admin u-2 issues /group-timezone Europe/Prague in group1; SELECT timezone FROM groups WHERE ... returns 'Europe/Prague'"
  - "Step (h) — user-left clears admin slot + next auto-promote: adapter.removeMember(group1, u-2) triggers UserLeft handling; group_membership row for u-2 has removed_at set and is_group_admin cleared; adapter.deliverGroupMention(group1, u-1, '/help') auto-promotes u-1 (slot now empty)"
  - "Step (i) — DM-only command in group returns DM-only error: adapter.deliverGroupMention(group1, u-1, '/grant-admin u-2') returns the ERROR_COMMAND_DM_ONLY reply"
  - "mvn -B clean verify from the repo root exits 0; GroupLifecycleIT runs under failsafe with no failures"
  - "Every prior test continues to pass"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupLifecycleIT.java
  preserves:
    - every test currently green on main
    - every test added by M1-079a, M1-079b, M1-079c, M1-079d, M1-079e
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/schema.md §Identity and access
  - docs/spec/commands.md §Source management
  - docs/spec/commands.md §Conversation control
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D9
  - D46
---

# M1-079: Group infrastructure umbrella — group lifecycle roundtrip IT

## Context

Umbrella commit for the M1-079 group (per
`docs/process/workflow.md` §Ticket-ID placeholder convention —
the umbrella + subticket idiom). M1-079a through M1-079e each ship a
slice of the T2-F.1 group infrastructure as its own reviewable commit
on `main`:

- **M1-079a** — V20 migration (groups + group_membership tables +
  partial unique index) + GroupRepository + GroupMembershipRepository.
- **M1-079b** — InMemoryAdapter group SPI (createGroup, addMember,
  removeMember, removeBot, deliverGroupMention) + MembershipEvent
  sealed type.
- **M1-079c** — /promote, /demote, /group-timezone command handlers +
  GroupAutoPromoteService + InboundRouter step 3 auto-promote wiring.
- **M1-079d** — Admin-gated handler group unwinding (7 handlers:
  AddSource, RemoveSource, SourceEnable, SourceDisable, FollowTag,
  UnfollowTag, Lang).
- **M1-079e** — Member-access handler group unwinding (Save, Saved,
  Unsave) + DM-only gates (GrantAdmin, RevokeAdmin).

Each subticket's per-class tests verify its own slice. This umbrella
verifies the **cross-cutting** property the subtickets cannot verify
in isolation: **the full group lifecycle — auto-promote, admin-gated
commands, member-access commands, /promote swap, user-left admin
clearing, re-auto-promote — works end-to-end through the
InMemoryAdapter**.

`security_relevant: true` — every IT step pins a spec commitment from
§Authorization model. A regression (non-admin accessing admin-gated
commands, auto-promote firing for banned users, /promote not clearing
the old admin) would be a security defect.

## Acceptance

The IT walks nine steps covering the full group-admin lifecycle:
auto-promote on first mention, non-admin rejection, member-access
bypass, /promote swap, /group-timezone, user-left clearing +
re-promote, and DM-only gate. Each step is a named assertion in the
acceptance list above.

## Out-of-scope

- Changes to any subticket file — all five subticket commits are
  frozen.
- Changes to migrations — V20 is M1-079a's commit.
- Periodic group digests — M1-080 territory.
- Probation users attempting group commands — the IT seeds
  non-probation users only; probation interaction is already covered
  by M1-045's IT.
- TranslationProvider exercise — T2-C; the IT asserts English bundle
  entries.
- Any modification to any pre-existing test.

## Notes

- The IT seeds users via raw JDBC (non-probation, non-banned,
  registered) before driving group interactions through the adapter.
- The bot-admin row (for /promote) is also seeded via JDBC — same
  pattern as M1-044's umbrella IT.
- InMemoryAdapter's group primitives (M1-079b) are the test driver;
  the IT calls `adapter.createGroup()`, `adapter.addMember()`, and
  `adapter.deliverGroupMention()` to simulate a messaging platform.
- The subticket commits are FROZEN at the umbrella round. If this IT
  exposes a defect, the fix is a NEW ticket — never an amendment to a
  passed commit.
