---
id: M1-170
title: "Membership-event hardening (M1-143 redteam findings 2-4)"
status: pending
created: 2026-06-05
last_updated: 2026-06-05
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
remediates: M1-143
out_of_scope:
  - the M1-143 spanning-transaction ordering itself (audit-before-effect and the sanitized failure propagation stay exactly as delivered in M1-143)
  - a full outbox/redelivery subsystem for adapter membership events (resilience here means per-event isolation plus an operator signal, not guaranteed redelivery)
  - the AuditLogWriter consolidation (deferred under M1-041)
  - other admin command handlers
acceptance:
  - "The MEMBER_LEFT spanning transaction reads the admin flag with a row lock (SELECT ... FOR UPDATE through GroupMembershipRepository.isGroupAdmin(Connection, ...) or a dedicated locking read) so a concurrent /promote or /demote cannot invalidate the audited was_group_admin value between the read and markMemberRemoved (M1-143 redteam finding 2)"
  - "A test proves the serialization: a promote committed concurrently with an in-flight MEMBER_LEFT transaction is either reflected in the MEMBER_LEFT audit row's was_group_admin value or blocked until the leave transaction commits — the audited value and the actual pre-removal admin state cannot diverge"
  - "One failing MembershipEventHandler.handle() invocation does not abort the adapter's dispatch of the remaining membership events in the same group update (per-event isolation in SignalGroupHandler.dispatchMembership and the AdapterRegistry-wired dispatch path); a test asserts that when the first of two memberLeft entries fails its transaction, the second member's removal still lands (M1-143 redteam finding 3)"
  - "A failed membership-event transaction is logged via SafeLog with group and user UUIDs only (no contact id, no exception message body) so a stranded removal — a phantom admin occupying the one-admin partial-unique-index slot — is operator-discoverable"
  - "MEMBER_LEFT audit-row minting is bounded under scripted leave/rejoin cycles: a test demonstrates that repeated leave events for the same (group, user) beyond the chosen bound do not mint one audit row each, while membership state still converges to the state implied by the final event — mirroring the principle 'The drop is counted but not individually audit-logged (a hostile actor can trigger many drops)' (security.md §Invite-code registration) (M1-143 redteam finding 4)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Invite-code registration
  - docs/spec/schema.md §Invariants
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-170: Membership-event hardening (M1-143 redteam findings 2-4)

## Context

The M1-143 in-branch redteam audit (verdict file
`docs/plan/m1/redteam/M1-143-2026-06-05.md`) returned four findings.
Finding 1 (INFO-LEAK, medium) was fixed inside M1-143; findings 2-4 (all
low) were recorded as out-of-scope remediation material and this ticket
carries them. All three live on the membership-event surface that M1-143
hardened: (2) the admin-flag read inside the MEMBER_LEFT transaction takes
no row lock, so a racing `/promote` erases admin provenance from the audit
trail; (3) the atomic abort plus one-shot adapter delivery means one failed
event silently strands a removal (phantom admin blocking the auto-promote
refill) and drops sibling `memberLeft` entries mid-loop; (4) leave events
are attacker-repeatable and each mints an audit row with no bound.

## Acceptance

See frontmatter. In prose: lock the admin-flag read inside the leave
transaction so audit provenance cannot race; isolate per-event dispatch
failures so one bad event cannot drop its siblings, and leave a sanitized
operator signal for stranded removals; bound audit-row minting under
leave/rejoin cycling while still converging membership state; full suite
green.

## Out-of-scope

See frontmatter. The M1-143 deliverables (audit-before-effect transaction,
sanitized failure propagation) must not be reworked — this ticket layers
on top of them. Guaranteed redelivery (outbox machinery for adapter
events) is explicitly not the goal; per-event isolation plus operator
discoverability is the v1 posture. Pre-existing tests in
`MembershipEventHandlerTest` may gain new test methods but existing
assertions must not change.

## Notes

- Source: findings 2-4 in `docs/plan/m1/redteam/M1-143-2026-06-05.md`
  (full PROMISE/GAP/REPRO blocks) and `redteam_findings:` in the M1-143
  ticket file.
- Finding 2 fix surface: `GroupMembershipRepository.isGroupAdmin(Connection,
  UUID, UUID)` — the V5 trigger clears `is_group_admin` during the
  `markMemberRemoved` UPDATE, which is why the read happens first; the
  lock must cover read-to-update.
- Finding 3 fix surfaces: `SignalGroupHandler.dispatchMembership` (the
  memberLeft loop) and the dispatch lambda wired in
  `AdapterRegistry.start()`; the sanitized-signal pattern to follow is
  `SafeLog` in infochat-core (read-only reference — not a file this
  ticket touches).
- Finding 4 carries a design tension the plan-writer should resolve:
  membership events are state sync, not attacker-authored prose — a
  genuine leave must still converge state even when its audit row is
  suppressed by the bound. Invariant 7 applies to privileged actions;
  whether a bound-suppressed leave mutation still requires a (deduped or
  aggregated) audit record is the key design call. Whatever mechanism is
  chosen (cooldown dedupe, token bucket, aggregation row), the acceptance
  item pins only the observable outcome: bounded rows, converged state.
