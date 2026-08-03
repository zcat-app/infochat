---
id: M1-755
title: "Display-time translation of /saved list headlines"
status: done
created: 2026-08-03
last_updated: 2026-08-04
blocked_by:
  - M1-747
files_budget: 11
files_scope:
  - infochat-core/src/main/resources/db/migration/V76__saved_post_source_language.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
  - docs/spec/security.md
  - infochat-provider/src/main/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The /summary and /retry leg — that is M1-747, whose
    `TranslationPipeline` display-hit entry point this ticket reuses
    rather than re-implements.
  - >-
    Translating the snapshot BODY, persisting translated text, or
    changing which saved rows are listed (the D13 user-global list, the
    M1-730 visibility interlock, the 20-row page — all untouched).
  - >-
    The digest-broadcast and degraded surfaces (M1-756).
  - >-
    Reading `source.language` live anywhere except the /save path's
    SELECT. The render path stays a pure snapshot read; no post->source
    join is added to any /saved query.
  - >-
    Offloading the /saved render off the adapter's transport dispatch
    thread (the D35 interruptible redesign). The leg stays inline by
    design; the per-page translator budget (acceptance item 7) is what
    bounds the per-invocation thread hold. (Redteam 2026-08-03,
    high/DOS — accepted remediation scope.)
  - >-
    Draining the display-hit translation cache on /forget. The 24h
    per-(user, language) retention is process-local, never persisted and
    bounded — the same accepted residual as the M1-746 query-anchoring
    cache (security.md §Secrets handling). (Redteam 2026-08-03,
    out-of-model in both auditor verdicts.)
  - >-
    The cache-hit-no-sanitize audit property: a translator-introduced
    token is audit-logged once per cache entry, not per delivery. That is
    M1-747's leg design, reused byte-unchanged; M1-747's own audit passed
    CLEAN with the identical property, so it is inherited-accepted, not a
    regression of a /saved control (the pre-pipeline DisplayHeadline
    sanitize+audit on every render is untouched). (Redteam 2026-08-03,
    low/AUDIT-EVASION — falsified as a regression.)
  - >-
    The `infochat.save.translation-max-per-page` key's absence from the
    docs would be a DocumentedConfigKeyParityTest failure — the spec
    amendment (acceptance item 8) is what documents it.
acceptance:
  - >-
    DESIGN DECIDED — snapshot `source.language` at /save time into a new
    `saved_post.source_language` column (V76, `TEXT NOT NULL DEFAULT
    'en'`, mirroring V74's `source.language`). Justification: the /saved
    render path is a pure snapshot read (SELECT_ROWS_BASE_SQL carries no
    post/source join — the only post interaction is the M1-730
    existence/status visibility interlock), so a render-time join would
    be the first content-affecting re-resolution against live rows since
    the D13/Invariant-6 snapshot carve-out, and it would join the one
    source column M1-750 opens to writes: a later `--lang` correction
    would retroactively change the translation language of bookmarks
    whose title/body stay frozen — the exact inconsistency the snapshot
    design exists to prevent. The save path already joins post -> source
    (SaveCommandHandler.SELECT_POST_SQL reads `s.bootstrap_tags`), so
    snapshotting costs one projected column at a site with the join in
    hand, and the render path gains nothing. V15 already snapshots
    `source_id` as the durable representation; `language` is the same
    class of value. Pre-column rows default 'en', which is truth for
    them (every row is 'en' until M1-750).
  - >-
    /save persists the post's declared source language: the save-time
    SELECT projects `s.language` and the INSERT writes it. The saved
    snapshot's language is frozen with the rest of the row — later
    edits to `source.language` never retro-apply.
  - >-
    A `cs`-scope /saved page translates each hit headline via M1-747's
    `TranslationPipeline.runForDisplayHit(displayHeadline,
    sourceLanguage, scopeKind, scopeId, scopeLanguage)` — same no-op
    legs (en scope, null/ISO-invalid source, same-language, empty), same
    §10 controls (pre-bound -> flatten -> sanitizer-2, ONE headline per
    sanitize call, re-truncate, marker after cut), same fallback, same
    cache. The display-hit cache partition for this leg is
    `hit/saved/<actorUserId>/<effectiveLanguage>`: the list is
    user-global (D13), so entries are shared across the user's own
    scopes but never across users — the M1-747 cross-scope partition
    rationale reduces to cross-user here, which this preserves.
    `scopeLanguage` is `inboundContext.effectiveLanguage()`.
  - >-
    `en` scope stays byte-identical with zero translator calls, asserted
    with a spy; the existing /saved byte pins are preserved.
  - >-
    LLM-COST METERING (redteam 2026-08-03, high/DOS — refine): the leg
    draws ONE per-user `LlmRateCap` token per invocation that actually
    makes a translator call — the same per-user bucket as chat replies,
    on-demand /summary and /retry (security.md §Rate limiting). The draw
    happens on the first row that will call the translator (a cache
    miss: non-empty headline, non-en scope, source language differing
    from the scope, headline absent from the display-hit keyspace); an
    en-scope page, an all-no-op page and a fully-converged page never
    draw. In group scope the D47 per-group LLM sub-bucket is drawn
    alongside the per-user token, and a group reject REFUNDS the
    per-user token (the RetryCommandHandler pattern) — the backstop
    must not drain a member's personal budget. A rejected draw degrades
    the page's cache-miss rows to untranslated, unmarked — cached
    translations still render (they cost no generative call), the
    listing stays in the cheap parser-only+DB-read class and stays
    usable, no busy reply.
  - >-
    PER-PAGE TRANSLATOR BUDGET (redteam 2026-08-03, high/DOS — refine):
    at most `infochat.save.translation-max-per-page` (default 5,
    operator-tunable, documented in security.md §Rate limiting)
    translator calls per /saved invocation. Rows beyond the budget
    render untranslated, unmarked (the M1-747 degraded-cluster
    precedent). Cache hits cost nothing, so repeated renders of a page
    converge to fully-translated (the budget bounds per-invocation
    calls, never the cache). This bounds the per-invocation
    dispatch-thread hold to budget x per-call timeout, and the
    amplification to rate-cap x budget.
  - >-
    SPEC TRUTH (refine): `docs/spec/security.md` §Rate limiting
    documents the /saved display-hit leg as an LLM-triggering operation
    metered by the per-user bucket draw + per-page budget, amending the
    "cheap" classification's parser-only premise; §Secrets handling
    names the /saved payload class (the user's saved-post headlines,
    per-user D13 state) in the `translator` disclosure surface, folded
    into the pending M1-758 switch-llm.sh disclosure update.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
      — the save path persists the projected source language: the
      fixture source carries a non-en language and the INSERT argument
      list carries it (a probe that deleting the column from the save
      path is a test failure). The en fixture keeps its existing byte
      assertions.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
      — ONE added case pinning the renderer wiring, the M1-747
      precedent: a cs-scope page whose saved row carries a differing
      `source_language` renders the translated, marked headline through
      the real bundle marker, and the en-scope byte pins stay untouched.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
      — the `saved`-leg cache partition: an entry written for one user is
      NOT readable for another (same headline, same language), and an
      entry written by the saved leg under the same user IS readable
      across that user's scopes. No behavioral pipeline change — the
      entry point and its controls are M1-747's, reused byte-unchanged;
      the only touch is the display-hit key composition
      (`displayHitCacheLanguage`) opened to public so the handler's
      per-page-budget probe uses the REAL keyspace, never a re-derived
      copy.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
      — the rate-cap degrade (redteam refine): with the user's per-user
      LLM bucket saturated, a cs-scope /saved renders the page's
      cache-miss rows untranslated with ZERO translator calls (the
      TestLlmProvider spy); the saturation is refunded at the end of the
      test so the bucket never leaks across tests.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
      — the D47 group backstop (redteam refine): with the group's
      aggregate LLM bucket saturated, a group-scope cs /saved degrades
      with ZERO translator calls AND the per-user token is refunded —
      the user pre-filled to cap-1 must still have its last slot free
      after the render (the mutation that drops the refund fails).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
      — the per-page budget bound (redteam refine): a page with more
      budget-eligible rows than `infochat.save.translation-max-per-page`
      makes exactly `budget` translator calls and renders the remaining
      rows untranslated on the first render; a second render of the same
      page translates the remainder (the cache converges) and ends with
      every row translated. A mutation that removes the budget or the
      rate-cap draw fails these.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
      — the converged-page no-draw pin (redteam refine, opencode
      finding): a second render of an already-fully-translated page
      makes ZERO translator calls AND draws no per-user token — the
      user's bucket must not be consumed by cache-hit renders (asserted
      via a post-render tryAcquire succeeding with the user pre-filled
      to cap-1).
  preserves:
    - >-
      All /saved render behavior on main: the D13 user-global list, the
      M1-730 visibility interlock, the 20-row page, the en-scope line
      bytes (compat: the new column defaults 'en' — the never-translate
      no-op for every existing fixture).
    - >-
      SaveCommandHandlerTest's existing assertions (the new column is a
      strict addition to the SELECT and INSERT column lists).
    - >-
      SavedLibraryIT / SaveCapConcurrencyIT (the INSERT gains one column
      at the end; stub row-mappers that answer INSERT args must carry
      the extra slot or be updated mechanically — a census of stub
      ResultSet/PreparedStatement mappers at implementation time).
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/design/02-schema.md §2.6.1
  - docs/design/02-schema.md §2.3.1
decision_refs:
  - D29
  - D43
  - D30
  - D13
reviews:
  - round: 1
    date: 2026-08-04
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 768
      removed: 31
  - round: 2
    date: 2026-08-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 792
      removed: 32
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - >-
    high/DOS (corroborated claude + opencode, 2026-08-03): up to 20
    synchronous ModelTask.TRANSLATOR calls per /saved page, inline on the
    adapter's transport dispatch thread while a pool connection is held,
    with zero LlmRateCap draws and no per-page bound — the spec's cheap
    bucket premise silently broken (~600 unmetered generative calls/min
    at the cheap-bucket rate; up to ~600s thread hold against a
    slow-but-answering endpoint). Remediated in-band by refine: per-user
    bucket draw per translating invocation + per-page translator budget.
  - >-
    medium/INFO-LEAK (claude) / low (opencode), 2026-08-03: the leg
    sends the user's saved-post headlines (per-user D13 state) to
    ModelTask.TRANSLATOR, which may be remote; the §Secrets handling
    disclosure surface does not name this payload class. Partially
    falsified (the headline text class is M1-747's already-shipped
    leg); remediated by the spec amendment (acceptance item 8).
  - >-
    low/AUDIT-EVASION (claude only), 2026-08-03: cache-hit deliveries of
    translator-introduced tokens emit no LLM_OUTPUT_SANITIZED row.
    Falsified as a regression: the property is M1-747's leg design,
    reused byte-unchanged and audited CLEAN there; the pre-pipeline
    DisplayHeadline sanitize+audit per render is untouched. Accepted
    (see out_of_scope).
  - >-
    low/DOS (opencode only, re-audit 2026-08-03): LlmRateCap.refund
    removes the NEWEST timestamp rather than this invocation's (a
    concurrent same-user draw between tryAcquire and the group-reject
    refund is uncounted), and the handler's cache probe is not atomic
    with the pipeline's internal lookup (a concurrent same-user render
    can draw a token for a call that never happens, or vice versa on a
    microsecond-timing TTL expiry). Adjudicated accepted: the refund
    pattern is pre-existing at InboundRouter chat and
    RetryCommandHandler (audited CLEAN), both races require the user
    racing their own concurrent operations (no cross-user impact), and
    the sequential bound (rate-cap x budget) holds. A precise fix needs
    a LlmRateCap lease-token API + all call sites — a follow-up ticket
    candidate, not an M1-755 blocker.
redteam_audits:
  - date: 2026-08-03
    verdict: FINDINGS
    base: 8b533e7f6a773fce989b528aa9b59db8d2ac8e4f
    head: working tree (fork-point + uncommitted branch work)
    verdict_file: docs/plan/m1/redteam-multi/M1-755-2026-08-03/verdict-claude.txt
    findings_count: 4
    out_of_model_count: 2
    note: |
      Multi-auditor redteam gate (/redteam-multi, claude+codex+opencode),
      run ahead of review per /m1-tick run step 4. claude: FINDINGS x4
      (2x DOS high, INFO-LEAK medium, AUDIT-EVASION low). codex: CLEAN.
      opencode: FINDINGS x2 (DOS high, INFO-LEAK low). Corroborated:
      the DOS high and the INFO-LEAK. The r2 claude slot was UNAVAILABLE
      (process exit 1 — NO DATA); the r1 packet holds claude's valid
      verdict. Cross-examination: redteam-multi/M1-755-2026-08-03-r2/
      cross-examination.md. Findings falsification + disposition:
      DOS confirmed in full; INFO-LEAK reduced to a disclosure gap;
      AUDIT-EVASION inherited-accepted. Resolution: escalate -> refine
      (this commit).
  - date: 2026-08-03
    verdict: FINDINGS
    base: 8b533e7f6a773fce989b528aa9b59db8d2ac8e4f
    head: working tree (fork-point + uncommitted branch work)
    verdict_file: docs/plan/m1/redteam-multi/M1-755-2026-08-03-r2/cross-examination.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      Completed multi-auditor packet (claude UNAVAILABLE, codex CLEAN,
      opencode FINDINGS x2). Durable subset committed with the ticket
      commit.
  - date: 2026-08-03
    verdict: FINDINGS
    base: 8b533e7f6a773fce989b528aa9b59db8d2ac8e4f
    head: working tree (fork-point + uncommitted branch work)
    verdict_file: docs/plan/m1/redteam-multi/M1-755-2026-08-03-r4/cross-examination.md
    findings_count: 1
    out_of_model_count: 4
    note: |
      Re-audit of the remediated diff (kimi+opencode+codex) after the
      in-band refine fixes (per-user bucket draw after cache probe, D47
      group backstop, converged-page no-draw). kimi CLEAN, codex CLEAN,
      opencode FINDINGS x1 (low/DOS — LlmRateCap refund non-atomicity +
      probe/lookup race). Adjudicated accepted with the user (see
      redteam_findings last entry). Durable subset committed with the
      ticket commit.
clarity_check:
  date: 2026-08-03
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-755: Display-time translation of /saved list headlines

## Context

Follow-up to M1-747 (merged 2026-08-03), filed from its surface-binding
rework. M1-747 translates the `/summary`/`/retry` flat-block headline;
`/saved` (`SavedCommandHandler`, headline at the
`DisplayHeadline.of(row.title, row.body, ...)` call, `SavedCommandHandler
.java:328`) is the same reader-comprehension gap on a different surface,
split out because its `saved_post` snapshot columns carry no source
language — a schema/design question M1-747 must not absorb.

## The design decision (resolved 2026-08-03)

**Snapshot `source.language` at save time** (new column, V76) rather than
joining `post -> source` at render.

- The render path is a pure snapshot read: `SELECT_ROWS_BASE_SQL` reads
  only frozen columns, and the only `post` interaction is the M1-730
  existence/status visibility interlock (which reads status only, never
  content). The snapshot contract — "content never re-resolves against
  post" (V15) — is what makes a bookmark survive the post-partition TTL
  (D13/D33). A render-time join would be the first content-affecting
  re-resolution since the snapshot design, and it would join the one
  source column M1-750 opens to writes (`/add-source --lang`): a later
  language correction would retroactively change the translation language
  of old bookmarks whose title and body stay frozen. That inconsistency
  is precisely what the snapshot carve-out exists to prevent.
- The save path already joins `post -> source`
  (`SaveCommandHandler.SELECT_POST_SQL` reads `s.bootstrap_tags`), so
  snapshotting the language costs one projected column at a site where
  the join is already in hand; the render path gains nothing (no join, no
  per-page cost, no source-lifetime coupling).
- Precedent: V15 already snapshots `source_id` as the durable
  representation ("the snapshot is the durable representation" —
  post_uid is TEXT rather than a UUID FK for the same reason). `language`
  is the same class of value: a property of what the user saw at save
  time.
- Migration shape: `ALTER TABLE saved_post ADD COLUMN source_language
  TEXT NOT NULL DEFAULT 'en'` — the same default V74 gave
  `source.language`. Pre-column rows read 'en', which is truth for them
  (every row is 'en' until M1-750); the M1-747 no-op legs then make old
  saves never-translate, which is correct.

**The /saved cache partition is per-user, not per-scope.** D13 makes the
saved list user-global: the same rows render in every scope the user is
in. The M1-747 display-hit partition exists so feed-authored headline
entries never leak across scopes (the accepted timing side-channel
widens only if user-authored content is shared); for /saved the
security-relevant boundary is the USER, so the partition is
`hit/saved/<userId>/<effectiveLanguage>` — the user's own scopes share
entries, no user sees another's.

## Notes

- Draft completed 2026-08-03 (design decision, files_scope, sizing,
  migration_touch) after M1-747's merge; start was deferred behind
  M1-746, which was already in-flight.
- `migration_touch: true` because of V76.
- No `## Census` section: the ticket touches SQL column lists and render
  wiring, not a record-construction class. The one census-like obligation
  is the stub-mapper sweep in test_plan preserves.

## Round 1 rework

1. Two stale migration references introduced by the diff itself name
   `V75` where the column ships in `V76__saved_post_source_language.sql`:
   the `Row`-record comment in
   `SavedCommandHandler.java` ("saved_post.source_language is NOT NULL
   DEFAULT 'en' (V75)") and the fixture comment in
   `SavedCommandHandlerTest.java` ("the V75 default"). Both changed to
   V76 to match the shipped migration and the ticket's wording.
