---
id: M1-222
title: "Per-group LLM rate cap (D47) on the group chat path"
status: done
created: 2026-06-07
last_updated: 2026-06-07
blocked_by:
  - M1-183
remediates: M1-183
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/LlmRateCap.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - per-group COMMAND rate cap (D47, security.md §Rate limiting "Per-group command rate") — carve-out ABSORBED post-commit by user decision 2026-06-07 (redteam second-audit finding, docs/plan/m1/redteam/M1-222-2026-06-07-b.md): RateCapBucket.tryAcquireGroupCommand + InboundRouter step-6 slash gate + group.command_rate_limit bundle key landed in-branch before squash
  - per-user LLM rate cap (LlmRateCap, M1-183) — tryAcquire semantics, the sliding-window shape, and the config knob stay untouched; the ONLY permitted change is the additive refund(UUID) method (redteam 2026-06-07 finding 3), no signature changes to the existing constructor or tryAcquire
  - per-group REPLY rate cap (RateCapBucket.tryAcquireGroupReply, M1-112) — its cap, acquire semantics, and call site in GroupApprovalCheck stay untouched; the ONLY permitted behavior change is the shared eviction-threshold arithmetic fix (redteam 2026-06-07 finding 2), which corrects the same eviction-rebirth bug for the reply bucket as a side effect of the shared sweep
  - enabling group-scope /summary or post-anchor /retry — both are DM-only today (SummaryCommandHandler.resolveScopeId / RetryCommandHandler.resolveUserId reject group scope); this ticket does NOT add group variants of those commands; gating the ALREADY group-scope /retry --digest re-roll is IN scope (redteam 2026-06-07 finding 1)
  - DigestRetryService and the digest cooldown — periodic digests are system-initiated and MUST NOT consume the per-group LLM bucket; DigestRetryService and its lastRetryAt cooldown stay untouched — the /retry --digest cap gate lives in RetryCommandHandler, before retryDigest is invoked
acceptance:
  - "Per docs/spec/security.md §Rate limiting — \"**Per-group LLM rate (D47)** — a separate sub-bucket per approved group bounding LLM-triggering operations ... across all group members.\" — RateCapBucket gains tryAcquireGroupLlm(UUID groupId) keyed on groups.id; a named RateCapBucketTest exhausts the per-group LLM bucket for one group id and asserts the next acquire returns false"
  - "Per docs/spec/security.md §Rate limiting — \"The per-user LLM cap fires first; the per-group cap is the backstop for groups with many active members.\" — a named InboundRouterTest issues a group chat-mode message that passes the per-user LlmRateCap but exhausts the per-group LLM bucket, and asserts the request is rejected and ChatAgent.handle is NOT called"
  - "Per docs/design/04-security.md §4.9 (Action on overflow: Fixed \"group LLM rate limit\" reply) — overflow sends the fixed BundleKeys.GROUP_LLM_RATE_LIMIT reply (new key; entries added to both en.properties and cs.properties); the named InboundRouterTest above asserts the reply body equals that bundle entry"
  - "Per docs/design/04-security.md §4.9 — the per-group LLM cap is profile-driven via infochat.ratelimit.group-llm-per-15min with the table values laptop=5, vps=10, pi=3, remote-llm=10 (and a %test value mirroring the group-reply pattern); application.properties carries the base + per-profile overrides"
  - "Per docs/spec/security.md §Rate limiting — \"Periodic digests do NOT count against user-initiated per-group LLM budget.\" — tryAcquireGroupLlm is consulted ONLY on user-initiated paths (the InboundRouter chat branch and the /retry --digest gate in RetryCommandHandler); DigestRetryService is not modified and the system-initiated periodic digest path never consumes the bucket — named tests pin that the bucket is touched only on those two user-initiated dispatches"
  - "A named RateCapBucketTest asserts the per-group LLM bucket refills over its 15-minute window (mirroring the existing group-reply refill assertion) and is swept by evictIdleBuckets"
  - "Existing RateCapBucket test-seam constructor call sites (the five 4-arg new RateCapBucket(...) sites in RateCapBucketTest and the 6-arg site in GroupApprovalCheckTest) stay green — the new group-LLM config is added without breaking those signatures (default the new fields in the existing constructors; add a dedicated seam for the group-LLM tests)"
  - "Per docs/spec/security.md §Rate limiting — \"a separate sub-bucket per approved group bounding LLM-triggering operations (chat replies + on-demand `/summary` + `/retry` re-rolls) across all group members\" (redteam 2026-06-07 finding 1, DOS-medium) — RetryCommandHandler.handleDigestRetry gates the group-scope /retry --digest re-roll on rateCapBucket.tryAcquireGroupLlm(groupDbId) after the group-admin gate and before DigestRetryService.retryDigest; a named RetryDigestCommandTest exhausts the group-LLM bucket and asserts retryDigest is NOT called and the reply body equals the BundleKeys.GROUP_LLM_RATE_LIMIT bundle entry"
  - "Per docs/spec/security.md §Rate limiting — \"The per-user LLM cap fires first\" (redteam 2026-06-07 finding 1, per-user half) — handleDigestRetry draws a per-user llmRateCap.tryAcquire(actor.id) token BEFORE consulting the group bucket, rejecting with the existing BundleKeys.ERROR_CHAT_LLM_RATE_CAP reply (mirroring the DM /retry path at RetryCommandHandler line ~183); a named RetryDigestCommandTest exhausts the per-user cap and asserts retryDigest is NOT called"
  - "(redteam 2026-06-07 finding 3, DOS-low) LlmRateCap gains an additive refund(UUID userId) method that removes the caller's most recently recorded timestamp; BOTH group-LLM call sites (InboundRouter chat branch, RetryCommandHandler.handleDigestRetry) refund the per-user token when the group bucket rejects, so a group-cap rejection consumes no per-user budget; a named InboundRouterTest pins that after a group-cap rejection the same user's per-user budget is intact (tryAcquire still succeeds the full configured count)"
  - "(redteam 2026-06-07 finding 2, DOS-low) evictIdleBuckets sweeps each map under an effective threshold of max(eviction-threshold, that map's refill window), so an evicted-then-recreated bucket can never yield tokens faster than the configured refill schedule; a named RateCapBucketTest drains a group-LLM bucket, advances the clock past the eviction threshold but within the refill window, runs the sweep, and asserts the entry survives and the next acquire still returns false"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs:
  - D47
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
      files: 10
      added: 368
      removed: 12
  - round: 2
    date: 2026-06-07
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 903
      removed: 28
  - round: 2
    date: 2026-06-07
    verdict: OVERRIDE-APPROVE
    checks:
      # carried through from the overridden MANUAL verdict; scope_drift
      # remains FAIL as the reviewer reported it — the verdict alone
      # carries the override.
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 903
      removed: 28
    override_ref: 0
escalations:
  - date: 2026-06-07
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      /redteam M1-222 (2026-06-07): verdict FINDINGS — 3 findings (1 medium
      + 2 low, all DOS / rate-limit fix class). Medium: /retry --digest
      (RetryCommandHandler → DigestRetryService.retryDigest) never calls
      tryAcquireGroupLlm and never draws a per-user LlmRateCap token. Low 1:
      evictIdleBuckets (PT10M) recreates a drained bucket with a full
      allotment before the 15-min window elapses (~1.5× effective rate;
      inherited M1-112 pattern). Low 2: per-user LLM token is consumed
      before the group bucket check, so group-cap rejections burn the
      sender's global per-user budget. Full report:
      docs/plan/m1/redteam/M1-222-2026-06-07.md
  - date: 2026-06-07
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-triggered: about to touch
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java,
      which is outside files_scope. The 2026-06-07 redteam refine named
      RetryCommandHandlerTest as the digest-gate test home, but the
      /retry --digest branch is tested in RetryDigestCommandTest
      (M1-080c split); its setUp wires neither llmRateCap nor
      rateCapBucket, so the new handleDigestRetry gate breaks its four
      existing tests unless that file is modified.
      RetryCommandHandlerTest needs no change — its DM-path tests never
      reach handleDigestRetry. Net files-in-scope unchanged: one file
      swaps for another.)
  - date: 2026-06-07
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      VERDICT: MANUAL. SCOPE-DRIFT-CHECK: FAIL — "Mechanical must-shrink
      trip, and ONLY that. Round-2 stats (14 files, +903, -28) grew
      along ALL THREE dimensions vs round 1 (10 files, +368, -12), and
      the rule's sole exception ... is unsatisfiable here: round 1's
      verdict was APPROVE with zero REWORK items ... the growth is
      instead mandated by the ticket itself: the user's 2026-06-07
      redteam-finding refine ... A diff that satisfies the revised
      acceptance criteria is necessarily larger than the round-1 diff on
      all three dimensions." All other checks PASS (test_integrity,
      out_of_scope, negative_space, acceptance, spec-conformance).
      Reviewer's stated resolution options: (a) user overrides
      must-shrink for this round — "after which every other check is
      PASS and the diff is commit-ready as it stands"; or (b) move the
      three redteam fixes to a follow-up remediation ticket.
revisions:
  - date: 2026-06-07
    reason: |
      Budget-breach refine (files_scope correction): the redteam refine
      below named RetryCommandHandlerTest as the digest-gate test home,
      but the /retry --digest branch is tested in RetryDigestCommandTest
      (M1-080c split — "existing personal-retry tests remain in
      RetryCommandHandlerTest" per its javadoc), whose setUp wires
      neither llmRateCap nor rateCapBucket; the new handleDigestRetry
      gate breaks 2 of its 4 tests unless it is modified.
      RetryCommandHandlerTest needs no change (DM-path tests never
      reach handleDigestRetry). Swap the two files in files_scope and
      test_plan and rename the test class in the two finding-1
      acceptance items. Net scope size unchanged; files_budget 12
      untouched.
    snapshot:
      files_scope_test_entry: infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
      test_plan_entry: infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
      acceptance_finding1_group: |
        "... a named RetryCommandHandlerTest exhausts the group-LLM
        bucket and asserts retryDigest is NOT called and the reply body
        equals the BundleKeys.GROUP_LLM_RATE_LIMIT bundle entry"
      acceptance_finding1_peruser: |
        "... a named RetryCommandHandlerTest exhausts the per-user cap
        and asserts retryDigest is NOT called"
  - date: 2026-06-07
    reason: |
      Redteam-finding refine (user choice: fix all 3 findings of the
      2026-06-07 audit in-branch rather than spawn another remediation
      ticket — this ticket is itself the M1-183 remediation). Finding 1
      (DOS-medium): the group-scope /retry --digest re-roll bypasses
      both LLM caps; the gate goes in RetryCommandHandler before
      DigestRetryService.retryDigest. Finding 2 (DOS-low): eviction at
      PT10M recreates drained buckets full before the 15-min refill
      window elapses; per-map effective threshold becomes
      max(eviction-threshold, refill window). Finding 3 (DOS-low): a
      group-cap rejection burns the sender's per-user token; LlmRateCap
      gains an additive refund(UUID). files_scope +3 files
      (RetryCommandHandler, RetryCommandHandlerTest, LlmRateCap),
      files_budget 9 → 12, out_of_scope carve-outs narrowed to permit
      exactly these changes.
    snapshot:
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
      out_of_scope:
        - per-group COMMAND rate cap (D47, security.md §Rate limiting "Per-group command rate") — the sibling unimplemented per-group bucket; a separate follow-up, NOT this ticket
        - per-user LLM rate cap (LlmRateCap, M1-183) — already correct; do not touch tryAcquire on LlmRateCap or its config knob
        - per-group REPLY rate cap (RateCapBucket.tryAcquireGroupReply, M1-112) — already implemented; do not change its behavior, cap, or call site in GroupApprovalCheck
        - enabling group-scope /summary or /retry — both are DM-only today (SummaryCommandHandler.resolveScopeId / RetryCommandHandler.resolveUserId reject group scope); this ticket does NOT add group variants of those commands, it only gates the live group chat path
        - DigestRetryService and the digest cooldown — periodic digests are system-initiated and MUST NOT consume the per-group LLM bucket; the digest path is untouched
      acceptance_item_5: |
        "Per docs/spec/security.md §Rate limiting — \"Periodic digests do
        NOT count against user-initiated per-group LLM budget.\" —
        tryAcquireGroupLlm is consulted ONLY on the user-initiated group
        chat path in InboundRouter; the digest path (DigestRetryService)
        is not modified and never consumes the bucket — a named test or
        the InboundRouterTest above pins that the bucket is touched only
        on chat dispatch"
overrides:
  - date: 2026-06-07
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — "Mechanical must-shrink trip, and ONLY
      that. Round-2 stats (14 files, +903, -28) grew along ALL THREE
      dimensions vs round 1 (10 files, +368, -12), and the rule's sole
      exception — 'the round-(N-1) REWORK explicitly required a
      refactor ... cited in the round-N commit message' — is
      unsatisfiable here: round 1's verdict was APPROVE with zero
      REWORK items."
    user_justification: |
      User-directed in-branch redteam remediation (option 2 on the
      manual-verdict menu, 2026-06-07). The user had already ruled out
      a follow-up ticket ("this ticket is already a fix for the
      previous redteam finding, I will not create yet another bug-fix
      ticket — fix it within this ticket") and authorized the growth
      through the redteam-finding refine (files_budget 9 -> 12, +4
      acceptance items). A diff satisfying the revised acceptance is
      necessarily larger than round 1 on all three dimensions; the
      must-shrink arithmetic is the only failing check and every
      substantive check (test integrity, out-of-scope, negative space,
      acceptance, spec conformance) is PASS. Matches the M1-131
      precedent: in-branch redteam refine on an APPROVED ticket always
      trips must-shrink; resolution is override, not shrinking.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-07
    category: DOS
    severity: medium
    promise: |
      "**Per-group LLM rate (D47)** — a separate sub-bucket per approved group
      bounding LLM-triggering operations (chat replies + on-demand `/summary` +
      `/retry` re-rolls) across all group members." (security.md §Rate limiting)
    gap: |
      The sub-bucket is consulted at exactly one production call site — the
      chat-mode branch (InboundRouter.java:617-618). The only group-scope
      LLM-triggering re-roll that exists today, `/retry --digest`
      (RetryCommandHandler.java:375-407 → DigestRetryService.retryDigest),
      never calls tryAcquireGroupLlm — and it also never draws a per-user
      LlmRateCap token (its only bound is the group-admin gate plus a
      standalone per-group cooldown in lastRetryAt). The spec's bullet
      enumerates /retry re-rolls as inside the sub-bucket; the diff delivers
      the sub-bucket for chat replies only.
    repro: |
      In an approved group, a hostile or compromised group admin (1) has
      members drain the group-LLM bucket with 5 chat messages, then (2)
      issues /retry --digest each time the DigestRetryService cooldown
      elapses. Every re-roll runs full digest prose generation through the
      LLM while tryAcquireGroupLlm reports the group exhausted; no per-user
      LLM token is spent either.
    suggested_fix_class: rate-limit
  - date: 2026-06-07
    category: DOS
    severity: low
    promise: |
      "**Per-group LLM rate (D47)** — ... Profile-driven." (security.md
      §Rate limiting — the cap value is the operator-configured bound)
    gap: |
      Eviction-recreation outpaces the refill schedule: evictIdleBuckets
      removes a group-LLM bucket once idle past
      infochat.rate-cap.eviction-threshold (default PT10M), and
      tryAcquireGroupLlm recreates an evicted key with a FULL allotment.
      10-min threshold < 15-min refill window → a drained-then-idle bucket
      is reborn full at ~10 min; effective sustained rate ≈ 1.5× the
      configured budget. Inherited pattern — the pre-existing group-reply
      bucket (M1-112) has the same arithmetic.
    repro: |
      Drain the 5-token group-LLM bucket in a burst, keep the group
      chat-silent 10+ minutes, repeat after the sweep evicts the entry:
      ~5 LLM calls per ~10-11 minutes against a configured 5-per-15-min cap.
    suggested_fix_class: rate-limit
  - date: 2026-06-07
    category: DOS
    severity: low
    promise: |
      "The per-user LLM cap fires first; the per-group cap is the backstop
      for groups with many active members." (security.md §Rate limiting) —
      the backstop should reject without spending the user's budget.
    gap: |
      In InboundRouter.java:592-622, llmRateCap.tryAcquire(actorId) consumes
      a per-user token BEFORE the group bucket is checked; on group-cap
      rejection the sender's global per-user token stays spent (actor-keyed,
      so DM chat budget is reduced too). Contrast SummaryCommandHandler /
      RetryCommandHandler, which order checks so rejections consume nothing.
      Note: the implemented order IS the spec-mandated order; a refund
      requires an LlmRateCap API change that this ticket's out_of_scope
      forbids.
    repro: |
      A hostile member keeps the 5-token group bucket pinned empty with 5
      cheap chat messages per window; every other member's chat attempt in
      that group burns one of their own per-user LLM tokens for a fixed
      rate-limit reply, locking victims out of DM chat after a few attempts.
    suggested_fix_class: rate-limit
  - date: 2026-06-07
    category: DOS
    severity: low
    promise: |
      "Per-group command rate (D47) — a sub-bucket per approved group
      bounding total command volume from all members within a sliding
      window. Only meaningful for approved groups (pending/rejected groups
      never reach command dispatch). When the cap fires, the reply is a
      fixed 'this group has reached its command rate limit' message.
      Profile-driven." (security.md §Rate limiting)
    gap: |
      Second audit (commit 563563f). The D47 rate-cap family in
      RateCapBucket has the group-reply bucket (line 199) and the new
      group-LLM bucket (line 232), but the third promised sub-bucket —
      per-group command rate — has no implementation anywhere in the
      Provider: no tryAcquireGroupCommand-shaped method, no command-rate
      bundle key, no fixed "this group has reached its command rate limit"
      reply in infochat-provider/src/main. Residual exposure is bounded by
      the per-user transport cap (InboundRouter.java:359) and the per-group
      reply bucket (GroupApprovalCheck.java:122, silent drop), which is why
      this is low — but the spec's distinct fixed-reply overflow semantics
      for aggregate command volume are undelivered, and per-group DB-read
      cost from many members each under their personal transport cap is
      gated only by the reply bucket's silent-drop budget.
    repro: |
      An approved group with N registered, post-probation members; each
      sends slash commands (e.g. /summary, /list-sources --page k) at just
      under their individual transport cap. No per-group command bucket
      bounds the aggregate, so command dispatch (and the DB queries behind
      each) scales linearly with N until the per-group reply bucket starts
      silently dropping replies — at which point processing cost has
      already been paid per-inbound and the spec-promised fixed "command
      rate limit" reply is never produced.
    suggested_fix_class: rate-limit
redteam_audits:
  - date: 2026-06-07
    verdict: FINDINGS
    base: 867bf77 (fork point of m1/M1-222-per-group-llm-rate-cap)
    head: working tree (uncommitted, post-APPROVE round 1, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-222-2026-06-07.md
    findings_count: 3
    out_of_model_count: 1
    note: |
      1 medium + 2 low, all DOS / rate-limit fix class. The medium
      (/retry --digest bypasses both LLM caps) and the per-user-token-waste
      low both collide with this ticket's out_of_scope carve-outs
      (DigestRetryService untouched; LlmRateCap untouched) — candidates for
      a follow-up remediation ticket rather than in-branch fixes. The
      eviction-rebirth low is an inherited M1-112 pattern shared with the
      group-reply bucket. Disposition (user, 2026-06-07): refine — fix
      all 3 in-branch; out_of_scope carve-outs narrowed accordingly.
  - date: 2026-06-07
    verdict: FINDINGS
    base: 563563f^ (= 33b6b03)
    head: 563563f
    verdict_file: docs/plan/m1/redteam/M1-222-2026-06-07-b.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Second same-day audit, re-run on the squashed implementation commit
      (the first audit ran pre-fix on the working tree; 563563f absorbed
      its in-branch fixes). All 3 prior findings verified closed: refund
      path conserves per-user tokens, eviction threshold widened to
      max(evictionThreshold, refillWindow), /retry --digest draws the
      group-LLM bucket (RetryCommandHandler.java:414). 1 new low: the D47
      per-group COMMAND rate sub-bucket (security.md §Rate limiting) has
      no implementation in the Provider — outside this ticket's LLM-cap
      scope; candidate for a follow-up ticket. 2 out-of-model notes
      (in-memory bucket state across restart; eviction/acquire race ≤1
      surplus token — both pre-existing patterns).
      Disposition (user, 2026-06-07): fix in-branch — the per-group
      command sub-bucket landed as a post-commit branch commit
      (tryAcquireGroupCommand + step-6 slash gate +
      group.command_rate_limit key + tests); full mvn verify green
      (689/689 provider tests); out_of_scope carve-out absorbed.
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
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

### Redteam rework (2026-06-07 refine)

- **Finding 1 gate placement**: in `handleDigestRetry`, after the
  group-admin authorization AND after `writeDigestRetryAudit` (the
  digest path audits every authorized attempt regardless of outcome —
  cooldown-RATE_LIMITED and NO_PRIOR are audited today; a cap-rejected
  attempt staying audit-visible is exactly finding 1's hostile-admin
  repro), before `retryDigest`. Order: per-user `llmRateCap.tryAcquire(actor.id)`
  first (reject → `ERROR_CHAT_LLM_RATE_CAP`, the same key the DM /retry
  path uses), then `rateCapBucket.tryAcquireGroupLlm(groupDbId)`
  (reject → `GROUP_LLM_RATE_LIMIT` + per-user refund). No new bundle
  keys. `RetryCommandHandler` already injects `LlmRateCap`; add the
  `RateCapBucket` injection. Tokens are consumed before `retryDigest`
  even though non-SUCCESS results (ALREADY_IN_PROGRESS / NO_PRIOR)
  skip the LLM — over-counting is conservative for an anti-DOS cap;
  do not add refunds for those outcomes.
- **Finding 3 refund shape**: `refund(UUID userId)` removes the most
  recently recorded timestamp from the caller's deque (no-op on empty
  or absent entry — the entry can be evicted between acquire and
  refund only across the 120 s sweep cutoff, which cannot happen
  within one dispatch; the no-op is for the map-miss shape, not a
  defensive branch). The 7 existing `new LlmRateCap(...)` test sites
  are unaffected (additive method, no signature change).
- **Finding 2 arithmetic**: rebirth-at-full is only sound when the
  idle time would have refilled the bucket anyway, i.e. idle ≥ refill
  window. Per-map effective threshold
  `max(evictionThreshold, thatMapsRefillWindow)` — contact map keeps
  PT10M (window PT1M), both group maps effectively PT15M. The
  idle-alone predicate from M1-044a stays; only the threshold value
  per map changes.
