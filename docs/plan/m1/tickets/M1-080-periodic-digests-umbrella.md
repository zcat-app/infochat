---
id: M1-080
title: Periodic digests umbrella — digest lifecycle roundtrip IT
status: pending
created: 2026-05-25
last_updated: 2026-05-25
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
  - "Step (d) — subscription-version cache hit: a /summary request from the group (same subscription versions) returns the cached content without a second LLM call; assert the LLM mock's call count did NOT increment"
  - "Step (e) — subscription-version cache miss: after changing the group's tag_subscription_version (simulating /follow-tag), a /summary request returns fresh content; the LLM mock IS called"
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
worker generates prose → cache hit on /summary → degraded fallback
on timeout → /retry --digest replaces degraded → serialization
rejects concurrent retries — works end-to-end through the
InMemoryAdapter with a fake LLM**.

## Acceptance

The IT walks eight steps covering the full digest lifecycle: slot
firing, delivery, zero-posts, cache hit/miss by subscription version,
degraded fallback, /retry replacement, and retry serialization.

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
