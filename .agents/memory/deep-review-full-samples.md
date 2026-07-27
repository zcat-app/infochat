---
name: deep-review-full-samples
description: "/deep-code-review full USED to be a sampling audit that read clean because nobody looked; rebuilt 2026-07-27 to partition every file into verified-complete-cover slices on a cheap model. Historical: the sampling shape found 8 findings where the partitioned shape found ~75."
metadata:
  type: feedback
---

**Fixed 2026-07-27 — `full` is now exhaustive by construction.** It partitions
every reviewable file into slices (≤22 production / ≤40 test per slice),
verifies the partition is a complete cover with `comm` BEFORE spawning, refuses
to spawn if the check fails, and verifies every slice report landed afterward.
Reviewers run on the fast/cheap tier (`sonnet` on Claude Code) — that is what
makes the exhaustive form affordable, and affordability is what previously
pushed runs into the sampling shape. Model selection lives in the skill
procedure, never in `model:` frontmatter, because frontmatter binds Claude Code
alone and would let the Claude and opencode/kimi paths diverge invisibly.

**The history, kept because the failure mode generalizes.** `full` was
originally one agent per *module*. A 492-file module cannot be read in one
context, so each agent read a *cross-section* and surfaced ~1–3 findings, with
different runs drawing different samples from the same latent defect pool.

**Why this is dangerous, not merely imprecise:** the report does not read like
a sample — it reads like a verdict, so a clean run manufactures confidence in
a codebase nobody actually audited. On 2026-06-27 the SAME code produced
**8 findings / 0 highs** from a sampling `full` run and **~75 findings / 2
highs** when the file list was exhaustively partitioned (a stranded-RAW-post
correctness bug, a dead group `/retry`, a future partition build-break).

**How to apply:**
- `full` now delivers the partitioned form itself — just run it. Read its
  `Coverage:` line: `complete cover verified` means every reviewable file was
  in some agent's inventory; `INCOMPLETE` means it degraded and the report is a
  sample again, so treat it as one.
- **Never let a partial cover be reported as `full`.** That is the whole
  defect: the report does not read like a sample, it reads like a verdict, so a
  clean run manufactures confidence in code nobody audited.
- Watch the resources glob when editing the skill: `.sql` under
  `src/test/resources` is missed by a java/kt/properties/json/xml glob, and any
  new glob must be added to the cover check too or the cover silently stops
  being complete.
- **The cheap model is deliberate, not a compromise** (decision 2026-07-19,
  implemented 2026-07-27). Do not "fix" the apparent inconsistency with the
  gates' strong-model rule by raising it — that rule exists because a weak
  *gate* APPROVEs, and this skill emits no verdict. Raise coverage, not model.
- When a later run surfaces higher severities than the last, check git history
  first — the items usually predate recent commits and are latent, not
  regressions.
- For defects that are *deterministic* (doc cross-references, bundle-key
  parity), encode them as CI checks/tests rather than relying on probabilistic
  rediscovery. See [[doc-only-edits-skip-verify]] for the doc-fix path and
  [[reviewer-is-conformance-not-correctness]] for what the ticket-gate reviewer
  does and does not prove.
