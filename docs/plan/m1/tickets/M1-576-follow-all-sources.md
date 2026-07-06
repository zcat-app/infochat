---
id: M1-576
title: "/follow-all-sources: bulk-subscribe an approved scope to all live sources"
status: done
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandlerIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandIT.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/design/03-commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Auto-subscribing users at registration. Making bootstrap sources a default
    subscription for every new user is a broader policy change (a new decision
    about default feeds) and is NOT this ticket — this ticket adds an explicit,
    opt-in bulk command, keeping subscription per-scope opt-in.
  - >-
    Subscribing any scope other than the caller's own (DM) or the group the
    command is issued in. No cross-scope bulk subscribe.
  - >-
    Tag following. This subscribes to SOURCES; `/follow-tag` is unchanged and the
    scope's tag-mode (ALL vs EXPLICIT) is not altered.
  - >-
    Subscribing to soft-deleted or (optionally) disabled sources. The set is the
    currently-live source rows; deleted rows are excluded.
acceptance:
  - >-
    A new command (proposed name `/follow-all-sources`) subscribes the caller's
    scope to every currently-live (deleted_at IS NULL) source in one call,
    upserting `source_subscription` rows. The operation is idempotent: a re-run
    adds only the sources not already followed and never duplicates. The reply
    names how many sources were newly subscribed and the total now followed.
  - >-
    Permission mirrors `/add-source`: allowed for an approved (non-probation)
    user in their own DM scope, and for a group admin (or bot admin) for the
    group scope. Probation refusal is `InboundRouter`'s centralized step-5 gate,
    NOT handler logic: like `/add-source`, the new command is simply not in
    `CommandPermissions`' allowed-during-probation set, so the router emits the
    probation reply before the handler runs (a handler-level probation check
    would be dead defensive code per §No defensive code). The subscription is
    written for the correct `(scope_kind, scope_id)`.
  - >-
    After running it, `/summary` for that scope returns content from the newly
    followed sources (subject to the scope's existing tag-mode and the `-w`
    window) — i.e. it resolves the empty-feed cliff for a fresh approved user
    without adding feeds one-by-one.
  - >-
    Catalogue hygiene: the new command is added to (a) the command-index marker
    in docs/spec/commands.md, (b) the permission matrix in
    docs/design/03-commands.md (so the M1-527 parity test stays green), (c)
    `HelpCommandHandler.CATALOGUE` so it is discoverable via `/help`, and (d) the
    closed privileged-tier set: the group-admin bullet of commands.md §"Closed
    list of privileged-tier commands" gains `/follow-all-sources` in groups AND
    `LlmOutputSanitizer.CLOSED_LIST` gains the matching entry (the
    matchSetEqualsSpecClosedList parity test enforces equality; M1-575
    precedent). New en+cs bundle keys (D43): the reply/error keys plus a
    `HELP_CMD_FOLLOW_ALL_SOURCES_SHORT` catalogue line. LangCommandIT's cs `/help`
    ordered-equality golden (line 136) gains the new line; HelpCommandHandlerTest
    asserts the command appears in `/help` for an approved user.
  - >-
    FollowAllSourcesCommandHandlerIT (named *IT per the M1-495 naming ratchet —
    a DB-backed @QuarkusTest must run in the failsafe phase) covers: a fresh
    approved scope goes from 0 subscriptions to N; a re-run is a no-op
    (idempotent); the new command is excluded from the probation allowlist
    (assert `CommandPermissions.allowedDuringProbation("follow-all-sources")` is
    false, matching `/add-source` — the router-level refusal, no handler
    probation logic); the reply count is correct.
  - "`mvn verify` is green from the repo root (new tests pass; full suite passes)."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandlerIT.java — 0→N, idempotent re-run, probation-allowlist exclusion, count."
  modifies:
    - "docs/spec/commands.md — command-index + catalogue + closed-list group-admin bullet; docs/design/03-commands.md — permission matrix; BundleKeys.java — reply/error + HELP_CMD_FOLLOW_ALL_SOURCES_SHORT constants; en/cs bundles — the new values; HelpCommandHandler.java — CATALOGUE entry; HelpCommandHandlerTest.java — positive /help assertion; LangCommandIT.java — cs /help ordered golden gains one line; LlmOutputSanitizer.java — CLOSED_LIST gains \"/follow-all-sources\" (parity, M1-575 precedent; LlmOutputSanitizerTest untouched). No shared-helper extraction (the new handler owns its set-based bulk-subscribe SQL); AddSourceCommandHandler untouched."
  preserves:
    - "per-scope opt-in subscription model; /unfollow-source as the per-source undo; all tests green on main; M1-527 parity green."
spec_refs:
  - "docs/spec/commands.md §Source management"
  - "docs/design/03-commands.md §3.2 Permission matrix"
decision_refs:
  - "D45 (slow-start probation gate)"
reviews:
  - round: 1
    date: 2026-07-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 792
      removed: 28
escalations:
  - date: 2026-07-06
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (second files_scope gap, found mid-implementation; not a reviewer
      verdict). Two under-scoped couplings — the same set the M1-575 /pending
      ticket hit (see memory note "new-admin-command-couplings"):
      (a) Closed-list parity: /follow-all-sources is group-admin-gated in
      groups, the same tier as /add-source and /unfollow-source, both of which
      are in commands.md §"Closed list of privileged-tier commands" — the
      load-bearing set LlmOutputSanitizerTest.matchSetEqualsSpecClosedList
      asserts equal to LlmOutputSanitizer.CLOSED_LIST. The spec bullet gains
      the entry (commands.md is in scope), so CLOSED_LIST must gain one line —
      LlmOutputSanitizer.java is NOT in files_scope. M1-575 precedent: same
      one-line addition, no LlmOutputSanitizerTest change (parity is
      mechanical). budget 10 -> 11.
      (b) IT naming ratchet (M1-495): IntegrationTestNamingGuardTest fails any
      NEW *Test.java containing @QuarkusTest + injected DataSource (the
      UnfollowSourceCommandHandlerTest template predates the ratchet, frozen in
      the baseline). The planned DB-backed test must be named
      FollowAllSourcesCommandHandlerIT.java — a files_scope path swap, no
      count change (M1-575's test was PendingCommandHandlerIT for this reason).
  - date: 2026-07-06
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-implementation files_scope gap, not a reviewer verdict).
      Acceptance item 4 mandates new en+cs bundle keys. The success reply must
      report counts (newly-subscribed + total) — no existing BundleKeys constant
      has that shape — so a new constant is unavoidable, and BundleLoaderTest
      reflects over BundleKeys requiring an en AND cs entry per constant. Editing
      BundleKeys.java is therefore mandatory, but BundleKeys.java is NOT in
      files_scope. Total touched files stay at 7: AddSourceCommandHandler.java is
      listed but unused (no shared-helper extraction — the subscribe SQL lives in
      SourceUpsertService, and the bulk path is a distinct set-based statement the
      new handler owns), so the correct fix swaps that path for BundleKeys.java.
      This is a files_scope path omission, not a numeric budget overrun.
overrides: []
revisions:
  - date: 2026-07-06
    reason: |
      budget-breach refine (user chose "widen for /help", budget 10). The original
      files_scope omitted BundleKeys.java, mandatory for the new bundle-key
      constants (BundleLoaderTest reflects over BundleKeys and requires en+cs
      entries per constant). User opted to also make /follow-all-sources
      discoverable via /help, pulling in HelpCommandHandler.java (CATALOGUE entry),
      HelpCommandHandlerTest.java (positive assertion), and LangCommandIT.java —
      whose cs /help ordered-equality golden (line 136) gains one line (the M1-419
      catalogue-mirror coupling). AddSourceCommandHandler.java removed from scope
      (unused — no shared-helper extraction; the bulk path is a distinct set-based
      statement the new handler owns). files_budget 7 → 10; files_scope now 10 paths.
    prior_files_budget: 7
    prior_files_scope:
      - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandler.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
      - infochat-provider/src/main/resources/bundles/en.properties
      - infochat-provider/src/main/resources/bundles/cs.properties
      - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandlerTest.java
      - docs/spec/commands.md
      - docs/design/03-commands.md
  - date: 2026-07-06
    reason: |
      budget-breach refine #2 (user chose "apply both"). Two under-scoped
      couplings found mid-implementation — the M1-575 /pending set:
      (a) LlmOutputSanitizer.java added to files_scope (budget 10 → 11): the
      spec's closed privileged-tier list gains `/follow-all-sources` in groups
      (group-admin tier, same as /add-source and /unfollow-source), and
      matchSetEqualsSpecClosedList enforces CLOSED_LIST equality — one-line
      runtime addition, LlmOutputSanitizerTest untouched (parity is mechanical).
      (b) Test path swapped FollowAllSourcesCommandHandlerTest.java →
      FollowAllSourcesCommandHandlerIT.java: the M1-495 IntegrationTestNamingGuard
      ratchet fails any NEW *Test.java with @QuarkusTest + injected DataSource;
      DB-backed tests must run in the failsafe (*IT) phase.
    prior_files_budget: 10
    prior_files_scope_note: |
      The post-refine-#1 10-path list (commit bab62419): as the current list but
      with FollowAllSourcesCommandHandlerTest.java (not ...IT.java) and without
      LlmOutputSanitizer.java.
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-07-06
  verdict: WARN
  warnings:
    - "Acceptance item 3 (/summary returns newly-followed content) is not bound to a named test — fold a subscription-completeness assertion into FollowAllSourcesCommandHandlerTest."
    - "Shared-helper-extraction note names AddSourceCommandHandler.java, but the subscribe-upsert SQL lives in SourceUpsertService.java (out of scope) — resolved: no extraction, the new handler owns its bulk-subscribe SQL (6 files, under budget)."
    - "New command is not wired into HelpCommandHandler.CATALOGUE / BundleKeys /help pattern; no CI impact (parity test checks command-index vs CDI beans only) — staying in ticket scope, /help discoverability is a candidate follow-up."
  blockers: []
---

# M1-576: Bulk-follow all sources

## Context

Bootstrap seeding creates the global `source` rows (so the collector fetches
them) but creates **no** `source_subscription` rows for anyone. `/summary` is
per-scope and filtered to `source_id IN (subscribed sources)`, so a freshly
approved user's `/summary` is empty even though thousands of posts exist — and
the only remedy today is `/add-source` one feed at a time (77 in the live
deployment). There is no `/follow-source` and no bulk follow.

This ticket adds an explicit, opt-in bulk subscribe so an approved user can get
a useful feed in one command. (Observed live this session: with 0 subscriptions
`/summary` returned "no posts"; subscribing the scope to all sources made
204 posts eligible in a 7-day window.)

## Approach

- New `/follow-all-sources` handler that upserts a `source_subscription` row for
  the caller's scope for every live source, idempotently (skip already-followed).
- The new handler owns its bulk-subscribe SQL: one set-based
  `INSERT INTO source_subscription (scope_kind, scope_id, source_id, added_by)
  SELECT ?, ?, id, ? FROM source WHERE deleted_at IS NULL
  ON CONFLICT (scope_kind, scope_id, source_id) DO NOTHING`. No shared-helper
  extraction — the single-row upsert lives in `SourceUpsertService` and the bulk
  shape differs; duplicating one small statement beats coupling.
- Permission and scope resolution mirror `/add-source` exactly (approved self in
  DM; group admin for group).

## Notes

- **Opt-in, not default.** This deliberately does not auto-subscribe at
  registration — that default-feed policy is a separate decision. Keeping it an
  explicit command preserves the per-scope opt-in model while removing the
  one-by-one toil.
- Pairs with the existing `/unfollow-source <id>` (per-source undo) and
  `/unfollow-tag --all`.
- New command → update the M1-527 command-index marker in the same change.
- **Joins the closed privileged-tier set** (budget-breach refine #2). As a
  group-admin-gated dual command it belongs in commands.md §"Closed list of
  privileged-tier commands" (group-admin bullet) and, by the
  matchSetEqualsSpecClosedList parity test, in `LlmOutputSanitizer.CLOSED_LIST`
  — so LLM output naming the command is stripped, same as `/add-source`.
- **Discoverable via `/help`** (budget-breach refine, user choice). Wired into
  `HelpCommandHandler.CATALOGUE` with a `HELP_CMD_FOLLOW_ALL_SOURCES_SHORT` line.
  This couples to `LangCommandIT`'s cs `/help` ordered-equality golden (line 136,
  which asserts the full command list) and to `HelpCommandHandlerTest` — both
  updated here (the M1-419 catalogue-mirror pattern).
- **Probation is a router gate, not handler logic.** `InboundRouter` step 5
  refuses any command not in `CommandPermissions.ALLOWED` (a spec-closed
  allowlist, §Slow-start tier) before dispatch, exactly as it already does for
  `/add-source`. The handler therefore adds NO probation check; the "probation
  is refused" property is proven by the command's absence from that allowlist
  (asserted via `CommandPermissions` in the test). Grounding confirmed
  `AddSourceCommandHandler` has no probation check either.
