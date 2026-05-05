Here's my thorough review of the infochat spec, organized by category. Overall, this is a well-architected spec with strong security          
  boundaries and clear separation of concerns. I've identified critical issues, significant concerns, and smaller improvements.                  
                                                                                                                                                 
  ---                                                                                                                                            
  🔴 Critical Issues                                                                                                                             
                                                                                                                                                 
  C1. Chat Agent Tool Use Creates a Major Security Gap
                                                                                                                                                 
  Location: 01-architecture.md §1.4 (chat mode), 05-llm-and-embeddings.md §5.4.3                                                                 
  Problem: The chat agent has tools (searchByTag, getPostById, getReferences, recallMemory). The spec says "Read-only DB role (cannot write)" but
   LLM tool calling is inherently non-deterministic. An attacker who socially engineers the bot in chat mode could potentially:                  
  - Exhaust the embedding provider via repeated recallMemory calls
  - Trigger expensive vector queries repeatedly                                                                                                  
  - Use getReferences to map the entire graph structure
                                                                                                                                                 
  The per-user rate limiter (60 chat/min) is way too high for this — that's 1 request per second, and each tool call may trigger LLM + vector DB 
  + join queries.                                                                                                                                
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Add a separate tool-call budget distinct from the chat rate limit. E.g., 10 tool calls per conversation, with exponential backoff.
  - Cache tool results within a single conversation turn (if LLM calls getPostById twice with same ID, return cached result).                    
  - Add a tool_cost column to rate limiter tracking; expensive tools (vector search) cost more than cheap ones (tag lookup). 
                                                                                                                                                 
  ---                                                                                                                                            
  C2. LISTEN/NOTIFY Has No Delivery Guarantee                                                                                                    
                                                                                                                                                 
  Location: 01-architecture.md §1.3, 02-schema.md §2.8
  Problem: The eval pipeline does NOTIFY new_post after setting status='READY'. If the Provider is down or reconnecting, that notification is    
  lost forever. The Provider's chat agent cache invalidation relies on this.                                                                     
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Add a post_notification_log table (or use post timestamps): Provider tracks last_processed_post_at and on startup/reconnect, queries for
  post.status='READY' AND post.fetched_at > last_processed_post_at as a backfill.                                                                
  - OR: Change the notification payload to include the post's fetched_at, and have the provider record a high-water mark.
                                                                                                                                                 
  ---                                                                                                                                            
  C3. No Mention of Connection Pool Exhaustion Between Two Services                                                                              
                                                                                                                                                 
  Location: 07-deployment.md §7.4                                                                                                                
  Problem: Both services connect to the same Postgres DB with max-size=20. Under load (collector writing + provider reading), plus LLM latency   
  blocking provider threads, you can easily exhaust 20 connections. The provider especially risks this because each incoming message may hold a  
  connection for the duration of command processing.                                                                                             
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Split pools explicitly: collector=10, provider=20 (or provider=30).
  - Use Quarkus reactive/vertx SQL client for provider's read paths so connections aren't held during LLM calls.                                 
  - Document that quarkus.datasource.jdbc.max-size must be service-specific.                                    
                                                                                                                                                 
  ---                                                                                                                                            
  🟡 Significant Concerns                                                                                                                        
                                                                                                                                                 
  S1. The Eval Pipeline Stage 2 Fallback Is a Risky Compromise                                                                                   
                                                                                                                                                 
  Location: 01-architecture.md §1.3, 04-security.md §4.4                                                                                         
  Problem: When Stage 2 (LLM judge) is down, posts are auto-released as READY with Stage 1 redactions. The rationale is "avoid quarantine        
  backlog." But Stage 1 is regex+HTML sanitizer — it's trivial for an attacker to bypass regex-based detection (e.g., base64-encoded payloads,   
  ignore previous in Unicode homoglyphs, etc.). The spec acknowledges this by having Stage 2 at all.
                                                                                                                                                 
  Releasing potentially malicious content because the judge is down creates a degraded-security-during-outage vulnerability.                     
  
  Suggested fix:                                                                                                                                 
  - When Stage 2 is down, release as READY but set a quarantine_review_required flag on the post.
  - Provider treats such posts in one of two ways:                                                                                               
    - Option A (safer): Exclude them from /summary and /search results entirely. User sees "some posts are unavailable due to security review
  backlog."                                                                                                                                      
    - Option B: Include them but prepend a [REVIEW PENDING] marker in summaries.                                                                 
  - OR: Keep the current behavior but require explicit operator opt-in via config: infochat.security.release-on-stage2-failure=true with default 
  false.                                                                                                                                         
                                                                                                                                                 
  ---                                                                                                                                            
  S2. Group Admin Auto-Promote Is a Race Condition                                                                                               
                                                                                                                                                 
  Location: 04-security.md §4.3, 03-commands.md
  Problem: "First user to @mention the bot in a new group is auto-promoted." If two users @mention simultaneously (or in rapid succession), both 
  could pass the "is first?" check before either INSERT completes.                                                                               
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Use a SELECT ... FOR UPDATE on groups row during creation, or
  - Make group creation an atomic operation: INSERT INTO groups ... ON CONFLICT DO NOTHING + INSERT INTO group_membership (...,                  
  is_group_admin=TRUE) inside a single transaction, using a unique constraint on adapter_group_id.
                                                                                                                                                 
  ---             
  S3. saved_post.post_id ON DELETE RESTRICT Breaks Partition Pruning                                                                             
                                                                                                                                                 
  Location: 02-schema.md §2.6
  Problem: The TTL pruner does DELETE FROM post WHERE fetched_at < now() - interval '30 days' AND id NOT IN (SELECT post_id FROM saved_post). The
   NOT IN (subquery) against saved_post forces Postgres to scan the entire post table to check the condition, defeating any index on fetched_at. 
  With millions of posts, this will time out or hold locks for minutes.                                                                          
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Add post.is_saved BOOLEAN updated by trigger on saved_post insert/delete.
  - Pruner becomes: DELETE FROM post WHERE fetched_at < ... AND is_saved = false.                                                                
  - This is index-friendly: (fetched_at, is_saved) partial index.                
                                                                                                                                                 
  ---                                                                                                                                            
  S4. No Input Validation on external_id Length                                                                                                  
                                                                                                                                                 
  Location: 02-schema.md §2.3 (post table)
  Problem: external_id TEXT NOT NULL with no length limit. RSS GUIDs can be URLs (potentially KBs in length). Combined with UNIQUE (source_id,   
  external_id), this creates a index bloat and potential DoS vector.                                                                             
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Add CHECK (length(external_id) <= 2048) or hash it if longer. 2048 is generous — most GUIDs are <256 chars.
                                                                                                                                                 
  ---             
  S5. chat_session.messages JSONB Grows Unbounded                                                                                                
                                                                                                                                                 
  Location: 02-schema.md §2.6
  Problem: messages JSONB stores the full context window. With a 32K context window (remote profile), each message could be ~8K tokens ≈ 32KB. 50
   messages = 1.6MB per row. The table is keyed by (user_id, scope_kind, scope_id) so there are potentially many rows, but each can grow large.  
  JSONB doesn't compress well for large arrays.                                                                                                  
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Cap messages array length in application code (e.g., max 100 messages, with /compress at 75%).
  - Consider splitting to chat_message child table if you expect very long sessions, but honestly the spec's auto-compress at 75% is probably    
  enough IF enforced at the DB level too. Add a check or a periodic cleanup job.
                                                                                                                                                 
  ---             
  S6. SimpleX WebSocket Auth Is Single-Point-of-Failure                                                                                          
                                                                                                                                                 
  Location: 06-messaging.md §6.4.1
  Problem: infochat.adapter.simplex.session-token=${SIMPLEX_SESSION_TOKEN} read from env. No mention of token rotation, expiry, or what happens  
  when the token is compromised. The adapter reconnects infinitely on failure — if the token is revoked, it'll reconnect forever and spam logs.  
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Document token rotation procedure in runbook.
  - After N consecutive auth failures (distinguish from network failures), the adapter should fail fatally and take the provider's health check  
  to 503, so the operator notices.                                                                                                             
  - Add metric adapter.auth.fail separate from adapter.connection.status.                                                                        
                  
  ---                                                                                                                                            
  🟢 Medium / Design Concerns
                                                                                                                                                 
  M1. post.body Sanitized HTML → Text Conversion Is Undefined
                                                                                                                                                 
  Location: 01-architecture.md §1.3, 02-schema.md §2.3                                                                                           
  Problem: The spec says body=sanitized_html but also says body is used for embedding and entity extraction. Is it raw HTML? Plain text? How are 
  <img> tags handled? The body_summary field is populated "when length(body) > 2000 chars" — what about HTML tag length vs visible text length?  
                  
  Suggested fix:                                                                                                                                 
  - Be explicit: body is always plain text (post-HTML-stripping). Store original HTML in a separate post.raw_html column if needed for
  quarantine.original_html references.                                                                                                           
  - Clarify that length(body) means character count of plain text.
                                                                                                                                                 
  ---                                                                                                                                            
  M2. Embedding Dimension Migration Is Underspecified
                                                                                                                                                 
  Location: 02-schema.md §2.7
  Problem: The scripts/reembed.sh approach (add column, re-embed, switch, drop old) is fine but the spec doesn't say how LinkingJob knows which  
  column to read during the migration window. Also, the migration locks the table while adding a vector(N) column.                               
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Use a post_embedding_version lookup table or config key that LinkingJob reads to decide which column to query.
  - For online migration without locks, create a new table post_embedding_v2 rather than adding a column, then switch via a view: CREATE OR      
  REPLACE VIEW active_embedding AS SELECT * FROM post_embedding_v2.                                                                         
                                                                                                                                                 
  ---             
  M3. source.deleted_at Soft-Delete + Unique Constraint Conflict                                                                                 
                                                                                                                                                 
  Location: 02-schema.md §2.2                                                                                                                    
  Problem: UNIQUE (fetcher, url) on source. If a source is soft-deleted (deleted_at IS NOT NULL), re-adding via /add-source needs to clear       
  deleted_at. But UNIQUE constraints in Postgres include NULL values (two NULLs are distinct, but a non-deleted row and a deleted row both have  
  actual fetcher/url values). So you can't have a deleted source and a new source with same fetcher+url.                                         
                                                                                                                                                 
  Wait, actually the spec says: "re-adding via /add-source clears deleted_at on the existing row instead of inserting." That's fine, but what if 
  two different scopes both soft-delete the same source? The second one would fail to clear deleted_at because... no, the first one already
  cleared it. This is actually fine if all scopes share the source table. But the spec also says DM sources are private. So if user A deletes a  
  DM source, user B shouldn't lose it.

  Clarification needed: Does /remove-source (bot admin) set deleted_at globally? What about a user unsubscribing — is that /unfollow-source      
  (removes source_subscription row) vs /remove-source (sets deleted_at on source globally)? The spec needs to clarify that only bot admins can
  /remove-source (global soft-delete), and regular users use /unfollow-source (just removes their subscription).                                 
                  
  ---
  M4. AdapterCapabilities.maxRequestsPerSecond Is Ambiguous
                                                                                                                                                 
  Location: 06-messaging.md §6.2
  Problem: The field says "soft client-side rate limit" but the comment on send() says "adapter MUST NOT block the calling thread for more than  
  maxRequestsPerSecond worth of time" — that's nonsensical (a rate limit is not a time duration).                                                
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Rename to maxInflightSends or clarify the semantics. If it's a rate limit, the adapter should internally throttle. If it's a concurrency
  limit, rename it. The current spec conflates both.                                                                                             
                  
  ---                                                                                                                                            
  M5. Digest Cache Key Is Insufficient
                                                                                                                                                 
  Location: 01-architecture.md §1.4.1
  Problem: Cache is Cache(group_id, slot). But a group's followed tags can change between digests. If admin changes /follow-tag at 9am, the 8pm  
  digest should reflect the new tag set, not a stale cache.                                                                                      
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Include a hash of scope_tag + source_subscription state in the cache key, or use a cache invalidation strategy: any /follow-tag,
  /unfollow-tag, /add-source operation invalidates the group's digest cache.                                                                     
                  
  ---                                                                                                                                            
  M6. No Pagination on /list-sources, /quarantine list, /audit
                                                                                                                                                 
  Location: 03-commands.md
  Problem: As the system grows, /list-sources --all could return 1000+ sources. /quarantine list could grow large if Stage 1 is over-sensitive.  
  /audit is unbounded.                                                                                                                           
                                                                                                                                                 
  Suggested fix:                                                                                                                                 
  - Add pagination to these commands. For a chat interface, simple "page N of M" with /list-sources --page 2 is fine.
  - Cap default output to ~20 items with a footer: ... and 47 more. Use /list-sources --page 2.                                                  
                                                                                               
  ---                                                                                                                                            
  🟦 Minor / Polish                                                                                                                              
                                                                                                                                                 
  m1. chat_memory.referenced_topics UUID[] References Ephemeral IDs                                                                              
                                                                                                                                                 
  Location: 02-schema.md §2.6                                                                                                                    
  Topic IDs are computed at query time from post_reference connected components and cached for 60 minutes. Storing them in durable chat_memory   
  means they'll be meaningless after cache expiry. Document that referenced_topics is best-effort and may not resolve on recall.                 
                  
  m2. post.social_score Formula Is Hidden                                                                                                        
                  
  Location: 02-schema.md §2.3                                                                                                                    
  The formula 2*reposts + likes is mentioned only in a schema comment. It should be in 01-architecture.md or 05-llm-and-embeddings.md as a formal
   scoring definition, especially if it affects /summary ranking.                                                                                
                  
  m3. Bootstrap Sources JSON Has No Versioning                                                                                                   
                  
  Location: 07-deployment.md §7.6                                                                                                                
  bootstrap-sources.json could evolve. Add a "schema_version": 1 field so the loader can reject incompatible files with a clear error instead of
  silently ignoring new fields.                                                                                                                  
                  
  m4. ConfirmationStore Token Collision Risk                                                                                                     
                  
  Location: 03-commands.md, 08-verification.md §8.3.2                                                                                            
  Confirmation tokens are used for destructive ops. If these are simple incrementing integers or timestamps, they're guessable. The spec should
  require cryptographically random tokens (e.g., 8-char alphanum).                                                                               
                  
  m5. ScopeRef Sealed Interface in Java 21                                                                                                       
                  
  Location: 06-messaging.md §6.2                                                                                                                 
  Using sealed interface ScopeRef with record implementations is clean Java 21 code. Good choice.
                                                                                                                                                 
  ---                                                                                                                                            
  📊 Summary Matrix                                                                                                                              
                                                                                                                                                 
  ┌─────────────────────────────────────────────┬─────────────┬────────┬────────────┐
  │                    Issue                    │  Severity   │  File  │ Fix Effort │                                                            
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ C1. Chat agent tool budget                  │ Critical    │ 01, 05 │ Medium     │                                                            
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ C2. LISTEN/NOFIFY no delivery guarantee     │ Critical    │ 01, 02 │ Small      │                                                            
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ C3. Connection pool exhaustion              │ Critical    │ 07     │ Small      │                                                            
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ S1. Stage 2 fallback releases risky content │ Significant │ 01, 04 │ Medium     │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ S2. Group admin race condition              │ Significant │ 04, 03 │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ S3. saved_post RESTRICT breaks pruning      │ Significant │ 02     │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ S4. external_id unlimited length            │ Significant │ 02     │ Tiny       │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ S5. chat_session.messages unbounded         │ Significant │ 02     │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ S6. SimpleX token rotation                  │ Significant │ 06, 07 │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ M1. body sanitization undefined             │ Medium      │ 01, 02 │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ M2. Embedding migration underspecified      │ Medium      │ 02     │ Medium     │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ M3. source soft-delete semantics            │ Medium      │ 02, 03 │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ M4. maxRequestsPerSecond ambiguous          │ Medium      │ 06     │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ M5. Digest cache stale on tag changes       │ Medium      │ 01     │ Small      │
  ├─────────────────────────────────────────────┼─────────────┼────────┼────────────┤                                                            
  │ M6. No pagination                           │ Medium      │ 03     │ Medium     │
  └─────────────────────────────────────────────┴─────────────┴────────┴────────────┘                                                            
                  
  ---                                                                                                                                            
  ✅ What's Done Well
                                                                                                                                                 
  1. Strong security boundary: "Determinism boundary" principle — SQL for retrieval, LLM only for prose. This prevents prompt injection from
  affecting which data a user sees.                                                                                                              
  2. Per-(user, scope) isolation: The schema enforces this at the row level, not just application logic.
  3. Outbox + rehydrator: Makes the eval pipeline resilient to crashes without needing Kafka.                                                    
  4. Hardware profiles: Clever bundling that simplifies ops without losing flexibility.                                                          
  5. Two-tier admin model: Prevents escalation attacks by keeping destructive ops out of LLM tool use.                                           
  6. Test strategy: Contract tests parameterized over SPI implementations; FakeLlmProvider for CI determinism.                                   
  7. Partitioning for TTL: Row-level deletes are avoided; partition drops are O(1).                                                              
                                                                                                                                                 
  --- 
