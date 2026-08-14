---
id: M1-822
title: restore.sh surfaces inherited failed asset pairs and sources
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  RestoreWiringTest#inheritedFailedAssetPairsSurfaceAsRestoreWarning
  (to-be-written — child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/restore-robustness.md). Probe against the
  current tree: grep -n 'asset_config' prod/scripts/restore.sh returns
  nothing — the restore never inspects inherited operational state.
  Observed live (2026-08-11, .scratch/setup-hurdles.md item 11): the clone
  silently inherited a zcash/coingecko asset_config row at status='failed',
  consecutive_failures=5 (D42 ladder tripped 2026-07-31 ON THE SOURCE
  HOST); the fetcher never retries failed pairs
  (AssetSnapshotFetcher.java:314-318 selects status='active' only), bare
  /zcash resolves to that default sub-verb (docs/spec/commands.md:663-668),
  so one inherited failed pair made the whole command look broken while
  /zcash kraken and /zcash bitfinex worked and the upstream API was
  healthy. Manual SQL reset recovered it within one tick.
analysis_ref: docs/plan/m1/tick-analysis/restore-robustness.md
blocked_by:
  - M1-821
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The /asset-enable admin command — batch F owns it
    (docs/design/10-asset-commands.md §10.8b names it a v2 candidate). This
    ticket's warning names the §10.8b operator SQL; it MUST NOT name
    /asset-enable (P8). Interface: when batch F lands the command, it
    updates this warning's wording.
  - >-
    Any AUTO-RESET of inherited failed rows — the restore reports; the
    operator decides (the ladder state is legitimate source-host history;
    clearing it silently would falsify the clone's continuity with the
    source DB).
  - >-
    The M1-819 Flyway gate and the M1-821 failure-path messaging —
    siblings, already landed when this starts (blocked_by).
  - >-
    8-verify.sh, pack.sh, the Collector's runtime surfacing
    (ParkedSetSummaryJob already summarizes failed sources post-boot —
    unchanged), and all app-side code.
  - >-
    Wizard scripts (batch B) and restart-policy/lifecycle surfaces
    (batch D).
acceptance:
  - "RestoreWiringTest.inheritedFailedAssetPairsSurfaceAsRestoreWarning (the reproduction, written and run RED at start) passes — a fake psql probe returning one failed zcash/coingecko row yields a WARN block naming asset, sub_verb, consecutive_failures and last_failure_at plus the §10.8b recovery UPDATE and the /source-enable pointer, and the restore CONTINUES (the fake-docker argv log shows model rehydration reached; exit semantics unchanged — P7)."
  - "RestoreWiringTest.cleanInheritedStatePrintsNoAssetWarning passes — an all-active probe result prints no asset/source WARN (a mutation that always warns fails this)."
  - "Failure-mode case (P10): RestoreWiringTest.inheritedStateProbeFailureDegradesToSkipNote passes — the probe psql made to fail yields a one-line skip note and the restore continues, and the partial-state note does not appear (an informational probe must not abort or scare)."
  - "RestoreWiringTest.finalBannerRepeatsInheritedFailureCount passes — the final CLONE RECONSTRUCTED banner repeats the count when failed pairs/sources were found (the operator reads the banner at cutover time, possibly long after the WARN scrolled)."
  - "The WARN text names the §10.8b UPDATE statement and /source-enable, and never the not-yet-existing /asset-enable (P8). Verify: grep -c '/asset-enable' prod/scripts/restore.sh is 0 (also asserted by the reproduction test)."
  - "docs/design/07-deployment.md §7.10.1 records the inherited-state surfacing (restore.sh reports failed asset pairs / sources inherited with the dump; recovery is the §10.8b operator action). Verify: grep -n 'asset_config' docs/design/07-deployment.md names it."
  - "mvn verify from repo root is green (engineering-rules §5), including every pre-existing RestoreWiringTest case unmodified."
test_plan:
  adds:
    - RestoreWiringTest.inheritedFailedAssetPairsSurfaceAsRestoreWarning
    - RestoreWiringTest.cleanInheritedStatePrintsNoAssetWarning
    - RestoreWiringTest.inheritedStateProbeFailureDegradesToSkipNote
    - RestoreWiringTest.finalBannerRepeatsInheritedFailureCount
  preserves:
    - all tests currently green on main
    - >-
      every pre-existing RestoreWiringTest case plus the M1-819/M1-821
      cases (siblings land first — blocked_by): this ticket INSERTS an
      informational probe after the M1-819 gate; no existing message or
      exit path changes.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/design/10-asset-commands.md §10.8b
  - docs/spec/commands.md §Asset commands
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

# M1-822: restore.sh surfaces inherited failed asset pairs and sources

## Context

Live session 2026-08-11 (`.scratch/setup-hurdles.md` item 11): a host clone
silently inherited a `status='failed'` zcash/coingecko `asset_config` row —
the source host's D42 ladder had tripped twelve days earlier and the state
migrated with the dump unnoticed. Bare `/zcash` resolves to the per-asset
default sub-verb (docs/spec/commands.md §Asset commands, commands.md:663-668),
so the one failed pair made the whole command look broken while the explicit
sub-verbs and the upstream API were fine; the operator recovered with the
manual `UPDATE asset_config SET status='active', consecutive_failures=0`.
The restore gave no signal that inherited failure state was present.
Analysis: `docs/plan/m1/tick-analysis/restore-robustness.md`.

## Root cause

Proven by read: restore.sh's post-pg_restore validation checks schema
PRESENCE only (restore.sh:600-607) and nothing anywhere in the script reads
`asset_config` or `source` status (grep: no match). Meanwhile the fetcher's
eligibility predicate permanently excludes failed rows
(AssetSnapshotFetcher.java:314-318 — `enabled = true AND status =
'active'`), so inherited failed state is invisible AND self-perpetuating
until an operator acts. The recovery surface for asset pairs is
operator-side SQL by design (docs/design/10-asset-commands.md §10.8b —
"recovery is operator-side", the exact UPDATE the session used); sources
have `/source-enable` (SourceEnableCommandHandler.java:111-114).

## Pitfalls

Numbered per the analysis document; this ticket carries P7, P8, P9, P10.

- P7: Surface, never fail. A failed pair in the dump is legitimate state —
  the source host's ladder did its job. A non-zero exit here would block a
  healthy clone (restore.sh propagates failures as cutover blockers,
  restore.sh:808-811; M1-818's surface-don't-fail posture).
- P8: Do not invent the recovery path. `/asset-enable` does not exist
  (batch F owns introducing it); the WARN names the §10.8b UPDATE and
  `/source-enable` only. Naming a nonexistent command in operator-facing
  text is the §11 stale-truth trap at print time.
- P9: Wiring-test sandbox — the probe is a new in-container psql exec; give
  it a distinguishing argv marker + FAKE-DOCKER echo (the fake matches
  substrings of "$*", RestoreWiringTest.java:121-136) and add any new
  coreutil to REAL_TOOLS (:66-69).
- P10: The probe is informational: `|| true` + skip note on its own
  failure, never an abort, and the partial-state note must not fire for it
  (the run hasn't failed). Runs under set -e with the ERR trap armed
  (restore.sh:426-428) — the probe's guard must be explicit.

## Approach

Derived from `spec_refs:` — §7.10.1's exact-clone contract is what makes
inherited state SILENT today (the clone is faithful; nothing is wrong to
fail on — so the only honest addition is surfacing); §10.8b supplies the
recovery action the WARN names; commands.md §Asset commands explains why
one failed default pair looks like a dead command (the WARN names the
default pair's blast radius when the failed row is the is_default one).

- **Files to touch** (plan, not allowlist): `prod/scripts/restore.sh` (the
  probe + WARN block + one banner line); `RestoreWiringTest.java`;
  `docs/design/07-deployment.md` §7.10.1 (one paragraph).
- **Steps in order** (each green before the next):
  1. The probe, inserted AFTER the M1-819 Flyway gate (a drifted history
     makes inherited-state trivia moot — gate first) and before model
     rehydration: one in-container psql (the restore.sh:600-601 pattern,
     PGPASSWORD from container env) returning the failed asset pairs
     (asset, sub_verb, consecutive_failures, last_failure_at) and the
     failed-source count in one round trip; guarded `|| true` with a skip
     note (P10).
  2. The WARN block: for each failed pair print asset/sub_verb/count/last
     failure and the §10.8b UPDATE shape; for failed sources print the
     count and the /source-enable pointer; when a failed pair is the
     asset's is_default row, add the one line explaining bare /<asset> is
     the dead surface while explicit sub-verbs may work (P7/P8 wording).
     Zero rows → silence (cleanInheritedStatePrintsNoAssetWarning).
  3. The final-banner count line (printed only when something was found —
     carry the count in a variable; the banner heredoc is static today, so
     the line is a conditional echo after it).
  4. RestoreWiringTest cases + harness additions (P9).
  5. The §7.10.1 paragraph — last, it records the landed shape.
- **Controls to preserve (§10):** the M1-819 gate's position and failure
  semantics (this probe sits after it and must not run when the gate
  failed); the partial-note single-print flag; the M1-571 model recovery
  and every later step (argv-log assertions prove the WARN path still
  reaches them); no secret material in the probe output (asset/sub_verb
  names and counts only — P5's no-leak posture from the analysis).
- **Pitfall→mitigation:** P7→WARN-only + the continue-assertion in the
  reproduction test; P8→the grep assertion (acceptance item 5);
  P9→step 4; P10→step 1's guard + the skip-note failure-mode case.

## Definition of done

The reproduction test passes (failed inherited pair → WARN naming it, the
§10.8b UPDATE, and restore continues); the clean-state case prints no WARN;
the probe-failure case degrades to a skip note without the partial note;
the final banner repeats the count; the WARN never names /asset-enable;
§7.10.1 records the surfacing; `mvn verify` green with all pre-existing and
sibling RestoreWiringTest cases unmodified.

## Verification

- P7 → RestoreWiringTest.inheritedFailedAssetPairsSurfaceAsRestoreWarning —
  fake psql returns the failed zcash/coingecko row; asserts the WARN
  content AND (via the fake-docker argv log) that model rehydration ran and
  the exit path is unchanged.
- P8 → the reproduction test's grep assertion: '/asset-enable' never
  appears in prod/scripts/restore.sh; the WARN text contains the §10.8b
  UPDATE shape and '/source-enable'.
- P9 → the harness additions fail loudly under the restricted PATH when a
  marker/tool is missing.
- P10 → RestoreWiringTest.inheritedStateProbeFailureDegradesToSkipNote —
  the failure-mode case: fake psql fails the probe; asserts the skip note,
  continued execution, and absence of the partial-state note (an abort or a
  partial-note print fails it).
- acceptance item 2 → RestoreWiringTest.cleanInheritedStatePrintsNoAssetWarning
  (all-active probe output; a mutation that always warns fails it).
- acceptance item 4 → RestoreWiringTest.finalBannerRepeatsInheritedFailureCount.
- acceptance item 6 → grep probe on docs/design/07-deployment.md.
- acceptance item 7 → `mvn verify` from repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. `/asset-enable` is batch F's — this ticket's
WARN deliberately points at the §10.8b operator SQL (the exact statement
the live session used); batch F updates the wording when the command exists
(interface note in the YAML list). No auto-reset of inherited rows — the
restore reports, the operator decides; silently clearing ladder state would
falsify the clone's continuity with the source DB. The M1-819/M1-821
siblings are already landed (blocked_by) and untouched. 8-verify.sh,
pack.sh, ParkedSetSummaryJob, app-side code, batch B and batch D surfaces:
untouched. This ticket modifies NO pre-existing test.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-822`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-822-restore-robustness-4.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
