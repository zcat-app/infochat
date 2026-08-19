---
id: M1-887
title: "Generalize the pre-V84 preflight and gate upgrade.sh on it"
status: pending
created: 2026-08-19
last_updated: 2026-08-20
flow: tick
reproduction: >-
  to-be-written TagTreeCutoverCheckIT.dynamicUnmappedNamesFailThePreflight
  (child of a 2+ decomposition — analysis
  docs/plan/m1/tick-analysis/upgrade-pre-v84-cutover.md; `start` converts
  the marker per workflow §0). The wrong behavior it states: a pre-V84
  database carrying an arbitrary legacy dynamic tag (`ai-image` — an
  operator/tagger coinage, NOT the ruled nostr/video) passes
  tag-tree-cutover.sh preflight CLEAN today, because PREFLIGHT_SQL
  inventories only the ruled two names (tag-tree-cutover.sh:86-104, the
  deliberate scope its :83-85 comment states); the name surfaces only at
  the V84 migrate boot as an E4002 crash-loop. Probes against the current
  tree: grep -n "nostr','video'" prod/scripts/tag-tree-cutover.sh shows
  the two-name filters at :88-103; grep -n 'tag-tree-cutover'
  prod/scripts/upgrade.sh returns nothing — upgrade.sh restarts the apps
  on every run (upgrade.sh:291-309, including no-op-pull redeploys) with
  no pre-restart check. Live evidence: routine upgrade.sh against a
  pre-V84 DB with legacy dynamic tags ai-image + video restarted the apps
  into the V84 crash-loop, DB left at V83
  (.scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §7;
  .scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md D-1, release blocker).
analysis_ref: docs/plan/m1/tick-analysis/upgrade-pre-v84-cutover.md
blocked_by: []
files_scope:
  - prod/scripts/tag-tree-cutover.sh
  - prod/scripts/upgrade.sh
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/UpgradeWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Any edit to V84__tag_tree_seed_and_migration.sql or any other applied
    migration — shipped-migration content is immutable
    (docs/spec/deployment.md §Topology, deployment.md:56-58;
    scripts/lint-migration-immutability.py:73-75). This ticket prevents
    V84's failure from pre-migrate tooling; it never touches a migration
    file (migration_touch: false). Probe: git diff --name-only shows no
    db/migration path.
  - >-
    Any Java application code — BootstrapLoader, TaggerWorker,
    SourceUpsertService, the resolver, or any consumer. The gates stay
    exactly as M1-866/M1-882 shipped them; only the operator tooling
    around them changes.
  - >-
    upgrade.sh APPLYING the cutover itself (running apply, writing the
    rulings file, or any DB mutation). The gate inventories, prints the
    findings and the rulings-file path and the cutover commands, and
    exits — the operator-decision ruling (M1-880 out_of_scope; M1-866's
    loud contract) forbids auto-removal or auto-mapping of unruled names
    (analysis P4). The preflight's skeleton write happens only on the
    operator-run cutover script, never inside upgrade.sh.
  - >-
    The runtime bootstrap-sources.json reconcile verb (reconcile-file)
    and any JSON rewriting — M1-888. This ticket's preflight FLAGS
    non-leaf file tags and its gate/runbook wording stays
    subcommand-neutral about the file fix (analysis P10); the hand-edit
    path remains the documented remedy until M1-888 lands.
  - >-
    Argv rulings channels (--map/--drop CLI flags on apply). The rulings
    FILE is the only ruling input (directed shape, 2026-08-20): a second
    channel would need precedence rules and would re-admit the
    unreviewable one-shot path the direction rejected (analysis, Solution
    options).
  - >-
    A catch-all rulings line (`*: drop`) — refused as malformed. Weighed
    and rejected in the analysis: a wildcard writes no name, so a name
    appearing after the file is written would be disposed without ever
    being individually reviewed — the inverse of the loud contract.
  - >-
    Mapping nostr/video into the tree. The user ruling (M1-866) is
    disposal: the skeleton pre-fills them as `drop` lines and the
    validator refuses a map ruling for either name.
  - >-
    post.tag_candidates and frozen content (summary_cache bodies, digest
    replay slugs, saved_post snapshots) — never rewritten (D19/D36/D65
    byte-faithful replay; the M1-880 census: V84 does not validate them
    and pre-M1-868 code never wrote tag_candidates). Operator-ruled
    remaps do NOT salvage old names into tag_candidates (no invention);
    the printed apply output and the rulings file are the record.
  - >-
    New audit rows, GRANT changes, or any service-role widening — the
    apply runs as the migration-owner role via psql (docs/spec/security.md
    §DB roles), the ownership-level operator action; it is a pre-boot
    operator act and writes no audit row.
acceptance:
  - "TagTreeCutoverCheckIT.dynamicUnmappedNamesFailThePreflight (the converted reproduction) passes — it seeds the hostile four-surface state with ARBITRARY legacy names on the real Testcontainers DB (a tag row 'ai-image', a post carrying {ai, ai-image}, a source carrying {cybersecurity, ai-image}, a scope_tag row referencing the ai-image row, plus a 'video' occurrence) plus a runtime-file fixture carrying the title-cased legacy name 'Development', drives prod/scripts/tag-tree-cutover.sh preflight through the CUTOVER_PSQL seam, and asserts: exit 1; every occurrence named per surface WITH counts (tag / post.tags / source.bootstrap_tags / scope_tag / file); the known names (ai, cybersecurity) NOT flagged; the discriminating assertion — a DB-side 'development' element (a V84 mapping key) is NOT flagged on the DB surfaces but the file-side 'Development' IS flagged (DB predicate nodes ∪ keys, file predicate leaves only — analysis P1); and the skeleton rulings file is written at CUTOVER_MAP_FILE with one commented placeholder per unknown name (per-surface counts in the comments) and a pre-filled ACTIVE `video: drop` line for the standing ruling, its path printed in the output (analysis P15). A second preflight after an operator edit to the rulings file leaves that file BYTE-IDENTICAL (the skeleton never clobbers — failure mode: the review artifact must survive re-runs)."
  - "TagTreeCutoverCheckIT.preflightGreenStateExecutesV84Cleanly passes (mirror completeness, P1/P7) — seeds one edge name from EVERY known-set class (a mapping key as a tag row AND as array elements incl. 'news' in post.tags, an operator coinage colliding with a to-be-seeded leaf name, the identity leaves) → preflight exits 0 → the migrate step re-executes the real V84 classpath resource (the runV84 mechanics) and SUCCEEDS → postflight exits 0; and a post-V84-state preflight run is silent-clean (the gate's every-upgrade posture: no false positives on migrated or fresh DBs)."
  - "TagTreeCutoverCheckIT.applyAppliesTheRulingsFile passes (failure modes mandatory) — with a rulings file carrying `ai-image: ai` and `video: drop`: apply --dry-run prints the per-surface plan and changes nothing (preflight still RED, bystanders byte-identical — failure mode: a would-be destructive run must be reviewable and side-effect-free); the real apply: (a) a scope following BOTH ai-image and ai re-points without a PK violation (ON CONFLICT DO NOTHING, the V84:316-333 mechanics), (b) a mapped tag row whose target leaf row is absent pre-migrate is RENAMED with node_kind/parent_name/fallback set from the script's V84 mirror so the post-migrate postflight counts (9 tops / 53 parented leaves / 8 fallbacks) stay GREEN, (c) post {ai, ai-image} rewrites to exactly {ai} — order-preserving dedup (V84:233-311 mechanics), (d) per-surface counts are printed along with the consumed ruling lines to retire (the audit/count plan §7 requires), (e) every bystander row/element is byte-identical, (f) a re-run with the unretired file refuses the consumed lines as stale (exit 2 naming the lines) with 0 rows changed, and after the lines are retired a further run exits 0 with 0 rows (the staleness guard, analysis P14/P15)."
  - "TagTreeCutoverCheckIT.applyRefusesAnInvalidRulingsFile passes (failure mode, analysis P14) — each invalid rulings-file shape fed as a fixture: an uncovered unknown name; a duplicate name line; an extra line (a name in no current unknown inventory); a malformed line; a `*: drop` catch-all line; a map target that is no seeded leaf; a map ruling for nostr/video — every leg asserts exit 2 naming the offending line or name and ZERO mutation (preflight still RED, bystander rows byte-identical: validation completes before the transaction opens, never a partial apply)."
  - "UpgradeWiringTest.preflightFindingsAbortBeforeBuildAndRestart passes — driving the REAL upgrade.sh under the RestoreWiringTest seam (restricted PATH, fake docker + git, -y) with a RED preflight seam: exit 1, output names the per-surface findings, the rulings-file path ($RUNTIME_DIR/tag-cutover-map.txt), and the subcommand-neutral cutover instruction (pointing at tag-tree-cutover.sh and docs/design/07-deployment.md §7.14 WITHOUT naming reconcile-file — analysis P10), and the fake-docker argv log shows NEITHER a build NOR any `up -d`: the abort precedes the build confirm, so there is nothing to roll back (analysis P2/P4). UpgradeWiringTest.cleanPreflightReachesTheRestart passes — a GREEN seam (including the no-op-pull leg, the M1-476 redeploy path) reaches build + Collector/Provider restart unchanged."
  - "UpgradeWiringTest.unreachableDatabaseFailsLoud passes (failure mode, P3) — the psql seam made to fail (postgres unreachable) aborts the upgrade non-zero naming the container-exec wrapper and the recovery instruction; a gate that cannot read the DB never silently passes (the M1-819 P10 shape)."
  - "docs/design/07-deployment.md §7.14 'Cut over the tag-tree migration (one-time)' is rewritten to the rulings-file sequence — preflight (full four-surface + file inventory; skeleton written on first RED) → complete the rulings in $RUNTIME_DIR/tag-cutover-map.txt (one line per name) → apply --dry-run → apply → retire the consumed lines → reconcile the runtime bootstrap file to tree leaves (hand-edit wording, subcommand-neutral until M1-888) → apps.sh start (migrate) → postflight — and the 'Arriving via a routine upgrade?' paragraph now states that upgrade.sh gates automatically before the build/restart (the M1-880 decline-restart workaround is retired); the §7.15 E4002 row still points at the subsection. Probes: grep -n 'Cut over the tag-tree migration' docs/design/07-deployment.md hits; grep -o 'tag-tree-cutover.sh [a-z-]*' docs/design/07-deployment.md matches only subcommands the script implements; grep -n 'tag-cutover-map.txt' docs/design/07-deployment.md names the rulings file."
  - "The scripts meet the prod/scripts conventions and the credential discipline (P9): executable, set -euo pipefail, shellcheck-clean (author-run, the M1-427 convention), INFOCHAT_DB_PASSWORD read via the non-sourcing read_dotenv_value shape and passed as PGPASSWORD environment only — never argv, never echoed. Probes: grep -n 'source.*secrets.env' prod/scripts/tag-tree-cutover.sh prod/scripts/upgrade.sh returns nothing; grep -n 'INFOCHAT_DB_PASSWORD' prod/scripts/tag-tree-cutover.sh shows only the read + PGPASSWORD lines; ls -l shows mode 0755."
  - "mvn verify from repo root is green; the diff touches no file under infochat-core/src/main/resources/db/migration/ (probe: git diff --name-only <fork>..HEAD | grep db/migration returns nothing) and no pre-existing test is modified beyond the four TagTreeCutoverCheckIT cases explicitly authorized in Out-of-scope below (engineering-rules §8)."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java — new cases: dynamicUnmappedNamesFailThePreflight, preflightGreenStateExecutesV84Cleanly, applyAppliesTheRulingsFile, applyRefusesAnInvalidRulingsFile
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/UpgradeWiringTest.java — new: preflightFindingsAbortBeforeBuildAndRestart, cleanPreflightReachesTheRestart, unreachableDatabaseFailsLoud
  modifies:
    - >-
      TagTreeCutoverCheckIT leftoverOccurrencesFailThePreflight,
      cleanupRemovesExactlyNostrAndVideo, cutoverRehearsalPassesPostflight,
      runtimeBootstrapFileNamesOnlyTreeNodes — the four case-level
      authorizations stated in plain language in Out-of-scope below
      (verb rename cleanup→apply with the rulings file as input,
      generalized per-surface listing and clean message; effects
      assertions unchanged).
  preserves:
    - >-
      all other tests currently green on main — in particular, unmodified:
      TagTreeMigrationIT (V84's seed/loud-failure pins), BootstrapLoaderIT
      (the leaf gate), TaggerWorkerSweepIT, RestoreFlywayChecksumIT, and
      every pre-existing RestoreWiringTest case.
spec_refs:
  - docs/spec/deployment.md §Topology
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/schema.md §Sources and tags
  - docs/spec/security.md §DB roles
decision_refs:
  - D22
  - D34
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

# M1-887: Generalize the pre-V84 preflight and gate upgrade.sh on it

## Context

Release blocker D-1 of the 2026-08 live E2E campaign: a routine
`upgrade.sh` against a pre-V84 database restarts the apps into the V84
migration crash-loop — V84 raises E4002 on any tag name that is neither a
seeded tree node nor in its hardcoded lookup (live reproduction: the
legacy dynamic tags `ai-image` + `video`), the Collector crash-loops, the
DB stays at V83, and the operator learns of the required cutover only
after the restart, with the code auto-rollback masking the cause
(upgrade.sh:151-171). V84 is frozen on main (immutability,
docs/spec/deployment.md §Topology) and no later migration can rescue a
failed V84 (Flyway stops at the failure), so the fix must live BEFORE
migrate. The directed shape (2026-08-19, refined 2026-08-20): an
automatic PREFLIGHT inventorying EVERY unmapped name across the four DB
surfaces plus the runtime file, printed with per-surface counts, before
any restart/migrate; operator rulings entering via a reviewable rulings
FILE (`$RUNTIME_DIR/tag-cutover-map.txt` — one line per name,
`name: <tree-leaf>` or `name: drop`), validated totally and applied
deterministically. Full analysis:
docs/plan/m1/tick-analysis/upgrade-pre-v84-cutover.md. Do not restate the
spec — cite `spec_refs:`.

## Root cause

Verified (analysis §Ground truth): the cutover script's preflight was
deliberately scoped to the ruled two names — "any other unmapped name is
the operator's decision, signalled by V84's own loud failure at boot"
(tag-tree-cutover.sh:83-85, SQL :86-104) — and upgrade.sh has no
pre-restart gate at all (`grep -n 'tag-tree-cutover'
prod/scripts/upgrade.sh` returns nothing; the restart at upgrade.sh:291-
309 runs on every upgrade, including no-op-pull redeploys, :230-246).
D-1 is the live falsification of that design (M1-880's P6 predicted it;
the campaign confirmed it). The signal arrives too late: after the
restart, inside a crash-loop, masked by the rollback.

## Pitfalls

Numbered consistently with the analysis document; this ticket owns the
DB-side, rulings-file, and gating pitfalls (the runtime-file rewrite
pitfalls are M1-888's).

- P1: Mirror drift vs V84's frozen known-set — the preflight's per-surface
  predicates must EXACTLY mirror V84 (tag rows: 9 tops + 53 leaves + 21
  keys; arrays: 53 leaves + 21 keys — `news` legal only as the key;
  scope_tag follows its row's name; the FILE predicate is stricter:
  normalized name ∈ leaves only). The same mirror backs rulings-target
  validation. A miss false-REDs clean deployments; an invention
  false-GREENs a leftover.
- P2: Gate placement — after the pull (the gate runs the NEW checkout's
  script), before the build confirm; it must fire on no-op-pull redeploys
  too (upgrade.sh:230-246). On RED nothing has changed but backup/pull —
  no rollback.
- P3: DB unreachable (stopped postgres, no host psql) — fail loud with
  the recovery instruction; no new host prerequisite (container-exec
  CUTOVER_PSQL wrapper, 07-deployment.md:1679-1684 shape); never skip.
- P4: upgrade.sh must never mutate tag data — gate + instruct only (the
  operator-decision ruling; upgrade.sh's own never-writes-runtime
  promise, :30-31). The skeleton write belongs to the operator-run
  cutover script, never to upgrade.sh.
- P5: scope_tag FK order + UNIQUE collisions — re-point/delete references
  before tag rows (V7:64-69 no cascade), ON CONFLICT DO NOTHING on
  re-point (V84:316-333); rename-with-mirror-columns when the target leaf
  row is absent pre-migrate, or postflight's exact counts go RED.
- P6: Array dedup — order-preserving (V84:233-311); naive array_replace
  duplicates.
- P7: False positives — post-V84 and fresh DBs must be silent-clean;
  mirror by NAME, never via node_kind (arrives at V82; prod was recorded
  at V79).
- P8: Bystander byte-identity; --dry-run changes nothing; targets are
  always explicit — and the rulings FILE is the explicitness: one written
  line per name, reviewed before any run.
- P9: Credentials — PGPASSWORD environment only, non-sourcing read
  (tag-tree-cutover.sh:40-55 shape).
- P10: Fixture calibration — this ticket modifies four existing
  TagTreeCutoverCheckIT cases (§8 authorizations below); the gate's
  file-fix wording stays subcommand-neutral (it names the rulings file
  and §7.14, never reconcile-file) so M1-888 lands without re-touching
  this ticket's pins (the M1-785 lesson).
- P14: Rulings-file validation must be total and refuse BEFORE any
  mutation — a malformed line (any `*` catch-all included), a duplicate,
  an extra/stale line, an uncovered unknown, a non-leaf map target, or a
  map ruling for the ruled nostr/video each refuses with exit 2 naming
  the line or the name, with zero mutation so far; validation runs
  against the CURRENT union inventory (four DB surfaces + runtime file)
  so a ruling written against stale state never silently applies (the
  M1-819 print-never-auto-repair posture; engineering-rules §2).
- P15: Rulings-file lifecycle — the skeleton is written only when the
  file is absent (operator edits are never clobbered); the home is the
  GITIGNORED runtime area because a tracked one would trip upgrade.sh's
  clean-tracked-tree gate (upgrade.sh:202-210) on the next run; keys
  match the normalized stored form (skeleton comments show the raw
  forms); consumed lines go stale and are retired between runs — the
  extras refusal is the staleness guard.

## Approach

Derived from `spec_refs:`: §Topology commits only the Collector to
migrate and shipped migrations to immutability — so the fix is tooling
before migrate; §Bootstrap behavior on startup keeps the loader's
fail-fast gate as the backstop the preflight predicts; §Sources and tags
commits the stored normalization form the mirror and the rulings keys
match by; §DB roles reserves this ownership-level psql action to the
owner role. The disposal of nostr/video stays the user ruling (skeleton
pre-fills `drop`; map rulings for them refuse); every other unmapped
name takes the operator's written ruling — the apply executes rulings,
it never decides (M1-866's loud contract).

**Files to touch** (guidance, not an allowlist):

1. `prod/scripts/tag-tree-cutover.sh`
   - Generalize PREFLIGHT_SQL: per surface, list every name NOT in the
     mirrored known-set, with per-surface counts (tag rows — V84's tag
     predicate; post.tags / source.bootstrap_tags — the array predicate;
     scope_tag — its referenced row's name). The mirror is a
     comment-cited copy of V84's frozen lists (ONE stable reference:
     V84's path — engineering-rules §11), matched by NAME, never
     node_kind (P7).
   - File check: flag every file tag whose lower-cased form is not a
     seeded leaf (a mapping key in the file is a finding — it must be
     converted, not passed).
   - Skeleton (P15): on RED, if $CUTOVER_MAP_FILE (default
     `$RUNTIME_DIR/tag-cutover-map.txt`) is ABSENT, write it — header
     comment documenting the format, one commented placeholder per
     unknown name carrying its per-surface counts, and ACTIVE
     `nostr: drop` / `video: drop` lines for any ruled names seen
     (commented as the standing M1-866 ruling). Never overwrite an
     existing file. The RED output names the file's path and the
     complete-then-apply instruction.
   - Replace `cleanup` with `apply [--dry-run]` (no argv rulings): read
     the rulings file, validate totally against the current union
     inventory (P14 — malformed/duplicate/extra/uncovered/non-leaf
     target/catch-all/nostr-video-map → exit 2 naming the line or name,
     before any mutation), then one transaction: scope_tag re-points
     (ON CONFLICT DO NOTHING) or deletions first, tag-row retire /
     rename-with-mirror-columns (P5), order-preserving deduped array
     rewrites (P6); per-surface counts printed; the consumed ruling
     lines printed for retirement; --dry-run prints the same plan and
     changes nothing.
   - Usage text updated; exit codes unchanged (0 pass / 1 findings / 2
     usage-or-environment — a rulings validation refusal is 2).
2. `prod/scripts/upgrade.sh` — new gate step after the config-diff step
   and before the step-4 build confirm: run the NEW checkout's
   `tag-tree-cutover.sh preflight` with CUTOVER_PSQL defaulted to a
   mktemp container-exec wrapper (P3). RED → print the findings, the
   rulings-file path, and the subcommand-neutral cutover instructions
   (P10), exit 1. Probe failure → loud abort (P3). GREEN → continue
   unchanged. The gate never writes the skeleton (P4): on RED it points
   at the operator-run cutover script. Header comment gains the gate in
   the flow description (§11: current truth, one stable reference to
   §7.14).
3. `infochat-core/.../TagTreeCutoverCheckIT.java` — the four new cases +
   the four authorized modifications (Out-of-scope).
4. `infochat-provider/.../wiring/UpgradeWiringTest.java` — new, the
   RestoreWiringTest seam (restricted PATH + fake docker/git + argv log).
5. `docs/design/07-deployment.md` — the §7.14 rewrite per acceptance 7.

**Steps, in order:** script first (tests consume it) → convert the
reproduction RED → IT cases green → UpgradeWiringTest (gate legs) →
runbook wording (citing only implemented subcommands — the M1-582
stale-reference lesson) → shellcheck + probes + mvn verify.

**Controls to preserve (engineering-rules §10):** the M1-880 sequence's
obligations travel: stop-first order in the runbook, FK-order deletes
(P5), dry-run review, bystander byte-identity pins (P8), postflight's
end-state predicates (unchanged), owner-role credential discipline (P9),
"migrate = Collector start, never psql-applied V84" (§Topology), and the
nostr/video disposal ruling made executable (skeleton pre-fill +
map-refusal). upgrade.sh's: backup-first, auto-rollback of CODE for
post-gate failures, per-gate confirms, never writing prod/runtime (the
psql wrapper is mktemp; the skeleton write stays out of upgrade.sh).
The four modified IT cases keep pinning the SAME properties (leftovers
named per surface; exact disposal with bystanders intact; the full
rehearsal end state; the file failure mode) under the generalized verbs —
stated per case below.

**Pitfall→mitigation mapping:** P1→the mirror + acceptance 2's
drive-the-real-V84 case; P2→gate before build + the wiring RED/GREEN
legs; P3→the container-exec default + acceptance 6; P4→out_of_scope +
the wiring test's no-mutation argv assertions; P5/P6→acceptance 3's
collision/rename/dedup arms; P7→acceptance 2's clean-state arms;
P8→acceptance 3's bystander/dry-run arms; P9→acceptance 8's probes;
P10→the named §8 authorizations + subcommand-neutral wording; P14→
acceptance 4's refusal legs; P15→acceptance 1's skeleton arms +
acceptance 3's retire arms.

## Definition of done

Every `acceptance:` item, verified by its named test/command/probe: the
converted reproduction green (arbitrary legacy names on all five surfaces
RED the preflight with per-surface counts, known names untouched, the
skeleton written with placeholders + standing-ruling drops and never
clobbered); the mirror-completeness case green (GREEN preflight ⇒ the
real V84 resource executes clean ⇒ postflight GREEN); the rulings-file
apply green incl. all failure arms (dry-run purity, collision re-point,
rename-with-mirror columns, dedup, counts + consumed-lines print,
bystanders, stale-line refusal, clean post-retirement run); every invalid
rulings-file shape refused with exit 2 and zero mutation; the three
upgrade.sh gate legs green (RED aborts before build/restart naming
findings + rulings-file path + instructions; GREEN flows through;
unreachable DB fails loud); the runbook rewritten with only implemented
subcommands and the rulings file named; the credential probes green;
`mvn verify` green; no db/migration diff hunk.

## Verification

- P1 → TagTreeCutoverCheckIT.preflightGreenStateExecutesV84Cleanly —
  feeds every known-set class; asserts preflight GREEN then real-V84
  success; mutation caught: a mirror missing 'news'-as-key or a seeded
  leaf REDs a clean state / fails V84.
- P1 → TagTreeCutoverCheckIT.dynamicUnmappedNamesFailThePreflight — the
  reproduction: arbitrary names RED per surface with counts; the
  'development' DB-vs-file discrimination pins the two predicates.
- P2/P4 → UpgradeWiringTest.preflightFindingsAbortBeforeBuildAndRestart
  (failure mode) — RED seam; asserts exit 1 + findings/rulings-file
  path/instructions in output + argv log with no build and no `up -d`;
  the gate never mutates.
- P2 → UpgradeWiringTest.cleanPreflightReachesTheRestart — GREEN seam
  incl. the no-op-pull leg reaches build + restart.
- P3 → UpgradeWiringTest.unreachableDatabaseFailsLoud (failure mode) —
  feeds a failing psql seam; asserts the gate refuses the upgrade loud
  (non-zero) naming the wrapper/recovery and never falls through to
  build/restart.
- P5/P6/P8 → TagTreeCutoverCheckIT.applyAppliesTheRulingsFile (failure
  modes) — hostile seeds (scope following both old name and target,
  absent target row, duplicate-producing array) plus dry-run purity and
  bystander byte-identity; a wrong FK order raises on the tag DELETE, a
  naive array_replace fails the {ai} assertion.
- P7 → the same IT's GREEN arms run pre- and post-migrate (the
  Testcontainers baseline has no tree rows; the post-runV84 run asserts
  silent-clean).
- P9 → acceptance 8's greps + shellcheck (author-run) + security_relevant:
  true.
- P10 → this ticket's Out-of-scope authorizations; the reviewer's
  TEST-INTEGRITY-CHECK; M1-888's test_plan carries preserves on every
  pin this ticket adds.
- P14 → TagTreeCutoverCheckIT.applyRefusesAnInvalidRulingsFile (failure
  mode) — feeds each invalid rulings-file shape (uncovered, duplicate,
  extra/stale, malformed, `*: drop`, non-leaf target, nostr/video map);
  asserts exit 2 naming the line/name and that nothing mutated — the
  refusal never partially applies.
- P15 → the reproduction's skeleton arms (first RED writes the skeleton,
  second RED never clobbers an operator edit — byte-identical) +
  applyAppliesTheRulingsFile's retire arms (stale lines refused naming
  the lines with 0 rows changed; exit 0 with 0 rows after retirement).
- acceptance item 7 → the named greps (subsection anchor; subcommand-set
  match; tag-cutover-map.txt named).
- acceptance item 9 → `mvn verify` exit 0 + the git-diff probe.

## Out-of-scope

Per the YAML block: no migration edit; no Java change; upgrade.sh never
applies the cutover or writes the rulings file (gate + instruct only); no
reconcile-file verb (M1-888); no argv rulings channel (the file is the
only input); no catch-all rulings line; no tree-mapping of nostr/video
(ruled disposal, executable); no tag_candidates/frozen-content salvage;
no audit rows or grant changes.

**Authorized pre-existing test modifications (engineering-rules §8 —
plain-language authorization per case):**

1. `leftoverOccurrencesFailThePreflight` — this ticket generalizes the
   preflight from the ruled two names to every unmapped name; the case's
   nostr/video seeding stays, and its assertions are updated to the
   generalized per-surface listing format (same surfaces named, now with
   counts) plus the skeleton-file assertions. The property it pins is
   unchanged: leftovers on every surface RED the preflight.
2. `cleanupRemovesExactlyNostrAndVideo` — the `cleanup` verb is replaced
   by `apply`, which reads rulings from the rulings file; the case
   writes a `nostr: drop` + `video: drop` rulings file and drives
   `apply` / `apply --dry-run` instead. Every effect assertion (exact
   disposal, FK order, bystander byte-identity, dry-run purity) is
   unchanged; the unretired second run now refuses the consumed lines as
   stale (exit 2) instead of printing 0-row counts — the staleness guard
   is the new expected behavior, and a post-retirement run exits 0 with
   0 rows.
3. `cutoverRehearsalPassesPostflight` — the rehearsal's cleanup step
   becomes the rulings-file-driven apply invocation and the generalized
   clean message replaces "preflight: clean (zero nostr/video
   occurrences)"; the rehearsal's end-state assertions are unchanged.
4. `runtimeBootstrapFileNamesOnlyTreeNodes` — the preflight file check
   now flags ANY non-leaf name (not only nostr/video); the case's
   postflight legs are unchanged, its preflight leg asserts the
   generalized file finding.

## Census

Class: **DB surfaces that can carry a tag name the preflight must
inventory and the apply must reach.** Re-runnable enumeration:
`grep -nE "unnest\(|scope_tag|node_kind"
infochat-core/src/main/resources/db/migration/V84__tag_tree_seed_and_migration.sql`
— the tag-row validation (V84:131-176), the array validation covering
post.tags and source.bootstrap_tags (V84:181-226), the scope_tag remap
(V84:316-344); columns per the M1-880 census (V7:159 post.tags, V6:42
source.bootstrap_tags, V7:64-69 scope_tag FK, V6:74-84 tag.name).
Dispositions: tag.name → preflight inventory + apply (retire/rename);
post.tags → inventory + apply (deduped rewrite); source.bootstrap_tags →
inventory + apply (deduped rewrite); scope_tag → inventory + apply
(re-point/delete first — P5). Examined and excluded: post.tag_candidates
(V83 — empty on a pre-cutover DB and not validated by V84, the M1-880
census; no salvage on operator maps); frozen content (summary_cache /
digest slugs / saved_post — D19/D36/D65 replay); the runtime
bootstrap-sources.json file — flagged by this ticket's preflight,
reconciled by M1-888.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-887-upgrade-preflight-dynamic-tag-cutover.md
```
