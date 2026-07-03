---
id: M1-548
title: Per-task max-tokens for OpenAiCompatibleProvider (F-live-6)
status: pending
created: 2026-07-03
last_updated: 2026-07-03
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - AnthropicProvider changes (it already reads and sends max-tokens; its
    required-key semantics stay as they are)
  - per-profile sizing of max-tokens defaults (laptop/vps/pi rows in the
    §5.7 profile table) — that is the F-live-5 profile-sizing decision's
    territory, a separate ticket
  - documenting the pre-existing `timeout-ms` key in the design notes (it
    is undocumented today; a doc-only follow-up, not this ticket)
  - host runtime values in `prod/runtime/application.properties` — post-merge
    host work (HANDOFF §START HERE step 1)
  - s12 scenario changes or any live-harness change
  - any other sampling parameter (temperature, top_p, stop) — max_tokens only
acceptance:
  - OpenAiCompatibleProvider's `configFor` reads
    `infochat.llm.<task>.max-tokens` as an optional Integer defaulting to
    1024, carries it in its TaskConfig record, and validates explicit
    values via the existing `LlmHttpSupport.requirePositiveMaxTokens` so a
    non-positive value fails the startup scan naming the offending
    property key (same behavior AnthropicProvider has today).
  - `doCall` sends `"max_tokens"` in the JSON request body for every task
    (alongside the existing `model` field), sourced from the resolved
    TaskConfig — the request body is never again `{model, messages}` with
    unbounded generation.
  - OpenAiCompatibleProviderTest pins the new wire shape (request body
    contains `max_tokens` with the configured value), the default (key
    absent → 1024 in the body), and the startup-scan failure for a
    non-positive explicit value naming the property.
  - docs/design/05-llm-and-embeddings.md documents the
    `infochat.llm.<task>.max-tokens` key (default 1024, output cap only)
    in the per-task property example block.
  - mvn verify is green.
test_plan:
  adds:
    - OpenAiCompatibleProviderTest: request-body wire format includes
      max_tokens with the configured per-task value
    - OpenAiCompatibleProviderTest: absent key defaults to 1024 in the
      request body
    - OpenAiCompatibleProviderTest: non-positive explicit max-tokens fails
      the startup scan naming the property key
  preserves:
    - all existing OpenAiCompatibleProviderTest tests (config routing,
      startup scan on missing model key, usage/model response parsing)
    - AnthropicProviderTest untouched and green
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Failure handling (recap)
decision_refs: []
---

## Context

**F-live-6 (HIGH for chat, live 4b-3 run 2026-07-03):** s12 (chat mode)
fails even at a 240 s client timeout with an idle collector. llama.cpp shows
the chat task healthy at ~4.5 tok/s having generated 1033+ tokens (prompt
487) when the client timeout cancels it — generation simply never finishes,
because `OpenAiCompatibleProvider.doCall` sends only `{model, messages}`
with no `max_tokens`, and `TaskConfig` has no such key to configure.
`AnthropicProvider` already reads `infochat.llm.<task>.max-tokens` and sends
`cfg.maxTokens()`; the OpenAI-compatible path needs the per-task twin.

Verified NOT a reasoning cutoff (gemma-4 instruct, no thinking channel;
llama.cpp reports `truncated = 0` throughout).

## Design (settled with user 2026-07-03, HANDOFF §START HERE)

- **Per-task, not global.** Chat can afford ~500–600 tokens; the summarizer
  needs less (the digest makes ONE LLM call PER CLUSTER —
  `DigestRenderer` → `SummaryProseGenerator.generate` loops clusters — and
  its system prompt demands only the short summary paragraph; all
  structural fields are deterministic Java, so ~400 has generous headroom);
  tagger/entity need a handful. Follows the existing per-task `timeout-ms`
  read in `configFor` (`getOptionalValue(...).orElse(...)`).
- **Optional key, defaulted — NOT uncapped when absent.** The `timeout-ms`
  precedent is `orElse(30000)`; the max-tokens twin is `orElse(1024)`. An
  absent-means-uncapped default would re-create exactly the F-live-6
  failure mode on every deployment that doesn't set the key, so the
  HANDOFF's "lean required-or-defaulted" resolves to defaulted: 1024 is
  generous for every v1 task's legitimate output (largest is chat prose)
  while guaranteeing generation terminates. Making the key *required*
  (Anthropic-style) was rejected: it would force 6 new keys into every
  existing config (dev, CI, wizard output) for no added safety over a safe
  default.
- **`max_tokens` caps OUTPUT only** — digest/summary INPUT size (many
  sources, lots of text) is unaffected.
- **Sizing invariant the caps encode** (host-side, for the runtime values
  set post-merge): `cap × per-token-decode-time + prefill < task
  timeout-ms`. A `finish_reason=length` truncation (cosmetic) beats the
  observed total loss (timeout after 1033+ tokens generated; user gets the
  unavailable-fallback).
- **Validation reuses `LlmHttpSupport.requirePositiveMaxTokens`** (already
  exists for AnthropicProvider, the M1-412 sibling pattern) so a
  misconfigured non-positive value fails at the startup scan, not per call.

## Implementation anchors (surveyed 2026-07-03)

- `infochat-llm-adapter/.../llm/impl/OpenAiCompatibleProvider.java`:
  `configFor` (~l.155–174, per-task reads incl. the `timeout-ms`
  `orElse(30000L)` to mirror), `doCall` (~l.176–212, Jackson ObjectNode;
  `max_tokens` slots after `root.put("model", ...)`), `TaskConfig` record
  (~l.247, gains `int maxTokens`).
- Reference: `AnthropicProvider.java` config read ~l.137–138 + body write
  ~l.151; `LlmHttpSupport.requirePositiveMaxTokens` ~l.293–298.
- Wire-format test to mirror: `AnthropicProviderTest.generatePostsCorrectWireFormat`.

## Not security_relevant — justification

No trust-boundary or threat-model surface changes: the field bounds the
size of an internal outbound LLM request's completion. If anything it
tightens resource exhaustion (bounded generation), but no security.md
property is implicated.
