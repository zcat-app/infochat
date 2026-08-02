---
id: M1-753
title: "RssFeedParser rejects an entire feed that exceeds MAX_ITEMS instead of truncating, so a large legitimate archive feed can never be ingested at all"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParserTest.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    RAISING OR REMOVING THE CAP. `MAX_ITEMS = 1000` is a deliberate
    allocation bound against a hostile feed serving an unbounded item list
    (`RssFeedParser:54-59`). This ticket changes what happens AT the cap,
    not where the cap sits. A diff that edits the constant's value has
    left scope and has weakened a security control.
  - >-
    CHANGING BEHAVIOUR in `BlueskyResponseParser` or
    `RedditResponseParser`. Their caps stay as they are — those parsers
    read a single paginated API response, where a 1000+ item payload IS
    anomalous, whereas an RSS archive feed legitimately grows past it
    forever. They are in files_scope for their PARITY COMMENTS ONLY
    (`BlueskyResponseParser:31`, `RedditResponseParser:32`, both of which
    currently claim parity with `RssFeedParser.MAX_ITEMS`), which become
    false the moment RSS diverges. Comment-only edits in those two files;
    any executable line change there has left scope.
  - >-
    The D42 failure ladder and the parked-source recovery question
    (M1-752). `Open AI` is evidence in both tickets but the defects are
    independent: this one would have kept the source dark even with a
    perfect recovery rung, because every single fetch fails identically.
  - >-
    Re-fetching or backfilling the posts this feed never ingested. The fix
    applies to future fetches; historical catch-up is bounded by whatever
    the feed still serves and is not in scope.
  - >-
    `RssFetcher`'s HTTP layer, the response size cap, and the D42 failure
    counting. The fetch succeeds today — this is purely a parse-stage
    defect.
acceptance:
  - >-
    A feed carrying MORE than `MAX_ITEMS` entries yields the first
    `MAX_ITEMS` parsed posts instead of raising. Today both loops raise
    `RssFeedParseException("feed item count exceeded 1000")` — the RSS
    `<item>` path at `:134-137` and the Atom `<entry>` path at `:202-205`
    — which discards the whole payload, including the ~1000 items that
    parsed fine.
  - >-
    BOTH loops change. Fixing only the RSS path leaves the identical
    defect in the Atom path, and the ticket's own evidence feed is RSS, so
    an Atom regression would not show up in manual verification.
  - >-
    THE ALLOCATION BOUND IS PRESERVED AND THIS IS THE LOAD-BEARING
    REQUIREMENT. The parser must stop consuming the stream once it holds
    `MAX_ITEMS` posts — it must not parse the remainder and discard it,
    and must not accumulate past the cap before trimming. A hostile feed
    serving an unbounded item list must still cost O(MAX_ITEMS) memory,
    exactly as the current throw guarantees. A fix that collects
    everything then calls `subList` has removed the control this cap
    exists for while appearing to pass.
  - >-
    Truncation is not silent — and the signal does NOT come from a log
    line invented inside the parser. `RssFeedParser` is a static utility
    with no logger that receives a `long dispatchKey` and never the source
    UUID (`:66`), so it cannot name the source it is clipping. The
    established in-repo path for "a fetcher hit a cap" is
    `PaginationSaturationTracker.signalCapHit()`: a static ThreadLocal the
    scheduler drains via `consumeCapHit()` immediately after `fetch()`
    returns, existing precisely because the Fetcher SPI returns only the
    post list. The truncation signal travels that path or an explicitly
    argued equivalent. Whether it reuses the pagination-saturation counter
    or gets its own is the implementer's call — note that an archive feed
    will truncate on EVERY tick forever, so a streak-based notifier fires
    once on transition, which may be the desired behaviour or may be
    conflating two conditions.
  - >-
    The comment at `RssFeedParser:54-59` is rewritten to describe
    truncation rather than rejection, and states WHY the two behaviours
    differ in risk: rejecting an over-cap feed is not a stricter security
    posture than truncating it, because both bound the allocation
    identically while only one of them ingests the feed.
  - >-
    The parity comments in `BlueskyResponseParser:31` and
    `RedditResponseParser:32` are corrected to record that RSS
    deliberately diverges, and why (archive feed vs single paginated
    response). Leaving them claiming parity is a false comment about a
    security control.
test_plan:
  adds:
    - >-
      NOTE: there is NO existing RSS item-cap test. Reddit and Bluesky
      each have one (`RedditResponseParserItemCapTest`,
      `BlueskyResponseParserTest`); RSS was never given the parity
      coverage its constant's comment claims. Every cap case below is
      new, and that absence is the likeliest reason the reject-vs-truncate
      asymmetry survived this long.
    - >-
      An RSS feed of `MAX_ITEMS + 1` items returns exactly `MAX_ITEMS`
      posts and does not throw.
    - >-
      The same for an Atom feed of `MAX_ITEMS + 1` entries — the Atom
      loop is a separate code path with its own copy of the check.
    - >-
      The exactly-`MAX_ITEMS` boundary: a feed carrying precisely the cap
      parses cleanly and returns all of them. `RssFeedParser:57-59`
      documents this behaviour today but nothing pins it.
    - >-
      A case pinning WHICH items survive, so the truncation point is a
      specified behaviour rather than an accident of loop order.
  preserves:
    - >-
      `RssFeedParserTest`'s existing 15 cases, none of which touch the
      item cap: the guid/link identifier-precedence group, the
      pubDate/published Instant-parsing pair, the RSS and Atom fixture
      counts, and the raise/tolerate group
      (`parseRaisesOnUnrecognizedRootElement`,
      `parseRaisesOnAllWhitespaceBody`, and the two leading-whitespace
      tolerance cases from M1-502).
    - >-
      `RedditResponseParserItemCapTest` and `BlueskyResponseParserTest` —
      their over-cap-REJECTS assertions stay green and must NOT be
      retargeted onto the new truncating behaviour. They pin the
      deliberate divergence this ticket creates, so retargeting them
      would erase the only evidence that the divergence was intentional.
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D42
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-753: an over-cap feed is discarded whole, not clipped

## Context

Found on the prod deployment 2026-08-02 while verifying an unrelated
operator fix. The `Open AI` source (`https://openai.com/news/rss.xml`) had
been in `status='failed'` since 2026-07-06 with **`last_success_at` NULL —
it has never once been ingested since it was seeded.**

The endpoint is fine. It returns HTTP 200, from inside the collector
container, under both curl's and the JDK client's User-Agent. The fetch
succeeds every time; the parse throws every time:

```
2026-08-02 21:53:57 WARN FetchScheduler tick failed for source
  uuid=c2c1e87d-… (dispatch=25):
  RssFeedParseException: feed item count exceeded 1000
```

The feed currently carries **1105 `<item>` elements**. `MAX_ITEMS` is
1000, and the check at `:134` raises rather than stopping, so all 1105
items are thrown away — including the 1000 that parsed without incident.

This is worth stating plainly because it inverts the usual failure
intuition: **the more content a source publishes, the less of it we
ingest, until at 1001 items we ingest none of it.**

## Why the cap is right and the throw is not

The cap itself is sound and is not under discussion. `:54-59` explains it:
a normal feed publishes 10–500 items, 1000 is an order of magnitude above
legitimate use, and the bound exists so a hostile feed serving an
unbounded item list cannot drive allocation. That reasoning holds.

What does not follow is discarding the parsed prefix. Against a hostile
feed, throwing and truncating are equivalent — both stop at 1000 items and
bound the allocation identically. Against a legitimate archive feed they
are opposite: one ingests the newest 1000 posts, the other ingests
nothing, forever, and burns the D42 failure ladder doing it. The throw
buys no security over truncation and costs the whole source.

Note the interaction with M1-752: because every fetch fails identically,
this source re-parks after five ticks no matter what recovery policy that
ticket lands on. Re-enabling it — which the operator did on 2026-08-02 —
resets the counter and changes nothing. The two defects are genuinely
independent and this one is not fixed by that one.

## Census

The class is "every site that enforces or documents the 1000-item cap".
Enumerated mechanically, re-runnable:

```
grep -rn "MAX_ITEMS" --include=*.java infochat-collector/src
```

34 hits across 5 files. Disposition:

| file | cap sites | this ticket |
|---|---|---|
| `rss/RssFeedParser.java` | `:60` const, `:134` RSS loop, `:202` Atom loop | **CHANGES** — both loops truncate; `:54-59` comment rewritten |
| `bluesky/BlueskyResponseParser.java` | `:38` const, `:66` check | comment `:31` only |
| `reddit/RedditResponseParser.java` | `:39` const, `:63` check | comment `:32` only |
| `bluesky/BlueskyResponseParserTest.java` | over-cap + boundary cases | unchanged, must stay green |
| `reddit/RedditResponseParserItemCapTest.java` | over-cap + boundary cases | unchanged, must stay green |

The census output is itself the sharpest evidence for this ticket: **the
two parsers whose cap is a copy of the RSS one both have a dedicated cap
test; the RSS parser that they cite as the original has none.** The
authoritative constant is the untested one.

## Approach

Stop the loop instead of raising, in both the RSS `<item>` and Atom
`<entry>` paths. The condition is already evaluated after each successful
per-item parse and already knows the count, so the change is the branch
body, not the control flow around it.

The one thing to get right is that the parser must stop *consuming*, not
merely stop *collecting* — see the allocation-bound acceptance item. A
`break` out of the read loop satisfies this; parsing on and trimming
afterwards does not, and would quietly remove the protection the cap
exists to provide while turning the tests green.

## Ordering caveat, deliberately surfaced

Truncating keeps the first `MAX_ITEMS` items in document order. RSS and
Atom conventionally publish newest-first, so in practice that is the
newest 1000 — but it is a convention, not a guarantee, and a feed ordered
oldest-first would have its newest items clipped. The acceptance criteria
require the surviving set to be pinned by a test so this is a stated
behaviour rather than an accident; if a future source is found publishing
oldest-first, that is a follow-up, not a reason to sort 1000+ items here.
