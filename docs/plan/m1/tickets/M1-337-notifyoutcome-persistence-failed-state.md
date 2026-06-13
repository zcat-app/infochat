---
id: M1-337
title: "NotifyOutcome: distinguish degraded-DB persistence-failure from throttle-suppressed"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 4
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/NotifyOutcome.java
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/test/java/app/zcat/infochat/core/notifier
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - M1-258 (deferred — fold suppressed_count bump into the UPSERT). Adjacent (same class) but a distinct concern; do not pull it in. This ticket only adds the third outcome constant and returns it on the degraded-DB fallback path.
  - The throttle gate shouldEmitFallback() and the canonical PERSISTENCE_FAILED_KEY one-per-window contract — unchanged.
acceptance:
  - "NotifyOutcome gains a third constant (e.g. PERSISTENCE_FAILED) whose javadoc states: the notifier's own persistence path failed; a degraded-DB fallback WARN may have been emitted on the canonical admin-notifier-persistence-failed key (throttled one per window); suppressed_count was NOT incremented (DB unreachable); callers should not retry on this outcome. The existing SUPPRESSED javadoc ('suppressed_count was incremented and no log emitted') is no longer overloaded onto a path that violates both halves."
  - "ThrottledAdminNotifier's SQLException catch block returns the new PERSISTENCE_FAILED constant instead of SUPPRESSED, so a caller branching on the outcome to decide 'did the operator see this failure?' gets a correct answer for the degraded-DB fallback path."
  - "sqlExceptionFallbackEmitsCanonicalAdminNotifyFormat and ThrottledAdminNotifierFallbackThrottleTest are updated to assert the new value where they previously asserted SUPPRESSED for a fallback-emitting call. Any collector/provider call site that switches on NotifyOutcome handles the new case (current sites read EMITTED only, so this is a low-burden exhaustiveness update)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/notifier (assert PERSISTENCE_FAILED on the fallback path)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-337: NotifyOutcome — distinguish persistence-failure from suppressed

## Context

Deep-review v5.5 (opus-47, `02-module-infochat-core.md` F1) found that the
degraded-DB fallback path in `ThrottledAdminNotifier` emits a WARN but returns
`NotifyOutcome.SUPPRESSED`, contradicting the enum's documented contract.
**Verified at source 2026-06-14:** the SQLException catch block emits a WARN when
`shouldEmitFallback()` is true, then `return NotifyOutcome.SUPPRESSED`
(ThrottledAdminNotifier.java:266-273); the `SUPPRESSED` javadoc says
"suppressed_count was incremented and no log emitted" (NotifyOutcome.java:16) —
both false on the fallback path (the DB is unreachable so the count is not bumped,
and a WARN IS emitted).

A caller branching on `outcome == SUPPRESSED` to decide "did the operator see
this failure?" gets a wrong answer for the first fallback call in every window.
The behavior is pinned by `sqlExceptionFallbackEmitsCanonicalAdminNotifyFormat`,
so the drift is structural, not accidental. Three distinct states (emitted /
throttle-suppressed / persistence-failed) deserve three names; the existing pair
compressed two of them at the cost of a wrong-for-one-state doc.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Report Option A (add the constant) is preferred over Option B (rewrite the
  SUPPRESSED javadoc to admit the fallback case) — Option B leaves a caller seeing
  `SUPPRESSED` and reasonably assuming no log line.
- Adjacent to deferred **M1-258** (fold `suppressed_count` bump into the UPSERT) —
  same class, different concern. They can be sequenced but are independent.
