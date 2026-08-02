---
id: M1-747
title: "Display-time translation of a retrieved post's title and snippet into the reader's language"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by:
  - M1-745
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Translating the post BODY. D29 permits title and snippet only. A body
    is unbounded, would cost a translator call proportional to corpus text
    per result set, and is the thing the user is explicitly shown in its
    original form.
  - >-
    Writing any translated text back to the `post` row. Display-time output
    is ephemeral. A diff that persists it has turned a presentation concern
    into a second derived field and collided with M1-745's.
  - >-
    Translating a post that is already in the reader's language. The
    no-op path must stay a no-op.
  - >-
    The ingest leg (M1-745) and the query leg (M1-746).
  - >-
    Changing which posts are retrieved, or their order. This ticket renders
    a result set; it never selects one.
acceptance:
  - >-
    When the scope language is `en` (the default, and every scope today),
    rendering is BYTE-IDENTICAL to today and no translator call is made.
    Asserted — a regression here adds an LLM call to every result set in
    the deployment.
  - >-
    When the scope language is non-English and a retrieved post's
    `source.language` differs from it, the post's TITLE and SNIPPET are
    translated into the scope language for display. The stored
    `post.title` / `post.body` are untouched, asserted directly.
  - >-
    Translation is bounded per result set - at most one call per rendered
    hit, and the snippet is capped before translation, not after. Capping
    after would let a long body drive the call size while displaying a
    short excerpt.
  - >-
    Translated hit text passes `LlmOutputSanitizer` and folds into the D21
    `UNTRUSTED_CONTENT` wrapper on the same path untranslated hit text uses
    today. Carried across explicitly (engineering rules §10) - the rendered
    string is being rerouted through a model, and the controls that
    governed it must travel with it.
  - >-
    A translator failure or open breaker falls back to the ORIGINAL title
    and snippet with the existing one-line note, not to an error and not to
    an empty hit. Degraded comprehension beats a lost result.
  - >-
    The existing `TranslationCache` is reused rather than duplicated - its
    key is already (sha256 of source text, target lang), which is exactly
    this shape. A hit skips both the translator call and the sanitizer, as
    it does for prose today.
  - >-
    A new bundle key marks display-translated hits so the reader can tell
    machine-translated text from source text, with the `cs` twin present
    (D43 bilateral keyset — a missing twin fails `BundleLoaderTest`).
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
      — `en` scope renders unchanged with no
      provider call; a non-English scope translates title and snippet only;
      the stored row is untouched; a same-language post is not translated;
      a thrown translator falls back to the original text; the snippet is
      capped before the call; the sanitizer runs on translated output.
  preserves:
    - >-
      Existing `TranslationPipeline` behaviour for LLM-authored prose
      (cluster summaries, chat replies, digest prose) and its sanitizer
      ordering — sanitize, translate, sanitize.
    - >-
      `TranslationCache` semantics: 24h TTL, 10k entries, hit skips
      translator and second sanitizer.
    - >-
      Every `BundleLoaderTest` keyset-parity assertion.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/design/05-llm-and-embeddings.md §5.6
decision_refs:
  - D29
  - D43
  - D21
  - D30
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-747: Display-time translation of a retrieved post's title and snippet

## Context

M1-745 and M1-746 make a cross-language hit *findable*. Neither makes it
*readable*: D29 retains the original body as what the user is shown, so a Czech
reader who searches and matches a Spanish post gets a Spanish headline back.

The amendment (`21ad3517`) closed that explicitly — "translating a retrieved
post's title and snippet into the reader's language at display time is
permitted, because comprehension of a cross-language hit is otherwise
unaddressed." Retrieval was solved; comprehension was addressed nowhere in the
spec.

This is deliberately the cheap half of the problem. Title and snippet are a
handful of short strings per result set, not per corpus row — the cost scales
with what a user actually reads, not with what has been ingested.

## Approach

Reuse `TranslationPipeline` and `TranslationCache` rather than growing a
parallel path. The cache's key — (sha256 of source text, target language) — is
already the right shape for this, and headlines repeat across result sets, so
the hit rate should be high.

The `en` scope path is a strict no-op. Every scope today is `en`, so a
regression there does not degrade a feature; it adds a translator call and a
failure mode to every rendered result in the deployment.

Cap the snippet **before** the call. Capping after means a 3,000-character body
drives the request while 200 characters reach the reader.

## Out-of-scope

Body translation. Persisting translated text. Translating same-language posts.
The ingest and query legs. Anything that changes which posts are retrieved or
in what order.

## Notes

- **This is the only leg that shows model output directly to a user as if it
  were source content**, which is why `security_relevant: true` and why the
  sanitizer and D21 wrapper are named in acceptance rather than assumed. The
  rendered string is being rerouted through a model; engineering rules §10
  requires the controls travel with it.
- **The marker key is not decoration.** Without it a machine translation is
  indistinguishable from the publisher's own words, which matters when the text
  is a headline about a security advisory or a price. D30 keeps it plain-text.
- **Fallback direction is deliberate**: on failure the reader sees the original
  language, not an error. The opposite turns a translator outage into an empty
  result set.
- Bundle keys need the `cs` twin in the same diff or `BundleLoaderTest` fails on
  D43's bilateral keyset requirement.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-747-display-time-hit-translation.md`
  is clean.
