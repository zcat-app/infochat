---
id: M1-094
title: "Fetcher failure ladder (D42)"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/SourceRepository.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerFailureLadderIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerFailureLadderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java
  - infochat-collector/src/test/resources/application.properties
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI or schema changes; consecutive_failures, last_fetch_at, last_success_at, status columns already exist on the source table (V6)
  - any Flyway migration — all required schema columns already exist
  - infochat-messaging-adapter/** — no adapter changes
  - any change to individual Fetcher implementations (RssFetcher, BlueskyFetcher, etc.) — the failure ladder lives in FetchScheduler, not in fetchers
  - any change to FetchSchedulerIT.java — existing tests pass unchanged; new failure-ladder tests go in the new IT file
  - any change to StreamSource or StreamSourceSupervisor — D42 is the Fetcher mirror; D38 per-relay degradation is M3 scope
  - any modification to EmbeddingWorker, TaggerWorker, Stage1Worker, Stage2Worker, or any test in infochat-collector/src/test/java outside the fetch/ package
  - any change to SourceDisableCommandHandler — /source-disable already works; only /source-enable needs the counter reset
acceptance:
  - "FetchScheduler.tickOnce increments source.consecutive_failures on a Fetcher exception and resets it to 0 on success"
  - "FetchScheduler.tickOnce updates source.last_fetch_at on every tick (success or failure) and source.last_success_at on success only"
  - "When source.consecutive_failures reaches the profile-driven threshold (infochat.fetch.failure-threshold, e.g. 5), FetchScheduler transitions source.status from 'active' to 'failed'"
  - "A source with status='failed' is excluded from the scheduler's active-source enumeration — the scheduler does not attempt to fetch it"
  - "On threshold crossing, a throttled admin notification fires via ThrottledAdminNotifier with the error class, source id, and consecutive failure count"
  - "No immediate same-tick retry — a failure increments the counter and the source is skipped until the next scheduled tick for its kind"
  - "SourceEnableCommandHandler resets source.consecutive_failures to 0 when transitioning a source from 'failed' to 'active'"
  - "FetchSchedulerFailureLadderIT.consecutiveFailures_transitionsToFailed passes — seeds an active source, forces N consecutive Fetcher failures via a test double, asserts source.status='failed' and consecutive_failures=N after the Nth tick"
  - "FetchSchedulerFailureLadderIT.failedSourceSkippedByScheduler passes — a source with status='failed' is not in the active-source enumeration; the test Fetcher is never called for it"
  - "FetchSchedulerFailureLadderIT.successResetsCounter passes — a source with consecutive_failures=3 (below threshold) succeeds on the next tick; consecutive_failures resets to 0"
  - "FetchSchedulerFailureLadderIT.thresholdCrossing_firesAdminNotification passes — on the Nth failure (threshold crossing), a throttled admin notification is emitted; failures before the threshold do not fire notifications"
  - "FetchSchedulerFailureLadderTest.lastFetchAtUpdatedOnEveryTick passes — last_fetch_at is set on both success and failure ticks"
  - "FetchSchedulerFailureLadderTest.lastSuccessAtUpdatedOnlyOnSuccess passes — last_success_at is set on success, unchanged on failure"
  - "SourceEnableCommandHandlerTest.reEnableResetsFailureCounter passes — /source-enable on a failed source resets consecutive_failures to 0 and transitions status to 'active'"
  - "All pre-existing FetchSchedulerIT test methods pass unchanged"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerFailureLadderIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerFailureLadderTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java (add reEnableResetsFailureCounter test; authorization: counter-reset is new behavior on /source-enable)
  preserves:
    - all tests currently green on main
    - FetchSchedulerIT existing tests pass unchanged
spec_refs:
  - docs/spec/decisions.md §Decisions log
  - docs/spec/schema.md §Sources and tags
  - docs/spec/security.md §Failure handling
decision_refs:
  - D42
  - D38
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-094: Fetcher failure ladder (D42)

## Context

D42 commits to a per-source failure escalation ladder for HTTP-shaped
sources: consecutive failures increment a counter; after N consecutive
failures the source transitions to `status='failed'` and the scheduler
stops fetching it; an admin notification fires; recovery requires an
explicit `/source-enable`. This is the Fetcher mirror of D38's
per-relay degradation for StreamSource.

The schema columns (`consecutive_failures`, `last_fetch_at`,
`last_success_at`, `status`) already exist on the `source` table
(V6). FetchScheduler currently logs failures and keeps ticking with
no source-row updates — the comment at lines 71–79 explicitly defers
this to D42 work. `/source-enable` exists (M1-053) but the
failure-ladder trigger path that would flip `status='failed'` does
not.

## Acceptance

**Failure counting.** FetchScheduler increments
`source.consecutive_failures` on every Fetcher exception and resets
it to 0 on success. `source.last_fetch_at` updates on every tick
(success or failure). `source.last_success_at` updates on success
only.

**Threshold transition.** When `consecutive_failures` reaches the
profile-driven threshold (`infochat.fetch.failure-threshold`), the
source transitions `active → failed`. A throttled admin notification
fires via `ThrottledAdminNotifier` with the error class, source id,
and failure count. No immediate same-tick retry — the source is
skipped until the next tick, and once failed, skipped indefinitely.

**Scheduler exclusion.** The active-source enumeration query filters
`status='active'` (it already does — `schema.md` §Sources and tags:
"The fetcher / StreamSource scheduler selects rows where
`status = 'active'` AND `deleted_at IS NULL`"). A failed source is
mechanically excluded.

**Admin recovery.** `/source-enable` (M1-053) resets
`consecutive_failures` to 0 when transitioning a source from
`failed` to `active`. This prevents the source from immediately
re-tripping the threshold on the next failure.

## Out-of-scope

- **Schema changes** — all required columns exist in V6. No
  migration needed.
- **Individual Fetcher implementations** — the failure ladder lives
  in FetchScheduler, not in the fetchers. RssFetcher, BlueskyFetcher,
  etc. are unchanged.
- **StreamSource / Nostr relay degradation** — D38's per-relay
  failure policy is M3 scope. D42 applies to polled Fetcher sources
  only.
- **FetchSchedulerIT existing tests** — pass unchanged. New
  failure-ladder tests go in a separate file.
- **SourceDisableCommandHandler** — `/source-disable` works
  independently of the failure ladder; no change needed.
- **M1-042 log redaction** — separate ticket.

## Notes

- **Failure threshold config.** Profile-driven: the value lives in
  `application.properties` under `infochat.fetch.failure-threshold`
  (or profile-specific override). Design notes give the exact value.
  Tests assert the behavioral boundary (N-1 failures = still active,
  Nth failure = failed), not a specific number.
- **ThrottledAdminNotifier wiring.** Same pattern as M1-081a's
  re-eval notifications. The error_class for the notification should
  identify the failure type (e.g. `fetch_failure_ladder`) so the
  admin notifier coalesces by `(channel, error_class)`.
- **SourceRepository.** If a `SourceRepository` or equivalent DAO
  does not exist, this ticket introduces one in
  `collector/fetch/SourceRepository.java` for the
  `incrementFailures` / `recordSuccess` / `transitionToFailed`
  operations. If one already exists, the methods are added there.
  Check at start time.
- **Race safety.** Two concurrent ticks for the same source are
  impossible (D41: exactly one Collector), so no CAS/optimistic-lock
  is needed for the counter increment. The UPDATE is a simple
  `SET consecutive_failures = consecutive_failures + 1`.
- **Adjacent code patterns.** FetchScheduler's existing
  `tickOnce(SourceRow)` method is the modification target. The
  existing WARN log on failure remains; the counter increment and
  status transition are additions wrapping the same catch block.
- **Design reference:** `docs/design/01-architecture.md` §1.3
  (fetcher flow), `docs/design/02-schema.md` §2.3.1 (source table).
