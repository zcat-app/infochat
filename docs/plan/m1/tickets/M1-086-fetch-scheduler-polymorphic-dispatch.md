---
id: M1-086
title: "FetchScheduler polymorphic per-kind dispatch"
status: done
created: 2026-05-26
last_updated: 2026-05-26
clarity_check:
  date: 2026-05-26
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetcherKind.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFetcher.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — Fetcher SPI is not modified
  - infochat-provider/** — no provider changes
  - infochat-messaging-adapter/** — no adapter changes
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java — not modified
  - any new Fetcher implementation (Bluesky, Reddit, etc.) — those are M1-087..M1-091
  - D42 consecutive-failure wiring — not yet wired, out of scope for this refactoring
  - any Flyway migration
acceptance:
  - "FetchScheduler discovers all registered Fetcher CDI beans at startup and maps each to its source kind via a CDI qualifier or equivalent kind discriminator; no changes to the Fetcher interface in infochat-core"
  - "FetchScheduler enumerates all active sources (not just kind='rss') and dispatches each to the Fetcher registered for its source kind"
  - "Each source kind ticks at its own configured interval via `infochat.fetch.<kind>.interval` (existing: `infochat.fetch.rss.interval=5m`); a kind whose interval has not elapsed since its last tick is skipped"
  - "Sources whose kind has no registered Fetcher are skipped with a WARN log (not an error or crash)"
  - "RssFetcher gains only a kind discriminator annotation; its functional behavior, constructor, and public API are unchanged"
  - "SourceRow carries the source kind so the scheduler can dispatch without a second query"
  - "tickOnce(SourceRow) remains public for IT-callable deterministic ticking"
  - "FetchSchedulerIT.tickDispatchesSourceToFetcherMatchingKind passes"
  - "FetchSchedulerIT.tickSkipsSourceWithUnregisteredKind passes"
  - "All pre-existing FetchSchedulerIT test methods pass unchanged"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
    - FetchSchedulerIT existing test methods pass unchanged
    - RssFetcherTest passes unchanged
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D38
reviews:
  - round: 1
    date: 2026-05-26
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 274
      removed: 73
  - round: 2
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 300
      removed: 73
---

# M1-086: FetchScheduler polymorphic per-kind dispatch

## Context

FetchScheduler is currently hardcoded to RSS: it injects `RssFetcher`
directly and queries `WHERE kind = 'rss'`. The spec commits to
per-kind, profile-driven tick intervals (`architecture.md` §Ingest
SPIs). T3-B adds five new polled Fetcher implementations; all need the
scheduler to dispatch by source kind. This ticket refactors the
scheduler to be kind-agnostic so individual fetcher tickets (M1-087
through M1-091) can register via CDI and tick automatically.

## Acceptance

1. **Fetcher discovery.** The scheduler discovers all registered
   Fetcher CDI beans at startup and builds a kind → Fetcher mapping.
   The kind discriminator is a CDI qualifier or equivalent annotation
   on each Fetcher bean. The `Fetcher` interface in infochat-core is
   NOT modified.

2. **All-source enumeration.** The SQL query enumerates all active
   sources (`status = 'active' AND deleted_at IS NULL`), not just
   `kind = 'rss'`. SourceRow carries the kind field.

3. **Per-kind intervals.** Each kind ticks at its own configured
   interval via `infochat.fetch.<kind>.interval`. A kind whose
   interval has not elapsed since its last tick is skipped until the
   next heartbeat. Existing `infochat.fetch.rss.interval=5m` is
   unchanged.

4. **Unknown-kind safety.** Sources whose kind has no registered
   Fetcher are skipped with a WARN log. The scheduler does not crash
   on unknown kinds (bootstrap-sources.json already contains `bluesky`
   and `nostr` entries that have no Fetcher impl yet).

5. **RssFetcher annotation.** RssFetcher gains only a kind
   discriminator annotation (one line). Its functional behavior,
   constructor injection, and public API are unchanged.

6. **IT backward compatibility.** `tickOnce(SourceRow)` remains
   public. All existing FetchSchedulerIT methods pass. Two new IT
   methods verify kind-based dispatch and unknown-kind skip.

7. **Full suite.** `mvn verify` green.

## Out-of-scope

- **Fetcher SPI** (`infochat-core`) — not modified. The kind
  discriminator lives on concrete beans, not the interface.
- **RssFeedParser** — not modified.
- **New Fetcher implementations** — M1-087..M1-091 each add one.
- **D42 consecutive-failure wiring** — the scheduler's error handler
  remains "WARN-log and keep ticking." D42 integration is a separate
  ticket.
- **StreamSource dispatch** — StreamSourceSupervisor is T3-C's
  concern; this ticket only refactors polled Fetcher dispatch.

## Notes

- **Kind discriminator mechanism.** Options: (a) custom `@FetcherKind`
  CDI qualifier annotation (most explicit); (b) `@Named("rss")` from
  jakarta.inject (standard CDI, no new type); (c) a companion
  interface `KindedFetcher extends Fetcher` with a `kind()` method
  (avoids annotation on RssFetcher but introduces a second interface).
  The acceptance criteria describe behavior, not mechanism — the
  implementer picks whichever is cleanest. If a new annotation file is
  needed, it costs one files_budget slot (budget is 6, scope lists 3).
- **Per-kind heartbeat vs multiple @Scheduled.** The simplest model:
  one heartbeat timer at a base interval (e.g. 1 minute); each tick
  checks `now - lastTick[kind] >= interval[kind]`. Alternatively,
  Quarkus programmatic scheduling can register one timer per kind at
  startup. Either satisfies the acceptance criteria.
- **SourceRow expansion.** SourceRow currently has `(uuid, identifier,
  dispatchKey)`. Add `kind` (String) so the scheduler can route
  without a second query or a kind-per-row lookup.
- **Design reference:** `docs/design/01-architecture.md` §1.3.1
  (polled Fetcher flow), §1.6 (per-kind pagination caps and cadence).
- **Existing FetchSchedulerIT** tests RSS-only via QuarkusMock. New
  test methods can register a test-only Fetcher (inner class or
  `@Alternative`) for a non-RSS kind and seed a matching source row.

## Round 1 rework

1. **SCOPE-DRIFT-CHECK fix:** Add the two missing paths to `files_scope`
   in the ticket frontmatter: `FetcherKind.java` (new annotation file)
   and `application.properties` (heartbeat interval key). Ticket-authoring
   gap — the Notes section anticipated the annotation file.
2. **PARAMETER-CONTRACT-CHECK fix:** Add `@NonNull` to the `String`
   parameter of `FetcherKind.Literal(String value)`.
