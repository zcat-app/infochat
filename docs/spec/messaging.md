# Messaging adapters

This file specifies the contract between Provider and the messaging                                                                                                                                                                                   
backend. Concrete adapter implementations (SimpleX wire protocol, the                                                                                                                                                                                 
in-memory test adapter), property keys, and capability defaults live in                                                                                                                                                                               
`docs/design/06-messaging.md`.

## Goals

1. **Adapter pluggability.** A new transport (Telegram, Matrix, etc.)
   is a class drop-in, not a rewrite. v1 ships SimpleX and Signal
   (decision D32).
2. **Identity is the trust anchor.** The adapter's cryptographic contact                                                                                                                                                                              
   id is the *only* identity the system trusts (decision D10). Display                                                                                                                                                                                
   names are informational.
3. **Capability negotiation, not feature flags.** Provider asks the              
   adapter what it supports; adapter says yes/no. No transport-specific                                                                                                                                                                               
   conditionals leak into business logic.
4. **Adapter is a thin transport.** All policy (commands, permissions,                                                                                                                                                                                
   ban handling, rate limits, formatting, translation) lives in                  
   Provider. Adapters move bytes and assert identity.
5. **Consistent UX across adapters.** Plain-text formatting (decision                                                                                                                                                                                 
   D30) means the bot reads the same way whether the user is on SimpleX,
   Signal, or a future text-only transport.

## Required SPI surface

Every adapter implements:

- **Identity assertion.** Receives a wire message, returns a stable,                                                                                                                                                                                  
  cryptographically-anchored contact id plus optional display name. An                                                                                                                                                                                
  adapter that cannot do this MUST be marked low-trust and the operator                                                                                                                                                                               
  must opt in explicitly.
- **Receive.** Pushes inbound `(scope, contact_id, body)` to Provider.                                                                                                                                                                                
  Group messages arrive only when the bot is `@mentioned`; the mention                                                                                                                                                                                
  is stripped before delivery (the adapter may do the strip, or                  
  Provider may do it consistently across adapters — see design notes).
- **Send.** Provider sends a (scope, body); adapter returns an opaque
  message handle.
- **Update.** Given a handle, replace the message body. Optional —                                                                                                                                                                                    
  adapters that don't support edits return a "not supported" signal so                                                                                                                                                                                
  the progress notifier can fall back gracefully.
- **Finalize.** Given a handle, mark the message complete (the                                                                                                                                                                                        
  notifier's terminal state). For adapters with edit support this is                                                                                                                                                                                  
  one last `update`; for others it's the only `send` that ever happened.
- **Typing indicator.** `setTyping(scope, bool)`. Optional.
- **Capability flags.** A static description of what the adapter                                                                                                                                                                                      
  supports: identity trust level, markdown rendering, message edits,                                                                                                                                                                                  
  edit minimum interval, typing indicator, and any future flag a new                                                                                                                                                                                  
  transport needs.

## Capability flags (minimum set)

- `trustLevel` — `HIGH` for cryptographically anchored ids, `LOW`                                                                                                                                                                                     
  otherwise. Provider rejects identity assertions from `LOW` adapters                                                                                                                                                                                 
  unless the operator explicitly opts in.
- `supportsMarkdownCode` — when true, code spans render as monospace.                                                                                                                                                                                 
  When false, the user sees backticks (still readable, decision D30).
- `supportsMessageEdit` — required for in-place progress updates.
- `minEditInterval` — adapter-imposed floor between edits on the same                                                                                                                                                                                 
  message; the progress notifier honors `max(adapterMin, system floor)`.
- `supportsTypingIndicator` — drives the typing-on/off pulses around
  long-running requests.

Future flags (richer attachments, voice, reactions, etc.) extend this                                                                                                                                                                                 
list; v1 ships only the above. Provider must treat an unknown flag as                                                                                                                                                                                 
"not supported" by default.

## Message handles

A message handle is an opaque token returned by `send()`. It lets the                                                                                                                                                                                 
caller subsequently `update` or `finalize` the same visible message.

- Contents are adapter-defined.
- Callers MUST NOT inspect or persist a handle. It is valid only within                                                                                                                                                                               
  the originating adapter, in-process.
- Handles are how the progress notifier turns a stream of stage events                                                                                                                                                                                
  into a single visibly-evolving message.

## Progress notifications

Long-running handlers (`/summary`, periodic digest, chat agent) publish                                                                                                                                                                               
stage events to a cross-cutting `ProgressNotifier` (decision D31). The                                                                                                                                                                                
notifier:

1. Acquires a placeholder message via `send()` and captures the handle.
2. Turns on typing if the adapter supports it.
3. Receives stage events (`STARTED`, `RETRIEVING`, `GENERATING`,                                                                                                                                                                                      
   `TRANSLATING`, `FINALIZING`) and renders each as a *localized* string                                                                                                                                                                              
   via `update(handle, text)`. Edits are coalesced; only the latest
   pending text is transmitted at the next eligible tick.
4. On terminal `COMPLETED` / `FAILED`, calls `finalize(handle, text)` and        
   turns off typing. Both are guaranteed via try/finally — placeholders                                                                                                                                                                               
   are never left dangling.

Constraints:

- Stage strings are looked up by enum from the deterministic
  localization bundle (decision D43; `llm.md` §Translation flow).
  **User input is never interpolated into progress strings** — security
  requirement, prevents reflective injection in screenshots and logs.
- Adapters without `supportsMessageEdit` collapse to a single final                                                                                                                                                                                   
  `send` of the completed text. Business logic does not change. The                                                                                                                                                                                   
  caller does not know which transport it has.
- Short, deterministic SQL commands bypass the notifier entirely.

The exact event names, edit interval floor, and localization-bundle
structure live in design notes.

## Identity and groups

- Inbound message → adapter resolves to `(contact_id, scope)`. DM scope                                                                                                                                                                               
  is just the user; group scope is `(group_id, user_id)`.
- The adapter is responsible for surfacing a stable per-group id                 
  (cryptographic where possible). On first sight the Provider creates a
  `groups` row.
- Groups behave per `commands.md` and decision D16: bot replies only on
  `@mention`; group destructive ops require group admin; periodic
  summaries fire on per-group local time.

## Output formatting (transport view)

Provider produces plain text per decision D30. The adapter:

- May choose to render single-backtick spans as inline code if                                                                                                                                                                                        
  `supportsMarkdownCode = true`.
- MUST leave URLs bare (no markdown link wrapping) regardless of                                                                                                                                                                                      
  capability — the URL is the citation; obscuring it would defeat the                                                                                                                                                                                 
  point.
- MUST NOT mutate command output (e.g. `/help`). Only LLM-authored                                                                                                                                                                                    
  output is subject to the chat output sanitizer (`security.md`); the            
  adapter doesn't know which is which.

## Failure handling

- Send/update/finalize failures are reported to Provider as exceptions                                                                                                                                                                                
  with a category (transient vs. permanent). Transient failures retry                                                                                                                                                                                 
  with backoff; permanent failures abort the affected reply and log.
- An adapter cannot silently drop messages. Either delivery succeeds or                                                                                                                                                                               
  the caller learns it didn't.
- Adapter-internal back-pressure (e.g. rate limits enforced by the                                                                                                                                                                                    
  transport) surfaces as transient failures so Provider's per-user rate          
  limiter is the single source of truth for "slow this user down".

## What lives in design notes

- The SimpleX adapter wire protocol (chat CLI / WebSocket framing)
- The "live message" rendering mode the SimpleX adapter uses internally
- The Signal adapter wire protocol (signal-cli / JSON-RPC framing) and capability defaults
- The in-memory test adapter's API and assertion helpers
- Concrete property keys for adapter selection
- Per-adapter default capability values
- Edit-coalesce minimum interval value
- Group `@mention` stripping responsibility (adapter vs. Provider)
- Localization-bundle key naming 