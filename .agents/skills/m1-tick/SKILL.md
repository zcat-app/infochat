---
name: m1-tick
description: Drive the M1 ticket workflow — pick the next runnable ticket, start work on a branch, run review via the code-reviewer subagent, commit on approval, or surface the escalation menu when a round cap or trigger fires. Use when the user invokes `/m1-tick <subcommand>` (run [id] | next | start <id> | review <id> | commit <id> | merge <id> | escalate <id> | abort <id> | show <id> | reopen <id> | status). `run` drives one ticket through the whole cycle unattended, stopping only at human-owned gates. For adversarial security review, see the separate `/redteam` skill.
---

This is a compatibility wrapper for non-Claude coding agents. The procedure
is single-sourced at `.claude/skills/m1-tick/SKILL.md` (dispatch router) and
`.claude/skills/m1-tick/subcommands/*.md` (per-subcommand steps).

1. Read `.claude/skills/m1-tick/SKILL.md` and follow it verbatim, including
   its instruction to Read the matching subcommand file fresh per invocation.
2. Wherever it names a Claude Code primitive, apply the binding for YOUR
   tool from `docs/process/harness-mapping.md`:
   - "spawn the <name> subagent" → mapping §2 (fresh-context gate agent; §3
     for the headless form), then the §6 contamination check
   - "AskUserQuestion" → mapping §4 (print the menu as numbered text, stop,
     wait for a typed reply — the same pattern the escalate menu already uses)
   - `--parallel` / git worktrees → mapping §5 (degrade to sequential if your
     tool cannot operate in another working directory)
3. Everything else — `scripts/*`, prompt rendering via
   `scripts/m1-render-prompt.py`, verdict files on disk, flock serialization
   — is plain bash/python; run it as written.

Never modify anything under `.claude/`.
