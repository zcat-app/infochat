---
name: comments-on-a-diet
description: Comments exist only to make genuinely complex logic understandable; code should be self-documenting, javadoc stays short and useful and never replaces the documentation — details are referenced (spec/design section), not retold. Rule-doc adaptation scheduled after M1-789.
metadata:
  type: feedback
---

User direction (2026-08-07, mid-M1-789), to be folded into the rule docs
(engineering rules, CLAUDE.md §Coding style, tick comment-hygiene rule)
AFTER M1-789 lands — the adaptation itself is a process change the user
reviews:

- Comments are a comprehension aid for logic that cannot be simplified
  away (business logic, a non-obvious decision, a trap). They are not a
  narrative, not a changelog, not a substitute for documentation.
- Code should be self-documenting first; fewer comment lines also means
  smaller classes and less context burned reading them.
- Javadoc stays, but short and useful: what the contract IS, not how it
  came to be. For more detail, point at the spec section or the design /
  analysis document — never retell the story inline.
- Longer comments remain legitimate but are the rare exception.
- Clean-code-done-wrong is explicitly called out as the failure mode to
  avoid: no overnaming (stories as identifiers), no mechanical stripping
  of comments that guard a real trap.

Rationale the user gave: rationale-in-comments is fragile — any later
edit can falsify the comment's premise, and a stale comment is a
confusion liability (M1-789 itself found two: a "Runs FIRST" that had
stopped being first, and a collapse-branch premise falsified by a new
deleting pass). Related: [[spec-edits-need-approval-no-journal]] (the
same "rules here, history there" separation, applied to the spec).
