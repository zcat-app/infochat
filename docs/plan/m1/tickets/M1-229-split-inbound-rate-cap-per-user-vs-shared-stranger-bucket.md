---
id: M1-229
title: "Split inbound rate-cap: per-user vs shared stranger bucket"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
remediates: M1-205
files_budget: 10
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - SimpleX connection-gate / one-time-invite-link onboarding redesign — the v2 "never see the flood" root fix (gates who can connect at all; changes D44 in-band invite to connection-time issuance). Bigger, SimpleX-specific, spec-level; record separately, NOT here.
  - the InviteCodeConsumer brute-force counter's own key-space — this ticket bounds the rate-cap notebook; if the brute-force counter is found to be a parallel unbounded per-stranger map, file a follow-up (do not redesign it here).
  - per-newcomer fairness WITHIN the shared stranger bucket — v1 ships no per-user fair scheduler (design 06-messaging §6.3.7); the shared bucket is intentionally first-come-first-served.
  - the LLM-call rate cap (LlmRateCap, M1-183) and the per-group sub-buckets (groupReply / groupLlm / groupCommand on RateCapBucket) — untouched.
  - the bounded inbound dispatch queue (M1-224) and the outbound maxSendsPerSecond pacer (M1-205, done) — untouched.
acceptance:
  - "A named test asserts a flood of many DISTINCT unregistered contact ids does NOT grow the per-(adapter, contactId) bucket map: after the flood the per-user bucket count reflects only registered contacts, not the flood ids (strangers no longer create per-id rate-cap state)."
  - "A named test asserts unregistered inbound is bounded by a single shared/aggregate limiter: sustained over-budget unregistered inbound is dropped (tryAcquire-equivalent returns false; silent, no outbound, per spec §Authorization model step 1.5)."
  - "A named test asserts a registered contact retains an independent per-user inbound rate cap (existing 60/min per-user behavior preserved) and that a stranger flood does NOT consume a registered user's per-user budget."
  - "A named test asserts a brand-new contact's invite-code message is admitted while the shared stranger budget has capacity, AND becomes admissible again after the shared budget refills — i.e. registration is rate-limited but NOT permanently closed under a sustained flood (the M1-205 capacity-wall lockout is replaced by a transient, self-clearing rate)."
  - "design 06-messaging is updated to document the registered-per-id vs unregistered-shared split and that the M1-205 maxContactBuckets hard cap now backstops only the registered key set."
  - "Banned-user intake, ban-check ordering, and all existing InboundRouter + RateCapBucket tests stay green (incl. the CountingRateCapBucket / NoopRateCapBucket test doubles)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (new flood / split-routing tests)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §What's intentionally NOT in v1
decision_refs:
  - D44
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-229: Split inbound rate-cap: per-user vs shared stranger bucket

## Context

Remediates the medium DOS finding on M1-205 (done):
`docs/plan/m1/redteam/M1-205-2026-06-08-post-impl.md`. M1-205 added a
hard cap (`maxContactBuckets`, default 100 000) on the per-(adapter,
contactId) inbound rate-cap map to stop unbounded memory growth from
adapter-supplied contact ids (gpt S5). The cap works, but trades the
unbounded-memory DoS for a **registration-availability** DoS: an attacker
sustaining 100 000 warm distinct contact-id keys (each ~1 inbound / <10
min — far under the 60/min per-key cap) pins the map at the cap, so every
brand-new contact's first DM is silent-dropped at step 1.5 before it can
present an invite code (`RateCapBucket.java:234-236`, comment 71-72).
Existing registered users are unaffected; new sign-ups are frozen for as
long as the flood runs.

Root cause: strangers get a **per-id** bucket at all. The fix is to stop
giving unregistered contacts per-id rate-cap state. Registered users keep
a per-id bucket (bounded by the invite-gated population). All unregistered
inbound shares a **single aggregate** limiter — so a Sybil flood burns one
shared budget and gets dropped without creating any per-id entries. The
per-id map then only ever holds registered contacts, the unbounded-growth
/ capacity-wall both disappear, and a new user's lockout softens from a
10-minute capacity wall to a transient, continuously-refilling rate. The
spec's per-user limiter (messaging.md §Failure handling) and the step-1.5
transport cap (security.md §Rate limiting) are preserved.

## Acceptance

See frontmatter. In prose: strangers no longer create per-id rate-cap
state (flood does not grow the map); unregistered inbound is bounded by
one shared limiter (over-budget dropped silently per step 1.5); registered
users keep an independent per-user cap a flood cannot consume; a new
contact's invite-code message is admitted when the shared budget has
capacity and again after it refills (registration is rate-limited, never
permanently closed); design 06-messaging documents the split and that
`maxContactBuckets` now backstops only the registered set; existing
intake / ban-ordering / RateCapBucket tests (and the test doubles) stay
green; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The big one: this is the **v1-mechanism** hardening, NOT
the v2 root fix. The decisive fix is gating who can connect at all
(SimpleX one-time invite links instead of an open address — see
security.md §Sybil resistance, deferred to v2). That changes the D44
onboarding model and is SimpleX-specific; it belongs in its own ticket and
must NOT be pulled in here. Likewise the InviteCodeConsumer brute-force
counter is a separate potential per-stranger surface — flag a follow-up if
it is one, but do not redesign it here.

## Notes

- **The core design fork (for the plan-writer at start):** routing
  registered-vs-stranger happens at step 1.5, which runs BEFORE today's
  `InboundRouter.lookupUser` DB read (`InboundRouter.java:359` rate cap →
  `:382` `SELECT ... FROM users`). Resolving the route via a DB lookup
  per inbound would re-introduce a per-stranger DB flood — the exact cost
  the cap exists to prevent. The likely shape is an **in-memory registered
  -contact set** (bounded by the invite-gated population, populated on
  registration, invalidated on ban) consulted cheaply at step 1.5; a miss
  routes to the shared stranger bucket. Cache coherence (new registrations,
  bans, restart rehydration) is the subtle part. The plan-writer must pin
  this before code.
- Sole inbound caller: `InboundRouter.java:359`
  (`rateCapBucket.tryAcquire(adapterName, contactId)`). The
  `tryAcquireGroup*` / quarantine / LLM caps are separate methods and out
  of scope.
- Test doubles to keep in sync if the SPI shape changes:
  `CountingRateCapBucket.java`, `NoopRateCapBucket.java`.
- The shared stranger bucket loses per-newcomer fairness — accepted, v1
  ships no fair scheduler (design 06-messaging §6.3.7). A legit newcomer
  still contends with the swarm for the shared budget during an active
  flood, but as a transient rate, not a held capacity slot. This is a
  defense-in-depth improvement, not full Sybil resistance (still v2).
- Source: M1-205 post-impl redteam finding (medium DOS), main-session
  design discussion 2026-06-08.
