---
id: M1-095
title: "StreamSourceSupervisor lifecycle and drain framework"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceSupervisor.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceRegistration.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceDrainHandle.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/StreamSourceSupervisorTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/StreamSourceSupervisorIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/StreamSource.java — the SPI interface is not modified
  - infochat-provider/** — no provider changes
  - infochat-messaging-adapter/** — no adapter changes
  - any Nostr implementation code — M1-096..M1-101
  - any change to FetchScheduler — polled Fetcher dispatch is separate
  - any change to BootstrapLoader beyond wiring the supervisor registration call
  - SSRF guard integration for wss:// — M1-101
acceptance:
  - "StreamSourceSupervisor is a CDI bean at @Priority(450) that manages StreamSource registrations"
  - "StreamSourceSupervisor.register(sourceId, StreamSource) starts the StreamSource's background worker asynchronously — a relay unreachable at boot does not fail Collector startup or the readiness probe"
  - "Collector readiness probe goes healthy when the supervisor has accepted the StreamSource registration, not when every relay is connected"
  - "StreamSourceSupervisor.drainAll(timeout) on graceful shutdown signals all registered StreamSources to flush in-flight events to the outbox within the profile-driven hard timeout"
  - "Events not drained within the hard timeout are dropped; a per-source 'events lost on shutdown' counter is exposed for operator monitoring"
  - "StreamSourceSupervisor.stop(sourceId) stops a single StreamSource (used when source.status transitions to 'failed')"
  - "StreamSourceSupervisorTest.registerStartsBackgroundWorker passes — registering a test StreamSource starts it asynchronously; the supervisor is ready before the StreamSource's start() completes"
  - "StreamSourceSupervisorTest.drainFlushesWithinTimeout passes — a test StreamSource with buffered events flushes them on drain; the supervisor waits up to the timeout"
  - "StreamSourceSupervisorTest.drainTimeoutDropsInFlight passes — a test StreamSource that does not complete drain within the timeout has its events dropped; the lost-events counter increments"
  - "StreamSourceSupervisorTest.stopSingleSource passes — stopping one StreamSource leaves others running"
  - "StreamSourceSupervisorIT.supervisorIntegratesWithCollectorLifecycle passes — the supervisor starts and drains as part of the Collector's CDI lifecycle"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/StreamSourceSupervisorTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/StreamSourceSupervisorIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D38
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-095: StreamSourceSupervisor lifecycle and drain framework

## Context

The `StreamSource` SPI exists (`core/ingest/StreamSource.java`) but has
no runtime supervisor. `BootstrapLoader` references a future
`StreamSourceSupervisor` at `@Priority(450)`. This ticket creates the
supervisor that manages StreamSource registrations, async startup, and
graceful drain.

The spec commits to async startup (`architecture.md` §Ingest SPIs):
"A relay unreachable at boot does not fail Collector startup or the
readiness probe — it surfaces as the ordinary per-relay degradation
path." And drain on shutdown: "On graceful shutdown the StreamSource
implementation MUST aggressively flush in-flight events to the outbox
before acknowledging the shutdown signal."

## Acceptance

See frontmatter. The supervisor is the lifecycle container; it does not
implement any specific StreamSource (Nostr is M1-096). It manages
registration, async start, per-source stop, and coordinated drain on
shutdown.

## Out-of-scope

- StreamSource SPI — unchanged.
- Nostr implementation — M1-096 through M1-101.
- FetchScheduler — polled fetcher dispatch is separate.
- SSRF guard for wss:// — M1-101.

## Notes

- **Async startup pattern.** The supervisor's `register()` starts the
  StreamSource in a virtual thread (or `ExecutorService`). The
  supervisor tracks the registration as "started" immediately; the
  StreamSource's internal reconnect loop handles relay availability.
- **Drain protocol.** On Quarkus shutdown (`@PreDestroy` or
  `ShutdownHandler`), the supervisor calls each StreamSource's `stop()`
  and waits up to the configured timeout for all to complete. The
  timeout is profile-driven (`infochat.stream.drain-timeout`).
- **Lost-events counter.** A simple `AtomicLong` per source, exposed
  via a logging event or Micrometer counter (implementer's choice).
- **Adjacent code:** BootstrapLoader's `@Priority(450)` reference
  is the integration point — the supervisor registers after sources
  are loaded but before the readiness probe.
