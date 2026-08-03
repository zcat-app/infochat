---
id: M1-719
title: "Russian (ru) localization and target-script check"
status: pending
created: 2026-07-30
last_updated: 2026-08-04
blocked_by:
  - M1-716
  - M1-746
files_budget: 8
files_scope:
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/LanguageRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
  - docs/spec/commands.md
out_of_scope:
  - >-
    The `en`, `cs` and `es` bundles. Existing key VALUES must not be
    edited — bundle-equality tests pin them.
  - >-
    Script-aware inbound normalization. `IngestTextNormalizer.stripBidiAndZeroWidth`
    strips U+200C/U+200D/U+061C, which is load-bearing orthography in
    Persian and Indic scripts but harmless for Russian — Cyrillic uses
    none of them. Do not touch the strip; it is the M1-676
    copy-paste-dispatch control.
  - >-
    RTL and bidi output shaping. Russian is left-to-right; that work
    belongs to a hypothetical Arabic ticket.
  - >-
    The summarizer's direct-target-language generation shortcut.
    `SummaryProseGenerator` produces English which is then translated;
    changing that is a separate quality ticket.
  - >-
    Per-language full-text regconfig. Deferred; see M1-717 out_of_scope.
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
acceptance:
  - >-
    Retrieval for a non-English scope is settled architecturally by the
    English pivot (D29 amended, D58) — M1-749 anchors the corpus in
    English at ingest, M1-746 translates the query into that anchor — so
    no embedder measurement gates this ticket. The
    `EMBEDDER-MEASUREMENT-RESULTS.md` §4 and §2c verdicts this item used
    to gate on belonged to the embedder swap (M1-717, abandoned as
    superseded) and were never filled. The Cyrillic obligation that
    survives is the translator-output check below, which validates
    generated prose and is independent of the embedder.
  - >-
    `ru.properties` carries a non-empty value for every key in
    `BundleKeys`, and `BundleLoaderTest`'s bilateral parity check covers
    `ru` (D43)
  - >-
    `/lang ru` is accepted and the confirmation reply resolves from the
    `ru` bundle
  - >-
    TranslationPipelineTest.fallsBackToEnglishWhenOutputCarriesNoTargetScript
    passes — translator output containing zero Cyrillic characters takes
    the English-plus-note fallback, alongside the existing (b) blank and
    (c) identical-to-input conditions
  - >-
    The expected script per language is read from `LanguageRegistry`, not
    hardcoded in `TranslationPipeline`, so a fourth script needs no
    pipeline edit
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D43
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

## Context

Russian output localization, plus the first non-Latin target
script — which is what makes `TranslationPipeline`'s missing failure
condition (d) reachable.

`TranslationPipeline.run` implements the spec's failure conditions (b)
blank output and (c) output identical to the input, and its comment states
condition (d) — "zero target-script characters" — is unreachable "because
`cs` is the only non-English language and is Latin-script." Russian
retires that argument: a translator that silently answers in English
passes both (b) and (c) and delivers untranslated prose with no
"(translation unavailable)" note. Condition (d) is the check that catches
it, and Cyrillic is the first target where it can fire — which is why it
ships here rather than in M1-716 (adding it earlier would be a branch that
cannot execute, forbidden by the no-defensive-code rule).

Russian was the strongest case for the embedder swap: on
`nomic-embed-text` it ranks 0/7 within a Russian-only pool with a negative
margin, random Cyrillic characters score 0.710 against a real Russian
sentence versus 0.805 for a genuine paraphrase, and against the English
corpus it ranks 1/7 with 0/7 above the admit line. The English pivot (D29
amended, D58) makes those numbers inapplicable rather than better: M1-749
embeds the English anchor and M1-746 translates the query into it, so no
Cyrillic ever reaches the embedder. M1-717 is abandoned as superseded.
Condition (d) is untouched by this — it validates *translator output*, not
embeddings.

## Census

The class is every key `BundleKeys` declares. Re-runnable inventory:

```
grep -cE '^\s+public static final String [A-Z_]+ =' \
  infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
# 421 as of 2026-07-30

grep -oE '^[A-Za-z0-9_.-]+=' infochat-provider/src/main/resources/bundles/en.properties | sort
```

The authoritative check is not the grep but `BundleLoaderTest`'s reflective
bilateral-parity assertion, which fails the build on any key present in one
loaded bundle and absent from another. The grep is the pre-flight inventory;
the test is the gate.

**Known deliberate asymmetry:** `test.fallback.probe` is present in
`en.properties` only, exercising the missing-key→`en` fallback in
`BundleLoader.get(String, String)`. A new bundle must NOT add it.

Second class in this ticket, and the reason it is `complexity: medium`:
every `TranslationPipeline` failure condition. `(b)` blank output and `(c)`
output identical to input are implemented at
`TranslationPipeline.java:106-112`; `(d)` zero target-script characters is
absent, with the comment at `:103-105` stating why. Confirm at `start` that
`(b)` and `(c)` are untouched by the `(d)` addition — all three are one
fallback decision and the existing two are the regression surface.

## Acceptance

See frontmatter. There is no measurement verdict to confirm at `start` —
see §Context for why the §4/§2c gate no longer applies.

## Out-of-scope

See frontmatter `out_of_scope`.

## Notes

**Why the script belongs in the registry.** Putting the expected script on
the `LanguageRegistry` record rather than in a `TranslationPipeline` switch
means a future Thai or Japanese bundle inherits the check for free. This is
the reason M1-716 introduces a record per language instead of a bare set.

**Cyrillic needs no normalization change.** Unlike Persian or Devanagari,
Russian does not use ZWNJ/ZWJ, so the inbound
`stripBidiAndZeroWidth` pass alters nothing in a Russian query. That is
what keeps this ticket out of the M1-676 security surface.
