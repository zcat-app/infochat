---
id: M1-012
title: STATUS.md regeneration via fresh-context subagent
status: done
created: 2026-05-11
last_updated: 2026-05-11
blocked_by:
  - M1-011
files_budget: 6
files_scope:
  - .claude/agents/status-regenerator.md
  - docs/process/status-regen-prompt.md
  - .claude/skills/m1-tick/subcommands/status.md
  - .claude/skills/m1-tick/subcommands/commit.md
  - docs/plan/m1/STATUS.md
  - docs/plan/m1/tickets/M1-012-status-md-regenerator-subagent.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change to the STATUS.md template format (counts table, runnable section, in-flight table, blocked section, escalated table, done table, deferred groupings, dependency DAG, optional-flags footer). This ticket RELOCATES the template from the skill subcommand body into the prompt template; the rendered output is byte-identical given the same ticket set.
  - any change to the STATUS.md placeholder rules (the "no tickets yet" first-line override, the `_(none)_` and `_(none — ...)_` per-section fallbacks)
  - any change to the runnable / blocked / deferred / escalated classification rules (these are defined elsewhere and the regenerator just applies them)
  - any change to the `## commit` other procedures (test-freshness safety check, commit-message build, staging, the actual `git commit` invocation). Only step 5's "Regenerate STATUS.md" delegates; the surrounding steps stay byte-identical.
  - any change to `/m1-tick status` optional flags (`--deferred`, `--escalated`). Those are bounded read-only filters that print to chat without writing STATUS.md; their main-session cost is bounded by N tickets and the consistency benefit of delegating them isn't worth the procedure-text expansion. They keep their current main-session implementation.
  - any change to other subcommands (next, start, review, merge, escalate, abort, show, reopen). They don't regenerate STATUS.md outside the `commit` step 5 path this ticket already covers.
  - any change to the agent definitions for clarity-reviewer, code-reviewer, or threat-actor (this ticket only ADDS a new agent definition)
  - any change to other prompt templates (clarity-prompt.md, reviewer-prompt.md, plan-prompt.md, redteam-prompt.md, engineering-rules-verbatim.md, ticket-template.md, workflow.md)
  - any edit to `.claude/skills/redteam/`, `.claude/skills/m1-tick/SKILL.md` (the router — this ticket touches only the per-subcommand files for status and commit), or other ticket files under docs/plan/m1/tickets/
  - any change to repo source code, poms, application.properties, migrations, or test code
  - any new Maven module or pom change of any kind
  - any change to STATUS.md content beyond what the regenerator emits (the file is generated; manual edits are explicitly forbidden by SKILL.md's cross-cutting rules)
acceptance:
  - "test -f .claude/agents/status-regenerator.md returns 0 (the new agent definition exists)"
  - ".claude/agents/status-regenerator.md has a minimal tool allowlist: Read + Write + Glob only. Verify: grep -nE '^tools:' .claude/agents/status-regenerator.md returns exactly one match; that line contains all three of 'Read', 'Write', 'Glob'; and that line contains NONE of 'Edit', 'Bash', 'Grep', 'WebFetch', 'NotebookEdit'. Grep is excluded deliberately — the work is 'load all + render', not 'search within'."
  - ".claude/agents/status-regenerator.md frontmatter includes `name: status-regenerator`, `description:` (non-empty), `model:` (pinned), `color:` (any value). Verify by grep that each key appears in the frontmatter block (between the opening `---` and closing `---`)."
  - "the status-regenerator persona body (post-frontmatter) describes the fresh-context contract: no conversation history, reads every ticket via Glob+Read, writes STATUS.md via Write, returns a short structured summary to chat. Verify: grep -niE 'fresh context|no conversation history' returns at least one match AND grep -niE 'Write.*STATUS|Write.*docs/plan/m1/STATUS' returns at least one match AND grep -niE 'short.*summary|summary.*return' returns at least one match — all post-frontmatter."
  - "test -f docs/process/status-regen-prompt.md returns 0 (the new prompt template exists)"
  - "docs/process/status-regen-prompt.md substitutes `{{TICKETS_GLOB}}` and `{{STATUS_FILE_PATH}}` placeholders. Verify: grep -nF '{{TICKETS_GLOB}}' docs/process/status-regen-prompt.md returns at least one match AND grep -nF '{{STATUS_FILE_PATH}}' docs/process/status-regen-prompt.md returns at least one match."
  - "docs/process/status-regen-prompt.md does NOT substitute ticket content inline. Verify: grep -nF '{{TICKET_FILE_CONTENT}}' docs/process/status-regen-prompt.md returns zero matches AND grep -nF '{{TICKETS_CONTENT}}' docs/process/status-regen-prompt.md returns zero matches AND grep -niE 'inline.*ticket' docs/process/status-regen-prompt.md returns zero matches (the agent loads tickets itself via Glob+Read, not from the prompt)."
  - "docs/process/status-regen-prompt.md carries the canonical STATUS.md template inline (the same template that lives in pre-M1-011 SKILL.md `## status` step 4 / post-M1-011 subcommands/status.md). Verify by grep that template-defining anchors appear: grep -nE '^# M1 status board' docs/process/status-regen-prompt.md returns at least one match (the rendered output's top heading) AND grep -nE 'Auto-generated by `/m1-tick status`' docs/process/status-regen-prompt.md returns at least one match AND grep -nF 'Runnable now' docs/process/status-regen-prompt.md returns at least one match AND grep -nF 'Dependency graph' docs/process/status-regen-prompt.md returns at least one match."
  - "docs/process/status-regen-prompt.md documents a short chat-reply contract carrying only the counts + runnable-list-one-liner + path-to-STATUS.md. Verify: grep -niE 'short chat reply|short return payload|short summary' docs/process/status-regen-prompt.md returns at least one match within the `## Template` block bounded by `## Skill responsibilities`."
  - "within .claude/skills/m1-tick/subcommands/status.md, the procedure delegates to the status-regenerator subagent. Verify: grep -nF 'subagent_type: \"status-regenerator\"' .claude/skills/m1-tick/subcommands/status.md returns at least one match AND grep -nF 'docs/process/status-regen-prompt.md' .claude/skills/m1-tick/subcommands/status.md returns at least one match (the procedure cites the prompt-template path it substitutes)."
  - "within .claude/skills/m1-tick/subcommands/status.md, the inline STATUS.md template body is removed. Verify: grep -nE '^# M1 status board' .claude/skills/m1-tick/subcommands/status.md returns zero matches AND grep -nE 'Auto-generated by `/m1-tick status`' .claude/skills/m1-tick/subcommands/status.md returns zero matches (the template now lives ONLY in the prompt template — single source of truth)."
  - "subcommands/status.md is materially smaller after the relocation. Verify: wc -l .claude/skills/m1-tick/subcommands/status.md returns a count less than 75 (post-M1-011 the file was ~130 lines; removing the template + delegating shrinks it to roughly the dispatch+reply parse + optional-flags handling). The 75 ceiling allows headroom for the filter-flag procedures if the developer wants them more thoroughly documented while still keeping the must-shrink signal (130 → < 75 is a real cut)."
  - "subcommands/status.md still implements the `--deferred` and `--escalated` filter flags as today (these stay main-session per out_of_scope). Verify: grep -nF '--deferred' .claude/skills/m1-tick/subcommands/status.md returns at least one match AND grep -nF '--escalated' .claude/skills/m1-tick/subcommands/status.md returns at least one match."
  - "within .claude/skills/m1-tick/subcommands/commit.md, the step that regenerates STATUS.md delegates to the status-regenerator subagent. Verify: grep -nF 'subagent_type: \"status-regenerator\"' .claude/skills/m1-tick/subcommands/commit.md returns at least one match AND the commit step's other procedures are unchanged (test-freshness check, commit-message build, the explicit `git add` staging, the final `git commit -m` invocation all still appear): grep -nF 'Test-freshness safety check' .claude/skills/m1-tick/subcommands/commit.md returns at least one match AND grep -nF 'Reviewed-by:' .claude/skills/m1-tick/subcommands/commit.md returns at least one match AND grep -nF 'git add' .claude/skills/m1-tick/subcommands/commit.md returns at least one match."
  - "the main session does NOT read all ticket files for STATUS.md regeneration. Verify by inspection of the post-edit `subcommands/status.md` and `subcommands/commit.md` delegation paragraphs: the Agent call's `prompt` argument is built from the static prompt template + path placeholders only; no `for each ticket` / `Read every M1-*.md` step remains in the main-session procedure for the regeneration path. (The `--deferred` and `--escalated` filter flags still read tickets in the main session — that is the per-out_of_scope exception.)"
  - "the structural contract for the regenerator is: subagent globs `docs/plan/m1/tickets/M1-*.md`, Reads each, computes the per-status counts and runnable list, Writes the rendered STATUS.md to `docs/plan/m1/STATUS.md` directly (not to a `.new` temp file — Write is OS-atomic for files of this size), and returns a structured short reply (counts + runnable-list-one-liner + the STATUS.md path). Verify that the prompt template documents each of these four steps: grep -niE 'Glob.*docs/plan/m1/tickets' docs/process/status-regen-prompt.md returns at least one match AND grep -niE 'Write.*STATUS|Write.*docs/plan/m1/STATUS' docs/process/status-regen-prompt.md returns at least one match."
  - "the calling subcommand wraps the status-regenerator spawn with a Write-scope guard: it snapshots `git status --porcelain` BEFORE spawning, captures the post-spawn snapshot, and refuses to proceed if any new working-tree change outside `docs/plan/m1/STATUS.md` appeared. This catches an agent that writes to an unintended path before the change can be staged. Verify: within each of .claude/skills/m1-tick/subcommands/status.md and .claude/skills/m1-tick/subcommands/commit.md, grep -niE 'git status --porcelain|Write-scope guard|new change.*outside.*STATUS' returns at least one match in the paragraph that spawns the subagent."
  - "mvn -B verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (this ticket only edits process docs, the new agent definition, and two skill subcommand files; mvn verify is a smoke check that no source code was perturbed)
spec_refs:
  - .claude/skills/m1-tick/subcommands/status.md §/m1-tick status
  - .claude/skills/m1-tick/subcommands/commit.md §/m1-tick commit
  - .claude/skills/m1-tick/SKILL.md §Cross-cutting rules
  - docs/process/clarity-prompt.md §Skill responsibilities
  - docs/process/reviewer-prompt.md §Skill responsibilities
  - .claude/agents/clarity-reviewer.md §Your role
  - .claude/agents/code-reviewer.md §Your role
decision_refs: []

reviews:
  - round: 1
    date: 2026-05-11
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 257
      removed: 133
  - round: 2
    date: 2026-05-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 308
      removed: 137
escalations:
  - date: 2026-05-11
    reason: budget-breach
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL
      The diff touches 6 files but files_budget: 4. Two paths outside
      files_scope: docs/plan/m1/STATUS.md and the ticket's own file.
      Both are workflow-mandated lifecycle side effects (skill regenerates
      STATUS.md and mutates ticket frontmatter at start/review/commit),
      but the numeric budget and lexical scope-list were not updated.
      Reviewer recommendation: raise files_budget 4→6 and append the two
      lifecycle paths to files_scope.
revisions:
  - date: 2026-05-11
    reason: round 1 REWORK directed files_budget+files_scope widening; per workflow rule "frontmatter changes go through escalate → refine", surfaced via budget-breach escalation rather than silent application
    snapshot:
      files_budget: 4
      files_scope:
        - .claude/agents/status-regenerator.md
        - docs/process/status-regen-prompt.md
        - .claude/skills/m1-tick/subcommands/status.md
        - .claude/skills/m1-tick/subcommands/commit.md
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-11
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-012: STATUS.md regeneration via fresh-context subagent

## Context

Every `/m1-tick commit` invocation (step 5) and every `/m1-tick
status` invocation regenerates `docs/plan/m1/STATUS.md` from the
union of ticket frontmatter under `docs/plan/m1/tickets/M1-*.md`.
The current procedure reads every ticket file into the **main
session**: for N tickets, that is N Read calls accumulating N
file-bodies in main-session transcript on every regeneration. At
~13 tickets today (~100–500 lines each), a single regeneration
pulls roughly 3–5 KB per ticket × 13 ≈ 30–60 KB into the main
session. The cost scales linearly with ticket count and pays out
even when the regeneration result is unchanged.

The work is structurally a perfect fit for a fresh-context
subagent: it Globs the tickets, Reads each, computes counts and
the dependency DAG, and Writes the rendered STATUS.md. None of
that needs to be in the main session — the regenerator does not
need conversation history, does not need to spawn other agents,
and does not need to inform any decision the developer makes. The
main session needs only the resulting summary (counts +
runnable-list one-liner) so the operator sees what landed.

This pairs with M1-010 (substitution-and-return-channel split for
clarity/review) and M1-011 (per-subcommand SKILL split) as the
third structural slim-down of the m1-tick workflow. M1-010 moved
**inputs** to disk-and-path; M1-012 moves **execution** (with
file-level inputs and outputs) to a fresh-context subagent. The
three together remove the dominant per-invocation costs of the
common `/m1-tick start`, `/m1-tick review`, `/m1-tick commit`,
and `/m1-tick status` paths.

The `--deferred` and `--escalated` filter flags stay in the main
session deliberately: they print filtered lists to chat without
writing STATUS.md, their cost is bounded by N tickets per
invocation, and delegating them would force a second subagent
invocation contract for a marginal benefit. The win this ticket
captures is on the regeneration path, which runs at least once
per `commit` and on every explicit `status` call.

## Definition of Done

- `.claude/agents/status-regenerator.md` exists with frontmatter
  declaring `name: status-regenerator`, `tools: Read, Write, Glob`
  (the minimal allowlist for the role — no Grep, no Bash, no
  Edit), a pinned `model:`, a `color:`, and a non-empty
  `description:`. The persona body (post-frontmatter) describes:
  fresh-context operation, Glob+Read over `docs/plan/m1/tickets/M1-*.md`,
  Write to the prompt-supplied `{{STATUS_FILE_PATH}}`, structured
  short summary as the chat reply. The persona explicitly notes
  that Write is allowed only at the path the prompt supplies —
  matching the M1-010 contract for clarity-reviewer / code-reviewer.
- `docs/process/status-regen-prompt.md` exists with a `## Template`
  block that substitutes `{{TICKETS_GLOB}}` (the glob pattern the
  agent uses to locate ticket files, e.g. `docs/plan/m1/tickets/M1-*.md`)
  and `{{STATUS_FILE_PATH}}` (the destination path,
  `docs/plan/m1/STATUS.md`). The template body INLINES the
  canonical STATUS.md output template (the same markdown block
  currently inside `subcommands/status.md` step 4): summary
  counts table, runnable now, in-flight table, blocked, escalated
  table, done table (10 most recent), deferred groupings, ASCII
  dependency DAG, plus the placeholder rules (the "no tickets
  yet" first-line override and the `_(none)_` per-section
  fallbacks). This makes the prompt template the single source of
  truth for the STATUS.md layout; `subcommands/status.md` no
  longer carries a copy.
- `docs/process/status-regen-prompt.md` documents a short chat-reply
  contract — the agent returns only the per-status counts, the
  runnable-list one-liner ("M runnable: ID1, ID2, ID3"), and the
  STATUS.md path. The full rendered STATUS.md is the artifact
  on disk; nothing else returns to chat. This matches the
  M1-010 return-channel pattern (full output to disk, short to
  chat).
- `.claude/skills/m1-tick/subcommands/status.md` is slimmed to:
  parse args (no args → regenerate; `--deferred` → filter mode;
  `--escalated` → filter mode), then for the regenerate path
  Read `docs/process/status-regen-prompt.md`, substitute
  `{{TICKETS_GLOB}}` and `{{STATUS_FILE_PATH}}`, spawn
  `Agent(subagent_type: "status-regenerator", prompt: <substituted>,
  description: "Regenerate STATUS.md")`, parse the short reply,
  print it. The inline STATUS.md template body is REMOVED from
  this file. The optional-flags branches (`--deferred`,
  `--escalated`) keep their existing main-session implementation.
- `.claude/skills/m1-tick/subcommands/commit.md` step 5
  ("Regenerate STATUS.md") is updated to spawn the
  `status-regenerator` subagent instead of regenerating in the
  main session. The substitution and spawn shape mirrors
  `subcommands/status.md` exactly (same prompt-template Read,
  same placeholders, same Agent invocation). The surrounding
  commit-step procedures — the test-freshness safety check (step
  2), the commit-message build (step 3), the frontmatter status
  update (step 4), the explicit `git add` staging (step 6), the
  final `git commit -m` (step 7), the `security_relevant` redteam
  reminder (step 8), and the next-step print (step 9) — are all
  byte-identical to their pre-edit form.
- `mvn -B verify` from the repo root exits 0.

## Implementation notes

- **The `{{TICKETS_GLOB}}` placeholder shape.** The skill
  substitutes the literal string `docs/plan/m1/tickets/M1-*.md`
  on every invocation today. Generalising via a placeholder
  means: (a) when M2 starts, only the skill (`subcommands/status.md`)
  changes — the prompt template and the agent definition are
  reused as-is for `docs/plan/m2/tickets/M2-*.md`; (b) the
  placeholder makes the agent's contract explicit and grep-able
  (the agent receives the glob, not a hardcoded path).
- **The `{{STATUS_FILE_PATH}}` placeholder shape.** Same shape
  as `{{TICKETS_GLOB}}`: the skill substitutes the literal
  string `docs/plan/m1/STATUS.md` today; the agent uses Write
  at the supplied path. The agent persona explicitly notes this
  constraint. (Trust-grant scope is wider than M1-010's
  `{{VERDICT_FILE_PATH}}`, which stayed under `target/`; the
  skill-level Write-scope guard documented below is what closes
  that gap.)
- **Why the STATUS.md template stays inline in the prompt template,
  not in a separate `status-template.md` file.** This is the
  central design choice of the ticket and worth surfacing before
  the alternatives section. Under M1-010's distinction,
  *fixed-per-invocation content* stays inline (the recency-bias
  defense applies and there is no leak across invocations), while
  *variable-size per-invocation content* moves to paths (the leak
  is per-invocation, the path indirection removes it). The
  STATUS.md template body is fixed-per-invocation — the same
  ~110 lines render every regeneration. The variable content is
  the ticket set, which already moves to paths via the agent's
  own Glob+Read; the per-ticket bytes never enter the main session.
  Splitting the template into a third file (e.g.,
  `docs/process/status-template.md`) would add a layer of
  indirection without removing a leak — the prompt template
  already lives under `docs/process/`, the agent Reads it once
  per invocation, and recency-bias for the template format is
  a feature for the agent. See Alt C for the rejected
  separate-file shape and its specific costs.
- **The agent's tool allowlist is tight.** Read (to load each
  ticket), Glob (to find them), Write (to emit STATUS.md). NOT
  Grep — the work is "load all, parse, render"; Grep would be
  redundant. NOT Bash — there is no shell-level operation
  required. NOT Edit — STATUS.md is rewritten in full, not
  incrementally patched. NOT any other tool. The minimal
  allowlist matches the existing pattern (clarity-reviewer has
  Read/Grep/Glob plus Write after M1-010; code-reviewer same).
- **Wider Write trust grant than M1-010 — note the category-shift.**
  M1-010 constrained the clarity-reviewer and code-reviewer Write
  to `target/` paths — workflow artifacts under Maven's
  clean-on-`mvn clean` directory that never enter git. M1-012's
  status-regenerator writes to `docs/plan/m1/STATUS.md`, which IS
  staged and committed. This is a real broadening of trust at the
  agent boundary; the harness `Write` permission is path-agnostic,
  and the constraint to the prompt-supplied path is prompt-level
  only. Two layered defenses keep the broadened trust honest:
  - **Prompt-level guard.** The agent persona body explicitly
    states that Write is allowed only at the prompt-supplied
    `{{STATUS_FILE_PATH}}`. The substituted prompt repeats the
    constraint; recency-bias holds.
  - **Skill-level guard.** Each calling subcommand
    (`subcommands/status.md` and `subcommands/commit.md`) snapshots
    `git status --porcelain` BEFORE spawning the subagent and
    captures the post-spawn snapshot. If any working-tree change
    outside `docs/plan/m1/STATUS.md` appears in the delta, the
    skill refuses to proceed and surfaces a clear error
    ("status-regenerator wrote to <path> outside its contract").
    A misbehaving agent's writes are caught before they can be
    staged or committed.
  Together: the prompt tells the agent what to do; the skill
  verifies the agent did only that. The skill-level guard is the
  load-bearing defense — prompt-level constraints are advisory,
  the porcelain diff is mechanical.
- **Atomicity.** Write to `docs/plan/m1/STATUS.md` directly. No
  temp-file-plus-rename dance. STATUS.md is small (a few KB) and
  Write is OS-atomic at this size. There are no concurrent
  writers — the skill serialises subcommand invocations and the
  workflow forbids parallel commits.
- **What the agent's structured short reply looks like.** Roughly:
  ```
  STATUS REGENERATED: docs/plan/m1/STATUS.md
  Counts: pending=N, in-progress=N, in-review=N, escalated=N, done=N, deferred=N
  Runnable: M tickets — M1-AAA, M1-BBB, M1-CCC
  In flight: <id-or-none>
  ```
  Three to five lines. The main session prints this to the
  operator and proceeds. The full STATUS.md is on disk for any
  follow-up inspection.
- **The dependency-DAG ASCII rendering.** The current
  `subcommands/status.md` step 4 includes an ASCII DAG with edges
  for `blocked_by` AND `deferred_on`. The agent reproduces this
  exactly; the template body inlines the same ASCII example block
  the current SKILL.md / subcommands/status.md carries.
- **The "no tickets yet" first-line override.** Currently
  documented in `subcommands/status.md` step 4: when the total
  ticket count is `0`, the `Last updated:` line renders as
  `(no tickets yet — Phase 1 scaffolding only; no tickets drafted)`
  instead of today's date. This rule travels with the template
  into the prompt — the agent applies it.
- **Why subcommands/status.md doesn't shrink to ~10 lines.** The
  optional `--deferred` and `--escalated` flags keep their
  current main-session implementation (per out_of_scope). Those
  branches still need their dispatch + classification + print
  procedures. The regenerate-from-no-args path is what slims.
  Expected post-edit size: ~30–40 lines (down from ~130).
- **commit.md step 5 minimal touch.** The replacement is two-or-
  three lines: read the prompt template path, substitute the two
  placeholders, spawn the subagent, parse the reply. The
  surrounding step numbering (1–9) stays unchanged; only the body
  of step 5 changes.
- **The agent does not regenerate `STATUS.md` for filter modes.**
  When the operator runs `/m1-tick status --deferred`, no
  regeneration happens — the main session reads tickets, filters
  by `status: deferred`, prints. STATUS.md on disk is not
  touched. Same for `--escalated`. The status-regenerator agent
  is invoked only on the no-args regenerate path and from
  commit's step 5.

## Big-picture notes

- **The structural win compounds with M1-010 and M1-011.** M1-010
  moves the dominant per-invocation INPUT costs to disk-and-path
  (ticket body, diff, test log). M1-011 splits the SKILL.md
  monolith so each invocation loads only the subcommand it needs.
  M1-012 moves the dominant per-invocation EXECUTION cost (ticket
  enumeration for STATUS.md) into a fresh-context subagent. After
  all three, a `/m1-tick commit M1-NNN` on a milestone with 50
  tickets pays the same main-session cost as one on a milestone
  with 5 tickets. Today the cost grows linearly with N.
- **STATUS.md is the right scope unit.** Other workflow artifacts
  (the audit-trail trailer in commit messages, the per-round diff
  stats stored in frontmatter, the clarity/review verdict files)
  are bounded by per-ticket size and don't grow with N. STATUS.md
  is the only workflow artifact that aggregates across N tickets;
  it is the only one that benefits from delegation. Don't
  generalise the pattern to artifacts that don't need it.
- **The agent's contract is small and stable.** Glob, Read N
  ticket frontmatters, compute the rendered template, Write
  once, return summary. There are no branching modes, no
  user-interactive prompts, no escalation paths. The agent is
  the simplest of the four subagents this workflow defines
  (clarity, code-reviewer, threat-actor, status-regenerator) —
  its prompt template can be substantially shorter than the
  others.
- **What this does NOT do.** It does not change WHAT STATUS.md
  contains. It does not change WHEN it regenerates. It does not
  change who can edit it (still "no hand edits" per SKILL.md
  cross-cutting rules). It changes only WHERE the regeneration
  computation runs — main session → fresh-context subagent.

## Out-of-scope expansion

- **STATUS.md template format.** The counts table, the runnable
  list shape, the deferred groupings, the ASCII DAG conventions
  — none change. The template moves from skill subcommand body
  to prompt template body; the rendered output is byte-identical.
  Changing the format is a separate concern that warrants its
  own ticket (and likely a spec_amend if any external doc
  references a specific column or section name).
- **Other regenerations.** This ticket does not delegate the
  in-memory ticket reads that happen for `/m1-tick next` (which
  lists runnable tickets without writing anything) or
  `/m1-tick show <id>` (which reads a single ticket). Those have
  different cost profiles — `next` reads N tickets but does NOT
  write a file, and `show` reads ONE ticket. The regeneration
  delegation is justified by the write-a-file work; the others
  can stay main-session.
- **`--deferred` and `--escalated` filter flags.** Stay main-
  session. Their cost is bounded by N tickets per invocation,
  they don't write a file, and the operator invokes them on
  demand for inspection — not on every commit. Delegating them
  would add a second subagent contract and a second prompt
  template for marginal benefit. Reconsider only if a future
  optimisation pass shows them as a hot path.
- **Atomicity / temp-file-and-rename.** No. Write at the target
  path directly. The file is small; there are no concurrent
  writers; the workflow serialises subcommands. Adding an
  atomic-rename ceremony would be ceremony without benefit.
- **Threat-actor / `/redteam` skill.** Untouched. The threat-actor
  agent is intentionally decoupled per CLAUDE.md §M1 workflow;
  STATUS.md regeneration is not security-relevant.
- **Plan-prompt slim-down.** The plan subagent and its prompt
  template are out of scope; that is a separate (still-deferred)
  follow-up to M1-010.
- **Engineering-rules / ticket-template / workflow.md.** None
  reference STATUS.md regeneration mechanics. Untouched.
- **CLAUDE.md.** Its §M1 workflow references `/m1-tick status`
  by invocation surface, not by internal mechanism. The
  invocation surface is preserved; CLAUDE.md is untouched.
- **Repo source code, poms, tests, migrations.** Process-docs-
  and-skill-and-agent-definition diff only.
- **Other ticket files.** M1-001 through M1-011 are untouched.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The suite
  is currently green at M1-001..M1-003 level; `mvn verify` is a
  smoke check, not a behavioral assertion of these edits. The
  acceptance criteria are checked by grep against the edited
  files and by inspection of the post-edit delegation paragraphs
  in subcommands/status.md and subcommands/commit.md.)

## Alternatives considered

- **Alt A: don't refactor — live with the N-ticket main-session
  read cost.** Rejected: scales linearly with ticket count. M1
  is small today (~13 tickets) but the cost is real and grows.
  The fix is structural; the longer it waits, the more
  invocations pay the avoidable cost.
- **Alt B: use the built-in `general-purpose` subagent instead
  of defining a new `status-regenerator` agent.** Rejected
  on two grounds: (1) the project pattern (clarity-reviewer,
  code-reviewer, threat-actor) is custom agents per role with
  tight tool allowlists — diverging from that for one
  subcommand reduces consistency without saving meaningful
  effort; (2) `general-purpose` has a broad tool allowlist that
  the status-regenerator does not need (no Edit, no Bash, no
  WebFetch). The minimal-allowlist custom agent is the safer
  shape.
- **Alt C: move the STATUS.md template to a third static file
  (e.g., `docs/process/status-template.md`) that both the prompt
  and any future documentation reference.** Rejected: the
  template is the agent's task contract, not separately-
  referenceable spec. One file per role-and-template (the
  prompt template) is the existing pattern; adding a third
  layer is bureaucracy for no payoff. If a future doc needs to
  cite the template shape, it cites the prompt template.
- **Alt D: bundle this with M1-010 or M1-011.** Rejected: M1-010
  is the substitution-channel split for inputs/outputs of the
  clarity and review subagents; M1-011 is the SKILL.md
  per-subcommand split. M1-012 is a third structural change to
  delegate execution for a specific subcommand. Bundling any
  pair would inflate per-round diff stats and risk muddling the
  reviewer's APPROVE/REWORK signals. Sequential keeps each
  diff focused.
- **Alt E: also delegate `--deferred` and `--escalated` flag
  paths to the subagent.** Rejected: per out_of_scope reasoning.
  Those flags don't write a file and their main-session cost
  is bounded; the consistency benefit isn't worth the second
  prompt-mode in the template.
- **Alt F: write STATUS.md atomically via `target/STATUS.md.new`
  + `mv`.** Rejected: Write is OS-atomic at this file size;
  there are no concurrent writers; the workflow serialises
  subcommands. Adding the rename dance solves a problem that
  doesn't exist.
- **Alt G: block this ticket on M1-010 only (not M1-011), so it
  can run in parallel with M1-011.** Rejected: M1-011 moves the
  `## status` body and the `## commit` body into
  `subcommands/status.md` and `subcommands/commit.md`. If
  M1-012 lands first, it edits SKILL.md sections that M1-011
  will then relocate — guaranteed merge conflict. Sequencing
  M1-012 after M1-011 means we edit the post-split files
  directly with no churn. The `files_scope` list reflects the
  post-M1-011 paths.

## Round 1 rework

Reviewer verdict (2026-05-11, round 1): REWORK. One item.

1. The diff exceeds `files_budget: 4` (6 files touched) and includes 2 paths outside `files_scope`:
   - `docs/plan/m1/STATUS.md` (regenerated by `start` and again by `commit` step 5)
   - `docs/plan/m1/tickets/M1-012-status-md-regenerator-subagent.md` (frontmatter status flip by `start`; reviews trailer + status flip by `review` and `commit`)

   Both are workflow-mandated lifecycle side effects, not implementation edits. The ticket's `out_of_scope` text carves them out qualitatively, but `files_budget` and `files_scope` were not numerically/lexically updated to match.

   Reviewer recommendation: raise `files_budget` from 4 to 6 and append the two lifecycle paths to `files_scope`.
