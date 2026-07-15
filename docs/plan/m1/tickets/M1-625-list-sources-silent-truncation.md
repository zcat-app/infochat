---
id: M1-625
title: "/list-sources (and /get-sources) silently truncates at ~20 of N sources with no pagination hint"
status: done
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
clarity_check:
  date: 2026-07-15
  verdict: WARN
  warnings:
    - >-
      SECURITY-FLAG-CONSISTENT: security_relevant:false while the touched handler class
      (ListSourcesCommandHandler) also holds the audited admin --all privileged-enumeration
      path (LIST_SOURCES_ALL audit write, admin-only gate). The rendering change should not
      touch that logic; implementer must confirm the audit write and gate ordering are
      unchanged by the diff, and the reviewer should check that adjacency.
  blockers: []
reviews:
  - round: 1
    date: 2026-07-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 185
      removed: 17
out_of_scope:
  - >-
    Changing which sources a scope sees (subscription/world model). Only the LISTING's
    completeness/pagination affordance changes.
acceptance:
  - >-
    /list-sources over a scope with more sources than the per-message limit either
    paginates (with a page indicator like the /saved and /audit commands already use)
    or clearly states "…and N more — <how to see them>". A user must be able to
    discover and reach every source in their scope.
  - >-
    /get-sources (the documented alias) behaves identically.
  - >-
    A test pins that a source count above the single-message limit yields a
    completeness indicator, not a silent cut.
---

Found in the 2026-07-14/15 isolated live test: /list-sources over 104 sources rendered
~20 (alphabetical, ending at "ClaudeDevs") then stopped — no page number, no "N more",
no hint to page. Other list commands (/saved, /audit, /list-groups) already show
"page X/Y"; /list-sources should match.
