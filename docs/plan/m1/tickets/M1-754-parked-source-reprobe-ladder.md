---
id: M1-754
title: "Parked-source re-probe ladder + park-reason discriminator + recurring parked-set signal"
status: done
created: 2026-08-03
last_updated: 2026-08-03
blocked_by:
  - M1-752
files_budget: 14
files_scope:
  - infochat-core/src/main/resources/db/migration/V*__source_park_reason.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/**
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/**
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerReprobeLadderIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ParkedSetSummaryIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ParkReasonWriteGuardIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ReprobeSelectionGuardIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ReprobeBudgetNoRefillOnRestoreIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ReprobeRestoreCompareAndSwapIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerUpgradeIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerReprobeExclusionIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/CycleCapReprobeExclusionIT.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/**
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/**
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/**
  - docs/spec/security.md
  - docs/design/02-schema.md
  - docs/design/01-architecture.md
  - docs/design/04-security.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    asset_config re-probe. D42 as amended explicitly excludes asset
    feeds from the re-probe rung (`architecture.md` §Ingest SPIs);
    a diff touching `AssetSnapshotFetcher` or `asset_config` has left
    scope.
  - >-
    Changing D38's relay-ladder behaviour. The cycle-cap park stays
    terminal; the ONLY permitted edit to `NostrStreamSource` is
    recording the park reason on the existing UPDATE at the cycle-cap
    write site.
  - >-
    Changing `infochat.fetch.failure-threshold` or any existing ladder
    semantics (crossing-tick notifyOnce, per-kind poll cadence). The
    re-probe rung is additive; `FetchSchedulerFailureLadderIT`'s pinned
    behaviour must not change.
  - >-
    The M1-753 parse-cap defect. A parser rejection parks a source with
    reason fetch-failure like any other fetch failure; fixing the
    rejection itself is M1-753.
  - >-
    New user-facing commands or command output changes beyond the
    `/source-enable` reset semantics (no `/source-status`, no listing
    changes).
  - >-
    A blanket `GRANT UPDATE ON source TO infochat_provider`, or any
    grant that leaves the identity columns (`kind`, `identifier`,
    `display_name`, `category`, `added_by`) writable by the Provider
    role. The V31 grant is extended column-by-column or not at all —
    this is the shortest path to a green build and the exact control
    V31 exists to hold (`security.md` §DB roles).
acceptance:
  - >-
    A Flyway migration `V<next>__source_park_reason.sql` adds a
    park-reason discriminator to `source` (closed set per `schema.md`
    §Sources and tags as amended by M1-752: fetch-failure /
    unknown-rate / stream-cycle-cap; nullable, null when not parked)
    and applies cleanly on a fresh DB. The migration does NOT backfill
    pre-existing `status='failed'` rows to any reason — they stay NULL
    and therefore manual-only (D42 property (c); recovering the
    already-parked corpus is an operator `/source-enable`, never a
    migration side effect). All three park writers record their reason
    in the same statement that sets `status='failed'` (no two-step
    write): the D42 ladder (`SourceRepository.recordFailure`),
    `PerSourceUnknownTracker`, and the `NostrStreamSource` cycle-cap
    write.
  - >-
    Park-reason write is GUARDED, not unconditional (D42 property (a),
    redteam finding 1). In `RECORD_FAILURE_SQL` the reason term sits
    inside the SAME `CASE ... WHEN consecutive_failures + 1 >= ? AND
    status = 'active'` guard that flips the status — NOT alongside the
    deliberately-unconditional `consecutive_failures` increment, which
    fires even against an already-`failed` row and would otherwise
    relabel a row `PerSourceUnknownTracker` parked moments earlier.
    Named test seeds a row already parked with reason `unknown-rate`,
    runs `recordFailure` against it, and asserts the reason is STILL
    `unknown-rate` — the write-after-park case the static-seed
    exclusion tests below cannot reach.
  - >-
    Restore write is COMPARE-AND-SWAP, not blind (D42 property (e),
    redteam r3 finding). Selection and restore are separated by a
    network probe, and the writers that can invalidate eligibility in
    that window run concurrently — the UNKNOWN-rate evaluator is a
    separate scheduled job and `/remove-source` runs in the Provider
    process, so D41's single-Collector topology serializes neither.
    The restoring UPDATE therefore repeats the full eligibility
    predicate in its own WHERE (`status='failed'` AND the same
    re-probe-eligible `park_reason` AND `deleted_at IS NULL` AND under
    cap); zero rows updated is a no-op that leaves the park intact, is
    not an error, and writes no audit row and no RECOVERED
    notification. Two named test legs, both driving a concurrent write
    between selection and restore: (a) an UNKNOWN-rate upgrade lands
    during the probe → the restore no-ops and the row stays parked
    `unknown-rate`; (b) `/remove-source` sets `deleted_at` during the
    probe → the restore no-ops and the row is not revived.
  - >-
    The probe's PAYLOAD is gated on the CAS result (redteam r4
    finding). Ordering on this path is fetch → CAS → persist+enqueue
    ONLY if the CAS updated a row; on a no-op the fetched batch is
    discarded and nothing reaches `PostPersister` or
    `EvalQueueProducer`. This deliberately inverts the active tick's
    persist-before-emit outbox discipline (FetchScheduler:67-70) for
    the re-probe path only — on the active path the source is already
    authorized, whereas here authorization is precisely what the CAS
    is still deciding. Both test legs above extend to assert zero
    posts persisted and zero emitted when the CAS no-ops.
  - >-
    The V31 column-scoped Provider grant is EXTENDED, never widened
    wholesale (redteam r4 finding). The `/source-enable` reset item
    below makes that command — a Provider-side write — touch the new
    park-reason and re-probe-state columns, which V31's five-column
    `GRANT UPDATE (status, consecutive_failures, deleted_at,
    deleted_by, bootstrap_tags) ON source TO infochat_provider` does
    not cover; without a grant change Postgres rejects the UPDATE with
    42501. The migration therefore names the new columns explicitly in
    an extended column-scoped grant, and `docs/spec/security.md` §DB
    roles' enumeration is updated in the SAME ticket so the spec's
    closed list stays true. The identity columns (`kind`,
    `identifier`, `display_name`, `category`, `added_by`) stay
    revoked. Named test asserts the Provider role still CANNOT update
    `source.identifier` (the anti-repoint property V31 exists for).
  - >-
    Manual-only UPGRADE path (D42 property (b), redteam r2 finding).
    `PerSourceUnknownTracker` must be able to claim a row the D42
    ladder already parked: BOTH its candidate selection
    (`PerSourceUnknownTracker:114`, today `WHERE s.status = 'active'`)
    and its UPDATE (`:148`, today `AND status = 'active'`) widen to
    include rows parked with a re-probe-eligible reason, upgrading
    them to `unknown-rate`. Without this the control is a silent
    no-op against any source that failed its way into a
    `fetch-failure` park first, and the re-probe ladder auto-readmits
    the exact feed the control exists to stop. Named test: seed a row
    parked `fetch-failure`, drive the UNKNOWN rate above threshold,
    assert the reason upgrades to `unknown-rate`, the source-disabled
    event and admin notification still fire, and the row is
    thereafter never selected by the re-probe path. The reverse
    direction stays forbidden (a `fetch-failure` write never
    overwrites `unknown-rate` — the guarded-write test above).
  - >-
    Re-probe budget does NOT refill on restore (redteam r2 finding,
    the flap-forever leg). Clearing the cap counter is gated on the
    restored source staying healthy for a profile-driven
    sustained-success window, not on the restore itself; a source that
    re-parks inside that window resumes from its existing count. Named
    test: park → probe → restore → immediately re-park, repeated, and
    assert the source reaches the terminal cap rather than cycling
    indefinitely. Note this constrains what the restore UPDATE may
    reset — `consecutive_failures` yes, the re-probe cap counter no.
  - >-
    Fail-closed selection (D42 property (c), redteam finding 2): the
    re-probe predicate matches ONLY the explicit `fetch-failure`
    reason. A parked row whose reason is NULL or unrecognized is never
    selected. The predicate must not be written as a negation
    (`park_reason IS DISTINCT FROM 'unknown-rate'` or similar), which
    would make every future reason value re-probe-eligible by default.
    Named test asserts zero probes for a NULL-reason parked row.
  - >-
    Soft-delete exclusion (D42 property (d), redteam finding 4): the
    re-probe selection carries `deleted_at IS NULL`, matching
    FetchScheduler:456/:717/:751. A source the admin `/remove-source`d
    while it sat at `status='failed'` is never probed and never
    automatically revived. Named test.
  - >-
    Audit coverage of the automatic transition (redteam finding 3):
    each automatic `failed → active` restore writes an `audit_log` row
    in the SAME transaction as the UPDATE, under a job actor id
    (the `RE_EVAL_RELEASED` / `actorContactId("re_eval_job")` pattern
    at ReEvaluationJob:540-541), with a new `AuditAction` value. The
    throttled RECOVERED notification does not substitute for it. Named
    test asserts the row exists and that a rolled-back restore leaves
    no orphan row.
  - >-
    Re-probe ladder: a source parked with reason fetch-failure is
    re-probed on exponential backoff (first probe after hours, capped;
    profile-driven values recorded in design notes) on a scheduling
    path separate from the active fetch enumeration, so re-probes
    cannot delay healthy sources. First successful re-probe restores
    `status='active'`, zeroes `consecutive_failures`, clears the park
    reason, and fires a RECOVERED notifyOnce keyed on the source UUID.
    It does NOT clear the absolute re-probe cap counter — that is
    gated on the sustained-success window (item below). Named IT:
    FetchSchedulerReprobeLadderIT.
  - >-
    Absolute re-probe cap: after the profile-driven cap the source is
    terminally parked — the re-probe path never selects it again — and
    only `/source-enable` revives it. Test leg proves no probe fires
    after the cap.
  - >-
    Exclusion legs (the security property of the M1-752 amendment):
    a source parked by `PerSourceUnknownTracker` is NEVER selected by
    the re-probe path
    (PerSourceUnknownTrackerReprobeExclusionIT), and a source parked
    by the `NostrStreamSource` cycle cap is NEVER selected
    (CycleCapReprobeExclusionIT) — each seeds a parked row with its
    reason and asserts zero probe attempts.
  - >-
    Recurring parked-set signal: a scheduled reader emits a recurring
    operator notification enumerating the parked set (source UUIDs,
    park reasons, parked-since; never the identifier URL — the M1-023
    INFO-LEAK rule), silent when the set is empty, cadence
    profile-driven. Covers ALL park reasons, including terminally
    parked sources. Named test: ParkedSetSummaryIT.
  - >-
    `/source-enable` clears the park reason and re-probe state in the
    same UPDATE that sets `status='active'` and
    `consecutive_failures=0`, so a re-enabled source starts a fresh
    ladder (both UPDATE sites in `SourceEnableCommandHandler`).
  - >-
    Every backoff/cadence/cap decision reads the injected
    `java.time.Clock` (engineering-rules §9); no inline `now()` or
    `Instant.now()` in decision logic. Pure park-timestamp record
    writes may stay on the DB clock, but any value read back to decide
    the next probe time uses the same clock that wrote it.
  - >-
    mvn verify green from the repo root.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerReprobeLadderIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerReprobeExclusionIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/CycleCapReprobeExclusionIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ParkedSetSummaryIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ParkReasonWriteGuardIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ReprobeSelectionGuardIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ReprobeBudgetNoRefillOnRestoreIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/ReprobeRestoreCompareAndSwapIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerUpgradeIT.java
  preserves:
    - >-
      FetchSchedulerFailureLadderIT — the existing ladder semantics
      (crossing-tick-only notifyOnce, threshold behaviour) are pinned
      and must not change.
    - >-
      AutoDisableStopBeforeNotifyIT — the UNKNOWN auto-disable
      stop-before-notify ordering is untouched.
    - all tests currently green on main
spec_refs:
  - docs/spec/decisions.md §Decisions log
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/schema.md §Sources and tags
  - docs/spec/security.md §Failure handling
decision_refs:
  - D42
  - D38
reviews:
  - round: 1
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 28
      added: 3372
      removed: 40
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-08-03
    verdict: CLEAN
    base: 184f0e3c
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-754-2026-08-03.md
    out_of_model_count: 0
    note: >-
      Redteam gate at /m1-tick run, ahead of review, against the uncommitted
      branch tip. CLEAN first pass: the adversary confirmed SSRF inheritance
      via the registered Fetcher SPI, positive-equality park_reason selection
      (manual-only and NULL-reason parks never probed), the CAS restore
      re-checking the full eligibility predicate with the probe payload gated
      on its result, the audit row riding the restore transaction, the
      INFO-LEAK posture of the summary and probe-failure logs, and the V75
      column-scoped grant leaving identity columns revoked. Re-confirmed CLEAN
      twice as test-only fixes landed: the *Test -> *IT renames and the
      CDI-client-proxy recorder fix, then the @AfterEach fixture-leak fix and
      the two SourceEnableParkResetIT fixes (self-contained @TestProfile/stub
      replacing a cross-test-class reference that loaded production types in
      the application classloader; audit_log cleanup ahead of the FK-blocked
      users DELETE). The trigger-disable in that cleanup was raised for
      adversarial scrutiny and cleared as the established pattern at 53
      existing sites, confined to an ephemeral test DB, fail-loud not
      fail-silent; hardening it is a repo-wide follow-up, not this ticket. No
      production file changed after the snapshot — verified by mtime.
clarity_check:
  date: 2026-08-03
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
outline_file: target/m1-tick-outline-M1-754.md
---

# M1-754: parked-source re-probe ladder, park-reason discriminator, recurring parked-set signal

## Context

Implements D42 as amended by M1-752 (see that ticket's §Outcome and the
amended row in `docs/spec/decisions.md`). The policy work is done; this
ticket is behaviour only.

Three commitments, all from the amendment:

1. **Re-probe ladder** — a source parked by consecutive fetch failures
   is automatically re-probed on exponential backoff, restored on first
   success, terminally parked after an absolute cap.
2. **Park-reason discriminator** — with four normative properties from
   D42 (guarded write, manual-only precedence, fail-closed on absent
   reason, `deleted_at IS NULL` in the selection). These came from the
   M1-752 redteam audit and are the security core of this ticket, not
   polish: three of the four close a path by which an
   adversary-controlled feed regains automatic recovery.
   `status='failed'` has THREE writers
   (`SourceRepository.recordFailure` via the D42 ladder,
   `PerSourceUnknownTracker:148`, `NostrStreamSource:558`), and only
   the first may auto-recover. The UNKNOWN-rate park is a
   quarantine-exhaustion security control (`security.md` §Failure
   handling); re-probing it would hand an adversary-controlled feed an
   automatic way back in. Eligibility is decided on the recorded
   reason, never on bare `status='failed'`.
3. **Recurring parked-set signal** — the missing half of the one-shot
   crossing-tick notification: a recurring operator-visible statement
   of the whole parked set, silent when empty. This is what keeps
   terminally-parked and manual-only sources visible; on the live
   deployment a parked source went unnoticed for 27 days.

## Census

Every writer of `source.status = 'failed'`.

The park-reason discriminator is only sound if EVERY writer of the
terminal status records a reason; a missed writer leaves rows with a
NULL reason, which is fail-closed (never re-probed) but silently
un-recoverable. Re-runnable enumeration:

```
grep -rn "status = 'failed'\|status='failed'" --include=*.java \
  infochat-collector/src/main infochat-provider/src/main
```

| site | mechanism | reason to record | recovery |
|---|---|---|---|
| `SourceRepository` `RECORD_FAILURE_SQL` (via `FetchScheduler:520`) | D42 consecutive-fetch-failure ladder | `fetch-failure` | automatic re-probe, then cap |
| `PerSourceUnknownTracker:148` | Stage 2 UNKNOWN-rate auto-disable | `unknown-rate` | manual only; also UPGRADES an existing `fetch-failure` row |
| `NostrStreamSource:558` | D38 all-relays-bad cycle cap | `stream-cycle-cap` | manual only |

Two grep hits are NOT `source` rows and are out of scope:
`AssetSnapshotFetcher:289` writes `asset_config.status` (excluded by
D42's amendment and by this ticket's `out_of_scope`), and
`NitterFetcher:69` only mentions the status in a comment.

The upgrade path is required only for `PerSourceUnknownTracker`:
`FetchScheduler` never enumerates stream kinds (`FetchScheduler:276-281`
— a source is either polled via a `Fetcher` or event-driven via a
`StreamSource`, never both), so a nostr row can never carry a
`fetch-failure` reason and the cycle-cap writer has nothing to upgrade
from.

## Design constraints

- The re-probe path must not run inside `enumerateActiveSources` — it
  is a separate selection (`status='failed' AND deleted_at IS NULL AND
  park_reason = 'fetch-failure' AND next probe due AND under cap`) so
  healthy-source scheduling latency is unaffected. Note `deleted_at IS
  NULL` and the positive `park_reason` equality: both are normative
  (D42 properties (c) and (d)), not stylistic.
- The probe itself MUST go through the existing `Fetcher` SPI
  (`Fetcher.fetch(dispatchKey, identifier)`), which is how it inherits
  the SSRF allowlist — each Fetcher implementation constructs its own
  `SsrfGuardedHttpClient` internally, so reusing the SPI carries the
  IP blocklist and DNS-rebind checks for free. A bespoke HTTP client
  on the new scheduling path would silently bypass D20.
- Backoff state (probe count, next-eligible time) needs columns or a
  derivation; concrete shape is the implementer's choice, recorded in
  `docs/design/02-schema.md`. Whatever the shape, `/source-enable`
  must reset it.
- `ThrottledAdminNotifier.notifyOnce` is already keyed per source UUID
  for the parked notification; the RECOVERED and parked-set-summary
  notifications follow the same key discipline (never the identifier
  URL — M1-023 INFO-LEAK).
- Profile-driven values (first-probe delay, backoff factor/ceiling,
  absolute cap, summary cadence) land in
  `infochat-collector/src/main/resources/application.properties` with
  per-profile overrides as needed, documented in design notes.
