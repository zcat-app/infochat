---
id: M1-762
title: "Localization cleanup after Turkish enablement"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/spec/verification.md
  - docs/spec/decisions.md
out_of_scope:
  - >-
    The `cs`, `es`, `ru` and `tr` bundle files. Item 1 edits exactly ONE
    `en` value (`reply.digest.mode_already`); the guard in item 2 is a
    test and must not rewrite any bundle to make itself pass. The four
    non-en bundles already satisfy the guard — verified, see §Census.
  - >-
    Pre-uppercasing the `reply.digest.category.*` values in the bundles
    as a shortcut for item 3. `sectionHeader`'s two keys are shared with
    `/summary`, which renders them WITHOUT uppercasing, so baking caps
    into the value would corrupt the `/summary` surface. The case
    conversion must stay in the renderer.
  - >-
    The two remaining locale-less `toLowerCase()` sites,
    `DigestCommandHandler.java:276` and
    `QuarantineReviewListener.java:158`. Both fold ASCII-only literals
    (`on`/`off`, `PENDING`) and depend on the JVM DEFAULT locale, which
    no scope-language setting reaches — a different concern from item
    3's scope-language conversion. See M1-720 §Notes.
  - >-
    Enabling, disabling or retranslating any language, and any change to
    `BundleLoader.LOADED_LANGUAGES` or
    `LanguageRegistry.ENABLED_LANGUAGES`. The bundle set is settled by
    M1-716/718/719/720.
  - >-
    Widening the item-2 guard beyond MessageFormat quoting — e.g. into
    placeholder-arity or key-parity checks. Those invariants already
    have tests in the same class.
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
acceptance:
  - >-
    LIVE BUG. `en.properties` `reply.digest.mode_already` renders its
    placeholder instead of the mode: the value carries an undoubled `'`
    in `group's`, and `DigestCommandHandler.java:146` passes it to
    `MessageFormat.format`, which treats the apostrophe as a quote —
    output is `This groups digest mode is already {0}.` Double it to
    `group''s` so a second `/digest brief` renders the mode literal.
  - >-
    `DigestCommandHandlerTest`'s already-in-mode assertion is rewritten
    so it can FAIL on the above: today it builds its expected value with
    the same `MessageFormat.format(bundleLoader.get(...))` call as the
    handler, so it compares the broken output against itself and passes
    vacuously. The replacement must assert the reply CONTAINS the mode
    literal and does NOT contain `{0}`.
  - >-
    A `BundleLoaderTest` guard fails the build when any value in any
    loaded bundle contains a `{n}` placeholder AND an undoubled `'`.
    Scoped to placeholder-bearing values because only those are
    MessageFormat patterns — `en` legitimately carries 46 apostrophes in
    non-pattern values, so a blanket no-apostrophe rule would be wrong.
    Red before item 1 is fixed, green after.
  - >-
    All five `DigestRenderer` uppercase sites case the translated header
    with the SCOPE language rather than `Locale.ROOT`, so a Turkish
    section header renders `DİĞER HABERLER`, not the current
    `DIĞER HABERLER`. Named test in `DigestRendererSectionsTest`.
  - >-
    The interpolated category tag stays English-cased in a Turkish
    scope: a section for tag `ai` renders `AI HABERLERİ`, never
    `Aİ HABERLERİ`. Tags are an English controlled vocabulary (D38), so
    Turkish casing must not reach them — the trap a blanket swap of
    `Locale.ROOT` for the scope locale walks into. Named test in
    `DigestRendererSectionsTest`.
  - >-
    English, Czech, Spanish and Russian digest output is byte-identical
    to before — `DigestRendererTest` and the rest of
    `DigestRendererSectionsTest` pass unmodified.
  - >-
    The three spec sentences that still enumerate a two-bundle v1 name
    all five: `commands.md` §Discovery ("the bundle-completeness CI
    covers each key in `en` and `cs`"), `verification.md`
    §Localization bundle completeness ("any shipped language bundle
    (`en`, `cs` in v1)"), and `decisions.md` D43 ("v1 ships **`en` and
    `cs` (Czech)** bundles"). All three were left stale by M1-718 and
    M1-719; none is a decision change, only a factual sync.
  - mvn verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/DigestCommandHandlerTest.java
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

# M1-762: Localization cleanup after Turkish enablement

## Context

Four loose ends the five-bundle localization work (M1-716, M1-718,
M1-719, M1-720) left behind, collected into one cleanup ticket. They are
independent of each other; the only reason they share a ticket is that
they share an origin and all touch the localization surface.

The first is a live defect and should be treated as the ticket's
priority — the rest is hygiene and guard work. If this ticket needs to be
split later, split item 1 out, not the others.

## Census

Two classes, both enumerated mechanically.

**Class A — bundle values that are MessageFormat patterns with an
undoubled apostrophe.** A value reaching `MessageFormat.format` treats
`'` as the quoting character: a single apostrophe swallows the rest of
the pattern including its placeholders. Only values carrying a `{n}` are
patterns, so the enumeration is scoped to those:

```
python3 - <<'PY'
import re
from pathlib import Path
B='infochat-provider/src/main/resources/bundles/'
for lang in ['en','cs','es','ru','tr']:
    for line in Path(B+lang+'.properties').read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'): continue
        m=re.match(r'^([A-Za-z0-9_.\-]+)=(.*)$',line)
        if not m: continue
        k,v=m.group(1),m.group(2)
        if re.search(r'\{\d',v) and "'" in v.replace("''",""):
            print(lang,k,'=',v)
PY
```

| Site | Disposition |
|---|---|
| `en.properties` `reply.digest.mode_already` | fix (item 1) |
| `cs` / `es` / `ru` / `tr` — zero hits | no action; they already satisfy the guard |

Run as of 2026-08-04 the enumeration returns exactly one row. `en`
carries 46 further apostrophes in NON-placeholder values, which are
correct and must not be touched — that is why the guard is scoped to
placeholder-bearing values rather than being a blanket ban.

**Class B — case conversions applied to translated user-facing prose.**

```
grep -rn 'toUpperCase(\|toLowerCase(' --include=*.java infochat-provider/src/main
```

| Site | Disposition |
|---|---|
| `DigestRenderer.java:304` (`reply.digest.lead.header`) | fix (item 3) |
| `DigestRenderer.java:669` (`reply.digest.category.other`) | fix (item 3) |
| `DigestRenderer.java:673` (`reply.digest.category.header` + tag) | fix — tag must stay ROOT-cased |
| `DigestRenderer.java:691` (`reply.digest.category.other_count`) | fix (item 3) |
| `DigestRenderer.java:696` (`reply.digest.category.header_count` + tag) | fix — tag must stay ROOT-cased |
| `DigestCommandHandler.java:276` | out-of-scope: ASCII literal, JVM-default locale |
| `QuarantineReviewListener.java:158` | out-of-scope: ASCII literal, observability label only |
| `AssetRegistry.java:215` | out-of-scope: `Character.toUpperCase(char)` takes no locale; English asset names |
| every other returned site | out-of-scope: all pass `Locale.ROOT` deliberately on identifiers, enum names, URLs, scheme/host, window suffixes and currency codes — machine tokens that MUST fold locale-independently |

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter `out_of_scope`.

## Notes

**Item 1 is reproducible without the app.** The handler call is
`DigestCommandHandler.java:146-148`:
`MessageFormat.format(bundleLoader.get(REPLY_DIGEST_MODE_ALREADY, lang), desiredMode)`.
Feeding the en value straight to `MessageFormat` prints
`This groups digest mode is already {0}.` — apostrophe eaten, placeholder
emitted literally. The same call with the tr value prints correctly, so
this is an en-only defect that the other four bundles happen to dodge.

**Why the existing test cannot catch item 1.**
`DigestCommandHandlerTest.java:261` computes its expected string with the
same `MessageFormat.format(bundleLoader.get(...), "normal")` expression
the handler uses, so both sides are broken identically and the assertion
is self-consistent. This is the shape to avoid when rewriting it: assert
against a literal expectation, not against a re-derivation of the code
under test.

**Item 3, the tag is the trap.** Sites `:673` and `:696` interpolate the
category tag into the header BEFORE uppercasing, so a single
`toUpperCase(scopeLocale)` over the composed string would case the tag
too. Tags are an English controlled vocabulary, so under a Turkish locale
`ai` becomes `Aİ` — trading one wrong header for another. The conversion
has to case the translated prose with the scope locale and the tag
independently, whatever shape that takes. Acceptance pins the observable
result rather than the technique.

**Item 3, resolving the locale.** `langCode` is already a parameter on
`sectionHeader`, `sectionCountHeader` and the lead-header block, so no
plumbing is needed — `Locale.forLanguageTag(langCode)` at the call site
suffices. `LanguageRegistry` is NOT involved: the script metadata it
carries (M1-719) answers "what script should the translator have
emitted", not "how does this language case", and conflating the two would
be wrong.

**Item 3, why the suite is green today.** `DigestRendererTest` and
`DigestRendererSectionsTest` assert on English headers (`SECURITY NEWS`,
`OTHER NEWS`, `TOP STORIES`), and English uppercases identically under
`Locale.ROOT` and any locale. The new assertions must therefore be
Turkish-scope; an English-scope test cannot distinguish the two
implementations and would be a vacuous pass — the same failure mode as
item 2's existing test.

**Item 4 is a factual sync, not a decision change.** D43's substance (two
paths: deterministic strings from the bundle, LLM prose through the
translator) is untouched; only its parenthetical count of shipped bundles
is stale. Do not treat it as a spec amendment.

- Adjacent code: `DigestRenderer.sectionHeader` / `sectionCountHeader`
- Related: M1-720 §Round 1 rework, which records these three spec sites
  as deliberately deferred here rather than fixed inline
