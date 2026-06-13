---
id: M1-306
title: "Provider mediums: edit-interval floor, /retry counter order, chat-tool caps"
status: done
created: 2026-06-11
last_updated: 2026-06-13
blocked_by: []
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/SummaryAnchorRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/ListSavesTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The /stop terminal-state work (M1-297) — StageProgressNotifier is shared; this ticket touches only its interval computation.
  - The rate-cap token acquire ORDER in /retry — deliberate and documented in-code (rate-cap tokens self-heal, retry slots don't); only the counter mutation order changes (see Notes).
  - Collector-side post.title bounding (upstream half of U-66) — backlogged.
acceptance:
  - "U-32: StageProgressNotifier uses max(systemFloor, adapter.capabilities().minEditInterval()) for its edit-coalescing interval (today only the system floor at ~:123, while the javadoc claims per-adapter min 'is not exposed' — false, it is one call away; SimpleX declares 600ms); the javadoc is corrected; a named test with an adapter declaring a 600ms minEditInterval asserts the larger floor wins."
  - "U-42 residual: /retry reads-then-checks the cap BEFORE incrementing (today incrementAndGetRetryCount at ~:194 mutates first, so the anchor counter grows unboundedly past the cap and an LLM rate-cap token is spent on a known-exhausted retry); after the fix an at-cap /retry consumes neither a retry slot nor further counter growth; named tests pin counter-stays-at-cap and the cap-exhausted reply."
  - "U-66a: SearchPostsTool enforces the aggregate output byte cap its sibling tools have (LLM tool-call arguments and outputs are a trust boundary); a named test."
  - "U-66b: ListSavesTool clamps a model-supplied window to WINDOW_MAX (today :49 parses Duration without clamping, so the model can request an arbitrary window); a named test passes an oversized window and asserts the clamp."
  - "U-66c: per-tag validation batches to one SELECT instead of one per tag; a named test asserts that validating N tags issues a single tag-existence SELECT (not one per tag) and that an unknown tag in the batch is still rejected."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-13
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 607
      removed: 67
  - round: 1
    date: 2026-06-13
    verdict: OVERRIDE-APPROVE
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    override_ref: 0
overrides:
  - date: 2026-06-13
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — the diff creates
      docs/plan/m1/tickets/M1-323-provider-group-row-reuse.md, a path outside
      files_scope and not among the three lifecycle-exempt paths (STATUS.md, the
      operand M1-306 ticket, a redteam verdict). Under the rule as written, a
      diffed file outside files_scope is an automatic SCOPE-DRIFT FAIL.
    user_justification: |
      The M1-323 file is the mechanical byproduct of the approved budget-breach
      refine that split U-67 out of M1-306 (documented in M1-306's own revisions:
      block, snapshot rehomed_to: M1-323); it is not a developer scope expansion
      and a code-rework round cannot remove it (M1-323 must exist to be runnable).
      The file is a refine artifact analogous to a lifecycle path — the same
      mechanical SCOPE-DRIFT situation resolved by override on M1-305. All
      substantive checks (TEST-INTEGRITY, OUT-OF-SCOPE, NEGATIVE-SPACE,
      ACCEPTANCE) PASS and the full reactor build is green.
escalations:
  - date: 2026-06-13
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — files_scope path gap surfaced during implementation. U-42 requires a
      non-mutating read of the in-memory retry counter before incrementing
      (acceptance: "reads-then-checks the cap BEFORE incrementing"; "an at-cap
      /retry consumes neither a retry slot nor further counter growth").
      SummaryAnchorRepository exposes only incrementAndGetRetryCount (no peek),
      and SummaryAnchorRepository.java is outside files_scope. No in-scope
      alternative: increment-then-check always grows the counter, and reordering
      the increment ahead of the LLM-token acquire violates the documented
      out_of_scope token-first ordering. (Resolved by round-1 refine: added the
      one path to files_scope.)
  - date: 2026-06-13
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — files_budget exceeded by U-67's call-site sweep. The five other items
      (U-32, U-42, U-66a/b/c) consume 10 files. U-67 changes the Outcome.Approved
      record signature (empty -> carries groups.id) AND drops InboundRouter's
      step-4.1 lookupGroupId re-read; both fan out to every construction/match
      site: 3 production files (GroupApprovalCheck, GroupApprovalService,
      InboundRouter) + ~10 test files (RecordingGroupApprovalCheck,
      NoopGroupApprovalCheck, GroupApprovalCheckTest, GroupApprovalServiceTest,
      GroupAuthorizationRoundtripIT, CountingDispatchDataSource,
      InboundRouterAcquisitionCountTest, InboundRouterChatModeIT,
      InboundRouterIntakeOrderingTest, InboundRouterProbationOrderingTest). Total
      ~23 files vs files_budget 16. Two further concerns: (1) preserving the
      removed-but-approved-group drop (today done by step-4.1's removed_at filter)
      forces moving removed_at handling into the approval layer's dispatch, a
      change to a timing-oracle protection (docs/spec/security.md) on a ticket
      marked security_relevant: false; (2) the vanished-group router test pins the
      OLD lookupGroupId-empty mechanism and must be reworked, straining U-67's
      "existing router tests stay green" clause.
  - date: 2026-06-13
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — the implementation/test code is fully in scope
      and within budget (9 non-exempt infochat-provider files vs files_budget 16),
      but the diff creates docs/plan/m1/tickets/M1-323-provider-group-row-reuse.md,
      a path outside files_scope and not among the three lifecycle-exempt paths
      (STATUS.md, the operand M1-306 ticket, a redteam verdict). Under the rule as
      written, a diffed file outside files_scope is an automatic SCOPE-DRIFT FAIL.
      All other checks PASS (TEST-INTEGRITY, OUT-OF-SCOPE, NEGATIVE-SPACE,
      ACCEPTANCE all PASS; full reactor BUILD SUCCESS, 158 provider tests green).
      UNCERTAINTY: the blocker is mechanical, not a code defect — the M1-323 file
      is the artifact of the budget-breach refine that split U-67 out of M1-306
      (documented in M1-306 revisions: snapshot rehomed_to: M1-323). A code-rework
      round cannot resolve it: the split is correct and approved, and the new
      ticket file must exist for M1-323 to be runnable. Resolution is a
      user/workflow scope decision (override, as on M1-305; or refine files_scope
      to include docs/plan/m1/tickets/), so this is MANUAL not REWORK.
revisions:
  - date: 2026-06-13
    reason: |
      budget-breach refine (round 1). U-42's read-then-check-before-increment
      needs a non-mutating peek on the in-memory retry counter, which lives in
      SummaryAnchorRepository (not previously in files_scope). Added that one
      production path. Also tightened U-66c to name the SELECT-count assertion
      (clarity WARN [5]). files_budget unchanged at 16 (now 8 production files +
      4 test dirs).
    snapshot:
      files_scope_added:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/SummaryAnchorRepository.java
      u66c_before: "U-66c: per-tag validation batches to one SELECT instead of one per tag; existing behaviour pinned by tests."
  - date: 2026-06-13
    reason: |
      budget-breach refine (round 1). U-67 (group-row reuse) removed from this
      ticket: its Outcome.Approved signature change + lookupGroupId removal fan
      out to ~13 files (3 production + ~10 test doubles/ITs), pushing the ticket
      to ~23 files against files_budget 16, and it moves a removed_at
      timing-oracle protection into the approval layer (security-relevant).
      Re-homed as standalone ticket M1-323 with correct sizing and
      security_relevant: true. M1-306 now ships the five remaining items (U-32,
      U-42, U-66a/b/c), ~10 files, within budget. Dropped the U-67 files_scope
      entries (GroupApprovalCheck, GroupApprovalService, InboundRouter, group
      test dir) and the group test_plan.modifies entry.
    snapshot:
      acceptance_removed: "U-67: Outcome.Approved carries the groups.id the check already read, and the router's step-4.1 re-read is dropped (today the groups row is read three times per approved-group inbound message); existing router tests stay green."
      files_scope_removed:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalCheck.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalService.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/group
      rehomed_to: M1-323
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-13
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE [5] U-66c: 'existing behaviour pinned by tests' is ambiguous — clarify whether this requires adding new tests (and if so, name the assertion) or relies on the existing test suite staying green."
    - "SECURITY-FLAG-CONSISTENT: U-66a and U-66b explicitly gate model-supplied inputs at a stated trust boundary; consider setting security_relevant: true to trigger the post-implementation redteam pass."
  blockers: []
---

# M1-306: Provider mediums: edit-interval floor, /retry counter order, chat-tool caps

## Context

Deep-review v5 verified **U-32** (MEDIUM), **U-42** (PARTIAL→LOW-MED
residual), **U-66** (LOW ×3)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3/§4; sources `fable-5/01#F2` +
`deepseek/01#F2` + `gpt-55#M-14` (U-32), `opus-48/07#F1` (U-42),
`fable-5/07#F7` + `gpt-55#L-01/L-02/L-03` (U-66) — gitignored; all
load-bearing facts inlined; anchors verified 2026-06-11:
StageProgressNotifier floor-only javadoc at :45-47; RetryCommandHandler
increment at :194 with the rate-cap-order rationale comment at :186-188;
ListSavesTool WINDOW_MAX unclamped at :49).

**U-67** (group-row reuse, LOW) was split out to standalone ticket
**M1-323** during a budget-breach refine: its Outcome.Approved signature
change fans out to ~13 files and touches a security-relevant timing-oracle
protection, so it needs its own sizing and redteam pass.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- U-42 carries a report correction (§6.5): opus-48 missed the in-code
  rationale for acquiring the rate-cap token first — that order is
  DELIBERATE (tokens self-heal; retry slots don't) and stays. Only the
  increment-before-check on the anchor counter and the
  token-spent-when-cap-already-exhausted half are defects. Read the
  comment at :186-188 before touching anything.
- Coordination: M1-297 (notifier terminal), M1-303 (/retry renderer),
  M1-307 (router dead constant) overlap files; check worktrees at start.
- U-67 (group-row reuse) moved to M1-323 — see Context. Do NOT touch
  GroupApprovalCheck / GroupApprovalService / InboundRouter in this ticket.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-306-*.md
```
