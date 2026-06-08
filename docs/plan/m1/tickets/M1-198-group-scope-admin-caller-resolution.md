---
id: M1-198
title: "Group-scope bot-admin commands: return accurate command_dm_only, keep DM-only"
status: done
created: 2026-06-07
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: test_plan.modifies points at the command test dir but there is no formal 'authorized test changes' section; the preserves note implies additions-only (new group-scope tests added to existing handler test classes, no existing assertion changed or removed). Advisory only — an explicit 'additions only' note would remove reviewer ambiguity."
  blockers: []
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ListSourcesCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the four already-correct handlers (AuditCommandHandler, QuarantineCommandHandler, RevokeAdminCommandHandler, GrantAdminCommandHandler) — they ALREADY return error.command_dm_only in group scope via an early `if (scope instanceof ScopeRef.Group)` guard; not modified, not in files_scope
  - Promote/Demote/ApproveGroup/RejectGroup — group-contextual commands that correctly operate in group scope; untouched
  - any change to WHO may run these commands — the bot-admin-only tier (spec §Permission model closed set) is untouched; only the group-scope REPLY changes from the misleading error.admin_only to the accurate error.command_dm_only
  - the senderContactId()/InboundContext caller-resolution approach (the pre-refine thesis) — superseded; Option B blocks group scope BEFORE caller resolution, so no caller-resolution swap is made and InboundContext usage is unchanged
  - routing admin-command replies privately to the admin's DM (the "reply-to-DM" alternative) — a larger cross-scope-reply architectural change, not in scope here
  - the base /list-sources (no admin flag) group behavior — stays functional for every group member (matrix ✅); only the --all / --include-deleted flag path in group scope is guarded
  - the intent-row and no-op-audit legs in Unban/Vouch (M1-195, done) and the SET LOCAL actor_id concat (M1-206, done) — sibling work already merged; do not duplicate
  - confirm-flow state keying (ConfirmStateService is per-(actor, scope) and is unaffected — the group guard short-circuits before any confirm fork)
acceptance:
  - "Per docs/spec/commands.md §Permission model — the closed bot-admin set remains bot-admin only and the per-tier permission outcomes are unchanged in DM scope: a non-admin invoking /invite, /ban, /unban, /vouch in DM still receives error.admin_only, and a non-admin passing /list-sources --all or --include-deleted in DM still receives error.list_sources.admin_only_flag — existing DM refusal tests stay green (named, per handler)"
  - "A bot admin (and any caller) invoking each of the five previously-misreporting commands in an approved GROUP scope now receives error.command_dm_only instead of the misleading error.admin_only (or, for ListSources, instead of error.list_sources.admin_only_flag): named test per handler — InviteCommandHandler (any subcommand), BanCommandHandler, UnbanCommandHandler, VouchCommandHandler in group → error.command_dm_only; ListSourcesCommandHandler with --all OR --include-deleted in group → error.command_dm_only. This matches the accurate message AuditCommandHandler/QuarantineCommandHandler/RevokeAdminCommandHandler/GrantAdminCommandHandler already return in group scope"
  - "Base /list-sources (no admin flag) continues to return the scope's subscriptions for every member in group scope — the existing test ListSourcesCommandHandlerTest.listSourcesGroupReturnsGroupSubscriptionsForEveryMember stays green"
  - "DM-scope behavior of all five handlers is otherwise unchanged: a bot admin in DM still proceeds to execute, the audit-before-effect order (ban check, permission check, audit intent, execute) is preserved, and existing DM happy-path / ordering tests stay green"
  - "Per docs/spec/security.md §Authorization model — authorization stays in deterministic Java; the group-scope DM-only guard is added at the TOP of handle() (fail fast: no DB read, no audit row, no LLM) BEFORE the permission/audit/execute steps, mirroring the existing guard in the four already-guarded handlers"
  - "docs/design/03-commands.md Permission matrix: the 'Bot admin (anywhere)' cells for these nine bot-global admin commands are corrected to reflect DM-only scope (e.g. the qualified-cell form '✅ DM only', consistent with the matrix's existing '✅ self' / '✅ for group' notation), with a short footnote: bot-global admin commands are DM-only because a group-scope reply is visible to ALL group members and these commands' replies disclose secrets or enumerations not appropriate for that audience (verbatim invite codes in /invite create & /invite list, the audit trail in /audit, deployment-wide source URLs in /list-sources --all, cross-group admin roles in /unban). The spec-level closed privileged-set (the bot-admin TIER) is NOT changed — this is a scope correction, not a tier change"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main — no existing test pins the OLD group-scope error.admin_only for the five handlers (the draft-time sweep found their admin_only assertions are all DM-scope); in particular ListSourcesCommandHandlerTest.listSourcesGroupReturnsGroupSubscriptionsForEveryMember and all DM-scope admin/non-admin tests for the five handlers must stay green
spec_refs:
  - docs/spec/commands.md §Permission model
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 146
      removed: 27
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: f167e39
    head: 5a16d7e
    verdict_file: docs/plan/m1/redteam/M1-198-2026-06-08.md
    out_of_model_count: 0
    note: |
      Adversarial audit of the implementation diff (f167e39..5a16d7e, the
      branch commit pre-merge). CLEAN — the change tightens the trust
      boundary by adding ScopeRef.Group guards that short-circuit five
      bot-admin handlers before any caller resolution, transaction, or
      group-visible disclosing reply. Threat-actor confirmed all 13
      DM-only handlers (5 changed + 4 already-guarded + the rest) carry the
      guard, the Ban guard precedes the confirm-fork, and the ListSources
      base group path still returns only group-scoped subscriptions. No
      remediation ticket; safe to merge.

revisions:
  - date: 2026-06-08
    reason: premise-fail refine — pivot from "make all nine group-runnable via InboundContext.senderContactId()" to "all nine bot-global admin commands are DM-only; fix the misleading error message". Grounding showed implementing the matrix literally leaks single-use invite codes (/invite create, /invite list), the audit trail (/audit), source URLs (/list-sources --all) and cross-group roles (/unban) into all-member-visible group replies; the four guarded handlers already emit the correct command_dm_only, so the real defect is only the misleading admin_only from the five unguarded ones. User chose end-state B (uniform DM-only).
    snapshot:
      title: "Group-scope bot-admin commands: resolve caller via InboundContext"
      files_budget: 20
      files_scope_count: 11
      thesis: "swap contactIdOf(scope) -> InboundContext.senderContactId() across nine handlers + remove the four DM-only guards so bot admins are recognized in group scope (matrix 'Bot admin anywhere')"
escalations:
  - date: 2026-06-08
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (escalated during implementation grounding, before any review).
      Two premise defects surfaced:
      (1) The acceptance states all nine handlers surface a false
      error.admin_only in group scope via contactIdOf(scope)==null. True
      for 5 handlers (Invite, Ban, Unban, Vouch, ListSources). FALSE for
      4 (Audit, Quarantine, RevokeAdmin, GrantAdmin): they have an earlier
      `if (scope instanceof ScopeRef.Group) return error.command_dm_only;`
      DM-only guard and never reach contactIdOf. Implementing the matrix
      for these four means REMOVING a deliberate DM-only guard, not the
      caller-resolution swap the ticket describes.
      (2) The design matrix marks all nine ✅ "Bot admin (anywhere)", but a
      reply to group scope is visible to ALL group members (reply(scope,…)
      sends to the inbound scope; no private-reply-to-admin path exists).
      /invite create and /invite list print single-use invite CODES
      verbatim (InviteCommandHandler renderListEntry: "Full code, not a
      prefix"; reply.invite.created/list_entry interpolate the raw code),
      and /audit dumps the audit trail — so implementing the matrix as
      written leaks registration secrets and the audit log into group
      chats. This is the exact "group-visible disclosure of invite codes"
      case the ticket Context names as an escalate trigger. Contact ids in
      replies are already ContactIds.redact-ed, so the contact-id leak is
      contained; invite codes and the audit trail are not.
---

# M1-198: Group-scope bot-admin commands: return accurate command_dm_only, keep DM-only

## Context

Unified finding P11 (`deep-code-review/v2/UNIFIED.md` §2) reported a
trap for "/invite (+ban/unban)": a real bot admin invoking these
commands from an approved group gets the misleading `error.admin_only`
reply. The draft-time sweep widened it to nine handlers carrying a
private `contactIdOf(scope)` that returns null for group scope (the
"M1-044c DM-only convention").

**Pre-refine thesis (superseded).** The original ticket read the design
matrix's ✅ "Bot admin (anywhere)" column literally and proposed to make
all nine commands run in group scope by swapping
`contactIdOf(scope)` → `InboundContext.senderContactId()` (the
ApproveGroup/Promote/Demote corpus pattern) and removing the four
DM-only guards.

**Why that was wrong (premise-fail escalation, 2026-06-08).** Grounding
against the code surfaced two defects in that thesis:

1. **The mechanism is not uniform.** Only five handlers (Invite, Ban,
   Unban, Vouch, ListSources) reach `contactIdOf` and emit the false
   `error.admin_only`. The other four (Audit, Quarantine, RevokeAdmin,
   GrantAdmin) have an *earlier* `if (scope instanceof ScopeRef.Group)
   return error.command_dm_only;` guard — they already emit the
   **accurate** message and never reach `contactIdOf`.
2. **A group-scope reply is visible to all group members** (`reply(scope, …)`
   targets the inbound scope; there is no private-reply-to-admin path).
   These nine are *bot-global* operations whose replies disclose
   material no group audience should see:
   - `/invite create`, `/invite list` print single-use invite **codes**
     verbatim (`renderListEntry`: "Full code, not a prefix") — a hard
     registration-secret / auth-bypass leak.
   - `/audit` dumps the audit trail; `/quarantine list` the queue;
     `/list-sources --all` deployment-wide source URLs; `/unban`'s
     `group_admins_restored` reply lists the user's group-admin roles in
     *other* groups (cross-group disclosure).

   Contrast `/approve-group`, `/promote`, `/demote`, which legitimately
   work in group scope because they are group-*contextual*.

**Direction (refined, end-state B).** These nine bot-global admin
commands are **DM-only**. The fix is narrow: make the five misreporting
handlers return the accurate `error.command_dm_only` in group scope
(matching the four that already do), and correct the design matrix's
scope cells. This is a **scope correction in the design tier** — the
spec's bot-admin-only *tier* (the closed privileged-set the LLM-output
sanitizer and probation classifier read) is unchanged, so no spec
amendment is required.

## Implementation sketch (non-binding; the developer owns the diff)

- **Invite, Ban, Unban, Vouch:** add `if (scope instanceof ScopeRef.Group)
  return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY));`
  at the top of `handle()`, before any lookup/transaction — mirroring the
  guard in Audit/Quarantine/RevokeAdmin/GrantAdmin. The DM path is
  unchanged (`contactIdOf` still resolves the DM contact id).
- **ListSources:** the base command stays group-functional; guard only the
  admin-flag path — when `(args.all || args.includeDeleted)` AND scope is
  `ScopeRef.Group`, return `error.command_dm_only`.
- Clean up surgically: where the new guard makes a `callerContactId == null`
  short-circuit and its "M1-044c DM-only convention" comment unreachable
  (e.g. VouchCommandHandler), remove the dead branch/comment. Do not touch
  unrelated code.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T21 under `deep-code-review/v2/` (opus-47 prov
  F4); split out of T21 at draft time.
- The four already-correct handlers (Audit, Quarantine, RevokeAdmin,
  GrantAdmin) are deliberately NOT in `files_scope` — they need no change.
- Draft-time test sweep: no existing test pins the old group-scope
  `error.admin_only` for the five handlers; their admin_only assertions
  are DM-scope. `ListSourcesCommandHandlerTest.listSourcesGroupReturnsGroupSubscriptionsForEveryMember`
  must stay green (base `/list-sources` in group).
- The "reply privately to the admin's DM" alternative (which would let
  these run in group per the matrix's original intent without leaking) is
  a larger cross-scope-reply change; if desired it is a separate ticket.
