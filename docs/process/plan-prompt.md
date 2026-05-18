# Plan subagent prompt template

Used when `/m1-tick start <id>` encounters a ticket with `complexity: high`. The skill spawns the built-in `Plan` subagent (`Agent(subagent_type: "Plan")`) with the prompt below to produce an implementation outline before any code is written. The successful outline is Written by the subagent to a sidecar file (`target/m1-tick-outline-{ID}.md`); the ticket frontmatter gains an `outline_file:` pointer so future Reads of the ticket don't drag the outline body back into context. The developer (the main conversation) reads the sidecar before touching code. An OUTLINE FAILED block is returned inline (not Written to the sidecar) so the skill can append it to the ticket's `escalations:` frontmatter entry as the persistent audit trail.

The Plan subagent is a Claude Code built-in (one of `claude-code-guide`, `Explore`, `general-purpose`, `Plan`, `statusline-setup`); we do not define a custom agent for it. The prompt below is what the skill substitutes and passes as the `prompt` argument. The path-based slimming pattern mirrors [`clarity-prompt.md`](clarity-prompt.md): only path placeholders are substituted, and the subagent loads the ticket and each cited spec file via its own Read tool in fresh context.

---

## Template

```
You are producing an implementation outline for a single ticket
that has been classified `complexity: high`. You have NO conversation
context; everything you know is in the ticket and the spec/design
files it references. You will NOT write any code. Your output is a
markdown outline that the developer (a separate agent in the main
conversation) will follow.

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
   citing the mismatch ("frontmatter id was X, prompt id was
   {{TICKET_ID}}") as your chat reply, and do NOT Write the outline
   file.
3. For each entry in the ticket's `spec_refs:` list (frontmatter),
   resolve the anchor yourself using the algorithm below. The skill
   no longer pre-resolves spec_refs in the main session; you resolve
   each spec_ref in your fresh context. Every spec_ref entry has the
   form `<file-path> §<section-title>`.

   **`spec_refs` anchor resolution algorithm.** For each entry:
   1. Use the Read tool to read `<file-path>`.
   2. Walk the file line-by-line maintaining a `fence_open` flag
      (initially false). For each line, in order:
      a. If the line is a CommonMark fenced code-block delimiter —
         0–3 spaces of leading indent, then a run of three or more
         backtick characters (U+0060) or three or more tilde
         characters (U+007E), optionally followed by an info string
         such as a language tag — toggle `fence_open` and continue
         to the next line. Fence delimiter lines are themselves
         never headings, regardless of what follows the delimiter
         (a language tag after the opening run does not change
         the line's role).
      b. If `fence_open` is true after step (a), skip the line.
         Anything inside a fenced code block is content, not
         document structure — a line that reads `## Foo` inside a
         fence is a literal `## Foo` in the rendered code block, not
         a section heading. This is the rule whose absence caused
         the §5.4–§5.11 anchors in
         docs/design/05-llm-and-embeddings.md to disappear when an
         unclosed fence in §8.7.3 swallowed everything below it
         (see commit 7de7e515).
      c. If the line matches `^[ ]{0,3}#{1,6}[ \t]+\S` (a CommonMark
         ATX heading: 0–3 leading spaces, then 1–6 `#` markers, then
         one or more spaces or tabs, then a non-empty title body),
         record the line as a candidate heading with its line number
         and the count of `#` markers as its depth. Otherwise skip.
   3. For each candidate, derive the heading text by stripping, in
      order: the leading whitespace, the `#`-marker run, the
      whitespace between the markers and the title, and any trailing
      whitespace or trailing `#`-run (per CommonMark, a trailing
      run of `#` characters preceded by whitespace is decorative
      and not part of the heading text).
   4. Lowercase both the candidate heading text and the searched
      section-title; do a substring match (the searched title must
      appear as a substring of the candidate, or vice-versa for
      partial titles).
   5. If exactly one heading matches, the resolution is
      `FOUND (line N: "<heading>")`.
   6. If zero match, the resolution is `ANCHOR-NOT-FOUND`. Flag
      ANCHOR-NOT-FOUND as a risk in the outline. If the anchor is
      load-bearing on an acceptance item — i.e. the implementer
      cannot proceed without re-reading the cited section — escalate
      via OUTLINE FAILED instead.
   7. If multiple match, prefer the heading whose depth (count of
      `#` markers) is closest to the most recently resolved anchor's
      depth; tie-break by line number ascending. If still tied, the
      resolution is `AMBIGUOUS (lines: N, M, ...)`. Treat AMBIGUOUS
      the same way as ANCHOR-NOT-FOUND.

   Resolve every spec_ref before beginning the outline.

---

## What the outline must cover

1. **File-level plan.** List every production file you propose to
   create or modify, in implementation order. Annotate each with
   "create" or "modify" and one-line purpose. Do NOT exceed the
   ticket's `files_budget` (this is a hard ceiling — if your plan
   exceeds it, name the surplus and recommend escalation BEFORE
   the developer starts).

2. **API-surface audit.** For every method, attribute, or
   constructor that an acceptance criterion pins on a named
   pre-existing class (e.g. "calls `X.foo(URI)`", "passes header
   `Y` through `Z.send()`", "uses `Foo.bar(...)` for HEAD-then-GET
   fallback"), Read the cited class file and verify the method
   actually exists with the cited signature, parameter types, and
   any side-channels (header maps, response shape) the acceptance
   pins. Naming the class is not enough — the **method shape** the
   acceptance pins must already exist, OR the class must be inside
   `files_scope` so the implementer can add the missing surface.
   Mismatches are common when an acceptance item is written by
   pattern-matching against the spec rather than against the actual
   code. If you find one, OUTLINE FAILED with reason "API-surface
   mismatch": the ticket is unimplementable without (a) widening
   `files_scope` to allow modifying the class, (b) citing a
   different class that already exposes the needed method, or
   (c) dropping the acceptance item. Quote the cited class's
   actual relevant signatures in your evidence so the user can
   refine against the real surface.

3. **Test-scaffolding plan.** List the test files to add or modify.
   For each, name the test cases that must exist for the acceptance
   criteria to be checkable. If the ticket modifies any pre-existing
   tests, confirm that the "Authorized test changes" body section
   names them; if not, FAIL the outline with "Test-modification
   authorization missing — escalate via /m1-tick escalate refine".

4. **Cross-cutting concerns.** From the spec_refs, identify any
   invariants the implementation must preserve that aren't obvious
   from the immediate diff (e.g. per-(user, scope) isolation rules,
   determinism boundaries, plain-text formatting, audit log
   coverage). Name them so the developer keeps them in mind.

5. **Implementation order with rationale.** Why this order? Where
   would a wrong order produce broken intermediate states (e.g.
   "create the migration before the entity, otherwise integration
   tests will fail")?

6. **Risks and escalation triggers.** Anything you noticed that
   suggests the ticket should be re-scoped, blocked on a missing
   dependency, or carries hidden complexity not reflected in
   `complexity: high`. Each risk → which escalation reason fits
   (refine | decompose | defer | spec-amend).

7. **Out-of-scope reminders.** Echo the ticket's `out_of_scope`
   list so the developer keeps it visible while implementing.

8. **Audit-coverage enumeration.** In your return, list every
   audit dimension above (1 through 6 — items 7 and this one are
   echo/meta, not audits) with one of three statuses:
   - **audited (pass)** — you checked it and found no problems.
   - **audited (fail)** — you checked it and an OUTLINE FAILED
     above is what you found.
   - **not audited** — you did not check this pass. Name the
     reason in one line (e.g. "the acceptance items pin no
     methods on any pre-existing class, so API-surface had
     nothing to audit"; "ran out of time after item 1 found
     a blocker — recommend re-Plan after refine").
   This enumeration is the safety net for the refinement step.
   An OUTLINE FAILED block only proves the dimensions Plan
   audited; dimensions marked "not audited" may still hide
   blockers and must be re-checked on the next Plan pass after
   the user refines the ticket. Do NOT silently skip a
   dimension — every dimension gets a status line.

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
## Implementation outline (M<N>-NNN, generated by Plan subagent on YYYY-MM-DD)

### Files to touch ({M} of {files_budget})
- create: `<path>` — <one-line purpose>
- modify: `<path>` — <one-line purpose>
- ...

### Tests
- add: `<path>` — covers <acceptance item N>
- modify: `<path>` — authorized in ticket body §"Authorized test changes" item M
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

### Audit coverage
- file accounting — <audited (pass) | audited (fail) | not audited: reason>
- API-surface — <audited (pass) | audited (fail) | not audited: reason>
- test-scaffolding — <audited (pass) | audited (fail) | not audited: reason>
- cross-cutting concerns — <audited (pass) | audited (fail) | not audited: reason>
- implementation order — <audited (pass) | audited (fail) | not audited: reason>
- risks — <audited (pass) | audited (fail) | not audited: reason>
```

After the Write call returns, send exactly these three lines as your
chat reply — nothing else, no preamble, no postscript:

OUTLINE: PASS
Outline file: {{OUTLINE_FILE_PATH}}
Risks: <integer count>

The skill parses these three lines literally; the full outline body
lives only in the file you Wrote, which the skill records via an
`outline_file:` pointer in the ticket frontmatter (not by inlining
the outline body).

---

## Return format — failure path

If the outline fails any check (files_budget exceeded; API-surface
mismatch; test-modification unauthorized; ANCHOR-NOT-FOUND on a
load-bearing spec_ref), return the following block inline as your
chat reply. Do NOT call the Write tool. Do NOT write the outline
file. The failure reasoning belongs in the persistent audit trail
(the ticket's `escalations:` frontmatter), not in a gitignored
sidecar.

```markdown
## OUTLINE FAILED — escalation recommended

REASON: <one paragraph>
SUGGESTED ESCALATION: <refine | decompose | defer | spec-amend>
EVIDENCE: <pointer to the failing item in the ticket or spec, with
verbatim quotes of the cited class signatures when the failure is
API-surface mismatch>

### Audit coverage
- file accounting — <audited (pass) | audited (fail) | not audited: reason>
- API-surface — <audited (pass) | audited (fail) | not audited: reason>
- test-scaffolding — <audited (pass) | audited (fail) | not audited: reason>
- cross-cutting concerns — <audited (pass) | audited (fail) | not audited: reason>
- implementation order — <audited (pass) | audited (fail) | not audited: reason>
- risks — <audited (pass) | audited (fail) | not audited: reason>
```

The skill detects the OUTLINE FAILED block by the leading
`## OUTLINE FAILED` heading and surfaces it as a pre-implementation
escalation rather than letting the developer start. The block is
recorded in the ticket's `escalations:` frontmatter entry.

The audit-coverage block in the OUTLINE FAILED return is the safety
net for the refinement step: an OUTLINE FAILED block with "file
accounting — audited (fail), API-surface — not audited" tells the
user (and the next Plan run) that the API surface remains un-audited
and may hide a separate blocker the next Plan pass will discover.
Without this enumeration, an OUTLINE FAILED block reads as
exhaustive when it's actually one observation pass.
```

---

## Skill responsibilities (what `/m1-tick start` does around the prompt)

1. After the clarity pre-flight passes, check the ticket's `complexity` field. If `complexity: high`, continue; otherwise skip the Plan step.
2. Pre-allocate the outline sidecar path at `target/m1-tick-outline-{{ID}}.md` (the directory `target/` already exists by Maven convention and is excluded from version control).
3. Read this file to load the template. Substitute three placeholders only: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (repo-relative path to the ticket file the skill resolved from the ID), and `{{OUTLINE_FILE_PATH}}` (the path pre-allocated in step 2). No content placeholders — the subagent loads the ticket and each cited spec file via Read in its own fresh context, and runs the spec_refs anchor resolution algorithm itself.
4. Spawn `Agent(subagent_type: "Plan", prompt: <substituted>, description: "Implementation outline M<N>-NNN")`. Foreground.
5. Branch on the chat reply:
   - If the reply begins with `## OUTLINE FAILED`, treat as a `clarity-fail`-equivalent escalation: append the OUTLINE FAILED block to the ticket's `escalations:` frontmatter entry (existing escalation flow) and fire `escalate` with `reason: outline-fail`. Do NOT add an `outline_file:` pointer; the sidecar was not written.
   - Otherwise the reply begins with `OUTLINE: PASS`; parse the three-line reply for the outline file path and risk count. Set the ticket frontmatter `outline_file: target/m1-tick-outline-M<N>-NNN.md` as a one-line pointer. Do NOT append the outline body to the ticket — the sidecar IS the outline.

The Plan subagent never edits files (other than the outline sidecar in the success case) or runs commands. It Reads the inputs, Writes the outline sidecar (success) or returns the OUTLINE FAILED block inline (failure), and exits.
