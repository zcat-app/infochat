---
id: M1-483
title: "/group-timezone: missing-arg wrong error + zone work before the auth gate"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "The /follow-tag /unfollow-tag normalization fix (that is M1-489)."
  - "The source commands (RemoveSource/SourceDisable/SourceEnable) — the original report's 12#F5 (bot admin blocked by the group-admin pre-check) was FALSIFIED: isGroupAdmin() short-circuits to true when user.is_admin, so a bot admin is never blocked. No change there."
acceptance:
  - >-
    /group-timezone with a missing or blank timezone argument returns an
    argument/usage error, not ERROR_GROUP_TIMEZONE_NOT_ADMIN
    (GroupTimezoneCommandHandler.java:78-81 currently maps missing-arg to the
    not-admin reply because parseTimezone returns null and the null path falls
    through to the not-admin branch).
  - >-
    /group-timezone resolves the actor and the authorization gate BEFORE running
    ZoneId.of validation and the full-zone fuzzy-suggestion scan
    (currently :84-113 run the zone work first, so any group member can force the
    full IANA-zone fuzzy scan with an invalid tz before auth).
  - >-
    Tests cover: missing-arg /group-timezone returns the usage error; an
    unauthorized /group-timezone is rejected before zone validation runs.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneAuthOrderTest.java"
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

# M1-483: /group-timezone — missing-arg wrong error + zone work before the auth gate

## Context

From `/deep-code-review full` (2026-06-27), report
`12-main-infochat-provider-02.md` findings F3 and F6 (re-verified by a
falsification pass at HEAD). Two `GroupTimezoneCommandHandler` defects:

- **F3** — `/group-timezone` with no argument returns
  `ERROR_GROUP_TIMEZONE_NOT_ADMIN` (`GroupTimezoneCommandHandler.java:78-81`):
  `parseTimezone` returns null for a missing arg and the null path falls through
  to the not-admin branch, misleading the user about why it failed.
- **F6** — the same handler runs `ZoneId.of` + `fuzzySuggestions`
  (`:84-113`, iterating all IANA zone ids) *before* actor/admin resolution
  (`:100-113`), so any group member can force the full fuzzy scan pre-auth.

The report's third finding (**12#F5** — source commands blocking a bot admin via
the group-admin pre-check) was **falsified** and dropped: `isGroupAdmin()`
returns true immediately when `user.is_admin`
(`RemoveSourceCommandHandler.java:351` and siblings), so a bot admin is never
blocked. See out_of_scope.

## Acceptance

See frontmatter. Fix the missing-arg error mapping and move the authorization
gate ahead of zone validation; cover each with a test.

## Out-of-scope

See frontmatter. Tag normalization is M1-489; the source commands are correct as
written (12#F5 falsified).

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 12#F3, 12#F6 (12#F5
  dropped after falsification).
- Early-return guard-clause ordering (CLAUDE.md §Early return) is the shape: gate
  authorization first, then validate the argument.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-483-*.md
```
