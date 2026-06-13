---
id: M1-341
title: "FetchScheduler: exclude stream-shaped kinds + filter due kinds at SQL"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Fetcher/StreamSource architectural split itself — unchanged; this ticket only stops the polled-fetch scheduler from enumerating/warning about stream kinds.
  - The genuine orphan-warning purpose (a polled kind shipped in bootstrap-sources.json with no Fetcher impl) — preserved; only the stream-kind false alarm is removed.
  - NostrStreamSource.Registrar (drives nostr) — untouched.
acceptance:
  - "FetchScheduler no longer logs 'No fetcher registered for source kind nostr, skipping' for nostr (a StreamSource, deliberately never bound to a Fetcher). Stream-shaped kinds are excluded from the scheduler's enumeration so the orphan warning fires only for genuinely-missing polled-fetch bindings, restoring that signal's meaning (an operator triaging logs no longer chases the expected stream-kind case as a misconfiguration). The closed v1 stream-kind set (nostr) is named explicitly (a small Set or a SQL kind <> ALL(?) exclusion)."
  - "enumerateActiveSources is filtered by the due-kind set at SQL rather than scanning every active source of every kind on every heartbeat and filtering in Java: a new enumerateActiveSourcesByKinds(Set<String>) selects WHERE status='active' AND deleted_at IS NULL AND kind IN (...), bound to kindsToTick. The result set scales by due-source count, not total active source count. The unfiltered enumerateActiveSources overload is kept for the IT that references it."
  - "A test pins both: with an active nostr source present, a heartbeat where nostr is due-but-unbound produces NO orphan warning for nostr; and a tick where only one polled kind is due enumerates only that kind's rows (the due-kind SQL filter is exercised). A genuinely-missing polled Fetcher binding still warns once."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch (stream-kind exclusion + due-kind filter cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-341: FetchScheduler — exclude stream kinds, filter due kinds at SQL

## Context

Two deep-review v5.5 findings on `FetchScheduler.enumerateActiveSources`,
fixed together because they touch the same enumeration:

- **opus-48 `06-module-infochat-collector.md` F3** — `nostr` (a StreamSource,
  never a Fetcher) is enumerated by the polled-fetch scheduler and logged as a
  "No fetcher registered" orphan, a misleading operational warning for a
  deliberately-absent binding. **Verified at source 2026-06-14:**
  `enumerateActiveSources` (FetchScheduler.java:470) selects every active kind;
  the orphan-warn fires at lines 208-212 for any kind not in `fetchersByKind`,
  which includes `nostr`. `docs/spec/architecture.md` §Ingest SPIs makes the
  Fetcher/StreamSource split disjoint ("Sources MUST NOT straddle"), so a genuine
  missing-Fetcher gap is now indistinguishable from the expected stream-kind case.

- **opus-47 `06-module-infochat-collector.md` F4** — `enumerateActiveSources`
  scans every active row of every kind on every heartbeat even when only one
  kind's interval elapsed, then filters in Java. **Verified at source
  2026-06-14:** same method; the SQL has no kind filter and `onTick` filters with
  `kindsToTick.contains(row.kind())` in the loop. Negligible at v1 scale but
  scales by total source count rather than due-source count.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- A SQL `kind IN (...)` filter bound to the due-kind set both excludes stream
  kinds (they never appear in `fetchersByKind`, so they are never due) and reduces
  transfer; it also opens the door to a `(status, kind)` index later without
  taking it now.
