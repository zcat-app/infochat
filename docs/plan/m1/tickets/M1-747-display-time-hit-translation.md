---
id: M1-747
title: "Display-time translation of a retrieved post's title and snippet into the reader's language"
status: pending
created: 2026-08-02
last_updated: 2026-08-03
blocked_by:
  - M1-749
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/LlmTranslationProvider.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-llm-adapter/src/main/resources/prompts/translator.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Translating the post BODY beyond the headline's existing body-fallback
    excerpt. D29 permits title and snippet only; the DisplayHeadline
    derivation (title, else the bounded body excerpt) IS the surface — a
    body is unbounded and is the thing the user is explicitly shown in its
    original form.
  - >-
    Writing any translated text back to the `post` row (or anywhere else).
    Display-time output is ephemeral. The `title_en`/`body_en` fields are
    M1-749's RETRIEVAL artifacts — display translation reads the ORIGINAL
    `title`/`body`, never the `*_en` fields, and persists nothing.
  - >-
    Translating a post that is already in the reader's language, an
    `en`-scope hit, or a post with unknown (`null`) source language. The
    no-op path must stay a no-op.
  - >-
    The ingest leg (M1-749, done), the source-language write path
    (M1-750), and the query leg (M1-746).
  - >-
    Changing which posts are retrieved, or their order. This ticket renders
    a result set; it never selects one.
  - >-
    PROMPT inputs. `SummaryProseGenerator.buildPrompt` keeps appending the
    full untranslated title, and `CategoryRollupGenerator` keeps feeding
    `DisplayHeadline.of` output into its prompt untranslated.
    `DisplayHeadline.of` itself stays a pure derivation with no translator
    call inside it — translation happens at the display call site only,
    because one of the helper's callers is a prompt builder.
  - >-
    The OTHER display surfaces, filed as follow-ups: `/saved`
    (M1-755 — its `saved_post` snapshot columns carry no source language,
    a design question of its own) and the digest-broadcast headline block
    plus the two degraded renderers (M1-756 — `DigestRenderer
    .appendHeadlines`, `DegradedDigestRenderer`,
    `SummaryProseGenerator.degradedProseFor`; the degraded paths compose
    bundle-sourced prose, which D43's bundle-not-translator invariant
    keeps out of the translator, so they need a design call this ticket
    does not make).
  - >-
    The D21 `UNTRUSTED_CONTENT` wrapper paths. Chat-mode retrieval hits
    reach users only through model prose that already runs the full
    sanitize -> translate pipeline; no display-hit surface exists there,
    and nothing in this ticket touches prompt assembly.
acceptance:
  - >-
    When the scope language is `en` (the default, and every scope today),
    rendering is BYTE-IDENTICAL to today and no translator call is made —
    asserted with a provider spy. A regression here adds an LLM call to
    every result set in the deployment.
  - >-
    `TranslationPipeline` gains a display-hit entry point
    (`runForDisplayHit(displayHeadline, sourceLanguage, scopeLanguage)` or
    similarly named; `sourceLanguage` is `@Nullable`). No-op legs, each
    returning the input with no provider call: `en` scope, source language
    equal to scope language (case-insensitive), `null` source language,
    empty headline (the renderer's existing empty-headline omission
    short-circuits before the pipeline). The translating leg runs
    translator call -> flatten to one line -> sanitizer-2 -> cache write ->
    truncate -> marker. `run()`'s prose behaviour is byte-unchanged.
  - >-
    CONTROLS CARRIED ACROSS (engineering rules §10) — the rendered
    headline is rerouted through a model, so the controls that govern it
    travel, enumerated: (a) the translator's output is flattened to one
    line BEFORE sanitizer-2 — the sanitizer's token separators are
    ASCII-only and its canonical form leaves U+0085/U+2028/U+2029 intact,
    so an unflattened output could smuggle a line-start privileged command
    past the closed list exactly as the 2026-07-30 DisplayHeadline
    finding; (b) sanitizer-2 is the provider `LlmOutputSanitizer` bean, so
    every match keeps its `LLM_OUTPUT_SANITIZED` audit row, and the
    sanitize unit is ONE post's headline per call (M1-697) — never a
    concatenation; (c) the translated headline is re-bounded at
    `DisplayHeadline.MAX_LENGTH` via DisplayHeadline's existing
    marker-safe/surrogate-safe truncate exposed as a public pure static
    (existing callers byte-unchanged) — sanitize-before-truncate order
    preserved so the audit sees the full output before the cut; (d) the
    marker is appended AFTER truncation so the cut can never produce a
    half-marker. Each is asserted by a test naming the control.
  - >-
    A translator failure or blank output falls back to the ORIGINAL
    headline plus the existing `REPLY_TRANSLATION_UNAVAILABLE` note (the
    same key the prose path uses), with NO marker — not an error and not
    an empty hit. Degraded comprehension beats a lost result.
  - >-
    The existing `TranslationCache` is reused — key (sha256 of the
    headline text, target lang). The cached value is the flattened,
    sanitized translation; truncation and the marker are applied OUTSIDE
    the cache. A hit skips both the translator call and sanitizer-2, as it
    does for prose today, and still gets truncate + marker.
  - >-
    A new bundle key marks machine-translated headlines, appended
    space-separated to every delivered translation, plain text (D30). The
    key is a `BundleKeys` constant (the D43 parity test walks BundleKeys'
    reflective field set, so a properties-only key would evade the twin
    check) with both `en` and `cs` values present.
  - >-
    `EligiblePostQuery.selectPosts` projects `s.language`; the `Post`
    record's canonical constructor gains a trailing `@Nullable String
    sourceLanguage`, and BOTH existing shapes (the 14-component previous
    canonical and the 10-component M1-724 compat) remain as compat
    overloads defaulting it `null`, so every §Census site compiles
    unchanged. `null` means "unknown — never translate".
  - >-
    /retry replay parity: `SELECT_POSTS_BY_UIDS` projects `s.language` and
    `mapPost` carries it, so a `cs`-scope flat replay translates the same
    headlines the original render did (subject to the same cache/provider
    temporal variance the `summary:` field already has).
  - >-
    `translator.md`'s "from English" becomes "from {{SOURCE_LANGUAGE}}";
    `LlmTranslationProvider` substitutes the from-locale's English display
    name, before `{{content}}` (the substitution-order invariant — content
    last — per the M1-749 round-1 finding). For the prose path
    (from=ENGLISH) the rendered prompt is BYTE-IDENTICAL to today,
    asserted.
  - >-
    The display path introduces no write: neither `TranslationPipeline`
    nor `ClusterBlockRenderer` holds a `DataSource`; `post.title` /
    `post.body` are read-only inputs and the output is ephemeral (D29).
    Reviewer-checkable structurally.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
      — `en` scope renders byte-identical with zero provider calls (spy);
      `cs` scope + `en` source translates the headline and appends the
      marker; `cs` scope + `cs` source, `null` source language, and empty
      headline each make no call; a thrown/blank translator falls back to
      the original headline + `REPLY_TRANSLATION_UNAVAILABLE` note with no
      marker; a multiline translator output is flattened BEFORE
      sanitizer-2 (the U+2028 smuggle case is the fixture); an overlong
      translator output is re-truncated with the marker-safe cut and the
      marker lands after the cut; a cache hit skips translator and
      sanitizer-2 but still gets truncate + marker; the cached value
      carries no marker; the prose path's rendered prompt bytes are
      unchanged by the {{SOURCE_LANGUAGE}} substitution.
  preserves:
    - >-
      Existing `TranslationPipeline` prose behaviour (`run()`) and its
      sanitizer ordering — sanitize, translate, sanitize — plus
      `TranslationPipelineIT`'s exact call-count pins (en-scope fixtures
      see zero new calls; the new leg is not reachable from `run()`).
    - >-
      `TranslationCache` semantics: 24h TTL, 10k entries, hit skips
      translator and second sanitizer.
    - >-
      `ClusterBlockRendererTest`'s existing byte pins (all en-scope — the
      compat constructor defaults `sourceLanguage` null, which is a no-op
      leg).
    - >-
      Every `BundleLoaderTest` keyset-parity assertion.
    - >-
      `DisplayHeadline`'s existing behaviour and tests — the only change
      is truncate's visibility.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/security.md §LLM output sanitizer
  - docs/design/05-llm-and-embeddings.md §5.6
decision_refs:
  - D29
  - D43
  - D30
  - D19
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

M1-749 makes a cross-language hit *findable*. It does not make it *readable*:
D29 retains the original body as what the user is shown, so a Czech reader
who matches an English (or, post-M1-750, Spanish) post gets that language's
headline back. The amendment (`21ad3517`) closed this explicitly —
"translating a retrieved post's title and snippet into the reader's language
at display time is permitted, because comprehension of a cross-language hit
is otherwise unaddressed."

**Surface binding (verified against the code, 2026-08-03).** D29's "title
and snippet" binds to the `DisplayHeadline` derivation — the post's title,
else its bounded body excerpt (which IS the snippet case; the body branch is
capped at `BODY_SCAN_LIMIT` before sanitize and `MAX_LENGTH` at display).
The one NON-degraded surface where `/summary` and `/retry` show a retrieved
post's source-authored text is `ClusterBlockRenderer`'s headline line (flat
blocks; one headline per cluster, the first post). `DigestRenderer
.renderSummarySections` — the bare/`--full` path — renders section headers
and LLM prose only, no headlines, so it needs no change. The other headline
surfaces (`/saved`, the digest broadcast's normal-mode headline block, the
two degraded renderers) are follow-ups M1-755/M1-756 — see out_of_scope.

This is deliberately the cheap half of the problem: one bounded string
(≤ `MAX_LENGTH` + ellipsis) per rendered cluster, so a result set costs at
most `cluster-cap` extra translator calls, cache-deduped — at most doubling
the per-cluster translation volume a `cs` scope already pays for the
`summary:` field, with no new config key.

## Approach

Reuse `TranslationPipeline` and `TranslationCache` rather than growing a
parallel path — a new display-hit entry point on the same bean, sharing the
cache, the sanitizer bean, and the fallback note. The pipeline order on the
translating leg mirrors `DisplayHeadline`'s own (flatten → sanitize →
truncate) for the same reasons, applied to the translator's output.

The `en` scope path is a strict no-op, asserted with a spy. Every scope
today is `en`, so a regression there does not degrade a feature; it adds a
translator call and a failure mode to every rendered result in the
deployment.

The translation INPUT is the DisplayHeadline OUTPUT — already flattened,
sanitized, and truncated — so "the snippet is capped before translation" is
structural: a 3,000-character body can never drive the request size.

`Locale`-wise the translator call passes the post's real source locale; the
provider's `from.equals(to)` short-circuit stays a redundant second guard
behind the pipeline's own same-language no-op.

## Census

Post record construction sites (`grep -rn "new Post(\|new
EligiblePostQuery.Post(" --include=*.java infochat-provider/src`; record
patterns and `Post::new` refs verified absent, 2026-08-03): 29 invocations.

Disposition: ALL 29 keep compiling unchanged via the two compat overloads
(the previous 14-component canonical and the 10-component M1-724 compat,
both defaulting `sourceLanguage` to `null`). Exactly 2 sites move to the new
15-component canonical: `EligiblePostQuery.selectPosts` and
`RetryCommandHandler.mapPost` — the two real SELECT-backed constructors.

| File | Sites | Disposition |
|---|---|---|
| main: EligiblePostQuery.java | 1 | → 15-arg canonical (projects `s.language`) |
| main: RetryCommandHandler.java | 1 | → 15-arg canonical (projects `s.language`) |
| main: DigestPostCollector.java | 1 | compat (digest surfaces are M1-756) |
| test: ClusterBlockRendererTest.java | 4 | compat (en-scope pins preserved) |
| test: RetryCommandHandlerTest.java | 2 | compat |
| test: SummaryCommandHandlerTest.java | 1 | compat |
| test: CategoryRollupGeneratorTest.java | 1 | compat |
| test: DegradedDigestRendererTest.java | 1 | compat |
| test: DigestCategorizerTest.java | 2 | compat |
| test: DigestRendererSectionsTest.java | 1 | compat |
| test: DigestRendererTest.java | 1 | compat |
| test: DigestWorkerClockTest.java | 1 | compat |
| test: DigestWorkerTest.java | 3 | compat |
| test: DisplayHeadlineTest.java | 1 | compat |
| test: ClusterProminenceTest.java | 1 | compat |
| test: ClusterTraversalTest.java | 1 | compat |
| test: SummaryProseGeneratorTest.java | 4 | compat |
| test: SummaryProseInjectionTest.java | 1 | compat |
| test: SummaryProseRefusalDegradeTest.java | 1 | compat |

## Out-of-scope

See frontmatter. In one line each: no body translation, no persistence, no
no-op regression, no other legs (M1-746/749/750), no retrieval changes, no
prompt-input translation, other display surfaces → M1-755/M1-756, D21
paths untouched.

## Notes

- **Display translation reads the ORIGINAL `title`/`body`, never
  `title_en`/`body_en`.** The `*_en` fields are retrieval artifacts (one
  hop, source → en). A `cs` reader of a Spanish post gets es→cs directly,
  not es→en→cs.
- **Only `cs` is selectable today** (`LanguageRegistry` enables `{en, cs}`),
  so the translating leg fires for `cs` scopes only; and `source.language`
  is `'en'` for every row until M1-750 lands the write path. The
  `{{SOURCE_LANGUAGE}}` prompt slot is what makes the non-`en`-source case
  correct the day it becomes reachable, at zero byte-cost to the prose path
  today.
- **This is the only leg that shows model output directly to a user as if
  it were source content** — why `security_relevant: true` and why the §10
  enumeration is in acceptance rather than assumed.
- **The marker key is not decoration.** Without it a machine translation is
  indistinguishable from the publisher's own words, which matters when the
  headline is about a security advisory or a price. D30 keeps it plain
  text; applying it AFTER truncation keeps it whole.
- **Fallback direction is deliberate**: on failure the reader sees the
  original language plus the existing note, not an error. The opposite
  turns a translator outage into an empty result set.
- Bundle keys need the `cs` twin in the same diff or `BundleLoaderTest`
  fails on D43's bilateral keyset requirement.
- Pre-flight: `python3 scripts/lint-ticket.py
  docs/plan/m1/tickets/M1-747-display-time-hit-translation.md` re-run at
  start.
