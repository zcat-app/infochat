# Plan subagent prompt template

Used when `/m1-tick start <id>` encounters a ticket with `complexity: high`. The skill spawns the built-in `Plan` subagent (`Agent(subagent_type: "Plan")`) with the prompt below to produce an implementation outline before any code is written. The outline is appended to the ticket body under a new "Implementation outline" section; the developer (the main conversation) reads it before touching code.

The Plan subagent is a Claude Code built-in (one of `claude-code-guide`, `Explore`, `general-purpose`, `Plan`, `statusline-setup`); we do not define a custom agent for it. The prompt below is what the skill substitutes and passes as the `prompt` argument.

---

## Template

```
You are producing an implementation outline for a single ticket
that has been classified `complexity: high`. You have NO conversation
context; everything you know is in the ticket below and the spec/design
files it references. You will NOT write any code. Your output is a
markdown outline that the developer (a separate agent in the main
conversation) will follow.

The ticket is: {{TICKET_ID}}

---

## Ticket

{{TICKET_FILE_CONTENT}}

---

## Spec sections cited (verbatim, for cross-referencing)

{{SPEC_REF_RESOLUTIONS}}

(Each spec_ref in the ticket frontmatter is resolved here. If a ref's
anchor wasn't found, the resolution will say "ANCHOR-NOT-FOUND" — flag
that as a risk.)

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

## Return format

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

If the outline fails any check (files_budget exceeded; API-surface
mismatch; test-modification unauthorized; ANCHOR-NOT-FOUND on a
load-bearing spec_ref), return:

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

The audit-coverage block appears in **both** the success outline and
the OUTLINE FAILED return. Its purpose in the failed case is the
safety net for the refinement step: an OUTLINE FAILED block with
"file accounting — audited (fail), API-surface — not audited" tells
the user (and the next Plan run) that the API surface remains
un-audited and may hide a separate blocker the next Plan pass will
discover. Without this enumeration, an OUTLINE FAILED block reads
as exhaustive when it's actually one observation pass.

The skill will surface OUTLINE FAILED to the user as a pre-implementation
escalation rather than letting the developer start.

Return ONLY the outline (or the OUTLINE FAILED block). No preamble, no
postscript. The skill appends your output verbatim to the ticket body.
```

---

## Skill responsibilities (what `/m1-tick start` does around the prompt)

1. After the clarity pre-flight passes, check the ticket's `complexity` field.
2. If `complexity: high`, read this file and substitute `{{TICKET_ID}}`, `{{TICKET_FILE_CONTENT}}`, and `{{SPEC_REF_RESOLUTIONS}}` (the same resolved-anchors block built for clarity).
3. Spawn `Agent(subagent_type: "Plan", prompt: <substituted>, description: "Implementation outline M<N>-NNN")`. Foreground.
4. Capture the response. If it begins with `## OUTLINE FAILED`, treat as a `clarity-fail`-equivalent escalation: refuse the start, append the OUTLINE FAILED block to the ticket body, and prompt the user to `/m1-tick escalate <id> refine` (or whichever escalation the outline suggested).
5. Otherwise append the outline to the ticket body under a new `## Implementation outline` section, then proceed with the rest of the start procedure (set status, create branch, etc.).

The Plan subagent never edits files or runs commands. It reads the prompt, returns the outline, and exits.
