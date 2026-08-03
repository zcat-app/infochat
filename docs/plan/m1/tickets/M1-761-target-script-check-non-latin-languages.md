---
id: M1-761
title: "Extend the translator target-script check to the display-hit leg"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by:
  - M1-719
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The prose leg (`TranslationPipeline.run`) and the `LanguageRegistry`
    script metadata it reads. BOTH SHIPPED IN M1-719 — `EnabledLanguage`
    carries a required `UnicodeScript`, `LanguageRegistry.scriptOf`
    exposes it, and `run` applies condition (d) to non-Latin targets.
    This ticket is the residual: the leg M1-719 deliberately left out.
    Do not re-implement either; do not widen `scriptOf`.
  - >-
    ENABLING ANY LANGUAGE. This ticket adds no bundle and no registry
    entry. `ru` was enabled by M1-719; `tr` is M1-720.
  - >-
    NEW BUNDLE KEYS. The failure path reuses the EXISTING
    `reply.translation.unavailable` note — a zero-target-script result is
    the same user-visible outcome as the blank case the leg already
    covers. Adding a key would break keyset parity across the shipped
    bundles.
  - >-
    LANGUAGE DETECTION over post or query text. D29 is explicit that
    language is DECLARED, never inferred. This check reads the DECLARED
    target language's expected script and asks only "does the output
    contain any character in it" — it never guesses what language a
    string is in.
  - >-
    Conditions (a) provider error and (c) blank output on the display-hit
    leg. Both are built and behave as specified; this ticket adds (d)
    beside them without touching them.
  - >-
    The display-hit cache KEYSPACE (the `hit/` partition and its
    per-(scope_kind, scope_id) split), the marker string, and the
    truncation bound. This ticket changes WHEN a value is written and
    marked, never how the key or the bound is computed.
acceptance:
  - >-
    `runForDisplayHit` applies condition (d): a translation carrying zero
    characters of the target language's expected script falls back to the
    original headline plus the existing localized note, exactly as the
    leg's blank-output branch already does. Non-Latin targets only —
    the Latin carve-out is `llm.md` §Failure handling and is already
    implemented for `run`; reuse that predicate rather than restating it.
  - >-
    ORDERING, which is the reason this is a separate ticket rather than
    one more line in M1-719. The check must run AFTER the byte-identity
    passthrough `finishDisplayHit` already applies (a headline that
    translates to itself — a proper noun, a ticker, an all-Latin title —
    is delivered unmarked and unchanged, and is NOT a failure) and BEFORE
    `translationCache.put`, so a rejected translation is never cached.
    The leg caches byte-identical translations deliberately, unlike
    `run`'s condition (b); that behaviour is preserved.
  - >-
    A rejected translation is never marked. The D30 machine-translation
    marker must not be attached to text the check has just judged
    untranslated — that pairing is the security-relevant failure this
    ticket closes, not a cosmetic one.
  - >-
    `DisplayHitTranslationTest` covers three cases against a `ru` scope: a
    Latin-only translation that DIFFERS from the input takes the
    fallback-with-note path, is not cached and is not marked; a
    translation carrying Cyrillic is delivered, marked and cached; and a
    headline whose translation is byte-identical to the input is still
    returned unmarked and unchanged (the passthrough the ordering above
    must not break).
  - >-
    `TranslationPipelineTest`'s `run`-leg condition (d) tests from M1-719
    still pass byte-unchanged — the shared predicate is refactored, not
    re-specified.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
  preserves:
    - >-
      Conditions (a)/(c) fallback behaviour on the display-hit leg and the
      en short-circuit, byte-unchanged.
    - >-
      The display-hit cache keyspace, marker suppression on a
      self-identical translation, and the `hit/` partition.
    - >-
      M1-719's condition (d) behaviour on the prose leg and its
      LanguageRegistry script declarations.
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

# M1-761: Target-script check on the display-hit leg

## Context

Filed 2026-08-04 while reviewing the display-translation design against
the in-flight es/tr/ru localization work, and **rescoped the same day**
after M1-719 landed the bulk of it.

`TranslationPipeline` sanity-checks translator output before sanitizing
it. The spec's fourth failure condition — zero characters in the target
script (`llm.md` §Failure handling) — was absent because the code argued
it was unreachable while `cs` was the only non-English language. Russian
retired that argument, so M1-719 built it: `EnabledLanguage` now carries a
required `UnicodeScript`, `LanguageRegistry.scriptOf` exposes it, the
stale unreachability comment is gone, and `run` falls back to English plus
the localized note when a non-Latin target receives output carrying none
of its script.

M1-719 applied it to `run` only. This ticket is the remaining leg.

## Why the display-hit leg is separate rather than one more line

`runForDisplayHit` (M1-747) is not the prose leg with a different operand.
It carries its own ordering obligations that condition (d) has to be
threaded between, and getting them wrong fails silently:

- It deliberately does NOT implement condition (b). A short headline can
  translate to itself legitimately — a proper noun is not a failure — so
  `finishDisplayHit` returns a byte-identical translation unmarked and
  unchanged. Condition (d) placed before that passthrough would refuse
  exactly the headlines the passthrough exists to allow.
- It caches byte-identical translations on purpose, which `run` does not,
  to spare the translator call on every subsequent render. A (d) check
  placed after the cache write would persist a rejected translation.
- It appends the D30 machine-translation marker. Marking text the check
  has just judged untranslated is the failure mode, not a side issue.

None of that is visible from the prose leg, which is why M1-719 stopped at
its boundary rather than guessing.

## What the failure looks like here

A translator asked for Russian that returns different English is not
blank, and — because the words changed — is not byte-identical to its
input. It therefore passes every check the display leg has today and
reaches the reader as a translation, carrying the marker that says a
machine produced it in their language. The reader gets no signal that the
line is untranslated.

## Notes

- `security_relevant: true` (carried over from the original filing): the
  failure delivers model output under a provenance marker that
  misdescribes it, and the D30 marker is a spec-level honesty commitment
  about what is machine-generated. Narrowing the ticket to one leg does
  not weaken that — the marker exists only on this leg.
- The predicate is already written. `TranslationPipeline.containsScript`
  and the non-Latin guard around it were added by M1-719 for `run`;
  lifting that guard into one predicate both legs call is what this
  ticket should produce, and is why `TranslationPipelineTest` is in
  `files_scope` — the existing `run` tests must stay byte-unchanged
  through the refactor.
- `ru` IS enabled as of M1-719, so this is a live defect on the display
  leg rather than a preventative fix.
