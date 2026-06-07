---
id: M1-183
title: "LLM rate-cap + in-flight coverage for /summary and /retry"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
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
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D35
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
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

## Notes

- Source: `UNIFIED.md` §3 T7 under `deep-code-review/v2/` (opus-47 prov F1,
  kimi-folder prov F1).
- `tryAcquireLlmRateCap` currently lives on InboundRouter as a
  package-private method; reusing it from the two handlers may mean
  extracting it to a shared collaborator — InboundRouter is in files_scope
  for exactly that, not for chat-path behavior changes.
- RetryCommandHandler's existing InFlightTracker registration is correct;
  only the rate bucket is missing there.
