# Session handoff — Tier 3 Group A: production adapters (SimpleX + Signal)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T3-A ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0, Tier 1, and Tier 2 implementation tickets are done and
  merged on main. This includes the group infrastructure (T2-F:
  M1-079a..M1-079e, M1-079, M1-084), periodic digests (M1-080 +
  subs), quarantine workflow (M1-081 + subs, M1-083), and every
  preceding ticket through M1-084.
- Deferred (not T3-A's concern): M1-019, M1-020, M1-021, M1-031,
  M1-034, M1-042 — all post-MVP hardening.
- Branch is main, otherwise clean.
- T3-A is the first Tier 3 group: production adapters behind the
  MessagingAdapter SPI. The two v1 adapters (SimpleX, Signal) run
  alongside the existing InMemoryAdapter (test-only, never alongside
  production adapters per D46).

**Verify at authoring time (do not trust brief's values if main moved):**

  - Next free ticket ID:
    `ls docs/plan/m1/tickets/ | sort -V | tail`
  - Next free Flyway migration version:
    `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail`
    (T3-A is unlikely to need a migration but verify no gap)
  - MessagingAdapter SPI shape (confirmed at brief-authoring time but
    re-check):
    `cat infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java`
  - InMemoryAdapter reference impl:
    `cat infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java`
  - AdapterRegistry registration flow:
    `cat infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java`
  - CapabilityFlags record shape:
    `cat infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java`
  - MessageHandle record shape:
    `cat infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessageHandle.java`
  - MembershipEvent sealed interface shape:
    `cat infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MembershipEvent.java`
  - Maven module structure:
    `find . -name "pom.xml" -path "*/infochat-messaging-adapter/*"`

## What T3-A creates

T3-A lands the two production `MessagingAdapter` implementations:
**SimplexAdapter** and **SignalAdapter**. Each is a CDI bean discovered
by AdapterRegistry via `Instance<MessagingAdapter>` and activated when
its `name()` appears in the `infochat.adapters` CSV.

### SPI gap: lifecycle methods

At brief-authoring time, `MessagingAdapter` has no `start()` / `stop()`
lifecycle methods — the javadoc says these are "deferred to the first
concrete adapter (SimpleX / Signal)." T3-A is that concrete adapter.
The authoring session MUST decide:

  **Option A (SPI widening):** Add `void start() throws
  MessagingException` and `void stop() throws MessagingException` to
  `MessagingAdapter`. InMemoryAdapter gains no-op implementations.
  AdapterRegistry (or a `@Startup` bean) calls `start()` on each
  activated adapter. This matches the design doc (§6.2).

  **Option B (CDI lifecycle):** Each production adapter uses
  `@Observes StartupEvent` / `@PreDestroy` directly. The SPI stays
  untouched. InMemoryAdapter is unaffected. Cons: adapter startup
  order is not guaranteed relative to AdapterRegistry's gate checks.

  The design doc §6.2 describes `start(InboundHandler handler)` and
  `stop()` on the SPI. The current code separates inbound-handler
  registration (`setInboundHandler`) from lifecycle. **Recommend
  Option A** (add `start()`/`stop()`, keep `setInboundHandler`
  separate as-is) — it aligns with the design and gives
  AdapterRegistry explicit startup-order control.

  Verify whether groups work (T2-F) introduced `start()`/`stop()` or
  `groupExists()` — read the SPI file at authoring time.

### Per-adapter what's new

**SimplexAdapter** (per design §6.4):

  - Maven module: `infochat-messaging-adapter-simplex` (or inline
    under `infochat-messaging-adapter/impl/simplex/` — verify module
    layout precedent at authoring time; the design doc §6.1 shows
    separate Maven modules per adapter but the InMemoryAdapter is
    inline under `impl/inmemory/`).
  - WebSocket client to simplex-cli
    (`ws://localhost:5225`, configurable via
    `infochat.adapters.simplex.url`).
  - Session-token auth
    (`infochat.adapters.simplex.session-token`).
  - Bot identity material directory
    (`infochat.adapters.simplex.identity-dir`).
  - SimplexEventDecoder: maps SimpleX chatItem JSON → InboundMessage.
    Mention recognition by bot's queue address (byte-equality,
    §6.2.3). Group messages without matching mention payload are
    dropped.
  - SimplexCommandEncoder: OutboundMessage → `/_send` commands.
    Chunking at 4000 bytes. Update via `/_update item` with
    `live=on/off`.
  - Reconnection: exponential backoff (1s→2s→5s→15s→60s).
    Auth failures: 3 consecutive → terminal `AUTH_FAILED` state.
  - Capabilities: `supportsMessageEdit=true`,
    `supportsTypingIndicator=false`,
    `supportsMentionByContactId=true`,
    `supportsMembershipEvents=false`, `supportsMarkdownLinks=false`,
    `supportsCodeFormatting=false`, `maxMessageBytes=4000`,
    `minEditInterval=600ms`.
  - trustLevel = HIGH (queue-address-based identity).
  - Bootstrap admin: `infochat.adapters.simplex.bootstrap-admin`
    (queue address, optional).

**SignalAdapter** (per design §6.5):

  - Maven module: `infochat-messaging-adapter-signal` (or inline
    under `impl/signal/`).
  - `signal-cli` JSON-RPC subprocess (provisional, §6.5.1).
  - Bot identity directory
    (`infochat.adapters.signal.identity-dir`).
  - SignalEventDecoder: maps signal-cli JSON-RPC envelopes →
    InboundMessage. ACI-based mention recognition
    (`dataMessage.mentions[].mentionUuid` byte-equal against bot's
    cached ACI, §6.2.3).
  - SignalCommandEncoder: OutboundMessage → JSON-RPC `send`/
    `sendEdit` calls. Chunking at 8000 bytes.
  - Reconnection: same backoff cadence as SimpleX. Auth failures
    (account no longer registered): 3 → terminal `AUTH_FAILED`.
  - Capabilities: `supportsMessageEdit=true`,
    `supportsTypingIndicator=true`,
    `supportsMentionByContactId=true`,
    `supportsMembershipEvents=true`, `supportsMarkdownLinks=false`,
    `supportsCodeFormatting=true`, `maxMessageBytes=8000`,
    `minEditInterval=600ms`.
  - trustLevel = HIGH (ACI-based identity).
  - Bootstrap admin: `infochat.adapters.signal.bootstrap-admin`
    (ACI UUID, optional).

### MessageHandle pattern

MessageHandle is a record with a single `opaqueValue` string. Both
adapters store protocol-specific state (SimpleX chatItemId, Signal
send-timestamp) in an internal `ConcurrentHashMap<String, ...>` keyed
by the opaque value. The handle's lifecycle is in-process and
request-scoped — no database persistence.

### Bootstrap admin wiring (DOES NOT EXIST — T3-A creates)

Per CLAUDE.md §Bootstrap admin: each enabled adapter optionally has
a bootstrap admin contact id. On startup a `@Startup` bean ensures
the contact exists with `is_admin=true`, creates the user if needed.
Audit log records each bootstrap. The property is optional per adapter
as long as the union across all adapters is non-empty.

**This bean does NOT exist on disk** (confirmed). There is no
`@Startup` bean that bootstraps admin users from adapter config.
`AutoRegisterService` and `InviteCodeConsumer` handle regular user
registration but not admin bootstrapping. T3-A must CREATE this
bean. It should:
  - Read `infochat.adapters.<name>.bootstrap-admin` for each
    activated adapter
  - UPSERT user rows with `is_admin=true` for configured contacts
  - Record each bootstrap in `audit_log`
  - Validate the union-non-empty invariant (at least one admin
    across all adapters)

### CDI bean discovery for production adapters

InMemoryAdapter is registered via a `@Produces` method on
`AdapterRegistry` — the `infochat-messaging-adapter` JAR has no
`beans.xml` and no CDI annotations (it's a plain library). Production
adapters need CDI discovery: either annotate with
`@ApplicationScoped` and ensure the adapter module has `beans.xml`
(or Jandex indexing), or add `@Produces` methods on AdapterRegistry
for each production adapter bean. Verify the discovery mechanism at
authoring time.

If separate Maven modules per adapter (design §6.1): each module
needs its own `META-INF/beans.xml` or Jandex index so CDI discovers
the bean. If inline under `impl/simplex/` etc.: the parent module's
existing CDI config may suffice.

### What T3-A does NOT create

  - No new Flyway migration (adapters don't add tables).
  - No changes to InMemoryAdapter (it stays as-is for tests).
  - No InboundRouter changes (the router already dispatches by
    adapter name; T3-A's adapters just register with it).
  - No changes to existing command handlers.
  - No Nostr, Bluesky, or other fetcher work (T3-B, T3-C).

## Key seams in the current code

### MessagingAdapter SPI

Location: `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java`

Methods: `name()`, `capabilities()`, `trustLevel()`,
`assertIdentity(InboundMessage)`, `send(OutboundMessage) → MessageHandle`,
`update(MessageHandle, String)`, `finalize(MessageHandle, String)`,
`setTyping(ScopeRef, boolean)`, `setInboundHandler(InboundHandler)`,
`onMembershipEvent(MembershipEvent)` (default no-op).

**Missing vs design doc:** no `start()`/`stop()`/`groupExists()` —
see §SPI gap above.

### InMemoryAdapter (reference implementation)

Location: `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java`

Pattern to follow:
- Thread-safe mutable state (ConcurrentHashMap, CopyOnWriteArrayList)
- MessageHandle keyed by `"inmem-" + counter`
- `assertIdentity` returns `msg.sender()` (no crypto in test double)
- Test helpers (`deliverDm`, `deliverGroupMention`, `sentMessages`,
  etc.) are NOT on the SPI; tests cast to concrete type

### AdapterRegistry

Location: `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java`

Six startup gates (must pass for adapter to activate):
1. `infochat.adapters` non-empty
2. Every name resolves to a CDI bean
3. `supportsMarkdownLinks=false` (all adapters)
4. `supportsMentionByContactId` check (if group SPI wired)
5. Production-exclusion: inmemory cannot coexist with simplex/signal
6. LOW-trust opt-in required for LOW-trust adapters

Wiring: calls `setInboundHandler` with a lambda that captures
`adapter.name()` so InboundRouter sees the source adapter.

### ProgressNotifier

Location: find via `find . -name "ProgressNotifier*" -path "*/main/*"`

Consumes `send`/`update`/`finalize`/`setTyping` per adapter's
capabilities. Coalesces edits to honor `minEditInterval`. T3-A
adapters must honor the edit coalescing contract in §6.3.8.

## Spec sections T3-A cites

- `docs/spec/messaging.md` — WHOLE FILE (every section is load-
  bearing for production adapters)
- `docs/spec/security.md` §Per-adapter admin threat profile (line 419)
- `docs/spec/security.md` §Trust boundaries (line 38)
- `docs/spec/security.md` §User ban (line 473)
- `docs/spec/security.md` §Invite-code registration (line 525)
- `docs/spec/deployment.md` §Operator inputs (item 7: bot identity
  material)
- `docs/spec/deployment.md` §Bootstrap behavior (per-adapter resilience)
- `docs/spec/verification.md` — adapter-related test items
  (search for "adapter", "SimpleX", "Signal", "reconnect",
  "trust gate", "markdown links")
- `docs/design/06-messaging.md` §6.2 (SPI), §6.3 (contract),
  §6.4 (SimpleX), §6.5 (Signal), §6.7 (multi-adapter), §6.8 (trust),
  §6.12 (observability)

## Recommended ticket split

T3-A is 2 tickets per session-grouping-plan: one per adapter.

  **M1-??? — SimplexAdapter**
  - WebSocket client, event decoder, command encoder
  - Reconnection + auth-failure terminal state
  - Capability flags (per §6.4.2)
  - Bot identity validation
  - Mention recognition (queue address byte-equality)
  - Chunking (4000 bytes)
  - Update encoding (`/_update item`, `live=on/off`)
  - Failure categorization (§6.4.7)
  - Bootstrap admin wiring for SimpleX
  - Config properties: `infochat.adapters.simplex.*`
  - Unit tests with recorded JSON fixtures (canned WS frames)
  - Estimate: ~10-14 files (adapter + client + decoder + encoder +
    message-handle impl + config + properties + 3-4 test files)

  **M1-??? — SignalAdapter**
  - JSON-RPC subprocess management (spawn, stdio/TCP pipe, health
    monitoring, SIGTERM→SIGKILL escalation on stop, crash respawn —
    this is the primary complexity driver)
  - Event decoder, command encoder
  - Reconnection + auth-failure terminal state
  - Capability flags (per §6.5.2)
  - Bot identity validation (ACI from signal-cli directory)
  - Mention recognition (ACI byte-equality)
  - Chunking (8000 bytes)
  - Edit encoding (`sendEdit` JSON-RPC)
  - Failure categorization (§6.5.9)
  - Membership events (user joined/left group via `groupV2.revision`)
  - Bootstrap admin wiring for Signal
  - Config properties: `infochat.adapters.signal.*`
  - Unit tests with recorded JSON fixtures (canned JSON-RPC envelopes)
  - Estimate: ~12-16 files
  - **complexity: high** (subprocess lifecycle management is
    significantly more complex than WebSocket client code)

  If either adapter's file count exceeds 12, split the client/lifecycle
  from the event-decoder/encoder into two subtickets per the M1-008
  umbrella pattern. This is unlikely for the Signal adapter but
  possible for SimpleX if the WebSocket reconnection logic is complex.

  **SPI widening ticket (optional).** If Option A is chosen for the
  lifecycle gap, the `start()`/`stop()` addition to
  `MessagingAdapter.java` + InMemoryAdapter no-op impls +
  AdapterRegistry caller changes are small enough to fold into the
  SimplexAdapter ticket (it's the first to need them) rather than a
  separate ticket.

## Dependencies and ordering

- Both tickets depend on Tier 2 completion. For `blocked_by`, use
  the last done M1 ticket at authoring time (verify via
  `ls docs/plan/m1/tickets/ | sort -V | tail`).
- SimplexAdapter and SignalAdapter are independent of each other.
  Recommended order: SimplexAdapter first (SimpleX is the recommended
  high-assurance admin placement per security spec; getting it working
  first lets integration testing of admin flows proceed). The SPI
  widening (`start()`/`stop()`) and bootstrap-admin bean should be
  part of the SimplexAdapter ticket since it's the first to need them.
- Neither ticket depends on T3-B, T3-C, or T3-D.
- Profile-driven values use Quarkus config profiles:
  `%laptop.infochat.adapters.simplex.url=ws://localhost:5225`,
  `%vps.infochat.adapters.simplex.url=ws://simplex:5225`, etc.

## Design-vs-spec drift notes

1. The design doc §6.2 shows `start(InboundHandler handler)` /
   `stop()` / `groupExists(String)` on the SPI. The actual code has
   `setInboundHandler(InboundHandler)` instead and no
   `start`/`stop`/`groupExists`. The authoring session must reconcile
   this (see §SPI gap above).

2. The design doc §6.2 shows `MessageHandle` as a sealed interface
   (`permits SimplexMessageHandle, SignalMessageHandle,
   InMemoryMessageHandle`). The actual code uses a record with an
   opaque string. The record approach works — adapters keep internal
   state keyed by the opaque value. No sealed interface needed.

3. The design doc §6.2 shows `SentMessage send(OutboundMessage)`.
   The actual code is `MessageHandle send(OutboundMessage)`.
   The `SentMessage` wrapper (carrying both handle and original
   message) does not exist. Use the actual code shape.

4. The design doc §6.1 shows separate Maven modules per adapter
   (`messaging-adapter-simplex`, `messaging-adapter-signal`). The
   InMemoryAdapter is inline under `impl/inmemory/`. Decide at
   authoring time: separate modules (allows deployment to exclude
   unused adapter JARs) vs inline (simpler build). The design note
   recommendation is separate modules.

5. The design doc §6.5.1 flags Signal wire-protocol path as an "open
   decision" — `signal-cli` JSON-RPC is the provisional default.
   Commit to it in the ticket or document the fallback.

## Existing tests to not break

- InMemoryAdapterTest — tests identity stability, send→update→finalize
  lifecycle, finalize exclusivity, typing events, trust-level defaults
- AdapterRegistryTest (if it exists) — tests the six startup gates
- All existing ITs that use InMemoryAdapter (they exercise the test
  adapter, not production adapters; T3-A must not change InMemoryAdapter
  behavior)
- RssFetcherTest, LlmRouterTest — unrelated but must stay green
- Full `mvn verify` from repo root

## Task

Author the T3-A ticket files in `docs/plan/m1/tickets/`. Follow
the ticket template at `docs/process/ticket-template.md`. Each
ticket must have correct frontmatter (id, title, status: pending,
complexity, risk, spec_refs, files_budget, files_scope, out_of_scope,
blocked_by, acceptance). Mark both `security_relevant: true` (per-adapter
admin threat profile, identity assertion, mention recognition are
security-critical). Use the first two free IDs at the tail.

After authoring, run `scripts/lint-ticket.py` on each new ticket
file and fix any errors. Do NOT run `/m1-tick start` — only author.
```
