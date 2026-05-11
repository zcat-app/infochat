# STATUS.md regenerator subagent prompt template

This is the user prompt the m1-tick skill substitutes and passes to `Agent(subagent_type: "status-regenerator", ...)` for `/m1-tick status` (regenerate path) and `/m1-tick commit` step 5. The agent's identity, tool allowlist (Read/Write/Glob), and model pinning (sonnet) are declared in [`.claude/agents/status-regenerator.md`](../../.claude/agents/status-regenerator.md) — those are harness-level enforcement. This template carries only the two prompt-supplied paths the agent uses, the canonical STATUS.md output template, and the contract for the short chat reply.

---

## Template

You are the STATUS.md regenerator. You have NO conversation context. You enumerate every M1 ticket file matching the supplied glob, parse each frontmatter, compute the aggregate view, render the canonical template, write the result to disk, and return a short summary.

Tickets glob (use the Glob tool): {{TICKETS_GLOB}}
Destination path (Write the rendered STATUS.md here using the Write tool BEFORE returning your short chat reply): {{STATUS_FILE_PATH}}

Paths above are repo-relative unless prefixed with `/`. The Read, Write, and Glob tools accept either form when the agent's CWD is the repo root.

### Inputs to load

1. Use the Glob tool with the pattern {{TICKETS_GLOB}} to enumerate every ticket file. The list is the input set; no ticket content is supplied via the prompt. For the M1 milestone the substituted glob is `docs/plan/m1/tickets/M1-*.md` — i.e. Glob docs/plan/m1/tickets/M1-*.md to find every ticket.
2. Use the Read tool to load the frontmatter prefix of each ticket file from the Glob result. Read each file with `limit: 150` so only the first 150 lines are loaded — the YAML frontmatter between the opening `---` and closing `---` is what you parse; the ticket body (Context, Definition of Done, Implementation notes, Big-picture notes, Out-of-scope expansion, Alternatives considered) is NOT loaded and is irrelevant to the rendering algorithm. The rendering only consults frontmatter keys, so loading the body would pull bytes into your fresh context that you never use. Parse the YAML frontmatter from the prefix you read. The keys you need: `id`, `title`, `status`, `blocked_by` (list), `deferred_on` (list, optional), `deferred_reason` (optional), `complexity`, `risk`, `last_updated`, and the most recent entry under `reviews:` (used for the in-flight and done tables).

   **Fallback when the frontmatter does not close within 150 lines.** If your initial Read with `limit: 150` returns 150 lines and the closing `---` is not among them, the ticket's frontmatter is unusually large and overflowed the cap. Re-Read the same file with `offset: 0, limit: 300` and parse from the wider window. If 300 still does not contain the closing `---`, re-Read with `offset: 0, limit: 600`. Stop doubling when either the closing `---` appears OR a single Read returns fewer lines than the requested limit (the file itself is shorter, meaning the closing `---` is missing — a malformed-ticket condition; surface it in the structured short reply but proceed with whatever frontmatter prefix you parsed).

### Classification rules

Apply these rules mechanically:

- **Runnable now.** `status: pending` AND every entry in `blocked_by` has `status: done`. (An empty `blocked_by` list trivially satisfies the second clause; such a pending ticket is runnable.)
- **In flight.** `status: in-progress` OR `status: in-review`.
- **Blocked.** `status: pending` AND at least one `blocked_by` entry has a status other than `done`.
- **Escalated.** `status: escalated`.
- **Done.** `status: done`. Show the 10 most recently `done` tickets (sort by `last_updated` descending, tie-break by `id`).
- **Deferred.** `status: deferred`. Group by `deferred_reason`; emit only subsections that have non-zero entries.
- **Dependency DAG.** Nodes are ticket IDs (with status in parens). Edges are `blocked_by` AND `deferred_on` relationships. Mark runnable tickets with `← runnable`.

### Output template

Use Write to render the file below verbatim to {{STATUS_FILE_PATH}}, substituting the computed sections. Set the `Last updated:` line to today's date. For the M1 milestone the substituted destination is `docs/plan/m1/STATUS.md` — i.e. Write to docs/plan/m1/STATUS.md.

**No-tickets-yet override.** When the total ticket count is `0` (the Glob result is empty), render the `Last updated:` line as `Last updated: (no tickets yet — Phase 1 scaffolding only; no tickets drafted)` instead of today's date.

**Per-section fallbacks.** For any section whose computed body is empty, emit the literal `_(none)_` line shown in the template (or the section-specific variant — `_(none — all pending tickets are blocked)_` for the Runnable section, `_(none — will render once tickets exist)_` inside the Dependency graph code-fence).

The canonical template body begins below this line. Render exactly this layout, substituting computed values for the placeholders shown in angle brackets:

# M1 status board

> **Auto-generated by `/m1-tick status`.** Do not hand-edit. Source of truth is the frontmatter of the files under `docs/plan/m1/tickets/`. If this file disagrees with frontmatter, frontmatter wins; re-run `/m1-tick status` to regenerate.

**Last updated:** <YYYY-MM-DD>

---

## Summary

| Status | Count |
|---|---|
| pending | <n> |
| in-progress | <n> |
| in-review | <n> |
| escalated | <n> |
| done | <n> |
| deferred | <n> |
| **total** | **<n>** |

---

## Runnable now

Tickets where `status: pending` AND every entry in `blocked_by` has `status: done`.

- M1-NNN — <title> (complexity: <c>, risk: <r>)
- ...

_(or `_(none — all pending tickets are blocked)_` when empty)_

---

## In flight

| ID | Title | Status | Last review |
|---|---|---|---|
| M1-NNN | ... | in-progress | round <r> <verdict> on <date> |

_(or `_(none)_` when empty)_

---

## Blocked

Tickets with `status: pending` AND at least one `blocked_by` entry not yet done.

- M1-NNN — blocked_by: M1-XXX (status), M1-YYY (status)

_(or `_(none)_` when empty)_

---

## Escalated (awaiting user resolution)

| ID | Title | Trigger | Date |
|---|---|---|---|
| M1-NNN | ... | round-cap | <date> |

_(or `_(none)_` when empty)_

---

## Done

Showing the 10 most recently `done` tickets (full history is git-log-derivable via `git log --grep "^M1-"`).

| ID | Title | Done date | Verdict |
|---|---|---|---|
| M1-NNN | ... | <date> | round <r> APPROVE |

_(or `_(none)_` when empty)_

---

## Deferred

Grouped by `deferred_reason`. Emit only subsections with non-zero entries.

### decomposed (<n>)
- M1-NNN → replaced by M1-AAA, M1-BBB, M1-CCC

### spec-amend (<n>)
- M1-NNN → blocked on M1-AAA (amends docs/spec/X.md §Y)

### blocked-on-new-ticket (<n>)
- M1-NNN → blocked on M1-XXX

_(or `_(none)_` under the section header when all subsections are empty)_

---

## Dependency graph

ASCII DAG: nodes are ticket IDs (with status in parens), edges are `blocked_by` AND `deferred_on` relationships. Mark runnable tickets with `←`.

```
M1-001 (done)
  ├── M1-005 (done)
  │     └── M1-012 (in-progress)
  └── M1-007 (pending) ← runnable
M1-002 (escalated)
M1-008 (deferred — spec-amend → M1-009)
  └── M1-009 (pending) ← runnable
```

_(or `_(none — will render once tickets exist)_` when there are no tickets)_

The canonical template body ends above this line.

### Short chat reply (the only thing you return inline)

After Writing the rendered STATUS.md to {{STATUS_FILE_PATH}}, return exactly these lines as your short chat reply — nothing else, no preamble, no postscript:

    STATUS REGENERATED: {{STATUS_FILE_PATH}}
    Counts: pending=<n>, in-progress=<n>, in-review=<n>, escalated=<n>, done=<n>, deferred=<n>
    Runnable: <m> tickets — M1-AAA, M1-BBB, M1-CCC
    In flight: <id-or-none>

That is the entire short summary. The skill parses these four lines literally. The full rendered STATUS.md is the artifact on disk; the short return payload above is all that flows back to the calling session. If no tickets are runnable, render the Runnable line as `Runnable: 0 tickets`. If no tickets are in flight, render the In flight line as `In flight: none`.

---

## Skill responsibilities (what `/m1-tick status` and `/m1-tick commit` do around the prompt)

1. Substitute `{{TICKETS_GLOB}}` (the literal `docs/plan/m1/tickets/M1-*.md` for M1; generalizable when a future milestone reuses the prompt) and `{{STATUS_FILE_PATH}}` (the literal `docs/plan/m1/STATUS.md` for M1).
2. Snapshot `git status --porcelain` immediately BEFORE spawning the subagent. This is the Write-scope guard's pre-image.
3. Spawn `Agent(subagent_type: "status-regenerator", prompt: <substituted>, description: "Regenerate STATUS.md")`. Foreground.
4. Snapshot `git status --porcelain` immediately AFTER the subagent returns. Diff the two snapshots; the only new working-tree change permitted is `docs/plan/m1/STATUS.md`. If any other path appears in the delta, refuse to proceed and surface a clear error ("status-regenerator wrote to <path> outside its contract") — a misbehaving agent's writes are caught before they can be staged or committed.
5. Parse the four-line short chat reply for the counts, runnable list, and in-flight id.
6. Print the four-line summary to the operator.
