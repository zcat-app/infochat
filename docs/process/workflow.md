# Ticket-driven workflow specification

This document is the universal workflow specification — the lifecycle, ticket frontmatter, reviewer behavior, escalation, and commit conventions that apply to ticket-driven work across milestones. It is the single source of truth for the procedure.

The verbatim engineering rules and test-integrity rules the reviewer enforces live in [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md). That file is the editing source; this document references it rather than duplicating the rule prose. The reviewer prompt embeds it inline because the reviewer subagent runs in fresh context.

The skill-loaded summary lives in [`.claude/skills/m1-tick/SKILL.md`](../../.claude/skills/m1-tick/SKILL.md) §M1 workflow rules (loads when `/m1-tick` fires). A slim pointer + the commit-prefix table stay in `CLAUDE.md` §M1 workflow. Per-milestone framing (what's in scope this milestone, deltas from this universal workflow) lives in `docs/plan/<milestone>/README.md` (e.g. `docs/plan/m1/README.md`).

Precedence on conflict: the SKILL.md / CLAUDE.md summary content < this document < [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md) (the rules themselves are canonical). If anything here contradicts the summary, sync the summary. If anything here contradicts `engineering-rules-verbatim.md`, this file is wrong.

Harness bindings for the primitives this spec names (fresh-context subagent spawn, blocking user menu, worktree parallelism) live in [`harness-mapping.md`](harness-mapping.md) — Claude Code is the native harness; other coding agents apply that mapping.

> **Milestone tokens used below.** Examples use `M<N>` (e.g. `M1`, `M2`) for ticket-ID prefixes and `m<N>` (e.g. `m1`, `m2`) for branch and directory tokens. The currently active milestone is M1, driven by the `/m1-tick` skill. Future milestones may instantiate their own driver skill or extend the existing one.

> **Ticket-ID placeholder convention.** Where examples and skill text reference ticket IDs, they use these placeholders to avoid confusing the *operand* of an invocation with *other* tickets touched by it:
>
> - `M<N>-NNN` — **operand**: the ticket the user passed to the subcommand (e.g. `/m1-tick start M1-007` makes `M<N>-NNN = M1-007` everywhere in that invocation).
> - `M<N>-AAA`, `M<N>-BBB`, `M<N>-CCC` — **newly created** by this invocation (decompose children, spec-amend amendment ticket, drafted blocker on defer).
> - `M<N>-XXX`, `M<N>-YYY`, `M<N>-ZZZ` — **referenced** by the operand or by frontmatter (existing `blocked_by` entries, `decomposed_from`, `deferred_on`, `spec_amend_parent` from the perspective of an unrelated ticket).
>
> **Umbrella + subticket idiom (recommended for logical-group splits).** A ticket ID may optionally carry a lowercase-letter suffix (`M1-008a`, `M1-008b`, `M1-008c`) to denote a subticket of an umbrella ticket sharing the same digit slot. The pattern: a bare `M1-NNN` ticket holds the *topic-level* context (shared design intent, cross-cutting invariants, the whole-topic acceptance) and its `blocked_by` lists the subtickets `M1-NNNa`, `M1-NNNb`, etc.; each subticket implements one slice with its own tests and own commit; once all subtickets are `done`, the umbrella becomes runnable and ships the whole-topic integration test as its own reviewable commit. The umbrella and its subtickets are **distinct independent tickets** — they each have their own frontmatter, branch, review round, and entry on the dependency graph. Suffix-IDs are authored by hand at planning time; the milestone-driver skill never auto-generates them. To the rest of the workflow they are opaque strings — they participate in `blocked_by`, branch names, commit subjects, and grep history identically to primary IDs. The only place the suffix is meaningful is the [ID allocation algorithm](../../.claude/skills/m1-tick/subcommands/escalate.md#id-allocation-algorithm).
>
> When you see `M<N>-NNN` in skill steps, it is always the operand. When you see `M<N>-AAA` and friends, those are fresh IDs the skill allocates per the [ID allocation algorithm](../../.claude/skills/m1-tick/subcommands/escalate.md#id-allocation-algorithm). When you see `M<N>-XXX`, the ticket is being referenced by ID without being either the operand or newly created.

---

## Process doctrine — global deletions, not local patches

When a ticket failure surfaces, the default response is to evaluate whether an existing check, field, or rule should be REMOVED — not whether a new one should be added. Process surface grows easily; shrinking requires deliberate doctrine.

1. **When a ticket fails, ask "what *class* of problem is this?" before patching.** A single failure is a data point; two failures of the same shape may be a pattern; only a pattern justifies a process change. One-off failures are noise — fix the ticket, not the process.
2. **Prefer deletion over addition.** A failure pointing at an existing check being insufficient is more often a sign the check shouldn't exist than that it needs more rules. If the check has produced ≥3 false positives, delete it; do not strengthen it.
3. **The contract lives in code + tests + spec, not in ticket prose.** Tickets are briefs that point at the spec; the reviewer compares the diff against the spec. Do not re-state spec content inside tickets; do not police ticket-internal consistency of paraphrased spec.
4. **`mvn verify` is the green gate.** Author claims about test behavior (will-stay-green, won't-conflict, dependency-on-X) are unnecessary — the test suite proves or disproves them at runtime. Do not require authors to predict what the suite will do.
5. **Git is the audit trail.** Escalation history, refine reasons, prior-round verdicts — git log preserves them. Do not accumulate the same data in YAML frontmatter; redundant copies drift.
6. **Ground-truth checks over prose checks.** When a new failure mode genuinely requires a process change, prefer a check that reads ground truth — the reviewer (code + tests + the diff) or the deterministic linter (`scripts/lint-ticket.py`, mechanical facts about the ticket file) — over one that reads and judges ticket prose. The clarity-reviewer subagent (a prose-judging gate) was deleted in the 2026-07-19 cutover for exactly this reason: it FAILed ~1 in 5 tickets at a rate that never improved, ~90% of its catches were mechanical (now the linter's job), and the residue was "the ticket is wrong about the code," which the developer catches for free at implementation. A prose-judging subagent is the last resort, not the default.

Apply the doctrine on every proposed process change: is this addition addressing a class with evidence, or patching a one-off? Could a deletion solve it better?

---

## Lifecycle

```
   pending  ─────────────────────────────────────────────────────────┐
      │  ▲                                                           │
      │  │ refine after outline-fail                                 │
      │  │ (no implementation yet; status returns to pending,        │
      │  │  user re-runs /<driver> start to re-run the pre-flight)   │
      │  │                                                           │
      ▼  │   outline-fail  (skips in-progress)                       ▼
   in-progress  ──────────→  in-review  ─────────────────→  done  ──→  (squash-merged into main)
   (developer)               (reviewer)                     (commit on branch)   (post-done; via /<driver> merge)
       ▲                         │
       │   REWORK rounds 1..N    │
       └─────────────────────────┘
       (N=2 default; N=3 if            ▼
        round_cap: 3)                escalated  ───→  refine     ─→ (in-progress if branch exists; pending otherwise)
                                         │           override   ─→ (straight to commit; APPROVE bypassed)
                                         │           decompose  ─→ (this ticket → deferred or abandoned; new tickets created)
                                         │           defer      ─→ (this ticket → deferred; blocker queued)
                                         │           spec-amend ─→ (this ticket → deferred or abandoned; amendment ticket queued)
                                         ▼           abandon    ─→ (this ticket → abandoned; decided against, terminal)
                                      deferred                     abandoned
                                      (reopen once blocker done)   (terminal — not reopenable)
```

Edges:

- `pending → in-progress` — normal `start` path (ticket-readiness pre-flight passes: the linter reports no BLOCKER and the developer self-check raises no blocking question; for `complexity: high` tickets, also requires the plan-writer subagent to return an outline rather than `OUTLINE FAILED`).
- `pending → escalated` — `outline-fail` for `complexity: high` tickets (plan-writer subagent returned `OUTLINE FAILED` after the branch was created); the branch exists but is rolled back if the user resolves with refine-to-pending or abort. (A ticket-readiness lint BLOCKER does NOT escalate — `start` refuses and the ticket stays `pending` until the user fixes the mechanical defect and re-runs `start`.)
- `in-progress → in-review` — `review` step.
- `in-review → in-progress` — REWORK (rounds 1..N).
- `in-review → escalated` — round-cap or `MANUAL` verdict.
- `in-review → done` — `APPROVE` (or `OVERRIDE-APPROVE`) followed by `commit` (which lands the commit on the per-ticket branch). Squash-merge into `main` is a separate post-`done` step (`/<driver> merge`); it does not change `status` because `done` is the only terminal status.
- `escalated → pending` — `refine` resolution when the prior escalation reason was `outline-fail` (branch existed but held no implementation; it is deleted). The user re-runs `/<driver> start` to re-run the pre-flight and re-spawn the plan-writer.
- `escalated → in-progress` — `refine` resolution when the prior escalation reason was `round-cap`, `manual-verdict`, `budget-breach`, `premise-fail`, `loop`, or `redteam-finding` (branch exists; the pre-flight does NOT re-run).
- `escalated → in-review → done` — `override` resolution (APPROVE bypassed; `OVERRIDE-APPROVE` recorded in `reviews:`).
- `escalated → deferred` — `defer`; `decompose` or `spec-amend` when the operand retains residual work and will be reopened after the new ticket(s) land.
- `escalated → abandoned` — `abandon` (decided against outright); or `decompose`/`spec-amend` when the operand is fully replaced/obsoleted by the new ticket(s) and will NOT be reopened.
- `deferred → pending` — `/<driver> reopen` once the blocker is `done`.
- `abandoned` is terminal — the driver's `reopen` refuses it. Reviving an abandoned ticket is a fresh, deliberate decision (draft a new ticket, or re-escalate with explicit justification).

Status values (used in ticket frontmatter):

| Status | Meaning |
|---|---|
| `pending` | Drafted, not yet started. May be `blocked_by` other tickets. |
| `in-progress` | Developer (the main conversation) is actively implementing. Branch exists. |
| `in-review` | Code is committed to the branch, `mvn verify` is green, reviewer subagent is running or has just returned a verdict. |
| `escalated` | Round cap hit, or an immediate-escalation trigger fired. Awaiting user resolution via the escalation menu. |
| `done` | Reviewer returned `APPROVE` (or `OVERRIDE-APPROVE`), ticket commit landed on the per-ticket branch via `/<driver> commit`. Squash-merge into `main` is a separate post-`done` step via `/<driver> merge`; the squash commit on `main` is the merge audit trail and does not require a status change. |
| `deferred` | Work paused **but still intended** — it will be reopened. Blocked on a new ticket the work surfaced, waiting on a spec amendment that will land, or a decomposition whose umbrella retains integration/assembly work. Reopenable via `/<driver> reopen` once the blocker is `done`. Grouped in STATUS.md by `deferred_reason`. |
| `abandoned` | Work **decided against** — will not be implemented as this ticket. Terminal (not reopenable via `reopen`). `abandoned_reason` records why: `decomposed` (fully replaced by shipped children), `superseded` (absorbed by another ticket), `obsoleted-by-spec-amend` (a spec change dropped the requirement), or `wont-do-infeasible` (evaluated and judged not worth building). Grouped in STATUS.md by `abandoned_reason`. |

---

## Ticket frontmatter

Every ticket file under `docs/plan/<milestone>/tickets/` starts with YAML frontmatter. The **complete, authoritative schema lives in [`ticket-template.md`](ticket-template.md)** — that file is the single editing source for field names, defaults, comments, body section order, and example values. This section is a navigation aid: it summarises the load-bearing fields so you can read a ticket without opening the template, and points at the canonical text for the full field list.

If this section disagrees with `ticket-template.md`, the template wins; sync this section.

**Load-bearing fields (the ones that gate workflow behavior):**

| Field | Purpose | Used by |
|---|---|---|
| `id` | Stable ticket identifier (`M<N>-NNN`). Never reused. | every step |
| `status` | Lifecycle state (`pending` → `in-progress` → `in-review` → `done` / `escalated` / `deferred` / `abandoned`). | every step |
| `blocked_by` | List of ticket IDs that must be `done` before this can start. | `next`, `start` preconditions |
| `files_budget` | Numeric file-count hint. **Advisory** as of 2026-07-19 — a count overage is informational, not a SCOPE-DRIFT FAIL; the real scope gates are untraceable-lines, `files_scope` membership, and `out_of_scope`. | reviewer SCOPE-DRIFT-CHECK (advisory note) |
| `files_scope` | Optional path/glob list. Enables negative-space check + parallelism eligibility. | reviewer NEGATIVE-SPACE-CHECK, `start --parallel` |
| `out_of_scope` | Path/feature exclusions the diff MUST NOT touch. | reviewer OUT-OF-SCOPE-CHECK |
| `acceptance` | Runnable / testable criteria, ideally one assertion per item. | reviewer ACCEPTANCE-CHECK, lint PROSE-VERB-IN-VERIFY |
| `complexity` | `low` / `medium` / `high`; `high` triggers the plan-writer subagent at `start`. | `start` |
| `risk` | `low` / `medium` / `high`; `high` triggers the commit-time `mvn verify` re-run. | `commit` |
| `round_cap` | Default 2; may be 3 for `complexity: high` OR `risk: high` tickets. | reviewer round bookkeeping |
| `security_relevant` | When `true`, `/redteam` is recommended after APPROVE. | `commit` reminder |
| `migration_touch` | When `true`, serializes parallel start globally. | `start --parallel` preconditions |
| `spec_refs` / `decision_refs` | Anchors into `docs/spec/` and the decisions log. | lint SPEC-REFS-RESOLVABLE |
| `clarity_check`, `reviews`, `overrides`, `redteam_findings`, `aborted_attempts`, `reopens` | Dynamic — populated by the milestone-driver skill. Authors leave empty. `clarity_check` (now the ticket-readiness pre-flight result: lint verdict + developer self-check) and `reviews` carry only the LATEST entry (no per-round accumulation); `escalations` and `revisions` are not in the schema — git log is the audit trail for refine/escalation history. | the driver skill |
| `escalation_reason` | Scalar; the reason of the CURRENT open escalation, set by `escalate` and cleared on resolution. The two escalate-path readers (override-eligibility gate, refine dispatch) consult it; it survives a session resume where `escalations:`/`revisions:` history would not (M1-662). | `escalate` |
| Lineage (`decomposed_from`, `replaces`, `replaced_by`, `deferred_on`, `deferred_reason`, `abandoned_reason`, `spec_amend_for`, `spec_amend_parent`, `remediates`) | Populated only when applicable (escalation paths, redteam remediation on done tickets). | the driver skill |

For body section order (Context → Definition of Done → Implementation notes → Big-picture notes → Out-of-scope expansion → Authorized test changes → Alternatives considered) and field defaults / comments / example values, read [`ticket-template.md`](ticket-template.md) directly.

---

## The flow (the milestone-driver skill orchestrates this)

The active skill is [`/m1-tick`](../../.claude/skills/m1-tick/SKILL.md) for milestone M1. Future milestones may instantiate their own driver. Steps below describe the procedure; the skill applies it.

### 0. Pick a ticket — `/<driver> next`

The skill reads `docs/plan/<milestone>/tickets/`, finds tickets where `status: pending` AND every entry in `blocked_by` has `status: done`, and prints the runnable list ordered by ID. The user picks one.

### 1. Start — `/<driver> start M<N>-NNN`

- **Ticket-readiness pre-flight.** Two parts, both in the main session — no subagent. (a) The deterministic linter `scripts/lint-ticket.py` checks the mechanical facts: `spec_refs` resolve to real anchors, `out_of_scope` is non-empty and specific, ticket-ID references resolve to files, `files_scope` covers `test_plan` paths, a security surface in `files_scope` matches `security_relevant`, a class-scoped ticket carries a §Census, acceptance items avoid unrunnable prose verbs. A BLOCKER refuses the start (the user fixes the ticket and re-runs). (b) The developer self-check (the main session applies its own judgment against the ticket AND the code it names): every acceptance item implementable without guessing, no ticket claim about existing code is false, the census grep re-runs clean. A genuine ambiguity → one `AskUserQuestion` (a question, not an escalation). Result recorded under `clarity_check:` in frontmatter. Full procedure in [`start.md`](../../.claude/skills/m1-tick/subcommands/start.md) step 1.
- Set frontmatter `status: in-progress`. Update `last_updated`.
- Create branch `m<N>/M<N>-NNN-<slug>` off `main`.
- If `complexity: high`, spawn `Agent(subagent_type: "plan-writer")` with the ticket body and require an implementation outline before any code is written. (`plan-writer` is defined at `.claude/agents/plan-writer.md`; the built-in `Plan` subagent type is read-only and cannot Write the outline sidecar, so the procedure uses the custom agent.)
- The main conversation IS the developer from this point. No developer-subagent.
- Regenerate `STATUS.md`.

### 2. Implement

- If `files_scope` is set, every touched file must match a glob in that list; stay outside `out_of_scope`. A path departure from either is a genuine scope drift → escalate before making it. `files_budget` is an advisory file-count hint (2026-07-19 cutover) — exceeding it is not a hard failure, so it does not by itself require pre-escalation; keep the diff surgical regardless.
- Match existing style. No adjacent improvements (CLAUDE.md §Surgical changes).
- If a better alternative surfaces → record under `Alternatives considered:` in the eventual commit message; complete the ticket as written.

### 3. Test — `mvn verify` from repo root

- Run the **full** suite, not just the new tests.
- If anything fails:
  - Fix the code. Never the test, unless the test itself was the change requested by the ticket.
  - Two consecutive failures with the same root cause → escalate (loop indicator).
  - Failure mode that suggests the ticket's premise is wrong (e.g., a spec invariant breaks no matter what) → escalate.

### 4. Review — `/<driver> review M<N>-NNN`

- Skill spawns `Agent(subagent_type: "code-reviewer")` with the prompt from [`reviewer-prompt.md`](reviewer-prompt.md).
- Reviewer receives:
  - The ticket file.
  - The diff (`git diff $(git merge-base main HEAD)`, working-tree-vs-fork-point; the implementation lives in the working tree at review time because `commit` runs *after* `review`, so a commit-range diff against `main` would be empty. The driver runs `git add -N` on any untracked files first so newly created files appear in the diff. The fork point, never `main` itself: in a worktree pinned behind a moved `main`, diffing against the `main` ref drags every since-landed ticket into the review as phantom changes; the merge-base is the branch's fork point and is identical to `main` whenever `main` has not moved. Observed: M1-096, 2026-05-30).
  - The list of files in `files_scope` that were NOT touched (the "negative space"), so the reviewer can judge whether the un-touched files were a deliberate skip or a forgotten part of the scope. When `files_scope` is empty, the negative-space report is empty and `NEGATIVE-SPACE-CHECK` reports PASS by definition.
  - The test output.
  - On rounds ≥ 2: the previous-round diff stats (files touched, lines added, lines removed) for the must-shrink check.
  - The verbatim engineering rules and test-integrity rules embedded inline (the reviewer subagent has no other context).
- Reviewer returns the structured verdict (see "Reviewer verdict format" below).
- Set frontmatter `status: in-review`.

### 5. Resolve

| Verdict | Action |
|---|---|
| `APPROVE` | Proceed to commit (step 6). |
| `REWORK` (round 1) | Address only the named items. Do not re-architect. Re-run `mvn verify`. Re-invoke reviewer. |
| `REWORK` (round 2) | If the ticket has `round_cap: 2` (default): escalate (no round 3). If the ticket has `round_cap: 3`: address only the named items, re-run `mvn verify`, re-invoke reviewer for round 3. (Must-shrink is advisory as of 2026-07-19 — a non-convergent round surfaces as a reviewer WARN, not a FAIL that escalates.) |
| `REWORK` (round 3) | Only reachable when `round_cap: 3`. Escalate; no round 4 exists. |
| `MANUAL` | Escalate immediately. The reviewer's uncertainty is not for the developer to resolve. |

**Round-N must-shrink (N ≥ 2) — advisory (2026-07-19 cutover).** Every rework round is a fix-only round. The reviewer compares round-N diff stats to round-(N−1) along files-touched, net lines added, and net lines removed. Growth along **all three** dimensions simultaneously with no citable mandate surfaces as a reviewer **WARN** on SCOPE-DRIFT-CHECK — it does NOT force REWORK or escalate, because the round cap already bounds non-convergent rework and a second hard gate here was redundant friction. Holding equal or shrinking along any dimension is convergent and silent. The canonical rule is in [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md) §8 "Round-N must-shrink".

### 6. Commit — `/<driver> commit M<N>-NNN`

- **Safety re-run before committing.**
  - For tickets with `complexity: high` OR `risk: high`: the commit subcommand re-runs `mvn verify` from the repo root. The commit only proceeds on success. This closes the "skipped tests, faked review" gap for the tickets where the cost is highest.
  - For all other tickets: the commit subcommand checks that the most recent test log (`target/<driver>-test-{ID}-r*.log`) has an mtime newer than the latest mtime among the staged files. If the log is older than any staged file, refuse and require a fresh `mvn verify`.
- One commit on the per-ticket branch.
- Subject: `M<N>-NNN: <imperative summary>` (≤ 72 chars).
- Body: the Context paragraph from the ticket + any `Alternatives considered:` trailer.
- `Reviewed-by:` trailer carrying the reviewer's `APPROVE` (or `OVERRIDE-APPROVE`) verdict line, the round number, and the reviewer agent's run identifier (or `NA` if the harness did not surface one). Exact format under "Commit conventions" below.
- Set frontmatter `status: done`. Update `last_updated`.
- The commit lives on the per-ticket branch only; `main` is unchanged until step 7. The driver prints a pointer at `/<driver> merge M<N>-NNN` as the next step.

### 7. Merge — `/<driver> merge M<N>-NNN`

Squash-merge the per-ticket branch into `main` so `main` history stays one-commit-per-ticket. Idempotent: re-running on an already-merged ticket cleans up a stale branch instead of double-merging.

- Preconditions: ticket `status: done`; working tree clean; the per-ticket branch is resolvable per the **branch resolution procedure** in §"Naming conventions (slug, branch, ticket file)" OR the canonical implementation-commit (subject `M<N>-NNN: <ticket-title>` exactly) already exists on `main` (the idempotent-cleanup arm). Otherwise refuse with a diagnostic message — the driver does not silently paper over inconsistent state.
- Idempotency precheck. The driver reads the ticket file's `title` field (stripping surrounding YAML quotes if present — YAML requires `"..."` or `'...'` quoting when the title contains a colon, hash, or other reserved character; the quotes are not part of the title), constructs the canonical implementation-commit subject `M<N>-NNN: <title>`, and counts commits on `main` whose subject EQUALS this string (fixed-string whole-line match: `git log main --format=%s | grep -cFx "M<N>-NNN: <title>"`). Auxiliary commits the workflow naturally produces with the same `M<N>-NNN: ` prefix (`M<N>-NNN: draft ticket`, `M<N>-NNN: refine ticket spec ...`, `M<N>-NNN: aborted attempt #N`, etc.) have distinct summaries by convention and do NOT collide with the canonical subject. Arms:
  - `0` matches AND branch resolves → squash-merge path below.
  - `0` matches AND branch missing → refuse: ticket says `done` but no committed work is locatable; state is inconsistent.
  - `1` match → ticket already on `main`; delete the stale branch if it still resolves, otherwise no-op. Success exit.
  - `≥2` matches → refuse: duplicate canonical ticket commits on `main` indicate a prior partial merge or hand-amend the driver will not silently fix.
- Squash-merge path: `git checkout main` → `git merge --squash <branch>` → `git commit -C <branch-tip>` (reuses the branch tip's commit message verbatim, preserving the `Reviewed-by:` trailer and any `Alternatives considered:` block) → `git branch -D <branch>`. The squash hides the branch's intermediate refine commits so `main`'s history stays one ticket = one commit.
- Status is NOT mutated — `done` is the only terminal status. The squash commit on `main` IS the merge audit trail; `git log main --grep -F "M<N>-NNN: <title>"` (with `<title>` filled in from the ticket frontmatter) answers "is this merged?" in one command.
- The driver never pushes. Push remains the user's call.
- Conflicts at the `git merge --squash` step indicate `main` advanced between commit and merge (e.g. another ticket landed in between). The driver branches on the conflict set:
  - **Conflict set is exactly the regenerated status board** (`docs/plan/<milestone>/STATUS.md` in the M1 driver, `M2` driver, etc.): the board is a deterministic regen from the union of ticket frontmatter, so a divergent regen between branch tip and moved `main` is a pseudo-conflict whose post-merge union is well-defined. The driver auto-resolves by re-running its status regen script against the post-merge tree, staging the regenerated board, and continuing the merge.
  - **Conflict set is anything else** (substantive code, design, or ticket-body conflict, or a multi-path set that happens to include the board alongside a substantive file): the driver refuses; the user rebases the per-ticket branch onto fresh `main` and re-runs `/<driver> merge`.

### 8. Escalate — `/<driver> escalate M<N>-NNN`

The skill prints the escalation menu in chat:

```
M<N>-NNN: <title>  —  ESCALATED
Trigger: <reason: round-cap | manual-verdict | budget-breach | premise-fail | loop | redteam-finding>

Reviewer's last verdict:
  <verbatim block>

Choose:
  1. refine     — acceptance criteria were ambiguous; rewrite the ticket
  2. override   — reviewer was too strict; record the override and approve
  3. decompose  — split into N tickets; defer this one and queue replacements
  4. defer      — block on a new ticket the work surfaced; pause this one
  5. spec-amend — the spec itself is wrong; raise an amendment ticket and pause

Reply with: <number> [optional notes]
```

- `refine` → user edits the ticket; status returns to `in-progress`. The commit message records the refine reason (`M<N>-NNN: refine ticket spec (<reason>-rework)`); git log is the audit trail. No YAML accumulation.
- `override` → reviewer's specific objections are recorded under `overrides:` with a one-line user justification. Status returns to `in-review` and the skill proceeds to commit.
- `decompose` → driver allocates fresh IDs (`M<N>-AAA`, `M<N>-BBB`, ...) via the ID allocation algorithm; user provides only titles. Replacement skeletons created in `docs/plan/<milestone>/tickets/M<N>-AAA-<slug>.md` (etc.) with `decomposed_from: M<N>-NNN` (the operand) populated on each child. The operand's terminal state depends on whether it retains residual work: if the children **fully replace** it (no umbrella integration/assembly left), operand → `status: abandoned` with `abandoned_reason: decomposed`; if the operand retains integration work to run after the children ship (the umbrella pattern), operand → `status: deferred` with `deferred_reason: decomposed` and is reopened later. The lineage is queryable either way so a stale child doesn't get lost.
- `defer` → user names the blocking ticket ID (or asks the skill to draft it). Original → `status: deferred` with `deferred_on:` and `deferred_reason: blocked-on-new-ticket`. Blocker → new pending ticket.
- `spec-amend` → the spec itself is wrong. Driver allocates a fresh ID (`M<N>-AAA`) for the amendment ticket, whose acceptance criteria amend the spec section, with `spec_amend_for: <spec-path-and-section>` and `spec_amend_parent: M<N>-NNN` (the operand). If the operand will be reopened after the amendment lands, operand → `status: deferred` with `deferred_reason: spec-amend` and `deferred_on: M<N>-AAA`; if the amendment **obsoletes** the operand (drops its requirement), operand → `status: abandoned` with `abandoned_reason: obsoleted-by-spec-amend`. Use this instead of `decompose` whenever the issue is "the spec said X but should say Y", not "the implementation needs to be split into N pieces."
- `abandon` → the ticket is decided against outright — the work will not be built and there is no split or amendment that carries it. Operand → `status: abandoned` with `abandoned_reason: superseded` (absorbed by another named ticket) or `wont-do-infeasible` (evaluated, not worth building). Terminal; the driver's `reopen` refuses it.

---

## Reviewer verdict format

The reviewer subagent returns a single structured message: a top-level `VERDICT` line (`APPROVE` / `REWORK` / `MANUAL`) followed by per-check results (`SCOPE-DRIFT-CHECK`, `TEST-INTEGRITY-CHECK`, `OUT-OF-SCOPE-CHECK`, `NEGATIVE-SPACE-CHECK`, `ACCEPTANCE-CHECK`) and, on REWORK, a list of specific addressable `REWORK ITEMS`.

The **canonical, complete output specification — including the exact paragraph-level instructions for each check and the verdict-rules block — lives in [`reviewer-prompt.md`](reviewer-prompt.md)**, which is what the reviewer subagent actually sees verbatim. That file is the single editing source; this section is a navigation aid.

If this section disagrees with `reviewer-prompt.md`, the prompt template wins; sync this section.

**Verdict-rules summary** (full text in `reviewer-prompt.md` §"Verdict rules"):

- Any `*-CHECK: FAIL` forces `VERDICT` to be at least `REWORK`. `APPROVE` requires every check to be `PASS` — `NEGATIVE-SPACE-CHECK: WARN` is permitted under `APPROVE` and surfaces as informational.
- `ACCEPTANCE-CHECK: PARTIAL` is `REWORK` unless the **ticket body itself** explicitly names a deferred dependency for the missing item (a citation visible in the ticket in front of the reviewer), in which case `MANUAL`. The reviewer does not crawl the ticket graph; if the citation is not in the ticket body, the missing item is REWORK.
- `TEST-INTEGRITY-CHECK: FAIL` with developer rationale "this is fine because…" is `MANUAL`, not `REWORK` — test integrity is not developer-overridable. Override is the user's call only.
- `MANUAL` is for genuine reviewer uncertainty (ambiguous spec, conflicting rules, no clear path). Loop indicators are `REWORK`, not `MANUAL`.
- `REWORK ITEMS` must be specific and addressable in the existing diff. "Refactor X for clarity" is too vague; "rename `Foo.bar()` to `Foo.baz()` to match `docs/spec/X.md` §Y" is fine.

The `OVERRIDE-APPROVE` verdict is distinct from `APPROVE`: it is written by the milestone-driver skill on the override escalation path (not by the reviewer) and preserves the original FAIL/WARN check results in the audit trail. The commit step accepts both `APPROVE` and `OVERRIDE-APPROVE`.

---

## Test-integrity rules (no shortcuts; the reviewer enforces these)

The full forbidden-pattern list — syntactic, semantic, test-modification authorization, stack-specific (Postgres+pgvector), and round-N must-shrink — lives in [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md) §8. That file is the editing source; the reviewer prompt embeds its text inline so the fresh-context subagent sees it without external reads.

A `FAIL` on `TEST-INTEGRITY-CHECK` is never `REWORK`-able by the developer alone. The reviewer escalates to `MANUAL` if the developer's stated rationale is "this is fine because ...". The user is the only one who can override test-integrity violations.

---

## Commit conventions

```
M<N>-NNN: <imperative summary, ≤ 72 chars>

<Context paragraph from the ticket: why this ticket exists, what it
unlocks. Wraps at 72 chars.>

<If alternatives were considered:>
Alternatives considered:
  - <alt 1>: <one-line reason rejected>
  - <alt 2>: ...

Reviewed-by: code-reviewer (VERDICT: <APPROVE|OVERRIDE-APPROVE>; round <r>; agent run: <id-or-NA>)
```

- `git push` is the user's decision, not the agent's. The skill stops at the local commit.
- `git revert <commit>` cleanly undoes a ticket because there is one commit per ticket on `main`.
- `git bisect` becomes ticket-bisection for the same reason.
- Never `--amend` a passed commit. Defects → new ticket → new commit.

---

## Non-ticket commits (spec / process)

The ticket flow exists for code, tests, migrations, and spec changes coordinated with code. The reviewer's checks (surgical changes, scope drift, test integrity, files-budget, negative space) all bite on production code; for a pure-documentation edit they reduce to ceremony. Pure-doc edits **do not need a ticket** — they commit directly on `main` with a non-ticket subject prefix.

### Prefixes

| Prefix | Scope | Skipped vs ticket flow |
|---|---|---|
| `M<N>-NNN:` | Implementation ticket: code, tests, migrations, or spec changes coordinated with code | (full flow) |
| `spec:` | Pure spec/design edit under `docs/spec/` or `docs/design/`, no code change | ticket-readiness pre-flight, reviewer, `mvn verify`, STATUS regen |
| `process:` | Edit under `.claude/`, `docs/process/`, `docs/plan/`, `CLAUDE.md`, or the agent-tooling surface (`AGENTS.md`, `.agents/`, `.opencode/`, `.codex/`, `CONTRIBUTING.md`), no code change | ticket-readiness pre-flight, reviewer, `mvn verify`, STATUS regen |

### Rules

1. **Touch code → ticket.** Any commit that adds, deletes, or modifies a file under `infochat-*/`, a module's `src/`, a `pom.xml`, or `db/migration/` is a ticket and uses `M<N>-NNN:`. The `spec:` and `process:` prefixes are pure-doc only.
2. **Touch spec coordinated with code → ticket.** If a spec amendment is *paired* with the code change it justifies, both land in the same `M<N>-NNN:` commit. The `spec:` prefix is for amendments that stand alone — clarifications, decision-log entries, formatting fixes, refinements with no code consequence yet.
3. **Dominant-path prefix.** If a `process:` commit incidentally fixes a typo in a spec file, it stays `process:` — pick the prefix that names the load-bearing change. Co-prefixing (`spec+process:`) is forbidden; if the change is genuinely split across both surfaces, make two commits.
4. **Grep safety.** `git log --grep "^M<N>-"` continues to enumerate implementation-ticket work cleanly because no non-ticket prefix starts with `M`. Tools that build the Done table from `git log` (the status regenerator or its replacement) keep working unchanged.
5. **Human review present.** The user is the reviewer for `spec:` and `process:` commits. Skipping the reviewer subagent is not a relaxation of the surgical-changes principle — it reflects that the reviewer's automated checks have nothing to bite on for pure-doc edits. Stay surgical.
6. **STATUS unchanged.** Non-ticket commits do not touch ticket frontmatter, so `STATUS.md` is unaffected and `/<driver> status` is not invoked.
7. **Revert semantics preserved.** `git revert <commit>` undoes a non-ticket commit cleanly; the one-prefix-per-commit convention keeps history searchable.

### When in doubt

Default to the ticket flow. The cost of an unnecessary ticket is lower than the cost of an unreviewed code change slipping through under a `process:` prefix. The `spec:` and `process:` prefixes are for changes that would feel silly going through the ticket-readiness pre-flight and reviewer — a typo fix in a skill prompt; a sentence-level spec refinement; a rule-rewording in `engineering-rules-verbatim.md`; replacing an LLM subagent with a deterministic script.

---

## Parallelism

Default: sequential. One `in-progress` ticket at a time.

Parallel allowed when:
- Two `pending` tickets have empty intersection on `files_scope` (no shared paths). Tickets without `files_scope` (purely numeric budgets) cannot be parallelized — the skill cannot mechanically prove disjointness without a path list.
- Two `pending` tickets have empty intersection on `out_of_scope` exclusions.
- Neither has `migration_touch: true` AND no in-flight ticket has `migration_touch: true` (migrations serialize globally; the flag makes the rule mechanically checkable).
- The user explicitly opts in via `/<driver> start <id> --parallel`.

If parallel, each ticket runs in a git worktree (`Agent(isolation: "worktree")`). The skill refuses to start a parallel ticket whose constraints overlap an in-flight ticket; the conflict is surfaced via `STATUS.md`.

### Migration ordering (below-max interaction audit)

Migration version numbers are reserved at ticket-draft time, but tickets land in any order, and a migration added to an in-flight ticket mid-implementation (e.g. a redteam remediation) takes the next free number — above reserved-but-unlanded ones — and may land first. The result is a migration whose version is **below** one already on `main`: on every fresh database it executes *earlier* than a higher-numbered migration that was authored and tested before it existed.

Any migration whose version is below a version already on `main`, or below any `V*.sql` present in a sibling worktree, MUST carry in its header comment an interaction audit against each such higher-numbered migration:

1. **Objects both re-declare.** A `CREATE OR REPLACE` body must be copied from the highest-numbered declaration on disk (any checkout), never an older lineage — the higher-numbered migration runs later and silently clobbers a lower-numbered re-declaration.
2. **Structure the higher one depends on.** Tables, columns, or grants this migration changes that the higher-numbered migration's DDL, DML, or function bodies reference. plpgsql bodies are not resolved against the catalog at `CREATE` time, so a broken reference applies cleanly and fails only when the function is first called.
3. **Data backfills crossing the boundary.** Any row-copying in either migration that assumes the other's pre-change structure.

The reviewer rejects a diff that adds a below-max migration without this audit. Precedent: V39's header reasons explicitly about ACL preservation across the already-landed V41 re-declaration (the 2026-06-07 V39/V40/V41 inversion — both audits held; the rule makes them mandatory rather than voluntary).

---

## Naming conventions (slug, branch, ticket file)

The same slug derivation is used in three places: the per-ticket branch name, the ticket file name on disk, and any tooling that needs to refer to a ticket by branch (notably `/redteam <id> --in-progress`). Define it once here so every milestone-driver skill and any auxiliary skill can produce the same string from the same ticket.

**Slug computation rule (canonical):**

1. Take the ticket's `title` field.
2. Lowercase it.
3. Drop every character that is not ASCII `[a-z0-9 -]` (Unicode, punctuation, accents, smart quotes — all stripped).
4. Collapse runs of whitespace to a single space; trim leading/trailing whitespace.
5. Replace each remaining space with a hyphen.
6. Collapse runs of consecutive hyphens to a single hyphen; trim leading/trailing hyphens.
7. Truncate to 30 characters maximum, then trim a trailing hyphen if the truncation produced one.
8. If the result is the empty string after all of the above (e.g. the title was non-ASCII), use the literal string `untitled`.

**Derived names:**

- Per-ticket branch: `m<N>/M<N>-NNN-<slug>` — e.g. `m1/M1-007-rss-fetcher-spi`.
- Ticket file path: `docs/plan/m<N>/tickets/M<N>-NNN-<slug>.md`.
- Test-log path: `target/<driver>-test-M<N>-NNN-r<round>.log` (no slug; the ID alone is enough).

The slug is computed from the title at `start`. Subsequent steps (`/<driver> review`, `/<driver> commit`, `/<driver> abort`, `/redteam <id> --in-progress`) need to find the same branch later — but because titles can be edited via `refine`, the slug derived from the *current* title may diverge from the slug embedded in the existing branch name.

**Branch resolution procedure (canonical; used by every consumer of the slug).**

1. Compute the slug from the ticket's *current* title using the rule above; this gives the *expected* branch name `m<N>/M<N>-NNN-<slug>`.
2. If the expected branch exists (`git rev-parse --verify --quiet refs/heads/m<N>/M<N>-NNN-<slug>`), use it.
3. Otherwise glob `m<N>/M<N>-NNN-*` and select the unique match. Use that branch even though its trailing slug differs from the current title; the title was edited via `refine` after the branch was created, and `M<N>-NNN` remains the stable identifier.
4. If the glob returns zero matches, refuse with: `<consumer>: branch m<N>/M<N>-NNN-* does not exist on this checkout. Either the ticket has not been started, or the branch was deleted. Run /<driver> start M<N>-NNN to begin.`
5. If the glob returns multiple matches, refuse with: `<consumer>: branch m<N>/M<N>-NNN-* matched multiple branches: <list>. Resolve by deleting stale branches before retrying — the no-amend / one-branch-per-ticket invariant has been violated.`

The procedure is identical for every consumer; the consumer's name (e.g. `m1-tick review`, `redteam --in-progress`) appears in the refusal message but the algorithm is the same.

The slug is NOT used as a stable identifier — `M<N>-NNN` is. The slug is only a human-readable affordance attached to branch and file names so `git branch -a` and directory listings are scannable.

---

## Spec-anchor resolution (canonical)

A `spec_refs:` entry has the form `<file-path> §<section-title>`. Several consumers resolve the `§<section-title>` to a concrete heading in the file: `scripts/lint-ticket.py` (SPEC-REFS-RESOLVABLE, the authoritative implementation), the reviewer subagent (SPEC-CONFORMANCE-CHECK reads the cited section by anchor range), and the plan-writer subagent. Define the algorithm once here so every consumer produces the same resolution. (Before the 2026-07-19 cutover this text lived in `clarity-prompt.md`; that file is gone and this section is its canonical replacement.)

For each entry:

1. Read `<file-path>`.
2. Walk the file line-by-line maintaining a `fence_open` flag (initially false). For each line, in order:
   a. If the line is a CommonMark fenced code-block delimiter — 0–3 spaces of leading indent, then a run of three or more backtick (U+0060) or three or more tilde (U+007E) characters, optionally followed by an info string — toggle `fence_open` and continue to the next line. Fence delimiter lines are themselves never headings.
   b. If `fence_open` is true after step (a), skip the line. Anything inside a fenced code block is content, not document structure.
   c. If the line matches `^[ ]{0,3}#{1,6}[ \t]+\S` (a CommonMark ATX heading), record it as a candidate heading with its line number and the count of `#` markers as its depth. Otherwise skip.
3. For each candidate, derive the heading text by stripping the leading whitespace, the `#`-marker run, the whitespace between the markers and the title, and any trailing whitespace or trailing `#`-run.
4. Lowercase both the candidate heading text and the searched section-title; do a substring match.
5. If exactly one heading matches → `FOUND (line N: "<heading>")`.
6. If zero match → `ANCHOR-NOT-FOUND`.
7. If multiple match → prefer the heading whose depth is closest to the most recently resolved anchor's depth; tie-break by line number ascending. If still tied → `AMBIGUOUS (lines: N, M, ...)`.

The linter treats `ANCHOR-NOT-FOUND` as a BLOCKER and `AMBIGUOUS` as a WARN. The reviewer, when reading a cited section for SPEC-CONFORMANCE-CHECK, reads from the resolved line until the next heading at the same-or-higher depth; on `ANCHOR-NOT-FOUND` / `AMBIGUOUS` it falls back to a whole-file read and raises SPEC-CONFORMANCE-CHECK to WARN with a note.

---

## Red-team (threat-actor) review

Adversarial security review running in fresh context. The procedure is its own skill — see [`.claude/skills/redteam/SKILL.md`](../../.claude/skills/redteam/SKILL.md). Prompt template lives in [`redteam-prompt.md`](redteam-prompt.md). Invoked via `/redteam <ticket-id | milestone <name> | id-range <a..b> | release <tag>>`.

**When it runs:**
- At a milestone boundary, when all tickets in a milestone phase reach `done`.
- On any single ticket flagged `security_relevant: true`, after that ticket's normal review passes APPROVE.
- Before tagging a release.

**What the subagent sees:**
- `docs/spec/security.md` (the threat model — what the system promises to defend against).
- The cumulative diff across the tickets being red-teamed (`git diff <base>...<head>`).
- The list of authentication, authorization, input-validation, and ban-handling code paths touched.

**What it does NOT see:** the implementation rationale, the design notes (`docs/design/**`), the ticket bodies. The adversary is looking for the gap between spec promise and shipped delivery; reading the rationale would anchor it on the implementer's mental model.

**Verdict format:**

```
RED-TEAM VERDICT: <CLEAN | FINDINGS>

FINDINGS: (omit on CLEAN; one entry per finding)
  - CATEGORY: <AUTH-BYPASS | INFO-LEAK | INJECTION | DOS | PERM-ESCAL | AUDIT-EVASION>
    SEVERITY: <critical | high | medium | low>
    PROMISE: <what the spec says the system defends against>
    GAP: <how the diff fails to deliver the promise>
    REPRO: <concrete attack sequence the adversary would run>
    SUGGESTED-FIX-CLASS: <one of: input-sanitization | trust-boundary-tightening |
                          missing-auth-check | rate-limit | audit-log-coverage | other>

OUT-OF-MODEL: (optional)
  - <attacks that look juicy but fall outside the documented threat model;
    flag them so the user can decide whether to extend the model>
```

**What happens with findings:** Findings are NOT auto-converted to REWORK. The path depends on the affected ticket's status:

- For `in-progress` or `in-review` tickets — the user opens the standard escalation menu (trigger reason: `redteam-finding`) on that ticket; `redteam_findings:` is populated on it. The user can choose `refine` to widen acceptance, `decompose`, `defer`, `spec-amend`, or `abandon`.
- For `done` tickets — the original commit is **never amended** (per `.claude/skills/m1-tick/SKILL.md` §M1 workflow rules "never amend a passed commit"). Instead, the user creates a **new remediation ticket** with `remediates: M<N>-XXX` set on the new ticket pointing back at the done ticket. The new ticket carries the fix; the done ticket's `redteam_findings:` is populated for traceability but its commit stays untouched. This preserves the one-commit-per-ticket invariant of `main`.
- For findings that span multiple tickets or describe an architectural gap with no clear owner, the user files a fresh ticket (no `remediates:`) or raises a spec amendment via `spec-amend` on a related ticket.

The `/redteam` skill itself never opens escalations or creates tickets; it prints recommendations and writes to `redteam_findings:` only.

---

## What is not in this workflow

- **No automated push.** The user reviews and pushes.
- **No automated PR creation.** Per-ticket branches are local; the user opens PRs at their cadence (one PR per ticket is the natural shape).
- **No CI definition yet.** The reviewer + `mvn verify` IS the gate. CI mirrors of the same gate land in a later milestone.
- **No metrics.** "How long did this ticket take?" is git-log-derivable; no separate tracker.
- **No external trackers.** GitHub Issues is fine for *user-facing* bug reports later; ticket-as-code is the dev-facing source of truth.
- **No automated red-team on every ticket.** Cost-bounded by milestone-end + flagged-ticket triggers.
