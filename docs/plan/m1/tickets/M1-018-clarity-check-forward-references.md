---
id: M1-018
title: Clarity check validates forward references to ticket IDs
status: pending
created: 2026-05-12
last_updated: 2026-05-12
blocked_by: []
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

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-018: Clarity check validates forward references to ticket IDs

## Context

> **Skeleton.** Sizing, `acceptance`, `out_of_scope`, and `spec_refs` are
> intentionally empty. The user must flesh them out before
> `/m1-tick start M1-018` will pass the clarity pre-flight.

The clarity pre-flight currently validates **backward** references — it
checks that `spec_refs:` resolves to real anchors in `docs/spec/` and
that `decision_refs:` are well-formed. It does NOT validate **forward**
references: prose that names a future ticket ("follow-up ticket
filed once M1-XXX lands", "see the M1-NNN umbrella", "deferred to
M1-YYY") is unchecked. When the named ticket doesn't actually exist as
a file, the deferral is a prose promise with no tracked work behind it.

This was the proximate failure mode that surfaced during `M1-009`:

- `M1-005` "Alternatives considered" said: *"Cleaner to land
  Collector-owned migrations now, **move them after M1-007a as a small
  follow-up**."*
- `M1-007a` `out_of_scope` line 30 confirmed: *"the
  migration-move-into-core follow-up is a SEPARATE ticket filed once
  M1-007a lands."*
- M1-007a landed. The follow-up ticket was never filed. The promise
  rotted into a pom comment.
- `M1-009` hit the consequence: Provider's tests had no schema
  source, and the developer had to detect the gap by hand and
  escalate via `defer` onto a freshly-filed `M1-017`.

The lesson: when a ticket defers work to a named future ticket, that
future ticket should *exist* (even as a skeleton with empty acceptance
criteria) before the deferring ticket can reach `done`. A clarity
rule that flags missing forward references is the forcing function.

## Definition of Done

> Provisional — refine before `/m1-tick start`.

- The clarity pre-flight (run by `/m1-tick start` via the
  `clarity-reviewer` subagent — prompt at
  `docs/process/clarity-prompt.md`) gains a new check that scans the
  ticket body and frontmatter for forward references to ticket IDs
  matching the pattern `M<N>-<digits>[<suffix>]`.
- For each forward reference found, the check resolves it against
  `docs/plan/<milestone>/tickets/M<N>-*.md`. References that don't
  resolve to a real file are flagged.
- The check distinguishes legitimate forward references (named
  tickets that exist) from broken ones (named tickets that don't).
  Self-references (e.g., a ticket mentioning its own ID) are not
  flagged.
- The check applies to ticket frontmatter fields known to carry
  forward references — at minimum `out_of_scope:`, `blocked_by:`,
  `decomposed_from:`, `deferred_on:`, `spec_amend_parent:`,
  `remediates:`, `replaced_by:` — and to the prose sections of the
  body (Context, Implementation notes, Big-picture notes,
  Out-of-scope expansion, Alternatives considered).
- The check's failure mode is **WARN** (not FAIL) by default — a
  forward reference to a not-yet-filed ticket isn't necessarily a
  block; sometimes the deferring ticket is filed first and the
  blocker right after. The WARN surfaces the gap to the operator so
  they can decide. **FAIL** is reserved for forward references in
  frontmatter fields that are load-bearing for the runnable check
  (e.g., `blocked_by:` naming an ID that doesn't exist — a runnable
  ticket cannot block on a phantom).
- The clarity prompt template
  (`docs/process/clarity-prompt.md`) is updated to instruct the
  subagent to perform the check.
- A new self-test ticket fixture (or test fixture under
  `docs/plan/m1/tickets/` if practical) demonstrates both pass and
  fail paths.

## Implementation notes

> Provisional hints; not a recipe.

- The clarity pre-flight runs as a subagent (`clarity-reviewer`) at
  `/m1-tick start`. The subagent reads the ticket and the cited spec
  files, then writes a verdict file. The new check would extend the
  subagent's prompt to enumerate the forward references and resolve
  each against the tickets directory.
- The check is **prompt-level**, not a Java/code-level addition.
  This ticket modifies process documentation
  (`docs/process/clarity-prompt.md`) and possibly the skill files
  under `.claude/skills/m1-tick/`, not application code.
- Forward-reference scanning is a simple regex over the ticket file:
  `M[0-9]+-[0-9]+[a-z]*\b`. The subagent then globs the tickets
  directory and checks set membership.
- Consider whether the check should also surface forward references
  in `decision_refs:` (e.g., "D44") — out of scope for this ticket
  unless the user explicitly extends it; decisions live in a
  different doc (`docs/spec/decisions.md`) and have their own
  validation surface.

## Big-picture notes

- This is a **process improvement**, not a feature. It applies
  retroactively to all future tickets; existing pending tickets are
  not re-validated unless `/m1-tick start` runs against them.
- The change is forward-compatible: existing tickets with valid
  forward references (the ticket exists) continue to pass; only
  tickets with broken forward references (the named ticket
  doesn't exist) start surfacing WARNs.
- Related lessons from the same root cause:
  - **Pom and code comments referencing future state are
    documentation debt unless backed by a ticket.** This is the
    structural reason `infochat-provider/pom.xml`'s "migrations
    move to infochat-core after M1-007a" comment rotted. A
    code-comment-level check is out of scope here, but the same
    discipline applies: if a comment names a future ticket, that
    ticket should exist.
  - **The "separate ticket" escape hatch is convenient but cheap.**
    Authors reach for it to keep their current ticket surgical;
    the cost is silent loss of the deferred work. This ticket
    closes the loophole at the clarity-check boundary.

## Out-of-scope expansion

> To be filled in. Likely includes:
>
> - any change to the reviewer subagent (`docs/process/reviewer-prompt.md`).
>   Forward-reference scanning is a *pre-flight* concern, not a
>   per-diff concern; the reviewer's job is to validate the diff
>   against acceptance, not to police the ticket's own references.
> - any retroactive scan of existing tickets. The check fires only
>   when a ticket reaches `/m1-tick start`.
> - decision_refs validation (deferred to a future ticket if
>   wanted).

## Authorized test changes

- (none yet — to be filled in before start)

## Alternatives considered

- **Enforce at commit time (post-implementation) instead of at
  pre-flight (pre-implementation).** Rejected: the forcing function
  is most useful *before* implementation starts, when the author can
  still create the missing follow-up ticket cheaply. Catching the
  gap at commit time means the implementation already happened
  against an unstable premise.
- **Enforce at reviewer time.** Rejected: the reviewer's job is the
  diff against the acceptance criteria. Adding a ticket-reference
  scan to the reviewer dilutes its focus and runs the check N rounds
  per ticket instead of once.
- **Manual discipline only ("authors should file the follow-up
  ticket").** Rejected: that was the de-facto policy that produced
  this bug. Process changes that rely on humans noticing a missed
  step have a known failure mode.
