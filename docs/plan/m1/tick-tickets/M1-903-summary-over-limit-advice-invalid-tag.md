---
id: M1-903
title: "/summary over-limit advice names a non-vocabulary tag"
status: pending
created: 2026-08-22
last_updated: 2026-08-22
flow: tick
reproduction: >-
  BundleLoaderTest.overLimitAdviceExampleTagIsInTheTagSeedVocabulary
  (to-be-written) — extracts the `/summary <tag> -w` example from each
  shipped bundle's reply.summary.window_too_large_notice and asserts the tag
  is in the V84 seed vocabulary parsed from the classpath; RED today because
  all five bundles name `technology`, which the seed does not contain.
  Live corroboration (A5 advice leg, Vulkan, 2026-08-22): the over-limit
  notice advised `/summary technology -w 2h`; executing it verbatim returned
  `Unknown tag 'technology'. Did you mean one of: tech, tennis, tv, africa,
  ai?` (`.scratch/v2-fix-a5-r2-summary-20260822.md`).
analysis_ref: docs/plan/m1/tick-analysis/v2-acceptance-blockers.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any change to the advice SENTENCE shape, the interpolation arity ({0}
    post count, {1} limit), or the `/get-tags` pointer — the owner-decided
    fix is the example tag token only.
  - >-
    Making the advised command guaranteed to RETURN posts. On the campaign
    scope even `/summary tech -w 2h` hit the empty-window refusal and
    `-w 7d` stayed over-limit; the acceptance criterion is vocabulary-valid
    advice (plan criterion (b)), not a converging result set. Widening the
    advice grammar or the summarizer limit is M1-781 territory, explicitly
    abandoned.
  - >-
    Any code change (SummaryCommandHandler, EligiblePostQuery, the tag
    seed, the tag parser). Validation and expansion already handle a top
    node correctly.
  - >-
    The help.cmd.summary.examples values — they name `/summary ai -w 30d`,
    and `ai` IS a V84 seeded leaf (Census below): valid as-is, no defect.
  - >-
    The A5 live re-leg. Rerun ownership is deferred (campaign cross-cutting
    note); this ticket closes the defect the re-leg will re-verify.
acceptance:
  - "REPRODUCTION closed: BundleLoaderTest.overLimitAdviceExampleTagIsInTheTagSeedVocabulary (test_plan.adds) passes — for EACH shipped bundle (en/cs/es/ru/tr) it extracts the example tag from reply.summary.window_too_large_notice's `/summary <tag> -w` advice and asserts membership in the V84 seed tag names, parsed at test tier from `db/migration/V84__tag_tree_seed_and_migration.sql` on the classpath (infochat-core main resources ride the provider test classpath). The test ALSO asserts it extracted exactly five example tags, so an extraction-regex rot cannot vacuously pass (§8 non-vacuity; the pre-fix tree is the failing mutation — `technology` is absent from the seed)."
  - "All five bundles' reply.summary.window_too_large_notice values name `tech` (a V84 top node, V84__tag_tree_seed_and_migration.sql:50) in place of `technology`, translated naturally per bundle; everything else in each value is byte-preserved ({0}, {1}, the /get-tags pointer) — docs/spec/commands.md §Content's over-cap reply must give actionable advice. Verification: `grep -c 'reply.summary.window_too_large_notice' infochat-provider/src/main/resources/bundles/*.properties` still shows exactly 1 per bundle; `grep 'technology' infochat-provider/src/main/resources/bundles/` returns no hit on this key's values; the values still contain `{0}`, `{1}`, and `/get-tags`."
  - "FAILURE-MODE / drift guard: the same test fails the build when a FUTURE bundle's example tag drifts out of the seed — it reads the seed of record rather than a hardcoded list (the LlmOutputSanitizerTest.matchSetEqualsSpecClosedList precedent: read the source of truth at test tier, never a copy). A rename or removal of `tech` in the seed, or a bundle edit naming a non-seed tag, fails the build."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java — overLimitAdviceExampleTagIsInTheTagSeedVocabulary (reproduction closure + drift guard, one test serving both)
  preserves:
    - The bundle keyset-parity guards (everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle, everyShippedBundleHasExactlyEnKeysetMinusTheEnOnlyProbe) and the apostrophe guard — the edited values keep their placeholders and any doubled apostrophes.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
decision_refs:
  - D43
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-903: /summary over-limit advice names a non-vocabulary tag

## Context

The v2.0.0 fix-verification campaign (Vulkan rerun, 2026-08-22) FAILED the
A5 advice leg: on an over-limit window the bot's
`reply.summary.window_too_large_notice` advises "try a tag, e.g. `/summary
technology -w 2h`", and following the advice VERBATIM returns the
"Unknown tag 'technology'" refusal — `technology` is not in the V84
controlled vocabulary. The string is hardcoded copy identical across all
five bundles, so the failure is bundle-wide by construction. Owner
disposition (DECIDED 2026-08-22): replace `technology` with `tech` in all
five bundles; nothing else in the values changes. Analysis:
`docs/plan/m1/tick-analysis/v2-acceptance-blockers.md`. This ticket blocks
the A5 advice re-leg.

## Root cause

The V84 tag-tree cutover never swept user-facing strings naming example
tags. M1-781 prescribed and M1-780 landed the "e.g. `/summary technology -w
2h`" wording pre-tag-tree; the V84 seed
(`infochat-core/src/main/resources/db/migration/V84__tag_tree_seed_and_migration.sql:50,98-104`)
names the top node `tech` (leaves `ai`, `software-development`,
`cybersecurity`, `robotics`, `hardware`, `internet`, `other-tech`) and never
`technology` (grep-verified). The advice target is validated by
`SummaryCommandHandler.java:254-263` against
`EligiblePostQuery.readVocabulary()` (`SELECT name FROM tag` — ALL nodes,
EligiblePostQuery.java:532-545) and expanded via
`tagTreeExpansion.expandNames` (EligiblePostQuery.java:352-355), so the TOP
node `tech` passes validation AND matches every tech-leaf post. Verified
occurrences, one per bundle: en.properties:203, cs.properties:201,
es.properties:222, ru.properties:228, tr.properties:217. No existing test
pins the notice's example tag (grep: `SummaryCommandHandlerTest` has no
`window_too_large`/`technology` hit) — which is how the drift shipped.

## Pitfalls

- P1: **Partial-bundle fix** — the key exists once per bundle in FIVE
  bundles; keyset parity is build-enforced but a stale VALUE in one bundle
  is a silent regression (M1-781 carried this acceptance). All five change
  together.
- P2: **Wrong replacement tag** — a leaf (e.g. `ai`) also passes the guard
  but narrows the advice to one leaf's posts; a non-seed name re-fails
  verbatim. `tech` (top node) maximizes the chance the advised command
  returns posts and is the owner-decided token.
- P3: **A guard that hardcodes the vocabulary re-introduces the drift it
  guards** — the test must parse the V84 seed at test tier (the
  `matchSetEqualsSpecClosedList` precedent), and must assert it extracted
  five example tags or an extraction-regex rot vacuously passes (§8
  non-vacuity, M1-651).

## Approach

Derived from docs/spec/commands.md §Content (the over-cap reply and its
advice must be actionable; the copy itself is bundle text per D43).

- **Files to touch:** the five bundle files (one token each) +
  `BundleLoaderTest.java` (one added test).
- **Steps, in order:**
  1. Write `overLimitAdviceExampleTagIsInTheTagSeedVocabulary` in
     `BundleLoaderTest`; run it RED against the unfixed bundles (workflow
     §0 — the RED run is this ticket's reproduction closure evidence).
  2. Edit the five bundle values: `technology` → the bundle-natural form of
     the `tech` example (`/summary tech -w 2h`), touching nothing else in
     each value.
  3. Re-run the test GREEN; full `mvn verify`.
- **Controls to preserve (§10):** bundle keyset parity and the MessageFormat
  apostrophe guard (the edited values keep `{0}`/`{1}` and any doubled
  apostrophes); the `/get-tags` pointer; one occurrence of the key per
  bundle. No code path is rerouted — nothing else to carry.
- **Pitfall→mitigation:** P1 → step 2 edits all five in one diff, grep
  acceptance; P2 → the decided token is `tech`, and the test asserts seed
  membership (tops included, matching `readVocabulary`); P3 → the test
  parses V84 from the classpath and asserts a five-value extraction.

## Definition of done

Every acceptance item verified by its named check: the new BundleLoaderTest
method green (and demonstrably RED pre-fix); all five values naming `tech`
with `{0}`/`{1}`/`/get-tags` intact; the drift guard proven non-vacuous by
the pre-fix RED; `mvn verify` green.

## Verification

- P1 → grep acceptance (one occurrence per bundle; no `technology` left in
  the key's values) + the test iterating all five bundles.
- P2 → `BundleLoaderTest.overLimitAdviceExampleTagIsInTheTagSeedVocabulary`
  — feeds every shipped bundle's real value and asserts the example tag is
  a seeded name.
- P3 → the same test's five-extraction assertion; its RED-ness on the
  pre-fix tree is the non-vacuity proof (a guard that cannot fail on the
  current wrong value is decoration).
- Failure-mode (negative, beyond the reproduction) → the pre-fix RED run
  itself is the hostile input: the guard REJECTS the shipped
  `technology`-naming values (a bundle value naming a non-seed tag fails
  the build, never passes silently). Complement: the unknown-tag refusal
  path the advice tripped over stays intact — this ticket touches no
  production code, so SummaryCommandHandler's existing unknown-tag /
  fuzzy-suggestion tests must remain green unchanged (§5 full suite).
- acceptance 1 → the named test.
- acceptance 2 → the named greps.
- acceptance 3 → reasoning over the test's construction (seed-of-record
  parse), corroborated by the pre-fix RED.
- acceptance 4 → `mvn verify`.

## Out-of-scope

The advice sentence's shape, arity, and `/get-tags` pointer are untouched —
the decided fix is the example token only. Whether the advised command
RETURNS posts on a given scope is not this ticket: on the campaign scope
`tech -w 2h` was empty-window and `-w 7d` still over-limit, and plan
criterion (b) asks for vocabulary-valid advice, not a converging chain
(window grammar / limit changes were M1-781, abandoned). No code changes —
validation and top-node expansion already work (SummaryCommandHandler.java:254-263,
EligiblePostQuery.java:352-355). The `help.cmd.summary.examples` values are
untouched (Census: valid as-is). The A5 live re-leg is deferred campaign
ownership, not an acceptance here. No pre-existing test is modified — the
only test change is the additive BundleLoaderTest method.

## Census

The class is "bundle copy naming an example `/summary <tag>` the vocabulary
rejects", enumerated 2026-08-22 by
`grep -n '/summary [a-z][a-z-]* -w' infochat-provider/src/main/resources/bundles/`:

| Site | Disposition |
|---|---|
| `reply.summary.window_too_large_notice` — en:203, cs:201, es:222, ru:228, tr:217 | DEFECT — names `technology`; fix (this ticket, all five) |
| `help.cmd.summary.examples` — en:76, cs:78, es:94, ru:100, tr:92 | VALID as-is — names `/summary ai -w 30d`, and `ai` is a V84 seeded leaf (V84:98); no fix, out of scope |
| any future bundle value naming a `/summary <tag>` example | guard — the new BundleLoaderTest method fails the build if the notice's example tag leaves the seed |
