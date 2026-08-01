# Scan-window fixture census (M1-602)

Status: ticket deliverable (acceptance item 1). Classifies every collector test
that seeds an absolute instant (`Instant.parse("20NN-...")` or a SQL
`'20NN-...'` timestamp literal) near a time-gated pickup path, as either an
**(A) live/latent time-bomb** (the seed feeds a `fetched_at >= now − Nd`-style
gate computed from the UNPINNED wall clock, so the test fails — or an assertion
goes silently vacuous — on a calendar date with no code change) or **(B)
benign**. The governing rule is `engineering-rules-verbatim.md` §9; the pin
pattern is M1-444 / M1-601 (`ReEvalVerdictNotifyIT`); the format precedent is
[`now-clock-audit.md`](now-clock-audit.md) (M1-447).

Method: the universe was derived mechanically (grep for `Instant.parse("20` →
65 files; plus SQL-literal `'20NN-NN-NN …'` seeds → 2 extra files; 14 of the 65
already pin the Clock via `Clock.fixed(`/`installMockForType(..., Clock.class)`
— 12 parse-file pins + 2 pin-only files). Every file was then opened and its
seed→gate path traced to the production query — proximity of a seed to a
`fetched_at` column proves nothing either way (`Stage2BenignNotifyScopeIT`
seeds `fetched_at` but only ever drives exact-key calls; `NostrSinceCursorIT`
drives a real windowed query but is date-robust by construction).

Today's reference date for "detonated": 2026-07-09; the eval-pipeline window is
32 days (`retention-days.post` 30 + `PARTITION_SCAN_SLACK` 2).

## Classification rules applied

- **(A)** — at least one test method's pass/fail, or a negative assertion's
  *meaningfulness*, depends on the seeded absolute instant vs a floor computed
  from unpinned wall-clock now. **Vacuous negatives are (A):** an "is not
  picked up" assertion whose subject aged out of the window passes even if the
  filter it exists to prove regresses — the suite stays green while coverage
  silently evaporates. That is a wall-clock dependence exactly as much as a
  red-on-boundary failure.
- **(B)** — the absolute instant is parser input or an expected value; lands in
  a column no exercised query floors on; is only used by direct/exact-key calls
  (`WHERE id = ? AND fetched_at = ?`, procedure-by-id); is an explicit `now`
  method argument; or is **deliberately below-floor with a loud fixture guard**
  (the date-robust pattern, below).

## (A) — the sweep worklist: 3 fixtures, all vacuous-negative bombs, 2 classes already detonated

| # | Fixture | Windowed path | Seed | Detonated | What went silently vacuous |
|---|---------|--------------|------|-----------|----------------------------|
| 1 | `eval/embedding/EmbeddingWorkerDimensionMismatchTest` | test 2 `repeatedMismatchDoesNotPropagateExceptionOutOfOnTick`: seeds a pickup-ready post (L134→L167), drives `onTick()` → `enumeratePending` (L140-144) | `FETCHED_AT = 2026-05-16T10:00Z` (L63) | **2026-06-17** | `onTick` enumerates nothing, the queued wrong-dimension stub responses are never consumed, `assertDoesNotThrow` passes without ever exercising the mismatch-containment path (M1-233 contract untested) |
| 2 | `eval/tagger/TaggerWorkerIT` | test 27.7 `quarantinedPostIsNotPickedUpAndTaggerDoneStaysFalse`: `enumeratePending(10)` (L207) + `assertFalse(foundQuarantined)` (L211) | `fetchedAt = 2026-05-15T13:30Z` (L261) | **2026-06-16** | the QUARANTINED post is excluded by the fetched_at floor instead of the `status` filter — the exclusion no longer proves quarantined posts are skipped |
| 3 | `eval/ready/ReadyPromoterIT` | `@Order(2)` `sameTransactionRollsBackBothUpdateAndNotify`: `onTick()` (L159) → `enumeratePending`; `@Order(3)` `quarantinedPostIsNotPromoted`: `enumeratePending(10)` (L185) | `FETCHED_AT = 2026-05-16T11:00Z` (L72) | **2026-06-17** | Order(2): `onTick` enumerates nothing, the throwing after-update hook never fires, "stays RAW + no NOTIFY" passes with the same-transaction rule untested. Order(3): quarantine exclusion decided by the floor, not the status filter |

Fix applied per fixture (acceptance item 2): pin the injected Clock in
`@BeforeEach` at `<seed> + 1h` via
`QuarkusMock.installMockForType(Clock.fixed(..., ZoneOffset.UTC), Clock.class)`
— the M1-444 / M1-601 pattern. Every existing assertion is kept byte-identical;
only the time seam changes.

Note on #3: `ReadyPromoterIT` already contained an in-method pin
(`@Order(8)`, L455, added by M1-597 for the `classifier_done` gate) — the file
was "pinned" by grep yet two OTHER methods hit the windowed query unpinned.
The `@BeforeEach` pin re-arms Orders 2 and 3; Order(8) re-installs its own pin
on top, unaffected.

## (B) — benign, by family (56 unpinned files; no edit)

### Direct-call eval fixtures (seeds land in `fetched_at` but only exact-key paths run)

Verified production shapes: `Stage1Worker.loadPost` and every
`Stage2VerdictHandler`/`processBatch`/`processOne` write-back use exact
`WHERE id = ? AND fetched_at = ?`; `Stage1Worker.loadStaleRawKeys` filters
`status='RAW' AND status_changed_at < now() - ?::INTERVAL` (fetched_at is only
an ORDER BY key, and the tests seed `status_changed_at` RELATIVE);
`approve_quarantine`/`reject_quarantine` (V53) key on the quarantine id;
`AdminReviewTtlJob.enumerateExpired` floors on `flagged_at <= cutoff` (the
tests seed `flagged_at` relative, `Instant.now()-48h`).

- `eval/stage1/`: `QuarantinePendingNotifyIT`, `Stage1MatchOverflowIT`,
  `Stage1OrphanRescueIT`, `Stage1PipelineIT`, `Stage1WatchdogIT`,
  `Stage1WorkerBoundaryIT`, `Stage1WorkerEmitterThreadIT`,
  `Stage1WorkerStaleRawReEmitterIT`
- `eval/stage2/`: `Stage2BenignNotifyScopeIT`, `Stage2VerdictPersistenceIT`,
  `Stage2WorkerIT`, `Stage2FirstPassQuarantineRowIT` (M1-739 — direct
  `Stage2VerdictHandler.apply` exact-key calls only)
- `eval/embedding/` (direct `processBatch`): `EmbeddingWorkerBackoffTest`,
  `EmbeddingWorkerNonFiniteTest`, `EmbeddingWorkerPgvectorRejectionTest`
- `eval/entity/`: `EntityExtractorWorkerTest` (direct `processOne`),
  `EntityExtractorWorkerBackoffTest` (instant is an UNSEEDED hand-built
  `PostRow` ctor arg — never a DB column)
- `eval/tagger/`: `TaggerWorkerTest` (direct `processOne`/`validate`/
  `renderPrompt`), `TaggerWorkerBackoffTest` (unseeded ctor arg, as entity twin)
- `eval/reeval/`: `AdminReviewTtlJobTest`, `QuarantineAuditBeforeEffectIT`,
  `FirstPassStage2RowBenignCloseIT` (M1-739 — same boundary caution as
  `ReEvaluationBenignAuditScopeIT`: benign ONLY because it drives
  `processOne` on a hand-built candidate; routing it through
  `enumerateCandidates` would make it an (A) instantly),
  `ReEvaluationBenignAuditScopeIT` (**boundary caution**: its
  `FETCHED_AT = 2026-06-08` is benign ONLY because every method drives
  `processOne` on a hand-built candidate; if a future edit routes it through
  `enumerateCandidates`, it becomes an (A) instantly — prefer a pinned Clock
  from the start in any such edit), `ReEvaluationJobTest`,
  `ReEvaluationJobWindowTest`
- `notify/`: `ApproveQuarantinePhantomNotifyIT`, `QuarantineProcedureNotifyIT`
  (procedures invoked by explicit id; phantom-suppression by ROW_COUNT of an
  exact-match UPDATE)

### Floor-less or non-window paths

- `outbox/`: `OutboxRehydratorIT`, `OutboxRehydratorPaginationIT` (SQL-literal
  seeds; `loadChunk` scans `WHERE status='RAW'` with NO fetched_at floor —
  the absolute instants only feed keyset `(fetched_at, id)` ordering),
  `PostPersisterIT`, `PostPersisterNormalizationIT` (direct persist,
  read-back by key)
- `partition/`: `PartitionInsertIT` (direct INSERT + `pruneOnce` with explicit
  `(YearMonth, Instant)` args over synthetic 2020 partitions),
  `PartitionCreatorTest` (every gate takes `now` as an explicit argument;
  the SQL literals are EXPECTED generated-DDL output)
- `stream/`: `StreamSourceStopDrainIT` (rows counted by source_id+status)
- `stream/nostr/`: `Kind6HandlerIT`, `Kind6LinkingIT`,
  `Kind6RepostResolutionIT` (direct handler/deliver-callback paths;
  `RepostEdgeResolver` keys on `upstream_identifier`), `NostrEventTest`
  (direct clamp unit test; its L30 `Instant.now()` sanity is backstopped by an
  exact-equals anchor), `RelayHealthTrackerTest` (constructor-injected
  `MutableClock` governs the cooldown — the pin-equivalent)
- `linking/`: `LinkingJobBehaviorIT`, `LinkingJobIT`, `LinkingJobSemanticProbeIT`
  — window-gated on `infochat.linking.lookback-days`/`semantic-window-hours`,
  but all three run under `WideLookbackProfile` (36500 d / 876000 h ≈ 100
  years), so the fixed May-2026 seeds cannot age out for a century
- `db/`: `DbGrantsRevocationIT` (SQL-literal `captured_at` inside the permanent
  V30 partition; the fixture comment explicitly hardens against drift)
- `fetch/`: `FetchSchedulerPersistFailureIT` (seeds ride a NormalizedPost that
  never persists; `tickOnce` applies no pacing gate — pacing lives in the
  bypassed `drainPending`)
- `fetcher/` + `assets/`: `BlueskyFetcherTest`, `BlueskyResponseParserTest`,
  `RssFeedParserTest`, `RedditResponseParserItemCapTest`,
  `RedditResponseParserNameValidationTest`, `RedditResponseParserPermalinkTest`,
  `RedditResponseParserSocialSignalTest`
  (fetched_at is an explicit `parse()` argument; other instants are expected
  values), `AssetSnapshotFetcherSupportGateTest`, `AssetSnapshotFetcherTest`,
  `PriceSnapshotStoreTest` (captured_at is stored output routed against STATIC
  migration-defined partition bounds; `runHostTick` never reads captured_at
  vs now)

### The date-robust window-test pattern (correct way to test a floor WITHOUT a pin)

Three fixtures test real windowed queries yet are (B) because they are built
so no assertion can silently invert:

1. in-window rows seed `Instant.now()` (relative — never ages out);
2. the below-floor row is a fixed date that only recedes FURTHER below the
   advancing floor (e.g. `2026-05-01`);
3. a loud fixture self-guard asserts the below-floor seed is actually below
   the floor, so drift fails the build instead of passing vacuously.

Exemplars: `EmbeddingWorkerPickupFloorIT` (guard L64),
`ReEvaluationJobWindowTest` (guard L71), `NostrSinceCursorIT` (guard L68-70).
New window tests should copy one of these OR pin the Clock; both are immune to
calendar drift.

## Already pinned (12 parse files + 2 pin-only) — verified effective, no edit

`ClassifierWorkerIT`, `EmbeddingWorkerIT`, `EntityExtractorWorkerIT`,
`ReadyPromoterClockIT`, `AdminReviewTtlJobClockIT`,
`PerSourceUnknownTrackerClockIT`, `ReEvalVerdictNotifyIT` (M1-601, out of
scope here beyond guard-pass confirmation), `ReEvaluationJobScheduledPathIT`,
`TaggerWorkerClockIT`, `LinkingJobClockIT`, `PartitionPrunerClockIT` — all pin
in `@BeforeEach` with seeds either computed relative to the pin or fixed a
small offset inside the pinned floor. (`FetchSchedulerClockIT` and
`FetchSchedulerHostPacingIT` pin but seed no absolute instants.)
`ReadyPromoterIT` also greps as "pinned" but was only method-locally pinned —
see (A) #3; it is the reason the recurrence guard is file-granular ONLY as a
lower bound (below).

## Detection-gap addendum: SQL-literal absolute seeds

The ticket's letter (and the guard's pattern) target `Instant.parse("20NN-`.
Three files seed absolute timestamps as SQL string literals instead, invisible
to that grep: `DbGrantsRevocationIT` (TIMESTAMPTZ '2026-06-15...', ACL test
against a permanent partition — benign), `OutboxRehydratorPaginationIT`
('2026-05-31' + per-row millisecond increments feeding a floor-less RAW scan —
benign), `PartitionCreatorTest` (expected-DDL literals — benign). All three are
(B) today, so nothing detonates; the guard intentionally stays
`Instant.parse`-scoped per acceptance item 3, and this addendum is the
documented residual risk: a FUTURE fixture seeding a windowed `fetched_at` via
a SQL literal would evade the guard. If one appears, widen the guard's
detection regex then.

Residual calendar coupling of a different mechanism (not scan-window, not
guard-relevant): absolute-dated INSERTs rely on the V7/V30 bootstrap
partitions (2026-05/06/07) existing; `%test.quarkus.scheduler.start-mode=halted`
keeps `PartitionPruner` from ever dropping them in tests, and
`PartitionInsertIT`'s prune case pins its args to synthetic 2020 partitions.
A failure there would be loud (INSERT error), not vacuous.

## Recurrence guard (acceptance item 3)

`infochat-collector/src/test/java/app/zcat/infochat/collector/testsupport/ScanWindowFixtureGuardTest.java`
— a plain surefire unit test (M1-495 `IntegrationTestNamingGuardTest` shape):
walks this module's `src/test/java`, flags any test source containing
`Instant.parse("20NN-` with NO `Clock.fixed(` /
`installMockForType(..., Clock.class)` in the same file, and asserts the
found-set ⊆ the in-source benign baseline (the 51 (B) files above = the 54
minus the 3 SQL-literal-only/pin-only combinations, i.e. every unpinned parse
file that this census verified benign).

- A NEW unpinned absolute-instant fixture → not in the baseline → build fails
  with instructions (pin it, or — only if genuinely benign — add it to the
  baseline AND record why in this census).
- Pinning or deleting a baseline file only shrinks the found-set — no guard
  edit needed (subset assertion).
- **Known limitation (file granularity):** a file that pins in ONE method but
  hits the windowed query unpinned in ANOTHER (the pre-sweep `ReadyPromoterIT`
  shape) is invisible to the guard, as are SQL-literal seeds (addendum above).
  The guard is a strong lower bound, not a proof; reviewers should still check
  new window-reliant tests against the date-robust pattern or the pin pattern.
