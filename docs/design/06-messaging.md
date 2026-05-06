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
  │   ├── AdapterCapabilities.java    # supportsMarkdownCode, supportsAttachments, ...                                                                                                                                                                  
  │   ├── AdapterTrustLevel.java      # HIGH | LOW                                                                                                                                                                                                      
  │   └── MessagingException.java                                                                                                                                                                                                                       
  ├── routing/                                                                                                                                                                                                                                          
  │   └── AdapterRegistry.java        # CDI: discover all adapters; only one active at a time in v1                                                                                                                                                     
  ├── impl/                                                                                                                                                                                                                                             
  │   ├── simplex/                                                                 
  │   │   ├── SimplexAdapter.java                                                                                                                                                                                                                       
  │   │   ├── SimplexCliClient.java   # WebSocket bot client                       
  │   │   ├── SimplexEventDecoder.java                                                                                                                                                                                                                  
  │   │   └── SimplexCommandEncoder.java
  │   └── inmemory/                                                                                                                                                                                                                                     
  │       └── InMemoryAdapter.java    # test double (no network)                   
  └── observability/                                                                                                                                                                                                                                    
      └── AdapterMetrics.java                                                      
                                                                                                                                                                                                                                                        
  `infochat-provider` depends on `messaging-adapter-api`. Concrete impls are pulled in as separate Maven modules (`messaging-adapter-simplex`, `messaging-adapter-inmemory`) so a deployment can ship only what it needs.
                                                                                                                                                                                                                                                        
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
   *  (e.g., SimplexMessageHandle wraps chatItemId; InMemoryMessageHandle
   *  wraps an in-memory id). Callers MUST NOT inspect or persist it. */
  public sealed interface MessageHandle
      permits SimplexMessageHandle, InMemoryMessageHandle /*, future adapter handles */ {}
                                                                                                                                                                                                                                                        
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
      boolean supportsMarkdownCode,        // backticks render as monospace
      boolean supportsMultilineCode,       // triple-backtick blocks render
      boolean supportsAttachments,         // future use
      boolean supportsThreading,           // future use
      int     maxMessageBytes,             // chunking threshold
      int     maxInflightSends,            // CONCURRENCY: max send() calls in flight at once
                                           //   (e.g. 4 means up to 4 outbound messages
                                           //    may be transmitting simultaneously)
      int     maxSendsPerSecond,           // RATE: token-bucket cap on sends per second
                                           //   averaged over a 1s window, regardless of
                                           //   how many are concurrently in flight
      boolean supportsMessageEdit,         // adapter can update an already-sent message
                                           //   (e.g., SimpleX APIUpdateChatItem)
      boolean supportsTypingIndicator,     // adapter can show "bot is typing…"
      Duration minEditInterval             // adapter's recommended floor between edits
                                           //   on the same message; ProgressNotifier
                                           //   uses max(this, 600ms). Duration.ZERO if
                                           //   not applicable (e.g., InMemoryAdapter).
  ) {}                                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  public enum AdapterTrustLevel { HIGH, LOW }                                                                                                                                                                                                           
  ```                                                                                 
  The handler-style API (push from adapter to provider via InboundHandler) means each adapter manages its own connection lifecycle and event loop. Provider doesn't poll.                                                                               
                                                                                   
  ---                                                                                                                                                                                                                                                   
  6.3 Contract every adapter MUST honor                                            
                                                                                                                                                                                                                                                        
  These rules are part of the SPI contract. Tests in 08-verification.md enforce them via the AdapterContractTest suite, parameterized over every adapter.
                                                                                                                                                                                                                                                        
  6.3.1 Identity                                                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  - Identity.contactId is stable for the lifetime of the underlying user. Adapters must use the most stable identifier their protocol provides (SimpleX contact ID, Telegram user_id, Matrix MXID).                                                     
  - Identity.contactId is cryptographic when possible. Adapters that can't bind identity to a keypair must declare trustLevel = LOW.
  - Identity.displayName is never authoritative and may change. Provider stores it only for UX (e.g., showing "you" the right way).                                                                                                                     
  - Two messages with the same contactId from the same adapter MUST come from the same user. Spoofing requires private-key compromise (HIGH) or admin opt-in to a LOW-trust adapter.                                                                    
                                                                                                                                                                                                                                                        
  6.3.2 Scope                                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  - Every InboundMessage must carry a ScopeRef:                                                                                                                                                                                                         
    - Dm(contactId) for direct messages — the contact id of the human, not the bot.
    - Group(adapterGroupId) for group messages — a stable group identifier the adapter assigns.                                                                                                                                                         
  - A user's DM scope and any group scope are independent. Adapter must never collapse them.                                                                                                                                                            
                                                                                                                                                                                                                                                        
  6.3.3 @mention semantics in groups                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  - In a group, the adapter MUST only deliver messages that contain an @mention of the bot to InboundHandler.onMessage.                                                                                                                                 
  - Other group messages are silently dropped (the bot doesn't see them).          
  - The adapter MUST strip the @mention from text before delivery so the parser sees the user's actual command/message. Example: in a group, "@infochat-bot /summary tech" is delivered as "/summary tech".                                             
  - DM messages are delivered as-is.                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  6.3.4 Output formatting                                                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  - Adapter receives OutboundMessage.text already formatted with the project's plain-text-plus-backticks convention (see 03-commands.md §3.1).                                                                                                          
  - If capabilities.supportsMarkdownCode = true, the adapter MAY translate single backticks to its protocol's monospace formatting. Otherwise it MUST send the text verbatim — the recipient sees raw backticks (still readable).
  - The adapter MUST NOT inject extra formatting (no auto-markdown link conversion, no auto-emoji, no auto-mention).                                                                                                                                    
  - The adapter MUST chunk messages exceeding maxMessageBytes at line boundaries when possible, otherwise at maxMessageBytes - 1. Chunked messages MUST preserve code-block fences (close before chunk, reopen after).                                  
                                                                                                                                                                                                                                                        
  6.3.5 Idempotency                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  - The provider may retry send() after a transient failure. Adapters SHOULD deduplicate by OutboundMessage.correlationId over a 60-second window when the underlying protocol allows.                                                                  
  - If the adapter cannot deduplicate, the operator must accept occasional duplicate messages on retry. This is acceptable; bot output is not safety-critical.
                                                                                                                                                                                                                                                        
  6.3.6 Delivery semantics

  - `send()` returns when the adapter has accepted the message for transmission, NOT when the recipient has read it.
  - **Concurrency** is governed by `capabilities.maxInflightSends`: the adapter MUST NOT have more than that many `send()` calls actively transmitting at once. Excess callers either block briefly on an internal semaphore or are queued.
  - **Rate** is governed independently by `capabilities.maxSendsPerSecond`: even if `maxInflightSends` is high, the adapter MUST NOT exceed this many sends per second averaged over a 1s window. The two limits compose — whichever is reached first applies. (Concurrency caps "how many at once"; rate caps "how many per second".)
  - The adapter MUST NOT block the calling thread for more than a small bounded interval; if either cap would be violated, it must enqueue internally rather than stall the caller.
  - On unrecoverable send failure, the adapter throws `MessagingException`. Provider logs and retries up to 3 times with exponential backoff; subsequent failures notify a bot admin (throttled).                                                         
                                                                                                                                                                                                                                                        
  6.3.7 Inbound back-pressure                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  - InboundHandler.onMessage may take time (LLM calls, DB queries). The adapter MUST NOT drop inbound messages while the handler is busy.                                                                                                               
  - The adapter SHOULD enqueue inbound messages with a bounded queue (default 1000). On overflow, the adapter MUST drop the **NEWEST** message — the one that just arrived — and MUST send a synchronous throttle reply to its sender. Older messages already in the queue are preserved because they have already been acknowledged to the user (the bot didn't reject them on arrival) and may carry context the user is still waiting on a reply for.
  - The throttle reply uses a fixed, friendly text: `you're sending faster than I can keep up; the most recent message was dropped — please retry in a moment.` It is emitted via the same `OutboundMessage` path with `correlationId = <dropped inbound message id>` so it lines up under the dropped message in the user's client, bypasses the inbound queue (it does not pass through `InboundHandler.onMessage`), and is treated as priority on the outbound queue (throttle replies themselves are never dropped).
  - Drops are recorded as `adapter.inbound.dropped{adapter, scope_kind, reason='queue_full'}` and logged at WARN with the dropped message's `adapterMessageId` and `sender.contactId` (redacted). Persistent overflow from a single user is a hint that rate-limiting (§4.9) needs tightening — the throttle reply alone is not a substitute for `LlmRateLimiter`.
  - Per-user fairness: the adapter SHOULD NOT let one chatty user starve others. InMemoryAdapter and SimplexAdapter both implement a per-user-fair scheduler.

  6.3.8 Progress notifications

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

  6.3.9 Typing indicators

  - Adapters with `supportsTypingIndicator = true` SHOULD render the indicator while
    a long-running operation is in progress. Provider invokes `setTyping(scope, true)`
    at request start and `setTyping(scope, false)` at completion or error.
  - `setTyping` calls are advisory: the adapter MAY ignore rapid toggles or apply
    its own debouncing. The adapter MUST NOT block the caller.
  - Adapters with the capability disabled MUST treat both calls as silent no-ops.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  6.4 SimpleX Chat adapter                                                                                                                                                                                                                              
                                                                                   
  6.4.1 Underlying protocol

  SimpleX provides a self-hosted Chat CLI / SimpleX Chat server with a WebSocket-based bot API. The adapter speaks that WebSocket protocol.                                                                                                             
   
  - Connection: WebSocket to simplex-cli (default ws://localhost:5225), configurable via infochat.adapter.simplex.url.
  - Authentication: cookie-based session against the local simplex-cli; cookie configured via infochat.adapter.simplex.session-token (read from env in production).
  - Identity: the SimpleX contact display ID (e.g., xftp://...); cryptographically bound. trustLevel = HIGH.
  - **Auth-failure distinction:** the adapter classifies WebSocket close codes into two buckets — *auth failures* (401-equivalent codes from simplex-cli, e.g., revoked or invalid session token) and *network failures* (everything else: TCP reset, server unreachable, idle timeout). The two are handled with different reconnection policies; see §6.4.6. After 3 consecutive auth failures the adapter transitions to the terminal `state=AUTH_FAILED` and stops reconnecting until process restart.                                                                                                                                            
                                                                                                                                                                                                                                                        
  6.4.2 Capabilities (declared)                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  supportsMarkdownCode    = false   // SimpleX renders backticks as literal characters
  supportsMultilineCode   = false
  supportsAttachments     = false   // v1 doesn't use them
  supportsThreading       = false
  maxMessageBytes         = 4000    // SimpleX hard limit; adapter chunks above this
  maxInflightSends        = 4       // up to 4 outbound sends in flight concurrently
  maxSendsPerSecond       = 5       // and at most 5/s averaged; conservative, raise after observing
  supportsMessageEdit     = true    // APIUpdateChatItem ("/_update item …") with live=on/off
  supportsTypingIndicator = false   // SimpleX has no first-class typing indicator
  minEditInterval         = 600ms   // conservative floor; refine after observation                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  6.4.3 Lifecycle                                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
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
                                                                                                                                                                                                                                                        
  6.4.4 Event decoding                                                                                                                                                                                                                                  
   
  SimplexEventDecoder maps SimpleX chatItem events to InboundMessage:                                                                                                                                                                                   
                                                                                   
  - event.kind == "newChatItem" and chatItem.chatType == "direct" → ScopeRef.Dm(contact_id).                                                                                                                                                            
  - event.kind == "newChatItem" and chatItem.chatType == "group" → ScopeRef.Group(group_id). Filter for messages containing the bot's @mention; strip mention from text; deliver.
  - event.kind == "memberJoined" (group) → InboundHandler.onUserJoinedGroup(group_id, identity).                                                                                                                                                        
  - Other event kinds: logged at DEBUG, dropped.                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  The decoder is pure (no I/O) and unit-tested with recorded JSON fixtures.                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  6.4.5 Command encoding                                                                                                                                                                                                                                
                                                                                   
  SimplexCommandEncoder serializes OutboundMessage to SimpleX /sendMessage commands:                                                                                                                                                                    
   
  - DM: /_send @<contact> text=<base64-encoded text>                                                                                                                                                                                                    
  - Group: /_send #<group> text=<base64-encoded text>                              
                                                                                                                                                                                                                                                        
  Chunking: messages over 4000 bytes are split at the nearest line break before the limit; if a single line is longer, it's split at the limit. Code-block fences are preserved across chunks.

  ### Update encoding

  `update(handle, text)` and `finalize(handle, text)` both serialize to the SimpleX
  `APIUpdateChatItem` command:

      /_update item <chatRef> <chatItemId> live=<on|off> json {"msgContent": {"type": "text", "text": "<text>"}}

  - `update` uses `live=on` so the recipient client renders the message with its
    live-update affordance.
  - `finalize` uses `live=off` so the message presents as a normal completed message.
  - The `chatItemId` is captured from each `APISendMessages` response and stored
    inside `SimplexMessageHandle`; callers never see it.

  ### Update failure handling

  A `CRChatCmdError` carrying `CEInvalidChatItemUpdate` (item too old, deleted, or
  not the bot's own message) is non-recoverable for that handle. The adapter falls
  back to a fresh `send` of the new text with the original `correlationId`,
  increments `adapter.outbound.update.fail{reason=…}`, and increments
  `adapter.outbound.update.total{outcome=fallback_send}`. Subsequent `update` calls
  on the same handle continue to fall back; `finalize` clears the fallback path.                                                          
                                                                                                                                                                                                                                                        
  6.4.6 Reconnection

  - **Network failures** (TCP reset, server unreachable, idle timeout, etc.) → exponential backoff reconnect (1s → 2s → 5s → 15s → 60s, then steady at 60s).
  - Reconnect attempts are logged; first failure logged at WARN, subsequent at INFO.
  - After 5 consecutive network failures, the Provider's admin notifier is invoked (throttled).
  - Inbound queue continues accepting outbound enqueues during disconnect; messages are sent on reconnect.
  - **Auth failures** (401-equivalent close codes — invalid or revoked session token) are handled separately: the adapter does NOT use the network-failure backoff schedule, because retrying with the same revoked token is futile and produces a tight reconnect loop that fills the log and burns CPU.
  - Auth-failure policy: each auth failure increments a counter; the adapter waits 5s then retries up to **3 consecutive auth failures**. On the 3rd consecutive auth failure the adapter transitions to **`state=AUTH_FAILED` (terminal)**, reports unhealthy via `adapter.connection.status=0`, stops reconnecting, and does NOT recover until the Provider process is restarted (typically with a new `infochat.adapter.simplex.session-token`). Each auth failure increments `adapter.simplex.auth.fail` (see §6.11) and is logged at ERROR; the terminal transition triggers the admin notifier (not throttled — operator must intervene).
  - A successful authenticated reconnect at any point resets both the network-failure and auth-failure counters.                                                                                                                                              
                                                                                                                                                                                                                                                        
  6.4.7 Failure surfaces                                                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
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
  6.5 InMemoryAdapter (test double)                                                
                                                                                                                                                                                                                                                        
  Purpose
                                                                                                                                                                                                                                                        
  Used by 08-verification.md end-to-end tests. No network, no SimpleX dependency. Lets us run full command flows in-process.                                                                                                                            
   
  Behavior                                                                                                                                                                                                                                              
  ```java                                                                                 
  public final class InMemoryAdapter implements MessagingAdapter {
      private final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();                                                                                                                                                                          
      private InboundHandler handler;
                                                                                                                                                                                                                                                        
      @Override public String name() { return "inmemory"; }                        
                                                                                                                                                                                                                                                        
      @Override public AdapterCapabilities capabilities() {
          return new AdapterCapabilities(
              true, true, false, false,
              100_000,         // maxMessageBytes — generous for tests
              1000,            // maxInflightSends — effectively unlimited concurrency
              10_000,          // maxSendsPerSecond — effectively unlimited rate
              true,            // supportsMessageEdit
              true,            // supportsTypingIndicator
              Duration.ZERO    // minEditInterval — tests assert exact event sequences
          );
      }                                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
      @Override public AdapterTrustLevel trustLevel() { return AdapterTrustLevel.HIGH; }                                                                                                                                                                
   
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
                                                                                   
  InMemoryAdapter.capabilities() declares supportsMarkdownCode=true so tests exercise the markdown-code path; the SimpleX adapter declares false so tests of the plain-text fallback also run.                                                          
   
  ---                                                                                                                                                                                                                                                   
  6.6 Adapter selection                                                            
                       
  Exactly one MessagingAdapter is active per Provider deployment in v1.
                                                                                                                                                                                                                                                        
  # Production with SimpleX                                                                                                                                                                                                                             
  infochat.adapter=simplex                                                                                                                                                                                                                              
  infochat.adapter.simplex.url=ws://localhost:5225                                                                                                                                                                                                      
  infochat.adapter.simplex.session-token=${SIMPLEX_SESSION_TOKEN}                                                                                                                                                                                       
   
  # CI / tests                                                                                                                                                                                                                                          
  infochat.adapter=inmemory                                                        
                                                                                                                                                                                                                                                        
  AdapterRegistry finds all CDI beans implementing MessagingAdapter, picks the one matching infochat.adapter. Boot fails if zero or multiple matches.                                                                                                   
   
  Multi-adapter (e.g., SimpleX + Telegram simultaneously) is deferred to v2. Designing the SPI to support it (handler dispatch keyed by adapter name) is in scope; activating two at once is not.                                                       
                                                                                   
  ---                                                                                                                                                                                                                                                   
  6.7 Trust levels and operator opt-in                                             
                                                                                                                                                                                                                                                        
  AdapterTrustLevel.HIGH   — adapter binds identity to cryptographic keys
  AdapterTrustLevel.LOW    — adapter trusts protocol-level user IDs (e.g., chat handles)                                                                                                                                                                
                                                                                                                                                                                                                                                        
  - HIGH adapters: identity assertion is non-bypassable without key compromise. Recommended for any user-facing deployment.                                                                                                                             
  - LOW adapters: an attacker who can spoof the protocol (e.g., set their display handle to match a real user) could impersonate. Acceptable for closed groups or LANs.                                                                                 
                                                                                                                                                                                                                                                        
  Provider's startup log:                                                          
                                                                                                                                                                                                                                                        
  INFO  AdapterRegistry – activating adapter: simplex (trust=HIGH)                 
                                                                                                                                                                                                                                                        
  If trust=LOW, the operator must set infochat.adapter.allow-low-trust=true explicitly. Otherwise the Provider refuses to start. This forces a conscious choice.                                                                                        
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  6.8 Translation interaction                                                      
                             
  The provider always hands OutboundMessage.text to the adapter in the per-scope language already (after TranslationProvider, see 05-llm-and-embeddings.md §5.6). Adapters do not translate.
                                                                                                                                                                                                                                                        
  For inbound text, adapters do not translate either. Commands (slash-prefix) are English-only; chat-mode text is passed through verbatim to the chat agent, which receives scope_lang in its prompt and replies in that language.                      
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  6.9 @mention rules in groups                                                     
                              
  The bot's mention is configured per deployment:
                                                                                                                                                                                                                                                        
  infochat.adapter.bot-mention-name=@infochat-bot
                                                                                                                                                                                                                                                        
  The adapter must:                                                                

  1. Detect the mention in inbound group messages.                                                                                                                                                                                                      
  2. Strip the mention token (and a single trailing space) from the delivered text.
  3. Drop messages without the mention silently.                                                                                                                                                                                                        
                                                                                   
  If a group has multiple bots that share the same Provider deployment (rare but possible in v2), the per-adapter mention name must be unique across them.                                                                                              
                                                                                   
  The mention name is case-insensitive but unicode-normalized (NFKC) before comparison to defeat lookalike-character impersonation.                                                                                                                     
                                                                                   
  ---                                                                                                                                                                                                                                                   
  6.10 Audit considerations                                                        
                           
  Adapters do not write to audit_log directly. The Provider records auditable events (admin actions, ban, etc.) using Identity.contactId as the actor key.
                                                                                                                                                                                                                                                        
  Adapter-internal events (connection lost, reconnect, decode failure) go to application logs at appropriate levels and to AdapterMetrics, NOT to audit_log.                                                                                            
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  6.11 Observability                                                               
                    
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
  6.12 What's intentionally NOT in v1                                              
                                     
  - Multi-adapter active simultaneously — SPI supports it but only one is wired.
  - Telegram, Matrix, Signal adapters — deferred to v2; the SPI is designed to accept them.                                                                                                                                                             
  - Voice / file attachments — supportsAttachments capability exists but is unused.                                                                                                                                                                     
  - Threaded replies in groups — supportsThreading exists but unused.                                                                                                                                                                                   
  - Per-message read receipts back to the bot — adapters don't surface "delivered/read" to Provider.                                                                                                                                                    
  - End-to-end encryption configuration in the adapter layer — handled by the messaging app itself; nothing to configure.                                                                                                                               
  - Auto-translate of inbound user messages to English — chat agent receives original language, replies per /lang.                                                                                                                                      
  - Bot-initiated DM to a contact who never spoke first — most messaging apps disallow this; we don't try. The bot only sends to scopes it has heard from.                                                                                              
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  6.13 Verification (what 08-verification.md will assert)                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  - AdapterContractTest runs the same suite against every registered adapter.
  - Identity stability: same contact id across multiple inbound messages.                                                                                                                                                                               
  - Scope isolation: DM and Group with the same contact id produce different ScopeRefs.                                                                                                                                                                 
  - Group filtering: a group message without @mention is NOT delivered.                                                                                                                                                                                 
  - Mention stripping: delivered text does NOT contain the bot's mention.                                                                                                                                                                               
  - Chunking: a 10K outbound message arrives as multiple inbound chunks at the recipient (when fixture supports it; for SimpleX it's a recorded-WS-fixture test).                                                                                       
  - Reconnection: simulated WS disconnect → adapter reconnects, queued outbounds eventually deliver.                                                                                                                                                    
  - Trust gate: starting with trust=LOW and allow-low-trust=false fails fast with a clear error.
  - Edit lifecycle: on adapters with `supportsMessageEdit=true`, a `send` → 3× `update` → `finalize` sequence produces the expected coalesced edit count and the correct final visible text.
  - Edit fallback (no-edit adapter): on adapters with `supportsMessageEdit=false`, the same call sequence produces a single `send` carrying the final text only — no intermediate sends.
  - Edit rejection: a simulated `CEInvalidChatItemUpdate` (or equivalent adapter rejection) during `update` triggers a fallback `send` with the original `correlationId` and increments the `fallback_send` outcome counter.
  - `finalize` exclusivity: any `update` call after `finalize` on the same handle throws `MessagingException`.
  - Typing indicator: on adapters with `supportsTypingIndicator=true`, a request emits exactly one `setTyping(scope, true)` at start and exactly one `setTyping(scope, false)` at end — including on error paths.                                                                                                                                                        

  ---

  6.14 Capability matrix (non-normative)

  Reference for future adapter authors. Captures how common messaging protocols
  map onto the SPI capabilities introduced in §6.2 and §6.3.8/9. Non-normative —
  an adapter that does better than this table is welcome to declare it.

  | Platform   | supportsMessageEdit | supportsTypingIndicator | Notes                                                                            |
  |------------|---------------------|-------------------------|----------------------------------------------------------------------------------|
  | SimpleX    | yes (`APIUpdateChatItem`) | no                | Reference impl. `live=on/off` flag used internally to render live updates.       |
  | Signal     | yes (edit message)  | yes                     | Edit window ~24h; not a concern for our request-scoped progress flow.            |
  | Telegram   | yes (`editMessageText`) | yes                 | Per-chat edit rate ~1/sec; honour via `minEditInterval = 1000ms`.                |
  | Matrix     | yes (`m.replace`)   | yes                     | Edits are first-class; no time window.                                           |
  | XMPP       | partial (XEP-0308 LMC) | yes (XEP-0085)       | Only the *most recent* message can be corrected — fits our handle lifecycle.     |
  | IRC / SMS  | no                  | no                      | Fall back to single-`send` finalize; consider deferred "still working…" message. |

  ---
