---
name: redteam-multi
description: Multi-auditor red-team — fan the same rendered red-team prompt through several independent coding-agent CLIs (claude, opencode, codex, kimi) headlessly and cross-examine their findings. Single-auditor findings are surfaced for falsification. Use for high-stakes security_relevant tickets, milestone boundaries, or pre-release where a single auditor's systematic blind spot is unacceptable. Invoke as `/redteam-multi <ticket-id | milestone <name> | id-range <a..b> | release <tag>>`.
---

This is a sibling of `/redteam`, not a flag on it. `/redteam` runs ONE
adversary; `/redteam-multi` runs SEVERAL and cross-examines them. The two
share the diff-range algorithm, the verdict-file format, and the threat
model, but diverge at dispatch: `/redteam` spawns a single in-session
subagent, `/redteam-multi` spawns N headless processes for attribution.

The procedure is single-sourced in
[`docs/process/harness-mapping.md`](../../../docs/process/harness-mapping.md)
§7 "Cross-harness gate". The wrapper script is
[`scripts/redteam-multi.sh`](../../../scripts/redteam-multi.sh); the
cross-examination parser is
[`scripts/redteam-multi-cross.py`](../../../scripts/redteam-multi-cross.py).

## Preconditions

Same as `/redteam` (the wrapper consumes the same prompt template and
threat model):

- `docs/spec/security.md` exists — refuse if not.
- `docs/process/redteam-prompt.md` exists — refuse if not.
- The target resolves to a diff range per
  [`.claude/skills/redteam/SKILL.md`](../../.claude/skills/redteam/SKILL.md)
  §1 (read it; the algorithm is not duplicated here).
- At least one of claude / opencode / codex / kimi is installed and
  authenticated on this host. `scripts/redteam-multi.sh preflight` is the
  cheap (no tokens) way to check. opencode and kimi may need PATH entries
  the non-interactive shell lacks (`~/.opencode/bin`, `~/.kimi-code/bin`);
  a missing one silently drops that auditor.

## Steps

1. **Resolve the diff range** exactly as
   [`.claude/skills/redteam/SKILL.md`](../../.claude/skills/redteam/SKILL.md)
   §1 prescribes. The wrapper script handles the MERGED `<ticket-id>`
   form; for the `--in-progress` branch form, resolve it yourself (run
   `/redteam <id> --in-progress` mentally, capture the working-tree-vs-
   fork-point diff) and pass the result via `--diff` or `--base`/`--head`.

2. **Preflight.** Run `scripts/redteam-multi.sh preflight`. It probes each
   auditor: binary on PATH, auth valid, agent definition resolvable where the
   harness reads one at all (codex and kimi read none — harness-mapping §6.2,
   §6.3), and (for opencode) the resolved `write=true` per §6.1(a).
   Costs no model tokens. If fewer than two auditors are AVAILABLE, surface
   that to the user before proceeding — cross-examination is meaningless
   without an independent refuter (the script will still run a single
   auditor and emit a degenerate cross-exam, but the user should opt in).

3. **Render and dispatch.** Run
   `scripts/redteam-multi.sh run --ticket <id>` (or `--diff`, or
   `--base`/`--head`). The script renders the prompt once per available
   auditor, dispatches each headlessly with `timeout 900`, writes a
   per-auditor verdict under `docs/plan/m1/redteam-multi/<slug>-<date>[-rN]/`,
   and emits `cross-examination.md` (or skips it on a single-auditor run).

   `--prepare-only` renders prompts without spawning auditors — the
   cheapest end-to-end pipeline verification. Use it the first time you
   wire this up on a new host.

4. **Read back the cross-examination report.** Open
   `cross-examination.md` in the evidence directory. The summary line
   tells you how many findings were corroborated (≥2 auditors) and how
   many were single-auditor (falsification candidates). The side-by-side
   table shows which auditor flagged what severity. The per-cluster
   detail includes the verbatim PROMISE/GAP for each finding.

5. **Resolve each finding by ASKING — never by assuming.** Do NOT
   auto-escalate, and do NOT disposition anything yourself. The
   cross-examination report is the surface for a design discussion, not
   an automatic rework trigger and not a licence to decide. For EVERY
   finding — corroborated, single-auditor and out-of-model alike — ask
   the user to choose: (1) fix within the current ticket's scope,
   (2) defer to a new follow-up ticket, (3) accept as a stated residual,
   (4) raise a spec amendment. Attach your recommendation to the
   question; it does not replace the question. Ask BEFORE drafting a
   ticket, allocating an ID, editing the operand ticket beyond its audit
   record, touching a source file, or writing a `disposition.md`.
   "It is pre-existing", "`out_of_scope` fences it" and "the engineering
   rules say file a follow-up" are inputs to that decision, never
   substitutes for it. Same rule and same wording as
   [`.claude/skills/redteam/SKILL.md`](../../.claude/skills/redteam/SKILL.md)
   §8 — keep the two halves in lockstep.

6. **Commit the durable subset of the evidence directory.** In
   `docs/plan/m1/redteam-multi/<slug>-<date>[-rN]/`, the durable audit
   record is exactly `verdict-*.txt`, `cross-examination.md`, and any
   hand-written `disposition.md`. Commit those alongside
   `docs/plan/m1/redteam/` as a `process:` commit (or fold them into the
   ticket commit if the audit ran on that ticket's branch — same
   lifecycle-path exemption as single-auditor redteam). The rest —
   `prompt-*.txt`, `reply-*.txt`, `inv-*.txt`, `diff.patch`,
   `porcelain-*.txt`, `preflight.txt` — is regenerable scratch,
   `.gitignored` (M1-684); do not commit it. **Timed-out-auditor
   carve-out:** when an auditor times out (exit 124) its `verdict-*.txt`
   is the `UNAVAILABLE` stub, so its raw `reply-*.txt` may carry a
   conclusion the verdict does not — capture that conclusion in
   `disposition.md` (or the cross-examination report) before the reply is
   dropped (M1-672 r2 kimi is the worked precedent).

## Harness bindings (non-Claude substitutions)

The wrapper script invokes the gate agents headlessly per harness-mapping
§3. Wherever §7 names a Claude Code primitive, apply the binding for YOUR
tool:

- "spawn the threat-actor subagent" → mapping §2 (fresh-context gate
  agent; §3 for the headless form). The wrapper already does this — the
  skill need not.
- any user menu → mapping §4 (printed numbered menu, stop, wait).
- contamination check → mapping §6 (mandatory on non-Claude harnesses).
  The wrapper does this per-auditor.

Everything else — diff assembly, prompt rendering via
`scripts/m1-render-prompt.py`, the verdict file under
`docs/plan/m1/redteam-multi/`, the cross-examination parser — is plain
bash/python; run it as written.

Never modify anything under `.claude/`.

## opencode-boundary note

This skill was authored by an opencode session (opencode cannot edit
`.claude/**` per `AGENTS.md`). The parallel Claude Code wrapper
`.claude/skills/redteam-multi/SKILL.md` was authored 2026-07-20, so
`/redteam-multi` is now invocable as a slash command on Claude Code. Both
skill files are thin harness wrappers; the procedure is single-sourced in
`docs/process/harness-mapping.md` §7 — keep them pointers, not forks. The
`scripts/redteam-multi.sh` wrapper works on any host regardless.
