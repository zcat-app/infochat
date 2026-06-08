---
id: M1-208
title: "Per-adapter bootstrap-admin contact-id parse validation (SPI surface decision)"
status: done
created: 2026-06-07
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: [M1-178]
files_budget: 9
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalIdentity.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the bootstrap bean itself (ensure-user-row, is_admin=true, audit BOOTSTRAP_ADMIN) — M1-178's; this ticket adds only the parse-validation gate the spec promises on top of it
  - last-admin protection and /grant-admin//revoke-admin scoping — unchanged
  - InMemoryAdapter contact-id format (test adapter accepts free-form ids; its validator is permissive by design)
  - general contact-id validation on inbound traffic — this gate is for the OPERATOR-SUPPLIED bootstrap value at startup only (inbound ids come from the transport and are trusted at the adapter boundary as today)
acceptance:
  - "Per docs/spec/deployment.md §Operator inputs — \"The contact-id string format is **adapter-specific** — SimpleX contact ids are not Signal ACI/UUIDs — so each value MUST be parseable by its own adapter; Provider validates each at startup and refuses to start on a mismatch.\" — a configured bootstrap-admin contact id that its adapter cannot parse fails Provider startup fast (IllegalStateException) with a message naming the adapter and the offending infochat.adapters.<name>.admin property. One named test per production adapter asserts the reject: a malformed Signal admin id (non-UUID) fails the Signal validator (test in infochat-messaging-adapter/src/test/.../impl/signal), a malformed SimpleX admin id fails the SimpleX validator (test in .../impl/simplex), and a registry-level gate test in infochat-provider/src/test/.../provider/messaging asserts the thrown message names the adapter and the property."
  - "A well-formed value per adapter passes the gate: the same named tests assert a valid Signal ACI (a UUID) and a valid SimpleX address are accepted, and the registry gate test asserts startup proceeds past the gate to the M1-178 bootstrap behavior."
  - "SPI surface decision (chosen at start, argued in the commit message): the per-adapter parse logic lives as static well-formedness validators on the identity records — SignalIdentity.isWellFormed(String) (a Signal ACI is a UUID) and SimpleXIdentity.isWellFormed(String) — invoked registry-side. AdapterRegistry adds a startup gate immediately after the existing gate-7 union check that, for each activating adapter whose infochat.adapters.<name>.admin is non-blank, dispatches on adapter.name() (\"signal\"/\"simplex\" → the matching validator; any other name including \"inmemory\" → permissive) and throws when the value is malformed. MessagingAdapter is NOT widened: the SPI interface is unchanged (the engineering rules bar speculative SPI surface for a single in-tree caller, and AdapterRegistry already name-couples via INMEMORY_NAME / gate 5, so the name dispatch follows existing precedent). A diff that adds a method to MessagingAdapter fails this item."
  - "Cross-adapter union semantics preserved: the new gate runs only on non-blank admin values, so an adapter with NO configured admin is never rejected and the gate-7 union behavior is unchanged. The only change to existing gate-7 tests is updating the dummy simplex.admin fixtures in ProductionAdapterActivationTest and MultiAdapterProductionIT to well-formed SimpleX addresses so they pass the new parse gate (their signal.admin values are already valid UUIDs and stay unchanged); no assertion is removed, weakened, or skipped."
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProductionAdapterActivationTest.java — update the dummy infochat.adapters.simplex.admin fixture to a well-formed SimpleX address so the new parse gate passes; no assertion changed"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java — same simplex.admin fixture update; no assertion changed"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D44
  - D46
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
      files: 10
      added: 308
      removed: 9
escalations:
  - date: 2026-06-08
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      ACCEPTANCE-RUNNABLE: FAIL — Item 3 (SPI surface decision) is not a
      checkable criterion: "Where the per-adapter parse logic lives ... is
      decided at start and argued in the commit message — the SPI surface
      decision is the ticket's core." This records a design intent, not an
      outcome. No reviewer can verify "the implementer argued a shape in the
      commit message" against the diff, a test result, or a behavioral
      assertion. Replace with a behavioral assertion for the chosen shape.
revisions:
  - date: 2026-06-08
    reason: |
      clarity-fail rework. Rewrote acceptance item 3 from a non-checkable
      design-intent note into a behavioral assertion pinning the chosen SPI
      shape: registry-side static validators on SignalIdentity / SimpleXIdentity
      invoked from AdapterRegistry by adapter.name(), MessagingAdapter NOT
      widened (anti-speculative-SPI rule + AdapterRegistry already name-couples
      via INMEMORY_NAME / gate 5). Named the per-adapter test classes in
      items 1-2 (clarity warnings). Reworded item 4 and added
      test_plan.modifies to authorize the spec-forced simplex.admin fixture
      updates in ProductionAdapterActivationTest and MultiAdapterProductionIT
      (a real SimpleX parse gate rejects their current dummy values; their
      signal.admin values are already valid UUIDs). Removed
      MessagingAdapter.java from files_scope — the chosen shape leaves the SPI
      untouched.
    prior_acceptance_item_3: |
      Where the per-adapter parse logic lives (a MessagingAdapter SPI method,
      per-adapter identity/config validators, or registry-side parsers keyed by
      adapter name) is decided at start and argued in the commit message — the
      SPI surface decision is the ticket's core; a chosen shape that widens
      MessagingAdapter goes through the normal review of SPI changes
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: 798e1886b1ac9d493a47f8af529e9c432ddf5eda
    head: d6facf35afaca1a31853c4670a691ada2a8d5d7a
    verdict_file: docs/plan/m1/redteam/M1-208-2026-06-08.md
    findings_count: 0
    out_of_model_count: 2
    note: |
      CLEAN — purely additive fail-fast hardening at the trusted
      operator-config boundary (gate 7b validates each non-blank
      infochat.adapters.<name>.admin and refuses startup on a mismatch).
      No adversary-reachable surface, no weakened gate, no broken
      security.md commitment. Two advisory OUT-OF-MODEL notes feed future
      design judgement, not this ticket: (1) the `default -> true`
      permissive branch will silently accept a future third production
      adapter's admin id until a case arm is added — consider an
      explicit-deny default / exhaustiveness check then; (2) the SimpleX
      43-char length floor is a typo heuristic, not a cryptographic
      liveness proof (acceptable under the trusted-config tier).
---

# M1-208: Per-adapter bootstrap-admin contact-id parse validation (SPI surface decision)

## Context

Not an audit finding — a spec promise with no implementation hook,
discovered while drafting M1-178 (batch 1) and carved out of it there.
deployment.md §Operator inputs item 2 promises that each per-adapter
bootstrap-admin contact id "MUST be parseable by its own adapter" and
that the Provider "refuses to start on a mismatch". M1-178 implements
the @Startup bootstrap bean (ensure row, audit) but the parse-validation
gate needs a surface the SPI does not have: nothing lets the registry
ask an adapter "is this string a well-formed contact id for you?".

A mistyped ACI/queue address today silently seeds an admin row no real
contact can ever claim — the deployment looks bootstrapped but has an
unreachable admin, which is exactly the failure the spec's fail-fast
sentence exists to prevent.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: batch-1 drafting carve-out (recorded in M1-178's notes);
  surfaced per the batch-2 prompt's stray-findings list (disposition in
  the batch summary).
- blocked_by M1-178: the gate wraps the bootstrap value M1-178's bean
  consumes; building the gate first would validate a property nothing
  reads.
- M1-177 also touches MessagingAdapter.java — if the chosen shape adds
  an SPI method, serialize behind M1-177 as well (worktree in flight at
  draft time).
- M1-204 deletes the dead SignalIdentity/SimpleXIdentity resolve()
  stubs; if this ticket lands its validators in those classes,
  coordinate so neither ticket resurrects/deletes the other's code.
- Chosen shape (clarity-fail refine, 2026-06-08): registry-side. The
  validators are additive static methods on the SignalIdentity /
  SimpleXIdentity records (the records currently carry no logic; M1-204
  already removed the dead resolve() stubs, so no collision). The
  MessagingAdapter SPI stays frozen — adding a method there for one
  in-tree caller (the registry) is the speculative-SPI surface the SPI's
  own javadoc refuses for groupExists. M1-177 (done) touched
  MessagingAdapter; with the SPI left untouched there is no overlap.
- SimpleX address grammar: reuse whatever queue-address parsing the
  SimpleX adapter/codec already relies on rather than inventing a parallel
  grammar; the validator only needs to reject the kind of mistyped value
  the spec's fail-fast sentence targets (the existing test fixtures
  "simplex-test-bootstrap-admin" / "m1-109-simplex-bootstrap-admin" are
  exactly such non-addresses and must become well-formed under the chosen
  rule). A Signal ACI is a UUID (UUID.fromString round-trips).
