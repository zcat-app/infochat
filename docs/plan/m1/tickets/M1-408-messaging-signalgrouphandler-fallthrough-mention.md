---
id: M1-408
title: "messaging: don't drop a co-delivered bot mention after a Signal group membership delta"
status: done
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 2
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The membership-delta dispatch itself (join/leave handling) — unchanged; this ticket only removes the early return that follows it.
  - The mention-detection and message-handling logic below the early return — unchanged; it is reached, not rewritten.
  - The §6.3.10 counters — unchanged unless a counter is needed to make the co-delivered case observable (see Notes).
acceptance:
  - "SignalGroupHandler no longer early-returns after dispatching a membership delta; control falls through to the message-handling path, so a bot mention co-delivered in the same notification as a membership delta is still processed rather than silently dropped."
  - "A test in infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal feeds a synthetic notification carrying BOTH a membership delta AND a message body containing a bot mention, and asserts the membership delta is dispatched AND the mention is handled (not dropped)."
  - "A test asserts a membership-delta-only notification (no message body) behaves exactly as before: the membership delta is dispatched and the message/mention path is a no-op (no spurious mention dispatch)."
  - "Existing SignalGroupHandler tests remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal (co-delivered delta+mention is not dropped)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 102
      removed: 20
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-20
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-408: don't drop a co-delivered bot mention after a Signal group membership delta

## Context

Deep-review full (2026-06-20) messaging-adapter finding **F1** (MAINTAINABILITY-
RULES-DRIFT, medium). Verified at source 2026-06-20 (verdict: PARTIAL — the
mechanism is real; reachability is latent, see below):

`SignalGroupHandler` dispatches a group membership delta and then early-returns
(`if (dispatchedMembership) return;`), which skips the bot-mention check further
down the same method. The membership delta (`dataMessage.groupV2.memberJoined` /
`memberLeft`) and the message body (`dataMessage.message`) are independent fields on
the same notification object — nothing in `SignalGroupHandler`, the message codec,
or any test enforces that they cannot co-occur. If a notification ever carries both,
the user's bot command is silently discarded before the mention check and before the
§6.3.10 counters, so the drop is invisible (no log, no metric).

This is a latent footgun, not a live bug: today's signal-cli framing is not known to
combine a member delta and a message body in one notification, which is why the
current code is not wrong against today's wire behavior. But the safety rests on an
undocumented-by-protocol assumption at a boundary where the loopback peer is
explicitly treated as untrusted. The fix is to remove the early return so control
falls through: the message branch is already a no-op for a member-delta-only
notification, so well-behaved traffic is unaffected, and a co-delivered mention is
no longer dropped.

## Acceptance

See frontmatter. Remove the early return; fall through to the existing message path.

## Out-of-scope

See frontmatter. The delta dispatch and the mention/message logic are unchanged.

## Notes

- `security_relevant: false` is defensible: `SignalGroupHandler` is adapter-inbound
  boundary code, but this change adds no security control and alters no trust or
  authorization decision — it only stops a co-delivered legitimate mention from being
  silently dropped. (Pre-empts the clarity SECURITY-FLAG-CONSISTENT check.)
- Optional, implementer's discretion within scope: if the co-delivered case is worth
  making observable, increment an existing §6.3.10 counter when both shapes arrive in
  one notification. Not required by acceptance.
</content>
