---
id: M1-162
title: "[INVESTIGATE] confirm-or-drop adapter SPI surfaces vs D47"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - docs/design/06-messaging.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - provider-side membership wiring (AdapterRegistry, MembershipEventHandler — carries the M1-170 hardening; only the adapter-side dispatch shape changes here)
  - wiring ProgressNotifier into /summary, digest, or chat-agent handlers (provider module; if the verdict is "wire in v1" that work is a follow-up ticket, not this one)
  - deleting ProgressNotifier or ProgressStage (spec §Progress notifications mandates the surface; a departure goes through spec-amend escalation, never silent removal)
  - SignalAdapter DM send/receive path beyond SignalGroupHandler and the groupHandler() seam
  - capability-flag values and the CapabilityFlags surface (landed in M1-147)
  - db migrations
acceptance:
  - "docs/design/06-messaging.md gains a '## SPI surface decisions (D47/D31 reconciliation)' section with one entry per surface — (a) MessagingAdapter.onMembershipEvent dispatch shape, (b) SignalGroupHandler unwired producer / group-envelope decode, (c) ProgressNotifier — each recording a verdict (keep+unify | wire | remove | keep-as-seam) plus a rationale citing the §Contract block in this ticket"
  - "Membership-event delivery is unified on exactly one dispatch shape: either SignalGroupHandler delivers through MessagingAdapter.onMembershipEvent (the shape InMemoryAdapter uses) instead of invoking MembershipHandler.onEvent directly (SignalGroupHandler.java:188), or InMemoryAdapter switches to direct handler invocation and the onMembershipEvent default method (MessagingAdapter.java:176) is removed; whichever survives, a test in infochat-messaging-adapter asserts both adapters deliver a membership event through the surviving shape, and the M1-170 per-event isolation behaviour is preserved"
  - "The SignalGroupHandler producer gap is resolved per the recorded verdict: either SignalAdapter's receive path routes group-scope envelopes into groupHandler() (SignalAdapter.java:337 gains a production caller) with a test driving a group envelope end-to-end through the adapter, or the unwired seam is removed and its envelope-decode collapses into the surviving receive path"
  - "ProgressNotifier's design-note entry records keep-as-v1-seam (interface retained, provider wiring explicitly out of scope) OR flags the spec divergence for a spec-amend escalation; the interface file is not deleted in this ticket"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Progress notifications
decision_refs:
  - D47
  - D31
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 399
      removed: 54
escalations:
  - date: 2026-06-05
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL
      BLOCKERS:
        1. ACCEPTANCE-RUNNABLE — Item 1 ("Reconcile with the D47 group-authorization design..."):
           no checkable artifact. Name a concrete output — a decision comment block, a note in
           docs/design/06-messaging.md, or a new test assertion — so the reviewer can verify the
           reconciliation occurred.
        2. ACCEPTANCE-RUNNABLE — Item 2 ("Record per-surface decision (keep + unify, or remove)
           with rationale"): no artifact path or form given. State WHERE the decision is recorded
           (e.g., "add a §SPI-decision-log section to docs/design/06-messaging.md") so this is
           checkable.
        3. SELF-CONTAINED-CHECK — Acceptance item 1 defers the behavioral contract to D47 without
           inlining what D47 requires. Add the relevant D47 invariants to the ticket body (what
           the correct group-authorization dispatch shape is, which events D47 mandates) so an
           implementer does not need to load D47 to make the keep/drop judgment.
revisions:
  - date: 2026-06-05
    reason: clarity-fail rework (acceptance used prose verbs with no checkable artifact; D47 contract not inlined; out_of_scope was a timing constraint, not an exclusion list)
    snapshot:
      status: escalated
      escalation_reason: clarity-fail
      files_budget_at_snapshot: 8
      acceptance_at_snapshot:
        - "Reconcile with the D47 group-authorization design whether each surface is live or dead: MessagingAdapter.onMembershipEvent (two incompatible dispatch shapes — SignalAdapter bypasses it, InMemoryAdapter routes through it), the Signal group handler DM-decode duplication with no wired producer, and the ProgressNotifier SPI with zero implementations"
        - "Record per-surface decision (keep + unify, or remove) with rationale"
        - "Implement the chosen outcome: unify onMembershipEvent on one dispatch shape, and either wire or remove the Signal group path / ProgressNotifier per the decision"
        - "mvn -B clean verify from the repo root exits 0"
      out_of_scope_at_snapshot:
        - deleting any SPI surface before its intent is reconciled with the D47 design
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-162: [INVESTIGATE] confirm-or-drop adapter SPI surfaces vs D47

## Context

Three adapter SPI surfaces are structurally inconsistent (all grounded on disk,
2026-06-05):

1. **Two membership-event dispatch shapes.** `MessagingAdapter` declares both
   `setMembershipEventHandler` (`MessagingAdapter.java:163`, stores Provider's
   one callback) and a no-op default `onMembershipEvent`
   (`MessagingAdapter.java:176`, javadoc: "the adapter dispatches events through
   the registered handler"). `InMemoryAdapter` routes events through its
   `onMembershipEvent` override; `SignalGroupHandler` holds the registered
   `MembershipHandler` directly and calls `handler.onEvent(event)`
   (`SignalGroupHandler.java:188`), so for Signal the `onMembershipEvent`
   surface is dead. Exactly one shape should survive.
2. **Unwired Signal group producer.** `SignalAdapter.groupHandler()`
   (`SignalAdapter.java:337`) builds a `SignalGroupHandler`, but no production
   code calls it — the `SignalJsonRpcClient` receive loop never routes
   group-scope envelopes into it (the class javadoc still points at "M1-109
   integration"). Its envelope-decode partially duplicates the DM receive
   path's decode (audit finding C-SIGNAL-GROUP-DUP).
3. **ProgressNotifier has zero implementations.** `ProgressNotifier.java` is
   referenced only by SPI load tests. It is NOT dead code by spec: see
   §Contract below.

The M1-143/M1-170 hardening (per-event isolation, audit-before-effect) lives on
the membership surface and must be preserved by whichever dispatch shape
survives.

## Contract (inlined — the ticket is self-contained)

- **D47 (group authorization gate):** group interaction requires
  `registration_state IN ('invited','vouched')` AND
  `groups.approval_status = 'approved'`. Membership events feed the
  group-admin lifecycle: per `docs/spec/messaging.md` §Failure handling, a
  departing group admin's `UserLeft` must soft-clear the membership row AND
  clear `is_group_admin` in the same transaction, and a row with
  `removed_at IS NOT NULL` is not eligible for first-mention auto-promote.
  These invariants hold only if every adapter delivers membership events to
  Provider through the same registered-handler path — divergent per-adapter
  dispatch risks divergent D47 semantics.
- **§Required SPI surface — Membership events:** adapters that can detect
  group-membership changes surface them as `user_joined_group` /
  `user_left_group` events to Provider; adapters without a native left-group
  signal MUST set `supportsMembershipEvents = false` and MUST NOT synthesise
  events from inactivity. Signal exposes member-joined/member-left natively
  (`memberJoined`/`memberLeft` ACI arrays in `groupV2` update envelopes), so
  its group path is spec-live, not vestigial.
- **§Progress notifications (D31):** long-running handlers (`/summary`,
  periodic digest, chat agent) publish stage events to a cross-cutting
  `ProgressNotifier`. The spec MANDATES this surface — zero implementations
  means an unshipped v1 surface, not dead code. Removing it requires a spec
  amendment; this ticket may only record keep-as-seam or recommend the
  amendment.

## Acceptance

See frontmatter. Decide per surface against the inlined contract, record the
verdicts in the design-note section, then implement within the adapter module.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-MEMBERSHIP-SPI, §C-SIGNAL-GROUP-DUP,
  §D-PROGRESS-NOTIFIER; `opus-47-full-handout.md` §F-MAINT-24/42/49/84; `opus-47-only-handout.md` §M9.
- Line references grounded 2026-06-05 against main @ f432289.
