---
id: M1-106
title: "Signal adapter skeleton — capabilities and ACI"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by: []
files_budget: 8
files_scope:
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
  - "SignalConfig validates at startup: binary path exists and is executable, data-dir exists and is writable, account is non-empty"
  - "SignalConfig validation failure causes startup to fail with a descriptive error"
  - "SignalIdentity resolves the bot's ACI (Account Credential Identifier) from the signal-cli data directory at startup — this is the bot's stable contact_id on Signal"
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
