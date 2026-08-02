---
id: M1-748
title: "Investigate why no similarity threshold separates true from false matches, and whether a single global threshold is the right model at all"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by: []
files_budget: 1
files_scope:
  - docs/measurement/retrieval-separability.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    CHANGING ANY THRESHOLD VALUE. This ticket produces a finding and a
    recommendation; it touches no production configuration and no
    production code. Re-tuning belongs to the follow-up implementation
    ticket this one is expected to file, gated normally. A diff that edits
    a `.properties` value or a Java constant has left scope.
  - >-
    Swapping the embedding model. M1-717 is abandoned as superseded and
    the incumbent stands; separability is independent of which model
    ships, which is the whole reason it outlived that ticket.
  - >-
    The English pivot legs (M1-745/746/747). Those change WHICH TEXT is
    embedded; this asks whether the resulting scores can be thresholded at
    all. Both are true at once and neither blocks the other.
  - >-
    Building a new evaluation harness. `.bench/m1-717/` already carries
    the fixtures, the corpus container and `M1-717-embedder-eval.py`.
    Reimplementing them is how this becomes a month of work.
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
    read from, so the follow-up ticket can set values without re-deriving
    them. `infochat.linking.semantic-threshold` must carry a value for
    every profile that overrides it today — base 0.18 and the `%pi` 0.20 —
    or record that the spread collapses and say why.
  - >-
    A named follow-up IMPLEMENTATION ticket is filed for whatever the
    finding recommends, or the document states explicitly that no
    production change is warranted. This ticket must not end with a
    finding nobody owns.
  - >-
    No production file is modified. Asserted by the diff itself — the
    files_scope is one document.
test_plan:
  adds: []
  preserves:
    - >-
      No test is added or changed. The deliverable is a measurement
      record, not code — the evaluation runs against the existing
      `.bench/m1-717/` harness and the `m1-717-corpus` container (9,224
      READY posts), neither of which is in the repo.
    - all tests currently green on main
spec_refs:
  - docs/design/05-llm-and-embeddings.md §5.4.6
decision_refs:
  - D19
  - D54
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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

**Investigation, not implementation.** The deliverable is a written finding plus
a follow-up ticket. Threshold changes go through their own gates with their own
acceptance — bundling them here is how an investigation acquires a migration and
a round cap it does not need.

## Out-of-scope

Any threshold value change. Any embedding-model change. The pivot legs. Building
a new harness.

## Notes

- **"The fixtures are sound and the scores genuinely do not separate" is a
  valid finding**, and the acceptance says so deliberately. An investigation
  that can only return one answer is not an investigation. If that is the
  outcome, the recommendation is likely per-surface thresholds or a different
  gating mechanism, and that is worth knowing precisely.
- **`.bench/` is gitignored**, so the harness, fixtures and corpus container are
  not in the repo and not in `files_scope`. The committed artifact is the
  finding. `docs/measurement/README.md` records why measurement records live in
  the tree at all: a decision whose evidence exists only in a scratch folder
  cannot be audited or re-checked.
- `infochat.linking.semantic-threshold` is the one with a live per-profile
  spread (0.18 base, 0.20 on `%pi`), so it is the one where "the spread
  collapses" is a real possible finding rather than a formality.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-748-retrieval-separability-investigation.md`
  is clean.
