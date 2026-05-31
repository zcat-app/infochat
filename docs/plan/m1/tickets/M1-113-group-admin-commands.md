---
id: M1-113
title: "D47 admin commands — approve-group, reject-group, list-groups"
status: pending
created: 2026-05-27
last_updated: 2026-05-31
blocked_by:
  - M1-112
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RejectGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ListGroupsCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
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
  - infochat-core/** — no SPI changes
  - any migration file — M1-110 is frozen
  - GroupApprovalService internals — M1-112 is frozen
  - InboundRouter — M1-112 is frozen
  - /status changes — M1-114
  - GroupAutoPromoteService — not modified here; activated_by priority can be wired if clean, but is not required by acceptance
  - adapter-layer group support — M1-104, M1-108
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
  - docs/spec/commands.md §Permission model — closed list
decision_refs:
  - D47
reviews: {}
escalations:
  - date: 2026-05-31
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        - docs/spec/commands.md §Admin /approve-group → ANCHOR-NOT-FOUND. No heading contains "admin /approve-group". The content about /approve-group is a bullet point inside "### Admin (bot admin)" (line 813), not a separate heading.
        - docs/spec/commands.md §Admin /reject-group → ANCHOR-NOT-FOUND. Same: /reject-group is a bullet point under "### Admin (bot admin)", not a heading.
        - docs/spec/commands.md §Admin /list-groups → ANCHOR-NOT-FOUND. Same: /list-groups is a bullet point under "### Admin (bot admin)", not a heading.
        - docs/spec/commands.md §Permission model — closed list → FOUND (line 984).
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
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
