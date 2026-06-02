---
id: M1-153
title: "Collector worker hygiene (dead semaphores, interrupt, backoff Random, dup counter, timeouts)"
status: pending
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
  - "NostrRelayConnection.backoffDelay no longer mixes a static method with an instance Random; AssetSnapshotFetcher shares the SourceRepository failure-counter instead of duplicating it; collector HttpClient instances get connect timeouts"
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-153: Collector worker hygiene

## Context

Module-scoped `infochat-collector` hygiene bundle: (C-DEAD-SEMAPHORE) the
`Semaphore(maxConcurrency)` in `TaggerWorker`/`EntityExtractorWorker` is dead —
`enumeratePending(maxConcurrency)` + a serial loop means it never has >1
acquirer, misleading readers about the concurrency bound; (C-ACQUIRE-INT)
`acquireUninterruptibly()` swallows the interrupt, hindering shutdown;
(C-NOSTR-BACKOFF-RANDOM) static `backoffDelay` uses an instance `Random`;
(C-ASSETFETCHER-DUP) `AssetSnapshotFetcher` duplicates the failure-counter;
(C-HTTPCLIENT-NOTIMEOUT) collector `HttpClient` lacks connect timeouts.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-DEAD-SEMAPHORE, §C-ACQUIRE-INT,
  §C-NOSTR-BACKOFF-RANDOM, §C-ASSETFETCHER-DUP, §C-HTTPCLIENT-NOTIMEOUT;
  `opus-47-full-handout.md` §F-SIM-05/08, F-MAINT-64/78, F-PERF-11; `opus-47-only-handout.md` §Si1.
- Defer the virtual-thread fan-out alternative for the workers — it changes throughput characteristics.
