---
id: M1-716
title: "Decouple language enablement from bundle presence"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/LanguageRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/LangCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/LanguageRegistryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandHandlerTest.java
  - docs/spec/llm.md
  - docs/spec/commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Adding any new language bundle. Spanish, Russian and Turkish each
    have their own ticket and each is gated on a measured quality bar.
    This ticket ships the gate and changes NO language's availability:
    the enabled set after this diff is exactly `{en, cs}`, the same set
    users can select today.
  - >-
    `BundleLoader.LOADED_LANGUAGES` membership. Which bundles are
    *loaded* is unchanged; only the claim that loading implies
    availability is corrected. A diff that adds or removes an entry from
    that list has left scope.
  - >-
    `LlmRouter.Entry#supportedLanguages()` and
    `infochat.llm.<provider>.languages`. That is a DIFFERENT
    same-named method describing which languages an LLM provider can
    generate, unrelated to bundle availability. See §Census — conflating
    the two is the specific error this ticket must not make.
  - >-
    The `/lang` permission gate (`LangCommandHandler.handle` group-admin
    and bot-admin checks, lines 113-137). This ticket changes only which
    codes are accepted, not who may set them.
  - >-
    `TranslationPipeline` and its failure conditions, including the
    unimplemented "zero target-script characters" check. That check is
    only reachable once a non-Latin language is enabled and ships with
    the Russian ticket.
  - >-
    The embedder, the five retrieval thresholds, and the
    `'english'`-pinned `search_tsv` / `plainto_tsquery` regconfig.
    Retrieval quality is orthogonal to output localization.
  - >-
    Making the enabled set runtime-configurable via a config property.
    That would be a feature flag, which the engineering rules forbid;
    see §Notes.
acceptance:
  - LanguageRegistryTest.enabledSetIsExactlyEnAndCs passes
  - >-
    LanguageRegistryTest.loadedBundleIsNotEnabledUnlessDeclared passes,
    pinning that a bundle present on the classpath and listed in
    BundleLoader.LOADED_LANGUAGES is still rejected by the registry
    unless it is also declared enabled
  - LangCommandHandlerTest.rejectsLoadedButNotEnabledLanguageCode passes
  - >-
    LangCommandHandlerTest existing `/lang cs` and `/lang en` success
    scenarios pass unchanged, and the unsupported-code error still
    interpolates a sorted comma-joined list — now of enabled codes
  - >-
    `LangCommandHandler` reads the enabled set from `LanguageRegistry`,
    and `bundleLoader.supportedLanguages()` has no remaining production
    call site outside BundleLoader itself and the bundle-parity tests
  - >-
    docs/spec/llm.md §Translation flow states that a loaded bundle does
    not by itself make a language selectable, replacing the current
    "adding a third language is a bundle drop-in" sentence
  - >-
    docs/spec/commands.md §Conversation control states that the codes
    `/lang` accepts come from an explicit enabled set
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/LanguageRegistryTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
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

Today a language becomes user-selectable **by existing**. `BundleLoader`
loads every code in `LOADED_LANGUAGES` (`BundleLoader.java:57`), exposes
them as `supportedLanguages()` (`:135`), and `LangCommandHandler:140`
validates `/lang <code>` against exactly that set. So dropping in a
`th.properties` and adding one list entry makes Thai selectable the moment
it compiles — with no statement anywhere about whether Thai *works*.

That coupling is load-bearing in the wrong direction. Measured on the
current embedder, a scope language whose retrieval is broken fails
silently: a Russian query against the English corpus ranks 0/7 correct,
and a Spanish one ranks 7/7 but lands every correct match below the 0.60
admit threshold, so the semantic arm returns zero rows rather than wrong
rows. A language can therefore look shipped — bundle present, `/lang`
accepts it, replies arrive translated — while chat retrieval returns
nothing. Nothing in the codebase can currently express "the bundle exists
and is testable, but this language is not offered."

This ticket introduces that distinction and nothing else. After the diff
the enabled set is exactly `{en, cs}` — no user-visible change — and
subsequent language tickets flip one registry entry each, gated on their
own measured evidence.

The spec currently asserts the coupling in two places, so this is a
code-plus-spec change rather than code alone:

- `docs/spec/llm.md` §Translation flow: "v1 ships **`en` and `cs` (Czech)
  bundles**; adding a third language is a bundle drop-in."
- `docs/spec/commands.md` §Conversation control: "`/lang <code>` — sets
  per-scope output language. v1 ships English and Czech."

## Census

Every consumer of the provider-side `BundleLoader.supportedLanguages()`,
from `grep -rn "supportedLanguages()" --include=*.java`:

| site | kind | disposition |
|---|---|---|
| `LangCommandHandler.java:140` | production gate | **repoint** at `LanguageRegistry` |
| `LangCommandHandler.java:47` | javadoc naming it the supported set | correct |
| `BundleLoader.java:112` | javadoc calling the handler pre-check "authoritative" | correct |
| `BundleLoader.java:128-135` | javadoc calling it the "source of truth for LangCommandHandler's unsupported-code rejection" | correct |
| `BundleKeys.java:1345` | javadoc deriving the error's code list from it | correct |
| `BundleLoaderTest.java:31,84,86,131,133` | bundle **parity** assertions | unchanged — parity is over loaded bundles, which is still the right set |

**Distinct same-named method, explicitly not in scope:**
`LlmRouter.Entry#supportedLanguages()`
(`LlmRouter.java:204,298`, `LlmRouterEntryTest.java:25,33`) describes which
languages an *LLM provider* declares it can generate, fed by
`infochat.llm.<provider>.languages`. It has no relationship to bundle
availability. The two must not be unified.

## Acceptance

See frontmatter `acceptance`.

## Out-of-scope

See frontmatter `out_of_scope`.

## Notes

**Why a code constant, not config.** The enabled set ships as a code
constant in `LanguageRegistry`, mirroring `BundleLoader.LOADED_LANGUAGES`.
A config property would be a feature flag, which the engineering rules
forbid outright, and it would let an operator enable a language whose
quality was never measured — the exact failure this ticket exists to
prevent. Enabling a language stays a reviewed one-line code change with a
ticket behind it.

**Why the registry, not just a second list in `BundleLoader`.** The gate
needs to carry more than a code per language — the follow-on Russian
ticket needs the expected Unicode script for the `TranslationPipeline`
target-script check. A record per language gives that a home without
reopening `BundleLoader`, whose job stays "load and resolve bundle keys."

**No new bundle key.** The unsupported-code reply reuses
`ERROR_LANG_UNSUPPORTED_CODE` with the same `MessageFormat` argument
shape, only sourced from the enabled set. This avoids the D43 bilateral
`en`/`cs` keyset requirement that a new key would trigger.

**Deferred, not forgotten.** Two observations found while scoping this,
neither ticket-worthy on its own: `QuarantineReviewListener.java:158`
lowercases `"PENDING"` without a locale, which would render `"pendıng"`
under a Turkish JVM default locale (observability label only, and no
locale is configured anywhere); and `DigestCommandHandler.java:202` uses
locale-less `toLowerCase()` on a sub-verb, which is harmless because
neither `"on"` nor `"off"` contains a case-sensitive `i`. Fold either fix
into the next ticket that legitimately touches those files.

**Decision record.** The "availability is declared, not inferred"
principle may warrant its own D-number once the target language set is
settled. No D-number is claimed here to avoid the double-claim hazard;
this ticket carries D43 only, whose bundle-not-translator rule it leaves
intact.
