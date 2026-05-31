---
id: M1-120
title: "SimpleX + Signal CDI activation (D46)"
status: pending
created: 2026-05-31
last_updated: 2026-05-31
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProductionAdapterBeans.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/MessagingStartup.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProductionAdapterActivationTest.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfig.java
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
