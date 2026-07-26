---
name: gate-subagent-audits-wrong-tree-on-relative-paths
description: "A gate subagent (reviewer / threat-actor) handed relative paths can resolve them against the orchestrating session's cwd — NOT the per-ticket worktree being audited — and return a verdict against the wrong tree, with no visible contamination because the stray artifact lands in a gitignored target/. The line-number fingerprint in the verdict body is the tell."
metadata:
  type: feedback
---

A gate subagent (code-reviewer, threat-actor, plan-writer) Reads its inputs
from paths the rendered prompt names. When the orchestrating session runs in
the primary checkout but the audited work lives in a per-ticket worktree
(`.claude/worktrees/<id>`), a **relative** path in the stub prompt or in a
rendered `*_FILE_PATH` placeholder resolves against the session's cwd — the
primary — not the worktree. The subagent then reads the primary's
(main-version) files, audits the wrong diff, and writes its verdict to the
primary's gitignored `target/`. The §6 `git status --porcelain` contamination
check PASSES (target/ is gitignored), so the failure is silent. Hit on M1-690
(2026-07-25): the redteam subagent returned a FINDINGS verdict citing
`ChatPromptBuilder.java:38 "Answer any question the user asks"` — a line/quote
matching the MAIN version, not the worktree's reframed template where that
string spans lines 41-42. The verdict was auditing the wrong tree.

**The tell.** The line-number fingerprint in the verdict body. If a cited
`file:line` or quoted string does not match the diff's working-tree version
(check with `git diff $(git merge-base main HEAD) -- <file>`), the subagent
read the wrong tree — discard the verdict and re-run.

**Mitigation (binding).** Every path handed to a gate subagent — the stub
prompt's prompt-file path AND every `*_FILE_PATH` / `VERDICT_FILE_PATH` /
`@file` placeholder substituted by `scripts/m1-render-prompt.py` — MUST be
absolute whenever the session is not parked inside the audited worktree.
Re-render with absolute paths (`DIFF_FILE_PATH=$WT/target/...`, etc.) and
re-spawn with an absolute prompt path. On M1-690 this produced a CLEAN verdict
whose file landed in the worktree's `target/` as expected.

**Scope.** opencode Task-tool subagents inherit the session cwd (the
opencode-specific manifestation is documented in
`docs/process/harness-mapping.md` §6.1(d), alongside the other three
"silently breaks the gates" opencode gotchas). Claude Code subagents inherit
the worktree cwd correctly and are unaffected. The headless §3 recipes
(codex `exec`, kimi `-p`, `opencode run`) take a positional prompt and have
the SAME exposure when the prompt carries relative paths — use absolute paths
there too. This is a workflow-procedure fact, not purely a harness quirk:
the absolute-path requirement is what makes a gate auditable across all
harnesses.
