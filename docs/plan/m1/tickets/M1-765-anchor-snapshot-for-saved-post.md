---
id: M1-765
title: "Anchor snapshot for saved posts"
status: done
created: 2026-08-04
last_updated: 2026-08-05
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
reviews:
  - round: 1
    date: 2026-08-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 759
      removed: 24
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-08-05
    verdict: CLEAN
    base: 02baa3a153973f668ce59f7288aa70b48ea3cd3a
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-765-2026-08-05.md
    out_of_model_count: 4
    note: |
      Ran at the /m1-tick run gate, ahead of review, against the working
      tree vs the fork point. CLEAN at every severity: the adversary
      confirmed the sanitize unit stays one author's field per call
      (DisplayHeadline.derive gives the anchor its own call rather than
      widening the original's input), the read path stays a pure snapshot
      read, and both new columns carry the BODY_SCAN_LIMIT bound in SQL
      and again in Java.
      Four out-of-model items, all advisory and none a delivery gap:
      (1) a privileged command split across the anchor line and the
      original line is neither redacted nor audited — an exposure
      security.md §"Flag position mirrors the parser's own scan" already
      accepts for "two posts' fields", which the anchor now makes
      reachable from ONE hostile publisher; (2) that same section's
      sanitize-call census does not list the anchor, stale since M1-759
      for the digest surfaces and now for /saved; (3) nothing verifies
      the anchor column is actually English, so D29 (c)'s unbracketed-
      means-readable invariant rests on an unchecked column (pre-exists
      on the digest surfaces; this diff extends it to /saved); (4) audit
      writes per row moved from two sanitize calls to three, the third
      firing even when usesAnchor is false.
      Dispositions (user, 2026-08-05, each after a falsification pass).
      NOTE: the 1+2 disposition below was SUPERSEDED the same day — see
      the round-2 entry; the spec amendment was reverted back out and
      the gap is closed in code by M1-772 instead.
      1+2 folded into this ticket (refine 990c7f5f) — item 1 survived,
      and the first falsification of it was WRONG: the two lines DO
      combine, because the parser tokenizes the whole multi-line body
      and bracketed() wraps only the line's ends, so `--all` stays a
      clean token. 3 deferred to M1-771, whose out_of_scope fences the
      audit's proposed target-script fix as unworkable
      (missingTargetScript no-ops on LATIN targets; the anchor targets
      English). 4 dropped as falsified — emitAuditRows does no DB work
      without a match. Detail in the verdict file.
  - date: 2026-08-05
    verdict: FINDINGS
    base: 02baa3a153973f668ce59f7288aa70b48ea3cd3a
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-765-2026-08-05-r2.md
    findings_count: 1
    out_of_model_count: 4
    note: |
      Round 2, re-audit of the spec amendment items 1+2 had folded in.
      One low/AUDIT-EVASION finding, and it was against the AMENDMENT,
      not the code: the new text carried the old residual's two-legged
      accept-rationale onto the within-row case, but only the
      `is_admin=true` leg transfers. The multi-author leg ("one sanitize
      call over assembled multi-author prose is strictly worse") cannot
      apply when both values come from ONE post — a joint pass there
      would delete only that publisher's own bytes, self-suppression
      rather than the third-party content-suppression vector the spec
      cites. So the text recorded a CLOSABLE gap as a permanent
      residual. Three of the four out-of-model items were also against
      the amendment: it misattributed the mechanism to M1-765 when the
      original+anchor adjacency shipped with M1-759 (reaching the
      digest and /summary, not just /saved); it stated the precondition
      as "a single hostile publisher" when §Trust boundaries item 9 puts
      a hostile LLM endpoint in scope, which needs no publisher control
      at all; and the census remains short by two pre-existing one-field
      calls (the /saved <tag> filter echo, the display-hit sanitizer-2
      leg). None was falsifiable — all four were verified against the
      code and the threat model and all survived.
      RESOLUTION (user, 2026-08-05): the amendment was REVERTED out of
      this ticket entirely rather than corrected a third time. Two facts
      drove it — the sentence was already inaccurate when M1-759 merged,
      so it is not "paired with the code change that justifies it"
      (workflow.md §Non-ticket commits rule 2) but a pre-existing
      inaccuracy this diff surfaced; and with nothing deployed beyond
      v1.0 there is no reason to ACCEPT a closable gap at all. M1-772
      closes it in code (detection-only joint scan emitting the audit
      row, output sanitize still per-field) and shrinks the spec to say
      the pair is covered, which removes the residual paragraph rather
      than perfecting it. The finding is therefore resolved by
      reversion: the text it was against no longer exists in this
      branch, and the code it audited is byte-identical to the round-1
      CLEAN verdict.
clarity_check:
  date: 2026-08-05
  verdict: PASS
  warnings:
    - >-
      Self-check note: `post.title_en` is LLM-authored and capped at no
      write path (`IngestTranslationWorker.persistTranslation`), unlike
      `post.title` which `IngestTextNormalizer.TITLE_MAX_LENGTH` bounds
      at ingest. The read path therefore bounds BOTH new columns with
      `left(..., DisplayHeadline.BODY_SCAN_LIMIT)`, not just `body_en`
      — the same M1-730 paginated-read guard the sibling `body` column
      already carries, applied to the operands that share its property.
  blockers: []
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
