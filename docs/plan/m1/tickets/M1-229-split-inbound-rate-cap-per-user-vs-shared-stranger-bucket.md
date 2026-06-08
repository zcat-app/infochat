---
id: M1-229
title: "Split inbound rate-cap: per-user vs shared stranger bucket"
status: done
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
remediates: M1-205
files_budget: 13
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-229.md
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
  - "A named test asserts the registered-contact set's eligibility filter is conservative: a contact whose registration_state is 'preban' (or has no users row, or is_banned=TRUE) is NOT reported as registered, so it can never be routed to the per-id bucket — the false-positive-for-stranger error this ticket exists to prevent."
  - "design 06-messaging is updated to document the registered-per-id vs unregistered-shared split and that the M1-205 maxContactBuckets hard cap now backstops only the registered key set."
  - "Banned-user intake, ban-check ordering, and all existing InboundRouter + RateCapBucket tests stay green (incl. the CountingRateCapBucket / NoopRateCapBucket test doubles)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (new flood / split-routing tests in RateCapBucketTest; new RegisteredContactSetTest for the eligibility filter)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/CountingRateCapBucket.java (3-arg tryAcquire override; recorded call name unchanged)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopRateCapBucket.java (3-arg tryAcquire override)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java (drain-loop call updated to the 3-arg signature; markRegister the seeded contacts so they use isolated per-id buckets; assertions unchanged)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterExportIT.java (markRegister the SQL-seeded 'vouched' users in seedUser() — they bypass the boot-rehydration + coherence hooks, so without this they route as strangers and share the ExportProfile's cap-2 stranger bucket across both test methods; mirrors InboundRouterTest and restores the per-actor "correct bucket" intent; assertions unchanged)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §What's intentionally NOT in v1
decision_refs:
  - D44
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 687
      removed: 28
escalations:
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation budget-breach. Plan-writer outline + main-session
      file accounting put the complete, well-tested implementation at ~12-13 files
      against files_budget: 10. The clarity estimate (7-10) did not count the
      InviteCodeConsumer / BanCommandHandler / UnbanCommandHandler coherence
      write-sites the outline identifies as load-bearing. 10 is reachable only by
      dropping the unban re-hook AND the test that verifies the security-critical
      registered-set eligibility SQL (a wrong filter would mark a 'preban' contact
      as registered — the exact false-positive this ticket exists to prevent).
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — test-time budget-breach surfaced by mvn verify round 1 (1 IT
      failure of 138). The registered/stranger split routes SQL-seeded
      'vouched' users as strangers (they are inserted after app boot, so they
      bypass RegisteredContactSet boot-rehydration AND the invite/ban/unban
      coherence hooks). InboundRouterExportIT.ExportProfile lowers the inbound
      rate cap to 2/min; its two test methods deliver from two DISTINCT
      stranger-routed contacts that now share ONE per-adapter stranger bucket,
      so rateLimitedInCorrectBucket drains it (3 sends) and deliveredInBand's
      single /export from the other contact hits the empty shared bucket -> 0
      replies. The fix is the same production-faithful registeredContactSet
      .markRegistered wiring already applied (authorized) in InboundRouterTest,
      applied to InboundRouterExportIT.seedUser() (it also restores that
      class's original per-actor "correct bucket" intent). InboundRouterExportIT
      is NOT in test_plan.modifies, so the fix takes the change set to 13 files
      vs files_budget: 12. The clarity/refine sweep did not enumerate low-cap
      rate-cap test profiles (ExportProfile is the only one). Default-cap ITs
      are unaffected (<=30 cumulative stranger deliveries vs cap 60).
revisions:
  - date: 2026-06-08
    reason: "budget-breach rework (option 1 refine; user picked 'reuse the existing plan'). The plan-writer outline (target/m1-tick-outline-M1-229.md) already lays out the complete design — it did its job by revealing, pre-code, that the load-bearing coherence write-sites (InviteCodeConsumer markRegistered / BanCommandHandler invalidate / UnbanCommandHandler re-add) plus the test doubles, the eligibility-SQL test, and the InboundRouterTest drain-call edit push the honest file count to ~12 against files_budget: 10. Refine: files_budget 10→12; outline RETAINED and reused (status stays in-progress on the branch, plan-writer NOT re-run); add test_plan.modifies authorizing the two RateCapBucket test doubles + InboundRouterTest (resolves the clarity WARN TEST-CHANGES-AUTHORIZED); add an acceptance item making the security-critical registered-set eligibility filter (preban/banned/no-row excluded) a hard, runnable requirement. Two deltas on top of the outline, both already offered by it: (1) fold the rehydrator into RegisteredContactSet (the outline's own file-saving suggestion) so 12 holds; (2) add InboundRouterTest to the modify set (the outline had it only under 'preserve', but its 2-arg tryAcquire drain call breaks under the SPI change). SPI shape PINNED to tryAcquire(adapter, contactId, boolean registered) — a 3rd boolean param, NOT a separate method, so the recorded call name 'rateCapBucket.tryAcquire' is unchanged and InboundRouterIntakeOrderingTest needs zero edits. Shared stranger bucket is PER-ADAPTER (outline Risk 3; preserves D46 isolation)."
    prior_values: |
      files_budget: 10
      (test_plan had no `modifies:` list)
      (acceptance had no registered-set eligibility-filter item)
  - date: 2026-06-08
    reason: "budget-breach rework (round 1 refine; user picked option 1, 'bump budget to 13 and authorize InboundRouterExportIT'). mvn verify round 1 failed on exactly one IT — InboundRouterExportIT.deliveredInBand (expected 1 reply, got 0). Root cause: the registered/stranger split routes SQL-seeded 'vouched' users as strangers because they are inserted after app boot, bypassing RegisteredContactSet boot-rehydration AND the invite/ban/unban coherence hooks. InboundRouterExportIT.ExportProfile lowers the inbound rate cap to 2/min, so its two test methods' distinct stranger-routed contacts share ONE per-adapter stranger bucket: rateLimitedInCorrectBucket drains it, deliveredInBand then gets 0 replies. Fix: add registeredContactSet.markRegistered to InboundRouterExportIT.seedUser() — the same production-faithful wiring already authorized in InboundRouterTest, which also restores that class's per-actor 'correct bucket' intent. Refine: files_budget 12→13; add InboundRouterExportIT.java to test_plan.modifies. Branch + existing diff RETAINED (status stays in-progress; clarity/plan-writer NOT re-run — implementation context preserved). The clarity/refine sweep had not enumerated low-cap rate-cap test profiles; ExportProfile is the only one, and only this IT was affected (138 ITs ran, 1 failure)."
    prior_values: |
      files_budget: 12
      (test_plan.modifies did not include InboundRouterExportIT.java)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: fa5b3bc^
    head: fa5b3bc
    verdict_file: docs/plan/m1/redteam/M1-229-2026-06-08.md
    out_of_model_count: 2
    note: |
      Post-commit pre-merge audit of the implementation commit fa5b3bc.
      CLEAN — no gap against a documented security.md promise; the M1-205
      memory-bound DOS defense is delivered and tightened, the registered-set
      eligibility SQL is conservative, seed SQL is non-interpolated, the
      in-memory coherence mutations carry no SQL/audit surface, and the
      per-adapter stranger bucket preserves D46 isolation. Two OUT-OF-MODEL
      advisories (non-blocking, user decisions): (1) the shared per-adapter
      stranger bucket couples Sybil/banned-flood traffic with newcomer
      registration availability (within v1-deferred Sybil territory; optional
      follow-up to size the stranger bucket independently and/or split banned
      vs never-seen newcomers); (2) security.md step 1.5 still says
      "per-(adapter, contact_id)" while strangers are now per-adapter —
      consider a spec amendment to describe the split.
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: RateCapBucket SPI shape may change, requiring updates to CountingRateCapBucket.java and NoopRateCapBucket.java (pre-existing test doubles). Notes acknowledge this but no test_plan.modifies / Authorized-test-changes section documents the permitted modification + new SPI shape. If SPI changes, add that section."
  blockers: []
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

## Refinement decisions (budget-breach refine, 2026-06-08)

The plan-writer outline (`target/m1-tick-outline-M1-229.md`) is RETAINED
and reused as-is; these pins record the decisions made at refine time so
the implementation follows the outline without re-running plan-writer:

- **SPI shape:** `RateCapBucket.tryAcquire(String adapter, String
  contactId, boolean registered)` — a 3rd boolean parameter, NOT a
  separate `tryAcquireStranger` method. `registered=true` → existing
  per-id bucket (now bounded only by the registered population, so
  `maxContactBuckets` backstops the registered key set); `registered=false`
  → shared per-adapter stranger limiter, no per-id entry created. Keeping
  one method preserves the recorded call name `rateCapBucket.tryAcquire`,
  so `InboundRouterIntakeOrderingTest`'s call-order assertions need ZERO
  edits (resolves the clarity WARN's worst case).
- **Shared stranger bucket is PER-ADAPTER** (keyed by adapter name; outline
  Risk 3): one adapter's Sybil flood must not starve another adapter's
  newcomers. Bounded by the (tiny, fixed) enabled-adapter count — no
  key-space cap and no eviction needed; document the invariant.
- **Rehydrator folds into `RegisteredContactSet`** (the outline's own
  file-saving suggestion): the set is `@Startup @ApplicationScoped`, injects
  the `DataSource`, and seeds itself at boot from
  `SELECT adapter, contact_id FROM users WHERE registration_state IN
  ('invited','vouched') AND is_banned=FALSE`. This keeps the complete
  implementation at 12 files.
- **`InboundRouterTest` is in the modify set** (outline had it only under
  "preserve"): its `rateCapOverflowDropsSilentlyWithoutOutbound` drains via
  a direct 2-arg `tryAcquire("inmemory", overflowContact)` call that breaks
  under the 3-arg SPI; the fix is a one-line signature update (drain the
  stranger bucket with `registered=false`), assertions unchanged.

## Authorized test changes

Per the clarity WARN (TEST-CHANGES-AUTHORIZED) — the 3-arg SPI change
requires the following test edits, which track the signature only and do
NOT weaken any assertion:

- `CountingRateCapBucket.java` / `NoopRateCapBucket.java`: update the
  `@Override` to the 3-arg `tryAcquire(String, String, boolean)`. The
  `CountingRateCapBucket` MUST keep recording the literal
  `"rateCapBucket.tryAcquire"` (the intake-ordering call-order assertions
  depend on it) and ignore the boolean.
- `InboundRouterTest.java`: update the drain loop's direct `tryAcquire`
  call to the 3-arg form with `registered=false` (the overflow contact is
  unregistered); the zero-outbound and zero-users-row assertions are
  unchanged.
