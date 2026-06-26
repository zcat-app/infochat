---
id: M1-459
title: Note the confirm prompt for /clear and /unfollow-tag --all in USER_GUIDE
status: done
created: 2026-06-26
last_updated: 2026-06-26
clarity_check:
  date: 2026-06-26
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-06-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 13
      removed: 9
blocked_by: []
files_budget: 1
files_scope:
  - USER_GUIDE.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any code, test, or migration                  # doc-only
  - ADMIN_GUIDE.md confirm list                    # admin commands; separate (M1-458 covers /quarantine reject)
  - re-documenting the general confirm mechanism   # already in docs/spec/commands.md §Surface conventions
acceptance:
  - "USER_GUIDE.md §The essentials — the `/clear` cheat-sheet row includes a confirmation note (e.g. '(asks to confirm)')"
  - "USER_GUIDE.md §The essentials — the `/unfollow-tag --all` cheat-sheet row includes a confirmation note (e.g. '(asks to confirm)')"
  - "No file other than USER_GUIDE.md is touched"
  - mvn verify is green
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Per-scope tag preferences
decision_refs: []
---

# M1-459: Note the confirm prompt for /clear and /unfollow-tag --all in USER_GUIDE

## Context

The guide-doc audit found USER_GUIDE.md flags the confirmation prompt for
`/forget` (line 279) but not for the two other user-facing commands that also
require `confirm`: `/clear` (`docs/spec/commands.md:586` "Requires confirm";
`ClearCommandHandler:88-100` implements it via `ConfirmStateService`) and
`/unfollow-tag --all` (`docs/spec/commands.md:578` "Requires confirm";
`UnfollowTagCommandHandler` implements it as `UnfollowTagAllConfirm`). The
omission is not a contradiction — the guide never claims they skip confirm —
but the cheat sheet is the canonical reference and reads inconsistently. This
ticket closes that gap. Doc-only; no behavior changes.

## Acceptance

- USER_GUIDE.md §"The essentials (command cheat sheet)" — the `/clear` row
  (under "Language and control") gains a short confirmation note so a reader
  knows it prompts before erasing context.
- USER_GUIDE.md §"The essentials (command cheat sheet)" — the
  `/unfollow-tag --all` row (under "Tuning your topics") gains the same note.
- No file other than USER_GUIDE.md is modified.
- `mvn verify` is green (no code touched — a regression-free no-op).

## Out-of-scope

Doc-only: no code, test, or migration. The ADMIN_GUIDE.md confirmation list is
a separate surface (admin commands) and `/quarantine reject` there is handled
by M1-458 — do not touch it here. The general confirm mechanism is already
documented in `docs/spec/commands.md` §Surface conventions; this ticket only
adds the per-command note to the two user cheat-sheet rows, not a re-explanation.

## Notes

- Wording should match the existing house style — `/forget`'s note ("It asks
  you to confirm first.") is the reference; a parenthetical "(asks to confirm)"
  on the cheat-sheet row is sufficient and consistent with the table's terse
  style.
- The §Advanced "`/clear` vs `/forget`" bullet (lines ~302-304) may optionally
  gain the same note for consistency, but the binding requirement is the two
  cheat-sheet rows.
- Both commands are confirmed in code, so no spec/code reconciliation is needed
  here — unlike M1-458, this is purely the guide catching up to existing,
  correct behavior.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-459-user-guide-confirm-notes.md
```
