---
id: M1-194
title: "EligiblePostQuery SQL LIMIT + chat tool result budgets"
status: done
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
  - docs/spec/commands.md §Command catalogue
  - docs/spec/commands.md §Chat mode
decision_refs: []
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
revisions:
  - date: 2026-06-07
    reason: clarity-fail rework
    summary: |
      - body: added "Authorized test changes" section resolving the
        TEST-CHANGES-AUTHORIZED blocker — test_plan.modifies kept, but scoped
        to adding new named test methods to EligiblePostQueryIT.java only; no
        pre-existing test method's assertions, seeds, or names change.
      - Notes: struck "LIMIT cap+1 probe" as a satisfying implementation
        shape — EligiblePostQueryIT.capDropsOldestAndReportsExcludedCount
        asserts exact totals (totalBeforeCap=8, excludedCount=3 with cap 5),
        which a standalone cap+1 probe cannot report; acceptance items 2+4
        force an exact-count shape.
      - spec_refs: §Conversation control (covers /stop, /clear, /retry —
        clarity warning: wrong section) replaced by §Command catalogue (the
        /summary cluster-cap paragraph) and §Chat mode (chat tool surface).
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 449
      removed: 31
escalations:
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL — test_plan.modifies lists
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary,
      but the ticket body has no "Authorized test changes" section
      enumerating which pre-existing test class(es) will be modified, what
      they currently assert, and the new expected behavior. Acceptance
      item 4 ("existing tests stay green") contradicts the modifies field.
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

## Authorized test changes

`test_plan.modifies` lists the summary test directory because the new named
tests for acceptance items 1 and 2 are added as new test methods inside the
existing `EligiblePostQueryIT.java` (next to its seed helpers, which they
may reuse) — a file modification, not a test-behavior modification. No
pre-existing test method's assertions, seeds, or names change. In
particular `capDropsOldestAndReportsExcludedCount` (seeds 8 posts against
cap 5; asserts `posts().size()==5`, `totalBeforeCap()==8`,
`excludedCount()==3`, `profileCap()==5`) stays green verbatim — it is part
of the do-not-regress surface of acceptance item 2.

## Notes

- Source: `UNIFIED.md` §3 T18 under `deep-code-review/v2/` (gpt P1/P2).
- The audit's suggested shape — `LIMIT clusterCap` plus a `COUNT(*)` for
  the excess note — is Tier B (unverified): a window-function count
  (`COUNT(*) OVER ()`) also satisfies the acceptance. A standalone
  `LIMIT cap+1` probe does NOT: it can only establish "total ≥ cap+1",
  while the existing `EligiblePostQueryIT.capDropsOldestAndReportsExcludedCount`
  asserts exact totals (`totalBeforeCap()==8` with cap 5), so acceptance
  items 2 and 4 jointly force an exact-count shape.
- Tool budget values are design-tier; document the chosen caps where the
  tool registry documents its other bounds.
