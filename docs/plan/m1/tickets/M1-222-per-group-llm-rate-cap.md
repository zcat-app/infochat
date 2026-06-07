---
id: M1-222
title: "Per-group LLM rate cap (D47) on the group chat path"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by:
  - M1-183
remediates: M1-183
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - per-group COMMAND rate cap (D47, security.md §Rate limiting "Per-group command rate") — the sibling unimplemented per-group bucket; a separate follow-up, NOT this ticket
  - per-user LLM rate cap (LlmRateCap, M1-183) — already correct; do not touch tryAcquire on LlmRateCap or its config knob
  - per-group REPLY rate cap (RateCapBucket.tryAcquireGroupReply, M1-112) — already implemented; do not change its behavior, cap, or call site in GroupApprovalCheck
  - enabling group-scope /summary or /retry — both are DM-only today (SummaryCommandHandler.resolveScopeId / RetryCommandHandler.resolveUserId reject group scope); this ticket does NOT add group variants of those commands, it only gates the live group chat path
  - DigestRetryService and the digest cooldown — periodic digests are system-initiated and MUST NOT consume the per-group LLM bucket; the digest path is untouched
acceptance:
  - "Per docs/spec/security.md §Rate limiting — \"**Per-group LLM rate (D47)** — a separate sub-bucket per approved group bounding LLM-triggering operations ... across all group members.\" — RateCapBucket gains tryAcquireGroupLlm(UUID groupId) keyed on groups.id; a named RateCapBucketTest exhausts the per-group LLM bucket for one group id and asserts the next acquire returns false"
  - "Per docs/spec/security.md §Rate limiting — \"The per-user LLM cap fires first; the per-group cap is the backstop for groups with many active members.\" — a named InboundRouterTest issues a group chat-mode message that passes the per-user LlmRateCap but exhausts the per-group LLM bucket, and asserts the request is rejected and ChatAgent.handle is NOT called"
  - "Per docs/design/04-security.md §4.9 (Action on overflow: Fixed \"group LLM rate limit\" reply) — overflow sends the fixed BundleKeys.GROUP_LLM_RATE_LIMIT reply (new key; entries added to both en.properties and cs.properties); the named InboundRouterTest above asserts the reply body equals that bundle entry"
  - "Per docs/design/04-security.md §4.9 — the per-group LLM cap is profile-driven via infochat.ratelimit.group-llm-per-15min with the table values laptop=5, vps=10, pi=3, remote-llm=10 (and a %test value mirroring the group-reply pattern); application.properties carries the base + per-profile overrides"
  - "Per docs/spec/security.md §Rate limiting — \"Periodic digests do NOT count against user-initiated per-group LLM budget.\" — tryAcquireGroupLlm is consulted ONLY on the user-initiated group chat path in InboundRouter; the digest path (DigestRetryService) is not modified and never consumes the bucket — a named test or the InboundRouterTest above pins that the bucket is touched only on chat dispatch"
  - "A named RateCapBucketTest asserts the per-group LLM bucket refills over its 15-minute window (mirroring the existing group-reply refill assertion) and is swept by evictIdleBuckets"
  - "Existing RateCapBucket test-seam constructor call sites (the five 4-arg new RateCapBucket(...) sites in RateCapBucketTest and the 6-arg site in GroupApprovalCheckTest) stay green — the new group-LLM config is added without breaking those signatures (default the new fields in the existing constructors; add a dedicated seam for the group-LLM tests)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs:
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-222: Per-group LLM rate cap (D47) on the group chat path

## Context

The `/redteam M1-183` audit (2026-06-07, see
`docs/plan/m1/redteam/M1-183-2026-06-07.md`) raised a DOS-medium finding:
the per-group LLM rate cap promised by `docs/spec/security.md` §Rate
limiting and tabulated in `docs/design/04-security.md` §4.9 is
unimplemented. Every LLM-triggering surface routes through the per-user
`LlmRateCap` only; the group chat-mode dispatch
(`InboundRouter.java`, the chat branch that calls
`llmRateCap.tryAcquire(actorId)` then `chatAgent.handle`) has no
per-group aggregate ceiling. N registered members of one approved group
can each spend their own per-user budget, so aggregate LLM cost for a
single group scales linearly with member count — exactly the "groups
with many active members" exhaustion case the per-group backstop was
specified to bound.

The finding was explicitly out of scope for M1-183 (per-user coverage
for `/summary` and `/retry`); the fix belongs beside the existing
per-group REPLY cap (`RateCapBucket.tryAcquireGroupReply`, M1-112), not
in the per-user `LlmRateCap`. This ticket adds the LLM sub-bucket. The
group chat path is the only live group surface that reaches the LLM —
`/summary` and `/retry` re-rolls are DM-only — so the fix is a single
gate plus the bucket machinery.

## Acceptance

See frontmatter — each separable spec/design promise transcribed and
paired with a named test pinning the rejection behavior and the absence
of the LLM call. Note the overflow action differs from the per-group
REPLY cap: reply-rate overflow is a silent drop; LLM-rate overflow is a
**fixed reply** (`group LLM rate limit`) per design §4.9.

## Out-of-scope

See frontmatter. The sibling per-group COMMAND rate cap is also
unimplemented but is its own follow-up. The per-user `LlmRateCap`
(M1-183) and the per-group REPLY cap (M1-112) are correct and untouched.
This ticket does not enable group-scope `/summary` or `/retry`. The
digest path must never consume the per-group LLM bucket.

## Notes

- Spec: `docs/spec/security.md` §Rate limiting "Per-group LLM rate (D47)".
  Design table: `docs/design/04-security.md` §4.9 "Per-group rate caps
  (D47)" — laptop 5 / vps 10 / pi 3 / remote-llm 10 per 15 min, overflow
  = fixed "group LLM rate limit" reply, approved-only.
- Wiring location: `InboundRouter` chat-mode branch. After the per-user
  `llmRateCap.tryAcquire(actorId)` passes and `resolveChatScopeId`
  returns the scope UUID, gate the group-scope case on
  `rateCapBucket.tryAcquireGroupLlm(scopeId)` BEFORE `chatAgent.handle`.
  The per-user cap fires first by construction (it is already the outer
  check). The "approved only" constraint is satisfied by position —
  pending/rejected groups are stopped at step 3.5 and never reach chat
  dispatch. The DM case (scopeId == user UUID) must NOT consult the
  group bucket.
- Implementation shape mirrors `tryAcquireGroupReply`: a second
  `ConcurrentHashMap<UUID, Bucket>` (or a keyed variant), profile-driven
  cap + 15-min window config, sharing the existing `evictIdleBuckets`
  sweep (the predicate is key-shape independent).
- Constructor sweep (see the engineering call-site rule): the
  package-private test-seam constructors of `RateCapBucket` are used by
  five 4-arg sites in `RateCapBucketTest` and one 6-arg site in
  `GroupApprovalCheckTest`. Add the group-LLM config without breaking
  those — default the new fields in the existing constructors and add a
  dedicated seam for the group-LLM tests. `GroupApprovalCheckTest` is
  not in this ticket's `files_scope`; keep its constructor call
  compiling (do not change the 6-arg signature it depends on).
- A new bundle key needs parity in both `en.properties` and
  `cs.properties` (the loader expects the key in both; `group.pending` /
  `group.rejected` already exist in both as the precedent).
