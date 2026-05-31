> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in `docs/spec/`.

 ---
  # 06 — Messaging adapters                                                                                                                                                                                                                             
                           
  This file specifies the `MessagingAdapter` SPI, the SimpleX Chat first implementation, the `InMemoryAdapter` test double, and the rules every adapter must follow to remain interchangeable.                                                          
                                                                                                                                                                                                                                                        
  The adapter is the only path between the Provider Server and the outside world. Everything user-facing flows through it. Strong contract here means the rest of the system (commands, chat agent, group summaries) is messaging-app-agnostic.
                                                                                                                                                                                                                                                        
  ---                                                                              
                                                                                                                                                                                                                                                        
  ## 6.1 Module layout                                                             
                                                                                                                                                                                                                                                        
  infochat-messaging-adapter/
  ├── api/                                                                                                                                                                                                                                              
  │   ├── MessagingAdapter.java       # the SPI                                    
  │   ├── InboundMessage.java         # what the adapter delivers to provider                                                                                                                                                                           
  │   ├── OutboundMessage.java        # what the provider hands to the adapter                                                                                                                                                                          
  │   ├── ScopeRef.java               # DM(contact_id) | Group(adapter_group_id)                                                                                                                                                                        
  │   ├── Identity.java               # contact_id (cryptographic), display_name (informational)                                                                                                                                                        
  │   ├── AdapterCapabilities.java    # supportsCodeFormatting, supportsMarkdownLinks, supportsMembershipEvents, ...
  │   ├── AdapterTrustLevel.java      # HIGH | LOW                                                                                                                                                                                                      
  │   ├── FailureCategory.java        # TRANSIENT | PERMANENT
  │   └── MessagingException.java                                                                                                                                                                                                                       
  ├── routing/                                                                                                                                                                                                                                          
  │   └── AdapterRegistry.java        # CDI: discovers every CDI MessagingAdapter bean and
  │                                   #   activates the non-empty subset listed in
  │                                   #   infochat.adapters (D46 — see §6.7)
  ├── impl/                                                                                                                                                                                                                                             
  │   ├── simplex/                                                                 
  │   │   ├── SimplexAdapter.java                                                                                                                                                                                                                       
  │   │   ├── SimplexCliClient.java   # WebSocket bot client                       
  │   │   ├── SimplexEventDecoder.java                                                                                                                                                                                                                  
  │   │   └── SimplexCommandEncoder.java
  │   ├── signal/
  │   │   ├── SignalAdapter.java
  │   │   ├── SignalCliClient.java    # JSON-RPC subprocess client (open: see §6.5.1)
  │   │   ├── SignalEventDecoder.java
  │   │   └── SignalCommandEncoder.java
  │   └── inmemory/                                                                                                                                                                                                                                     
  │       └── InMemoryAdapter.java    # test double (no network)                   
  └── observability/                                                                                                                                                                                                                                    
      └── AdapterMetrics.java                                                      
                                                                                                                                                                                                                                                        
  `infochat-provider` depends on `messaging-adapter-api`. Concrete impls are pulled in as separate Maven modules (`messaging-adapter-simplex`, `messaging-adapter-signal`, `messaging-adapter-inmemory`) so a deployment can ship only what it needs.
                                                                                                                                                                                                                                                        
  ---
                                                                                                                                                                                                                                                        
  ## 6.2 The SPI                                                                   

  ```java
  public interface MessagingAdapter {

      /** Stable identifier ("simplex", "inmemory", "telegram", ...). */                                                                                                                                                                                
      String name();
                                                                                                                                                                                                                                                        
      AdapterCapabilities capabilities();                                          

      AdapterTrustLevel trustLevel();   // HIGH if cryptographic identity, LOW otherwise                                                                                                                                                                
   
      /** Lifecycle. Called once on Provider Server startup. */                                                                                                                                                                                         
      void start(InboundHandler handler) throws MessagingException;                
                                                                                                                                                                                                                                                        
      /** Lifecycle. Called on shutdown. Must be idempotent. */                                                                                                                                                                                         
      void stop() throws MessagingException;                                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
      /** Send one message. Returns an opaque handle the caller may later use to
       *  update or finalize the same message. Synchronous from the caller's
       *  perspective; the adapter may queue internally. */
      SentMessage send(OutboundMessage msg) throws MessagingException;

      /** Replace the visible text of a previously-sent message.
       *  No-op for adapters with capabilities.supportsMessageEdit = false. */
      void update(MessageHandle handle, String text) throws MessagingException;

      /** Last update for a message; signals the operation is complete. After
       *  finalize, further update calls on the same handle MUST throw. Adapters
       *  may use this to clear "live" decorations (e.g., SimpleX live=off).
       *  For adapters with supportsMessageEdit = false, finalize behaves as
       *  a send() of the final text with the original correlationId. */
      void finalize(MessageHandle handle, String text) throws MessagingException;

      /** Show or clear a typing indicator for a scope.
       *  No-op for adapters with capabilities.supportsTypingIndicator = false. */
      void setTyping(ScopeRef scope, boolean typing);

      /** Strongly-typed identity assertion for an incoming message. NEVER trust display name. */                                                                                                                                                       
      Identity assertIdentity(InboundMessage msg);                                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
      /** Did this group exist before? Used by the group-admin auto-promote flow. */                                                                                                                                                                    
      boolean groupExists(String adapterGroupId);                                                                                                                                                                                                       
  }                                                                                                                                                                                                                                                     
                                                                                   
  public interface InboundHandler {                                                                                                                                                                                                                     
      void onMessage(InboundMessage msg);                                          
      void onUserJoinedGroup(String adapterGroupId, Identity user);                                                                                                                                                                                     
      void onUserLeftGroup(String adapterGroupId, Identity user);                                                                                                                                                                                       
  }                                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  public record InboundMessage(                                                    
      Identity sender,
      ScopeRef scope,        // DM or Group                                                                                                                                                                                                             
      String text,
      Instant receivedAt,                                                                                                                                                                                                                               
      String adapterMessageId                                                      
  ) {}                                                                                                                                                                                                                                                  
   
  public record OutboundMessage(                                                                                                                                                                                                                        
      ScopeRef scope,                                                              
      String text,
      Instant requestedAt,
      String correlationId   // matches an inbound message id, when this is a reply                                                                                                                                                                     
  ) {}

  /** Returned by send(). Carries the original message plus an opaque handle
   *  that the caller passes to update() / finalize() to mutate the same
   *  visible message. The handle's contents are adapter-defined. */
  public record SentMessage(MessageHandle handle, OutboundMessage original) {}

  /** Opaque token. Adapters define their own implementations
   *  (e.g., SimplexMessageHandle wraps chatItemId; SignalMessageHandle wraps
   *  the (timestamp, recipient) pair signal-cli uses to address an edit;
   *  InMemoryMessageHandle wraps an in-memory id). Callers MUST NOT inspect
   *  or persist it. */
  public sealed interface MessageHandle
      permits SimplexMessageHandle, SignalMessageHandle, InMemoryMessageHandle /*, future adapter handles */ {}
                                                                                                                                                                                                                                                        
  public sealed interface ScopeRef {                                                                                                                                                                                                                    
      record Dm(String contactId) implements ScopeRef {}                           
      record Group(String adapterGroupId) implements ScopeRef {}                                                                                                                                                                                        
  }                                                                                                                                                                                                                                                     
   
  public record Identity(                                                                                                                                                                                                                               
      String contactId,      // cryptographic, stable, used for auth               
      String displayName,    // informational only, may change
      Instant lastSeen                                                                                                                                                                                                                                  
  ) {}
                                                                                                                                                                                                                                                        
  public record AdapterCapabilities(
      // --- Identity / mention anchoring (spec/messaging.md §Capability flags) ---
      boolean supportsMentionByContactId,  // adapter's group @mention payload references
                                           //   the bot's per-adapter cryptographic contact
                                           //   id (SimpleX queue address, Signal ACI).
                                           //   REQUIRED-true for any adapter that exposes
                                           //   group mode in v1 (§6.3.3); display-name
                                           //   string matching is never a fallback.
      boolean supportsMembershipEvents,    // adapter exposes native user_joined_group /
                                           //   user_left_group signals at the protocol
                                           //   layer. When false, Provider falls back to
                                           //   permanent-delivery-failure cleanup
                                           //   (§6.3.6 + spec/messaging.md §Failure
                                           //   handling — User left group). Adapters
                                           //   MUST NOT synthesise membership events
                                           //   from inactivity.
      // --- Output formatting (spec/messaging.md §Capability flags) ---
      boolean supportsCodeFormatting,      // backticks render as monospace. v1 adapters render
                                           //   ONLY code spans — never markdown links or other
                                           //   markdown features (the field name is deliberately
                                           //   narrower than "markdown" so its scope cannot drift).
      boolean supportsMarkdownLinks,       // MUST be false for every v1 adapter; Provider
                                           //   validates at adapter-registration startup
                                           //   and fails fast on a true value (§6.2.1).
      boolean supportsMultilineCode,       // triple-backtick blocks render
      boolean supportsAttachments,         // future use
      boolean supportsThreading,           // future use
      // --- Sizing ---
      int     maxMessageBytes,             // outbound chunking threshold
      int     maxInboundMessageBytes,      // transport-layer first-defense cap on inbound
                                           //   message size (§6.2.2 + spec/messaging.md
                                           //   §Required SPI surface — Inbound message
                                           //   size cap). Tighter than the application-level
                                           //   chat-mode body cap; messages over this size
                                           //   are dropped by the adapter before delivery.
                                           //   Set to Integer.MAX_VALUE only if the protocol
                                           //   provides no enforcement mechanism; the
                                           //   adapter's design note MUST justify that.
      int     maxInflightSends,            // CONCURRENCY: max send() calls in flight at once
                                           //   (e.g. 4 means up to 4 outbound messages
                                           //    may be transmitting simultaneously)
      int     maxSendsPerSecond,           // RATE: token-bucket cap on sends per second
                                           //   averaged over a 1s window, regardless of
                                           //   how many are concurrently in flight
      // --- Editing / typing ---
      boolean supportsMessageEdit,         // adapter can update an already-sent message
                                           //   (e.g., SimpleX APIUpdateChatItem)
      boolean supportsTypingIndicator,     // adapter can show "bot is typing…"
      Duration minEditInterval             // adapter's recommended floor between edits
                                           //   on the same message; ProgressNotifier
                                           //   uses max(this, 600ms). Duration.ZERO if
                                           //   not applicable (e.g., InMemoryAdapter).
  ) {}                                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  public enum AdapterTrustLevel { HIGH, LOW }                                                                                                                                                                                                           

  /** Categorisation of send/update/finalize failures (spec/messaging.md
   *  §Failure handling). Adapters MUST classify each raised
   *  MessagingException into one of these. An adapter that cannot tell
   *  the two apart MUST default to PERMANENT — silently looping a
   *  permanent failure is a worse failure mode than aborting an
   *  occasionally-transient one. */
  public enum FailureCategory { TRANSIENT, PERMANENT }
  ```                                                                                 
  The handler-style API (push from adapter to provider via InboundHandler) means each adapter manages its own connection lifecycle and event loop. Provider doesn't poll.                                                                               

  `MessagingException` carries a `FailureCategory category()` accessor.
  Adapters set the field at throw site; callers (most importantly
  `ProgressNotifier` and the outbound retry layer) branch on it without
  any heuristic re-classification.

  ### 6.2.1 Startup validation — `supportsMarkdownLinks` fail-fast

  Per [../spec/messaging.md](../spec/messaging.md) §Capability flags,
  `supportsMarkdownLinks` MUST be `false` for every v1 adapter — bot
  output is plain text and silently widening the rendering surface
  would let an LLM-authored URL become a clickable target with the
  visible text under attacker control. The check runs **once, at
  adapter-registration startup**, not per-message:

  ```java
  for (var adapter : enabledAdapters) {
      var caps = adapter.capabilities();
      if (caps.supportsMarkdownLinks()) {
          throw new IllegalStateException(
              "Adapter '" + adapter.name() + "' declares supportsMarkdownLinks=true; "
            + "v1 forbids markdown link rendering — see spec/messaging.md §Capability flags");
      }
  }
  ```

  A per-message check would miss adapters that silently upgrade their
  capabilities at runtime (a future protocol revision flipping the flag
  in a hotfix); the startup gate guarantees that widening the render
  surface requires both a code change *and* a Provider restart.

  ### 6.2.2 Inbound message size cap (transport-layer first defense)

  Per [../spec/messaging.md](../spec/messaging.md) §Required SPI surface
  — *Inbound message size cap*, every adapter SHOULD enforce a
  transport-layer ceiling tighter than the application-level chat-mode
  body cap from [03-commands.md](03-commands.md) §3.1. The two caps
  compose: the adapter drops oversize messages **before delivery to
  Provider** (raising the inbound counter
  `adapter.inbound.dropped{reason='oversize'}` and emitting a fixed
  reply via the same `correlationId` path used for queue-overflow drops
  in §6.3.7), and the application-level cap fires as the second defense
  on anything that slipped through (e.g., on adapters whose protocol
  has no enforcement mechanism).

  The transport ceiling is profile-driven and lives on the adapter's
  `AdapterCapabilities.maxInboundMessageBytes()`. Concrete per-profile
  values (cross-cutting) are set in
  [07-deployment.md](07-deployment.md); the v1 design commits to:

  | Profile | Adapter `maxInboundMessageBytes` (transport-layer) |
  |---|---|
  | `laptop` | 16 KiB |
  | `vps` | 32 KiB |
  | `pi` | 8 KiB |
  | `remote-llm` | 32 KiB |

  Per-adapter overrides are allowed when the protocol's own ceiling is
  lower (e.g., a hypothetical SMS adapter would clamp far below
  `pi`'s 8 KiB). An adapter whose underlying protocol provides no
  size-enforcement primitive sets `maxInboundMessageBytes =
  Integer.MAX_VALUE` and explicitly documents the gap in its design
  note; the application-level cap is then the only line of defense for
  that adapter.

  ### 6.2.3 Mention-recognition rule

  Per [../spec/messaging.md](../spec/messaging.md) §Required SPI
  surface — *Mention-recognition rule*, whether a group message
  counts as an `@mention` of the bot is decided **only** by the
  cryptographic contact id of the mention target. Concretely:

  - Each enabled adapter has its own bot identity material (per
    [../spec/deployment.md](../spec/deployment.md) §Operator inputs
    item 7) from which the bot's per-adapter contact id is derived
    at adapter startup. SimpleX: the bot's queue address. Signal:
    the bot's ACI (the UUID Signal binds to its identity keys;
    `signal-cli` surfaces it as `mentionUuid`).
  - The adapter's mention payload — SimpleX's mention metadata, the
    Signal message envelope's mention list — references the
    mentioned party's contact id. The adapter compares that id by
    **byte-equality** against its own bot contact id; a match means
    the message is delivered to `InboundHandler.onMessage` with the
    mention payload stripped.
  - Display-name string matching is **never** sufficient. An attacker
    who can spoof or impersonate the bot's display name in a group
    must not be able to suppress legitimate mentions or forge
    mentions of the bot (see §6.10 for the operator-facing
    consequence — there is no operator-typed mention name property).
  - An adapter whose underlying protocol exposes no mention primitive
    at all MUST declare `supportsMentionByContactId = false`; the
    Provider refuses to enable group mode for that adapter at
    adapter-registration startup. Falling back to display-name
    string matching is forever-out-of-v1
    ([../spec/security.md](../spec/security.md) §What's intentionally
    NOT in v1).
                                                                                   
  ---                                                                                                                                                                                                                                                   
  ## 6.3 Contract every adapter MUST honor                                            
                                                                                                                                                                                                                                                        
  These rules are part of the SPI contract. Tests in 08-verification.md enforce them via the AdapterContractTest suite, parameterized over every adapter.
                                                                                                                                                                                                                                                        
  ### 6.3.1 Identity                                                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  - Identity.contactId is stable for the lifetime of the underlying user. Adapters must use the most stable identifier their protocol provides (SimpleX contact ID, Telegram user_id, Matrix MXID).                                                     
  - Identity.contactId is cryptographic when possible. Adapters that can't bind identity to a keypair must declare trustLevel = LOW.
  - Identity.displayName is never authoritative and may change. Provider stores it only for UX (e.g., showing "you" the right way).                                                                                                                     
  - Two messages with the same contactId from the same adapter MUST come from the same user. Spoofing requires private-key compromise (HIGH) or admin opt-in to a LOW-trust adapter.                                                                    
                                                                                                                                                                                                                                                        
  ### 6.3.2 Scope                                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  - Every InboundMessage must carry a ScopeRef:                                                                                                                                                                                                         
    - Dm(contactId) for direct messages — the contact id of the human, not the bot.
    - Group(adapterGroupId) for group messages — a stable group identifier the adapter assigns.                                                                                                                                                         
  - A user's DM scope and any group scope are independent. Adapter must never collapse them.                                                                                                                                                            
                                                                                                                                                                                                                                                        
  ### 6.3.3 @mention semantics in groups                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  - In a group, the adapter MUST only deliver messages that contain an @mention **of the bot's per-adapter cryptographic contact id** to InboundHandler.onMessage. Recognition is by byte-equality against the bot's contact id (§6.2.3), not by string matching the bot's display name.
  - The mention is taken from the adapter's mention payload (SimpleX mention metadata; Signal envelope mention list). An inbound group message that has no mention payload referencing the bot is silently dropped, even if the message body textually contains a string that resembles the bot's display name.
  - The adapter MUST strip the recognized mention payload (and the spans of the message body it covers) from the delivered text so the parser sees the user's actual command/message. Example: in a group, an inbound message whose body is "@infochat-bot /summary tech" — with the mention payload referencing the bot's contact id over the leading 14 characters — is delivered as "/summary tech".
  - An adapter that declares `supportsMentionByContactId = false` MUST refuse to start in a deployment that enables its group SPI; the registration-time check (§6.7) catches the mismatch.
  - DM messages are delivered as-is.                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  ### 6.3.4 Output formatting                                                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  - Adapter receives OutboundMessage.text already formatted with the project's plain-text-plus-backticks convention (see 03-commands.md §3.1).                                                                                                          
  - If capabilities.supportsCodeFormatting = true, the adapter MAY translate single backticks to its protocol's monospace formatting. Otherwise it MUST send the text verbatim — the recipient sees raw backticks (still readable).
  - URLs are always rendered bare. Adapters MUST NOT wrap URLs in protocol-specific link syntax even if the protocol supports it; `supportsMarkdownLinks` is asserted false at adapter-registration startup (§6.2.1) and the field is therefore not consulted on the per-message path.
  - The adapter MUST NOT inject extra formatting (no auto-markdown link conversion, no auto-emoji, no auto-mention).                                                                                                                                    
  - The adapter MUST chunk messages exceeding maxMessageBytes at line boundaries when possible, otherwise at maxMessageBytes - 1. Chunked messages MUST preserve code-block fences (close before chunk, reopen after).                                  
                                                                                                                                                                                                                                                        
  ### 6.3.5 Idempotency                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  - The provider may retry send() after a transient failure. Adapters SHOULD deduplicate by OutboundMessage.correlationId over a 60-second window when the underlying protocol allows.                                                                  
  - If the adapter cannot deduplicate, the operator must accept occasional duplicate messages on retry. This is acceptable; bot output is not safety-critical.
                                                                                                                                                                                                                                                        
  ### 6.3.6 Delivery semantics

  - `send()` returns when the adapter has accepted the message for transmission, NOT when the recipient has read it.
  - **Concurrency** is governed by `capabilities.maxInflightSends`: the adapter MUST NOT have more than that many `send()` calls actively transmitting at once. Excess callers either block briefly on an internal semaphore or are queued.
  - **Rate** is governed independently by `capabilities.maxSendsPerSecond`: even if `maxInflightSends` is high, the adapter MUST NOT exceed this many sends per second averaged over a 1s window. The two limits compose — whichever is reached first applies. (Concurrency caps "how many at once"; rate caps "how many per second".)
  - The adapter MUST NOT block the calling thread for more than a small bounded interval; if either cap would be violated, it must enqueue internally rather than stall the caller.

  #### Failure categorisation and retry policy

  Per [../spec/messaging.md](../spec/messaging.md) §Failure handling, every send/update/finalize failure raised by an adapter is categorised as `TRANSIENT` or `PERMANENT` (the `FailureCategory category()` accessor on `MessagingException`). The adapter MUST set the category at throw site; an adapter that cannot tell the two apart MUST default to `PERMANENT`.

  | Category | Examples (adapter-specific shapes in §6.4.7 / §6.5.7) | Retried? |
  |---|---|---|
  | `TRANSIENT` | network timeout, TCP/TLS reset, transport rate-limit response, transport "try again later" / 5xx-style signal, ephemeral signing-server unavailability | yes — bounded |
  | `PERMANENT` | user blocked the bot, group no longer exists or bot was removed, recipient identity rotated/revoked, message rejected as policy violation by the transport, transport-side oversize rejection | no — aborts the affected reply immediately |

  **Transient retry policy** (uniform across adapters and channels, so the per-user `LlmRateLimiter` remains the single source of truth for "slow this user down"):

  - **Maximum attempts:** **3** (the original send plus two retries). No adapter MUST add its own retry wrapper on top — the policy below is the only retry layer between Provider and the transport.
  - **Schedule:** exponential back-off with **full jitter**. Per-profile parameters:

    | Profile | Base delay | Growth factor | Jitter window |
    |---|---|---|---|
    | `laptop` | 250 ms | ×2 | full (uniform [0, current_delay)) |
    | `vps` | 250 ms | ×2 | full (uniform [0, current_delay)) |
    | `pi` | 500 ms | ×2 | full (uniform [0, current_delay)) |
    | `remote-llm` | 500 ms | ×2 | full (uniform [0, current_delay)) |

    The jitter discipline is the AWS architecture-blog "full jitter" form: each retry's actual delay is sampled uniformly from `[0, base * factor^attempt)`. Concrete values are tuning and live in [07-deployment.md](07-deployment.md); the spec commits to bounded attempt count, exponential growth, and full jitter.
  - **Terminal action on cap exhaustion:** the failure is **escalated to permanent for the rest of this reply's lifecycle**. The affected reply is aborted, the adapter does NOT enqueue another retry, and the throttled-admin-notification path ([04-security.md §4.7](04-security.md)) fires per `(channel, error_class)`. The user is not pinged about the failed delivery; the next inbound from the same scope reuses the standard intake path.
  - **Transport-internal back-pressure** (e.g., an HTTP 429 returned by a Signal proxy, a SimpleX `/_send` rejection with retry-after) surfaces as a `TRANSIENT` failure and counts toward the same attempt budget.
  - **No silent extension.** An adapter MUST NOT swallow a `TRANSIENT` failure to retry internally before surfacing it; the attempt budget belongs to Provider, not to the adapter.

  #### Permanent-delivery-failure cleanup

  - A `PERMANENT` failure aborts the affected reply **without advancing chat session state** — the context window remains as if the message was never generated, and `chat_memory` is not written ([../spec/messaging.md](../spec/messaging.md) §Failure handling — Permanent delivery failure cleanup).
  - For periodic group digests, the failure is logged and the next slot retries.
  - **Bot-removed-from-group:** when the adapter detects bot removal (via an adapter-specific signal *or* via N consecutive `PERMANENT` send failures past a profile-driven threshold; **N is always > 1** so a single misclassified failure cannot trigger cleanup), Provider sets `groups.removed_at = NOW()`, cancels the periodic-digest scheduler entries for that group, and preserves group state for re-add. Per-profile threshold:

    | Profile | Consecutive `PERMANENT` sends to the same group → bot-removed |
    |---|---|
    | `laptop` | 3 |
    | `vps` | 3 |
    | `pi` | 5 |
    | `remote-llm` | 3 |
  - **Group-deleted-upstream** (adapter signal: SimpleX group-not-found; Signal group-no-longer-exists) is treated identically to bot-removed.
  - **User-left-group:** when the adapter exposes a per-user left-group signal (`supportsMembershipEvents = true`) **or** surfaces a `PERMANENT` send failure to a specific user in the group, Provider soft-clears the `group_membership` row by setting `removed_at = NOW()` per [02-schema.md §2.1.4](02-schema.md). Adapters with `supportsMembershipEvents = false` MUST NOT synthesise a left-group event from inactivity; in that case the row stays `removed_at IS NULL` until an explicit bot-removed-from-group / group-deleted signal fires.
                                                                                                                                                                                                                                                        
  ### 6.3.7 Inbound back-pressure                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  - InboundHandler.onMessage may take time (LLM calls, DB queries). The adapter MUST NOT drop inbound messages while the handler is busy.                                                                                                               
  - The adapter SHOULD enqueue inbound messages with a bounded queue (default 1000). On overflow, the adapter MUST drop the **NEWEST** message — the one that just arrived — and MUST send a synchronous throttle reply to its sender. Older messages already in the queue are preserved because they have already been acknowledged to the user (the bot didn't reject them on arrival) and may carry context the user is still waiting on a reply for.
  - The throttle reply uses a fixed, friendly text: `you're sending faster than I can keep up; the most recent message was dropped — please retry in a moment.` It is emitted via the same `OutboundMessage` path with `correlationId = <dropped inbound message id>` so it lines up under the dropped message in the user's client, bypasses the inbound queue (it does not pass through `InboundHandler.onMessage`), and is treated as priority on the outbound queue (throttle replies themselves are never dropped).
  - Drops are recorded as `adapter.inbound.dropped{adapter, scope_kind, reason='queue_full'}` and logged at WARN with the dropped message's `adapterMessageId` and `sender.contactId` (redacted). Persistent overflow from a single user is a hint that rate-limiting (§4.9) needs tightening — the throttle reply alone is not a substitute for `LlmRateLimiter`.
  - Per-user fairness: the adapter SHOULD NOT let one chatty user starve others. InMemoryAdapter and SimplexAdapter both implement a per-user-fair scheduler.

  ### 6.3.8 Progress notifications

  Long-running user requests (`/summary`, `/digest`, chat-mode generation) publish
  progress events to the Provider's `ProgressNotifier` (see 01-architecture.md §1.5
  principle 7), which renders them through this SPI's `update`/`finalize`/`setTyping`
  methods. The contract:

  - Adapters that declare `supportsMessageEdit = true` MUST honor the lifecycle:
    - `update(handle, text)` may be called any number of times after `send` and
      before `finalize`. Each call replaces the visible text of the original message.
    - `finalize(handle, text)` is called exactly once per handle and represents the
      operation's terminal state (success or error). After `finalize`, further
      `update` calls on the same handle MUST throw `MessagingException`.
    - Adapters MUST coalesce updates to satisfy `capabilities.minEditInterval` even
      if the caller exceeds the rate. The latest update wins; intermediate texts may
      be discarded silently. The terminal `finalize` is always sent regardless of
      the coalescing window.
  - On unrecoverable update failure (e.g., underlying message deleted by the user,
    edit window expired in the protocol, adapter rejection), the adapter MUST fall
    back to sending a NEW message via `send`, with `correlationId` matching the
    original. The fallback is recorded in `adapter.outbound.update.total{outcome=fallback_send}`.
  - Adapters with `supportsMessageEdit = false` MUST treat `update` as a no-op and
    `finalize` as a `send` of the final text (with the original `correlationId`).
    Provider-side logic relies on this fallback to remain transport-neutral —
    callers MUST NOT condition on the capability flag themselves.

  ### 6.3.9 Typing indicators

  - Adapters with `supportsTypingIndicator = true` SHOULD render the indicator while
    a long-running operation is in progress. Provider invokes `setTyping(scope, true)`
    at request start and `setTyping(scope, false)` at completion or error.
  - `setTyping` calls are advisory: the adapter MAY ignore rapid toggles or apply
    its own debouncing. The adapter MUST NOT block the caller.
  - Adapters with the capability disabled MUST treat both calls as silent no-ops.

  ### 6.3.10 Inbound message size cap

  - Per §6.2.2, every adapter SHOULD enforce a transport-layer size ceiling on inbound messages, dropping anything above `capabilities.maxInboundMessageBytes()` **before** delivery to `InboundHandler.onMessage`.
  - The drop is recorded at `adapter.inbound.dropped{adapter, scope_kind, reason='oversize'}` and logged at WARN with the redacted `sender.contactId` and the message's `adapterMessageId`. A fixed friendly reply is emitted via the same priority `OutboundMessage` path used for queue-overflow drops in §6.3.7 (`correlationId = <dropped inbound message id>`).
  - The application-level chat-mode body cap from [03-commands.md §3.1](03-commands.md) fires as the **second defense** on anything that slips past — typically on adapters whose protocol provides no enforceable transport ceiling and which therefore declare `maxInboundMessageBytes = Integer.MAX_VALUE`. The two caps are layered, not redundant: the transport cap bounds resource cost from a hostile sender at the adapter boundary; the application cap bounds prompt-injection blast radius once the message has been parsed.

  ### 6.3.11 Membership events

  - Adapters with `supportsMembershipEvents = true` deliver native group-membership signals through `InboundHandler.onUserJoinedGroup(adapterGroupId, identity)` and `InboundHandler.onUserLeftGroup(adapterGroupId, identity)`.
  - Adapters with `supportsMembershipEvents = false` MUST NOT call either method. Provider falls back to permanent-delivery-failure-driven cleanup per §6.3.6 — a left-group event is **never** synthesised from inactivity, send-receipt absence, or any other indirect signal at the adapter layer.
  - The bot-removed-from-group and group-deleted-upstream events are separate from per-user membership events (they fire whether or not `supportsMembershipEvents` is true) and continue to flow through their existing adapter signals — typically a top-level error or an adapter-specific "you are no longer a member" event, not the per-user membership stream.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.4 SimpleX Chat adapter                                                                                                                                                                                                                              

  ### 6.4.1 Underlying protocol

  SimpleX provides a self-hosted Chat CLI / SimpleX Chat server with a WebSocket-based bot API. The adapter speaks that WebSocket protocol.                                                                                                             
   
  - Connection: WebSocket to simplex-cli (default ws://localhost:5225), configurable via infochat.adapters.simplex.url.
  - Authentication: cookie-based session against the local simplex-cli; cookie configured via infochat.adapters.simplex.session-token (read from env in production).
  - **Bot identity material** (per [../spec/deployment.md](../spec/deployment.md) §Operator inputs item 7): the SimpleX adapter owns its own bot queue keypair. The on-disk path is configured via `infochat.adapters.simplex.identity-dir` (a directory containing the SimpleX queue keypair file plus the auxiliary state simplex-cli persists). The adapter validates the directory at startup — readable, contains a usable keypair, queue address derivable; failure refuses *that adapter's* startup but does not abort the Provider (per-adapter resilience, §6.7). The bot's per-adapter contact id is the queue address derived from this material at adapter startup; it is the value used by the mention-recognition rule (§6.2.3 / §6.10) and is NOT an operator-typed property.
  - Identity: the SimpleX contact display ID (e.g., xftp://...); cryptographically bound. trustLevel = HIGH.
  - **Mention anchoring:** SimpleX's group event payloads carry a structured mention list. The adapter compares the bot's queue address (derived from bot identity material above) byte-equal against the mention target's contact id; it does NOT scan the message body for the bot's display name. `supportsMentionByContactId = true`.
  - **Auth-failure distinction:** the adapter classifies WebSocket close codes into two buckets — *auth failures* (401-equivalent codes from simplex-cli, e.g., revoked or invalid session token) and *network failures* (everything else: TCP reset, server unreachable, idle timeout). The two are handled with different reconnection policies; see §6.4.6. After 3 consecutive auth failures the adapter transitions to the terminal `state=AUTH_FAILED` and stops reconnecting until process restart.                                                                                                                                            

  ### 6.4.2 Capabilities (declared)                                                                                                                                                                                                                         

  supportsMentionByContactId = true   // SimpleX mention payload references queue address (§6.2.3)
  supportsMembershipEvents   = false  // OPEN — SimpleX has a join event but no documented per-user
                                      //   left-group event at the bot-API layer. v1 ships false and
                                      //   relies on permanent-failure-driven cleanup (§6.3.6).
                                      //   See "Open questions" at end of file.
  supportsCodeFormatting     = false  // SimpleX renders backticks as literal characters
  supportsMarkdownLinks      = false  // hard-asserted at startup (§6.2.1); SimpleX renders bare URLs
  supportsMultilineCode      = false
  supportsAttachments        = false  // v1 doesn't use them
  supportsThreading          = false
  maxMessageBytes            = 4000   // SimpleX hard limit; adapter chunks above this
  maxInboundMessageBytes     = profile-driven (see §6.2.2)  // SimpleX bot-API inbound text frames
                                                            //   are bounded by the WS frame cap; the
                                                            //   adapter clamps tighter to give the
                                                            //   application-layer cap headroom.
  maxInflightSends           = 4      // up to 4 outbound sends in flight concurrently
  maxSendsPerSecond          = 5      // and at most 5/s averaged; conservative, raise after observing
  supportsMessageEdit        = true   // APIUpdateChatItem ("/_update item …") with live=on/off
  supportsTypingIndicator    = false  // SimpleX has no first-class typing indicator
  minEditInterval            = 600ms  // conservative floor; refine after observation                                                                                                                                                                               

  ### 6.4.3 Lifecycle                                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  SimplexAdapter.start(handler):                                                                                                                                                                                                                        
    1. Open WS to simplex-cli URL
    2. Send /api/showActiveUser to verify connection                                                                                                                                                                                                    
    3. Subscribe to incoming events: /subscribe events                                                                                                                                                                                                  
    4. Spawn event reader coroutine → SimplexEventDecoder → InboundHandler                                                                                                                                                                              
    5. Spawn outbound queue worker → SimplexCommandEncoder → WS                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  SimplexAdapter.stop():                                                                                                                                                                                                                                
    1. Send /unsubscribe events                                                                                                                                                                                                                         
    2. Drain outbound queue (best-effort, max 5s)                                  
    3. Close WS                                                                                                                                                                                                                                         
    4. Idempotent: second stop is a no-op

  ### 6.4.4 Event decoding                                                                                                                                                                                                                                  
   
  SimplexEventDecoder maps SimpleX chatItem events to InboundMessage:                                                                                                                                                                                   
                                                                                   
  - event.kind == "newChatItem" and chatItem.chatType == "direct" → ScopeRef.Dm(contact_id).                                                                                                                                                            
  - event.kind == "newChatItem" and chatItem.chatType == "group" → ScopeRef.Group(group_id). The decoder reads the SimpleX mention metadata on the chatItem; if any mention's target contact id matches the bot's per-adapter queue address byte-equal (§6.2.3 / §6.10), the mention spans are stripped from the rendered text and the message is delivered. Group messages without a mention payload referencing the bot are dropped — display-name string matching is forever-out-of-v1.
  - event.kind == "memberJoined" (group) → InboundHandler.onUserJoinedGroup(group_id, identity). (`supportsMembershipEvents = false` despite this — Provider does not rely on the join event for cleanup logic; per-user *left* events are the missing piece, see §6.4.2.)
  - Other event kinds: logged at DEBUG, dropped.                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  The decoder is pure (no I/O) and unit-tested with recorded JSON fixtures.                                                                                                                                                                             


  #### Queue-address character set (decode and encode validation)

  Every identifier the adapter pastes into a SimpleX command string —
  `contactId` (DM scope), `adapterGroupId` (group scope), and the
  `chatItemId` round-tripped on edit — MUST match the regex
  `^[A-Za-z0-9_=.-]+$`. The character class is the intersection of two
  shapes the simplex-chat bot API uses for these fields: URL-safe
  base64 (the SimpleX queue address itself) and decimal (the
  simplex-chat DB row id, used by some API versions). It explicitly
  excludes whitespace, newlines, and the simplex-chat command
  terminators (`@`, `#`, ` `) so an attacker-controlled id cannot
  piggyback a forged verb (`/_send`, `/_update item`,
  `/_set_contact_typing`, …) into the outbound command string.

  - **Decode-time** (`newChatItem`): a contactId failing the regex
    drops the frame as `Ignored` before any `InboundMessage` is
    constructed, so no scope state is populated and no echoed text
    is ever pasted back into an outbound command. This is the
    adapter-inbound trust boundary (`docs/spec/security.md`
    §Trust boundaries).
  - **Decode-time also enforces** `capabilities.maxInboundMessageBytes`
    on the extracted `text` field (UTF-8 byte length): a frame whose
    text exceeds the SPI-declared cap is dropped as `Ignored` before
    `InboundMessage` is constructed, so the Provider's downstream
    budgets (LLM tokens, Stage 1 watchdog) plan against a real ceiling
    rather than the 1 MiB WebSocket frame ceiling.
  - **Encode-time**: the encode helpers (`encodeSendCommand`,
    `encodeUpdateCommand`, `encodeFinalizeCommand`,
    `encodeTypingCommand`) re-assert the validator on the
    `ScopeRef` and on `chatItemId`. A failure throws
    `IllegalStateException` — this is defense-in-depth (the
    decoder should already have rejected the value), and the
    exception documents the invariant for any future caller.

  ### 6.4.5 Command encoding                                                                                                                                                                                                                                
                                                                                   
  SimplexCommandEncoder serializes OutboundMessage to SimpleX /sendMessage commands:                                                                                                                                                                    
   
  - DM: /_send @<contact> text=<base64-encoded text>                                                                                                                                                                                                    
  - Group: /_send #<group> text=<base64-encoded text>                              
                                                                                                                                                                                                                                                        
  Chunking: messages over 4000 bytes are split at the nearest line break before the limit; if a single line is longer, it's split at the limit. Code-block fences are preserved across chunks.

  #### Update encoding

  `update(handle, text)` and `finalize(handle, text)` both serialize to the SimpleX
  `APIUpdateChatItem` command:

      /_update item <chatRef> <chatItemId> live=<on|off> json {"msgContent": {"type": "text", "text": "<text>"}}

  - `update` uses `live=on` so the recipient client renders the message with its
    live-update affordance.
  - `finalize` uses `live=off` so the message presents as a normal completed message.
  - The `chatItemId` is captured from each `APISendMessages` response and stored
    inside `SimplexMessageHandle`; callers never see it.

  #### Update failure handling

  A `CRChatCmdError` carrying `CEInvalidChatItemUpdate` (item too old, deleted, or
  not the bot's own message) is non-recoverable for that handle. The adapter falls
  back to a fresh `send` of the new text with the original `correlationId`,
  increments `adapter.outbound.update.fail{reason=…}`, and increments
  `adapter.outbound.update.total{outcome=fallback_send}`. Subsequent `update` calls
  on the same handle continue to fall back; `finalize` clears the fallback path.                                                          

  ### 6.4.6 Reconnection

  - **Network failures** (TCP reset, server unreachable, idle timeout, etc.) → exponential backoff reconnect (1s → 2s → 5s → 15s → 60s, then steady at 60s).
  - Reconnect attempts are logged; first failure logged at WARN, subsequent at INFO.
  - After 5 consecutive network failures, the Provider's admin notifier is invoked (throttled).
  - Inbound queue continues accepting outbound enqueues during disconnect; messages are sent on reconnect.
  - **Auth failures** (401-equivalent close codes — invalid or revoked session token) are handled separately: the adapter does NOT use the network-failure backoff schedule, because retrying with the same revoked token is futile and produces a tight reconnect loop that fills the log and burns CPU.
  - Auth-failure policy: each auth failure increments a counter; the adapter waits 5s then retries up to **3 consecutive auth failures**. On the 3rd consecutive auth failure the adapter transitions to **`state=AUTH_FAILED` (terminal)**, reports unhealthy via `adapter.connection.status=0`, stops reconnecting, and does NOT recover until the Provider process is restarted (typically with a new `infochat.adapters.simplex.session-token`). Each auth failure increments `adapter.simplex.auth.fail` (see §6.12) and is logged at ERROR; the terminal transition triggers the admin notifier (not throttled — operator must intervene).
  - A successful authenticated reconnect at any point resets both the network-failure and auth-failure counters.                                                                                                                                              

  ### 6.4.7 Failure surfaces                                                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  ┌──────────────────────────────────────────┬────────────────────────────────────┬───────────────────────────────────────────────────────────┐                                                                                                         
  │                 Failure                  │          Adapter behavior          │                    User-visible effect                    │
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤                                                                                                         
  │ WS disconnect, < 60s                     │ Auto-reconnect                     │ None (messages queued)                                    │
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ WS disconnect, > 5 min                   │ Auto-reconnect + admin notify      │ None to user; admin sees notification                     │                                                                                                         
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤                                                                                                         
  │ simplex-cli down                         │ Reconnect loop indefinitely        │ Bot appears offline; recipient eventually sees no replies │
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Session token revoked / invalid (401)    │ 3 auth retries → state=AUTH_FAILED │ Bot offline until restart with new token; admin notified  │
  │                                          │ (terminal); stop reconnecting      │                                                           │
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Identity assertion fails                 │ Drop the inbound message; log WARN │ None (silent skip)                                        │
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤                                                                                                         
  │ Outbound chunking exceeds protocol limit │ Throw MessagingException           │ Provider retries 3x, then logs                            │
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Update rejected (CEInvalidChatItemUpdate │ Fall back to new send() with orig. │ User sees a new message rather than an in-place update    │
  │   — item too old, deleted, not owner)    │ correlationId; metric incremented  │                                                           │
  └──────────────────────────────────────────┴────────────────────────────────────┴───────────────────────────────────────────────────────────┘                                                                                                         
                                                                                   
  ---

  ## 6.5 Signal adapter

  ### 6.5.1 Underlying protocol — open decision

  The Signal adapter implements the same `MessagingAdapter` SPI as the
  SimpleX adapter. The wire-protocol path between the adapter and Signal's
  servers is **an open v1 decision** (see "Open questions" at end of file);
  the three candidates and their trade-offs:

  | Path | Process model | Pros | Cons |
  |---|---|---|---|
  | `signal-cli` JSON-RPC subprocess | child process; adapter speaks JSON-RPC over stdio or local TCP | mature, used in production by other bots; identity material lives in a directory the operator can back up and rotate independently of the JVM; ACI mention rendering already implemented | extra OS process; subprocess lifecycle the adapter has to supervise; restart cost on JSON-RPC schema changes |
  | `libsignal-service-java` in-process | linked Java library; adapter speaks the Signal Service protocol directly | no subprocess; lowest latency; native JVM crash semantics | the upstream library tracks Signal-Android internals closely and breaks on Signal protocol upgrades more often than `signal-cli`; harder to isolate identity material from other JVM state |
  | `signald` daemon | sibling process; adapter speaks signald's JSON socket protocol | single shared daemon can serve multiple bots | additional moving piece; signald is in maintenance mode upstream; not recommended unless one of the others becomes infeasible |

  Provisional default for design-document purposes: **`signal-cli` JSON-RPC subprocess** — it has the cleanest identity-material story (the `~/.local/share/signal-cli/data/<account>/` directory) and is the most-used path in the existing bot ecosystem. The decision is provisional because the choice affects the on-disk shape of bot identity material (§6.5.4 below) and the failure-mode taxonomy; downgrading to `libsignal-service-java` later is a code change but not a spec change.

  Regardless of path, the adapter exposes the same SPI surface; downstream code (§6.3 contract, §6.7 selection, ProgressNotifier) does not care which transport variant is in use.

  ### 6.5.2 Capabilities (declared)

  supportsMentionByContactId = true   // Signal mention payload references ACI (mentionUuid) — §6.2.3
  supportsMembershipEvents   = true   // Signal exposes group-join and group-leave events at the
                                      //   protocol layer; the adapter surfaces them through
                                      //   InboundHandler.onUserJoinedGroup / onUserLeftGroup.
  supportsCodeFormatting     = true   // Signal renders monospace via its formatting metadata
  supportsMarkdownLinks      = false  // hard-asserted at startup (§6.2.1); v1 keeps URLs bare
                                      //   on Signal even though the protocol could carry link
                                      //   formatting — widening the rendering surface is a spec
                                      //   amendment, not a per-adapter footgun.
  supportsMultilineCode      = true   // Signal preserves newlines in code spans
  supportsAttachments        = false  // v1 doesn't use them
  supportsThreading          = false
  maxMessageBytes            = 8000   // Signal's effective text-content cap is well above SimpleX;
                                      //   conservative chunking floor, refine after observation.
  maxInboundMessageBytes     = profile-driven (see §6.2.2)
  maxInflightSends           = 4      // matches SimpleX; Signal's rate envelope is per-account
  maxSendsPerSecond          = 5      // conservative; Signal's per-account ceiling is higher but
                                      //   the v1 LLM concurrency cap (D46 §Topology) is the
                                      //   binding constraint anyway.
  supportsMessageEdit        = true   // Signal supports message edits within ~24h of original send;
                                      //   well beyond our request-scoped progress flow window.
  supportsTypingIndicator    = true   // Signal's typing indicator is first-class
  minEditInterval            = 600ms  // matches SimpleX; coalescing floor for ProgressNotifier

  ### 6.5.3 Identity assertion

  The Signal **ACI** (Account Identifier — a UUID Signal binds to its identity keys) is the cryptographic anchor (D10). `signal-cli` surfaces the ACI on every inbound envelope as `mentionUuid` for mention payloads and as the sender envelope identifier for the message itself. The adapter's `assertIdentity` returns:

  ```java
  Identity {
      contactId   = <ACI as canonical lowercase UUID string, e.g. "a1b2c3d4-...">,
      displayName = <profile name from Signal envelope, informational only — sanitized
                     at storage time per 04-security.md §4.8>,
      lastSeen    = <envelope timestamp>
  }
  ```

  The Signal phone number / username is **not** used as the contact id — those are recoverable through carrier and account-recovery flows; the ACI is the identity-key-bound primitive. Cross-adapter isolation invariant: an ACI is never matched against a SimpleX queue address even on byte-equality — the `(adapter, contact_id)` join key in [02-schema.md §2.1.1](02-schema.md) keeps the identity spaces disjoint.

  trustLevel = HIGH for ordinary user identity. The recovery-flow caveat for *admin* placement (SIM-swap, port-out fraud) lives at [04-security.md §4.4](04-security.md) "Per-adapter admin threat profile"; the design-side commitment in this section is mechanical (capability flags, identity assertion, wire path), not threat modelling.

  ### 6.5.4 Bot identity material

  Per [../spec/deployment.md](../spec/deployment.md) §Operator inputs item 7, the Signal adapter owns its own bot identity material. The on-disk shape depends on the §6.5.1 wire-protocol decision; for the provisional `signal-cli` JSON-RPC default:

  - The adapter expects a `signal-cli` account directory at the path configured via `infochat.adapters.signal.identity-dir` (typically `~/.local/share/signal-cli/data/<account>/` or a path mounted into the container). The directory contains the registered account's identity keys, profile keys, and signal-cli's local message store.
  - The bot's per-adapter contact id (the ACI used by the mention-recognition rule, §6.2.3 / §6.10) is read from this directory at adapter startup; it is NOT an operator-typed property. The adapter validates that the directory is readable, contains a registered account, and the ACI is parseable — failure refuses *that adapter's* startup but does not abort the Provider (per-adapter resilience, §6.7).
  - Account registration (`signal-cli register` / verification SMS) is an out-of-band operator step; the adapter never registers an account itself. A "directory present but no registered account" failure is logged at ERROR and surfaced as `state=AUTH_FAILED` (terminal until restart), parallel to the SimpleX session-token-revoked path in §6.4.6.

  An alternative wire-protocol path (`libsignal-service-java` or `signald`) would substitute its own identity-material shape; the spec-level commitment is "the adapter owns its identity material and validates it at startup," which is path-independent.

  ### 6.5.5 Lifecycle

  SignalAdapter.start(handler):
    1. Validate `infochat.adapters.signal.identity-dir` (§6.5.4)
    2. Spawn / connect to the `signal-cli` JSON-RPC endpoint
    3. Read the account ACI from the identity store; cache as the bot's per-adapter contact id
    4. Subscribe to the account's inbound message stream
    5. Spawn event reader → SignalEventDecoder → InboundHandler
    6. Spawn outbound queue worker → SignalCommandEncoder → JSON-RPC send

  SignalAdapter.stop():
    1. Drain outbound queue (best-effort, max 5s)
    2. Unsubscribe / close the JSON-RPC connection
    3. Send SIGTERM to the `signal-cli` subprocess (if owned), wait up to 3s, escalate to SIGKILL
    4. Idempotent: second stop is a no-op

  ### 6.5.6 Event decoding

  SignalEventDecoder maps `signal-cli` JSON-RPC envelopes to InboundMessage:

  - `envelope.dataMessage` with no `groupV2` field → `ScopeRef.Dm(senderACI)`. Body is `dataMessage.message`; mentions are decoded for completeness but DM mentions don't gate delivery.
  - `envelope.dataMessage` with a `groupV2` field → `ScopeRef.Group(groupV2.id)`. The decoder reads `dataMessage.mentions` (a list of `{mentionUuid, start, length}` records) and checks any `mentionUuid` for byte-equality against the bot's cached ACI. A match strips the mention spans from the rendered text and delivers; no match drops the message silently. Display-name matching is forever-out-of-v1 (§6.10).
  - `envelope.typingMessage` → ignored on the inbound side (we only *send* typing indicators).
  - `envelope.dataMessage.groupV2.revision` increments with `members` deltas → `InboundHandler.onUserJoinedGroup` / `onUserLeftGroup` per the diff. Both directions are exposed natively (`supportsMembershipEvents = true`).
  - `envelope.editMessage` (a remote edit of an inbound message) → ignored in v1; the bot does not re-process edited inbound messages.
  - Other envelope kinds (sync messages, receipts, story messages, call messages): logged at DEBUG, dropped.

  The decoder is pure (no I/O) and unit-tested with recorded JSON fixtures, mirroring the SimplexEventDecoder structure.

  ### 6.5.7 Command encoding

  SignalCommandEncoder serializes OutboundMessage to `signal-cli` JSON-RPC `send` / `sendEdit` calls:

  - DM: `{"method":"send","params":{"recipient":[<contactId>],"message":<text>}}`
  - Group: `{"method":"send","params":{"groupId":"<adapterGroupId>","message":<text>}}`
  - Edit (`update`/`finalize` on a `supportsMessageEdit=true` send): `{"method":"sendEdit","params":{"recipient"|"groupId":..., "targetTimestamp":<original send timestamp>,"message":<text>}}`

  Chunking: messages over 8000 bytes are split at the nearest line break before the limit; if a single line is longer, it's split at the limit. Code-block fences are preserved across chunks (close before chunk, reopen after) — same discipline as SimpleX §6.4.5.

  Edit failure handling: a `signal-cli` JSON-RPC error indicating the original message is no longer editable (deleted by user, edit window expired) is non-recoverable for that handle. The adapter falls back to a fresh `send` with the original `correlationId` and increments `adapter.outbound.update.fail{reason='edit_window_expired' | 'item_deleted' | …}`, paralleling the SimpleX `CEInvalidChatItemUpdate` handling in §6.4.5.

  ### 6.5.8 Reconnection

  - **Network failures** (subprocess died, JSON-RPC pipe broken, signal-server unreachable from `signal-cli`'s perspective surfaced as a transient signal-cli error) → exponential backoff reconnect (1s → 2s → 5s → 15s → 60s, then steady at 60s), matching the SimpleX reconnection cadence in §6.4.6.
  - First failure logged at WARN, subsequent at INFO. After 5 consecutive failures the Provider's admin notifier is invoked (throttled).
  - **Auth failures** (account no longer registered with Signal — typically a remote unregister, account compromise, or rate-limit-driven disable) are handled separately: each auth failure increments a counter; after **3 consecutive auth failures** the adapter transitions to terminal `state=AUTH_FAILED`, reports unhealthy via `adapter.connection.status=0`, stops reconnecting, and does NOT recover until the Provider process is restarted (typically with a freshly-registered identity directory). The terminal transition triggers the admin notifier (not throttled — operator intervention required).
  - A successful authenticated reconnect resets both the network-failure and auth-failure counters.

  ### 6.5.9 Failure surfaces

  ┌───────────────────────────────────────────────┬────────────────────────────────────┬───────────────────────────────────────────────────────────┐
  │                    Failure                    │          Adapter behavior          │                    User-visible effect                    │
  ├───────────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ signal-cli JSON-RPC pipe broken (< 60s)       │ Auto-reconnect                     │ None (messages queued)                                    │
  ├───────────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ signal-cli subprocess crash                   │ Respawn + auto-reconnect           │ None (messages queued); admin notified after 5x          │
  ├───────────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Account no longer registered (auth)           │ 3 retries → state=AUTH_FAILED      │ Bot offline until restart with re-registered identity     │
  │                                               │ (terminal); stop reconnecting      │ directory; admin notified                                 │
  ├───────────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Identity assertion fails (malformed envelope) │ Drop the inbound message; log WARN │ None (silent skip)                                        │
  ├───────────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Outbound oversize (transport rejection)       │ Throw MessagingException (PERMANENT)│ Reply aborted; not retried (matches §6.3.6)              │
  ├───────────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Outbound transient (rate-limit, network blip) │ Throw MessagingException (TRANSIENT)│ Provider retries per §6.3.6; escalates to PERMANENT     │
  │                                               │                                    │ on cap exhaustion                                        │
  ├───────────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤
  │ Edit rejected (window expired, item deleted)  │ Fall back to new send() with orig. │ User sees a new message rather than an in-place update    │
  │                                               │ correlationId; metric incremented  │                                                           │
  └───────────────────────────────────────────────┴────────────────────────────────────┴───────────────────────────────────────────────────────────┘

  ---
  ## 6.6 InMemoryAdapter (test double)                                                
                                                                                                                                                                                                                                                        
  Purpose

  Used by 08-verification.md end-to-end tests. No network, no SimpleX dependency. Lets us run full command flows in-process.

  **Production-deployment exclusion (D46).** The in-memory adapter is for tests. Production deployments MUST NOT enable it alongside SimpleX or Signal in the same Provider — its identity assertion is whatever the test harness hands it, with no cryptographic anchor. The adapter-registration startup check in §6.7 refuses a configuration that lists `inmemory` together with any production adapter (`simplex`, `signal`); the in-memory test deployment shape ([07-deployment.md §Deployment scenarios](07-deployment.md)) runs Provider with `infochat.adapters=inmemory` exclusively.

  **Default `trustLevel = LOW`.** Per [../spec/messaging.md](../spec/messaging.md) §Per-adapter trust level, the in-memory adapter's identity assertion is configurable but **defaults to LOW** so accidental privilege escalation in a test harness is impossible by default. Tests that exercise admin paths opt into HIGH explicitly via the `InMemoryAdapter(trustLevel)` constructor (and the LOW-trust opt-in property described in §6.8 must be set for the adapter to register at LOW at all — the test harness sets it).

  Behavior
  ```java
  public final class InMemoryAdapter implements MessagingAdapter {
      private final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();
      private final AdapterTrustLevel configuredTrust;  // LOW by default; HIGH only when test opts in
      private InboundHandler handler;

      /** Default constructor — trustLevel = LOW. Used by tests that do NOT
       *  exercise admin paths. */
      public InMemoryAdapter() { this(AdapterTrustLevel.LOW); }

      /** Test-only escape hatch: HIGH trust for tests that mint admin rows
       *  through the bootstrap flow and then drive admin commands. */
      public InMemoryAdapter(AdapterTrustLevel trust) { this.configuredTrust = trust; }

      @Override public String name() { return "inmemory"; }

      @Override public AdapterCapabilities capabilities() {
          return new AdapterCapabilities(
              true,            // supportsMentionByContactId — tests assert mention-by-id paths
              true,            // supportsMembershipEvents — tests drive group join/leave directly
              true,            // supportsCodeFormatting — exercises the markdown-code render path
              false,           // supportsMarkdownLinks — MUST be false (§6.2.1 startup gate)
              true,            // supportsMultilineCode
              false,           // supportsAttachments
              false,           // supportsThreading
              100_000,         // maxMessageBytes — generous for tests
              100_000,         // maxInboundMessageBytes — generous for tests
              1000,            // maxInflightSends — effectively unlimited concurrency
              10_000,          // maxSendsPerSecond — effectively unlimited rate
              true,            // supportsMessageEdit
              true,            // supportsTypingIndicator
              Duration.ZERO    // minEditInterval — tests assert exact event sequences
          );
      }

      @Override public AdapterTrustLevel trustLevel() { return configuredTrust; }

      @Override public void start(InboundHandler h) { this.handler = h; }
      @Override public void stop() { /* no-op */ }                                 
                                                                                                                                                                                                                                                        
      @Override public SentMessage send(OutboundMessage m) {
          var handle = new InMemoryMessageHandle(nextId.incrementAndGet());
          sent.add(m);
          updateHistory.put(handle, new CopyOnWriteArrayList<>(List.of(
              new UpdateEvent(Instant.now(), m.text(), false))));
          return new SentMessage(handle, m);
      }

      @Override public void update(MessageHandle h, String text) {
          updateHistory.get(h).add(new UpdateEvent(Instant.now(), text, false));
      }

      @Override public void finalize(MessageHandle h, String text) {
          updateHistory.get(h).add(new UpdateEvent(Instant.now(), text, true));
      }

      @Override public void setTyping(ScopeRef scope, boolean typing) {
          typingEvents.add(new TypingEvent(Instant.now(), scope, typing));
      }                                                                                                                                                                                                                                                 

      @Override public Identity assertIdentity(InboundMessage m) { return m.sender(); }                                                                                                                                                                 
   
      @Override public boolean groupExists(String id) { return knownGroups.contains(id); }                                                                                                                                                              
                                                                                   
      /* Test helpers (not part of SPI) */                                                                                                                                                                                                              
      public void deliverDm(String contactId, String text) { /* construct InboundMessage, dispatch */ }
      public void deliverGroupMention(String groupId, String contactId, String text) { /* same */ }                                                                                                                                                     
      public List<OutboundMessage> sentMessages() { return List.copyOf(sent); }    
      public List<UpdateEvent> updateHistory(MessageHandle h) { return List.copyOf(updateHistory.get(h)); }
      public List<TypingEvent> typingEvents() { return List.copyOf(typingEvents); }
      public void reset() { sent.clear(); updateHistory.clear(); typingEvents.clear(); }                                                                                                                                                                                                             
  }

  public record UpdateEvent(Instant at, String text, boolean isFinal) {}
  public record TypingEvent(Instant at, ScopeRef scope, boolean typing) {}                                                                                
  ```                                                                                                                                                                                                                                                      
  The test helpers (deliverDm, deliverGroupMention, sentMessages) aren't on the SPI; tests cast to the concrete type. This is fine because InMemoryAdapter is a test artifact.

  Capabilities posture

  InMemoryAdapter.capabilities() declares `supportsCodeFormatting = true` so tests exercise the code-formatting render path; the SimpleX adapter declares it false so tests of the plain-text fallback also run. The `supportsMarkdownLinks = false` declaration must match every other v1 adapter — the §6.2.1 startup gate would refuse to register an InMemoryAdapter that flipped it true, even in tests.
   
  ---
  ## 6.7 Adapter selection (multi-adapter, D46)

  One Provider may run **any non-empty subset of** the available `MessagingAdapter` implementations simultaneously (D46; [../spec/deployment.md](../spec/deployment.md) §Topology). The set is closed at startup — adding or removing an adapter is a Provider restart.

  ```properties
  # Production: SimpleX only
  infochat.adapters=simplex
  infochat.adapters.simplex.url=ws://localhost:5225
  infochat.adapters.simplex.session-token=${SIMPLEX_SESSION_TOKEN}
  infochat.adapters.simplex.identity-dir=/var/lib/infochat/simplex

  # Production: SimpleX + Signal in the same Provider (the v1 multi-adapter shape)
  infochat.adapters=simplex,signal
  infochat.adapters.simplex.url=ws://localhost:5225
  infochat.adapters.simplex.session-token=${SIMPLEX_SESSION_TOKEN}
  infochat.adapters.simplex.identity-dir=/var/lib/infochat/simplex
  infochat.adapters.signal.identity-dir=/var/lib/infochat/signal-cli/data/+15551234567

  # CI / tests (test-time deployment shape — must NOT include simplex/signal, §6.6)
  infochat.adapters=inmemory
  ```

  **Registration flow.** `AdapterRegistry` discovers every CDI bean implementing `MessagingAdapter`, then activates exactly the subset whose `name()` appears in `infochat.adapters`. The boot-time checks:

  1. The list MUST be non-empty (Provider has nothing to talk to otherwise).
  2. Every name in the list MUST resolve to a registered bean — an unknown name is a fatal startup error naming the offending entry.
  3. The §6.2.1 `supportsMarkdownLinks = false` gate is applied to every activated adapter's capabilities.
  4. Any activated adapter that declares `supportsMentionByContactId = false` MUST refuse to register if its group SPI is wired in this deployment (the v1 default — group access requires a cryptographic mention anchor).
  5. The production-exclusion check from §6.6 fires: a configuration that lists `inmemory` together with any production adapter is rejected.
  6. Per-adapter trust-level opt-in is checked (§6.8).

  **Per-adapter resilience.** Once registration passes, each activated adapter goes through its own `start(handler)` lifecycle. **A connection failure on one adapter does not prevent the others from coming up and does not abort Provider startup** ([../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior on startup — *Per-adapter resilience*; [../spec/architecture.md](../spec/architecture.md) §Ingest SPIs — *Asynchronous startup* applies the same shape to StreamSource). Each failed adapter is logged at ERROR severity and retries on a profile-driven backoff (1s → 2s → 5s → 15s → 60s, then steady at 60s — same cadence as §6.4.6 / §6.5.8).

  **Readiness rule.** The Provider's readiness probe reports **ready when at least one** activated adapter is connected (Provider can serve traffic on that adapter); **not-ready when zero adapters are connected**. Per-adapter connection state is exposed separately via metrics (`adapter.connection.status{adapter}`) so an operator can distinguish "fully healthy" from "degraded — one adapter down" without parsing readiness alone.

  **Per-adapter bot identity material.** Each adapter owns its own bot identity material (SimpleX queue keypair, Signal account directory; §6.4.1, §6.5.4) and validates it at adapter startup. Provider does not synthesize bot identity. The bot's per-adapter contact id used for mention recognition is derived from this material at adapter startup; it is not an operator-typed property.
                                                                                   
  ---
  ## 6.8 Trust levels and operator opt-in

  AdapterTrustLevel.HIGH   — adapter binds identity to cryptographic keys
  AdapterTrustLevel.LOW    — adapter trusts protocol-level user IDs (e.g., chat handles)

  - HIGH adapters: identity assertion is non-bypassable without key compromise. Recommended for any user-facing deployment.
  - LOW adapters: an attacker who can spoof the protocol (e.g., set their display handle to match a real user) could impersonate. Acceptable for closed groups or LANs and for the in-memory test adapter (§6.6).

  **Per-adapter LOW opt-in.** Because one Provider may run several adapters with different trust levels (D46), the LOW-trust opt-in is **keyed by adapter name**, not a single per-process flag. An adapter that reports `trustLevel = LOW` at registration is rejected unless the operator has explicitly set the matching opt-in property:

  ```properties
  infochat.adapters.<name>.allow-low-trust=true
  ```

  Provider's startup log emits one line per activated adapter:

  ```
  INFO  AdapterRegistry – activating adapter: simplex   (trust=HIGH)
  INFO  AdapterRegistry – activating adapter: signal    (trust=HIGH)
  INFO  AdapterRegistry – activating adapter: inmemory  (trust=LOW; allow-low-trust=true)
  ```

  If any adapter reports `trustLevel = LOW` and the matching `allow-low-trust=true` is missing, Provider refuses to start, naming the adapter. This forces a conscious per-adapter choice: lifting LOW-trust opt-in from one adapter does not implicitly lift it from another in the same deployment.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.9 Translation interaction                                                      
                             
  The provider always hands OutboundMessage.text to the adapter in the per-scope language already (after TranslationProvider, see 05-llm-and-embeddings.md §5.6). Adapters do not translate.
                                                                                                                                                                                                                                                        
  For inbound text, adapters do not translate either. Commands (slash-prefix) are English-only; chat-mode text is passed through verbatim to the chat agent, which receives scope_lang in its prompt and replies in that language.                      
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.10 @mention rules in groups

  Mention recognition is anchored to the bot's per-adapter cryptographic contact id (§6.2.3). There is **no operator-typed mention name** — the prior `infochat.adapter.bot-mention-name=@infochat-bot` property has been removed. The bot's per-adapter contact id is derived from the adapter's bot identity material at adapter startup (SimpleX queue address; Signal ACI / `mentionUuid`).

  The adapter must:

  1. Read each inbound group message's mention payload (the structured list of `(target_contact_id, span_start, span_length)` records the protocol provides). For SimpleX this is the chatItem's mention metadata; for Signal it is `dataMessage.mentions`.
  2. For each mention, compare `target_contact_id` byte-equal against the bot's cached per-adapter contact id. A match means the message is delivered.
  3. Strip the matched mention's covered spans from the rendered text before delivery so the parser sees the user's actual command/message. A trailing space immediately after the stripped span is also removed.
  4. Drop messages without a matching mention silently.

  Display-name string matching is **forever-out-of-v1** ([../spec/security.md](../spec/security.md) §What's intentionally NOT in v1; [04-security.md §4.8](04-security.md)). NFKC normalization, case-folding, and lookalike-character defenses are not relevant here — the comparison is over the cryptographic contact id, not the rendered display name. (Display names *are* still NFKC-normalized at storage time per [04-security.md §4.8](04-security.md), but that is a defense for terminal-output safety in admin surfaces, not a mention-recognition primitive.)

  If a deployment runs more than one bot identity on the same protocol (a v2 candidate, not a v1 shape), each bot's per-adapter contact id is naturally distinct because adapter contact ids are cryptographic — no naming-collision discipline is required at the property layer.
                                                                                   
  ---                                                                                                                                                                                                                                                   
  ## 6.11 Audit considerations                                                        
                           
  Adapters do not write to audit_log directly. The Provider records auditable events (admin actions, ban, etc.) using Identity.contactId as the actor key.
                                                                                                                                                                                                                                                        
  Adapter-internal events (connection lost, reconnect, decode failure) go to application logs at appropriate levels and to AdapterMetrics, NOT to audit_log.                                                                                            
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.12 Observability                                                               
                    
  AdapterMetrics (Micrometer):
                                                                                                                                                                                                                                                        
  - adapter.inbound.total{adapter, scope_kind} — counter                                                                                                                                                                                                
  - adapter.outbound.total{adapter, scope_kind, outcome} — counter, outcome ∈ {ok, retry, fail}                                                                                                                                                         
  - adapter.inbound.queue.size{adapter} — gauge                                                                                                                                                                                                         
  - adapter.outbound.queue.size{adapter} — gauge                                   
  - adapter.connection.status{adapter} — gauge (1 connected, 0 disconnected)                                                                                                                                                                            
  - adapter.identity.assert.fail{adapter} — counter (per-message identity assertion failure; e.g., a malformed inbound payload whose sender ID can't be verified)
  - adapter.simplex.auth.fail{adapter} — counter (per-session auth failure on the SimpleX WebSocket — invalid or revoked session token; distinct from the per-message `identity.assert.fail` above. 3 consecutive increments transition the adapter to terminal `state=AUTH_FAILED`; see §6.4.6.)
  - adapter.message.bytes{adapter, direction} — histogram
  - adapter.outbound.update.total{adapter, scope_kind, outcome} — counter, outcome ∈ {ok, coalesced, fail, fallback_send}
  - adapter.outbound.update.fail{adapter, reason} — counter, reason ∈ {item_too_old, item_deleted, not_owner, transport, unknown}
  - adapter.outbound.update.lag{adapter} — histogram (time between caller `update()` and edit actually transmitted, after coalescing)
  - adapter.typing.toggle{adapter, scope_kind, value} — counter (value ∈ {on, off}); zero for adapters without `supportsTypingIndicator`                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  /status (admin) reports adapter name, trust level, connection status, and the inbound/outbound queue sizes.                                                                                                                                           
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.13 What's intentionally NOT in v1

  - Telegram, Matrix, IRC, XMPP adapters — the SPI is designed to accept them but the v1 production set is closed at SimpleX + Signal (D32, D46). Adding a new adapter requires it to declare its trust level and identity-assertion shape per §6.2.3 and [04-security.md §4.8](04-security.md) before it can be enabled in production.
  - Voice / file attachments — `supportsAttachments` capability exists but is unused.
  - Threaded replies in groups — `supportsThreading` exists but unused.
  - Per-message read receipts back to the bot — adapters don't surface "delivered/read" to Provider.
  - End-to-end encryption configuration in the adapter layer — handled by the messaging app itself; nothing to configure.
  - Auto-translate of inbound user messages to English — chat agent receives original language, replies per `/lang`.
  - Bot-initiated DM to a contact who never spoke first — most messaging apps disallow this; we don't try. The bot only sends to scopes it has heard from.
  - Display-name-based mention recognition — forever-out-of-v1 (§6.10, [../spec/security.md](../spec/security.md) §What's intentionally NOT in v1).
  - Markdown link rendering — forever-out-of-v1 (§6.2.1; widening the rendering surface is a spec amendment, not a per-adapter capability flip).
  - Runtime adapter add/remove — the activated adapter list is closed at startup (§6.7); changing it is a Provider restart.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.14 Verification (what 08-verification.md will assert)

  - `AdapterContractTest` runs the same suite against every registered adapter (SimpleX, Signal, InMemory).
  - Identity stability: same contact id across multiple inbound messages.
  - Scope isolation: DM and Group with the same contact id produce different ScopeRefs.
  - Cross-adapter isolation: a SimpleX `(contact_id, simplex)` and a Signal `(contact_id, signal)` with byte-equal contact ids resolve to distinct `users` rows (covers the §6.5.3 invariant against the schema's `(adapter, contact_id)` join key).
  - Group filtering by **mention payload**: a group message whose mention payload does NOT reference the bot's per-adapter contact id is NOT delivered, even if the body textually contains a string that matches the bot's display name (asserts §6.10).
  - Mention stripping: delivered text does NOT contain the bot's mention span.
  - Mention-by-id rejection of display-name spoof: a group message whose body contains `@<bot-display-name>` *without* the matching mention payload is dropped (regression-test for the forever-out-of-v1 rule).
  - Chunking: a 10K outbound message arrives as multiple inbound chunks at the recipient (when fixture supports it; for SimpleX it's a recorded-WS-fixture test, for Signal a recorded JSON-RPC fixture).
  - Reconnection: simulated transport disconnect → adapter reconnects, queued outbounds eventually deliver (per-adapter; the simulation differs by adapter — WS for SimpleX, JSON-RPC pipe for Signal).
  - Per-adapter resilience: with two adapters configured, a forced startup failure on one of them must NOT prevent the other from coming up; readiness probe reports ready while the failing adapter retries (asserts §6.7).
  - Readiness rule: with the only enabled adapter disconnected, readiness reports not-ready; reconnect flips it to ready (asserts §6.7).
  - Trust gate: starting with an adapter that reports `trustLevel = LOW` and the matching `infochat.adapters.<name>.allow-low-trust=false` fails fast with a clear error naming the adapter (asserts §6.8).
  - `supportsMarkdownLinks` startup gate: registering an adapter that declares `supportsMarkdownLinks = true` fails Provider startup with a fatal log message (asserts §6.2.1).
  - Production-exclusion gate: a configuration that lists `inmemory` together with `simplex` or `signal` fails Provider startup (asserts §6.6).
  - Inbound size cap: an inbound message over `maxInboundMessageBytes` is dropped at the adapter, the drop counter `adapter.inbound.dropped{reason='oversize'}` increments, and the sender receives the fixed friendly reply (asserts §6.3.10).
  - Failure categorisation: a TRANSIENT failure is retried per the §6.3.6 schedule (max 3 attempts, full-jitter exponential); cap exhaustion escalates to PERMANENT and aborts the reply. A PERMANENT failure is NOT retried.
  - Membership events: an adapter with `supportsMembershipEvents = true` delivers a left-group event that triggers `group_membership.removed_at` soft-clear; an adapter with `supportsMembershipEvents = false` does NOT synthesise a left-group event from inactivity.
  - Edit lifecycle: on adapters with `supportsMessageEdit=true`, a `send` → 3× `update` → `finalize` sequence produces the expected coalesced edit count and the correct final visible text.
  - Edit fallback (no-edit adapter): on adapters with `supportsMessageEdit=false`, the same call sequence produces a single `send` carrying the final text only — no intermediate sends.
  - Edit rejection: a simulated `CEInvalidChatItemUpdate` (SimpleX) or `signal-cli` edit-window-expired error (Signal) during `update` triggers a fallback `send` with the original `correlationId` and increments the `fallback_send` outcome counter.
  - `finalize` exclusivity: any `update` call after `finalize` on the same handle throws `MessagingException`.
  - Typing indicator: on adapters with `supportsTypingIndicator=true`, a request emits exactly one `setTyping(scope, true)` at start and exactly one `setTyping(scope, false)` at end — including on error paths.

  ---

  ## 6.15 Capability matrix (non-normative)

  Reference for **prospective** adapter authors. The two v1 adapters (SimpleX, Signal) ship in-tree and have their own normative sections (§6.4, §6.5). This table captures how other common messaging protocols are likely to map onto the SPI capabilities introduced in §6.2, §6.3.8/9, §6.3.10/11. Non-normative — an adapter that does better than this table is welcome to declare it.

  | Platform   | supportsMessageEdit | supportsTypingIndicator | supportsMembershipEvents | Notes                                                                            |
  |------------|---------------------|-------------------------|--------------------------|----------------------------------------------------------------------------------|
  | SimpleX    | yes (`APIUpdateChatItem`) | no                | partial — see §6.4.2     | v1, normative — see §6.4. `live=on/off` flag used internally for live updates.   |
  | Signal     | yes (edit message)  | yes                     | yes                      | v1, normative — see §6.5. Edit window ~24h; ACI is the mention anchor.           |
  | Telegram   | yes (`editMessageText`) | yes                 | yes                      | Per-chat edit rate ~1/sec; honour via `minEditInterval = 1000ms`.                |
  | Matrix     | yes (`m.replace`)   | yes                     | yes                      | Edits are first-class; no time window.                                           |
  | XMPP       | partial (XEP-0308 LMC) | yes (XEP-0085)       | partial (MUC presence)    | Only the *most recent* message can be corrected — fits our handle lifecycle.     |
  | IRC / SMS  | no                  | no                      | partial / no              | Fall back to single-`send` finalize; consider deferred "still working…" message. |

  ---

  ## 6.16 Open questions

  Working list of design choices that v1 has not yet committed to. Each entry is named here so it can be picked up explicitly in a later design pass.

  1. **Signal wire-protocol path.** §6.5.1 lists three candidates (`signal-cli` JSON-RPC subprocess, `libsignal-service-java` in-process, `signald` daemon). The provisional default for design-document purposes is `signal-cli` JSON-RPC, but the choice has not been committed. The choice affects the on-disk shape of bot identity material (§6.5.4) and the failure-mode taxonomy (§6.5.9); downgrading later is a code change but not a spec change.

  2. **SimpleX `user_left_group` event availability.** SimpleX's bot API exposes `memberJoined` but the existence of a per-user `memberLeft` (or equivalent) at the chat-CLI / WebSocket layer is not yet confirmed against the current SimpleX release. The v1 design ships with `supportsMembershipEvents = false` for SimpleX (§6.4.2) and relies on permanent-delivery-failure-driven cleanup (§6.3.6 + spec/messaging.md §Failure handling — "User left group"). If a native left-group event is verified in a later SimpleX release, flipping the flag to `true` and surfacing the event via `InboundHandler.onUserLeftGroup` is a localized adapter change with no SPI or schema impact.

  ---
