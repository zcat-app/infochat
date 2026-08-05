---
id: M1-766
title: "Anchor-first degraded renderers"
status: pending
created: 2026-08-04
last_updated: 2026-08-05
blocked_by:
  - M1-759
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseRefusalDegradeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDegradedRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestBudgetScopingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    NEW PROJECTIONS. Both surfaces already receive
    `EligiblePostQuery.Post` instances — `DegradedDigestRenderer.render`
    takes `List<EligiblePostQuery.Post>` and
    `SummaryProseGenerator.degradedProseFor` iterates `cluster.posts()` —
    and M1-759 already added the anchor to that record and to all three
    projections that populate it. No SQL changes.
  - >-
    CALLER BEHAVIOUR BEYOND PASSING THE LANGUAGE. The four caller files
    are in `files_scope` for the `scopeLanguage` argument and nothing
    else: each already holds the value, so the edit is one added
    argument per call site. Their own rendering, budgeting, degradation
    and translation decisions are untouched — in particular
    `DigestRenderer.appendHeadlines`' display-hit leg and translation
    budget, and `ClusterBlockRenderer`'s degraded skip, stay exactly as
    M1-759 / M1-769 left them.
  - >-
    ANY TRANSLATOR CALL. The degraded path exists BECAUSE the LLM is
    unavailable or refused. Reading the anchor column is free and is the
    whole reason this surface can have it; invoking
    `runForDisplayHit` here would put a generative call on the one path
    guaranteed not to have a working model. The reader-language line is
    the anchor as stored, never a display-time translation.
  - >-
    `SummaryProseGenerator.buildPrompt` and the summarizer/roll-up
    PROMPT inputs. Prompt text stays untranslated and anchor-free
    (M1-747); only `degradedProseFor`, which renders to the user, changes.
    `SummaryProseGenerator` appears in `files_scope` for that one method.
  - >-
    THE BLOCK SHAPE ITSELF. M1-759 owns the bracket invariant, the
    suppression rule and the line order. This ticket adopts them; it does
    not redefine them. Note the two surfaces have their own line
    templates (`DegradedDigestRenderer` renders
    `headline — sourceDisplayName` then the URL on its own line;
    `degradedProseFor` joins headline and url with `" — "` and appends
    the uid), and per M1-759's CONSISTENT-ACROSS-SURFACES item what
    must match is the DERIVATION and the invariant, not the bytes.
  - >-
    `DisplayHeadline.of(Post, …)`'s existing behaviour for any caller
    outside `files_scope`. After this ticket the only remaining callers
    of the original-only entry point are prompt builders, which must
    keep it.
acceptance:
  - >-
    THE READER LANGUAGE REACHES BOTH SURFACES. Neither
    `DegradedDigestRenderer.render(List<Post>)` nor
    `SummaryProseGenerator.degradedProseFor(Cluster, sanitizer)` takes a
    scope language today, and `DisplayHeadline.usesAnchor` /
    `primaryFor` cannot be called without one — so each grows a
    `scopeLanguage` parameter and every caller passes the value it
    ALREADY holds (`DigestWorker` `meta.language()`; `DigestRenderer`
    `langCode` at all three sites; `SummaryCommandHandler` and
    `ClusterBlockRenderer` `scopeLanguage`). Parameter-threading only:
    no caller resolves, defaults, or derives a language it did not
    already have, and no other signature changes.
  - >-
    BOTH SURFACES SWITCH to the anchor-aware entry point M1-759 added,
    so a non-English post renders its English anchor with the bracketed
    original beneath on the degraded digest and in degraded prose, and
    an unbracketed line still means "already in the reader's language".
  - >-
    ZERO PROVIDER CALLS ON THE DEGRADED PATH — asserted with a spy on
    both surfaces. This is the property that makes the change safe to
    make here at all: the anchor is a column read, and the degraded path
    is by definition running without a usable model.
  - >-
    THE M1-714 OMISSION CONTRACT SURVIVES. Both renderers drop an empty
    headline together with its separator — `DegradedDigestRenderer` so
    the entry leads with the source display name and never a dangling
    `" — "`, `degradedProseFor` so the line opens with its url and the
    uid still identifies it. Adding a second line must not resurrect a
    dangling separator when the headline is empty, nor emit an empty
    bracket `[]`. Pinned by test on both.
  - >-
    THE M1-729 SENTINEL FALLBACK SURVIVES, on the same rule M1-759
    adopts: choose the field (title vs body) from the ORIGINAL, then take
    that field's anchor. A titleless non-English post must still render
    from its body, not from a translated `"untitled"` sentinel.
  - >-
    SANITIZE UNIT AND ORDER UNCHANGED: bound -> flatten -> sanitize ->
    truncate, one author's field per `sanitize` call (M1-697), the anchor
    and the original never concatenated before the sanitizer sees them.
    Per CLAUDE.md §"Preserve the controls of a path you replace", the
    unit is part of the control.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  preserves:
    - >-
      M1-714's empty-operand omission contract on both renderers.
    - >-
      The degraded path's no-LLM guarantee — `SummaryProseRefusalDegradeTest`
      pins that a refusal degrades rather than calls again.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
  - D43
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-766: Anchor-first degraded renderers

## Context

Filed out of M1-759 (2026-08-04). That ticket makes the English anchor a
display artifact, but deliberately introduces a NEW `DisplayHeadline`
entry point rather than changing `of(Post, …)` in place, because
`of(Post, …)` has four callers and two of them —
`DegradedDigestRenderer.render` (line 78) and
`SummaryProseGenerator.degradedProseFor` (line 233) — are outside its
`files_scope`. Changing the shared method would have altered two
user-facing surfaces with no authorized test. So M1-759 leaves them
rendering the original, and this ticket closes the gap.

## Why the degraded path deserves the anchor most

The degraded renderers run when the LLM is unavailable, over budget, or
has refused. That is exactly the condition under which display-time
translation is impossible — and exactly the condition under which a
pre-computed column costs nothing. A reader who gets a degraded digest
today sees raw foreign headlines with no recourse; after this ticket they
see the English anchor that was already computed at ingest, with the
publisher's own words bracketed beneath.

M1-759 already added the anchor to `EligiblePostQuery.Post` and to all
three projections that populate it, and both surfaces here take that
record — so no SQL and no new projection is needed.

**What the filing pass missed** (found at `start`, 2026-08-05; scope
widened 5 → 16 files by user decision). The anchor-aware entry point is
not a drop-in for `DisplayHeadline.of`: `usesAnchor(headline,
sourceLanguage, scopeLanguage)` and `primaryFor(primary,
primaryInReaderLanguage)` both need the READER's language, and neither
degraded surface receives one — `render(List<Post>)` has no language
parameter and `degradedProseFor` is `static` with none either. Without
it the bracket invariant cannot be honoured: promoting the anchor
unconditionally shows a Czech reader English for a Czech-source post
(exactly what `usesAnchor` exists to suppress), and bracketing
unconditionally brackets every English headline in an English digest,
which inverts D29 (c)'s "unbracketed means already in your language".
So both signatures grow a `scopeLanguage` parameter and the six call
sites across four caller files pass the value they already hold. That
threading, not the anchor switch, is the bulk of the diff.

## Census

The class is "callers of `DisplayHeadline.of(…)`". Re-run at `start` —
M1-759 lands first and will already have moved several of these to its
new anchor-aware entry point, so the table below is the pre-M1-759
picture and the disposition is what must be TRUE when this ticket
finishes:

```
grep -rn "DisplayHeadline\.of(" --include=*.java infochat-provider/src/main
```

| Site | Disposition |
|---|---|
| `digest/DegradedDigestRenderer.java:78` | IN — switch to the anchor-aware entry point |
| `summary/SummaryProseGenerator.java:233` (`degradedProseFor`) | IN — switch to the anchor-aware entry point |
| `digest/DigestRenderer.java:865` | ALREADY SWITCHED by M1-759 — verify, do not re-edit |
| `command/ClusterBlockRenderer.java:116` | ALREADY SWITCHED by M1-759 — verify, do not re-edit |
| `command/SavedCommandHandler.java:413` | ALREADY HANDLED by M1-759 via the `(String, String, sanitizer)` overload; the anchor itself arrives with M1-765 |
| `digest/CategoryRollupGenerator.java:329` | OUT — prompt builder, keeps the original-only overload (M1-747) |

If the re-run finds a caller not in this table, that is a new site added
between filing and start: stop and surface it rather than deciding its
disposition silently.

## Notes

- `blocked_by: M1-759` is a real dependency: the entry point, the bracket
  invariant and the anchor-absent branch this ticket adopts are all
  introduced there.
- The companion follow-up from the same M1-759 pass is M1-765 (the
  `saved_post` anchor snapshot). The two are independent and share no
  files, so they may run in parallel once M1-759 lands.
