---
id: M1-153
title: "Collector worker hygiene (dead semaphores, interrupt, backoff Random, dup counter, timeouts)"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the reeval-job filter (M1-128) and config keys (M1-122)
  - the fetcher URL-encoding (M1-149)
acceptance:
  - "The dead Semaphore in TaggerWorker and EntityExtractorWorker is removed; the batch-per-tick + serial-loop concurrency bound is documented (the semaphore never had >1 acquirer)"
  - "acquireUninterruptibly() in the workers becomes acquire() + InterruptedException handling that restores the interrupt flag"
  - "NostrRelayConnection.backoffDelay no longer mixes a static method with an instance Random; AssetSnapshotFetcher's D42 failure-counter is left intentionally un-unified with SourceRepository's, documented by a brief comment in AssetSnapshotFetcher.recordFailure recording why the two ladders are NOT commonized (different tables source/asset_config, keys UUID vs (asset, sub_verb), failure-timestamp columns, caller-driven vs inline notify, and the assets-are-not-posts domain boundary); collector HttpClient instances get connect timeouts"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Pipelines
  - docs/spec/llm.md §Bounded concurrency and observability
decision_refs: []
revisions:
  - date: 2026-06-02
    reason: >-
      premise-fail refine (caught at /m1-tick start grounding, before any code).
      Acceptance item #3's clause "AssetSnapshotFetcher shares the SourceRepository
      failure-counter instead of duplicating it" is unimplementable as literally
      written and rests on a wrong-abstraction premise. SourceRepository keys the
      D42 ladder on source.id (UUID); AssetSnapshotFetcher keys it on
      (asset, sub_verb) — there is no source.id for an asset pair, so the literal
      "use SourceRepository's counter" cannot compile. The two ladders are
      incidental, not essential, duplication: different tables (source vs
      asset_config), different failure-timestamp columns (source bumps
      last_fetch_at every tick + last_success_at on success, no last_failure_at;
      asset_config bumps last_failure_at on failure + last_success_at on success,
      no per-tick fetch timestamp), and different notify control-flow
      (SourceRepository.recordFailure returns FailureOutcome and the caller fires
      notifyOnce; AssetSnapshotFetcher.recordFailure fires notifyOnce inline).
      Unifying would force a table/column/notify-parameterized helper more complex
      than the two concrete ~30-line methods, plus a cross-domain assets->fetch
      coupling that contradicts the spec's "assets are not posts" separation. A
      faithful extraction also pushes the file count to 8 production files, leaving
      no room under files_budget:8 for the test_plan-mandated new tests. Reframed
      #3b to record the deliberate non-sharing decision (a brief D42 cross-ref
      comment) instead of forcing the unification. C-ASSETFETCHER-DUP is a low-
      confidence FIX-LOW tidy suggestion (deepseek coll F2); documenting why we do
      NOT commonize closes the finding honestly.
    prior_acceptance:
      - "The dead Semaphore in TaggerWorker and EntityExtractorWorker is removed; the batch-per-tick + serial-loop concurrency bound is documented (the semaphore never had >1 acquirer)"
      - "acquireUninterruptibly() in the workers becomes acquire() + InterruptedException handling that restores the interrupt flag"
      - "NostrRelayConnection.backoffDelay no longer mixes a static method with an instance Random; AssetSnapshotFetcher shares the SourceRepository failure-counter instead of duplicating it; collector HttpClient instances get connect timeouts"
      - "mvn -B clean verify from the repo root exits 0"
escalations:
  - date: 2026-06-02
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — caught at /m1-tick start grounding before any implementation.
      Acceptance #3's "AssetSnapshotFetcher shares the SourceRepository
      failure-counter instead of duplicating it" is unimplementable as
      literally written (SourceRepository keys on source.id UUID; an asset
      pair keyed on (asset, sub_verb) has no source.id) and rests on a
      wrong-abstraction premise — the two D42 ladders are incidental, not
      essential, duplication across deliberately-separate domains.
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 162
      removed: 53
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-153: Collector worker hygiene

## Context

Module-scoped `infochat-collector` hygiene bundle: (C-DEAD-SEMAPHORE) the
`Semaphore(maxConcurrency)` in `TaggerWorker`/`EntityExtractorWorker` is dead —
`enumeratePending(maxConcurrency)` + a serial loop means it never has >1
acquirer, misleading readers about the concurrency bound; (C-ACQUIRE-INT)
`acquireUninterruptibly()` swallows the interrupt, hindering shutdown;
(C-NOSTR-BACKOFF-RANDOM) static `backoffDelay` uses an instance `Random`;
(C-ASSETFETCHER-DUP) `AssetSnapshotFetcher` *appears* to duplicate the
failure-counter; (C-HTTPCLIENT-NOTIMEOUT) collector `HttpClient` lacks
connect timeouts.

**C-ASSETFETCHER-DUP resolution (premise-fail refine, 2026-06-02).** The
"share the SourceRepository failure-counter" disposition was reframed after
grounding: `SourceRepository` keys the D42 ladder on `source.id` (UUID) while
`AssetSnapshotFetcher` keys it on `(asset, sub_verb)`, so literally reusing
`SourceRepository.recordFailure` is impossible. The two ladders are *incidental*
(not *essential*) duplication — different tables, keys, failure-timestamp
columns (`last_fetch_at` vs `last_failure_at`), and notify control-flow
(caller-driven `FailureOutcome` vs inline `notifyOnce`) — and live in
deliberately-separate domains (spec §Asset commands: "assets are not posts").
Unifying them would be the wrong abstraction (a table/column/notify-
parameterized helper more complex than the two concrete methods, plus a cross-
domain `assets`→`fetch` coupling). The fix is therefore to DOCUMENT the
deliberate non-sharing with a brief D42 cross-reference comment in
`AssetSnapshotFetcher.recordFailure`, not to commonize. See the `revisions:`
entry for the full rationale.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-DEAD-SEMAPHORE, §C-ACQUIRE-INT,
  §C-NOSTR-BACKOFF-RANDOM, §C-ASSETFETCHER-DUP, §C-HTTPCLIENT-NOTIMEOUT;
  `opus-47-full-handout.md` §F-SIM-05/08, F-MAINT-64/78, F-PERF-11; `opus-47-only-handout.md` §Si1.
- Defer the virtual-thread fan-out alternative for the workers — it changes throughput characteristics.
