# Test pyramid

Three layers, each with a single responsibility. A test belongs to exactly one layer; if you find yourself reaching across, the test is at the wrong layer.

The split lands as the structural fix for M1-044b's premise-fail #2: when handler tests routed through `adapter.deliverDm → InboundRouter → handler`, a router-level edit (invite gate / ban check splice) changed handler-test outcomes even though the handler under test was byte-for-byte unchanged. The seven surfaced failures (5 unknown-DM + 2 banned-DM) all happened in `AddSourceCommandHandlerTest` + `AddSourceBanCheckOrderingTest`. After this convention lands those tests stop exercising the router at all; router changes can only break router-tier tests + ITs.

## Handler unit tests

**Responsibility.** Pin the behavior of one `CommandHandler` implementation in isolation: argument parsing, permission gates, collaborator orchestration, reply-building. One `@Test` per behavioral branch the handler owns.

**MAY use:**
- Plain JUnit 5 (`@Test`, `@BeforeEach`, `Assertions.*`). No Quarkus boot, no CDI container.
- Direct construction: `new HandlerUnderTest()`, then assign package-private collaborator fields by hand.
- Hand-rolled recording subclasses for collaborators (`BundleLoader`, `DataSource`, `UrlProbe`, `EligiblePostQuery`, etc.). The project standard is a `public static final class` inside the test that extends or implements the collaborator's production type and records calls into a `List` or counter. Mockito is **not** on the classpath — do not add it.
- The real production `BundleLoader` (constructed by hand and `load()` invoked explicitly, since `@PostConstruct` does not fire without CDI) when the test asserts bundle-driven literal text. A `RecordingBundleLoader` is fine for assertions about which keys are looked up.
- A hand-constructed `InboundContext` whose `setAdapterName("inmemory")` runs in `@BeforeEach`, then assigned to handlers that read it.

**MUST NOT use:**
- `@QuarkusTest` or `@TestProfile`.
- `@Inject` or any CDI annotation.
- `adapter.deliverDm(...)` or any other path that exercises `InboundRouter`. If the test calls `router.onMessage`, it is a router-tier test, not a handler-tier test.
- A real `DataSource` connection. Stub the JDBC chain (`DataSource` → `Connection` → `PreparedStatement` → `ResultSet`) with hand-rolled subclasses that return canned `ResultSet` rows. The integration-tier ITs cover real-Postgres behavior.

**Canonical examples:**
- `HelpCommandHandlerTest` — the simplest shape. Constructs the handler, assigns the real `BundleLoader` (or a `RecordingBundleLoader` spy), calls `handler.handle()` direct.
- `SummaryCommandHandlerTest` — the larger shape. Seven collaborators stubbed; nine scenarios, one per behavioral branch.
- `AddSourceCommandHandlerTest` — pins URL-probe outcomes per scenario via a hand-rolled `UrlProbe` subclass.

## Router unit tests

**Responsibility.** Pin the behavior of `InboundRouter` in isolation: normalization, size cap, slash vs. chat-mode dispatch, unknown-command reply, exception → internal-error reply, auto-register call, intake-order (ban check / invite gate when those land).

**MAY use:**
- Plain JUnit 5 or `@QuarkusTest` — both shapes ship today (`InboundRouterNormalizeTest` is plain JUnit on the static `normalize()` function; `InboundRouterTest` is `@QuarkusTest` for the dispatch end-to-end). New router tests prefer the plain-JUnit shape when feasible.
- A stub `Instance<CommandHandler>` returning recording handlers; the test asserts the right handler was dispatched, not the handler's own reply.
- A stub `AutoRegisterService` that records `(sender, adapterName)` invocations.
- The real `InboundContext` request-scope bean when `@QuarkusTest` is used.

**MUST NOT use:**
- A production `CommandHandler` implementation (`AddSourceCommandHandler`, `SummaryCommandHandler`, `HelpCommandHandler`) — the router test does not care about the handler's internals, only that dispatch reached the right name. Use a `public static final class RecordingHandler implements CommandHandler` instead.
- `adapter.deliverDm(...)` — the router's entry point is `onMessage(InboundMessage, String)`; call it directly.

**Canonical examples:**
- `InboundRouterTest` — slash dispatch, unknown command, chat-mode fallback, internal-error replies.
- `InboundRouterNormalizeTest` — NFKC + bidi-strip + zero-width-strip + fenced-code carve-out, on the static `normalize()`.
- `InboundRouterContactIdRedactionTest` — log redaction at the three error sites.

## Integration tests

**Responsibility.** Prove the full chain (adapter → router → handler → DB → outbound) end-to-end against a real Postgres + the real CDI graph. The IT layer is the spec-conformance backstop the handler-tier and router-tier tests can no longer provide.

**MAY use:**
- `@QuarkusTest` with `@TestProfile(MvpProfile.class)` activating `inmemory` + the `allow-low-trust` opt-in.
- The real `DataSource` (Postgres via Quarkus dev-services or Testcontainers).
- `InMemoryAdapter.deliverDm(...)` + `inMemoryAdapter.sentMessages()` to drive and observe the full round-trip.
- `LoopbackProbe` / equivalent `@Alternative` to substitute network-egress collaborators where running an in-process server is more sensible than hitting the internet.

**MUST NOT use:**
- Direct `handler.handle(...)` calls — that's a handler-tier shape. ITs always go through the adapter.

**Canonical examples:**
- `AddSourceIT` — full chain for /add-source: `inmemory` adapter → router → AddSource handler → real source/source_subscription/audit_log tables.
- `SummaryIT` — full chain for /summary: seeded posts → handler → real EligiblePostQuery → real cluster + prose generator (with `TestLlmProvider` stubbing the LLM).
- `AddSourceAdapterScopeIT`, `SummaryAdapterScopeIT`, `AdapterRouterIT` — multi-adapter and adapter-scope behavior.

## Choosing the layer

A simple test for placement: which production class is the test exercising directly?

- One `CommandHandler` implementation, no router involvement → **handler unit test**.
- `InboundRouter` itself, no real handler implementations → **router unit test**.
- The wired chain (adapter ↔ router ↔ handler ↔ DB) → **integration test**.

If a single test asserts more than one layer's invariants, split it. Cross-layer assertions are how M1-044b's "stays green" claim turned into seven surprise failures.
