---
id: M1-208
title: "Per-adapter bootstrap-admin contact-id parse validation (SPI surface decision)"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: [M1-178]
files_budget: 9
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
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
  - "Per docs/spec/deployment.md §Operator inputs — \"The contact-id string format is **adapter-specific** — SimpleX contact ids are not Signal ACI/UUIDs — so each value MUST be parseable by its own adapter; Provider validates each at startup and refuses to start on a mismatch.\" — a configured bootstrap-admin contact id that its adapter cannot parse fails Provider startup fast with a message naming the adapter and the offending property: a named test per production adapter (today the property value is consumed without any adapter-specific parse check — there is no SPI surface to ask an adapter whether a contact id is well-formed)"
  - "A well-formed value per adapter passes the gate: named tests with a valid Signal ACI and a valid SimpleX address assert startup proceeds to the M1-178 bootstrap behavior"
  - "Where the per-adapter parse logic lives (a MessagingAdapter SPI method, per-adapter identity/config validators, or registry-side parsers keyed by adapter name) is decided at start and argued in the commit message — the SPI surface decision is the ticket's core; a chosen shape that widens MessagingAdapter goes through the normal review of SPI changes"
  - "Cross-adapter union semantics are preserved: per CLAUDE.md §Bootstrap admin & sources the property is optional per adapter as long as the union across enabled adapters is non-empty — the new gate must not reject an adapter with NO configured admin (existing gate-7 tests stay green)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D44
  - D46
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
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
