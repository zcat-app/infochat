---
name: deep-review-full-samples
description: "/deep-code-review full is a SAMPLING audit — 'all lows' means 'this sample surfaced lows', never 'no highs exist'. For real coverage, partition every file into small per-slice agents; that form found ~75 findings where sampling found 8."
metadata:
  type: feedback
---

`/deep-code-review full` is a probabilistic, sampling-based multi-agent audit,
not an exhaustive one. Each reviewer agent reads a *cross-section* of its
target (a 405-file module is never fully deep-read in one run) and surfaces
~1–3 findings. Different runs draw different samples from the same latent
defect pool.

**Why this is dangerous, not merely imprecise:** the report does not read like
a sample — it reads like a verdict, so a clean run manufactures confidence in
a codebase nobody actually audited. On 2026-06-27 the SAME code produced
**8 findings / 0 highs** from a sampling `full` run and **~75 findings / 2
highs** when the file list was exhaustively partitioned (a stranded-RAW-post
correctness bug, a dead group `/retry`, a future partition build-break). Also:
when a later run surfaces higher severities than the last, check git history
first — the items usually predate recent commits and are latent, not
regressions.

**How to apply:**
- Read any `full` result as "this sample surfaced X", and say so when
  reporting it. Never as "the module is clean".
- **For real coverage, do NOT re-run `full`** — it samples by design. Partition
  every reviewable file (`git ls-files` across all modules: main + test +
  migrations) into slices of ≤~22 production / ≤~40 test files, so the
  template's "read every file in this list" is literally achievable. Verify
  the partition is a complete cover (`comm` the slice-union against the full
  set — zero gaps, zero dups) BEFORE spawning, and verify every report landed
  after. Watch the test glob: `.sql` under `src/test/resources` is missed by a
  java/kt/properties/json/xml glob.
- **Run the exhaustive form on a cheap, fast model** (decision 2026-07-19).
  Cost is what makes teams reach for the sampling form; a cheaper model makes
  the complete-cover partition affordable, which is the form that actually
  works. Do not spend the saving on cheaper *sampling* — that keeps the false
  confidence and lowers the find rate.
- For defects that are *deterministic* (doc cross-references, bundle-key
  parity), encode them as CI checks/tests rather than relying on probabilistic
  rediscovery. See [[doc-only-edits-skip-verify]] for the doc-fix path and
  [[reviewer-is-conformance-not-correctness]] for what the ticket-gate reviewer
  does and does not prove.
