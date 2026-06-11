---
id: M1-306
title: "Provider mediums: edit-interval floor, /retry counter order, chat-tool caps, group row reuse"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/ListSavesTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalCheck.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
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
  - "U-66c: per-tag validation batches to one SELECT instead of one per tag; existing behaviour pinned by tests."
  - "U-67: Outcome.Approved carries the groups.id the check already read, and the router's step-4.1 re-read is dropped (today the groups row is read three times per approved-group inbound message); existing router tests stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-306: Provider mediums: edit-interval floor, /retry counter order, chat-tool caps, group row reuse

## Context

Deep-review v5 verified **U-32** (MEDIUM), **U-42** (PARTIAL→LOW-MED
residual), **U-66** (LOW ×3), **U-67** (LOW)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3/§4; sources `fable-5/01#F2` +
`deepseek/01#F2` + `gpt-55#M-14` (U-32), `opus-48/07#F1` (U-42),
`fable-5/07#F7` + `gpt-55#L-01/L-02/L-03` (U-66), `fable-5/07#F8` +
`gpt-55#L-04` (U-67) — gitignored; all load-bearing facts inlined; anchors
verified 2026-06-11: StageProgressNotifier floor-only javadoc at :45-47;
RetryCommandHandler increment at :194 with the rate-cap-order rationale
comment at :186-188; ListSavesTool WINDOW_MAX unclamped at :49;
GroupApprovalCheck.Outcome.Approved an empty record at :66)."

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
- U-67's record change fans into Outcome consumers — grep construction and
  match sites including tests before finalizing (recorded call-site rule).
- Coordination: M1-297 (notifier terminal), M1-303 (/retry renderer),
  M1-307 (router dead constant) overlap files; check worktrees at start.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-306-*.md
```
