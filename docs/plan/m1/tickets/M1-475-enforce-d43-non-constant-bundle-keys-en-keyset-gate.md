---
id: M1-475
title: "Enforce D43 over non-constant bundle keys (en-keyset gate)"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/en.properties
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # The discovered non-constant keys (RATE_LIMIT_KEY in QuarantineCommandHandler,
  # the new Failure("error.add_source.userinfo_rejected") literal in AddSourceArgs)
  # stay as-is. AddSourceArgs deliberately keys its Failure(String) errors by
  # literal; once the en-keyset gate guarantees completeness, converting them to
  # BundleKeys constants adds only call-site compile-safety — a separate optional
  # cleanup, not this ticket. Do NOT touch handler code.
  - "Registering RATE_LIMIT_KEY / AddSourceArgs literals as BundleKeys constants"
  - "QuarantineCommandHandler.java, AddSourceArgs.java (any handler edits)"
  # The M1-474 BundleKeys-constant own-keyset check is complementary, not
  # superseded: it catches a constant whose key is missing from en (the D43
  # startup-error case) which the en-keyset parity check cannot. The new check is
  # ADDITIVE. Do not remove or weaken everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle.
  - "Removing/weakening the M1-474 constant-completeness test"
  # test.fallback.probe is the DELIBERATE en-only key proving the 2-arg fallback
  # path; the new en-keyset gate MUST exclude it (it is legitimately en-only).
  # Do not move/alter it or its fallback test.
  - "The test.fallback.probe en-only key and its fallback test"
  # No new languages, no new BundleKeys constants.
  - "Adding new languages or new BundleKeys constants"
  # en content is frozen EXCEPT the single stale asset-section comment this ticket
  # corrects (acceptance item 5). Do not translate or reword en strings.
  - "en.properties string values (only the stale asset-section comment is edited)"
  # LLM-prose localization is the TranslationProvider path (D29), unrelated.
  - "TranslationProvider / LLM-prose translation (D29)"
acceptance:
  - >-
    BundleLoaderTest gains an en-keyset parity assertion: for every shipped
    language bundle in BundleLoader.supportedLanguages(), that bundle's OWN key
    set (read directly from the classpath resource, the M1-474 loadOwnKeys path)
    equals en.properties' OWN key set minus the deliberate en-only
    test.fallback.probe. A key shipped in en but absent from another bundle MUST
    fail the test, AND an orphan key present in a non-en bundle but absent from en
    MUST fail. This catches non-constant bundle keys (e.g.
    error.quarantine.rate_limit, error.add_source.userinfo_rejected) that the
    M1-474 BundleKeys-constant iteration cannot see, realizing D43's "every shipped
    bundle contains every key" for the full shipped keyset, not just the constant
    subset. (Provably complete: no bundle key is constructed dynamically — every
    lookup is a static literal — so en's keyset is the full enumeration.)
  - >-
    Removing error.quarantine.rate_limit (or any other shipped key) from
    cs.properties fails the new parity test; adding a cs-only orphan key not in
    en.properties also fails it. This is the regression-proof that the parity
    assertion inspects each bundle's own keyset bilaterally.
  - >-
    cs.properties contains a non-empty Czech value for error.quarantine.rate_limit
    (en value: "You are sending quarantine reviews too quickly. Please wait a
    moment before trying again."), consistent with the existing cs terminology
    (cf. error.chat.llm_rate_cap, group.command_rate_limit). This is the single
    live cs gap the new gate would otherwise fail on. Author is not a verified
    Czech speaker — flag for native-speaker review before merge (as in M1-474).
  - >-
    The M1-474 constant-completeness test
    (everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle) is preserved
    unmodified, and the en-fallback tests
    (unknownKeyThroughTwoArgAccessorThrowsAfterEnFallbackFails, the
    test.fallback.probe fallback test) still pass unmodified.
  - >-
    The stale en.properties asset-section comment (the parenthetical stating "cs
    and the rest of reply.asset.* fall back to en until the asset section is
    translated as a unit — a separate cs-completeness gap") is corrected to reflect
    that cs is now complete (M1-474). No other en.properties content changes.
  - mvn -B verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
      — add the en-keyset parity test (reuse loadOwnKeys); keep the M1-474
      constant test and the fallback tests intact.
  preserves:
    - all tests currently green on main
    - >-
      the M1-474 constant-completeness test and the en-fallback tests — behavior
      and assertions unchanged
spec_refs: []
decision_refs:
  - D43
---

# M1-475: Enforce D43 over non-constant bundle keys (en-keyset gate)

## Context

Decision **D43** mandates that every shipped language bundle contain every
key in the registry and that CI fail on a missing key (`docs/spec/decisions.md`;
`docs/spec/verification.md` §Localization bundle completeness; `docs/spec/commands.md`
§`/help`). **M1-474** gave the completeness test teeth — but only for keys that
are `BundleKeys` constants, because the check iterates `BundleKeys` via
reflection.

A bundle key does not have to be a `BundleKeys` constant, though: it only has
to be a string that reaches `BundleLoader`. Two keys bypass the constant
registry and therefore the gate:

- `error.quarantine.rate_limit` — declared as a local `RATE_LIMIT_KEY` string in
  `QuarantineCommandHandler` (`:75`). It is **missing from cs.properties**, so a
  `/lang cs` user hits English on the quarantine rate-limit error. This is a live
  D43 conformance gap that M1-474 did not — and structurally could not — catch.
- `error.add_source.userinfo_rejected` — passed as a raw `new Failure("…")`
  literal in `AddSourceArgs` (`:166`). Present in both bundles today (latent
  only), but equally invisible to the constant-driven gate.

This is the same defect class M1-474 set out to eliminate — a key missing from a
shipped bundle while CI stays green — so D43 is not yet fully enforced. The fix
is to drive the gate off **en's own keyset** (the de-facto registry of shipped
keys), not just the `BundleKeys` constant subset. A grep confirmed there is **no
dynamic bundle-key construction** anywhere in the Provider — every lookup key is
a static literal — so en's keyset is a provably complete enumeration and an
en-keyset gate can be airtight.

Discovered during M1-474 (commit `924d36e7`, "Follow-ups discovered" trailer).

## Acceptance

See frontmatter. (1) Add an en-keyset parity assertion to `BundleLoaderTest` so
a key shipped in en but absent from another bundle — or an orphan key absent from
en — fails the build, covering non-constant keys. (2) Prove teeth: removing
`error.quarantine.rate_limit` from cs fails it; a cs-only orphan fails it. (3)
Backfill the one live gap (`error.quarantine.rate_limit`) in cs with Czech. (4)
Keep the M1-474 constant test and the fallback tests intact. (5) Correct the now-
stale en asset-section comment. (6) Full suite green.

## Out-of-scope

See frontmatter. Handler code is untouched: the two non-constant keys stay keyed
by literal (`AddSourceArgs` does this by design; converting to `BundleKeys`
constants is an optional separate cleanup whose only benefit, once the parity
gate exists, is call-site compile-safety). The M1-474 constant check is kept as a
complementary guard (it catches a constant missing from en, which the en-keyset
parity check cannot). `test.fallback.probe` remains the deliberate en-only key
and must be excluded from the parity check. en string values, new languages, new
constants, and the D29 path are all untouched.

## Notes

- **Why en-keyset parity, additive to the constant check.** The two checks have
  distinct, non-overlapping coverage. The M1-474 constant check asserts every
  `BundleKeys` constant is present non-empty in every bundle *including en* — it
  catches a constant whose key is missing from en (D43's "missing keys in en are
  a startup error"). The new parity check asserts every key en *ships* is present
  in every other bundle — it catches a non-constant shipped key missing from a
  translation. Neither subsumes the other; keep both.
- **Mechanism (implementer's choice).** The cleanest form is likely a set-equality
  assertion per shipped language: `ownKeys(lang) == ownKeys("en") - {test.fallback.probe}`,
  reusing the M1-474 `loadOwnKeys` helper and the existing `FALLBACK_PROBE_KEY`
  constant as the single exclusion. An equivalent union-driven single check
  (`BundleKeys` constants ∪ en keyset, minus exclusions, present in every bundle)
  is also acceptable — but prefer the additive form to avoid rewriting the
  M1-474 test we just landed.
- **Czech translation — review gate.** As in M1-474, the one new cs value is
  author-drafted (not a verified Czech speaker) and MUST be flagged for native-
  speaker review before merge.
- **Optional cleanup deferred.** Registering `RATE_LIMIT_KEY` and the
  `AddSourceArgs` literal as `BundleKeys` constants (for call-site compile-safety
  and belt-and-suspenders gate coverage) is intentionally out of scope; file a
  separate cleanup ticket if wanted.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-475-*.md
```
