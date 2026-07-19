# Harness mapping — running the workflow on non-Claude coding agents

This file binds the abstract harness primitives named by
[`workflow.md`](workflow.md) and the skill procedures under `.claude/skills/`
to concrete mechanisms per coding agent. Claude Code is the native harness and
needs none of this; opencode and OpenAI Codex CLI are the two mapped tools;
the Generic column is the contract any capable agent can satisfy by hand.
Nothing in this file changes the workflow itself — if a binding here seems to
require a different procedure, this file is wrong and the procedure wins.

Both tool columns were verified empirically on 2026-07-19 against **opencode
1.18.3** and **codex-cli 0.144.6** — discovery, agent resolution, config
loading, and the gotchas in §6.1/§6.2 were measured, not read from docs. Two
published claims turned out false against the real binaries (noted in place).
The only step not yet run is a live end-to-end gate (§7), which costs a real
model call. Versions move: re-check on first use.

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
| Codex CLI | **none — no repo-shippable agent definition exists** (§6.2) | `spawn_agent` with the stub prompt, or headless `codex exec` (§3) |
| Generic (any agent) | none needed | any FRESH session/process of a capable model, given the stub prompt |

Codex has no per-agent definition because its spawned agents are deliberately
generic — the prompt states all agents "are equally intelligent and capable,
and have access to the same set of tools". That costs nothing here: this
architecture already carries the persona in the rendered prompt plus
`.claude/agents/<name>.md`, which the stub prompt tells the agent to read, so a
generic spawn arrives at the same behavior. What is lost is per-agent
capability scoping — see §6.2.

The stub prompt is the same everywhere:

> Read `<rendered-prompt-path>` and follow it exactly. It names every input
> file and the output path. Write the required artifact to that path and
> reply only in the format the prompt specifies.

## 3. Headless recipes

When a tool's in-session subagent mechanism is unavailable (or you want a
process boundary for CI), run the gate as a separate headless invocation.
Render first (primitive 2), then:

Codex CLI (`-o/--output-last-message` captures the final chat reply; the real
artifact is the verdict file the rendered prompt names; `workspace-write`
because gate agents must Write that artifact). The prompt may be passed
positionally or on stdin via `-`:

    printf 'Read <rendered-prompt-path> and follow it exactly. It names every input file and the output path. Write the required artifact to that path and reply only in the format the prompt specifies.' \
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

## 6.1 opencode: two verified gotchas that silently break the gates

Both were measured against opencode 1.18.3. Both fail *silently* — the flow
appears to work and produces nothing usable.

**(a) `edit: false` disables the `write` tool.** opencode gates writing under
the edit permission, so an agent declaring `write: true, edit: false` resolves
to `write=false` and cannot produce its verdict file. The Claude allowlist is
Write-without-Edit, so the natural translation is exactly wrong. The agent
definitions in `.opencode/agent/` therefore set `edit: true` with a comment
saying why — meaning **on opencode a gate agent can also edit files**, which is
what makes the `git status --porcelain` check below load-bearing rather than
advisory. Verify with `opencode debug agent <name>`; the resolved map, not the
frontmatter, is the truth.

**(b) Same-named skills in `.claude/skills/` and `.agents/skills/` resolve
NONDETERMINISTICALLY.** opencode scans both trees; when a name exists in each,
which copy wins is a race — measured flipping run to run across five runs. If
the `.claude/` copy wins, opencode executes the raw Claude procedure with no
harness translation and will try to spawn Claude subagents. opencode's own docs
say only "ensure skill names are unique across all locations" and document no
way to disable a source. The binary does carry an undocumented
`OPENCODE_DISABLE_CLAUDE_CODE_SKILLS` env var, and it works — with it set, all
three skills resolved to the `.agents/` wrappers 5/5 runs:

    OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=1 opencode …

Set it in the shell profile or task runner you launch opencode from. Confirm
with `opencode debug skill` that every workflow skill's `location` is under
`.agents/skills/`. Being undocumented it may change; the check is the
safeguard, not the variable. (Codex is unaffected — it reads `.agents/skills/`
only and never `.claude/skills/`, so no collision exists there.)

## 6.2 Codex: verified discovery facts

Measured against codex-cli 0.144.6 with `codex debug prompt-input`, which
renders the model-visible prompt locally (no auth, no token spend) and is the
cheapest way to confirm any of this.

- ✅ **`AGENTS.md` is loaded** — it appears verbatim in the prompt as
  "AGENTS.md instructions for `<repo>`".
- ✅ **`.agents/skills/` is the working skills path** — all three workflow
  skills register with their file locators alongside Codex's built-ins. Codex
  reads neither `.claude/skills/` nor `.codex/skills/`, so the opencode
  collision of §6.1(b) cannot occur here.
- ❌ **`.codex/agents/*.toml` is NOT read.** A deliberately malformed file
  there produces no error even with the project trusted, and the path does not
  appear in the binary. Repo-shipped per-agent definitions for Codex were
  written and then **deleted** rather than left as decoration — an inert config
  file is worse than none, because it implies capability scoping that is not
  in force. Custom-agent support does exist (the binary validates
  `developer_instructions`), but only via config, i.e. not repo-shippable
  per-project.
- ⚠ **A project `.codex/config.toml` is read ONLY if the project is trusted.**
  Untrusted, it is silently ignored — no warning. Trust is declared in the
  USER's `~/.codex/config.toml`, so it is a per-machine setup step a fork must
  perform, not something the repo can ship:

      [projects."/absolute/path/to/repo"]
      trust_level = "trusted"

  Trusting also flips the sandbox to `workspace-write`. Verify with
  `codex debug prompt-input t` — the permissions block names the mode and the
  writable roots.
- Concurrency: 4 slots by default (the prompt states it), so a fan-out wider
  than 4 queues rather than failing.

## 6. Weaker guarantees on non-Claude harnesses (honest notes)

- **No per-path Write restriction, and on Codex no per-agent scoping at all.**
  On Claude Code the gate agents' `tools:` allowlist plus prompt constrain them
  to Write only their artifact. opencode's permissions are per-tool (and
  `write` drags `edit` in with it, §6.1(a)); Codex's spawned agents are generic
  and share the session's `workspace-write` sandbox (§6.2). Neither restricts
  WHICH path, so there the constraint is prompt discipline alone. Mitigation,
  MANDATORY on non-Claude harnesses: after a gate agent returns, run
  `git status --porcelain` and compare against the expected artifact path. ANY
  other new or changed path means a contaminated gate — discard the artifact,
  revert the contamination, re-run the gate.
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

opencode (steps 1–2 VERIFIED on 1.18.3, 2026-07-19 — re-run after an upgrade):
1. ✅ Skill discovery — `opencode debug skill` lists `m1-tick`, `redteam`,
   `deep-code-review`. Confirm each `location` is under `.agents/skills/`; if
   any resolves to `.claude/skills/`, apply §6.1(b).
2. ✅ Agent discovery — `opencode agent list` shows all five gate agents as
   `(subagent)`, and `opencode debug agent <name>` resolves `write=true`.
   A `write=false` here means §6.1(a) has regressed.
3. ⬜ One end-to-end review gate on a toy diff: rendered prompt → verdict file
   lands → §6 contamination check passes. (Not yet run — it costs a real model
   call. `opencode run --agent code-reviewer "<stub>"` takes the prompt as a
   positional argument; there is no stdin flag, and `-f/--file` attaches files
   rather than supplying the prompt.)

Codex CLI (steps 4–5 VERIFIED on 0.144.6, 2026-07-19):
4. ✅ Skill discovery from `.agents/skills/` and `AGENTS.md` loading — confirm
   with `codex debug prompt-input t` (local, no auth, no token spend).
5. ✅ Agent definitions: none needed — `.codex/agents/` is not read (§6.2).
   If you want a project `.codex/config.toml` honored, mark the project
   trusted in `~/.codex/config.toml` first, then confirm by breaking the file
   deliberately and seeing a parse error.
6. ⬜ One gate via `spawn_agent` AND one via `codex exec` (§3): the verdict
   lands, and only the verdict path changed. (Not yet run — this host has no
   Codex credentials; `codex doctor` reports the auth state.)

Any tool:
7. Drive `/m1-tick start` (via the `.agents/skills/m1-tick` wrapper) on a
   draft ticket to the first grounding menu; confirm the §4 printed-menu form
   renders and blocks.
8. After your setup and first runs: `git diff --stat -- .claude CLAUDE.md`
   MUST be empty. `.claude/` is Claude Code's config and is never modified by
   other tools' runs.
