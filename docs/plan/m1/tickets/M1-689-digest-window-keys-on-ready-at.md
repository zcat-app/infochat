---
id: M1-689
title: "Key post-retrieval windows on ready_at, not published_at"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by:
  - M1-688
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-core/src/main/resources/db/migration/V64__*.sql
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    The window's lower BOUND arithmetic (first-run fallback, zero-post
    boundary advance). That is M1-688, which must land first — this ticket
    changes which COLUMN the bound is compared against, not how the bound
    is computed.
  - >-
    ORDER BY and display order. Posts should still be presented in
    published_at order where they are today; only the window PREDICATE
    moves to ready_at. Reordering the digest narrative is a separate
    product decision.
  - >-
    The chat agent's hybrid retrieval (D58 semantic + lexical RRF arms).
    Those are relevance-ranked, not window-bounded, and are unaffected.
    SearchPostsTool IS in scope — it is a separate window-bounded tool, not
    an RRF arm — but only its window predicate moves; its result shape and
    sort key stay as they are.
  - >-
    published_at as a PROJECTED or STORED field: RetryCommandHandler's
    anchor re-fetch, SaveCommandHandler's saved_post snapshot, and the
    collector-side ingest clamps in PostPersister and NostrEvent. Those
    record or display publication time and are correct as they are.
  - >-
    Backfilling ready_at for pre-existing rows beyond whatever the
    migration needs to make the new predicate correct on existing data.
  - >-
    The re-evaluation and retention jobs (ReEvaluationJob, retention
    sweeps) that legitimately reason about publication time.
acceptance:
  - >-
    The digest collection window compares against the post's ready_at (the
    instant it became available to readers) rather than the source-supplied
    published_at, so a post fetched late with an old published_at is still
    delivered in the digest covering the period it arrived in.
  - >-
    /summary's -w window predicate and the chat searchPosts tool's window
    move to the same column, so every window-bounded surface agrees on what
    "in the last N hours" means. EligiblePostQuery's top-3-active-tags
    query moves with its main query.
  - >-
    Posts with a NULL published_at are reachable by both surfaces. Today
    they are permanently invisible to any window query (post.published_at
    is nullable per V7__joins_post.sql); a new DigestPostCollectorIT case
    covers a NULL-published_at post being collected.
  - >-
    A new EligiblePostQueryIT case covers a post whose published_at
    predates the window but whose ready_at falls inside it being returned
    by /summary.
  - >-
    Any index supporting the old predicate is replaced by one supporting
    the new one, so neither query regresses to a sequential scan on the
    post table.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D19
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-689: Key post-retrieval windows on ready_at, not published_at

## Context

Filed alongside M1-688 from the 2026-07-25 live-testing digest
investigation. M1-688 fixes how the digest's window *bound* is computed;
this ticket fixes which *column* that bound is compared against.

`DigestPostCollector`'s post SQL filters `p.published_at >= ?`
(`DigestPostCollector.java:126` and `:144`). `published_at` is
**source-supplied** — it comes from the feed, not from our pipeline — and it
is **nullable** (`post.published_at` in `V7__joins_post.sql:145`). Two
consequences, both silent:

1. A post fetched hours or days after its stated publication time carries an
   old `published_at`. By the time it clears the evaluation pipeline and
   reaches `status='READY'`, the digest window that would have covered it has
   already advanced past it. It is never delivered — not late, never.
2. A post with a NULL `published_at` fails `published_at >= ?` outright and
   is invisible to every window query for its entire lifetime.

The live data shows how wide the gap is: at the 2026-07-25 morning slot the
group had 1 post with `published_at >= 07:45Z` and 1 with
`ready_at >= 07:45Z`, but across a normal inter-slot period the two columns
diverge by however long fetch + evaluation lag runs — the pipeline keeps
posts in `RAW` long enough that `infochat.eval.stale-raw.age` is 30 minutes.

`ready_at` is the instant the post became available to readers, which is
what a "since the last digest" window actually means. Moving the predicate
there makes the window a statement about our pipeline (which we control and
which is monotonic) rather than about feed metadata (which we do not control
and which is neither monotonic nor non-null).

This is deliberately **not** folded into M1-688: that ticket is a contained
bugfix in one method with no schema change, and this one alters retrieval
semantics on two user-facing surfaces and needs an index change.

## Census

The class is **SQL window predicates comparing against `published_at`**.
Enumerate both halves — the predicate sites, then every file that touches
the column at all, so no site is disposed by omission:

    grep -rn "published_at *>=\|published_at *>\|published_at *<" \
      --include='*.java' infochat-provider/src/main infochat-collector/src/main
    grep -rln "published_at" --include='*.java' \
      infochat-provider/src/main infochat-collector/src/main infochat-core/src/main

| Site | Disposition |
|---|---|
| `infochat-provider/.../summary/EligiblePostQuery.java:218` (`/summary` window) | fix |
| `infochat-provider/.../summary/EligiblePostQuery.java:319` (top-3 active followed tags, same window) | fix — must move with :218 or the top-3 restriction disagrees with the post set it restricts |
| `infochat-provider/.../digest/DigestPostCollector.java:132` (`POSTS_ALL_SQL`) | fix |
| `infochat-provider/.../digest/DigestPostCollector.java:150` (`POSTS_EXPLICIT_SQL`) | fix |
| `infochat-provider/.../chat/tool/SearchPostsTool.java:134` (chat `searchPosts` window) | fix — leaving it makes "last 24h" mean two different things inside one conversation |
| `infochat-provider/.../command/RetryCommandHandler.java:78,353` | out-of-scope: projects the column into the anchor re-fetch, no predicate |
| `infochat-provider/.../command/SaveCommandHandler.java:115,145,341,374` | out-of-scope: snapshots publication time into `saved_post`; the `null` branch at :374 is existing correct handling |
| `infochat-provider/.../summary/ClusterTraversal.java:73` | out-of-scope: javadoc describing input ORDER, which this ticket preserves |
| `infochat-collector/.../outbox/PostPersister.java:194` | out-of-scope: ingest clamp (a source claiming a future date is pinned to `fetched_at`) |
| `infochat-collector/.../stream/nostr/NostrEvent.java:96` | out-of-scope: same ingest clamp, Nostr path |
| `infochat-collector/.../fetcher/{bluesky,reddit}/*ResponseParser.java`, `.../stream/nostr/NostrStreamSource.java` | out-of-scope: parse the field off the wire |

The two ingest clamps are worth reading before implementing: they prove
`published_at` can never be in the FUTURE, which is why the current
predicate looks safe. Nothing stops it being arbitrarily in the past, which
is the defect.

## Acceptance

See the frontmatter. Both the digest collection window and `/summary`'s `-w`
window compare against `ready_at`; late-arriving and NULL-`published_at`
posts become reachable; supporting indexes move with the predicate;
integration tests cover both new cases.

## Out-of-scope

The window's lower-bound arithmetic (M1-688, which blocks this), display
ordering, the chat agent's relevance-ranked retrieval, historical
backfill, and jobs that legitimately reason about publication time. See the
frontmatter.

## Notes

- **Verify the column before designing around it.** This ticket asserts
  `post.ready_at` exists and carries the pipeline-completion instant based
  on `V7__joins_post.sql` and on `NewPostHandler`'s log line
  (`new_post handled: post_id=… ready_at=…`), which reads it back after
  promotion. Re-run that check at `start` rather than trusting this
  paragraph — if `ready_at` turns out to be nullable or set at a different
  pipeline stage than assumed, the predicate needs a `COALESCE` and this
  ticket's shape changes.
- D19 is unaffected in substance: the retrieval stays deterministic SQL and
  the same query on unchanged DB state still returns the same posts. The
  reproducibility property moves from "same feed timestamps" to "same
  pipeline timestamps", which is strictly stronger — `ready_at` cannot be
  rewritten by a source re-publishing an item.
- User-visible consequence worth stating in the spec edit: a `/summary
  -w 24h` will start returning posts whose stated publication date is older
  than 24h, because they arrived within 24h. That is the intended behavior,
  but it is a change in what the window means and the spec should say so
  rather than leaving it as a surprise.
- Highest existing migration at filing time is V62; M1-687 claims V63, so
  this ticket's index migration is V64. Confirm at `start` — the numbers
  shift if either ticket lands out of order.
- Adjacent code: `DigestPostCollector.POSTS_ALL_SQL` /
  `POSTS_EXPLICIT_SQL`, `EligiblePostQuery`'s window clause.
