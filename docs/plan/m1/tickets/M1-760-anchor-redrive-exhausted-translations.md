---
id: M1-760
title: "Re-drive posts whose ingest translation exhausted its attempts"
status: pending
created: 2026-08-04
last_updated: 2026-08-05
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/resources/db/migration/V77__post_translation_redrive.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorker.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerIT.java
  - infochat-collector/src/test/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
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
    Membership in the re-drive set is a DURABLE STAMP written by the
    releasing path, never a predicate derived from the post's columns.
    `releaseNull` — and only `releaseNull` — stamps the ladder when the
    two-attempt budget is exhausted. The other three callers of
    `persistTranslation` MUST NOT stamp: the English-source
    short-circuit (NULL anchors by design, no translator call ever), the
    no-body arm, and `releaseRefused`. A derived predicate
    (`source.language <> 'en' AND title_en IS NULL AND translation_done`)
    is REJECTED as the discriminator precisely because it cannot separate
    those states — `releaseNull` and `releaseRefused` write byte-identical
    rows (`IngestTranslationWorker.java:523` and `:543`), so a derived
    predicate would re-drive refusals, contradicting the standing decision
    at `IngestTranslationWorker.java:296-300` that a structured refusal is
    never retried. Migration V77 carries the stamp; V66's
    `post.tagger_sweep_attempts` is the in-repo precedent for per-post
    re-drive bookkeeping on `post` (additive columns, PG fast default, no
    table rewrite, no new GRANTs — the collector holds table-level UPDATE
    on `post` per `V7__joins_post.sql:222`).
  - >-
    The ladder is bounded and terminal: a fixed number of re-drives on
    exponential backoff, then the post is left alone permanently. Follow
    M1-754's parked-source re-probe ladder for the shape — it is the
    in-repo precedent for "retry a terminal state on a slow ladder without
    starving the healthy path" — including a separate scheduling path so
    re-drives cannot delay first-pass translation. Here that separate path
    is a SECOND `@Scheduled` method on `IngestTranslationWorker` with its
    own poll-interval and its own batch cap, so a re-drive backlog can
    never consume first-pass batch capacity.
  - >-
    The whole ladder must complete INSIDE the partition scan window, which
    is `infochat.partitions.retention-days.post` widened by
    `PartitionScan.PARTITION_SCAN_SLACK` (2 days) — 32 days on the default
    profile but only 16 on `%pi`, where retention is 14 days. A rung
    scheduled past that floor is silently unreachable, so the configured
    first-delay/factor/ceiling/cap must sum to less than the `%pi` window.
    M1-754's own values (ceiling 4d, cap 10) overrun it and MUST NOT be
    copied verbatim.
  - >-
    A post re-hidden to `QUARANTINED` (or advanced to `NEEDS_REVIEW`) by
    `ReEvaluationJob` is NOT re-driven. The first-pass pickup gets this for
    free from its `status = 'RAW'` filter, which
    `IngestTranslationWorker.java:79` records as what "mechanically
    excludes quarantined posts"; the re-drive path runs on posts that have
    long since left `RAW`, so it must carry an explicit equivalent
    exclusion or that control is lost on the new path (engineering-rules
    §10). A post whose status later returns to a releasable state may be
    re-driven again on its remaining rungs.
  - >-
    A successful re-drive writes `title_en`/`body_en` through the SAME
    `persistTranslation` path, so the anchor lands sanitized and atomic
    exactly as a first-pass translation does. No second write path.
  - >-
    Time that drives the backoff decision is read from the injected
    `java.time.Clock` per CLAUDE.md §"Injectable time in decision logic",
    never `Instant.now()` or SQL `now()`. This component both WRITES the
    next-re-drive stamp and READS it back to decide dueness, so both sides
    use the same injected Clock — moving only the read would be an
    app-vs-DB skew bug. `ReprobeScheduler` is the reference implementation
    for the write-and-read-back case; `ReEvaluationJob` (M1-444) for the
    scan window. Tests pin it with
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
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerIT.java
      — every re-drive proof that needs the schema: only `releaseNull`
      stamps the ladder (the other three `persistTranslation` callers
      leave it unset), the QUARANTINED/NEEDS_REVIEW exclusion, dueness
      and the terminal cap under a pinned Clock, and the anchor landing
      through the same `persistTranslation` write.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
      — the pure half only: the backoff ladder's arithmetic and its
      whole-ladder fit inside the `%pi` scan window. This class is a
      no-DB, no-CDI unit test by construction.
  preserves:
    - >-
      Every first-pass translation test, including the English-source
      short-circuit (cursor flipped, no translator call) and the
      two-attempt exhaustion release.
    - >-
      The structured-refusal arm stays terminal: a post released by
      `releaseRefused` is never re-driven, so the "a retry would buy the
      same refusal at twice the tokens" decision survives this change.
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

| `source.language` | `title_en` | `translation_done` | Releasing path | Meaning | Re-drive |
|---|---|---|---|---|---|
| `en` | NULL | TRUE | English short-circuit | nothing to translate | **no** |
| non-`en` | NULL | FALSE | — | not yet attempted / in flight | no — first pass owns it |
| non-`en` | NULL | TRUE | `releaseNull` | **attempts exhausted** | **yes** |
| non-`en` | NULL | TRUE | `releaseRefused` | model refused an action request | **no** — standing decision |
| non-`en` | NULL | TRUE | never ran | pre-V74 row on a source later switched to non-`en` | no — first pass never owned it |
| non-`en` | present | TRUE | `persistTranslation` | translated | no |

Two rows drive the design. The first row is why `title_en IS NULL` alone
is not a predicate: it matches the entire current corpus. Rows 3, 4 and 5
are why NO column predicate works at all — all three are byte-identical
on disk, and only one of them should be re-driven. That is what forces
the durable stamp, and with it the migration.

## Why a durable stamp rather than a derived predicate

The ticket was authored with `migration_touch: false`, assuming the
exhausted set could be recognised from the columns already on `post`.
The start-time self-check falsified that, and the alternative — deriving
each rung's due-time from `fetched_at` and the injected Clock, with no
new state — fails on three counts:

- It cannot separate `releaseNull` from `releaseRefused`. Both write
  `persistTranslation(row, null, null)` and nothing else durable
  distinguishes them, so a derived ladder re-feeds action-request content
  to the model on a schedule.
- It loses work in exactly the burst this ticket exists for. A translator
  outage exhausts a whole cohort at once; they all share one rung window,
  the per-tick batch cap truncates it, the window expires, and the
  remainder skip that rung with no record. A durable stamp leaves them
  due until they are actually served.
- Anchoring on `fetched_at` misfires for a post `ReEvaluationJob`
  re-queued days after fetch: it can exhaust after its whole ladder has
  already elapsed, taking zero re-drives and emitting no signal.

The stamp costs one additive migration. V66 (`tagger_sweep_attempts`,
M1-736) is the same problem solved the same way, and `post` has taken
attempt/timestamp columns four times already (V21, V52, V66 ×2).

## Notes

- `security_relevant: false`: no trust boundary moves, no new external
  input, no authorization surface. The re-drive re-runs an existing
  sanitized path on posts already in the corpus.
- Worth checking at implementation time whether the exhausted set is
  non-empty in the live corpus. If it is empty because no non-English
  source has ever been added, this ticket is preventative — say so in the
  commit rather than implying it repaired live rows.
- V77 does NOT backfill. Rows already sitting in the exhausted state
  before the migration keep an unset stamp and are never re-driven, which
  is correct precisely because they are indistinguishable from the
  refusal and never-attempted rows above. The current corpus is 100%
  English and V74 is recent, so that set is expected to be empty; if the
  live check finds otherwise, raise it rather than adding a backfill
  UPDATE under this ticket.
- The re-drive proofs live in the `*IT`, not the `*Test`. Every
  acceptance item here is DB-backed, and
  `IntegrationTestNamingGuardTest` fails the build on a
  DataSource-injecting `*Test` class — the same constraint that already
  pushed M1-749's `processOne` contract tests into
  `IngestTranslationWorkerIT`. The `*Test` class keeps only what is pure.
  `infochat-collector/src/test/resources/application.properties` is in
  scope for one line: turning the new tick off under `@QuarkusTest` if
  its poll interval is short enough to fire mid-suite (the
  `infochat.fetch.reprobe.poll-interval=off` precedent). If a long
  interval makes that unnecessary, the file stays untouched.
- `migration_touch: true` (set by this refine) serializes the start:
  `docs/process/workflow.md:337` bars a parallel start while any other
  ticket is in flight. M1-758 and M1-759 were live when this was written.
