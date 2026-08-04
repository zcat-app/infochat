---
id: M1-762
title: "Locale-aware uppercase for digest section headers"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by:
  - M1-720
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
out_of_scope:
  - >-
    The bundle VALUES. Do not "fix" this by pre-uppercasing
    `reply.digest.category.*` in the properties files: `sectionHeader`'s
    two keys are shared with `/summary`, which renders them WITHOUT
    uppercasing, so baking caps into the value would corrupt the
    `/summary` surface. The case conversion must stay in the renderer.
  - >-
    The two remaining locale-less `toLowerCase()` sites,
    `DigestCommandHandler.java:276` and
    `QuarantineReviewListener.java:158`. Both fold ASCII-only literals
    (`on`/`off`, `PENDING`) and both depend on the JVM DEFAULT locale,
    which no scope-language setting reaches — a different concern from
    this ticket's scope-language conversion. See M1-720 §Notes.
  - >-
    Enabling, disabling or retranslating any language. This ticket
    changes how already-translated header prose is cased, nothing else.
  - >-
    `AssetRegistry.capitalize` (`Character.toUpperCase(char)`), which
    takes no locale by construction and operates on English asset names,
    not bundle prose.
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
acceptance:
  - >-
    A Turkish-scope digest section header renders the dotted capital:
    `reply.digest.category.other` (`diğer haberler`) must render
    `DİĞER HABERLER`, not the current `DIĞER HABERLER`. Named test in
    `DigestRendererSectionsTest`.
  - >-
    The interpolated category tag stays English-cased in a Turkish
    scope: a section for tag `ai` renders `AI HABERLERİ`, never
    `Aİ HABERLERİ`. Tags are an English controlled vocabulary (D38), so
    Turkish casing must not reach them — this is the trap a naive
    swap of `Locale.ROOT` for the scope locale walks into. Named test in
    `DigestRendererSectionsTest`.
  - >-
    All five uppercase sites in `DigestRenderer` are converted (see
    §Census); the lead header `reply.digest.lead.header` and both
    `*_count` headers behave the same as the two plain headers.
  - >-
    English, Czech, Spanish and Russian digest output is byte-identical
    to before — `DigestRendererTest` and the rest of
    `DigestRendererSectionsTest` pass unmodified.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D43
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-762: Locale-aware uppercase for digest section headers

## Context

`DigestRenderer` renders digest headers by uppercasing the bundle value
in code — the bundle ships lowercase prose and the renderer applies caps,
because plain-text output has no bold and caps are the strongest visual
anchor. Every one of those conversions passes `Locale.ROOT`.

`Locale.ROOT` maps `i → I`. Turkish maps `i → İ` and `ı → I`; the dotted
and dotless letters are different letters, not case variants of one. So
with `tr` enabled (M1-720) a Turkish reader sees `DIĞER HABERLER` where
correct Turkish is `DİĞER HABERLER` — a visible misspelling in the
header of every digest section, on the one surface designed to be the
most prominent text in the message.

This was found while implementing M1-720 and deliberately left out of it:
that ticket's `out_of_scope` forbids touching case conversion, on the
correct reasoning that the dotless-ı hazards it surveyed were all
JVM-default-locale properties a scope-language setting cannot reach.
This site is the exception the survey did not cover, because here the
language is an explicit parameter (`langCode`) rather than an ambient
default — which is also what makes it cheaply fixable.

Turkish is currently the only enabled language whose casing differs from
`Locale.ROOT`; English, Czech, Spanish and Russian all uppercase
identically under both, which is why this shipped unnoticed.

## Census

The class is every case conversion applied to *translated user-facing
prose*. Re-runnable enumeration:

```
grep -rn 'toUpperCase(\|toLowerCase(' --include=*.java infochat-provider/src/main
```

Of the sites it returns, the ones operating on a bundle value are all in
`DigestRenderer`, all `Locale.ROOT`, all in scope:

| Site | Disposition |
|---|---|
| `DigestRenderer.java:304` (`reply.digest.lead.header`) | fix |
| `DigestRenderer.java:669` (`reply.digest.category.other`) | fix |
| `DigestRenderer.java:673` (`reply.digest.category.header` + tag) | fix — tag must stay ROOT-cased |
| `DigestRenderer.java:691` (`reply.digest.category.other_count`) | fix |
| `DigestRenderer.java:696` (`reply.digest.category.header_count` + tag) | fix — tag must stay ROOT-cased |
| `DigestCommandHandler.java:276` | out-of-scope: ASCII literal, JVM-default locale |
| `QuarantineReviewListener.java:158` | out-of-scope: ASCII literal, observability label only |
| `AssetRegistry.java:215` | out-of-scope: `Character.toUpperCase(char)` takes no locale; English asset names |
| every other returned site | out-of-scope: all pass `Locale.ROOT` deliberately on identifiers, enum names, URLs, scheme/host, window suffixes and currency codes — machine tokens that MUST fold locale-independently |

`/summary` renders `reply.digest.category.header` and
`reply.digest.category.other` too, but without uppercasing them
(`grep -rn 'REPLY_DIGEST_CATEGORY_HEADER\|REPLY_DIGEST_CATEGORY_OTHER'
--include=*.java infochat-provider/src/main` returns only `DigestRenderer`
and the `BundleKeys` declarations), so no `/summary` bytes move.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter `out_of_scope`.

## Notes

**The tag is the trap.** Sites `:673` and `:696` interpolate the category
tag into the header *before* uppercasing, so a single
`toUpperCase(scopeLocale)` over the composed string would case the tag
too. Tags are an English controlled vocabulary, so under a Turkish locale
`ai` becomes `Aİ` — trading one wrong header for another. The conversion
has to case the translated prose with the scope locale and the tag
independently, whatever shape that takes (uppercase-then-interpolate, or
uppercase the tag with `Locale.ROOT` separately). Acceptance pins the
observable result rather than the technique.

**Resolving the locale.** `langCode` is already a parameter on both
`sectionHeader` and `sectionCountHeader`, and on the lead-header block,
so no plumbing is needed — `Locale.forLanguageTag(langCode)` at the call
site is sufficient. `LanguageRegistry` is not involved: the script
metadata it now carries (M1-719) answers "what script should the
translator have emitted", not "how does this language case", and the two
must not be conflated.

**Why the existing tests do not catch it.** `DigestRendererTest` and
`DigestRendererSectionsTest` assert on English headers (`SECURITY NEWS`,
`OTHER NEWS`, `TOP STORIES`), and English uppercases identically under
`Locale.ROOT` and any locale — so the whole suite is green today and will
stay green after the fix. The new assertions must therefore be
Turkish-scope ones; an English-scope test cannot distinguish the two
implementations and would be a vacuous pass.

- Adjacent code: `DigestRenderer.sectionHeader` / `sectionCountHeader`
  (the existing shape this should follow)
- Related: M1-720 §Notes (the dotless-ı survey this site sits outside of)
