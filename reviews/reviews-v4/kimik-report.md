1. Executive Summary                                                                                                                                                                                                                                  
                      
  The infochat specification is a well-structured, admirably precise document for a system of this complexity. It successfully separates spec from design, commits to strong invariants like per-(user, scope) isolation, and maintains a clear
  determinism boundary. However, the spec is not yet implementable without daily clarifications. The top issues are: (1) the post status enum in schema.md omits NEEDS_REVIEW, a terminal state referenced throughout security.md; (2) the scope model  
  for saved_post contradicts invariant 1 (per-scope isolation) because /save is described as per-user yet must be filtered by scope in /export; (3) the Nostr StreamSource assumes events dropped on shutdown "will reappear on the next relay
  connection," which the Nostr protocol does not guarantee. These create implementation ambiguity for deduplication, authorization, and data loss.                                                                                                      
                  
  ---
  2. Findings
             
  ▎ [F01] NEEDS_REVIEW post status missing from schema enum
                                                                                                                                                                                                                                                        
  ▎ Severity: blocker                                                                                                                                                                                                                                   
  ▎ Category: inconsistency                                                                                                                                                                                                                             
  ▎ Location: docs/spec/schema.md §Posts and derivatives; docs/spec/security.md §Failure handling                                                                                                                                                       
  ▎ Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says:                                                                                                                                                                                                                                   
  schema.md defines the post status enum as exactly three values: RAW, READY, QUARANTINED.
  security.md §Re-evaluation job says: "after the profile-driven maximum [retries] the post is permanently marked NEEDS_REVIEW and admin is notified."

  Why it's a problem:
  An engineer implementing the schema will create a check constraint or enum type with three values. The re-evaluation job will then try to set NEEDS_REVIEW, causing a constraint violation. Two implementers will diverge: one widens the enum
  (correct per security.md), the other treats NEEDS_REVIEW as QUARANTINED (incorrect). The spec's central data model and its security failure-handling path contradict each other.                                                                      
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add NEEDS_REVIEW to the status enum in schema.md. Also add it to the state machine description (active → failed, active ↔ disabled transitions) with its transitions: QUARANTINED → NEEDS_REVIEW (manual admin action or re-eval exhaustion).
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ▎ [F02] /save is per-user but invariant 1 demands per-scope isolation                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ▎ Severity: blocker
  ▎ Category: inconsistency                                                                                                                                                                                                                             
  ▎ Location: docs/spec/schema.md §Per-scope state / Invariant 1; docs/spec/commands.md §Content; docs/spec/decisions.md D13                                                                                                                            
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  schema.md Invariant 1: "Every row that holds user state carries a scope discriminator ('dm' or 'group') and a scope id... Every query against user state filters on both."
  commands.md: "/save — bookmark a post into the calling user's library (per-user, even in groups)."                                                                                                                                                    
  D13: "/save semantics: Per-user only (private even in groups)."                                                                                                                                                                                       
  /export in commands.md includes saved_post "whose scope key matches the calling (user, group)".                                                                                                                                                       
                                                                                                                                                                                                                                                        
  Why it's a problem:
  If saved_post is truly per-user (one library across all scopes), it does not need a scope discriminator — it only needs user_id. But invariant 1 requires one, and /export expects to filter by (user, scope). If an implementer keys saved_post by   
  user_id only (DM and group share a library), /export in a group will return DM saves too, leaking scope context. If an implementer keys by (user, scope), then a user's DM saves and group saves are separate libraries — but D13 says "per-user      
  only," implying one global library. Two competent engineers will build different things.
                                                                                                                                                                                                                                                        
  Suggested resolution:
  Clarify in schema.md whether saved_post carries a scope discriminator. If yes, state explicitly that there is one library per (user, scope) and update D13 to "per-(user, scope)". If no, add saved_post as an explicit exception to Invariant 1 and
  update /export to filter only by user_id for that table.                                                                                                                                                                                              
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F03] UID content-hash fallback algorithm is unspecified
                                                                                                                                                                                                                                                        
  ▎ Severity: blocker
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/schema.md §Posts and derivatives; docs/spec/decisions.md D38                                                                                                                                                                    
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "Each post has a stable UID derived deterministically from (source_id, upstream_identifier) ... with a content-hash fallback when the source provides no usable upstream identifier."
  D38: "Cross-relay dedup (same event-id from N relays → one posts row)."                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  For RSS, <guid> may be missing; for Nostr, the event id is always present. But for future Fetcher-shaped sources (e.g., raw HTML pages), the fallback is critical. If one engineer uses SHA-256 and another uses SHA3-256, two Collectors ingesting   
  the same source produce different UIDs for the same post. Cross-instance dedup breaks, and /save bookmarks become non-portable. The spec claims the UID is "stable globally" and "the dedup key for refetches," but without a normative hash function 
  and encoding, this is a fiction.
                                                                                                                                                                                                                                                        
  Suggested resolution:
  Add the normative algorithm to schema.md: e.g., "content-hash fallback uses SHA-256 of the canonical body bytes, lower-case hex-encoded, prefixed with source_id|." If the spec intentionally leaves this to design, say so explicitly and downgrade
  the "stable globally" claim to "stable within a deployment."                                                                                                                                                                                          
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F04] /retry permission model has an unbounded group context ambiguity
                                                                                                                                                                                                                                                        
  ▎ Severity: blocker
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/commands.md §Conversation control; docs/spec/decisions.md D36                                                                                                                                                                   
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  commands.md: "/retry — regenerates the prose for the last summary-producing command in the calling (user, scope)."
  "For periodic group digests, /retry is group-admin or bot-admin only and replaces the cached digest."                                                                                                                                                 
  D36 mirrors this.                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  In a group, user Alice (regular member) issues /summary security -w 24h. The bot generates clusters and prose. Alice then types /retry. Is this allowed? The general /retry description says it re-rolls prose for the caller's last summary-producing
   command — Alice's /summary qualifies. But if the periodic digest was the last summary-producing event in the group, and Alice tries /retry, does the system try to retry the periodic digest (admin-only) or tell her she has no eligible anchor? The
   spec doesn't say how the system distinguishes "Alice's on-the-fly summary" from "the group's periodic digest." If the anchor stores (user, scope, command_type), then Alice can retry her own. But the spec does not describe the anchor schema.
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to commands.md or D36: "The retry anchor stores the initiating user id and the command kind. In a group, a regular member may /retry only their own on-the-fly /summary; /retry of a periodic group digest requires group admin or bot admin
  regardless of who invokes it."                                                                                                                                                                                                                        
                  
  ---                                                                                                                                                                                                                                                   
  ▎ [F05] Nostr StreamSource drop-on-shutdown "will reappear" is protocol-false
                                                                                                                                                                                                                                                        
  ▎ Severity: blocker
  ▎ Category: failure-mode                                                                                                                                                                                                                              
  ▎ Location: docs/spec/architecture.md §Ingest SPIs / StreamSource                                                                                                                                                                                     
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "On graceful Collector shutdown the implementation drains in-flight events to the outbox within a profile-driven timeout; events not drained within that window are dropped and will reappear on the next relay connection."
                                                                                                                                                                                                                                                        
  Why it's a problem:
  Nostr subscriptions are stateless from the relay's perspective. On reconnect, the Collector re-subscribes with its filter; the relay sends only future events matching that filter. Unless the relay is one of very few that support replay from a    
  specific since timestamp with full historical backfill, dropped events are lost forever. The spec treats reappearance as guaranteed, which means an implementer might not build a more aggressive shutdown drain or a persistent "unacknowledged      
  events" buffer. This is data loss for ingest.
                                                                                                                                                                                                                                                        
  Suggested resolution:
  Replace the sentence with a realistic failure mode: "Events not drained within the timeout are dropped. For Nostr, reconnection does not guarantee redelivery of past events; the implementation SHOULD minimize this window by aggressively flushing
  in-flight events before accepting the shutdown signal." If the spec wants to guarantee no loss, require a persistent unacknowledged-events buffer (e.g., a stream_event_buffer table) that is replayed on startup before new subscriptions begin.     
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F06] Chat mode in groups requires @mention per message but spec is silent
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: gap                                                                                                                                                                                                                                       
  ▎ Location: docs/spec/commands.md §Surface conventions; docs/spec/messaging.md §Identity and groups
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "In a group the bot only sees messages that @mention it. The mention is stripped before parsing."                                                                                                                                                     
  "Anything not starting with / is routed to the chat agent."                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  These two rules combine to mean that chat mode in a group is impossible as a continuous conversation: the user must @mention the bot on every single chat turn. This is a terrible UX. The spec may intend that chat mode is DM-only, but it never    
  says so. An implementer building group chat mode will need to ask: do we maintain a "session" after the first @mention? If so, for how long? If not, why route non-slash text to the chat agent in groups at all?                                     
                  
  Suggested resolution:                                                                                                                                                                                                                                 
  Explicitly state in commands.md: "Chat mode is available in DMs and in groups. In groups, the user must @mention the bot on every chat turn; there is no persistent chat session across messages." OR, if group chat sessions are intended, add a
  /chat command or session timeout rule to commands.md.                                                                                                                                                                                                 
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F07] /export group contradiction: includes group-wide tables while claiming exclusion
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: inconsistency                                                                                                                                                                                                                             
  ▎ Location: docs/spec/commands.md §Conversation control / /export                                                                                                                                                                                     
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "/export … never group-wide content (other members' messages, the group's groups row beyond id and timezone, audit log entries about other users)."
  Table list includes source_subscription and scope_tag.                                                                                                                                                                                                
  schema.md: "Source subscription. A (scope, source) link. DM scope is per user; group scope is shared."                                                                                                                                                
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  source_subscription in a group is inherently group-wide: all members share the same subscriptions. By including it in /export, the spec contradicts the "never group-wide content" claim. A user exporting sees "the group's subscribed sources,"     
  which is a form of group-wide content. The same applies to scope_tag and scope_preferences (group timezone, followed tags). The line the spec is trying to draw is unclear.                                                                           
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Either remove the "never group-wide content" absolute statement and replace it with "never other users' personal data" (which allows group-scoped config tables), or explicitly list which group-scoped tables are included and justify why they are
  considered the caller's own data.                                                                                                                                                                                                                     
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F08] price_snapshot is partitioned but absent from schema entity list
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: gap                                                                                                                                                                                                                                       
  ▎ Location: docs/spec/schema.md §Entities; docs/spec/schema.md §Invariant 6
  ▎ Confidence: high                                                                                                                                                                                                                                    
                  
  What the spec says:                                                                                                                                                                                                                                   
  schema.md §Entities lists seven entity groups (Identity and access, Sources and tags, Posts and derivatives, Per-scope state, Operational). price_snapshot is mentioned only in Invariant 6 ("post, post_reference, post_embedding, price_snapshot,
  and similar bulk-derived rows are partitioned") and in schema.md §Sources and tags as "a collector-owned price_snapshot (or equivalent) table." It has no dedicated entity description despite being a top-level table with its own TTL, partitioning,
   and NOTIFY contract.
                                                                                                                                                                                                                                                        
  Why it's a problem:
  An engineer reading the schema file to model the system will miss price_snapshot entirely unless they read the invariant footnotes carefully. There is no description of its primary key ((asset, sub-verb) per commands.md), its columns, or its
  relationship to the Fetcher SPI.                                                                                                                                                                                                                      
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add a price_snapshot entry under a new "Asset data" entity heading or under "Operational." Describe its key, partitioning, retention, and the NOTIFY new_price_snapshot contract with the Provider.
                                                                                                                                                                                                                                                        
  ---
  ▎ [F09] No mechanism to enforce D41's "exactly one" topology guarantee                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: gap                                                                                                                                                                                                                                       
  ▎ Location: docs/spec/decisions.md D41; docs/spec/architecture.md §Deployment topology                                                                                                                                                                
  ▎ Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "v1 runs exactly one Collector and exactly one Provider against a shared Postgres." "Running more than one of either service is unsupported and will produce duplicate fetches, duplicate periodic digests, and contention on provider_state."
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  The spec states what an operator MUST NOT do but provides no enforcement. A container orchestrator or systemd restart could easily spin up a second instance. The "independent scaling" motivation in architecture.md suggests the services can scale 
  separately, but D41 says they cannot scale horizontally at all. An operator may reasonably assume "separate processes" means "as many as I need."                                                                                                     
                  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add a spec-level commitment in architecture.md: "On startup, each service acquires a named pg_advisory_lock (or writes a unique heartbeat row with an optimistic-lock expiry). A second instance that fails to acquire the lock logs a fatal error and
   refuses to start." If the spec intentionally leaves this to operators, state that explicitly.

  ---
  ▎ [F10] Re-evaluation job configuration missing from deployment surface

  ▎ Severity: major
  ▎ Category: gap
  ▎ Location: docs/spec/security.md §Re-evaluation job; docs/spec/deployment.md §Configuration surface
  ▎ Confidence: high

  What the spec says:
  security.md: "The Collector runs a background job on a profile-driven cadence (value in design notes)."
  deployment.md §Configuration surface lists 12 categories (Profile, LLM routing, Messaging adapter, etc.). Re-evaluation cadence and per-post retry cap are not among them.
                                                                                                                                                                                                                                                        
  Why it's a problem:
  An operator standing up the system cannot discover that this job exists or that its cadence is tunable. It is omitted from the enumeration of operator inputs. This is a spec/design layering violation: the spec promises a profile-driven behavior  
  but does not surface it in the operator interface.                                                                                                                                                                                                    
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to deployment.md §Configuration surface: "Re-evaluation job cadence and per-post attempt cap (profile-driven)."
                                                                                                                                                                                                                                                        
  ---
  ▎ [F11] Quarantine rows not exempted from TTL partition drop                                                                                                                                                                                          
                  
  ▎ Severity: major
  ▎ Category: gap
  ▎ Location: docs/spec/schema.md §Invariant 6; docs/spec/security.md §Quarantine workflow
  ▎ Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  Invariant 6: "Old post-derived rows expire by partition drop."                                                                                                                                                                                        
  security.md: Quarantine rows hold "span offsets, the verbatim original, and review status." Admins review via /quarantine list and /quarantine approve|reject.                                                                                        
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  If quarantine is partitioned on the same TTL schedule as post, a quarantine entry could be dropped before an admin reviews it. The original content would be lost, and the admin workflow would be silently broken. The spec never explicitly exempts 
  quarantine from TTL. schema.md §Entities describes quarantine under "Posts and derivatives," implying it shares the post lifecycle.                                                                                                                   
                  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to schema.md Invariant 6: "quarantine rows are exempt from automatic TTL and are retained until explicitly approved or rejected by an admin (or until a separate, longer admin-review TTL expires)." Or add a dedicated quarantine retention
  invariant.                                                                                                                                                                                                                                            
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F12] /save personal tag filtering behavior is undefined
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/commands.md §Content / /save, /saved
  ▎ Confidence: high                                                                                                                                                                                                                                    
                  
  What the spec says:                                                                                                                                                                                                                                   
  "/save <uid> [-t personal-tags] — bookmark a post into the calling user's library... Personal tags are free-form and never join the controlled vocabulary."
  "/saved [tag] [-w …] [--page N] — list saved posts with optional filters and pagination."                                                                                                                                                             
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  Does /saved security filter personal tags by exact match? Prefix? Substring? Case-insensitive? If a user saves with -t "Security News" and later does /saved security, does it match? Two implementers will build different behaviors. Since personal 
  tags are free-form, there is no controlled vocabulary to normalize against.                                                                                                                                                                           
                  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to commands.md: "Personal tag filtering on /saved is case-insensitive substring match on the tag text (or exact match — pick one and state it)."

  ---
  ▎ [F13] Progress notifier may inadvertently interpolate user input
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: security / ambiguity                                                                                                                                                                                                                      
  ▎ Location: docs/spec/messaging.md §Progress notifications; docs/spec/decisions.md D31                                                                                                                                                                
  ▎ Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "Stage strings are looked up by enum from the deterministic localization bundle... User input is never interpolated into progress strings."
  "Stage events (STARTED, RETRIEVING, GENERATING, TRANSLATING, FINALIZING)."                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  A progress string like "Retrieving 47 posts for #security" includes a tag name. If the tag name is user-provided (e.g., /summary user-chosen-name), interpolating it into the progress string violates D31's security requirement. The spec's example 
  stage events are all generic, but a realistic UI would want counts, tag names, or command keywords. The spec does not say whether variable data (counts, tag names) is allowed in progress strings, or whether the localization bundle supports       
  parameterized-but-safe templates (e.g., retrieving_posts_count=47, tag=security).
                                                                                                                                                                                                                                                        
  Suggested resolution:
  Clarify in messaging.md: "Progress strings may include deterministic, sanitized scalar values (counts, tag names) via a pre-registered template parameter system. User-authored free text is never interpolated." Or, if no variable data is allowed:
  "Progress strings are fixed enum-to-string mappings with no parameters."                                                                                                                                                                              
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F14] supportsMarkdownCode naming implies broader markdown support
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: smell                                                                                                                                                                                                                                     
  ▎ Location: docs/spec/messaging.md §Capability flags; docs/spec/decisions.md D30
  ▎ Confidence: medium                                                                                                                                                                                                                                  
                  
  What the spec says:                                                                                                                                                                                                                                   
  "supportsMarkdownCode — when true, code spans render as monospace."
  D30: "Plain-text formatting for all bot output. Inline code in single backticks, multi-line in triple backticks; bare URLs (no markdown link syntax)."                                                                                                
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  The flag name supportsMarkdownCode implies the adapter supports Markdown code blocks. But the spec forbids markdown link syntax entirely. A future adapter implementer might see this flag and assume links wrapped in [text](url) are acceptable. The
   capability granularity is too coarse for the "bare URLs only" rule.                                                                                                                                                                                  
                  
  Suggested resolution:                                                                                                                                                                                                                                 
  Rename to supportsCodeFormatting or add a separate supportsMarkdownLinks flag that is explicitly false for all v1 adapters. Alternatively, change D30 to "URLs are always bare; adapters MUST NOT render markdown link syntax even if they support
  other markdown features."                                                                                                                                                                                                                             
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F15] Translation sanity-check criteria are unbounded
                                                         
  ▎ Severity: major
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/llm.md §Failure handling                                                                                                                                                                                                        
  ▎ Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says:
  "Translation — sanity-check the output: if the response is identical to the input, empty, or clearly not in the target language, fall back to English with a one-line note."
                                                                                                                                                                                                                                                        
  Why it's a problem:
  "Clearly not in the target language" is subjective. An implementer might check for ASCII-only output when targeting Czech, or use a language-detection library, or do nothing. The fallback behavior is security-relevant (it prevents showing        
  garbage) but not testable.                                                                                                                                                                                                                            
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Replace with concrete criteria: "Sanity-check fails if (a) output equals input, (b) output is empty or whitespace-only, (c) output contains fewer than N characters from the target script (e.g., no Czech characters for cs), or (d) the translation
  provider returned an HTTP error."                                                                                                                                                                                                                     
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F16] Auto-compress failure mode is unspecified
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: failure-mode                                                                                                                                                                                                                              
  ▎ Location: docs/spec/decisions.md D24; docs/spec/commands.md §Conversation control                                                                                                                                                                   
  ▎ Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  D24: "Auto-triggered near the context-window ceiling."
  commands.md: "/compress — forces an immediate chat_memory checkpoint... Auto-triggered near the context-window ceiling."                                                                                                                              
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  Auto-compress runs the summarizer LLM in the background during a chat turn. If the LLM is slow or down, does the chat agent proceed with a truncated context? Block? Error out? A bounded timeout would silently truncate, losing the oldest messages.
   An unbounded wait would hang the user. The spec commits to the trigger but not the failure path.                                                                                                                                                     
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to llm.md or commands.md: "If auto-compress fails or times out, the chat agent proceeds with the truncated context window; the oldest messages are discarded. A one-line notice is included in the agent prompt: (earlier messages omitted due to 
  length)." Or, if the spec wants to block: state the timeout and the user-visible error.                                                                                                                                                               
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F17] No failure mode for permanent messaging adapter delivery failure
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: failure-mode                                                                                                                                                                                                                              
  ▎ Location: docs/spec/messaging.md §Failure handling
  ▎ Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says:                                                                                                                                                                                                                                   
  "Send/update/finalize failures are reported to Provider as exceptions with a category (transient vs. permanent). Transient failures retry with backoff; permanent failures abort the affected reply and log."
                                                                                                                                                                                                                                                        
  Why it's a problem:
  If the adapter reports a permanent failure (e.g., the user blocked the bot), the Provider aborts the reply. But what about the user's state? If the failure happens during a chat-mode conversation, does the Provider wipe the in-flight context?    
  Does it record a "delivery failed" event? If periodic digests fail permanently for a group, does the scheduler skip the group forever or retry next slot? The spec is silent on state cleanup after permanent failures.                               
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to messaging.md: "Permanent delivery failures abort the reply and do not advance the chat session state. The context window remains as if the message was never generated. For periodic digests, a permanent failure is logged and the next slot
  attempts delivery normally."                                                                                                                                                                                                                          
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F18] Group admin auto-promote on first @mention — group already exists edge case
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/security.md §Authorization model
  ▎ Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says:                                                                                                                                                                                                                                   
  "Bootstrapped by first @mention in a new group."
  "On first sight the Provider creates a groups row."                                                                                                                                                                                                   
  
  Why it's a problem:                                                                                                                                                                                                                                   
  If the Provider restarts and loses in-memory state but the groups row persists, is the group still "new"? The "first @mention" rule and the auto-promote path depend on whether the group row exists, not on whether this is the first-ever @mention.
  If a group row exists with zero admins (e.g., previous admin was demoted), the next @mention should probably trigger auto-promotion. But the spec says "first @mention in a new group." Does "new group" mean "group row didn't exist before this     
  message" or "group has never had an admin"?
                                                                                                                                                                                                                                                        
  Suggested resolution:
  Clarify: "Auto-promotion triggers when a non-banned user @mentions the bot in a group that has zero admins. This covers both newly created groups and groups left without an admin due to demotion or ban."
                                                                                                                                                                                                                                                        
  ---
  ▎ [F19] Fetcher partial-parse failure is unspecified                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: failure-mode                                                                                                                                                                                                                              
  ▎ Location: docs/spec/decisions.md D42; docs/spec/architecture.md §Ingest SPIs                                                                                                                                                                        
  ▎ Confidence: medium
                                                                                                                                                                                                                                                        
  What the spec says:
  D42: "Fetcher failure (HTTP error, connection timeout, feed parse failure) on an HTTP-shaped source retries on the next scheduled tick."                                                                                                              
  "After N consecutive per-source failures... source status transitions to 'failed'."                                                                                                                                                                   
  
  Why it's a problem:                                                                                                                                                                                                                                   
  What if the HTTP fetch succeeds and the body is partially parseable (e.g., 10 items parse, 1 is malformed)? Is this a "parse failure" that counts toward N? Or are the 10 good items enqueued and the 1 dropped? The "one bad RSS feed must not stall
  the rest" principle suggests partial success is desirable, but the spec doesn't say how to count it.                                                                                                                                                  
                  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to D42 or architecture.md: "A partially parseable response enqueues the successfully parsed items and counts as a success for the per-source failure counter. An item-level parse failure increments an item-error counter but does not affect the
   source health metric."                                                                                                                                                                                                                               
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F20] [refused-action] marker name leaked from design into spec
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: layering                                                                                                                                                                                                                                  
  ▎ Location: docs/spec/llm.md §Prompt-injection-aware prompt shape; docs/spec/security.md §What lives in design notes                                                                                                                                  
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  llm.md: "The system prompt instructs the model to... refuse action requests with a [refused-action] marker."
  security.md §What lives in design notes: "The [refused-action] marker convention."                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  The exact marker string is an implementation convention, not a behavior commitment. If the design changes the marker to [rejected] to avoid false positives, the spec text in llm.md becomes a lie. The spec should describe the goal (the model      
  signals refusal via a structured marker) without fixing the marker string.                                                                                                                                                                            
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Replace in llm.md: "refuse action requests with a structured refusal marker (marker convention in design notes)." Move the literal string [refused-action] entirely to design notes.
                                                                                                                                                                                                                                                        
  ---
  ▎ [F21] "chat-output sanitizer for admin commands" is misleading                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/security.md §Prompt-injection defenses
  ▎ Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "Untrusted-content delimiter convention with per-call random marker; chat-output sanitizer for admin commands; LLM never has admin tools or arbitrary SQL."                                                                                           
                                                                                                                                                                                                                                                        
  Why it's a problem:
  Admin commands are dispatched deterministically; their output never passes through an LLM. A "chat-output sanitizer for admin commands" therefore has nothing to sanitize. The sentence implies admin command output goes through the LLM output      
  sanitizer, which contradicts the determinism boundary. This is either a leftover phrase or an accidental widening of the sanitizer scope.                                                                                                             
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Delete "chat-output sanitizer for admin commands;" from this sentence. The sanitizer scope is correctly defined two paragraphs below.
                                                                                                                                                                                                                                                        
  ---
  ▎ [F22] schema.md Invariant 5 outbox description contradicts status enum                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  ▎ Severity: major
  ▎ Category: inconsistency                                                                                                                                                                                                                             
  ▎ Location: docs/spec/schema.md §Invariant 5                                                                                                                                                                                                          
  ▎ Confidence: high
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
  "A startup rehydrator picks up any post left in RAW (or an intermediate evaluating state) after a crash."
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  The status enum only defines RAW, READY, and QUARANTINED. There is no "intermediate evaluating state" status value. The outbox rehydrator must infer evaluation progress from the per-stage outcome flags ("Stage-1 / Stage-2 / tagger / embedding
  outcome flags"). But Invariant 5 says "or an intermediate evaluating state," implying there are statuses beyond the three listed. This is a schema/documentation mismatch.                                                                            
                  
  Suggested resolution:                                                                                                                                                                                                                                 
  Remove "(or an intermediate evaluating state)" from Invariant 5, replacing it with: "A startup rehydrator picks up any post left in RAW after a crash. Posts with RAW status but with some stage flags already set resume from the next uncompleted
  stage."                                                                                                                                                                                                                                               
                  
  ---                                                                                                                                                                                                                                                   
  ▎ [F23] No spec for how provider_state high-water mark handles concurrent updates
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: gap                                                                                                                                                                                                                                       
  ▎ Location: docs/spec/schema.md §Operational; docs/spec/architecture.md §Inter-service communication
  ▎ Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says:                                                                                                                                                                                                                                   
  "The catch-up query uses > last_ready_post_at; the high-water mark is advanced in the same DB transaction as the side effect it triggers."
  "provider_state Singleton(s) holding catch-up high-water marks."                                                                                                                                                                                      
  
  Why it's a problem:                                                                                                                                                                                                                                   
  If the Provider processes two NOTIFY events concurrently (or if a catch-up query and a NOTIFY-triggered handler race), both may read the same old high-water mark, process overlapping post sets, and both try to advance the mark. The "same DB
  transaction" claim guarantees atomicity per transaction, but not ordering across concurrent transactions. The spec doesn't say whether the high-water mark update uses optimistic locking, SELECT FOR UPDATE, or is inherently single-threaded.       
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to architecture.md: "High-water mark updates are serialized per channel via SELECT FOR UPDATE on the provider_state row, or the Provider processes catch-up and NOTIFY events on a single thread." If single-threaded, state that explicitly; it
  has performance implications.                                                                                                                                                                                                                         
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F24] Deployment.md bootstrap order: Provider may start before sources exist
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: operator                                                                                                                                                                                                                                  
  ▎ Location: docs/spec/deployment.md §Bootstrap behavior on startup
  ▎ Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says:                                                                                                                                                                                                                                   
  "There is no requirement to start the services in a fixed order; on a clean checkout running both at once is supported."
  Provider bootstraps bot admin. Collector bootstraps sources.                                                                                                                                                                                          
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
  An operator running both simultaneously might see the Provider ready before the Collector has seeded sources. A user DMing the bot immediately after startup gets /help and tries /summary — there are no posts. The spec doesn't describe this as an 
  error or a loading state. Is the user told "no posts yet, try later"? Or does /summary return empty silently?                                                                                                                                         
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add to commands.md §/summary: "If no READY posts exist in the requested window, return a friendly 'no posts yet' reply rather than an empty summary." This is likely the intended behavior, but it is not explicitly committed.
                                                                                                                                                                                                                                                        
  ---
  ▎ [F25] CoinGecko "free tier" may require API key                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/decisions.md D39                                                                                                                                                                                                                
  ▎ Confidence: medium
                                                                                                                                                                                                                                                        
  What the spec says:
  "Public no-auth endpoints only in v1... Kraken public REST, Bitfinex public REST, CoinGecko free tier."
  "Exchanges that require an API key or auth token... are out of v1."                                                                                                                                                                                   
  
  Why it's a problem:                                                                                                                                                                                                                                   
  CoinGecko's free tier has historically required an API key (even if no payment is required). The spec says "no-auth endpoints only" but calls out CoinGecko, which may in fact require an API key. This creates confusion about whether the operator
  must provide a CoinGecko key in v1. If they do, the "no-auth" rule is violated. If they don't, CoinGecko may not work.                                                                                                                                
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Clarify D39: "CoinGecko free tier via public endpoint (no API key required). If CoinGecko changes their policy, v1 falls back to Kraken/Bitfinex only." Or, if a key is allowed: "API-key-less endpoints only; operator-provided keys for
  otherwise-free tiers are deferred to the operator-secret SPI (v2)."                                                                                                                                                                                   
  
  ---                                                                                                                                                                                                                                                   
  ▎ [F26] /get-sources behavior not fully specified as an alias
                                                                                                                                                                                                                                                        
  ▎ Severity: minor
  ▎ Category: ambiguity                                                                                                                                                                                                                                 
  ▎ Location: docs/spec/commands.md §Discovery
  ▎ Confidence: low                                                                                                                                                                                                                                     
  
  What the spec says:                                                                                                                                                                                                                                   
  "/get-sources — alias of /list-sources without --all."

  Why it's a problem:
  Does this mean /get-sources accepts --page? Does it show status flags for failed/disabled sources (which /list-sources does)? An alias should behave identically to the target command with certain flags defaulted, but the spec doesn't say which
  flags /get-sources accepts.                                                                                                                                                                                                                           
  
  Suggested resolution:                                                                                                                                                                                                                                 
  Add: "/get-sources accepts the same flags as /list-sources except --all, which is omitted."
                                                                                                                                                                                                                                                        
  ---             
  3. Cross-cutting Observations                                                                                                                                                                                                                         
                               
  Failure handling for stream sources is consistently underspecified.
  Findings F05 (Nostr re-delivery), F19 (partial fetcher parse), and F16 (auto-compress failure) all share a pattern: the spec commits to a happy-path behavior but leaves the degraded path to inference. For the Fetcher SPI, D42 provides a clear    
  failure ladder. For StreamSource, the equivalent per-relay degradation commitment (D38) exists but the event-loss-on-shutdown path is hand-waved. The determinism boundary is strong for retrieval but weak for background jobs.                      
                                                                                                                                                                                                                                                        
  The determinism boundary is stated in llm.md but not consistently enforced in commands.md.                                                                                                                                                            
  llm.md says retrieval is always SQL, LLMs only generate prose. But /save personal tags are free-form (F12) with no normalization, meaning two users saving the same post with semantically identical but textually different tags (e.g., "security" vs
   "Security") will create divergent filterable rows. If /saved security does fuzzy matching, the result set becomes LLM-influenced-by-implementation. If it does exact match, it's deterministic but user-hostile. The spec needs to pick.             
                  
  The scope model has a tension between "per-(user, scope)" and "per-user global."                                                                                                                                                                      
  Findings F02 (saved_post), F07 (/export group-wide tables), and F06 (chat mode in groups) all reveal that the scope abstraction works cleanly for DM vs group isolation, but breaks down for features that are naturally global to a user (/save,
  /export self-data). Invariant 1 tries to be absolute but may need explicit exceptions.                                                                                                                                                                
                  
  ---                                                                                                                                                                                                                                                   
  4. Spec Evaluation

  ┌──────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │       Axis       │                                                                                                          Assessment                                                                                                           │
  ├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤  
  │ Completeness     │ Good, with gaps. The core ingest and chat pipelines are well-covered. Asset commands, invite codes, and the two-admin model are thorough. Missing: concrete failure modes for background jobs, quarantine TTL exemption, and  │
  │                  │ the NEEDS_REVIEW state.                                                                                                                                                                                                       │  
  ├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤  
  │ Consistency      │ Fair. Most sections agree, but schema.md and security.md disagree on post statuses. The scope model for saved_post contradicts invariant 1. The /retry permission model has an unbounded group context.                       │
  ├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤  
  │ Implementability │ Borderline. A senior engineer could start building, but would need clarification on: UID hash algorithm, saved_post scope key, stream-source event-loss behavior, and progress notifier parameterization. Expect daily        │
  │                  │ clarifications for the first two weeks.                                                                                                                                                                                       │  
  ├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ Testability      │ Good. verification.md (not fully audited here, but referenced) has a clear mandate. However, the missing NEEDS_REVIEW state and unbounded translation sanity-check make some security-path tests unwritable.                  │  
  ├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤  
  │ Evolvability     │ Good. The spec/design split mostly holds. The most likely leakage points are: concrete marker strings ([refused-action]) in spec text, and capability flag names that imply broader behavior than committed.                  │
  └──────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘  
                  
  ---                                                                                                                                                                                                                                                   
  5. Pros and Cons

  Pros

  - Strong invariants. Invariant 1 (per-scope isolation), the determinism boundary, and the "no LLM in the trust path" rule are excellent guardrails.                                                                                                   
  - Clear spec/design split. The "What lives in design notes" trailers in every file make the boundary explicit.
  - Security-first ingest pipeline. Stage 1 deterministic + Stage 2 LLM judge with clear verdict/infra separation is well thought out.                                                                                                                  
  - Adapter SPI is thin and correct. Capability negotiation, not feature flags, means future adapters won't force rewrites.                                                                                                                             
  - Decisions log (D1–D45) is a force multiplier. It makes cross-cutting reasoning possible without grep.                                                                                                                                               
                                                                                                                                                                                                                                                        
  Cons                                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  - Schema status enum is incomplete (NEEDS_REVIEW missing), which breaks the security failure-handling path.                                                                                                                                           
  - Scope model has unresolved tension between per-user and per-scope, particularly for /save and /export.
  - Failure modes for background jobs (re-evaluation, auto-compress, stream-source drain) are under-specified compared to the foreground request path.                                                                                                  
  - No topology enforcement for D41's "exactly one" guarantee — an easy operator foot-gun.                                                                                                                                                              
  - Some design details leaked up (e.g., [refused-action] literal string) where they can ossify.                                                                                                                                                        
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  6. Recommended Next Actions                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  1. Fix the NEEDS_REVIEW status gap (F01). This is a schema/security inconsistency that blocks implementation of the re-evaluation job. Do this first.
  2. Resolve the saved_post scope model (F02). Decide whether saves are per-user-global or per-scope, update D13 and Invariant 1, then propagate to /export. This affects almost every user-state query.                                                
  3. Specify the UID hash algorithm (F03). A one-sentence normative commitment prevents cross-instance dedup failures.                                                                                                                                  
  4. Add quarantine TTL exemption (F11). Without this, the admin review workflow is silently broken after the post retention window.                                                                                                                    
  5. Fix the Nostr stream-source event-loss claim (F05). Replace the false "will reappear" guarantee with a realistic flush-or-lose contract.                                                                                                           
  6. Add re-evaluation cadence to deployment config surface (F10). Operators cannot tune what they cannot discover.                                                                                                                                     
  7. Clarify /retry permission in groups (F04). Define the anchor schema so regular members can retry their own summaries without accidentally touching periodic digests.          
