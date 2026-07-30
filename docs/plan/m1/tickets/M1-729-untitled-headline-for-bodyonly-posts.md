---
id: M1-729
title: "Bluesky posts render a headline of \"untitled\" while their text sits in body"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope: []
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
      non-empty — the Bluesky shape per
      `BlueskyResponseParser.java:115`) produces a headline derived from
      its body rather than the `"untitled"` sentinel, asserted at
      whichever layer the chosen route lands in.
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
      M1-714's `DisplayHeadline` behaviour and its tests — this ticket
      changes the helper's INPUT, not the helper.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
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

## Notes

- Retention bounds the urgency but does not remove it: the `"untitled"`
  rows are written continuously from here on, so unlike M1-714's legacy
  surface this one does not age out.
- Whichever route wins, the `"untitled"` literal should end up as a
  named constant rather than a bare string in a method body.
