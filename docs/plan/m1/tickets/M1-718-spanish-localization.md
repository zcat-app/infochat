---
id: M1-718
title: "Spanish (es) localization bundle and enablement"
status: done
created: 2026-07-30
last_updated: 2026-08-04
blocked_by:
  - M1-716
  - M1-746
files_budget: 6
files_scope:
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/LanguageRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/LanguageRegistryTest.java
  - docs/spec/commands.md
out_of_scope:
  - >-
    The `en` and `cs` bundles. Existing key VALUES must not be edited —
    bundle-equality tests pin them, and a copy change is its own ticket.
  - >-
    Any other language. Russian and Turkish have their own tickets and
    their own quality gates.
  - >-
    Command parsing, tag vocabulary, and post bodies. Commands stay
    English-only, tags stay an English controlled vocabulary, and source
    post bodies are never translated (docs/spec/llm.md §Translation
    flow).
  - >-
    The `TranslationPipeline` target-script check. Spanish is
    Latin-script, so that check is not reachable from this ticket and
    ships with the Russian one.
  - >-
    Per-language full-text regconfig. Deferred; see M1-717 out_of_scope.
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
acceptance:
  - >-
    Retrieval for a non-English scope is settled architecturally, not by a
    per-language embedder measurement: the English pivot (D29 amended,
    D58) anchors the corpus in English at ingest (M1-749) and translates
    the query into that anchor (M1-746), so both retrieval arms compare
    English to English and the embedder never sees Spanish. The
    `EMBEDDER-MEASUREMENT-RESULTS.md` §4 verdict this item used to gate on
    belonged to the embedder swap (M1-717, abandoned as superseded); §4
    was never filled and gates nothing.
  - >-
    `es.properties` carries a non-empty value for every key in
    `BundleKeys`, and `BundleLoaderTest`'s bilateral parity check covers
    `es` with no missing or extra keys (D43)
  - >-
    `/lang es` is accepted and the confirmation reply is resolved from the
    `es` bundle; `/lang` unsupported-code errors list `es` among the
    supported codes
  - >-
    `LanguageRegistryTest`'s enabled-set assertions are retargeted to
    `{en, cs, es}` with their SHAPE preserved: exact set equality (not
    `contains`), and `loadedBundleIsNotEnabledUnlessDeclared` still proves
    a loaded-but-undeclared bundle (`th`) is rejected. Its stubbed loaded
    sets must gain `es` or `LanguageRegistry.validate()` fails fast, which
    is the declared-without-bundle guard working as designed
  - >-
    docs/spec/commands.md §Conversation control names the enabled set
    including `es`
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
  - round: 1
    date: 2026-08-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 987
      removed: 22
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-08-04
  verdict: WARN
  warnings:
    - >-
      Self-check found acceptance item 1's SKELETON gate
      (`EMBEDDER-MEASUREMENT-RESULTS.md` §4 = `enable`) unsatisfiable and
      superseded: §4 was never filled, and it gated the abandoned embedder
      swap M1-717 rather than the adopted English pivot (D29 amended,
      D58). Raised as a blocking question; user chose to repair the gate
      across M1-718/719/720. Repaired on `main` @ 898d261d before this
      start. Lint PASS (0 blockers, 0 warnings) before and after.
  blockers: []
---

## Context

Spanish output localization: a 421-key `es.properties`, the
`BundleLoader.LOADED_LANGUAGES` entry, and the `LanguageRegistry` enable
flag from M1-716.

**The retrieval blocker this ticket was filed behind is gone.** As authored
(2026-07-30) it waited on M1-717: on `nomic-embed-text` a Spanish query
ranked the correct English document first in all 7 test topics but scored
only 0.41–0.50, under `SemanticSearchTool`'s 0.60 admit line, so the
semantic arm returned **zero rows** and only the lexical arm fired.
Shipping the bundle then would have delivered fluent Spanish replies over a
chat surface that finds nothing. The English pivot adopted 2026-08-02 (D29
amended, D58) removes that failure by construction rather than by a better
embedder: M1-749 translates a non-English post to English at ingest and
embeds the English field, M1-746 translates the query into the same anchor,
so a Spanish user's search never presents non-English text to the embedder.
M1-717 is abandoned as superseded and its §4 verdict table was never
filled — there is no measurement to consult.

Spanish stays the easiest of the three candidate bundles on the
presentation side: Latin script, so no bidi or ZWNJ concerns and no
`TranslationPipeline` target-script obligation.

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
`BundleLoader.get(String, String)`. A new bundle must NOT add it — copying
the `en` key list wholesale would defeat the fallback test.

## Acceptance

See frontmatter. There is no measurement verdict to confirm at `start` —
see §Context for why the §4 gate no longer applies.

## Out-of-scope

See frontmatter `out_of_scope`.

## Notes

Adding a bundle key requires a twin in every loaded bundle or
`BundleLoaderTest` fails (D43 bilateral keyset) — with `es` loaded that
becomes a three-way obligation for any future key. Note this in the review
so later tickets budget for it.
