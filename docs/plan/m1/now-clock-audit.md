# now() / Instant.now() classification audit (M1-447)

Status: ticket deliverable (acceptance item 1). Classifies every production
current-time read across both services so the injectable-`Clock` conversion is
scoped to the sites that actually gate a decision. The governing rule is
CLAUDE.md §"Injectable time in decision logic" / `engineering-rules-verbatim.md`
§9; the reference implementation is M1-444 (`ReEvaluationJob`).

## Classification scheme

- **(A) decision-logic time** — the instant feeds a comparison/gate that
  determines behaviour: scan/candidate windows, cooldowns, TTL/expiry checks,
  rate-limit windows, probation/ban/invite-expiry timing, retry/retention
  cutoffs. These are the conversion targets (read from an injected `Clock` so a
  test can pin them).
- **(B) pure audit/record write** — `created_at` / `updated_at` /
  `status_changed_at` / `used_at` stamps, audit-log event timestamps, and DDL
  `DEFAULT now()`: a value only *written*, never read back to gate a decision.
  Left on the DB clock (system-of-record convention).
- **(C) display/formatting** — a `now()` used only to render a timestamp into a
  user reply or compute a human-readable "X ago". Not gating behaviour; left as
  is.

Raw surface (ripgrep): ~90 Java `*.now()` matches + ~21 files with SQL
`now()`/`current_timestamp` in query strings, across both services. The vast
majority are (B)/(C). The **unconverted (A) set is 11 components**.

## (A) decision-logic components

| # | Component | Module | Gate | Disposition |
|---|-----------|--------|------|-------------|
| 1 | `InviteCodeConsumer` | provider | invite-expiry (`expires_at > NOW()`, SQL), brute-force-attempt window count, in-memory breach-sweep gate+cutoff+mark, probation-window write | **CONVERTED (this ticket)** |
| 2 | `GroupAutoPromoteService` | provider | probation-eligibility gate (`probation_until` vs now) | **CONVERTED (this ticket)** |
| 3 | `AdminReviewTtlJob` | collector | quarantine-review TTL auto-reject (`Instant.now().minus(adminReviewTtl)`) | **CONVERTED (this ticket)** |
| 4 | `EmbeddingWorker` | collector | partition-scan pickup window (SQL `now() - ?::INTERVAL`) | DEFERRED — has existing IT to modify |
| 5 | `EntityExtractorWorker` | collector | partition-scan pickup window | DEFERRED — has existing IT to modify |
| 6 | `TaggerWorker` | collector | partition-scan pickup window | DEFERRED |
| 7 | `ReadyPromoter` | collector | partition-scan pickup window | DEFERRED |
| 8 | `PerSourceUnknownTracker` | collector | per-source UNKNOWN-rate scan window | DEFERRED |
| 9 | `PartitionPruner` | collector | partition retention cutoff (`YearMonth.now`, `Instant.now`) | DEFERRED |
| 10 | `DigestRetryService` | provider | digest retry cooldown (`Instant.now().isBefore(lastRetryAt.plus(cooldown))`) | DEFERRED |
| 11 | `FetchScheduler` | collector | per-kind tick interval (`Duration.between(lastTick, now) >= interval`) | DEFERRED |

The 8 DEFERRED components are out of scope for M1-447 (see the ticket's
`out_of_scope`) and land as follow-up tickets, each a small independently
reviewable diff per the ticket-body decomposition note. `EmbeddingWorker` and
`EntityExtractorWorker` additionally carry existing wall-clock-relative ITs
(M1-398 / M1-400 de-rots) that a conversion would have to modify, so their
follow-up tickets must declare a `test_plan.modifies` — which is precisely why
they are excluded from this additive-only ticket.

### Already-correct (injected `Clock`, NOT re-touched)

`ReEvaluationJob` (converted by M1-444 — the reference), `DigestScheduler`,
`RateCapBucket`, `ConfirmStateService`, `RelayHealthTracker`, `NostrStreamSource`,
and `ThrottledAdminNotifier` itself (which also hosts the app-wide
`@Produces @ApplicationScoped Clock systemUtcClock()`).

## In-scope conversion detail (the trio)

### `AdminReviewTtlJob`
- (A) `enumerateExpired()`: `Instant cutoff = Instant.now().minus(adminReviewTtl)`
  → `clock.instant().minus(adminReviewTtl)`. The TTL cutoff gates which PENDING
  quarantine rows auto-reject.
- (B) LEFT on DB clock: `updated_at = now()` and `status_changed_at = now()`
  inside `rejectExpired`'s UPDATEs — pure record stamps.

### `GroupAutoPromoteService`
- (A) `isEligible()`: `probation_until.isAfter(Instant.now())` →
  `…isAfter(clock.instant())`. Gates whether a probation user can be
  auto-promoted.

### `InviteCodeConsumer`
A single `Instant now = clock.instant()` is sampled once per `consume()` and
threaded to every Java-side decision read, so the in-memory breach mark and the
sweep that reads it back share one instant (no two-clock split, M1-444 rule):
- (A) `countAttempts()` cutoff: `OffsetDateTime.now().minus(bruteForceWindow)`
  → derived from the sampled `now`. Brute-force window.
- (A) `evictStaleBreachAudited()` gate + cutoff: `Instant.now()` → the sampled
  `now`. Sweep staleness.
- (A) breach mark `breachAudited.put(key, Instant.now())` → `put(key, now)`. The
  value the sweep reads back.
- (A) `insertOrSelectUser()` probation write:
  `OffsetDateTime.now().plus(probationDuration)` → derived from the sampled
  `now`. The `probation_until` value.
- (A)-stays-DB-clock: `CONSUME_INVITE_SQL`'s `expires_at > NOW()` invite-expiry
  gate is decision-logic but is an **intra-statement** comparison against the
  DB-authored `expires_at` column inside one conditional UPDATE; moving only the
  comparison to a Java-bound instant would split the gate from the column's
  authorship. It stays on the DB clock, which is byte-for-byte equivalent to the
  production `Clock.systemUTC()`. A follow-up could bind a Java instant if a
  determinism need ever arises; none does today.
- (B) LEFT on DB clock: `used_at = NOW()` in the same UPDATE.

### Cross-component note: `probation_until` write/read split
`InviteCodeConsumer` (in scope) WRITES `probation_until`; it is read for a
decision by `GroupAutoPromoteService.isEligible` (in scope, converted) AND by
`ProbationCheck` (**out of scope** — reads `probation_until > NOW()` on the DB
clock). The conversion moves the WRITE to the app `Clock` while `ProbationCheck`
keeps DB `now()`. This is an app-write/DB-read authorship split, accepted here
ONLY because production `Clock` is `Clock.systemUTC()` ≈ DB `now()`, so
behaviour is byte-for-byte preserved (ticket acceptance item 2). The follow-up
ticket that converts `ProbationCheck` should move its read onto the same `Clock`
to close the split; this audit records that the write side already moved.

## (B) / (C) summary (NOT converted)

- **(B) audit/record stamps** — `created_at` / `updated_at` / `status_changed_at`
  / `used_at` / `ready_at` written via SQL `now()` or DDL `DEFAULT now()` across
  the stage workers (`Stage1Worker`, `TaggerWorker`'s stamp writes,
  `ReadyPromoter`'s `ready_at`, `DigestWorker`, etc.), every command handler that
  records an action, and the audit-log writer. These only record time; nothing
  reads them back to gate a decision. ~50+ sites. Left on the DB clock.
- **(C) display/formatting** — asset snapshot sources stamp
  `snapshot_created_at` at ingest (`Instant.now()`), command/reply renderers
  format stored timestamps. None compares against `now()` to gate behaviour.
  ~10 sites. Left as is.
