---
id: M1-765
title: "Anchor snapshot for saved posts"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by:
  - M1-759
files_budget: 7
files_scope:
  - infochat-core/src/main/resources/db/migration/V78__saved_post_english_anchor.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    BACKFILLING EXISTING ROWS. Rows saved before this migration keep a
    NULL anchor and render M1-759's anchor-absent form forever. Filling
    them from `post` would be a render-time-equivalent re-resolution of
    frozen content against live rows — exactly what V15's Invariant 6
    ("copied at /save time, never re-resolved against post") and V76's
    header forbid, and the post may have been partitioned away entirely.
    The bookmark is a point-in-time artifact; a later anchor is not part
    of it.
  - >-
    THE RENDER LAYOUT. M1-759 owns the block shape, the bracket
    invariant and the `MessageFormat` threading. This ticket only makes
    the anchor AVAILABLE to `/saved`; the rendering rules it feeds are
    already written and must not be re-litigated here.
  - >-
    RE-RESOLVING CONTENT AGAINST `post` AT RENDER. `SavedCommandHandler
    .SELECT_ROWS_BASE_SQL` stays a pure snapshot read with no post/source
    join — the M1-730 existence/status visibility interlock remains the
    only post interaction.
  - >-
    THE INGEST LEG and the anchor write path. `post.title_en` /
    `post.body_en` are read-only inputs here, as in M1-759.
acceptance:
  - >-
    MIGRATION. `V78__saved_post_english_anchor.sql` adds
    `saved_post.title_en TEXT` and `saved_post.body_en TEXT`, both
    NULLABLE with no default. Nullable is the point and differs from
    V76's `source_language TEXT NOT NULL DEFAULT 'en'`: for the language
    column `'en'` was TRUTH for every pre-column row, whereas a
    pre-column row has no anchor at all, and NULL is precisely the state
    M1-759's anchor-absent branch already renders correctly. A
    non-nullable default would fabricate an anchor that was never
    computed.
  - >-
    WRITE PATH. `SaveCommandHandler.SELECT_POST_SQL` projects
    `p.title_en, p.body_en` alongside the fields it already snapshots and
    stores them on the new columns. The join is already in hand (the
    statement joins `post` to `source` for `s.language`), so this is one
    projection change at a site that already pays for the join — the
    same argument V76's header makes for `source_language`.
  - >-
    READ PATH. `SavedCommandHandler.SELECT_ROWS_BASE_SQL` projects the
    two new columns and feeds M1-759's anchor-first derivation, so a
    non-English save made after this ticket renders the English anchor
    with the bracketed original beneath, exactly as the digest does for
    the same post.
  - >-
    OLD SAVES STILL RENDER, AND RENDER HONESTLY. A row with a NULL
    anchor takes M1-759's anchor-absent branch — bracketed original in
    the primary slot — not a blank headline and not a bare line. Pinned
    with a test that saves through the pre-migration shape.
  - >-
    ZERO TRANSLATOR CALLS ADDED. `/saved` already meters a per-page
    translator budget and a per-user LLM bucket (M1-755). Reading a
    snapshotted anchor is a column read; an English reader viewing a
    non-English save must make NO provider call, asserted with a spy.
    This is the same property M1-759 pins for the digest and it is the
    reason the anchor is worth snapshotting at all.
  - >-
    SANITIZE UNIT AND ORDER UNCHANGED. The anchor is LLM-authored text
    already sanitized at ingest by `LlmOutputSanitizerCore`; it enters
    `DisplayHeadline` at the same point the snapshot title does, one
    author's field per `sanitize` call (M1-697), never concatenated with
    the original. Per CLAUDE.md §"Preserve the controls of a path you
    replace", state the unit, not just the call.
  - >-
    `mvn verify` is green from the repo root, and the migration applies
    cleanly on a fresh DB.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  preserves:
    - >-
      V15 Invariant 6 — snapshot content never re-resolves against
      `post`. The new columns are written once at /save and never
      refreshed.
    - >-
      M1-755's `/saved` per-user cache partitioning and rate-cap
      metering, and M1-730's existence/status visibility interlock.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D13
  - D29
  - D33
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-765: Anchor snapshot for saved posts

## Context

Filed out of M1-759 (2026-08-04), whose plan-writer pass found that
`/saved` cannot render the English anchor at all: `saved_post` carries
`title`, `body`, `url`, `author`, `published_at`, `snapshot_tags` (V15)
plus `source_language` (V76), and no anchor. `SavedCommandHandler
.SELECT_ROWS_BASE_SQL` is a pure snapshot read, and V76's header states
plainly why it must stay one — a render-time join would let a later
`/add-source --lang` correction retroactively change the translation
language of bookmarks whose title and body are frozen.

So M1-759 ships `/saved` rendering the anchor-ABSENT form: the bracketed
original in the primary slot. That is honest and it satisfies the
unbracketed-line invariant, but it means an English reader's Turkish
bookmark stays Turkish while the same post in a digest shows its English
anchor.

The fix is the pattern V76 already established one ticket earlier: snapshot
the field at `/save` time, at a site that already holds the join.

## Why nullable, when V76 chose NOT NULL DEFAULT

V76 could default `source_language` to `'en'` because that was TRUE of
every pre-column row — no source declared anything else until M1-750. No
such truth exists for the anchor: a row saved before this migration was
never accompanied by one. NULL is not a gap to be papered over here, it
is the accurate value, and M1-759 already renders it correctly. A
`DEFAULT ''` or a backfill would manufacture an anchor the ingest
translator never produced.

## Notes

- `blocked_by: M1-759` is a real dependency, not sequencing preference:
  this ticket feeds an anchor into a derivation and an anchor-absent
  branch that M1-759 introduces.
- The version is **V78, not V77**: V77 is claimed by M1-760
  (`V77__post_translation_redrive.sql`) and that claim lives only in the
  ticket, not on disk, so the migration directory's max version does not
  reveal it. Re-check the CLAIMANTS — `grep -oE "V[0-9]+__" over
  `docs/plan/m1/tickets/` — at `start`, since another ticket may claim
  V78 in the meantime. Both tickets are `migration_touch: true`, so they
  serialize against each other at start regardless.
- The companion follow-up from the same M1-759 pass is M1-766 (the
  degraded render paths). The two are independent and share no files.
