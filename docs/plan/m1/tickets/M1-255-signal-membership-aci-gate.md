---
id: M1-255
title: "Signal membership-event ACIs: apply canonical-UUID gate"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 3
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The DM decode path (SignalMessageCodec.extractDm) and the group-message path (SignalGroupHandler.handleReceive) — already run inbound ACIs through isAcceptableAci (landed by M1-242); not re-touched.
  - SignalMessageCodec.isAcceptableAci itself — reused as-is; this ticket only calls it from the membership path, it does not change the matcher.
  - The per-event isolation try/catch around handler.onEvent (the class-name-only SafeLog pattern) — unchanged; one failing event must still not drop sibling entries.
  - SimpleX group membership — its identity boundary is separate and out of scope here.
acceptance:
  - "aciFromArrayEntry (the STRING and OBJECT branches of the membership member-delta parser in SignalGroupHandler) runs the extracted ACI through SignalMessageCodec.isAcceptableAci before returning it, so a member-delta entry that is not a canonical UUID is dropped (returns null) rather than becoming a MembershipEvent contactId — identical to the gate the DM and group-message paths already apply."
  - "A named test in the signal test package asserts: a memberJoined/memberLeft entry carrying a non-canonical-UUID value (bare non-UUID string, and an OBJECT whose uuid is non-canonical) dispatches NO MembershipEvent, while a canonical-UUID entry dispatches a UserJoined/UserLeft event with the lower-cased canonical contactId."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Identity and groups
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 145
      removed: 14
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-09
    verdict: CLEAN
    base: main (650b4cd)
    head: working tree on branch m1/M1-255-signal-membership-aci-gate (uncommitted, --in-progress)
    verdict_file: docs/plan/m1/redteam/M1-255-2026-06-09.md
    out_of_model_count: 1
    note: |
      CLEAN — diff is a focused security tightening (canonical-UUID gate on the
      membership member-delta parser, matching DM/group-message paths). One
      OUT-OF-MODEL observation: membership join/leave dispatch has no bot-mention
      gate (unlike the group-message path); verified accurate but correctly out of
      model (signal-cli is the trusted identity boundary; membership events have no
      mention semantics). Not a gap in this diff, no remediation ticket. Provider-side
      membership-event reconciliation vs authorized group state is a possible future
      spec-coverage question for the user, not an action item for M1-255.
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-255: Signal membership-event ACIs: apply canonical-UUID gate

## Context

The signal-cli daemon stream is an untrusted adapter-inbound boundary. Every
other inbound identity path in this adapter runs the wire ACI through
`SignalMessageCodec.isAcceptableAci` before it can become a
`(adapter, contact_id)` join key — the DM path (`extractDm`) and the
group-message path (`handleReceive`, gated by M1-242). The membership path
(`dispatchMembership` / `aciFromArrayEntry`) is the lone exception: it takes
whatever string signal-cli put in the `memberJoined` / `memberLeft` array,
lower-cases it, and hands it straight to Provider as
`MembershipEvent.UserJoined/UserLeft.contactId()`. Provider keys
`group_membership` row operations (`removed_at`, `is_group_admin` clearing) on
exactly that `(adapter, contact_id)` tuple, so a non-canonical `memberLeft`
entry can soft-clear or detach state Provider can never reconcile against a real
`users.contact_id` — the one inbound surface that bypasses the gate while
*mutating* per-user authorization-adjacent state. Source: deep review
`deep-code-review/v3.5/opus-48/05-module-infochat-messaging-adapter.md#F1`
(verified live against `SignalGroupHandler.java:239-285` on main; M1-242 hardened
the DM/group-message paths but did not extend the gate to the member-delta array).

## Acceptance

See frontmatter. In prose: gate the membership member-delta parser with
`isAcceptableAci` exactly as the DM and group-message paths do; a non-canonical
entry is dropped at decode, a canonical UUID dispatches the membership event with
the lower-cased canonical contactId. A named test pins both the dropped and the
dispatched cases; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The DM/group-message paths (already gated), the
`isAcceptableAci` matcher itself, the per-event isolation try/catch, and SimpleX
membership are untouched.

## Notes

- The codec already documents the invariant verbatim: an inbound ACI that cannot
  be asserted is "dropped at decode rather than becoming a permanent
  `(adapter, contact_id)` join key" (`SignalMessageCodec` javadoc; spec at
  `docs/spec/messaging.md` §Required SPI surface — "a message whose identity
  cannot be asserted is dropped at decode, before delivery").
- `isAcceptableAci` lower-cases internally for the match; the existing paths emit
  the lower-cased canonical form, so return `raw.toLowerCase(Locale.ROOT)` only
  *after* the gate passes, preserving the canonical-join-key invariant.
- v1 accepts canonical-UUID identities only; a hypothetical future E.164-only
  member with no ACI being dropped is the correct v1 behavior, matching the DM
  path's treatment of non-UUID senders.
- Adjacent code / pattern to match: `SignalGroupHandler.handleReceive` line ~140
  (`if (sourceUuid == null || !SignalMessageCodec.isAcceptableAci(sourceUuid)) return;`).
</content>
</invoke>
