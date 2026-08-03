---
id: M1-755
title: "Display-time translation of /saved list headlines"
status: pending
created: 2026-08-03
last_updated: 2026-08-03
blocked_by:
  - M1-747
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration/V75__saved_post_source_language.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
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
acceptance:
  - >-
    DESIGN DECIDED — snapshot `source.language` at /save time into a new
    `saved_post.source_language` column (V75, `TEXT NOT NULL DEFAULT
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
      across that user's scopes. No other pipeline change — the entry
      point and its controls are M1-747's, reused byte-unchanged.
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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

**Snapshot `source.language` at save time** (new column, V75) rather than
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
- `migration_touch: true` because of V75.
- No `## Census` section: the ticket touches SQL column lists and render
  wiring, not a record-construction class. The one census-like obligation
  is the stub-mapper sweep in test_plan preserves.
