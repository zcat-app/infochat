---
id: M1-793
title: "Evaluate translation sanity checks on the sanitized reply"
status: done
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  TranslationPipelineTest#aReplyThatSanitizesToEmptyFallsBackToEnglish
  — the sanity conditions (b)/(c) at TranslationPipeline.java:197-226
  evaluate the RAW translator reply, but the delivered text is the
  post-sanitize-2 form. A raw reply that is non-blank and non-identical
  (passes (c) and (b)) but sanitizes to empty (a marker-only reply
  through the M1-789 scaffolding strip) is delivered as the empty
  string — and the "never empty for a non-empty input" promise
  (TranslationPipeline.java:517) is broken. RED on main today: the test
  feeds such a reply and asserts the English-with-note fallback; main
  delivers the empty string.
analysis_ref: docs/plan/m1/tick-analysis/llm-output-leaks-scaffolding-markdown.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    MOVING the protocol-token detectors (ChatAgent, CategoryRollupGenerator,
    SummaryProseGenerator) — M1-791 owns that; this ticket only re-anchors
    the translation sanity checks.
  - >-
    THE EMPTY-BODY DELIVERY GUARD — filed separately as M1-794 (P8); this
    ticket decides the fallback at the pipeline, that ticket guards the
    delivery path.
  - >-
    CHANGING what the spec promises in docs/spec/llm.md §Failure handling:
    the checks and their fallback stay exactly as documented; only the
    operand they evaluate changes.
acceptance:
  - TranslationPipelineTest.aReplyThatSanitizesToEmptyFallsBackToEnglish passes — REPRODUCTION.
  - TranslationPipelineTest.conditionsBCEvaluateTheSanitizedReply pass — FAILURE-MODE (docs/spec/llm.md §Failure handling): conditions (b)/(c) evaluate the post-sanitize-2 text, so a blank check that sees the delivered bytes can never be fooled by deletion shapes.
  - The English-with-note fallback is byte-identical to today for every pre-existing condition-(b)/(c) failure, verified by mvn -B -pl infochat-provider -am verify running TranslationPipelineTest and DisplayHitTranslationTest UNCHANGED.
  - mvn -B -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling (recap)
reviews:
  - round: 1
    date: 2026-08-08
    verdict: APPROVE
    checks: SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS
    diff_stats: 4 files, +60/-8
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-793: Evaluate translation sanity checks on the sanitized reply

## Context

Filed by M1-792's census (row TranslationPipeline.java:276) and the
analysis's P8 family. The prose-leg translator's sanity conditions (b)/(c)
(`docs/spec/llm.md` §Failure handling (recap)) evaluate the RAW translator
reply BEFORE sanitizer-2, but the delivered text is the post-sanitize-2
form. The M1-789 scaffolding strip can delete an entire reply (a
marker-only reply), so a raw reply that passes every sanity check can
sanitize to the empty string — delivered as an empty message. This is the
same defect CLASS as M1-791 (a check on raw text where the delivered text
is the sanitized one) but on the rendering/robustness path, not the
protocol-token path; M1-791's out_of_scope names it.

## Root cause

`TranslationPipeline.run` evaluates condition (c) (`translated.isBlank()`,
:197-201) and condition (b) (`translated.equals(postSanitizer1Text)`,
:222-224) on the raw translator reply, then sanitizes at :276. The
never-empty promise at :517 ("the headline component is never empty for a
non-empty input") is written against the pre-M1-789 transform, which could
not delete a whole reply; the scaffolding strip (M1-789, live) can.

## Pitfalls

- P1: re-anchoring the checks on the sanitized text must not double-call
  sanitize() or change the fallback bytes — the sanitize-2 call at :276
  stays the single call, and the checks move to its result.
- P2: condition (b)'s identity semantics (byte-identical-to-input) must
  keep comparing against `postSanitizer1Text` — sanitize-2 of an English
  echo is still an echo of English input.

## Approach

- **Files to touch:** the two in `files_scope`.
- **Steps, in order:**
  1. Write the reproduction, run RED.
  2. Move the (b)/(c) evaluations to the sanitize-2 result; keep the
     single sanitize call and the fallback path byte-identical.
- **Controls to preserve (§10):** the sanitize-2 call site, the
  English-with-note fallback, the display-hit leg, and every existing
  TranslationPipelineTest / DisplayHitTranslationTest assertion.
- **Pitfall→mitigation:** P1→the checks read the sanitize-2 result, no
  extra call; P2→condition (b) still compares against
  `postSanitizer1Text`.

## Definition of done

The reproduction and the conditions-BC test pass; the fallback is
byte-identical for pre-existing failures; provider verify is green.

## Verification

- P1 → `TranslationPipelineTest.conditionsBCEvaluateTheSanitizedReply` —
  FAILURE-MODE: feeds a raw reply that passes both conditions but
  sanitizes to empty and asserts the fallback; the empty string never
  reaches the caller.
- P2 → the existing identity-echo tests run unchanged.
- acceptance item 1 → `aReplyThatSanitizesToEmptyFallsBackToEnglish`
  (the reproduction).
- acceptance item 3 → `mvn -B -pl infochat-provider -am verify` with
  TranslationPipelineTest and DisplayHitTranslationTest unedited.

## Out-of-scope

Named in `out_of_scope`: no detector moves (M1-791), no delivery-path
guard (M1-794), no spec change — the checks and their fallback are exactly
the documented ones; only their operand changes.

## Census

This ticket is filed BY M1-792's census; its own census is the
single defect row (TranslationPipeline.java:276) plus the M1-794 sibling.
