---
name: m1-tick
description: Drive the M1 ticket workflow — pick the next runnable ticket, start work on a branch, run review via the code-reviewer subagent, commit on approval, or surface the five-way escalation menu when a round cap or trigger fires. Use when the user invokes `/m1-tick <subcommand>` (next | start <id> | review <id> | commit <id> | merge <id> | escalate <id> | abort <id> | show <id> | reopen <id> | status). For adversarial security review, see the separate `/redteam` skill.
---

# /m1-tick — M1 ticket workflow

This skill is the procedure. The engineering rules live in `CLAUDE.md` §Engineering rules and verbatim in [`engineering-rules-verbatim.md`](../../../docs/process/engineering-rules-verbatim.md); the M1-specific workflow rules live in the §M1 workflow rules section of this file (moved here from `CLAUDE.md` so they load only when `/m1-tick` fires). The universal workflow specification is [`docs/process/workflow.md`](../../../docs/process/workflow.md); M1-specific framing is [`docs/plan/m1/README.md`](../../../docs/plan/m1/README.md). If this skill conflicts with any of those, those win — flag the drift and stop.

Adversarial security review (formerly `/m1-tick redteam`) is now its own skill: [`/redteam`](../redteam/SKILL.md). The two skills are intentionally decoupled — redteam findings reach this workflow only via the user invoking `/m1-tick escalate <id> redteam-finding`.

## Subcommand routing

The user invokes the skill as `/m1-tick <subcommand> [args]`. Each subcommand's full procedure lives in its own file under `.claude/skills/m1-tick/subcommands/`. This router holds only the dispatch table and the cross-cutting rules; per-subcommand wording, preconditions, and steps are the source of truth in the per-subcommand file.

**Dispatch.** When the user invokes `/m1-tick <subcommand> [args]`:

1. Parse the args. Identify the subcommand.
2. Read `.claude/skills/m1-tick/subcommands/<subcommand>.md` (the path from the table below).
3. Apply that file's procedure verbatim. The per-subcommand file is the single source of truth for that subcommand — do NOT apply procedure from memory or from a stale cached copy of this router; Read the file fresh.

Dispatch table:

| User invocation | Procedure file |
|---|---|
| `/m1-tick next` (or empty) | [`.claude/skills/m1-tick/subcommands/next.md`](subcommands/next.md) — list runnable tickets |
| `/m1-tick start <id>` | [`.claude/skills/m1-tick/subcommands/start.md`](subcommands/start.md) — begin work on a ticket |
| `/m1-tick start <id> --parallel` | [`.claude/skills/m1-tick/subcommands/start.md`](subcommands/start.md) — start in a worktree |
| `/m1-tick review <id>` | [`.claude/skills/m1-tick/subcommands/review.md`](subcommands/review.md) — spawn reviewer subagent |
| `/m1-tick commit <id>` | [`.claude/skills/m1-tick/subcommands/commit.md`](subcommands/commit.md) — finalize the per-ticket commit |
| `/m1-tick merge <id>` | [`.claude/skills/m1-tick/subcommands/merge.md`](subcommands/merge.md) — squash-merge the per-ticket branch into main |
| `/m1-tick escalate <id> [reason]` | [`.claude/skills/m1-tick/subcommands/escalate.md`](subcommands/escalate.md) — fire the five-way menu |
| `/m1-tick abort <id>` | [`.claude/skills/m1-tick/subcommands/abort.md`](subcommands/abort.md) — cancel an in-progress ticket and roll back |
| `/m1-tick show <id>` | [`.claude/skills/m1-tick/subcommands/show.md`](subcommands/show.md) — read-only inspection of a ticket |
| `/m1-tick reopen <id>` | [`.claude/skills/m1-tick/subcommands/reopen.md`](subcommands/reopen.md) — bring a deferred ticket back to pending |
| `/m1-tick status` | [`.claude/skills/m1-tick/subcommands/status.md`](subcommands/status.md) — regenerate STATUS.md and print summary |

If the args don't match any row, print the table above and stop. For `redteam`, point the user at the separate [`/redteam`](../redteam/SKILL.md) skill.

---

## M1 workflow rules

These are the lifecycle / process rules the skill applies. Previously the always-loaded summary lived in `CLAUDE.md` §M1 workflow; it now loads here on `/m1-tick` invocation so non-M1 conversations no longer pay the context cost. If these conflict with `docs/process/workflow.md`, that file wins — flag the drift and stop.

- **Tickets** live in `docs/plan/m1/tickets/M1-NNN-<slug>.md`, one file per ticket, YAML frontmatter — see `docs/process/ticket-template.md` for the full schema (key fields: `id`, `status`, `blocked_by`, `acceptance`, `files_budget`, `out_of_scope`, `complexity`, `risk`, `round_cap`, `security_relevant`, `migration_touch`).
- **Status board** is `docs/plan/m1/STATUS.md`, regenerated from frontmatter; never hand-edit, always derive.
- **Lifecycle**: `pending` → `in-progress` → `in-review` → `done` (or `escalated` / `deferred`).
- **One ticket = one branch = one commit on `main` after `/m1-tick merge` squash-merges the branch.** Branch name `m1/M1-NNN-<slug>`. Commit subject `M1-NNN: <imperative summary>`. Body includes a `Reviewed-by:` trailer with the reviewer's verdict line. `/m1-tick commit` lands the commit on the per-ticket branch (status: done); `/m1-tick merge` performs the squash-merge into `main` as a separate explicit step.
- **Never amend a passed commit.** Defects found after a passed review become a new ticket and a new commit.
- **Round cap: 2 by default.** Implement → `mvn verify` → reviewer (round 1). If `REWORK`, fix only the named items → `mvn verify` → reviewer (round 2). If round 2 isn't `APPROVE`, escalate. Tickets with `complexity: high` or `risk: high` may set `round_cap: 3` in frontmatter.
- **Capture `mvn verify` output to a fixed path.** During implementation, always run from the repo root: `mkdir -p .scratch && mvn -B clean verify > .scratch/m1-tick-test-<ID>-r<round>.log 2>&1 ; ec=$? ; mkdir -p target && cp .scratch/m1-tick-test-<ID>-r<round>.log target/m1-tick-test-<ID>-r<round>.log ; exit $ec`. `/m1-tick review` and `/m1-tick commit` read the `target/` copy — if missing they must re-run, which wastes minutes. The round number matches the upcoming review round (1 for the first review, 2 for round-2 rework, etc.).
  - **Why redirect through `.scratch/` then copy, not write to `target/` directly.** The parent-module `mvn-clean-plugin` deletes `<repo-root>/target/` early in the build. A shell redirect opened to `target/m1-tick-test-...log` before `mvn` starts becomes an orphaned inode the moment clean fires; subsequent build output writes to the orphan (invisible to readers) even when the build itself succeeds (`tail` returns nothing useful, but `$?` is 0). Redirecting to `.scratch/` (gitignored, worktree-local) first and copying into `target/` after the build closes that gap. Same hazard applies to the `/m1-tick commit` safety re-run for high-complexity / high-risk tickets — see [`subcommands/commit.md`](subcommands/commit.md) step 2.
- **Every rework round must shrink (round-N must-shrink, N ≥ 2).** Growth along **all three** of files-touched, lines added, lines removed simultaneously vs round-(N−1) → automatic SCOPE-DRIFT-CHECK fail unless a citable mandate requires the growth — a prior-round REWORK item or a user-accepted in-branch redteam remediation (citation in the commit message required); holding equal or shrinking along any dimension is convergent. Round arithmetic and enforcement live in [`subcommands/review.md`](subcommands/review.md) step 6.
- **On session resume, inventory worktrees first.** Resuming after a crash or context loss with any ticket `in-progress`: run `git worktree list` and check each worktree's fork distance (`git rev-list --count main..<branch>`) before touching anything — case-variant worktree names can hide where the real branch lives. (Observed: M1-054, 2026-05-24.)
- **Ticket-clarity pre-flight at start.** `/m1-tick start` spawns a fresh-context subagent that validates the ticket itself before implementation begins; FAIL blocks the start. Procedure in [`subcommands/start.md`](subcommands/start.md) step 1.
- **Immediate escalation triggers** (skip remaining rounds): reviewer returns `MANUAL`; developer about to exceed `files_budget` or touch a path outside `files_scope` (when set); tests fail in a way that suggests the ticket's premise is wrong; two consecutive test failures with the same root cause.
- **Escalation surfaces a five-way menu** to the user in chat: refine / override / decompose / defer / spec-amend.
- **Reviewer is a fresh-context subagent** (`Agent` with `subagent_type: "code-reviewer"`); developer-as-subagent is forbidden. The reviewer's prompt template lives in `docs/process/reviewer-prompt.md`; input capture (diff, stats, negative-space list) lives in [`subcommands/review.md`](subcommands/review.md).
- **Threat-actor (red-team) review** runs at milestone boundaries, on tickets with `security_relevant: true`, and before release tags — via the separate [`/redteam`](../redteam/SKILL.md) skill; findings reach the lifecycle workflow only when the user runs `/m1-tick escalate <id> redteam-finding`.
- **Commit safety re-runs `mvn verify`.** For `complexity: high` or `risk: high` tickets, `/m1-tick commit` re-executes the full suite rather than trusting the prior log; for other tickets it verifies test-log freshness by mtime. Mechanics in [`subcommands/commit.md`](subcommands/commit.md) step 2.
- **Default sequential.** Parallel tickets require provably disjoint path-level scopes and no in-flight `migration_touch: true`; the precise gating criteria are the `--parallel` preconditions in [`subcommands/start.md`](subcommands/start.md). Tickets with only a numeric `files_budget` (no `files_scope`) cannot be parallelized.

---

## Cross-cutting rules this skill must obey

- **Never push.** That's the user's call. The skill performs squash-merge locally on demand via `/m1-tick merge <id>`, but never pushes the result.
- **Never skip `mvn verify`** before review. If the developer claims tests pass without running them, refuse and re-run.
- **Never skip the commit-step safety re-run** for high-complexity / high-risk tickets. The cost of re-running is small; the cost of shipping a faked review is large.
- **Never spawn a developer-subagent.** The main conversation is the developer; subagents this skill spawns are: the reviewer (always at `review`), the planner (only on `complexity: high` at `start`), and the clarity pre-flight (always at `start`). The threat-actor subagent lives in the separate [`/redteam`](../redteam/SKILL.md) skill — this skill never spawns it directly.
- **Never edit `STATUS.md` by hand.** Always regenerate from frontmatter.
- **Never silently expand a ticket's `files_budget`, `files_scope`, or `out_of_scope`.** Frontmatter changes go through `escalate → refine`.
- **Never set `status: in-progress` manually.** The `pending → in-progress` transition happens only inside `/m1-tick start <id>`, which runs the clarity pre-flight and (for `complexity: high`) the plan-writer subagent. Flipping `status` by hand — including after any `escalate refine` arm — skips those gates; the per-arm rules live in [`subcommands/escalate.md`](subcommands/escalate.md) step 5.
- **Never use destructive shortcuts** (`--no-verify`, `git reset --hard`, `--skip-tests`, force-push) to make obstacles disappear. Escalate instead.
- **`abort` is destructive and requires explicit user confirmation.** Branch deletion uses `git branch -D` only after the user types `yes`.
- **If this skill's procedure conflicts with `CLAUDE.md` §Engineering rules, `docs/process/workflow.md`, `docs/plan/m1/README.md`, or `docs/process/engineering-rules-verbatim.md`, those win.** Stop and surface the conflict; do not proceed. (The §M1 workflow rules section above is part of this file and is the source of truth for the M1-specific rules it enumerates.)
