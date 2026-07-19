---
id: M1-662
title: "Reconcile the ticket-frontmatter and verdict-check record registries"
status: abandoned
abandoned_reason: superseded
created: 2026-07-19
last_updated: 2026-07-19
blocked_by: []
files_budget: 4
files_scope:
  - docs/process/workflow.md
  - docs/process/ticket-template.md
  - .claude/skills/m1-tick/subcommands/escalate.md
  - .claude/skills/m1-tick/subcommands/review.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Retrofitting escalations:/revisions: onto tickets that already escalated
    without them, or stripping them from the M1-600..611 batch that carries
    them. This ticket fixes the FORWARD contract; existing tickets are
    history and are left byte-untouched.
  - >-
    Changing what any check DOES, or adding/removing a check. This is a
    registry-and-schema reconciliation only. ASSERTION-ADEQUACY-CHECK's
    behavior was settled by M1-661 and is not reopened.
  - >-
    docs/process/reviewer-prompt.md. Its lifecycle-exemption paragraph
    (line ~123) mentions escalations:/revisions: only to EXEMPT the ticket
    file from budget checks; that exemption is correct under either
    resolution, so no edit is owed and opening the file invites drift.
  - >-
    Any change under a module src/ tree, any *.java file, pom.xml, or
    src/**/resources/**. Documentation and skill-procedure only.
acceptance:
  - >-
    A single resolution is chosen for whether escalations: and revisions: are
    ticket frontmatter, and ALL of docs/process/workflow.md,
    docs/process/ticket-template.md, and
    .claude/skills/m1-tick/subcommands/escalate.md state it consistently. No
    file asserts the opposite of another. The chosen resolution is recorded
    with its rationale in the commit message.
  - >-
    If the resolution is "not frontmatter", escalate.md no longer instructs
    writing escalations:/revisions:, AND its two functional readers are
    rewritten to a source that survives a fresh session: the override
    eligibility gate (step 5 arm 2, currently "Read the most recent entry in
    escalations: and inspect its reason") and the refine-arm dispatch (step 5
    arm 1, currently "dispatch on the prior escalation reason (read from the
    most recent escalations: entry)"). A resolution that deletes the field
    while leaving either reader pointing at it is NOT acceptable.
  - >-
    If the resolution is "yes, frontmatter", workflow.md:119 and
    ticket-template.md's "Dynamic fields" comment are corrected to list both
    fields, and ticket-template.md gains the two keys with their schema.
  - >-
    .claude/skills/m1-tick/subcommands/review.md step 4's per-check
    extraction list names SPEC-CONFORMANCE-CHECK, which it currently omits
    despite reviewer-prompt.md defining it and emitting it in every verdict
    file.
  - >-
    review.md step 5's reviews[].checks YAML block carries a key for every
    check the step-4 list extracts — currently five keys for what will be
    seven names (spec_conformance and assertion_adequacy are both absent).
    Key naming follows the existing snake_case convention.
  - >-
    A one-line note in review.md records that an unregistered check still
    gates correctly — reviewer-prompt.md §Verdict rules makes any *-CHECK
    FAIL force the VERDICT line to at least REWORK, and the skill parses that
    line — so the omission costs the per-check RECORD, not the gate. This
    keeps a future reader from re-escalating the severity, as happened when
    this was first reported.
test_plan:
  adds: []
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

# M1-662: Reconcile the ticket-frontmatter and verdict-check record registries

> **ABANDONED — superseded by the m1-tick cutover (2026-07-19).** Both
> defects this ticket was filed to fix are resolved by the cutover, which
> reshapes the surrounding surface more deeply than a registry-only patch
> could:
>
> - **(1) The `escalations:`/`revisions:` frontmatter contradiction** is
>   resolved by cutover 2/3: `escalations:`/`revisions:` are definitively
>   NOT frontmatter (git log is the refine/escalation history), and the two
>   functional readers in `escalate.md` (override-eligibility gate,
>   refine-arm dispatch) now read the durable `escalation_reason:` scalar,
>   which survives a cold session resume. `regen-status.py`,
>   `ticket-template.md`, `abort.md`, `reviewer-prompt.md`, and
>   `workflow.md` were all made consistent with this in the same commit.
> - **(2) The unregistered `SPEC-CONFORMANCE-CHECK`** in `review.md` is
>   handled by cutover 3/3, which reshapes the reviewer's check registry.
>
> Retained as history (not reopenable). Its Notes' recommended resolution
> ("make the fields schema") was NOT taken: the durable-scalar resolution
> is smaller AND honors the "git is the audit trail" doctrine (workflow.md
> §Process doctrine point 5) that the schema route would have bent.

## Context

Two record registries in the M1 process docs disagree with themselves. Both
were found during M1-661 implementation (2026-07-19) and deliberately not
fixed inline, because neither traced to that ticket's acceptance and §Surgical
changes forbids the drive-by.

**(1) The frontmatter-schema contradiction.** Four documents disagree on
whether `escalations:` and `revisions:` are ticket-frontmatter fields:

| Document | Position |
|---|---|
| `docs/process/workflow.md:119` | NOT in the schema — "git log is the audit trail" |
| `docs/process/ticket-template.md` (Dynamic fields comment) | NOT in the schema |
| `.claude/skills/m1-tick/subcommands/escalate.md` | Live — step 1 writes `escalations:`, refine arm snapshots `revisions:` |
| `docs/process/reviewer-prompt.md:123` | Live — lists both as fields the skill mutates |

The obvious reading — "the skill file went stale when the schema changed" —
is **false**, and the ticket states so explicitly to stop the next person
re-deriving it. `workflow.md`'s line is the OLDER artifact (`c7dc5ce0`,
2026-05-23); `escalate.md` was revised three times after it (`3ae93ae3`
2026-06-09, `ce7aac32` 2026-06-12, `8ceebac5` 2026-07-07) and kept the
instruction each time. Neither side is simply stale.

This is load-bearing, not cosmetic. `escalate.md` **functionally reads** the
field it is told to write: the override eligibility gate inspects "the most
recent entry in `escalations:`" to decide whether option 2 is permitted, and
the refine arm dispatches on the same entry's `reason`. On M1-661 the
`SKILL.md` precedence rule (workflow.md wins over a skill file) was followed
and the field was skipped; the refine completed only because the driving
session still held the reason in context. A session resuming that escalation
cold would have had nothing to read. Whichever way the resolution goes, the
writers and the readers must end up pointing at the same place.

**(2) The unregistered check.** `review.md` step 4 enumerates the check names
it extracts from the verdict file, and step 5 maps them into
`reviews[].checks`. `SPEC-CONFORMANCE-CHECK` is in neither, though
`reviewer-prompt.md` defines it and the reviewer emits it in every verdict.
M1-661 added `ASSERTION-ADEQUACY-CHECK` to the step-4 list — its acceptance
scoped it there — so step 5 is now short two keys.

Severity is **bookkeeping, not gate bypass**, and the ticket says so because
the first report of it overstated the case. `reviewer-prompt.md` §Verdict
rules makes any `*-CHECK: FAIL` force the VERDICT line to at least REWORK,
and the skill parses that VERDICT line, so an unregistered check still stops
a bad ticket. What is lost is the per-check record in frontmatter — the
audit trail that answers "did spec-conformance pass on this ticket?" after
the fact. Early tickets (M1-005, M1-007, M1-007b) do carry
`spec_conformance:` in `reviews[].checks`, so the field was recorded in
practice before the documented schema dropped it.

## Why one ticket and not two

Both defects are the same shape: a registry of what-gets-recorded that
disagrees with the code path that records it, in the same four-file process
surface, fixable by the same reader in one pass. Splitting them would double
the gate overhead for two small documentation diffs that touch adjacent
sections of `review.md` and `escalate.md`.

## Acceptance

Mirrors the YAML list. In prose: pick ONE answer on the frontmatter question
and make workflow.md, ticket-template.md, and escalate.md agree on it — with
the constraint that deleting the field obliges rewriting escalate.md's two
functional readers, since a writer-less reader is worse than the present
inconsistency. Then register `SPEC-CONFORMANCE-CHECK` in review.md step 4,
give step 5 a `checks:` key for every extracted name, and record the
one-line note that an unregistered check still gates.

## Out-of-scope

Covered in the YAML. The load-bearing exclusions: **no retrofitting** of
existing tickets in either direction — M1-600..611 keep the fields they
carry, and tickets that escalated without them are not backfilled; both are
history. **reviewer-prompt.md is not opened** — its mention of the two fields
sits inside the lifecycle-path budget exemption, which holds under either
resolution.

## Notes

Non-binding rationale.

`mvn verify` is N/A: the diff touches none of `*.java`, `pom.xml`, or
`src/**/resources/**`, making it fully inert under the m1-tick inert-diff
gate ("`mvn verify` scope — Java/config/DB only"). Record the inert-N/A round
log; do not run the suite for a green tick that covers nothing.

`risk: medium` because the resolution changes an escalation-path contract
that every future escalation traverses, and because the tempting fix (delete
the field, it's "not in the schema") is the one that silently breaks resume.
`complexity: low` reflects the four-file documentation diff.

Recommended resolution, non-binding: make the fields **schema**, correcting
workflow.md and ticket-template.md. It is the smaller edit, it matches what
the newer document and the actual tooling already do, and it preserves the
resume path without rewriting two readers. The competing argument — git log
genuinely is a durable audit trail — is real but does not answer the reader
problem, because `git log` is not what escalate.md's override gate consults.
