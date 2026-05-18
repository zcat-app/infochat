---
name: m1-tick
description: Drive the M1 ticket workflow — pick the next runnable ticket, start work on a branch, run review via the code-reviewer subagent, commit on approval, or surface the five-way escalation menu when a round cap or trigger fires. Use when the user invokes `/m1-tick <subcommand>` (next | start <id> | review <id> | commit <id> | escalate <id> | abort <id> | show <id> | reopen <id> | status). For adversarial security review, see the separate `/redteam` skill. The universal workflow specification is in `docs/process/workflow.md`; M1-specific framing is in `docs/plan/m1/README.md`; the engineering rules are in `CLAUDE.md` §Engineering rules + §M1 workflow + `docs/process/engineering-rules-verbatim.md` — those are the source of truth; this skill is the procedure that applies them.
---

# /m1-tick — M1 ticket workflow

This skill is the procedure. The rules live in `CLAUDE.md` §Engineering rules + §M1 workflow and verbatim in [`engineering-rules-verbatim.md`](../../../docs/process/engineering-rules-verbatim.md). The universal workflow specification is [`docs/process/workflow.md`](../../../docs/process/workflow.md); M1-specific framing is [`docs/plan/m1/README.md`](../../../docs/plan/m1/README.md). If this skill conflicts with any of those, those win — flag the drift and stop.

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

## Cross-cutting rules this skill must obey

- **Never push.** That's the user's call. The skill performs squash-merge locally on demand via `/m1-tick merge <id>`, but never pushes the result.
- **Never amend a passed commit.** Defects → new ticket.
- **Never skip `mvn verify`** before review. If the developer claims tests pass without running them, refuse and re-run.
- **Never skip the commit-step safety re-run** for high-complexity / high-risk tickets. The cost of re-running is small; the cost of shipping a faked review is large.
- **Never spawn a developer-subagent.** The main conversation is the developer; subagents this skill spawns are: the reviewer (always at `review`), the planner (only on `complexity: high` at `start`), and the clarity pre-flight (always at `start`). The threat-actor subagent lives in the separate [`/redteam`](../redteam/SKILL.md) skill — this skill never spawns it directly.
- **Never edit `STATUS.md` by hand.** Always regenerate from frontmatter.
- **Never silently expand a ticket's `files_budget`, `files_scope`, or `out_of_scope`.** Frontmatter changes go through `escalate → refine`.
- **Never set `status: in-progress` manually.** The `pending → in-progress` transition happens only inside `/m1-tick start <id>`, which runs the clarity pre-flight and (for `complexity: high`) the Plan subagent. Flipping `status` by hand — including immediately after `escalate refine` — skips those gates. The same applies after an `outline-fail` refine: the refined ticket commits to `main` with `status: pending`, and the next implementation pass must go through `/m1-tick start` so Plan re-runs against the rewritten acceptance.
- **Never use destructive shortcuts** (`--no-verify`, `git reset --hard`, `--skip-tests`, force-push) to make obstacles disappear. Escalate instead.
- **`abort` is destructive and requires explicit user confirmation.** Branch deletion uses `git branch -D` only after the user types `yes`.
- **If this skill's procedure conflicts with `CLAUDE.md` §Engineering rules, §M1 workflow, `docs/process/workflow.md`, `docs/plan/m1/README.md`, or `docs/process/engineering-rules-verbatim.md`, those win.** Stop and surface the conflict; do not proceed.
