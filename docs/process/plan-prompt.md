# Plan-writer subagent prompt template

Used when `/m1-tick start <id>` encounters a ticket with `complexity: high`. The skill spawns the custom `plan-writer` subagent (`Agent(subagent_type: "plan-writer")`, defined at `.claude/agents/plan-writer.md`) with the prompt below to produce an implementation outline before any code is written. The successful outline is Written by the subagent to a sidecar file (`target/m1-tick-outline-{ID}.md`); the ticket frontmatter gains an `outline_file:` pointer so future Reads of the ticket don't drag the outline body back into context. The developer (the main conversation) reads the sidecar before touching code. An OUTLINE FAILED block is returned inline (not Written to the sidecar) so the skill can record the escalation and the user can resolve via the standard escalation menu.

The `plan-writer` agent has `Read, Grep, Glob, Write` capability — the Write capability is what the sidecar pattern requires. The built-in `Plan` subagent type is read-only and cannot Write the sidecar, which is why the procedure uses `plan-writer` instead.

---

## Template

```
You are producing an implementation outline for a single ticket that
has been classified `complexity: high`. You have NO conversation
context; everything you know is in the ticket and the spec/design
files it references. You will NOT write any code. Your output is a
markdown outline that the developer (a separate agent in the main
conversation) will follow.

Ultrathink before you produce the outline. The ticket is classified
`complexity: high` precisely because the work has cross-cutting
consequences, multiple call sites, or non-obvious ordering pitfalls.
Spend the thinking budget on (a) ground-truth verification of every
claim the outline would make about existing code, (b) the API-surface
audit of every class cited in acceptance items, (c) the implementation
order's failure modes if a step is taken before its prerequisite, and
(d) which risks rise to "name in the outline" vs which rise to
"OUTLINE FAILED". Shallow planning here costs the developer rounds.

The ticket is: {{TICKET_ID}}
Ticket file (Read this with the Read tool): {{TICKET_FILE_PATH}}
Outline file (Write the full implementation outline here using the
Write tool BEFORE returning your short chat reply — but ONLY in the
success case; on OUTLINE FAILED, return the failure block inline as
your chat reply and do NOT Write the outline file):
{{OUTLINE_FILE_PATH}}

Paths above are repo-relative unless prefixed with `/`. The Read and
Write tools accept either form when the agent's CWD is the repo root.

---

## Inputs to load

1. Use the Read tool to read the ticket file at {{TICKET_FILE_PATH}}.
2. Before evaluating anything else, verify the ticket file you Read
   has `id: {{TICKET_ID}}` in its YAML frontmatter. If the frontmatter
   id does not match, abort the audit, return an OUTLINE FAILED block
   citing the mismatch as your chat reply, and do NOT Write the
   outline file.
3. For each entry in the ticket's `spec_refs:` list (frontmatter),
   resolve the anchor yourself using the same algorithm documented in
   `docs/process/clarity-prompt.md` §"`spec_refs` anchor resolution
   algorithm". Resolve every spec_ref before beginning the outline.
   On ANCHOR-NOT-FOUND or AMBIGUOUS for a load-bearing anchor —
   i.e. the implementer cannot proceed without re-reading the cited
   section — escalate via OUTLINE FAILED. Otherwise flag as a risk
   and proceed.

---

## What the outline must cover

1. **File-level plan.** List every production file you propose to
   create or modify, in implementation order. Annotate each with
   "create" or "modify" and one-line purpose. Do NOT exceed the
   ticket's `files_budget` (hard ceiling — if your plan exceeds it,
   name the surplus and recommend escalation BEFORE the developer
   starts).

2. **Test-scaffolding plan.** List the test files to add or modify.
   For each, name the test cases that must exist for the acceptance
   criteria to be checkable. If the ticket modifies any pre-existing
   tests, confirm that the ticket body (§Out-of-scope or §Notes)
   names them; if not, OUTLINE FAILED with reason
   "Test-modification authorization missing — escalate via /m1-tick
   escalate refine".

3. **Cross-cutting concerns.** From the spec_refs, identify any
   invariants the implementation must preserve that aren't obvious
   from the immediate diff (e.g. per-(user, scope) isolation rules,
   determinism boundaries, plain-text formatting, audit log
   coverage). Name them so the developer keeps them in mind.

4. **Implementation order with rationale.** Why this order? Where
   would a wrong order produce broken intermediate states (e.g.
   "create the migration before the entity, otherwise integration
   tests will fail")?

5. **Risks and escalation triggers.** Anything you noticed that
   suggests the ticket should be re-scoped, blocked on a missing
   dependency, or carries hidden complexity not reflected in
   `complexity: high`. Each risk → which escalation reason fits
   (refine | decompose | defer | spec-amend).

6. **Out-of-scope reminders.** Echo the ticket's `out_of_scope`
   list so the developer keeps it visible while implementing.

---

## What the outline MUST NOT do

- No code. Not even pseudocode beyond a method-signature outline.
- No "alternatives considered" content (that lives in the ticket
  body or commit message; this outline is the chosen path only).
- No restating the spec verbatim. Reference spec_refs by section.
- No prescribing style choices that differ from existing code in
  the touched files. Match what's there.

---

## Return format — success path

Use the Write tool to write the markdown body below to
{{OUTLINE_FILE_PATH}}. This is the developer's reading material;
the file is the only artifact the main session will see in detail.

```markdown
## Implementation outline (M<N>-NNN, generated by plan-writer subagent on YYYY-MM-DD)

### Files to touch ({M} of {files_budget})
- create: `<path>` — <one-line purpose>
- modify: `<path>` — <one-line purpose>
- ...

### Tests
- add: `<path>` — covers <acceptance item N>
- modify: `<path>` — authorized in ticket body
- ...

### Cross-cutting concerns
- <invariant> — <why it matters here>
- ...

### Implementation order
1. <step> — <rationale>
2. <step> — <rationale>
- ...

### Risks
- <risk> — escalation: <refine | decompose | defer | spec-amend>
- ...

### Out-of-scope (echoed from ticket)
- <path or feature>
- ...
```

After the Write call returns, send exactly these three lines as your
chat reply — nothing else, no preamble, no postscript:

OUTLINE: PASS
Outline file: {{OUTLINE_FILE_PATH}}
Risks: <integer count>

The skill parses these three lines literally; the full outline body
lives only in the file you Wrote, which the skill records via an
`outline_file:` pointer in the ticket frontmatter.

---

## Return format — failure path

If the outline fails any check (files_budget exceeded;
test-modification unauthorized; ANCHOR-NOT-FOUND on a load-bearing
spec_ref), return the following block inline as your chat reply. Do
NOT call the Write tool. Do NOT write the outline file.

```markdown
## OUTLINE FAILED — escalation recommended

REASON: <one paragraph>
SUGGESTED ESCALATION: <refine | decompose | defer | spec-amend>
EVIDENCE: <pointer to the failing item in the ticket or spec>
```

The skill detects the OUTLINE FAILED block by the leading
`## OUTLINE FAILED` heading and surfaces it as a pre-implementation
escalation rather than letting the developer start.
```

---

## Skill responsibilities (what `/m1-tick start` does around the prompt)

1. After the clarity pre-flight passes, check the ticket's `complexity` field. If `complexity: high`, continue; otherwise skip the Plan step.
2. Pre-allocate the outline sidecar path at `target/m1-tick-outline-{{ID}}.md`.
3. Substitute three placeholders: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (the ticket file under `docs/plan/<milestone>/tickets/`), and `{{OUTLINE_FILE_PATH}}`. No content placeholders — the subagent loads the ticket and each cited spec file via Read in its own fresh context.
4. Spawn `Agent(subagent_type: "plan-writer", prompt: <substituted>, description: "Implementation outline M<N>-NNN")`. Foreground.
5. Branch on the chat reply:
   - If the reply begins with `## OUTLINE FAILED`, fire `escalate` with `reason: outline-fail`. The commit message records the escalation; git log is the audit trail. Do NOT add an `outline_file:` pointer; the sidecar was not written.
   - Otherwise the reply begins with `OUTLINE: PASS`; parse the three-line reply for the outline file path and risk count. Set the ticket frontmatter `outline_file: target/m1-tick-outline-M<N>-NNN.md` as a one-line pointer.
