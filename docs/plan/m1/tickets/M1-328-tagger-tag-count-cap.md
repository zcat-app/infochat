---
id: M1-328
title: "TaggerWorker: cap accepted tag count per post"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The partial-valid contract (valid tags kept, invalid silently dropped per docs/spec/llm.md §Failure handling) — unchanged; the cap is a separate STRUCTURAL bound applied on top of vocabulary filtering, not a change to validity semantics.
  - normalizeTag / TagNormalizer and the vocabulary membership check — unchanged.
  - A profile-driven knob for the cap — out of scope; a static constant on the worker is the right home until a knob is needed.
acceptance:
  - "TaggerWorker.validate applies a hard upper bound (e.g. MAX_TAGS_PER_POST = 8, a 2x headroom over the design-intended 1–4 tags) on the number of valid tags accepted from one LLM response. Valid, vocabulary-matched tags are accepted in the model's emission order (LinkedHashSet preserves first-emit order, the model's relevance signal) up to the cap; tags past the cap are counted as dropped, not written to post.tags."
  - "ValidationResult carries the dropped count and the tagger_partial_valid log line surfaces it, so an operator can observe sustained high drop rates (a misbehaving or prompt-injected model returning many vocabulary-valid tags)."
  - "post.tags can never exceed MAX_TAGS_PER_POST entries regardless of how many vocabulary-valid tags the LLM returns, bounding the downstream tags && ARRAY[...] overlap-retrieval surface (searchPosts / follow-tag / chat retrieval)."
  - "A test pins the cap: a stubbed LLM returning more than MAX_TAGS_PER_POST distinct vocabulary-valid tags persists exactly MAX_TAGS_PER_POST tags (the first by emission order) and reports the correct dropped count. A companion test confirms a normal 1–4 tag response is unchanged."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger (tag-cap cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-328: TaggerWorker — cap accepted tag count per post

## Context

Deep-review v5.5 (opus-47, `06-module-infochat-collector.md` F2) found that
`TaggerWorker.validate` deduplicates and vocabulary-filters the LLM's tag list
but applies **no upper bound** on how many valid tags it accepts.
**Verified at source 2026-06-14:** `validate` (TaggerWorker.java:420-432)
iterates `parsed`, adds every vocabulary-matched `normalized` to a
`LinkedHashSet`, and returns `List.copyOf(valid)` with no size check;
`persistCursor` writes the whole array to `post.tags`.

The design intent is 1–4 tags per post from a controlled vocabulary. A
misbehaving or prompt-injected model that returns every vocabulary entry inflates
`post.tags` unboundedly (bounded only by the operator-controlled vocabulary
size). Each extra tag multiplies the post's match count against every downstream
`tags && ARRAY[...]` overlap query, polluting retrieval. This is a defense-in-
depth gap on the LLM trust boundary: Stage 2 catches injection in the post body,
but the tagger LLM call drives tag generation from the same body and its output
carries no structural cap matching the spec's "1–4 tags" intent.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Truncate from the tail (`LinkedHashSet` first-emit order = the model's
  relevance order), keeping the most relevant tags. A genuinely rich post losing
  a few low-priority tail tags is acceptable — retrieval works on overlap, not
  coverage.
