---
id: M1-194
title: "EligiblePostQuery SQL LIMIT + chat tool result budgets"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetPostTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/RecallMemoryTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the duplicate-row inflation feeding this query — M1-179 fixes the source (cross-tick UID dedup); this ticket bounds the read side regardless
  - statement_timeout / pid registration on these connections — M1-193's
  - SearchPostsTool's result shape and its ready_at JSON mislabel — the mislabel is UNIFIED.md T21's (mediums batch, not yet filed)
  - summary clustering/prose logic — only how many rows reach it changes
acceptance:
  - "EligiblePostQuery's main query carries a SQL-side bound: a named test seeds more eligible posts than clusterCap and asserts the rows materialized in Java never exceed the cap (today the query has no LIMIT and selects body for EVERY eligible row, then truncates via subList in Java — full-table materialization of bodies on every /summary)"
  - "The cap-excess reporting is NOT regressed: the Result still carries the true total / excluded counts that compose the cap-excess message — a named test asserts total and excluded remain correct when the SQL bound is in place (the audit explicitly warns the naive LIMIT would silently break this)"
  - "Chat tool results carry a byte budget: getPost truncates the returned body to a documented cap with an explicit truncation marker, and recallMemory bounds its aggregate result size — named tests assert oversized seeded content comes back bounded (today getPost reinjects the full post body into the prompt unbounded)"
  - "Existing summary handler behavior (cluster ordering, restriction handling, profile-driven caps: 200 default / 500 remote-llm) is unchanged — existing tests stay green"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-194: EligiblePostQuery SQL LIMIT + chat tool result budgets

## Context

EligiblePostQuery's main query has no LIMIT and selects `body` for every
eligible row; the cap is applied afterwards in Java
(`all.subList(0, clusterCap)` at EligiblePostQuery.java:141-145, computing
`total`/`excluded` for the cap-excess message — the `:275 LIMIT` belongs to
the separate topActiveFollowedTags query). Every /summary materializes every
eligible post body in heap before discarding all but clusterCap (200
default, 500 remote-llm) — and this compounds directly with the duplicate
inflation from the missing cross-tick UID dedup (M1-179): a stable feed's
duplicates all become READY rows this query loads. Related (gpt P2): chat
tool results are reinjected into the prompt unbounded — GetPostTool returns
the full body, RecallMemoryTool returns up to 50 entries with no byte cap.
Unified findings P3 (high-perf) + gpt P2, `deep-code-review/v2/UNIFIED.md`
§2/§3 T18.

## Acceptance

See frontmatter. The cap-excess counts (total/excluded) are the explicit
do-not-regress surface.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T18 under `deep-code-review/v2/` (gpt P1/P2).
- The audit's suggested shape — `LIMIT clusterCap` plus a `COUNT(*)` for
  the excess note — is Tier B (unverified): a window-function count or
  `LIMIT cap+1` probe also satisfy the acceptance; whatever is chosen, the
  excluded-count behavior is pinned by the second acceptance item.
- Tool budget values are design-tier; document the chosen caps where the
  tool registry documents its other bounds.
