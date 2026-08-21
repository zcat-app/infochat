---
id: M1-902
title: "Make postflight's runtime-file check leaf-only"
status: done
created: 2026-08-21
last_updated: 2026-08-21
flow: tick
reproduction: >-
  app.zcat.infochat.core.schema.TagTreeCutoverCheckIT.postflightFileCheckIsLeafOnly
  (written and run RED before the fix: the top-name-through-standalone-
  postflight shape was reasoned at the M1-887-r1 review, not driven live
  before `start`).
  The wrong behavior it states: on a post-migrate database (V84 applied),
  with the runtime bootstrap-sources.json carrying a TOP name — `news` is a
  tag-tree top node (V84__tag_tree_seed_and_migration.sql:52) —
  `tag-tree-cutover.sh postflight` exits 0 printing
  "GREEN: bootstrap-sources.json tags[] all name tag-tree nodes"
  (prod/scripts/tag-tree-cutover.sh:958), because the file check tests
  membership in ALL tag rows (:942, `SELECT name FROM tag ORDER BY name`),
  while the Collector boot gate the check claims to mirror —
  BootstrapLoader.failFastOnNonLeafTags
  (infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java:312,
  `SELECT name FROM tag WHERE node_kind = 'leaf' AND name = ANY(?)`) —
  REFUSES the same file at the next boot ("not an existing source-eligible
  leaf tag-tree node", :326-327). A GREEN postflight — the runbook's final
  gate (docs/design/07-deployment.md §7.14 step 8) — does not guarantee the
  boot it gates. The fixed behavior the test asserts: the same top-carrying
  fixture REDs the postflight (exit 1; the RED line names `news` and states
  the leaf requirement), while an all-leaf fixture stays GREEN with the
  pinned positive wording.
analysis_ref: self
blocked_by: []
files_scope:
  - prod/scripts/tag-tree-cutover.sh
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The Collector's BootstrapLoader gate itself
    (infochat-collector/**/BootstrapLoader.java) — it is the CORRECT
    reference this ticket mirrors, never touched. Probe: git diff
    --name-only shows no infochat-collector path.
  - >-
    The DB-side postflight predicates (POSTFLIGHT_SQL,
    tag-tree-cutover.sh:847-875, and their seven checks :895-936) —
    examined for the same all-rows weakness and found NOT weak: those rows
    verify leftover-freedom (the V84/E4002 contract — no stored name
    outside the tree), and no boot gate predicates leaf-ness on post.tags /
    source.bootstrap_tags / scope_tag content; the file check is the only
    postflight row that mirrors a boot gate. Untouched (P1).
  - >-
    The file check's lower-casing mechanics (:948, bash `tr`) versus the
    loader's NFC + `Locale.ROOT` normalization — pre-existing difference,
    not this defect; reconcile-file writes normalized stored forms, so the
    post-reconcile file carries no mixed-case names. Aligning the two
    normalizers is a separate question (engineering-rules §1).
  - >-
    The in-flight family's surfaces: M1-898 (parse_rulings /
    validate_coverage / load_actions / apply_transaction / dry_run_plan /
    write_skeleton; §7.14 step 4), M1-899 (the consumed-rulings printout
    :604-614; §7.14 steps 5-6), M1-900 (the span-parser FAIL text :88-89;
    the §7.14 shape sentence). This ticket's regions are disjoint —
    postflight()'s file-side block (:938-962) and §7.14 step 8 (:1720)
    only. See the sequencing note in Context.
  - >-
    Any migration file, upgrade.sh, reconcile-file classification, or Java
    production change (migration_touch: false — probe: git diff
    --name-only shows no db/migration path).
acceptance:
  - "TagTreeCutoverCheckIT.postflightFileCheckIsLeafOnly (the converted reproduction) passes — on a post-migrate database (the class's runV84 mechanics, :595), a runtime-file fixture carrying the TOP name `news` (a tag-tree top node, V84:52) alongside the leaf `ai`: `postflight` exits 1 and its RED line keeps the pinned prefix `RED: bootstrap-sources.json tags[]`, names `news`, and states the LEAF requirement in the loader's own terms (its message language is 'source-eligible leaf tag-tree node', BootstrapLoader.java:326-327) — RED today: exit 0 with the GREEN line (the failure mode: the final gate GREENs a file the next boot refuses; implements docs/spec/schema.md §Sources and tags — bootstrap_tags are a leaf-only reference set). Two discriminating legs: (a) an all-leaf fixture (`ai`, `world` — V84:98/:111 leaves) exits 0 with the BYTE-IDENTICAL pinned line `GREEN: bootstrap-sources.json tags[] all name tag-tree nodes`; (b) a fixture naming an operator parented leaf present on the DB (the 'football'-under-'sport' shape preflightGreenStateExecutesV84Cleanly seeds at :254) is also GREEN — the leaf set comes from the live DB query, never the script's 53-name IS_LEAF mirror (P4). Non-vacuity (engineering-rules §8): reverting :942 to all-rows greens the `news` leg; consulting the mirror instead of the DB REDs leg (b) — both mutations fail this case. Boundary siting: assertions are on the operator-facing stdout and exit code, the end of the path."
  - "Every pre-existing pin passes UNMODIFIED — this ticket modifies NO pre-existing test (engineering-rules §8): cutoverRehearsalPassesPostflight (:215) and runtimeBootstrapFileNamesOnlyTreeNodes (:234 RED prefix, :240 GREEN line) pin the exact GREEN wording and the RED prefix this ticket preserves (P2); the seven DB-side GREEN/RED texts are byte-untouched, so the live-pinned 8/8 shape (/tmp/opencode/c1/postflight-live2.out) stays reproducible on an all-leaf file. Probe: mvn verify."
  - "docs/design/07-deployment.md §7.14 step 8 (:1720) states the leaf-only predicate: its final clause says every runtime-file tag names a tree LEAF — postflight now mirrors the loader's boot gate (docs/spec/deployment.md §Bootstrap behavior on startup), so a GREEN postflight guarantees the next Collector boot passes the file gate. Probes: grep -n 'leaf' docs/design/07-deployment.md hits the step-8 line; grep -o 'tag-tree-cutover.sh [a-z-]*' docs/design/07-deployment.md still matches only implemented subcommands."
  - "The DB-side predicates and the script conventions are untouched (P1, P5): git diff shows no hunk inside POSTFLIGHT_SQL (:847-875) or the seven DB-side checks (:895-936); the script stays shellcheck-clean (author-run, the M1-427 convention) and mode 0755; grep -n 'PGPASSWORD' prod/scripts/tag-tree-cutover.sh shows only the existing read/export lines (:976-977)."
  - "mvn verify from repo root is green; the diff touches no file under infochat-core/src/main/resources/db/migration/ and no infochat-collector path."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java — new case: postflightFileCheckIsLeafOnly
  preserves:
    - >-
      all tests currently green on main — in particular, unmodified: every
      existing TagTreeCutoverCheckIT case (including the two
      GREEN-wording pins at :215/:240 and the RED-prefix pin at :234),
      BootstrapLoaderIT (the loader gate's own pin — the reference is
      never touched), TagTreeMigrationIT, UpgradeWiringTest.
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/deployment.md §Bootstrap behavior on startup
decision_refs:
  - D38
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates: M1-887
reviews:
  - round: 1
    date: 2026-08-21
    verdict: REWORK
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 44 insertions(+), 17 deletions(-)"
    verdict_file: .scratch/tick-review-M1-902-r1.txt
    note: >-
      Medium: leg (b) of postflightFileCheckIsLeafOnly seeds/feeds
      `football` — a name on the script's 53-name IS_LEAF mirror and
      V84-seeded — so the P4 mirror-mutation greens the leg; the leg does
      not discriminate live-DB query from mirror. Fix: seed/feed a leaf
      outside the mirror (e.g. `rugby` under `sport`).
  - round: 2
    date: 2026-08-21
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 88 insertions(+), 18 deletions(-)"
    verdict_file: .scratch/tick-review-M1-902-r2.txt
    note: >-
      r1 item SATISFIED via a documented deviation: the named seed-a-54th-leaf
      mechanics were undeliverable (they trip the pinned 9/53 census row), so
      leg (b) discriminates via a census-neutral rename of the seeded
      `football` row to the mirror-unknown `rugby`; mirror-mutation probe REDs
      the leg, live query GREENs it, full mvn verify green.
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  lint: "PASS with WARN NEGATIVE-TESTS; no BLOCKER"
  developer: "PASS — acceptance is implementable; cited code and spec refs resolve; census returns the two classified SELECT sites; M1-898/M1-899/M1-900 are done, so no in-flight file conflict; no blocked_by tests require tracing"
escalation_reason:
---

# M1-902: Make postflight's runtime-file check leaf-only

## Context

`tag-tree-cutover.sh postflight` is the cutover runbook's final gate
(docs/design/07-deployment.md §7.14 step 8): its whole job is to be the
trustworthy last word before the operator declares the cutover done. Its
last check — "bootstrap-sources.json tags[] all name tag-tree nodes" —
verifies each runtime-file tag by membership in ALL tag rows
(:942, `SELECT name FROM tag ORDER BY name`), but the Collector boot gate
it claims to mirror is LEAF-ONLY (BootstrapLoader.java:312,
`WHERE node_kind = 'leaf'`). A standalone postflight over a post-migrate DB
whose runtime file carries a TOP name (hand-edited file after reconcile, a
restored clone, skipped steps — exactly the off-path use a prod cutover
runbook invites) prints GREEN, and the next Collector start then fails the
loader's leaf gate: a GREEN postflight does not guarantee the boot it
gates. Found at the M1-887-r1 review (RECOMMENDED-NEW-TICKET,
.scratch/tick-review-M1-887-r1.txt:172-186, rated LOW urgency because the
documented §7.14 sequence is protected — preflight's file check IS
leaf-only, file_inventory :196-220 via the IS_LEAF mirror at :206, so the
same file is flagged before migrate); carried open as P12 of
docs/plan/m1/tick-analysis/cutover-apply-mapping-key-destruction.md
(:296-303); owner called for filing 2026-08-21. Live evidence pinning the
current wording: /tmp/opencode/c1/postflight-live2.out (8/8 GREEN on an
all-leaf shaped copy).

**Sequencing vs the in-flight family (M1-898/M1-899/M1-900, approved
2026-08-21, pending, not merged).** The deciding question — does any
assertion or runbook paragraph actually overlap — answers NO, verified
against the three ticket files: the family touches parse_rulings /
validate_coverage / load_actions / apply_transaction / dry_run_plan /
write_skeleton (M1-898), the consumed printout :604-614 (M1-899), and the
file_tag_names FAIL text :88-89 (M1-900) — all disjoint from postflight()'s
file-side block (:938-962); the family's new IT cases are disjoint from
this ticket's one new case; the family's §7.14 edits are steps 4 / 5-6 /
the shape sentence — disjoint from step 8 (:1720). The only message pin
near this surface is M1-900's preserved "cannot read every \"tags\""
prefix (a different message on the exit-2 parser path — no collision with
the GREEN/RED lines this ticket touches). Therefore `blocked_by: []` — but
SEQUENTIAL execution: same script file, same IT class, same §7.14
subsection, same Maven module (which already forbids --parallel); if any
family member is in flight, land in sequence, and re-resolve this ticket's
line refs against the post-family tree at `start` (do not assume the
family's post-merge text).

## Root cause

Fully proven. postflight()'s file-side block (:938-962) populates its
membership set with `SELECT name FROM tag ORDER BY name` (:942) — every
tag row, tops included — and the comment above it (:938-939) claims this
is "the BootstrapLoader gate's own predicate". It is not: the loader's
failFastOnNonLeafTags queries `SELECT name FROM tag WHERE node_kind =
'leaf' AND name = ANY(?)` (BootstrapLoader.java:312) and rejects any file
tag not in that set with "not an existing source-eligible leaf tag-tree
node" (:326-327), failing the boot (:331-336). The spec commitment the
loader enforces is docs/spec/schema.md §Sources and tags (:309-313):
"A source's `bootstrap_tags` are a leaf-only reference set: every value
MUST name an existing `tag` row whose `node_kind` is `leaf`." Postflight's
weaker predicate admits exactly the names (the 9 tops) that the boot gate
rejects. A top reaches the file only off the documented path — reconcile-file
converts or drops every non-leaf and preflight flags any file non-leaf
(:205-206) — which is the review's low-urgency rationale, and exactly why
the standalone final gate must not share the weaker predicate.

**Examined and cleared (per the brief's scope instruction):** the DB-side
rows (POSTFLIGHT_SQL :847-875) use the same all-rows membership shape
(`NOT EXISTS (SELECT 1 FROM tag t WHERE t.name = e.name)`, :866-872) but
do NOT have the weakness: they verify leftover-freedom — the V84/E4002
contract that no stored name sits outside the tree — and no boot gate
predicates leaf-ness on post.tags / source.bootstrap_tags / scope_tag
content (top nodes remain valid for follow/filter operations,
schema.md:312-313). Only the file check mirrors a boot gate. The DB-side
rows stay untouched.

**Brief discrepancies (verified, minor):** the loader lives in
infochat-collector (`.../collector/bootstrap/BootstrapLoader.java`), not
infochat-provider as the brief states; the all-rows query is at :942 (the
r1 review cited :737 — line drift the brief warned of; the cutover
analysis's :942 still resolves).

## Pitfalls

- P1: Do not "fix" the DB-side rows while here — the same all-rows shape
  is not a weakness there (Root cause, examined-and-cleared). Widening
  them to leaf-only is scope drift (engineering-rules §1) and could
  false-RED legitimate post-migrate content the runbook never promised to
  police. The diff stays inside postflight()'s file-side block.
- P2: The GREEN wording is pinned byte-for-byte by two pre-existing tests
  (TagTreeCutoverCheckIT.java:215 and :240) and the C1 campaign artifacts
  (postflight-live2.out's 8/8 shape); the brief requires the positive
  wording stay recognizable. Keep
  "GREEN: bootstrap-sources.json tags[] all name tag-tree nodes" IDENTICAL —
  it stays TRUE under the leaf predicate (leaves are nodes), and changing
  it would force two §8-authorized test modifications for zero behavioral
  gain. The RED suffix beyond the pinned prefix
  "RED: bootstrap-sources.json tags[]" (:234 pins only the prefix) MUST
  change: "name(s) not tag-tree nodes: news" would be a FALSE statement
  about a top name (news IS a node) — the new suffix states the leaf
  requirement in the loader's own "source-eligible leaf" terms
  (BootstrapLoader.java:326-327).
- P3: The discriminating fixture must discriminate (the M1-785 lesson —
  a pin true of both options breaks its own purpose). A non-node name
  ("claude") is already RED today and proves nothing; the discriminating
  input is a TOP name — `news`, a top post-V84 (V84:52) — GREEN under
  all-rows, RED under leaf-only. The all-leaf leg (`ai`, `world` — V84
  leaves :98/:111) pins the positive path and the pinned wording.
- P4: The leaf set MUST come from the live DB query
  (`WHERE node_kind = 'leaf'`), never the script's IS_LEAF mirror.
  Postflight's first row (history84, :895-900) already proves V84 applied,
  so node_kind exists; and a legitimate post-migrate DB can carry operator
  parented leaves OUTSIDE the 53-name mirror —
  preflightGreenStateExecutesV84Cleanly seeds "football" under "sport"
  (:254) and drives the real V84 over it. A mirror-based check would
  false-RED a file the loader's DB-query gate accepts — reintroducing a
  GREEN-postflight-but-failed-boot class in the opposite direction. The
  mirror exists for the PRE-migrate gates, which must answer on pre-V82
  schemas with no node_kind column (:97-98 comment); postflight never runs
  there. Mirroring the loader means mirroring its data source too.
- P5: Comment truthfulness (engineering-rules §11): the comments at
  :844-846 and :938-939 describe the file check as mirroring the loader
  gate — false today precisely in the predicate this ticket changes.
  Touching the code means re-reading those comments as claims about the
  NEW code: the :938-939 comment's "an existing node" must name the leaf
  gate. (:844-846's "mirror the BootstrapLoader node gate's predicate on
  the file side below" reads TRUE post-fix and stays — §1.)
- P6: Coordination with the in-flight family — no blocked_by (no
  assertion or runbook-paragraph overlap, Context) but sequential
  execution, and no assumption of the family's post-merge text or line
  numbers: this ticket's regions are disjoint by the family tickets' own
  named surfaces; if M1-898/899/900 lands first, re-resolve :938-962 /
  :1720 at `start`.

## Approach

Derived from `spec_refs:`: schema.md §Sources and tags commits
bootstrap_tags to leaf-only membership in the live tag rows — the
postflight file check verifies exactly that commitment for the deployed
file, so its predicate becomes the loader's; deployment.md §Bootstrap
behavior on startup is the boot the postflight gates (the Collector loads
the bootstrap sources file at startup; §7.14 already cites this section
for the loader gate), which is why the loader is the reference and stays
untouched.

**Files to touch** (guidance, not an allowlist):

1. `prod/scripts/tag-tree-cutover.sh` — postflight()'s file-side block
   (:938-962) only:
   - :942 — the membership query gains the loader's predicate:
     `SELECT name FROM tag WHERE node_kind = 'leaf' ORDER BY name` (P4).
   - :938-939 — the comment re-stated to name the leaf gate (P5).
   - :960 — the RED line's suffix states the leaf requirement
     ("not source-eligible tag-tree leaves", the loader's :326-327
     language); the pinned prefix "RED: bootstrap-sources.json tags[]"
     is kept (P2).
   - :958 GREEN line, the empty-name skip (:945-947), the lower-casing
     (:948), the raw-form missing-list accumulation (:949-955), and the
     exit-code contract (:964-967) are UNTOUCHED. The usage text (:37-39)
     says only "runtime file tags" — no predicate claim, no change.
2. `infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java`
   — the one new case (acceptance 1). NO pre-existing test modified.
3. `docs/design/07-deployment.md` — §7.14 step 8's final clause (:1720):
   "every runtime-file tag naming a node" → every runtime-file tag naming
   a tree LEAF; postflight now mirrors the loader's boot gate, so a GREEN
   postflight guarantees the next boot passes the file gate.

**Steps, in order:** write postflightFileCheckIsLeafOnly and run it RED
against the current script (the `news` fixture exits 0 GREEN today) → the
script edit → green (all three legs) → the §7.14 step-8 clause → shellcheck
+ probes + mvn verify.

**Controls to preserve (engineering-rules §10):** the rerouted path is the
file-side check; its incidental obligations all travel — the fail-loud
parser contract (file_tag_names exit 2 on unreadable spans, :87-91 —
M1-900 owns its text; this ticket never feeds an unreadable span), the
empty-name skip, the pre-membership lower-casing, the RAW file forms in
the missing list (operator-facing — the loader likewise reports raw
forms, :326), exit 1 on any RED with every other row's output unchanged,
and the byte-identical GREEN line pinned at :215/:240 (P2). The seven
DB-side rows and their texts are untouched (P1). Credential discipline
untouched (PGPASSWORD environment only, :976-977; the change adds one
WHERE clause to a read-only SELECT — no new surface; security.md §DB roles
posture unchanged, hence security_relevant: false).

**Pitfall→mitigation mapping:** P1→the diff boundary + acceptance 4's
git-diff probe; P2→the byte-identical GREEN + prefix-preserved RED +
acceptance 2's unmodified-pins; P3→the `news` top-name fixture (the
discriminating input) + the all-leaf leg; P4→the DB-query predicate +
acceptance 1's operator-leaf leg (b); P5→the comment edit + acceptance 4;
P6→Context's sequencing note + `start`-time re-resolution.

## Definition of done

Every `acceptance:` item, verified by its named test/probe: the converted
reproduction green (top-carrying file REDs with exit 1, the RED line
names `news` and the leaf requirement; all-leaf and operator-leaf fixtures
GREEN with the pinned wording); every pre-existing pin passes unmodified
(no pre-existing test touched); §7.14 step 8 states the leaf-only gate
with its probes green; the DB-side predicates provably untouched (git-diff
probe) with conventions probes green; `mvn verify` green; no migration or
infochat-collector hunk.

## Verification

- P2/P3 → TagTreeCutoverCheckIT.postflightFileCheckIsLeafOnly (failure
  mode, the reproduction) — feeds the post-migrate DB a file carrying the
  TOP name `news`; asserts exit 1, the pinned RED prefix, `news` named,
  and the leaf requirement stated. Mutations it catches: reverting :942
  to all-rows (news leg greens); a RED text still claiming "not tag-tree
  nodes" (the leaf-requirement assertion fails); a reworded GREEN line
  (the byte-identical assertion fails — as do the unmodified :215/:240
  pins, acceptance 2).
- P4 → the same case's leg (b): an operator parented leaf on the DB
  ("football" under "sport", the :254 seeding shape) named in the file
  stays GREEN — a mirror-consulting implementation REDs it.
- P1 → acceptance 4's git-diff probe (no hunk in POSTFLIGHT_SQL or the
  seven DB-side checks) + the rehearsal pin
  (cutoverRehearsalPassesPostflight) green unmodified.
- P5 → acceptance 4 + the reviewer's comment-truthfulness leg
  (engineering-rules §11): the file-side comment names the leaf gate.
- P6 → process probe at `start`: re-resolve this ticket's line refs
  against the then-current tree; the disjoint-regions claim is verified
  by the family tickets' named surfaces (Context), and any in-flight
  family member forces sequencing (same Maven module, no --parallel).
- acceptance item 3 → the named greps on §7.14 step 8.
- acceptance item 5 → `mvn verify` exit 0 + the git-diff probes.

## Out-of-scope

Per the YAML block: the BootstrapLoader gate is the correct reference,
never touched; the DB-side postflight predicates stay all-rows (examined,
cleared — Root cause); the file check's `tr` lower-casing vs the loader's
NFC + Locale.ROOT normalization is a pre-existing, separate question
(§1); every M1-898/899/900 surface (their script functions, their IT
cases, §7.14 steps 4-6 and the shape sentence) is theirs — this ticket's
regions are disjoint and the execution is sequential, not blocked; no
migration, upgrade.sh, reconcile-file, or Java production change.
**This ticket modifies NO pre-existing test** — the GREEN wording and RED
prefix pins are preserved by construction (P2).

## Census

Class: **runtime-file tag membership predicates in the cutover tooling.**
Re-runnable enumeration: `grep -n 'SELECT name FROM tag'
prod/scripts/tag-tree-cutover.sh` returns :427 (apply's map-target
EXISTENCE check — a different job: the target's leaf-ness is already
enforced upstream by parse_rulings' IS_LEAF validation; disposition:
out-of-class, examined) and :942 (the file-side membership set — THIS
ticket's fix; disposition: fixed). The mirror-based file predicates:
file_inventory (:205-206, IS_LEAF — already leaf-only, correct for the
pre-migrate gate) and reconcile-file's resolve_tag classification
(mirror-based, converts keys/drops ruled names — correct). Every returned
site has a disposition; the class has exactly one defective site.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-902-postflight-leaf-only-file-predicate.md
```

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-902-r1.txt):
1. Finding 1: make leg (b) of
   TagTreeCutoverCheckIT.postflightFileCheckIsLeafOnly discriminate the
   data source — seed and feed a parented leaf whose name is outside the
   script's frozen known-set (e.g. `rugby` under `sport`) at
   TagTreeCutoverCheckIT.java:424-425. Verified by: (a)
   `sed -n '103,118p' prod/scripts/tag-tree-cutover.sh | grep -w rugby`
   returns nothing and the test source names `rugby` in
   seedParentedOperatorLeaf; (b) `mvn verify` green with
   postflightFileCheckIsLeafOnly passing, and the same test failing on
   the leg-(b) exit assertion when the live query at
   prod/scripts/tag-tree-cutover.sh:1007 is locally replaced by the
   IS_LEAF mirror membership.
