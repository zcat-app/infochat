---
id: M1-316
title: "Permanent-failure-driven group/membership cleanup writes BOT_REMOVED / MEMBER_LEFT audit rows"
status: pending
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The native membership-event path (MembershipEventHandler) — it already writes BOT_REMOVED / MEMBER_LEFT correctly; this ticket only closes the gap on the permanent-failure-driven fallback path that the flagship SimpleX adapter relies on.
  - The retry / cap-escalation / threshold logic itself (M1-284, done) — unchanged; this ticket only adds the audit write to the cleanup effect.
  - New AuditAction enum values — BOT_REMOVED and MEMBER_LEFT already exist (AuditAction.java); no enum or migration change.
acceptance:
  - "When repeated permanent group-send failures cross the threshold and OutboundDelivery soft-removes the group (groups.removed_at = NOW()), a BOT_REMOVED audit row (system actor: actor_user_id NULL, scope = the group) is written in the SAME transaction as the removed_at mutation, audit-before-effect per Invariant 7 (the MembershipEventHandler.writeAudit / BanCommandHandler pattern). A named test crossing the threshold asserts the BOT_REMOVED row exists; a named test asserts an audit-write failure rolls the removed_at mutation back (no orphan removal without an audit row)."
  - "When a permanent member-attributed failure soft-clears the group_membership row (removed_at = NOW()), a MEMBER_LEFT audit row (actor = the departing member's user_id + contact_id, scope = the group) is written in the same transaction as the soft-clear. A named test covers it, including the group-admin case (is_group_admin cleared + MEMBER_LEFT row in one tx)."
  - "The audit rows match the shape MembershipEventHandler already writes for the native-event path (same AuditAction, same actor/scope columns), so /audit and audit_log_view render system-initiated and native-event removals identically. Verified by comparing the written columns against MembershipEventHandler's existing rows."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions: []
---

# M1-316: Permanent-failure-driven group/membership cleanup writes BOT_REMOVED / MEMBER_LEFT audit rows

## Context

M1-284 (done, commit 2e9ca987) added the permanent-failure-driven cleanup
that soft-removes a group (`groups.removed_at`) after repeated permanent
group-send failures, and soft-clears a `group_membership` row after a
permanent member-attributed failure. This is the **only** membership-cleanup
path for the flagship SimpleX adapter (`supportsMembershipEvents=false`).

The design defines these as auditable system-actor events
(`docs/design/02-schema.md`): `BOT_REMOVED` ("Bot removed from group
(system actor)", scope `group`) and `MEMBER_LEFT` (membership-left, scope
`user`). The **native** membership-event path already writes them —
`MembershipEventHandler.java:149` calls
`writeAudit(conn, AuditAction.BOT_REMOVED, …)` and `:111`
`writeAudit(conn, AuditAction.MEMBER_LEFT, …)`, audit-before-mutation in one
transaction (Invariant 7), with tests asserting the rows exist.

But M1-284's permanent-failure path (`OutboundDelivery` →
`GroupRepository.markRemoved(groupId)` and the membership soft-clear) sets
`removed_at` **without** writing the corresponding audit row. So on the
flagship adapter, bot-removals and member-leaves are **unaudited** —
inconsistent with both the design and the native-event path. This was an
M1-284 acceptance gap (items 5/6 specified `removed_at` + scheduler-cancel
but never the audit row), surfaced as an out-of-model note in the M1-284
redteam audit (`docs/plan/m1/redteam/M1-284-2026-06-12.md`) and verified
real against source 2026-06-12.

## Approach (implementer/plan decides the seam)

`AuditLogWriter` (infochat-core) is the existing audit-write API. The
constraint is audit-before-effect in the SAME transaction (Invariant 7): an
audit-write failure must roll the `removed_at` mutation back. Today
`GroupRepository.markRemoved` opens its own connection, so the audit write
must join that transaction — either by folding the audit INSERT into the
repository method(s) or by introducing a coordinating transactional method
that does both. Mirror the actor/scope columns `MembershipEventHandler`
already writes so the two paths are indistinguishable downstream.

## Out-of-scope

See frontmatter — native-event path (already correct), M1-284's retry/
threshold logic (unchanged), and any AuditAction/migration change (the enum
values already exist).
