---
id: M1-102
title: "SimpleX adapter skeleton — capabilities and config"
status: done
created: 2026-05-26
last_updated: 2026-05-30
blocked_by: []
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfig.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java
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
  - parser for the simplex-chat on-disk data-directory layout that SimpleXIdentity.resolve(Path) will eventually consume — M1-103 (rides along with subprocess/WebSocket since both require a running simplex-chat to validate format)
  - group support or mention recognition — M1-104
  - multi-adapter Provider wiring — M1-105
  - Signal adapter — M1-106..M1-109
acceptance:
  - "SimpleXAdapter implements MessagingAdapter with name()='simplex' and trustLevel()=HIGH"
  - "SimpleXAdapter.capabilities() returns CapabilityFlags with supportsMarkdownLinks=false, supportsMentionByContactId=true, supportsMessageEdit=true (SimpleX supports edits), supportsTypingIndicator=true, supportsMembershipEvents=false — false is the safe default for a skeleton that does not yet subscribe to group-membership events; M1-104 (group support) flips this to the researched value when it adds group event handling"
  - "SimpleXConfig is a plain value object carrying the operator-config values and defines the keys infochat.adapters.simplex.binary (path to simplex-chat binary), infochat.adapters.simplex.data-dir (identity material directory), infochat.adapters.simplex.ws-port (WebSocket API port) as constants; binding these Quarkus config keys to a SimpleXConfig instance is performed by Provider's adapter wiring (M1-035b/M1-105), not this Quarkus-free module"
  - "SimpleXConfig.validate() enforces: binary path exists and is executable, data-dir exists and is writable, ws-port is in valid range"
  - "SimpleXConfig.validate() throws a descriptive exception naming the offending property on any failed check; Provider invokes validate() during its startup gates so a bad config fails Provider startup"
  - "SimpleXIdentity declares the entry point for resolving the bot's contact id (queue address) from the simplex-chat data directory — a record carrying the queue address with a static resolve(Path) method that throws UnsupportedOperationException in this skeleton, mirroring the adapter-method deferral pattern items 7–8 use; the parser that reads simplex-chat's on-disk data-directory layout is implemented in M1-103 (which actually exercises a running simplex-chat), and the startup-time invocation is performed by Provider wiring (M1-035b/M1-105)"
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
reviews:
  - round: 1
    date: 2026-05-30
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 7
      added: 353
      removed: 9
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
      files: 7
      added: 425
      removed: 11
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
  - date: 2026-05-30
    reason: clarity-fail rework — resolve TBD capability flag value (acceptance item 2) and remove stale Notes paragraphs (§Config properties, §Conditional activation) that contradict the post-refine plain-value-object design
    prior_values: |
      acceptance item 2 (pre-clarity-rework):
        - "SimpleXAdapter.capabilities() returns CapabilityFlags with supportsMarkdownLinks=false, supportsMentionByContactId=true, supportsMessageEdit=true (SimpleX supports edits), supportsTypingIndicator=true, supportsMembershipEvents=TBD (researched in M1-104)"

      body Notes §Config properties (pre-clarity-rework):
        "Config properties. The skeleton reads config via @ConfigMapping or
         @ConfigProperty. Design notes have the concrete property keys. The
         binary path and data dir are mandatory; the WebSocket port defaults
         to 5225 (simplex-chat's default)."

      body Notes §Conditional activation (pre-clarity-rework):
        "Conditional activation. The adapter is only a CDI bean when
         infochat.adapters includes simplex. Use @IfBuildProfile or runtime
         config check — the implementer picks the mechanism that matches
         InMemoryAdapter's activation pattern."

      (Acceptance item 2 left supportsMembershipEvents as "TBD (researched in
       M1-104)" — an unverifiable flag value. Body §Config properties and
       §Conditional activation referenced @ConfigMapping/@ConfigProperty and
       @IfBuildProfile/CDI activation, both of which contradict the prior
       refine that made this a Quarkus-free plain-library module with no
       CDI annotations in production scope. Reworked acceptance commits to
       a concrete boolean and reworked Notes describe the plain-library
       design.)
  - date: 2026-05-30
    reason: round 1 rework — acceptance item 6 used active "resolves" verb but the skeleton ticket cannot satisfy that without M1-103's simplex-chat data-directory groundwork; refine aligns item 6 with the deferral pattern items 7–8 already use, and adds the parser to out_of_scope
    prior_values: |
      acceptance item 6 (pre-rework):
        - "SimpleXIdentity resolves the bot's contact id (queue address) from
           the simplex-chat data directory as a pure function over that
           directory; the startup-time invocation is performed by Provider
           wiring (M1-035b/M1-105)"

      out_of_scope (pre-rework): did NOT enumerate the simplex-chat
      data-directory parser as out of scope; the parser sat in a gray zone
      between this skeleton (acceptance item 6 said "resolves") and M1-103
      (subprocess + WebSocket, which actually exercises simplex-chat).

      (Round 1 reviewer flagged the mismatch: SimpleXIdentity.resolve(Path)
      throws UnsupportedOperationException citing M1-103, while item 6's
      active verb "resolves" demanded a working pure function. Items 7-8
      already use the in-this-skeleton-then-M1-103 deferral pattern; item 6
      did not. Refine brings item 6 in line with that pattern and explicitly
      lists the parser under out_of_scope so the reviewer never sees the
      mismatch again. No files_scope/files_budget change. The clarity WARN
      on the same day predicted exactly this resolution: "document deferral
      to M1-103.")
escalations:
  - date: 2026-05-30
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A — clarity pre-flight FAIL during /m1-tick start; see clarity_check.blockers
  - date: 2026-05-30
    reason: premise-fail
    reviewer_verdict_excerpt: |
      Round 1 reviewer REWORK item 1 (ACCEPTANCE-CHECK: PARTIAL on item 6):
      acceptance item 6 says SimpleXIdentity "resolves" the queue address
      as a pure function over the simplex-chat data directory, but the
      ticket body §Notes ("operator initializes simplex-chat manually")
      plus the items-7-8 deferral pattern make the data-directory parser
      M1-103's responsibility. The premise that item 6 was satisfiable in
      this skeleton is wrong; the skeleton can declare the entry point
      but cannot resolve the queue address without M1-103's groundwork.
      Reviewer named refine as the explicit alternative path (option b).
      Clarity pre-flight 2026-05-30 predicted exactly this:
        "Consider a SimpleXIdentityTest or document deferral to M1-103."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-05-30
    verdict: CLEAN
    base: main
    head: m1/M1-102-simplex-adapter-skeleton
    verdict_file: docs/plan/m1/redteam/M1-102-2026-05-30.md
    findings_count: 0
    out_of_model_count: 2
    note: |
      Skeleton fails closed across every security-bearing surface. Two
      OUT-OF-MODEL observations flagged for M1-103 attention: (1) Path
      TOCTOU on SimpleXConfig.validate() vs M1-103 use-time (operator
      config is trusted, so not a current gap); (2) AdapterTrustLevel.HIGH
      declared before real identity verification exists — safe today
      because assertIdentity throws, but M1-103 must implement the real
      queue-address signature verification before wiring into Provider.
clarity_check:
  date: 2026-05-30
  verdict: WARN
  warnings:
    - "Acceptance item 6 (SimpleXIdentity): no named test in test_plan.adds covers SimpleXIdentity; reviewer can only verify by code inspection. Consider a SimpleXIdentityTest or document deferral to M1-103."
  blockers: []
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
- **Plain value object; Provider does the binding.** This module is
  deliberately Quarkus-free (see its pom: "NO Quarkus extensions in
  production scope") and carries NO CDI/Quarkus annotations
  (`@ConfigMapping`, `@ConfigProperty`, `@IfBuildProfile`,
  `@ApplicationScoped`, etc.) in production scope. `SimpleXConfig` is a
  plain value object holding the three operator values plus a
  `validate()` method enforcing the binary/data-dir/ws-port rules.
  `SimpleXAdapter` and `SimpleXIdentity` are likewise plain classes.
  Reading `infochat.adapters.simplex.*` from Quarkus config,
  constructing the `SimpleXConfig` value, invoking `validate()` at
  startup, and registering the adapter as a CDI bean only when
  `infochat.adapters` includes `simplex` are ALL Provider
  responsibilities (the `AdapterRegistry` adapter wiring,
  M1-035b/M1-105), mirroring how `InMemoryAdapter` carries no CDI
  annotations until the Provider-side producer is authored.
- **Config property keys (declared as constants here, bound by Provider).**
  `SimpleXConfig` declares the three property-key names as string
  constants: `infochat.adapters.simplex.binary` (path to simplex-chat
  binary), `infochat.adapters.simplex.data-dir` (identity material
  directory), `infochat.adapters.simplex.ws-port` (WebSocket API port,
  default 5225 — simplex-chat's default). The constants live with
  `SimpleXConfig` so the Provider-side binding (M1-035b/M1-105) and the
  tests reference the same names. Binary path and data dir are
  mandatory; ws-port has a default.
- **Adjacent code:** InMemoryAdapter for the SPI implementation
  pattern. CapabilityFlags for the flag record shape.
- **Open planning gap (flag, do not fix here).** No ticket yet owns the
  Provider-side invocation of `SimpleXConfig.validate()` / binding of
  `infochat.adapters.simplex.*` — M1-035b/M1-105's startup gates are
  generic (`infochat.adapters` enable-list, `supportsMarkdownLinks`,
  trust) and do not read the simplex-chat binary/data-dir/ws-port keys.
  The "bad SimpleX config fails startup" promise needs a home in M1-105
  (Provider wiring) or an M1-103 scope extension.

## Round 1 rework

Reviewer verdict 2026-05-30: REWORK (ACCEPTANCE-CHECK: PARTIAL).

1. Resolve acceptance item 6's "SimpleXIdentity resolves... as a pure
   function over that directory" mismatch with the current
   `UnsupportedOperationException`-throwing stub in
   `SimpleXIdentity.resolve(Path)`. Pick one path:
   - **(a)** Implement `SimpleXIdentity.resolve(Path)` as the documented
     pure function over the simplex-chat data dir and add a
     `SimpleXIdentityTest` covering it (the clarity_check WARN named
     exactly this).
   - **(b)** Escalate → refine the ticket so acceptance item 6
     explicitly defers `resolve()`'s implementation to M1-103 (mirroring
     the pattern used for `start`/`close`/`send`/`update`/`finalize`/
     `setTyping` in items 7–8) and add the M1-103 reference to the
     ticket body §Out-of-scope alongside "Subprocess management,
     WebSocket connection".
