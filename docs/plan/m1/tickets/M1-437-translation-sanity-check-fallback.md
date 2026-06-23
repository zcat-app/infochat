---
id: M1-437
title: "Translation pipeline must sanity-check output and fall back to English with a one-line note"
status: pending
created: 2026-06-23
last_updated: 2026-06-23
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Spec condition (d) — for non-Latin target scripts, output containing zero target-script characters — is NOT implemented. cs (Latin) is the only non-English language in v1, so (d) is unreachable; implementing it now would be defensive code for an impossible-in-v1 scenario (§No defensive code). Deferred to whenever a non-Latin /lang target ships."
  - "No change to LlmTranslationProvider, per-task translator model routing, the TranslationCache, or sanitizer-2 (LlmOutputSanitizer)."
  - "The fallback note is appended to the message body as one line; no new OutboundMessage type or second outbound is introduced."
  - "No retry of the translator on a sanity-check failure — the spec's 'no fallback chain' stands; the fallback is English, not a re-translate."
acceptance:
  - "After a successful translator call, TranslationPipeline applies sanity checks before returning: (c) empty-or-whitespace translated output triggers fallback; (b) translated output byte-identical to the post-sanitizer-1 English input triggers fallback. The pre-existing RuntimeException catch (a) remains a fallback path (TranslationPipeline.java:82-86)."
  - "On every fallback path the returned text is the post-sanitizer-1 English text followed by a one-line note resolved from a new bundle key (e.g. reply.translation.unavailable) via bundleLoader.get(key, scopeLanguage) — sourced from the localization bundle, not hardcoded English (D43)."
  - "The new bundle key is declared in BundleKeys.java and present in both bundles/en.properties and bundles/cs.properties."
  - "A test in TranslationPipelineTest asserts each fallback case returns the English text plus the bundle note: (1) translator returns empty string, (2) translator returns whitespace-only, (3) translator returns the input unchanged, (4) translator throws RuntimeException."
  - "A test in TranslationPipelineTest asserts a successful, distinct (non-identical, non-empty) translation returns the sanitizer-2 output with NO note appended (happy path unchanged)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java (four fallback cases + happy-path no-note)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Translation flow
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date:
  verdict:
  warnings: []
  blockers: []
---

# M1-437: Translation sanity-check + English fallback note

## Context

`docs/spec/llm.md §Failure handling (recap)` states as current behavior
that translation output is sanity-checked and, on failure, "falls back to
English with a one-line note … a localization-bundle string (D43). The
user must never see a hung or garbled response because translation
flaked." The four failure conditions are (a) HTTP/provider error, (b)
output byte-identical to input, (c) empty/whitespace output, (d)
wrong-script output.

The code implements almost none of this. `TranslationPipeline.java:77-99`
only `catch (RuntimeException)` (≈ condition a) and returns the English
text **silently** — no note. Conditions (b) and (c) are absent, so an
empty or whitespace-only LLM response is delivered to the user as an empty
message. No fallback-note bundle key exists.

This ticket implements the v1-reachable conditions — (b) and (c), both
applicable to the only non-English v1 language, cs — and adds the
spec-required bundle-sourced fallback note (also applied to the existing
(a) path). Condition (d) is deferred (cs is Latin; no non-Latin target
exists in v1 — see out-of-scope).

## Acceptance

See frontmatter. The checks sit immediately after the translator call in
`TranslationPipeline`, before the sanitizer-2/cache-write steps. On any
fallback the returned value is the already-sanitized English plus a
one-line note from a new bundle key in the scope language. The fallback
path does not write the cache (consistent with the current code, which
caches only translated forms).

## Out-of-scope

See frontmatter. Condition (d) (non-Latin zero-target-script) is not
built — unreachable in v1 and would be defensive code for an impossible
scenario. The translator provider, routing, cache, and sanitizer-2 are
untouched.

## Notes

- **Source map (verified 2026-06-23):**
  - `TranslationPipeline.java:76-86` — the translator call and the only
    current failure handling (silent English return on RuntimeException).
  - `TranslationPipeline.java:88-99` — sanitizer-2 + cache-write happy
    path; the new checks gate entry to this block.
  - `LlmTranslationProvider.java:86-87` returns `response.text()` verbatim
    — no sanity check lives in the provider.
  - `grep` of `bundles/en.properties` confirms no translation-fallback
    note key exists today.
- **security_relevant: false** — the fallback returns the
  post-sanitizer-1 English text (already passed through the inbound
  sanitizer) plus a static bundle string; no untrusted text bypasses
  sanitizer-2, and the note carries no interpolated user content.
- **Note delivery:** the note is one bundle line appended to the body
  (e.g. "(translation unavailable — showing English)"), localized via the
  scope language bundle, which is available regardless of LLM state (D43).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-437-translation-sanity-check-fallback.md
```
