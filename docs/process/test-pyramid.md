# Test pyramid

Three layers, each with a single responsibility. A test belongs to exactly one layer; if you find yourself reaching across, the test is at the wrong layer.

The split lands as the structural fix for M1-044b's premise-fail #2: when handler tests routed through `adapter.deliverDm → InboundRouter → handler`, a router-level edit (invite gate / ban check splice) changed handler-test outcomes even though the handler under test was byte-for-byte unchanged. The seven surfaced failures (5 unknown-DM + 2 banned-DM) all happened in `AddSourceCommandHandlerTest` + `AddSourceBanCheckOrderingTest`. After this convention lands those tests stop exercising the router at all; router changes can only break router-tier tests + ITs.

## Handler unit tests

**Responsibility.** Pin the behavior of one `CommandHandler` implementation in isolation: argument parsing, permission gates, collaborator orchestration, reply-building, and the handler's transactional contract with the DB where the contract IS the SQL. One `@Test` per behavioral branch the handler owns.

**MUST NOT use (applies to BOTH shapes below):**
- `adapter.deliverDm(...)` or any other path that exercises `InboundRouter`. If the test calls `router.onMessage`, it is a router-tier test, not a handler-tier test.

The router-leak prohibition is the load-bearing convention this layer enforces. It exists because of M1-044b premise-fail #2: when handler tests routed through `adapter.deliverDm → InboundRouter → handler`, a router-level edit (invite gate / ban check splice) changed handler-test outcomes even though the handler under test was byte-for-byte unchanged. After this convention lands those tests stop exercising the router at all; router changes can only break router-tier tests + ITs. The rule applies regardless of which shape below the test takes.

Two shapes ship today, each fitting a different handler type. Pick the shape that matches the handler under test; both are equally legitimate.

### Shape A: Collaborator-orchestrator

**When to use.** The handler has ≥2 non-DB collaborators with rich orchestration logic (`UrlProbe`, `EligiblePostQuery`, `ClusterTraversal`, `SummaryProseGenerator`, etc.). The interesting behavior is how the handler sequences and reacts to those collaborators; the DB is incidental and can be stubbed without losing test value.

**MAY use:**
- Plain JUnit 5 (`@Test`, `@BeforeEach`, `Assertions.*`). No Quarkus boot, no CDI container.
- Direct construction: `new HandlerUnderTest()`, then assign package-private collaborator fields by hand.
- Hand-rolled recording subclasses for collaborators (`BundleLoader`, `DataSource`, `UrlProbe`, `EligiblePostQuery`, etc.). The project standard is a `public static final class` inside the test that extends or implements the collaborator's production type and records calls into a `List` or counter. Mockito is **not** on the classpath — do not add it.
- The real production `BundleLoader` (constructed by hand and `load()` invoked explicitly, since `@PostConstruct` does not fire without CDI) when the test asserts bundle-driven literal text. A `RecordingBundleLoader` is fine for assertions about which keys are looked up.
- A hand-constructed `InboundContext` whose `setAdapterName("inmemory")` runs in `@BeforeEach`, then assigned to handlers that read it.

**MUST NOT use:**
- `@QuarkusTest` or `@TestProfile`.
- `@Inject` or any CDI annotation.
- A real `DataSource` connection. Stub the JDBC chain (`DataSource` → `Connection` → `PreparedStatement` → `ResultSet`) with hand-rolled subclasses that return canned `ResultSet` rows. The integration-tier ITs cover real-Postgres behavior.

**Canonical examples:**
- `HelpCommandHandlerTest` — the simplest shape. Constructs the handler, assigns the real `BundleLoader` (or a `RecordingBundleLoader` spy), calls `handler.handle()` direct.
- `SummaryCommandHandlerTest` — the larger shape. Seven collaborators stubbed; nine scenarios, one per behavioral branch.
- `AddSourceCommandHandlerTest` — pins URL-probe outcomes per scenario via a hand-rolled `UrlProbe` subclass.

### Shape B: Thin-SQL

**When to use.** The handler has ≤1 non-DB collaborator AND ≥2 DB statements that depend on real-DB semantics — triggers, `FOR UPDATE` locking, `RETURNING` clauses, PK / `UNIQUE` / FK / `CHECK` constraints, or partition-routing. Thin-SQL handlers have no rich orchestration logic to assert in isolation; stubbing the JDBC chain reduces tests to whitebox tautologies (asserting that the handler issued the exact SQL string the test stubbed). The handler's behavioral contract IS the DB interaction (lock acquisition, trigger-driven state, constraint enforcement); the test must observe the DB to verify the contract.

The orthodox alternative — stubbed JDBC at the handler tier plus a separate migration-test layer that asserts trigger and constraint behavior in isolation — is rejected here. No migration-test layer exists in the project; building one would be larger-scope than codifying the shape that already ships, and would add a third layer whose only job is to mirror invariants the handler tier already exercises end-to-end against the real Postgres image.

**MAY use:**
- `@QuarkusTest` (no `@TestProfile` is needed; the default profile activates Quarkus DevServices Postgres).
- `@Inject DataSource` (the real connection pool against the DevServices Postgres image; the V1..VN migrations run on container start so every trigger, constraint, stored procedure, and `CHECK` predicate the handler depends on is in place).
- `@Inject BundleLoader`, `@Inject InboundContext`, `@Inject <HandlerUnderTest>`, plus the handler's at-most-one non-DB collaborator (resolved through CDI rather than hand-assigned).
- Direct `handler.handle(scope, rawText)` calls — same direct-dispatch shape as Shape A; the test never goes through the router.
- A `@BeforeEach` cleanup that runs SQL against the `DataSource` to drop test rows by a class-wide contact-id prefix; the cleanup may temporarily disable append-only triggers in a `try`/`finally` so the table cannot be left without its invariant.

**MUST NOT use:**
- `adapter.deliverDm(...)` or any other path that exercises `InboundRouter` (the section-root router-leak rule, repeated for emphasis — Shape B's `@QuarkusTest` boot includes the router, but the test still calls `handler.handle(...)` directly).
- Assertions that overlap with integration-tier ITs by going through `inMemoryAdapter.deliverDm(...) → router → handler`. If the test would benefit from full-chain observation, write an IT instead.

**Canonical examples:**
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java` — the primary canonical example. Exercises the M1-046 audit-before-effect transaction (`SELECT ... FOR UPDATE` on the actor row + target lookup + audit-row INSERT + `UPDATE users` against the V5 last-admin-protection trigger).
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java` — companion to GrantAdmin; observes the V5 `trg_last_admin_protection_update` trigger raising `last_admin_protection` and the handler translating it to `error.revoke_admin.last_admin`.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java` — multi-row audit correlation (BAN + INVITE_REVOKE sharing one request_id) + V5 trigger-driven preban INSERT path.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerTest.java` — `SET LOCAL infochat.request_id` + V5 `delete_preban_user` SECURITY DEFINER stored procedure via JDBC `CALL`.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java` — per-adapter open cap + global contact cap + `SELECT ... FOR UPDATE` on `invite_code` + `gen_random_uuid()` from pgcrypto.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java` — actor `FOR UPDATE` TOCTOU close + two-transitions-in-one-statement `UPDATE` (`probation_until` + `registration_state` CASE).

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
  - Handler has ≤1 non-DB collaborator AND ≥2 real-DB-dependent statements → [Shape B (Thin-SQL)](#shape-b-thin-sql).
  - Otherwise → [Shape A (Collaborator-orchestrator)](#shape-a-collaborator-orchestrator).
- `InboundRouter` itself, no real handler implementations → **router unit test**.
- The wired chain (adapter ↔ router ↔ handler ↔ DB) → **integration test**.

If a single test asserts more than one layer's invariants, split it. Cross-layer assertions are how M1-044b's "stays green" claim turned into seven surprise failures.
