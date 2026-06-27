---
id: M1-489
title: "/follow-tag and /unfollow-tag skip spec-mandated tag normalization"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "/saved [tag] — its positional arg filters FREE-FORM personal tags (commands.md: personal tags are free-form and never join the controlled vocabulary), so normalizing it would BREAK case-preserving matches; deliberately not changed (deep-review 12#F2 verified as a non-issue)."
  - "/summary <tag> — already normalizes via SummaryArgs; not re-touched except to share the helper."
acceptance:
  - >-
    /follow-tag and /unfollow-tag apply the same controlled-vocabulary
    normalization pipeline (trim → NFC → lowercase → char-class) that
    docs/spec/commands.md mandates before the vocabulary lookup, instead of the
    current raw exact-match (FollowTagCommandHandler.java:167-183 returns the raw
    token; lookupTagId does WHERE name = ? exact; /unfollow-tag is identical). A
    case/Unicode variant of a controlled-vocabulary tag now resolves.
  - >-
    The normalize/validate logic lives in one shared helper consumed by
    /follow-tag, /unfollow-tag, and the existing /summary path (lifted from
    SummaryArgs), so the four-site pipeline the spec describes is applied from a
    single source.
  - >-
    Tests assert that a mixed-case / NFC-variant controlled-vocabulary tag is
    followed and unfollowed successfully (previously a silent miss).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/FollowTagNormalizationTest.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-489: /follow-tag and /unfollow-tag skip spec-mandated tag normalization

## Context

From `/deep-code-review full` (2026-06-27), report
`11-main-infochat-provider-01.md#F1` (medium, verified at source; cross-cutting
theme CT2). `docs/spec/commands.md` §Surface conventions commits to one
trim→NFC→lowercase→char-class pipeline applied identically at the
controlled-vocabulary command sites. `/summary` (`SummaryArgs`) implements it,
but `/follow-tag` and `/unfollow-tag` do raw exact-match lookups
(`FollowTagCommandHandler.java:167-183,379-391`; `WHERE name = ?`), so
case/Unicode variants silently miss. The verification pass confirmed `/saved` is
*correctly* free-form (personal tags) and must NOT be normalized — narrowing CT2
to these two handlers plus a shared helper.

## Acceptance

See frontmatter. Normalize at `/follow-tag` and `/unfollow-tag` before the vocab
lookup, via a shared helper lifted from `SummaryArgs`; cover with a test.

## Out-of-scope

See frontmatter. `/saved` stays free-form (12#F2 verified non-issue); `/summary`
is only refactored to share the helper, no behavior change.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 11#F1 (CT2, reduced after
  verification dropped the `/saved` half).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-489-*.md
```
