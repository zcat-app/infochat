---
id: M1-526
title: "Bot-admin command to recover auto_joined_group pool"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
blocked_by:
  - M1-525
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-522
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-526: Bot-admin command to recover auto_joined_group pool

## Context

Decomposed from M1-522 (the plan-writer flagged that M1-522's four acceptance
items could not fit `files_budget: 8`). This child carries the in-band
bot-admin recovery command (M1-522 acceptance item 3): a deterministic
bot-admin command that frees `auto_joined_group` slots from chat so a flooded
global pool is recoverable in-band, not only via operator psql under the DB
owner role (M1-519 redteam Finding 2). It is `blocked_by` M1-525 because it
depends on the `auto_joined_group.removed_at` column and the
`GroupJoinRepository` freeing method M1-525 introduces.

## Acceptance

TODO — fill in before `/m1-tick start M1-526`. Sketch (from M1-522 item 3):

1. A bot-admin-facing recovery command frees `auto_joined_group` slots from
   chat so a flooded global pool is recoverable in-band. Authorization is a
   deterministic bot-admin check in Java (NEVER an LLM tool); the freeing
   privilege (a `removed_at` UPDATE) is granted to the command's role only —
   DELETE stays revoked. A NAMED test asserts an admin can recover a saturated
   pool (M1-519 redteam Finding 2).
2. `mvn -B verify` is green from the repo root.

## Out-of-scope

TODO — fill in before `/m1-tick start M1-526`. The automatic leave-detection
freeing (M1-522 items 1+2) belongs to M1-525. The D47 total caps (M1-519) are
not re-implemented.

## Notes

- A new bot-admin slash command drags in CI-enforced coupling the plan-writer
  enumerated: a handler, localized reply strings in BOTH `bundles/en.properties`
  and `bundles/cs.properties`, a `BundleKeys` constant, a new `AuditAction`
  verb, the `GroupJoinRepository` freeing/list method, AND additions to BOTH
  `docs/spec/commands.md` §"Closed list of privileged-tier commands" and
  `LlmOutputSanitizer.CLOSED_LIST` (byte-equality enforced by
  `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList`). Budget the file count
  against this floor (~7-8 files) when fleshing out.
- This is an admin-tier gate change with a DB GRANT: set
  `security_relevant: true` (triggers `/redteam`) and `risk: medium`-to-`high`
  when fleshing out. The skeleton uses template defaults.
- Consider `remediates: M1-519` once acceptance is finalized.
- Parent context: `docs/plan/m1/tickets/M1-522-auto-join-slot-freeing.md`;
  M1-519 redteam audit: `docs/plan/m1/redteam/M1-519-2026-06-29.md`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-526-*.md
```
