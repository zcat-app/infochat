---
name: pre-registration-free-variable
description: "A pre-registered threshold is only pinned if its arithmetic has NO free variable — if any input is unknown until the data arrives, the decision point moves with the data and the pre-registration is decorative."
metadata:
  type: feedback
---

Before locking a threshold, **write it as a formula, list every input, and mark
which ones are known today**. If any input is unknown until the data arrives,
the threshold is *not* pre-registered — the decision point moves with the data,
which is exactly the refit pre-registration exists to prevent. Percentages hide
this; absolute counts do not.

**Why:** Track A's gold panel pre-registered "if the human audit disagrees on
more than 10% of the sampled unanimous cases, escalate", and worked the
arithmetic as "at n=68 a 20% sample is ~14 cases, so it fires at 2 or more
disagreements." Recorded 2026-08-02 as settled, before any case was drawn — it
looked airtight. It was not: the sample is 20% of the **unanimous** cases, not
of 68, and the worked example silently assumed 100% unanimity. Recomputed
across plausible unanimity rates, it fires at 2 down to ~66% unanimity and at
**1** below that — straddling the range three frontier models would plausibly
land in. The trigger point depended on the panel's own output.

**How to apply:** Two fixes, in order of preference. (1) **Shrink the
population until the rule is invariant** — narrowing the judge set to its 28
production-shaped cases (R4) made the sample ≤6 for *every* unanimity rate, and
`1/6 = 16.7% > 10%` holds unconditionally, so "one disagreement escalates" is
true with no free variable left. (2) Failing that, **state the trigger as an
absolute count** ("2 disagreements"), never as a percentage of a survivor set.

Note the second-order win in (1): the stricter trigger was also the *cheaper*
one to obey (28 cases to re-adjudicate, not 68). A gate whose false negative is
expensive should be easy to trip and cheap to satisfy; check whether narrowing
buys both.

**Where this fires next:** Track A's still-unwritten per-task "what gap counts
as real" thresholds are the same shape. Write them against a fixed, stated n —
not as a percentage of whatever survives scoring. See
[[bench-box-and-eval-project]] for where that work lives.
