---
id: M1-011
title: Split SKILL.md per subcommand
status: pending
created: 2026-05-11
last_updated: 2026-05-11
blocked_by:
  - M1-010
files_budget: 12
files_scope:
  - .claude/skills/m1-tick/SKILL.md
  - .claude/skills/m1-tick/subcommands/next.md
  - .claude/skills/m1-tick/subcommands/start.md
  - .claude/skills/m1-tick/subcommands/review.md
  - .claude/skills/m1-tick/subcommands/commit.md
  - .claude/skills/m1-tick/subcommands/merge.md
  - .claude/skills/m1-tick/subcommands/escalate.md
  - .claude/skills/m1-tick/subcommands/abort.md
  - .claude/skills/m1-tick/subcommands/show.md
  - .claude/skills/m1-tick/subcommands/reopen.md
  - .claude/skills/m1-tick/subcommands/status.md
  - docs/process/workflow.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change to subcommand procedure semantics — every per-subcommand file is the verbatim relocated procedure from the pre-split SKILL.md, with the substitution edits from M1-010 already in place. Wording stays. Section order within each subcommand stays. The split is a relocation, not a rewrite.
  - any edit to docs/process/clarity-prompt.md or docs/process/reviewer-prompt.md (those are M1-010's territory; M1-011 only references them by path from inside the relocated start.md/review.md files)
  - any edit to docs/process/engineering-rules-verbatim.md, docs/process/ticket-template.md, docs/process/plan-prompt.md, docs/process/redteam-prompt.md (process-doc files outside this split)
  - any edit to docs/plan/m1/README.md or CLAUDE.md (their §M1 workflow text and the `/m1-tick next` pointers stay accurate because the skill name and invocation surface are unchanged)
  - any edit to .claude/agents/clarity-reviewer.md or .claude/agents/code-reviewer.md or .claude/agents/threat-actor.md (the agent definitions are untouched by the skill-internal split)
  - any edit to .claude/skills/redteam/ (the redteam skill is a separate skill; not affected)
  - any edit to other ticket files under docs/plan/m1/tickets/ (M1-001..M1-009 stay untouched, including M1-002's now-stale §commit/§review spec_refs — those are historical artifacts of a done ticket and per the workflow are immutable)
  - any change to repo source code, poms, application.properties, migrations, or test code
  - any new Maven module or pom change of any kind
  - any change to STATUS.md content beyond what the regenerator emits
  - the routing-table format itself is not load-bearing for parallelism or any other workflow rule — the only invariant is "the router maps subcommand → file and instructs the agent to Read that file before applying the procedure"
acceptance:
  - "test -d .claude/skills/m1-tick/subcommands returns 0 (directory exists)"
  - "for each of next, start, review, commit, merge, escalate, abort, show, reopen, status: test -f .claude/skills/m1-tick/subcommands/<name>.md returns 0 (10 per-subcommand files exist)"
  - "wc -l .claude/skills/m1-tick/SKILL.md returns a count less than 200 (the router is materially smaller than the pre-split monolith of 697 lines; the per-subcommand procedure bodies have all moved out)"
  - "grep -nE '^## ' .claude/skills/m1-tick/SKILL.md returns exactly two procedure-level headings: `## Subcommand routing` and `## Cross-cutting rules this skill must obey`. No other `## ` heading representing a subcommand procedure remains in SKILL.md (specifically: grep -nE '^## (next|start|review|commit|merge|escalate|abort|show|reopen|status)' .claude/skills/m1-tick/SKILL.md returns zero matches)."
  - "grep -nF 'subcommands/' .claude/skills/m1-tick/SKILL.md returns at least 10 matches (the routing table cites one per-subcommand file path per row)"
  - "grep -niE 'read .*subcommands/' .claude/skills/m1-tick/SKILL.md returns at least one match (the router explicitly instructs the agent to Read the relevant per-subcommand file before applying its procedure — without this instruction the agent might try to apply procedure from memory)"
  - "each per-subcommand file under .claude/skills/m1-tick/subcommands/ begins with a `# /m1-tick <name>` first-level heading (verify per file: `head -1 .claude/skills/m1-tick/subcommands/<name>.md` matches `^# /m1-tick `)"
  - "subcommands/start.md is the new home for the clarity pre-flight + branch creation + Plan-subagent + STATUS regenerate flow. Verify: grep -nF 'Ticket-clarity pre-flight' .claude/skills/m1-tick/subcommands/start.md returns at least one match AND grep -nF 'git checkout -b' .claude/skills/m1-tick/subcommands/start.md returns at least one match"
  - "subcommands/review.md is the new home for the reviewer-subagent spawn + verdict parsing flow. Verify: grep -nF 'code-reviewer' .claude/skills/m1-tick/subcommands/review.md returns at least one match AND grep -nF 'must-shrink' .claude/skills/m1-tick/subcommands/review.md returns at least one match"
  - "subcommands/commit.md is the new home for the test-freshness safety-check + commit-message-build flow. Verify: grep -nF 'Test-freshness safety check' .claude/skills/m1-tick/subcommands/commit.md returns at least one match AND grep -nF 'Reviewed-by:' .claude/skills/m1-tick/subcommands/commit.md returns at least one match"
  - "subcommands/escalate.md is the new home for the five-way menu AND the ID allocation algorithm. Verify: grep -nF 'five-way menu' .claude/skills/m1-tick/subcommands/escalate.md returns at least one match AND grep -nE '^### ID allocation algorithm' .claude/skills/m1-tick/subcommands/escalate.md returns at least one match"
  - "the two markdown link refs in docs/process/workflow.md to the ID allocation algorithm anchor are updated to point at the new home. Verify: grep -nF '/SKILL.md#id-allocation-algorithm' docs/process/workflow.md returns zero matches AND grep -nF 'subcommands/escalate.md#id-allocation-algorithm' docs/process/workflow.md returns at least two matches (the two existing link instances are both updated)"
  - "the routing table in .claude/skills/m1-tick/SKILL.md still preserves the existing subcommand surface so user invocations like `/m1-tick next`, `/m1-tick start <id>`, `/m1-tick review <id>`, etc. continue to dispatch unchanged. Verify: grep -nE '`/m1-tick (next|start|review|commit|merge|escalate|abort|show|reopen|status)' .claude/skills/m1-tick/SKILL.md returns at least one match per subcommand (10 distinct dispatch lines)"
  - "the Cross-cutting rules section is preserved in the router (still inside SKILL.md). Verify: grep -nF 'Cross-cutting rules this skill must obey' .claude/skills/m1-tick/SKILL.md returns at least one match AND grep -nF 'Never push' .claude/skills/m1-tick/SKILL.md returns at least one match (representative cross-cutting rule)"
  - "the relocated procedure bodies match the pre-split content semantically — no rewording, no scope drift. Verify by inspection of the diff: the per-subcommand files are the relocated text from SKILL.md, modulo the new top-level `# /m1-tick <name>` heading and removed `## <subcommand>` heading. No new procedure steps, no removed procedure steps. The diff's net line change in `.claude/skills/m1-tick/SKILL.md` is large-negative (text removed); the net line change across the new subcommand files sums to approximately the same large-positive (text added) — preserving the procedure body verbatim modulo heading adjustments."
  - "mvn -B verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (this ticket only edits skill/process markdown; mvn verify is a smoke check that no source code was perturbed)
spec_refs:
  - .claude/skills/m1-tick/SKILL.md §Subcommand routing
  - .claude/skills/m1-tick/SKILL.md §Cross-cutting rules
  - .claude/skills/m1-tick/SKILL.md §start
  - .claude/skills/m1-tick/SKILL.md §escalate
  - .claude/skills/m1-tick/SKILL.md §ID allocation algorithm
  - docs/process/workflow.md §Naming conventions
decision_refs: []

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-011: Split SKILL.md per subcommand

## Context

`.claude/skills/m1-tick/SKILL.md` is a 53 KB / ~697-line monolith
documenting all 10 `/m1-tick` subcommands plus the cross-cutting
rules. Any `/m1-tick <anything>` invocation loads the whole file
into the agent's context — even when the user runs `/m1-tick start`
and never needs the `commit`/`merge`/`abort`/`status` procedures.

A subcommand router plus per-subcommand files is the structural
fix: SKILL.md keeps the routing table, dispatch instructions, and
cross-cutting rules; the per-subcommand procedure bodies move to
`.claude/skills/m1-tick/subcommands/<name>.md`. The agent reads
only the relevant subcommand file at dispatch time. A `/m1-tick
start` invocation then loads the slim router + subcommands/start.md
instead of the full monolith.

The pair-with: M1-010 lands the prompt-template substitution change
(`{{TICKET_FILE_PATH}}` not `{{TICKET_FILE_CONTENT}}`; same for
diff and test output; full verdict to disk, short payload to chat).
M1-010 leaves its edits in SKILL.md's `## start <id>` and
`## review <id>` sections; M1-011 picks them up and relocates them
into subcommands/start.md and subcommands/review.md. Sequencing
M1-011 after M1-010 (via `blocked_by`) keeps each diff tight.

## Definition of Done

- `.claude/skills/m1-tick/subcommands/` directory exists and
  contains exactly ten files, one per subcommand: `next.md`,
  `start.md`, `review.md`, `commit.md`, `merge.md`, `escalate.md`,
  `abort.md`, `show.md`, `reopen.md`, `status.md`. Every file's
  first line is a top-level heading `# /m1-tick <name>`.
- Each per-subcommand file is the verbatim relocated procedure
  from the pre-split SKILL.md (post-M1-010 wording). No semantic
  changes; no new steps, no removed steps; no reworded acceptance
  preconditions. The split is a relocation, not a rewrite.
- `subcommands/escalate.md` contains the full ID allocation
  algorithm (currently a `### ID allocation algorithm` sub-section
  under `## escalate` in SKILL.md). The sub-heading shape is
  preserved so `subcommands/escalate.md#id-allocation-algorithm`
  is a valid anchor.
- The two markdown link references in `docs/process/workflow.md`
  to `(.../SKILL.md#id-allocation-algorithm)` are updated to
  `(.../subcommands/escalate.md#id-allocation-algorithm)`. No
  other links break.
- `.claude/skills/m1-tick/SKILL.md` is slimmed to a router carrying:
  (a) the skill's frontmatter (unchanged), (b) a short intro
  paragraph pointing at the rules and the universal workflow doc
  (verbatim from current SKILL.md), (c) a `## Subcommand routing`
  section with the dispatch table mapping each subcommand to its
  per-subcommand file path AND an explicit instruction to the
  agent to Read the relevant file before applying its procedure,
  (d) the `## Cross-cutting rules this skill must obey` section
  (verbatim from current SKILL.md). No per-subcommand procedure
  body remains in SKILL.md.
- Total post-split SKILL.md length is < 200 lines. The current
  pre-split file is 697 lines; the router carries ~80 lines of
  routing + ~70 lines of cross-cutting rules + intro overhead.
- `mvn -B verify` from the repo root exits 0.

## Implementation notes

- **The exact split boundaries.** Pre-split SKILL.md has the
  following procedure-body sections (heading-line numbers approximate,
  taken from the file at draft time of this ticket):
  - `## next — list runnable tickets` (lines 34–55)
    → `subcommands/next.md`
  - `## start <id> [--parallel]` (lines 59–114)
    → `subcommands/start.md`
  - `## review <id>` (lines 118–177)
    → `subcommands/review.md`
  - `## commit <id>` (lines 181–222)
    → `subcommands/commit.md`
  - `## merge <id>` (lines 226–259)
    → `subcommands/merge.md`
  - `## escalate <id> [reason]` (lines 263–415, including the
    `### ID allocation algorithm` sub-section at lines 417–428)
    → `subcommands/escalate.md`
  - `## abort <id>` (lines 432–494)
    → `subcommands/abort.md`
  - `## show <id>` (lines 498–507)
    → `subcommands/show.md`
  - `## reopen <id>` (lines 511–550)
    → `subcommands/reopen.md`
  - `## status — regenerate STATUS.md` (lines 554–683)
    → `subcommands/status.md`
  These line numbers shift after M1-010 lands (the substitution
  edits change the `## start <id>` and `## review <id>` sections).
  The split derives its boundaries from the post-M1-010 SKILL.md;
  the boundaries above are the structural map, not byte-precise
  cut points.
- **Per-subcommand file shape.** Each file starts with:
  ```
  # /m1-tick <subcommand>
  
  <intro sentence — one-liner naming what the subcommand does>
  
  <body — relocated procedure verbatim, demoted one heading
  level so the file's `## ` becomes `## ` matching what was
  `## <subcommand>` in the monolith, etc.>
  ```
  Actually heading demotion is the OPPOSITE shape: the monolith's
  `## <subcommand>` becomes the per-file `# /m1-tick <subcommand>`,
  the monolith's `### <something>` becomes the per-file `## <something>`.
  Pick the convention and apply it consistently: top-level heading
  for the subcommand, second-level for the algorithm sub-sections
  (e.g., `## ID allocation algorithm` inside `subcommands/escalate.md`).
- **The router's "Read this file first" instruction.** This is
  the load-bearing wiring of the split. The router must include
  (verbatim, post-edit):
  > When the user invokes `/m1-tick <subcommand>`:
  >   1. Parse the args. Identify the subcommand.
  >   2. Read `.claude/skills/m1-tick/subcommands/<subcommand>.md`.
  >   3. Apply that file's procedure verbatim. The procedure file
  >      is the single source of truth for that subcommand.
  Without this instruction, the agent might try to apply procedure
  from memory; the entire point of the split is to ensure the
  per-subcommand file IS what the agent loads.
- **Cross-cutting rules stay in the router.** The current
  `## Cross-cutting rules this skill must obey` section at the
  bottom of SKILL.md (lines 686–697) is short, applies to every
  subcommand, and is the right shape to load on every invocation
  alongside the routing table. Keep it in SKILL.md.
- **Internal cross-references within subcommand files.** Several
  subcommand procedures reference others (e.g. `commit.md`
  mentions running `/m1-tick merge`; `escalate.md` mentions
  branches dispatching to `refine` → re-run `/m1-tick start`).
  These references are by `/m1-tick <name>` invocation, not by
  file path, so they don't break. No internal link rewriting
  needed beyond the workflow.md anchor fix.
- **External cross-references to specific SKILL.md sections.**
  The grep at this ticket's draft time found:
  - `docs/process/workflow.md` lines 19, 21 — two markdown link
    refs to `#id-allocation-algorithm`. Fix to point at the new
    location: `subcommands/escalate.md#id-allocation-algorithm`.
  - `docs/plan/m1/tickets/M1-002-*.md` — three references in
    spec_refs and historical clarity_check excerpts. M1-002 is
    `done`; per CLAUDE.md §M1 workflow ("never amend a passed
    commit"), these are immutable historical artifacts and NOT
    in scope.
  - `docs/plan/m1/tickets/M1-010-*.md` — this ticket's blocker;
    its spec_refs cite SKILL.md §start and §review. At the time
    M1-010's clarity ran, those anchors existed in pre-split
    SKILL.md; this ticket's split moves them out, but M1-010
    will be `done` before M1-011 starts, so the spec_refs at
    M1-010 are historical and untouched.
- **No edit to .claude/agents/*.md.** The agent definition files
  reference the m1-tick skill by name and the prompt template by
  path — neither is affected by the SKILL.md internal split.
- **No edit to CLAUDE.md or docs/plan/m1/README.md.** They
  reference the skill's surface (`/m1-tick next`, `/m1-tick start`,
  etc.) which is preserved. They don't deep-link into SKILL.md.

## Big-picture notes

- **The structural win.** The pre-split SKILL.md is 53 KB. The
  post-split router is < 15 KB (router intro + ~80 line routing
  table + ~70 line cross-cutting rules section). A `/m1-tick
  start M1-005` invocation loads the router + subcommands/start.md
  (~8–10 KB) instead of the full monolith — a ~30 KB reduction
  for every `start` invocation, even more for `next`/`show` which
  only need their own small subcommand file plus the router.
  Combined with M1-010's substitution-and-verdict slim-down, the
  user's observed 100k-token start cost should drop materially.
- **The split is structural, not behavioral.** Every subcommand
  procedure stays semantically identical. The reviewer's job at
  review time is to confirm relocation, not rewrite — the diff
  is mostly file-moves (large delete from SKILL.md, large add to
  the subcommands directory) with a small focused edit to the
  router section and a two-link patch to workflow.md.
- **Why a flat `subcommands/` directory instead of subdir-per-
  subcommand.** Each subcommand is one self-contained procedure
  file; no per-subcommand assets (no test data, no sub-includes).
  A flat layout keeps the file list scannable in
  `.claude/skills/m1-tick/subcommands/` and matches the routing
  table's flat shape one-to-one.
- **What if a subcommand grows complex enough to warrant its
  own split later?** That is a M2+ concern. M1's surface — 10
  subcommands, each procedure is one document — fits the flat
  layout. The split itself is the precedent if a future
  subcommand needs further decomposition.
- **The cross-cutting rules section's "loaded every invocation"
  property is preserved.** Today the rules section is at the
  bottom of SKILL.md and the agent reads the full file at any
  invocation; post-split, the rules section is in the router
  (which is the entry point for every invocation), so the
  read-every-invocation property is intact. Without this, a
  subcommand-specific procedure might violate a cross-cutting
  rule the agent hadn't loaded.

## Out-of-scope expansion

- **Subcommand procedure semantics.** Every per-subcommand file
  is the verbatim relocated procedure. No new steps, no removed
  steps, no reworded acceptance preconditions, no changed
  ordering. The point is to reduce token load, not to rewrite
  the workflow.
- **M1-010's prompt-template files.** `docs/process/clarity-prompt.md`
  and `docs/process/reviewer-prompt.md` are M1-010's territory.
  M1-011 only references them by path from inside the relocated
  `subcommands/start.md` and `subcommands/review.md` files (the
  references already exist in pre-split SKILL.md and travel with
  the relocated procedure body unchanged).
- **Other process docs.** `engineering-rules-verbatim.md`,
  `ticket-template.md`, `plan-prompt.md`, `redteam-prompt.md`,
  `docs/plan/m1/README.md` — all out of scope. The split is
  internal to the `/m1-tick` skill.
- **Agent definition files.** `.claude/agents/{clarity-reviewer,
  code-reviewer,threat-actor}.md` are untouched.
- **The `/redteam` skill.** A separate skill in a separate
  directory; not affected by the m1-tick split.
- **CLAUDE.md.** Its §M1 workflow section references subcommands
  by `/m1-tick <name>` invocation, not by SKILL.md anchor; the
  split preserves the invocation surface.
- **Other ticket files** under `docs/plan/m1/tickets/`. M1-001
  through M1-009 are untouched. M1-002's historical references
  to `SKILL.md §commit` and `§review` are immutable artifacts
  of a done ticket.
- **Repo source / poms / tests / migrations.** Process-docs-and-
  skill diff only.
- **STATUS.md content.** Regenerator output; the regen step in
  the (relocated) commit subcommand is untouched in semantics.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The suite
  is currently green at M1-001..M1-003 level; `mvn verify` is a
  smoke check, not a behavioral assertion of these edits. The
  acceptance criteria are checked by grep and `wc` against the
  edited files, and by inspection of the split's relocation
  fidelity.)

## Alternatives considered

- **Alt A: bundle the split with M1-010's substitution edits.**
  Rejected: two structurally different changes (substitution wire
  format vs file-layout split) in one diff doubles cognitive load
  for the reviewer and inflates the per-round diff stats, making
  the round-2 must-shrink check noisier than necessary. Sequencing
  M1-011 after M1-010 keeps each diff focused.
- **Alt B: subdir-per-subcommand layout
  (`subcommands/start/start.md`, `subcommands/start/README.md`,
  etc.).** Rejected as premature generalization. Each subcommand
  is one self-contained procedure today; no per-subcommand assets
  exist. A flat layout matches the surface and stays scannable.
- **Alt C: keep the ID allocation algorithm in the router.**
  Rejected: the algorithm is only invoked from `escalate`
  (decompose and spec-amend resolutions). Loading it on every
  invocation is wasted load. Moving it to `subcommands/escalate.md`
  matches "load only what this subcommand needs" — which is the
  whole point of the split.
- **Alt D: only split the largest subcommands (escalate, status,
  abort), leave smaller ones (show, reopen, next) in SKILL.md.**
  Rejected: the routing table's value comes from consistency —
  "every subcommand has its own file". Mixed layouts force the
  agent (and the human reader) to remember which subcommands are
  inline vs separate, which is a worse experience than a uniform
  split.
- **Alt E: rewrite the cross-cutting rules section while we're
  here (e.g., move it to its own file under `subcommands/`).**
  Rejected as scope drift. The cross-cutting rules section
  belongs in the router (it applies to every subcommand and the
  router loads on every invocation). Leave it where it is and
  where its semantic role places it.
- **Alt F: use markdown transclusion / link refs (`{{> path}}`)
  instead of moving the text.** Rejected: there is no markdown
  transclusion mechanism in the Claude Code harness; the agent
  reads files literally. The relocation has to be real file moves.
