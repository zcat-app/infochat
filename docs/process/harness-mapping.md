# Harness mapping — running the workflow on non-Claude coding agents

This file binds the abstract harness primitives named by
[`workflow.md`](workflow.md) and the skill procedures under `.claude/skills/`
to concrete mechanisms per coding agent. Claude Code is the native harness and
needs none of this; opencode and OpenAI Codex CLI are the two mapped tools;
the Generic column is the contract any capable agent can satisfy by hand.
Nothing in this file changes the workflow itself — if a binding here seems to
require a different procedure, this file is wrong and the procedure wins.

Tool flags and discovery paths below were verified against the tools' public
docs on 2026-07-19. Versions move: verify flags against your installed version
on first use, and treat §7 as the acceptance test.

## 1. The five primitives

The workflow needs exactly five harness-provided capabilities. Everything else
— ticket linting, STATUS regen, prompt rendering, verdict files, flock
serialization, git mechanics — is plain bash/python/git and runs identically
everywhere.

| # | Primitive | Contract |
|---|---|---|
| 1 | Fresh-context gate agent | A reader with NO conversation history is pointed at a rendered prompt file; it Reads that prompt plus the files the prompt names, Writes a verdict/outline/report to the path the prompt supplies, and replies in the short fixed format the prompt specifies. Independence (no implementer rationale in its context) is the point of the gate. |
| 2 | Prompt render | `python3 scripts/m1-render-prompt.py <template> <out> KEY=VALUE… [KEY=@file]` substitutes `{{KEY}}` slots in a `docs/process/*` template and writes the rendered prompt to disk. Already tool-agnostic. |
| 3 | Verdict-file readback | The orchestrating session treats the ON-DISK artifact as the result; the gate agent's chat reply is a 3–4 line summary parsed literally. Never accept a chat-only verdict. |
| 4 | Blocking user menu | Present fixed options, stop, wait for the human's typed choice. |
| 5 | Worktree parallelism | Optional. `--parallel` tickets run in git worktrees; `scripts/verify-serialized.sh` (flock) serializes full-suite verifies across them. |

## 2. Gate-agent bindings

Five gate agents exist: `code-reviewer`, `plan-writer`, `threat-actor`,
`review-synthesizer`, `senior-developer`. Their roles and constraints are
single-sourced in `.claude/agents/<name>.md`; their operating instructions
arrive via the rendered prompt (primitive 2), which is why the per-tool
definitions can stay thin pointers. The binding pattern is identical for all
five:

| Harness | Definition read | Invocation |
|---|---|---|
| Claude Code (native) | `.claude/agents/<name>.md` | the skill spawns the agent with the stub prompt below — status quo |
| opencode | `.opencode/agent/<name>.md` (`mode: subagent`) | Task-tool routing or `@<name>` mention with the stub prompt, or headless (§3) |
| Codex CLI | `.codex/agents/<name>.toml` | `spawn_agent` + `wait` with the stub prompt, or headless (§3) |
| Generic (any agent) | none needed | any FRESH session/process of a capable model, given the stub prompt |

The stub prompt is the same everywhere:

> Read `<rendered-prompt-path>` and follow it exactly. It names every input
> file and the output path. Write the required artifact to that path and
> reply only in the format the prompt specifies.

## 3. Headless recipes

When a tool's in-session subagent mechanism is unavailable (or you want a
process boundary for CI), run the gate as a separate headless invocation.
Render first (primitive 2), then:

Codex CLI (the `-o` file captures the chat reply; the real artifact is the
verdict file the rendered prompt names; `workspace-write` because gate agents
must Write their artifact):

    echo 'Read <rendered-prompt-path> and follow it exactly. It names every input file and the output path. Write the required artifact to that path and reply only in the format the prompt specifies.' \
      | codex exec - --sandbox workspace-write -o target/<gate>-reply.txt

opencode:

    opencode run --agent <name> \
      "Read <rendered-prompt-path> and follow it exactly. It names every input file and the output path. Write the required artifact to that path and reply only in the format the prompt specifies." \
      > target/<gate>-reply.txt

After either: read the verdict/outline/report file back from disk
(primitive 3) and run the §6 contamination check. If CI or repeated headless
use materializes, extract these recipes into `scripts/run-gate.sh`; until
then this section is their single source.

## 4. Blocking-menu degradation

Claude Code uses the AskUserQuestion widget at exactly three sites (`start`
grounding-confirm, `start` ambiguity question, `commit` test-freshness
Skip/Run). Every other human gate in the workflow — `escalate`'s six-way
menu, `abort`'s confirmation, `reopen` — ALREADY uses the degraded form. On
any other harness, use that same form at all sites: print the options as
numbered lines plus one line saying what to reply, then STOP and wait for the
human's reply. Never auto-pick an option.

## 5. Parallelism degradation

Worktrees are plain git — nothing Claude-specific. If your tool can operate
in another working directory, `--parallel` works as written (workflow.md
§Parallelism). If it can't, skip `--parallel` and run tickets sequentially;
nothing else changes. `scripts/verify-serialized.sh` works on any Linux/macOS
with flock and must stay in the loop wherever parallel verifies are possible.

## 6. Weaker guarantees on non-Claude harnesses (honest notes)

- **No per-path Write restriction.** On Claude Code the gate agents' `tools:`
  allowlist plus prompt constrain them to Write only their artifact.
  opencode's permissions are per-tool (write on/off) and Codex's sandbox is
  per-mode (`workspace-write`) — neither restricts WHICH path, so there the
  constraint is prompt discipline. Mitigation, MANDATORY on non-Claude
  harnesses: after a gate agent returns, run `git status --porcelain` and
  compare against the expected artifact path. ANY other new or changed path
  means a contaminated gate — discard the artifact, revert the contamination,
  re-run the gate.
- **Fresh context is a process property.** In-session subagents on
  opencode/Codex are fresh by construction, like Claude's. Hand-running a
  gate inside your MAIN session forfeits the independence guarantee; treat
  such a verdict as advisory at best.
- **`model: inherit` has no cross-tool equivalent.** The per-tool agent defs
  omit `model`, so each tool uses its configured default. Use a strong model
  for gates — a weak reviewer is worse than none, because it APPROVEs.

## 7. Fork-side smoke checklist

No non-Claude CLI is installed on the reference machine, so these are the
acceptance checks a fork runs once per tool (record the results in your
fork):

opencode:
1. Skill discovery: `m1-tick` / `redteam` / `deep-code-review` are listed
   (source: the `.agents/skills/` wrappers; opencode also reads
   `.claude/skills/` natively — note whether both register and whether
   routing dedups; if double registration breaks routing, disable one source
   via opencode's config rather than touching either skill tree).
2. `opencode run --agent code-reviewer` — verify current flags (stdin/file
   input was undocumented at mapping time; shell-substitute the prompt text
   if needed).
3. One end-to-end review gate on a toy diff: rendered prompt → verdict file
   lands → §6 contamination check passes.

Codex CLI:
4. Skill discovery from `.agents/skills/` (Codex does NOT read
   `.claude/skills/`).
5. Whether `.codex/agents/*.toml` is auto-discovered in a trusted project or
   requires a project `.codex/config.toml` — add the minimal config only if
   required.
6. One gate via `spawn_agent` AND one via `codex exec` (§3): the verdict
   lands, and only the verdict path changed.

Any tool:
7. Drive `/m1-tick start` (via the `.agents/skills/m1-tick` wrapper) on a
   draft ticket to the first grounding menu; confirm the §4 printed-menu form
   renders and blocks.
8. After your setup and first runs: `git diff --stat -- .claude CLAUDE.md`
   MUST be empty. `.claude/` is Claude Code's config and is never modified by
   other tools' runs.
