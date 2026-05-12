---
id: M1-018
title: Clarity check validates forward references to ticket IDs
status: done
created: 2026-05-12
last_updated: 2026-05-13
blocked_by: []
clarity_check:
  date: 2026-05-13
  verdict: WARN
  warnings:
    - "Acceptance item 9 cites the wrong verdict file path: 'docs/plan/m1/clarity/M1-NNN-clarity.md' should be 'target/m1-tick-clarity-<ID>.txt' per docs/process/clarity-prompt.md §Skill responsibilities (line 224). The directory docs/plan/m1/clarity/ does not exist. The behavioral assertion in item 9 is still testable; only the parenthetical path description is wrong. The implementer should note this discrepancy and verify against the actual verdict file location."
  blockers: []
reviews:
  - round: 1
    date: 2026-05-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 113
      removed: 26
files_budget: 5
files_scope:
  - docs/process/clarity-prompt.md
  - docs/process/ticket-template.md
  - .claude/agents/clarity-reviewer.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - docs/process/reviewer-prompt.md (forward-reference scanning is pre-flight only, not per-diff; the reviewer's job is to validate the diff against acceptance, not to police the ticket's own references)
  - docs/process/redteam-prompt.md (threat-actor pass reads docs/spec/security.md, not ticket forward refs)
  - docs/process/status-regen-prompt.md (status regenerator reads frontmatter only and writes STATUS.md; ticket-content forward refs are not its concern)
  - .claude/skills/m1-tick/subcommands/start.md (the dispatch path is unchanged; only the prompt template substituted into the subagent is updated)
  - retroactive scanning of already-pending tickets (the check fires only when /m1-tick start runs against a ticket; existing pending tickets are not re-validated until they reach start)
  - decision_refs validation (D-prefixed references like "D44" live in docs/spec/decisions.md with their own validation surface — separate concern, separate ticket if wanted)
  - spec_refs validation against docs/spec/ anchors (already covered by the existing clarity backward-reference check)
  - code-comment-level forward-reference checks (e.g., the infochat-provider/pom.xml comment that referenced "after M1-007a" — comments in source files are out of clarity's reach; a separate "comment-rot" ticket would own that surface)
  - any Java/Maven change (this ticket is prompt-template and process-documentation only)
acceptance:
  - "docs/process/clarity-prompt.md adds a check named FORWARD-REFERENCE-CHECK (or a self-describing equivalent) instructing the clarity-reviewer subagent to (a) scan the ticket frontmatter and body for substrings matching the regex M[0-9]+-[0-9]+[a-z]*, (b) resolve each match against the tickets glob docs/plan/<milestone>/tickets/M<N>-*.md, and (c) classify each as resolved or unresolved"
  - "The check exempts self-references — a ticket of id M1-NNN mentioning M1-NNN in its own body or frontmatter does not count as an unresolved forward reference"
  - "The check exempts placeholder IDs documented in docs/process/workflow.md §Ticket-ID placeholder convention (e.g., M<N>-NNN, M<N>-AAA, M<N>-BBB, M<N>-XXX, M<N>-YYY) — these are syntactic placeholders, not real ticket references"
  - "The check returns FAIL when an unresolved forward reference appears in a frontmatter field that is load-bearing for runnable-state computation: blocked_by, deferred_on, decomposed_from, replaces, replaced_by, spec_amend_parent, remediates"
  - "The check returns WARN (not FAIL) when an unresolved forward reference appears anywhere else — out_of_scope: prose, the ticket body sections (Context, Implementation notes, Big-picture notes, Out-of-scope expansion, Alternatives considered, Authorized test changes), or any field outside the load-bearing list above"
  - "docs/process/ticket-template.md adds an inline comment near out_of_scope: documenting that forward-reference resolution applies (so future ticket authors understand the rule when drafting)"
  - "The clarity verdict output format documented in docs/process/clarity-prompt.md gains a section for FORWARD-REFERENCE-CHECK results so the reviewer's verdict file consistently includes the new check's findings"
  - "Running /m1-tick start against M1-018 itself passes the new check (this ticket's own forward refs — to M1-005, M1-007a, M1-009, M1-017 — all resolve to existing files; this is the self-test that proves the check works)"
  - "Running /m1-tick start against a manually-constructed ticket with a deliberately broken forward reference (e.g., 'blocked_by: [M1-999]') returns clarity FAIL with the unresolved reference cited in the blockers list (the verdict file under docs/plan/m1/clarity/M1-NNN-clarity.md mentions M1-999 and the missing-file diagnosis)"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
---

# M1-018: Clarity check validates forward references to ticket IDs

## Context

The clarity pre-flight currently validates **backward** references —
it checks that `spec_refs:` resolves to real anchors in `docs/spec/`
and that `decision_refs:` are well-formed. It does NOT validate
**forward** references: prose that names a future ticket
("follow-up ticket filed once M1-XXX lands", "see the M1-NNN
umbrella", "deferred to M1-YYY") is unchecked. When the named
ticket doesn't actually exist as a file, the deferral is a prose
promise with no tracked work behind it.

This was the proximate failure mode that surfaced during `M1-009`:

- `M1-005` "Alternatives considered" said: *"Cleaner to land
  Collector-owned migrations now, **move them after M1-007a as a
  small follow-up**."*
- `M1-007a` `out_of_scope` line 30 confirmed: *"the
  migration-move-into-core follow-up is a SEPARATE ticket filed
  once M1-007a lands."*
- M1-007a landed. The follow-up ticket was never filed. The promise
  rotted into a pom comment.
- `M1-009` hit the consequence: Provider's tests had no schema
  source, and the developer had to detect the gap by hand and
  escalate via `defer` onto a freshly-filed `M1-017`.

The lesson: when a ticket defers work to a named future ticket,
that future ticket should *exist* (even as a skeleton with empty
acceptance criteria) before the deferring ticket can reach `done`.
A clarity rule that flags missing forward references is the
forcing function.

## Definition of Done

- `docs/process/clarity-prompt.md` adds a new check —
  `FORWARD-REFERENCE-CHECK` — instructing the clarity-reviewer
  subagent to scan the ticket for forward references to ticket IDs
  and resolve each against the tickets directory.
- The check distinguishes:
  - **Resolved** — the named ticket exists as a file under
    `docs/plan/<milestone>/tickets/`. Reported as informational
    (no WARN, no FAIL).
  - **Self-reference** — the named ticket is the one being checked.
    Exempt; no flag.
  - **Placeholder** — the named ID is one of the documented
    placeholders (`M<N>-NNN`, `M<N>-AAA`, `M<N>-XXX`, etc.). Exempt;
    no flag.
  - **Unresolved, load-bearing** — the named ticket does not exist,
    and the reference is in a frontmatter field used to compute
    runnable state (`blocked_by`, `deferred_on`, `decomposed_from`,
    `replaces`, `replaced_by`, `spec_amend_parent`, `remediates`).
    Reported as **FAIL** — clarity blocks `/m1-tick start`.
  - **Unresolved, prose** — the named ticket does not exist, and
    the reference is in `out_of_scope:` prose or in the body.
    Reported as **WARN** — clarity does not block, but the operator
    sees the gap.
- `docs/process/ticket-template.md` gains an inline comment
  documenting the rule so future ticket authors understand
  forward-reference resolution applies.
- The clarity verdict output (described in
  `docs/process/clarity-prompt.md`) includes a
  `FORWARD-REFERENCE-CHECK` section so the new check's findings
  reach the verdict file consistently.
- Self-test: running `/m1-tick start M1-018` itself passes the
  new check — every forward reference in this ticket
  (`M1-005`, `M1-007a`, `M1-009`, `M1-017`) resolves.
- Negative-test: a manually-constructed ticket with `blocked_by:
  [M1-999]` (or any non-existent ID in a load-bearing field)
  triggers a FAIL with the unresolved reference cited.

## Implementation notes

- The clarity pre-flight runs as a subagent (`clarity-reviewer`)
  at `/m1-tick start`. The subagent reads the ticket and the
  cited spec files, then writes a verdict file. The new check
  extends the subagent's prompt; the change is **prompt-level**,
  not Java/code-level.
- Forward-reference scanning is a simple regex pass over the
  ticket file: `M[0-9]+-[0-9]+[a-z]*\b`. The subagent already
  has Read and Glob tools (per `.claude/agents/clarity-reviewer.md`);
  globbing `docs/plan/<milestone>/tickets/M<N>-*.md` to compute
  the existence set is straightforward.
- Placeholder IDs (`M<N>-NNN`, etc.) come from
  `docs/process/workflow.md` §Ticket-ID placeholder convention.
  The clarity prompt should cite that section so the subagent
  knows what to exempt without re-deriving the placeholder rules.
- Load-bearing frontmatter fields are enumerated in
  `docs/process/ticket-template.md` (under the Lineage and
  Dynamic field comments). The clarity prompt should list them
  explicitly rather than infer them.
- The check's verdict output format should follow the pattern
  already established by other clarity checks (each check
  produces a section in the verdict file with PASS/WARN/FAIL
  and a one-line rationale per finding).

## Big-picture notes

- This is a **process improvement**, not a feature. It applies
  to all future ticket starts; existing pending tickets are not
  re-validated until they reach `/m1-tick start`.
- The change is forward-compatible: existing tickets with valid
  forward references continue to pass; only tickets with broken
  forward references start surfacing WARNs (or FAIL when the
  reference is load-bearing).
- Related lessons from the same root cause that are NOT in this
  ticket's scope:
  - **Pom and code comments referencing future state are
    documentation debt unless backed by a ticket.** The
    `infochat-provider/pom.xml` comment that referenced "after
    M1-007a" is an example. A code-comment-level scanner is
    out of scope here; a separate "comment-rot" ticket would
    own that surface if wanted.
  - **The "separate ticket" escape hatch is convenient but
    cheap.** Authors reach for it to keep their current ticket
    surgical; the cost is silent loss of the deferred work.
    This ticket closes the loophole at the clarity-check
    boundary; the higher-level discipline (think hard before
    deferring; file the follow-up ticket immediately) remains
    a human responsibility.

## Out-of-scope expansion

- **Reviewer prompt changes.** Forward-reference scanning is a
  *pre-flight* concern, not a per-diff concern. The reviewer's
  job is to validate the diff against the acceptance criteria;
  adding a ticket-reference scan to the reviewer dilutes its
  focus and re-runs the check on every review round. Clarity
  runs once per `start`; the reviewer runs once per round.
- **Threat-actor / redteam prompt.** The threat-actor pass reads
  `docs/spec/security.md` and the diff. Ticket-internal
  references are outside its surface.
- **Status-regenerator prompt.** The regenerator reads
  frontmatter only and writes `STATUS.md`. Ticket body forward
  references are not its concern.
- **Skill subcommand routing.** The `/m1-tick start` dispatch
  is unchanged. Only the prompt template substituted into the
  clarity-reviewer subagent is updated. `.claude/skills/m1-tick/
  subcommands/start.md` does NOT need changes — the change is
  contained in the prompt template the subagent receives.
- **Retroactive scanning.** Existing pending tickets are not
  re-validated. The check fires when `/m1-tick start` runs.
- **decision_refs validation.** D-prefixed references like
  "D44" live in `docs/spec/decisions.md` with their own
  conventions. A separate ticket can extend the check to
  decisions if wanted; this ticket is ticket-ID-only.
- **spec_refs anchor validation.** Already covered by the
  existing clarity backward-reference check.
- **Code-comment forward-ref scan.** Comments in `.java` /
  `.xml` / `.properties` files are outside clarity's reach.
  This ticket is bounded to ticket files.
- **Application code.** This is documentation + prompt-template
  only. No Java or Maven changes.

## Authorized test changes

- (none — this ticket modifies process documentation only. The
  Maven test suite is not affected; `mvn verify` outcome is
  unchanged. The functional test of the new check is
  observational: running `/m1-tick start M1-018` against this
  ticket should produce a clarity verdict including a
  `FORWARD-REFERENCE-CHECK: PASS` section; running against a
  ticket with a deliberately broken reference should produce
  `FORWARD-REFERENCE-CHECK: FAIL` with the missing ID cited.)

## Alternatives considered

- **Enforce at commit time (post-implementation) instead of at
  pre-flight (pre-implementation).** Rejected: the forcing
  function is most useful *before* implementation starts, when
  the author can still create the missing follow-up ticket
  cheaply. Catching the gap at commit time means the
  implementation already happened against an unstable premise.
- **Enforce at reviewer time.** Rejected: the reviewer's job
  is the diff against the acceptance criteria. Adding a
  ticket-reference scan to the reviewer dilutes its focus and
  runs the check N rounds per ticket instead of once.
- **Manual discipline only ("authors should file the follow-up
  ticket").** Rejected: that was the de-facto policy that
  produced this bug. Process changes that rely on humans
  noticing a missed step have a known failure mode.
- **WARN-only for everything (no FAIL).** Rejected: a
  `blocked_by:` entry pointing at a non-existent ticket means
  the runnable-state computation is broken — the ticket can
  never become runnable because its blocker can never reach
  `done`. That class of broken reference must FAIL, not WARN.
- **FAIL for everything (no WARN).** Rejected: a forward
  reference in prose ("see M1-XXX umbrella for context") to a
  not-yet-filed umbrella is a legitimate planning state. The
  ticket can run; the operator just sees the missing umbrella
  flagged.
