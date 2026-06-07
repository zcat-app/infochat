---
id: M1-183
title: "LLM rate-cap + in-flight coverage for /summary and /retry"
status: done
created: 2026-06-07
last_updated: 2026-06-07
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/LlmRateCap.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - /stop cancellation plumbing (registerPgBackendPid wiring, statement_timeout coverage) — that is M1-193's; this ticket is admission control (rate bucket + single-slot), M1-193 is cancellation; both touch SummaryCommandHandler, so coordinate rather than serialize
  - the per-group LLM sub-bucket (D47) — a separate, unimplemented rate surface the audit noted only as an observation; not verified as a finding
  - chat-path rate-cap behavior in InboundRouter — already correct; InboundRouter is in scope only if the cap helper needs extraction for reuse
  - RateCapBucket key lifecycle / pre-auth key creation (UNIFIED.md gpt S5, judgment-tier)
  - digest-path LLM calls — periodic digests are not user-triggered and are spec'd as not interruptible
acceptance:
  - "Per docs/spec/security.md §Rate limiting — \"**LLM-triggering operations** (chat replies + on-demand `/summary` + `/retry` re-rolls) — its own bucket, capped lower, profile-driven.\" — /summary consumes the same per-user LLM rate bucket as chat replies: a named test exhausts the bucket and asserts the next /summary is rejected with the rate-limit reply and makes no LLM call"
  - "/retry consumes the same per-user LLM rate bucket: a named test exhausts the bucket and asserts the next /retry re-roll is rejected with the rate-limit reply and makes no LLM call"
  - "Per docs/spec/commands.md §Conversation control — \"**Interruptible operations:** chat-mode agent loops, user-issued `/summary` prose generation, and user-issued `/retry` re-rolls (decision D35).\" — an in-flight /summary registers with InFlightTracker so /stop can find it: a named test asserts the registration during prose generation and the release afterwards"
  - "Per docs/spec/commands.md §Surface conventions — \"**At most one in-flight interruptible request per (user, scope).** A second request from the same caller while one is in flight returns a localized \\\"request already in progress; use `/stop` to cancel\\\" reply.\" — a named test issues a second /summary while one is in flight for the same (user, scope) and asserts the in-progress reply with no second LLM call"
  - "A rejected (rate-capped or already-in-flight) /summary or /retry leaves the in-flight slot and rate bucket in a state where the next permitted request succeeds — a named test covers the release/no-leak path"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D35
reviews:
  - round: 1
    date: 2026-06-07
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 410
      removed: 130
  - round: 2
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
      added: 432
      removed: 128
overrides: []
aborted_attempts: []
reopens: []
redteam_audits:
  - date: 2026-06-07
    verdict: FINDINGS
    base: 0cbb8d5
    head: working tree (m1/M1-183-llm-rate-cap-in-flight-coverag, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-183-2026-06-07.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One DOS-medium finding (per-group LLM sub-bucket / D47 absent) lands on a
      surface M1-183 explicitly declares out_of_scope. Threat-actor confirms the
      diff's per-user multi-surface LLM rate-cap coverage is delivered soundly —
      no token burn on rejection, no in-flight slot leak, no cross-user-isolation
      gap. The per-group backstop is a pre-existing gap not remediable in this
      ticket; feeds a separate D47 ticket or spec-amend. No M1-183 change.
redteam_findings:
  - date: 2026-06-07
    category: DOS
    severity: medium
    promise: |
      **Per-group LLM rate (D47)** — a separate sub-bucket per approved group
      bounding LLM-triggering operations (chat replies + on-demand `/summary` +
      `/retry` re-rolls) across all group members. The per-user LLM cap fires
      first; the per-group cap is the backstop for groups with many active
      members.
    gap: |
      The diff routes every LLM-triggering surface through LlmRateCap, which is
      strictly per-users.id (key is UUID userId, LlmRateCap.java:50/:34). Group
      chat-mode LLM dispatch (InboundRouter.java:592) gates on
      llmRateCap.tryAcquire(actorId) where actorId is the individual sender. No
      per-groups-row aggregate bucket is consulted. GroupApprovalCheck has no
      LLM-cap logic; the only per-group LLM-cost control is DigestRetryService's
      digest cooldown (/retry --digest only). The group-aggregate dimension the
      threat model names is absent.
    repro: |
      An approved group has many registered, non-banned members. Each sends
      chat-mode @mention messages at or below the per-user cap (default 10/min),
      each passing llmRateCap.tryAcquire at InboundRouter.java:592 and reaching
      chatAgent.handle. Aggregate LLM invocations for that single group scale
      linearly with member count, bounded only by the sum of per-user caps — the
      "groups with many active members" exhaustion case the per-group backstop
      was specified to bound. No per-group LLM bucket ever rejects the flood.
    suggested_fix_class: rate-limit
    disposition: |
      Out_of_scope for M1-183 — frontmatter explicitly excludes "the per-group
      LLM sub-bucket (D47)". Pre-existing gap, NOT introduced by this diff; the
      per-user multi-surface coverage that IS this ticket's subject is delivered
      soundly. Remediate via a separate D47 per-group-bucket ticket or a
      spec-amend deferring the backstop. No change to M1-183 warranted.
revisions:
  - date: 2026-06-07
    reason: clarity-fail rework (TEST-CHANGES-AUTHORIZED blocker; latent files_scope/files_budget gap found during escalation grounding — cap-helper extraction forces changes to two messaging test files outside the original scope)
    snapshot:
      status: escalated
      escalation_reason: clarity-fail
      files_budget_at_snapshot: 7
      files_scope_at_snapshot:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
      test_plan_at_snapshot:
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/command
        modifies:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/command
        preserves:
          - all tests currently green on main
escalations:
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL
      TEST-CHANGES-AUTHORIZED: FAIL — test_plan.modifies lists
      infochat-provider/src/test/java/app/zcat/infochat/provider/command
      (same directory as test_plan.adds) but the ticket body has no
      "Authorized test changes" section enumerating which existing test
      classes change, why, or what new expected behavior replaces old.
---

# M1-183: LLM rate-cap + in-flight coverage for /summary and /retry

## Context

The per-user LLM rate cap is enforced only on the chat path:
`tryAcquireLlmRateCap` is referenced exactly once outside its definition, in
InboundRouter's chat branch (InboundRouter.java:601). SummaryCommandHandler
runs SummaryProseGenerator ("one LLM call per cluster" — up to the cluster
cap per invocation) with zero InFlightTracker or rate-cap references;
RetryCommandHandler registers with InFlightTracker (RetryCommandHandler.java:175)
but never consults the rate bucket. The spec names all three surfaces as one
bucket. A registered user can bypass the LLM cap entirely via repeated
/summary — each invocation fanning out one LLM call per cluster — and
concurrent /summary invocations are not bounded by the single-in-flight rule,
nor stoppable via /stop. Unified finding P1 (high-sec),
`deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter — spec sentences transcribed verbatim, each paired with a
named test pinning rejection behavior and the absence of the LLM call.

## Out-of-scope

See frontmatter. M1-193 owns the /stop cancellation plumbing; the two
tickets share SummaryCommandHandler and should land with awareness of each
other, but neither blocks the other.

## Authorized test changes

Pre-existing test classes change for two mechanical reasons. No assertion
weakening, deletion, or expected-value change is authorized — every change
below preserves the behavior the existing tests pin.

1. **New-collaborator wiring.** The handlers are CDI field-injected; the
   existing tests construct them bare (`new SummaryCommandHandler()`) and
   assign fields by hand, so every new injected field must be wired in test
   setup or existing tests NPE:
   - `SummaryCommandHandlerTest` — setup gains wiring for the LLM rate-cap
     collaborator and `InFlightTracker`; existing assertions unchanged.
   - `RetryCommandHandlerTest` — setup gains wiring for the LLM rate-cap
     collaborator; existing assertions unchanged.
   - `RetryDigestCommandTest` — setup gains wiring for the LLM rate-cap
     collaborator; existing assertions unchanged.
2. **Cap-helper extraction.** `tryAcquireLlmRateCap` (and its sliding-window
   state, config knob, and idle-entry sweep) moves off `InboundRouter` to the
   shared collaborator; no delegating shim stays behind:
   - `InboundRouterTest` — the `tryAcquireLlmRateCap` window/cap assertions
     repoint at the extracted collaborator; same scenarios, same expected
     values.
   - `InboundRouterIntakeOrderingTest` — the `router.llmRateCapPerMinute = 10`
     test wiring repoints to the collaborator equivalent; ordering assertions
     unchanged.

## Notes

- Source: `UNIFIED.md` §3 T7 under `deep-code-review/v2/` (opus-47 prov F1,
  kimi-folder prov F1).
- `tryAcquireLlmRateCap` currently lives on InboundRouter as a
  package-private method; the handlers live in a different package and
  injecting the router into handlers it dispatches to would be a dependency
  cycle, so reuse means extracting it to a shared collaborator —
  InboundRouter is in files_scope for exactly that, not for chat-path
  behavior changes. The files_scope entry
  `provider/chat/LlmRateCap.java` pins the location (alongside
  InFlightTracker, which the same surfaces consume); the exact class name is
  the implementer's call within that path.
- RetryCommandHandler's existing InFlightTracker registration is correct;
  only the rate bucket is missing there.

## Round 1 rework

1. Revert the two comment-only hunks in
   `infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java`
   (the "Chat-mode errors" section comment and the `ERROR_CHAT_IN_FLIGHT`
   javadoc) — the file matches no `files_scope` entry and is not
   lifecycle-exempt, so its presence in the diff is an automatic files_scope
   membership failure. If the comment refresh is wanted (the keys are now
   shared with the /summary and /retry handlers, making the old "never by
   command handlers" wording stale), either escalate for a `files_scope`
   revision via the workflow or file a follow-up doc-comment ticket; do not
   carry the file in this diff.
