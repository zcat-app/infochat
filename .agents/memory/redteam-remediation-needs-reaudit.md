---
name: redteam-remediation-needs-reaudit
description: "An in-branch redteam remediation invalidates the audit it answers — re-audit the new diff, and explicitly tell the auditor it MAY return CLEAN or it will manufacture findings to justify itself."
metadata: 
  type: feedback
---

Learned driving M1-651 (2026-07-18), which took **three** redteam audits before landing:
audit 1 → 2 low findings → fix in-branch → audit 2 → confirmed those 2 CLOSED but raised
2 NEW low findings *against the remediation itself* → fix properly (incl. a spec
amendment) → audit 3 → **CLEAN**.

Two transferable lessons:

1. **Never commit on the strength of a superseded audit.** After remediating in-branch,
   the diff the auditor read no longer exists. Re-audit the new diff, and say in the
   prompt that this IS a re-audit, list the prior findings, and instruct: "do NOT assume
   they are closed because remediation was attempted, and do NOT re-report them if they
   genuinely are closed." Otherwise you get either false-closure or duplicate findings.
2. **Explicitly authorize a CLEAN verdict.** Adding "Be willing to return CLEAN if the
   diff genuinely warrants it. An audit that manufactures a finding to justify itself is
   worse than no audit." is what produced the honest CLEAN on round 3. Without it, a
   third audit under momentum tends to invent a finding.

**Why:** remediation → new surface → new failure modes. The loop terminates, but only if
each pass audits the *current* diff and is allowed to say "nothing here." Left implicit,
audits ratchet indefinitely and the ticket never lands.

**How to apply:** budget for N+1 audits on a `security_relevant` ticket whose first audit
returns FINDINGS. Each remediation round also needs its own `mvn verify` + review round —
M1-651 needed `round_cap` raised 2→3 via `escalate → refine`, and each growth round needs
the must-shrink mandate (user-accepted redteam remediation) cited **in the round's commit
message**, since the reviewer verifies that citation against git rather than trusting the
prompt. Persist every audit separately (`docs/plan/m1/redteam/<id>-<date>[-rN].md`).

Related: [[redteam-diffrange-refine-uncommitted-gap]] (all three audits needed the
manual working-tree diff workaround), [[investigation-ticket-flow-too-heavy]].
