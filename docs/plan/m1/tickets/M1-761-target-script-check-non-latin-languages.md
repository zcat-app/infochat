---
id: M1-761
title: "Translator output target-script check, required before a non-Latin language is enabled"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/LanguageRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/LanguageRegistryTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ENABLING ANY LANGUAGE. This ticket does not add a bundle, does not add
    a `LanguageRegistry` entry, and does not flip a language on. It builds
    the check that a non-Latin enablement REQUIRES. The es/tr/ru work owns
    its own enablement.
  - >-
    NEW BUNDLE KEYS. The failure path reuses the EXISTING
    `reply.translation.unavailable` note — a zero-target-script result is
    the same user-visible outcome as the blank and identical cases it
    already covers. Adding a key would break keyset parity against the
    in-flight bundles.
  - >-
    LANGUAGE DETECTION over post or query text. D29 is explicit that
    language is DECLARED, never inferred. This check reads the DECLARED
    target language's expected script and asks only "does the output
    contain any character in it" — it never guesses what language a string
    is in.
  - >-
    Conditions (a) provider error, (b) output identical to input and (c)
    blank output. All three are built and behave as specified; this ticket
    adds condition (d) beside them without touching them.
  - >-
    The display-hit leg's cache keyspace, marker, or truncation. A
    rejected translation is never cached — the cache stores translated
    forms only — which is the existing fallback rule, not a new one.
acceptance:
  - >-
    `TranslationPipeline` implements spec condition (d): a translator
    output containing ZERO characters in the target language's expected
    script is treated as unusable and falls back to the original plus the
    existing localized note, exactly as conditions (b) and (c) do. Both
    legs — `run` and `runForDisplayHit` — are covered; a display headline
    can fail this way as readily as prose.
  - >-
    The check is DATA-DRIVEN off `LanguageRegistry`, not a hard-coded
    language list. `EnabledLanguage` gains the expected-script metadata
    its javadoc already anticipates ("per-language metadata (expected
    Unicode script, …) lands on this record with the ticket that needs
    it") — this is that ticket. A language whose script is Latin yields a
    Latin expectation and the check is a no-op for it, so enabling es or
    tr changes nothing observable.
  - >-
    A language cannot be enabled without declaring its script: the
    registry entry requires the field, so a future non-Latin addition
    cannot silently skip the check. This is the load-bearing half — the
    defect being fixed is not a missing branch, it is a reachability
    assumption that no longer holds and that nothing re-checks.
  - >-
    The stale justification is corrected where it is written down.
    `TranslationPipeline`'s step 3.5 comment currently states that
    condition (d) "is unreachable in v1 — cs is the only non-English
    language and is Latin-script — so it is not built." That sentence is
    the reason the check is absent; it must be replaced by the mechanism,
    not merely have code appear beside it.
  - >-
    A test proves the reachable case with a synthetic non-Latin registry
    entry: a translator returning Latin text for a Cyrillic-script target
    falls back with the note rather than delivering the untranslated
    string as though it were a translation. It must NOT depend on `ru`
    being enabled — the check has to be provable before the language
    lands, or it cannot gate it.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/LanguageRegistryTest.java
  preserves:
    - >-
      Conditions (a)/(b)/(c) fallback behaviour and the en short-circuit,
      byte-unchanged.
    - >-
      The display-hit cache keyspace, marker suppression on a
      self-identical translation, and the `hit/` partition.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
  - D43
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-761: Target-script check before a non-Latin language is enabled

## Context

Found 2026-08-04 while reviewing the display-translation design against
the in-flight es/tr/ru localization work.

`TranslationPipeline` sanity-checks translator output before sanitizing
it. Three of the spec's four failure conditions are implemented: provider
error, blank output, and output byte-identical to the input. The fourth —
zero characters in the target script — is deliberately absent, and the
code says why:

> Condition (d) (zero target-script characters) is unreachable in v1 — cs
> is the only non-English language and is Latin-script — so it is not
> built.

That argument is sound today and dies the moment a Cyrillic-script
language is enabled. Spanish and Turkish do not trip it; both are Latin.
Russian does.

The failure it guards against is specific and silent. A translator asked
for Russian that returns English is not blank, and is not byte-identical
to its input once any word differs — so it passes both existing checks and
is delivered to the reader as though it were a translation, complete with
the D30 machine-translation marker attached to untranslated text.

## Why this is filed separately from the language work

Enabling a language looks like dropping in a bundle and adding a registry
entry. Nothing about that task points at translation failure handling, so
the assumption this ticket repairs is very unlikely to be noticed by the
session doing it — the comment stating the assumption lives in a different
package from anything a bundle addition touches.

Filing it separately also means the check can land BEFORE the language
does, which is the only ordering in which it actually gates anything.

## Notes

- `security_relevant: true`: the failure delivers model output to a user
  under a provenance marker that misdescribes it, and the D30 marker is a
  spec-level honesty commitment about what is machine-generated.
- The registry's javadoc already names this ticket's landing spot, which
  is a good sign the extension point was designed for exactly this and a
  reason to prefer it over a lookup table in the pipeline.
- If the `ru` session lands first, this becomes a live defect rather than
  a preventative one. Worth checking `LanguageRegistry`'s enabled set
  before starting, and saying which case it was in the commit message.
