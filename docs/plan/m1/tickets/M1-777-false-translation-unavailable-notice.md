---
id: M1-777
title: "Bot claims 'showing in English' on a reply that is not English"
status: done
created: 2026-08-06
last_updated: 2026-08-06
clarity_check:
  date: 2026-08-06
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE GENERATOR SIDE. Why the text arrives already in the target
    language is M1-778. This ticket makes the NOTICE truthful about
    whatever text it is handed; it does not change what the chat agent
    or summarizer produces.
  - >-
    ANY BUNDLE FILE. `reply.translation.unavailable` keeps its current
    wording in all five bundles. Adding or editing a key here would
    collide with M1-781/M1-782, which own bundle edits.
  - >-
    The other three fallback legs from M1-437 — empty output,
    whitespace-only output, and the translator throwing. Those are
    genuine failures and keep their current behaviour, note included.
acceptance:
  - >-
    THE (b) LEG STOPS ASSUMING ITS INPUT IS ENGLISH. M1-437's acceptance
    made "translated output byte-identical to the post-sanitizer-1
    English input" a fallback trigger. That is right when the input IS
    English and wrong when the text is already in the reader's language,
    where returning it unchanged is the CORRECT translation. Decide once
    between: (a) verify the text's language before claiming it is
    English, or (b) have the caller tell the pipeline the input's
    language so identity can be interpreted. Record which and why.
  - >-
    A reply whose text is already in the scope language is returned with
    NO note appended. Pinned by a test: scope language `cs`, input text
    Czech, translator returns it unchanged → output is the Czech text
    alone.
  - >-
    A genuine echo failure still falls back WITH the note: scope
    language `cs`, input text English, translator returns the English
    unchanged → English text plus `reply.translation.unavailable`.
    This is the M1-437 case and must not regress.
  - >-
    The notice never asserts a language the pipeline has not
    established. If the leg cannot tell what language the text is in, it
    does not claim "showing in English".
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
  preserves:
    - >-
      All four M1-437 fallback cases (empty, whitespace-only, identical,
      throw) keep their asserted behaviour where the input really is
      English.
    - >-
      The happy path (distinct, non-empty translation) still returns the
      sanitizer-2 output with no note.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
decision_refs:
  - D43
reviews:
  - round: 1
    date: 2026-08-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 151
      removed: 23
overrides: []
---

## Why

The user is shown a Czech sentence with an English-language disclaimer stapled to
it. It reads as a broken bot even though the reply itself is fine.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §F2).

## Observed

Provider log gives the mechanism directly:

```
TranslationPipeline: translator returned unusable output for target_language=cs
(blank or identical to input); falling back to English with a note
```

What the user sees — Czech text, English claim:

```
Ještě upřesněte, co vás u Zcash zajímá víc – jestli jen aktuální cenu, nebo
spíš souhrn toho, co se o projektu nově píše (např. upgrade Ironwood)?
(překlad není k dispozici — zobrazuji anglicky)
```

## Expected

```
Ještě upřesněte, co vás u Zcash zajímá víc – jestli jen aktuální cenu, nebo
spíš souhrn toho, co se o projektu nově píše (např. upgrade Ironwood)?
```
