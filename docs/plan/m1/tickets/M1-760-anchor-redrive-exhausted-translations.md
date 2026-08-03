---
id: M1-760
title: "Re-drive posts whose ingest translation exhausted its attempts"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorker.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE DISPLAY SIDE. How a missing anchor RENDERS is M1-759 (bracketed
    original in the primary slot). This ticket reduces how often that case
    occurs; it does not change what the reader sees when it does.
  - >-
    THE TRANSLATION PROMPT, the delimiter rotation, the sanitizer pass, or
    the `translation_done` cursor semantics. A re-drive re-runs the
    EXISTING path; it does not tune it.
  - >-
    THE EMBEDDING GATE. `translation_done = TRUE` continues to release a
    post to embedding whether or not an anchor was produced — a re-drive
    must never hold a post out of retrieval waiting for a translation.
    Posts stay searchable from the original text via the existing
    `coalesce` fallbacks throughout.
  - >-
    RE-EVALUATION generally (`ReEvaluationJob`, Stage 1/2 re-runs). This
    is the translation cursor only; borrow the scheduling SHAPE, do not
    extend that job.
  - >-
    Widening attempt counts on the FIRST pass. The initial two-attempt
    budget is unchanged; this ticket adds a later, slower retry, not a
    more persistent immediate one.
acceptance:
  - >-
    A post with a non-English source whose anchor fields are NULL and
    whose `translation_done` is TRUE — the exhausted-attempts terminal
    state — is re-enumerated for translation on a backoff schedule rather
    than being dark forever. The discriminator is exactly
    `source.language <> 'en' AND title_en IS NULL AND translation_done`;
    an English-source post also has NULL anchors by design (the worker
    flips its cursor with no translator call) and MUST NOT be enumerated.
  - >-
    The ladder is bounded and terminal: a fixed number of re-drives on
    exponential backoff, then the post is left alone permanently. Follow
    M1-754's parked-source re-probe ladder for the shape — it is the
    in-repo precedent for "retry a terminal state on a slow ladder without
    starving the healthy path" — including a separate scheduling path so
    re-drives cannot delay first-pass translation.
  - >-
    A successful re-drive writes `title_en`/`body_en` through the SAME
    `persistTranslation` path, so the anchor lands sanitized and atomic
    exactly as a first-pass translation does. No second write path.
  - >-
    Time that drives the backoff decision is read from the injected
    `java.time.Clock` per CLAUDE.md §"Injectable time in decision logic",
    never `Instant.now()` or SQL `now()`. `ReEvaluationJob` (M1-444) is
    the reference implementation. Tests pin it with
    `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`.
  - >-
    Re-drive volume is observable: the count of posts in the
    exhausted-anchor state is logged per tick, so an operator can see a
    systematic translator failure rather than inferring it from missing
    translations. A recurring non-empty set is the signal that the
    translator route itself is misconfigured.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
  preserves:
    - >-
      Every first-pass translation test, including the English-source
      short-circuit (cursor flipped, no translator call) and the
      two-attempt exhaustion release.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-760: Re-drive exhausted ingest translations

## Context

Surfaced by the 2026-08-04 display-leg design session as the durable half
of the missing-anchor problem.

`IngestTranslationWorker` gives a post two attempts; on exhaustion it
releases the post with `translation_done = TRUE` and `title_en`/`body_en`
left NULL, so downstream `coalesce` fallbacks keep it searchable and
embeddable from the original text. That release is correct — a translation
failure must not strand a post outside retrieval — but it is also
permanent. Nothing ever tries again.

Two consequences. Retrieval quality for that post is silently worse than
the corpus around it: both arms match on the anchor, and this post has
none, so it competes on original-language text inside an English-anchored
index. And after M1-759 the reader sees a bracketed foreign headline where
every neighbouring entry is in their language.

A transient failure — the translator route down, a rate limit, a model
returning garbage once — therefore becomes a permanent per-post defect.
That is the gap this ticket closes.

## Why collector-side rather than at render

The alternative is a display-time retry when the anchor is missing. It was
rejected in the design session: it puts a generative call back on the
scheduled digest broadcast path, which is the exact cost surface M1-756's
metering exists to bound and which drew a high/DOS finding on the smaller
`/saved` equivalent. A re-drive fixes the post ONCE for every reader and
every surface; a display retry pays per render, per scope, forever.

## Census

The exhausted-anchor state and its neighbours, so the enumeration
predicate is provably narrow:

| `source.language` | `title_en` | `translation_done` | Meaning | Re-drive |
|---|---|---|---|---|
| `en` | NULL | TRUE | English post, nothing to translate | **no** |
| non-`en` | NULL | FALSE | not yet attempted / in flight | no — first pass owns it |
| non-`en` | NULL | TRUE | **attempts exhausted** | **yes** |
| non-`en` | present | TRUE | translated | no |

The first row is why `title_en IS NULL` alone is not the predicate: it
matches the entire current corpus.

## Notes

- `security_relevant: false`: no trust boundary moves, no new external
  input, no authorization surface. The re-drive re-runs an existing
  sanitized path on posts already in the corpus.
- Worth checking at implementation time whether the exhausted set is
  non-empty in the live corpus. If it is empty because no non-English
  source has ever been added, this ticket is preventative — say so in the
  commit rather than implying it repaired live rows.
