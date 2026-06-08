---
id: M1-211
title: "MessagingAdapter.assertIdentity: wire the spec-mandated surface or remove it"
status: done
created: 2026-06-07
last_updated: 2026-06-08
blocked_by: []
files_budget: 15
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - docs/spec/messaging.md
  - docs/design/06-messaging.md
  - docs/design/04-security.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - M1-208's bootstrap-admin contact-id validation — it may itself add a parse-validation method to MessagingAdapter; if both tickets change the SPI, coordinate the surface in one direction (name the interaction in the commit message), but its gate logic is untouched here
  - inbound dispatch threading — M1-177's (in-flight; same adapter files — rebase after it lands)
  - mention stripping and span propagation — M1-187's
  - the AdapterRegistry trust-level opt-in gates — whichever direction is chosen, the existing gate machinery stays as-is
  - InboundMessage's shape and the sender() construction path — referenced as the de-facto identity carrier; reshaping it is not this ticket
acceptance:
  - "A decision is recorded and applied: EITHER (a) the Provider inbound path invokes assertIdentity so the SPI method has a production caller — per docs/spec/messaging.md §Required SPI surface, \"**Identity assertion.** Receives a wire message, returns a stable, cryptographically-anchored contact id plus optional display name. An adapter that cannot do this MUST be marked low-trust and the operator must opt in explicitly.\" — with named tests proving the asserted identity (not any other field) is what reaches registration/ban/admin checks; OR (b) assertIdentity is removed from the SPI with docs/spec/messaging.md §Required SPI surface and design 06-messaging §6.2 amended to bind identity assertion to the adapter's InboundMessage construction (the de-facto shape today: adapters populate the sender identity before dispatch), all three production implementations deleted, and every test double swept — the build staying green is the proof no caller remained"
  - "Per docs/spec/messaging.md §Capability flags (minimum set) — \"`trustLevel` — `HIGH` for cryptographically anchored ids, `LOW` otherwise. Provider rejects identity assertions from `LOW` adapters unless the operator explicitly opts in.\" — whichever direction is chosen, this promise keeps a named, identifiable enforcement point (today's trust-level opt-in gating stays intact; direction (a) must not bypass it, direction (b) must restate in the spec where the rejection lives)"
  - "The choice and its argument (zero production callers since the SPI landed; the adapters' inbound paths already construct sender identity; D10 identity-anchor implications) are recorded in the commit message"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    # Direction (b) chosen at start: the SPI method is removed, so the
    # proof is the green build (no new positive-identity test added).
    # Every path below is already inside files_scope; this list is
    # corrected for accuracy against the call-site sweep, not expanded.
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Per-adapter trust level and identity
decision_refs:
  - D10
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 103
      removed: 132
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: main
    head: m1/M1-211-assert-identity-spi-decision
    verdict_file: docs/plan/m1/redteam/M1-211-2026-06-08.md
    out_of_model_count: 0
    note: |
      In-progress audit before merge. assertIdentity SPI removal is a
      pure SPI narrowing — zero production callers, three no-op
      passthrough impls deleted, prose reworded, test doubles swept.
      Trust boundary 1 intact (identity still flows via
      InboundMessage.sender()); the two new spec sentences map to
      pre-existing unchanged machinery (AdapterRegistry Gate 6
      low-trust rejection; non-null Identity sender on InboundMessage).
      No defense removed, no remediation needed.
escalations:
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — mid-implementation the developer found docs/design/04-security.md
      §4.8 (a live design doc outside files_scope) still asserts future
      adapters "must implement assertIdentity()", a claim direction (b)
      makes false. Resolution: refine to add the file to files_scope
      (user-approved 2026-06-08) and reword the stale line; files_budget
      unchanged (15 touched = budget).
revisions:
  - date: 2026-06-08
    reason: "budget-breach refine: add docs/design/04-security.md §4.8 to files_scope to correct the removed-method claim"
    snapshot:
      files_budget: 15
      files_scope:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
        - docs/spec/messaging.md
        - docs/design/06-messaging.md
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
        - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: \"recorded in the commit message\" is a process check verifiable only post-commit, not a behavioral criterion checkable against the diff or test output."
    - "FILES-BUDGET-PLAUSIBLE: files_budget 15 is plausible for direction (a) but tight for direction (b) given the call-site sweep identifies roughly 14+ test files needing edits."
    - "TEST-CHANGES-AUTHORIZED: test_plan.modifies is substantially incomplete for direction (b); the body's call-site sweep names ~9 additional test files not listed there."
  blockers: []
---

# M1-211: MessagingAdapter.assertIdentity — wire or remove

## Context

Unified finding A4 (`deep-code-review/v2/UNIFIED.md` §2): zero
production call sites for `MessagingAdapter.assertIdentity`
(re-verified 2026-06-07 — the only caller anywhere is one assertion
in InMemoryAdapterTest). The spec mandates the identity-assertion
surface and design 06-messaging §6.2 declares the method, all three
adapters implement it — but the Provider never calls it; inbound
identity reaches the router via the InboundMessage the adapters
construct.

This is a D-level decision (user call at start): wiring it makes the
spec sentence literally true at a single audit-able point; removing it
acknowledges the InboundMessage construction path as the identity
assertion and amends the documents to say so. Both directions are
spec-coordinated (spec edits ride with code, hence one ticket).

**Call-site sweep (draft time, M1-175/M1-160 precedent):** declared
abstract on the SPI; implemented by SignalAdapter, SimpleXAdapter,
InMemoryAdapter; overridden/stubbed in provider test doubles
NoopAdapter and CapturingAdapter plus inline adapters in
AdapterRegistryTest, StartupGatesTest, ProductionAdapterActivationTest,
InboundRouterContactIdRedactionTest, DigestWorkerTest, and in
messaging-side SignalGroupHandlerMembershipIsolationTest /
SignalGroupHandlerTest; called in InMemoryAdapterTest. The
removal direction touches every one of these.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Direction chosen at start (2026-06-08): (b)-strengthened.** Remove
  `assertIdentity` from the SPI + the three passthrough implementations
  + the test-double sweep, AND promote the verify-at-decode invariant
  into binding spec/design prose (every adapter MUST assert a
  cryptographically-anchored contact id at decode and drop on failure;
  trust level declared; LOW gated at registration via AdapterRegistry
  Gate 6). The design §6.4/§6.5 "Identity assertion fails → drop"
  failure-table rows are left intact — they already describe the
  decode-time assertion (Signal's row names "malformed envelope"), so
  they stay accurate under direction (b). Argument
  (zero production callers; all three impls are `return msg.sender();`;
  assertIdentity(InboundMessage) cannot verify because InboundMessage
  already carries the asserted Identity; asserting deeper would be a
  security regression vs. earliest-boundary drop; D10 is a property of
  contactId, not of a method name) is recorded in the implementation
  commit message per acceptance item 3.
- Source: `UNIFIED.md` §3 T31 leg (a) under `deep-code-review/v2/`
  (opus-47 arch F4).
- Cross-ticket wiring: M1-208 considers adding a contact-id
  parse-validation method to the same SPI — if M1-208 lands first with
  an SPI method, re-ground this ticket's surface description; if this
  lands first with removal, M1-208's option space narrows. Name the
  interaction either way.
- Serialize against in-flight M1-177 (SignalAdapter/SimpleXAdapter in
  its worktree) and pending M1-204 (same adapter files).
