---
id: M1-035b
title: AdapterRegistry, InboundRouter, startup gates
status: pending
created: 2026-05-17
last_updated: 2026-05-17
blocked_by:
  - M1-035a
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/io/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/main/java/io/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/io/infochat/provider/messaging/MessagingStartup.java
  - infochat-provider/src/main/java/io/infochat/provider/messaging/CommandHandler.java
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java
  - infochat-provider/src/test/java/io/infochat/provider/messaging/StartupGatesTest.java
  - infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java (the M1-035 umbrella's cross-cutting inbound→register→/help→outbound IT — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention; this subticket asserts the registry, router, and per-gate unit/integration behavior only)
  - any change under infochat-messaging-adapter/ (the SPI surface and InMemoryAdapter are FROZEN at M1-035a's umbrella round; if a defect surfaces here, file a follow-up ticket against M1-035a — never amend a passed commit per docs/process/workflow.md §M1 workflow)
  - any /help command handler, AutoRegisterService, BundleLoader, BundleKeys, or en.properties bundle file (M1-035c lands the first command + the auto-register service + the bundle infrastructure; this subticket's InboundRouter stubs the slash-prefix branch via the bundle key `error.unknown_command` and a deterministic chat-mode reply but does NOT author the bundle infrastructure — that is M1-035c's territory)
  - any Flyway migration under infochat-core/src/main/resources/db/migration/ (T1-E is migration-free per the T1-E handoff; reaching for V12 is an escalation trigger, not an authoring choice)
  - any SimpleX or Signal adapter bean (those are T3-A and out of T1-E entirely; the AdapterRegistry is shaped to accept multi-adapter D46 cases via CDI bean discovery, but the SimpleX/Signal beans themselves are NOT authored here)
  - any bootstrap-admin @Startup bean from docs/spec/deployment.md §Operator inputs (the bean that ensures `infochat.admin.contact-id` exists with `is_admin=true` is deferred; MVP relies on a manual SQL grant in docker-compose bootstrap or a future ticket per the T1-E handoff — the umbrella M1-035 calls out this gap in its Big-picture notes)
  - any invite-gating (D44), slow-start probation filter (D45), `/ban` / `/unban` (D11), or chat-mode handler proper (the chat-mode branch in InboundRouter returns a deterministic "chat-mode is not in MVP" reply via a bundle key; the chat-mode dispatcher lands in T2-D; invite + probation + ban land in T2-A; this subticket leaves a one-line comment in the entry point naming the missing intake steps)
  - any LLM output sanitizer integration from docs/spec/security.md §LLM output sanitizer (the chat-mode reply is a deterministic localization-bundle string, not LLM-authored; the sanitizer lands in T1-F's `/summary`)
  - any TranslationProvider integration / `/lang` (deferred to T2-C; the InboundRouter's outbound replies use English bundle keys only — translation pre-pass through TranslationProvider is T2-C's wiring)
  - any inbound back-pressure queue / per-user-fair scheduler / synchronous-throttle-reply path from docs/design/06-messaging.md §6.3.7 (the registered handler call-through is synchronous; the bounded-queue machinery is deferred to T2-G or whenever SimpleX/Signal land)
  - any transport-layer inbound size cap enforcement / application-level Command body cap / Chat-mode body cap (the M1-035a `maxInboundMessageBytes` field exists on the record; the synchronous-drop machinery is deferred. This subticket MAY apply a hardcoded sensible application-level cap (e.g., 4096 bytes) at the InboundRouter entry point with a one-line comment naming the profile-driven follow-up, OR may defer the cap entirely with the same comment — pick whichever is cleaner)
  - any confirmation-pending state machine from docs/spec/commands.md §Surface conventions ("Confirmation for destructive commands"); /help is not destructive; MVP has no destructive commands; the in-memory confirmation map lives in T2-A
  - any audit_log row writes from the router (Provider command handlers write audit rows per docs/design/06-messaging.md §6.11; /help is not auditable per docs/design/00-mvp.md §5 Operations carve-out — "Audit-log entries beyond bot-admin bootstrap and `/add-source` are deferred")
acceptance:
  - "infochat-provider/src/main/java/io/infochat/provider/messaging/CommandHandler.java exists and declares a Java interface consumed by InboundRouter via CDI `Instance<CommandHandler>` discovery. The interface MUST expose at minimum (a) the command name the handler binds to (a `String name()` accessor, OR an equivalent CDI-qualifier mechanism) and (b) a handler-entry method whose argument list carries the resolved user identifier + the ScopeRef + the inbound text or parsed args. Exact method shape is implementer's choice as long as InboundRouter's `Instance<CommandHandler>` lookup can dispatch through it and M1-035c's HelpCommandHandler can implement it without a non-additive interface change. Verify: `grep -E 'interface CommandHandler' CommandHandler.java` returns ≥1 match"
  - "infochat-provider/src/main/java/io/infochat/provider/messaging/AdapterRegistry.java exists and declares an `@ApplicationScoped` (or equivalent CDI-singleton) class `AdapterRegistry`. Verify: `grep -E 'class AdapterRegistry' AdapterRegistry.java` returns ≥1 match AND the file references `@Inject Instance<MessagingAdapter>` or `@Any Instance<MessagingAdapter>` as the bean-discovery mechanism (`grep -E 'Instance<MessagingAdapter>' AdapterRegistry.java` returns ≥1 match)"
  - "AdapterRegistry reads `infochat.adapters` as a comma-separated property and activates the subset of registered beans whose `name()` appears in the value. Verify: `grep -E 'infochat\\.adapters' AdapterRegistry.java` returns ≥1 match"
  - "AdapterRegistry applies the six startup gates from docs/design/06-messaging.md §6.7 in the documented order. Each gate's failure throws an `IllegalStateException` whose message names the offending adapter (or the offending property value). The gates: (1) `infochat.adapters` non-empty; (2) every name resolves to a registered bean; (3) `supportsMarkdownLinks=false` per §6.2.1; (4) production-exclusion (`inmemory` + multi-adapter set rejected) per §6.6; (5) per-adapter LOW-trust opt-in via `infochat.adapters.<name>.allow-low-trust=true` per §6.8; (6) `supportsMentionByContactId=false` + group-SPI-wired refusal per §6.3.3 / §6.7. The StartupGatesTest exercises all six (one @Test per gate)"
  - "AdapterRegistry, for each activated adapter, calls `adapter.setInboundHandler(router)` exactly once at startup so its inbound deliveries reach the Provider-side router. Verify: AdapterRegistryTest asserts that for a configuration `infochat.adapters=inmemory`, the InMemoryAdapter receives one setInboundHandler call and its registered handler is the Provider's InboundRouter bean"
  - "AdapterRegistry emits one INFO log line per activated adapter per docs/design/06-messaging.md §6.8 format: `activating adapter: <name> (trust=<HIGH|LOW>[; allow-low-trust=true])`. Verify: `grep -E 'activating adapter' AdapterRegistry.java` returns ≥1 match"
  - "infochat-provider/src/main/java/io/infochat/provider/messaging/InboundRouter.java exists, declares a CDI bean that implements the M1-035a `MessagingAdapter.InboundHandler` interface (`void onMessage(InboundMessage msg)`), and applies the spec-required normalization pass FIRST at entry per docs/spec/security.md §Authorization model step 1.7 + docs/spec/commands.md §Surface conventions (bidi-strip, zero-width-strip, leading-whitespace-trim, empty-drop). Verify: `grep -E 'implements\\s+MessagingAdapter\\.InboundHandler|implements\\s+InboundHandler' InboundRouter.java` returns ≥1 match"
  - "InboundRouter drops empty / whitespace-only / bidi-only / zero-width-only inbound text before any further dispatch (no slash-prefix check, no outbound reply, no audit row). Verify: InboundRouterTest asserts that an empty body, a whitespace-only body, a body of only U+200B (zero-width space), and a body of only U+202E (right-to-left override) ALL produce zero outbound messages on the test InMemoryAdapter and no exception"
  - "InboundRouter trims leading whitespace before the slash-prefix check so `  /help` parses as `/help` per docs/spec/commands.md §Surface conventions. Verify: InboundRouterTest asserts that a body `  /help` from a registered user produces the same outbound reply as a body `/help`"
  - "InboundRouter routes a slash-prefixed inbound body to the (M1-035c-authored) command-dispatch surface; in this subticket the dispatch surface is a stub that, for any non-existent command (including `/help` until M1-035c lands), responds with a friendly bundle-key-based error reply. The bundle infrastructure is shipped by M1-035c; this subticket's router uses a deterministic English string literal for the error message until M1-035c supersedes it with the bundle lookup. Verify: InboundRouterTest asserts an unknown command body `/xyz` from a registered user produces one outbound message whose body is the friendly unknown-command reply (NOT empty, NOT an exception trace)"
  - "InboundRouter routes a non-slash-prefixed inbound body to a deterministic chat-mode stub reply (NOT a silent drop, NOT an exception). The reply text is a deterministic English string literal; the chat-mode dispatcher proper lands in T2-D. Verify: InboundRouterTest asserts an inbound body `hello there` from a registered user produces one outbound message whose body is the deterministic chat-mode-not-in-MVP reply"
  - "InboundRouter's exception-handling path catches command-handler exceptions and emits a friendly internal-error reply WITHOUT interpolating the exception's `getMessage()` into the user-visible text (per the T1-E handoff: that is the M1-020 sanitization concern; using a fixed bundle-key error string sidesteps it for MVP). Verify: `grep -E 'getMessage|toString' InboundRouter.java` returns ZERO matches inside any code path that builds the OutboundMessage body string (a `getMessage()` call inside an SLF4J `log.error` call is permitted; a `getMessage()` call inside a `new OutboundMessage(scope, msg, ...)` string is NOT)"
  - "InboundRouter's exception-logging code path uses raw SLF4J (`org.slf4j.Logger`) for now; M1-020 will retrofit SafeLog when un-deferred. Verify: `grep -E 'LoggerFactory\\.getLogger\\(InboundRouter' InboundRouter.java` returns ≥1 match"
  - "InboundRouter's entry-point method body includes a one-line comment naming the missing intake steps that T2-A wires: ban check, invite gate, probation filter. Verify: `grep -E 'TODO|T2-A|ban check|invite gate|probation' InboundRouter.java` returns ≥1 match (the comment may use any of those phrasings)"
  - "infochat-provider/src/main/java/io/infochat/provider/messaging/MessagingStartup.java exists, is `@ApplicationScoped` (or equivalent), and is annotated `@Startup` (Quarkus) or observes the `@Observes StartupEvent` so the Quarkus container drives its lifecycle hook on Provider boot. Verify: `grep -E '@Startup|StartupEvent' MessagingStartup.java` returns ≥1 match"
  - "MessagingStartup drives the AdapterRegistry's `start()` lifecycle once Quarkus is up. Per-adapter `start()` failure is logged at ERROR via SLF4J. Verify: `grep -E 'log\\.error' MessagingStartup.java` returns ≥1 match"
  - "infochat-provider/src/test/java/io/infochat/provider/messaging/StartupGatesTest.java exists with one `@Test` per gate (six gates → ≥6 `@Test` methods). Each test exercises a failing configuration and asserts the registry raises `IllegalStateException` whose message names the offending adapter (or property value). Verify: `grep -cE '^\\s*@Test\\b' StartupGatesTest.java` returns ≥ 6"
  - "StartupGatesTest gate 1 (empty `infochat.adapters`) — IllegalStateException; message contains the substring `no adapters configured` (or equivalent literal that pinpoints the empty-list problem). Verify: the @Test asserts the exception type AND a substring match on the message"
  - "StartupGatesTest gate 2 (unknown name in `infochat.adapters`) — IllegalStateException; message contains the unknown entry's literal name. Verify: the @Test seeds `infochat.adapters=inmemory,nope` and asserts the exception message contains the literal string `nope`"
  - "StartupGatesTest gate 3 (a test-only fake adapter declaring `supportsMarkdownLinks=true`) — IllegalStateException; message names the adapter. Verify: the @Test seeds a fake CDI bean whose capabilities() returns `supportsMarkdownLinks=true` and asserts the exception message contains the fake adapter's `name()`"
  - "StartupGatesTest gate 4 (production-exclusion — `infochat.adapters=inmemory,otheradapter` where the second is a test-only fake) — IllegalStateException; message names BOTH `inmemory` and the conflicting adapter. Verify: the @Test seeds the multi-adapter list and asserts the exception message contains both adapter names"
  - "StartupGatesTest gate 5 (InMemoryAdapter at default LOW trust + missing `infochat.adapters.inmemory.allow-low-trust=true`) — IllegalStateException; message names `inmemory` and the missing property. Verify: the @Test omits the `allow-low-trust` property and asserts the exception message contains both `inmemory` and either `allow-low-trust` or `trust=LOW`"
  - "StartupGatesTest gate 6 (a test-only fake adapter declaring `supportsMentionByContactId=false` AND group-SPI wired) — IllegalStateException; message names the adapter. Since MVP has no group SPI wired, the test uses a fake group-SPI-wired flag exposed via a test-only adapter bean (the production check is `caps.supportsMentionByContactId == false AND deployment.groupSpiWired == true` → reject). Verify: the @Test asserts the exception message contains the fake adapter's `name()`"
  - "infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java exists and contains ≥2 `@Test` methods covering: (1) single-adapter happy path — with `infochat.adapters=inmemory` and `infochat.adapters.inmemory.allow-low-trust=true`, exactly one adapter activates and its `setInboundHandler` is called exactly once with the Provider's InboundRouter; (2) multi-adapter happy path — with TWO test-only InMemoryAdapter beans bearing distinct `name()` returns (e.g. `inmemory` and `inmemory2`) and `infochat.adapters=inmemory,inmemory2`, BOTH activate. Verify: `grep -cE '^\\s*@Test\\b' AdapterRegistryTest.java` returns ≥ 2"
  - "infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java exists and contains ≥5 `@Test` methods covering: (1) empty / whitespace-only / bidi-only / zero-width-only inbound text is dropped (no outbound, no exception); (2) leading whitespace + `/help` parses as `/help`; (3) chat-mode (non-slash) inbound produces the deterministic chat-mode-not-in-MVP reply; (4) unknown command produces the friendly unknown-command reply; (5) command-handler exception path produces the friendly internal-error reply WITHOUT interpolating the exception text. Verify: `grep -cE '^\\s*@Test\\b' InboundRouterTest.java` returns ≥ 5"
  - "mvn -B -pl infochat-provider test exits 0; surefire reports show at least the three new test classes executing (AdapterRegistryTest, StartupGatesTest, InboundRouterTest). Verify: `grep -rE 'Tests run: [1-9]' infochat-provider/target/surefire-reports` returns at least three matches across the new classes"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass alongside the new Provider-side messaging beans"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java (≥2 @Test methods covering single-adapter happy path and multi-adapter happy path)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/StartupGatesTest.java (≥6 @Test methods, one per spec-required startup gate)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java (≥5 @Test methods covering normalization-pass behavior, slash-prefix dispatch, chat-mode stub, unknown-command reply, exception-path reply)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c, possibly modified by M1-035a)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (M1-035a)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - all M1-008/008a/008b/008c schema tests
    - all M1-022/023/024/025/026 ingest-source tests
    - all M1-027/028 outbox/NOTIFY tests
    - all M1-032/033/034a/034b eval-pipeline tests
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/commands.md §Surface conventions
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Trust boundaries
  - docs/design/06-messaging.md §6.2.1 Startup validation — supportsMarkdownLinks fail-fast
  - docs/design/06-messaging.md §6.6 InMemoryAdapter
  - docs/design/06-messaging.md §6.7 Adapter selection (multi-adapter, D46)
  - docs/design/06-messaging.md §6.8 Trust levels and operator opt-in
decision_refs:
  - D10
  - D11
  - D30
  - D46
---

# M1-035b: AdapterRegistry, InboundRouter, startup gates

## Context

Second subticket of the M1-035 umbrella (per
`docs/process/workflow.md` §Ticket-ID placeholder convention — the
umbrella + subticket idiom). M1-035a shipped the SPI fill-in plus
the concrete `InMemoryAdapter` under `infochat-messaging-adapter`.
This subticket lands the **Provider-side wiring**: the
`AdapterRegistry` CDI bean that discovers every
`MessagingAdapter` bean and activates the configured subset, the
`InboundRouter` that consumes inbound `InboundMessage` deliveries
from each activated adapter and applies the spec-required
normalization pass before slash-prefix dispatch, the
`MessagingStartup` `@Startup` bean that drives the registry's
`start()` lifecycle once Quarkus is up, and the **six startup
gates** docs/design/06-messaging.md §6.7 + §6.2.1 + §6.6 + §6.8
mandate.

The startup gates are spec-load-bearing security guarantees:
`supportsMarkdownLinks=false` prevents an LLM-authored
clickable-link injection vector;
production-exclusion prevents in-memory identity assertions from
being trusted in a production deployment; LOW-trust opt-in forces
a conscious operator choice; `supportsMentionByContactId=false` +
group-SPI-wired refusal guards against display-name spoofing in
groups. Getting any one of these wrong is a real attack surface
even though MVP has only the in-memory adapter today. All six ship
in this subticket because the gates are cheap and prevent silent
configuration drift the day SimpleX lands. Specifically the
production-exclusion gate is shaped generically ("if `inmemory`
is in the activated set AND the activated set has size > 1, reject")
so it generalizes cleanly when T3-A adds the SimpleX/Signal beans.

The MVP exit criterion §3 from docs/design/00-mvp.md §6 — "A
non-admin user, sending their first DM via `InMemoryAdapter`, is
auto-registered and receives `/help`" — depends on this
subticket's router routing the inbound DM through the
normalization pass, calling M1-035c's `AutoRegisterService`
before slash-prefix dispatch, and dispatching `/help` to
M1-035c's `HelpCommandHandler`. This subticket's router is the
LAST common code path before commands branch — every command
handler (including `/help`) consumes the router's output. The
M1-035 umbrella's IT asserts the full round-trip; this subticket's
unit tests assert the router's per-branch behavior.

`security_relevant: true` — every one of the six startup gates is
a spec-mandated security guarantee. The InboundRouter's
normalization-first discipline is also security-mandated per
docs/spec/security.md §Authorization model step 1.7 (Unicode
normalize the body BEFORE any body-content check, so a `/` cannot
be disguised by homoglyphs or bidi overrides).

`round_cap: 3` — high-complexity / high-risk per CLAUDE.md §M1
workflow allows the third round. The startup-gate test matrix
(six gates × happy-path + sad-path) plus the InboundRouter
normalization-pass correctness argument plus the multi-adapter
D46 selection-shape correctness all carry weight; the third
review round is the right margin for this surface.

## Definition of Done

- `CommandHandler` Java interface under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`:
  - Minimal interface that the `InboundRouter`'s slash-prefix
    branch dispatches through via `Instance<CommandHandler>`
    CDI discovery. At minimum the interface exposes (a) a
    command-name binding (a `String name()` accessor, or an
    equivalent CDI-qualifier mechanism) and (b) a handler-entry
    method whose arguments carry the resolved user identifier
    + the ScopeRef + the inbound text or parsed args (e.g.
    `OutboundMessage handle(UUID userId, ScopeRef scope,
    String[] args)`). The exact method shape is implementer's
    choice as long as M1-035c's `HelpCommandHandler` can
    implement it additively.
  - No `CommandHandler` implementations are shipped in this
    subticket; the `Instance<CommandHandler>` lookup resolves
    to an empty set at runtime and the slash-prefix branch
    replies to every command with the unknown-command bundle
    key. M1-035c lands the first impl (`HelpCommandHandler`).

- `AdapterRegistry` CDI bean under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`:
  - `@ApplicationScoped` (or equivalent CDI singleton). Discovers
    every CDI bean implementing `MessagingAdapter` via
    `@Inject Instance<MessagingAdapter>` (or `@Any Instance<...>`).
  - Reads `infochat.adapters` (comma-separated names) from
    Quarkus configuration and activates the subset whose
    `name()` value appears in the property.
  - Applies the six startup gates in the order documented in
    Big-picture notes below. Each gate failure throws
    `IllegalStateException` whose message names the offending
    adapter (or the offending property value) so the operator
    gets actionable feedback in the Provider's startup log.
  - For each activated adapter: calls
    `adapter.setInboundHandler(inboundRouter)` so the adapter's
    inbound deliveries reach the Provider-side router.
  - Emits one INFO log line per activated adapter per
    docs/design/06-messaging.md §6.8 format:
    `activating adapter: <name> (trust=<HIGH|LOW>[; allow-low-trust=true])`.
    The optional `; allow-low-trust=true` tail appears only when
    the adapter's `trustLevel() == LOW`.
- `InboundRouter` CDI bean implementing the M1-035a
  `MessagingAdapter.InboundHandler` interface
  (`void onMessage(InboundMessage msg)`):
  - **Step 1: normalization pass.** Apply
    bidi-control-strip + zero-width-strip + leading/trailing
    whitespace trim to the inbound `msg.text()`. The
    normalization is the same pass spec'd in
    docs/spec/security.md §Authorization model step 1.7 +
    docs/spec/commands.md §Surface conventions. Use
    `java.text.Normalizer.normalize(text, Form.NFKC)` followed by
    the strip helpers for bidi control characters
    (U+202A–U+202E, U+2066–U+2069) and zero-width characters
    (U+200B, U+200C, U+200D, U+FEFF). The normalized body
    REPLACES the raw body for every downstream consumer; the
    raw body is discarded after this step (it never reaches the
    LLM, the command parser, or any DB query).
  - **Step 2: empty-drop.** If the normalized body is empty or
    whitespace-only, return silently — no outbound reply, no
    audit row, no exception.
  - **Step 3: AutoRegisterService.** (Stubbed in this ticket — the
    real service lands in M1-035c.) The InboundRouter has a
    `@Inject AutoRegisterService autoRegister` field; the call
    site is wired to a no-op stub method `resolveUser(Identity,
    String adapter)` that M1-035c will fill in. The seam is
    visible at the entry-point method body.
  - **Step 4: leave a one-line comment** in the entry-point body
    naming the missing intake steps T2-A wires: ban check,
    invite gate, probation filter. The comment captures the
    seam so a future reader sees the deferred intake order.
  - **Step 5: slash-prefix branch.** If the normalized body
    starts with `/`, dispatch to the (M1-035c-authored)
    `CommandDispatcher` surface. In this subticket the
    dispatcher surface is a minimal stub:
    `@Inject Instance<CommandHandler> handlers` discovers
    command handlers by CDI; the router looks up by command
    name and either invokes the handler or replies with the
    friendly unknown-command bundle key. Until M1-035c lands
    `/help`, the only handler is the unknown-command fallback;
    the router's behavior is still correct (every command
    looks unknown).
  - **Step 6: chat-mode branch.** If the normalized body does
    NOT start with `/`, reply with a deterministic English
    string literal: `"Chat-mode replies are not in the MVP; try
    /help for the available commands."` (Exact wording impl-choice
    as long as it is short and deterministic.) The chat-mode
    dispatcher proper lands in T2-D; this stub prevents a
    silent drop.
  - **Step 7: exception handling.** Any uncaught exception in
    the dispatch branch is logged at ERROR via SLF4J
    (`LoggerFactory.getLogger(InboundRouter.class)`) and a
    friendly internal-error reply is sent. The reply text is a
    fixed English string literal; the exception's
    `getMessage()` is NOT interpolated into the user-visible
    reply (M1-020 sanitization concern).
  - The router's outbound replies are sent via the same
    `MessagingAdapter` the inbound came from. The router needs
    a handle on the originating adapter; the simplest path is
    for AdapterRegistry to pass `(adapter, message)` pairs to
    the router rather than just `message`. Either evolve
    `InboundHandler.onMessage` to a two-arg shape OR have the
    AdapterRegistry register a per-adapter handler that closes
    over the adapter — implementer's choice. The latter shape
    avoids changing the M1-035a SPI signature.
- `MessagingStartup` `@Startup` (or `@Observes StartupEvent`)
  bean:
  - Drives the AdapterRegistry's `start()` lifecycle once
    Quarkus is up. Per-adapter `start()` failure is logged at
    ERROR via SLF4J. MVP has one adapter (InMemoryAdapter has
    no transport to start), so this is essentially a no-op for
    the InMemory case — but the shape is in place for SimpleX
    / Signal later.
  - The readiness probe wiring from §6.7 is NOT in scope; the
    bean log lines are sufficient for MVP. T3-A SimpleX/Signal
    will add the readiness wiring.
- Three new test classes under
  `infochat-provider/src/test/java/io/infochat/provider/messaging/`:
  - `StartupGatesTest` — one `@Test` per of the six startup
    gates (six gates → at least six `@Test` methods).
  - `AdapterRegistryTest` — single-adapter happy path +
    multi-adapter happy path.
  - `InboundRouterTest` — normalization-pass behavior +
    slash-prefix dispatch + chat-mode stub + unknown-command
    reply + exception-path reply.
- `mvn -B clean verify` from the repo root exits 0. Every prior
  test continues to pass; the three new test classes execute and
  pass against a Testcontainers Postgres (the Provider tests'
  existing infra supplies the container).

## Implementation notes

- **Six startup gates, six tests.** The gate order matters: a
  configuration that violates gate 1 (empty adapters list)
  should fail with the "no adapters configured" error, not the
  gate-2 "unknown name" error. Implement the gates as a sequence
  of checks; the first failure short-circuits and raises with
  the most-specific message available at that point. Each gate
  has a corresponding @Test in StartupGatesTest; each test
  exercises ONLY that gate's failure path (other gates are
  satisfied by the test's setup).
- **Test-only fake adapter beans.** Several gate tests need a
  CDI bean implementing `MessagingAdapter` whose
  `capabilities()` returns a deliberately-broken shape (e.g.,
  `supportsMarkdownLinks=true`) or whose `name()` returns a
  test-only string (e.g., `inmemory2` for the multi-adapter
  test). The simplest path is a test-only static-nested class
  inside each test that implements `MessagingAdapter` minimally
  and is registered as a `@QuarkusTestResource` or via
  `@Alternative @Priority(...)` for that single test. Pick
  whichever Quarkus mechanism produces the smaller test diff.
- **`Instance<MessagingAdapter>` discovery.** Quarkus's
  `@Inject @Any Instance<MessagingAdapter>` returns every
  registered bean implementing the interface (including
  `@Alternative` beans if their priority is set). The registry
  iterates this `Instance` and filters by `name()` against the
  configured list. The CDI mechanism is the cleanest fit for
  the D46 multi-adapter shape — adding a new adapter is a new
  bean, not a registry edit.
- **`infochat.adapters` parsing.** Quarkus's
  `@ConfigProperty(name = "infochat.adapters") String csv`
  (or `Optional<String>` to handle the empty case explicitly)
  followed by `csv.split(",")` + trim is sufficient. The
  property is required (gate 1 raises if absent or empty); a
  more elaborate `List<String>` ConfigMapping is optional.
- **`infochat.adapters.<name>.allow-low-trust` parsing.**
  Quarkus supports property-key interpolation natively. Read
  per-adapter at gate-5 evaluation time:
  `ConfigProvider.getConfig().getValue("infochat.adapters." +
  adapter.name() + ".allow-low-trust", Boolean.class)` (default
  false). The adapter's `trustLevel()` returns the per-instance
  value; the property is the per-adapter opt-in.
- **Production-exclusion gate (#4).** Generic shape: if
  `inmemory` is in the activated set AND the activated set has
  size > 1, reject. This generalizes cleanly when T3-A adds
  SimpleX/Signal — the gate doesn't need to enumerate
  "production adapter names" anywhere; it just refuses to host
  `inmemory` alongside any other adapter. The error message
  names ALL adapters in the offending set so the operator sees
  the full picture in one line.
- **Mention-by-id gate (#6) requires a deployment.groupSpiWired
  flag.** Provider doesn't yet have a group SPI wired (T2-F).
  The natural shape is a no-op now — the check is satisfied
  vacuously (no adapter has group SPI wired) — but the gate
  code MUST be present so T2-F doesn't have to revisit
  AdapterRegistry. The simplest impl: a constant
  `private static final boolean GROUP_SPI_WIRED = false;` (or
  a future config property), with the gate's condition
  `if (caps.supportsMentionByContactId() == false &&
  GROUP_SPI_WIRED) raise(...)`. The corresponding StartupGatesTest
  uses a test-only adapter that exposes a fake group-SPI-wired
  flag via a constructor parameter so the test can exercise the
  reject branch without having to wait for T2-F.
- **InboundRouter normalization helper.** Pull the bidi-strip +
  zero-width-strip into a private static method
  `normalize(String raw)` so it's testable in isolation if the
  implementer wants to add a unit test for it (not required for
  acceptance — InboundRouterTest exercises the behavior via the
  router's entry point, which is sufficient). The helper SHOULD
  NOT live in `infochat-core` for now; if a future ticket needs
  the same normalization at another call site (e.g., a future
  search index that has the same homoglyph concern), promote it
  then. Pre-moving it is speculative.
- **Outbound reply path.** The router needs to send replies via
  the originating adapter. The handoff suggests two shapes:
  (a) evolve `InboundHandler.onMessage` to
  `(MessagingAdapter source, InboundMessage msg)` so the
  callback gets the adapter handle; or (b) keep the M1-035a SPI
  signature and have AdapterRegistry register a closure-style
  handler per adapter that captures the adapter reference.
  Shape (b) does not change the M1-035a SPI; it requires
  AdapterRegistry to wrap each adapter's `setInboundHandler`
  call with a lambda. Pick (b) unless there's a reason not to
  — it keeps the SPI evolution monotonic.
- **`MessagingStartup` resilience.** Per design §6.7
  "Per-adapter resilience", a connection failure on one
  adapter does NOT prevent the others from coming up and does
  NOT abort Provider startup. MVP has one adapter so this is
  trivially satisfied, but the shape is: iterate the activated
  list, call `start()` on each, catch and log per-adapter
  failures, do NOT propagate to the caller. T3-A's SimpleX
  startup may fail (signing server unavailable); the registry's
  resilience promise is the load-bearing contract there.
- **No CDI test bean redundancy.** Quarkus's
  `@QuarkusTest` + a test-only `@Alternative @Priority`
  InMemoryAdapter bean is the right shape for the
  AdapterRegistryTest and StartupGatesTest. The
  `infochat-messaging-adapter` module's production bean is
  the real one; tests provide an `@Alternative` only when they
  need a fake (e.g., the multi-adapter test needs a second
  bean with `name()="inmemory2"`).

## Big-picture notes

- **The six startup gates in order.** Big-picture: the gates
  proceed from coarse to specific. (1) `infochat.adapters`
  non-empty — without this, the registry has nothing to do.
  (2) Every name resolves to a bean — without this, the
  operator typed a typo. (3) `supportsMarkdownLinks=false` per
  §6.2.1 — without this, an LLM-authored URL becomes a
  clickable target. (4) Production-exclusion per §6.6 —
  without this, `inmemory` could run alongside a production
  adapter and silently launder identity assertions. (5)
  Per-adapter LOW-trust opt-in per §6.8 — without this, a
  test-trust adapter could be enabled without a conscious
  operator choice. (6) `supportsMentionByContactId=false` +
  group-SPI-wired — without this, a group adapter could
  silently fall back to display-name string matching for
  mention recognition. Each gate's @Test in StartupGatesTest
  is a regression guard against each of these failure modes.
- **The cost of these gates is three lines each.** They are
  cheap to ship now and expensive to retrofit later — once
  SimpleX lands, the gate that prevents `inmemory` from running
  alongside it is the operator's only defense against the
  test adapter accidentally being left in `infochat.adapters`
  in a production deployment. Shipping them all in T1-E means
  T3-A is purely about the SimpleX/Signal beans + their config
  surface; no T3-A reviewer needs to also re-examine the
  registry's gate logic.
- **The InboundRouter is the LAST common code path before
  commands branch.** Every future command handler (the
  /help here, /add-source and /summary in T1-F, /save in T2-B,
  /ban in T2-A, etc.) consumes the router's output. The
  router's normalization-first discipline is what makes the
  homoglyph-evasion claim in docs/spec/security.md §Authorization
  model step 1.7 a real defense — every command parser sees the
  normalized form, not the raw inbound. If a future command
  handler were to bypass the router (e.g., a /admin-reset
  ticket that reads from a special slash-command queue), that
  bypass would defeat the normalization promise. The router
  is the single intake gate; future handlers MUST stay
  downstream of it.
- **The `inmemory` literal name is load-bearing.**
  AdapterRegistry registers the InMemoryAdapter under the
  string returned by `name()`. The AutoRegisterService
  (M1-035c) inserts `users.adapter='inmemory'` verbatim. The
  cross-adapter isolation invariant from docs/spec/messaging.md
  §Per-adapter trust level uses `(adapter, contact_id)` as the
  join key, so a mismatch between AdapterRegistry's registered
  name and AutoRegisterService's insert string would silently
  create two users rows for the same human. M1-035a fixes the
  literal at `"inmemory"`; this subticket consumes the same
  literal; M1-035c writes the same literal. All three are
  pinned in the M1-035 umbrella's IT to catch any drift.
- **Subticket isolation against M1-035a and M1-035c.** This
  subticket touches only files under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`
  and `infochat-provider/src/test/java/io/infochat/provider/messaging/`.
  M1-035a's files live under `infochat-messaging-adapter/`.
  M1-035c's files live under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`
  (HelpCommandHandler, AutoRegisterService) and
  `infochat-provider/src/main/java/io/infochat/provider/bundle/`
  (BundleLoader, BundleKeys) plus the bundle resource. The
  three subtickets' `files_scope` lists are disjoint. Note:
  M1-035c's HelpCommandHandler and AutoRegisterService live in
  the SAME directory as this ticket's InboundRouter; the
  disjointness is at the file level, not the directory level.
- **The umbrella's whole-topic IT.** The M1-035 umbrella's
  AdapterRouterIT exercises the full inbound → AutoRegister →
  /help → outbound roundtrip via the InMemoryAdapter's test
  helpers. This subticket's tests assert per-class behavior;
  the umbrella asserts the cross-class roundtrip. The
  `out_of_scope` list pins the umbrella's IT path so a stray
  IT pre-emption here is caught by the reviewer.
- **M1-020 deferred_on update.** Per the T1-E handoff's
  "After authoring all tickets" step 6, the M1-020
  (exception-message sanitization) deferred_on is updated to
  point at this subticket once the four tickets are authored.
  M1-020's grep targets InboundHandler exception sites under
  `infochat-provider/src/main/java/.../messaging/`, which this
  subticket's `InboundRouter` first introduces. The metadata
  edit + STATUS regen is a separate `process:` commit, not
  part of this ticket's implementation diff.

## Out-of-scope expansion

- **The umbrella's whole-topic integration test.**
  `infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java`
  is reserved for M1-035. Same rationale as M1-035a: the
  umbrella + subticket idiom exists so the cross-cutting
  verification ships as its own reviewable unit.
- **Any change under `infochat-messaging-adapter/`.** The SPI
  surface and InMemoryAdapter are FROZEN at the M1-035a
  umbrella round. If a defect surfaces here (e.g., an
  InMemoryAdapter method signature that the registry can't
  call cleanly), the fix is a new ticket against the affected
  module — never an amendment to M1-035a's commit. The
  `CLAUDE.md` §M1 workflow "never amend a passed commit"
  invariant applies verbatim.
- **`HelpCommandHandler`, `AutoRegisterService`, `BundleLoader`,
  `BundleKeys`, `en.properties`.** M1-035c lands these.
  This subticket's InboundRouter uses a deterministic English
  string literal for the unknown-command and chat-mode replies
  until M1-035c supersedes those with bundle-key lookups
  (M1-035c MAY edit InboundRouter to swap the literal for a
  bundle key as an authorized minor change, listed in
  M1-035c's "Authorized test changes" / impl-notes section).
- **Flyway migrations.** T1-E is migration-free. Reaching for
  V12 is an escalation trigger.
- **SimpleX / Signal adapter beans.** T3-A. The registry is
  shaped to discover them via CDI when they land.
- **Bootstrap-admin @Startup bean.** Deferred per the T1-E
  handoff. MVP relies on a manual SQL grant. The umbrella
  M1-035 notes this gap in its Big-picture notes.
- **Invite-gating (D44), slow-start probation (D45),
  /ban / /unban (D11), chat-mode handler proper.** All T2-A
  or T2-D. The InboundRouter's entry point includes the
  one-line comment naming the deferred steps; no further
  implementation here.
- **LLM output sanitizer integration.** /help's reply is a
  deterministic localization-bundle string, not LLM-authored;
  the unknown-command reply and chat-mode stub reply are also
  deterministic. The sanitizer lands in T1-F's /summary
  (first LLM-authored prose).
- **TranslationProvider integration / `/lang`.** Deferred to
  T2-C. Outbound replies use English literals (this ticket) or
  English bundle keys (M1-035c onward); the
  translate-before-send wiring is T2-C.
- **Inbound back-pressure / per-user-fair scheduler /
  synchronous-throttle-reply path.** Design §6.3.7 machinery
  is deferred to T2-G.
- **Transport-layer inbound size cap enforcement /
  application-level input length caps.** The
  `maxInboundMessageBytes` field exists on CapabilityFlags from
  M1-035a; enforcement is deferred. This subticket MAY apply a
  hardcoded sensible application-level cap (e.g., 4096 bytes)
  at the InboundRouter entry point with a one-line comment
  naming the profile-driven follow-up, OR may defer the cap
  entirely with the same comment — pick whichever is cleaner.
  The cap value itself is not spec-load-bearing for MVP.
- **Confirmation-pending state machine.** /help is not
  destructive; MVP has no destructive commands; the in-memory
  confirmation map lives in T2-A.
- **Audit_log row writes from the router.** /help is not
  auditable per docs/design/00-mvp.md §5 Operations carve-out
  ("Audit-log entries beyond bot-admin bootstrap and
  `/add-source` are deferred"). When T1-F's /add-source lands,
  it writes its own audit row; the router itself does not.

## Authorized test changes

- (none — this subticket adds three new test classes in
  `infochat-provider` and modifies no pre-existing tests.)

## Alternatives considered

- **Implement only the gates MVP actually exercises (gates 1
  and 5) and defer the other four to a later ticket.**
  Rejected: the other four cost three lines each, surface
  spec-load-bearing security guarantees, and ship at the
  exact moment the AdapterRegistry is first authored. A later
  ticket to add them would require touching the same file the
  reviewer just signed off on; the cost saving today is
  negative tomorrow. Per the T1-E Locked decisions, all six
  gates ship in this subticket.
- **Use Quarkus ConfigMapping for the `infochat.adapters`
  list (typed `List<String>` with validation).** Acceptable
  but not required. Plain
  `@ConfigProperty(name="infochat.adapters") String` + manual
  split-trim is the smaller diff and matches the existing
  Quarkus config style under `infochat-collector` /
  `infochat-provider`. Either shape meets acceptance.
- **Make the InboundRouter dispatch synchronous (current
  shape) vs. asynchronous (via SmallRye Mutiny / a bounded
  executor).** Synchronous picked. Per the T1-E handoff's
  inbound-back-pressure carve-out, the synchronous-throttle-
  reply machinery is deferred. A synchronous router is simpler
  to reason about, matches InMemoryAdapter's synchronous
  delivery shape, and does not need to make the
  `InboundHandler` interface returning a CompletionStage.
  T2-G evolves this when the bounded queue lands.
- **Co-locate the normalization helper in `infochat-core` as a
  shared utility.** Rejected for MVP: no other call site needs
  it today. Speculative promotion to `infochat-core` would
  create cross-module surface without a second consumer. The
  helper is a private static method on InboundRouter; if a
  future ticket has the same homoglyph concern, promote it
  then.
- **Make `InboundRouter` the type that dispatches to per-
  command handlers (i.e., include the command dispatcher
  inside InboundRouter).** Accepted in shape, deferred in
  population: this subticket ships the router that performs
  the `Instance<CommandHandler>` lookup AND ships the
  `CommandHandler` interface itself (the router cannot
  compile against an interface that doesn't exist yet). The
  first command implementation (`HelpCommandHandler`) lands in
  M1-035c. Until M1-035c lands, `Instance<CommandHandler>`
  resolves to an empty set at runtime and the router replies
  to every slash command with the unknown-command bundle key
  — which is exactly the spec'd behavior for an unknown
  command. M1-035c's commit adds `HelpCommandHandler` as an
  `@ApplicationScoped CommandHandler` bean; CDI discovery
  picks it up automatically with no router-side change.
- **Replace `IllegalStateException` with a typed
  `MessagingConfigException`.** Acceptable but not required.
  `IllegalStateException` is the conventional Java type for
  "the operator's configuration is wrong; restart with a
  fixed config." The error message is the load-bearing part;
  callers don't catch the type. A typed exception would let
  future code (a /diagnose admin command?) inspect the
  exception programmatically, but no such caller exists
  today.
- **Evolve `InboundHandler.onMessage` to a two-arg
  `(MessagingAdapter, InboundMessage)` shape so the router can
  send replies via the originating adapter.** Acceptable but
  not the simpler path. The simpler path is for
  AdapterRegistry to register a per-adapter handler that
  closes over the adapter reference via a lambda — keeps the
  M1-035a SPI signature monotonic. The reviewer should not
  flag either choice; pick whichever is the smaller
  M1-035b-internal diff.
