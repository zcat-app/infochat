---
id: M1-720
title: "Turkish (tr) localization bundle and enablement"
status: pending
created: 2026-07-30
last_updated: 2026-08-04
blocked_by:
  - M1-716
  - M1-746
files_budget: 6
files_scope:
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/LanguageRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  - docs/spec/commands.md
out_of_scope:
  - >-
    The `en`, `cs`, `es` and `ru` bundles. Existing key VALUES must not be
    edited — bundle-equality tests pin them.
  - >-
    The `TranslationPipeline` target-script check. Turkish is
    Latin-script, so it adds no new script obligation; the check ships
    with the Russian ticket.
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
    docs/spec/commands.md §Conversation control names the enabled set
    including `tr`
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

Turkish output localization: a 421-key `tr.properties`, the
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
# 421 as of 2026-07-30

grep -oE '^[A-Za-z0-9_.-]+=' infochat-provider/src/main/resources/bundles/en.properties | sort
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

## Notes

**The dotless-ı question, resolved as a non-issue.** Turkish locale maps
`I→ı` and `i→İ`, which breaks locale-less case conversion. Two such sites
exist: `DigestCommandHandler.java:202` lowercases a sub-verb, but neither
`"on"` nor `"off"` contains a case-sensitive `i`, so the result cannot
change; and `QuarantineReviewListener.java:158` lowercases `"PENDING"`,
which would render `"pendıng"` in an observability label only. Both depend
on the JVM *default* locale, which a scope-language setting does not
affect, and no `user.language` / `LANG` is configured in compose, the
Dockerfiles, or any properties file. Enabling Turkish as a scope language
therefore introduces no case-folding risk, and this ticket must not touch
those call sites.
