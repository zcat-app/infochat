---
id: M1-284
title: "Outbound delivery failure layer: retry, cap escalation, cleanup"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 18
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java
  - docs/design/06-messaging.md
complexity: high
risk: high
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Adapter-side classification fixes (SimpleX classifyError substring matching is M1-294; adapters MUST NOT grow their own retry wrapper — the spec's "No silent extension" rule).
  - The edit/finalize fallback-to-fresh-send path (M1-285) — this ticket owns fresh-send failure handling; M1-285 owns the in-place-edit failure fork.
  - §6.12 AdapterMetrics counters — no metrics surface exists (M1-305 decides schedule-or-amend).
  - Native membership events (supportsMembershipEvents=true adapters) — only the permanent-failure-driven fallback path is in scope.
  - Group state purge beyond what the spec commits — the spec says "Group state … is not purged automatically"; cleanup of long-removed groups is a v2 admin command.
acceptance:
  - "A single Provider-side outbound-delivery chokepoint exists and InboundRouter, StageProgressNotifier (terminal sends), DigestWorker, and command reply paths route sends through it; a grep over infochat-provider/src/main shows no direct adapter send/update/finalize call outside the chokepoint (test code exempt)."
  - "TRANSIENT failures are retried per spec (messaging.md §Failure handling, verbatim: 'Maximum attempts: 3 (the original send plus two retries)', 'exponential back-off with full jitter'; start delay/growth/jitter window are profile-driven design values): a named test delivers on attempt 2 after one TRANSIENT failure; a named test asserts a third consecutive TRANSIENT failure stops retrying."
  - "Cap exhaustion escalates per spec (verbatim: 'the failure is escalated to permanent for the rest of this reply's lifecycle … and the throttled-admin-notification path (security.md §Failure handling) fires per (channel, error_class)'): a named test asserts the throttled admin notification fires exactly once per (channel, error_class) window on exhaustion and the user is not pinged."
  - "PERMANENT failures abort the affected reply immediately, are never retried, and do not advance chat session state (spec: 'the context window remains as if the message was never generated, and chat_memory is not written'): named tests for the no-retry and no-chat_memory-write halves."
  - "Bot-removed cleanup per spec: repeated permanent group-send failures past a profile-driven threshold (spec: 'The permanent-failure threshold is always greater than 1') set groups.removed_at = NOW() and cancel the periodic-digest scheduler entries for that group; a named test crosses the threshold and asserts both effects; a second named test asserts a single permanent failure does NOT trigger cleanup."
  - "User-left cleanup per spec §Failure handling 'User left group': a permanent send failure attributed to a specific group member soft-clears the group_membership row (removed_at = NOW()), preserves chat_memory/chat_session/summary_anchor/subscription rows, and clears is_group_admin in the same transaction when the departing user was group admin; a named test covers the admin case."
  - "MessagingException's javadoc (currently naming a nonexistent 'outbound retry layer') names the real layer landed by this ticket."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-284: Outbound delivery failure layer: retry, cap escalation, cleanup

## Context

Deep-review v5 verified HIGH **U-01** (`deep-code-review/v5/UNIFIED-REPORT.md`
§2; sources `deep-code-review/v5/fable-5/01-architecture.md#F1`,
`deep-code-review/v5/gpt-55/report.md#H-02` — gitignored, primary checkout
only; provenance, not needed for implementation: everything load-bearing is
inlined here):

`MessagingException.category()` (TRANSIENT/PERMANENT) has producers in both
production adapters and **zero consumers** — grep of
`infochat-provider/src/main` finds no `FailureCategory` reference (verified
2026-06-11). The spec's entire §Failure handling contract (retry budget,
cap-exhaustion escalation, permanent-failure-driven cleanup) is
unimplemented: `InboundRouter` (~:860) and `DigestWorker` (~:123) catch and
log only. Every transient send failure today permanently drops the reply.
Because SimpleX declares `supportsMembershipEvents=false`,
permanent-failure-driven cleanup is the **only** spec'd membership-cleanup
mechanism for the flagship adapter — departed users and admins keep live
`group_membership` rows indefinitely.

## Acceptance

See frontmatter. The spec sentences quoted there are transcribed from
`docs/spec/messaging.md` §Failure handling (verified verbatim 2026-06-11);
re-read that section in full before implementing — it is the contract.

## Out-of-scope

See frontmatter — adapter-side classification (M1-294), edit fallback
(M1-285), metrics (M1-305).

## Notes

- **⚠ User decision at start:** both top reports insist this must not stay
  implicit. Default = **implement in v1** (written into acceptance): the spec
  text is unambiguous and SimpleX membership cleanup depends on it. The
  alternative the report allows is an explicit spec amendment deferring the
  whole §Failure handling contract to v2 — if the user picks that, refine
  this ticket to a doc-only amendment; do not implement half.
- `complexity: high` — expect a plan-writer outline before code. The fix
  sketch from fable-5: one `OutboundDelivery` bean wrapping
  `MessagingAdapter` send/update/finalize; retry loop on TRANSIENT with
  jittered backoff; per-(group) and per-(group,member) permanent-failure
  counters feeding `groups.removed_at` / membership soft-clear; throttled
  admin notification on cap exhaustion (ThrottledAdminNotifier exists in
  core).
- Profile-driven values (backoff start/growth/jitter, permanent-failure
  threshold) belong in `application.properties` per profile + design 06
  documents them (spec commits the shape only).
- Coordination: M1-285 (edit fallback) is the other half of the
  silent-reply-loss story (fable-5 CT1). Sequence consciously — this ticket
  first is the natural order; if M1-285 lands first, its fallback sends
  must route through this chokepoint when it exists.
- The DigestWorker/group package touch is for cleanup wiring
  (digest-scheduler cancellation on bot-removed); keep the diff surgical.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-284-*.md
```
