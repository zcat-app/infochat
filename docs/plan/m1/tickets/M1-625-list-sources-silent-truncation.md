---
id: M1-625
title: "/list-sources (and /get-sources) silently truncates at ~20 of N sources with no pagination hint"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
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
