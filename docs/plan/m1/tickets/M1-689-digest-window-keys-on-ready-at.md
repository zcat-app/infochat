---
id: M1-689
title: "Key post-retrieval windows on ready_at, not published_at"
status: done
created: 2026-07-25
last_updated: 2026-07-25
blocked_by:
  - M1-688
files_budget: 22
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/ClusterTraversal.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-core/src/main/resources/db/migration/V64__*.sql
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryClockIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java
  - docs/spec/commands.md
  - docs/spec/security.md
  - docs/spec/schema.md
  - docs/design/03-commands.md
  - docs/design/02-schema.md
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
    ORDER BY and display order, EXCEPT the NULL-ordering remediation the
    2026-07-25 redteam requires (high/INJECTION). Posts carrying a stated
    date keep their published_at DESC presentation order exactly as
    today, and reordering the digest narrative remains a separate product
    decision. What this ticket MAY change is only how a post with NO
    stated date sorts — because admitting such posts to the window
    (acceptance item 3) without touching the sort key puts them ahead of
    every clamped row, which is the finding.
  - >-
    The chat agent's hybrid retrieval (D58 semantic + lexical RRF arms).
    Those are relevance-ranked, not window-bounded, and are unaffected.
    SearchPostsTool IS in scope — it is a separate window-bounded tool, not
    an RRF arm — but only its window predicate moves; its result shape and
    sort key stay as they are.
  - >-
    published_at as a PROJECTED or STORED field: SaveCommandHandler's
    saved_post snapshot and the collector-side ingest clamps in
    PostPersister and NostrEvent. Those record or display publication
    time and are correct as they are. RetryCommandHandler's anchor
    re-fetch was carved OUT of this item by the round-3 redteam refine —
    its mapper's unguarded published_at read was correct only while the
    window predicate excluded NULL rows, which is the premise THIS ticket
    removes, so the null guard is in scope. Nothing else about that
    re-fetch moves: no predicate is added and the projection is unchanged.
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
    A slot that collected zero posts no longer strands a late-arriving
    post. A new DigestPostCollectorIT case covers a post whose published_at
    predates an empty slot's boundary but whose ready_at falls after it
    being collected by the NEXT slot. This is the property M1-688
    deliberately did not compensate for in DigestWorker (see its §Notes):
    under the ready_at predicate the zero-post boundary advance is
    lossless, and this case is what pins that.
  - >-
    Any index supporting the old predicate is replaced by one supporting
    the new one, so neither query regresses to a sequential scan on the
    post table. docs/design/02-schema.md's DDL mirror drops the same index
    line, so the schema notes do not contradict the migration landing in
    the same commit.
  - >-
    Every window-bounded test fixture seeds ready_at. Fixtures that set
    published_at alone leave ready_at NULL, which the new predicate
    excludes — so without this the affected suites go red for a fixture
    reason rather than a behavioral one.
  - >-
    DigestRoundtripIT positions its post relative to digest slots by
    REWRITING published_at mid-test, so seeding alone does not fix it —
    the helper must move ready_at with it, or the post leaves the window
    on the first rewrite. Note this failure mode hides: with no eligible
    post the digest sends the "no posts yet" reply, which is non-degraded
    and non-empty, so the roundtrip's early steps pass vacuously and only
    the later "LLM must be called" assertion fails. Fixing it restores
    the coverage those early steps are supposed to provide.
  - >-
    The two tests that pin the window's boundary semantics by column —
    EligiblePostQueryClockIT and SearchPostsToolClockTest — are
    re-pointed at ready_at in fixture, name, and comment. Both currently
    assert the boundary against published_at; SearchPostsToolClockTest
    additionally seeds ready_at = published_at + 300s, which would place
    its excluded post inside the new window. They must pin the new
    semantics, not pass by fixture accident.
  - >-
    SearchPostsToolTest.windowFilterBindsToPublishedAtNotReadyAt asserts
    this ticket's core case with the polarity reversed — a post published
    5h ago and readied 30m ago must be EXCLUDED from a 2h window. It is
    INVERTED, not deleted or relaxed: renamed to
    windowFilterBindsToReadyAtNotPublishedAt and asserting the post now
    surfaces, so coverage strength is unchanged and simply aimed at the
    new contract the spec edit in this commit makes authoritative. Its
    sibling resultOrderingBindsToPublishedAtNotReadyAt stays green and
    UNCHANGED — it guards this ticket's out_of_scope promise that
    presentation order does not move.
  - >-
    REDTEAM high/INJECTION remediation. Admitting NULL-published_at posts
    to the window (item 3) while leaving the sort key a bare
    published_at DESC places them AHEAD of every clamped row, because
    Postgres sorts NULLs first under DESC. schema.md documents the ingest
    clamp as existing precisely to deny that head-of-ordering position to
    a source, so this is a regression against a defended property, not a
    cosmetic ordering nit. All three window-bounded queries
    (EligiblePostQuery main, DigestPostCollector x2, SearchPostsTool)
    order on COALESCE(published_at, fetched_at) DESC so an absent date
    sorts by the fetch instant instead of beating every stated date.
    fetched_at, not ready_at: round 2 of the audit showed ready_at is
    stamped AFTER fetch and re-stamped by approve_quarantine/re-eval, so
    it ranks an undated post above the clamp ceiling and lets a released
    post jump the queue. fetched_at is the immutable partition key and is
    exactly that ceiling. Dated rows are unaffected: their COALESCE
    resolves to published_at, so existing presentation order is
    byte-identical. The residual is stated, not over-promised: an undated
    post sorts at the top of its own fetch batch and no higher.
  - >-
    A test pins the ordering property directly: a NULL-published_at post
    seeded alongside dated posts must NOT sort ahead of them. Without
    this the fix is unpinned and the next change silently reintroduces
    the finding.
  - >-
    REDTEAM medium/INFO-LEAK remediation. docs/spec/security.md's closed
    searchPosts allowlist row currently states the inverted contract
    ("the window filter and result ordering both bind to published_at ...
    a post with an old published_at but a recent ready_at does not
    surface in a short window"). It is corrected to describe the shipped
    behaviour. The row is spec-CLOSED, so leaving it stale ships a
    security-tier document that contradicts the code, and the parity test
    reads only the Name column so no gate catches it. The stale comment
    at SearchPostsTool.java:47 citing the published_at cutoff is
    corrected with it.
  - >-
    docs/spec/schema.md's published_at-clamp rationale is extended to
    state that an ABSENT date is ordered by ready_at, so the
    head-of-ordering defense the clamp describes is complete rather than
    bounded only from above.
  - >-
    Both redteam out-of-model items — caused by this diff, so dispositioned
    here rather than dropped. (a) After V64 no published_at index remains
    and the COALESCE sort key could not use one anyway, so the window set
    is sorted per call; the cost is stated in the migration note and the
    design notes rather than left implicit. (b) approve_quarantine
    re-stamps ready_at, so an admin approving an old post now re-delivers
    it into every in-world scope's CURRENT window — a blast-radius change
    this ticket introduces, stated in the spec.
  - >-
    ClusterTraversal's javadoc names EligiblePostQuery's sort key as
    "published_at DESC, id DESC" — the key this ticket replaces — and it
    is load-bearing prose, not decoration: it carries the determinism
    argument for the BFS seed order. It is corrected to the COALESCE key.
    This is an orphan THIS diff creates (CLAUDE.md §Surgical changes
    permits fixing those), not one of the pre-existing parked nits.
  - >-
    REDTEAM round-3 high/DOS remediation. RetryCommandHandler.mapPost
    reads rs.getTimestamp("published_at").toInstant() UNGUARDED, and its
    anchor re-fetch (SELECT_POSTS_BY_UIDS) filters only on uid and
    status='READY' — there is no published_at predicate to exclude a NULL
    row. This ticket makes a NULL-published_at post reachable in /summary
    for the first time, so its uid is frozen into
    summary_anchor.post_uids and the following /retry NPEs outside any
    try/catch in handle(). That breaks /retry for the (user, scope) for as
    long as the post satisfies the window, and for every member of a group
    since the D59 world is shared. It fires with NO adversary: an undated
    or unparseable <pubDate> is common in real RSS. The mapper is
    null-guarded exactly as DigestPostCollector.mapPost and
    EligiblePostQuery already guard the same read, and
    RetryCommandHandlerGroupScopeIT gains a case driving an undated post
    through /summary then /retry. No existing gate catches this:
    Post.publishedAt is already @Nullable so NullAway has no contract to
    violate, ResultSet.getTimestamp is unannotated third-party so
    .toInstant() compiles clean, and RetryCommandHandlerTest's fake
    ResultSet builds its Timestamp from the same nullable field, so a unit
    test would NPE inside the harness before reaching the code.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryClockIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolClockTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerGroupScopeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Periodic group digests
decision_refs:
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
      files: 29
      added: 1987
      removed: 158
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-25
    category: INJECTION
    severity: high
    promise: |
      security.md §Prompt-injection defenses treats all post bodies as
      untrusted (D21) and schema.md §"published_at clamp" states the
      ingest clamp exists so a source "cannot claim a future publish
      time", keeping a claim "from sorting to the top of every
      searchPosts window and ORDER BY published_at DESC fed to the chat
      LLM".
    gap: |
      The clamp bounds a supplied date from above; it cannot bound an
      ABSENT one, and Postgres sorts NULLs FIRST under DESC. That hole
      was closed downstream only because published_at >= ? excluded NULL
      rows from every window. Moving membership to ready_at while
      leaving the sort key on published_at (no NULLS LAST, no COALESCE)
      admits NULL-dated rows into all three LLM-fed result sets and
      places them ahead of every clamped row — EligiblePostQuery,
      DigestPostCollector (both SQL), and SearchPostsTool, each bounded
      by a LIMIT/cap that then evicts the legitimate tail.
    repro: |
      Attacker controls any feed in a scope's D59 world (/add-source is
      open to any non-banned user). Publish 200+ benign-passing items
      with <pubDate> omitted or malformed — RssFeedParser returns null
      for both and PostPersister binds SQL NULL. Each satisfies
      ready_at >= cutoff for every window in the retention span and
      sorts ahead of all clamped posts, filling the digest, /summary,
      and searchPosts head-to-cap and holding first-read position in the
      summarizer and chat prompts.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-07-25
    category: INFO-LEAK
    severity: medium
    promise: |
      docs/spec/security.md's closed searchPosts allowlist row: "The
      window filter and result ordering both bind to published_at ...
      ready_at ... drives neither the window nor the ordering, so a post
      with an old published_at but a recent ready_at does not surface in
      a short window." The list is "closed at spec level (additions or
      removals are spec amendments, not design tweaks)".
    gap: |
      The diff inverts exactly that sentence and leaves security.md
      untouched — docs/spec/security.md is absent from this ticket's
      files_scope. commands.md was amended instead, so the two spec
      files now state opposite contracts for the same tool, with the
      security-tier one carrying the false statement.
      ChatToolAllowlistSpecParityTest matches only the Name column
      (TOOL_NAME_CELL), so the "CI fails on a mismatch" claim does not
      cover semantic drift in the Notes column and the build stays green
      with the spec false. A stale comment at SearchPostsTool.java:47
      also still cites the published_at binding.
    repro: |
      Seed a post with published_at 5h ago and ready_at 30m ago in a
      subscribed source; have the chat agent call searchPosts with
      window PT2H. security.md says it cannot appear; it appears. The
      ticket's own replacement test asserts the new behaviour, so the
      divergence is pinned green.
    suggested_fix_class: other
  - date: 2026-07-25
    round: 3
    category: DOS
    severity: high
    status: >-
      SCOPED for remediation. Was OPEN when round 3 reported it; the
      round-3 refine brought RetryCommandHandler.java into files_scope and
      carved its anchor re-fetch out of out_of_scope (whose "correct as
      they are" disposition was premised on the predicate this ticket
      replaces). Closure is verified by the round-4 audit, not by this
      field.
    promise: |
      security.md §Threat model treats all feed content as untrusted, and
      §Failure handling promises "/retry after recovery re-rolls the prose
      with the original frozen post selection".
    gap: |
      This ticket makes NULL-published_at posts reachable in /summary for
      the first time. RetryCommandHandler.java:353 reads
      rs.getTimestamp("published_at").toInstant() UNGUARDED, and its
      re-fetch (SELECT_POSTS_BY_UIDS) filters only on uid + status='READY'
      — no published_at predicate to exclude the NULL row that /summary
      froze into summary_anchor.post_uids. The NPE escapes handle().
      DigestPostCollector's identical read WAS guarded (it was open in the
      diff); SaveCommandHandler:374 was already guarded. This one sat in
      out_of_scope as "correct as they are" — a disposition premised on the
      OLD predicate, which this ticket invalidates.
      No gate catches it: Post.publishedAt is already @Nullable so NullAway
      has no contract to violate, ResultSet.getTimestamp is unannotated, and
      RetryCommandHandlerTest:520's fake ResultSet would NPE inside the
      harness first. Three green full suites passed over it.
    repro: |
      Any non-banned user /add-source's a feed (or any existing in-world
      feed) publishes ONE item with <pubDate> omitted or unparseable →
      published_at is SQL NULL, body benign so it reaches READY. A user runs
      /summary (post is in-window via ready_at, renders fine, uid frozen in
      the anchor), then /retry → NPE at RetryCommandHandler:353. Repeats for
      as long as the post satisfies the window; group scopes share the world.
      Fires with NO adversary — undated RSS items are common.
    suggested_fix_class: input-sanitization
  - date: 2026-07-25
    round: 4
    category: INFO-LEAK
    severity: low
    status: >-
      Remediated in-branch, doc-only. docs/spec/security.md is already in
      files_scope and the correction lands inside the existing round-1
      medium/INFO-LEAK acceptance item ("corrected to describe the shipped
      behaviour"), so no refine was needed.
    promise: |
      docs/spec/security.md's spec-CLOSED searchPosts allowlist row, as
      REWRITTEN BY THIS DIFF: "an undated post sorts at the top of its own
      fetch cycle ... and no higher. It cannot outrank later fetches, and
      it cannot move on release." The section binds the table as "closed
      at spec level" and claims "CI fails on a mismatch in either
      direction".
    gap: |
      "It cannot outrank later fetches" is false against the shipped sort
      key COALESCE(published_at, fetched_at) DESC. The ingest clamp bounds
      published_at from ABOVE only (PostPersister:193), never from below,
      so an honest backfilled item fetched LATER keys arbitrarily earlier
      than an undated item fetched EARLIER — and the undated row outranks
      it. No attacker capability follows: a dated post from the same fetch
      can already claim the identical key, so omitting the date buys a
      position that was always available. The delivered ordering is safe;
      the security-tier sentence describing it is not true. The three
      sibling statements of the same bound (schema.md, commands.md
      §Content, the EligiblePostQuery inline comment) are all correct and
      do NOT carry the false clause, so the divergence is confined to the
      one spec-CLOSED row. Nothing catches it: the round-1 structural gap
      is unchanged — ChatToolAllowlistSpecParityTest parses only the Name
      column, so the "CI fails on a mismatch" claim does not reach the
      Notes column.
    repro: |
      Monday's fetch tick (fetched_at = Mon 12:00) ingests an item with
      <pubDate> omitted; its sort key is COALESCE(NULL, Mon 12:00).
      Tuesday's tick ingests an honest backfill item dated the previous
      Friday; its key is Fri. Both are inside a searchPosts window: PT48H.
      The undated Monday item sorts ABOVE the Tuesday-fetched item in the
      JSON re-injected into the chat prompt, and identically in /summary
      and the digest — which the row says cannot happen. The hazard is a
      future change reasoning from that sentence (e.g. dropping the
      fetched_at fallback because "later fetches dominate anyway"), which
      would reintroduce the round-1 high while appearing spec-conformant.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-25
    verdict: FINDINGS
    base: e691f30f555c0a2b74c702b126612abbfe8c164c
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-689-2026-07-25.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Run at the pre-review gate on user override (ticket is
      security_relevant: false; the user directed the audit to run ahead
      of code review). Both findings independently verified in the main
      session before acceptance — see the verdict file's disposition
      block for the confirming evidence. Halted here: no code review was
      run and nothing committed. Two out-of-model items are advisory —
      the post-V64 sort-plan cost with no published_at index remaining,
      and approve_quarantine re-stamping ready_at, which under the new
      predicate re-delivers an arbitrarily old approved post into the
      current window of every scope in the source's world.
  - date: 2026-07-25
    round: 2
    verdict: FINDINGS
    base: e691f30f555c0a2b74c702b126612abbfe8c164c
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-689-2026-07-25-r2.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Re-audit of the round-1 remediation. Round-1 medium CLOSED; round-1
      high substantially closed. New medium: COALESCE(published_at,
      ready_at) was insufficient — ready_at is stamped after fetch and
      re-stamped by approve_quarantine/re-eval. Remediated in-branch by
      moving the fallback to fetched_at (immutable partition key).
  - date: 2026-07-25
    round: 3
    verdict: FINDINGS
    base: e691f30f555c0a2b74c702b126612abbfe8c164c
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-689-2026-07-25-r3.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Sort-key remediation VERIFIED CLOSED — all four sites, the security.md
      contract, and legs A and B re-derived from the tree rather than
      assumed; fetched_at confirmed attacker-uninfluenceable. NEW high/DOS
      still OPEN: RetryCommandHandler:353 reads published_at unguarded on
      the /retry replay path this ticket newly makes NULL-reachable.
      Ticket halted here; code review has NOT been run.
  - date: 2026-07-25
    round: 4
    verdict: FINDINGS
    base: e691f30f555c0a2b74c702b126612abbfe8c164c
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-689-2026-07-25-r4.md
    findings_count: 1
    out_of_model_count: 4
    note: |
      Re-audit of the round-3 remediation. ALL findings from rounds 1-3
      VERIFIED CLOSED against the current bytes, each re-derived rather
      than assumed. Round 3's open question — whether
      RetryCommandHandler.mapPost was the ONLY newly-NULL-reachable
      unguarded reader — was answered by enumerating READERS rather than
      predicate sites (the blind spot behind all six scope misses on this
      ticket): the reader set is complete and no consumer dereferences the
      mapped Post.publishedAt downstream. One new low/INFO-LEAK, doc-only,
      remediated in-branch: security.md's searchPosts row claimed an
      undated post "cannot outrank later fetches", which the clamp's
      one-sided bound makes false. Second time this same sentence
      over-claimed (round 2 flagged the same shape), so the correction now
      states explicitly that a later fetch does NOT automatically displace
      an undated post. Four out-of-model items carried forward, none
      filed.
  - date: 2026-07-25
    round: 5
    verdict: CLEAN
    base: e691f30f555c0a2b74c702b126612abbfe8c164c
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-689-2026-07-25-r5.md
    out_of_model_count: 5
    note: |
      Re-audit of the round-4 remediation (doc-only: the bound sentence in
      security.md's spec-CLOSED searchPosts row). CLEAN. Rounds 1-4 all
      re-verified closed; the replacement sentence was checked clause by
      clause against the four ORDER BY sites and the ingest clamp, and
      cross-checked against its three sibling statements for new
      divergence. Because this sentence had already over-claimed twice,
      the prompt told the adversary not to grant it the benefit of the
      doubt — and, symmetrically, that a fabricated fifth finding was now
      the likelier failure mode than a missed one. Five out-of-model
      items, four carried forward. The ONE new item is a durability note,
      not a defect: the NULL-ordering property is delivered at all four
      sort sites but regression-pinned at only one (SearchPostsToolTest),
      so a future revert of the other three would ship green. Not
      remediated here — this ticket's acceptance item is singular and
      satisfied, and widening it does not trace to the acceptance
      criteria. Filed as M1-692 per CLAUDE.md §"Better alternatives
      surface as proposals, not scope expansion".
clarity_check:
  date: 2026-07-25
  verdict: WARN
  warnings:
    - >-
      lint: PASS (0 blockers, 0 warnings) before and after the refine.
    - >-
      Self-check: census grep reproduces the disposition table exactly (5
      predicate sites + 1 stale comment at EligiblePostQuery.java:195).
    - >-
      Self-check: ready_at needs no COALESCE — verified every status='READY'
      writer stamps it in the same UPDATE, and all 5 predicate sites already
      filter on that status.
    - >-
      Self-check: files_scope was under-scoped by 7 window-bounded test
      fixtures; escalated as budget-breach and resolved via refine
      (files_budget 9 -> 15) at commit e691f30f.
  blockers: []
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
| `infochat-provider/.../command/RetryCommandHandler.java:78,353` | fix (round-3 redteam refine) — still no predicate and the projection is unchanged, but the unguarded read at :353 was safe only while the old predicate kept NULL rows out of the anchor |
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
  - **Checked at start (2026-07-25): no `COALESCE` needed.** The column is
    nullable in DDL, but every writer that sets `status='READY'` sets
    `ready_at` in the same UPDATE — `ReadyPromoter.java:210` plus the
    quarantine-approve procedures (V21/V25/V32/V41/V48/V50/V53) — and all
    five predicate sites already filter `status='READY'`, so the predicate
    never sees a NULL `ready_at`.
- **The census extends to test fixtures (budget-breach refine, 2026-07-25).**
  The `## Census` table above enumerates main-source predicate sites only.
  Moving the predicate also invalidates every window-bounded *fixture* that
  seeds `published_at` without `ready_at`, because those rows go NULL on the
  new column and vanish from the surface under test. Enumerate with:

      grep -rn "INSERT INTO post" --include='*.java' infochat-provider/src/test

  and check each hit's column list for `ready_at`. Seven files need it; they
  are in `files_scope`. `InboundRouterStopRetryIT` seeds a `ready_at`-less
  post but is NOT affected — its `/retry` re-fetches by uid with no window.
- **The supporting index already exists.** `idx_post_ready_at ON post(ready_at,
  id) WHERE status = 'READY'` (`V7__joins_post.sql:184`) matches the new
  predicate exactly — partial on the same status literal, range scan on the
  leading column. V64's content is therefore the *removal* half of acceptance
  item 6: after this change no query filters on `published_at`, leaving
  `idx_post_published` (`V7:182`) as write-amplification serving nothing. It
  can only offer a published_at-ordered scan that filters on `ready_at` and
  discards — and under exactly the late-arrival case this ticket fixes the two
  orders are uncorrelated, so that plan scans far past the window. Per-source
  lookups keep `idx_post_source_published` (`V36:33`).
- **`DigestPostCollector.mapPost` needs a null guard.** It calls
  `rs.getTimestamp("published_at").toInstant()` unguarded, so it NPEs on the
  NULL-`published_at` post acceptance item 3 requires. `EligiblePostQuery`
  already guards the same read and `Post.publishedAt` is already `@Nullable`.
  - **So does `RetryCommandHandler.mapPost` (round-3 redteam refine,
    2026-07-25).** The original claim that "only the digest mapper changes"
    was wrong, and wrong for the reason §Scope history names: the census
    enumerated where the PREDICATE lives, never who reads the column the
    predicate was implicitly guarding. `published_at >= ?` was acting as a
    `NOT NULL` filter for every downstream reader, and `/summary` freezes
    its uids into `summary_anchor.post_uids`, which `/retry` re-reads with
    no window at all. `SaveCommandHandler:374` reads the same column on
    the same replay-ish path but is **already guarded** — leave it alone.
- **This ticket carries the zero-post boundary property.** M1-688 was filed
  with a second acceptance item — an empty slot must not become the next
  slot's collection boundary — and dropped it at its start gate on
  2026-07-25: the `summary_cache` row it would have suppressed is the
  scheduler's only re-fire guard, the missed-slot sentinel rewrites the same
  boundary anyway, and the marker-column alternative would be a schema
  compensator for a defect this ticket removes at the root. Under
  `ready_at >= since` the boundary advance is lossless — a post that goes
  READY after an empty slot satisfies the next slot's predicate. That is why
  the acceptance item above exists here rather than there; do not treat it
  as scope creep.
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
