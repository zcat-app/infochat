---
name: audit-the-instrument-not-just-the-diff
description: An eval/golden set inherits its world's sampling frame — challenge the frame BEFORE optimizing against it; gates and reviews never audit it for you
metadata:
  type: feedback
---

# Audit the instrument, not just the diff

An evaluation instrument (golden set, benchmark fixture, frozen corpus) is
itself an artifact under test: it inherits the sampling frame of the world
it was built from. Every gate downstream of it — eval runners, sign tests,
review verdicts, re-baselines — measures CONFORMANCE to the instrument, and
will happily certify optimization against a biased frame. Challenge the
frame FIRST, before any measurement campaign:

- Which instance/world/deployment does the sample represent, and what does
  the product's actual distribution look like elsewhere?
- Is the "mainstream" of the instrument the mainstream of the product, or
  an artifact of which sources/feeds happened to be frozen?
- Can the instrument even represent the claims being gated on it (does the
  ground-truth density exist), or is it mathematically incapable?

Cost of learning it late (2026-08-28): an entire retrieval campaign
(M1-930..947: baseline, corrections, characterization, counterfactuals,
multilingual shadow eval) measured against a tech-instance world (ai/cyber/
crypto ≈ 90% of the frozen corpus) while a live second instance (fam) ran
the same architecture over global general-news (economy/world/health/sports
mainstream, ai/cyber as tail, real cs-speaking users). The representativeness
question was raised by the user, not by any agent-side gate — the user
should be able to EXPECT the challenge to come from the agent. Sibling of
[[campaign-harness-must-disclose-excluded-paths]] (disclose what the harness
omits) — this one is: disclose, and challenge, what world the harness samples.
