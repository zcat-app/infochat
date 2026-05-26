---
id: M1-080
title: Periodic digests umbrella — digest lifecycle roundtrip IT
status: done
created: 2026-05-25
last_updated: 2026-05-26
clarity_check:
  date: 2026-05-26
  verdict: PASS
  warnings: []
  blockers: []
blocked_by:
  - M1-080a
  - M1-080b
  - M1-080c
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change to the spec — §Periodic group digests is complete on main HEAD; this umbrella is test-only
  - any change to M1-080a's V21 migration, DigestScheduler, or SummaryCacheRepository — FROZEN
  - any change to M1-080b's DigestWorker, DigestRenderer, DegradedDigestRenderer, or DigestPostCollector — FROZEN
  - any change to M1-080c's RetryCommandHandler --digest branch, DigestRetryService, or ThrottledAdminNotifier integration ��� FROZEN
  - any change under infochat-core/src/main/resources/db/migration/ — V21 is M1-080a's commit; this umbrella adds no schema
  - any modification to any pre-existing test in infochat-provider/src/test/, infochat-core/src/test/, or infochat-messaging-adapter/src/test/
acceptance:
  - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java exists, ends with *IT suffix, contains at least one @Test annotation"
  - "The IT is a @QuarkusTest with an inline @TestProfile setting infochat.adapters=inmemory, infochat.adapters.inmemory.allow-low-trust=true, and configuring digest slot hours + window width for deterministic testing (e.g., morning-hour=0 so the test can trigger at any time)"
  - "Step (a) — scheduler fires slot for group with active subscriptions: a group exists with timezone UTC, at least one tag subscription, and posts matching that subscription; the scheduler tick fires a slot; after execution, a summary_cache row exists for the group with non-empty content and is_degraded=false"
  - "Step (b) — digest message delivered to group: after step (a), the InMemoryAdapter's sentGroupMessages contains one message for the group whose body matches the cached content"
  - "Step (c) — zero-eligible-posts produces fixed reply: a second group exists with no subscriptions; scheduler fires its slot; the delivered message matches the 'no posts yet' bundle value; a summary_cache row exists with the fixed content"
  - "Step (d) — scheduler slot deduplication (cache hit): after step (a), re-fire scheduler.tickAt for the same morning slot; the existsByGroupAndSlot guard prevents re-execution; assert testLlmProvider.callCount() did NOT increment and no new message was delivered to the group (sentToGroup size unchanged)"
  - "Step (e) — subscription-version capture on new slot: after changing the group's tag_subscription_version to 2 (simulating /follow-tag), fire a new evening slot via scheduler.tickAt; the worker generates fresh content; assert the summary_cache row stores tag_subscription_version=2 and the LLM mock IS called"
  - "Step (f) — degraded fallback: configure a slow LLM mock that exceeds the slot window; the delivered digest contains headlines + sources only (no prose); the summary_cache row has is_degraded=true"
  - "Step (g) — /retry --digest replaces degraded with full prose: after step (f), reset the LLM mock to respond instantly; group admin issues /retry --digest; the reply contains full prose; the summary_cache row is updated with is_degraded=false"
  - "Step (h) — /retry --digest serialization: two concurrent retries from the same group; the second returns a 'retry already in progress' friendly error"
  - "mvn -B clean verify from the repo root exits 0; DigestRoundtripIT runs under failsafe with no failures"
  - "Every prior test continues to pass"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
  preserves:
    - every test currently green on main
    - every test added by M1-080a, M1-080b, M1-080c
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/schema.md §Operational
  - docs/spec/llm.md §Per-task routing rules
reviews:
  - round: 1
    date: 2026-05-26
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 2
      added: 408
      removed: 2
  - round: 2
    date: 2026-05-26
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 2
      added: 465
      removed: 10
  - round: 2
    date: 2026-05-26
    verdict: OVERRIDE-APPROVE
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 2
      added: 465
      removed: 10
    override_ref: 0
overrides:
  - date: 2026-05-26
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — must-shrink violation. Round 2 (2/465/10)
      not smaller than round 1 (2/408/2) on any dimension. The must-shrink
      rule text has no explicit exception for premise-fail refines that
      change acceptance criteria.
    user_justification: |
      Growth is 100% from the refine procedure's own metadata writes to the
      ticket file (escalation entries, revision snapshots, revised acceptance,
      superseded-rework note — ~60 lines). The test file delta is +4 lines
      net (step d: replaced trivial SQL re-read with scheduler re-fire +
      assertions). All 12 acceptance items PASS. The must-shrink rule targets
      implementation scope creep; the growth here is workflow-generated
      process metadata, not developer scope expansion.
escalations:
  - date: 2026-05-26
    reason: premise-fail
    reviewer_verdict_excerpt: |
      REWORK ITEMS:
        1. Step (d) cache-hit: Replace the direct readCacheRow SQL call with
           adapter.deliverGroupMention(UPSTREAM_G1, ADMIN_CONTACT, "/summary")
        2. Step (e) cache-miss: Replace scheduler.tickAt(eveningTick) with
           adapter.deliverGroupMention(UPSTREAM_G1, ADMIN_CONTACT, "/summary")
      Developer investigation:
        SummaryCommandHandler.resolveScopeId() returns Optional.empty() for
        group scope (line 262) — handler replies "no posts yet" for all non-DM.
        SummaryCommandHandler does not read summary_cache at all. The
        subscription-version cache hit/miss mechanism described in acceptance
        items (d)/(e) has no runtime code path in the codebase.
  - date: 2026-05-26
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — must-shrink violation. Round 2 (2/465/10)
      not smaller than round 1 (2/408/2) on any dimension. Growth is from
      premise-fail refine metadata (escalations, revisions, revised
      acceptance) in the ticket file. ACCEPTANCE-CHECK: PASS on all 12
      items. Reviewer flagged as MANUAL because must-shrink text has no
      explicit carve-out for premise-fail refines.
revisions:
  - date: 2026-05-26
    reason: premise-fail refine — steps (d)/(e) referenced group-scope /summary cache-serving that SummaryCommandHandler does not implement (group scope returns "no posts yet"; handler does not read summary_cache). Rewrote (d) to test scheduler slot deduplication and (e) to test subscription-version capture on a new slot.
    prior_acceptance_d: "Step (d) — subscription-version cache hit: a /summary request from the group (same subscription versions) returns the cached content without a second LLM call; assert the LLM mock's call count did NOT increment"
    prior_acceptance_e: "Step (e) — subscription-version cache miss: after changing the group's tag_subscription_version (simulating /follow-tag), a /summary request returns fresh content; the LLM mock IS called"
decision_refs:
  - D16
  - D17
---

# M1-080: Periodic digests umbrella — digest lifecycle roundtrip IT

## Context

Umbrella commit for the M1-080 group (per
`docs/process/workflow.md` §Ticket-ID placeholder convention —
the umbrella + subticket idiom). M1-080a, M1-080b, and M1-080c each
ship a slice of the T2-F.2 periodic group digest system as its own
reviewable commit on `main`:

- **M1-080a** — V21 summary_cache migration + DigestScheduler
  (staggered slot windows, missed-slot skip, per-group timezone) +
  SummaryCacheRepository.
- **M1-080b** — DigestWorker (LLM prose generation + delivery) +
  DigestRenderer + DegradedDigestRenderer + DigestPostCollector +
  subscription-version-keyed cache writes + zero-eligible-posts
  handling.
- **M1-080c** — /retry --digest routing (per-group serialized,
  group-admin-only, replaces cache row) + ThrottledAdminNotifier
  integration for digest_slot_missed notifications.

Each subticket's per-class tests verify its own slice. This umbrella
verifies the **cross-cutting** property the subtickets cannot verify
in isolation: **the full digest lifecycle — scheduler fires slot →
worker generates prose → scheduler deduplication prevents re-fire →
subscription-version capture on new slot → degraded fallback on
timeout → /retry --digest replaces degraded → serialization rejects
concurrent retries — works end-to-end through the InMemoryAdapter
with a fake LLM**.

## Acceptance

The IT walks eight steps covering the full digest lifecycle: slot
firing, delivery, zero-posts, scheduler slot deduplication,
subscription-version capture on new slot, degraded fallback,
/retry replacement, and retry serialization.

## Out-of-scope

- Changes to any subticket file — all three subticket commits are
  frozen.
- Changes to migrations — V21 is M1-080a's commit.
- Group infrastructure (M1-079) — already on main by the time this
  umbrella runs.
- Any pre-existing test modification.

## Notes

- The IT uses a fake LLM bean (CDI @Alternative @Priority) that
  returns deterministic prose and can be configured to simulate
  timeout (delayed response past the window-end).
- The IT configures digest slot hours to deterministic values
  (e.g., morning-hour=0, evening-hour=12, window=1440 minutes) so
  the scheduler always finds an open slot regardless of when the
  test runs.
- Group setup: seeded via JDBC (groups row + group_membership row +
  scope_preferences with tag subscriptions + posts matching those
  tags). The InMemoryAdapter group (M1-079b) is also set up.
- The subticket commits are FROZEN at the umbrella round. If this IT
  exposes a defect, the fix is a NEW ticket.

## Round 1 rework (superseded by refine)

Original rework items required routing `/summary` through
`adapter.deliverGroupMention`, which is infeasible:
`SummaryCommandHandler` returns "no posts yet" for group scope and
does not read `summary_cache`. Acceptance items (d)/(e) were refined
to test the actual digest lifecycle code paths: scheduler slot
deduplication and subscription-version capture on new slot.
