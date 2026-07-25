---
id: M1-693
title: "Normalize post.title at ingest: empty, content-as-title, and unbounded length"
status: done
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/IngestTextNormalizer.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterTest.java
  - docs/spec/schema.md
  - docs/design/02-schema.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The render sites. ClusterBlockRenderer, DigestRenderer and
    SummaryProseGenerator.degradedProseFor all print post.title verbatim
    and must keep doing so — the fix belongs at ingest, where the value
    is written once, not at three renderers that would each need their
    own cap and would still disagree with searchPosts and the chat tools.
  - >-
    Backfill of existing rows. This ticket normalizes the write path
    only. Whether to rewrite already-stored titles is a separate
    migration decision with its own destructive-change review.
  - >-
    The per-fetcher decision about what SHOULD fill title for a
    titleless-by-design source (Bluesky, Nostr). Changing
    BlueskyResponseParser to synthesize a title from the body is a
    source-semantics change, not a normalization one; this ticket makes
    the empty case render acceptably wherever it lands.
  - >-
    post.body length. Bodies are already bounded by their consumers
    (prompt assembly, GetPostTool's TRUNCATION_MARKER); only title is
    unbounded end-to-end.
acceptance:
  - >-
    post.title is length-bounded at ingest. A title longer than the cap
    is stored truncated with a trailing ellipsis marker; a title at or
    under the cap is stored byte-identical to today. The cap is a named
    constant with a WHY comment, not an inline literal.
  - >-
    An empty or whitespace-only title (BlueskyResponseParser:115 passes
    null; PostPersister:175 coerces null to "") no longer produces a
    blank headline downstream. The chosen representation is applied once
    at ingest so every consumer — /summary, the digest, searchPosts, the
    chat tools — sees the same value.
  - >-
    Truncation runs AFTER IngestTextNormalizer.stripMetadataField, so a
    bidi/zero-width/control strip can never be split mid-sequence by the
    cut, and the cut never lands inside a surrogate pair.
  - >-
    PostPersisterTest covers: over-cap title truncated, at-cap title
    untouched, null title, whitespace-only title, and a title whose
    codepoint at the cut boundary is a surrogate pair.
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterTest.java
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Ingest pipeline (security side)
  - docs/spec/schema.md §Posts and derivatives
decision_refs:
  - D17
  - D19
reviews:
  - round: 1
    date: 2026-07-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 282
      removed: 17
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-25
    verdict: CLEAN
    base: 8b2c41d13ca0147491d7493015a670cf3ecbc771
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-693-2026-07-25.md
    out_of_model_count: 2
    note: |
      Pre-commit --in-progress audit at the /m1-tick run gate. CLEAN — the
      title-normalization diff violates no threat-model promise. Two out-of-model
      advisories (neither warrants a follow-up ticket): pre-M1-693 rows are not
      backfilled (already this ticket's explicit out_of_scope), and NFKC is not
      applied to title at the write boundary (pre-existing, unchanged; no promise
      violated — NFKC is body-only at Stage 1, title command-shape redaction is
      render-side). Full verdict in verdict_file.
clarity_check:
  date: 2026-07-25
  verdict: PASS
  warnings:
    - >-
      test_plan listed PostPersisterTest.java under 'modifies' but the file
      does not exist on main; moved to 'adds' (the file is already in
      files_scope; intent — a unit test for the five title-normalization
      cases — is unchanged).
escalation_reason:
---

# M1-693: Normalize post.title at ingest

## Context

Raised during M1-687's ticket-readiness self-check (2026-07-25) and
deliberately deferred out of it: M1-687 is a render-side capping ticket,
and this is an ingest-side normalization gap that predates it and affects
every surface equally.

`post.title` is `TEXT NOT NULL` (`V7__joins_post.sql:141`) with **no length
bound anywhere on the path**. Ingest applies only a bidi/zero-width/control
strip (`PostPersister.java:162-175` → `IngestTextNormalizer.stripMetadataField`,
M1-433). Every renderer then prints it verbatim.

Three distinct defects share that one gap:

| Source kind | Bootstrap count | What lands in `title` |
|---|---|---|
| `nitter` | 28 of 79 | the feed's `<title>`, which for nitter/xcancel **is the tweet text** — unbounded |
| `bluesky` | 3 of 79 | `null` (`BlueskyResponseParser.java:115`), coerced to `""` at `PostPersister.java:175` — renders as a blank headline |
| `rss` / `youtube` / `odysee` | 48 of 79 | a real headline — the case the renderers were designed around |

Observable consequences today:

- `SummaryProseGenerator.degradedProseFor:196` emits
  `<title> — <url> (uid p-…)` per post. For a Bluesky post that is
  ` — https://… (uid p-…)` with nothing before the dash. For a nitter post
  it is an entire tweet on one line.
- `ClusterBlockRenderer:87` emits `sanitize(first.title())` as the cluster
  headline, with the same two failure shapes.

M1-687 caps the *number* of rendered lines per category; it does not and
should not cap their *length*. A category of 12 nitter posts is still 12
paragraph-length lines after M1-687 ships.

## Why this is a separate ticket

The natural single fix — truncating inside
`SummaryProseGenerator.degradedProseFor` — is shared by `DigestRenderer`,
so it changes the scheduled group digest's bytes. M1-687's `out_of_scope`
forbids exactly that ("`DigestRenderer` itself may only be extended in ways
that leave `renderSections`' existing output byte-identical for the digest's
call site"), and truncation traces to none of M1-687's acceptance items.
Per `CLAUDE.md` §"Better alternatives surface as proposals, not scope
expansion", the widening lands here.

Fixing it at ingest rather than in `degradedProseFor` is also the better
answer on the merits: `searchPosts`, `GetPostTool` and the chat prompt
assembly read `post.title` too, and a renderer-local cap would leave those
unbounded.

## Census

The class is "every site that reads a stored post title". Enumerate it
mechanically — both spellings, since the value is read as a record accessor
in the render layer and as a column in the query layer:

```
grep -rnE "\.title\(\)|getString\(\"title\"\)" --include=*.java \
  infochat-provider/src/main infochat-collector/src/main infochat-core/src/main
```

As of 2026-07-25 that returns the sites below. **Every one is disposed by
normalizing at the write path** — none is modified by this ticket, which is
the argument for fixing it at ingest rather than per-renderer.

| Site | Reads via | Disposition |
|---|---|---|
| `PostPersister.java:174` | `NormalizedPost.title()` | **the fix** — sole write path |
| `ClusterBlockRenderer.java:87` | record | inherits |
| `SummaryProseGenerator.java:175,196` | record | inherits (prompt + degraded prose) |
| `DegradedDigestRenderer.java:38` | record | inherits |
| `CategoryRollupGenerator.java:181` | record | inherits |
| `EligiblePostQuery.java:291` | column | inherits |
| `DigestPostCollector.java:112` | column | inherits |
| `RetryCommandHandler.java:352` | column | inherits |
| `SearchPostsTool.java:183` | column | inherits |
| `SemanticSearchTool.java:231` | column | inherits |
| `GetPostTool.java:82` | column | inherits |
| `ListSavesTool.java:130` | column | inherits |
| `SaveCommandHandler.java:343`, `SavedCommandHandler.java:238` | column | inherits (reads `saved_post.title`, a copy taken at save time — pre-fix rows keep their stored value, see the backfill exclusion in `out_of_scope`) |
| `EmbeddingWorker.java:486`, `TaggerWorker.java:391`, `ClassifierWorker.java:303`, `EntityExtractorWorker.java:299` | column, in-pipeline | inherits; all four already null-coerce, so a bounded title only shortens their prompts |
| `NitterFetcher.java:86-87` | record, **pre-persist** | NOT affected — matches the xcancel placeholder title before `PostPersister` runs. A cap shorter than `"RSS reader not yet whitelisted!"` (31 chars) would break the D42 placeholder detection; the chosen cap must exceed it. |

The last row is the one live coupling — verify it explicitly.

## Notes

- **Cap value.** `CompressCommandHandler.java:272` uses 200 chars as an
  existing in-repo precedent for a prose-summary cut. A headline cap wants
  to be shorter than a tweet's 280 so the common nitter case actually
  truncates. Pick one value, name it, and record the WHY.
- **Ordering matters.** `stripMetadataField` removes bidi/zero-width
  controls; truncating first could leave a dangling override that the strip
  would otherwise have removed. Truncate after.
- **Surrogate pairs.** `String.substring` cuts by `char`, not codepoint —
  `ThrottledAdminNotifier.java:138` has the same hazard and is worth reading
  before choosing the cut helper.
- Adjacent code: `IngestTextNormalizer`, `PostPersister.persist`,
  `ContactIds.java:109-119` (existing prefix+ellipsis helper shape).
