---
name: relocated-controls-dont-travel
description: "A diff that reroutes or replaces a code path silently drops that path's INCIDENTAL controls — sanitize calls, audit rows, the unit they operate on, and tests that pinned a security property. Root cause behind the 3-and-5-round redteam patterns; codified as engineering-rules §10."
metadata: 
  type: feedback
---

When a diff **reroutes, replaces, or re-parameterizes an existing code path**,
that path's *incidental* obligations do not travel with its stated purpose.

M1-694 (reference case, 3 redteam rounds): `ClusterBlockRenderer`'s stated job
was "render a cluster block". Its unstated jobs were sanitizing the
upstream-controlled headline, thereby emitting the `LLM_OUTPUT_SANITIZED` audit
row that is the operator's only detector, at a granularity of one author's
title per call. Swapping in `DigestRenderer` did the stated job and none of the
others. All five findings across three rounds were that one mistake:

- r1: the relocated `sanitize` call and its audit row
- r1: retargeted assertions had been pinning D46 non-leakage, not just "the
  reply shows the right post"
- r2: the spec amendment overclaimed the sibling (`--full`) form
- r3: **granularity** — the restored `sanitize` was fed a whole cluster's
  multi-post prose instead of one title, so a redaction span could cross post
  boundaries

**Why:** the displaced control usually has NO test of its own (that is *why* it
is invisible), so the suite stays green; and the diff matches its ticket, so
the reviewer APPROVEs. Only adversarial review finds it — one instance per
round, which is what makes these tickets run long.

**How to apply:** at ticket-authoring time (not implementation), open the path
being displaced and enumerate what it does that the new path's job description
omits — sanitize/redaction, audit emissions, authorization checks, validation,
the **unit** each operates on, and which retargeted assertions pin a *security*
property. Put them in `acceptance:` so `ACCEPTANCE-CHECK` enforces them.
Codified in `docs/process/engineering-rules-verbatim.md` §10 and `start.md`
step 1b (commit `8b2c41d1`).

Corollaries:

- A **test-breakage census is not a behavior census** — that one is only about
  finding *tests*. See [[census-enumerate-by-invocation]].
- **Remediate the class, not the instance.** Rounds 2 and 3 were both findings
  in the *previous* round's fix.
- A reviewer WARN saying "worth an assertion in a future round" is how holes
  get created; close it in-round. M1-694 round 2 did, and it caught a real
  one-token mutation that would otherwise have shipped group-worded copy into
  DMs.

Related: [[reviewer-is-conformance-not-correctness]],
[[redteam-remediation-needs-reaudit]].
