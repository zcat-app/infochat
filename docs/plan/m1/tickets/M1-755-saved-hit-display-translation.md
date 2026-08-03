---
id: M1-755
title: "Display-time translation of /saved list headlines"
status: pending
created: 2026-08-03
last_updated: 2026-08-03
blocked_by:
  - M1-747
files_budget: 8
files_scope: []
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The /summary and /retry leg — that is M1-747, whose
    `TranslationPipeline` display-hit entry point this ticket reuses
    rather than re-implements.
  - >-
    Translating the snapshot BODY, persisting translated text, or
    changing which saved rows are listed.
  - >-
    The digest-broadcast and degraded surfaces (M1-756).
acceptance:
  - >-
    DESIGN FIRST (this is the open question the ticket exists to answer):
    `/saved` renders `saved_post` SNAPSHOT columns and never re-resolves
    content against `post`, so the row carries no source language. Decide
    and justify: snapshot `source.language` at save time (new column,
    migration) vs. joining `post -> source` at render for language only.
    The chosen answer must respect the snapshot semantics D-decision that
    made /saved snapshot-based in the first place.
  - >-
    A `cs`-scope /saved list translates each hit headline via M1-747's
    display-hit pipeline entry point — same no-op legs, same §10 controls
    (flatten-before-sanitize, ONE field per sanitize call, re-truncate,
    marker after cut), same fallback, same cache.
  - >-
    `en` scope stays byte-identical with zero translator calls, asserted
    with a spy.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
  - D43
  - D30
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

Draft follow-up filed from M1-747's surface-binding rework (2026-08-03).
M1-747 translates the `/summary`/`/retry` flat-block headline; `/saved`
(`SavedCommandHandler`, headline at the `DisplayHeadline.of(row.title,
row.body, ...)` call) is the same reader-comprehension gap on a different
surface, split out because its `saved_post` snapshot columns carry no
source language — a schema/design question M1-747 must not absorb.

## Notes

- Draft: `files_scope`, sizing, and the design answer need filling in
  before `/m1-tick start` (the readiness pre-flight will force it).
- The snapshot-vs-join question decides whether `migration_touch` flips
  to true.
