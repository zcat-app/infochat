---
id: M1-906
title: Restore-time derivative retention floor + old-bundle WARN
status: pending
created: 2026-08-22
last_updated: 2026-08-22
flow: tick
reproduction: >-
  to-be-written RestoreWiringTest#restoredOldDerivativePartitionsRaiseTheRetentionFloor
  (child of a 2-ticket decomposition — analysis
  docs/plan/m1/tick-analysis/d17-restore-retention-and-bootverify.md; `start`
  converts the marker per workflow §0). Probe against the current tree:
  `grep -n 'retention' prod/scripts/restore.sh` returns nothing — the restore
  never adjusts retention for the bundle's age. Observed live (C-RT leg,
  2026-08-22, durable reproduction text
  /home/infochat/infochat/.scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md:450-466;
  plan row /home/infochat/infochat/.scratch/V2.0.0-FIX-VERIFICATION-PLAN-2026-08-21.md:621):
  restoring the 2026-07-29 bundle with shipped retention effective (post 30d /
  derivative 4d) let the first pruner tick DROP post_embedding_202607,
  post_entity_202607, post_reference_202607 within seconds of the v84 boot;
  the post-boot DB check found none of them. The original incident then burned
  the PAID API at 46 failures/2 min on re-embed retries against the dropped
  partitions (plan :567-573).
analysis_ref: docs/plan/m1/tick-analysis/d17-restore-retention-and-bootverify.md
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
    post and price_snapshot retention (P8 — the brief's scope hygiene: the
    D-17 defect implicates DERIVATIVE partitions only; post's 30d horizon
    kept the corpus, and price_snapshot self-heals via the asset fetcher).
    Probe: `grep -c 'price_snapshot' prod/scripts/restore.sh` stays 0.
  - >-
    Any app-side change — PartitionPruner / PartitionDdl / EmbeddingWorker
    stay exactly as they are (the active/next-month floor guard already
    exists; a pruner-side grace is analysis option D, rejected). Probe:
    `git diff --name-only` shows no infochat-* path.
  - >-
    The unshipped re-embed procedure (docs/design/02-schema.md §2.8 is
    deferred — "neither is shipped today", :1684-1686) and any reference to
    reembed.sh in operator-facing text (P9). Probe: `grep -c 'reembed.sh'
    prod/scripts/restore.sh` is 0.
  - >-
    The v2.0.0 release-note warning — the owner's pre-agreed action
    (2026-08-21T21:41Z contingency, option (a)), not a ticket.
  - >-
    Auto-LOWERING the floor after a re-embed or operator decision — the
    floor is raised once, loudly; returning to the shipped horizon is the
    operator's named action.
  - >-
    8-verify.sh and the config-freshness leg — sibling M1-907's surface.
    This ticket's banner/WARN text never promises a staleness check.
acceptance:
  - "RestoreWiringTest.restoredOldDerivativePartitionsRaiseTheRetentionFloor (the reproduction, written and run RED at start) passes — a fake psql probe returning post_embedding_/post_entity_/post_reference_ partition rows computed >=2 months old at run time yields: (a) the three infochat.partitions.retention-days.{post-embedding,post-entity,post-reference} keys APPENDED to the placed runtime application.properties under a marker comment, each value inside the runtime-recomputed [oldest-partition-end age in days + 30, +31] envelope (P1 — a start-vs-end or floor-vs-ceil mutation falls outside); (b) a WARN block naming each affected table's oldest partition, the applied floor, the date the floor lapses, and the two operator actions (keep the floor; lower the key back to the prior effective value to accept the drop); (c) the fake-docker argv log showing the config write BEFORE `up -d --wait … infochat-collector` (P4 — the boot tick is the trigger); (d) the restore continuing to completion with exit semantics unchanged (P3); (e) no fake-secret value anywhere in output (P7)."
  - "RestoreWiringTest.freshBundleLeavesRetentionConfigUntouched passes — a probe result carrying only current/next-month partitions yields a placed config BYTE-IDENTICAL to the staged file and no WARN (a mutation that always floors fails this; the clean-clone-stays-silent principle, M1-822)."
  - "Failure-mode / edge (P2): RestoreWiringTest.highOperatorRetentionIsNeverLowered passes — a staged config carrying infochat.partitions.retention-days.post-embedding=400 (the bench-override shape, campaign plan :563) WITH old partitions in the probe yields no write and no WARN (a floor that lowers an operator override re-creates D-17 with the operator's own value)."
  - "Failure-mode (P3): RestoreWiringTest.derivativeAgeProbeFailureWarnsAndContinues passes — the probe psql made to fail yields a loud skip WARN naming that the derivative-age check did not run plus the manual check command, the restore continues, the partial-state note does NOT print, and no config write occurs (instrumentation must not abort a healthy restore or impersonate a failure)."
  - "Scope and stale-truth probes (P8/P9): `grep -c 'price_snapshot' prod/scripts/restore.sh` is 0 and `grep -c 'reembed.sh' prod/scripts/restore.sh` is 0 — the floor covers exactly the three derivative parents and the WARN names only actions that exist."
  - "docs/design/07-deployment.md §7.10.1 records the floor: restore.sh raises the derivative retention horizon on the placed config to cover the restored derivative partitions' ages plus a 30-day grace when the effective value is lower (WARN + banner note; raise-only), and one sentence notes that a MANUAL §7.10 restore of a backup older than the derivative horizon needs the same floor by hand. Verify: `grep -n 'retention-days' docs/design/07-deployment.md` hits the §7.10.1 region (:1126-1192)."
  - "mvn verify from repo root is green (engineering-rules §5), including every pre-existing RestoreWiringTest case UNMODIFIED — this ticket adds cases only; no test modification is authorized or needed."
test_plan:
  adds:
    - RestoreWiringTest.restoredOldDerivativePartitionsRaiseTheRetentionFloor
    - RestoreWiringTest.freshBundleLeavesRetentionConfigUntouched
    - RestoreWiringTest.highOperatorRetentionIsNeverLowered
    - RestoreWiringTest.derivativeAgeProbeFailureWarnsAndContinues
  preserves:
    - all tests currently green on main
    - >-
      every pre-existing RestoreWiringTest case — the M1-819 gate cases, the
      M1-821 failure-path cases, the M1-822 probe cases: the new probe inserts
      after the M1-822 probe and before model rehydration, so gate-failure and
      bring-up drives see no new output on their paths (the new probe's psql
      is a distinguishing-argv-marker addition to the fake docker; drives that
      do not arm it get the clean-bundle shape: no write, no WARN).
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/spec/schema.md §Invariants
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs:
  - D33
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

# M1-906: Restore-time derivative retention floor + old-bundle WARN

## Context

Reproduced live on the C-RT leg of the v2.0.0 fix-verification campaign
(2026-08-22, disposable isolated stack): restoring the 2026-07-29 bundle with
the shipped retention in force (post 30d / derivative 4d) let the first
Collector pruner tick drop `post_embedding_202607`, `post_entity_202607`,
`post_reference_202607` within seconds of the v84 boot; the post-boot DB check
found none of them (defects log
`.scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md:450-466` — the durable reproduction
text; the /tmp effective-retention log is empty at analysis time). A restore
can thereby destroy the historical embedding/entity/reference surfaces — none
of which regenerate (no re-embed procedure ships, 02-schema.md §2.8) — and, on
paid-provider profiles, restored in-flight RAW rows retry embedding INSERTs
into the dropped partitions every tick (the original incident: 46 failures/2
min). Owner pre-agreed the follow-up fix (2026-08-21T21:41Z, option (a)): a
restore-time retention floor or archive warning; the release-note warning is
the owner's own action. Analysis: `analysis_ref:`.

## Root cause

Fully proven (analysis, Ground truth links 1-6): restore.sh places the
bundle's runtime application.properties verbatim (`cp -p`, restore.sh:451)
and never adjusts retention (`grep -n 'retention' prod/scripts/restore.sh`
returns nothing); no wizard script writes the
`infochat.partitions.retention-days.*` keys (`grep -rn 'retention-days'
prod/scripts` → nothing), so the shipped defaults
(infochat-collector/src/main/resources/application.properties:176-180 —
derivative 4d) apply unless the source operator hand-set them;
PartitionPruner drops every child partition whose END date is more than
`retentionDays` before now (PartitionDdl.prunablePartitions,
PartitionDdl.java:128-149), guarded only for the active/next month; and
restore.sh starts the Collector itself (restore.sh:892-893), so the boot tick
(campaign plan :526-527: "the boot tick is the trigger") destroys the
restored derivative partitions inside the restore run, before any operator
action is possible. The loss is permanent: embedding pickup only selects RAW,
tagger_done, embedding_done=FALSE rows inside the post-retention+slack window
(EmbeddingWorker.java:579-585; EmbeddingWorkerPickupFloorIT.java:22-33), so
restored READY posts are never re-embedded. Not re-derived (fix-independent):
the exact Quarkus first-fire timing of the pruner tick — the campaign
evidence is the authority.

## Pitfalls

Numbered per the analysis document; this ticket carries P1-P10.

- P1: Pruner-predicate fidelity — partition END vs `now - retentionDays`,
  ceil, round-trip names only (PartitionDdl.java:133,140-144,156-167); an
  off-by-one either re-creates D-17 on the boundary month or over-raises. A
  floor equal to the exact age erodes the next day, so the floor is age + 30d
  grace and the WARN names the lapse date.
- P2: Raise-only, never lower — a bundle carrying a deliberate 400d override
  (the bench shape) keeps it; the floor writes only when the computed
  requirement exceeds the effective value.
- P3: WARN-and-continue; probe failure degrades to a loud skip WARN, never an
  abort, never the partial-state note (ERR trap armed at restore.sh:444-446;
  the M1-822 P7/P10 posture).
- P4: The floor lands in `$CONFIG_FILE` — the placed file the Collector
  container mounts (docker-compose.yml:140) — BEFORE the Collector start
  (restore.sh:892-893); a floor in the staging copy or after bring-up
  protects nothing.
- P5: Last-wins append with a marker comment (read_prop is explicitly
  last-wins, restore.sh:212-216; the wizard appends, 1-profile.sh:59 /
  4-llm.sh:114); never sed-rewrite an operator's existing line; the marker
  carries the design anchor (§7.10.1), never a ticket chronicle (§11).
- P6: Env-shadowing — verified none shipped (no `INFOCHAT_PARTITIONS_*`
  pass-through in docker-compose.yml; env ordinal 300 outranks the file's
  260, :106-139 comment); the WARN names the floor's file so a hand-added
  override is discoverable. Do not build detection for the unshipped case
  (§7).
- P7: No secrets, read-only probe — partition names, months, day counts,
  dates only; pg_inherits SELECT over the existing PGPASSWORD-env
  in-container psql pattern (restore.sh:740-742); no post content.
- P8: Derivative tables only (post_embedding, post_entity, post_reference) —
  post was not implicated; price_snapshot self-heals (the fetcher refreshes
  it, deployment.md §Bootstrap :222-223). Widening is §1 scope drift against
  the brief's explicit exclusion.
- P9: Never name the unshipped re-embed (02-schema.md:1684-1686); every
  operator action the WARN names must exist (the M1-893 P8 stale-truth trap).
- P10: Wiring-harness discipline — distinguishing argv marker + FAKE-DOCKER
  echo for the new psql probe, new coreutils (date) registered in the
  restricted PATH (the M1-822 P9 / REAL_TOOLS pattern); fixture months
  computed old at runtime, expected floor asserted as a recomputed envelope —
  never a wall-clock-relative pinned constant (the M1-740 rot lesson).

## Approach

Derived from `spec_refs:` — §7.10.1's exact-clone contract is what makes the
short horizon silently destructive (the clone faithfully inherits a config
whose 4d derivative horizon is younger than the bundle), so the restore
discloses and repairs the one hostile interaction at the only point that can:
its own run, before it boots the Collector. Invariant 6 (schema.md §Invariants)
is the mandate being deferred, not changed — the horizon stays profile-driven
config; deployment.md §Backups, rotation, secrets (:446-447) assigns retention
policy to design notes/runbook, so a config write recorded in §7.10.1 needs no
spec amendment. D33 is the horizon-decision lineage.

- **Files to touch** (plan, not allowlist): `files_scope` — restore.sh (probe
  + floor write + WARN + banner note), RestoreWiringTest.java (four added
  cases), docs/design/07-deployment.md §7.10.1 (one paragraph + the
  manual-path sentence).
- **Steps in order** (each green before the next):
  1. The RED wiring test (workflow §0, P10): the four cases against the
     current script — the reproduction fails on the absent floor/WARN.
  2. The probe (P3, P7, P8): after the M1-822 probe (ends restore.sh:786),
     before model rehydration (:788) — one in-container psql (the :740-750
     pattern) listing child partition names of the three derivative parents
     in one round trip (the PartitionPruner.listChildren SQL,
     PartitionPruner.java:110-127, extended via `p.relname = ANY(...)`),
     written to a staging file; guarded so its failure prints the skip WARN
     and continues.
  3. The floor computation and write (P1, P2, P4, P5): per table, keep only
     names round-tripping `^<parent>_[0-9]{6}$`; oldest month → partition end
     = first of next month 00:00 UTC; required = ceil((now − end)/86400) + 30;
     effective = read_prop of the placed `$CONFIG_FILE`, else the shipped
     default 4; when required > effective, append marker comment +
     `infochat.partitions.retention-days.<key>=<required>` to `$CONFIG_FILE`.
     All before the Collector start at :892.
  4. The WARN block (P1, P6, P9): per affected table — oldest partition,
     applied floor, lapse date (end + required days), the two real operator
     actions (keep the floor; lower the key back to the prior value to accept
     the drop on the next tick after the lapse) — plus one line naming the
     paid-retry consequence the floor prevents.
  5. The banner note (the :998-1004 pattern): one line, printed only when a
     floor was applied — the operator reads the banner at cutover, possibly
     long after the WARN scrolled.
  6. The §7.10.1 paragraph — last; it records the landed shape and the
     manual-§7.10-path sentence.
- **Controls to preserve (§10):** the M1-819 Flyway gate's position and
  failure semantics (:671-731 — the probe runs after it; gate-failure drives
  never reach the new code); the M1-822 probe's WARN-and-continue and
  skip-note degrade (mirrored, not moved); the ERR trap / partial-state
  single-print flag (:444-446); exit semantics and every later step (model
  rehydration, image build, Collector --wait, the single-owner gate
  :918-955, the 8-verify run :965-967); PGPASSWORD-env-only credential
  discipline; every pre-existing RestoreWiringTest case unmodified; the
  pruner untouched (PartitionDdl.java:140-142's floor guard and its pins hold
  by construction).
- **Pitfall→mitigation:** P1→step 3 + the envelope assertion in the
  reproduction; P2→step 3's raise-only comparison + the 400d case; P3→step
  2's guard + the failure-mode case; P4→step 3's placement + the argv-log
  order assertion; P5→step 3's append + the byte-identical operator-lines
  assertion; P6→step 4's file-naming + the analysis's compose grep; P7→step
  2's read-only SELECT + the no-secret assertion; P8→step 2's three-parent
  scope + the price_snapshot grep probe; P9→step 4's exists-only actions +
  the reembed.sh grep probe; P10→step 1.

## Definition of done

The reproduction test passes (old derivative partitions → the three keys
appended in the age+30 envelope, WARN naming partitions/floors/lapse
dates/actions, config write ordered before the Collector start, restore
continues, no secrets in output); the fresh-bundle case leaves the placed
config byte-identical with no WARN; the 400d-override case writes nothing;
the probe-failure case degrades to the skip WARN without the partial-state
note; the scope/stale-truth grep probes hold; §7.10.1 records the floor and
the manual-path note; `mvn verify` green with every pre-existing
RestoreWiringTest case unmodified.

## Verification

- P1 → RestoreWiringTest.restoredOldDerivativePartitionsRaiseTheRetentionFloor
  — feeds probe rows ≥2 months old; asserts the appended values land in the
  runtime-recomputed [age+30, age+31] envelope and the WARN names the lapse
  date. Mutations caught: start-vs-end dating, floor-vs-ceil, missing grace,
  absent lapse date.
- P2 → RestoreWiringTest.highOperatorRetentionIsNeverLowered — the 400d
  staged override with old partitions; asserts no write, no WARN.
- P3 → RestoreWiringTest.derivativeAgeProbeFailureWarnsAndContinues
  (failure-mode) — the probe psql fails; asserts the skip WARN, continued
  execution, absent partial-state note, absent config write.
- P4 → the reproduction's fake-docker argv-log assertion: the config write
  precedes the Collector `up -d --wait` call.
- P5 → the reproduction's byte-comparison of the operator's pre-existing
  config lines (a sed-rewrite fails it) + the marker-comment presence.
- P6 → the WARN names `$CONFIG_FILE`'s path; compose grep disposition is in
  the analysis (no shipped pass-through).
- P7 → the reproduction asserts no fake `INFOCHAT_*_PASSWORD` value appears
  in output (the M1-821 no-leak assertion pattern).
- P8 → probe: `grep -c 'price_snapshot' prod/scripts/restore.sh` is 0.
- P9 → probe: `grep -c 'reembed.sh' prod/scripts/restore.sh` is 0; the WARN
  text review names only the two real actions.
- P10 → the harness additions fail loudly under the restricted PATH when a
  marker or tool is missing (the M1-822 P9 self-check property); fixtures
  compute months at runtime.
- acceptance item 2 → RestoreWiringTest.freshBundleLeavesRetentionConfigUntouched
  (a mutation that always floors fails it).
- acceptance item 6 → `grep -n 'retention-days' docs/design/07-deployment.md`
  inside the §7.10.1 region.
- acceptance item 7 → `mvn verify` from repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. post and price_snapshot retention are
untouched — the D-17 defect implicates derivative partitions only (the
brief's scope hygiene; post's 30d horizon kept the corpus, price_snapshot
self-heals via the fetcher). No app-side change: PartitionPruner,
PartitionDdl, and EmbeddingWorker stay exactly as they are — the analysis
weighed a pruner-side grace (option D) and rejected it as a global behavior
change for a restore-scoped problem. The unshipped re-embed procedure
(02-schema.md §2.8, deferred) is neither built nor referenced. The v2.0.0
release-note warning is the owner's pre-agreed action. The floor is never
auto-lowered — returning to the shipped horizon is the operator's named
action. 8-verify.sh is sibling M1-907's surface; this ticket's output never
promises a staleness check. This ticket modifies NO pre-existing test.

## Census

Class: **paths that boot a Collector over a restored old database with the
shipped derivative retention in force.** Re-runnable enumeration:
`grep -ln 'pg_restore' prod/scripts/*.sh` returns
- `prod/scripts/restore.sh` — THIS ticket's fix (floor before the Collector
  start).
- `prod/scripts/upgrade.sh` (:47, :171) — only PRINTS a manual pg_restore
  recovery against the minutes-old pre-upgrade backup; out-of-class (the
  backup is same-age by construction; nothing is old enough to prune).
- `prod/scripts/backup.sh` (:8) — a dump-format comment; backup.sh has no
  restore path (M1-570's frozen contract). Out-of-class.
Plus the non-script site: the §7.10 MANUAL restore runbook
(docs/design/07-deployment.md:1082-1096 — operator-hand pg_restore of an
arbitrary-age backup) — disposition: covered by the §7.10.1 amendment's
manual-path sentence (acceptance item 6); no script change is possible for a
hand-driven path.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-906`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-906-restore-derivative-retention-floor.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
