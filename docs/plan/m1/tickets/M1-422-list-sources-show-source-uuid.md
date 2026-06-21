---
id: M1-422
title: Show the source UUID in /list-sources output
status: done
created: 2026-06-21
last_updated: 2026-06-21
blocked_by: []
clarity_check:
  date: 2026-06-21
  verdict: PASS
  warnings: []
  blockers: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ListSourcesCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ListSourcesCommandHandlerTest.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # Do NOT change how /unfollow-source or /remove-source parse <id> — they
  # already accept the source UUID; this ticket only makes that UUID visible.
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandler.java
  # source.id already exists — no Flyway migration.
  - infochat-collector/src/main/resources/db/migration/**
  # No spec/design change: commands.md §Source management already names <id> as
  # the source identifier; this only closes the discoverability gap in output.
  - docs/spec/**
  - docs/design/**
acceptance:
  - ListSourcesCommandHandler's three per-scope SELECTs (dm / group-join / all)
    add the source id column (`s.id` for the group join, `id` for the source-table
    selects) and `SourceRow` carries a `UUID id`; no other column or the per-scope
    WHERE logic changes.
  - reply.list_sources.line (en.properties + cs.properties) is extended to include
    the source UUID rendered as inline code (single backticks), so a user can copy
    it straight into `/unfollow-source <id>` and `/remove-source <id>`. The existing
    fields (display_name, identifier, kind, status) and the URL-visibility caveat
    line are preserved.
  - ListSourcesCommandHandlerTest gains a named test (e.g.
    listSourcesLineIncludesSourceUuid) asserting the rendered line contains the
    seeded source's UUID; the pre-existing list-sources assertions stay green.
  - mvn -pl infochat-provider verify is green.
test_plan:
  adds:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ListSourcesCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Source management
decision_refs:
reviews:
  - round: 1
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 39
      removed: 15
revisions: []
escalations: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-422: Show the source UUID in /list-sources output

## Context

`/unfollow-source <id>` (M1-419) and `/remove-source <id>` both parse `<id>` as a
source UUID, but `/list-sources` — the only command that enumerates a scope's
sources — renders each row as `display_name · identifier · kind · status`
(`ListSourcesCommandHandler.renderReply`, `reply.list_sources.line`) and never
shows the UUID. So a user has **no in-band way** to discover the id those commands
require (gap noted when M1-419 landed). This ticket closes that gap by adding the
source UUID to each `/list-sources` row.

## Behavioral contract

- Each `/list-sources` row gains the source UUID, rendered as inline code in single
  backticks (plain-text formatting convention), positioned so the existing fields
  stay readable — e.g. append `· id=\`{4}\`` to `reply.list_sources.line`.
- The id shown is `source.id` (the same value `/unfollow-source` / `/remove-source`
  accept). Per-scope isolation is unchanged: `/list-sources` still lists only the
  caller scope's subscriptions; the UUID is a non-secret row id and the source
  identifier/URL (already shown, already flagged admin-visible) is the sensitive
  field, not the id.

## Out-of-scope / invariants

- No change to `/unfollow-source` or `/remove-source` `<id>` parsing — they already
  accept the UUID.
- No Flyway migration — `source.id` already exists.
- No spec/design edit — `commands.md §Source management` already names `<id>`.

## Notes

- Three SELECTs to touch in `ListSourcesCommandHandler` (the group-join variant uses
  `s.id`; the dm and all variants select from `source` directly so use bare `id`).
  Add `UUID id` to the `SourceRow` record and thread it into the `MessageFormat`
  args of `renderReply`.
- Bundle keys: no new `BundleKeys` constant is needed — only the values of the
  existing `reply.list_sources.line` in `en.properties` and `cs.properties` change
  (one extra `{4}` placeholder).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-422-*.md
```
