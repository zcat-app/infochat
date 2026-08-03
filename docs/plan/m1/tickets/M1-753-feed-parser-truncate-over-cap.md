---
id: M1-753
title: "RssFeedParser rejects an entire feed that exceeds MAX_ITEMS instead of truncating, so a large legitimate archive feed can never be ingested at all"
status: done
created: 2026-08-02
last_updated: 2026-08-03
blocked_by: []
files_budget: 7
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParserTest.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/PaginationSaturationTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerSaturationIT.java
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
    defect. The redteam refine widens `files_scope` to `FetchScheduler`
    for the truncation-signal drain and notification ONLY; the D42
    ladder, `recordFailure`/`recordSuccess`, and the fetch-failure
    notification stay untouched.
  - >-
    CHANGING `recordTick`'s STREAK-RESET SEMANTICS. Added by the redteam
    refine. Making a non-saturated tick stop clearing the streak would
    "fix" the oscillation finding by redefining pagination saturation for
    Bluesky and Reddit, breaking a spec-anchored behaviour
    (`PaginationSaturationTracker:30-36`) and the IT that pins it. The
    truncation signal must be SEPARATE from the pagination-saturation
    signal, not a loosening of it.
  - >-
    RE-WORDING the existing `fetch_saturation` notification message
    (`FetchScheduler:594-598`). Added by the redteam refine. Once
    truncation has its own signal, no RSS-family source reaches that
    message, so its wording is correct as it stands and editing it is
    scope drift.
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
  - >-
    THE TRUNCATION SIGNAL MUST NOT DEPEND ON A RESETTABLE CONSECUTIVE
    STREAK, AND MUST LEAVE A DURABLE RECORD. Added by the redteam
    refine (finding AUDIT-EVASION/low, verdict file
    `docs/plan/m1/redteam/M1-753-2026-08-03.md`). The first
    implementation routed truncation through
    `PaginationSaturationTracker.signalCapHit()`, whose notification is
    gated on `recordTick` reaching `saturation-threshold` CONSECUTIVE
    saturated ticks — and `:86-88` deletes the streak on any
    non-saturated tick. A feed oscillating across the cap
    (1001/1001/999, repeating) therefore never reaches the threshold and
    never notifies. This needs no adversary: any feed hovering near
    `MAX_ITEMS` oscillates naturally. Verified during the refine that
    NOTHING else records the drop — `capHitCount()` is read only by a
    test, there are no fetch-path metrics, `posts.size()` is never
    logged, and the success path actively CLEARS the D42 counter. So
    when the streak does not fire, the fact that the collector discarded
    content is recoverable from nothing.
  - >-
    The durable record is what makes truncation auditable, so it is the
    load-bearing half of "truncation is not silent". `ThrottledAdminNotifier.notifyOnce`
    already provides exactly this shape — it persists a row in
    `admin_notification_state` AND coalesces per
    `(notification_key, window)` — so firing it on EVERY truncating tick
    yields a durable record without alert spam and without any streak
    gate. Key it per source (`feed_truncated:<uuid>`, mirroring the
    existing `fetch_saturation:<uuid>` and `fetch_failure_ladder:<uuid>`
    forms) so the key set stays bounded by the source table: the
    notifier's key cap exists because that table grows monotonically and
    only a DBA TRUNCATE recovers it, so a per-tick-unique key would be a
    new unbounded-growth vector and is forbidden.
  - >-
    PAGINATION-SATURATION SEMANTICS FOR BLUESKY AND REDDIT MUST NOT
    CHANGE. The tempting wrong fix is to stop `recordTick` resetting the
    streak on a non-saturated tick. That is a documented,
    spec-anchored behaviour ("consistently saturates ... across multiple
    ticks" reads consecutive, `PaginationSaturationTracker:30-36`) shared
    with the two paginating fetchers, and `FetchSchedulerSaturationIT`
    pins it. Truncation gets its OWN signal distinct from pagination
    saturation; it does not redefine the shared one.
  - >-
    A CONSEQUENCE TO CARRY, NOT AN OPTIONAL EXTRA: once truncation has
    its own signal, no RSS-family source reaches the pagination-saturation
    notification at all (no RSS-family fetcher calls `signalCapHit()` —
    only `RedditFetcher:113` and `BlueskyFetcher:107` do). That is the
    correct end state, and it also removes the wart the first
    implementation introduced, where an RSS source would receive the
    pagination message's remedy advice ("raising the per-source page cap
    or increasing fetch frequency") despite RSS having no pagination and
    no page cap. Do NOT edit that message's text — leaving it untouched
    is correct once RSS no longer reaches it.
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
    - >-
      THE OSCILLATION CASE, which is the one the redteam refine exists
      for: a source that truncates on ticks 1 and 2, does NOT truncate on
      tick 3, then truncates again must still produce its durable
      truncation record. A test that only drives consecutive truncating
      ticks passes against the streak-gated implementation the audit
      rejected, so it does not discriminate and does not close the
      finding.
    - >-
      A case pinning that a truncating tick leaves an operator-recoverable
      record — the persisted `admin_notification_state` row, asserted the
      way `FetchSchedulerSaturationIT` already asserts the
      `fetch_saturation` key rather than by scraping a log line.
    - >-
      A case pinning that pagination-saturation behaviour for a
      paginating (Bluesky/Reddit-shaped) source is UNCHANGED — the
      streak-gated `fetch_saturation` transition still fires exactly on
      the threshold tick and still resets on a non-saturated tick. This
      is the regression guard for the forbidden fix.
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
reviews:
  - round: 1
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 1152
      removed: 31
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-03
    category: DOS
    severity: medium
    promise: |
      docs/spec/security.md §Failure handling — "**Fetcher failure** (HTTP
      error, connection timeout, feed parse failure on an HTTP-shaped
      source) → retry on the next scheduled tick (decision D42). After *N*
      consecutive per-source failures (profile-driven), the source `status`
      transitions to `'failed'` and the scheduler skips it; a throttled
      admin notification is sent with the error class and source id."
    gap: |
      The diff converts an over-cap feed from a hard parse failure into a
      silent partial ingest on the document-order prefix, and the tick now
      records SUCCESS — FetchScheduler:575 recordSuccess() zeroes
      consecutive_failures and refreshes last_success_at on every
      truncating tick. The source is indistinguishable in DB state and in
      /list-sources from a fully-ingesting healthy source while a slice of
      its content is discarded every tick, indefinitely. The removed throw
      was the D42 on-ramp; nothing in the diff carries a "this source is
      degraded" signal across except the streak-gated saturation counter
      (see the low finding). NitterFetcher:63-76 already treats this exact
      shape as a defect worth a deliberate guard ("a dead source would look
      healthy", M1-588); the diff reintroduces that shape one layer down,
      at the parser, for all four RSS-family kinds (rss, nitter, youtube,
      odysee).
    repro: |
      A multi-author RSS/Atom feed legitimately carries >1000 entries under
      the 5 MiB SSRF body cap. Every tick, RssFeedParser stops at the
      1000th entry and returns only the document-order prefix; entries
      below it never reach Stage 1, the outbox, or any user-facing query.
      FetchScheduler records SUCCESS, so the source shows active with zero
      consecutive failures and a fresh last_success_at. Before the diff the
      same input failed every tick, drove the D42 ladder to
      status='failed', and fired fetch_failure_ladder naming the source.
      NOTE (disputed, see verdict file §disposition): the audit's
      adversarial framing overstates the delta — before the diff the same
      flooding adversary suppressed EVERY publisher on the feed completely
      and permanently, so truncation strictly reduces suppression power.
      The undisputed core is the loss of the degradation signal.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-08-03
    category: AUDIT-EVASION
    severity: low
    promise: |
      docs/spec/security.md §Failure handling — the durable,
      operator-visible record the over-cap path used to produce, and the
      general commitment that degradation is observable.
    gap: |
      The sole replacement signal for a truncating source is gated on a
      counter that resets, and it leaves no durable row when it does not
      fire. PaginationSaturationTracker:85-93 returns true ONLY on the tick
      where the CONSECUTIVE saturated streak equals
      infochat.fetch.saturation-threshold (default 3), and :86-88 deletes
      the streak on any non-saturated tick. A single under-cap tick
      interleaved between over-cap ticks resets the counter to zero and the
      transition is never reached. Nothing else records the drop: the
      tracker's maps are in-memory (:55-56), no audit row is written, no
      source column changes, and the success path actively CLEARS the
      failure counter.
    repro: |
      A feed serves 1001 entries on ticks 1 and 2 and 999 on tick 3,
      repeating. Each over-cap tick silently drops the tail; each under-cap
      tick calls consecutiveSaturatedTicks.remove(sourceId). The streak
      never reaches the threshold, notifyOnce("fetch_saturation:…") never
      runs, and the operator receives zero notifications, zero audit rows,
      and a source reporting active with a fresh last_success_at forever,
      while ingest is clipped on two ticks out of every three. This needs
      no adversary — a feed hovering near MAX_ITEMS oscillates naturally.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-08-03
    verdict: FINDINGS
    base: 1d7d753af3d72c1cddb3723589ba9a23b053e841
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-753-2026-08-03.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Gate audit at /m1-tick run, ahead of review, against the uncommitted
      working tree. Both findings target one property: the diff removes a
      loud degradation signal (throw -> D42 ladder -> status='failed' +
      fetch_failure_ladder notification) and replaces it with a
      streak-gated counter an oscillating feed never trips, while the tick
      now records SUCCESS and zeroes consecutive_failures. Independently
      re-verified: the streak reset (PaginationSaturationTracker:86-88),
      recordSuccess on the truncating tick (FetchScheduler:575), the
      4-kind blast radius, and out-of-model #2's non-reachability. Finding
      1's content-suppression framing is partially disputed — before the
      diff the same adversary suppressed every publisher on the feed
      completely, so truncation strictly reduces suppression power; the
      undisputed core is the silence, not the suppression. Remediation
      needs PaginationSaturationTracker and/or FetchScheduler, both
      outside files_scope, so it cannot land without escalate -> refine.
  - date: 2026-08-03
    verdict: CLEAN
    base: 1d7d753af3d72c1cddb3723589ba9a23b053e841
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-753-2026-08-03-r2.md
    out_of_model_count: 2
    note: |
      Re-audit of the remediated diff, required because the round-1 fix
      invalidated the audit that prompted it. Both round-1 findings
      confirmed CLOSED: truncation now carries its own per-occurrence
      signal with a durable admin_notification_state row, and
      recordTick's streak semantics are byte-unchanged so the
      pagination-saturation commitment for Bluesky/Reddit is intact.
      `redteam_findings:` deliberately RETAINS the round-1 entries
      rather than being reset to [] on this CLEAN — those findings were
      real, they are cited by the refine commit, and erasing them would
      destroy the traceability the field exists for. The CLEAN applies
      to the round-2 diff, recorded here.
      Diff-range note: the branch carried the refine commit by now, so
      the mechanical single-ticket algorithm would have selected a
      commit range holding only the ticket file and none of the code.
      The fork-point working-tree form was used instead; a CLEAN from
      the mechanical range would have been vacuous.
      Out-of-model (advisory, neither auto-filed): (1) ingest-fairness
      on shared multi-author feeds — judged not a model gap, since an
      exactly-at-cap payload achieves the same monopoly more quietly and
      the spec carries no ingest-fairness commitment; (2) the tick's
      catch path drains the new TRUNCATED flag but not the sibling
      CAP_HIT — pre-existing, unreachable today, and the one item worth
      a small hardening follow-up ticket.
clarity_check:
  date: 2026-08-03
  verdict: WARN
  warnings:
    - "lint FILES-SCOPE-COVERAGE x5 — the linter's first-word-as-path heuristic misreads prose test_plan.adds entries ('NOTE:', 'An', 'The', 'The', 'A') as file paths; no real coverage gap, every test lands in RssFeedParserTest.java which IS in files_scope"
    - "self-check: census re-run live at start — 34 MAX_ITEMS hits across 5 files, matching the ## Census disposition table exactly; all cited line numbers (:54-59, :60, :134-137, :202-205, Bluesky :31, Reddit :32) verified accurate"
    - "self-check: acceptance item 4's signal path is in-scope — PaginationSaturationTracker.signalCapHit() is static (no CDI wiring) and FetchScheduler.tickOnce drains it kind-agnostically at :498, so RSS can signal without touching a file outside files_scope"
    - "self-check: control preservation — the replaced throw path fed logFetchFailure's M1-042 URL redaction and the D42 ladder. The redaction property is preserved a fortiori (the saturation notify carries uuid+kind only, never the identifier URL); the D42 ladder no longer counting an over-cap parse IS the ticket's stated intent (acceptance 1) and is explicitly out_of_scope. No sanitize/audit/authz call rides on the throw."
  blockers: []
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

## Refine, 2026-08-03 (redteam-finding)

The first implementation satisfied all six original acceptance items and
the full suite was green, but the redteam gate found the truncation
signal itself was not robust. Recorded here because the widened
`files_scope` (4 → 7 files) is otherwise unexplained in the diff.

Two findings were returned; **one survived falsification**.

**Survived — AUDIT-EVASION (low).** Routing truncation through
`signalCapHit()` gates the only notification on a CONSECUTIVE streak, and
`PaginationSaturationTracker:86-88` deletes that streak on any
non-saturated tick. A feed oscillating across the cap never notifies. Three
attempts to falsify this all failed: `capHitCount()` is read only by a
test, there are no fetch-path metrics, and `posts.size()` is never logged.
When the streak does not fire, the discard is recoverable from nothing.
The oscillation needs no adversary — a feed hovering near `MAX_ITEMS`
does it naturally.

**Did not survive — DOS (medium), on its promise mapping.** The audit
cited two spec passages, neither of which this diff breaches:

- `security.md:542-550` (content-suppression) is about **LLM-output
  sanitize-span granularity over assembled multi-author prose at render
  time**, not ingest-stage item selection. Category error.
- `security.md:1358-1365` (D42) promises what happens *when a parse
  fails*. This ticket's sanctioned premise is that over-cap is **not** a
  parse failure, so the promise's trigger condition ceases to exist
  rather than being violated.

Its factual observations are true (the tick records SUCCESS and zeroes
`consecutive_failures`), but the harm they produce is exactly the
surviving finding's, so it is not carried as a separate requirement. Its
adversarial framing also overstates the delta: **before** this ticket, an
attacker flooding a multi-author aggregator past `MAX_ITEMS` suppressed
*every* publisher on that feed completely and permanently. Truncation
strictly reduces that suppression power. The diff does not create the
vector; it narrows it. What it creates is the silence — which is what the
surviving finding fixes.

Note the durable record also partially mitigates the oldest-first
ordering caveat above: such a feed would at least become visible instead
of silently ingesting a frozen prefix forever.
