---
id: M1-792
title: "Census sanitize() caller postconditions and pin them"
status: pending
created: 2026-08-07
last_updated: 2026-08-07
flow: tick
reproduction: >-
  to-be-written: LlmOutputSanitizerPostconditionTest#everyBeanCallSitePostconditionIsPinned
  — the defect is a CLASS: callers of the shared transform rely on
  postconditions (line count, emptiness, leading bytes, token
  non-synthesis, shrinkage-to-input) that no test pins, which is why
  two redteam rounds on M1-779 each found one more caller by
  inspection (handoff §6). A test enumerating the census rows and
  asserting each documented postcondition is RED wherever a
  postcondition is unpinned; `start` writes it RED after the census
  document lands.
analysis_ref: docs/plan/m1/tick-analysis/llm-output-leaks-scaffolding-markdown.md
blocked_by: []
files_scope:
  - docs/plan/m1/sanitize-caller-census.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerPostconditionTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    FIXING the postconditions the census surfaces. Findings become
    follow-up tickets; this ticket's only behavior change is pinning
    tests. Two follow-ups are pre-named (see Acceptance): the
    TranslationPipeline conditions-(b)/(c) raw-text checks, and the
    empty-body delivery guard (P8).
  - >-
    CHANGING any sanitize() pass, the closed list (P11), or any caller.
  - >-
    RE-RUNNING the M1-779 round-1 falsification (handoff §3.1: done and
    recorded).
acceptance:
  - docs/plan/m1/sanitize-caller-census.md lands, grounded against docs/spec/security.md §LLM output sanitizer — verified by LlmOutputSanitizerPostconditionTest.everyBeanCallSitePostconditionIsPinned, which resolves each row (the 14-site roster re-derived at start by the grep in §Census) to a named pinning test or a filed follow-up — for each site the sanitize UNIT, what it assumes (line count / emptiness / leading bytes / token synthesis / shrinkage), whether its checks run before or after the call, and the pinning test, or an explicit "unpinned, follow-up M<N>-XXX" row.
  - LlmOutputSanitizerPostconditionTest.everyBeanCallSitePostconditionIsPinned passes — REPRODUCTION. The test fails on any census row that has neither a pinning test nor a filed follow-up.
  - LlmOutputSanitizerPostconditionTest.sanitizeReturnsOriginalBytesOnNoClosedListMatch passes — honest pin of LlmOutputSanitizerCore.java:561-562 (caller's own bytes back on no match).
  - LlmOutputSanitizerPostconditionTest.sanitizeMayReturnTheCanonicalFormOnMatch passes — honest pin of LlmOutputSanitizerCore.java:567; documents the synthesis channel rather than hiding it, so a future deleting-pass ticket sees the contract it is joining.
  - LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions passes — FAILURE-MODE. Feeds the transform the deletion shapes M1-789/M1-790 introduce (marker-only line, thematic-break line, emphasis-joined token) and asserts the DOCUMENTED postcondition for each — including that "" is a possible return today (P8), pinned as documentation so the follow-up's diff must update the pin deliberately, never silently.
  - Two follow-up tickets are FILED and resolve under python3 scripts/tick-lint.py — (a) TranslationPipeline conditions (b)/(c) evaluate the raw translator reply before sanitize (TranslationPipeline.java:197-226, coordinate with M1-778, which owns the file); (b) no empty-body guard exists between sanitize() and OutboundDelivery (P8), with TranslationPipeline.java:454-455's documented "never empty for a non-empty input" contract as the broken promise. Both are linked from the census rows.
  - mvn -B -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerPostconditionTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-792: Census sanitize() caller postconditions and pin them

## Context

The M1-779 attempt died of a meta defect (handoff §6): adding
character-deleting passes to the shared `sanitize()` transform changes
postconditions its callers assume — and the assumptions live in the
callers, checked before or after the call, pinned by no test. Each
audit round found exactly one more instance (DisplayHeadline.derive
line count; ChatAgent/generator refusal + tool-call detectors;
TranslationPipeline shrinkage). The census is the bounded, finishable
alternative to discovering them one finding per round. This ticket
performs it once, deliberately. Shared analysis: `analysis_ref:`.

## Root cause

Verified by the analysis's Ground truth: 13 bean call sites in 8
provider classes plus 1 collector composition site exist (the handoff
estimated ~8), and their assumed postconditions are scattered across
private branches and comments (e.g. DisplayHeadline.java:316-322,
ChatAgent.java:534-536, TranslationPipeline.java:454-455) with no
test-level statement of the contract they rely on.

## Pitfalls

- P1 (local): census drift — the roster must be re-derived by grep at
  `start`, not copied from the analysis; new call sites since
  2026-08-07 get rows.
- P2 (local): pinning as concealment — a pin that asserts the DESIRED
  postcondition where the code does not hold it turns this ticket into
  a behavior change. Pins assert the DOCUMENTED (current) contract;
  desired-state changes are the follow-ups. P8's "" return is pinned
  as possible, deliberately.
- P3 (local): scope creep into fixes — the two pre-named follow-ups
  are filed, not fixed.
- P11 (analysis): the closed list is untouched.

## Approach

- **Files to touch:** the two in `files_scope`, plus the two new
  follow-up ticket files.
- **Steps, in order:**
  1. Re-run the Census grep below; reconcile against the analysis's
     roster (13 bean + 1 collector).
  2. Write `docs/plan/m1/sanitize-caller-census.md`: one row per call
     site with unit / assumption / before-or-after / pinning test or
     follow-up.
  3. Add `LlmOutputSanitizerPostconditionTest` with the honest pins
     (acceptance items 3-5).
  4. File the two follow-up tickets (acceptance item 6) and link them
     from the census rows.
- **Controls to preserve (§10):** none rerouted — this ticket adds a
  document and tests only; that is the point.
- **Pitfall→mitigation:** P1→step 1; P2→step 3 asserts current
  contract only; P3→step 4 files, never edits source; P11→no
  CLOSED_LIST edit.

## Definition of done

Census doc landed with every grep hit dispositioned; the
reproduction/meta test green; the honest pins green; both follow-up
tickets filed and linked; provider module verify green.

## Verification

- reproduction → `LlmOutputSanitizerPostconditionTest.everyBeanCallSitePostconditionIsPinned` — fails on any census row with neither pin nor follow-up
- P1 → the Census grep re-run at `start` is recorded in the census doc header with its hit count; the meta test enumerates rows from that doc
- P2 → `LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions` asserts "" is a possible `sanitize()` return TODAY; the follow-up that fixes it must edit the pin (deliberate, reviewed — a test modified to match new behavior without this ticket's or that ticket's authorization is a §8 violation)
- P3 → `git diff --name-only` piped through `grep -c 'src/main'` returns 0 — only the census doc, the new test file, and two new ticket files; no production source touched
- P11 → `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList` passes unedited; `git diff -- infochat-core` shows no CLOSED_LIST change
- failure-mode → the deletion-shape pins (marker-only line dropped, thematic-break dropped once M1-790 lands, canonical-form-on-match) each feed the transform the hostile input and assert the documented outcome — each pin is written against a named mutation of the pass composition (reordering the passes, dropping the id-class exclusion) that it must catch, so no pin is vacuous

## Out-of-scope

All source changes, the closed list, and fixing any surfaced gap —
named in `out_of_scope`. No pre-existing test is modified.

## Census

Class: every call site of `LlmOutputSanitizer.sanitize()` and every
explicit composition of `LlmOutputSanitizerCore` passes. Re-runnable:
`grep -rn '\.sanitize(\|LlmOutputSanitizerCore\.' --include='*.java' infochat-provider/src/main infochat-collector/src/main`.
The verified 2026-08-07 roster (re-derive at start): ChatAgent:548;
CategoryRollupGenerator:212; SummaryProseGenerator:128 (render sites
ClusterBlockRenderer:183, DigestRenderer:610, DigestRenderer:881);
DisplayHeadline:144, :311, :344, :736; TranslationPipeline:245;
SavedCommandHandler:387, :516, :521; IngestTranslationWorker:775-777
(collector, explicit composition). Every returned path gets a row in
`docs/plan/m1/sanitize-caller-census.md` (fix-test / already-pinned /
defer: follow-up / out-of-scope: reason).
