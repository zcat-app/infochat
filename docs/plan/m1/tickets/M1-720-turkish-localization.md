---
id: M1-720
title: "Turkish (tr) localization bundle and enablement"
status: done
created: 2026-07-30
last_updated: 2026-08-04
blocked_by:
  - M1-716
  - M1-746
files_budget: 7
files_scope:
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/LanguageRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/LanguageRegistryTest.java
  - docs/spec/commands.md
  - docs/spec/llm.md
out_of_scope:
  - >-
    The `en`, `cs`, `es` and `ru` bundles. Existing key VALUES must not be
    edited — bundle-equality tests pin them.
  - >-
    The `TranslationPipeline` target-script check itself, which shipped
    with the Russian ticket (M1-719). Turkish is Latin-script and
    condition (d) short-circuits for Latin targets — byte identity
    (condition b) is its Latin form — so no pipeline edit is needed.
    Declaring `UnicodeScript.LATIN` for `tr` in `LanguageRegistry` is
    NOT such an edit: after M1-719 widened `EnabledLanguage` to carry a
    script, every enabled language must supply one or the registry does
    not compile.
  - >-
    Changing any `toLowerCase()` / `toUpperCase()` call site. The
    dotless-ı hazard is a JVM-default-locale property, not a scope-language
    one, and no locale is configured anywhere in the deployment — see
    §Notes. A diff that touches case conversion has left scope.
  - >-
    Per-language full-text regconfig. Deferred; see M1-717 out_of_scope.
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
acceptance:
  - >-
    Retrieval for a non-English scope is settled architecturally by the
    English pivot (D29 amended, D58) — M1-749 anchors the corpus in
    English at ingest, M1-746 translates the query into that anchor — so
    no embedder measurement gates this ticket. The
    `EMBEDDER-MEASUREMENT-RESULTS.md` §4 verdict this item used to gate on
    belonged to the embedder swap (M1-717, abandoned as superseded); §4
    was never filled and gates nothing.
  - >-
    `tr.properties` carries a non-empty value for every key in
    `BundleKeys`, and `BundleLoaderTest`'s bilateral parity check covers
    `tr` (D43)
  - >-
    `/lang tr` is accepted and the confirmation reply resolves from the
    `tr` bundle
  - >-
    `LanguageRegistryTest`'s enabled-set assertions are retargeted to
    include `tr` with their SHAPE preserved: exact set equality (not
    `contains`), and `loadedBundleIsNotEnabledUnlessDeclared` still proves
    a loaded-but-undeclared bundle (`th`) is rejected. Its stubbed loaded
    sets must gain `tr` or `LanguageRegistry.validate()` fails fast, which
    is the declared-without-bundle guard working as designed
  - >-
    docs/spec/commands.md §Conversation control names the enabled set
    including `tr`
  - >-
    Every spec sentence that ENUMERATES the shipped bundle set names
    `tr`: `docs/spec/llm.md` §Translation flow ("v1 ships `en`, `cs`,
    `es` and `ru` bundles") and its §Design-tier list ("the
    `en`/`cs`/`es`/`ru` commitment is spec"), plus
    `docs/spec/commands.md` §Discovery /help ("in every shipped language
    bundle (`en`, `cs`, `es` and `ru` in v1)"). Both files are cited in
    `spec_refs`, so leaving either stale would make the diff contradict
    its own cited spec — the same obligation M1-719 carried. Excluded as
    pre-existing staleness that `es` and `ru` already left behind, none
    of it in `files_scope` and none of it created by this diff:
    `commands.md` §Discovery's "`en` and `cs`" bundle-completeness
    sentence, `verification.md`'s "(`en`, `cs` in v1)", and
    `decisions.md` D43's "v1 ships `en` and `cs` (Czech) bundles". All
    three belong to one follow-up. `commands.md`'s
    `SourceLanguageRegistry` `{en, cs}` is NOT in this class — that is
    the declared SOURCE-language set (D29), not the output bundle set.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/LanguageRegistryTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D43
reviews:
  - round: 2
    date: 2026-08-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 956
      removed: 34
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-08-04
  verdict: WARN
  warnings:
    - >-
      lint: PASS (0 blockers, 0 warnings).
    - >-
      self-check, census truth: the §Census inventory grep now returns 430
      `BundleKeys` constants and 440 `en.properties` keys, not the "421 as
      of 2026-07-30" the body recorded. Corrected inline; the class
      definition is unchanged and `BundleLoaderTest`'s bilateral parity
      remains the gate, so this is a count refresh, not a scope change.
    - >-
      self-check, ticket-vs-code truth: adding `tr` to
      `LanguageRegistry.ENABLED_LANGUAGES` breaks two assertions in
      `LanguageRegistryTest`, a path this ticket never declared. Raised as
      a `budget-breach` escalation at start and resolved by refine before
      any code was written.
  blockers: []
---

## Context

Turkish output localization: a 439-key `tr.properties`, the
loader entry, and the `LanguageRegistry` enable flag.

Turkish measured worst of the three candidates on `nomic-embed-text`: 4/7
top-1 within a Turkish-only pool with a mean margin of +0.023 and an
unplaceable threshold (worst-correct 0.583 < worst-false 0.716); 15 of 21
unrelated Turkish pairs score above the 0.60 admit line, so unrelated
documents look related; against the English corpus it is 3/7 with 0/7 above
the admit line. The cause is agglutination — heavy suffixation fragments
under an English-trained tokenizer. The English pivot (D29 amended, D58)
makes those numbers inapplicable rather than better: M1-749 embeds the
English anchor and M1-746 translates the query into it, so no Turkish text
reaches the embedder. M1-717 is abandoned as superseded and its §4 verdict
table was never filled, so there is no rejection verdict that could abandon
this ticket.

## Census

The class is every key `BundleKeys` declares. Re-runnable inventory:

```
grep -cE '^\s+public static final String [A-Z_]+ =' \
  infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
# 430 as of 2026-08-04 (421 when the ticket was authored 2026-07-30)

grep -oE '^[A-Za-z0-9_.-]+=' infochat-provider/src/main/resources/bundles/en.properties | sort
# 440 keys as of 2026-08-04 — more than BundleKeys declares, because a
# bundle key need not be a constant (see BundleLoaderTest's en-keyset
# parity test). en's own keyset, not BundleKeys, is the authoring target.
```

The authoritative check is not the grep but `BundleLoaderTest`'s reflective
bilateral-parity assertion, which fails the build on any key present in one
loaded bundle and absent from another. The grep is the pre-flight inventory;
the test is the gate.

**Known deliberate asymmetry:** `test.fallback.probe` is present in
`en.properties` only, exercising the missing-key→`en` fallback in
`BundleLoader.get(String, String)`. A new bundle must NOT add it.

## Acceptance

See frontmatter. There is no measurement verdict to confirm at `start` —
see §Context for why the §4 gate no longer applies.

## Out-of-scope

See frontmatter `out_of_scope`.

## Round 1 rework

Reviewer verdict REWORK on `SPEC-CONFORMANCE-CHECK` — every other check
PASSed. The diff makes its own cited spec section factually false: a
five-bundle build under a four-bundle spec sentence.

1. `docs/spec/llm.md` §Translation flow, the D43 shipped-bundle sentence
   ("v1 ships **`en`, `cs` (Czech), `es` (Spanish) and `ru` (Russian)
   bundles**"), and the matching design-tier enumeration ("the
   `en`/`cs`/`es`/`ru` commitment is spec"). Required a `files_scope`
   widening first — done in this refine, mirroring M1-719, which carried
   `docs/spec/llm.md` in scope for exactly this reason.
2. `docs/spec/commands.md` §Discovery /help, the bundle-completeness
   sentence ("in every shipped language bundle (`en`, `cs`, `es` and
   `ru` in v1 per `llm.md` §Translation flow)"). Already in
   `files_scope`; no widening needed.

Explicitly NOT touched: the `commands.md` §Discovery sentence reading
"the bundle-completeness CI covers each key in `en` and `cs`",
`docs/spec/verification.md`'s "(`en`, `cs` in v1)", and — found by
re-running the enumeration sweep rather than by the reviewer, which
named only the first two — `docs/spec/decisions.md` D43's "v1 ships
**`en` and `cs` (Czech)** bundles". All three were stale before this
diff (`es` and `ru` shipped without updating them), none is in
`files_scope`, so fixing them here would be scope drift on a defect this
ticket did not create. One follow-up owed for all three.

The sweep that establishes the set is re-runnable:

```
grep -rn '`en`, `cs`\|`en`/`cs`\|`en` and `cs`' docs/spec/
```

`commands.md`'s `SourceLanguageRegistry` `{en, cs}` also matches it but
is NOT in the class: that is the declared SOURCE-language set (D29) —
which languages a feed's posts may be declared in — not the output
bundle set, and it is correctly unchanged by a ticket that adds an
output bundle.

## Notes

**The dotless-ı question, resolved as a non-issue.** Turkish locale maps
`I→ı` and `i→İ`, which breaks locale-less case conversion. Two such sites
exist: `DigestCommandHandler.java:276` lowercases a sub-verb, but neither
`"on"` nor `"off"` contains a case-sensitive `i`, so the result cannot
change; and `QuarantineReviewListener.java:158` lowercases `"PENDING"`,
which would render `"pendıng"` in an observability label only. Both depend
on the JVM *default* locale, which a scope-language setting does not
affect, and no `user.language` / `LANG` is configured in compose, the
Dockerfiles, or any properties file. Enabling Turkish as a scope language
therefore introduces no case-folding risk, and this ticket must not touch
those call sites.
