# Ticket-driven workflow specification

This document is the universal workflow specification — the lifecycle, ticket frontmatter, reviewer behavior, escalation, and commit conventions that apply to ticket-driven work across milestones. It is the single source of truth for the procedure.

The verbatim engineering rules and test-integrity rules the reviewer enforces live in [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md). That file is the editing source; this document references it rather than duplicating the rule prose. The reviewer prompt embeds it inline because the reviewer subagent runs in fresh context.

The always-loaded summary lives in `CLAUDE.md` §M1 workflow. Per-milestone framing (what's in scope this milestone, deltas from this universal workflow) lives in `docs/plan/<milestone>/README.md` (e.g. `docs/plan/m1/README.md`).

Precedence on conflict: `CLAUDE.md` summary < this document < [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md) (the rules themselves are canonical). If anything here contradicts `CLAUDE.md`, sync `CLAUDE.md`. If anything here contradicts `engineering-rules-verbatim.md`, this file is wrong.

> **Milestone tokens used below.** Examples use `M<N>` (e.g. `M1`, `M2`) for ticket-ID prefixes and `m<N>` (e.g. `m1`, `m2`) for branch and directory tokens. The currently active milestone is M1, driven by the `/m1-tick` skill. Future milestones may instantiate their own driver skill or extend the existing one.

> **Ticket-ID placeholder convention.** Where examples and skill text reference ticket IDs, they use these placeholders to avoid confusing the *operand* of an invocation with *other* tickets touched by it:
>
> - `M<N>-NNN` — **operand**: the ticket the user passed to the subcommand (e.g. `/m1-tick start M1-007` makes `M<N>-NNN = M1-007` everywhere in that invocation).
> - `M<N>-AAA`, `M<N>-BBB`, `M<N>-CCC` — **newly created** by this invocation (decompose children, spec-amend amendment ticket, drafted blocker on defer).
> - `M<N>-XXX`, `M<N>-YYY`, `M<N>-ZZZ` — **referenced** by the operand or by frontmatter (existing `blocked_by` entries, `decomposed_from`, `deferred_on`, `spec_amend_parent` from the perspective of an unrelated ticket).
>
> When you see `M<N>-NNN` in skill steps, it is always the operand. When you see `M<N>-AAA` and friends, those are fresh IDs the skill allocates per the [ID allocation algorithm](../../.claude/skills/m1-tick/SKILL.md#id-allocation-algorithm). When you see `M<N>-XXX`, the ticket is being referenced by ID without being either the operand or newly created.

---

## Lifecycle

```
   pending  ─────────────────────────────────────────────────────────┐
      │  ▲                                                           │
      │  │ refine after clarity-fail / outline-fail                  │
      │  │ (no branch ever existed; status returns to pending,       │
      │  │  user re-runs /<driver> start to re-trigger clarity)      │
      │  │                                                           │
      ▼  │   clarity-fail / outline-fail  (skips in-progress)        ▼
   in-progress  ──────────→  in-review  ─────────────────→  done
   (developer)               (reviewer)                     (squash-merge into main)
       ▲                         │
       │   REWORK rounds 1..N    │
       └─────────────────────────┘
       (N=2 default; N=3 if            ▼
        round_cap: 3)                escalated  ───→  refine     ─→ (in-progress if branch exists; pending otherwise)
                                         │           override   ─→ (straight to commit; APPROVE bypassed)
                                         │           decompose  ─→ (this ticket → deferred; new tickets created)
                                         │           defer      ─→ (this ticket → deferred; blocker queued)
                                         ▼           spec-amend ─→ (this ticket → deferred; amendment ticket queued)
                                      deferred
                                      (resume via /<driver> reopen once blocker is done)
```

Edges:

- `pending → in-progress` — normal `start` path (clarity PASS or WARN; for `complexity: high` tickets, also requires the Plan subagent to return an outline rather than `OUTLINE FAILED`).
- `pending → escalated` — `start`-blocked-by-clarity-FAIL OR `outline-fail` for `complexity: high` tickets (Plan subagent returned `OUTLINE FAILED` after the branch was created). The status never passes through `in-progress` for the clarity-fail arm; for the outline-fail arm the branch exists but is rolled back if the user resolves with refine-to-pending or abort.
- `in-progress → in-review` — `review` step.
- `in-review → in-progress` — REWORK (rounds 1..N).
- `in-review → escalated` — round-cap or `MANUAL` verdict.
- `in-review → done` — `APPROVE` (or `OVERRIDE-APPROVE`) followed by `commit` and squash-merge.
- `escalated → pending` — `refine` resolution when the prior escalation reason was `clarity-fail` or `outline-fail` (no branch). The user re-runs `/<driver> start` to re-trigger clarity.
- `escalated → in-progress` — `refine` resolution when the prior escalation reason was `round-cap`, `manual-verdict`, `budget-breach`, `premise-fail`, `loop`, or `redteam-finding` (branch exists; clarity does NOT re-run).
- `escalated → in-review → done` — `override` resolution (APPROVE bypassed; `OVERRIDE-APPROVE` recorded in `reviews:`).
- `escalated → deferred` — `decompose`, `defer`, or `spec-amend`.
- `deferred → pending` — `/<driver> reopen` once the blocker is `done`.

Status values (used in ticket frontmatter):

| Status | Meaning |
|---|---|
| `pending` | Drafted, not yet started. May be `blocked_by` other tickets. |
| `in-progress` | Developer (the main conversation) is actively implementing. Branch exists. |
| `in-review` | Code is committed to the branch, `mvn verify` is green, reviewer subagent is running or has just returned a verdict. |
| `escalated` | Round cap hit, or an immediate-escalation trigger fired. Awaiting user resolution via the five-way menu. |
| `done` | Reviewer returned `APPROVE`, ticket commit landed on `main` via squash-merge. |
| `deferred` | Work paused. Either intentionally postponed (out-of-milestone scope discovered), blocked on a new ticket the work surfaced, or waiting on a spec amendment. |

---

## Ticket frontmatter

Every ticket file under `docs/plan/<milestone>/tickets/` starts with YAML frontmatter. The **complete, authoritative schema lives in [`ticket-template.md`](ticket-template.md)** — that file is the single editing source for field names, defaults, comments, body section order, and example values. This section is a navigation aid: it summarises the load-bearing fields so you can read a ticket without opening the template, and points at the canonical text for the full field list.

If this section disagrees with `ticket-template.md`, the template wins; sync this section.

**Load-bearing fields (the ones that gate workflow behavior):**

| Field | Purpose | Used by |
|---|---|---|
| `id` | Stable ticket identifier (`M<N>-NNN`). Never reused. | every step |
| `status` | Lifecycle state (`pending` → `in-progress` → `in-review` → `done` / `escalated` / `deferred`). | every step |
| `blocked_by` | List of ticket IDs that must be `done` before this can start. | `next`, `start` preconditions |
| `files_budget` | Numeric upper bound on file count touched by the diff (always enforced). | reviewer SCOPE-DRIFT-CHECK |
| `files_scope` | Optional path/glob list. Enables negative-space check + parallelism eligibility. | reviewer NEGATIVE-SPACE-CHECK, `start --parallel` |
| `out_of_scope` | Path/feature exclusions the diff MUST NOT touch. | reviewer OUT-OF-SCOPE-CHECK |
| `acceptance` | Runnable / testable criteria, ideally one assertion per item. | reviewer ACCEPTANCE-CHECK, clarity pre-flight |
| `complexity` | `low` / `medium` / `high`; `high` triggers the Plan subagent at `start`. | `start` |
| `risk` | `low` / `medium` / `high`; `high` triggers the commit-time `mvn verify` re-run. | `commit` |
| `round_cap` | Default 2; may be 3 for `complexity: high` OR `risk: high` tickets. | reviewer round bookkeeping |
| `security_relevant` | When `true`, `/redteam` is recommended after APPROVE. | `commit` reminder |
| `migration_touch` | When `true`, serializes parallel start globally. | `start --parallel` preconditions |
| `spec_refs` / `decision_refs` | Anchors into `docs/spec/` and the decisions log. | clarity pre-flight |
| `clarity_check`, `reviews`, `escalations`, `revisions`, `overrides`, `redteam_findings`, `aborted_attempts`, `reopens` | Dynamic — populated by the milestone-driver skill. Authors leave empty. | the driver skill |
| Lineage (`decomposed_from`, `replaces`, `replaced_by`, `deferred_on`, `deferred_reason`, `spec_amend_for`, `spec_amend_parent`, `remediates`) | Populated only when applicable (escalation paths, redteam remediation on done tickets). | the driver skill |

For body section order (Context → Definition of Done → Implementation notes → Big-picture notes → Out-of-scope expansion → Authorized test changes → Alternatives considered) and field defaults / comments / example values, read [`ticket-template.md`](ticket-template.md) directly.

---

## The flow (the milestone-driver skill orchestrates this)

The active skill is [`/m1-tick`](../../.claude/skills/m1-tick/SKILL.md) for milestone M1. Future milestones may instantiate their own driver. Steps below describe the procedure; the skill applies it.

### 0. Pick a ticket — `/<driver> next`

The skill reads `docs/plan/<milestone>/tickets/`, finds tickets where `status: pending` AND every entry in `blocked_by` has `status: done`, and prints the runnable list ordered by ID. The user picks one.

### 1. Start — `/<driver> start M<N>-NNN`

- **Ticket-clarity pre-flight.** Spawn a fresh-context subagent with the prompt at [`clarity-prompt.md`](clarity-prompt.md). It validates the ticket itself: are acceptance criteria runnable, is `out_of_scope` non-empty, do `spec_refs` resolve to real anchors in `docs/spec/`, is `files_budget` plausible given the acceptance criteria. Result is recorded under `clarity_check:` in frontmatter.
- If clarity returns `FAIL`, the start is blocked and the user is prompted to refine the ticket before implementation begins. `WARN` is informational and does not block.
- Set frontmatter `status: in-progress`. Update `last_updated`.
- Create branch `m<N>/M<N>-NNN-<slug>` off `main`.
- If `complexity: high`, spawn `Agent(subagent_type: "Plan")` with the ticket body and require an implementation outline before any code is written.
- The main conversation IS the developer from this point. No developer-subagent.
- Regenerate `STATUS.md`.

### 2. Implement

- Touch at most `files_budget` files; if `files_scope` is set, every touched file must also match a glob in that list. Stay outside `out_of_scope`. Approaching the numeric budget → escalate before exceeding it.
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
  - The diff (`git diff main...HEAD`).
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
| `REWORK` (round 2) | If the ticket has `round_cap: 2` (default): escalate (no round 3). If the ticket has `round_cap: 3`: address only the named items, re-run `mvn verify`, re-invoke reviewer for round 3. (Round 2's own must-shrink check has already run by the time this verdict applies; a must-shrink failure in this round is a `SCOPE-DRIFT-CHECK: FAIL` that escalates immediately regardless of `round_cap`.) |
| `REWORK` (round 3) | Only reachable when `round_cap: 3`. Escalate; no round 4 exists. (Round 3's own must-shrink check vs round 2 has already run by the time this verdict applies.) |
| `MANUAL` | Escalate immediately. The reviewer's uncertainty is not for the developer to resolve. |

**Round-N must-shrink (N ≥ 2).** Every rework round is a fix-only round. The reviewer compares round-N diff stats to round-(N−1): the round-N diff MUST be smaller along **at least one** of files-touched, net lines added, or net lines removed. Growth along **all three** dimensions simultaneously fails `SCOPE-DRIFT-CHECK` — unless the round-(N−1) REWORK explicitly required a refactor that grows the diff and the developer cited that REWORK item in the round-N commit message. Applies to round 2 (default `round_cap: 2`) AND to round 3 (only reachable when `round_cap: 3`). The canonical rule is in [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md) §8 "Round-N must-shrink".

### 6. Commit — `/<driver> commit M<N>-NNN`

- **Safety re-run before committing.**
  - For tickets with `complexity: high` OR `risk: high`: the commit subcommand re-runs `mvn verify` from the repo root. The commit only proceeds on success. This closes the "skipped tests, faked review" gap for the tickets where the cost is highest.
  - For all other tickets: the commit subcommand checks that the most recent test log (`target/<driver>-test-{ID}-r*.log`) has an mtime newer than the latest mtime among the staged files. If the log is older than any staged file, refuse and require a fresh `mvn verify`.
- One commit on the per-ticket branch.
- Subject: `M<N>-NNN: <imperative summary>` (≤ 72 chars).
- Body: the Context paragraph from the ticket + any `Alternatives considered:` trailer.
- `Reviewed-by:` trailer carrying the reviewer's `APPROVE` (or `OVERRIDE-APPROVE`) verdict line, the round number, and the reviewer agent's run identifier (or `NA` if the harness did not surface one). Exact format under "Commit conventions" below.
- Set frontmatter `status: done`. Update `last_updated`.
- Squash-merge into `main` is the user's call (so `main` history stays one-commit-per-ticket).

### 7. Escalate — `/<driver> escalate M<N>-NNN`

The skill prints the five-way menu in chat:

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

- `refine` → user edits the ticket; status returns to `in-progress`. The original frontmatter is preserved in a `revisions:` list with the date and a one-line reason.
- `override` → reviewer's specific objections are recorded under `overrides:` with a one-line user justification. Status returns to `in-review` and the skill proceeds to commit.
- `decompose` → driver allocates fresh IDs (`M<N>-AAA`, `M<N>-BBB`, ...) via the ID allocation algorithm; user provides only titles. Operand → `status: deferred` with `deferred_reason: decomposed`. Replacement skeletons created in `docs/plan/<milestone>/tickets/M<N>-AAA-<slug>.md` (etc.) with `decomposed_from: M<N>-NNN` (the operand) populated on each child. The lineage is queryable so a stale child doesn't get lost when its parent is later reopened.
- `defer` → user names the blocking ticket ID (or asks the skill to draft it). Original → `status: deferred` with `deferred_on:` and `deferred_reason: blocked-on-new-ticket`. Blocker → new pending ticket.
- `spec-amend` → the spec itself is wrong. Driver allocates a fresh ID (`M<N>-AAA`) for the amendment ticket, whose acceptance criteria amend the spec section, with `spec_amend_for: <spec-path-and-section>` and `spec_amend_parent: M<N>-NNN` (the operand). The operand → `status: deferred` with `deferred_reason: spec-amend` and `deferred_on: M<N>-AAA`. Use this instead of `decompose` whenever the issue is "the spec said X but should say Y", not "the implementation needs to be split into N pieces."

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

## Parallelism

Default: sequential. One `in-progress` ticket at a time.

Parallel allowed when:
- Two `pending` tickets have empty intersection on `files_scope` (no shared paths). Tickets without `files_scope` (purely numeric budgets) cannot be parallelized — the skill cannot mechanically prove disjointness without a path list.
- Two `pending` tickets have empty intersection on `out_of_scope` exclusions.
- Neither has `migration_touch: true` AND no in-flight ticket has `migration_touch: true` (migrations serialize globally; the flag makes the rule mechanically checkable).
- The user explicitly opts in via `/<driver> start <id> --parallel`.

If parallel, each ticket runs in a git worktree (`Agent(isolation: "worktree")`). The skill refuses to start a parallel ticket whose constraints overlap an in-flight ticket; the conflict is surfaced via `STATUS.md`.

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

- For `in-progress` or `in-review` tickets — the user opens the standard five-way menu (trigger reason: `redteam-finding`) on that ticket; `redteam_findings:` is populated on it. The user can choose `refine` to widen acceptance, `decompose`, `defer`, or `spec-amend`.
- For `done` tickets — the original commit is **never amended** (per `CLAUDE.md` §M1 workflow "never amend a passed commit"). Instead, the user creates a **new remediation ticket** with `remediates: M<N>-XXX` set on the new ticket pointing back at the done ticket. The new ticket carries the fix; the done ticket's `redteam_findings:` is populated for traceability but its commit stays untouched. This preserves the one-commit-per-ticket invariant of `main`.
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
