---
id: M1-258
title: "ThrottledAdminNotifier: fold suppressed_count bump into the UPSERT"
status: deferred
created: 2026-06-09
last_updated: 2026-06-09
deferred_reason: wont-do-infeasible
deferred_on: []
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/test/java/app/zcat/infochat/core/notifier
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The throttle-window semantics, the canonical ADMIN-NOTIFY WARN log format, and the EMITTED/SUPPRESSED outcome contract — all preserved byte-for-byte; this is a statement-count optimization, not a behavior change.
  - The admin_notification_state schema / columns — unchanged (no migration).
  - The notifier javadoc — the phantom-xmax correction already landed in M1-250 (T28); this ticket changes the SQL mechanism and must keep the javadoc accurate, but does not re-do that correction.
  - The Redactor / SafeLog masking of the notify message — unchanged.
acceptance:
  - "The SUPPRESSED branch no longer issues a second prepared statement (SUPPRESSED_BUMP_SQL): the suppressed_count increment, the notification_count increment, and the emit/suppress decision are all computed in the single ON CONFLICT DO UPDATE ... RETURNING UPSERT (the DO UPDATE runs on every conflict; the window predicate selects which counter advances)."
  - "A named test asserts: a first call EMITS and a follow-up call within the window SUPPRESSES while incrementing suppressed_count by exactly 1; N within-window calls produce suppressed_count == N and notification_count unchanged; a call after the window EMITS again and advances last_notified_at. Outcomes (EMITTED/SUPPRESSED) and counter values match the pre-change behavior."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/notifier
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts:
  - date: 2026-06-09
    prior_status: escalated
    reason: |
      Single-statement fold is infeasible; ticket abandoned. The deep-review F1
      premise ("the conflicting row is already located by the UPSERT, so the
      second statement is avoidable") is FALSE. Two independent premise-fails
      confirmed it:
      (1) AC1's single ON CONFLICT DO UPDATE ... RETURNING is invalid PostgreSQL
          (EXCLUDED is out of scope in RETURNING), and any "last_notified_at ==
          now" discriminator regresses the simultaneous-instant concurrent test.
      (2) The refined CTE fold was implemented and run: the new lifecycle test and
          the three per-scenario tests passed, but the PRESERVED concurrent test
          regressed — concurrentNotifyOnceRaceSafeForSameKey expected
          suppressed_count 19, got 18 (one lost bump). Root cause is fundamental:
          a single CTE shares one READ COMMITTED snapshot, so the bump CTE's plain
          UPDATE cannot see a row a concurrent thread inserted after the snapshot
          began. The original second statement's FRESH per-statement snapshot is
          what makes the concurrent bump correct — it is load-bearing.
      A correct emit/suppress discriminator cannot be returned from a single
      statement in the simultaneous-instant case without xmax (rejected, M1-250,
      out_of_scope) or a schema column (out_of_scope); MERGE/pre-read-CTE/JDBC-
      pipeline variants were all checked and fail the same squeeze. The only
      correct one-round-trip path is a server-side PL/pgSQL function (migration +
      Java/SQL logic split), judged not worth the marginal win on this rare,
      lightweight-UPDATE path. Deep-review finding F1 is NOT actionable as written.
    reviews_at_abort: {}
    clarity_check_at_abort:
      date: 2026-06-09
      verdict: PASS
      warnings: []
      blockers: []
    revisions_at_abort:
      - date: 2026-06-09
        reason: premise-fail rework (AC1 prescribed an infeasible single ON CONFLICT DO UPDATE; refined to a CTE single-statement fold, which then hit premise-fail #2)
        snapshot:
          status: escalated
          escalation_reason: premise-fail
    escalations_at_abort:
      - date: 2026-06-09
        reason: premise-fail
        note: "Pre-implementation: AC1's single ON CONFLICT DO UPDATE ... RETURNING is invalid SQL (EXCLUDED out of scope in RETURNING); any last_notified_at==now discriminator breaks the simultaneous-instant concurrent test. Resolved via refine to a CTE fold."
      - date: 2026-06-09
        reason: premise-fail
        note: "Round-1: refined CTE fold implemented; preserved concurrent test regressed (suppressed_count 18 vs 19) — the bump CTE's shared snapshot can't see a concurrently-inserted row. Single-statement fold fundamentally infeasible without xmax/schema column. Aborted."
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-258: ThrottledAdminNotifier: fold suppressed_count bump into the UPSERT

## Context

The throttled admin notifier is, by design, the hot path during an incident: a
Stage-2 outage or feed flood produces a burst of `notifyOnce` calls for the same
low-cardinality key, and by construction all but the first call per window land
on the SUPPRESSED branch. On that branch the code pays two prepared-statement
round-trips: the UPSERT (which already conflicted on the PK, took the row lock,
evaluated the window `WHERE`, and produced no row) followed by
`SUPPRESSED_BUMP_SQL`, which re-locates the same row by the same key to increment
one column. Doing two statements per suppressed call is exactly the case where
the table and connection pool are already under pressure. The conflicting row is
already located by the UPSERT, so the second statement is avoidable. Source:
`deep-code-review/v3.5/opus-48/02-module-infochat-core.md#F1` (verified live
against `ThrottledAdminNotifier.java:296-323`, `SUPPRESSED_BUMP_SQL` second
statement present on main).

## Acceptance

See frontmatter. In prose: always run the `DO UPDATE` (drop the WHERE filter that
currently suppresses the RETURNING) and compute both counters and the
emit/suppress decision in the `SET` + `RETURNING`, so the SUPPRESSED branch needs
no second statement. A named test pins the emit/suppress outcomes and the counter
values against the current behavior; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The throttle window, the WARN format, the outcome contract, the
schema, and the masking are all preserved — only the statement count drops from
two to one on the suppressed path.

## Notes

- `ON CONFLICT DO UPDATE` still serializes concurrent callers on the row PK;
  exactly one observes `last_notified_at` advancing to its own
  `EXCLUDED.last_notified_at` (emit), the rest see it unchanged (suppress) and
  increment `suppressed_count`. The race semantics the class documents are
  preserved.
- A self-describing boolean (`RETURNING (last_notified_at = EXCLUDED.last_notified_at)
  AS emitted`) removes the current implicit "no row returned == suppressed"
  dependency, which only works because the WHERE suppresses the RETURNING. The
  fresh-INSERT path returns emitted=true (a first sighting always emits) — confirm
  this in the test.
- The `interval` literal will appear multiple times in the SET; use positional
  `%1$s` formatting as the report suggests so it stays a single source.
- Keep the class javadoc accurate to the new mechanism (M1-250 already corrected
  the phantom-xmax wording; do not regress it).
</content>
