---
id: M1-227
title: "/digest on|off — group-admin toggle to pause/resume the periodic digest"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 14
files_scope:
  - infochat-core/src/main/resources/db/migration/V44__group_digest_enabled.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/DigestCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/schema.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - Per-group digest TIMES / intervals — the morning/evening slot hours stay deployment-wide operator properties (deployment.md §Configuration surface — Groups); this ticket adds an on/off delivery gate only, no per-group scheduling state.
  - DM-scope digests — there is no periodic DM digest in v1, so `/digest` from a DM scope is a friendly error, never a per-user toggle. This ticket does NOT introduce a DM periodic-digest path.
  - On-demand `/summary` — unaffected. A paused group can still run `/summary`; the gate suppresses only the scheduled push, not the on-the-fly path (D18).
  - The cached-digest mechanics, cluster traversal, translation cache, and degraded-fallback path — untouched; a paused group is simply not selected by the scheduler, so no digest is computed, cached, or sent for it.
  - A `decisions.md` D-number / addendum for the pause behavior — NOT included here (kept to the commands.md + schema.md spec edits). If the reviewer wants a decision-log trace, it is a separate one-line `spec:` follow-up.
  - Retiring Nostr-style inline redundancy or any adjacent digest code — surgical change only.
acceptance:
  - "Migration V44 adds `digest_enabled BOOLEAN NOT NULL DEFAULT true` to the `groups` table (alongside the existing `timezone` column from V5), so every existing and future group defaults to digests-on with no backfill needed."
  - "A new `DigestCommandHandler implements CommandHandler` with `name()` returning `\"digest\"`, modeled on `GroupTimezoneCommandHandler`: it is auto-discovered by `InboundRouter` (no router edit). `/digest on` sets `groups.digest_enabled = true`; `/digest off` sets it to `false`; both mutate the inbound group's row and audit-log BEFORE effect."
  - "Permission: `/digest on|off` is group-admin OR bot-admin only (same predicate as `/group-timezone`). A non-admin group member gets a friendly not-admin error (bundle key) and no state change."
  - "Scope: `/digest` invoked from a DM scope returns a friendly group-only error (bundle key), mirroring `/group-timezone`'s DM-scope rejection. `/digest` with a missing/unrecognized sub-verb (anything other than `on`/`off`) returns a friendly usage error listing the two sub-verbs; never a silent no-op and never a fall-through."
  - "`AuditAction` gains `DIGEST_ENABLE` and `DIGEST_DISABLE` (mirroring the existing `SOURCE_ENABLE`/`SOURCE_DISABLE` pair); the handler writes the matching action via `AuditLogWriter` before the row is mutated."
  - "`DigestScheduler.queryActiveGroups()` SELECT gains `AND digest_enabled` so the scheduler selects groups where `removed_at IS NULL AND approval_status = 'approved' AND digest_enabled`; a paused group is never selected for a slot (no compute, no cache write, no send)."
  - "A named `DigestSchedulerTest` case asserts a group with `digest_enabled = false` (otherwise approved, non-removed) is excluded from `queryActiveGroups()` / not scheduled, while an otherwise-identical `digest_enabled = true` group is included."
  - "`/digest` is added to the spec's §\"Closed list of privileged-tier commands\" under the Group-admin bullet (after `/group-timezone`, commands.md:1036-1039) AND to `LlmOutputSanitizer.CLOSED_LIST` (after `/group-timezone`, line 118). The existing sanitizer closed-list completeness test stays green with the new token present."
  - "`HelpCommandHandler.CATALOGUE` gains a `/digest` entry at the group-admin help tier with a new `BundleKeys.HELP_CMD_DIGEST_SHORT` key; all new bundle keys (help short line, on/off confirmations, DM-scope error, not-admin error, usage error) are present in BOTH `en.properties` and `cs.properties` so the build-time bundle-completeness check passes."
  - "`docs/spec/commands.md` §Conversation control documents `/digest on|off` (group only; group admin or bot admin; audit-logged; pauses/resumes the periodic digest; on-demand `/summary` unaffected), and `docs/spec/schema.md` §Group records the `digest_enabled` column (default true, gates the digest scheduler's group selection)."
  - "mvn -B clean verify from the repo root exits 0; all tests currently green on main stay green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Permission model
  - docs/spec/schema.md §Identity and access
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-227: /digest on|off — group-admin toggle to pause/resume the periodic digest

## Context

Today a group's morning/evening periodic digest fires automatically for
every `approval_status = 'approved' AND removed_at IS NULL` group
(`DigestScheduler.queryActiveGroups()`, `schema.md` §Group). There is no
way to keep the bot active in a group (chat, on-demand `/summary`, asset
commands, source management) while silencing the unsolicited twice-daily
push. The only current "off" switches are `/reject-group` or the bot
leaving — both of which kill ALL interaction (D47), which is far too
blunt for "I just don't want the auto-digest."

This ticket adds a narrow delivery gate: `/digest off` pauses the
scheduled digest for the calling group; `/digest on` resumes it. Data
collection is unaffected (the Collector ingests regardless — the digest
is a pure Provider-side read+send), and on-demand `/summary` keeps
working for a paused group. The gate is a single boolean the scheduler
ANDs into its existing group-selection query.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Column home — `groups.digest_enabled`, NOT `scope_preferences`.**
  An earlier sketch put the flag on `scope_preferences` (where
  `tag_mode` lives). That was reconsidered: the existing per-group
  digest-scheduling property — `timezone` — lives on the `groups`
  table (`V5__identity_audit.sql:159`, `NOT NULL DEFAULT 'UTC'`), and
  the scheduler already `SELECT … FROM groups`. Putting `digest_enabled`
  on `groups` next to `timezone` keeps both digest-scheduling knobs in
  one place, makes the scheduler change a single predicate (no join, no
  `coalesce` over a possibly-absent `scope_preferences` row), and the
  "generalizes to DM" argument for `scope_preferences` is moot because
  there is no DM periodic digest in v1. So `groups` is both simpler and
  more consistent.
- **Permission parity with `/group-timezone`.** Group admin OR bot admin
  (bot admins override group ops, per `/promote`/`/demote`). Settled
  with the requester 2026-06-08.
- **Privileged-tier closed-list amendment.** `/digest` is a group-admin
  command, so it MUST be added to the spec's load-bearing closed set
  (`commands.md` §Closed list) and to `LlmOutputSanitizer.CLOSED_LIST`;
  the spec section states this is a spec amendment, not a design-tier
  edit, and a CI completeness test pins code↔spec parity. The slow-start
  probation classifier was swept and holds NO independent code mirror of
  that list (it reads from the same source), so no extra file is needed
  there.
- **Migration version V44** assigned after sweeping main + all 10 active
  worktrees for the highest `V*.sql` (max was V43; in-flight worktrees
  are decision/no-migration tickets). Re-verify the MIG-lane queue at
  `start` time in case a parallel migration landed first.
- **Audit pair vs single action.** Chose `DIGEST_ENABLE`/`DIGEST_DISABLE`
  to mirror the `SOURCE_ENABLE`/`SOURCE_DISABLE` precedent. A single
  `DIGEST_TOGGLE` with on/off in the detail column is the considered
  alternative; the pair is more grep-friendly and matches the closest
  sibling.
- **No confirm step.** `/digest off` is reversible with `/digest on`, so
  unlike `/reject-group`/`/forget`/`/clear` it needs no confirm gate (no
  `_INTENT` audit action).
