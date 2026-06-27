---
id: M1-474
title: "Enforce D43 bundle completeness: make BundleLoaderTest fail on a missing per-bundle key + backfill cs.properties"
status: done
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/resources/bundles/cs.properties
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  # The 2-arg get(key, lang) en-fallback (BundleLoader.java:116-125) stays as
  # defense-in-depth — LangCommandHandler is the authoritative unsupported-code
  # gate. Once (1) makes every bundle complete, the fallback never has to fire
  # for a real key; do not remove it.
  - "BundleLoader.get(key, lang) en-fallback removal"
  # test.fallback.probe is a DELIBERATE en-only key (BundleLoaderTest.java:33,49)
  # that exists to prove the fallback path. It is NOT a BundleKeys constant, so
  # the constant-driven completeness check never touches it. Do not move/alter it
  # or its fallback test (unknownKeyThroughTwoArgAccessorThrowsAfterEnFallbackFails).
  - "The test.fallback.probe en-only key and its fallback test"
  # en.properties is the complete reference set; missing-en-key handling is a
  # separate startup-error path (D43), not this ticket. Do not edit en content.
  - "en.properties content"
  # No new languages and no new BundleKeys constants — this ticket only makes
  # cs complete against the EXISTING registry and makes the gate enforce it.
  - "Adding new languages or new BundleKeys constants"
  # LLM-authored prose localization is the TranslationProvider path (D29), a
  # different mechanism entirely. Not in scope.
  - "TranslationProvider / LLM-prose translation (D29)"
acceptance:
  - >-
    BundleLoaderTest's completeness test asserts, for every BundleKeys constant
    AND every shipped language bundle (en, cs), that the key is present in THAT
    bundle's own key set with a non-empty value, WITHOUT resolving through the
    en-fallback accessor get(key, lang). Today the test (BundleLoaderTest.java:80-91)
    calls get(key, lang), which falls back to en (BundleLoader.java:116-125), so a
    key missing only from cs still resolves and the assertion passes. After this
    change a BundleKeys constant present in en but absent from cs MUST fail the
    test — realizing D43's "CI fails on a missing key". The deliberate en-only
    test.fallback.probe is unaffected (it is not a BundleKeys constant, so the
    constant-driven iteration never inspects it).
  - >-
    Removing any single BundleKeys-constant line from cs.properties causes
    BundleLoaderTest to fail (the gate now has the teeth D43 requires). This is
    the regression-proof that the strengthened assertion actually inspects
    cs's own keys rather than the en fallback.
  - >-
    cs.properties contains a non-empty Czech value for every BundleKeys constant,
    backfilling the keys currently present in en.properties but absent from cs
    (approximately 48 — the BundleKeys constants missing from cs; the raw
    en-minus-cs key diff is 49, of which test.fallback.probe is the deliberate
    en-only non-constant). The missing keys span the /audit, /promote, /demote,
    /group-timezone, /quarantine, /retry-digest, and asset command families.
    Values are Czech, consistent with the terminology and placeholder syntax of
    the existing cs.properties entries.
  - >-
    The 2-arg get(key, lang) en-fallback behavior is unchanged and its existing
    tests still pass unmodified (unknownKeyThroughTwoArgAccessorThrowsAfterEnFallbackFails
    and the test.fallback.probe fallback test). The fallback remains as
    defense-in-depth; acceptance item 1 ensures it never has to fire for a real key.
  - mvn -B verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
      — strengthen everyBundleKeysConstantResolvesInEveryLoadedBundleToANonEmptyString
      to inspect each shipped bundle's OWN key set (not the en-fallback accessor),
      so a key missing from cs fails. Existing fallback/throw tests preserved.
  preserves:
    - all tests currently green on main
    - >-
      the en-fallback tests (unknownKeyThroughTwoArgAccessorThrowsAfterEnFallbackFails,
      test.fallback.probe) — behavior and assertions unchanged
spec_refs: []
decision_refs:
  - D43
clarity_check:
  date: 2026-06-27
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: round_cap: 3 set on complexity: medium / risk: medium; spec conditions for round_cap 3 are complexity:high or risk:high."
    - "FORWARD-REFERENCE-CHECK: M1-3xx in body prose matches the ticket-ID regex but resolves to no ticket; informal range shorthand."
  blockers: []
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 139
      removed: 27
---

# M1-474: Enforce D43 bundle completeness

## Context

Decision **D43** (`docs/spec/decisions.md`, reinforced at
`docs/spec/verification.md` §Localization bundle completeness and
`docs/spec/commands.md` §`/help`) is unambiguous:

> "Localization bundle completeness is enforced at build time: **every shipped
> language bundle MUST contain every key in the registry; CI fails on a missing
> key.** Missing keys in `en` are a startup error (no runtime fallback to a
> different language)."

Two coupled defects break this:

### A. The completeness gate is structurally blind to cs (the real bug)

`BundleLoaderTest.everyBundleKeysConstantResolvesInEveryLoadedBundleToANonEmptyString`
(`BundleLoaderTest.java:80-91`) is the build-time check D43 mandates. But it
resolves each key via the 2-arg accessor `bundleLoader.get(key, lang)`, which
falls back to the `en` bundle when the key is missing in `lang`
(`BundleLoader.java:116-125`). So for `lang = cs`, a key that exists only in
`en` still resolves to the en value — non-null, non-empty — and the assertion
passes. The test's name and javadoc claim per-bundle ("bilateral") parity, but
it only ever proves *resolvability via fallback*, never that cs carries the key
itself. The gate D43 requires has therefore been green while the invariant it
is supposed to protect is violated.

### B. cs.properties is incomplete (the D43 violation the gate should have caught)

`cs.properties` is missing ~48 `BundleKeys` constants that `en.properties`
carries — entire command families (`/audit`, `/promote`, `/demote`,
`/group-timezone`, `/quarantine`, `/retry-digest`, and the asset commands).
These were added to `en` + `BundleKeys` as those features landed (M1-3xx/4xx)
and never backfilled into cs, and the blind gate (A) let every build stay
green. A `/lang cs` user silently gets English for all of these.

**Severity note.** The `/deep-code-review full` run (2026-06-27) found defect A
and graded it *medium / test-efficacy*. That grade is wrong: cross-checked
against D43 this is a **spec-conformance violation** — a shipped bundle is
incomplete and the spec-mandated build-time completeness gate is non-functional.
The review agent read the module code but not the decisions log, so it saw the
broken test without seeing that the broken test *is* the D43 gate.

Source: `/deep-code-review full` (run `full-2026-06-27-1343`), provider report
F1, severity corrected against D43.

## Acceptance

See frontmatter. (1) Rewrite the completeness test to inspect each shipped
bundle's own key set so a key missing from cs fails the build (D43's "CI fails
on a missing key"). (2) Prove it has teeth: removing any one cs key line fails
the test. (3) Backfill cs with Czech for every `BundleKeys` constant. (4) Leave
the en-fallback and its tests untouched. (5) Full suite green.

## Out-of-scope

See frontmatter. The en-fallback accessor and the deliberate
`test.fallback.probe` key stay; `en.properties` content, new languages, new
`BundleKeys` constants, and the D29 LLM-prose translation path are all
untouched.

## Notes

- **Mechanism (implementer's choice).** The strengthened test needs each
  bundle's *own* keys, which the current public API does not expose without the
  fallback. Either add a non-fallback accessor to `BundleLoader` (e.g. a
  per-language key-set, or a strict `containsOwnKey(key, lang)`) — hence
  `BundleLoader.java` is in `files_scope` — or have the test read the bundle
  resources directly. Either is acceptable as long as a key missing from cs
  fails the build. If no `BundleLoader` change is needed, that file simply isn't
  touched.
- **Czech translation — review gate (decision: option (a)).** The ~48 cs values
  are drafted during implementation, consistent with the existing cs.properties
  terminology and placeholder syntax. The author is NOT a verified Czech
  speaker, so these MUST be flagged for native-speaker review before merge.
  (Alternative considered: operator supplies the strings — option (b); switch
  the implementation step to "wire in provided values" if preferred.)
- **Why backfill rather than downgrade the claim.** An earlier reading
  considered relaxing the test to accept the en-fallback and documenting cs as
  intentionally partial. D43 forecloses that ("no runtime fallback to a
  different language"; every bundle complete), so the only spec-compliant fix is
  to make cs complete and make the gate enforce it.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-474-*.md
```
