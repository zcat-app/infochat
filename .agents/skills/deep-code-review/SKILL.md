---
name: deep-code-review
description: Run a deep, honest, senior-engineer review of a target — uncommitted changes, a ticket diff, a commit range, a Maven module, an arbitrary path, the cross-module architecture surface, or "full" (architecture + every module in parallel with a consolidated summary). Writes comprehensive markdown reports under .reviews/deep-review/; advisory only — independent of /m1-tick gates and broader than /redteam's threat-model-only lens. Invoke as `/deep-code-review uncommitted | ticket <id> | range <a>..<b> | module <name> | architecture | full | path <path>`.
---

This is a compatibility wrapper for non-Claude coding agents. The procedure
is single-sourced at `.claude/skills/deep-code-review/SKILL.md`.

1. Read `.claude/skills/deep-code-review/SKILL.md` and follow it verbatim.
2. Wherever it names a Claude Code primitive, apply the binding for YOUR
   tool from `docs/process/harness-mapping.md`:
   - "spawn the senior-developer / review-synthesizer subagent" → mapping §2
     (fresh-context gate agent; §3 for the headless form), then the §6
     contamination check
   - `full`-mode parallel fan-out → run the per-target reviews sequentially
     if your tool cannot run agents concurrently; the reports are
     independent, so ordering does not matter
3. Everything else — prompt rendering via `scripts/m1-render-prompt.py`,
   report files under `.reviews/deep-review/` — is plain bash/python; run it
   as written.

Never modify anything under `.claude/`.
