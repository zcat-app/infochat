---
id: M1-942
title: "Golden-set label corrections, relabel, and extension"
status: done
created: 2026-08-27
last_updated: 2026-08-27
flow: tick
reproduction: >-
  RetrievalGoldenSetTest.adjudicatedCorrectionsPresent (written and run RED
  2026-08-27 before any fixture edit — observed: "expected: <18> but was:
  <0>" supersedes pairs; 0 retired targets; file 51 lines vs 77 expected):
  asserts the committed golden set carries the 2026-08-27 adjudicated
  corrections as supersedes pairs (18 pairs: el-2/el-4/el-5/el-3 + six
  topical relabels + the eight xl-ai-*/xl-cyber-* cascade successors) and
  active topical n = 16. A second RED leg,
  RetrievalGoldenSetTest.validatorAcceptsHonestShapes (observed RED:
  16-uid set rejected "label cap"; the 59-active end state rejected
  "outside 49-56"): a 16-uid expected set passes schema validation while
  a 17-uid set fails with "label cap", and the 59-active end state passes
  the re-derived floors/cap.
analysis_ref: docs/plan/m1/tick-analysis/golden-set-corrections.md
blocked_by: []
files_scope:
  - infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — infochat-provider/src/main/** is
    untouched; the corrected labels DESCRIBE shipped retrieval, they do not
    change it (probe: git diff --name-only names no src/main path).
  - >-
    The eval harness (RetrievalEvalRunnerIT loader/manifest) — M1-943 owns
    retired-record skipping and the golden-set hash; this ticket lands the
    set, not the runner that executes it.
  - >-
    The re-baseline run and its record — M1-944. No run quotes numbers off
    the corrected set until M1-943 has landed (analysis P5 ordering).
  - >-
    Fixing any retrieval weakness the corrected labels expose — the top-oss
    "open"→"OpenAI" lexical collision, top-crypto precision noise,
    duplicate-post collapsing, threshold derivation: recorded as
    observations in the adjudication report / record, never fixed here.
  - >-
    Re-labeling top-crypto or top-oss — the adjudication found their labels
    precise/defensible; their failure is retrieval-side (brief, binding).
  - >-
    ANY docs/spec/** edit — the measured contract (security.md semanticSearch
    row) is cited, not amended; no spec promise changes ride this ticket.
acceptance:
  - "RetrievalGoldenSetTest.adjudicatedCorrectionsPresent passes (written and run RED first, per reproduction): the committed set carries exactly 18 supersedes pairs — el-2 successor |E| = the one Czech-story row (Zcash-newsletter uid dropped), el-4 successor without the Mustang-Panda and Cavern-C2 uids (Kaspersky-as-source accident) but keeping Dahua/Manic with the tightened rationale, el-5 successor adding the GLM-5.3 row and keeping Jewelbug/vCenter + the arXiv China row with the looseness-naming rationale, el-3 successor without the Helgoland Bite uid, six topical successors (top-ai, top-cyber, top-ml, top-med, top-bio, top-robot) with FULL adjudicated sets, and the eight xl-ai-*/xl-cyber-* cascade successors inheriting their corrected siblings' sets verbatim — and active topical n = 16 after the extension (analysis P1/P2/P3/P9)."
  - "Every correction is a supersedes PAIR, never an in-place edit: each retired target gains ONLY the replaced_by field, each successor carries supersedes + labeled_against.db_fingerprint identical to the frozen fingerprint (docs/spec/llm.md §Determinism boundary — labels are bound to the pinned DB state) + a rationale naming its derivation and each drop/keep with its reason — probes: RetrievalGoldenSetTest.schemaRejectsMalformedRecords, failureModeSupersedesTargetStillValidates, failureModeSupersedesRetiredTargetPasses, and failureModeSupersedesAbsentTarget all green over the corrected file; git diff over golden-set.jsonl shows added successor lines plus single-field replaced_by insertions on targets and NOTHING else (analysis P2/P7)."
  - "Validator accommodates the honest shapes (binding user decision 3): MAX_EXPECTED_UIDS = 16, CLASS_FLOORS topical = 16, total cap re-derived over ACTIVE records (floors sum 57, expected active 59, cap 57-66), and classCoverageMeetsFloors filters retired records before counting — probes: classCoverageMeetsFloors passes at 77 file lines / 59 active records; RetrievalGoldenSetTest.validatorAcceptsHonestShapes asserts a 16-uid expected set passes schema validation while a 17-uid set fails with 'label cap'; fingerprintPinnedOnEveryRecord green over all 77 lines (new records carry the same frozen fingerprint)."
  - "FAILURE-MODE (the M1-928 r2 review observation, analysis P4): new RetrievalGoldenSetTest.failureModeRetiredRecordDoubleCounts feeds a corrupted copy that retires a temporal-today record whose successor is of a DIFFERENT class and asserts validateAll throws class-below-floor — the active-only filter is load-bearing, and a regression that counts retired records into class floors fails this leg."
  - "FAILURE-MODE (recalibrated ceiling, analysis P4/P11): failureModeOversizedExpectedSet pads an expected set to 17 uids and still fails with 'label cap'; a 16-uid set passes — the leg discriminates at the NEW cap, not the old one (the M1-785 stale-pin lesson)."
  - "The xling cascade stays consistent: every xl-ai-*/xl-cyber-* successor's notes name its ACTIVE English sibling (the corrected top-ai/top-cyber successor) and its expected set equals that sibling's set verbatim — probes: RetrievalGoldenSetTest.xlingRowsCarryNeedAnchor and failureModeXlingSetDriftsFromSibling green over the corrected file (analysis P3)."
  - "Extension queries are genuinely NEW topical information needs (not paraphrases of the existing eight topical queries or of each other), labeled via the M1-928 pooling pipeline (build-golden-set.py + pools-*.txt, operator-local) with two-direction adjudication (pooled SQL population ∪ returned-window adjudication), |E| <= 16, rationale naming derivation and full-pool size — the labels describe the measured contract (docs/spec/security.md §Prompt-injection defenses (LLM call sites), semanticSearch row; docs/spec/commands.md §Chat mode hybrid retrieval) without amending it — probes: adjudicatedCorrectionsPresent asserts active topical n = 16 with per-record |E| <= 16; rationaleAndPoolingFieldsPresent green; the eight new queries' distinctness reviewed against the file in the ticket's verification notes (analysis P1/P9)."
  - "mvn verify from repo root is green (the validator runs in the default suite, no DB); git diff --name-only names exactly the files_scope paths plus board/frontmatter regen — probe: git diff --name-only output."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
      — adjudicatedCorrectionsPresent, validatorAcceptsHonestShapes,
      failureModeRetiredRecordDoubleCounts (new legs mapping to acceptance
      items 1, 3, 4).
  modifies:
    - >-
      RetrievalGoldenSetTest.classCoverageMeetsFloors (authorized: gains the
      active-record filter; new expected behavior — retired records do not
      count toward class floors or the total cap, per analysis P4 and the
      M1-928 r2 review observation).
    - >-
      RetrievalGoldenSetTest.failureModeOversizedExpectedSet (authorized:
      pad target moves 9 → 17; new expected behavior — 17 uids fails with
      'label cap', 16 passes, per the raised MAX_EXPECTED_UIDS).
    - >-
      RetrievalGoldenSetTest constants MAX_EXPECTED_UIDS / CLASS_FLOORS /
      the 49-56 total cap (authorized: 16 / topical 16 / 57-66 over active
      records — the binding user decision 3 shapes).
  preserves:
    - all other tests currently green on main
    - >-
      every existing failure-mode leg of RetrievalGoldenSetTest (freeze
      pairing, fingerprint pin, xling sibling equality, rationale
      derivation) — recalibrated where named above, deleted never.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D19
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
reviews:
  - round: 1
    date: 2026-08-27
    verdict: REWORK
    checks: {SPEC-TRUTHNESS: FAIL, SECURITY: PASS, TEST-ADEQUACY: FAIL,
      MAINTAINABILITY: PASS, SCOPE: PASS}
    rework_items: 2
    critical_high: 0
    diff_stats: "4 files, +303/-55 (r1)"
  - round: 2
    date: 2026-08-27
    verdict: APPROVE
    checks: {SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: PASS,
      MAINTAINABILITY: PASS, SCOPE: PASS}
    rework_items: 0
    critical_high: 0
    diff_stats: "fix hunks 120 lines over r1; full diff 4 files, +361/-56"
    dispositions: "r1 items 1,2 SATISFIED (see .scratch/tick-review-M1-942-r2.txt)" 
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-27: start pre-flight passed — lint 0 findings; file:line citations
  spot-checked against RetrievalGoldenSetTest.java (MAX_EXPECTED_UIDS :47,
  label cap :142-144, 49-56 :161-163, pairing :95-121, unfiltered floors
  :267-277, fingerprint :300-308, xling :217-229, oversized pad :454-466);
  all seven analysis pitfalls present; no blocked_by; M1-943 landed first
  (ecc44d9a) per the approved wave order; operator surfaces (frozen DB
  volume, .bench pipeline, adjudication run artifacts) verified present on
  this host. No blocking ambiguity.
escalation_reason:
---

# M1-942: Golden-set label corrections, relabel, and extension

## Context

The retrieval-eval golden set (M1-928) — the answer key gating every future
retrieval change — is defective in two label layers, adjudicated 2026-08-27
(`.scratch/adjudication-report-20260827.md`): 7 confirmed per-record label
defects with binding user dispositions (Zcash-newsletter-as-Czech-news,
Kaspersky-attribution-as-Russia-news ×2, the omitted GLM-5.3 row, the
Fragments/Baeldung/Helgoland drops, the tightened keeps), and structural
incompleteness of all six snapshot topical classes whose relevant
populations are 2–3× the authoring cap — the measured topical recall 0.153
conflates a label artifact with real retrieval weakness. This ticket
corrects, relabels, and extends the set (topical 8→16, one-freeze
principle) and teaches the validator the honest shapes. Shared analysis:
`analysis_ref:`.

## Root cause

M1-928's pooling kept the newest ≤8 posts per broad topical query under the
|E|≤8 authoring ceiling (RetrievalGoldenSetTest.java:47, enforced
:142-144); the label sets are time-snapshot samples, not relevant
populations — the M1-748 one-sided-incompleteness defect resurfacing
through the cap. The entity-location defects are attribution judgment
errors of the same family (venue and researcher-attribution mentions read
as story nexus). The validator's caps encode the authoring ceilings as
invariants, so honest corrections are unrepresentable today: the 59-active
end state fails the 49-56 total check (:161-163) and honest topical sets
fail the 8-uid cap. Not provable in this checkout (operator-local, per
analysis P12): the uid-level identity of each adjudicated row — the
implementor re-derives it against the frozen DB from the adjudication
report BEFORE writing any record, after verifying the fingerprint
(analysis P7).

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P4,
P7, P9, P11.

- P1: snapshot-artifact relapse — relabeling as "newest ≤16" re-creates
  the defect one ceiling higher; pool BOTH directions (pooled SQL
  population ∪ returned-window adjudication incl. unlabeled relevant
  rows), rationale says so (family P4; M1-748 §3.2-3.3).
- P2: freeze violation — corrections are supersedes PAIRS (target gains
  only replaced_by; successor carries supersedes + corrected set +
  drop/keep rationale); in-place edits destroy the visible correction
  history the record's conventions require (validator :95-121 enforces).
- P3: xling cascade omission — validateXling (:217-229) forces every
  xling row to equal its ACTIVE English sibling's set; correcting top-ai
  and top-cyber cascades into 4+4 xl-* successors. 18 pairs, not 9-10
  (analysis Ground-truth discrepancy 1).
- P4: validator mis-calibration — MAX_EXPECTED_UIDS, topical floor, total
  cap, and the UNFILTERED classCoverageMeetsFloors (:267-277) must move
  together; leaving the happy-path call unfiltered double-counts retired
  records into class floors (the M1-928 r2 review observation).
- P7: fingerprint discipline — verify the frozen DB intact
  (ready=5214;…uid_sha256=06ed0de1…) BEFORE labeling reads; every
  successor and extension record carries the SAME labeled_against
  fingerprint (validator :300-308 asserts it over every line, retired
  included); M1-933/M1-934 deployments must not have touched the eval DB.
- P9: one-freeze — extension queries are NEW information needs, not
  paraphrases (paraphrase pairs recreate M1-748 §3.2 sibling-row
  inconsistency); extending later means unfreezing again (binding user
  decision 4).
- P11: §8 test-modification authorization — this ticket modifies
  pre-existing tests (classCoverageMeetsFloors, failureModeOversized-
  ExpectedSet, validator constants); each is named in test_plan.modifies
  with its new expected behavior; the recalibrated leg must discriminate
  at the NEW cap (M1-785 stale-pin lesson).

## Approach

- **Files to touch** — `files_scope`: the fixture JSONL and the validator
  test (plus operator-side labeling working data under gitignored
  `.bench/retrieval-eval/`, absent from a fresh clone).
- **Steps in implementation order:**
  1. Write the two RED legs first (workflow §0):
     `adjudicatedCorrectionsPresent` and `validatorAcceptsHonestShapes` —
     both fail on today's file (0 supersedes rows; 16-uid set rejected).
  2. Operator pre-flight: verify the frozen DB fingerprint (postgres
     only — models are NOT needed for labeling), re-derive the adjudicated
     uid identities from the adjudication report, and confirm the xling
     cascade list (P7, P12).
  3. Land the 18 supersedes pairs: the 4 entity-location corrections
     (approved drops + tightened keeps, each drop/keep named in the
     successor rationale), the 6 topical relabels to FULL adjudicated sets
     (two-direction pooling, deterministic 16-cap selection =
     adjudicated-relevant rows in returned-window rank order, rationale
     naming the full-pool size — top-robot may confirm its current set
     against the full pool, still as a successor record so the audit
     trail is uniform), and the 8 xl-ai-*/xl-cyber-* cascade successors
     naming the corrected active siblings (P1/P2/P3).
  4. Author the 8 extension records via the M1-928 pooling pipeline
     (build-golden-set.py + pools-*.txt): new topical information needs,
     two-direction adjudication, |E| ≤ 16, same frozen fingerprint (P9).
  5. Validator accommodation (P4/P11): MAX_EXPECTED_UIDS 16; topical
     floor 16; total cap 57-66 over ACTIVE records (floors sum 5+5+4+5+5+5
     +16+12 = 57; end state 59 active); classCoverageMeetsFloors gains the
     active filter; failureModeOversizedExpectedSet recalibrated to
     pad-to-17; add failureModeRetiredRecordDoubleCounts (the M1-928 r2
     observation leg).
  6. Drive everything green; `mvn verify` from the repo root.
- **Controls to preserve (§10):** no production path touched; the default
  suite's composition changes only inside RetrievalGoldenSetTest (named
  modifications above); every OTHER existing failure-mode leg (freeze
  pairing, fingerprint pin, xling equality, rationale derivation, floors)
  stays green unmodified — they are the controls that make the corrected
  file trustworthy.
- **Pitfall→mitigation:** P1→step 3 pooling + rationale; P2→step 3 pair
  shape (validator-enforced); P3→step 3 cascade; P4→step 5 coupled
  constants + active filter + new leg; P7→step 2 pre-flight + fingerprint
  on every record; P9→step 4 new needs; P11→test_plan.modifies
  authorization + discriminating recalibration.

## Definition of done

`adjudicatedCorrectionsPresent` and `validatorAcceptsHonestShapes` pass
(the RED legs of the reproduction, now green); the full validator suite is
green over the 77-line / 59-active-record file; every correction is a
supersedes pair with the frozen fingerprint; the xling cascade is
consistent; `mvn verify` is green from the repo root; the diff touches
nothing outside `files_scope`.

## Verification

- Operator pre-flight (P7, run 2026-08-27 before any labeling read): the
  frozen eval DB was brought up postgres-only from the test checkout and
  the fingerprint read EXACT — `ready=5214;max_ready_at=2026-08-24
  16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b
  103cc8ab8e064ffbe489727` (runner-equivalent SQL over the D59 world,
  uid-sha256 over uid-ordered concatenation). All adjudicated uid
  identities re-derived from that DB + run 20260827-115606 pass-1 windows;
  extension pools and row-by-row adjudication recorded operator-local in
  `.bench/retrieval-eval/{pools-extension-20260827.txt,
  adjudication-extension-20260827.txt}` (8 pools, sizes
  22/10/12/45/25/10/7/9; adjudicated sets 12/4/9/8/8/5/7/4 — every set is
  the FULL adjudicated population, no cap cut anywhere in the extension).
  Extension distinctness (P9): the eight needs — quantum computing,
  space industry, climate, semiconductors, physics research, drones,
  influence operations, video-game industry — are distinct from the
  existing eight topical queries (ai/crypto/cyber/ml/oss/med/robot/bio
  news-and-research needs) and from each other; row overlap across
  needs (e.g. the vCenter row in both el-5 and top-cyber-b) is legitimate
  cross-need relevance, not paraphrase.

- P1 → adjudicatedCorrectionsPresent (set shapes) +
  rationaleAndPoolingFieldsPresent (derivation named) + review of the six
  topical successor rationales against the adjudication report — each
  rationale names the two-direction pooling and the full-pool size; a
  relabel that only re-slices the newest 16 fails the rationale review.
- P2 → schemaRejectsMalformedRecords / failureModeSupersedesTargetStill-
  Validates / failureModeSupersedesRetiredTargetPasses /
  failureModeSupersedesAbsentTarget green; git diff over the JSONL shows
  added lines + single-field replaced_by insertions only — any other edit
  to a pre-existing record line fails the probe.
- P3 → xlingRowsCarryNeedAnchor + failureModeXlingSetDriftsFromSibling
  green; an un-cascaded correction fails the sibling-equality assertion.
- P4 → classCoverageMeetsFloors green at 77 lines / 59 active (an
  unfiltered count reads 77 and fails the 57-66 cap);
  failureModeRetiredRecordDoubleCounts fails validateAll with
  class-below-floor on the different-class-successor corruption;
  validatorAcceptsHonestShapes: 16 passes, 17 fails 'label cap';
  failureModeOversizedExpectedSet rejects the 17-uid pad.
- P7 → fingerprintPinnedOnEveryRecord green over all 77 lines; the
  operator pre-flight fingerprint read recorded in the ticket notes — a
  drifted DB aborts labeling (never relabels against a moved corpus).
- P9 → adjudicatedCorrectionsPresent asserts topical n = 16; the eight new
  queries checked against the existing topical queries and each other for
  paraphrase overlap — a near-duplicate of an existing need fails review.
- P11 → test_plan.modifies names each modified test; mvn verify green.
- acceptance items → the named legs above; the final item via
  `git diff --name-only` and the repo-root verify.

## Out-of-scope

Named in `out_of_scope`: any production/main-source change; the harness
(M1-943) and the re-baseline run/record (M1-944); fixing retrieval
weaknesses the corrected labels expose (oss lexical collision, crypto
precision noise, dedup, thresholds — recorded, not fixed); re-labeling
top-crypto/top-oss (labels stand); any spec edit. Pre-existing tests ARE
modified, each authorized by name in `test_plan.modifies` with its new
expected behavior (engineering-rules §8): classCoverageMeetsFloors (active
filter), failureModeOversizedExpectedSet (pad 9→17), and the validator
constants (caps/floors per the binding decisions).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-942-golden-set-corrections-relabel.md
```

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-942-r1.txt):

1. FINDING 1: replace the four fabricated uid strings in
   infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl:58
   with the four true identities from the retired top-ml record (:35),
   after re-deriving the full 16-member set against the frozen DB and
   run-20260827-115606 windows per ticket step 2 — evaluated via
   `grep -c e87965d1b51f16be golden-set.jsonl` → 0 (and the same for the
   other three fabricated prefixes), `grep -c e87965d1b51f03b96
   golden-set.jsonl` → 3, plus the green corrected-file run of
   RetrievalGoldenSetTest.adjudicatedCorrectionsPresent.
2. FINDING 2: extend
   RetrievalGoldenSetTest.adjudicatedCorrectionsPresent
   (RetrievalGoldenSetTest.java:374-384) to assert carried-keep uid
   identities verbatim for top-ml-b, top-med-b, top-bio-b and
   top-robot-b — evaluated via the mutation probe (any single
   carried-keep prefix-variant swap must turn the test red) and `mvn
   verify` green from the repo root.

## Review observations

- Mechanical phantom-uid detection in the golden-set validator
  (RECOMMENDED-NEW-TICKET, r1): the validator checks uid SHAPE only, so
  a well-formed but nonexistent row id passes every gate; committing the
  frozen corpus's uid-set identity (e.g. sha256 over the sorted uid list
  or the list itself as a pinned resource) plus a validator leg asserting
  every expected uid exists in that universe would make phantom detection
  a CI fact. TOUCHED-BY-THIS-DIFF: no. (User's call whether to file.)
