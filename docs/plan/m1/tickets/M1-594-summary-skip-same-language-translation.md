---
id: M1-594
title: "Provider: /summary emits a misleading \"Translating...\" progress step for an English scope (suppress the TRANSLATING stage when scope language == source)"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FixedUserAndLanguageDataSource.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    TranslationPipeline.run() itself. It ALREADY en-short-circuits at its top
    (skip translator, cache, and sanitizer-2 when scopeLanguage
    equalsIgnoreCase "en") — TranslationPipeline.java:67-69, per llm.md §Pipeline
    order step 3 "skipped if the scope language is English". So the FEARED
    wasteful remote-LLM translation call for en->en DOES NOT HAPPEN; no post
    content leaves the host for a no-op. This ticket does NOT touch
    TranslationPipeline, TranslationCache, or LlmTranslationProvider — the
    short-circuit is correct as-is. The defect is purely the progress LABEL.
  - >-
    The ProgressStage enum, StageProgressNotifier, and the progress.translating /
    progress.translating (en/cs) bundle strings. The TRANSLATING stage and its
    bundle key STAY — a cs (non-English) scope still translates and must still
    see "Překládám...". This ticket only stops PUBLISHING that stage for an
    English scope; it adds/removes/renames NO bundle key, so the D43 en/cs
    bilateral-keyset invariant is not engaged and no bundle file is touched.
  - >-
    The periodic group digest path (DigestRenderer). DigestRenderer calls
    translationPipeline.run(...) (which self-short-circuits en) but publishes NO
    TRANSLATING ProgressStage of its own — only SummaryCommandHandler does — so
    the digest never shows the misleading label and needs no change. Do NOT touch
    DigestRenderer.
  - >-
    The other four non-terminal stages (STARTED, RETRIEVING, GENERATING,
    FINALIZING). They are unconditional and correct — the summary always
    retrieves, generates, and finalizes regardless of language. Only TRANSLATING
    is language-conditional.
  - >-
    ChatAgent / RetryCommandHandler translation call sites. They are separate
    handlers; this live finding was observed on /summary and is scoped to it.
acceptance:
  - >-
    In SummaryCommandHandler.handle(), the
    `progressNotifier.publish(scope, ProgressStage.TRANSLATING)` call
    (SummaryCommandHandler.java:277) becomes conditional: it fires ONLY when the
    scope's target language differs from the source (English) — i.e. guarded by
    the SAME predicate TranslationPipeline uses to short-circuit
    (`!scopeLanguage.equalsIgnoreCase("en")`), reading the `scopeLanguage`
    already resolved at SummaryCommandHandler.java:258 via readScopeLanguage().
    For a default English-default scope, NO "Translating..." step is emitted;
    the user sees STARTED -> RETRIEVING -> GENERATING -> FINALIZING -> complete.
  - >-
    A non-English scope (e.g. /lang cs) is UNCHANGED: TRANSLATING is still
    published in spec order and the cluster prose still runs through
    TranslationPipeline (which does the real translator call for cs). The guard
    suppresses the label EXACTLY when — and only when — no translation work
    occurs, keeping the label truthful in both directions.
  - >-
    No remote-LLM behavior change and no correctness change to the composed
    summary body: TranslationPipeline already no-ops en->en, so the delivered
    text is byte-identical before and after this ticket for every scope. This is
    a progress-label-fidelity fix only.
  - >-
    NAMED TESTS in SummaryCommandHandlerTest: (a)
    terminalSummaryPublishesNonTerminalStagesInOrderThenCompletes is UPDATED — an
    English-default scope now asserts publishedStages() == [STARTED, RETRIEVING,
    GENERATING, FINALIZING] (TRANSLATING absent); and (b) a NEW cs-scope case
    asserts publishedStages() CONTAINS TRANSLATING (in spec order) when the
    scope language is "cs". (b) requires FixedUserAndLanguageDataSource to return
    a configurable language (today it hardcodes "en"); extend its constructor to
    accept the scope language, defaulting existing callers to "en". Red-before /
    green-after on (a) (the current test asserts the five-stage list including
    TRANSLATING and must flip to four).
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
      — flip terminalSummaryPublishesNonTerminalStagesInOrderThenCompletes to the
      four-stage English list (TRANSLATING dropped) and add a cs-scope case
      asserting TRANSLATING is published when the scope language is "cs".
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/FixedUserAndLanguageDataSource.java
      — add a scope-language parameter to the stub (return the supplied language
      from the scope_preferences getString) so a cs-scope test is expressible;
      existing constructors default to "en" so no other test changes.
  preserves:
    - all tests currently green on main
    - >-
      the existing SummaryCommandHandlerTest cases for happy-path compose,
      degraded fallback, /stop cancellation, rate-cap, and no-posts — none of
      which assert on the TRANSLATING stage.
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/messaging.md §Progress notifications
decision_refs:
  - D29
  - D43
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-594: /summary emits a misleading "Translating..." step for an English scope

## Context

Found 2026-07-08 during live testing. Running `/summary` as an **English-scope**
user (the default — no `/lang` set) shows the progress placeholder evolve
through `Working on it...` -> `Translating...` -> the final summary. The scope
language is English and the summary content is English, so a `Translating...`
step is **misleading**: nothing is being translated.

### What the label is (and is NOT)

The initial hypothesis was that the label reflected a real, wasteful remote-LLM
translation call for en->en (this deployment routes `TRANSLATOR` to remote
DeepSeek, so a no-op translation would mean extra latency AND post prose leaving
the host for nothing). Investigation **falsified** that:

- `TranslationPipeline.run()` already **en-short-circuits** at its very top —
  when `scopeLanguage.equalsIgnoreCase("en")` it returns the input unchanged,
  invoking neither the translator, the cache, nor sanitizer-2
  (`TranslationPipeline.java:67-69`, matching `llm.md` §Pipeline order step 3:
  "`TranslationProvider` — skipped if the scope language is English").
- So for an English scope **no remote call happens and no content leaves the
  host.** The delivered summary bytes are already correct.

The defect is therefore **purely the progress label**:
`SummaryCommandHandler.handle()` publishes `ProgressStage.TRANSLATING`
**unconditionally** at `SummaryCommandHandler.java:277`, regardless of scope
language — so an English scope is told "Translating..." for a translation that
the pipeline immediately no-ops one line later inside `ClusterBlockRenderer`.

## The fix

Guard the one `progressNotifier.publish(scope, ProgressStage.TRANSLATING)` call
so it fires only when the scope actually translates — the same predicate the
pipeline uses to decide whether to do real work:

```java
if (!scopeLanguage.equalsIgnoreCase("en")) {
    progressNotifier.publish(scope, ProgressStage.TRANSLATING);
}
```

`scopeLanguage` is already in scope: it is read at `SummaryCommandHandler.java:258`
(`readScopeLanguage(...)`, which defaults to `"en"` when the scope never ran
`/lang`). Using the identical `equalsIgnoreCase("en")` test keeps the label in
lockstep with the pipeline's short-circuit: the "Translating..." step appears
exactly when — and only when — the pipeline will do translation work.

No bundle change: the `progress.translating` key (and its `cs` twin `Překládám...`)
stays, still used by non-English scopes. No `TranslationPipeline` change: its
en-short-circuit is already correct.

## Out-of-scope

See frontmatter. Notably: `TranslationPipeline` (already short-circuits en — no
wasteful call to fix), the `ProgressStage` enum / bundle strings (the stage and
key stay; a cs scope must still see the label), the periodic digest
(`DigestRenderer` publishes no TRANSLATING stage of its own), and the other four
non-terminal stages (unconditional and correct).

## Notes

- **Provenance.** Live-test finding 2026-07-08 (SimpleX English-scope `/summary`
  walkthrough). Not a red-team finding.
- **Why the label, not the pipeline.** The pipeline's en-short-circuit means the
  summary is already delivered correctly and cheaply; only the operator/user-
  facing progress narration was wrong. A one-line conditional at the single
  publish site brings the narration into agreement with the work actually done.
- **Test-helper reach.** The new cs-scope assertion needs the test's
  `FixedUserAndLanguageDataSource` to report a non-"en" scope language (it
  currently hardcodes `"en"` for the `scope_preferences` lookup); the ticket
  extends that stub's constructor with a language parameter, defaulting existing
  callers to `"en"`. That is the reason `FixedUserAndLanguageDataSource.java` is
  in `files_scope`.
