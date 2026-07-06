---
id: M1-576
title: "/follow-all-sources: bulk-subscribe an approved scope to all live sources"
status: pending
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandlerTest.java
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
    Catalogue hygiene: the new command is added to the command-index marker in
    docs/spec/commands.md and the permission matrix in docs/design/03-commands.md
    so the M1-527 parity test stays green. New en+cs bundle keys (D43).
  - >-
    FollowAllSourcesCommandHandlerTest covers: a fresh approved scope goes from
    0 subscriptions to N; a re-run is a no-op (idempotent); the new command is
    excluded from the probation allowlist (assert
    `CommandPermissions.allowedDuringProbation("follow-all-sources")` is false,
    matching `/add-source` — the router-level refusal, no handler probation
    logic); the reply count is correct.
  - "`mvn verify` is green from the repo root (new tests pass; full suite passes)."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowAllSourcesCommandHandlerTest.java — 0→N, idempotent re-run, probation-allowlist exclusion, count."
  modifies:
    - "docs/spec/commands.md — command-index + catalogue; docs/design/03-commands.md — matrix; en/cs bundles. AddSourceCommandHandler only if a shared subscribe-upsert helper is extracted."
  preserves:
    - "per-scope opt-in subscription model; /unfollow-source as the per-source undo; all tests green on main; M1-527 parity green."
spec_refs:
  - "docs/spec/commands.md §Source management"
  - "docs/design/03-commands.md §3.2 Permission matrix"
decision_refs:
  - "D45 (slow-start probation gate)"
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
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
- Reuse the same subscribe-upsert path `/add-source` uses; if worthwhile,
  extract a tiny shared helper rather than duplicating the SQL.
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
- **Probation is a router gate, not handler logic.** `InboundRouter` step 5
  refuses any command not in `CommandPermissions.ALLOWED` (a spec-closed
  allowlist, §Slow-start tier) before dispatch, exactly as it already does for
  `/add-source`. The handler therefore adds NO probation check; the "probation
  is refused" property is proven by the command's absence from that allowlist
  (asserted via `CommandPermissions` in the test). Grounding confirmed
  `AddSourceCommandHandler` has no probation check either.
