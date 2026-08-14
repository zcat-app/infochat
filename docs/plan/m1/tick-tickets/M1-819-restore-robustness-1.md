---
id: M1-819
title: restore.sh pre-validates restored Flyway history vs checkout
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  RestoreWiringTest#restoredHistoryChecksumMismatchFailsLoudAfterPgRestore
  (to-be-written — child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/restore-robustness.md). Probe against the
  current tree: grep -n 'flyway_schema_history' prod/scripts/restore.sh
  returns only the comment at line 596 — no code validates the restored
  history. Observed live (2026-08-11, .scratch/setup-hurdles.md item 1):
  after pg_restore the Collector crash-looped on FlywayValidateException
  checksum mismatch on V50/V55 (commit a60315c3 had edited comments inside
  the already-applied files) and the restore flow offered no diagnosis;
  recovery was manual UPDATE of flyway_schema_history checksums.
analysis_ref: docs/plan/m1/tick-analysis/restore-robustness.md
blocked_by: []
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    AUTO-REPAIR of flyway_schema_history. The gate PRINTS the
    flyway-repair-equivalent UPDATE as one of two named operator options; it
    never runs it (analysis, option B — a checksum mismatch can mean a
    genuine semantic change, which auto-repair would silently bless).
  - >-
    The migration-immutability lint (M1-820's script) — restore-side
    detection and repo-side prevention are separate tickets.
  - >-
    The bounded pg_restore ignorable-error set (M1-580, restore.sh:559-592)
    — unchanged; this gate is a NEW check after the schema-presence
    backstop, not a widening of that tolerance.
  - >-
    ensure_gguf / the M1-571 custom-GGUF recovery path, and the other
    restore.sh regions beyond the inserted gate and its wiring-test support.
  - >-
    8-verify.sh, pack.sh, 4-llm.sh (frozen contracts), and all app-side
    code (a Java-side Flyway guard is not this ticket).
acceptance:
  - "RestoreWiringTest.restoredHistoryChecksumMismatchFailsLoudAfterPgRestore (the reproduction, written and run RED at start) passes — a fake flyway_schema_history whose V50 checksum drifts from the checkout file's recomputed checksum yields non-zero exit, output naming V50, both recovery options (matching-revision checkout OR the printed flyway-repair UPDATE), and the M1-581 partial-state note with placed items; the fake-docker argv log proves pg_restore ran and neither model rehydration nor image build nor any app start followed."
  - "RestoreFlywayChecksumIT.checksumFunctionMatchesMigratedSchemaHistoryForEveryAppliedMigration passes — it migrates a Testcontainers PostgreSQL from the checkout's migrations, reads flyway_schema_history, runs the bash checksum pipeline (ProcessBuilder, the RestoreWiringTest pattern) over the real migration files, and asserts equality for every applied SQL version; RestoreFlywayChecksumIT.commentOnlyEditChangesTheComputedChecksum passes — a comment-only edit to a scratch copy of one migration flips the computed value (non-vacuity, engineering-rules §8 assertion-adequacy)."
  - "Failure-mode cases pass (P3): RestoreWiringTest.failedHistoryRowsAndNonSqlRowsAreIgnored (success=false and non-SQL rows in the fake history do not trip the gate) and RestoreWiringTest.appliedVersionAbsentFromCheckoutGetsNewerBundleMessage (an applied version with no matching V*.sql file fails with the distinct newer-bundle-into-older-checkout message, not the checksum-drift wording)."
  - "Failure-mode case (P10): RestoreWiringTest.historyProbeFailureAbortsWithPartialStateNote passes — the history SELECT made to fail aborts the restore (a gate that cannot read the history cannot pass it) with the partial-state note printed exactly once."
  - "RestoreWiringTest.matchingHistoryPassesGateAndRestoreContinues passes — fake history matching the recomputed checksums lets the run proceed past the gate (the fake-docker argv log shows the model/build steps reached)."
  - "docs/design/07-deployment.md §7.10.1 records the new gate (restore.sh validates the dump's applied-migration history against the checkout before model rehydration; a mismatch fails loud with the two recovery options). Verify: grep -n 'flyway_schema_history' docs/design/07-deployment.md names the gate."
  - "mvn verify from repo root is green (engineering-rules §5), including all pre-existing RestoreWiringTest cases (M1-567/568/569/570/580/581/582/584/585 gates) unchanged."
test_plan:
  adds:
    - RestoreWiringTest.restoredHistoryChecksumMismatchFailsLoudAfterPgRestore
    - RestoreWiringTest.failedHistoryRowsAndNonSqlRowsAreIgnored
    - RestoreWiringTest.appliedVersionAbsentFromCheckoutGetsNewerBundleMessage
    - RestoreWiringTest.historyProbeFailureAbortsWithPartialStateNote
    - RestoreWiringTest.matchingHistoryPassesGateAndRestoreContinues
    - RestoreFlywayChecksumIT.checksumFunctionMatchesMigratedSchemaHistoryForEveryAppliedMigration
    - RestoreFlywayChecksumIT.commentOnlyEditChangesTheComputedChecksum
  preserves:
    - all tests currently green on main
    - >-
      every pre-existing RestoreWiringTest gate case — the new gate inserts
      after the schema-presence backstop; the M1-580 error-gate cases, the
      M1-570 role-before-pg_restore ordering case, and the M1-582
      consent-gate cases must pass unmodified.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/spec/deployment.md §Topology
decision_refs: []
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-819: restore.sh pre-validates restored Flyway history vs checkout

## Context

Live session 2026-08-11 (`.scratch/setup-hurdles.md` item 1): after a
pack.sh/restore.sh host clone, the Collector crash-looped on
`FlywayValidateException` — checksum mismatch on V50 and V55, because commit
a60315c3 had edited comments inside those already-applied migration files.
restore.sh declared its steps complete; the mismatch surfaced only as a
crash-loop log line, and the operator recovered by hand-updating
`flyway_schema_history` checksums (flyway-repair equivalent). restore.sh
today performs zero validation of the dump's applied-migration history
against the checkout it is about to boot (grep: only the comment at
restore.sh:596 mentions the table). Analysis:
`docs/plan/m1/tick-analysis/restore-robustness.md`. Do not restate the spec
— cite `spec_refs:`.

## Root cause

Proven: restore.sh's post-pg_restore validation is exactly the M1-580
bounded error gate (restore.sh:559-592) plus the schema-presence backstop
(:600-607) — both blind to history-vs-checkout drift; the first component
that compares them is the Collector's Flyway validate at boot, minutes and
(potentially gigabytes of GGUF download) later, and its failure arrives as a
container crash loop. Only the Collector migrates in production
(docs/spec/deployment.md §Topology, deployment.md:38-54), so no other
component would catch it earlier either. Assumed, to be verified by the
implementor via `git show a60315c3` (no shell tool was available to the
analyst): the exact set of files that commit touched. In-tree
corroboration: V50__banned_admin_actor_checks.sql:190-194 ends with a
dangling "Recorded in\n-- as ..." sentence whose referent was stripped —
consistent with a comment-editing pass.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P9,
P10, P11.

- P1: The reimplemented Flyway checksum must match the pinned Flyway
  exactly (CRC32 over the file bytes with line-ending normalization — the
  pinned version's exact normalization is an ASSUMPTION the
  RestoreFlywayChecksumIT verifies against a real migrated DB). A wrong
  reimplementation false-fails every healthy restore or false-passes real
  drift.
- P2: The gate runs post-mutation (after pg_restore), so its failure is a
  partial restore: it MUST print the M1-581 partial-state note, never the
  pre-mutation gates' "aborted before any change" shape.
- P3: Validate only rows that can bite — skip success=false and non-SQL
  history rows; an applied version ABSENT from the checkout (newer bundle,
  older checkout) is a different defect class with its own named message.
- P9: The wiring-test sandbox — add any new coreutil (e.g. `awk`) to
  RestoreWiringTest's REAL_TOOLS (:66-69) and give the new psql exec a
  distinguishing argv marker + FAKE-DOCKER echo (the fake `compose` branch
  matches on substrings of "$*", :121-136; M1-585 ordering precedent).
- P10: The history probe runs under set -euo pipefail with the ERR trap
  armed (:426-428): a failed history SELECT must abort via the normal
  failure path (partial note, single print via the existing :410-412 flag)
  — a gate that cannot read the history must not silently pass, and must
  not double-print the note.
- P11: Do not disturb the M1-580 trap dance (:548-557) or widen the
  ignorable pg_restore error set; the new check lives strictly after the
  :600-607 backstop.

## Approach

Derived from `spec_refs:` — design §7.10.1 commits restore.sh to fail loud
rather than half-restore and to "everything else fails loud" on divergence
from the exact-clone promise; deployment.md §Topology (the Flyway
paragraphs, deployment.md:38-54) establishes that Collector-boot validation
is otherwise the first drift detector.

- **Files to touch** (plan, not allowlist): `prod/scripts/restore.sh` (the
  gate + a `flyway_checksum` helper);
  `RestoreWiringTest.java` (fake-docker psql history modeling + the new
  cases); new `RestoreFlywayChecksumIT` (module per the implementor's best
  fit — it needs Testcontainers PostgreSQL and the
  `infochat-core/src/main/resources/db/migration` files; infochat-core is
  the natural home); `docs/design/07-deployment.md` §7.10.1 (one paragraph).
- **Steps in order** (each green before the next):
  1. The `flyway_checksum` helper: dependency-free CRC32 (awk — already a
     restore.sh dependency at :243) over the migration file with the
     line-ending normalization the pinned Flyway applies. Verify the
     normalization against the pinned flyway-core FIRST (P1) — the IT in
     step 3 is the oracle; if it reds, the assumption was wrong, adjust the
     helper, not the oracle.
  2. The gate in restore.sh, inserted immediately after the
     schema-presence backstop (:607) and before model rehydration (:694):
     in-container psql (the :600-601 pattern, PGPASSWORD from container env
     — no secret on the host) selects version/script/checksum from
     flyway_schema_history where success; for each applied SQL row locate
     the checkout file and compare checksums. Zero drift → one quiet
     confirmation line. Drift → FAIL naming every drifted version, printing
     BOTH recovery options — (a) re-run from a checkout matching the source
     host's revision, (b) deliberate repair via the printed UPDATE
     flyway_schema_history statement(s) carrying the recomputed checksums —
     then exit 1 through the normal failure path (partial-state note, P2).
     Missing checkout file → the distinct newer-bundle message (P3).
     Failed/non-SQL rows → skipped (P3).
  3. `RestoreFlywayChecksumIT`: Testcontainers migrate, read the real
     history, ProcessBuilder-run the helper over the real files, assert
     per-version equality; plus the comment-only-edit mutation case (P1
     non-vacuity).
  4. RestoreWiringTest cases (acceptance items 1, 3, 4, 5) with the
     fake-docker additions (P9).
  5. The §7.10.1 paragraph — last, it records the landed shape.
- **Controls to preserve (§10):** the M1-580 bounded error gate and its
  ignorable set; the :600-607 backstop; the ERR/EXIT trap discipline and
  single-print partial note; every downstream step (M1-571 model recovery,
  image build, single-owner gate) unchanged — the acceptance argv-log
  assertions prove the gate's failure stops before them.
- **Pitfall→mitigation:** P1→step 1+3; P2→step 2's failure path reuses
  print_partial_state_note; P3→step 2's row filtering + distinct messages;
  P9→step 4 harness enumeration; P10→step 2's probe failure falls through
  to the standard ERR path (no `|| true` on the gate's own SELECT — unlike
  an informational probe, this gate's input is load-bearing);
  P11→insertion point after :607, no edits inside :548-607.

## Definition of done

The reproduction test passes (drifted checksum → loud post-pg_restore
failure naming the version, both recovery options, partial note, nothing
downstream started); the checksum IT pins the helper against real Flyway
for every applied migration and the comment-only mutation case proves
sensitivity; the P3/P10 failure-mode cases pass; the healthy-history case
passes; §7.10.1 records the gate; `mvn verify` from the repo root is green
with all pre-existing RestoreWiringTest cases unmodified.

## Verification

- P1 → RestoreFlywayChecksumIT.checksumFunctionMatchesMigratedSchemaHistoryForEveryAppliedMigration
  (real migrated DB history vs helper output over the real files) +
  .commentOnlyEditChangesTheComputedChecksum (a comment edit MUST flip the
  value — kills a vacuous always-equal implementation).
- P2 → RestoreWiringTest.restoredHistoryChecksumMismatchFailsLoudAfterPgRestore
  asserts the partial-state note with placed items appears (and the
  fake-docker argv log proves pg_restore preceded the failure).
- P3 → RestoreWiringTest.failedHistoryRowsAndNonSqlRowsAreIgnored /
  .appliedVersionAbsentFromCheckoutGetsNewerBundleMessage — the failure-mode
  pair: feed the fake history a success=false row, a non-SQL row, and an
  unknown version; assert pass / pass / distinct message respectively.
- P9 → the RestoreWiringTest additions themselves: restricted PATH means a
  missing REAL_TOOLS entry fails the new cases loudly.
- P10 → RestoreWiringTest.historyProbeFailureAbortsWithPartialStateNote —
  failure-mode case: fake psql fails the history SELECT; assert non-zero
  exit + exactly one partial-note print.
- P11 → the pre-existing M1-580 cases in RestoreWiringTest run unmodified
  (test_plan.preserves).
- acceptance item 6 → grep probe on docs/design/07-deployment.md.
- acceptance item 7 → `mvn verify` from repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. No auto-repair: the gate informs, the
operator decides — printing the exact UPDATE is the actionable step the
live session needed; running it unprompted would silently bless a possibly
semantic migration change (analysis option B). The repo-side prevention
(migration-immutability lint) is M1-820. The pg_restore ignorable-error
set is untouched (P11). ensure_gguf, pack.sh, 4-llm.sh, 8-verify.sh, and
all app-side code are untouched. This ticket modifies NO pre-existing test.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-819`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-819-restore-robustness-1.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
