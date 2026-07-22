# Harness mapping — running the workflow on non-Claude coding agents

This file binds the abstract harness primitives named by
[`workflow.md`](workflow.md) and the skill procedures under `.claude/skills/`
to concrete mechanisms per coding agent. Claude Code is the native harness and
needs none of this; opencode, OpenAI Codex CLI and Kimi Code are the mapped
tools; the Generic column is the contract any capable agent can satisfy by hand.
Nothing in this file changes the workflow itself — if a binding here seems to
require a different procedure, this file is wrong and the procedure wins.

Every tool column was verified empirically against the real binary — **opencode
1.18.3** and **codex-cli 0.144.6** on 2026-07-19, **kimi-code 0.29.0** on
2026-07-22. Discovery, agent resolution, config loading, and the gotchas in
§6.1/§6.2/§6.3 were measured, not read from docs. Two published claims turned
out false against the real binaries (noted in place). The step not yet run on
every tool is a live end-to-end gate (§8), which costs a real model call.
Versions move: re-check on first use.

## 1. The six primitives

The workflow needs exactly six harness-provided capabilities. Everything else
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
| 6 | Cross-harness gate | Optional. Fan the SAME rendered prompt through several coding-agent CLIs headlessly, then cross-examine their verdicts. Single-sourced in §7; the wrapper script is `scripts/redteam-multi.sh`. |

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
| opencode | `.opencode/agent/<name>.md` (**`mode: all`** — see §6.1(c)) | Task-tool routing or `@<name>` mention with the stub prompt, or headless (§3) |
| Codex CLI | **none — no repo-shippable agent definition exists** (§6.2) | `spawn_agent` with the stub prompt, or headless `codex exec` (§3) |
| Kimi Code | **none — `.claude/agents/` is not a path it reads** (§6.3) | headless `kimi -p` with the stub prompt (§3) |
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

Kimi Code (`-p` is the headless form: it prints the reply on stdout — there is
no output-file flag — and auto-approves tool calls, so no permission flag is
passed; `--auto` is in fact REJECTED alongside `-p`. `--skills-dir <empty-dir>`
switches project skill discovery off, which a gate agent never needs — §6.3):

    kimi -p "Read <rendered-prompt-path> and follow it exactly. It names every input file and the output path. Write the required artifact to that path and reply only in the format the prompt specifies." \
      --skills-dir <empty-dir> > target/<gate>-reply.txt

After any of these: read the verdict/outline/report file back from disk
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

**(c) `opencode run --agent <name>` REFUSES a `mode: subagent` agent** and
falls back to the default primary agent — which has write, edit AND bash
allowed. It prints a warning and then proceeds, so a headless gate silently
runs unconstrained and without its persona. Verified: with `mode: subagent` the
run reported `> build · glm-4.7`; with `mode: all` it reported
`> code-reviewer · glm-4.7`. All five agent definitions therefore use
`mode: all`, which keeps them available BOTH for in-session subagent routing
and for headless `run --agent`. If a gate's output looks generic, check the
banner line for which agent actually ran.

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

## 6.3 Kimi Code: verified discovery facts

Measured against kimi-code 0.29.0 on 2026-07-22. Unlike §6.2, most of these
cost a (small) model call to establish, because kimi ships no local
prompt-rendering debug command.

- ❌ **`.claude/agents/` is NOT read.** Agent profiles are discovered from
  `.agents/agents/` and `~/.kimi-code/agents/` only, and the `--agent` /
  `--agent-file` flags that would load one are gated behind the experimental v2
  engine (`KIMI_CODE_EXPERIMENTAL_FLAG=1`; without it both flags are refused
  outright). No repo-shippable agent definition is therefore written for kimi:
  as with Codex (§2), the persona travels in the rendered prompt, which carries
  the full "You are an adversary…" framing on its own.
- ⚠ **Project skills are discovered from BOTH `.claude/skills/` and
  `.agents/skills/`** — the same two-tree collision that makes opencode
  nondeterministic (§6.1(b)). Measured in a scratch repo carrying a same-named
  skill in each tree: the `.agents/` copy won that run. No kill-switch env var
  exists, but `--skills-dir <dir>` replaces auto-discovery outright, and
  pointing it at an EMPTY directory suppresses every project and user skill
  (verified: 0 of the probe skills survived; only kimi's own built-ins remain).
  That is the binding the red-team wrapper uses — a gate agent needs no skill,
  so the safest resolution is none at all.
- ✅ **Headless writes work with no permission flag.** `kimi -p '<stub>'` runs
  one prompt non-interactively and auto-approves tool calls; a verified probe
  wrote its file and replied in the requested format. `--auto` is rejected in
  combination with `-p` ("Cannot combine --prompt with --auto"), so do not add
  it by analogy with the other harnesses.
- ✅ **Exit status is meaningful** — 1 on a failed run, 0 on success (unlike
  codex, which exits 0 even when its sandbox denies it the prompt file). The
  verdict-file check still governs, per primitive #3.
- **Auth probe:** `kimi doctor` validates config file SYNTAX only and reports
  OK on a host with no provider configured, so it cannot stand in for an auth
  check. `kimi provider list` can: unconfigured it prints "No providers
  configured." and nothing else; configured it prints the provider table plus a
  `Default model:` line. Costs no tokens.
- Model: kimi uses `default_model` from `~/.kimi-code/config.toml` unless `-m`
  overrides it. Per the §6 note below, leave it on the configured default and
  make sure that default is a strong model.

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

## 7. Cross-harness gate (multi-auditor red-team)

This section single-sources primitive #6. The wrapper script is
`scripts/redteam-multi.sh`; the cross-examination parser is
`scripts/redteam-multi-cross.py`. The skill wrapper that exposes it as a
command is `.agents/skills/redteam-multi/SKILL.md` (and, on Claude Code,
`.claude/skills/redteam-multi/SKILL.md` — not yet authored; see the
opencode-boundary note at the end of this section).

### What it is

`/redteam-multi <target>` runs the SAME rendered red-team prompt through
several independent coding-agent CLIs (claude, opencode, codex, kimi) and
cross-examines their verdicts. The point of fanning out is that a single
auditor's blind spots are systematic: a finding only one model reports is
either a real gap the others missed or a false positive exposing that
model's bias, and only a falsification pass tells those apart. The
cross-examination stage makes the difference legible to the user.

### Contract — what every harness MUST do identically

1. **Headless invocation, uniformly.** Every auditor — including the
   harness you invoked `/redteam-multi` from — is spawned as a separate
   headless process via the §3 recipes. An in-session subagent and a
   headless process differ in context assembly, system prompt and tool
   wrappers, so a difference in findings could not be attributed to the
   model. Uniform invocation is a correctness requirement of the
   comparison, not a convenience. (A subprocess also cannot call back into
   its parent agent session — bash has no channel to drive the
   orchestrating LLM.)
2. **One verdict file per auditor.** Each auditor Writes its verdict to
   `verdict-<auditor>.txt` under the run directory, in the exact format
   `docs/process/redteam-prompt.md` specifies. The auditor's chat reply is
   discarded; only the on-disk verdict is read back (primitive #3).
3. **No skill recursion.** The wrapper invokes the GATE AGENT (`threat-
   actor`), never the `/redteam` or `/redteam-multi` skill. The Claude and
   opencode `threat-actor` agent definitions declare no Task/skill tool,
   so recursion is structurally impossible there. Codex's spawned agents
   are generic and CAN read `.agents/skills/`, so on Codex the
   `REDTEAM_MULTI_DEPTH` env var is the primary guard: the wrapper sets it
   to 1 on every spawn and refuses to start when it is already non-zero.
   kimi is generic in the same way, but there the wrapper additionally
   denies it the skills entirely — `--skills-dir` aimed at an empty
   directory (§6.3) — leaving `REDTEAM_MULTI_DEPTH` as the backstop.
4. **The diff-range algorithm is consumed, not reimplemented.** The
   wrapper accepts a resolved range (`--base`/`--head`, `--diff`, or
   `--ticket` for the MERGED form) and does NOT reimplement the four-form
   algorithm from `.claude/skills/redteam/SKILL.md` §1 — duplicating it in
   bash is a drift risk. The in-progress branch forms of `/redteam` are
   deliberately unreachable here.

### What the wrapper handles (failure modes)

- **Empty diff.** Refuses to render or spawn. An audit of nothing returns
  CLEAN, and that verdict is indistinguishable in the record from a real
  one (the same fail-closed rule single-auditor `/redteam` applies).
- **Unavailable auditor.** A binary missing, auth invalid, agent
  definition unresolvable, or run failing to produce a verdict file → a
  stub verdict reading `RED-TEAM VERDICT: UNAVAILABLE`. Never CLEAN.
- **Single available auditor.** The run proceeds (one audit is still an
  audit) but cross-examination is SKIPPED — without an independent
  refuter the comparison is meaningless. A degenerate
  `cross-examination.md` is still written for layout symmetry.
- **Contamination.** After each auditor the wrapper captures
  `git status --porcelain`, filters out paths under the run directory
  (which is under `docs/plan/` and therefore tracked), and compares. Any
  residual delta is contamination — the auditor wrote outside its
  verdict path. This is the same load-bearing check §6 mandates for any
  non-Claude gate; it is mandatory here because the verdict paths
  themselves live in tracked space.
- **Reaudits.** Same target, same day → `-r2`, `-r3`, ... suffix on the
  run directory, mirroring the `docs/plan/m1/redteam/<id>-<date>-rN.md`
  convention.
- **Persistent storage.** Evidence lives under
  `docs/plan/m1/redteam-multi/<slug>-<date>[-rN]/`, NOT under `target/`
  (gitignored, wiped by `mvn clean`). The directory contains: `preflight.txt`,
  `diff.patch`, `inv-{auth,authz,input,ban,audit}.txt`, `prompt-<id>.txt`,
  `reply-<id.txt>`, `verdict-<id>.txt`, `porcelain-<id>.txt`, and
  `cross-examination.md` (plus an empty `kimi-no-skills/` when the kimi slot
  ran — the directory §6.3 aims `--skills-dir` at; git tracks no empty
  directory, so it never reaches the commit). Commit the whole directory alongside
  `docs/plan/m1/redteam/` as the audit record.

### Cross-examination — v1 (shipped) vs v2 (not yet wired)

- **v1** is `scripts/redteam-multi-cross.py`: a deterministic parser that
  extracts findings from each `verdict-<id>.txt`, clusters them across
  auditors by `(CATEGORY, primary file:line cited in GAP)`, and emits
  `cross-examination.md` with a summary, a side-by-side table, per-cluster
  detail, and a single-auditor-findings callout. Clustering is fuzzy on
  purpose — two auditors citing the same code site under slightly
  different `file:line` forms (e.g. `Foo.java:472` vs `Foo.java:472-474`)
  produce two clusters; the per-cluster detail section makes the match
  legible to a human.
- **v2** is a fresh-context synthesizer subagent that Reads every
  `verdict-<id>.txt` and falsifies each single-auditor finding by
  re-auditing it against the threat model. v1 surfaces the candidates; v2
  adjudicates them. Not yet wired — the v1 output is enough to land the
  primitive.

### Invocation

`scripts/redteam-multi.sh preflight` — probe each auditor (binary, auth,
agent definition, opencode `write=true` per §6.1(a)). Costs no model
tokens; prints an availability table.

`scripts/redteam-multi.sh run --ticket M1-NNN [--auditors <id,...>]`
`[--prepare-only]` — render the prompt, dispatch each available auditor
headlessly, write per-auditor verdicts, run cross-examination.
`--prepare-only` renders prompts without spawning auditors (free pipeline
verification).

`/redteam-multi <target>` — the skill-wrapper form (`.agents/skills/
redteam-multi/SKILL.md`); resolves the diff range per
`.claude/skills/redteam/SKILL.md` §1 and dispatches `scripts/redteam-multi.sh`.

### opencode-boundary note (authoring deferred)

This section was added by an opencode session. opencode cannot edit
`.claude/**` per `AGENTS.md`, so the parallel `.claude/skills/redteam-
multi/SKILL.md` wrapper is NOT yet authored — when Claude Code next
resumes, it should add that file mirroring the `.agents/skills/redteam-
multi/SKILL.md` wrapper that points back here. Until then, `/redteam-
multi` is invocable only on non-Claude harnesses (and directly via
`scripts/redteam-multi.sh` on any host).

## 8. Fork-side smoke checklist

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
3. ✅ End-to-end gate RUN and passed (2026-07-19, glm-4.7 via the Z.AI plan) on
   a toy diff carrying a gutted assertion and a commented-out authorization
   check. The agent read the prompt file, read the diff and
   `engineering-rules-verbatim.md`, wrote the verdict to the exact path, and
   replied in the exact three-line format; `git status --porcelain` showed no
   contamination. It cited the right rules (§8 test integrity, §2 no
   workarounds) — the gate is substantively working, not just wired up.
   The prompt is a positional argument: there is no stdin flag, and `-f/--file`
   attaches files rather than supplying the prompt.

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

Kimi Code (steps 6a–6d VERIFIED on 0.29.0, 2026-07-22):
6a. ✅ Auth — `kimi provider list` prints a `Default model:` line. `kimi doctor`
    is NOT a substitute (§6.3).
6b. ✅ Skill discovery — a same-named skill planted in both `.claude/skills/`
    and `.agents/skills/` of a scratch repo was discovered, and
    `--skills-dir <empty-dir>` suppressed both copies. If a fork wants kimi to
    RUN the workflow skills (rather than only serve as a red-team auditor),
    pin `--skills-dir .agents/skills` instead of an empty directory, and treat
    §6.1(b)'s collision warning as applying here too.
6c. ✅ Headless write — `kimi -p` created the file it was asked for with no
    permission flag, and exits non-zero on failure.
6d. ✅ End-to-end gate RUN and passed (2026-07-22, kimi-k3) through the `kimi`
    auditor slot of `scripts/redteam-multi.sh`, on the merged M1-668 diff:
    the auditor read the rendered prompt, the threat model and the diff,
    wrote a format-conformant `RED-TEAM VERDICT: CLEAN` to the exact
    prompt-supplied path, and returned the required four-line chat reply;
    `porcelain-kimi.txt` matched the pre-run state, so no contamination.
    Evidence: `docs/plan/m1/redteam-multi/M1-668-2026-07-22/`. Wall clock
    ~4 min for a 12 KB diff at the configured default effort — well inside
    the wrapper's `timeout 900`, but a large diff will need watching.

Any tool:
7. Drive `/m1-tick start` (via the `.agents/skills/m1-tick` wrapper) on a
   draft ticket to the first grounding menu; confirm the §4 printed-menu form
   renders and blocks.
8. After your setup and first runs: `git diff --stat -- .claude CLAUDE.md`
   MUST be empty. `.claude/` is Claude Code's config and is never modified by
   other tools' runs.
