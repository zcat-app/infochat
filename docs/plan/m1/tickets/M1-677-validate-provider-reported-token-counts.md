---
id: M1-677
title: "Validate provider-reported token counts before they reach the metric counters"
status: pending
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 5
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredLlmProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmObservabilityTest.java
  - docs/spec/llm.md
  - docs/spec/security.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The `embedding.dimension` gauge. The audit paired it with the token
    counts, but it does not carry the same exposure: the vector must
    arrive inside the 1–8 MiB response-body cap (so the value is
    bounded), its tags are config-derived, and EmbeddingWorker
    independently checks every vector's length against
    `embedding_metadata.dimension` — a mismatch alerts the operator and
    skips the row rather than storing a wrong-dimension vector. Nothing
    to fix there.
  - >-
    The providers' response parsing (OpenAiCompatibleProvider,
    AnthropicProvider). Reading the reported counts is legitimate, and
    the Anthropic cache-token fold is deliberate; the constraint belongs
    at the one boundary both providers pass through, not duplicated per
    parser — the same siting argument M1-673 settled for the `model`
    label.
  - >-
    Building the cost-weighted rate cap itself (future-features §E7).
    This ticket makes the input trustworthy; whether and how to charge a
    bucket against it stays parked.
  - >-
    LlmMetrics' meter set, metric names, and tag keys. Dashboard-facing
    names stay stable; only the values recorded change.
acceptance:
  - >-
    A new test in LlmObservabilityTest proves a response reporting
    NEGATIVE input/output token counts does not decrement or otherwise
    corrupt `llm.tokens.in` / `llm.tokens.out`. A Prometheus counter
    that moves backwards reads as a counter reset, so every `rate()`
    over the series silently mis-reports.
  - >-
    A new test in LlmObservabilityTest proves a response reporting an
    output-token count far above what the call could have produced is
    not recorded verbatim. The fix states its rule in the commit — clamp
    to the call's configured `max-tokens`, or drop the usage record for
    that call and count it as unreported — and the test pins whichever
    rule is chosen.
  - >-
    The constraint lives at the single boundary every provider passes
    through (MeteredLlmProvider, which since M1-673 already holds the
    LlmRouter.ConfigReader needed to read the per-task bound), not in
    each provider's parser.
  - mvn -pl infochat-llm-adapter verify is green
  - >-
    docs/spec/llm.md §Bounded concurrency and observability records that
    provider-reported usage values are untrusted input, and the
    §Trust boundaries entry 9 residual sentence in docs/spec/security.md
    ("v1 records it into the token counters as reported") is updated to
    match what now ships.
test_plan:
  adds: []
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmObservabilityTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/spec/security.md §Trust boundaries
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-677: Validate provider-reported token counts before they reach the metric counters

## Context

M1-673 expelled the wire-reported `model` string from the metric
**labels**. Two of the four auditors on that ticket's multi-auditor gate
(`docs/plan/m1/redteam-multi/M1-673-2026-07-22/`) independently observed
that the same untrusted response is still trusted for its numeric
**values**, and that observation survives checking:

- `OpenAiCompatibleProvider.java:302-306` accepts `prompt_tokens` /
  `completion_tokens` behind a `canConvertToLong()` guard and calls
  `asLong()` — no range check, no sign check. `AnthropicProvider.java:260-271`
  has the same shape and additionally folds
  `cache_read_input_tokens` + `cache_creation_input_tokens` into the
  reported input.
- `MeteredLlmProvider.java:93` passes that `response.usage()` straight to
  `LlmMetrics.recordLlmCall`, which increments `llm.tokens.in` /
  `llm.tokens.out` by the reported amounts (`LlmMetrics.java:88-95`).
- `canConvertToLong()` admits negatives, so a reply reporting
  `"prompt_tokens": -5` decrements a Prometheus counter — a monotonicity
  violation that reads downstream as a counter reset.

**Severity today is low and the ticket should not oversell it.** A
repo-wide grep finds no consumer of these counters other than the meters
themselves: no budget, no cap, no alert threshold in v1 reads them, so a
lying endpoint skews usage observability and nothing else. Unlike the
M1-673 label path there is no memory-growth component either — the meter
set is fixed, only the recorded numbers move.

What makes it worth closing now rather than parking is that the value is
already named as a future **decision input**: future-features §E7
("Cost-weighted LLM rate cap") proposes charging the rate-limit bucket by
"what a turn actually consumed (tool-loop iterations, or
provider-reported usage)". If that lands on top of unvalidated counts, a
hostile or compromised endpoint gets to choose how much of a sender's
budget each turn costs — an authorization-adjacent decision driven by
attacker-supplied data. Validating at the boundary now is a few lines;
retrofitting it under a rate cap later is not.

`docs/spec/security.md` §Trust boundaries entry 9 currently discloses
this residual in prose. This ticket closes it and updates that sentence.

## Acceptance

See the frontmatter. Negative counts cannot move the counters backwards;
an impossible output-token count is not recorded verbatim under a stated
rule; the constraint sits at the shared decorator boundary; both spec
files record the new state.

## Out-of-scope

The `embedding.dimension` gauge (already bounded by the body cap and
independently guarded by EmbeddingWorker's per-vector dimensionality
check), the providers' own parsing, the E7 rate cap itself, and the
meter/tag naming surface. See the frontmatter.

## Census

The class is "every site where a value the wire response chose reaches
the metric surface". Enumerate it mechanically — re-run this at `start`
and confirm the six sites below are still the whole set:

```
grep -rn "metrics\.record\|metrics\.llmInflight" --include=*.java infochat-*/src/main
```

| Site | Wire-derived value? | Disposition |
|---|---|---|
| `MeteredLlmProvider.java:85` `llmInflight(task, provider)` | no — enum + delegate name | none needed |
| `MeteredLlmProvider.java:93` `recordLlmCall(..., response.usage())` | **yes — token counts** | **fix here** |
| `MeteredLlmProvider.java:99` `recordLlmCall(..., null)` (fail path) | no — usage is null, model is the `unknown` constant | none needed |
| `MeteredEmbeddingProvider.java:62` `recordEmbeddingCall(...OK)` | no — provider name + configured model | none needed |
| `MeteredEmbeddingProvider.java:64` `recordEmbeddingDimension(..., results.get(0).dimension())` | yes — vector length | out of scope: bounded by the 1–8 MiB body cap and independently guarded by EmbeddingWorker's per-vector dimensionality check (alert + skip, never stored) |
| `MeteredEmbeddingProvider.java:71` `recordEmbeddingCall(...FAIL)` | no — same as :62 | none needed |

Labels across all six are already non-wire-derived (M1-673 for the LLM
side; `infochat.embeddings.model` for the embedding side), so this
ticket is about recorded **values** only.

## Notes

- The enabling change already shipped: M1-673 gave `MeteredLlmProvider` a
  `LlmRouter.ConfigReader`, so the decorator can read
  `task.configPrefix() + "max-tokens"` the same way it now reads
  `+ "model"` — the clamp bound is available at the site without new
  wiring.
- Deciding between clamp-to-bound and drop-the-record is a real choice,
  not a formality: clamping keeps a usable lower bound on cost
  observability while lying about the exact figure; dropping makes the
  gap visible as "unreported" rather than silently plausible. State the
  reasoning in the commit either way.
