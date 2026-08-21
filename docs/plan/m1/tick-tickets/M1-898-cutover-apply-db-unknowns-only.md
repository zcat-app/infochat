---
id: M1-898
title: "Scope cutover apply to DB unknowns; refuse key rulings"
status: done
created: 2026-08-21
last_updated: 2026-08-21
flow: tick
reproduction: >-
  to-be-written TagTreeCutoverCheckIT.fileOnlyMappingKeyRulingsRefuseLoudWithZeroMutation
  (child of a 2+ decomposition; `start` converts the marker per workflow §0).
  The wrong behavior it states (the D-13 trap, reproduced live in the C1
  rehearsal, /tmp/opencode/c1/): on a pre-V84 database carrying V84-MAPPING-KEY
  data (a `development` tag row, a post {openai,ai,development}, a source
  {development}) plus a D-2-shaped runtime file (Development, Java), the
  rulings file the apply-coverage trap forces (`development: drop`,
  `java: drop` — the only legal rulings, since a map onto a not-yet-seeded
  leaf refuses at load_actions, tag-tree-cutover.sh:444-449) makes
  `tag-tree-cutover.sh apply` exit 0 after DELETING the development/java tag
  rows and STRIPPING every development/java element from the arrays —
  3,177 post rows + 42 source rows in the live run (apply-real.out:
  "tag removed: 5", "post.tags rewritten: 3177") — data V84 is designed to
  MAP to software-development, silently destroyed, postflight GREEN
  afterwards. The fixed behavior the test asserts: apply refuses those
  rulings loud (exit 2 naming the key ruling) with zero mutation.
analysis_ref: docs/plan/m1/tick-analysis/cutover-apply-mapping-key-destruction.md
blocked_by: []
files_scope:
  - prod/scripts/tag-tree-cutover.sh
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java
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
    (docs/spec/deployment.md §Topology). The mapping keys' DB-side
    conversion is V84's frozen job; this ticket only stops the pre-migrate
    tooling from destroying what V84 maps. Probe: git diff --name-only
    shows no db/migration path (migration_touch: false).
  - >-
    The consumed-rulings printout's wording/classification and the §7.14
    steps 5-6 lifecycle rewrite — M1-899 owns those. This ticket's tests
    assert the consumed NAMES appear, never the surrounding wording, so
    M1-899 lands without re-touching this ticket's pins (analysis P5).
  - >-
    The span-parser FAIL text and the jq question — M1-900.
  - >-
    Changing reconcile-file's classification semantics (leaves kept, keys
    converted, nostr/video dropped, rulings for the rest — M1-888, pinned
    by the reconcileFile* cases). This ticket touches shared validation
    code (parse_rulings / validate_coverage) only as named in the Approach;
    every existing reconcile pin must pass unmodified.
  - >-
    prod/scripts/upgrade.sh — the gate runs preflight only
    (UpgradeWiringTest legs pass unmodified); upgrade.sh never mutates tag
    data (M1-887 P4).
  - >-
    The postflight all-rows-vs-leaf-only file predicate (M1-887 r1's
    RECOMMENDED-NEW-TICKET, analysis P12) — pre-existing, recorded open,
    owner's call; NOT fixed here.
  - >-
    New audit rows, GRANT changes, or service-role widening — the apply
    stays an owner-role pre-boot psql action (docs/spec/security.md §DB
    roles).
acceptance:
  - "TagTreeCutoverCheckIT.fileOnlyMappingKeyRulingsRefuseLoudWithZeroMutation (the converted reproduction) passes — seeds the C1/D-13 shape on the real Testcontainers DB (a `development` tag row, a post {openai,ai,development}, a source {development} — all V84-mappable key data; plus a `java` tag row) and a runtime-file fixture carrying [Development, Java, AI]; with the rulings file the trap forces (`development: drop`, `java: drop`), `apply` refuses with exit 2 NAMING the key ruling line and the reason (a V84 mapping key is never ruled — the DB side is V84's job, the file side converts in reconcile-file), and ZERO mutation: both tag rows survive, the post/source arrays are byte-identical, preflight stays RED (failure mode: the destruction vector is unwritable even when hand-authored — analysis P1/P3). `--dry-run` refuses identically (load-time validation precedes the dry branch, the M1-887-r2 shape)."
  - "TagTreeCutoverCheckIT.applyDemandsRulingsForDbUnknownsOnly passes — seeds a DB unknown `ai-image` (tag row + post {ai, ai-image} + scope_tag follow), DB key elements (post {openai,ai,development}, source {development}), and a file fixture [Development, Java, AI-Image]; a rulings file carrying ONLY `ai-image: ai` (no development/java lines): apply exits 0 — no 'has no ruling' for the file-only key names (coverage demand = DB unknowns, analysis P2) — executes exactly the ai-image ruling (post rewrites to {ai}, follow re-points, tag row retired), and every development/java row/element is BYTE-IDENTICAL afterwards (the witness post keeps {openai,ai,development}); `apply --dry-run` lists exactly the executed ruling and never a file-only name as a plan line (analysis P7); a ruling for a name in NO current inventory still refuses as extra/stale with exit 2 and zero mutation (the P14/P15 guard preserved — analysis P2); a file-only NON-key ruling (e.g. an `ai-image` line with ai-image absent from the DB inventory) is tolerated, NOT executed against DB surfaces, and reported as reconcile-file's business — never printed under the retire-now instruction (failure mode: file-only rulings never leak into DB execution, analysis P1)."
  - "The skeleton no longer signposts the trap (analysis P4): TagTreeCutoverCheckIT.dynamicUnmappedNamesFailThePreflight passes with its ONE authorized modification (Out-of-scope below) — seeded with a file-side `Development` finding, the written skeleton carries NO `# development: drop`-style placeholder for the mapping key and instead an informational comment that V84 mapping keys convert deterministically (no ruling line), while the file finding itself still prints in the preflight output and the non-key placeholders (ai-image) and the standing ACTIVE `video: drop` pre-fill are unchanged."
  - "Every pre-existing pin passes unmodified except the one authorized modification: applyRefusesAnInvalidRulingsFile (all refusal legs incl. absent-target), applyAppliesTheRulingsFile (collision re-point, dedup, bystanders, staleness), the three reconcileFile* cases, preflightGreenStateExecutesV84Cleanly (the real-V84 mirror pin — analysis P6), and the three UpgradeWiringTest legs (probe: mvn verify)."
  - "docs/design/07-deployment.md §7.14 step 4 is corrected minimally: placeholders exist for unknown names that NEED a ruling; a file tag that is a V84 mapping key needs no line — it converts deterministically (DB side: V84 itself; file side: reconcile-file) and a ruling naming one is refused. Probes: grep -n 'complete every placeholder' docs/design/07-deployment.md context now scopes the instruction; grep -o 'tag-tree-cutover.sh [a-z-]*' docs/design/07-deployment.md still matches only implemented subcommands."
  - "The scripts meet the prod/scripts conventions and the credential discipline (analysis P9): executable, set -euo pipefail, shellcheck-clean (author-run, the M1-427 convention), PGPASSWORD environment only. Probes: grep -n 'source.*secrets.env' prod/scripts/tag-tree-cutover.sh returns nothing; grep -n 'PGPASSWORD' prod/scripts/tag-tree-cutover.sh shows only the existing read/export lines; ls -l mode 0755."
  - "mvn verify from repo root is green; the diff touches no file under infochat-core/src/main/resources/db/migration/ and modifies NO pre-existing test beyond the one named in Out-of-scope (engineering-rules §8)."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java — new cases: fileOnlyMappingKeyRulingsRefuseLoudWithZeroMutation, applyDemandsRulingsForDbUnknownsOnly
  modifies:
    - >-
      TagTreeCutoverCheckIT.dynamicUnmappedNamesFailThePreflight — the
      single §8-authorized modification stated in plain language in
      Out-of-scope below (the skeleton no longer carries a placeholder for
      a file-side mapping key).
  preserves:
    - >-
      all other tests currently green on main — in particular, unmodified:
      every other TagTreeCutoverCheckIT case, every UpgradeWiringTest case,
      TagTreeMigrationIT, BootstrapLoaderIT, TaggerWorkerSweepIT,
      RestoreFlywayChecksumIT.
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
reviews:
  - round: 1
    date: 2026-08-21
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 194 insertions(+), 37 deletions(-)"
    verdict_file: .scratch/tick-review-M1-898-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-21
  verdict: PASS
  note: >-
    start pre-flight: tick-lint 0 BLOCKERs (3 WARNs — P8/P14/P15 have no
    Verification entry; P8/P10/P11 are siblings M1-899/M1-900's, P14/P15 are
    M1-887 cross-references; notify-and-continue). Every Root-cause/Approach
    citation spot-checked against the tree and holds (KEY_TARGET :126-135,
    PREFLIGHT_SQL :161, file_inventory :196, write_skeleton :225,
    parse_rulings :344, validate_coverage :379, load_actions :418,
    ruled_names_sql :455, dry_run_plan :464, apply_transaction :512,
    PGPASSWORD :976-977, 07-deployment.md:1716, the IT's :71-72 placeholder
    pin). Census grep re-ran clean: one KEY_TARGET mirror, consumers
    :138/:651-652/:762 only. Analysis cross-read: P1-P7 + P9 all landed in
    the ticket; P8/P10/P11 are explicitly out_of_scope (M1-899/M1-900), P12
    recorded open. blocked_by empty, replaces empty. Module check for
    --parallel: M1-897 (infochat-llm-adapter) is the only in-flight ticket;
    this ticket is infochat-core + prod/scripts + docs — no overlap, both
    migration_touch: false. No ambiguity; no blocking question needed.
escalation_reason:
---

# M1-898: Scope cutover apply to DB unknowns; refuse key rulings

## Context

Release-blocking defect D-13 of the v2.0.0 verification campaign (C1 FAIL,
`.scratch/V2.0.0-VERIFICATION-PLAN-2026-08-20.md` :482, :507-509; defects
log `.scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md` D-13): on the D-2
deployment shape (a runtime bootstrap-sources.json carrying legacy
title-cased tags), `tag-tree-cutover.sh apply` silently destroys
V84-mappable DB data. The live C1 rehearsal on a restored v65 dump
destroyed the `development`/`java` tag rows and stripped every
development/java element from 3,177 post rows + 42 source rows
(/tmp/opencode/c1/apply-real.out) — the witness post {openai,ai,development}
became {openai,ai} (bystanders-before.txt) — and postflight stayed GREEN
because the remaining data is internally consistent. This blocks the v2.0.0
tag and the prod cutover runbook. Full analysis:
docs/plan/m1/tick-analysis/cutover-apply-mapping-key-destruction.md. Do not
restate the spec — cite `spec_refs:`.

## Root cause

Verified (analysis §Ground truth): apply's coverage scope and execution
scope disagree. validate_coverage (tag-tree-cutover.sh:379-413) demands one
ruling per member of the DB∪file UNION inventory — including file-only
mapping keys, which file_inventory (:196-220) flags (correctly: a key in
the file must be converted). But a map ruling for a key is impossible
pre-migrate (load_actions :444-449 refuses an absent target row), leaving
`drop` as the only legal ruling; the skeleton even writes the baited
placeholders (:272-279; /tmp/opencode/c1/map-before-edit.txt:22-26) and
runbook step 4 says "complete every placeholder" (07-deployment.md:1716).
apply_transaction (:512-585) then executes EVERY active ruling against all
four DB surfaces with no file-only / no-DB-occurrence guard — and the key
names were never DB unknowns (PREFLIGHT_SQL :161-179 exempts them because
V84 maps them), so their DB rows/elements were live V84 input. The drop
rulings the coverage trap forced leak into DB destruction.

## Pitfalls

Numbered consistently with the analysis document:

- P1: Fix the execution side, not only the coverage side — the invariant
  "apply executes a ruling against DB surfaces ONLY when the name has
  current DB-unknown occurrences" must be enforced in
  apply_transaction/dry_run_plan themselves, independent of the mirror
  (defense in depth; the bystander byte-identity control travels —
  engineering-rules §10, M1-887 P8).
- P2: Do not weaken the staleness/extras guard while scoping coverage — a
  ruling for a name in NO current inventory still refuses exit 2 (M1-887
  P14/P15); the relaxation is scoped to names in the current FILE
  inventory only.
- P3: A mapping-key ruling refuses LOUD, never silently skips — exit 2
  naming the line and the deterministic paths (V84 DB-side, reconcile-file
  file-side). Silent skipping is the fail-loud contract violated in the
  opposite direction (M1-866 out_of_scope; §2).
- P4: The skeleton must stop signposting the trap — no placeholder for
  file-side mapping keys (the informational comment instead); non-key
  unknowns keep theirs (reconcile-file needs those rulings). Carries the
  family's ONE §8-authorized pre-existing test modification.
- P5: Fixture calibration — this ticket's new tests assert consumed NAMES,
  never the printout wording M1-899 owns; nothing here may pin a
  representation a sibling is mandated to change (the M1-785 lesson).
- P6: Mirror drift — the key refusal consults the script's ONE KEY_TARGET
  mirror (:126-135, 19 names), never a second list;
  preflightGreenStateExecutesV84Cleanly (drives the real V84 classpath
  resource) stays the pin.
- P7: Dry-run/real parity — the dry-run plan lists exactly the rulings
  that will execute; a plan line the real run will not execute is the
  M1-887-r1 Finding-1 shape in miniature.
- P9: Credential discipline untouched — PGPASSWORD environment only,
  non-sourcing read (:54-69, :976-977); no new credential path.

## Approach

Derived from `spec_refs:`: §Topology makes V84 immutable — the keys'
DB-side mapping is V84's frozen job, so the pre-migrate tooling must never
execute rulings against key data; §Sources and tags commits the stored
normalized form the inventories and rulings keys match by; §Bootstrap
behavior on startup keeps the loader gate as the file-side backstop;
§DB roles keeps this an owner-role psql action with no new surface.

**Files to touch** (guidance, not an allowlist):

1. `prod/scripts/tag-tree-cutover.sh`
   - parse_rulings (:344-374): refuse a ruling whose NAME is a V84 mapping
     key (`${KEY_TARGET[$name]:-}` set) — exit 2 naming the line and
     stating that mapping keys are never ruled (DB side: V84 itself; file
     side: reconcile-file converts deterministically). Shared with
     reconcile-file, which already refuses such lines as stale — the
     earlier, clearer refusal is consistent (P3).
   - validate_coverage (:379-413): the coverage DEMAND iterates DB_UNKNOWN
     only; the staleness/extras refusal still runs against the union
     inventory (IN_INVENTORY) unchanged (P2). File-inventory rulings
     without DB-unknown occurrences are tolerated and reported as
     reconcile-file's business, never as retire-now consumed lines and
     never as plan lines.
   - apply_transaction (:512-585) + dry_run_plan (:464-507) +
     ruled_names_sql (:455-462): restrict the executed/counted/scanned set
     to rulings whose names are in DB_UNKNOWN (P1, P7). The transaction
     order (scope_tag → tag rows → arrays), ON CONFLICT DO NOTHING,
     order-preserving dedup, per-surface counts, and the consumed-lines
     printout's DB_UNKNOWN filter (:604-614) are unchanged in wording —
     P5.
   - write_skeleton (:225-290): skip mapping keys when emitting
     placeholders; emit an informational comment that key names convert
     deterministically and take no ruling (P4). The no-clobber rule and
     the standing nostr/video pre-fill are untouched.
   - load_actions (:418-451): the M1-887-r2 informational nit — the dead
     `lineno=0` local at :419 — may be deleted on this touch of the
     function; no behavioral change there.
2. `infochat-core/.../TagTreeCutoverCheckIT.java` — the two new cases
   (acceptance 1-2) + the ONE authorized modification (acceptance 3).
3. `docs/design/07-deployment.md` — §7.14 step 4's minimal correction
   (acceptance 5); do NOT rewrite steps 5-6 (M1-899).

**Steps, in order:** script changes first (the IT consumes them) → write
the reproduction case and run it RED against the pre-change script (exit 0
+ destroyed witness data today) → fix green → applyDemandsRulingsForDbUnknownsOnly
→ the skeleton modification → runbook step 4 → shellcheck + probes +
mvn verify.

**Controls to preserve (engineering-rules §10):** total validation before
any mutation with zero-mutation refusals (M1-887 P14 — every
applyRefusesAnInvalidRulingsFile leg stays green, and the key refusal ADDS
a leg); the staleness/extras refusal against the union (P2); the apply's
audit surface (per-surface counts, consumed-names printout — names and the
"consumed rulings" header present, wording unpinned here); dry-run purity
and bystander byte-identity pins; the nostr/video standing ruling
(skeleton pre-fill + map refusal; they are never keys — disjoint sets);
the skeleton's no-clobber rule; credential discipline (P9); postflight's
predicates untouched; upgrade.sh untouched.

**Pitfall→mitigation mapping:** P1→the execution-side guard + acceptance
1/2's zero-mutation and byte-identity assertions; P2→acceptance 2's
stale-line leg + the preserved applyRefusesAnInvalidRulingsFile legs;
P3→the parse_rulings refusal + acceptance 1; P4→write_skeleton change +
acceptance 3; P5→out_of_scope + assertion discipline in the new cases;
P6→the single mirror + the preserved preflightGreenStateExecutesV84Cleanly;
P7→acceptance 2's dry-run plan assertion; P9→acceptance 6's probes.

## Definition of done

Every `acceptance:` item, verified by its named test/probe: the converted
reproduction green (trap rulings refuse loud, exit 2 naming the line, zero
mutation, byte-identical key data); the DB-unknowns-only case green
(apply succeeds without file-only lines, executes exactly the DB ruling,
key data byte-identical, dry-run parity, stale-line leg intact, file-only
non-key ruling tolerated-not-executed); the skeleton carries no key
placeholder (the one authorized modification green); every other pin
passes unmodified; §7.14 step 4 corrected with its probes; credential
probes + shellcheck green; `mvn verify` green; no db/migration diff hunk.

## Verification

- P1/P3 → TagTreeCutoverCheckIT.fileOnlyMappingKeyRulingsRefuseLoudWithZeroMutation
  (failure mode, the reproduction) — feeds the trap rulings against seeded
  key data; asserts exit 2 naming the ruling and byte-identical rows/arrays.
  A mutation that executes file-only rulings, or refuses silently, fails it.
- P1/P2/P7 → TagTreeCutoverCheckIT.applyDemandsRulingsForDbUnknownsOnly —
  the inverse shape: no file-only lines needed, exact execution set,
  {openai,ai,development} surviving byte-identical, dry-run listing exactly
  the executed set, the stale-line refusal intact, the tolerated-not-executed
  file-only non-key ruling. A coverage regression (demanding file names) or
  an execution regression (running file-only rulings) fails it.
- P4 → the authorized modification's assertions (acceptance 3): a skeleton
  still emitting a key placeholder fails it.
- P5 → test_plan.preserves + the reviewer's TEST-INTEGRITY-CHECK; M1-899's
  ticket carries the printout wording this ticket deliberately leaves
  unpinned.
- P6 → TagTreeCutoverCheckIT.preflightGreenStateExecutesV84Cleanly
  (existing, unmodified): the real V84 resource over every known-set class.
- P9 → acceptance 6's greps + shellcheck (author-run); security_relevant:
  true raises the review gate.
- acceptance item 5 → the named greps on §7.14.
- acceptance item 7 → `mvn verify` exit 0 + the git-diff probe.

## Out-of-scope

Per the YAML block: no migration edit; no printout-wording or §7.14 steps
5-6 work (M1-899); no span-parser or jq work (M1-900); no reconcile-file
classification change; no upgrade.sh touch; the postflight leaf-predicate
gap stays open (analysis P12); no audit rows or grants.

**Authorized pre-existing test modification (engineering-rules §8 — plain
language):** `TagTreeCutoverCheckIT.dynamicUnmappedNamesFailThePreflight`
currently asserts the written skeleton carries a `# development — file (1)`
placeholder line (:71-72). This ticket stops the skeleton from emitting
placeholders for V84 mapping keys (they convert deterministically; a ruling
for one is refused). The case's seeding and its preflight-output assertions
are unchanged; the skeleton assertion is replaced by: NO `development`
placeholder/ruling line appears, an informational deterministic-conversion
comment appears, and the non-key placeholder (`ai-image`) plus the standing
ACTIVE `video: drop` pre-fill are unchanged. The property the case pins —
first RED writes a complete, never-clobbered rulings skeleton — is
unchanged; only the key-placeholder content moves, because the placeholder
was the D-13 trap's signpost (P4).

## Census

Class: **rulings-file line classes the two verbs can meet.** Re-runnable
enumeration: every line parse_rulings accepts falls into exactly one of —
(i) DB-unknown name (apply executes); (ii) file-only non-key unknown
(reconcile-file consumes; apply tolerates, never executes); (iii) V84
mapping key (NEVER ruled — this ticket's loud refusal; DB side is V84's,
file side reconcile's); (iv) nostr/video (standing disposal ruling,
skeleton pre-filled, map refused); (v) name in no current inventory (stale,
refused). Dispositions: (i) unchanged execution; (ii) guarded out of DB
execution by this ticket; (iii) refused by this ticket; (iv) unchanged;
(v) unchanged refusal. The grep for the mirror the refusal consults:
`grep -n 'KEY_TARGET' prod/scripts/tag-tree-cutover.sh` — one mirror, all
consumers. Examined and excluded: post.tag_candidates and frozen content
(never rewritten — the M1-880 census, carried by M1-887).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-898-cutover-apply-db-unknowns-only.md
```
