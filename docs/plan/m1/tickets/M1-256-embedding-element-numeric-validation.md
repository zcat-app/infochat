---
id: M1-256
title: "Embedding vector elements: validate numeric before coercion"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 3
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The shared LlmHttpSupport send / non-2xx / body-cap pipeline — the embedding provider was migrated onto it by M1-251; this ticket touches only the response-parse loop (data[].embedding element read), not the send path.
  - The existing data[]-is-array check and the results.size()==expectedCount shape guard — both correct and retained; this adds the missing per-element type check between them.
  - The embedding request shape, dimension/clamp bounds, and the EmbeddingResult contract — unchanged.
acceptance:
  - "The data[].embedding element parse loop validates each element is a numeric JSON node before coercion: a non-numeric element (string, boolean, object, or JSON null) throws EmbeddingCallFailedException at the seam instead of silently coercing to 0.0. Read the value with the numeric accessor (doubleValue), not the lenient asDouble."
  - "A named test in the llm impl test package asserts: an embedding response whose embedding[] contains a non-numeric element (e.g. a string, and JSON null) throws EmbeddingCallFailedException (routing into the EmbeddingWorker one-failure-fails-batch retry, same as a missing-data[]/size-mismatch reply), while a well-formed numeric response still parses to the same float[] as before."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Failure handling (recap)
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-256: Embedding vector elements: validate numeric before coercion

## Context

The HTTP embedding provider response is a system-boundary input
(`LlmHttpSupport` classifies the endpoint as "operator-configured
(semi-trusted)"), and the spec requires a wrong-shape batch reply to become a
batch failure, not a silently corrupt result. The current parse validates that
`embedding` is an array but never validates the array *elements*:
`JsonNode.asDouble()` (the lenient no-arg overload) returns `0.0` for any
non-numeric node. So `{"data":[{"embedding":["x","y","z"]}]}`, or a reply with
`null` for a few coordinates, parses into an all-zero / partially-zero `float[]`
of the right length, passes the `results.size() == expectedCount` check, and is
persisted to the pgvector column — polluting cosine-similarity scoring with no
error surfaced and no retry triggered. This is the same defect class the
size-divergence guard already stops; element-type divergence slipped through.
Source: `deep-code-review/v3.5/opus-48/04-module-infochat-llm-adapter.md#F1`
(verified live against `OpenAiCompatibleEmbeddingProvider.java:166-178` on main).

## Acceptance

See frontmatter. In prose: in the element loop, throw
`EmbeddingCallFailedException` for any non-numeric element (`isNumber()` is
false) and read numeric elements via `doubleValue()` rather than `asDouble()`, so
element-type divergence becomes a batch failure indistinguishable from the
wrong-shape failures already handled. A named test pins both the throw and the
well-formed-parse paths; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The shared send pipeline (M1-251), the array/size guards, and
the request/clamp shape are untouched — this is a single-loop element-validation
fix on the parse path.

## Notes

- `isNumber()` is true only for numeric JSON nodes (int / long / double /
  decimal); `doubleValue()` yields the real value only for numeric nodes,
  removing the silent-zero coercion entirely.
- Adjacent guard to match in tone/placement: the `results.size() != expectedCount`
  shape check immediately below the loop (the existing "wrong shape → batch
  failure" precedent).
- The added branch is one integer-tag check per coordinate — negligible against
  the embedding HTTP round trip.
</content>
