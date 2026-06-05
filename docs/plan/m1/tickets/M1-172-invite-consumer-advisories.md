---
id: M1-172
title: "InviteCodeConsumer advisories: stale reply javadoc + sweep gating"
status: pending
created: 2026-06-05
last_updated: 2026-06-05
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
remediates: M1-156
out_of_scope:
  - any change to user-visible replies, bundle keys, or the InboundRouter outcome dispatch (InboundRouter.java already sends error.invite.required for Rejected AND BruteForceThresholdBreached — the dispatch is spec-conformant; only the consumer's javadoc and a pinning test change)
  - re-keying the brute-force counter, changing threshold/window values, or per-identity Sybil resistance (out of v1 scope per docs/spec/security.md threat model)
  - the breach-audit exactly-once semantics and the per-key below-threshold remove path in consume — sweep gating must not alter either
acceptance:
  - "InviteCodeConsumer's class javadoc no longer claims a distinct 'too many attempts' fixed reply: it states that Rejected and BruteForceThresholdBreached both map to the same error.invite.required reply at the InboundRouter dispatch, citing docs/spec/security.md §Invite-code registration ('does not change the per-failure user-visible reply')"
  - "InboundRouterIntakeOrderingTest.breachedThresholdRepliesIdenticallyToRejected passes: with FakeInviteCodeConsumer returning BruteForceThresholdBreached, the captured reply text equals the error.invite.required bundle entry — byte-identical to the Rejected-path reply — pinning the no-brute-force-oracle property"
  - "evictStaleBreachAudited's full-map removeIf is time-gated to at most once per gate interval (gate <= the brute-force window) instead of running on every consume; eviction semantics are preserved (a stale entry still leaves the map within one window plus one gate of breach-end); a named InviteCodeConsumerTest test asserts a second consume inside the gate does not re-sweep"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Invite-code registration
decision_refs: []
reviews: {}
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-172: InviteCodeConsumer advisories: stale reply javadoc + sweep gating

## Context

Resolves both out-of-model advisories from M1-156's redteam audit
(`docs/plan/m1/redteam/M1-156-2026-06-05.md` §OUT-OF-MODEL):

- **Reply-oracle advisory — premise falsified, residue is a stale javadoc.**
  The audit flagged a distinct "too many attempts" reply as a brute-force
  oracle, sourced from `InviteCodeConsumer`'s class javadoc (lines 35-38),
  which claims `Rejected` → "invalid or already-used code" reply and
  `BruteForceThresholdBreached` → "too many attempts" reply. The actual
  dispatch (`InboundRouter.java:406-411`) sends the same
  `error.invite.required` for both outcomes, exactly as
  docs/spec/security.md §Invite-code registration requires ("does not
  change the per-failure user-visible reply"). The behavior is conformant;
  the javadoc is wrong and misled the auditor (the router was outside the
  audited diff hunks). Fix the javadoc and pin the uniform-reply property
  with a router test — `InboundRouterIntakeOrderingTest` covers the
  Rejected reply (line ~234) but no test exercises the breached outcome's
  reply.
- **Sybil sweep-cost advisory.** `evictStaleBreachAudited`
  (`InviteCodeConsumer.java:240-243`) runs a full-map `removeIf` on every
  consume (line 155). Growth is already bounded to one window by the
  M1-156 eviction; this ticket amortizes the sweep cost (time-gate) so an
  adapter with free identity minting cannot make every unknown-contact
  consume pay an O(N) scan.

## Acceptance

See frontmatter. In prose: (1) the consumer javadoc states the uniform
`error.invite.required` reply for both failure outcomes, citing the spec
sentence; (2) a new `InboundRouterIntakeOrderingTest` test drives the
`BruteForceThresholdBreached` outcome through the router (the
`FakeInviteCodeConsumer.outcome` field makes this a three-line setup) and
asserts the reply is byte-identical to the Rejected path's; (3) the
stale-entry sweep runs at most once per gate interval, with a named test
that a second in-gate consume does not re-sweep; (4) full suite green.

## Out-of-scope

See frontmatter. Authorized pre-existing-test modification:
`InviteCodeConsumerTest.staleBreachAuditedEntriesAreEvictedOnConsume` may
be adjusted ONLY to satisfy the new gate (e.g. presetting the
package-private last-sweep timestamp the same way it presets stale
`breachAudited` entries); its eviction assertion itself must not weaken.
No other pre-existing test changes are authorized.

## Notes

- Source: M1-156 redteam audit out-of-model items 1 and 2 (verbatim record
  in `docs/plan/m1/redteam/M1-156-2026-06-05.md`).
- `security_relevant: false` deliberately: no security behavior changes —
  the reply mapping is untouched (already conformant) and counter
  threshold/window/keying semantics are out of scope; the diff is javadoc,
  test pinning, and a perf amortization.
- Gate design pointer: a package-private `volatile Instant lastSweep`
  checked in `evictStaleBreachAudited` mirrors how tests already
  manipulate `breachAudited` directly (see the test-visibility comment at
  `InviteCodeConsumer.java:337`). Per-key correctness does not depend on
  the sweep — the below-threshold path removes its own key inline — so
  gating only affects how long abandoned keys linger (memory, not
  semantics).
