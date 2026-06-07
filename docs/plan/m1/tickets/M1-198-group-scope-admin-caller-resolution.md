---
id: M1-198
title: "Group-scope bot-admin commands: resolve caller via InboundContext"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 20
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java
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
  - the intent-row and no-op-audit legs in Unban/Vouch — M1-195's; coordinate scope, don't duplicate
  - the SET LOCAL actor_id concat in these same files — M1-206's
  - any change to WHO may run these commands — the bot-admin-only permission set is untouched; only WHERE the caller is recognized changes
  - Promote/Demote/ApproveGroup/RejectGroup — already resolve the caller via InboundContext.senderContactId(); not touched
  - confirm-flow state keying (ConfirmStateService is per-(actor, scope) and works for group scope already)
acceptance:
  - "Per docs/spec/commands.md §Permission model — the closed bot-admin set (\"/grant-admin, /revoke-admin, /ban, /unban, /promote, /demote, /vouch, /invite create, /invite list, /invite revoke, /quarantine list, /quarantine approve, /quarantine reject, /audit, /remove-source, /source-enable, /source-disable, /list-sources --all, /list-sources --include-deleted, /approve-group, /reject-group, /list-groups\") remains bot-admin only — a non-admin group member invoking any migrated command is still refused: named refusal tests per migrated handler"
  - "A registered bot admin invoking each of the nine affected commands from an approved group scope is recognized as the caller and proceeds past caller resolution instead of receiving error.admin_only: named test per handler (today contactIdOf(scope) returns null for group scope in InviteCommandHandler, AuditCommandHandler, QuarantineCommandHandler, RevokeAdminCommandHandler, GrantAdminCommandHandler, BanCommandHandler, UnbanCommandHandler, VouchCommandHandler, ListSourcesCommandHandler — the M1-044c DM-only convention — which makes lookupUser fail and surfaces a false admin_only error to a real admin; this matches the design matrix's \"Bot admin (anywhere)\" column, which ApproveGroup/Promote/Demote already implement via InboundContext.senderContactId())"
  - "Per docs/spec/security.md §Authorization model — authorization stays in deterministic Java code and the per-step order (ban check, permission check, audit intent, execute) is unchanged for both DM and group scope — existing ordering tests stay green"
  - "Contact ids appearing in group-scope replies of the migrated commands follow the same redaction posture the handler already applies in DM scope — a named test compares the DM and group reply shapes for one list-shaped command"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main except those pinning error.admin_only for a bot admin in group scope (authorized to change — they pin the defect)
spec_refs:
  - docs/spec/commands.md §Permission model
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-198: Group-scope bot-admin commands: resolve caller via InboundContext

## Context

Unified finding P11 (`deep-code-review/v2/UNIFIED.md` §2) reported the
trap for "/invite (+ban/unban)"; the draft-time call-site sweep found it
is wider: **nine** handlers carry a private
`contactIdOf(scope)` that returns null for group scope (the "M1-044c
DM-only convention", documented in VouchCommandHandler:128-132 and
GrantAdminCommandHandler:196-199), so a real bot admin invoking them in
a group gets the misleading `error.admin_only` reply. The design matrix
(docs/design/03-commands.md §Permission matrix, rows at :263-266 etc.)
marks these commands ✅ under "Bot admin (anywhere)", and three handlers
(ApproveGroup, Promote, Demote) already implement that via
`InboundContext.senderContactId()` — the convention is half-abandoned.

Direction: implement the matrix (the corpus pattern). If review
surfaces a reason to keep specific list-shaped commands DM-only (e.g.
group-visible disclosure of invite codes), that is a design-matrix
amendment — go through the escalate path rather than silently narrowing.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T21 under `deep-code-review/v2/` (opus-47
  prov F4); split out of T21 at draft time because the sweep grew it to
  nine files.
- ApproveGroupCommandHandler's javadoc (:41) documents the
  InboundContext pattern to follow ("Works in BOTH DM and group").
- Shares files with M1-195 (Unban, Vouch) and M1-206 (all SET LOCAL
  handlers) — serialize against both; do not run concurrently.
- docs/design/03-commands.md is in files_scope only for the
  matrix-footnote update if the implementation reveals a needed
  clarification — not for weakening the ✅ cells.
