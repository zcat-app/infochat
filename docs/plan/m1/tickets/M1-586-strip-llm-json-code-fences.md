---
id: M1-586
title: "Strip markdown fences before entity/tagger JSON parse"
status: pending
created: 2026-07-07
last_updated: 2026-07-07
blocked_by: []
files_budget: 7
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/LlmJson.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/LlmJsonTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any change to the LLM adapter (OpenAiCompatibleProvider / AnthropicProvider).
    This ticket does NOT wire response_format / JSON-mode / temperature — the fix
    is parser-side only, so it stays provider-agnostic (helps any model that
    fences, breaks nothing for Ollama/Anthropic).
  - >-
    The D22 failure-release policy. A reply that is NOT a fenced JSON payload must
    still parse to null so the entity release-without-entities and tagger
    schema-violating degrade paths are byte-for-byte unchanged. This ticket only
    RECOVERS the fenced-but-valid case; it never makes a genuinely-bad reply pass.
  - >-
    The entity/tag controlled vocabularies, normalization, dedup, DB schema, and
    the {"tags":[...]} / bare-array response contracts. Only the pre-parse
    fence-strip is added.
  - >-
    Stage-1/Stage-2, summarizer, translator, and chat parsers. If any share the
    same raw-readTree exposure it is a separate follow-up; this ticket scopes to
    the two confirmed eval-pipeline JSON parsers (entity + tagger).
acceptance:
  - >-
    A new shared helper app.zcat.infochat.collector.eval.LlmJson strips a single
    leading code-fence opener (``` or ```json/```JSON with an optional language
    token) and its matching trailing ```, tolerating surrounding whitespace, and
    returns the inner text; text with no fence is returned unchanged. NAMED TEST:
    LlmJsonTest covers fenced array, fenced object, ```json vs bare ```, leading/
    trailing whitespace, no-fence passthrough, and a string that merely contains
    backticks mid-content (left unchanged).
  - >-
    EntityExtractorWorker.parseEntities returns the extracted entities (NOT
    SCHEMA_VIOLATING) for a ```json-fenced JSON array — the exact DeepSeek shape
    observed live 2026-07-07 (```json\n[{"text":..,"type":..}]\n```). NAMED TEST:
    a new case in EntityExtractorWorkerTest passes.
  - >-
    TaggerWorker.parseTags returns the tags for a ```json-fenced {"tags":[...]}
    object. NAMED TEST: a new case in TaggerWorkerTest passes.
  - >-
    Regression guard: a reply that is not a fenced JSON payload (e.g. prose, or a
    fenced-but-still-malformed body) still parses to null, so the entity
    release-without-entities (D22) and tagger schema-violating paths are
    unchanged. A preserved existing schema-violating test proves no regression.
  - "`mvn verify` is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/LlmJsonTest.java — fence-strip unit cases (array/object, ```json/```, whitespace, passthrough, mid-content backticks)."
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerTest.java — add a ```json-fenced-array recovery case."
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java — add a ```json-fenced-object recovery case."
  preserves:
    - "Every existing entity/tagger parse test, including the schema-violating-returns-null cases (D22 degrade path)."
spec_refs:
  - "docs/spec/llm.md §Failure handling (recap)"
decision_refs:
  - "D22"
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-586: strip markdown code fences before the entity/tagger JSON parse

## Context

The 2026-07-07 post-M1 beta smoke (Collector-alone bring-up, profile
`remote-llm`, generative backend DeepSeek `deepseek-chat`) surfaced ~85%
`SCHEMA_VIOLATING` responses from `EntityExtractorWorker` — most posts released
without entities. Confirmed against live DeepSeek + real DB posts: the failures
are **not** truncation, empty bodies, or bad content — DeepSeek wraps the JSON
in a markdown code fence (```` ```json\n[...]\n``` ````) on ~60% of calls
(non-deterministic at the default temperature), and the strict parser
(`objectMapper.readTree(trimmed)` requiring a bare top-level array) rejects the
fence wrapper even though the JSON inside is always valid. `TaggerWorker` uses
the identical raw-`readTree` parse and has the same latent exposure. Stripping a
leading/trailing code fence before parsing recovers every observed failure,
provider-agnostically, without touching the D22 degrade policy
(`docs/spec/llm.md §Failure handling (recap)`).

## Acceptance

1. New shared helper `eval/LlmJson.java` strips one leading fence opener (```` ``` ````
   or ```` ```json ````/```` ```JSON ```` with an optional language token) and its matching
   trailing ```` ``` ````, tolerant of surrounding whitespace, and returns inner
   text unchanged when no fence is present. `LlmJsonTest` covers fenced array,
   fenced object, ```` ```json ```` vs bare ```` ``` ````, whitespace, no-fence
   passthrough, and mid-content backticks (unchanged).
2. `EntityExtractorWorker.parseEntities` recovers entities from a ```` ```json ````-fenced
   array (the observed DeepSeek shape) instead of `SCHEMA_VIOLATING`
   (`EntityExtractorWorkerTest` new case).
3. `TaggerWorker.parseTags` recovers tags from a ```` ```json ````-fenced
   `{"tags":[...]}` object (`TaggerWorkerTest` new case).
4. A non-fenced-JSON reply still parses to `null` — the release-without-entities
   (D22) and tagger schema-violating degrade paths are unchanged (preserved
   existing test).
5. `mvn verify` is green from the repo root.

## Out-of-scope

The fix is **parser-side only**. It does NOT wire OpenAI `response_format`
JSON-mode or a temperature setting into the LLM adapter (that was the rejected
Option B — provider-specific, requires reshaping the entity contract to an
object, and adds adapter surface). It does not alter the vocabularies,
normalization, dedup, DB schema, or the `{"tags":[...]}` / bare-array contracts.
It must not weaken the D22 degrade path: a genuinely-unparseable reply still
returns `null`. Stage-1/Stage-2, summarizer, translator, and chat parsers are
not touched — a separate follow-up if they share the exposure. Both worker test
files are modified (new fenced-recovery cases) — authorized here per the
test-integrity rule.

## Notes

- Confirmed failure sample (live, 2026-07-07): `` '```json\n[\n  {"text":
  "CISA", "type": "org"}\n]\n```' `` — valid array, rejected solely for the
  fence. 6/10 sampled real posts failed this way; 0 for any other reason.
- Chosen fix = Option A (strip fences) over B (JSON-mode: bigger, provider-locked),
  C (prompt-only "no fences": models ignore it at temp ~1.0), D (lower temp /
  switch model: doesn't target the cause). A is minimal, robust across providers,
  and unit-testable.
- Belt-and-suspenders (optional, within the two worker files already in scope):
  add a one-line "output raw JSON, no markdown code fences" to the entity/tagger
  prompt templates to cut the fence rate (fewer wasted retries). The fence-strip
  is the load-bearing fix regardless; keep the prompt tweak minimal.
- Put the strip in one `eval/LlmJson` helper reused by both workers (DRY) rather
  than duplicating the logic — the `eval/` package is the existing home for
  shared worker helpers (`PartitionScan`, `RetryBackoff`, `TransactionHelper`).
- Memory: `deepseek-entity-extraction-schema-fail`. The `deepseek-v4-flash`
  model migration (due 2026-07-24, memory `deepseek-remote-llm-config`) is
  orthogonal — a fence-strip helps whatever model is configured.
