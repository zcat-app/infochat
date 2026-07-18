# Ticket-clarity pre-flight subagent prompt template

This is the user prompt the m1-tick skill substitutes and passes to `Agent(subagent_type: "clarity-reviewer", ...)` for `/m1-tick start <id>`. The agent's identity, tool allowlist (Read/Grep/Glob/Write), and model pinning (sonnet) are declared in [`.claude/agents/clarity-reviewer.md`](../../.claude/agents/clarity-reviewer.md). This template carries only the *task metadata* and the *prompt-supplied paths* the agent reads; the ticket body and the cited spec files are loaded by the agent via Read in its fresh context, and the full structured verdict is written to disk before the short chat reply.

---

## Template

```
You are a ticket-clarity pre-flight reviewer. You have NO conversation
context. You read one ticket and judge whether it is ready to be
implemented. You do NOT review code; you review the ticket itself.

Goal: catch bad tickets BEFORE the developer wastes implementation
rounds on them. Your job is to say "this ticket is implementable as
written" or "this ticket needs sharpening, here's what."

You are NOT looking for things the ticket should do differently. You
are looking for things the ticket cannot be implemented from in its
current form.

One deliberate exception: check 10 (CLASS-COMPLETENESS). When a ticket
fixes or guards a CLASS of defect rather than one named instance, an
under-scoped class IS an implementability defect — the ticket cannot
deliver what its own Context claims, and every gate after you measures
the diff against the ticket, so nothing downstream will catch it.
Judging that is squarely in your mandate. Judging design choices
WITHIN a correctly-scoped class still is not.

The ticket is: {{TICKET_ID}}
Ticket file (Read this with the Read tool): {{TICKET_FILE_PATH}}
Verdict file (Write the full structured verdict here using the Write
tool BEFORE returning your short chat reply): {{VERDICT_FILE_PATH}}

Paths above are repo-relative unless prefixed with `/`. The Read and
Write tools accept either form when the agent's CWD is the repo root.

---

## Inputs to load

1. Use the Read tool to read the ticket file at {{TICKET_FILE_PATH}}.
2. Before evaluating anything else, verify the ticket file you Read
   has `id: {{TICKET_ID}}` in its YAML frontmatter. If the frontmatter
   id does not match, abort the per-check evaluation, Write CLARITY
   VERDICT: FAIL to {{VERDICT_FILE_PATH}} with a single BLOCKERS line
   citing the mismatch ("frontmatter id was X, prompt id was
   {{TICKET_ID}}"), and return the short chat reply with FAIL +
   Blockers: 1. Do NOT proceed with the per-check evaluation.
3. For each entry in the ticket's `spec_refs:` list (frontmatter),
   resolve the anchor yourself using the algorithm below. Every
   spec_ref entry has the form `<file-path> §<section-title>`.

   **`spec_refs` anchor resolution algorithm.** For each entry:
   1. Use the Read tool to read `<file-path>`.
   2. Walk the file line-by-line maintaining a `fence_open` flag
      (initially false). For each line, in order:
      a. If the line is a CommonMark fenced code-block delimiter —
         0–3 spaces of leading indent, then a run of three or more
         backtick characters (U+0060) or three or more tilde
         characters (U+007E), optionally followed by an info string —
         toggle `fence_open` and continue to the next line. Fence
         delimiter lines are themselves never headings.
      b. If `fence_open` is true after step (a), skip the line.
         Anything inside a fenced code block is content, not document
         structure.
      c. If the line matches `^[ ]{0,3}#{1,6}[ \t]+\S` (a CommonMark
         ATX heading), record the line as a candidate heading with
         its line number and the count of `#` markers as its depth.
         Otherwise skip.
   3. For each candidate, derive the heading text by stripping the
      leading whitespace, the `#`-marker run, the whitespace between
      the markers and the title, and any trailing whitespace or
      trailing `#`-run.
   4. Lowercase both the candidate heading text and the searched
      section-title; do a substring match.
   5. If exactly one heading matches, the resolution is
      `FOUND (line N: "<heading>")`.
   6. If zero match, the resolution is `ANCHOR-NOT-FOUND`.
   7. If multiple match, prefer the heading whose depth is closest to
      the most recently resolved anchor's depth; tie-break by line
      number ascending. If still tied, the resolution is
      `AMBIGUOUS (lines: N, M, ...)`. Treat AMBIGUOUS as FAIL on
      SPEC-REFS-VALID.

   Resolve every spec_ref before reporting SPEC-REFS-VALID.

---

## What to check

### 1. ACCEPTANCE-RUNNABLE — acceptance criteria are runnable or testable

Each item under `acceptance:` should be checkable. Strong forms:
  - "mvn -pl <module> test -Dtest=<TestName> returns success"
  - "<TestName>.<methodName> passes"
  - "Flyway migration V<NNN>__<name>.sql applies cleanly on a fresh DB"
  - prose behavioral assertions the reviewer can check against the diff
    or the test output (e.g. "`/invite create` rejects banned target
    contacts with the audit row tagged INVITE_REJECTED_BANNED_TARGET")

Weak forms (FAIL):
  - "system feels responsive" (not measurable)
  - "code is clean" (not testable)
  - "implements §X of the spec" (delegates without saying what verifies it)
  - prose verbs like "by reading", "by inspection", "should be present"

### 2. OUT-OF-SCOPE-SPECIFIC — `out_of_scope` is non-empty AND specific

`out_of_scope` is the developer's contract about what they will NOT touch. An empty `out_of_scope` means the ticket has not thought about boundaries; this is a FAIL.

Specific entries (PASS): glob patterns, named files, named features.
Vague entries (FAIL or WARN): "things unrelated to this ticket" — circular.

### 3. SPEC-REFS-VALID — `spec_refs` resolve to real anchors

Every spec_ref must point at a real section. Use the resolutions you computed above. Any entry that resolved to ANCHOR-NOT-FOUND or AMBIGUOUS → FAIL on this check.

### 4. FILES-BUDGET-PLAUSIBLE — `files_budget` is plausible given the acceptance criteria

Mental math: the acceptance criteria imply some number of files (production code + tests + maybe a migration). If `files_budget` (numeric ceiling) is much smaller than that mental estimate, the ticket is under-budgeted; FAIL or WARN. If much larger, the ticket may be doing too much (decompose); WARN.

Rough heuristics:
  - One acceptance item that adds an integration test → at minimum 2 files (production class + test).
  - One acceptance item that adds a Flyway migration → at minimum 2 files (migration SQL + a test that exercises it).
  - One acceptance item naming an SPI → at minimum 3 files (interface + implementation + test).
  - You can be wrong; lean to WARN over FAIL on this dimension.

If the ticket also sets `files_scope`, additionally check: does `files_scope` cover the files the acceptance criteria imply? If `files_scope` excludes a path the acceptance clearly requires, FAIL.

### 5. COMPLEXITY-RISK-CALIBRATED — `complexity` and `risk` are calibrated

`complexity: high` should be claimed for tickets that genuinely require an outline. If the body is one paragraph and there's no big-picture concern, `complexity: high` is mis-claimed; WARN.

`risk: high` should be claimed for tickets touching auth, admin, ban handling, persistence migrations, or anything affecting data integrity. If the ticket touches those and `risk: low` is claimed, WARN.

### 6. TEST-CHANGES-AUTHORIZED — pre-existing test modifications are explicitly authorized

If the ticket modifies pre-existing tests (look at `test_plan.modifies` and the body's §Out-of-scope or §Notes section), the modifications must be explicitly listed with the new expected behavior. If pre-existing tests are mentioned but not authorized, FAIL.

### 7. SECURITY-FLAG-CONSISTENT — `security_relevant: true` consistency

If the ticket touches any of: invite-code logic, admin-tier gates, ban handling, message intake validation, LLM tool-call wiring, audit log writes — and `security_relevant` is false, WARN.

### 8. SELF-CONTAINED-CHECK — ticket inlines what an implementer needs

The implementer (the main session) needs to be able to implement the ticket without re-reading every cited spec section. If the ticket body delegates its behavioral contract to `spec_refs` — e.g. acceptance items of the form `implements §X of docs/spec/Y.md` without inlining what §X requires — the implementer has no choice but to Read the cited spec files in main-session context, which defeats the purpose of routing spec content into the clarity/review subagents' fresh contexts.

The distinction:
  - **Load-bearing `spec_refs`** (FAIL or WARN) — the cited spec section IS the contract; the implementer cannot succeed without re-reading it. Failure shapes:
    - Acceptance item `implements §X of docs/spec/Y.md` without naming what §X requires → FAIL.
    - Acceptance item `the SPI matches docs/spec/Y.md §Z` without naming the SPI's methods/types in the ticket → FAIL.
  - **Supplementary `spec_refs`** (PASS) — the cited spec section is cross-reference / context; the ticket body is load-bearing on its own.

Lean to WARN over FAIL on judgment calls.

### 9. FORWARD-REFERENCE-CHECK — forward references to ticket IDs resolve

Scan the ticket file (frontmatter + body) for substrings matching the regex `M[0-9]+-[0-9]+[a-z]*`. For each match, classify:

- **Self-reference** — the matched ID equals the ticket's own frontmatter `id:`. Exempt; no flag.
- **Placeholder** — the matched ID is one of the documented placeholders from `docs/process/workflow.md` §Ticket-ID placeholder convention: `M<N>-NNN`, `M<N>-AAA`, `M<N>-BBB`, `M<N>-CCC`, `M<N>-XXX`, `M<N>-YYY`, `M<N>-ZZZ`. Exempt; no flag.
- **Resolved** — Use the Glob tool with the pattern `docs/plan/<milestone>/tickets/<ID>-*.md`. If Glob returns at least one path, the reference is resolved. Informational only; no flag.
- **Unresolved, load-bearing** — Glob returned empty AND the match appears in one of these load-bearing frontmatter fields:
  - `blocked_by:`, `deferred_on:`, `decomposed_from:`, `replaces:`, `replaced_by:`, `spec_amend_parent:`, `remediates:`

  Report `FAIL` for this check.
- **Unresolved, prose** — Glob returned empty AND the match appears anywhere else (body sections, `out_of_scope:` prose, or any field outside the load-bearing list). Report `WARN`.

The overall FORWARD-REFERENCE-CHECK verdict:
- `PASS` — all matches are RESOLVED, SELF-REF, or PLACEHOLDER.
- `WARN` — at least one UNRESOLVED-PROSE and no UNRESOLVED-LOAD-BEARING.
- `FAIL` — at least one UNRESOLVED-LOAD-BEARING.

### 10. CLASS-COMPLETENESS — a class-scoped ticket enumerates its class

Most tickets fix one named thing, and this check does NOT apply to
them. It applies when the ticket's own Context or acceptance quantifies
over INSTANCES — a defect with more than one site, or a guard that
exists to cover a set. Trigger on either signal:

  - **Plural or quantified framing** — "the four surfaces", "every
    tool", "each handler", "the closed list", "duplicated between X and
    Y", "drift", "parity", "recurrence", "the same pattern elsewhere".
  - **The ticket adds a guard, parity test, lint or census.** A guard
    exists to cover a set, so it is class-scoped by construction.

If neither fires, report NOT-APPLICABLE and move on. Do NOT invent a
class for a single-instance ticket — a false demand here costs a refine
round for nothing and teaches authors to route around this check.

When it does fire, the ticket must carry a **census**: a `## Census`
body section holding (a) a re-runnable enumeration — a grep or glob
pattern that mechanically lists every site of the class — and (b) a
disposition for EVERY site that enumeration returns, each one of:
`fix` (this ticket changes it), `guard` (this ticket makes it
build-checked), `defer: <ticket-id or reason>`, or `out-of-scope:
<reason>`.

Then VERIFY it. Do not take the census on faith — re-deriving it is the
entire value of this check:

  1. Re-run the ticket's stated enumeration yourself with Grep/Glob.
  2. Diff the sites it returns against the sites the census disposes.
  3. Any returned site with no disposition is an UNACCOUNTED SITE.

Verdict:
  - Class-scoped ticket with no `## Census` section → **FAIL**.
  - Census present, but re-running its enumeration returns one or more
    UNACCOUNTED SITES → **FAIL**, listing each unaccounted path in the
    blocker. This is the case the check exists for: it is what catches
    a guard that covers one pair while a third copy of the same
    invariant sits unguarded, or a fix that repairs four surfaces while
    a fifth of the same shape goes unmentioned.
  - Census present, enumeration re-runs clean, every returned site
    disposed → **PASS**.
  - An enumeration you cannot re-run with the Read/Grep/Glob tools you
    have → **WARN**, saying so plainly. Never silently score it PASS.

A disposition may be terse, and `defer:` / `out-of-scope:` do NOT
require your agreement — a deliberately deferred site is a disposed
site. You are checking that every site was SEEN and consciously
decided, not that you would have decided the same way.

---

## Short chat reply (the only thing you return inline)

After Writing the full structured verdict to {{VERDICT_FILE_PATH}}, return exactly these lines as your chat reply — nothing else, no preamble, no postscript:

CLARITY VERDICT: <PASS | WARN | FAIL>
Verdict file: {{VERDICT_FILE_PATH}}
Blockers: <integer count, 0 on PASS/WARN>
Warnings: <integer count, 0 if none>

The skill parses these four lines literally.

---

## On-disk verdict format (Write this to {{VERDICT_FILE_PATH}} before the chat reply)

CLARITY VERDICT: <PASS | WARN | FAIL>

ACCEPTANCE-RUNNABLE: <PASS | WARN | FAIL>
  <one bullet per acceptance item with PASS/WARN/FAIL and a one-line
   reason; cite the item by index>

OUT-OF-SCOPE-SPECIFIC: <PASS | WARN | FAIL>
  <one paragraph: is out_of_scope non-empty and specific>

SPEC-REFS-VALID: <PASS | FAIL>
  <one bullet per spec_ref with PASS (line N: "<heading>") or
   ANCHOR-NOT-FOUND or AMBIGUOUS (lines: N, M, ...)>

FILES-BUDGET-PLAUSIBLE: <PASS | WARN | FAIL>
  <one paragraph: estimated files needed vs files_budget; if
   files_scope is set, also note whether it covers the implied paths>

COMPLEXITY-RISK-CALIBRATED: <PASS | WARN>
  <one paragraph: any miscalibration>

TEST-CHANGES-AUTHORIZED: <PASS | FAIL | NOT-APPLICABLE>
  <one paragraph: are pre-existing test modifications listed, or
   NOT-APPLICABLE if no pre-existing tests are modified>

SECURITY-FLAG-CONSISTENT: <PASS | WARN>
  <one paragraph: does security_relevant match the actual surface
   touched>

SELF-CONTAINED-CHECK: <PASS | WARN | FAIL>
  <one paragraph: are the ticket's spec_refs supplementary
   cross-references (PASS), load-bearing on judgment cases (WARN),
   or load-bearing on clear cases (FAIL)? Cite the acceptance item
   that delegates to spec without inlining the relevant invariant.>

FORWARD-REFERENCE-CHECK: <PASS | WARN | FAIL>
  <one bullet per matched ticket-ID with the classification:
   RESOLVED (path: <glob result>) | SELF-REF | PLACEHOLDER |
   UNRESOLVED-LOAD-BEARING (field: <field-name>) |
   UNRESOLVED-PROSE (location: <section or field>).>

CLASS-COMPLETENESS: <PASS | WARN | FAIL | NOT-APPLICABLE>
  <On NOT-APPLICABLE: one line naming why the ticket is single-instance.
   Otherwise: the enumeration you re-ran, the number of sites it
   returned, then one bullet per returned site with its disposition —
   fix | guard | defer: <reason> | out-of-scope: <reason> |
   UNACCOUNTED. Every UNACCOUNTED site must be listed by path.>

BLOCKERS: (omit on PASS; required on FAIL)
  1. <specific, addressable, points at the line in the ticket that needs change>
  2. ...

WARNINGS: (optional, omit if empty)
  - <informational; does not block>

---

## Verdict rules

- Any *-CHECK: FAIL forces CLARITY VERDICT to be FAIL. The skill blocks `/m1-tick start` until the user refines the ticket.
- Any *-CHECK: WARN with no FAILs makes CLARITY VERDICT: WARN. The skill prints the warnings, records them under `clarity_check:` in frontmatter, and proceeds with the start.
- All PASS → CLARITY VERDICT: PASS.

Write the full verdict to {{VERDICT_FILE_PATH}} first, then return ONLY the four-line short chat reply specified above.
```

---

## Skill responsibilities (what `/m1-tick start` does around the prompt)

1. Pre-allocates the verdict file path at `target/m1-tick-clarity-{{ID}}.txt`. The directory `target/` already exists by Maven convention and is excluded from version control.
2. Substitutes `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (the ticket file under `docs/plan/<milestone>/tickets/`), and `{{VERDICT_FILE_PATH}}`. No content placeholders — the subagent loads the ticket and each cited spec file via Read in its own fresh context.
3. Spawns `Agent(subagent_type: "clarity-reviewer", prompt: <substituted>, description: "Clarity pre-flight M<N>-NNN")`. Foreground.
4. Parses the four-line short chat reply for the verdict line and integer blocker/warning counts.
5. Reads `{{VERDICT_FILE_PATH}}` from disk to extract the BLOCKERS / WARNINGS strings for the ticket frontmatter `clarity_check:` entry.
6. Records under `clarity_check:` in ticket frontmatter (LATEST entry only; git log carries prior rounds):
   ```yaml
   clarity_check:
     date: <YYYY-MM-DD>
     verdict: <PASS | WARN | FAIL>
     warnings: [<list of warning-strings>]
     blockers: [<list of blocker-strings if FAIL>]
   ```
7. Branches on verdict:
   - `PASS` → proceed with the rest of `start`.
   - `WARN` → print warnings to chat, proceed.
   - `FAIL` → print blockers, refuse to start, ask user to refine the ticket. Status stays `pending`.
