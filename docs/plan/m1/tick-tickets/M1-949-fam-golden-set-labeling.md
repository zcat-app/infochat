---
id: M1-949
title: "Fam golden set: label the broad-distribution world"
status: pending
created: 2026-08-28
last_updated: 2026-08-28
flow: tick
reproduction: >-
  Child of a 2+ decomposition (analysis
  docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md); the RED legs
  are to-be-written per workflow §0 (only this child can make them writable —
  the fam resource does not exist yet); /tick start converts the marker:
  write the tests, run them RED against the unmodified tree before any
  fixture edit. The wrong behavior (fixture
  ticket, the M1-928 posture — the missing set IS the defect): the broad
  -distribution leg has no answer key — `ls
  infochat-provider/src/test/resources/retrieval-eval/golden-set-fam.jsonl`
  returns ENOENT (verified 2026-08-28) and the validator's world map knows
  only the tech set (RetrievalGoldenSetTest.java:33 hardcodes
  /retrieval-eval/golden-set.jsonl; :35-43 bake the tech class vocabulary and
  floors). Observed consequence: no fam-leg reading can exist, so no product
  -wide retrieval claim can ever be gated on both legs (the two-world
  instrument has one leg). RED tests (to-be-written, converted at start):
  RetrievalGoldenSetTest#famSetMeetsWorldFloors (fails RED: fam resource not
  on the classpath) and
  RetrievalGoldenSetTest#famRecordsCarryTheReplicaFingerprint (same).
analysis_ref: docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md
blocked_by: [M1-948]
files_scope:
  - infochat-provider/src/test/resources/retrieval-eval/golden-set-fam.jsonl
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — infochat-provider/src/main/** is
    untouched; the labels DESCRIBE shipped retrieval on the fam world, they
    do not change it (the M1-942 posture; probe: git diff --name-only names
    no src/main path).
  - >-
    Touching the tech golden set — golden-set.jsonl stays BYTE-IDENTICAL
    (every tech validator leg, the M1-944 record's golden_set_sha256 pin,
    and the campaign's gating reference depend on it; analysis P15) — probe:
    git diff shows no change to golden-set.jsonl.
  - >-
    The harness/runner — M1-950 owns world resolution; this ticket lands the
    fixture + validator only.
  - >-
    Verbatim user chat text as query forms — the cs forms are AUTHORED
    strings motivated by real Czech general-news usage against the replica's
    Czech sources (Novinky.cz/Echo24), never quotes of real users' messages
    (that would need explicit user approval; analysis P9). A verbatim quote
    in the fixture fails review.
  - >-
    A price class — price data lives in price_snapshot (deployment-global,
    not part of the fam world's post distribution); the fam set's needs come
    from fam's own distribution (analysis P1).
  - >-
    ANY docs/spec/** edit — the measured contract (security.md semanticSearch
    row) is cited, never amended.
acceptance:
  - "REPRODUCTION closed: RetrievalGoldenSetTest.famSetMeetsWorldFloors passes — the committed fam set meets the world-keyed floors with arithmetic stated in the ticket body: temporal-today >= 5, temporal-2h >= 5, temporal-24h >= 4 (windows derived from the REPLICA's max ready_at — the P7 DB-state binding, never wall clock), topical >= 16 across >= 10 DISTINCT broad information needs drawn from the fam distribution census (MUST include economy, world affairs, public health or medicine, football, other-sports, and at least two regional needs among asia/middle-east/americas — analysis P1), cross-lingual >= 16 with cs >= 12 across >= 4 needs (real cs usage; optional es/ru/tr rows to 16 for cross-leg comparability); floors sum 46, total cap band re-derived over ACTIVE fam records with the arithmetic in the body (the M1-942 re-derivation precedent, analysis P15)."
  - "Every fam record carries labeled_against.db_fingerprint byte-equal to the REPLICA fingerprint printed by M1-948's fingerprint verb — a record carrying the TECH fingerprint (ready=5214;…06ed…927) is REJECTED (world-keyed pin; analysis P14's label side) — probes: RetrievalGoldenSetTest.famRecordsCarryTheReplicaFingerprint green; FAILURE-MODE corrupted-copy leg: one record's fingerprint swapped for the tech pin fails validation with the named rejection."
  - "Selection is census-cited (analysis P1): the ticket body carries the fam distribution census (tag counts + source counts from the replica readout, restated from M1-948's run record) and each need's rationale names its derivation (pooled SQL population ∪ returned-window adjudication, the M1-928/M1-942 two-direction pipeline) with the full-pool size; a need with no census support fails review — probe: rationaleAndPoolingFieldsPresent-style leg green over the fam file; the body's census table matches the M1-948 run record."
  - "Cross-lingual rows follow the tech contract (security.md:329 D58 anchoring; llm.md §Translation flow :334-336 — the anchor runs because the embedding store is English): each xling row names its ACTIVE English sibling IN THE SAME FILE and carries that sibling's adjudicated set VERBATIM; the query form is an authored cs/es/ru/tr string for the need (cs-dominant per real usage) — probes: the world-keyed xlingRowsCarryNeedAnchor + sibling-equality legs green over the fam file; FAILURE-MODE: a one-uid drift in an xling row's set fails 'xling-set-drift'."
  - "Freeze discipline inherited verbatim: records carry id/class/query/scope_lang/expected.retrieval/rationale/labeled_at/labeled_against; |E| <= 16; corrections (none expected at first freeze) are supersedes PAIRS, never in-place edits; retired rows excluded from floors (the active-only filter) — probes: the existing schema/corrupted-copy legs re-run world-keyed over the fam file: failureModeRetiredRecordDoubleCounts and failureModeOversizedExpectedSet equivalents green (a 17-uid fam set fails 'label cap')."
  - "Every pre-existing TECH leg of RetrievalGoldenSetTest stays green UNMODIFIED (test_plan.modifies authorizes only the world-keyed EXTENSION — shared validators parameterized by world, tech constants byte-identical; analysis P15) — probe: git diff over RetrievalGoldenSetTest.java shows no deleted assertion; mvn verify green from repo root (plain JUnit, no DB)."
  - "git diff --name-only names exactly the files_scope paths plus board/frontmatter regen; no user-derived text and no path under .opencode/worktrees/** in the diff (analysis P9/P16) — probe: git status --porcelain + a grep of the fixture for user-shaped data (message ids, addresses) returns nothing."
test_plan:
  adds:
    - >-
      RetrievalGoldenSetTest — the fam legs: famSetMeetsWorldFloors,
      famRecordsCarryTheReplicaFingerprint, the world-keyed xling/supersedes/
      corrupted-copy equivalents over golden-set-fam.jsonl (RED first per
      reproduction).
  modifies:
    - >-
      RetrievalGoldenSetTest (AUTHORIZED: the class gains a world-keyed
      fixture map — tech constants (GOLDEN_SET path, KNOWN_CLASSES,
      CLASS_FLOORS, cap band) stay byte-identical for the tech world; a fam
      world entry adds the fam resource, fam classes/floors/cap, and the
      replica fingerprint constant; the shared validators parameterize on
      the world; new expected behavior — fam legs validate the fam file,
      tech legs validate the tech file).
  preserves:
    - >-
      every existing tech leg of RetrievalGoldenSetTest byte-identical in
      behavior (schema, floors, freeze, fingerprint, xling, corrupted-copy)
      — the regression control the two-world instrument depends on.
    - all tests currently green on main.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Translation flow
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D19
  - D29
  - D58
  - D59
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

# M1-949: Fam golden set: label the broad-distribution world

## Context

The two-world instrument's broad leg has no answer key. The tech golden set
(59 active records, every one pinned to the tech fingerprint) cannot ground
claims about fam's mainstream — economy/world/health/sports with ai/cyber as
tail, the exact inversion of the tech world — and the tech world is
structurally incapable of it (medicine 28 / football 20 posts there). This
ticket authors the fam set: labeled queries over the frozen replica
(M1-948's isolated postgres) selected from fam's OWN distribution, with a
cs-weighted cross-lingual slice that exercises the D58 anchor pipeline
against the replica's Czech-source posts — the same architecture, per spec
(D29: both retrieval arms operate on the English derived field corpus-wide).
Shared analysis: `analysis_ref:`. Blocked on M1-948 — labels bind to the
pinned replica fingerprint.

## Root cause

Not a code defect — a missing fixture (the M1-928 posture). Verified: no fam
resource exists on the test classpath; the validator hardcodes the tech
resource (:33) and bakes the tech vocabulary/floors (:35-43) with no world
notion. The labeling machinery is prior art adopted wholesale: the
M1-928/M1-942 two-direction pooling pipeline (pooled SQL population ∪
returned-window adjudication), rationale-per-record, |E| ≤ 16, supersedes
freeze, xling-sibling verbatim inheritance, fingerprint-on-every-record. What
is genuinely NEW is the frame discipline (analysis P1): fam's needs come from
fam's census, not from the tech set's shape.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P9, P11-adjacent
(label scoping), P15, and the labeling halves of P5/P6/P7 (labels bind to the
replica's pinned end state).

- P1: frame-inheritance — mirroring tech classes or picking needs by taste
  re-creates the niche artifact in mirror image; needs are census-cited
  (economy/world/public-health|medicine/football/other-sports + regional
  needs REQUIRED by the floors).
- P9: authored cs forms only — never verbatim user text.
- P15: validator coupling — fam floors/caps re-derived with arithmetic over
  ACTIVE records; tech legs byte-identical (the regression control); loader
  semantics untouched (M1-943 owns retired-skip; this ticket owns fam
  pairing/floors only).
- P5/P6/P7 (labeling halves): labels are valid ONLY for the replica's pinned
  end state (post-migration, coverage-pinned); the replica fingerprint (not
  the tech pin, not live fam's moving fingerprint) is on every record;
  temporal windows derive from the replica's max ready_at.

## Approach

- **Files to touch** — `files_scope`: the fam fixture and the validator's
  world-keyed extension (plus operator-local labeling working data under
  gitignored `.bench/retrieval-eval/`).
- **Steps in implementation order:**
  1. Write the fam legs RED (workflow §0): famSetMeetsWorldFloors,
     famRecordsCarryTheReplicaFingerprint — both fail today (no resource).
  2. Operator pre-flight: verify the replica fingerprint (two consecutive
     reads, M1-948's verb), restating the census (tags, sources, languages)
     as the selection evidence base (P1).
  3. Select needs FROM the census (economy, world affairs, public
     health/medicine, football, other-sports, asia/middle-east/americas
     regional needs, environment, + remaining broad needs to the floors);
     author queries; pool two-direction per class; adjudicate; write records
     with rationale + derivation + the replica fingerprint (|E| ≤ 16).
  4. Author the xling slice: ≥ 4 needs with ACTIVE English siblings in the
     file; cs forms for all (≥ 12 rows), optional es/ru/tr to 16; verbatim
     sibling-set inheritance, sibling named in notes.
  5. Extend the validator world-keyed (P15): fam constants with the floors
     arithmetic in the body; parameterized shared validators; the
     wrong-world-fingerprint rejection leg.
  6. Drive green; `mvn verify` from the repo root; diff fences.
- **Controls to preserve (§10):** the tech set and every tech validator leg
  stay byte-identical (test_plan.modifies authorizes only the extension);
  the default suite's composition changes only inside
  RetrievalGoldenSetTest; no production path touched.
- **Pitfall→mitigation:** P1→steps 2-3 (census-cited selection, floors
  mandate the broad/regional needs); P9→step 4 authored forms; P15→step 5
  parameterization + arithmetic + preserves; P5/P6/P7→step 2 pre-flight +
  fingerprint on every record.

## Definition of done

The fam legs pass (floors with arithmetic, replica fingerprint on every
record, wrong-world rejection fires); the tech legs are green unmodified;
every fam record carries rationale/derivation/the replica fingerprint; the
xling slice inherits verbatim with named siblings; `mvn verify` is green from
the repo root; the diff touches nothing outside `files_scope` and contains no
user-derived text.

## Verification

- P1 → the body's census table (restated from M1-948's run record) + each
  need's rationale naming its derivation; reviewer cross-checks every need
  against the census (a tech-mirrored need with no census row fails review).
- P9 → grep of the fixture for user-shaped data returns nothing; query forms
  are authored strings (reviewer check against the census/needs list).
- P11-adjacent (label scoping) → no fam row encodes a cross-leg expectation:
  every record's class/rationale is fam-world-scoped and pins the REPLICA
  fingerprint, never the tech set's; the wrong-world-fingerprint rejection
  leg (the P5/P6/P7 entry below) is the executable guard.
- P15 → famSetMeetsWorldFloors green with the stated arithmetic; the tech
  legs green byte-identical (git diff shows no deleted assertion); the
  corrupted-copy fam legs (retired double-count, oversized set, xling drift,
  wrong-world fingerprint) all fire.
- P5/P6/P7 (labeling halves) → famRecordsCarryTheReplicaFingerprint green;
  the FAILURE-MODE leg swapping in the tech pin fails with the named
  rejection; each temporal rationale names the replica's max ready_at.
- acceptance items → the named legs/probes; the final item via
  `git diff --name-only` and repo-root `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: any production change; touching the tech golden set;
the harness (M1-950); verbatim user text; a price class; any spec edit. The
validator IS modified — authorized in `test_plan.modifies`: a world-keyed
extension with tech constants byte-identical (engineering-rules §8).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-949-fam-golden-set-labeling.md
```
