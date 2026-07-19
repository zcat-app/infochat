---
name: redteam
description: Run an adversarial security review — a fresh-context threat-actor subagent reads the project's threat model (docs/spec/security.md) and the diff, then flags gaps between what the model promises and what the diff delivers, bucketed by category and severity. Use when the user asks for a "red-team", "security review", "threat-model audit", "adversarial review", or "vulnerability check"; natural at milestone boundaries, on tickets flagged security_relevant: true, and before tagging a release. Invoke as `/redteam <ticket-id | milestone <name> | id-range <a..b> | release <tag>>`.
---

This is a compatibility wrapper for non-Claude coding agents. The procedure
is single-sourced at `.claude/skills/redteam/SKILL.md`.

1. Read `.claude/skills/redteam/SKILL.md` and follow it verbatim.
2. Wherever it names a Claude Code primitive, apply the binding for YOUR
   tool from `docs/process/harness-mapping.md`:
   - "spawn the threat-actor subagent" → mapping §2 (fresh-context gate
     agent; §3 for the headless form), then the §6 contamination check. The
     auditor's independence — no design notes, no implementer rationale in
     its context — is the entire value of the gate; never run it inline.
   - any user menu → mapping §4 (printed numbered menu, stop, wait)
3. Everything else — diff assembly, prompt rendering via
   `scripts/m1-render-prompt.py`, the verdict file under
   `docs/plan/m1/redteam/` — is plain bash/python; run it as written.

Never modify anything under `.claude/`.
