# Session handoff — Tier 1 preparation

Paste the fenced block below as the opening message of a fresh Claude
Code session. The session will not have this conversation's context,
so the prompt is self-contained.

---

```
You are picking up the M1 milestone work on the `infochat` project. All
Tier 0 foundation tickets are done; we are about to enter Tier 1
(the MVP vertical slice). This session's job is the three-step
PREPARATION work that has to happen before Tier 1 ticket authoring
can begin. You will NOT author Tier 1 tickets in this session — that
is a separate session, opened by the handoff you produce in step 3
below.

## Current state (as of session start, 2026-05-13)

- `main` branch is clean. Latest 5 commits:
  ```
  db8aaa4 process: Drop pyyaml from regen-status.py, use stdlib only
  0d17a30 process: Fix stale subagent reference in status.md header
  c1dc6ec process: Replace status-regenerator subagent with deterministic script
  bb252ee process: Add spec/process prefix split — pure-doc edits bypass ticket flow
  a3ded94 M1-009: Advisory-lock single-instance enforcement + heartbeat
  ```
- 22 ticket files exist under `docs/plan/m1/tickets/`. STATUS.md says:
  pending=2 (M1-019, M1-020), done=20, no in-flight, no escalated.
- The runnable list claims M1-019 is runnable. It isn't, semantically.
  See step (a) below.

## Three things you do, in this order

### (b) FIRST — Update `docs/plan/m1/drafts/session-grouping-plan.md` to reflect ID drift

The plan in that file is stale. It was authored when M1-008..M1-018
were unassigned. Since then, M1-010..M1-018 were consumed by process
work on the `/m1-tick` skill itself (those are correctly committed
but they shifted the ID allocation), and M1-016 + M1-017 were used
for NOLOGIN-on-roles and Flyway-relocation rather than the planned
messaging-adapter umbrella + first-command tickets.

What to do:
- Read `docs/plan/m1/drafts/session-grouping-plan.md` end-to-end.
- Read each ticket's frontmatter to confirm what each ID actually
  shipped (use `scripts/regen-status.py` output or just grep `^id:`
  / `^title:` across `docs/plan/m1/tickets/`).
- Rewrite the file's "Current state" section and the Tier 1, Tier 2,
  Tier 3 group tables to reflect actual allocations. M1-008 and
  M1-008a/b/c are still reserved (no files exist on disk for those
  IDs). M1-016a/b/c are NOT available — M1-016 itself was consumed.
  The messaging umbrella will need a fresh ID at the tail.
- Add a paragraph naming the convention going forward: process-only
  edits ship as `process:` commits (see `CLAUDE.md` §"Commit
  prefixes") so they no longer consume ticket IDs. Spec-only edits
  ship as `spec:` commits for the same reason.
- Commit as a single `process:` commit on `main`. Subject:
  `process: Realign session-grouping-plan with actual ID drift`.

DO NOT renumber any existing ticket files. Done tickets have their
IDs baked into commit subjects on `main` (e.g. `M1-009: Advisory-lock
...`); renaming them would orphan `git log --grep "^M1-NNN"` history
the workflow depends on.

### (a) SECOND — Defer M1-019 and M1-020 to post-MVP

Read the two pending tickets:
- `docs/plan/m1/tickets/M1-019-stdout-log-key-redaction.md`
- `docs/plan/m1/tickets/M1-020-exception-message-sanitization.md`

Both are security hardening that protect code paths the MVP does not
yet contain. M1-019 redacts API keys from logs — but no code logs
API keys until LLM calls land in Tier 1. M1-020 sanitizes exception
messages on messaging-adapter intake — but those adapters do not
exist until Tier 1's T1-E group. They are flagged "runnable" because
their `blocked_by` lists are empty, not because the work is valuable
now.

What to do:
- Edit each ticket's frontmatter:
  - `status: deferred`
  - `last_updated: 2026-05-13` (today)
  - Add `deferred_reason: post-mvp-hardening` (a new category;
    `scripts/regen-status.py` groups deferred tickets by whatever
    reason string appears, so this just creates a new subsection
    under the Deferred header).
  - Leave `deferred_on:` empty for now. Once the Tier 1 LLM ticket
    (under T1-D) is authored, update `deferred_on:` on M1-019 to
    point at it; do the same for M1-020 against the T1-E messaging
    umbrella. The current state ("deferred without a specific
    blocker") is honest about why the work is paused.
- Run `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
  docs/plan/m1/STATUS.md` and verify the Deferred section now shows
  the two tickets under a `post-mvp-hardening (2)` subheading. The
  Runnable section should now read `_(none — all pending tickets
  are blocked)_`.
- Commit as a single `process:` commit on `main`. Subject:
  `process: Defer M1-019/M1-020 — post-MVP hardening, no Tier-1
  code paths yet`.

Note: the ticket files themselves are under `docs/plan/m1/tickets/`,
not code. Status changes on pending tickets are pure metadata. They
qualify as `process:` per `docs/process/workflow.md` §"Non-ticket
commits", rule (1).

### (c) THIRD — Draft the T1-A schema handoff

Tier 1 group A is the MVP schema umbrella: M1-008 (umbrella) + M1-008a
(identity + audit + last-admin trigger) + M1-008b (sources + tags) +
M1-008c (posts + subscriptions + scope_preferences + cross-cutting
isolation IT). All four IDs are reserved/unused.

What to do:
- Read the two Tier 0 handoff files as canonical templates for
  structure and depth:
  - `docs/plan/m1/drafts/handoff-tier0-group1-db-roles-and-advisory-lock.md`
  - `docs/plan/m1/drafts/handoff-tier0-group2-spi-surfaces.md`
- Read the relevant spec/design anchors and verify each exists by
  `grep -n '^## \\|^### ' <file>`:
  - `docs/spec/schema.md` §2.1, §2.2, §2.3
  - `docs/design/02-schema.md` §2.1, §2.2, §2.3
- Author the new handoff file as
  `docs/plan/m1/drafts/handoff-tier1-A-schema.md`. Match the Tier 0
  handoffs' structure exactly: a self-contained prompt that, when
  pasted into a fresh session, instructs that session to author the
  four ticket files M1-008, M1-008a, M1-008b, M1-008c under
  `docs/plan/m1/tickets/`.
- The handoff prompt itself (the fenced block inside the file)
  should:
  - State the four ticket IDs to author and their titles.
  - Cite the spec anchors with line numbers (so the authoring
    session does not have to re-verify them).
  - List the umbrella + subticket pattern (umbrella's `blocked_by`
    lists the subtickets; subtickets have empty `blocked_by`; one
    cross-cutting IT lives with the umbrella ticket).
  - Note the M1-007a + M1-007b + M1-007c precedent for naming
    conventions (lowercase suffix letters; same digit slot).
  - Identify the umbrella's integration-test location (per the
    "T1-A umbrella IT location" open question in
    `session-grouping-plan.md`, decide for `infochat-core/src/test/`
    since the schema is shared by collector and provider).
- Commit as a single `process:` commit on `main`. Subject:
  `process: Author T1-A schema handoff (M1-008 + M1-008a/b/c)`.

The handoff is a planning artifact; it does NOT modify any ticket
file, source file, or spec file. The session that consumes it
(NOT this session) will author the four ticket files.

## What you do NOT do in this session

- Do not author M1-008 or its subtickets. That is the next session's
  job, triggered by your output from step (c).
- Do not implement any Tier 1 code. No `src/` edits anywhere.
- Do not renumber, rename, or git mv any done ticket file.
- Do not modify CLAUDE.md, docs/process/workflow.md, the m1-tick
  skill, the engineering rules, or any spec/design file. Steps (b)
  and (c) edit planning artifacts under `docs/plan/m1/drafts/`;
  step (a) edits two pending ticket files. Nothing else.
- Do not run `mvn verify`. Nothing in this session touches Java code.

## Engineering rules in force

The full rules live in `CLAUDE.md` §"Engineering rules" and
`docs/process/engineering-rules-verbatim.md`. The two that bite for
this session:

- **Surgical changes.** Each commit touches only the files its task
  needs. No "while we're here" tidying of adjacent files in
  `session-grouping-plan.md` (step b), the two pending tickets (a),
  or the drafts directory (c).
- **Push back when simpler exists.** If you find a materially simpler
  way to express any of the three tasks before committing it, say so
  in chat before applying it.

## Outputs

By the end of this session, `git log --oneline -10` on `main` should
show three new `process:` commits at the top, in this order:
- `process: Author T1-A schema handoff (M1-008 + M1-008a/b/c)`
- `process: Defer M1-019/M1-020 — post-MVP hardening, no Tier-1 code paths yet`
- `process: Realign session-grouping-plan with actual ID drift`

And `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
docs/plan/m1/STATUS.md` should print:

    Counts: pending=0, in-progress=0, in-review=0, escalated=0, done=20, deferred=2
    Runnable: 0 tickets
    In flight: none

At that point the workflow's natural next step ("`/m1-tick next`
shows no runnable tickets") signals to the user that Tier 1 ticket
authoring is the unblocked work. They will open a fresh session and
paste the T1-A handoff produced in step (c).
```

---

## Quick-reference checklist for the operator

When you open the fresh session and paste the block above:

- [ ] Step (b) commit lands first — realign the plan doc.
- [ ] Step (a) commit lands second — defer M1-019/020. STATUS.md
      regenerates to show 0 runnable, 2 deferred.
- [ ] Step (c) commit lands third — T1-A handoff file exists.
- [ ] Three new `process:` commits on `main` total. No code change,
      no spec change.
- [ ] Working tree clean.

If the session deviates (touches code, tries to author M1-008,
renumbers anything), it has misread the brief — abort and start over
with the same prompt.
