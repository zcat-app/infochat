---
id: M1-748
title: "Investigate why no similarity threshold separates true from false matches, and whether a single global threshold is the right model at all"
status: done
created: 2026-08-02
last_updated: 2026-08-03
blocked_by: []
files_budget: 12
files_scope:
  - docs/measurement/retrieval-separability.md
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/TopicCorpusRetrievalIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
  - docs/design/03-commands.md
  - docs/spec/commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    `infochat.chat.semantic-threshold` (0.40 distance) and
    `ChatAgent.CONFIDENT_SIMILARITY_CUTOFF` (0.65). The measurement
    record concludes "no change" for both (§5.1, §5.3); a diff that edits
    either value has left scope.
  - >-
    Swapping the embedding model. M1-717 is abandoned as superseded and
    the incumbent stands; separability is independent of which model
    ships, which is the whole reason it outlived that ticket. The
    record's prefix observation likewise recommends no production change
    (frozen model contract, D54).
  - >-
    The English pivot legs (M1-745/746/747). Those change WHICH TEXT is
    embedded; this asks whether the resulting scores can be thresholded at
    all. Both are true at once and neither blocks the other.
  - >-
    Building a new evaluation harness, or completing the
    `pooling_pending` fixture labels in `.bench/m1-717/`. The record's
    recommendations read production-space or gross-margin data and need
    no fixture work (record §6).
acceptance:
  - >-
    `docs/measurement/retrieval-separability.md` states, with numbers,
    whether `worst_true < best_false` still holds on the incumbent after
    the fixture review below — the finding that outlived M1-717 and the
    reason its six thresholds were underivable.
  - >-
    THE PRIMARY HYPOTHESIS IS TESTED AND ANSWERED EITHER WAY - that this
    is a FIXTURE or METRIC problem rather than a model problem, suspected
    because absolute recall is low (0.56-0.63 @ k=8) across every model
    evaluated, incumbent and candidate alike. A model-independent ceiling
    that uniform is more consistent with the labels than with five
    different architectures failing identically. The document must record
    which it turned out to be, including "the fixtures are sound and the
    scores genuinely do not separate", which is a valid and useful answer.
  - >-
    A sample of the labelled fixtures is hand-checked for the specific
    failure that would produce this shape - a `relevant_uids` set that is
    incomplete, so a retrieved post scored FALSE is in fact relevant and
    its high similarity is correct. The sample size and selection rule are
    stated before the sample is drawn, not after.
  - >-
    The document answers whether a SINGLE GLOBAL threshold is the right
    model. The six live thresholds do not read one scale - two are cosine
    DISTANCE (`infochat.chat.semantic-threshold`,
    `infochat.linking.semantic-threshold`) and four are SIMILARITY
    (`ChatAgent.CONFIDENT_SIMILARITY_CUTOFF`,
    `HelpLookupTool.SIMILARITY_THRESHOLD`,
    `ChatAgent.INTENT_DELIVERY_SIMILARITY_THRESHOLD`,
    `CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD`) — and they gate
    different questions (is this post relevant / are these two posts the
    same story / did the user mean this command). A cutoff that separates
    for one need not exist for another, and "no single threshold" may be
    the correct finding rather than a failure.
  - >-
    Per-threshold recommendations, each carrying the distribution it was
    read from. `infochat.linking.semantic-threshold` must carry a value
    for every profile that overrides it today — base 0.18 and the `%pi`
    0.20 — or record that the spread collapses and say why.
  - >-
    THE RECOMMENDATIONS ARE IMPLEMENTED IN THIS TICKET (refined
    2026-08-03, user decision: the changes are small, so they land here
    rather than in a follow-up ticket): (a)
    `%pi.infochat.linking.semantic-threshold` 0.20 → 0.18 and the
    config-comment claim of a smaller Pi embedder corrected to the v1
    reality; (b) `HelpLookupTool.SIMILARITY_THRESHOLD` 0.60 → 0.52; (c)
    `CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD` 0.60 → 0.52; (d)
    `ChatAgent.INTENT_DELIVERY_SIMILARITY_THRESHOLD` 0.70 → 0.62. Each
    constant's javadoc calibration note cites the measurement record
    instead of "recalibration is a follow-up", and (d) preserves the
    stated stricter-than-tool design offset (+0.10) in its rationale.
  - >-
    Every doc surface that pins the old values is synced in the same
    diff: `docs/design/05-llm-and-embeddings.md` §5.7 pi linking row
    (0.20 → 0.18) and the §5.5 prose sentence explaining pi's margin;
    `docs/spec/commands.md` "(0.70 vs 0.60 similarity)"; and
    `docs/design/03-commands.md` §topic-block threshold prose (0.60 /
    "conservative 0.70").
  - >-
    Tests seeded relative to the old cutoffs still prove the same
    admit/reject legs at the new values — boundary fixtures are
    re-margined, never deleted; no admit-band or reject-band assertion is
    weakened or removed.
test_plan:
  adds: []
  preserves:
    - >-
      HelpLookupToolIT / CommandIntentIndexIT / TopicCorpusRetrievalIT /
      ChatAgentTest admit- and reject-leg assertions, re-margined to the
      new cutoffs where a fixture similarity sits inside a moved band.
    - all tests currently green on main
spec_refs:
  - docs/design/05-llm-and-embeddings.md §5.4.6
decision_refs:
  - D19
  - D54
reviews:
  - round: 1
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 534
      removed: 109
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-08-03
    verdict: CLEAN
    base: 3737750c66d62c428d6ade0093826ee54919e0db
    head: working-tree on m1/M1-748-retrieval-separability-investigation @ b6d4628d
    verdict_file: docs/plan/m1/redteam/M1-748-2026-08-03.md
    out_of_model_count: 0
    note: >-
      User-requested in-progress gate ahead of code review (ticket is
      security_relevant: false, so the standing gate would not have fired
      it). Audited the working-tree diff vs the fork point — the same
      byte-identical input the reviewer reads: the four threshold moves,
      the measurement record, the doc syncs, the test re-margining. CLEAN;
      no findings, no out-of-model observations. No re-audit owed (a
      threshold/config-only diff adds no attack surface, and nothing
      changed since the audit).
clarity_check:
  date: 2026-08-03
  verdict: PASS
  warnings:
    - >-
      Refined 2026-08-03 mid-ticket by user decision: the original
      "investigation only, file a follow-up" shape was replaced by
      "implement the small recommended changes in this ticket"; the
      drafted follow-up (M1-757) was withdrawn before it acquired any
      work.
  blockers: []
escalation_reason:
---

# M1-748: Why does no threshold separate true from false matches?

## Context

M1-717 bundled the multilingual-embedder swap with recalibration of six
similarity thresholds. It was abandoned as superseded (`f47269e2`) because the
pivot removed the reason to swap the model — but the recalibration half did not
die with it, and **it has no owner**. This ticket is that owner.

The measurement found something neither the swap nor the pivot addresses:

> **No model is separable.** `worst_true < best_false` on all five evaluated —
> incumbent and every candidate — so no single global similarity threshold
> splits true matches from false ones. The six thresholds M1-717 would have set
> cannot be derived from any of them.

Absolute recall is also low everywhere: **0.56–0.63 @ k=8** across five
architectures. That uniformity is the strongest clue in the whole result. Five
independently-trained models failing to the same ceiling is more consistent with
a problem in the labels than with a problem in the models.

This matters beyond tidiness. Four of the six thresholds are hardcoded Java
constants and two are config; together they gate whether chat answers
confidently, whether two posts are linked into one cluster, and whether a user's
phrasing resolves to a command. They are currently set to values nobody can
justify from a distribution — M1-619 moved the confident cutoff 0.75 → 0.65 on
live evidence, which is the right instinct applied one constant at a time.

## Approach

**Falsify the fixtures before blaming the scores.** The specific failure that
would produce exactly this shape is an incomplete `relevant_uids` set: a
retrieved post scored FALSE that is genuinely relevant. Its similarity is then
correctly high, and it lands above a true match by construction — no threshold
can separate a label error. Hand-check a stated sample before drawing
conclusions about the models.

**Do not assume one scale.** The six thresholds answer three different
questions on two different units. Treating "no global threshold exists" as a
failure presumes a global threshold was ever the right shape. It may not be, and
saying so with evidence is a better outcome than forcing a number.

**Measure first, then apply.** The deliverable is the written finding plus the
small per-surface value changes it justifies, landed together (refined
2026-08-03; the original follow-up-ticket shape was collapsed into this ticket
because the implementation is four value edits plus doc/test sync). The two
thresholds the record concludes are correctly placed are not touched.

## Out-of-scope

The two keep-thresholds (chat floor, confident cutoff). Any embedding-model or
prefix change. The pivot legs. Building a new harness or completing its labels.

## Notes

- **"The fixtures are sound and the scores genuinely do not separate" is a
  valid finding**, and the acceptance says so deliberately. An investigation
  that can only return one answer is not an investigation. If that is the
  outcome, the recommendation is likely per-surface thresholds or a different
  gating mechanism, and that is worth knowing precisely.
- **`.bench/` is gitignored**, so the harness, fixtures and corpus container are
  not in the repo. The committed artifact is the finding.
  `docs/measurement/README.md` records why measurement records live in the
  tree at all: a decision whose evidence exists only in a scratch folder
  cannot be audited or re-checked.
- `infochat.linking.semantic-threshold` is the one with a live per-profile
  spread (0.18 base, 0.20 on `%pi`), so it is the one where "the spread
  collapses" is a real possible finding rather than a formality.
- The doc-store constants' javadocs pin their values under the D19
  "deployment change requires a spec amendment, not a silent config tweak"
  posture — which is why the value changes land through this gated ticket
  with the spec/design surfaces synced in the same diff, not as config
  tweaks.
