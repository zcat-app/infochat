---
id: M1-202
title: "Collector fetch hygiene: tracker predicates/keys, Bluesky encoding+parse, Registrar CDI, poller overlap"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 18
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/nostr/NostrStreamSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nostr
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - ReEvaluationJob — M1-182 owns the file (re-eval verdict handling); its overlap policy lands with M1-182 or in a follow-up, not here
  - PartitionCreator and HeartbeatScheduler/InstanceLockGuard — M1-180 owns the partition package; the lock/heartbeat jobs are overlap-safe by construction (advisory-lock keyed) and stay untouched
  - cross-tick UID dedup (the duplicate source feeding these scans) — M1-179's
  - the ?::INTERVAL string-param style nit (audit K14) rides along ONLY if the tracker statement is already being rewritten for the predicate leg; otherwise it stays with UNIFIED.md T33 (lows batch, not yet filed)
  - SSRF pin-map internals — M1-191's (different layer; the Registrar leg here only changes HOW the client is obtained, not the client's behavior)
  - Bluesky identifier semantics (URL vs bare handle, audit's unverified leg) — investigate-tier, not included
acceptance:
  - "Per docs/spec/security.md §Re-evaluation job — on per-source UNKNOWN auto-disable \"a throttled admin notification fires citing the source id, the observed UNKNOWN rate, and the threshold\" — two different sources auto-disabled within one throttle window each produce their own notification: a named test (today disableSource calls notifyOnce with the constant ERROR_CLASS as the throttle key, so the second source's notification is suppressed for the window)"
  - "The tracker's scan is bounded by the partition key: posts whose fetched_at lies outside the rolling window (plus a documented slack) no longer participate in the UNKNOWN-rate computation, so partition pruning applies — a named test seeds an old-partition post and asserts it is excluded (today the only time bound is status_changed_at, which no partition key covers, so every 15-minute tick scans all partitions); the semantic delta is argued in the commit message"
  - "A Bluesky actor identifier containing URL-reserved characters cannot truncate or extend the XRPC query: a named test (today buildUri appends ?actor= raw while the cursor leg IS encoded)"
  - "One malformed indexedAt timestamp does not abort the whole Bluesky parse batch: a named test feeds a response with one malformed and N well-formed items and asserts the well-formed items still come back (today Instant.parse throws and the fetch dies)"
  - "NostrStreamSource's Registrar uses the CDI-configured SSRF-guarded client instead of constructing its own: a named test or wiring assertion (today :303 does new SsrfGuardedHttpClient(), silently dropping any configured policy)"
  - "Poller overlap is settled for the in-scope collector @Scheduled jobs: an overrunning tick can no longer be overlapped by the next tick of the same job double-picking the same work item — the policy choice (scheduler-level or claim-SQL-level) is argued in the commit message, and at least one work-claiming picker is exercised under forced overlap by a named test"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nostr
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D42
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-202: Collector fetch hygiene: tracker predicates/keys, Bluesky encoding+parse, Registrar CDI, poller overlap

## Context

Five collector hygiene findings (unified K6 ×2, K8, K9, K11, K7 —
`deep-code-review/v2/UNIFIED.md` §2):

1. **PerSourceUnknownTracker (K6, med ×2).** The rate query (re-grounded
   at draft time) bounds time only via `p.status_changed_at >= now() -
   ?::INTERVAL` — real, but not the partition key, so all post
   partitions are scanned per tick; and disableSource throttles with the
   constant error class as the notify key, so a second source's
   auto-disable inside the window is silently suppressed.
2. **Bluesky actor unencoded (K8, med).** buildUri appends
   `?actor=` raw; the adjacent cursor parameter is URL-encoded with a
   comment explaining exactly why — the actor leg predates it.
3. **Bluesky brittle timestamp parse (K9, low).** One malformed
   indexedAt kills the batch via Instant.parse.
4. **Registrar CDI bypass (K11, low-med).** NostrStreamSource.Registrar
   news up its own SsrfGuardedHttpClient (:303), bypassing configured
   policy.
5. **Poller overlap (K7, VALID-PARTIAL).** Zero `concurrentExecution`
   declarations and zero `FOR UPDATE SKIP LOCKED` across the collector —
   every @Scheduled job defaults to PROCEED, so an overrunning tick can
   be overlapped by the next and double-pick work. The audit did not
   trace every picker's claim SQL; this ticket settles the policy.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T25 under `deep-code-review/v2/` (opus-47
  coll F3/F6, kimi-folder coll F4/F5/F11, mimo coll F6).
- Shares the eval/reeval package with M1-182 (which owns
  ReEvaluationJob) — serialize against M1-182; PerSourceUnknownTracker
  itself is not in M1-182's files_scope.
- M1-179's dedup pre-check has a race window under overlapping ticks —
  the poller-overlap leg here is the beneficiary that closes it from
  the scheduler side (named per the cross-ticket wiring note in the
  audit).

## Suggested direction (unverified hypothesis)

The audit suggested `concurrentExecution = SKIP` on the pollers as the
cheap settle of K7 (avoiding SKIP-LOCKED claim-SQL work), and
`make_interval` for the tracker's interval binding.

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
— e.g. a poller whose tick MUST overlap for throughput; is there a
simpler alternative meeting the same acceptance?). Adopting, adapting,
or replacing it is the implementer's call as long as every acceptance
item holds; a replacement that changes files_scope goes through the
escalate path.
