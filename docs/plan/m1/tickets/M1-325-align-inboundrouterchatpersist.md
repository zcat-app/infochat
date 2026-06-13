---
id: M1-325
title: "Align InboundRouterChatPersistFailureTest with M1-323 test-double constructors"
status: done
created: 2026-06-13
last_updated: 2026-06-13
blocked_by: []
files_budget: 1
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatPersistFailureTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any production source change. The test-double constructors (CountingDispatchDataSource, NoopGroupApprovalCheck) are correct as landed by M1-323; only the consuming test is out of step.
  - Any other test file. The green sibling tests (InboundRouterAcquisitionCountTest etc.) already use the M1-323 signatures and must not be touched.
  - Behavioural change to InboundRouterChatPersistFailureTest. The test's assertions and intent (persist-failure after delivered reply does not resend) are unchanged; only the two test-double constructor calls are realigned.
acceptance:
  - "InboundRouterChatPersistFailureTest line ~39 constructs CountingDispatchDataSource with a single actorId argument (new CountingDispatchDataSource(ACTOR_ID)), matching the single-arg constructor M1-323 left in place — the stray second GROUP_DB_ID argument is removed."
  - "InboundRouterChatPersistFailureTest line ~59 constructs NoopGroupApprovalCheck with the groupId argument (new NoopGroupApprovalCheck(GROUP_DB_ID)), matching the single-arg constructor M1-323 left in place — the zero-arg call is replaced. GROUP_DB_ID is already declared in the test, so no new field is added."
  - "infochat-provider testCompile succeeds and mvn -B clean verify from the repo root exits 0 — the provider module that was red on main (M1-313/M1-323 merge skew) is green again."
  - "The diff touches exactly one path: git diff --name-only on the implementing commit lists only InboundRouterChatPersistFailureTest.java (plus the ticket file and regenerated STATUS.md as workflow metadata)."
test_plan:
  preserves:
    - all tests currently green on main
  adds:
    - InboundRouterChatPersistFailureTest now compiles and its existing assertions run
context: |
  main went red because M1-313 (bc62bfed) added InboundRouterChatPersistFailureTest
  using the OLD constructor signatures of two test-doubles, while M1-323 (216a1dff,
  which landed earlier) had already narrowed CountingDispatchDataSource to (UUID actorId)
  and NoopGroupApprovalCheck to (UUID groupId). M1-313 was not rebased onto M1-323's
  change, so infochat-provider testCompile fails on main. The correct form is copied
  verbatim from the green sibling InboundRouterAcquisitionCountTest (landed by M1-323).
  Surfaced while running mvn verify for M1-311 (doc-only) against a freshly-rebased main.
spec_refs: []
decision_refs: []
reviews: {}
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-325: Align InboundRouterChatPersistFailureTest with M1-323 test-double constructors

## Context

See frontmatter `context`. In short: a parallel-development merge skew left
`main` red. M1-313 added `InboundRouterChatPersistFailureTest` against the
pre-M1-323 test-double signatures; M1-323 had already changed those signatures.
`infochat-provider` fails `testCompile` as a result. This ticket realigns the
two consuming constructor calls to the current (M1-323) signatures, copying the
form already used by the green sibling `InboundRouterAcquisitionCountTest`.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Two-line, test-only change. The test-double constructors are authoritative
  (M1-323, already reviewed); the consuming test is the only thing out of step.
