---
id: M1-120
title: "SimpleX + Signal CDI activation (D46)"
status: done
created: 2026-05-31
last_updated: 2026-05-31
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProductionAdapterBeans.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/MessagingStartup.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProductionAdapterActivationTest.java
  # SimpleXConfig.java dropped at implementation time per ticket-body
  # Notes "if (a), drop SimpleXConfig.java from files_scope". Option
  # (a) was taken: the Producer instantiates SimpleXConfig from
  # @ConfigProperty injection points without modifying SimpleXConfig.
  # The five files below were added by the in-branch red-team
  # remediation (2026-05-31) after the redteam audit surfaced
  # findings 1-4 against the implementation commit (3b1f443). The
  # remediation lives on this branch as a 2nd commit; the canonical
  # squash subject at /m1-tick merge time remains the implementation
  # commit's subject (pass -C 3b1f443).
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log/SafeLogTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-core/** — no SPI changes
  - any change to SimpleXAdapter or SignalAdapter internals — the concrete adapter classes (M1-102/M1-103, M1-106/M1-107/M1-108) are frozen
  - any change to InMemoryAdapter — unchanged
  - any change to MessagingAdapter SPI — not modified
  - any change to AdapterRegistry — M1-105 is frozen; this ticket adds new Producer beans in a separate file and updates MessagingStartup, but does not re-shape the registry
  - the multi-adapter production-shape IT — that is M1-109 (deferred on this ticket)
  - SimpleXSubprocess / SignalSubprocess crash-detection / restart cap logic — covered by M1-103 / M1-107
  - any change to FakeSimpleXProcess or FakeSignalCli — those are M1-109's scope when it reopens
acceptance:
  - "A new file ProductionAdapterBeans (or equivalent name in files_scope) declares two @Produces @ApplicationScoped methods returning SimpleXAdapter and SignalAdapter respectively, wired with the operator config from infochat.adapters.<name>.* properties"
  - "The SimpleXAdapter @Produces method reads infochat.adapters.simplex.binary, .data-dir, .ws-port via @ConfigProperty and constructs SimpleXConfig + a JDK HttpClient + an adminNotifier Consumer<String> that routes to the existing audit/notification surface (or a stub if no admin notifier wiring exists in v1 yet — name the choice in the commit message)"
  - "The SignalAdapter @Produces method reads infochat.adapters.signal.binary, .data-dir, .account, .admin (used as botAci), and an endpoint property (derived or fixed) and constructs SignalAdapter via its production constructor"
  - "Instance<MessagingAdapter> at @PostConstruct time of AdapterRegistry resolves to all three production beans (inmemory + simplex + signal) when each is on the activation list"
  - "AdapterRegistry.start(\"simplex,signal\") passes all seven gates and adds both adapters to activatedAdapters() — verifiable in ProductionAdapterActivationTest"
  - "MessagingStartup.startAllAdapters() invokes adapter.start() on each activated adapter — replacing the current log-only stub — with per-adapter try/catch so a failure on one adapter is logged at ERROR and the loop continues to the next adapter (the §6.7 'per-adapter resilience' invariant)"
  - "ProductionAdapterActivationTest.bothAdaptersResolveAsBeans passes — Instance<MessagingAdapter> contains beans named \"simplex\" and \"signal\" alongside \"inmemory\""
  - "ProductionAdapterActivationTest.messagingStartupCallsAdapterStart passes — a spied/recording adapter implementation receives start() from MessagingStartup"
  - "ProductionAdapterActivationTest.startFailureDoesNotAbortLoop passes — when one adapter's start() throws, the next adapter's start() is still invoked and Provider startup completes"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProductionAdapterActivationTest.java
  preserves:
    - all tests currently green on main
    - AdapterRegistryTest passes unchanged
    - MultiAdapterIsolationIT passes unchanged
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/deployment.md §Bootstrap behavior on startup
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
      files: 5
      added: 519
      removed: 24
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-31
    category: AUTH-BYPASS
    severity: high
    promise: |
      From security.md §Trust boundaries (1): "The adapter asserts identity via a stable, cryptographically anchored ID. Display names are informational and never used for authorization (decision D10)." And from §"What's intentionally NOT in v1": "Display-name-based @mention recognition. v1 mention recognition is anchored to the cryptographic contact id only ... string-matching the bot's display name in inbound message bodies is forever out of v1 because an attacker who spoofs or impersonates the bot's display name could otherwise suppress or fake mentions."
    gap: |
      ProductionAdapterBeans.java:127 constructs the bot's SimpleX D10 trust anchor as `new SimpleXIdentity(simplexAdmin.orElse(""))`. (a) When `infochat.adapters.simplex.admin` is not set (gate 7 only requires the union across adapters to be non-empty — a deployment running `simplex,signal` with admin configured only on Signal passes gate 7), the bot's queue address becomes the empty string. SimpleXMentionParser.botMentioned (lines 62-69) calls `Arrays.equals(botBytes, mentionBytes)` where `botBytes` decodes from "" to an empty byte array; any mention entry that base64-decodes to empty bytes matches the bot. The forged-mention class the spec forever excludes is reintroduced. (b) Even when `simplex.admin` IS set, it carries the operator's bootstrap admin queue address, NOT the bot's own queue address — admin↔bot identities are conflated at the trust-anchor layer.
    repro: |
      Operator deploys `infochat.adapters=simplex,signal` with only `infochat.adapters.signal.admin` configured (recommended high-assurance placement per §Per-adapter admin threat profile). SimpleX bean wires `botIdentity=""`. Attacker in any SimpleX group sends a newChatItem whose mention list contains an empty-string entry. botMentioned returns true; the group-scope message reaches Provider through SimpleXGroupHandler.onGroupCandidate as if the bot had been mentioned. Attacker drives bot responses in groups without ever using a real mention.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-31
    category: AUTH-BYPASS
    severity: medium
    promise: |
      From security.md §Per-adapter admin threat profile: "Each enabled adapter has a different real-world compromise surface, and admin rows are per-(adapter, contact_id) ... Cross-adapter elevation is impossible by design (/grant-admin is inbound-adapter-scoped)." The per-adapter admin contact id is a separate, security-critical operator input; conflating it with the bot's own identity erases the per-adapter scoping that bounds blast radius.
    gap: |
      ProductionAdapterBeans.java:96-98, 111, 127, 234-241 reuses the single config key `infochat.adapters.<adapter>.admin` as BOTH (1) the bootstrap admin's contact id (consumed by AdapterRegistry gate 7 — AdapterRegistry.java:221-243) and (2) the bot's own per-adapter identity passed into SimpleXAdapter/SignalAdapter. These are distinct security primitives. An operator rotating the admin contact id (the documented "SimpleX queue rotation" mitigation) inadvertently changes the bot's identity at the same time, breaking mention recognition across all groups.
    repro: |
      Operator follows the spec-recommended rotation procedure and rotates `infochat.adapters.simplex.admin` to a fresh queue. The bot's group-mention recognition silently switches to the new value: every existing group's @mention of the bot now misses; the bot goes silent in all groups. The new value (the new admin's private queue address) is now compared against every inbound mention list — exposing a side-channel where group members can probe whether their messages "match" the admin's queue without ever seeing the address directly.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-31
    category: DOS
    severity: medium
    promise: |
      From security.md §Trust boundaries (assumption): "Operator-set config (properties files, bootstrap JSON) is trusted" — i.e. operator inputs are validated at config-boundary before any traffic is served. SimpleXConfig.validate() (SimpleXConfig.java:73-88) is the documented validation entry point for filesystem/port checks.
    gap: |
      ProductionAdapterBeans.java:42-44 documents that the Producer intentionally does NOT call SimpleXConfig#validate(). But SimpleXAdapter.start() (SimpleXAdapter.java:155-198) DOES NOT call cfg.validate() either — it goes straight to SimpleXSubprocess.commandFor(cfg) and waitForWebSocketReady(cfg.wsPort()). So spec-mandated filesystem and port-range checks (binary exists+executable, dataDir exists+writable, wsPort in 1..65535) never run on the production path. Mis-configuration surfaces deep inside subprocess launch with an opaque exception, which MessagingStartup.startAllAdapters() (per §6.7 resilience) SILENTLY ABSORBS via the broadened `catch (Throwable t)` clause. The deployment boots cleanly with the SimpleX path inert — only a single ERROR log line whose message body is stripped by SafeLog.
    repro: |
      Operator mis-types `infochat.adapters.simplex.ws-port=722654` (typo for 7265). Provider boots; AdapterRegistry passes all gates (no port-range check); MessagingStartup invokes simpleXAdapter.start(); waitForWebSocketReady(722654) eventually throws IllegalArgumentException; the catch on line 86 logs via SafeLog with the exception class name only. Operator sees no actionable signal; SimpleX is silently inert. Equivalent for non-executable binary path or non-writable data-dir.
    suggested_fix_class: input-sanitization
  - date: 2026-05-31
    category: INFO-LEAK
    severity: low
    promise: |
      From security.md §"User content in exceptions": "Exception messages and stack traces emitted via the application logger MUST NOT contain user-authored prose ... The original Throwable is never passed to the underlying SLF4J logger."
    gap: |
      MessagingStartup.java:86-91 catches `Throwable t` (widened from RuntimeException) and passes it to SafeLog.error. SafeLog correctly drops the message body and traverses only getCause() for class names (depth-capped at 5). The reflective rethrow at line 81-85 unwraps InvocationTargetException.getCause(). However, SafeLog's traversal does not walk getSuppressed() chains; if a future start() aggregates parallel failures via addSuppressed, suppressed messages bypass the redaction promise. Hardening gap, not a current exploit.
    repro: |
      Future adapter start() aggregates a "subprocess launch + WebSocket dial" using addSuppressed. If either branch throws an exception whose message contains operator-supplied path strings (which under §Secrets handling could carry an API key shape), SafeLog drops the primary message but a future contributor adding LOG.error("start failed", t) adjacent to a SafeLog call would surface them.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-05-31
    verdict: FINDINGS
    base: 94bc3e3fc1b7d67001f8cccf19ea895546938972
    head: 3b1f443b61c3bf92399317472cd9ca9973b2b8b9
    verdict_file: docs/plan/m1/redteam/M1-120-2026-05-31.md
    findings_count: 4
    out_of_model_count: 2
    note: |
      Post-/m1-tick commit, pre-/m1-tick merge audit. Four findings (1 high, 2 medium, 1 low). The high-severity finding (AUTH-BYPASS) traces to wiring the SimpleX bot identity from `infochat.adapters.simplex.admin` with `.orElse("")` fallback — when gate 7 accepts a deployment whose only configured admin is on a sibling adapter, botIdentity becomes the empty string and SimpleXMentionParser matches any empty-decoded mention entry. The two medium AUTH-BYPASS findings collapse into the same root: conflating the operator's bootstrap admin contact id with the bot's own per-adapter identity. The medium DOS finding is independent: SimpleXConfig.validate() is never called on the production path (neither by the Producer nor by SimpleXAdapter.start()). The low INFO-LEAK finding flags SafeLog's unwalked getSuppressed chains. Findings reach into M1-103 / M1-104 territory (SimpleXMentionParser, SimpleXIdentity.resolve still throwing UnsupportedOperationException), so remediation likely requires a multi-file ticket touching both the Producer and the messaging-adapter module.

      **DISPOSITION (2026-05-31, in-branch remediation, 2nd commit on m1/M1-120-simplex-signal-cdi-activation).** Per user instruction "fix all redteam findings within this ticket and branch," all four findings are remediated on this branch as a 2nd commit (canonical squash subject at merge time remains 3b1f443 per the per-/redteam-post-/commit-pre-/merge pitfall). Fixes:
        - Finding 1+2 (AUTH-BYPASS): separated bot identity from bootstrap admin via two new config keys — `infochat.adapters.simplex.bot-queue-address` and `infochat.adapters.signal.bot-aci`. The Producer reads these in place of `.admin` for botIdentity/botAci; the existing `.admin` keys remain as gate-7-only bootstrap-admin inputs. Validation lives in `SimpleXAdapter.start()` / `SignalAdapter.start()` so inmemory-only deployments do not trip the simplex/signal config checks. Property keys named in the thrown messages so an operator can fix the exact value.
        - Finding 3 (DOS): added `cfg.validate()` at the top of `SimpleXAdapter.start()`, after the existing null-wiring check. Fail-fast on filesystem/port misconfiguration at startup rather than absorbing an opaque ProcessBuilder/IOException inside the §6.7 catch-Throwable.
        - Finding 4 (INFO-LEAK): extended `SafeLog.formatSafe` to walk `getSuppressed()` arrays alongside the cause chain, emitting only class names in `[+ClassName,+ClassName]` form. Maintains the existing "no message body, no stack frames" invariant for the suppressed surface so a future contributor's `try-with-resources` or parallel-failure `addSuppressed` cannot leak user content via the same SafeLog path.
      Files touched by the remediation: SimpleXAdapter.java, SignalAdapter.java, ProductionAdapterBeans.java, ProductionAdapterActivationTest.java, SafeLog.java, SafeLogTest.java. `mvn -B clean verify` from repo root: BUILD SUCCESS (one initial flake on SimpleXSubprocessTest.failedStateAfterCapExhaustion — tight 5ms/20ms timing, not touched by this ticket — passed on retry). The redteam_findings entries above are LEFT IN PLACE as the structured per-finding record; the resolution status is captured in this disposition note rather than by deleting/mutating the findings (so the audit trail of "what the red-team saw on 3b1f443" stays intact).
clarity_check:
  date: 2026-05-31
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: files_scope unconditionally lists SimpleXConfig.java, but the ticket body instructs the implementer to drop it from files_scope if option (a) is chosen at implementation time. Consider either removing SimpleXConfig.java from files_scope (committing to option a) or removing the 'if (a), drop it' instruction (committing to option b), so the scope is unambiguous before the round starts."
  blockers: []
---

# M1-120: SimpleX + Signal CDI activation (D46)

## Context

D46 commits v1 to a Provider that can run any non-empty subset of
{SimpleX, Signal, InMemory} simultaneously. M1-102 through M1-108
delivered the concrete adapter classes (subprocess management,
WebSocket / JSON-RPC clients, group support, mention recognition),
and M1-105 wired the multi-adapter Provider registry shape. But the
discovery survey for M1-109 (the multi-adapter production-shape IT)
surfaced that neither SimpleXAdapter nor SignalAdapter is currently
registered as a CDI bean: `AdapterRegistry`'s
`Instance<MessagingAdapter>` injection point only resolves to
`InMemoryAdapter` (the lone `@Produces` method at
`AdapterRegistry.java:106`). Gate 2 ("every name in CSV resolves to
a registered bean") rejects `infochat.adapters=simplex,signal` today
— meaning the v1 production deployment shape is not actually
deployable from main.

`MessagingStartup.startAllAdapters()` also stops short of calling
`adapter.start()`; its in-code comment confirms the design always
expected this to land "when T3-A's SimpleX/Signal beans drop in
their connect call." That landing was never explicitly scheduled
as its own ticket. This ticket fills both gaps.

`security_relevant: true` — bootstrap admin resolution, per-adapter
identity, and at-least-one-up readiness are all security-load-bearing
per `docs/spec/security.md` §Authorization model + §Per-adapter
admin threat profile.

M1-109 (the production-shape IT) is deferred on this ticket and
reopens after this lands.

## Acceptance

See frontmatter. Briefly:

1. Add `@Produces @ApplicationScoped SimpleXAdapter()` and
   `@Produces @ApplicationScoped SignalAdapter()` in a new
   `ProductionAdapterBeans` class (separate from `AdapterRegistry`
   which is frozen per M1-105). Each Producer reads its adapter's
   per-name `infochat.adapters.<name>.*` config properties and
   constructs the adapter via its production constructor.

2. Update `MessagingStartup.startAllAdapters()` to actually call
   `adapter.start()` per activated adapter, with the §6.7
   per-adapter-resilience try/catch shape already named in
   `MessagingStartup.java:53`-67's javadoc.

3. Add a small `ProductionAdapterActivationTest` that asserts
   bean resolution + the MessagingStartup start() invocation +
   the per-adapter-resilience loop semantics.

## Out-of-scope

- **Adapter internals are frozen.** This ticket constructs the
  adapters via their existing public constructors only — no
  modification to `SimpleXAdapter.java`, `SignalAdapter.java`, or
  their subprocess/wire-protocol classes (`SimpleXSubprocess`,
  `SignalSubprocess`, `SimpleXWebSocketClient`,
  `SignalJsonRpcClient`).
- **`AdapterRegistry` is frozen.** Per M1-105 freeze. The new
  Producers live in a separate file (`ProductionAdapterBeans`)
  rather than as additional methods on `AdapterRegistry` so
  M1-105's reviewed surface is untouched.
- **`MessagingAdapter` SPI is frozen.** The Producers honor the
  existing constructor signatures; this ticket does not widen the
  SPI.
- **The multi-adapter production-shape IT** belongs to M1-109,
  which reopens once this ticket lands.
- **Fake visibility (FakeSimpleXProcess / FakeSignalCli public
  modifier)** is M1-109's scope when it reopens — this ticket's
  tests use lightweight stub `MessagingAdapter` implementations,
  not the cross-module fakes.

## Notes

- **Producer file location.** A new file
  `infochat-provider/src/main/java/.../messaging/ProductionAdapterBeans.java`
  is the cleanest home: keeps `AdapterRegistry` frozen, gives the
  v1 production Producers a discoverable home (matching the
  pattern of one class per concern), and lets the reviewer see the
  CDI graph contribution in one diff. Alternative considered:
  separate `SimpleXBeans.java` and `SignalBeans.java`. Rejected:
  unnecessary file proliferation; both Producers share the same
  conceptual scope (D46 v1 production adapter activation).
- **SimpleXConfig is currently not a CDI bean** (plain value class,
  no annotations). The Producer either (a) instantiates
  `SimpleXConfig` inside the @Produces method from the
  @ConfigProperty injection points, or (b) declares `SimpleXConfig`
  as @ApplicationScoped @Startup to mirror `SignalConfig`. Option
  (a) keeps `SimpleXConfig` untouched; option (b) touches
  `SimpleXConfig.java` to add the CDI annotations + @Inject
  constructor — that's the listed file in `files_scope` so the
  implementation can choose either path. Implementer note: if (a),
  drop `SimpleXConfig.java` from `files_scope` at clarity-check
  rework time.
- **Admin notifier wiring.** The current `SimpleXAdapter` constructor
  takes a `Consumer<String> adminNotifier`. v1 may not yet have a
  unified admin-notification channel; if so, pass a stub that logs
  at WARN. Name the choice in the commit message under
  "Alternatives considered:" so the gap is auditable when the
  notification surface lands later.
- **MessagingStartup change shape.** Replace the current
  no-op log loop with the body the javadoc already describes:
  ```java
  for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
      try {
          adapter.start();
          log.info("started adapter transport: {}", adapter.name());
      } catch (Exception e) {
          SafeLog.error(log,
              "Adapter " + adapter.name()
                  + " failed to start; continuing with the remaining adapters",
              e);
      }
  }
  ```
  The catch is `Exception` rather than `RuntimeException` because
  `SimpleXAdapter.start()` throws `MessagingException` (checked).
- **Test isolation.** The activation test uses
  `@ApplicationScoped` recording fakes (mirroring
  `AdapterRegistryTest`'s `FakeAdapterX`/`FakeAdapterY` pattern)
  so it does not need real subprocesses or the cross-module Fakes.
  M1-109's IT is the place where real production adapters meet
  the cross-module Fakes; this test stays narrower.
- **D47 group-approval gate is unaffected.** This ticket changes
  startup wiring only; the group-approval intake gate (M1-110..M1-114)
  is downstream of `AdapterRegistry.start()` and routes messages
  the same way regardless of which Producers register the adapters.
