---
id: M1-102
title: "SimpleX adapter skeleton — capabilities and config"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by: []
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfig.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java
  - infochat-messaging-adapter/src/main/resources/application.properties
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterSkeletonTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfigTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - infochat-provider/** — no provider changes beyond adapter registration wiring
  - any change to MessagingAdapter SPI or CapabilityFlags — the SPI is not modified; SimpleX implements it
  - any change to InMemoryAdapter — existing test double is unchanged
  - subprocess management or WebSocket connection — M1-103
  - group support or mention recognition — M1-104
  - multi-adapter Provider wiring — M1-105
  - Signal adapter — M1-106..M1-109
acceptance:
  - "SimpleXAdapter implements MessagingAdapter with name()='simplex' and trustLevel()=HIGH"
  - "SimpleXAdapter.capabilities() returns CapabilityFlags with supportsMarkdownLinks=false, supportsMentionByContactId=true, supportsMessageEdit=true (SimpleX supports edits), supportsTypingIndicator=true, supportsMembershipEvents=TBD (researched in M1-104)"
  - "SimpleXConfig is a plain value object carrying the operator-config values and defines the keys infochat.adapters.simplex.binary (path to simplex-chat binary), infochat.adapters.simplex.data-dir (identity material directory), infochat.adapters.simplex.ws-port (WebSocket API port) as constants; binding these Quarkus config keys to a SimpleXConfig instance is performed by Provider's adapter wiring (M1-035b/M1-105), not this Quarkus-free module"
  - "SimpleXConfig.validate() enforces: binary path exists and is executable, data-dir exists and is writable, ws-port is in valid range"
  - "SimpleXConfig.validate() throws a descriptive exception naming the offending property on any failed check; Provider invokes validate() during its startup gates so a bad config fails Provider startup"
  - "SimpleXIdentity resolves the bot's contact id (queue address) from the simplex-chat data directory as a pure function over that directory; the startup-time invocation is performed by Provider wiring (M1-035b/M1-105)"
  - "SimpleXAdapter.start() and close() are no-ops in this skeleton — subprocess and connection are M1-103"
  - "SimpleXAdapter.send(), update(), finalize(), setTyping() throw UnsupportedOperationException in this skeleton — wiring is M1-103"
  - "SimpleXAdapterSkeletonTest.capabilitiesAreCorrect passes — verifies trustLevel, name, and all capability flags"
  - "SimpleXConfigTest.validConfig_passes passes — valid config properties are accepted"
  - "SimpleXConfigTest.missingBinary_failsStartup passes — a non-existent binary path causes validation failure"
  - "SimpleXConfigTest.missingDataDir_failsStartup passes — a non-existent data dir causes validation failure"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterSkeletonTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfigTest.java
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
revisions:
  - date: 2026-05-30
    reason: pre-start reword — config-binding and startup-failure are Provider responsibilities (M1-035b/M1-105), not this Quarkus-free adapter module; no files_scope/files_budget change
    prior_values: |
      acceptance items 3–6 (pre-refine):
        - "SimpleXConfig reads operator properties: infochat.adapters.simplex.binary (path to simplex-chat binary), infochat.adapters.simplex.data-dir (identity material directory), infochat.adapters.simplex.ws-port (WebSocket API port)"
        - "SimpleXConfig validates at startup: binary path exists and is executable, data-dir exists and is writable, ws-port is in valid range"
        - "SimpleXConfig validation failure causes startup to fail with a descriptive error naming the offending property"
        - "SimpleXIdentity resolves the bot's contact id (queue address) from the simplex-chat data directory at startup"
      (Items 3 and 5 assumed Quarkus config + a startup hook available in
       infochat-messaging-adapter. The module is a plain library jar with NO
       Quarkus extensions; pom.xml is outside files_scope. Provider's
       AdapterRegistry (M1-035b/M1-105) is what reads infochat.adapters.* and
       runs startup gates. Refine makes SimpleXConfig a plain value object +
       validate() logic; the binding and startup-invocation stay Provider-side.)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-102: SimpleX adapter skeleton — capabilities and config

## Context

First production adapter. The `MessagingAdapter` SPI exists with full
contract; `InMemoryAdapter` is the only implementation. This ticket
creates the SimpleX adapter shell: capabilities, config validation,
identity resolution — everything except the actual subprocess and
WebSocket connection (M1-103).

`security_relevant: true` — adapter trust level and capability flags
are security-load-bearing (trust level gates admin operations; flags
gate group mode).

## Acceptance

See frontmatter. A skeleton that registers with the Provider's adapter
registry, declares correct capabilities, validates operator config, and
throws `UnsupportedOperationException` on actual messaging operations
until M1-103 wires the connection.

## Out-of-scope

- Subprocess management, WebSocket connection — M1-103.
- Group support, mention recognition — M1-104.
- Multi-adapter wiring — M1-105.
- Signal adapter — M1-106+.

## Notes

- **Identity material.** SimpleX stores its database and key material
  in a data directory. The bot's queue address (contact id) is
  readable from this data after simplex-chat has been initialized. On
  first run, the operator initializes simplex-chat manually
  (`simplex-chat -d <data-dir>` creates the identity); subsequent
  runs read the existing identity.
- **Config properties.** The skeleton reads config via
  `@ConfigMapping` or `@ConfigProperty`. Design notes have the
  concrete property keys. The binary path and data dir are mandatory;
  the WebSocket port defaults to 5225 (simplex-chat's default).
- **Conditional activation.** The adapter is only a CDI bean when
  `infochat.adapters` includes `simplex`. Use `@IfBuildProfile` or
  runtime config check — the implementer picks the mechanism that
  matches InMemoryAdapter's activation pattern.
- **Adjacent code:** InMemoryAdapter for the SPI implementation
  pattern. CapabilityFlags for the flag record shape.
- **Plain value object; Provider does the binding.** This module is
  deliberately Quarkus-free (see its pom: "NO Quarkus extensions in
  production scope"). `SimpleXConfig` is a plain value object holding the
  three operator values plus a `validate()` method enforcing the
  binary/data-dir/ws-port rules. Reading `infochat.adapters.simplex.*`
  from Quarkus config and invoking `validate()` at startup are Provider
  responsibilities (the `AdapterRegistry` adapter wiring, M1-035b/M1-105),
  mirroring how `InMemoryAdapter` carries no CDI annotations until the
  Provider-side producer is authored.
- **Open planning gap (flag, do not fix here).** No ticket yet owns the
  Provider-side invocation of `SimpleXConfig.validate()` / binding of
  `infochat.adapters.simplex.*` — M1-035b/M1-105's startup gates are
  generic (`infochat.adapters` enable-list, `supportsMarkdownLinks`,
  trust) and do not read the simplex-chat binary/data-dir/ws-port keys.
  The "bad SimpleX config fails startup" promise needs a home in M1-105
  (Provider wiring) or an M1-103 scope extension.
