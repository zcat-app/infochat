---
id: M1-718
title: "Spanish (es) localization bundle and enablement"
status: pending
created: 2026-07-30
last_updated: 2026-08-02
blocked_by:
  - M1-716
  - M1-746
files_budget: 6
files_scope:
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/LanguageRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
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
    SKELETON — gated on `EMBEDDER-MEASUREMENT-RESULTS.md` §4 recording
    `es` as `enable`. If the measurement rejects Spanish, this ticket is
    abandoned rather than shipped.
  - >-
    `es.properties` carries a non-empty value for every key in
    `BundleKeys`, and `BundleLoaderTest`'s bilateral parity check covers
    `es` with no missing or extra keys (D43)
  - >-
    `/lang es` is accepted and the confirmation reply is resolved from the
    `es` bundle; `/lang` unsupported-code errors list `es` among the
    supported codes
  - >-
    docs/spec/commands.md §Conversation control names the enabled set
    including `es`
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
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

**SKELETON.** Spanish output localization: a 421-key `es.properties`, the
`BundleLoader.LOADED_LANGUAGES` entry, and the `LanguageRegistry` enable
flag from M1-716.

Blocked on M1-717 for a measured reason, not a precautionary one. On the
current embedder a Spanish query ranks the correct English document first
in all 7 test topics — but every correct match scores 0.41–0.50, and
`SemanticSearchTool` admits only similarity > 0.60. So the semantic arm
returns **zero rows** for a Spanish user; only the lexical arm fires, on
shared tokens like proper nouns. Shipping the bundle alone would deliver
fluent Spanish replies over a chat surface that finds nothing — the
"looks shipped, isn't" failure M1-716's gate exists to prevent.

Spanish is the strongest of the three candidate languages: Latin script (no
bidi or ZWNJ concerns), a Postgres `spanish` snowball config already
installed for the deferred lexical work, and the highest same-language
ranking quality measured.

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

See frontmatter. Confirm the `es` verdict in results §4 at `start`.

## Out-of-scope

See frontmatter `out_of_scope`.

## Notes

Adding a bundle key requires a twin in every loaded bundle or
`BundleLoaderTest` fails (D43 bilateral keyset) — with `es` loaded that
becomes a three-way obligation for any future key. Note this in the review
so later tickets budget for it.
