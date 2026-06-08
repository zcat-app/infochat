---
id: M1-202
title: "Collector fetch hygiene: tracker predicates/keys, Bluesky encoding+parse, Registrar CDI, poller overlap"
status: done
created: 2026-06-07
last_updated: 2026-06-08
blocked_by: []
files_budget: 20
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/ssrf/CollectorSsrfClientProducer.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
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
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
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
  - "One malformed indexedAt timestamp does not abort the whole Bluesky parse batch: a named test feeds a response with one malformed and N well-formed items and asserts the well-formed items still come back (today Instant.parse throws and the fetch dies)"
  - "NostrStreamSource's Registrar obtains its SsrfGuardedHttpClient via CDI injection from a collector @Produces method (new CollectorSsrfClientProducer) instead of constructing its own at field init: a named wiring test asserts the Registrar's client resolves to the CDI-produced bean. (Ground-truth correction, premise-fail refine 2026-06-08: SsrfGuardedHttpClient is NOT a CDI bean and infochat-ssrf carries no CDI dependency, so there is no pre-existing 'CDI-configured client' to inject and the Registrar's new SsrfGuardedHttpClient() is the identical default-strict construction every sibling fetcher uses — it drops no configured policy. The fix introduces a collector-side @Produces supplying one shared default-strict instance, routing the Registrar — and any future collector consumer — through a single CDI-managed client. Behavior is unchanged: same default-strict guard, only HOW it is obtained changes. Other consumers' direct construction stays out of scope.)"
  - "Poller overlap is settled for the in-scope collector @Scheduled jobs: an overrunning tick can no longer be overlapped by the next tick of the same job double-picking the same work item — the policy choice (scheduler-level or claim-SQL-level) is argued in the commit message, and at least one work-claiming picker is exercised under forced overlap by a named test"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D42
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
      files: 18
      added: 399
      removed: 34
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: c215a8a^
    head: c215a8a
    verdict_file: docs/plan/m1/redteam/M1-202-2026-06-08.md
    out_of_model_count: 2
    note: |
      CLEAN. No threat-model promise broken. Two non-blocking OUT-OF-MODEL
      advisories: (1) the new fetched_at window+slack bound narrows the
      per-source UNKNOWN auto-disable signal, but the §Per-source UNKNOWN
      auto-disable defense is not meaningfully weakened — counted first-pass
      verdicts (stage2_failed=FALSE) land far inside the 2d slack; only a
      >2-day first-pass eval backlog could undercount, ruled out by the
      documented drain-time argument. (2) Cosmetic doc-vs-code mismatch:
      CollectorSsrfClientProducer produces @Singleton while the ssrfClient()
      accessor + wiring-IT javadoc say "@ApplicationScoped client proxy" — no
      behavioral impact, assertSame still holds. Neither needs a remediation
      ticket; advisory (2) could ride a future doc-touch.
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: acceptance item 5 fixes a CDI bypass causing NostrStreamSource.Registrar to construct its own SsrfGuardedHttpClient, dropping configured policy — reconnects the SSRF enforcement chain. Consider security_relevant: true. out_of_scope framing (changes HOW the client is obtained, not its behavior) is accurate; low-stakes judgment call, does not block."
  blockers: []
escalations:
  - date: 2026-06-08
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      Caught at /m1-tick start grounding (step 0), before the clarity-reviewer
      subagent ran. Two files_scope entries (and the matching test_plan.adds
      entry) point at package fetcher/nostr, which does not exist on disk;
      NostrStreamSource.java actually lives in stream/nostr. The other 14 scope
      paths resolve. Recorded as clarity-fail because it is a pre-implementation
      ticket-validity defect and the refine arm mechanics match (no branch,
      status returns to pending, re-run start with fresh clarity + plan).
  - date: 2026-06-08
    reason: premise-fail
    reviewer_verdict_excerpt: |
      Caught during /m1-tick start implementation grounding. Acceptance item 5's
      premise is factually wrong against the code: SsrfGuardedHttpClient is not a
      CDI bean (no scope annotation; the class is final), there is NO @Produces
      for it anywhere, and EVERY consumer (Rss/Reddit/Nitter/YouTube/Bluesky
      fetchers, the asset sources, UrlProbe, and the Nostr Registrar) constructs
      it identically via `new SsrfGuardedHttpClient()` — the no-arg constructor IS
      the default-strict policy. So the Registrar drops no "configured policy";
      its construction is identical to every sibling. Satisfying item 5 literally
      requires making SsrfGuardedHttpClient CDI-obtainable, which needs a path
      outside files_scope. Resolution: user chose refine-in-place (expand scope) —
      add a collector @Produces (CollectorSsrfClientProducer) and inject it in the
      Registrar; files_budget 18->20; item-5 acceptance corrected.

      Second premise-fail leg (same trigger): acceptance item 3 (Bluesky actor
      URL-encoding, "today buildUri appends ?actor= raw") is also stale. Git
      history shows the raw-actor buildUri was introduced by M1-087 (93d61ba)
      and DELETED by M1-220 (e9606c9), which moved Bluesky to the D38 design
      where source.identifier is the full operator-supplied XRPC URL with the
      actor pre-baked — the fetcher no longer constructs the actor param, and the
      only fetcher-built query param (cursor) is already URL-encoded. M1-220 is
      the merged investigate ticket this ticket's out_of_scope "Bluesky identifier
      semantics ... investigate-tier" line points at. Resolution (user): drop
      item 3, no follow-up (already closed by M1-220/D38); remove BlueskyFetcher.java
      from files_scope (item 3 was its only consumer).
revisions:
  - date: 2026-06-08
    reason: "clarity-fail refine: correct stale fetcher/nostr scope paths to stream/nostr"
    snapshot_files_scope:
      - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/nostr/NostrStreamSource.java
      - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nostr
  - date: 2026-06-08
    reason: "premise-fail refine (expand scope): item 5 has no pre-existing CDI-configured SsrfGuardedHttpClient to inject; add a collector @Produces (CollectorSsrfClientProducer) + Registrar inject"
    snapshot_files_budget: 18
    snapshot_files_scope_added:
      - infochat-collector/src/main/java/app/zcat/infochat/collector/ssrf/CollectorSsrfClientProducer.java
  - date: 2026-06-08
    reason: "premise-fail refine (drop item 3): Bluesky actor encoding already closed by merged M1-220 (D38 full-URL identifier; raw buildUri removed) — drop acceptance item 3, remove BlueskyFetcher.java from files_scope (its only consumer)"
    snapshot_files_scope_removed:
      - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java
    snapshot_acceptance_removed:
      - "A Bluesky actor identifier containing URL-reserved characters cannot truncate or extend the XRPC query: a named test (today buildUri appends ?actor= raw while the cursor leg IS encoded)"
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
