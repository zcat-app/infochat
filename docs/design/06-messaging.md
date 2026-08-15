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
      // --- Sizing ---
      int     maxInboundMessageBytes,      // transport-layer first-defense cap on inbound
                                           //   message size (§6.2.2 + spec/messaging.md
                                           //   §Required SPI surface — Inbound message
                                           //   size cap). Tighter than the application-level
                                           //   chat-mode body cap; messages over this size
                                           //   are dropped by the adapter before delivery.
                                           //   Set to Integer.MAX_VALUE only if the protocol
                                           //   provides no enforcement mechanism; the
                                           //   adapter's design note MUST justify that.
      int     maxSendsPerSecond,           // RATE: production adapters pace outbound sends
                                           //   (send / update / finalize) to this many per
                                           //   second, averaged over a 1s window, via a shared
                                           //   OutboundRateLimiter (§6.3.6). Transport
                                           //   self-protection beneath the Provider per-user
                                           //   limiter, never a second user-facing throttle.
                                           //   Concurrency is bounded by the transport's
                                           //   one-outstanding-send rule, not a capability field.
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
  `ProgressNotifier` and the outbound-delivery chokepoint
  `OutboundDelivery`, §6.3.6) branch on it without any heuristic
  re-classification.

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
  reply with `correlationId` set to the dropped inbound message id —
  §6.3.10), and the application-level cap fires as the second defense
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

  **v1 implementation note.** The per-profile values above are the design
  target. v1 ships a **fixed 16 KiB** transport cap (the `laptop` value) on
  both production adapters, single-sourced from each codec's
  `MAX_INBOUND_TEXT_BYTES` constant — the capability's
  `maxInboundMessageBytes()` reads the constant, so the decode-time
  enforcement and the advertised SPI value cannot drift (a test pins the
  equality in place of the prior hand-maintained "stay in lockstep"
  comment). Profile-driven sizing is deferred: the
  `infochat-messaging-adapter` module is intentionally decoupled from the
  `InfochatProfile` plumbing (it depends on neither `infochat-core` nor the
  per-service config modules that define the profile enum), and the
  capability is a compile-time constant on a static field, so threading a
  runtime profile value would mean a new cross-module dependency for no v1
  behavioural gain. Raising the cap on a larger profile is then a change to
  the constant (or to profile threading, if that coupling is later
  introduced), not a spec amendment.

  ### 6.2.3 Mention-recognition rule

  Per [../spec/messaging.md](../spec/messaging.md) §Required SPI
  surface — *Mention-recognition rule*, whether a group message
  counts as an `@mention` of the bot is decided **only** by the
  cryptographic contact id of the mention target. Concretely:

  - Each enabled adapter knows the bot's own cryptographic contact id
    for the group. **Signal**: the bot's ACI (the UUID Signal binds to
    its identity keys; `signal-cli` surfaces it as `mentionUuid`),
    derived once at adapter startup from the account store. **SimpleX**
    (decision D51): the bot's **per-group member id**, read from each
    inbound group frame at `chatInfo.groupInfo.membership.memberId` —
    member ids are per-group, so there is no single startup-derived
    value; the anchor travels in the frame. (The bot's queue address,
    which the pre-v6.5.4.1 SimpleX mention payload carried, no longer
    appears in that payload and is no longer the mention anchor.)
  - The adapter's mention payload — SimpleX's top-level `mentions{}`
    object (display name → `memberId`), the Signal message envelope's
    mention list — references the mentioned party's contact id. The
    adapter compares that id by **byte-equality** against the bot's own
    contact id (SimpleX: `groupInfo.membership.memberId`; Signal: the
    cached ACI); a match means the message is delivered to
    `InboundHandler.onMessage` with the mention payload stripped.
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

  ### 6.2.4 Outbound attachments (D74)

  The outbound half of the media surface: Provider hands the adapter
  an `OutboundAttachment` record —

  ```java
  public record OutboundAttachment(
      ScopeRef scope,
      String filePath,        // a PATH, never bytes (D74)
      String mimeType,
      String displayFileName,
      String correlationId    // same non-null-only contract as OutboundMessage
  ) {}
  ```

  — via `MessagingAdapter.sendAttachment(OutboundAttachment)`. The
  payload is a file path, not bytes, because signal-cli attaches by
  path and SimpleX file transfer completes asynchronously past
  `send()`'s return ([../spec/messaging.md](../spec/messaging.md)
  §Required SPI surface — *Send attachment*, decision D74).

  **Completion contract.** `sendAttachment` blocks until the transport
  reports delivery completion — success, or a classified failure per
  [../spec/messaging.md](../spec/messaging.md) §Failure handling. The
  return is the success signal; the throw with its `FailureCategory`
  is the failure signal. The file MUST remain readable by the adapter
  for the whole transmit, and the adapter MUST NOT retain or copy the
  payload beyond delivery — the spool file is reclaimed by Provider
  on return. Attachment sends obey the same transient/permanent
  classification, the same bounded retry ladder, and the same
  at-least-once delivery non-guarantee (D64) as text sends.

  **Caller gate.** Provider invokes `sendAttachment` only on an
  adapter whose `supportsOutboundAttachments` is true, and refuses
  payloads above `maxOutboundAttachmentBytes` before invoking it; the
  SPI default throws PERMANENT so a misinvocation on a false-flag
  adapter fails loudly. The in-memory test double declares `true`
  with a test-scale ceiling so Provider-side delivery paths are
  testable ahead of the production codecs.

  **Verified ceilings (M1-800, measured against the bundled
  transports).** `maxOutboundAttachmentBytes` is
  `1_073_741_824` (1 GiB) for SimpleX and `157_286_400` (150 MiB)
  for Signal. Measurement method, both extracted from the bundled
  binaries the adapters spawn (provider image
  `infochat-test-infochat-provider:latest`):
  - SimpleX — simplex-chat v6.5.4.1 links simplexmq's XFTP
    `maxFileSize = gb 1` (1 GiB, binary units), the soft limit its
    send path enforces on every file message (`checkSndFile` rejects
    an over-limit file with the `fileSize` error before any upload
    starts); the 5 GiB `maxFileSizeHard` is the agent-internal limit
    for standalone uploads and never applies to contact/group sends.
  - Signal — signal-cli 0.14.5's service library declares
    `ServiceConfig.MAX_ATTACHMENT_SIZE = 157286400` (150 MiB), the
    Signal service's single-attachment ceiling; signal-cli performs
    no client-side pre-check, so the adapter declares the ceiling
    and Provider's caller gate refuses over-limit payloads before
    the upload attempt.

  **SimpleX wire form and XFTP completion signal (M1-800, verified
  against the bundled simplex-chat v6.5.4.1).** The file send is the
  same id-addressed `/_send <target> json …` verb as text, carrying
  one composed message with a `filePath` member next to
  `msgContent` (`{"filePath":"…","msgContent":{"type":"file","text":""}}`);
  simplex-chat's composed-message parser accepts `filePath` (plain
  file) and starts an XFTP upload (live-probed: a real upload ran,
  emitting `sndFileProgressXFTP` frames with `sentSize`/`totalSize`).
  The completion signal `sendAttachment` blocks on is the async
  **`sndFileCompleteXFTP`** event whose `chatItem` carries the same
  `itemId` the send's `newChatItems` ack returned: simplex-chat emits
  it when the XFTP upload is done AND the file-description message is
  handed to the recipient's queue (the `SENT` confirmation), for both
  direct and group scopes — after it, the transport never reads the
  local file again, so the spool file is safe to release. Failure
  surfaces as `sndFileError` (with the chat item when one exists) or
  as the send command's `chatCmdError` (`fileNotFound`, `fileSize`,
  `fileIOError` tags — all classify PERMANENT). A contact that is not
  ready degrades the same upload to `sndStandaloneFileComplete`
  (live-probed) — the adapter treats any completion other than
  `sndFileCompleteXFTP` on its own chat item as a PERMANENT failure
  rather than a delivery. Verification trail: bundled-binary command
  surface (`/file @<contact> <file_path>` help text), live WS probe
  of a real XFTP upload, and the shipped version's source
  (v6.5.4 tag: `Subscriber.hs` SFDONE/`checkSndInlineFTComplete`);
  the probe host's inbound SMP delivery is broken (even the
  profile-creation welcome never arrives), so the ready-contact
  completion frame itself is source-verified, not live-captured.

  **Image-typed composed messages (M1-841, probed against the bundled
  simplex-chat v6.5.4 — the `SIMPLEX_CHAT_VERSION` Dockerfile.jvm pin;
  probe evidence under `.scratch/m1841-probe/`).** SimpleX apps send
  images as `MCImage` messages: an image-typed `msgContent` carrying a
  small inline preview beside the same `filePath` full-res upload. The
  bundled CLI accepts that form on `/_send <target> json`, in both DM
  and group scope, with the identical completion contract as the plain
  file send.

  - *Upstream source at the v6.5.4 pin (simplexmq `b981dcb7` /
    simplex-chat tag `v6.5.4`).* The image constructor is
    `MsgContent`'s `MCImage {text, image}` with wire tag `"image"`
    (`src/Simplex/Chat/Protocol.hs:704-713`, tag table `:612-645`); its
    JSON shape is `{"type":"image","text":"…","image":"…"}` where
    `image` is a raw JSON string — `newtype ImageData Text`
    (`src/Simplex/Chat/Types.hs:837-846`). The composed-message parser
    accepts `filePath` (plain `CryptoFile`) beside ANY `msgContent`,
    image included (`FromJSON ComposedMessage`,
    `src/Simplex/Chat/Controller.hs:1769-1778`). The size law: the
    whole encoded chat message must fit `maxEncodedMsgLength = 15602`
    bytes (`Protocol.hs:876-880`); an over-limit message fails the send
    with store error `largeMsg` — `ECMLarge → SELargeMsg` on the send
    path, which has no compression fallback
    (`src/Simplex/Chat/Store/Messages.hs:224-228`). Preview encoding:
    upstream's own inline images are `data:image/png;base64,…` /
    `data:image/jpg;base64,…` data URIs of 338 chars
    (`Library/Commands.hs:156`) and 12,202 chars
    (`Library/Internal.hs:2821`).
  - *Live probe (two-identity throwaway harness: the sha256-verified
    pinned binary, both identities on the public SMP/XFTP servers,
    frames captured on both sides).* DM send of
    `{"filePath":"/…/img.png","msgContent":{"type":"image","text":"…","image":"data:image/png;base64,…"}}`
    (199-byte PNG file, 154-char preview): ACCEPTED — the ack is the
    same `newChatItems` (item content `sndMsgContent` wrapping
    `MCImage`, `file` member `{fileId, fileName, fileSize,
    fileSource, fileStatus: sndStored, fileProtocol: "xftp"}`); the
    async sequence is `sndFileProgressXFTP` (sent/totalSize) ×2 →
    **`sndFileCompleteXFTP`** — the identical completion tag the
    adapter's `sndFileCompleteXFTP`-or-PERMANENT mapping
    (SimpleXMessageCodec.java:356-363) already enforces for file
    sends; no new ack or completion tag appears. The recipient's
    frames: `rcvMsgContent` carrying the image `msgContent` with the
    preview, then `rcvFileDescrReady` + `rcvInvitation` for the XFTP
    file. A not-ready contact still degrades the upload to
    `sndStandaloneFileComplete` (observed; same degradation as the
    file form, above).
  - *Group scope.* The same send via `/_send #<group> json` with a
    joined member: ACCEPTED — identical frame sequence
    (`sndFileProgressXFTP` ×2 → `sndFileCompleteXFTP`, status ladder
    sndNew → sndStored/sndSent/sndRcvd → sndComplete); the member
    receives it in-group (`groupRcv` item with the image
    `rcvMsgContent` + `rcvFileDescrReady`/`rcvInvitation`). A
    member still at `invited` (not joined) does not block the send or
    its completion on the sender.
  - *Refusal arm (limits are measured, not assumed).* A preview whose
    data URI is 16,500 chars — over `maxEncodedMsgLength` once
    wrapped — is REFUSED with `chatCmdError → errorStore →
    largeMsg` before any message is sent. Boundary: a 14,822-char
    preview is ACCEPTED (`sndFileCompleteXFTP`), so the practical
    preview ceiling is `maxEncodedMsgLength` (15,602) minus the
    message wrapper (~500-700 bytes of envelope, file member, text).
    **Operational nuance:** on the refused over-limit send the
    `filePath` file still uploaded and completed standalone
    (`sndStandaloneFileComplete`) — the preview must be bounded BEFORE
    the send or the recipient gets a stray standalone file beside the
    refused message (M1-842's generator enforces the recorded bound).
  - *Scope of these claims.* Parser acceptance, wire content, frame
    sequences, and the recipient-side receipt events are what the bot
    controls and what this record asserts; how a given client renders
    the preview is recipient-side and out of scope (spec
    messaging.md §Required SPI surface keeps the same promise
    boundary).

  VERDICT: CONTINUE — the bundled v6.5.4 CLI accepts image-typed
  composed messages with an inline preview in DM and group scope, the
  completion contract is unchanged (`sndFileCompleteXFTP`), and the
  measured preview ceiling (14,822 accepted / 16,500 refused, source
  limit 15,602 minus wrapper) plus the standalone-upload nuance are
  the recorded inputs M1-842/M1-843 build on.

  **Provider-side spool lifecycle** (M1-801; the D75 privacy posture).
  Provider owns the file lifecycle for D74's file-path payload: the
  backend's bytes are spooled in a tmpfs directory, handed to the
  adapter by path, and reclaimed on adapter-reported delivery
  completion — with an age sweeper as the crash guarantee (a Provider
  crash between fetch and send must not leak). The spool is
  tmpfs-resident, never persistent storage, so it is RAM-backed and
  capacity is host memory: writes refuse cleanly past the capacity
  bound (no partial file left). The PNG metadata strip (D75: the
  workflow graph ComfyUI embeds in text chunks, which contains the
  prompt, is removed before delivery; pixel-bounded before stripping,
  `commands.md` §Content) runs before the spool write. Config keys:

  | Key | Default | Meaning |
  |---|---|---|
  | `infochat.image.spool.dir` | `/dev/shm/infochat-image-spool` | tmpfs spool directory |
  | `infochat.image.spool.capacity-bytes` | `1073741824` | spool capacity; over-capacity writes are refused (tmpfs exhaustion is host memory exhaustion) |
  | `infochat.image.spool.max-age` | `PT1H` | age bound for the sweeper's eviction |
  | `infochat.image.spool.sweep-interval` | `15m` | the sweeper's cadence (@Scheduled expression default) |

  The sweeper reads the injected app-wide `Clock` for its eviction
  decision (engineering-rules §9; M1-444 pattern) — the same seam the
  rate buckets and probation checks use — and its age bound is
  independent of the container-side janitor window (M1-797): both
  bound the same privacy invariant as separate layers.

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
  - The mention is taken from the adapter's mention payload (SimpleX: the top-level `mentions{}` object resolved against `groupInfo.membership.memberId`, per D51 / §6.2.3; Signal: the envelope mention list). An inbound group message that has no mention payload referencing the bot is silently dropped, even if the message body textually contains a string that resembles the bot's display name (or, on SimpleX, only quote-replies to a bot message).
  - The adapter MUST strip the recognized mention payload (and the spans of the message body it covers) from the delivered text so the parser sees the user's actual command/message. Example: in a group, an inbound message whose body is "@infochat-bot /summary tech" — with the mention payload referencing the bot's contact id over the leading 14 characters — is delivered as "/summary tech".
  - An adapter that declares `supportsMentionByContactId = false` MUST refuse to start in a deployment that enables its group SPI; the registration-time check (§6.7) catches the mismatch.
  - DM messages are delivered as-is.                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  ### 6.3.4 Output formatting                                                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  - Adapter receives OutboundMessage.text already formatted with the project's plain-text-plus-backticks convention (see 03-commands.md §3.1).                                                                                                          
  - If capabilities.supportsCodeFormatting = true, the adapter MAY translate single backticks to its protocol's monospace formatting. Otherwise it MUST send the text verbatim — the recipient sees raw backticks (still readable).
  - URLs are always rendered bare. Adapters MUST NOT wrap URLs in protocol-specific link syntax even if the protocol supports it; `supportsMarkdownLinks` is asserted false at adapter-registration startup (§6.2.1) and the field is therefore not consulted on the per-message path.
  - The adapter MUST NOT inject extra formatting (no auto-markdown link conversion, no auto-emoji, no auto-mention).                                                                                                                                                                      
  - **Outbound chunking (over-cap delivery).** A transport with an adapter-side outbound text cap MUST NOT drop an over-cap message: the adapter splits the text into ordered chunks that each fit the cap and transmits them as consecutive sends. In v1 only SimpleX carries such a cap (`MAX_OUTBOUND_TEXT_BYTES`, 4 000 UTF-8 bytes, §6.4.2); `SimpleXAdapter.send` applies the split, and the codec's per-text cap check remains as a defensive second wall each chunk passes through. Signal declares no adapter-side outbound cap and never chunks. The split contract:
    - **Byte-split algorithm:** greedy and line-based — whole lines (including their trailing newline) are packed into a chunk while the chunk's UTF-8 byte length stays within the cap; a single line that cannot fit a chunk on its own is hard-split at code-point boundaries.
    - **Code-point boundary rule:** a split never cuts a UTF-8 multi-byte sequence or a UTF-16 surrogate pair — every chunk is well-formed text on its own. (The cap is bytes, not characters: non-Latin scripts cost 2–4 bytes per character, so over-cap texts are *more* likely under per-scope `/lang` translation, not less.)
    - **Code-block fence protocol:** a cut inside a triple-backtick block closes the fence at the end of the chunk (` ``` ` as the chunk's final line) and reopens it as the first line of the next chunk, so every chunk renders with balanced fences.
    - **Ordering:** chunk N+1 is transmitted only after chunk N's transport ack (one frame in flight per connection, §6.3.6); each chunk draws its own rate-limiter token.
    - **Handle semantics:** `send()` returns the handle of the **last** chunk; a later `update()`/`finalizeMessage()` edits that message.
    - **Non-atomicity:** a chunked send is not atomic — a mid-sequence failure can deliver a prefix; the Provider-side retry (§6.3.6) re-sends from the first chunk, which §6.3.5's duplicate tolerance accepts. The in-place edit path (`update`/`finalizeMessage`) is never chunked: an over-cap edit body still fails PERMANENT at the codec cap check (an in-place edit of one existing message cannot be split across messages).
                                                                                                                                                                                                                                                        
  ### 6.3.5 Idempotency                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  - **v1 outbound delivery is at-least-once, and no layer deduplicates it.** The provider may retry send() after a transient failure; that retry MAY deliver a duplicate. No adapter inspects `OutboundMessage.correlationId` (every occurrence in `infochat-messaging-adapter` is javadoc or record-field plumbing — the id is carried into the returned handle and never read back), and the Provider-side `OutboundDelivery` chokepoint keeps no delivery ledger. The operator must accept occasional duplicate messages on retry. This is acceptable; bot output is not safety-critical.
  - **`correlationId` is NOT a dedup key**, and MUST NOT be treated as one (§6.2). Stability across retries is a per-call-site property, not an SPI contract: all but three `new OutboundMessage(...)` sites in `infochat-provider` mint a fresh `UUID.randomUUID()` per construction, so a correlationId-keyed dedup table would be inert at nearly every site. The three stable-id sites — `DigestWorker`, `ApproveGroupCommandHandler`, `RejectGroupCommandHandler` — are all *deliberate* re-sends that MUST NOT be suppressed (`/retry --digest` is spec-mandated to post again; see [../spec/commands.md](../spec/commands.md) §Conversation control).
  - **Why the retired 60-second dedup window could not have worked**, independent of whether any adapter implemented it. The back-off sleeps are negligible: with `infochat.messaging.retry.{max-attempts=3,base-delay-ms=250,growth-factor=2.0}` and full jitter, the two inter-attempt sleeps total under 750 ms. Wall clock is instead dominated by the per-send ack wait — `SimpleXAdapter.ACK_TIMEOUT` is **30 s**, applied per `transmitChunk`, so three attempts against an unresponsive transport span ~90 s and the last attempt lands well outside a 60 s window. A chunked send (§6.3.4) multiplies that further: each chunk awaits its own ack, so an N-chunk message costs up to N × 30 s *per attempt*. The window was shorter than the retry sequence it was meant to cover.
  - **No Provider-side chokepoint can upgrade this to at-most-once.** The duplicate that actually reaches a user is the ambiguous transmit — the adapter sends, the ack times out, the ladder re-sends, and both copies arrive. Deciding whether to suppress the re-send requires knowing whether the ambiguous transmit landed, which only the adapter or the transport can observe; a chokepoint records success only *after* the adapter reports it, so it is blind to exactly this case. This is an observability boundary, not a missing implementation. Closing it is adapter/transport-level work and is out of scope for v1 (decision **D64**).
                                                                                                                                                                                                                                                        
  ### 6.3.6 Delivery semantics

  - `send()` returns when the adapter has accepted the message for transmission, NOT when the recipient has read it.
  - **Rate** is governed by `capabilities.maxSendsPerSecond`: each production adapter paces its outbound transmits (send / update / finalize) to at most this many per second, averaged over a 1s window, via a shared `OutboundRateLimiter` — a token bucket that starts full, so a burst up to the cap transmits immediately and sustained sending past it blocks the calling thread for the sub-second interval until the next token accrues. The contract is **one token per outbound wire frame**, charged at the frame boundary — so when a single SPI call expands into more than one frame (the edit-failure fresh-send fallback of §6.4.5 / §6.5.7 emits a second frame after the rejected edit), the extra frame draws its own token and a sustained-fallback stream cannot transmit at twice the cap. The bucket holds the per-token interval in **nanoseconds**, not milliseconds: a millisecond interval integer-divides to 1 ms/token for any cap above 1000/s, which would silently floor a 10000/s declaration (the `InMemoryAdapter`'s "effectively unlimited" rate) to 1000/s. This is transport self-protection: it keeps the Provider from driving the transport fast enough to trip the messaging service's own server-side rate limit or flag the bot. It is NOT a second user-facing throttle — per [../spec/messaging.md](../spec/messaging.md) §Failure handling the per-user limiter (the inbound `RateCapBucket` and the cost-side `LlmRateLimiter`) is the single source of truth for "slow this user down", and this pacer sits strictly underneath it. (The `InMemoryAdapter` test double declares the field for SPI completeness but has no transport to pace.)
  - **Concurrency** is bounded by the transport itself, not by a capability field: each v1 transport keeps exactly one outbound frame in flight per connection — the JDK WebSocket permits one outstanding `sendText` (SimpleX, §6.4) and the Signal JSON-RPC client serializes its writes likewise (§6.5) — so there is no `maxInflightSends` knob. A multi-in-flight outbound design is forever-out-of-v1.
  - The adapter MUST NOT block the calling thread for more than the small bounded pacing interval above.

  #### Failure categorisation and retry policy

  Per [../spec/messaging.md](../spec/messaging.md) §Failure handling, every send/update/finalize failure raised by an adapter is categorised as `TRANSIENT` or `PERMANENT` (the `FailureCategory category()` accessor on `MessagingException`). The adapter MUST set the category at throw site; an adapter that cannot tell the two apart MUST default to `PERMANENT`.

  The single Provider-side implementation is the `OutboundDelivery` bean (`infochat-provider`, package `…provider.messaging`): every reply, progress placeholder/finalize, periodic digest, and group command announcement routes its send/update/finalize through it — no caller touches `MessagingAdapter.send`/`update`/`finalizeMessage` directly. It owns the retry loop, cap-exhaustion escalation, and the bot-removed permanent-failure counter. The profile-driven values below are read from `application.properties` keys `infochat.messaging.retry.{max-attempts,base-delay-ms,growth-factor}` and `infochat.messaging.permanent-failure-threshold` (base defaults declared so the `%test` profile, which inherits no `%<profile>` namespace, still resolves them).

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
  - **Group-deleted-upstream:** in v1, handled via the same permanent-failure threshold path as bot-removed — a "group no longer exists" send failure counts as a `PERMANENT` failure toward the per-group threshold above, and cleanup fires when the streak crosses it. The adapter-specific group-not-found / group-no-longer-exists signal and the immediate-cleanup-on-single-signal branch are deferred to v2 (no adapter→Provider carrier for that failure sub-class exists today). See [../spec/messaging.md](../spec/messaging.md) §Failure handling.
  - **User-left-group:** when the adapter exposes a per-user left-group signal (`supportsMembershipEvents = true`), Provider soft-clears the `group_membership` row by setting `removed_at = NOW()` per [02-schema.md §2.1.4](02-schema.md). On adapters with `supportsMembershipEvents = false` (**both v1 production adapters**, F-live-10) there is no such signal, and per-user leave cleanup is an explicit **non-commitment** in v1: a group-scope send is addressed to the group, so a leaver produces no per-user `PERMANENT` send failure from which a leave could be inferred, and the permanent-failure-driven cleanup path covers only the bot-removed-from-group / group-deleted cases, never a per-user leave. Such adapters MUST NOT synthesise a left-group event from inactivity; the row stays `removed_at IS NULL` and any `is_group_admin` flag persists — a departed group admin still counts as the active admin (auto-promote does not fire) and silently resumes admin on rejoin, with a bot-admin `/demote` of the stale admin the documented remediation ([../spec/security.md](../spec/security.md) §Authorization model).
                                                                                                                                                                                                                                                        
  ### 6.3.7 Inbound back-pressure                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  - InboundHandler.onMessage may take time (DB-backed intake, non-interruptible command dispatch). To keep the transport read loop responsive, each v1 transport adapter hands inbound delivery to a dedicated single-thread executor: `SimpleXWebSocketClient` and `SignalJsonRpcClient` each own one; `InMemoryAdapter` dispatches synchronously and has no dispatch queue. The read thread never blocks on handler work, and inbound is never dropped while the handler is merely busy — slower-than-handler arrivals queue on the executor.
  - **Interruptible LLM work is offloaded Provider-side.** The transport dispatch thread runs only the intake gates (rate cap, size cap, normalize, the authorization steps) and non-interruptible dispatch — the whole inbound turn does NOT run on it. At step 6 the D35 interruptible class — chat-mode turns, user-issued `/summary`, user-issued `/retry` re-roll (never `/retry --digest`) — is handed to `InterruptibleDispatcher`, a bounded per-request worker pool (`infochat.chat.dispatch.max-concurrency`), freeing the dispatch thread the moment intake ends. That concurrency is what makes the spec'd behaviours reachable over live transports: a second same-`(user, scope)` request is admitted while the first still holds its `InFlightTracker` slot and gets the reject-with-guidance reply ([../spec/commands.md](../spec/commands.md) §Surface conventions), and `/stop` — dispatched inline like every other slash command — cancels a worker-held LLM call immediately (D35). Non-interruptible work (periodic digests, ingest, mutating commands, `/retry --digest`) keeps the dispatch thread's arrival order. On pool saturation, submissions degrade to the prior inline-on-dispatch-thread behaviour (caller-runs), so the bounded inbound queue below remains the DOS memory bound and no new drop path is introduced.                                                                                                               
  - **The dispatch executor's work queue is BOUNDED** (default 1000, `INBOUND_QUEUE_CAPACITY`, a per-client constructor parameter). This is the memory bound the threat model's DOS category requires: the Provider's step-1.5 per-`(adapter, contactId)` rate cap (`RateCapBucket` — [../spec/security.md](../spec/security.md) §Rate limiting) runs *inside* the task dispatched onto this executor, strictly downstream of the queue, so it bounds the work done per dequeued item but never the queue's own memory. Without the bound the dispatch executor's default unbounded `LinkedBlockingQueue` would absorb a sustained inbound flood whose passing fraction keeps the single dispatch thread busy, growing without limit until the only user-facing service OOMs.
  - On overflow — a delivery that cannot be enqueued because the queue is at capacity — the adapter drops the **NEWEST** delivery (the one that just arrived), increments a cumulative dropped-inbound counter, and logs the drop at WARN with the **redacted** `sender.contactId`. Deliveries already queued are preserved. The drop is **silent** to the sender: v1 ships no synchronous throttle reply — the original spec's friendly throttle text was a UX nicety, whereas the security property is the memory bound (error code `E4007`).
  - Persistent overflow from a single sender is a hint that rate-limiting (§4.9) needs tightening; the bounded queue is a last-resort memory guard, not a substitute for the upstream `RateCapBucket` / `LlmRateLimiter`.
  - **Step-1.5 rate cap is split by registration.** The step-1.5 `RateCapBucket` route forks on an in-memory `RegisteredContactSet` lookup BEFORE the router's users-row SELECT (which stays deliberately downstream, so the route adds no per-stranger DB read): a **registered** sender (`registration_state IN ('invited','vouched')` and not banned) gets its own per-`(adapter, contactId)` token bucket; an **unregistered** sender (a set miss) shares a **single per-adapter stranger limiter** and mints no per-id bucket at all. Consequently the per-id bucket map — and the `maxContactBuckets` hard cap (§4.9) that bounds it — now backstops **only the registered (invite-gated) key space**, not all inbound. This remediates the prior medium DOS finding: a Sybil flood of distinct stranger ids can no longer pin the per-id map at `maxContactBuckets` and silent-drop every brand-new contact's first DM (a registration-availability lockout); the flood now contends for one shared bucket, so a newcomer's invite message is rate-limited transiently and admitted again as that bucket refills, never held behind a capacity wall. The shared limiter is per-adapter (one adapter's flood cannot starve another's newcomers, D46), reuses the contact cap/window, and — its key space being the fixed enabled-adapter count — needs no key-space cap and is not swept by the idle-bucket eviction. `RegisteredContactSet` is rehydrated from `users` at Provider startup (after the bootstrap-admin ensure, before adapter activation) and kept coherent by committed registration effects (invite-accept add, ban remove, unban re-add). The shared stranger bucket loses per-newcomer fairness within it — accepted, consistent with the no-fair-scheduler note below; it is a defense-in-depth hardening of the v1 mechanism, not full Sybil resistance (the v2 connection-gate root fix is out of scope).
  - Per-user fairness is **not** implemented in v1: the single dispatch thread processes inbound intake and non-interruptible dispatch in arrival order, interruptible turns run on the bounded worker pool (above) with no fairness ordering of their own, and one sender's share is bounded by its per-minute rate-cap budget and by the per-user cross-scope concurrency cap (`infochat.chat.dispatch.per-user-cap`, default 2) rather than by a fair scheduler. The concurrency cap is a ceiling on one sender's concurrent share of the worker pool — at most that many non-terminal interruptible turns (queued + running) per sender across ALL scopes at one instant, so ordinary group membership cannot occupy every worker; a request beyond it is rejected at intake with fixed guidance, consuming no rate-cap token, no in-flight slot and no pool slot. It is a bound, never an ordering policy: queue order remains arrival order. A per-user-fair scheduler is deferred to a later revision.

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

  The provider-side concrete notifier is `StageProgressNotifier`; it
  drives this lifecycle for `/summary` (digest and chat-agent wiring remain named
  follow-ups). It resolves the bound adapter from
  `AdapterRegistry.activatedAdapters()` keyed by the inbound `adapterName`, sends
  the placeholder, renders the five non-terminal stages as coalesced `update`s,
  and finalizes on the payload-carrying terminal calls `complete(scope, finalText)`
  / `fail(scope)` (the SPI gained these so the finalized body is the real summary,
  not a stage label). Edit coalescing additionally runs **provider-side**, honoring
  a single system-wide floor (`infochat.messaging.progress.min-edit-interval-ms`,
  default 600ms): the spec's `max(adapterMin, systemFloor)` degrades to this
  `systemFloor` in v1 because per-adapter `adapterMin` is not yet surfaced to the
  notifier — the adapter-side coalescing bullet above remains the transport's own
  obligation. The dispatch seam: a `CommandHandler` that self-delivers via the
  notifier returns `null` from `handle`, and `InboundRouter` performs no send for
  that invocation (no double-send).

  ### 6.3.9 Typing indicators

  - Adapters with `supportsTypingIndicator = true` SHOULD render the indicator while
    a long-running operation is in progress. Provider invokes `setTyping(scope, true)`
    at request start and `setTyping(scope, false)` at completion or error.
  - `setTyping` calls are advisory: the adapter MAY ignore rapid toggles or apply
    its own debouncing. The adapter MUST NOT block the caller.
  - Adapters with the capability disabled MUST treat both calls as silent no-ops.

  ### 6.3.10 Inbound message size cap

  - Per §6.2.2, every adapter SHOULD enforce a transport-layer size ceiling on inbound messages, dropping anything above `capabilities.maxInboundMessageBytes()` **before** delivery to `InboundHandler.onMessage`.
  - The drop is recorded at `adapter.inbound.dropped{adapter, scope_kind, reason='oversize'}` and logged at WARN with the redacted `sender.contactId` and the message's `adapterMessageId`. The drop is **silent** at the adapter boundary — no reply is emitted — matching the §6.3.7 inbound-queue overflow drop. User-facing "message too large" feedback is the job of the application-layer chat-mode body cap ([03-commands.md §3.1](03-commands.md); [../spec/commands.md](../spec/commands.md) §Input length caps), which fires at a far smaller, profile-scaled threshold (well below `maxInboundMessageBytes`, so an honest oversize message reaches it first) and sits **behind the Provider rate cap** per [../spec/security.md](../spec/security.md) §Authorization model step 1.5 — *over-cap inbound never produces a friendly error reply* under a sustained flood. Emitting a reply at the adapter boundary, below that rate cap, would reopen the DoS-amplification surface that gate deliberately closes; the transport cap's role is the cheap hostile-sender load-shed described in the next bullet, not user feedback.
  - The application-level chat-mode body cap from [03-commands.md §3.1](03-commands.md) fires as the **second defense** on anything that slips past — typically on adapters whose protocol provides no enforceable transport ceiling and which therefore declare `maxInboundMessageBytes = Integer.MAX_VALUE`. The two caps are layered, not redundant: the transport cap bounds resource cost from a hostile sender at the adapter boundary; the application cap bounds prompt-injection blast radius once the message has been parsed.

  ### 6.3.11 Membership events

  - Adapters with `supportsMembershipEvents = true` deliver native group-membership signals through `InboundHandler.onUserJoinedGroup(adapterGroupId, identity)` and `InboundHandler.onUserLeftGroup(adapterGroupId, identity)`.
  - Adapters with `supportsMembershipEvents = false` MUST NOT call either method. Provider falls back to permanent-delivery-failure-driven cleanup per §6.3.6 — a left-group event is **never** synthesised from inactivity, send-receipt absence, or any other indirect signal at the adapter layer.
  - The bot-removed-from-group and group-deleted-upstream events are separate from per-user membership events (they fire whether or not `supportsMembershipEvents` is true) and continue to flow through their existing adapter signals — typically a top-level error or an adapter-specific "you are no longer a member" event, not the per-user membership stream.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.4 SimpleX Chat adapter                                                                                                                                                                                                                              

  ### 6.4.1 Underlying protocol

  SimpleX provides a self-hosted Chat CLI / SimpleX Chat server with a WebSocket-based bot API. The adapter speaks that WebSocket protocol.                                                                                                             
   
  - Connection: WebSocket to the local simplex-chat on a loopback port (default 5225), configured via infochat.adapters.simplex.ws-port; the simplex-chat executable is infochat.adapters.simplex.binary.
  - Authentication: none. The WebSocket bot API is a loopback channel to the co-located simplex-chat subprocess the adapter itself spawns, so there is no session, cookie, or token to present. The bot identity material lives in the subprocess data-dir (infochat.adapters.simplex.data-dir). Session-token auth is deferred for the v1 loopback-IPC transport (§6.4.6).
  - **Bot identity**: SimpleX group @-mention recognition resolves against the bot's **per-group `memberId`**, read per-frame from `chatInfo.groupInfo.membership.memberId` (decision D51, see §Mention anchoring below), so the bot needs no derived account-level identity. The earlier `/show_address` self-address derivation — queried from the running simplex-chat over the adapter's own WebSocket at `start()` and re-derived on supervised restart — became consumer-less once D51 landed and was **removed**; `start()` now issues no identity query and builds the group-candidate handler directly. The bot identity material is the subprocess data-dir, not an operator-typed property and not parsed from disk.
  - Identity: the SimpleX contact display ID (e.g., xftp://...); cryptographically bound. trustLevel = HIGH.
  - **Mention anchoring (D51):** SimpleX's group `newChatItem` carries a top-level `mentions{}` object (display name → per-group `memberId`), and the bot's own per-group `memberId` is in the same frame at `chatInfo.groupInfo.membership.memberId`. The adapter recognises a bot @mention by **byte-equality of a `mentions{}` memberId against `groupInfo.membership.memberId`**; it does NOT scan the message body for the bot's display name. The bot's own mention span is located by resolving the matched memberId back to its `mentions{}` display-name key and stripping the `formattedText` segment(s) carrying that `memberName`, leaving co-mentions of other members intact. `supportsMentionByContactId = true`.

  ### 6.4.2 Capabilities (declared)                                                                                                                                                                                                                         

  supportsMentionByContactId = true   // SimpleX mention resolves to a per-group memberId (D51, §6.2.3)
  supportsMembershipEvents   = false  // OPEN — SimpleX has a join event but no documented per-user
                                      //   left-group event at the bot-API layer. v1 ships false and
                                      //   relies on permanent-failure-driven cleanup (§6.3.6).
                                      //   See "Open questions" at end of file.
  supportsCodeFormatting     = false  // SimpleX renders backticks as literal characters
  supportsMarkdownLinks      = false  // hard-asserted at startup (§6.2.1); SimpleX renders bare URLs
  maxInboundMessageBytes     = 16 KiB, fixed in v1 (see §6.2.2)  // SimpleX bot-API inbound text frames
                                                            //   are bounded by the WS frame cap; the
                                                            //   adapter clamps tighter to give the
                                                            //   application-layer cap headroom.
  maxSendsPerSecond          = 5      // at most 5/s averaged, paced via OutboundRateLimiter
                                      //   (§6.3.6); conservative, raise after observing
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
                                                                                   
  - event.kind == "newChatItem" and chatInfo.type == "direct" → ScopeRef.Dm(contact_id).                                                                                                                                                            
  - event.kind == "newChatItem" and chatInfo.type == "group" → ScopeRef.Group(group_id). The decoder reads the chatItem's top-level `mentions{}` object (display name → `memberId`); if any mention's `memberId` byte-equals the bot's own `chatInfo.groupInfo.membership.memberId` (D51, §6.2.3 / §6.10), the bot's mention spans are stripped from the rendered text and the message is delivered. Group messages with no mention resolving to the bot are dropped — display-name string matching is forever-out-of-v1, and a quote-reply that sets `meta.userMention` but carries no bot mention is NOT delivered.
  - event.kind == "memberJoined" (group) → InboundHandler.onUserJoinedGroup(group_id, identity). (`supportsMembershipEvents = false` despite this — Provider does not rely on the join event for cleanup logic; per-user *left* events are the missing piece, see §6.4.2.)
  - Other event kinds: logged at DEBUG, dropped.                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  The decoder is pure (no I/O) and unit-tested with recorded JSON fixtures.

  #### Live v6.5.4.1 field locations

  The decoded field paths below are confirmed against frames captured from a
  live simplex-chat **v6.5.4.1** deployment (a throwaway loopback
  `ws://127.0.0.1:5225` probe). Earlier hand-rolled fixtures encoded different
  field names and silently dropped 100% of real inbound:

  - **chat-type discriminator** is `chatInfo.type` (`"direct"` / `"group"`),
    **not** `chatInfo.chatType`. A real `newChatItems` event with
    `chatInfo.type == "direct"` decodes to an inbound; the old `chatType` read
    dropped it as `Ignored("newChatItem-without-chatType")`.
  - **inbound adapterMessageId** is `chatItem.meta.itemId`, not a top-level
    `chatItem.itemId`.
  - **inbound DM sender display name** is `contact.localDisplayName` (the
    locally-resolved handle, e.g. `"admin_1"`), not `contact.displayName`.
    `contact.profile.displayName` is the sender's self-asserted profile name and
    is never read (identity is always the connection `contact_id`, D10).
  - **send-result chat-item id** (the response to our own `/_send`, a
    `newChatItems` frame carrying a `corrId`) is
    `chatItems[0].chatItem.meta.itemId`, same `meta.itemId` location as a
    received item.
  - **error tag** is the `.type` of a nested error object — both
    `chatError.errorType.type` (e.g. `"commandError"`) and
    `chatError.storeError.type` (e.g. `"groupAlreadyJoined"`) occur. Only the
    enum-like `.type` is read; the sibling free-form `message` (which may echo
    user prose) is never surfaced (security.md §User content in exceptions).
    Unrecognized tags still fail closed to PERMANENT (§6.4.7).                                                                                                                                                                             


  #### Group invitation auto-accept

  A `receivedGroupInvitation` async event (the bot was added to a group but has
  not yet joined — `membership.memberStatus == "invited"`) decodes to a
  `ReceivedGroupInvitation` carrying two fields from the live v6.5.4.1 frame:

  - **group id** is `resp.groupInfo.groupId` (the same numeric id echoed into
    `/_join`), queue-address-validated at decode like every other group id.
  - **inviter contact id** is `resp.groupInfo.membership.invitedBy.byContactId`,
    read only when `invitedBy.type == "contact"`. A non-contact inviter
    (`"member"` / `"unknown"`, e.g. a pre-contact host) carries no contact id
    Provider can resolve, so the invitation is dropped fail-closed and never
    auto-joined.

  The adapter makes no accept decision (D10): it surfaces the invitation across
  the new `MessagingAdapter.setGroupInvitationHandler` SPI callback (parallel to
  `setMembershipEventHandler`). Provider's `GroupInvitationHandler` applies the
  D47 registered-only gate — it instructs the adapter to `joinGroup` (issuing
  `/_join #<groupId>`) ONLY when the inviter is a registered
  (`registration_state IN ('invited','vouched')`), non-banned user. An invitation
  from an unregistered or banned inviter is **ignored, not declined**: the bot
  neither joins nor replies, so it does not join an arbitrary group and does not
  reveal it processed the invitation (less traffic, no presence signal). This
  closes a prior redteam's vector 3 — the gate cannot be bypassed to make the
  bot join arbitrary groups.

  `/_join #<groupId>` is live-confirmed against v6.5.4.1: it returns a
  `userAcceptedGroupSent` command response (`memberStatus "accepted"`) followed
  by an async `userJoinedGroup` (`memberStatus "connected"`), so the bot's
  membership transitions invited→connected. The join is issued fire-and-forget
  (it returns no chat-item handle and is not a paced user message, so it draws no
  rate token); the group then enters the
  D47 `approval_status='pending'` machine on the first @mention (no approval logic
  added here). The SPI addition is capability-shaped (default no-op
  `setGroupInvitationHandler` / `joinGroup`), so the Signal and in-memory adapters
  are unaffected and need no change.

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

  #### Bootstrap admin: claim-token, not an address (D50)

  SimpleX has **no operator-supplied bootstrap admin address** to canonicalize
  (decision D50). SimpleX cannot prove a sender's advertised address — inbound
  identity is the per-connection `contact_id`, and a sender's profile address is
  self-asserted, not verified — so the by-address bootstrap `AdminBootstrap` uses
  for Signal is impossible here (the discarded by-address approach of trusting the
  advertised address let any contact spoof the admin). Instead the operator
  configures a secret `infochat.adapters.simplex.admin-token`, and the **first DM
  whose normalized body equals the token** registers that connection's
  `contact_id` and flips `is_admin = true` on it (`SimpleXAdminClaim`; the claim
  is single-use while a SimpleX admin exists, §7.6.3 in
  [07-deployment.md](07-deployment.md)). `AdminBootstrap` deliberately skips
  SimpleX, and a stray `infochat.adapters.simplex.admin` is inert (gate 7 counts
  only the token).

  The `extractQueueAddressId` parser still exists; since the bot's own
  queue-address derivation was removed (the mention anchor is the
  per-group `memberId`, D51), its surviving caller is
  `SimpleXAdapter.canonicalizeContactId`, which canonicalizes an operator-supplied
  admin contact link to the bare queue id. The wizard (`6-adapter.sh`)
  collects only the secret token.

  ### 6.4.5 Command encoding                                                                                                                                                                                                                                
                                                                                   
  SimplexCommandEncoder serializes OutboundMessage to SimpleX /sendMessage commands:                                                                                                                                                                    
   
  - DM: /_send @<contact> text=<base64-encoded text>                                                                                                                                                                                                    
  - Group: /_send #<group> text=<base64-encoded text>                              

  #### Send content shape (live v6.5.4.1)

  The actual v6.5.4.1 bot-API `/_send` form (live-confirmed, superseding the
  `text=<base64>` sketch above) takes the message content as a JSON **array** of
  composed messages:

      /_send @<contact> json [{"msgContent": {"type": "text", "text": "<text>"}}]

  simplex-chat **rejects the bare single-object form** (`json {"msgContent": …}`)
  with `chatCmdError commandError "Failed reading: empty"`; the array is
  required. The array exists because a single send may compose multiple
  messages — v1 sends exactly one, so the array carries one element, and
  outbound chunking (§6.3.4) still emits one `/_send` per chunk. By contrast the
  `/_update item` edit (below) keeps a single `{"msgContent": …}` **object**: an
  edit targets exactly one existing item, so there is no composed-message list.
  (The `/_update` single-object form is inferred from this asymmetry, not
  live-re-verified — live-editing would mutate a real message.)

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
  - **simplex-chat subprocess respawn backoff jitter:** the supervisor's crash-respawn delay is **equal-jitter** exponential — sampled uniformly from `[exp/2, exp]`, where `exp` doubles per consecutive crash up to the cap — so the delay floor still grows per attempt and a fast-crashing child never enters a tight respawn loop. This deliberately differs from the §6.3.6 outbound-retry discipline (full jitter, `[0, exp)`): send retries contend with other clients against a shared remote dependency, where full jitter's wider spread de-synchronizes the herd; a local child respawn has no herd to avoid but must keep a growing minimum delay.
  - Reconnect attempts are logged; first failure logged at WARN, subsequent at INFO.
  - After 5 consecutive network failures, the Provider's admin notifier is invoked (throttled).
  - Inbound queue continues accepting outbound enqueues during disconnect; messages are sent on reconnect.
  - A successful reconnect resets the network-failure counter.
  - **Authentication / terminal `AUTH_FAILED` are deferred for the v1 loopback-IPC transport.** The WebSocket dials a co-located simplex-chat subprocess over loopback (the adapter spawns it; bot identity is the data-dir, §6.4.1), so there is no session token to revoke and no auth-vs-network close-code distinction: every close is treated as a transport failure, handled by the network-failure backoff above and the subprocess supervisor's crash-respawn. A SimpleX auth-failure state and its metric are therefore not implemented. (Contrast the Signal adapter, §6.5.8, whose account-unregistered auth failure is real and does reach a terminal `AUTH_FAILED`.)

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
  │ Identity assertion fails                 │ Drop the inbound message; log WARN │ None (silent skip)                                        │
  ├──────────────────────────────────────────┼────────────────────────────────────┼───────────────────────────────────────────────────────────┤                                                                                                         
  │ Update rejected (CEInvalidChatItemUpdate │ Fall back to new send() with orig. │ User sees a new message rather than an in-place update    │
  │   — item too old, deleted, not owner)    │ correlationId; metric incremented  │                                                           │
  └──────────────────────────────────────────┴────────────────────────────────────┴───────────────────────────────────────────────────────────┘                                                                                                         
                                                                                   
  ### 6.4.8 Subprocess log discipline

  The simplex-chat OS process is launched by the adapter and may emit
  envelopes (contact ids, message-body excerpts) on its own stdout/stderr
  depending on the simplex-chat log level the operator picks. Piping those
  bytes verbatim into the Provider's SLF4J chain would violate
  `docs/spec/security.md` §User-content logging (D37: the bodies of
  inbound chat-mode messages MUST NOT appear in non-audit logs) and the
  contact-id redaction rule.

  **Policy.** The drainer threads (`SimpleXSubprocess.drainStream`) still
  read both pipes — otherwise a full pipe buffer would block the
  subprocess — but the bytes are **discarded**. Exactly one fixed-shape
  marker (`simplex-chat subprocess stdout output suppressed` /
  `... stderr output suppressed`) fires per drain lifetime to record
  that output existed; the marker carries no bytes from the stream. The
  operator can still consult the subprocess's own log file (configured
  via `simplex-chat`'s flags, outside the adapter's control) for
  diagnostics.

  Rationale: structural over filtering. A redaction step that scans each
  drained line for sensitive content would inevitably drift behind
  simplex-chat's log format; the structural choice — never log the bytes
  in the first place — is invariant to whatever simplex-chat decides to
  print. The API-key redactor (`infochat-core`'s `Redactor`, registered
  as the JBoss LogManager console filter) is still in front of every
  SLF4J line for defence-in-depth.

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
  supportsMembershipEvents   = false  // signal-cli 0.14.5 exposes no per-user member-joined/left
                                      //   signal in its receive stream (live finding F-live-10);
                                      //   spec/messaging.md §Required SPI surface mandates false
                                      //   absent a native signal. Provider falls back to
                                      //   permanent-delivery-failure cleanup (§6.3.6) — the
                                      //   SimpleX posture (§6.4.2).
  supportsCodeFormatting     = true   // Signal renders monospace via its formatting metadata
  supportsMarkdownLinks      = false  // hard-asserted at startup (§6.2.1); v1 keeps URLs bare
                                      //   on Signal even though the protocol could carry link
                                      //   formatting — widening the rendering surface is a spec
                                      //   amendment, not a per-adapter footgun.
  maxInboundMessageBytes     = 16 KiB, fixed in v1 (see §6.2.2)
  maxSendsPerSecond          = 5      // conservative; Signal's per-account ceiling is higher but
                                      //   the v1 LLM concurrency cap (D46 §Topology) is the
                                      //   binding constraint anyway.
  supportsMessageEdit        = true   // Signal supports message edits within ~24h of original send;
                                      //   well beyond our request-scoped progress flow window.
  supportsTypingIndicator    = true   // Signal's typing indicator is first-class
  minEditInterval            = 600ms  // matches SimpleX; coalescing floor for ProgressNotifier

  ### 6.5.3 Identity assertion

  The Signal **ACI** (Account Identifier — a UUID Signal binds to its identity keys) is the cryptographic anchor (D10). `signal-cli` surfaces the ACI on every inbound envelope as `mentionUuid` for mention payloads and as the sender envelope identifier for the message itself. The adapter constructs the inbound message's `Identity` at decode time as:

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

  - The adapter runs `signal-cli` against the operator-configured data directory `infochat.adapters.signal.data-dir` (passed as signal-cli's `--config` directory; typically `~/.local/share/signal-cli` or a path mounted into the container) with the account selected by `infochat.adapters.signal.account`. There is no separate identity-dir property. The directory contains the registered account's identity keys, profile keys, and signal-cli's local message store; the boot-time `SignalConfig` bean validates the binary / data-dir / account inputs eagerly.
  - The bot's per-adapter contact id (the ACI used by the mention-recognition rule, §6.2.3 / §6.10) is derived at adapter startup from signal-cli's accounts index at `<data-dir>/data/accounts.json`: the entry whose `number` equals the configured account carries the account's ACI in its `uuid` field. It is NOT an operator-typed property. The derived value is canonicalized to lowercase and validated as a canonical UUID; any failure — missing/unreadable/malformed index, no entry for the configured account, absent or malformed ACI — refuses *that adapter's* startup but does not abort the Provider (per-adapter resilience, §6.7). The index is read in preference to the per-account data file because the index hop is required to locate that file anyway (its `path` need not equal the account number) and the index's flat entry shape has been stable across signal-cli versions while the per-account file's nested layout has churned. The format is signal-cli-internal and pinned to the one current layout: there is no integration test against a real signal-cli, so a signal-cli upgrade that changes the layout surfaces as a loud startup failure at the next restart, never as silent mis-derivation.
  - Account registration (`signal-cli register` / verification SMS) is an out-of-band operator step; the adapter never registers an account itself. A "directory present but no registered account" failure is logged at ERROR and surfaced as `state=AUTH_FAILED` (terminal until restart).

  An alternative wire-protocol path (`libsignal-service-java` or `signald`) would substitute its own identity-material shape; the spec-level commitment is "the adapter owns its identity material and validates it at startup," which is path-independent.

  ### 6.5.5 Lifecycle

  SignalAdapter.start(handler):
    1. Derive the bot ACI from the account store under `infochat.adapters.signal.data-dir` (§6.5.4; the `.data-dir` / `.account` inputs themselves are validated by the boot-time `SignalConfig` bean); canonicalize, validate, cache as the bot's per-adapter contact id. Runs BEFORE the spawn so an unreadable or malformed store surfaces as a config-shaped startup failure, not as subprocess noise
    2. Spawn / connect to the `signal-cli` JSON-RPC endpoint
    3. Subscribe to the account's inbound message stream
    4. Spawn event reader → SignalEventDecoder → InboundHandler
    5. Spawn outbound queue worker → SignalCommandEncoder → JSON-RPC send

  SignalAdapter.stop():
    1. Drain outbound queue (best-effort, max 5s)
    2. Unsubscribe / close the JSON-RPC connection
    3. Send SIGTERM to the `signal-cli` subprocess (if owned), wait up to 3s, escalate to SIGKILL
    4. Idempotent: second stop is a no-op

  ### 6.5.6 Event decoding

  SignalEventDecoder maps `signal-cli` JSON-RPC envelopes to InboundMessage:

  - `envelope.dataMessage` with neither a `groupInfo` nor a `groupV2` field → `ScopeRef.Dm(senderACI)`. Body is `dataMessage.message`; mentions are decoded for completeness but DM mentions don't gate delivery.
  - `envelope.dataMessage` with a group stanza → `ScopeRef.Group(<base64 group id>)`. The stanza signal-cli 0.14.5 emits on the real wire is `groupInfo{groupId, groupName, revision, type}` (live finding F-live-10 — the `groupV2{id}` spelling assumed pre-live came from fakes and was never observed); the group parser accepts BOTH spellings, `groupInfo.groupId` first, keeping route symmetry with the DM-side exclusion guard so no shape falls between the two complementary filters. The decoder reads `dataMessage.mentions` (a list of `{mentionUuid, start, length}` records) and checks any `mentionUuid` for byte-equality against the bot's cached ACI. A match strips the mention spans from the rendered text and delivers; no match drops the message silently. Display-name matching is forever-out-of-v1 (§6.10).
  - `envelope.typingMessage` → ignored on the inbound side (we only *send* typing indicators).
  - Group membership deltas: NONE are exposed in signal-cli 0.14.5's receive stream — `groupInfo` carries no `memberJoined`/`memberLeft` arrays (F-live-10). `supportsMembershipEvents = false` (§6.5.2); Provider relies on permanent-delivery-failure-driven cleanup, as on SimpleX.
  - `envelope.editMessage` (a remote edit of an inbound message) → ignored in v1; the bot does not re-process edited inbound messages.
  - Other envelope kinds (sync messages, receipts, story messages, call messages): logged at DEBUG, dropped.

  The decoder is pure (no I/O) and unit-tested with recorded JSON fixtures, mirroring the SimplexEventDecoder structure.

  ### 6.5.7 Command encoding

  SignalCommandEncoder serializes OutboundMessage to `signal-cli` JSON-RPC `send` / `sendEdit` calls:

  - DM: `{"method":"send","params":{"recipient":[<contactId>],"message":<text>}}`
  - Group: `{"method":"send","params":{"groupId":"<adapterGroupId>","message":<text>}}`
  - Edit (`update`/`finalize` on a `supportsMessageEdit=true` send): `{"method":"sendEdit","params":{"recipient"|"groupId":..., "targetTimestamp":<original send timestamp>,"message":<text>}}`

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
              100_000,         // maxInboundMessageBytes — generous for tests
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
  infochat.adapters.simplex.binary=/usr/local/bin/simplex-chat
  infochat.adapters.simplex.data-dir=/var/lib/infochat/simplex
  infochat.adapters.simplex.ws-port=5225

  # Production: SimpleX + Signal in the same Provider (the v1 multi-adapter shape)
  infochat.adapters=simplex,signal
  infochat.adapters.simplex.binary=/usr/local/bin/simplex-chat
  infochat.adapters.simplex.data-dir=/var/lib/infochat/simplex
  infochat.adapters.simplex.ws-port=5225
  infochat.adapters.signal.binary=/usr/local/bin/signal-cli
  infochat.adapters.signal.data-dir=/var/lib/infochat/signal-cli
  infochat.adapters.signal.account=+15551234567

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

  **Subprocess-launch classification (Signal) — deliberate fail-fast.** The Signal adapter spawns its `signal-cli` daemon synchronously inside `start()`; a launch `IOException` (missing or unexecutable binary, unreadable data dir) is classified `MessagingException(PERMANENT)` and fails *that adapter's* start, rather than being routed through the supervised exponential-backoff restart loop that handles a *post-launch* daemon crash (§6.5.8 / §6.5.9). This is intentional: an initial launch failure is a deterministic operator-config error that re-running the identical command can never resolve, so spending the restart budget on it only delays the inevitable terminal state. Failing fast surfaces the misconfiguration at boot through the per-adapter catch above — the ERROR log names the adapter and the operator fixes the path — while the other adapters and Provider startup proceed unaffected. The `PERMANENT` vs `TRANSIENT` category carries no behavioral difference at startup (the per-adapter catch is category-agnostic); it documents intent and is pinned by `SignalAdapterStartFailureTest`. Once the daemon *has* launched, the supervised crash-restart path is unchanged (§6.5.8). SimpleX differs only mechanically — its launch runs inside the supervisor virtual thread, so an initial launch failure shares the crash-restart loop — but reaches the same observable outcome (that adapter's `start()` fails after the readiness-probe timeout; the others proceed).

  **Readiness rule.** The Provider's readiness probe reports **ready when at least one** activated adapter is connected (Provider can serve traffic on that adapter); **not-ready when zero adapters are connected**. Per-adapter connection state is exposed separately via metrics (`adapter.connection.status{adapter}`) so an operator can distinguish "fully healthy" from "degraded — one adapter down" without parsing readiness alone.

  **Per-adapter bot identity material.** Each adapter owns its own bot identity material (SimpleX: the running simplex-chat's account state; Signal: the `signal-cli` account directory; §6.4.1, §6.5.4) and validates it at adapter startup. Provider does not synthesize bot identity. The bot's per-adapter contact id used for mention recognition is derived from that material at adapter startup — SimpleX: **queried from the running simplex-chat** over the adapter's own WebSocket; Signal: **read from the `signal-cli` identity store** — never an operator-typed property.
                                                                                   
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

  Mention recognition is anchored to the bot's cryptographic contact id (§6.2.3). There is **no operator-typed mention name** — the prior `infochat.adapter.bot-mention-name=@infochat-bot` property has been removed. The bot's contact id for mention recognition is: **SimpleX** — the bot's **per-group `memberId`**, read per-frame from `chatInfo.groupInfo.membership.memberId` (decision D51; the queue address the pre-v6.5.4.1 mention payload carried is gone); **Signal** — the ACI / `mentionUuid`, read once at adapter startup from the `signal-cli` identity store.

  The adapter must:

  1. Read each inbound group message's mention payload. For SimpleX this is the top-level `mentions{}` object (display name → `memberId`); for Signal it is `dataMessage.mentions` (`(mentionUuid, start, length)` records).
  2. Compare each mention's contact id byte-equal against the bot's own contact id — SimpleX: a `mentions{}` `memberId` vs `groupInfo.membership.memberId`; Signal: a `mentionUuid` vs the cached ACI. A match means the message is delivered.
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
                    
  Status: scheduled, not yet built — no `AdapterMetrics` exists in any `infochat-*` main source as of 2026-06-12. The planned implementation ticket (blocked on its Micrometer dependency) implements the catalogue below, including the §6.3.8 `adapter.outbound.update.total{outcome=fallback_send}` counter previously explicitly deferred; the /status reporting line at the end of this section is a named follow-up to be filed when that ticket lands.

  AdapterMetrics (Micrometer):
                                                                                                                                                                                                                                                        
  - adapter.inbound.total{adapter, scope_kind} — counter                                                                                                                                                                                                
  - adapter.outbound.total{adapter, scope_kind, outcome} — counter, outcome ∈ {ok, retry, fail}                                                                                                                                                         
  - adapter.inbound.queue.size{adapter} — gauge                                                                                                                                                                                                         
  - adapter.outbound.queue.size{adapter} — gauge                                   
  - adapter.connection.status{adapter} — gauge (1 connected, 0 disconnected)                                                                                                                                                                            
  - adapter.identity.assert.fail{adapter} — counter (per-message identity assertion failure; e.g., a malformed inbound payload whose sender ID can't be verified)
  - adapter.message.bytes{adapter, direction} — histogram
  - adapter.outbound.update.total{adapter, scope_kind, outcome} — counter, outcome ∈ {ok, coalesced, fail, fallback_send}
  - adapter.outbound.update.fail{adapter, reason} — counter, reason ∈ {item_too_old, item_deleted, not_owner, transport, unknown}
  - adapter.outbound.update.lag{adapter} — histogram (time between caller `update()` and edit actually transmitted, after coalescing)
  - adapter.typing.toggle{adapter, scope_kind, value} — counter (value ∈ {on, off}); zero for adapters without `supportsTypingIndicator`                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  /status (admin) reports adapter name, trust level, connection status, and the inbound/outbound queue sizes.                                                                                                                                           
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 6.13 What's intentionally NOT in v1

  - Telegram, Matrix, IRC, XMPP adapters — the SPI is designed to accept them but the v1 production set is closed at SimpleX + Signal (D32, D46). Adding a new adapter requires it to declare its trust level and identity-assertion shape per §6.2.3 and [04-security.md §4.8](04-security.md) before it can be enabled in production.
  - Voice messages and inbound attachments — out of scope for v1. Outbound attachments are in v1 as the D74 `/image` surface (§6.2.4).
  - Threaded replies in groups — out of scope for v1.
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
  - Reconnection: simulated transport disconnect → adapter reconnects, queued outbounds eventually deliver (per-adapter; the simulation differs by adapter — WS for SimpleX, JSON-RPC pipe for Signal).
  - Per-adapter resilience: with two adapters configured, a forced startup failure on one of them must NOT prevent the other from coming up; readiness probe reports ready while the failing adapter retries (asserts §6.7).
  - Readiness rule: with the only enabled adapter disconnected, readiness reports not-ready; reconnect flips it to ready (asserts §6.7).
  - Trust gate: starting with an adapter that reports `trustLevel = LOW` and the matching `infochat.adapters.<name>.allow-low-trust=false` fails fast with a clear error naming the adapter (asserts §6.8).
  - `supportsMarkdownLinks` startup gate: registering an adapter that declares `supportsMarkdownLinks = true` fails Provider startup with a fatal log message (asserts §6.2.1).
  - Production-exclusion gate: a configuration that lists `inmemory` together with `simplex` or `signal` fails Provider startup (asserts §6.6).
  - Inbound size cap: an inbound message over `maxInboundMessageBytes` is dropped at the adapter and the drop counter `adapter.inbound.dropped{reason='oversize'}` increments; the drop is silent at the adapter boundary (no reply), with user-facing oversize feedback handled separately by the application-layer chat-mode body cap (asserts §6.3.10).
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

  ## SPI surface decisions (D47/D31 reconciliation)

  Three adapter SPI surfaces were structurally inconsistent with the as-built adapters; each entry below records the reconciliation verdict and rationale (per the inlined §Contract block in the relevant ticket).

  ### (a) `MessagingAdapter.onMembershipEvent` dispatch shape — verdict: **remove**

  The SPI carried two membership-event dispatch shapes: `InMemoryAdapter` routed events through the interface's no-op `onMembershipEvent` default, while `SignalGroupHandler` invoked the registered `MembershipHandler` directly — so the default was dead surface on Signal and had zero external callers. D47's group-authorization invariants (departing-admin soft-clear + `is_group_admin` clear in one transaction; `removed_at IS NOT NULL` excluded from first-mention auto-promote) hold only if every adapter delivers membership events to Provider through the same registered-handler path, so exactly one shape survives: adapters invoke the handler registered via `setMembershipEventHandler` directly, mirroring the inbound path (`setInboundHandler` + direct `onMessage` — there is no interface-level inbound dispatch method either). The `onMembershipEvent` default is removed; the per-event isolation in `SignalGroupHandler`'s membership dispatch loop is unchanged.

  ### (b) `SignalGroupHandler` unwired producer / group-envelope decode — verdict: **wire**

  Signal's group path is spec-live, not vestigial: group bot-mentions are the D10 group-mode surface. (The original wire assumption here — `memberJoined`/`memberLeft` ACI arrays in `groupV2` update envelopes, `supportsMembershipEvents = true` — was falsified live by F-live-10: signal-cli 0.14.5 emits the group stanza as `groupInfo{groupId}` and no membership arrays at all, so the flag is now false per spec/messaging.md §Required SPI surface — Membership events, and the membership dispatch loop is removed; see §6.5.2/§6.5.6.) `SignalJsonRpcClient` routes every `receive` notification that is not DM-scope to a group-notification route, which `SignalAdapter` wires to its `groupHandler()` factory when attaching the connected client. The envelope decode is split, not duplicated: `SignalMessageCodec.extractDm` keeps only DM-scope envelopes and `SignalGroupHandler.handleReceive` keeps only group-scope ones — complementary filters over the same notification stream.

  ### (c) `ProgressNotifier` — verdict: **wired**

  spec/messaging.md §Progress notifications (D31; §6.3.8 above) mandates the surface: long-running handlers (`/summary`, periodic digest, chat agent) publish stage events to a cross-cutting `ProgressNotifier`. The concrete `StageProgressNotifier` (§6.3.8) is wired and `/summary` publishes through it — the prior keep-as-seam verdict (an unshipped v1 surface awaiting follow-up wiring) is superseded. The interface gained payload-carrying terminal calls (`complete`/`fail`) and stays an interface; `ProgressStage` keeps its seven values, so the SPI load tests are unchanged. Digest and chat-agent wiring through the same notifier remain named follow-ups (out of scope for that wiring ticket).

  ---
