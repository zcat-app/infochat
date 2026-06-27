# Deep-review full-exhaustive (2026-06-27) — finding → ticket map

Source: `.reviews/deep-review/full-exhaustive-2026-06-27-1620/` (architecture +
36 per-slice reports + `00-summary.md`).

**Two verification passes.** The first pass (9 parallel agents) was inadequate —
it read only the line each report cited and did not check whether the issue was
already resolved elsewhere, so it confirmed findings already fixed by later
migrations. A second **falsification pass** (Sonnet, "try to break each claim
against current HEAD: full migration chain, fix-in-another-file, current state")
caught those.

Combined result of 85 raw findings: **77 actionable (ticketed), 6 falsified and
dropped, 1 rejected, 1 exempt.** Ticketed as M1-478 … M1-501 (M1-501 is the
Stage-1 ticket, renumbered from the original M1-477).

## Tickets (M1-478 … M1-501)

| Ticket | Topic | Findings |
|---|---|---|
| M1-501 | Stage-1-flagged posts permanently evade Stage 2 (high) | 03#F1 |
| M1-478 | Group personal /retry never matches /summary anchor (high) | 12#F1 |
| M1-479 | infochat-core partition seeds break after 2026-08-01 (TEST-only; re-scoped) | 24#F1 |
| M1-480 | /unban missing in-transaction TOCTOU admin re-check (SECURITY) | 13#F1 |
| M1-482 | Re-eval BENIGN over-audit/notify + post.id vs post_uid | 03#F2, 03#F3 |
| M1-483 | /group-timezone missing-arg error + zone work before auth (12#F5 dropped) | 12#F3, 12#F6 |
| M1-484 | Asset fetcher ignores SPI support gate + readBigDecimal dedup | 02#F2, 02#F3 |
| M1-485 | Embedding batch retry missing backoff | 02#F1 |
| M1-486 | Signal inbound line cap collapses with body cap | 09#F1 |
| M1-487 | NewPostHandler @Transactional missing rollbackOn=SQLException | 15#F1 |
| M1-488 | quarantine_reject apostrophe breaks {0} + masking test | 16#F1 |
| M1-489 | /follow-tag & /unfollow-tag tag normalization (CT2, reduced) | 11#F1 |
| M1-490 | §9 split-clock decision sites reconciled w/ M1-447 (CT1, SECURITY) | 05#F2, 12#F4, 14#F1, 14#F2 |
| M1-491 | Log-sanitization hardening: NOTICE control-strip + SafeLog bidi (SECURITY) | 05#F1, 06#F1 |
| M1-492 | Production javadoc/contract drift | 01#F1, 08#F1, 09#F2, 15#F2 |
| M1-493 | Schema hardening: NOT NULL upstream_identifier + approve_quarantine NOTIFY (18#* dropped) | 19#F1, 19#F2 |
| M1-494 | Production dead-code & defensive-check sweep (07#F1, 14#F3 dropped) | 04#F1, 04#F2, 07#F2, 08#F3, 10#F1, 10#F2, 10#F3, 11#F2, 13#F2, 13#F3, 13#F4, 13#F5, 15#F3 |
| M1-495 | Integration tests named *Test run in surefire (CT3) + guard | 22#F2, 34#F1, 35#F2 |
| M1-496 | Test-integrity: vacuous/ambient-gated/over-permissive assertions (CT5) | 20#F1, 29#F3, 30#F1, 30#F2, 33#F1, 33#F2, 36#F2 |
| M1-497 | Test name/comment accuracy (CT6) | 20#F2, 25#F1, 30#F3, 30#F4, 33#F3, 35#F1 |
| M1-498 | Test fidelity & coverage gaps (21#F1 re-scoped: no seam exists) | 21#F1, 22#F1, 23#F1, 26#F1, 36#F1 |
| M1-499 | Test fixture dedup → testsupport + leaked teardown (CT4) | 21#F2, 24#F2, 24#F3, 27#F1, 28#F3, 29#F1, 31#F2, 34#F2, 23#F2 |
| M1-500 | Test dead-code/import/structure cleanup | 26#F2, 28#F1, 28#F2, 29#F2, 31#F1, 32#F1, 32#F2 |

## Falsified and dropped (NOT ticketed) — caught by the second pass

- **18#F1 — Collector UPDATE on price_snapshot.** ALREADY FIXED:
  `V39__db_grants_revocations.sql:27` revokes `UPDATE ON price_snapshot FROM
  infochat_collector`. The first pass read only V17 and missed it. Ticket M1-481
  was created then **deleted**.
- **18#F2 — duplicate idx_chat_message_session_seq.** ALREADY FIXED:
  `V42__drop_chat_message_duplicate_index.sql` drops it.
- **18#F3 — stage2_verdict has no CHECK.** ALREADY FIXED:
  `V36__schema_hardening.sql:27` adds `post_stage2_verdict_chk`.
- **12#F5 — source commands block a bot admin who isn't a group admin.** FALSE
  POSITIVE: `isGroupAdmin()` returns true immediately when `user.is_admin`
  (`RemoveSourceCommandHandler.java:351` and siblings), so a bot admin is never
  blocked. The first pass confirmed AND amplified this — both wrong.
- **07#F1 — LlmHttpSupport silently caps max-response-bytes at 8 MiB.**
  OVERSTATED: the clamp is documented as intentional operator-resilience
  (`LlmHttpSupport.java:81-84` javadoc); not a defect.
- **14#F3 — /help does a redundant second users query.** OVERSTATED: the second
  read is the documented per-handler pattern (`InboundContext` does not carry
  `is_admin`), architecturally necessary under the current design.

## Rejected by the first pass (premise failed verification)

- **12#F2 — `/saved [tag]` "no tag normalization".** Personal tags are
  **free-form** per `docs/spec/commands.md`; `/save` stores them case-preserving
  and `/saved` filters with the same token. Normalizing would *break* matches.
  Current behavior is correct. (Narrowed CT2 to just `/follow-tag` +
  `/unfollow-tag`, now M1-489.)

## Verified valid but EXEMPT (no ticket)

- **08#F2 — `Identity.lastSeen` uses `Instant.now()`.** Pure record-write,
  exempt under engineering-rules §9. Not ticketed.

## Re-scoped after falsification (kept, but the original framing was wrong)

- **24#F1 (M1-479)** — NOT a production-wide time-bomb: the live `PartitionCreator`
  (`infochat-collector`, M1-121) provisions months ahead in production. The real
  gap is **test-only in infochat-core**, whose test datasource never runs that
  collector bean and relies on V30's June/July partitions. Fails 2026-08-01.
- **21#F1 (M1-498)** — the report claimed a package-private seam "already exists"
  for the reflected `FetchScheduler` fields; it does NOT (only `clock` is
  package-private). Fix requires adding a seam or accepting the reflection.

## Other verification notes carried into tickets

- **CT1 §9 (M1-490):** the four sites are confirmed live splits at HEAD and are
  NOT yet on the injected Clock; several were left un-remediated by M1-447 /
  M1-450 / M1-452 and are not in `now-clock-audit.md` — the ticket reconciles
  against that backlog.
- **27#F1 / 31#F2 (M1-499):** duplication is wider than the report's file counts
  (e.g. `parse(...)` in 12 signal test files; `newEnShortCircuitPipeline` across
  3) — ticket says "every former copy."
- **28#F3 (M1-499):** `newAdapter`/`ackFrame` are genuinely triplicated;
  `updateRejectFrame` is only 2× (absent from the chunked-send test).
- **29#F2 (M1-500):** dead imports are lines 13,15,16,17 — NOT line 14
  (`SQLFeatureNotSupportedException` is used at line 191); the report's 13-16
  range was off.
