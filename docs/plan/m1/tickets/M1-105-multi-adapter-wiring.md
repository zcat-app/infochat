---
id: M1-105
title: "Multi-adapter Provider wiring + isolation IT"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-103
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterIsolationIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - any change to SimpleXAdapter internals — M1-102/M1-103 are frozen
  - any change to InMemoryAdapter — unchanged
  - Signal adapter — M1-106..M1-109
  - any change to MessagingAdapter SPI
acceptance:
  - "AdapterRegistry discovers all enabled adapter CDI beans at startup and validates: at least one adapter is up (readiness = at-least-one-up), supportsMarkdownLinks=false for all (fail-fast per messaging.md §Capability flags)"
  - "SimpleX + InMemory adapters coexist in the same Provider when both are enabled — messages from each adapter are routed independently"
  - "(adapter, contact_id) isolation: a user registered on SimpleX and a different user registered on InMemory with the same contact_id string are treated as distinct users — no cross-adapter state leakage"
  - "Bootstrap admin contact id is resolved per-adapter — the union across enabled adapters must be non-empty per D46"
  - "/grant-admin and /revoke-admin are scoped to the inbound adapter — an admin grant on SimpleX does not elevate on InMemory"
  - "Last-admin protection counts is_admin=true rows globally across adapters — cannot leave zero admins"
  - "MultiAdapterIsolationIT.crossAdapterIsolation passes — same contact_id string on two different adapters produces two distinct user rows; state on one does not leak to the other"
  - "MultiAdapterIsolationIT.grantAdminScopedToInboundAdapter passes — /grant-admin from SimpleX grants admin only for SimpleX; the same contact_id on InMemory is not elevated"
  - "MultiAdapterIsolationIT.lastAdminProtectionGlobal passes — revoking the last admin on one adapter is blocked if the global admin count would reach zero"
  - "AdapterRegistryTest.atLeastOneAdapterRequired passes — zero enabled adapters causes startup failure"
  - "AdapterRegistryTest.markdownLinksValidation passes — an adapter declaring supportsMarkdownLinks=true causes startup failure"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterIsolationIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D46
reviews:
  - round: 1
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 459
      removed: 13
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-05-31
    verdict: CLEAN
    base: main
    head: m1/M1-105-multi-adapter-wiring
    verdict_file: docs/plan/m1/redteam/M1-105-2026-05-31.md
    out_of_model_count: 3
    note: |
      Single-ticket audit on the post-commit / pre-merge branch tip.
      CLEAN — no spec/diff gap. 3 OUT-OF-MODEL observations recorded
      in the verdict file: (1) Gate 7 validates non-blankness only
      (no adapter-specific shape parsing — deferred to M1-046's
      @Startup admin-bootstrap bean); (2) IT's @BeforeEach disables
      audit_log append-only triggers during prefix-scoped DELETE
      cleanup (bounded by Surefire's per-module JVM fork); (3) the
      %test bootstrap-admin default would activate Gate 7 if an
      operator runs production with quarkus.profile=test. All three
      are operator-trust-boundary concerns flagged for future tickets,
      none block merge.
clarity_check:
  date: 2026-05-31
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: low is underclaimed for a security_relevant ticket that implements admin-tier scoping (/grant-admin, /revoke-admin, last-admin protection) and cross-adapter isolation. Consider raising to risk: medium or risk: high before start."
  blockers: []
---

# M1-105: Multi-adapter Provider wiring + isolation IT

## Context

D46: "One Provider may run one or more messaging adapters
simultaneously." The Provider must discover adapters, validate
capabilities, enforce per-adapter admin scoping, and maintain
(adapter, contact_id) isolation. This ticket proves the multi-adapter
wiring with SimpleX + InMemory coexisting.

`security_relevant: true` — cross-adapter isolation and admin scoping
are security-load-bearing.

## Acceptance

See frontmatter.

## Out-of-scope

- SimpleX adapter internals — M1-102/M1-103 are frozen.
- Signal adapter — M1-106+; the Signal+SimpleX production IT is M1-109.
- InMemoryAdapter changes — unchanged.

## Notes

- **AdapterRegistry.** May already exist in some form (the Provider
  currently discovers InMemoryAdapter). This ticket extends it to
  handle multiple adapters with the D46 invariants. Check at start
  time.
- **IT shape.** The IT runs with `infochat.adapters=simplex,inmemory`
  (or equivalent). It needs the SimpleX adapter to be functional
  enough to register and declare capabilities. If full subprocess
  integration is too heavyweight for CI, the IT can use a test
  profile that stubs the SimpleX connection while preserving the
  adapter's capability and trust-level declarations.
- **Bootstrap admin per-adapter.** The bootstrap admin contact id is
  configured per adapter (e.g. `infochat.adapters.simplex.admin=...`,
  `infochat.adapters.inmemory.admin=...`). The union must be
  non-empty. Per-adapter config is optional as long as the union is
  satisfied.
