# Ticket-clarity pre-flight subagent prompt template

This is the user prompt the m1-tick skill substitutes and passes to `Agent(subagent_type: "clarity-reviewer", ...)` for `/m1-tick start <id>`. The agent's identity, tool allowlist (Read/Grep/Glob/Write), and model pinning (sonnet) are declared in [`.claude/agents/clarity-reviewer.md`](../../.claude/agents/clarity-reviewer.md) — those are harness-level enforcement. This template carries only the *task metadata* and the *prompt-supplied paths* the agent reads; the ticket body and the cited spec files are loaded by the agent via Read in its fresh context, and the full structured verdict is written to disk before the short chat reply.

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
   resolve the anchor yourself using the algorithm below. The skill
   no longer pre-resolves spec_refs in the main session; you resolve
   each spec_ref in your fresh context. Every spec_ref entry has the
   form `<file-path> §<section-title>`.

   **`spec_refs` anchor resolution algorithm.** For each entry:
   1. Use the Read tool to read `<file-path>`.
   2. Find every line beginning with `#`-markers (`#`, `##`, `###`,
      etc.).
   3. Strip the `#`-markers and surrounding whitespace from each
      candidate heading.
   4. Lowercase both the candidate heading and the searched
      section-title; do a substring match (the searched title must
      appear as a substring of the candidate, or vice-versa for
      partial titles).
   5. If exactly one heading matches, the resolution is
      `FOUND (line N: "<heading>")`.
   6. If zero match, the resolution is `ANCHOR-NOT-FOUND`.
   7. If multiple match, prefer the heading whose depth (count of
      `#` markers) is closest to the most recently resolved anchor's
      depth; tie-break by line number ascending. If still tied, the
      resolution is `AMBIGUOUS (lines: N, M, ...)`. Treat AMBIGUOUS
      as FAIL on SPEC-REFS-VALID.

   Resolve every spec_ref before reporting SPEC-REFS-VALID.

---

## What to check

### 1. Acceptance criteria are runnable or testable

Each item under `acceptance:` should be checkable. Strong forms:
  - "mvn -pl <module> test -Dtest=<TestName> returns success"
  - "Flyway migration V<NNN>__<name>.sql applies cleanly on a fresh DB"
  - "grep -rn '<forbidden-pattern>' src/ returns zero matches"

Weak forms (FAIL):
  - "system feels responsive" (not measurable)
  - "code is clean" (not testable)
  - "implements §X of the spec" (delegates to the spec without saying what verifies it)

### 2. `out_of_scope` is non-empty AND specific

`out_of_scope` is the developer's contract about what they will NOT touch. An empty `out_of_scope` means the ticket has not thought about boundaries; this is a FAIL.

Specific entries (PASS): glob patterns, named files, named features.
Vague entries (FAIL or WARN): "things unrelated to this ticket" — that's circular.

### 3. `spec_refs` resolve to real anchors

Every spec_ref must point at a real section. Use the resolutions you
computed above. Any entry that resolved to ANCHOR-NOT-FOUND or
AMBIGUOUS → FAIL on this check.

### 4. `files_budget` is plausible given the acceptance criteria

Mental math: the acceptance criteria imply some number of files (production code + tests + maybe a migration). If `files_budget` (numeric ceiling) is much smaller than that mental estimate, the ticket is under-budgeted (the developer will breach budget); FAIL or WARN. If much larger, the ticket may be doing too much (decompose); WARN.

Rough heuristics:
  - One acceptance item that adds an integration test → at minimum 2 files (production class + test).
  - One acceptance item that adds a Flyway migration → at minimum 2 files (migration SQL + a test that exercises it).
  - One acceptance item naming an SPI → at minimum 3 files (interface + implementation + test).
  - You can be wrong; lean to WARN over FAIL on this dimension.

If the ticket also sets `files_scope` (an optional path/glob list enabling the reviewer's negative-space check), additionally check: does `files_scope` cover the files the acceptance criteria imply? If `files_scope` excludes a path the acceptance clearly requires, FAIL — the developer cannot succeed. If `files_scope` is empty or absent, only the numeric `files_budget` is evaluated.

### 5. `complexity` and `risk` are calibrated

`complexity: high` should be claimed for tickets that genuinely require an outline. If the body is one paragraph and there's no big-picture-notes section, `complexity: high` is mis-claimed; WARN.

`risk: high` should be claimed for tickets touching auth, admin, ban handling, persistence migrations, or anything affecting data integrity. If the ticket touches those and `risk: low` is claimed, WARN.

### 6. Authorized test changes section

If the ticket modifies pre-existing tests (look at `test_plan` and the body's "Authorized test changes" section), the modifications must be explicitly listed with the new expected behavior. If pre-existing tests are mentioned but not authorized, FAIL.

### 7. `security_relevant: true` consistency

If the ticket touches any of: invite-code logic, admin-tier gates, ban handling, message intake validation, LLM tool-call wiring, audit log writes — and `security_relevant` is false, WARN. The developer can still proceed; the WARN flags it for the user.

### 8. SELF-CONTAINED — ticket inlines what an implementer needs

After `/m1-tick start` finishes, the developer (the main session) implements the ticket. If the ticket body delegates its behavioral contract to `spec_refs` — e.g. acceptance items of the form `implements §X of docs/spec/Y.md` without inlining what §X requires — the implementer has no choice but to Read the cited spec files in main-session context, which defeats the purpose of routing spec content into the clarity/review subagents' fresh contexts.

The distinction:
  - **Load-bearing `spec_refs`** (FAIL or WARN) — the cited spec section IS the contract; the implementer cannot succeed without re-reading it. Failure shapes:
    - Acceptance item `implements §X of docs/spec/Y.md` without naming what §X requires → FAIL.
    - Acceptance item `the SPI matches docs/spec/Y.md §Z` without naming the SPI's methods/types in the ticket → FAIL.
    - Definition of Done bullet naming a spec concept (`per the threat model`, `per the LLM routing rules`) without restating the relevant invariant → WARN.
  - **Supplementary `spec_refs`** (PASS) — the cited spec section is cross-reference / context, not contract; the ticket body is load-bearing on its own. Shape: Context that cites a spec section to locate the ticket within the broader design (e.g. "this implements one stage of the pipeline described in docs/spec/architecture.md §Ingest pipeline") while the actual implementation contract lives in Definition of Done. Inline the relevant invariant in the ticket body; cite the spec section as cross-reference, not as substitute.

You can be wrong; lean to WARN over FAIL on this dimension (the same calibration as FILES-BUDGET-PLAUSIBLE). FAIL only on clear cases (the load-bearing examples above); WARN on judgment calls.

### 9. FORWARD-REFERENCE-CHECK — forward references to ticket IDs resolve

The ticket may reference other ticket IDs in its frontmatter
(e.g. `blocked_by:`, `decomposed_from:`, `deferred_on:`, prose in
`out_of_scope:`) or in its body (e.g. "see M1-XXX umbrella",
"deferred to the M1-YYY follow-up"). When the named ticket does
not exist as a file under `docs/plan/<milestone>/tickets/`, the
deferral is a prose promise with no tracked work behind it — the
class of bug that produced M1-009 (a `M1-007a` follow-up that was
promised in two places and never filed; the consequence surfaced
rounds later when Provider's tests had no schema source). This
check fires the forcing function: forward references resolve, or
the ticket cannot start.

Scan the ticket file (frontmatter + body, the same file you Read
in step 1) for substrings matching the regex
`M[0-9]+-[0-9]+[a-z]*`. For each match, classify:

- **Self-reference** — the matched ID equals the ticket's own
  frontmatter `id:`. Exempt; no flag. (A ticket of id `M1-018`
  mentioning `M1-018` in its body or frontmatter is fine.)
- **Placeholder** — the matched ID is one of the documented
  placeholders from `docs/process/workflow.md` §Ticket-ID
  placeholder convention: `M<N>-NNN`, `M<N>-AAA`, `M<N>-BBB`,
  `M<N>-CCC`, `M<N>-XXX`, `M<N>-YYY`, `M<N>-ZZZ`. These are
  syntactic placeholders, not real references. Exempt; no flag.
  (The regex above happens not to match `<N>` literal-bracket
  forms because `<` is not a digit, but the exemption is named
  explicitly so the rule is robust to future placeholder shapes
  and to a subagent's regex tolerance.)
- **Resolved** — Use the Glob tool with the pattern
  `docs/plan/<milestone>/tickets/<ID>-*.md`, where `<milestone>`
  is the lowercase prefix of the matched ID (e.g. `M1-007a` →
  `docs/plan/m1/tickets/M1-007a-*.md`). If Glob returns at least
  one path, the reference is resolved. Informational only;
  no flag.
- **Unresolved, load-bearing** — Glob returned empty AND the
  match appears in one of these load-bearing frontmatter fields
  (the fields the runnable-state computation consults):
  - `blocked_by:`
  - `deferred_on:`
  - `decomposed_from:`
  - `replaces:`
  - `replaced_by:`
  - `spec_amend_parent:`
  - `remediates:`

  Report `FAIL` for this check. The ticket cannot become runnable
  because its blocker (or lineage parent) can never reach `done`
  — the file does not exist.
- **Unresolved, prose** — Glob returned empty AND the match
  appears anywhere else: `out_of_scope:` prose, the ticket body
  sections (Context, Definition of Done, Implementation notes,
  Big-picture notes, Out-of-scope expansion, Alternatives
  considered, Authorized test changes), or any field outside the
  load-bearing list above. Report `WARN`. The ticket can run; the
  operator sees the missing follow-up flagged.

For each match, record the classification with the ID in the
verdict file's FORWARD-REFERENCE-CHECK section. Cite each
UNRESOLVED-LOAD-BEARING and UNRESOLVED-PROSE finding by ID in the
BLOCKERS / WARNINGS sections respectively.

The overall FORWARD-REFERENCE-CHECK verdict is:
- `PASS` — all matches are RESOLVED, SELF-REF, or PLACEHOLDER.
- `WARN` — at least one UNRESOLVED-PROSE and no UNRESOLVED-LOAD-BEARING.
- `FAIL` — at least one UNRESOLVED-LOAD-BEARING.

---

## Short chat reply (the only thing you return inline)

After Writing the full structured verdict to {{VERDICT_FILE_PATH}},
return exactly these lines as your chat reply — nothing else, no
preamble, no postscript:

CLARITY VERDICT: <PASS | WARN | FAIL>
Verdict file: {{VERDICT_FILE_PATH}}
Blockers: <integer count, 0 on PASS/WARN>
Warnings: <integer count, 0 if none>

That is the entire chat reply. The skill parses these four lines
literally; the full per-check verdict (with each BLOCKERS / WARNINGS
string, each per-check PASS/FAIL/WARN reason) lives only in the
verdict file you wrote, which the skill Reads to populate the ticket
frontmatter `clarity_check:` entry.

---

## On-disk verdict format (Write this to {{VERDICT_FILE_PATH}} before the chat reply)

Use the Write tool to write the following structured verdict — the
canonical full form, which the skill parses for audit and frontmatter
strings — to {{VERDICT_FILE_PATH}}:

CLARITY VERDICT: <PASS | WARN | FAIL>

ACCEPTANCE-RUNNABLE: <PASS | WARN | FAIL>
  <one bullet per acceptance item with PASS/WARN/FAIL and a one-line
   reason; cite the item by index>

OUT-OF-SCOPE-SPECIFIC: <PASS | WARN | FAIL>
  <one paragraph: is out_of_scope non-empty and specific, or PASS>

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
   or load-bearing on clear cases like acceptance items of the
   form `implements §X` without inlining the behavioral assertion
   (FAIL)? Cite the acceptance item or Definition-of-Done bullet
   that delegates to spec without inlining the relevant invariant.>

FORWARD-REFERENCE-CHECK: <PASS | WARN | FAIL>
  <one bullet per matched ticket-ID with the classification:
   RESOLVED (path: <glob result>) | SELF-REF | PLACEHOLDER |
   UNRESOLVED-LOAD-BEARING (field: <field-name>) |
   UNRESOLVED-PROSE (location: <section or field>).
   The section verdict is PASS when every match is
   RESOLVED/SELF-REF/PLACEHOLDER; WARN when there is at least one
   UNRESOLVED-PROSE and no UNRESOLVED-LOAD-BEARING; FAIL when
   there is at least one UNRESOLVED-LOAD-BEARING.>

BLOCKERS: (omit on PASS; required on FAIL)
  1. <specific, addressable, points at the line in the ticket that needs change>
  2. ...

WARNINGS: (optional, omit if empty)
  - <informational; does not block>

---

## Verdict rules

- Any *-CHECK: FAIL forces CLARITY VERDICT to be FAIL. The skill blocks `/m1-tick start` until the user refines the ticket.
- Any *-CHECK: WARN with no FAILs makes CLARITY VERDICT: WARN. The skill prints the warnings, records them under `clarity_check:` in frontmatter, and proceeds with the start.
- All PASS → CLARITY VERDICT: PASS. The skill records `clarity_check.verdict: PASS` and proceeds.

Write the full verdict to {{VERDICT_FILE_PATH}} first, then return
ONLY the four-line short chat reply specified above. The skill parses
both literally.
```

---

## Skill responsibilities (what `/m1-tick start` does around the prompt)

1. Pre-allocates the verdict file path under `target/m1-tick-clarity-{{ID}}.txt` so the subagent has a known location for its Write. The directory `target/` already exists by Maven convention and is excluded from version control.
2. Substitutes `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (repo-relative path to the ticket file the skill resolved from the ID), and `{{VERDICT_FILE_PATH}}` (the path pre-allocated in step 1). No content placeholders — the subagent loads the ticket and each cited spec file via Read in its own fresh context.
3. Spawns `Agent(subagent_type: "clarity-reviewer", prompt: <substituted>, description: "Clarity pre-flight M<N>-NNN")`. Foreground.
4. Parses the four-line short chat reply for the verdict line and integer blocker/warning counts.
5. Reads `{{VERDICT_FILE_PATH}}` from disk to extract the BLOCKERS / WARNINGS strings for the ticket frontmatter `clarity_check:` entry.
6. Records under `clarity_check:` in ticket frontmatter:
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
