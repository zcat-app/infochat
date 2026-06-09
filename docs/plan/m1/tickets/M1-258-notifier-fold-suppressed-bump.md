---
id: M1-258
title: "ThrottledAdminNotifier: fold suppressed_count bump into the UPSERT"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
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
aborted_attempts: []
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
