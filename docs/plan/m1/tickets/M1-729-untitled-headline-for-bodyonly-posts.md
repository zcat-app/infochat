---
id: M1-729
title: "Bluesky posts render a headline of \"untitled\" while their text sits in body"
status: done
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/IngestTextNormalizer.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  - docs/plan/m1/tickets/M1-729-untitled-headline-for-bodyonly-posts.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The display-side headline derivation itself (`DisplayHeadline`, the
    three render surfaces, truncation, sanitize ordering). That is
    M1-714, which ships independently; this ticket only decides what
    value the headline derivation is given for a body-only post.
  - >-
    Backfilling or re-normalizing existing `post` rows. Whatever
    representation this ticket picks applies to newly written rows;
    rows already carrying `"untitled"` age out with the 30-day `post`
    retention (14 on `pi`). A migration that rewrites stored source
    content is a separate decision and is not taken here.
  - >-
    Re-embedding or re-tagging existing rows. If the chosen route
    changes what `TaggerWorker` / `ClassifierWorker` / `EmbeddingWorker`
    see, that applies going forward only; recomputing historical
    embeddings is out of scope.
  - any other module beyond the one the chosen route requires
acceptance:
  - >-
    A post whose upstream carries no title but does carry body text no
    longer presents `"untitled"` as its user-visible headline in
    `/summary`, the periodic digest, or the degraded digest.
  - >-
    The chosen layer is recorded with its reasoning. The two candidates
    are (a) ingest — stop writing the `"untitled"` sentinel when body
    text is available, deriving the stored title from the body; and
    (b) display — treat the sentinel as "no title" and fall back to the
    body, which requires promoting the literal to a shared constant.
    Route (a) changes the input to three LLM/embedding consumers and
    route (b) re-decides M1-693's once-at-ingest principle, so the
    decision must be argued, not assumed.
  - >-
    If route (a) is chosen: the effect on `TaggerWorker.java:391`,
    `ClassifierWorker.java:303` and `EmbeddingWorker.java:486` is stated
    explicitly, because a body-derived title duplicates body text into
    the tagger prompt, the classifier input and the embedding vector.
    A test pins whatever de-duplication or exclusion is decided.
  - >-
    If route (b) is chosen: the sentinel is a single named constant with
    a WHY comment, referenced by both the writer and the reader — no
    magic string is duplicated across modules.
  - >-
    `PostPersisterTest`'s existing null-title and whitespace-only-title
    assertions are updated in step with whatever representation is
    chosen, not deleted.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      Coverage that a body-only post (upstream title null, body
      non-empty) produces a headline derived from its body rather than
      the `"untitled"` sentinel, asserted at whichever layer the chosen
      route lands in. The shape is not Bluesky-only:
      `BlueskyResponseParser.java:112` and `NostrEvent.java:100` both
      pass a null title, and `RedditResponseParser.java:128` uses
      `asText()` with no default, so an absent Reddit title yields `""`
      and normalizes to the same sentinel. The assertion is written
      against the shape, not against one fetcher.
    - >-
      Coverage that a post with neither title nor body still resolves to
      a stable representation and does not regress into a blank
      headline.
  preserves:
    - >-
      M1-693's guarantees on `post.title`: never null, stripped of
      bidi/zero-width/ISO-control/U+2028/U+2029, and bounded at
      `IngestTextNormalizer.TITLE_MAX_LENGTH` with a trailing ellipsis.
    - >-
      M1-714's `DisplayHeadline` guarantees and every one of its existing
      tests: title-wins-over-body, the flatten → sanitize → truncate
      order, the one-field sanitize unit, the surrogate/marker-safe cut,
      and the empty-string return for a post with no renderable text.
      Route (b) adds one condition to `headlineSource`; it changes none
      of the above. (This item read "changes the helper's INPUT, not the
      helper" at filing, which presumed route (a); corrected at start.)
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 212
      removed: 27
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-07-30
  verdict: WARN
  warnings:
    - >-
      lint FILES-SCOPE-COVERAGE x2 — test_plan.adds entries are prose
      rather than paths, and files_scope was empty by design at filing
      ("the file set follows from the route"). Filled at start per the
      ticket's own instruction; the adds now map onto PostPersisterTest
      and DisplayHeadlineTest, both in files_scope.
    - >-
      lint CENSUS-PRESENT-IF-CLASS-SCOPED — not class-scoped. The
      enumeration is one literal with one write site and one read site,
      verified by `grep -rn '"untitled"' --include=*.java`, which returns
      PostPersister (2 javadoc, 1 return) and PostPersisterTest (2
      assertions) and nothing else.
    - >-
      self-check: the route decision the ticket owns was resolved with
      the user before any code was written — route (b), display-side
      sentinel match. Reasoning recorded in the body.
    - >-
      self-check: blast radius is wider than the title states.
      `NostrEvent.java:100` also passes a null title, and
      `RedditResponseParser.java:128` yields "" for an absent title.
      test_plan.adds widened to assert against the shape rather than one
      fetcher. No acceptance change — item 1 was already source-agnostic.
    - >-
      self-check: test_plan.preserves presumed route (a); corrected.
  blockers: []
escalation_reason:
---

# M1-729: Bluesky posts render a headline of "untitled"

## Context

M1-693 (landed 2026-07-25) normalizes `post.title` once at ingest, in
`PostPersister.normalizeTitle` — the sole `INSERT INTO post` in the
codebase. A blank title becomes the literal `"untitled"`:

```java
String stripped = IngestTextNormalizer.stripMetadataField(
    rawTitle == null ? "" : rawTitle);
if (stripped.isBlank()) {
    return "untitled";
}
```

`BlueskyResponseParser.java:115` still passes `null` for the title and
puts the post text in `body`. So every Bluesky post ingested since
2026-07-25 stores `title="untitled"` with its real content in `body`.

M1-693's acceptance said a blank title "no longer produces a blank
headline downstream," and that is literally true — but for a reader the
cluster block still leads with nothing identifying it. The defect
changed shape from an empty line to the word `untitled`; it did not
close.

This was found while auditing M1-714 (`/redteam` 2026-07-30,
out-of-model item 2, then developer follow-up on the fetcher).

## Why M1-714 does not cover it

M1-714 adds a display-side body fallback, but its condition is
`title.isBlank()`. Against a stored `"untitled"` that predicate is
false, so the fallback cannot fire for any row written after
2026-07-25. M1-714 still earns its keep — it consolidates three
drifting render surfaces, bounds the headline, and closes a sanitizer
ordering bug — and it ships independently of this ticket.

## The decision this ticket owns

Two routes, neither free:

**(a) Ingest.** Stop writing the sentinel when body text exists; derive
the stored title from the body. Matches M1-693's stated principle
("applied once at ingest so every consumer sees the same value") and
fixes every consumer at once — `/summary`, digests, `searchPosts`, the
chat tools. But `post.title` feeds `TaggerWorker.java:391`,
`ClassifierWorker.java:303` and `EmbeddingWorker.java:486`, so a
body-derived title duplicates body text into the tagger prompt, the
classifier input and the embedding vector. Today's `"untitled"` is a
uniform constant contributing negligible noise; that would stop being
true.

**(b) Display.** Treat the sentinel as "no title" and fall back to the
body in `DisplayHeadline`. Leaves the embedding/tagger inputs exactly
as they are, which is the conservative choice. But it spans
`infochat-core` + `infochat-collector` + `infochat-provider` purely to
share the sentinel constant, and it couples a renderer to an ingest
sentinel — a value the renderer must now never diverge from.

The ticket is filed with `files_scope: []` deliberately: the file set
follows from the route, and the route is the decision. Fill both in at
`start`.

## Route decision: (b) display, taken 2026-07-30 at `start`

**The defect is a collision between two shipped tickets, not a missing
feature.** M1-693 (2026-07-25) decided the titleless-post problem
belongs at ingest and stored the `"untitled"` sentinel; its
`out_of_scope` argued the render sites explicitly — "the fix belongs at
ingest, where the value is written once, not at three renderers that
would each need their own cap." M1-714 (2026-07-30) then built exactly
that: a shared display-side derivation with its own cap and a body
fallback. Each is defensible alone; together the sentinel disables the
fallback, because `"untitled"` is not blank.

**What decides it is the consumer split.** Sorting every reader of
`post.title` by whether the body travels alongside it:

- **Body already present (7)** — `TaggerWorker:391`,
  `ClassifierWorker:303`, `EntityExtractorWorker:299`,
  `EmbeddingWorker:486`, `SummaryProseGenerator.buildPrompt:181`,
  `CategoryRollupGenerator.buildPrompt:199`, `GetPostTool:62`. A
  titleless post costs these nothing; the content is already in the
  input.
- **Title is the only content shown (5)** — `SavedCommandHandler:81`,
  `ListSavesTool:89`, `SearchPostsTool:147`, `SemanticSearchTool:150`,
  `GetReferencesTool:91`. These read `title` from SQL without the body.
- **Behind `DisplayHeadline` (3)** — the two digest renderers and
  `degradedProseFor`. Already solved by M1-714 once the sentinel stops
  blocking the fallback. These three are exactly acceptance item 1's
  surfaces.

Route (a) writes body text into all 7 of the first group to serve the
second. In both prompt builders the body is appended on the line
directly after the title, so the summarizer would read the post's
opening twice verbatim; `EmbeddingWorker.buildInputText` composes
`title + "\n\n" + body[0:800]`, which for a Bluesky post (300-char
platform cap) is close to the whole body twice. De-duplicating that is
7 sites, not the 3 acceptance item 3 anticipated — past `files_budget:
8`, and a quality regression in the meantime.

A third route was tested and rejected: store `""` and let M1-714's
existing `title.isBlank()` fallback fire. It is the principled shape —
it matches M1-723's rule that a column records what the source
reported, and it restores the empty-title input
`EmbeddingWorker.buildInputText`'s javadoc documents as the designed
case (the pre-M1-693 write path stored `""`; `668864f7^` binds
`stripMetadataField(title)` with no blank branch). But it regresses the
5 title-only surfaces from `untitled` to blank, which is the defect
M1-693 shipped to close.

**Route (b) therefore fixes acceptance item 1's three surfaces and
regresses none.** Its cost is the coupling the ticket predicted: a
renderer that must know an ingest sentinel. That is accepted here as
step one of two — a follow-up gives the 5 title-only surfaces the same
body fallback, after which the sentinel can be deleted outright and the
column becomes truthful. Sequenced this way no surface is ever blank;
done as one ticket, 5 of them would be.

## Notes

- Retention bounds the urgency but does not remove it: the `"untitled"`
  rows are written continuously from here on, so unlike M1-714's legacy
  surface this one does not age out.
- Whichever route wins, the `"untitled"` literal should end up as a
  named constant rather than a bare string in a method body.
