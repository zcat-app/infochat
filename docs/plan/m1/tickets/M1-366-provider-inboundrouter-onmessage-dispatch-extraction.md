---
id: M1-366
title: "provider: extract the InboundRouter.onMessage post-LLM dispatch/commit section into named methods"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any behaviour change in the slash/chat dispatch, rate-cap, or post-delivery commit flow — this is a pure structural extraction; observable behaviour is identical.
  - The pre-LLM intake block (already reads cleanly) — untouched.
  - The documented public/subclassable test seams (handle/dispatchChat) — preserved; new helpers are private.
acceptance:
  - "The onMessage post-LLM section (slash-vs-chat dispatch with rate caps, plus the post-delivery PendingCommit + auto-compress-notice handling) is extracted into named private methods (e.g. dispatchSlashOrChat returning a sealed DispatchResult, and a runPostDeliveryCommit helper), reducing onMessage's post-LLM body to a flat top-to-bottom read."
  - "No public/documented test seam is removed; the existing InboundRouter tests pass unchanged (behaviour is identical), demonstrating the extraction is behaviour-preserving."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main (this is a behaviour-preserving refactor)
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-366: InboundRouter.onMessage post-LLM extraction

## Context

Deep-review v6 finding **opus-47 `07-module-infochat-provider.md` F4** (low,
MAINTAINABILITY-RULES-DRIFT). The post-LLM section of `onMessage` is 4-deep
nested with a nullable `body` set across many branches, a separately-checked
nullable `handle`, and two `RuntimeException` catches — hard to read locally in
an already-460-line method. **Verified 2026-06-14:** the cited control-flow shape
is present at `InboundRouter.java` (the post-LLM block beginning ~line 702 in the
report's line base).

The reviewer explicitly flags this as a §1 "don't improve adjacent code inline"
risk and recommends a dedicated ticket — hence this standalone, lowest-priority
refactor ticket rather than folding it into a behaviour-changing one. opus-48
did not raise it.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Lowest priority of the v6 ticket set; schedule after the behaviour-changing
  provider tickets (M1-363/364/365) land to avoid churning the same file twice.
- The extraction must be strictly behaviour-preserving — the green pre-existing
  InboundRouter suite is the proof.
