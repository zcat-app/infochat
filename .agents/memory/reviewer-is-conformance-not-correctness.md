---
name: reviewer-is-conformance-not-correctness
description: "Measured 2026-07-19 — the code-reviewer gate is a conformance checker, not a correctness gate; a review APPROVE is weak evidence a diff is correct."
metadata: 
  type: project
  modified: 2026-07-19T09:52:07.517Z
---

Full M1 corpus census (670 reviewed tickets, 801 review rounds, 335 redteam
audits), run 2026-07-19:

- Review APPROVEs **88%** of tickets first-pass. Of its 83 REWORK/MANUAL
  rounds, **61% are `files_scope`/`files_budget` bookkeeping**; most of the
  rest are unused imports, missing annotations, and literal acceptance-grep
  mismatches.
- Genuine security/correctness catches by review: **~3 of 83** rejections
  (M1-066 audit-before-effect ordering, M1-061 Flyway version collision,
  M1-583 a deleted eligibility block). All three share a shape: a concrete,
  bounded question with a checkable answer — not open-ended bug hunting.
- Redteam returns FINDINGS on **30%** of audits (102/335; 212 findings, 3
  critical, 43 high). In **13** cases it found a real vulnerability in a diff
  review had already APPROVEd.

**Why:** the two gates do measurably different jobs. Every reviewer check
(SCOPE-DRIFT, TEST-INTEGRITY, OUT-OF-SCOPE, NEGATIVE-SPACE, ACCEPTANCE,
SPEC-CONFORMANCE) is a conformance check, and ACCEPTANCE explicitly takes the
test log as its oracle — so a diff whose tests assert at the wrong layer
passes everything. See [[handler-input-not-always-normalized]] for the
sharpest instance (review APPROVEd 2 of 3 Unicode bypasses and got the
fenced-code case backwards in round 4).

**How to apply:** do not read a review APPROVE as "this diff is correct" —
read it as "this diff matches its ticket". For adversarial correctness, rely
on `/redteam`, which now runs *ahead* of review (`66b8a5c0`). When asking the
reviewer for correctness work, give it a bounded named-artifact demand
("name a mutation this test would catch"), never an open invitation to look
for problems — that is the form of its three successes and the design of
M1-661's ASSERTION-ADEQUACY check.
