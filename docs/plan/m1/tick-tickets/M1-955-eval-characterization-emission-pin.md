---
id: M1-955
title: "Re-pin the retrieval characterization emission shape"
status: done
created: 2026-08-29
last_updated: 2026-08-30
flow: tick
reproduction: >-
  Single-ticket analysis (analysis_ref: self). The failing test that states
  the wrong behavior: RetrievalEvalCharacterizationIT#
  threeArmsRideTheProductionTool (in-tree, :100-101), whose every dispatch
  funnels through assertEmissionShape (:255 -> :261-278) — a PER-ROW exact
  equality assertEquals(expectedFields, fields) at :273 over
  Set.of("uid","title","url","ready_at","similarity") (:269). The wrong
  behavior is staged, not yet observable: the pin asserts a five-field
  tool shape that is current on TODAY's main
  (SemanticSearchTool.java:358-364 appends exactly
  uid/title/url/ready_at/similarity — the test is GREEN at main tip
  ff38802a), while the in-review M1-940 worktree (.worktree/M1-940,
  branch commit 370c60b9 per the brief) deliberately widens the emission
  to six fields — body_summary appended after similarity (worktree
  SemanticSearchTool.java:368-377; the fused SELECT carries the content
  columns at :258/:271/:285/:305) — and its diff touches NO eval file
  (verified: the worktree's copy of this IT still pins the five-field
  Set.of at :269; grep for body_summary over the worktree's eval package
  returns zero matches; the only worktree test files carrying it —
  SemanticSearchToolIT, SemanticSearchToolDiversityIT,
  SearchPostsToolTest — are exactly M1-940's own files_scope). The moment
  M1-940 merges, the pin is stale against the tool's new shape and the
  NEXT operator lane run of the tagged IT (@Tag("retrieval-eval") :50;
  CI-excluded by default, infochat-provider/pom.xml:168-173 + :229-255 —
  run only via the operator invocation in the class javadoc :37-46) goes
  RED on :273 with a field-set mismatch that names no cause. RED is
  deferred to start BY CONSTRUCTION (blocked_by: [M1-940], workflow §0
  executed at start on the post-merge tree): run the operator lane
  invocation FIRST, observe threeArmsRideTheProductionTool RED on the
  field-set assert, then apply the one-line fix, then green.
analysis_ref: self
blocked_by: [M1-940]
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalCharacterizationIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — the six-field emission is M1-940's
    landed behavior, consumed as-is (probe: git diff --name-only names no
    src/main path).
  - >-
    ANY other line of RetrievalEvalCharacterizationIT beyond the one
    expectedFields line (:269) — binding user decision: ONE-line scope. In
    particular the 12-pairs pin (:108) and CANONICAL_BY_NEED (:57-60) stay
    untouched even though M1-946's widening (four new xling rows, golden-set
    + validator only — its files_scope names no harness file) will
    independently stale them; that adjacent landmine is FLAGGED to the
    driver here, not fixed by this ticket (fixing it here would violate §1
    surgical scope and pin against a sibling's mid-flight state).
  - >-
    The answer-quality eval metric (binding user decision): M1-928 measures
    retrieval, not answers (two-world analysis P19's honesty framing) —
    campaign-level instrument work to be decided deliberately, never a
    rider on this un-red.
  - >-
    The lexical-row content-parity pin extension (default-OUT, confirmed):
    the per-row field-set assert already covers lexical-only rows — a
    mutation emitting body_summary only on semantic rows (similarity
    non-null) still reds :273 on the first lexical row, so the extension
    has NO discriminating mutation the field-set pin does not already
    catch; not added.
  - >-
    ANY change to RetrievalEvalRunnerIT, RetrievalEvalScorer, the golden
    sets, or the POM tag mechanics (the retrieval.eval.tag.excluded
    exclusion stays byte-identical; the lane stays operator-run).
  - >-
    ANY spec edit — the semanticSearch Output column is amended by M1-940's
    own diff (its acceptance item 10); this ticket FOLLOWS the amended row,
    never edits it.
  - >-
    Booting provider/collector against the test DB — the operator lane
    invocation (postgres + embedder + translator only) is the sole
    execution path; frozen-stack discipline (M1-945 P4).
acceptance:
  - "§8-AUTHORIZED test modification (engineering-rules §8 test-modification authorization; plain language): RetrievalEvalCharacterizationIT.assertEmissionShape's expectedFields (:269) gains body_summary — the set becomes exactly {uid, title, url, ready_at, similarity, body_summary}, one field per emitted row. WHY, in plain language: the pin's semantic is 'every emitted row carries exactly the tool's shape' (the :273 message), and the tool's shape deliberately gained body_summary in M1-940 — a user-approved change whose spec record (the amended security.md semanticSearch Output column) and landed emission both carry six fields. The test now asserts exactly what the spec-recorded tool shape is; nothing about the assertion's exactness changes. Verification: the full operator lane run of the tagged IT is GREEN after the edit — threeArmsRideTheProductionTool AND fingerprintRefusalFeedsMismatchedLabelAndRefuses both pass."
  - "RED-at-start leg (workflow §0, executed on the post-M1-940-merge tree — this is why the ticket carries blocked_by): BEFORE the edit, the operator lane invocation of the tagged IT (the class javadoc's documented command, :37-46) is run and observed RED on the :273 field-set assertEquals, with the failure message showing the five-field expected set against the six-field actual — the log lands at .scratch/tick-red-M1-955.log (the M1-945 convention); the merged tree is first confirmed to carry M1-940 (probe: grep -n 'body_summary' docs/spec/security.md returns the semanticSearch row — the M1-941 truthness-probe precedent)."
  - "FAILURE-MODE / non-vacuity (the pin keeps discriminating BOTH directions): the updated assert stays an EXACT per-row equality over the full field set — a field-OMITTING mutation (the emission's body_summary append deleted) and a field-ADDING mutation (a stray field appended to any row) each RED the same :273 assert; the item-2 RED log is the field-adding direction observed live (the merged emission's sixth field vs the stale pin). Probe: reviewer diff check that :269-273 keeps assertEquals(expectedFields, fields) — a loosening to contains/containsAll (§8 semantic: weakened assertions) fails review; git diff over the IT is exactly ONE hunk."
  - "Nothing else in the IT changes: git diff over RetrievalEvalCharacterizationIT.java names only the :269 line — the 12-pairs pin (:108), CANONICAL_BY_NEED (:57-60), the fingerprint-refusal test (:146-169), the double-invocation determinism leg (:140, :173-189), the scope-hygiene leg (:141, :193-219), and the zero-fallback assertion (:142) are byte-identical; git diff --name-only over the whole change names exactly the one files_scope path plus board/frontmatter regen (the M1-940 no-eval-file fence inverts here into a no-file-but-this-one fence)."
  - "SEQUENCING (binding): this ticket is done on main BEFORE any retrieval-eval lane run — before M1-946's labeling lane and before M1-952's two-leg record — and never runs --parallel with M1-954 (both are infochat-provider test scope; workflow §1 requires a different Maven module for parallel start). Probes: the board (STATUS-TICK.md git history) shows M1-955 done before M1-946/M1-952 leave their lane-run states; the driver's allocation record shows serial (never --parallel) placement against M1-954."
  - "mvn verify from the repo root is green with the eval stack ABSENT (engineering-rules §5) — the IT stays CI-excluded (POM untouched; probe: the verify log's failsafe list omits RetrievalEvalCharacterizationIT, the M1-950 verify-log convention; test-compile covers the edited line)."
test_plan:
  adds: []
  modifies:
    - >-
      RetrievalEvalCharacterizationIT.java :269 (expectedFields) — AUTHORIZED
      by this ticket: what the test must now assert (every emitted row
      carries EXACTLY uid, title, url, ready_at, similarity, body_summary)
      and why (the tool's emission shape deliberately gained body_summary in
      M1-940; the pin's semantic — exactly the tool's shape — follows the
      spec-recorded shape). One line; no other line of the class moves.
  preserves:
    - >-
      every other leg of RetrievalEvalCharacterizationIT (12-pairs pin,
      canonical fixtures, refusal, determinism, scope hygiene, zero
      fallback) — byte-identical.
    - RetrievalEvalRunnerIT and every lane fence — untouched.
    - all tests currently green on main.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/llm.md §Determinism boundary
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
reviews:
  - round: 1
    date: 2026-08-30
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "3 files, +32/-10 (IT one line: expectedFields gains body_summary; ticket frontmatter + clarity_check + this entry; board regen)"
    notes: >-
      0 rework items, 0 critical/high. Gate read the RED log (five-field
      expected vs six-field actual at assertEmissionShape:274, pre-edit) and
      the GREEN log (2/2 @Tests post-edit), confirmed the exact-equality
      assertion shape is preserved (both discrimination directions live),
      the CI-exclusion (0 mentions of the IT in the verify log), and the
      one-hunk one-file fence. Falsified-and-dropped by the gate: the
      overtaken sequencing leg (the landed M1-946/M1-952 runs are
      scored-lane and never execute this pin — parseToolJson :57-76 reads
      uid/similarity/ready_at only; the worktree artifact store holds
      exactly this ticket's own two characterization runs, git_commit
      f9a76203); the §13 port-naming concern (15432/18080/18081 are the
      frozen eval stack's fixture ports, already committed verbatim in six
      landed tickets and the IT's own javadoc :41); the content-leak
      masking concern (the pin compares field NAMES only — assertEquals at
      :274 — and never asserted content bounds in either version; the
      byte-cap contract is spec-owned, security.md:329, and pinned in
      M1-940's own test files). One RECOMMENDED-NEW-TICKET recorded under
      Review observations (scorer javadoc doc-rot; TOUCHED-BY-THIS-DIFF:
      no; no DECIDE-BEFORE).
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  >-
    Start-time self-check 2026-08-30, on the post-merge tree (M1-940 done at
    a916cbec; worktree gone; lint 0 findings). (1) Premise verified live:
    security.md:329 semanticSearch Output column carries body_summary,
    SemanticSearchTool.java:375 appends it, the IT still pins five fields —
    the sole `Set.of("uid"` site repo-wide (census re-run clean). (2) Citation
    drift, non-load-bearing: M1-946's merged canonical entry shifted the IT
    by +1 line — the Set.of sits at :270 and the assertEquals at :274 (ticket
    cites :269/:273); the same commit already widened the 12-pairs pin to 16
    and added the top-chips canonical (§8-authorized in M1-946's landing
    record), pre-clearing the P3 landmine this ticket had flagged. (3)
    Acceptance item 5's backward leg is overtaken by events: M1-946's
    labeling and M1-952's two-leg record landed 2026-08-30 while this ticket
    sat pending. No red fired — those were scored-lane runs (parseToolJson's
    additive-field tolerance, :57-76) that never execute this pin, and the
    characterization IT itself has not run post-merge (no characterization
    artifacts cite a commit >= a916cbec). The forward leg — done before the
    NEXT retrieval-eval lane run (the width-32 re-reads, next campaign step)
    — is intact and satisfiable; no in-flight tickets exist to serialize
    against (M1-954 done). No hurdle trigger; proceeding. (4) Eval stack
    confirmed left UP after M1-952's runs (postgres 15432 + llamacpp 18081
    + embeddings 18080); passwords from the test checkout's secrets.env,
    never printed.
escalation_reason:
---

# M1-955: Re-pin the retrieval characterization emission shape

## Context

M1-940 (in-review; worktree `.worktree/M1-940`, branch commit `370c60b9`
per the brief, based on main tip `ff38802a`) deliberately widens the
`semanticSearch` emission from five to six fields — `body_summary` after
`similarity` — and its diff intentionally touches NO eval file (verified in
the worktree: the eval package carries zero `body_summary` matches; the
three test files that carry it are exactly M1-940's own `files_scope`; the
worktree's copy of the characterization IT still pins the five-field set).
That fence leaves one committed landmine:
`RetrievalEvalCharacterizationIT` — M1-945's operator-run characterization
(@Tag("retrieval-eval") :50, CI-excluded via
`infochat-provider/pom.xml:168-173` + `:229-255`) — asserts on EVERY
dispatch that each emitted row carries EXACTLY
`{uid, title, url, ready_at, similarity}`
(`assertEmissionShape` :261-278, the Set.of at :269, the per-row
assertEquals at :273). The moment M1-940 merges, that pin is stale, and the
next owner-run of the lane goes RED mid-flow with a field-set mismatch
that names no cause — worst case mid-record on M1-952's two-leg run, after
M1-946's labeling. On CURRENT main the test is green (the five-field shape
is still the tool's shape there, `SemanticSearchTool.java:358-364`), so the
red exists only post-merge — hence `blocked_by: [M1-940]`. This ticket
pre-clears the landmine: ONE line, the authorized test-modification shape.
Single-ticket analysis (`analysis_ref: self`).

## Root cause

Not a code defect — a deliberately deferred acknowledgment. M1-940's
out_of_scope names the eval lane as "the eval side's own extension,
owner-run, never edited there", which is correct fence design (the emission
change and the eval-side pin follow stay independently reviewable), but it
leaves the characterization pin — the ONLY committed site that asserts the
emission's FULL field set (verified: `grep 'Set.of("uid"'` over the repo
returns exactly this one site) — bound to the pre-M1-940 shape. Mechanism,
verified end to end:

- TODAY's emission: `SemanticSearchTool.java:358-364` appends exactly
  `uid/title/url/ready_at/similarity` — the pin is green on main.
- POST-M1-940 emission: the worktree appends `,"body_summary":...` after
  `similarity` (:368-377; fused-SELECT content columns at :258/:271/:285/
  :305) — six fields per row, lexical and semantic rows alike.
- The pin: :269-273 reds on the FIRST row of the FIRST dispatch
  (`dispatch` :249-258 calls `assertEmissionShape` on every leg; the
  driving @Test is `threeArmsRideTheProductionTool` :100-101).
- The scored lane does NOT red: `RetrievalEvalScorer.parseToolJson`
  (:57-76) reads `uid`/`similarity`/`ready_at` only — additive-field
  tolerance pre-verified as two-world analysis P17. The runner
  (`RetrievalEvalRunnerIT:241`) consumes only through that parser. The
  CHARACTERIZATION pin is the sole red site — that parser-vs-pin gap is
  exactly this ticket.

Brief-vs-repo discrepancies (minor, none load-bearing): the brief cites the
refusal test at :144 (actual: comment :145, @Test :146, method :147) and
the POM mechanics at ":236" (the `excludedGroups` element is :245; :236
sits inside its explanatory comment; the property block is :168-173); the
brief calls M1-940 "in-review" while the committed board and ticket
frontmatter still read `pending ← runnable` (STATUS-TICK.md:29, :291) —
session-carried state, consistent with the worktree existing and review in
flight. The brief's "9 files" diff count is not re-derivable without git
here; the load-bearing half IS verified from file contents (no eval file
in the worktree carries the change; the three test files that do are
M1-940's own scope).

## Pitfalls

- **P1 — the §8 trap this ticket exists inside.** A test modified to match
  new behavior is the cardinal §8 semantic violation ("a test was modified
  to match a new (wrong) behavior") UNLESS the ticket authorizes the
  modification in plain language BEFORE the change: what the test must now
  assert and why. Here the "new behavior" is deliberate, user-approved,
  and spec-recorded (M1-940's amendment of the security.md semanticSearch
  Output column) — the authorization is genuine, not circular
  ("M1-940 changed the code so the test must follow" would be circular;
  "the pin's semantic is the tool's spec-recorded shape, and that shape is
  now six fields" is the authorization). Mitigation: acceptance item 1 +
  `test_plan.modifies` carry the plain-language authorization.
- **P2 — the weakening reflex.** "Fixing" the red by loosening
  `assertEquals(expectedFields, fields)` to a contains/containsAll check
  would green the six-field world while destroying the pin's exactness
  (its stray-field direction). §8 semantic: weakened assertions. The
  one-line change is to the EXPECTED VALUE, never to the assertion shape.
- **P3 — scope creep into the IT's other stale pins.** M1-946's widening
  (four new xling rows; its files_scope is golden-set + validator only)
  will independently stale the SAME class: the 12-pairs pin (:108) and
  possibly `CANONICAL_BY_NEED` (:57-60 with the :310-312 fixture assert).
  Fixing them here violates the binding one-line scope (§1) and pins
  against a sibling's mid-flight state. They are FLAGGED to the driver
  (Out-of-scope), not fixed.
- **P4 — sequencing.** Three ordering facts bite: (a) starting before
  M1-940 merges makes the RED leg unobservable (the IT is green on current
  main) — hence `blocked_by: [M1-940]`; (b) landing after M1-946's
  labeling or M1-952's record lets the red surface mid-lane with no
  attribution — land before any retrieval-eval lane run; (c) M1-954 is
  in-flight in the SAME Maven module (infochat-provider test scope, zero
  file overlap) — serialize, never `--parallel` (workflow §1's
  different-module rule).
- **P5 — the parser-tolerance misread.** Someone observing that the SCORED
  lane stays green (`parseToolJson` tolerates the additive field, :57-76)
  could conclude the lane needs nothing. But the characterization pin is
  not a scoring need — it is the instrument's PROVENANCE fence (M1-945 P6:
  "rows come from the tool, asserted by the tool's own emission shape").
  The gap parser-vs-pin is the whole ticket; the pin must follow.
- **P6 — the RED run's execution shape.** The IT is CI-excluded by design
  (pom :168-173, :229-255); the RED-at-start leg can only run on the
  operator lane (live eval DB + endpoints, the javadoc invocation :37-46).
  It must NOT be "fixed" by tampering with the tag mechanics (emptying the
  exclusion in the default suite, editing the POM) — §8's always-skip
  discipline and the lane's containment stay byte-identical.

## Approach

Derived from `spec_refs:` — security.md §Prompt-injection defenses owns the
semanticSearch Output column, i.e. the spec-recorded emission shape the pin
characterizes (as amended by M1-940's diff, which this ticket follows and
never edits); llm.md §Determinism boundary is the pinned-world premise the
whole lane rides (the emission the pin observes is SQL-decided and
reproducible, so "exactly the tool's shape" is a well-defined set).

- **Files to touch** — exactly one:
  `infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalCharacterizationIT.java`,
  line :269 — the expectedFields `Set.of` gains `"body_summary"`. Operator
  -local run logs land under gitignored `.scratch/` (the M1-945
  convention). Placement (§13): the one committed artifact is a
  test-source line — instance-free; no deployment-identifying material
  anywhere in the plan.
- **Steps, in implementation order:**
  1. Confirm M1-940 has merged (probe: `grep -n 'body_summary'
     docs/spec/security.md` returns the semanticSearch row — the truthness
     probe M1-941 specified for the same dependency).
  2. Run the operator lane invocation of the tagged IT (javadoc :37-46)
     RED-first; record the field-set assert failure at
     `.scratch/tick-red-M1-955.log` (P6; workflow §0).
  3. Apply the one-line change at :269 (P1/P2: expected value only).
  4. Re-run the lane GREEN — both @Tests of the class pass, determinism
     double-invocation leg included.
  5. `mvn verify` from the repo root (CI suite; the IT excluded, compile
     covered).
  6. Diff fences: one hunk in one file; no POM/spec/runner change.
- **Controls to preserve (engineering-rules §10):** the "reroute" here is
  one assertion's expected VALUE; the controls are the fences that
  assertion lives among — the refusal test (:146-169), double-invocation
  determinism (:140, :173-189), scope hygiene (:193-219), zero-fallback
  (:142) — all byte-untouched; the runner's fences and the POM tag
  mechanics untouched; the golden set consumed read-only.
- **Pitfall→mitigation:** P1→acceptance item 1's plain-language
  authorization; P2→acceptance item 3's exact-equality probe; P3→the
  one-hunk diff fence + the driver flag; P4→blocked_by + acceptance item
  5's board probes; P5→Root cause's parser-vs-pin statement; P6→step 2's
  operator-lane RED + the POM-untouched fence.
- **Alternatives considered (rejected; the commit message cites them):**
  (a) fold the pin update into M1-940's diff — breaks its own binding
  no-eval-file fence and couples the two reviews; (b) loosen the pin to a
  superset check — §8 weakening (P2); (c) also pin lexical-row content
  parity — no discriminating mutation beyond the field-set pin (the per-row
  loop already covers lexical rows), left out per the default-OUT rule;
  (d) ride an answer-quality eval metric — user-rejected as campaign-level
  instrument work (P19 framing); (e) also fix the 12-pairs/canonical pins
  — M1-946-adjacent landmine, out of the binding one-line scope (P3).

## Definition of done

The operator lane runs RED then GREEN in that order (RED log recorded
post-merge, pre-edit); the pin's expected set is exactly the six spec-
recorded fields with the assertion's exact-equality shape unchanged; the
diff is one hunk in one file; the ticket is done on main before M1-946's
labeling and M1-952's record, allocated serially against M1-954; repo-root
`mvn verify` is green with the eval stack absent; no fence inside or
around the lane moved.

## Verification

- P1 → acceptance item 1 (the §8 authorization text is IN the ticket) +
  the reviewer's TEST-INTEGRITY-CHECK reading it as authorization, not
  suspicion.
- P2 → acceptance item 3's diff probe (assertEquals on the full set kept;
  the two named mutations — field-omitting emission, stray field — each
  red it).
- P3 → acceptance item 4's one-hunk / one-file probes; the :108/:57-60
  pins byte-identical; the driver flag recorded (Out-of-scope).
- P4 → acceptance item 5's board-history probes (done before M1-946's
  labeling and M1-952's record; serial, never --parallel, vs M1-954) and
  the blocked_by frontmatter.
- P5 → Root cause's cited parser lines (:57-76) + the reproduction's
  sole-red-site grep (`Set.of("uid"` → one site repo-wide).
- P6 → step 2's RED log at `.scratch/tick-red-M1-955.log`; git diff shows
  no POM hunk; acceptance item 6's verify-log probe (failsafe list omits
  the IT).
- acceptance item 2 (the RED leg) → the recorded log itself: five-field
  expected vs six-field actual at :273.
- FAILURE-MODE coverage → acceptance items 2 + 3 (the observed sixth-field
  mismatch is the live hostile input; the exact-equality probe is the
  standing discriminator in both directions).

## Out-of-scope

Named in the YAML `out_of_scope` block: any production change; any other
line of the IT (the 12-pairs and canonical pins are M1-946-adjacent and
merely FLAGGED to the driver here); the answer-quality metric (binding
user decision — the P19 honesty framing); the lexical-row parity extension
(default-OUT with its no-discriminator reasoning stated); any runner/
scorer/golden-set/POM change; any spec edit (the security.md row is
M1-940's amendment, followed here); any provider/collector boot. The one
pre-existing test modified is authorized in `test_plan.modifies` with the
new expected behavior stated in plain language (engineering-rules §8).

## Census

The class this ticket guards: **committed sites that pin the semanticSearch
emission's field set.** Re-runnable enumeration: `grep -rn 'Set.of("uid"'`
over the repo. Rows (verified at draft time):

- `RetrievalEvalCharacterizationIT.java:269` — the five-field exact pin →
  **FIX** (this ticket; the only site M1-940's merge reddens).
- `SemanticSearchToolIT.java:261-296` regexes and
  `SemanticSearchToolDiversityIT.java:339-375` golden JSON and
  `SearchPostsToolTest` — emission-shape pins INSIDE M1-940's own
  files_scope, §8-authorized there and already updated in its worktree
  (verified: the worktree copies carry body_summary) → **DISPOSED** (owned
  by M1-940).
- `RetrievalEvalScorer.java:53-55` — the parseToolJson JAVADOC names the
  five-field shape in prose; a comment, not an assertion; no run reds on
  it; it reads stale post-merge → **DISPOSED** (out of the one-line scope
  per §1 surgical rule; flagged alongside the P3 pins for whoever next
  touches the scorer).
- `RetrievalEvalRunnerIT.java:241` — consumes via the tolerant parser only
  → **DISPOSED**, no pin.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-955-eval-characterization-emission-pin.md
```

## Review observations

- Round 1 (APPROVE) recommended-ticket observation, TOUCHED-BY-THIS-DIFF:
  no, no DECIDE-BEFORE — `RetrievalEvalScorer.java:53-55` (the
  `parseToolJson` javadoc) still documents the five-field emission shape
  `{uid,title,url,ready_at,similarity}`; a reader of the parser's contract
  today is told the emission lacks `body_summary`, while
  `SemanticSearchTool.java:368-379` appends it to every row. Pre-existing
  doc rot for whoever next touches the scorer — this ticket's §Census
  already flagged that line DISPOSED out of the binding one-line scope.
