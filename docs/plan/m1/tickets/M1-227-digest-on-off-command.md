---
id: M1-227
title: "/digest on|off — group-admin toggle to pause/resume the periodic digest"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 16
files_scope:
  - infochat-core/src/main/resources/db/migration/V44__group_digest_enabled.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/DigestCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/schema.md
complexity: high
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - "Spurious missed-slot record when a pause spans a slot window — split to M1-228. A group paused across its slot and re-enabled after window-end records ONE false 'missed slot' notification (DigestScheduler.recordMissedSlot, throttle-keyed once per slot). This ticket leaves that blemish; M1-228 adds the symmetric pause carve-out to processSlot. Not a security/correctness blocker (the gate already prevents the digest from firing) — a monitoring false-positive only."
  - "Per-group digest TIMES / intervals — the morning/evening slot hours stay deployment-wide operator properties (deployment.md §Configuration surface — Groups); this ticket adds an on/off delivery gate only, no per-group scheduling state."
  - "DM-scope digests — there is no periodic DM digest in v1, so /digest from a DM scope is a friendly error, never a per-user toggle. No DM periodic-digest path is introduced."
  - "On-demand /summary — unaffected. A paused group can still run /summary (on-the-fly, D18); the gate suppresses only the scheduled push, not the on-the-fly path."
  - "Cluster traversal, translation cache, degraded-fallback path — untouched; a paused group is simply not selected by the scheduler, so no digest is computed, cached, or sent."
  - "A decisions.md D-number for the pause behavior — NOT included (kept to commands.md + schema.md spec edits). If the reviewer wants a decision-log trace, it is a separate one-line spec: follow-up."
acceptance:
  - "Migration V44 adds `digest_enabled BOOLEAN NOT NULL DEFAULT true` to the `groups` table (alongside `timezone` from V5). Adding a column with a constant default is metadata-only on PG11+ (no table rewrite); every existing and future group defaults to digests-on with no backfill."
  - "A new `DigestCommandHandler implements CommandHandler` with `name()` returning `\"digest\"`, modeled on `GroupTimezoneCommandHandler` (resolves the group by `adapter + upstream_group_id`, reads `is_group_admin` from `group_membership`): it is auto-discovered by `InboundRouter` (no router edit). The `on`/`off` sub-verb is matched case-insensitively. `/digest on` sets `groups.digest_enabled = true`; `/digest off` sets it to `false`, for the inbound group."
  - "Permission: `/digest on|off` is group-admin OR bot-admin only (same predicate as `/group-timezone`). A non-admin group member gets a friendly not-admin error (bundle key) and no state change."
  - "Scope + parse: `/digest` from a DM scope returns a friendly group-only error (bundle key). `/digest` with a missing or unrecognized sub-verb (anything other than case-insensitive `on`/`off`) returns a friendly usage error naming the two sub-verbs; never a silent no-op, never a fall-through."
  - "Idempotency: a `/digest` call that requests the state the group is already in is a friendly no-op — it replies with an 'already on'/'already off' bundle string, performs NO UPDATE, and writes NO audit row. Only a call that actually flips `digest_enabled` mutates and audits (so repeated toggles do not spam the audit log)."
  - "`AuditAction` gains `DIGEST_ENABLE` and `DIGEST_DISABLE` (mirroring `SOURCE_ENABLE`/`SOURCE_DISABLE`). On an actual state change the handler writes the matching action via `AuditLogWriter` with `target_kind = 'group'` and `target_id` = the group's id, in the SAME transaction as the `digest_enabled` UPDATE, before the effect — so a committed audit row can never outlive a rolled-back mutation. (M1-228 reads these `DIGEST_ENABLE` rows; the `target_kind`/`target_id` convention is load-bearing for it.)"
  - "`DigestScheduler.queryActiveGroups()` SELECT gains `AND digest_enabled`, selecting groups where `removed_at IS NULL AND approval_status = 'approved' AND digest_enabled`. A paused group is never returned, so `processSlot` never runs for it: no fire, no cache write, no missed-slot evaluation while paused."
  - "A named `DigestSchedulerTest` case (DB-backed via `@SeedDataSource`) asserts a group with `digest_enabled = false` (otherwise approved, non-removed) is excluded from `queryActiveGroups()` / not scheduled, while an otherwise-identical `digest_enabled = true` group is included."
  - "Resume-within-window firing is documented and asserted: re-enabling (`/digest on`) while `now` is still inside an active slot window and past the group's stagger fire time fires that slot's digest (the group is eligible again before window-end, hitting the existing emit branch); re-enabling after window-end does NOT fire (no catch-up — the existing past-window branch is taken). The post-window false missed-slot record is M1-228's scope, not this test's."
  - "`RetryCommandHandler` rejects the digest-retry path when the inbound group's `digest_enabled = false`: after the existing group-only and group-admin checks and BEFORE delegating to `DigestRetryService.retryDigest(...)`, it reads `digest_enabled` for the group and, when false, returns a friendly 'digest paused' bundle error. This closes the bypass where a paused group's stale cached digest could be regenerated and re-sent via `/retry --digest`."
  - "`/digest` is added to the spec's privileged-tier closed set under the Group-admin bullet (`commands.md` §Permission model, after `/group-timezone`) AND to `LlmOutputSanitizer.CLOSED_LIST` (after `/group-timezone`, line 118). The existing sanitizer closed-list completeness test stays green with the new token present."
  - "`HelpCommandHandler.CATALOGUE` gains a `/digest` entry at the group-admin help tier (same tier as `/group-timezone`) with a new `BundleKeys.HELP_CMD_DIGEST_SHORT`. All new bundle keys — help short line, on/off confirmations, already-on/already-off no-op lines, DM-scope error, not-admin error, usage error, and the retry-paused error — are present in BOTH `en.properties` and `cs.properties` so the build-time bundle-completeness check passes."
  - "`docs/spec/commands.md` §Conversation control documents `/digest on|off` (group only; group admin or bot admin; case-insensitive; idempotent no-op when unchanged; audit-logged; pauses/resumes the periodic digest; on-demand `/summary` unaffected) AND notes that `/retry --digest` is rejected while a group's digest is paused. `docs/spec/schema.md` §Identity and access records the `groups.digest_enabled` column (default true; gates the digest scheduler's group selection; persists across bot remove/re-add like the other group state per §Group)."
  - "mvn -B clean verify from the repo root exits 0; all tests currently green on main stay green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
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
leaving — both kill ALL interaction (D47), far too blunt for "I just
don't want the auto-digest."

This ticket adds a narrow delivery gate: `/digest off` pauses the
scheduled digest for the calling group; `/digest on` resumes it. Data
collection is unaffected (the Collector ingests regardless — the digest
is a pure Provider-side read+send), and on-demand `/summary` keeps
working for a paused group. The gate is a single boolean the scheduler
ANDs into its existing group-selection query — plus the two interaction
fixes a self-review surfaced (see Notes).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Column home — `groups.digest_enabled`, NOT `scope_preferences`.** An
  earlier sketch put the flag on `scope_preferences` (where `tag_mode`
  lives). Reconsidered: the existing per-group digest-scheduling property
  — `timezone` — lives on `groups` (`V5__identity_audit.sql:159`,
  `NOT NULL DEFAULT 'UTC'`), and the scheduler already
  `SELECT … FROM groups`. Putting `digest_enabled` on `groups` next to
  `timezone` keeps both digest-scheduling knobs together, makes the
  scheduler change a single predicate (no join, no `coalesce` over a
  possibly-absent `scope_preferences` row), and the "generalizes to DM"
  argument is moot (no DM periodic digest in v1). Simpler AND more
  consistent.
- **Two self-review fixes folded in (the reason this is `high`, not the
  original `medium`):**
  - *F1 — close the `/retry --digest` bypass.* `/retry --digest` runs
    through `DigestRetryService`, NOT the scheduler, so the
    `digest_enabled` gate does not cover it. Without the
    `RetryCommandHandler` check, a group admin could regenerate and
    re-send a paused group's stale cached digest. Gated at the handler
    (where the sibling DM/admin friendly errors already live) rather
    than in the service.
  - *F3 — resume firing semantics pinned.* Re-enabling inside an active
    window fires that slot (eligible again before window-end); after
    window-end it does not catch up. Documented + tested so the
    behavior is a decision, not an accident.
- **F2 (false missed-slot) is split to M1-228** — it needs a temporal
  carve-out in `processSlot` mirroring the existing approval carve-out,
  derived from the `DIGEST_ENABLE` audit rows this ticket writes (no new
  column). Keeping it out holds this ticket to a single concern.
- **Permission parity with `/group-timezone`** (group admin OR bot
  admin) settled with the requester 2026-06-08.
- **Privileged-tier closed-list amendment.** `/digest` is a group-admin
  command, so it MUST join the spec's load-bearing closed set
  (`commands.md` §Permission model) and `LlmOutputSanitizer.CLOSED_LIST`;
  the spec states this is a spec amendment and a CI completeness test
  pins code↔spec parity. The slow-start probation classifier holds NO
  independent code mirror (it reads the same source), so no extra file.
  `security.md` derives its match set from the commands.md list
  (`security.md:286`, "etc." enumeration) — no security.md edit needed.
- **Migration V44** assigned after sweeping main + all 10 active
  worktrees (max was V43; in-flight ones are decision/no-migration
  tickets). Re-verify the MIG-lane queue at `start` in case a parallel
  migration lands first.
- **Audit pair vs single action.** `DIGEST_ENABLE`/`DIGEST_DISABLE`
  mirrors `SOURCE_ENABLE`/`SOURCE_DISABLE`; the pair is grep-friendly
  and lets M1-228 query `DIGEST_ENABLE` directly. No confirm step / no
  `_INTENT` action — `/digest off` is reversible with `/digest on`.
