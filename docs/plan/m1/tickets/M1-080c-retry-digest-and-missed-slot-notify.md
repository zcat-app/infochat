---
id: M1-080c
title: /retry --digest routing + missed-slot admin notification
status: done
created: 2026-05-25
last_updated: 2026-05-26
reviews:
  - round: 1
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 912
      removed: 15
clarity_check:
  date: 2026-05-26
  verdict: PASS
  warnings: []
  blockers: []
blocked_by:
  - M1-080a
  - M1-080b
  - M1-082
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerMissedSlotTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/src/main/resources/db/migration/** — no migration
  - infochat-messaging-adapter/** — no adapter changes
  - any modification to DigestWorker.java — M1-080b territory
  - any modification to SummaryCacheRepository.java — M1-080a territory
  - any modification to the personal /retry path (non-digest) — M1-065's existing logic stays unchanged
  - M1-080 umbrella's DigestRoundtripIT.java
  - any modification to any pre-existing test NOT listed in files_scope
acceptance:
  - "RetryCommandHandler: when invoked with --digest flag in group scope by a group-admin or bot-admin caller, delegates to DigestRetryService; non-admin callers receive a friendly 'group admin required' error; DM-scope /retry --digest returns a friendly 'digest retry is group-only' error"
  - "DigestRetryService.retryDigest(long groupId) is per-group serialized: at most one /retry --digest is in flight per group at any time; a second concurrent invocation returns a friendly 'retry already in progress' error"
  - "DigestRetryService invokes DigestWorker.execute with a synthetic DigestSlot for the most recent fired slot; the result replaces the existing summary_cache row (same group_id, slot_kind, slot_fired_at) with the new content"
  - "If a degraded cache existed, /retry --digest regenerates full prose (calls DigestRenderer, not DegradedDigestRenderer); if the LLM is still saturated, the retry itself degrades"
  - "DigestScheduler on missed-slot detection calls ThrottledAdminNotifier.notifyOnce with key 'digest_slot_missed', errorClass 'DIGEST', and a message identifying the group + slot; the throttle key ensures one notification per group per slot (not per scheduler tick)"
  - RetryDigestCommandTest.retryDigest_succeedsForGroupAdmin passes
  - RetryDigestCommandTest.retryDigest_rejectsNonAdmin passes
  - RetryDigestCommandTest.retryDigest_rejectsDmScope passes
  - RetryDigestCommandTest.retryDigest_rejectsConcurrentRetry passes
  - DigestRetryServiceTest.retryDigest_replacesCacheRow passes
  - DigestRetryServiceTest.retryDigest_regeneratesFullProseFromDegraded passes
  - DigestRetryServiceTest.retryDigest_serializedPerGroup passes
  - DigestSchedulerMissedSlotTest.missedSlot_notifiesAdminOnce passes
  - "The personal /retry path (without --digest) continues to work unchanged — existing RetryCommandHandler tests pass"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerMissedSlotTest.java
  preserves:
    - all tests currently green on main
    - existing RetryCommandHandler tests for personal /retry
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Failure handling
decision_refs:
  - D17
  - D22
redteam_findings:
  - date: 2026-05-26
    category: AUDIT-EVASION
    severity: high
    promise: |
      Authorization evaluation order on every inbound message:
      ... 8. Audit-log the intent. 9. Execute.
    gap: |
      RetryCommandHandler.handleDigestRetry and DigestRetryService.retryDigest
      execute a mutating operation (DELETE + LLM invocation + INSERT) with no
      audit log write anywhere in the path.
    repro: |
      A group admin sends /retry --digest. The command succeeds. No row is
      written to audit_log. An operator sees no record of who triggered
      digest replacements.
    suggested_fix_class: audit-log-coverage
  - date: 2026-05-26
    category: DOS
    severity: medium
    promise: |
      LLM-triggering operations — its own bucket, capped lower, profile-driven.
    gap: |
      RetryCommandHandler.handleDigestRetry does not check any LLM rate-cap
      bucket before calling digestRetryService.retryDigest(). The per-group
      serialization prevents concurrency but not frequency.
    repro: |
      A group admin repeatedly sends /retry --digest. Each call is serialized
      but unbounded in frequency, driving unbounded LLM cost.
    suggested_fix_class: rate-limit
  - date: 2026-05-26
    category: PERM-ESCAL
    severity: low
    promise: |
      Non-admin /retry --digest -> friendly error.
    gap: |
      rawText.contains("--digest") is a loose substring match. /retry --digestive
      or /retry foo--digest route to the digest path instead of personal retry.
    repro: |
      A bot admin in a group sends /retry something --digest-mode. The substring
      matches, routing to handleDigestRetry instead of personal retry.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-05-26
    verdict: FINDINGS
    base: main
    head: m1/M1-080c-retry-digest-and-missed-slot-notify
    verdict_file: docs/plan/m1/redteam/M1-080c-2026-05-26.md
    findings_count: 3
    out_of_model_count: 1
    note: |
      Three findings: missing audit log (high), missing LLM rate cap (medium),
      loose --digest substring match (low). All three are genuine gaps that
      warrant a remediation ticket. The out-of-model observation about
      multi-instance serialization is accepted per spec.
---

# M1-080c: /retry --digest routing + missed-slot admin notification

## Context

This ticket wires the `/retry --digest` command path (a group-admin
operation that re-generates the most recent digest) and integrates
`ThrottledAdminNotifier` into the DigestScheduler's missed-slot
detection. Together these complete the operator-facing and
admin-facing surfaces of the digest system.

The spec contract is `docs/spec/commands.md` §Periodic group digests
(concurrent retry serialization, degraded-slot retry semantics) +
§Conversation control (`/retry --digest` is group-admin-only, per-group
serialized).

## Acceptance

1. `/retry --digest` in group scope dispatches to
   `DigestRetryService`; only group-admin or bot-admin callers
   proceed; non-admins and DM scope get friendly errors.
2. Per-group serialization: at most one retry in flight per group.
3. The retry replaces the existing cache row (same slot coordinates)
   with new content; a degraded row is regenerated with full prose
   if the LLM is available.
4. DigestScheduler calls `ThrottledAdminNotifier.notifyOnce` on
   missed-slot detection with a per-group-per-slot throttle key.
5. The personal `/retry` path (M1-065) is completely unchanged.
6. All tests pass; `mvn verify` is green.

## Out-of-scope

- DigestWorker internals (M1-080b) — consumed but not modified.
- SummaryCacheRepository (M1-080a) — consumed but not modified.
- ThrottledAdminNotifier internals (M1-058) — consumed as-is.
- The umbrella IT (M1-080).
- Any pre-existing test modification beyond RetryCommandHandler.

## Authorized test changes

- `RetryCommandHandler.java` is modified to add the `--digest`
  flag parsing branch. Existing personal-retry test methods are NOT
  modified; their assertions remain unchanged (the `--digest` path
  is a new branch, not a modification of the personal path).

## Notes

- Per-group serialization uses a `ConcurrentHashMap<Long, Boolean>`
  or equivalent lock (not a DB lock — the serialization is
  Provider-instance-local per spec: "a Provider restart clears the
  in-progress state"). An in-flight flag is set on entry and cleared
  on completion/failure.
- The retry creates a synthetic `DigestSlot` for the most recent
  fired slot by reading the latest `summary_cache` row for the
  group. If no cache row exists (e.g., the group never received a
  digest), the retry returns a friendly "no prior digest to retry"
  error.
- `ThrottledAdminNotifier` integration: the scheduler calls
  `notifyOnce("digest_slot_missed:<groupId>:<slotKind>:<date>",
  "DIGEST", message)` — the compound key ensures one notification
  per unique missed slot, not per scheduler tick that notices it.
- M1-065's `/retry` (personal summary anchor) stays untouched in
  the DM-scope path. The `--digest` flag is the discriminator; its
  absence means the personal path runs as before.
- `ThrottledAdminNotifier` lives in `infochat-core` (relocated from
  `infochat-collector` by M1-082) with the provider role granted
  INSERT/UPDATE on `admin_notification_state` (V22). The import path
  is `app.zcat.infochat.core.notifier.ThrottledAdminNotifier`.
