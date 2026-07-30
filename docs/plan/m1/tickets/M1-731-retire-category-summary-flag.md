---
id: M1-731
title: "Retire infochat.digest.category-summary-enabled and reclaim the generateRollup name"
status: done
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "CategoryRollupGenerator.buildPrompt / ROLLUP_SYSTEM_PROMPT content — the prompt's WORDING and cluster-budgeting are M1-728"
  - "DigestRenderer.renderShortBody, renderSummarySections and render output SHAPE — the roll-up's new role as the category body is M1-732"
  - "any /digest or /summary mode, command surface or delivery change (M1-732, M1-733, M1-734)"
  - "documented-config-key-exemptions.txt — no exemption is needed or permitted; the @ConfigProperty and doc deletions land together"
  - "the sanitize/translate pipeline inside generateRollup — reused verbatim, only the gate and the name change"
  - "DigestRenderer's categoryRollupGenerator field INITIALIZER (= new CategoryRollupGenerator()) — it stays; only the comment above it is rewritten"
acceptance:
  - "CategoryRollupGenerator no longer declares the @ConfigProperty field or the categorySummaryEnabled boolean."
  - "The gated wrapper generateRollup and its javadoc are deleted; generateRollupUnconditional is renamed to generateRollup and its javadoc rewritten to describe the sole entry point — carrying across the @param docs and the failure-containment list the deleted wrapper's javadoc held, minus the flag-off bullet."
  - "No javadoc or comment in CategoryRollupGenerator describes a config flag (covers the class javadoc at :24 and :45 as well as the method javadoc)."
  - "DigestRenderer.renderSections no longer calls the roll-up generator: the prefix call and the explanatory comment above it are deleted."
  - "DigestRenderer's remaining flag prose is gone — the field-initializer comment is rewritten to state the rationale that survives (a default instance so plain-JUnit constructions reaching renderShortBody do not NPE; CDI overwrites it), renderSections' javadoc loses its flag-on/flag-default clauses, renderShortBody's javadoc loses the 'flag is NOT consulted' paragraph, and the 6-arg forSummaryRendering javadoc loses 'flag-gated'."
  - "The two DigestRenderer javadoc @links and the --short call site name the renamed generateRollup."
  - "CategoryRollupGeneratorTest: the five categorySummaryEnabled setup lines are gone and every surviving assertion is preserved verbatim (one-call-per-category determinism, sanitize-then-translate ordering and language forwarding, LLM-failure containment, refusal-marker containment, empty-response containment); the :107 assertion message loses its '(exactly the flag-off shape)' clause."
  - "CategoryRollupGeneratorTest.flagOffYieldsNoRollupAndNoLlmCall is deleted — its entire subject is the flag's off-state."
  - "DigestRendererSectionsTest.rollupPrefixAppearsInRenderedSectionWhenGeneratorReturnsOne is deleted — the renderSections prefix path it pins is the path this ticket removes, so it is coverage of a deleted behaviour, not coverage lost."
  - "DigestRendererSectionsTest.proseAndRollupsCoverOnlySectionsThatSurviveTheCap keeps its M1-721 assertion that per-cluster prose runs only for surviving sections, and loses only the roll-up call-count assertion and the RecordingRollupGenerator wiring; the test is renamed to match what it still pins."
  - "The now-dead RecordingRollupGenerator helper is deleted."
  - "RetryCommandHandlerTest and SummaryCommandHandlerTest: the two @Override declarations and the two javadoc @links name the renamed generateRollup; no assertion in either file changes."
  - "docs/spec/commands.md: the 'Optional per-category roll-up' paragraph is deleted in full."
  - "docs/design/03-commands.md: the --short bullet names generateRollup and no longer describes bypassing a flag."
  - "The census verification grep returns ZERO hits."
  - "The flag-prose sweep grep (second census grep) returns ZERO hits across the four files in files_scope under infochat-provider."
  - "mvn verify is green from the repo root."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main, except the three assertion sites this
      ticket removes because the behaviour they pin is the behaviour being
      retired (flagOffYieldsNoRollupAndNoLlmCall,
      rollupPrefixAppearsInRenderedSectionWhenGeneratorReturnsOne, and the
      roll-up call-count half of proseAndRollupsCoverOnlySectionsThatSurviveTheCap)
spec_refs: []
decision_refs: []
decomposed_from: M1-722
reviews:
  - round: 1
    date: 2026-07-30
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 148
      removed: 187
  - round: 2
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 172
      removed: 189
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-30
  verdict: WARN
  warnings:
    - "lint: clean after start-gate refine (initial run raised BLOCKER OUT-OF-SCOPE-PRESENT on the decompose skeleton)"
    - "self-check: ticket-vs-code truth FAILED on the skeleton's 'no assertion lost' claim — deleting DigestRenderer.java:134 orphans rollupPrefixAppearsInRenderedSectionWhenGeneratorReturnsOne and the roll-up half of proseAndRollupsCoverOnlySectionsThatSurviveTheCap. Raised as a blocking question; user chose refine-then-implement, and acceptance now states three assertion removals."
    - "self-check: census truth — all 17 identifier sites re-verified live and resolve; subtype grep confirms RecordingDigestRenderer needs no change. Added a third census grep for flag-describing PROSE (7 sites the identifier grep cannot see), folded into acceptance."
  blockers: []
escalation_reason:
---

# M1-731: retire the category-summary flag

> **Authored at the M1-731 start gate (2026-07-30)**, from the M1-722 decompose
> skeleton. The carried-across census was re-verified live and its identifier
> sites all resolve — but two corrections were needed and are folded in below:
> a third census grep (flag-describing PROSE), and the fact that deleting the
> prefix call orphans three assertions, not one. See §Census and §Acceptance.

## Context

`infochat.digest.category-summary-enabled` gates a roll-up PREFIX above the
digest's per-cluster prose, and ships default-`false`. The decomposition of
M1-722 makes the roll-up the category body outright (M1-732), so the gate loses
its last caller. Retiring it is behaviour-neutral at the shipped default, which
is why it is carved out as the first, independent child: it clears
`DigestRenderer` / `DigestRendererSectionsTest` churn out of M1-732's way and
unblocks M1-728, whose only stated reason for sequencing behind M1-722 is that
both edit `CategoryRollupGenerator.java`.

Once the gated wrapper is gone, `generateRollupUnconditional` is the only entry
point and reclaims the plain name — an orphan this change itself creates, so
the rename is in scope.

## Census

```bash
grep -rn "category-summary-enabled\|categorySummaryEnabled\|generateRollupUnconditional" \
  --include=*.java --include=*.properties --include=*.md . \
  | grep -v '^./.bench' | grep -v '^./docs/plan'
```

The acceptance verification is that this same grep returns ZERO hits.
`docs/plan` is excluded because closed tickets (M1-642, M1-700, M1-722) are a
historical record and are not rewritten.

| Site | Disposition |
|---|---|
| `CategoryRollupGenerator.java:103-104` | **delete** — the `@ConfigProperty` field |
| `CategoryRollupGenerator.java:126` | **delete** — the short-circuit branch |
| `CategoryRollupGenerator.java:125` `generateRollup` | **delete** — the gated wrapper |
| `CategoryRollupGenerator.java:24` | **delete** — javadoc describing the flag |
| `CategoryRollupGenerator.java:135,146` | **update** — javadoc + the declaration, renamed |
| `DigestRenderer.java:134` | **delete** — the prefix call, `generateRollup`'s only production caller |
| `DigestRenderer.java:46-47`, `:327` | **delete** — comments describing the gate |
| `DigestRenderer.java:315`, `:442` | **update** — two javadoc `@link`s, renamed |
| `DigestRenderer.java:361` | **update** — the `--short` call site, renamed |
| `CategoryRollupGeneratorTest.java:42,75,101,116,130` | **update** — drop the flag setup, keep every assertion |
| `CategoryRollupGeneratorTest.java:56-66` (incl. `:60`) | **delete** — `flagOffYieldsNoRollupAndNoLlmCall` asserts only the flag's off-state |
| `DigestRendererSectionsTest.java:137-167` | **delete** — `rollupPrefixAppearsInRenderedSectionWhenGeneratorReturnsOne` (with its anonymous override) pins the prefix path being removed; see §Acceptance |
| `DigestRendererSectionsTest.java:204-220` | **update, partial** — keep the prose/section-cap assertion, drop the roll-up call-count assertion and the generator wiring |
| `DigestRendererSectionsTest.java:272-283` | **delete** — `RecordingRollupGenerator` goes dead once its only two uses above are gone |
| `RetryCommandHandlerTest.java:778,806` | **update** — `@Override` renamed with the method |
| `SummaryCommandHandlerTest.java:1624,1656` | **update** — `@Override` renamed with the method |
| `docs/spec/commands.md:1983` | **delete** — the "Optional per-category roll-up" paragraph |
| `docs/design/03-commands.md:465-466` | **update** — the `--short` bypass aside |

**The identifier grep has a known blind spot** that cost M1-722 two gates: it
cannot see a subclass overriding a method it never names in text. The subtype
search is the complement, and it is what surfaces the four `@Override`s above:

```bash
grep -rn "class .* extends \(DigestRenderer\|CategoryRollupGenerator\)" \
  --include=*.java infochat-provider/src
```

This surfaces a fourth subclass, `RecordingDigestRenderer` — it overrides only
`renderSections`, touches no roll-up member, and correctly needs no change.

**The identifier grep has a SECOND blind spot**, found at this ticket's start
gate: it cannot see prose that *describes* the flag without naming it. Seven
such comments survive the zero-hit verification above while still asserting a
deleted flag exists, so a third grep is part of acceptance:

```bash
grep -rn "flag-off\|flag-on\|flag-gated\|flag gate\|the flag\|roll-up flag" \
  infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java \
  infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java \
  infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java \
  infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
```

| Prose site | Disposition |
|---|---|
| `DigestRenderer.java:46-54` | **rewrite** — the field-initializer rationale is *entirely* the flag short-circuit, and `:53` claims `renderSections()` calls `generateRollup()` per section, which stops being true. The initializer itself STAYS (see §Out-of-scope) |
| `DigestRenderer.java:82`, `:84` | **delete** — `renderSections` javadoc's "flag-on roll-up prefixes" and "byte-identical at the roll-up flag's default" |
| `DigestRenderer.java:125-133` | **delete** — the comment block above the deleted prefix call, all flag rationale |
| `DigestRenderer.java:446` | **update** — "(flag-gated, no LLM wiring)" in the 6-arg seam javadoc |
| `CategoryRollupGenerator.java:45` | **update** — class javadoc "(exactly the flag-off shape)" |
| `CategoryRollupGenerator.java:110` | **delete** — rides with the wrapper javadoc |
| `CategoryRollupGeneratorTest.java:107` | **update** — assertion message "(exactly the flag-off shape)" |

## Acceptance

Every site in both tables disposed as stated, both verification greps at zero,
and `mvn verify` green. The machine-checkable list is in frontmatter
`acceptance:`; two items deserve their reasoning here.

**Three assertions are removed, not one.** The skeleton claimed the single
deletion was `flagOffYieldsNoRollupAndNoLlmCall`. Deleting the prefix call at
`DigestRenderer.java:134` — which the census correctly requires — removes the
only behaviour two further assertions pin:

- `rollupPrefixAppearsInRenderedSectionWhenGeneratorReturnsOne` asserts a stub
  generator's prefix appears inside a rendered section. With the call gone the
  generator is never invoked, so the assertion cannot pass.
- `proseAndRollupsCoverOnlySectionsThatSurviveTheCap` asserts
  `rollupGenerator.callCount() == 8`. Post-change it is 0.

Neither is coverage lost: both pin the renderSections roll-up prefix, which is
the feature being retired. The second test's *other* half — that per-cluster
prose runs only for cap-surviving sections (M1-721) — pins a live behaviour and
is preserved verbatim; only the roll-up assertion goes, and the test is renamed
to match. Retargeting either onto `renderShortBody` is explicitly rejected
(`engineering-rules-verbatim.md` §10): the section cap does not apply there, so
the assertion would silently change meaning.

**Every `CategoryRollupGeneratorTest` assertion outside the deleted test is
preserved verbatim** — one-call-per-category determinism, sanitize-then-
translate ordering with language forwarding, and the three containment paths
(LLM throw, refusal marker, empty response). Only the five flag-setup lines and
one assertion-message clause change. Those tests keep calling `generateRollup`
by name, so the rename makes their call sites resolve to the renamed method
with no edit.

## Out-of-scope

Beyond the frontmatter list, one judgement worth stating: `DigestRenderer`'s
`categoryRollupGenerator = new CategoryRollupGenerator()` field initializer
**stays**. Its comment's stated rationale dies with the flag, but the
initializer is still the collaborator default for a plain-JUnit construction
that reaches `renderShortBody`. Removing it is a null-safety change this ticket
did not set out to make, so only the comment is rewritten.

## Notes

Retired means deleted, not left inert. A config key kept "just in case" after
its last caller goes is a feature flag by another name, which `CLAUDE.md`
§"No defensive code" forbids outright in a greenfield M1.

`DocumentedConfigKeyParityTest` (M1-708) gates the key, and it is not in
`documented-config-key-exemptions.txt` — so the `@ConfigProperty` deletion and
the doc deletions MUST land in the same diff. No exemptions-file edit is needed
or permitted.

Verified during M1-722's pass 3: the key appears in `docs/` (excluding
`docs/plan`) at exactly two sites — `docs/spec/commands.md:1983` and
`docs/design/03-commands.md:466`. It does **not** appear in
`docs/design/07-deployment.md`, contrary to M1-722's acceptance item 22, and
that file has no §"Configuration surface" heading at all (its headings are
`## 7.3 Configuration sources and precedence` and
`## 7.4 Canonical application.properties`).

## Round 1 rework

1. Delete the now-unused `import java.util.Optional;` at
   `DigestRendererSectionsTest.java:19` — this diff removed its last three
   uses (the two deleted `Optional<String> generateRollup` overrides,
   `Optional.of("TEST-ROLLUP-PREFIX")` and `Optional.empty()`), making it an
   orphan the change itself created (`engineering-rules-verbatim.md` §1, final
   bullet). The pre-existing unused `assertFalse` import in
   `CategoryRollupGeneratorTest.java:25` predates the diff and is left alone.
