---
id: M1-314
title: "Group-deleted-upstream immediate cleanup, distinct from threshold-counted bot-removed"
status: deferred
created: 2026-06-11
last_updated: 2026-06-13
deferred_on: M1-324
deferred_reason: spec-amend
blocked_by:
  - M1-284
  - M1-294
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The outbound-delivery chokepoint, TRANSIENT retry, cap escalation, throttled admin notification, and the threshold-counted bot-removed / user-left cleanup paths — all owned by M1-284. This ticket adds ONE new branch (immediate cleanup on a definitive group-not-found signal) to the chokepoint M1-284 builds; it does not alter the existing threshold path.
  - Adapter-side error classification (the substring/medium matching that yields the group-not-found sub-class) — owned by M1-294. This ticket consumes whatever finer FailureCategory / error-class signal M1-294 exposes; it does not build the classifier.
  - Native membership events (supportsMembershipEvents=true adapters) — the upstream-deletion signal handled here is the permanent-send-failure path, not a native event.
acceptance:
  - "When the M1-284 chokepoint observes a PERMANENT group-send failure classified as 'group deleted upstream' / group-not-found (the distinct error class M1-294 exposes), the group is cleaned up IMMEDIATELY on a single such failure — groups.removed_at = NOW() and the periodic-digest scheduler stops scheduling that group — WITHOUT waiting for the bot-removed permanent-failure threshold (spec docs/spec/messaging.md §Failure handling, 'Group deleted upstream': same effect as bot-removed, but a definitive deletion signal, not a streak): a named test drives one group-not-found PERMANENT failure and asserts removed_at is set and the next queryActiveGroups excludes the group."
  - "A generic PERMANENT group-send failure (not classified group-not-found) does NOT trigger immediate cleanup and still rides the M1-284 threshold path (spec: 'The permanent-failure threshold is always greater than 1'): a named test asserts a single generic permanent failure leaves removed_at NULL."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-13
  verdict: WARN
  warnings:
    - "complexity: medium may be slightly over-calibrated for a single-branch addition with two tests. Consider complexity: low if the M1-294 API shape turns out to be a simple enum constant check. Does not block implementation."
  blockers: []
escalations:
  - date: 2026-06-13
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise failure found during start/implementation grounding,
      before any test run. The ticket branches on "the distinct error class
      M1-294 exposes" for group-not-found, but M1-294 as delivered exposes no
      such signal: FailureCategory is binary TRANSIENT/PERMANENT, the only
      method on MessagingException is category(), and SimpleXMessageCodec
      .classifyError returns a binary FailureCategory (M1-294 U-23 deliberately
      made it a TRANSIENT include-list → else PERMANENT). The acceptance items
      require distinguishing group-not-found from generic PERMANENT, which is
      impossible without that signal; branching on the raw message string is
      explicitly forbidden by the ticket Notes and the spec's "No silent
      extension" rule, and building the classifier here is out_of_scope
      (owned by M1-294) and outside files_scope (messaging-adapter module).
---

# M1-314: Group-deleted-upstream immediate cleanup, distinct from threshold-counted bot-removed

## Context

Peeled off from M1-284 (the outbound delivery failure layer). M1-284 builds
the send-site chokepoint and the permanent-failure-driven group cleanup, but
it treats every PERMANENT group-send failure uniformly: cleanup fires only
after the bot-removed threshold (always > 1) is crossed. The spec's §Failure
handling "Group deleted upstream" case wants the SAME effect (groups.removed_at
+ scheduler cancel) on a DEFINITIVE single signal — the upstream group no
longer exists, so there is no reason to wait for a streak.

That distinction is unreachable until the adapter can tell "group not found"
apart from a generic PERMANENT failure. Today `MessagingException.category()`
is only TRANSIENT/PERMANENT (verified 2026-06-11) — no finer class. The
finer classification is owned by M1-294 (SimpleX `classifyError`). So this
ticket gates on BOTH M1-284 (the chokepoint + cleanup wiring it extends) and
M1-294 (the group-not-found error class it branches on). Treated as a generic
permanent failure in the interim, group-deletion already rides M1-284's
threshold path; this ticket only adds the immediate-on-single-signal branch.

This ticket was deferred from M1-284 during its `start` (2026-06-11): clarity
flagged the missing case in two consecutive passes, and the plan-writer
outline (R5) confirmed it cannot be tested distinctly until M1-294 lands the
classification.

## Acceptance

See frontmatter. This ticket gates on M1-284 (the chokepoint and cleanup it
extends) and M1-294 (the group-not-found classification it branches on) —
neither the branch point nor a distinguishable test exists until both land.

## Out-of-scope

See frontmatter. The chokepoint, retry, cap escalation, threshold cleanup, and
the adapter-side classifier are M1-284's and M1-294's; this ticket only adds
the single-signal immediate-cleanup branch and the test that distinguishes it
from the generic-permanent path.

## Notes

- The branch shape: where M1-284's chokepoint already feeds a PERMANENT
  group-send failure into the per-(group) counter, add a prior check — if the
  failure's error class is the group-not-found one M1-294 exposes, call
  `GroupRepository.markRemoved` directly (setting `removed_at` is the entire
  scheduler-cancel effect, since `DigestScheduler.queryActiveGroups` filters
  `removed_at IS NULL`) and skip the counter. Otherwise fall through to the
  existing threshold path unchanged.
- Keep the diff surgical: one new branch + two named tests. No new schema, no
  new retry behavior, no change to the threshold path's numbers.
- Confirm at implementation time exactly which signal M1-294 surfaces for
  group-not-found (a FailureCategory sub-value, an error-class enum, or a
  string code) and branch on that — do NOT re-derive the classification from
  the raw exception message here (that would duplicate M1-294 and breach the
  spec's "No silent extension" rule).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-314-*.md
```
