---
id: M1-106
title: "Signal adapter skeleton — capabilities and ACI"
status: done
created: 2026-05-26
last_updated: 2026-05-30
blocked_by: []
files_budget: 8
files_scope:
  - infochat-messaging-adapter/pom.xml
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalConfig.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalIdentity.java
  - infochat-messaging-adapter/src/main/resources/application.properties
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalConfigTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - infochat-provider/** — no provider changes
  - any change to MessagingAdapter SPI or CapabilityFlags — the SPI is not modified
  - any change to InMemoryAdapter or SimpleXAdapter — unchanged
  - subprocess management or JSON-RPC connection — M1-107
  - group support or mention recognition — M1-108
  - multi-adapter production IT — M1-109
acceptance:
  - "SignalAdapter implements MessagingAdapter with name()='signal' and trustLevel()=HIGH"
  - "SignalAdapter.capabilities() returns CapabilityFlags with supportsMarkdownLinks=false, supportsMentionByContactId=true (Signal mentionUuid = ACI), supportsMessageEdit=true, supportsTypingIndicator=true, supportsMembershipEvents=true (Signal exposes native membership events)"
  - "SignalConfig reads operator properties: infochat.adapters.signal.binary (path to signal-cli binary), infochat.adapters.signal.data-dir (signal-cli data directory), infochat.adapters.signal.account (phone number or account identifier)"
  - "SignalConfig.validate() enforces: binary path exists and is executable, data-dir exists and is writable, account is non-empty"
  - "SignalConfig validation failure causes startup to fail with a descriptive error"
  - "SignalIdentity is the type for the bot's ACI (Account Credential Identifier — the stable contact_id on Signal) and declares a pure resolve(dataDir) entry point; resolving the ACI from signal-cli's on-disk account state is implemented in M1-107 (which owns the signal-cli format and adds the JSON dependency there)"
  - "SignalAdapter.start() and close() are no-ops in this skeleton — subprocess and connection are M1-107"
  - "SignalAdapter.send(), update(), finalize(), setTyping() throw UnsupportedOperationException in this skeleton"
  - "SignalAdapterSkeletonTest.capabilitiesAreCorrect passes — verifies trustLevel, name, and all capability flags"
  - "SignalConfigTest.validConfig_passes passes"
  - "SignalConfigTest.missingBinary_failsStartup passes"
  - "SignalConfigTest.missingDataDir_failsStartup passes"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalConfigTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D32
  - D46
reviews:
  - round: 1
    date: 2026-05-30
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 420
      removed: 24
  - round: 2
    date: 2026-05-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 443
      removed: 25
escalations:
  - date: 2026-05-30
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalation surfaced during implementation grounding (pre-review).
      To literally satisfy acceptance items 3 and 5 ("SignalConfig reads
      operator properties" / "validation failure causes startup to fail")
      the module would need Quarkus config + a startup hook, requiring an
      edit to infochat-messaging-adapter/pom.xml — a path outside files_scope.
      Root cause: those items describe Provider-side behaviour (config
      binding + startup gates live in AdapterRegistry, M1-035b/M1-105), not
      this deliberately Quarkus-free SPI module (pom: "NO Quarkus extensions
      in production scope"; InMemoryAdapter precedent: "no CDI annotations yet").
revisions:
  - date: 2026-05-30
    reason: budget-breach rework — reword acceptance items 3–6 so config-binding and startup-failure (Provider responsibilities per M1-035b/M1-105) are not required of this Quarkus-free adapter module; no files_scope/files_budget change
    prior_values: |
      acceptance items 3–6 (pre-refine):
        - "SignalConfig reads operator properties: infochat.adapters.signal.binary (path to signal-cli binary), infochat.adapters.signal.data-dir (signal-cli data directory), infochat.adapters.signal.account (phone number or account identifier)"
        - "SignalConfig validates at startup: binary path exists and is executable, data-dir exists and is writable, account is non-empty"
        - "SignalConfig validation failure causes startup to fail with a descriptive error"
        - "SignalIdentity resolves the bot's ACI (Account Credential Identifier) from the signal-cli data directory at startup — this is the bot's stable contact_id on Signal"
      (Items 3 and 5 assumed Quarkus config + a startup hook available in
       infochat-messaging-adapter. The module is a plain library jar with NO
       Quarkus extensions; pom.xml is outside files_scope. Provider's
       AdapterRegistry (M1-035b/M1-105) is what reads infochat.adapters.* and
       runs startup gates. Refine makes SignalConfig a plain value object +
       validate() logic; the binding and startup-invocation stay Provider-side.)
  - date: 2026-05-30
    reason: budget-breach re-resolved — quarkus-arc approved; items 3/5 restored to original Quarkus wording; pom added to files_scope; item 6 deferred to M1-107
    prior_values: |
      The 2026-05-30 refine above made this module deliberately Quarkus-free
      (SignalConfig a plain value object; binding + startup-invocation pushed
      to Provider). That refine is reversed: the operator approved adding
      io.quarkus:quarkus-arc (compile, BOM-managed, no <version>) to this
      module, so SignalConfig is an @ApplicationScoped @Startup bean that
      reads infochat.adapters.signal.* via @ConfigProperty and validates in
      @PostConstruct — bad config fails Provider boot in-module rather than
      via Provider wiring. infochat-messaging-adapter/pom.xml added to
      files_scope (budget unchanged at 8). Acceptance items 3 and 5 restored
      to their original Quarkus wording; item 6 reworded so SignalIdentity
      declares a pure resolve(dataDir) entry point here and M1-107 owns the
      signal-cli-format resolution (and the JSON dependency it needs).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-05-30
    verdict: CLEAN
    base: "a352ccb^ (dc899e6)"
    head: a352ccb
    verdict_file: docs/plan/m1/redteam/M1-106-2026-05-30.md
    out_of_model_count: 2
    note: |
      CLEAN — all threat-model commitments this diff owned are delivered
      (ACI→HIGH trust, no unverified identity returned, supportsMarkdownLinks
      =false). Every security-sensitive transport method fails closed (throws).
      Two OUT-OF-MODEL advisories recorded in the verdict file: operator
      config values (paths, account phone number) embedded in startup
      IllegalStateException messages; and hard-coded vs profile-driven
      capability caps. Both outside the documented threat model — for the
      user to weigh, not blocking. Audit ran post-commit, pre-merge.
clarity_check:
  date: 2026-05-30
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-106: Signal adapter skeleton — capabilities and ACI

## Context

Second production adapter. Same skeleton pattern as SimpleX (M1-102):
capabilities, config validation, identity resolution. Signal's contact
id is the ACI (Account Credential Identifier) — a UUID bound to the
Signal identity keys.

`security_relevant: true` — trust level and capability flags are
security-load-bearing.

## Acceptance

See frontmatter.

## Out-of-scope

- Subprocess management, JSON-RPC connection — M1-107.
- Group support, mention recognition — M1-108.
- Multi-adapter production IT — M1-109.

## Notes

- **ACI as contact_id.** Signal's ACI is the UUID that `signal-cli`
  surfaces as `mentionUuid` in group messages. It's cryptographically
  bound to the Signal identity and does not change when the phone
  number changes (post-Signal usernames). This is the D10 trust
  anchor for Signal.
- **signal-cli data directory.** signal-cli stores its protocol
  state (identity keys, sessions, group state) in a configurable data
  directory. The ACI is readable from this state after registration.
- **Config properties.** Parallel to SimpleX: binary path, data dir,
  account identifier. The account is the phone number used to register
  signal-cli.
- **Conditional activation.** Same pattern as SimpleXAdapter — only
  active when `infochat.adapters` includes `signal`.
- **Self-validating CDI bean.** `SignalConfig` is an `@ApplicationScoped`
  `@Startup` bean. Its `@Inject` constructor reads
  `infochat.adapters.signal.{binary,data-dir,account}` via
  `@ConfigProperty`, and a `@PostConstruct validate()` enforces the
  binary/data-dir/account rules. `@Startup` makes the bean eager, so
  `validate()` runs at Provider boot and a bad config fails startup
  in-module. This requires `io.quarkus:quarkus-arc` (compile, BOM-managed,
  no `<version>` per the M1-001 invariant) on this module — the operator
  approved lifting the pom's former "NO Quarkus extensions" stance.
- **CDI discovery is not this ticket.** Making the Provider actually
  *discover* this bean (jandex / empty `beans.xml` in this module, or a
  `quarkus.index-dependency` entry in Provider) is wiring owned by
  M1-035b/M1-105, not M1-106. This ticket delivers the bean and its unit
  tests (which construct `SignalConfig` directly, no Quarkus runtime).

## Round 1 rework

1. Remove the edit to `docs/plan/m1/tickets/M1-107-signal-subprocess.md`
   (the hunk adding `SignalIdentity.java` to M1-107's `files_scope`). That
   sibling ticket is outside M1-106's `files_scope` and is not
   lifecycle-exempt (only the M1-106 operand ticket is). Adjusting M1-107's
   scope so its flow owns `SignalIdentity.resolve`'s real implementation is
   M1-107's own concern and must happen in that ticket's flow, not in
   M1-106's diff.
