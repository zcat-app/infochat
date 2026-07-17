---
id: M1-627
title: "/approve-group and /reject-group: clarify which group id token to pass"
status: done
created: 2026-07-15
last_updated: 2026-07-17
blocked_by: []
reviews:
  - round: 1
    date: 2026-07-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 20
      removed: 12
clarity_check:
  date: 2026-07-17
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1: the either/or fix leaves a design fork open; the accept-both-forms branch extends accepted input beyond commands.md line 1166-1168 and inherits cross-adapter upstream_group_id ambiguity. Labeling /list-groups is the lower-risk, spec-aligned branch."
    - "ACCEPTANCE-RUNNABLE item 2: the worked did-you-mean <uuid> example implies a DB lookup undefined when the token matches groups on more than one adapter; a generic hint that does not resolve a specific UUID avoids the ambiguity."
    - "FILES-BUDGET-PLAUSIBLE: files_budget 5 is 2-3 short of a full DB-lookup implementation of both items; a labeling + generic-hint design keeps it within budget."
    - "COMPLEXITY-RISK-CALIBRATED: risk low is a soft call given both handlers are bot-admin-gated and audit-logged and one is confirm-gated destructive."
    - "SECURITY-FLAG-CONSISTENT: security_relevant false while the diff lands inside two admin-tier audit-logged handlers; consistent with false only because the chosen design touches neither the admin gate nor target resolution."
files_budget: 5
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The group approval flow itself (D47). Only the id ergonomics / error message change.
acceptance:
  - >-
    An admin can approve/reject a group using the identifier /list-groups displays,
    without ambiguity between the DB UUID and the adapter-internal group number.
    Either /list-groups labels the token to pass, or the commands accept both forms.
  - >-
    Passing a plausible-but-wrong token yields a helpful message (e.g. "did you mean
    <uuid> from /list-groups?") rather than a bare "No group with id X is known".
---

Found in the 2026-07-14/15 isolated live test: /list-groups shows a UUID
(73558b10-…); /approve-group accepts that UUID, but the adapter-internal
upstream_group_id (an int like `1`) is a tempting wrong token and yields "No group with
id `1` is known to the bot." Small clarity fix.
