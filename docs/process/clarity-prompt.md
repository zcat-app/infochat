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

### 10. ACCEPTANCE-VS-DOD-CONSISTENT — grep-count assertions are satisfiable against the DoD's inlined commitments

Check #1 verifies that each acceptance item is *syntactically runnable*
(grep is a valid command, the regex parses, etc.). This check verifies
that each acceptance item is *semantically satisfiable* given what the
ticket's own Definition of Done commits the implementation to. The two
are distinct: a grep can be perfectly runnable and still describe a
predicate that no implementation matching the DoD could satisfy.

This check fires the forcing function against the class of bug that
produced M1-008c: an acceptance grep asserted "≥3 matches of
`PRIMARY KEY\s*\(\s*scope_kind\s*,\s*scope_id\s*,`" across three join
tables, but the same ticket's DoD specified the third table's PK as
2-column (`(scope_kind, scope_id)`), which doesn't match a regex
requiring a comma after `scope_id`. The assertion was unsatisfiable
the moment the DoD was written; the developer hit the trap at
implementation time and had to escalate.

For each acceptance item that asserts a **count over a regex's
matches** — phrasings like `returns exactly N matches`, `returns at
least N matches`, `returns N`, `returns zero matches`, `grep -c ...
returns N`:

1. Identify the artifact the grep targets (a migration file path, a
   test-class set, a source tree). The ticket may not commit to all of
   the artifact's content inline, but the DoD typically commits to the
   *relevant* fragments (CREATE TABLE columns, CHECK expressions,
   PRIMARY KEY shapes, GRANT statements, named constants).
2. Extract those DoD fragments. In M1-008c's DoD they are listed as
   bullets under "Definition of Done": each `- table_name per …` block
   inlines the column list, PK declaration, CHECK constraints, etc.
3. Apply the regex mentally (or with the Read tool's grep-equivalents)
   to the extracted fragments and count the matches you would expect.
4. Compare the expected count against the asserted count:
   - Expected count satisfies the assertion (≥ lower bound, ≤ upper
     bound, exactly N when N is asserted) → `PASS` on this item.
   - Expected count cannot satisfy the assertion (lower than a `≥N`
     bound, higher than an `exactly N` bound, non-zero when `zero` is
     asserted) → `FAIL` on this item.
   - The DoD is ambiguous about the count (e.g., DoD says "and
     additional indexes as needed" without enumerating, or the DoD
     does not commit to the relevant fragment at all) → `WARN`.

Examples (real and constructed):

- **FAIL (M1-008c shape):** Acceptance: `grep -E 'PRIMARY KEY\s*\(\s*
  scope_kind\s*,\s*scope_id\s*,' returns at least 3 matches`. DoD:
  source_subscription PK `(scope_kind, scope_id, source_id)`; scope_tag
  PK `(scope_kind, scope_id, tag_id)`; **scope_preferences PK
  `(scope_kind, scope_id)`**. Expected matches: 2. Asserted: ≥3. The
  third PK doesn't have the trailing comma the regex requires. FAIL
  with citation: "scope_preferences PK is 2-column per DoD; grep
  cannot return 3 matches; reduce to ≥2, or relax regex to allow
  `[,)]` after `scope_id`."
- **FAIL (constructed):** Acceptance: `grep -cE 'BOOLEAN NOT NULL' V7
  returns 7`. DoD enumerates 6 BOOLEAN NOT NULL columns. Expected: 6.
  Asserted: exactly 7. FAIL.
- **WARN (ambiguous DoD):** Acceptance: `grep -E 'CREATE INDEX ... ON
  post' returns at least 5 matches`. DoD: "the full index set per
  docs/design/02-schema.md §2.3.1: idx_post_status_fetched,
  idx_post_source, idx_post_published, idx_post_ready_at,
  idx_post_link_cursor, idx_post_tags_gin, idx_post_status_changed" —
  seven named. Expected: 7. Asserted: ≥5. PASS (assertion satisfied);
  no warning. But if DoD said only "and additional partial indexes",
  expected count would be ambiguous → WARN.

Lean to `FAIL` when the assertion is mechanically impossible to
satisfy given the DoD's enumeration (Shape-2). `WARN` only when the
DoD does not commit to the relevant fragment fully enough to count.

**Aggregate-count over NAMED heterogeneous elements — FAIL.** If
an acceptance item asserts a count across heterogeneous elements
that the same ticket's DoD or Implementation notes *enumerate by
name* (≥3 distinct scenarios labeled `(a)`–`(c)`/etc., named test
methods, named tables with different PK/FK/CHECK shapes, named
bundle keys with different value commitments), record `FAIL` on
this check. The canonical example is the M1-044b shape: DoD
enumerates 8 distinct test scenarios `(a)`–`(h)` with visibly
different collaborator sequences, and acceptance asserts
`grep -E '@Test' … returns ≥6 matches`. The aggregate count masks
per-scenario regressions — deleting scenario `(g)` (the DM-gate
carve-out test, which is itself a load-bearing security assertion)
and adding two duplicate copies of scenario `(a)` would still
satisfy `@Test ≥6` while silently dropping the DM-gate coverage.
The recommended replacement is **one acceptance item per named
scenario / method / element** asserting the specific identifier
grep (e.g., `grep -E 'void dmGroupOnlyWithSlashHelpReplacesReply
WithInviteRequired' … returns ≥1 match` per scenario), so a
regression in any one element fails its own check rather than
being absorbed by the aggregate. The threshold for FAIL is N≥3
named elements; ≤2 named elements is allowed to remain aggregate
because there is no structural-difference masking to worry about.

This rule is a hard-no, not a judgment call. It encodes a recurring
authoring-failure pattern that produced repeated rework on five
M1 tickets (M1-026/027/028/033/044b); the corrective discipline is
"name each method individually" applied at the clarity gate before
implementation starts.

**Aggregate-count smell over un-enumerated elements — WARN.** When
the heterogeneous aggregate is asserted without the DoD or
Implementation notes themselves enumerating the named elements
(e.g. `across the per-scope schema`, `over the catalogue tables`
without naming which tables or their shapes), the elements are
inferred rather than committed — record `WARN` so the author can
either enumerate the elements in the DoD and split the acceptance
item, or relax the regex to match the genuine shared shape. The
M1-008c precedent fits here: the DoD enumerated three join tables
but the third had a 2-column PK while the others had 3-column, and
the regex required a trailing comma. The structural break was in
the DoD but the acceptance item collapsed it. The shift from WARN
to FAIL in the previous paragraph fires when the enumeration is
explicit (named elements visible in the DoD); WARN remains for
the inferred-elements case where the enumeration has to be
reconstructed.

**Undefined-symbol count — FAIL.** Acceptance items that express
their count as a non-numeric expression — `≥N`, `≥(N+1)`, `=N`,
`returns N matches where N is the count before this ticket`,
`returns at least M+K matches`, `>=(N+1)` — are not independently
verifiable from the ticket. The developer must run the grep before
implementation to compute N, the reviewer cannot tell whether the
final state satisfies the assertion without re-running the same
arithmetic, and the acceptance item rots if the underlying state
changes (a test added in an unrelated ticket bumps N silently).
Refuse with a citation that names the symbol and points at the
DoD section that should commit to the specific integer or to the
specific named identifier. The canonical correction shape: an
acceptance item with `≥(N+1)` where N is the pre-existing @Test
count in a file should be replaced by `grep -E 'void
<new-method-name>' … returns ≥1` — pinning the new method by name
without depending on N.

Aggregate counts have ONE legitimate use: enforcing "exactly N and
no more" when **all N elements are structurally identical** — e.g.,
M1-008c's correct acceptance item `grep -cE '(stage1_done|stage2_done|
tagger_done|embedding_done|stage1_flagged|stage2_failed|tagger_fallback)
\s+BOOLEAN NOT NULL' V7 returns 7` (seven per-stage flags of the
same shape; the count is the load-bearing assertion). Do NOT flag
the structurally-identical case; the FAIL fires for heterogeneous
aggregates over named elements only, and the WARN fires for the
un-enumerated case only. Distinguishing test: if you can name each
element and the elements have visibly different shapes in the DoD
(different PK lengths, different FK targets, different CHECK
expressions, different test collaborator sequences), the aggregate
is heterogeneous. If the DoD enumerates N elements that share the
same shape and only differ in name, the aggregate is identical-count.

This check does NOT verify regex *correctness* on individual inputs
(that's the spec-vs-prose half — clarity catches it via test-vector
review on the spec side; the developer's own grep against the DoD
catches it here). It verifies regex *cardinality* — that the
count claim is satisfiable AND committed to a specific value —
and surfaces the aggregate-count authoring smell so the per-element
pattern can replace it.

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

ACCEPTANCE-VS-DOD-CONSISTENT: <PASS | WARN | FAIL>
  <one bullet per acceptance item that asserts a grep-match count;
   each bullet records the asserted count, the expected count derived
   from the DoD's inlined fragments, an aggregate-vs-identical
   classification of the elements counted (HETEROGENEOUS-AGGREGATE-
   NAMED | HETEROGENEOUS-AGGREGATE-UN-ENUMERATED | IDENTICAL-AGGREGATE
   | UNDEFINED-SYMBOL-COUNT | SINGLE-ELEMENT | N/A), and a
   PASS/WARN/FAIL classification. Items that do not assert a count
   (mvn invocations, prose behavioral assertions, integration-test
   outcomes) are reported as "N/A — no count assertion" and do not
   contribute to the verdict.

   Severity rules (the section verdict is the maximum of the per-item
   verdicts):
     - FAIL when at least one item:
         (a) is Shape-2 unsatisfiable (expected count cannot satisfy
             the asserted bound given the DoD's enumeration), OR
         (b) is HETEROGENEOUS-AGGREGATE-NAMED with ≥3 named elements
             enumerated in the DoD / Implementation notes (the
             M1-044b shape: 8 named scenarios (a)–(h) collapsed to
             one @Test count), OR
         (c) is UNDEFINED-SYMBOL-COUNT (the count expression contains
             a non-numeric symbol like `N`, `(N+1)`, `M+K` that the
             ticket does not commit to a specific integer for).
     - WARN when no item is FAIL and at least one item is
       HETEROGENEOUS-AGGREGATE-UN-ENUMERATED (DoD does not enumerate
       the elements; the heterogeneity is inferred) OR the DoD is
       ambiguous about the count.
     - PASS otherwise.

   Each FAIL bullet cites the specific recommended replacement
   (per-element grep set with named identifiers, or specific integer
   to replace the symbol expression) so the author can refine without
   re-deriving the structural break.>

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
