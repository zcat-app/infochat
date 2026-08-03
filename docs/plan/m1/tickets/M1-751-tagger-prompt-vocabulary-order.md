---
id: M1-751
title: "Tagger prompt renders the controlled vocabulary in a per-JVM-random order; make it deterministic"
status: done
created: 2026-08-02
last_updated: 2026-08-03
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagVocabularyRefreshTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    CHANGING THE PROMPT TEXT, the tag list's rendering shape, or
    `TaggerWorker.renderPrompt`. The block-expansion logic is correct; only
    the ORDER of the sequence it iterates is at issue. A diff touching
    `tagger.md` or `renderPrompt` has left scope.
  - >-
    `sweepFingerprint` (`TaggerWorker:872-882`). It already sorts into a
    `TreeSet` and is correct as written — it is cited here only as evidence
    that the codebase treats the vocabulary as unordered, not as a thing to
    change.
  - >-
    Setting a temperature, seeding, or otherwise making the tagger's LLM
    call deterministic. The measurement below found tagger sampling noise
    an order of magnitude larger than any order effect, but production sets
    no temperature deliberately and changing that is a separate decision
    with its own spec implications.
  - >-
    Re-tagging or re-evaluating existing posts. The fix changes future
    prompts only; no backfill is in scope.
  - >-
    The other consumers of the vocabulary (`contains`, the tagger's
    validation path). Membership is order-independent and unaffected.
acceptance:
  - >-
    `TagVocabulary.names()` returns the controlled vocabulary in a STABLE,
    documented order across JVM runs. Today `loadFromDatabase` builds a
    `LinkedHashSet` in `ORDER BY name` order and then discards that order by
    publishing through `Set.copyOf` (`:127`), whose iteration order is
    randomized per JVM by `ImmutableCollections.SALT`. `TaggerWorker:547`
    iterates `names()` straight into the prompt, so the Collector renders a
    differently-ordered tag list on every boot.
  - >-
    The published set remains effectively immutable and safe to publish
    through the existing `volatile` field, and `contains()` remains an O(1)
    hash lookup — the two properties the current `Set.copyOf` was chosen
    for. An order-preserving unmodifiable copy satisfies both; the
    javadoc's "immutable Set" claim must stay true.
  - >-
    A test asserts the order is stable and is the query's `ORDER BY name`
    order — not merely that the same NAMES are present. A set-equality
    assertion cannot fail on this defect and does not discharge this item.
  - >-
    The class javadoc records WHY the order is load-bearing: it is rendered
    into the tagger prompt, and LLM output is order-sensitive, so an
    unstable order makes ingest tagging vary across restarts for reasons
    unrelated to the post, the vocabulary or the model.
  - >-
    `TagVocabularyRefreshTest` still passes, including its existing refresh
    and scheduler-wiring assertions. The refresh path publishes the same way
    as the initial load, so both must be covered by whatever guarantees the
    order.
test_plan:
  adds:
    - >-
      An assertion in `TagVocabularyRefreshTest` that `names()` iterates in
      `ORDER BY name` order after both the initial load and a refresh. The
      probe must compare the ITERATION SEQUENCE against an expected list;
      comparing sets passes on the broken code.
  preserves:
    - >-
      `TagVocabularyRefreshTest`'s existing coverage — refresh picks up a
      new tag; the `@Scheduled` wiring pins the interval and SKIP
      concurrent-execution.
    - >-
      `TaggerWorkerTest`, `TaggerWorkerIT`, `TaggerWorkerSweepIT` — the
      sweep fingerprint sorts independently and must not shift.
    - all tests currently green on main
spec_refs:
  - docs/design/05-llm-and-embeddings.md §5.4.2
decision_refs: []
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
      files: 4
      added: 120
      removed: 16
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-08-03
  verdict: WARN
  warnings:
    - >-
      lint FILES-SCOPE-COVERAGE: "test_plan.adds path 'An' is not in
      files_scope" — linter artifact; it parses the prose entry's first
      token as a path. The file the entry names
      (`TagVocabularyRefreshTest.java`) IS in files_scope.
    - >-
      self-check: acceptance item 5 and test_plan.preserves cited an
      existing "failed refresh keeps the previous vocabulary" assertion in
      `TagVocabularyRefreshTest`. No such assertion exists (the class holds
      exactly two tests: runtime-add refresh, and the `@Scheduled` wiring
      pin); no failure-path test exists anywhere. Vacuous — nothing to
      preserve and test_plan.adds asks only for the order assertion — so
      corrected inline to name the assertions that do exist.
    - >-
      lint BLOCKER SPEC-REFS-RESOLVABLE (spec_ref missing the `§<section>`
      anchor) was cleared by a bounded self-refine before this start; see
      commit "M1-751: refine ticket spec (lint-blocker fix)".
  blockers: []
escalation_reason:
---

# M1-751: the tagger prompt's tag list is ordered at random, per JVM run

## Context

Found while falsifying an unrelated claim in the Track A measurement harness —
that a pinned copy of the vocabulary reproduced the prompt production sends. It
does not, and the reason is a production defect rather than a harness one.

`TagVocabulary.loadFromDatabase` (`:110-128`) builds a `LinkedHashSet` from
`SELECT name FROM tag ORDER BY name`, so at that point the order is
alphabetical and deterministic. It then returns `Set.copyOf(loaded)`, which
discards it: `Set.copyOf` produces a `java.util.ImmutableCollections` set whose
iteration order is derived from a per-JVM random `SALT`. `TaggerWorker:547`
iterates `tagVocabulary.names()` directly into the `{#tags}...{/tags}` block
with no intervening sort.

Consequence: **the Collector sends a differently-ordered tag list to the LLM on
every boot**, and two Collector instances on the same DB send different
prompts.

Measured, not inferred — the same 25 names through
`LinkedHashSet` -> `Set.copyOf` -> iterate, three JVM runs:

```
langchain4j ai-image news video oracle google java malware ai zcash ...
news ai-image langchain4j security openai privacy research qwen ...
google java malware ai zcash kimiai glmai test comfyui crypto ...
```

Runs 1 and 3 are rotations of one probe sequence; run 2 walks it in the other
direction.

The codebase already knows the set is unordered: `sweepFingerprint`
(`TaggerWorker:872-882`) copies into a `TreeSet` before hashing, with the
comment *"the vocabulary is an unordered Set, so the fingerprint must"* sort.
The prompt path never got the same treatment. That asymmetry is the sharpest
statement of the bug: **the re-evaluation fingerprint is stable while the
prompt it is supposed to characterize is not**, so the ordering variation is
invisible to the mechanism built to detect vocabulary change.

## What the measurement does and does not show

A 40-call probe (`.bench/track-a/probe-tag-order.py`, 10 real corpus posts x 4
prompt orderings) asked whether the order changes the tags chosen. It was
designed around the confound that decides the question — the tagger call is
sampled, so one ordering disagreeing with another proves nothing unless
compared against how much the SAME prompt disagrees with itself:

| pair kind | n | identical tag sets | mean Jaccard |
|---|---|---|---|
| same prompt, resampled | 10 | 5/10 | 0.783 |
| different tag order | 50 | 17/50 | 0.728 |

Per-post sign test: the same prompt agreed more on 4 and less on 4 of 10 —
p ~ 1.0, nowhere near the 6-discordant floor. **No order effect is detectable
at this n**, and that is "no large effect", not "no effect". Zero of the 40
calls hit the tagger fallback, so the disagreement is real tag variation rather
than a parse failure.

So this ticket is filed on the **determinism** argument, not on a measured
quality impact:

- identical post + identical vocabulary + identical model can produce different
  tags across restarts, for a reason no operator can see or reproduce;
- the fix is small, and the alternative is carrying a documented
  irreproducibility in the ingest path;
- any future measurement of the tagger has to control for a variable that
  should not exist.

Severity is low precisely because the probe found the effect, if any, to sit
under the tagger's own sampling noise. It should not be raised on the strength
of the ordering alone.

## Approach

Publish the vocabulary in the order the query already returns. The
`LinkedHashSet` is built in `ORDER BY name` order; keep it instead of copying
into a hash-ordered set, and wrap it so the published reference stays
unmodifiable. `contains()` stays O(1) and the volatile hand-off is unchanged,
which are the two properties `Set.copyOf` was there for.

## Related, deliberately not folded in

The probe's dominant result is not about ordering at all: **the same tagger
prompt resampled returns a different validated tag set half the time** (5/10
identical, mean Jaccard 0.783). That is a property of running the tagger
without a temperature and is out of scope here, but it is the number that
matters for any per-post reproducibility claim, and Track A's fixture design
has to model it. Recorded in `.bench/track-a/probe-tag-order.py` and the
session handoff rather than lost in this ticket.
