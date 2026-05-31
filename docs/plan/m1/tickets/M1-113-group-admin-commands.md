---
id: M1-113
title: "D47 admin commands — approve-group, reject-group, list-groups"
status: done
created: 2026-05-27
last_updated: 2026-05-31
blocked_by:
  - M1-112
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RejectGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ListGroupsCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RejectGroupCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ListGroupsCommandHandlerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-messaging-adapter/** — no SPI changes (CommandHandler, ScopeRef, MessagingAdapter all consumed unchanged)
  - any migration file — M1-110 is frozen
  - GroupApprovalService internals — M1-112 is frozen
  - InboundRouter — M1-112 is frozen
  - /status changes — M1-114
  - GroupAutoPromoteService — not modified here; activated_by priority can be wired if clean, but is not required by acceptance
  - adapter-layer group support — M1-104, M1-108
  - infochat-core touches other than the AuditAction enum-content additions in files_scope (no migration, no AuditLogWriter/RedactionHook/ContactIds changes)
acceptance:
  - "ApproveGroupCommandHandler implements CommandHandler with name()=='approve-group'. Requires is_admin=true. Parses one positional <group_id> argument (UUID). Transitions approval_status from 'pending' or 'rejected' to 'approved'. Sends a one-time 'group approved' message to the group. Audit-logged. No-op with friendly reply if already approved. No confirm required"
  - "RejectGroupCommandHandler implements CommandHandler with name()=='reject-group'. Requires is_admin=true. Parses one positional <group_id> argument (UUID). Transitions approval_status to 'rejected'. Sends a one-time 'group rejected' message to the group. Audit-logged. Requires confirm (destructive). No-op with friendly reply if already rejected"
  - "ListGroupsCommandHandler implements CommandHandler with name()=='list-groups'. Requires is_admin=true. Lists all groups with approval_status, activated_by (redacted contact id), member count, and timezone. Supports --page N for pagination"
  - "All three commands work from both DM and group context (the admin need not be a member of the target group)"
  - "ApproveGroupCommandHandlerTest covers: (a) non-admin → error.admin_only; (b) unknown group_id → error; (c) pending → approved + group message + audit; (d) rejected → approved; (e) already approved → no-op reply. grep -E '@Test' ApproveGroupCommandHandlerTest.java returns ≥5 matches"
  - "RejectGroupCommandHandlerTest covers: (a) non-admin → error.admin_only; (b) pending → rejected + confirm required; (c) approved → rejected + confirm required + group message + audit; (d) already rejected → no-op reply. grep -E '@Test' RejectGroupCommandHandlerTest.java returns ≥4 matches"
  - "ListGroupsCommandHandlerTest covers: (a) non-admin → error.admin_only; (b) empty list → friendly message; (c) mixed approval states → all shown with correct labels; (d) pagination. grep -E '@Test' ListGroupsCommandHandlerTest.java returns ≥4 matches"
  - "Bundle keys added for: group.approved_message, group.rejected_message, reply.approve_group.success, reply.approve_group.noop, reply.reject_group.success, reply.reject_group.noop, reply.list_groups.empty, error.group_not_found — in both en and cs bundles"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RejectGroupCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ListGroupsCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/commands.md §Permission model
decision_refs:
  - D47
reviews:
  - round: 1
    date: 2026-05-31
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 2136
      removed: 1
  - round: 2
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 2165
      removed: 2
escalations:
  - date: 2026-05-31
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        - docs/spec/commands.md §Admin /approve-group → ANCHOR-NOT-FOUND. No heading contains "admin /approve-group". The content about /approve-group is a bullet point inside "### Admin (bot admin)" (line 813), not a separate heading.
        - docs/spec/commands.md §Admin /reject-group → ANCHOR-NOT-FOUND. Same: /reject-group is a bullet point under "### Admin (bot admin)", not a heading.
        - docs/spec/commands.md §Admin /list-groups → ANCHOR-NOT-FOUND. Same: /list-groups is a bullet point under "### Admin (bot admin)", not a heading.
        - docs/spec/commands.md §Permission model — closed list → FOUND (line 984).
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      About to touch infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
      to add APPROVE_GROUP + REJECT_GROUP enum constants required by acceptance
      items 1 and 2 ("Audit-logged"). The file is NOT in files_scope (which lists
      10 provider-only paths) and is covered by out_of_scope entry
      "infochat-core/** — no SPI changes". The AuditLogWriter SPI requires an
      AuditAction enum constant; no existing value is semantically appropriate
      (APPROVE/REJECT_QUARANTINE are for posts, GRANT/REVOKE_ADMIN are for users).
      Prior admin tickets (M1-046, M1-053, M1-079c, M1-080c, M1-083) extended
      AuditAction in the same enum-content pattern; the gloss "no SPI changes"
      arguably permits content-only enum additions, but files_scope is the
      binding mechanical constraint.
revisions:
  - date: 2026-05-31
    reason: clarity-fail rework — collapse three non-resolving spec_refs into the single resolving §Admin (bot admin) heading; bump risk low→medium per WARN
    prior_values: |
      risk: low
      spec_refs:
        - docs/spec/commands.md §Admin /approve-group
        - docs/spec/commands.md §Admin /reject-group
        - docs/spec/commands.md §Admin /list-groups
        - docs/spec/commands.md §Permission model — closed list
      (The three /<cmd> entries pointed at bullet points under the
       "### Admin (bot admin)" heading, not at distinct headings, so
       spec_refs anchor resolution failed. The actual heading
       containing all three commands is "Admin (bot admin)" at
       commands.md:813. risk was also bumped because security_relevant
       is true and the commands mutate approval_status; the handlers
       still delegate to M1-112's frozen service, so the bump is
       calibration, not new risk.)
  - date: 2026-05-31
    reason: budget-breach rework — add infochat-core AuditAction.java to files_scope (+1, budget 10→11); narrow over-broad infochat-core/** out_of_scope entry to permit enum-content additions
    prior_values: |
      files_budget: 10
      files_scope: (lacked infochat-core AuditAction.java)
      out_of_scope:
        - infochat-collector/** — no collector changes
        - infochat-core/** — no SPI changes
        - any migration file — M1-110 is frozen
        - GroupApprovalService internals — M1-112 is frozen
        - InboundRouter — M1-112 is frozen
        - /status changes — M1-114
        - GroupAutoPromoteService — not modified here; activated_by priority can be wired if clean, but is not required by acceptance
        - adapter-layer group support — M1-104, M1-108
      (Acceptance items 1 and 2 mandate "Audit-logged" for /approve-group
       and /reject-group. The AuditLogWriter SPI requires an AuditAction
       enum constant; no existing verb fits semantically — APPROVE_QUARANTINE
       and REJECT_QUARANTINE are post-level moderation, PROMOTE_GROUP_ADMIN
       and DEMOTE_GROUP_ADMIN are group membership admin role (orthogonal to
       groups.approval_status). The corpus pattern across M1-046, M1-053,
       M1-079c, M1-080c, M1-083 is to extend AuditAction as a content
       addition; the prior infochat-core/** ban was over-broad. Narrow the
       out_of_scope entry to the migration subtree and the AuditLogWriter/
       RedactionHook/ContactIds SPI surface; add AuditAction.java explicitly
       to files_scope. Also moved the SPI-no-touch reason to its proper home
       on the infochat-messaging-adapter/** entry, which IS the SPI module.)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-31
    category: AUDIT-EVASION
    severity: medium
    promise: |
      From docs/spec/security.md §Authorization model, evaluation order step 8:
      "Audit-log the intent." The spec lists audit-log as a deterministic step
      that runs after the permission check and before execution — applying to
      every inbound dispatched command, with privileged-tier reads explicitly
      covered by the corpus pattern (compare ListSourcesCommandHandler line
      142–151 which writes a privilegedReadAuditRow precisely because the
      privileged enumeration is audited because it discloses deployment-wide
      state).
    gap: |
      infochat-provider/src/main/java/app/zcat/infochat/provider/command/ListGroupsCommandHandler.java
      lines 71–125 (the entire handle(...) method) writes NO audit row. The
      class-level javadoc declares "Read-only handler: no audit row, no state
      mutation". The handler enumerates every groups row including each
      group's id, approval_status, timezone, activated_by contact id
      (redacted but identifiable by prefix/suffix), and active member count
      — global, deployment-wide state visible only to bot admins. The
      corpus precedent in ListSourcesCommandHandler (writePrivilegedReadAuditRow)
      audits an analogous privileged-tier admin enumeration; /list-groups
      is the same shape yet writes no row.
    repro: |
      A compromised bot admin issues /list-groups --page 1, /list-groups
      --page 2, ..., enumerating every group's (id, approval_status, redacted
      activator contact, member count, timezone) across the deployment. No
      audit trail is written. A forensic investigator reviewing audit_log
      later cannot establish which admin enumerated the group inventory,
      when, or how often. The redacted activator contact id is reversible by
      anyone who already knows the contact id prefix/suffix (the redaction
      is identity-preserving for known contacts), so the disclosure scope is
      non-trivial — yet the operation is invisible in /audit.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-31
    verdict: FINDINGS
    base: 14d676f
    head: e19b99b
    verdict_file: docs/plan/m1/redteam/M1-113-2026-05-31.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Audit ran between /m1-tick commit and /m1-tick merge (canonical squash
      commit e19b99b on branch m1/M1-113-group-admin-commands). One MEDIUM
      AUDIT-EVASION finding RESOLVED on this branch (2026-05-31) via fix
      commit on top of e19b99b (M1-112 precedent). Added AuditAction.LIST_GROUPS
      constant and wired ListGroupsCommandHandler.writePrivilegedReadAuditRow
      mirroring ListSourcesCommandHandler line 190–230: audit-before-effect
      in own short transaction after admin gate, before deployment-wide
      SELECT. Audit row carries target_kind='group', target_id='all',
      details_json={"page":N}. Three new tests in ListGroupsCommandHandlerTest
      (4 → 7); mvn -B verify SUCCESS (97 provider tests, 0 failures). Two
      OUT-OF-MODEL observations recorded but not fixed (advisory only):
      (a) escapeJson string-concatenation JSON builder is sound for the
      current closed details_json field set, (b) RejectGroup intent-phase
      TOCTOU between outside-tx admin check and audit-row write
      (audit-row pollution only — execution is gated by the confirm-leg
      in-tx FOR UPDATE re-check).
clarity_check:
  date: 2026-05-31
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-113: D47 admin commands — approve-group, reject-group, list-groups

## Context

Three new bot-admin commands for the D47 group authorization gate.
These are the admin's interface for managing group approvals.

`security_relevant: true` — the commands mutate approval_status,
which gates all group interaction. They are in the closed
privileged-tier list (commands.md §Permission model).

## Acceptance

See frontmatter.

## Out-of-scope

- GroupApprovalService internals — M1-112 is frozen.
- InboundRouter — M1-112 is frozen.
- `/status` pending-groups count — M1-114.

## Notes

- **Confirm pattern.** `/reject-group` requires confirm — reuse the
  ConfirmStateService pattern from M1-051. `/approve-group` does not
  require confirm (constructive action, matching the `/grant-admin`
  pattern).
- **Group message delivery.** The "group approved" / "group rejected"
  one-time messages are sent to the group via the adapter. The admin
  need not be a member of the target group — the Provider sends
  the message using the group's adapter and upstream_group_id.
- **LLM output sanitizer.** `LlmOutputSanitizer.CLOSED_LIST` was
  synced with `commands.md` to include `/approve-group`,
  `/reject-group`, `/list-groups` by M1-115 (separate ticket,
  merged 2026-05-28) — the original D47 spec commit `8b22ee1`
  added the tokens to the spec only and touched no Java/SQL.
  M1-113 itself does NOT modify `LlmOutputSanitizer`: the
  sanitizer parses `commands.md` at test tier, so the closed-list
  vs. spec parity check is independent of whether the command
  handlers are registered.

## Round 1 rework

Reviewer verdict 2026-05-31, round 1: REWORK. All 5 structural
checks PASS; rework items are mechanical unused-symbol cleanups in
`RejectGroupCommandHandlerTest.java`. Per engineering-rules §1
("Clean up imports/variables that YOUR changes made unused").

1. Drop `import java.sql.Types;` (line 19) — never referenced
   (`seedGroup` uses `ps.setObject` only).
2. Drop the unused `UUID actorId =` LHS on
   `seedUser(...)` in `rejectPendingGroupRequiresConfirmThenFlipsStatus`
   (line 116) — the test never asserts the actor on the audit rows
   (only the count). Test (c) already covers actor-on-audit-row
   asymmetrically; no behaviour gap.
3. Drop the unused `String expectedPrompt = MessageFormat.format(...)`
   computation (line 124) — the assertions use `prompt.text()`
   substring checks, not equality against `expectedPrompt`. Keep the
   substring contract (the timeout token may differ across profiles).
