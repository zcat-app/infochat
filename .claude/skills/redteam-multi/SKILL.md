---
name: redteam-multi
description: Multi-auditor red-team — fan the same rendered red-team prompt through several independent coding-agent CLIs (claude, opencode, codex, kimi) headlessly and cross-examine their findings. Single-auditor findings are surfaced for falsification. Use for high-stakes security_relevant tickets, milestone boundaries, or pre-release where a single auditor's systematic blind spot is unacceptable. Invoke as `/redteam-multi <ticket-id | milestone <name> | id-range <a..b> | release <tag>>`.
---

# /redteam-multi — multi-auditor adversarial review

This skill is the Claude Code procedure. It is a **sibling of
[`/redteam`](../redteam/SKILL.md), not a flag on it**: `/redteam` spawns ONE
in-session threat-actor subagent; `/redteam-multi` spawns N headless auditor
processes (claude, opencode, codex, kimi) and cross-examines their verdicts. The
point of fanning out is that a single auditor's blind spots are systematic — a
finding only one model reports is either a real gap the others missed or that
model's false positive, and only a falsification pass tells those apart. The
two skills share the diff-range algorithm, the verdict-file format, and the
threat model; they diverge only at dispatch.

The **procedure is single-sourced in
[`docs/process/harness-mapping.md`](../../../docs/process/harness-mapping.md)
§7 "Cross-harness gate"**; this file is the Claude-Code operating wrapper, not
a re-derivation. The wrapper script is
[`scripts/redteam-multi.sh`](../../../scripts/redteam-multi.sh); the
cross-examination parser is
[`scripts/redteam-multi-cross.py`](../../../scripts/redteam-multi-cross.py). If
this skill and §7 ever disagree, §7 wins — flag the drift and stop.

## Preconditions

Same as [`/redteam`](../redteam/SKILL.md) (the wrapper consumes the same prompt
template and threat model):

- `docs/spec/security.md` and `docs/process/redteam-prompt.md` exist — refuse if
  either is absent.
- The target resolves to a diff range per [`../redteam/SKILL.md`](../redteam/SKILL.md)
  §1 — that algorithm is single-sourced there and is NOT duplicated here.
- At least one of claude / opencode / codex / kimi is installed and
  authenticated. `scripts/redteam-multi.sh preflight` is the zero-token check.
  Fewer than two AVAILABLE means no independent refuter, so cross-examination is
  degenerate — surface that and let the user opt in before running a
  single-auditor pass.

## Auditor availability on this host

The script invokes every auditor by **bare name** and does NOT set the
opencode Claude-skills kill-switch itself. On this host opencode and kimi are
both installed but on neither's non-interactive PATH (their `~/.bashrc` lines,
including the `opencode()` kill-switch function, do not reach the script's
invocation). Set all of it at the call so the child processes inherit it:

```
OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=1 PATH="$HOME/.opencode/bin:$HOME/.kimi-code/bin:$PATH"
```

Prefix every `scripts/redteam-multi.sh` call — `preflight` and `run` — with that
env. Each missing PATH entry silently drops an auditor; without the kill-switch
opencode could resolve a `.claude/skills/` skill of the same name and run the
raw Claude procedure with no harness translation (harness-mapping §6.1(b)).
`~/.local/bin/wopencode` bakes the env var in, but the script execs `opencode`,
not `wopencode`, so it is not a substitute. kimi needs no such variable — the
script passes it `--skills-dir` aimed at an empty directory, which suppresses
skill discovery from both trees outright (harness-mapping §6.3).

## Steps

1. **Resolve the diff range** exactly per [`../redteam/SKILL.md`](../redteam/SKILL.md)
   §1. The script handles the MERGED `<ticket-id>` form via `--ticket <id>`. For
   the **`--in-progress` branch form** — uncommitted working-tree state, the
   normal `/m1-tick run` gate condition — resolve the diff yourself and pass it
   as a patch file: `git add -N <untracked-files>` first so new files appear,
   then `git diff $(git merge-base main HEAD) > .scratch/redteam-multi-<id>.patch`
   and pass `--diff .scratch/redteam-multi-<id>.patch`. (Not `git diff main`: in
   a worktree pinned behind a moved `main` it drags every since-landed ticket in
   as phantom changes.)

2. **Preflight** (no tokens): `<env> scripts/redteam-multi.sh preflight`. Confirm
   ≥2 AVAILABLE; if not, surface it per the precondition above.

3. **Render and dispatch**: `<env> scripts/redteam-multi.sh run --ticket <id>`
   (or `--diff <patch>`, or `--base <a> --head <b>`). The script renders the
   prompt once per available auditor, dispatches each headlessly (`timeout 900`),
   writes a per-auditor verdict, and emits `cross-examination.md` under
   `docs/plan/m1/redteam-multi/<slug>-<date>[-rN]/`. `--prepare-only` renders
   without spawning — the cheapest end-to-end pipeline check when wiring up a new
   host.

4. **Read back the cross-examination report** — `cross-examination.md` in the
   evidence directory. The summary line reports how many findings were
   corroborated (≥2 auditors) versus single-auditor (falsification candidates);
   the side-by-side table shows which auditor flagged what severity; the
   per-cluster detail carries the verbatim PROMISE/GAP.

5. **Surface findings to the user — do NOT auto-escalate.** The report is the
   surface for a design discussion, not an automatic rework trigger — exactly as
   `/redteam` treats single-auditor findings (see [`../redteam/SKILL.md`](../redteam/SKILL.md)
   §8). The user decides which findings become remediation tickets, spec
   amendments, or accepted residual risk. Findings reach the lifecycle workflow
   only when the user runs `/m1-tick escalate <id> redteam-finding`.

6. **Commit the durable subset of the evidence directory.** In
   `docs/plan/m1/redteam-multi/<slug>-<date>[-rN]/`, the durable audit record is
   exactly `verdict-*.txt` (the per-auditor verdicts), `cross-examination.md`,
   and any hand-written `disposition.md`. Commit those as a `process:` commit, or
   fold them into the ticket commit when the audit ran on that ticket's branch
   (the reviewer's lifecycle-path exemption already covers the directory). The
   rest — `prompt-*.txt`, `reply-*.txt`, `inv-*.txt`, `diff.patch`,
   `porcelain-*.txt`, `preflight.txt` — is regenerable scratch, `.gitignored`
   (M1-684); do not commit it. **Timed-out-auditor carve-out:** when an auditor
   times out (exit 124) its `verdict-*.txt` is the `UNAVAILABLE` stub, so its raw
   `reply-*.txt` may carry a conclusion the verdict does not — capture that
   conclusion in `disposition.md` (or the cross-examination report) before the
   reply is dropped (M1-672 r2 kimi is the worked precedent).

## Cross-cutting rules this skill must obey

- **This is the Claude Code half of a two-harness pair.** The non-Claude half is
  [`../../../.agents/skills/redteam-multi/SKILL.md`](../../../.agents/skills/redteam-multi/SKILL.md).
  Both stay thin because the procedure lives in harness-mapping §7 — keep them
  pointers, not forks.
- **No skill recursion.** The script invokes the `threat-actor` GATE AGENT, never
  this skill or `/redteam`. `REDTEAM_MULTI_DEPTH=1` guards codex; the
  kill-switch above guards opencode; the empty `--skills-dir` guards kimi; the
  Claude `threat-actor` agent declares no skill tool, so recursion is
  structurally impossible there.
- **Advisory only.** Never modify code from this skill; it produces evidence and
  a report, nothing more.
