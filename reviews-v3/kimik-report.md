1. Executive summary

I read the full specification map, cross-cutting decision log, and all nine section files plus the MVP slice. The spec demonstrates strong architectural intent—deterministic retrieval, per-scope isolation, outbox durability, and a clean
spec/design split—but it has not yet converged on a single, implementable schema for the source table. The most severe issues are: (1) a direct contradiction in /add-source argument syntax between the command catalogue and the MVP, (2) a         
structural mismatch between the spec's generalized (kind, identifier, config) source identity and the design notes' (fetcher, url) columns, which breaks Nostr bootstrap, and (3) a disagreement about whether chat_memory has a TTL pruner in v1.
These three issues alone would cause two competent engineering teams to build incompatible database schemas and CLI parsers.
                  
---
2. Findings

[B1] /add-source argument syntax: positional url in MVP vs. --url flag in commands catalogue

Severity: blocker
Category: inconsistency                                                                                                                                                                                                                               
Location: docs/spec/commands.md §"Source management" / docs/00-mvp.md §4                                                                                                                                                                              
Confidence: high

What the spec says
- commands.md: /add-source --type … --url … --tags … (all double-dash flags)
- 00-mvp.md: /add-source <rss-url> --tags tag1,tag2[,...] (<url> is positional, --type omitted)

Why it's a problem                                                                                                                                                                                                                                    
Team A builds a POSIX-style parser with positional URL and infers --type=rss in the MVP; Team B builds a flag-only parser with mandatory --type and --url. The MVP exit criteria cannot be exercised against both parsers. The command grammar is the
contract between user and bot; ambiguity here breaks every integration test.

Suggested resolution                                                                                                                                                                                                                                  
Pick one shape and propagate it. Keep positional <url> for MVP (it's shorter) and make --type default to rss in the MVP, then document the full flag grammar in commands.md as the post-MVP surface. Or, adopt positional URL everywhere and drop
--url from the spec.
   
---                                                                                                                                                                                                                                                   
[B2] source table identity: spec commits to (kind, identifier, config); design notes implement (fetcher, url)

Severity: blocker
Category: inconsistency / scope                                                                                                                                                                                                                       
Location: docs/spec/architecture.md §"Source identity" / docs/spec/decisions.md D38 / docs/design/02-schema.md §2.2
Confidence: high

What the spec says
- architecture.md: "Source identity (decision D38) is (kind, identifier, config)... Together with kind it forms the unique key for source rows."
- decisions.md D38: "Source identity generalizes from (fetcher, url) to (kind, identifier, config)... identifier is the URL for HTTP-shaped sources and the filter spec for stream sources; config is opaque per-kind JSON."
- 00-mvp.md §2 still refers to UNIQUE (fetcher, url).

Why it's a problem                                                                                                                                                                                                                                    
The v1 schema as designed (docs/design/02-schema.md) has columns fetcher and url with UNIQUE (fetcher, url) and no kind, identifier, or config columns. For Nostr (a v1 StreamSource), the "identifier" is the filter spec and "config" is the relay  
list. There is nowhere to store either. Either the design notes must be rewritten to match the spec generalization, or the spec must retreat to (fetcher, url) plus an opaque config_json column. As written, Nostr cannot be bootstrapped.

Suggested resolution                                                                                                                                                                                                                                  
If the generalization is real, update 02-schema.md to add kind TEXT NOT NULL, identifier TEXT NOT NULL, config JSONB, and UNIQUE(kind, identifier). Deprecate fetcher and url or keep them as view aliases. If the generalization is aspirational,
move (kind, identifier, config) to the "Deferred to v2" section and revert D38's identity model to (fetcher, url, config_json).
                  
---                                                                                                                                                                                                                                                   
[B3] MVP lists a scope table that does not exist in schema or design notes

Severity: blocker
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/00-mvp.md §2 ("scope / scope_preferences") / docs/spec/schema.md §"Per-scope state" / docs/design/02-schema.md §2.6
Confidence: high

What the spec says
- 00-mvp.md: "In scope: scope / scope_preferences — minimal: a row per DM scope keyed by user.id."

Why it's a problem
schema.md treats scope as a concept ('dm' or 'group'), not a table. 02-schema.md has scope_preferences but no scope table. If MVP requires a scope table, the DDL is missing. If "scope / scope_preferences" is shorthand for "just                   
scope_preferences," the list item is misleading. An implementer will create a scope table that the rest of the system does not use.

Suggested resolution                                                                                                                                                                                                                                  
Change the bullet to "scope_preferences only" and clarify that scope is a virtual discriminator, not a table. If a scope table is genuinely needed (e.g., to FK source_subscription.scope_id), add it to schema.md and 02-schema.md with columns.
                                                                                                                                                                                                                                                        
---
[B4] chat_memory TTL: schema.md mandates a pruner; design notes say "indefinite, none in v1"

Severity: blocker
Category: inconsistency                                                                                                                                                                                                                               
Location: docs/spec/schema.md §"Invariants" #9 / docs/design/02-schema.md §2.9 TTL policy table / docs/spec/decisions.md D37
Confidence: high

What the spec says
- schema.md: "chat_memory rows carry a fixed retention horizon (value in design notes) after which they are removed by a scheduled pruner."
- design/02-schema.md TTL table: "chat_memory: indefinite (manual /forget planned for v2); mechanism: none in v1."
- decisions.md D37: "chat_memory entries that are not /saved carry a fixed TTL (value in design notes)."

Why it's a problem                                                                                                                                                                                                                                    
Two implementers will build opposite retention policies. The user-facing privacy promise (/forget exists because data is kept) is incompatible with the invariant (data is auto-deleted). verification.md even has a test for the pruner. This is an  
unambiguous contradiction.

Suggested resolution                                                                                                                                                                                                                                  
Decide in spec whether v1 has automatic TTL on chat_memory. If yes, fix 02-schema.md to add the pruner and the TTL value. If no, remove pruner expectations from schema.md, verification.md, and D37, keeping only /forget as the user purge
mechanism.
   
---                                                                                                                                                                                                                                                   
[M5] post.status enum: spec and MVP list 3 values; design adds EVALUATING and FAILED without spec backing

Severity: major
Category: layering / gap                                                                                                                                                                                                                              
Location: docs/spec/schema.md §"Post" / docs/00-mvp.md §2 / docs/design/02-schema.md §2.3
Confidence: high

What the spec says
- schema.md: "Carries status (RAW, READY, QUARANTINED)."
- 00-mvp.md: status ∈ {RAW, READY, QUARANTINED}.
- design/02-schema.md: 'RAW','EVALUATING','READY','QUARANTINED','FAILED'.

Why it's a problem                                                                                                                                                                                                                                    
EVALUATING is arguably an implementation detail (the outbox rehydrator needs to distinguish in-flight work), but FAILED is a terminal state the spec never describes. When does a post become FAILED? The failure handling section (security.md       
§"Failure handling") describes releasing with redactions, quarantining, or releasing without artifacts—none of which result in FAILED. Either the spec is missing a failure mode, or the design note leaked an unused state.

Suggested resolution                                                                                                                                                                                                                                  
If FAILED is real (e.g., unrecoverable fetch or parse error), add it to schema.md and describe the transition in security.md. If EVALUATING is the only intermediate state, restrict the design enum to {RAW, EVALUATING, READY, QUARANTINED} and drop
FAILED, or move it to a design-only internal column.
   
---                                                                                                                                                                                                                                                   
[M6] MVP embeds post.embedding as a column; design factors it out to post_embedding table

Severity: major
Category: inconsistency                                                                                                                                                                                                                               
Location: docs/00-mvp.md §2 (Schema) / docs/design/02-schema.md §2.4
Confidence: high

What the spec says
- 00-mvp.md: post table includes embedding (vector).
- design/02-schema.md: post_embedding is a separate partitioned table with embedding_model and fetched_at.

Why it's a problem                                                                                                                                                                                                                                    
The MVP is supposed to be the strict subset that proves the architecture. If the MVP schema has post.embedding inline, the first demo works. When v1 adds partitioning, the column must be removed and a new table created—this is a destructive
migration on a table that already has data. The spec should not commit to inline vectors in the MVP if the real design uses a separate table.

Suggested resolution                                                                                                                                                                                                                                  
Update 00-mvp.md to list post_embedding (not post.embedding) as the MVP table, keeping it minimal (no partitioning in MVP, just the table). This makes the MVP schema forward-compatible with v1.
                                                                                                                                                                                                                                                        
---
[M7] /retry anchoring model for periodic group digests is contradictory

Severity: major
Category: inconsistency                                                                                                                                                                                                                               
Location: docs/spec/commands.md §"/retry" / docs/spec/decisions.md D36                                                                                                                                                                                
Confidence: high

What the spec says
- commands.md: "/retry regenerates the prose for the last summary-producing command in the calling (user, scope)... For periodic group digests, /retry is group-admin or bot-admin only and replaces the cached digest."
- decisions.md D36: "/retry re-runs the LLM prose stage of the last summary-producing command for the calling (user, scope)... For periodic group digests /retry requires group admin or bot admin and replaces the cached digest."

Why it's a problem                                                                                                                                                                                                                                    
Periodic digests are not initiated by a user; they are generated by the scheduler. They therefore have no "calling (user, scope)" in the sense D36 uses for user-issued commands. If Alice (group admin) types /retry in a group, is the anchor the   
group's last periodic digest, or Alice's own last /summary command? Two implementers will build different anchor lookups.

Suggested resolution                                                                                                                                                                                                                                  
Clarify in commands.md and D36 that in a group context, /retry has two independent anchors: (1) the calling user's last on-demand summary, and (2) the group's last periodic digest, selectable by an implicit rule (group digest takes precedence if
it exists and the caller is admin). Or, split the command into /retry (user anchor) and /retry-digest (group anchor).
   
---                                                                                                                                                                                                                                                   
[M8] Bootstrap sources JSON schema omits Nostr config block

Severity: major
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/deployment.md §"Operator inputs" / docs/spec/decisions.md D38 / docs/spec/security.md §"Nostr"
Confidence: high

What the spec says
- deployment.md: "A JSON document listing the initial set of feeds (name, url, fetcher, category, tags[])."
- D38: Nostr source identity requires "operator-configured relay list" and a "kinds filter" inside an opaque config JSON block.

Why it's a problem                                                                                                                                                                                                                                    
The operator cannot write a valid bootstrap-sources.json for Nostr using the documented schema, because there is no field for config or the relay list. The MVP exit criteria only test RSS, so this won't be caught during MVP validation, but it
blocks v1 Nostr ingestion.

Suggested resolution                                                                                                                                                                                                                                  
Document the full bootstrap-sources.json schema in deployment.md, including the config object for StreamSource-shaped kinds. Reference design/10-asset-commands.md (which documents bootstrap-assets.json) as the template for completeness.
                                                                                                                                                                                                                                                        
---
[M9] Banned group admin blocks group admin promotion without documented escape hatch

Severity: major
Category: security / gap                                                                                                                                                                                                                              
Location: docs/spec/security.md §"User ban" / docs/spec/commands.md §"Admin (bot admin)"
Confidence: medium

What the spec says
- security.md: "Banning a user who is a group admin: their is_group_admin rows remain but are unreachable; /unban restores the role."
- schema.md: "At most one group admin per group" (enforced by partial unique index).
- commands.md: /promote demotes existing admin in the same transaction.

Why it's a problem                                                                                                                                                                                                                                    
If Alice is group admin and gets banned, the group has no functional admin. The unique index still reserves her slot. A new user @mentioning the bot will fail auto-promotion (index conflict). Can a bot admin /promote Bob while Alice is banned?   
The spec doesn't say. If /promote first tries to demote Alice, does it succeed even though she is banned? If the ban check runs before command execution, the bot admin passes; but if the demote requires touching Alice's row, a trigger might      
reject it. The group is effectively admin-frozen.

Suggested resolution
Explicitly state in security.md that /promote can demote a banned user and promote a new one in the same transaction. Add a verification test: banned admin can be demoted by bot admin, freeing the slot.
                                                                                                                                                                                                                                                        
---
[M10] post_reference table deferred in MVP, but Nostr kind 6 reposts need to store original event reference

Severity: major
Category: scope                                                                                                                                                                                                                                       
Location: docs/00-mvp.md §2 (Deferred) / docs/spec/security.md §"Nostr (StreamSource, v1)"
Confidence: high

What the spec says
- 00-mvp.md: post_reference is deferred (not created in MVP).
- security.md: "Kind 6 reposts are stored with a reference to the original event id."
- 00-mvp.md post table does not have an original_event_id column.

Why it's a problem                                                                                                                                                                                                                                    
If MVP is tested against a Nostr fixture containing a kind 6 repost, the system has nowhere to store the original event id. The MVP exit criteria don't include Nostr, but Nostr is listed as a v1 feature, and the spec says reposts "are stored with
a reference." The design notes need a column for this even if post_reference (the linking table) is deferred.

Suggested resolution                                                                                                                                                                                                                                  
Either: (a) add original_event_id to the post table as a nullable column in v1, or (b) explicitly defer kind 6 repost handling to post-MVP v1 and drop them on the floor in MVP, or (c) create post_reference in MVP with a simplified schema. Update
00-mvp.md accordingly.
   
---                                                                                                                                                                                                                                                   
[M11] Determinism boundary: who computes cluster grouping for /summary?

Severity: major
Category: ambiguity / smell                                                                                                                                                                                                                           
Location: docs/spec/commands.md §"/summary" / docs/spec/llm.md §"Determinism boundary" / docs/spec/decisions.md D19
Confidence: medium

What the spec says
- commands.md: "/summary [tag] [-w …] — on-the-fly summary of READY posts in the window. Cluster grouping by post_reference. LLM writes prose per cluster."
- llm.md: "Retrieval is always SQL. LLMs only generate prose or extract structured fields at ingest."
- D19: "All retrieval is deterministic SQL... Same query → same posts twice in a row."

Why it's a problem                                                                                                                                                                                                                                    
"Cluster grouping by post_reference" does not say who performs the grouping. If the LLM decides which posts form a cluster, the set of posts returned is no longer deterministic SQL. If SQL computes connected components, the LLM only writes prose
per pre-computed cluster, preserving D19. Two implementers could place the clustering in the LLM prompt or in a graph traversal job.

Suggested resolution                                                                                                                                                                                                                                  
Add one sentence to commands.md: "Cluster grouping is computed by deterministic SQL traversal of post_reference edges before the LLM prose stage; the LLM receives a fixed list of posts per cluster."
                                                                                                                                                                                                                                                        
---
[M12] Asset command integration with Fetcher SPI is under-specified

Severity: major
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/commands.md §"Asset commands" / docs/spec/architecture.md §"Fetcher"
Confidence: medium

What the spec says
- commands.md: "Polled data sources reuse the existing Fetcher SPI."
- architecture.md: Fetcher is "polled, request/response... Used by RSS, Bluesky, Nitter..."
- decisions.md D39: "Polled data sources reuse the existing Fetcher SPI on a profile-driven refresh interval."

Why it's a problem
The Fetcher SPI is tied to the source table: the scheduler ticks per source row, and the fetcher receives a source identity. Asset snapshots are explicitly "not posts" and live in price_snapshot. Do asset data sources create ghost rows in the    
source table (with kind='coingecko'), or does the Collector run a parallel scheduler not tied to source? The SPI contract is unclear—can the same Fetcher implementation produce non-post output?

Suggested resolution                                                                                                                                                                                                                                  
Clarify in architecture.md whether the asset fetcher is a separate scheduler or a Fetcher impl that writes to price_snapshot instead of enqueuing posts. If it's the latter, document the divergence in the Fetcher SPI contract (e.g., an output-type
discriminator).
   
---                                                                                                                                                                                                                                                   
[M13] Last-admin protection: spec claims UPDATE + DELETE; design note only mentions UPDATE trigger

Severity: major
Category: inconsistency / verification gap                                                                                                                                                                                                            
Location: docs/spec/schema.md §"Invariants" #2 / docs/spec/security.md §"Authorization model" / docs/design/02-schema.md §2.1 / docs/spec/verification.md §"Schema"                                                                                   
Confidence: high

What the spec says
- schema.md: "Enforced at the trigger layer, not just the command layer." (both UPDATE and implied DELETE)
- security.md: "Enforced at the trigger layer, not just the command layer."
- verification.md: "Trigger-level test, asserts both UPDATE and DELETE paths."
- design/02-schema.md: "-- Enforced by trigger on UPDATE." (DELETE not mentioned)

Why it's a problem                                                                                                                                                                                                                                    
The verification test requires a DELETE trigger that does not exist in design notes. If a sql-injection or buggy ORM issues DELETE FROM users WHERE id = :adminId, the last admin disappears. The spec promises trigger-level protection but design
only documents an UPDATE trigger.

Suggested resolution                                                                                                                                                                                                                                  
Either add a DELETE trigger to 02-schema.md, or remove the DELETE claim from verification.md and schema.md if the threat model considers row-level DELETE unreachable via the application layer.
                                                                                                                                                                                                                                                        
---
[M14] source.status vs. source.deleted_at semantics are undefined

Severity: major
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/schema.md §"Invariants" #4 / docs/design/02-schema.md §2.2
Confidence: high

What the spec says
- design/02-schema.md: source has status TEXT NOT NULL DEFAULT 'active' with values 'active','failed','disabled', and also a deleted_at TIMESTAMPTZ for soft-delete.
- schema.md: "Soft-delete only for sources... source is never hard-deleted."

Why it's a problem                                                                                                                                                                                                                                    
There is no explanation of the difference between status='disabled' and deleted_at IS NOT NULL. When does an operator or command set one vs. the other? What does status='failed' mean, and how does consecutive_failures relate to it? The bootstrap
loader "skips rows where deleted_at IS NOT NULL" but does not mention status. A source could be status='disabled' with deleted_at IS NULL and still be fetched.

Suggested resolution                                                                                                                                                                                                                                  
Document the state machine in schema.md: transitions between active/failed/disabled, and how soft-delete (deleted_at) interacts with status. For example: /remove-source sets deleted_at and status='disabled'; the fetcher scheduler only selects
status='active' AND deleted_at IS NULL.
   
---                                                                                                                                                                                                                                                   
[M15] Stage 2 BENIGN verdict behavior is not specified

Severity: major
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/security.md §"Ingest pipeline" / docs/spec/verification.md §"Security"
Confidence: high

● [M15] Stage 2 BENIGN verdict behavior is not specified

Severity: major                                                                                                                                                                                                                                       
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/security.md §"Ingest pipeline" / docs/spec/verification.md §"Security"                                                                                                                                                            
Confidence: high

What the spec says
- security.md: Stage-2 verdict outcomes are INJECTION / MALWARE / UNKNOWN.
- verification.md: "fake LLM returns each of BENIGN, INJECTION, MALWARE, UNKNOWN; post status is correct in each case."

Why it's a problem                                                                                                                                                                                                                                    
BENIGN appears in the test suite requirements but never in the security model. If Stage 2 returns BENIGN, does the post transition directly to READY, or does it still need tagger + embedding? The spec says Stage 2 is "only invoked when Stage 1
flagged something." If the judge says BENIGN, does that override Stage 1 and release with redactions removed, or keep redactions and route to tagger? Two implementers will disagree.

Suggested resolution                                                                                                                                                                                                                                  
Add BENIGN to the Stage 2 verdict taxonomy in security.md with explicit semantics: "BENIGN releases the post to tagger/embedding with Stage 1 redactions retained" (or "removed", but pick one).
                                                                                                                                                                                                                                                        
---
[M16] /stop cancellation of "in-flight read-only tool call" is contradictory

Severity: major
Category: inconsistency                                                                                                                                                                                                                               
Location: docs/spec/commands.md §"/stop" / docs/spec/decisions.md D35
Confidence: high

What the spec says
- commands.md: "The in-flight LLM stream is closed and any in-flight read-only tool call (e.g., DB query) is cancelled."
- D35: "any in-flight read-only tool call (e.g., DB query) is cancelled. The cancellation window covers the LLM and tool-call stages only."

Why it's a problem                                                                                                                                                                                                                                    
Most Java database drivers (including the PostgreSQL JDBC driver used by Quarkus) do not support reliable Statement.cancel() across all query shapes, especially when the result set is being consumed. The spec commits to a platform capability that
may not exist. If the driver can't cancel, the worker is not "freed for others" as promised. A timeout on the connection pool is the practical fallback, but that violates "immediately."

Suggested resolution                                                                                                                                                                                                                                  
Add a failure-mode sentence: "If the DB driver does not support query cancellation, the connection is closed and the pool recovers; the user receives the stop acknowledgement while the query may run to completion on the DB side." Or, define
"cancelled" as "the result is discarded, the connection is aborted, and the worker moves on," which is implementable.
  
---                                                                                                                                                                                                                                                   
[M17] recallMemory() tool is promised but never appears in the security tool allowlist

Severity: major
Category: gap / inconsistency                                                                                                                                                                                                                         
Location: docs/spec/llm.md §"Memory retrieval" / docs/spec/security.md §"Prompt-injection defenses"                                                                                                                                                   
Confidence: high

What the spec says
- llm.md: "Recall tool. A scope-filtered, read-only recallMemory(keywords) tool the agent can invoke for deeper digs."
- security.md tool allowlist: "tag-filtered SQL, single-post fetch, reference lookup, scope-filtered memory recall, per-user saved-list read" — note "memory recall," not recallMemory.
- commands.md deferred list: "/recall <keyword> and /memories commands" deferred to v2.

Why it's a problem                                                                                                                                                                                                                                    
The chat agent has a recallMemory() tool in v1 (per llm.md D28), but the only mention of "recall" in commands is deferred to v2. The security allowlist uses fuzzy wording ("memory recall") instead of the exact tool name. An implementer might     
build recallMemory() for the agent but omit the user-facing /recall command, or vice versa. The relationship between the agent tool and the deferred user command is unclear.

Suggested resolution                                                                                                                                                                                                                                  
In security.md, list the exact tool names the LLM may call, including recallMemory. In llm.md, add a note: "The recallMemory agent tool exists in v1; the user-facing /recall command is deferred to v2."
                                                                                                                                                                                                                                                        
---             
[M18] chat_session and chat_message tables are invisible to the spec

Severity: major
Category: layering                                                                                                                                                                                                                                    
Location: docs/spec/schema.md §"Per-scope state" / docs/design/02-schema.md §2.6                                                                                                                                                                      
Confidence: high

What the spec says
- schema.md: Mentions "Chat session / context window" as a per-(user, scope) live context state. "/clear wipes only this; chat memory is independent."
- design/02-schema.md: Full DDL for chat_session and chat_message tables with triggers.

Why it's a problem                                                                                                                                                                                                                                    
The spec acknowledges the concept but never commits the table names, column shapes, or the fact that the context window is persisted to the DB (not held in-memory). This means eviction policy (60 days in 02-schema.md), token counting, and /clear
semantics are design-only. If the design changes the table shape, the spec doesn't constrain it.

Suggested resolution                                                                                                                                                                                                                                  
Either pull chat_session and chat_message up into schema.md as formal entities with invariants, or remove the mention from schema.md and treat them as purely implementation artifacts. Given that /clear and /compress are spec-level commands, the
tables should be spec-level entities.
  
---                                                                                                                                                                                                                                                   
[M19] /export group-scoped behavior is vague about "group-wide content beyond caller's own contributions"

Severity: major
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/commands.md §"/export" / docs/spec/verification.md §"Commands and chat"                                                                                                                                                           
Confidence: medium

What the spec says
- commands.md: "Group: scoped to the calling (user, group) only — never another user's state, never group-wide content beyond the caller's own contributions."
- verification.md: "a DM /export does not leak any group-scoped state."

Why it's a problem                                                                                                                                                                                                                                    
What is a "contribution" in a group? If Alice posts /save 123 in a group (even though /save is per-user), is that a "contribution"? If Bob sends a chat-mode message that the bot replies to, does Bob's export include the bot's reply (which was
generated in the group scope)? The spec is trying to say "only your own data," but the boundary between user-authored data and bot-generated data in a group is unclear.

Suggested resolution                                                                                                                                                                                                                                  
Replace "contributions" with explicit table list: "/export contains the caller's chat_memory, saved_post, chat_session/chat_message, and scope_preferences for the current scope only. It does not include post rows, source rows, or other users'
state."
  
---                                                                                                                                                                                                                                                   
[M20] provider_state table is not mentioned in the spec

Severity: major
Category: layering                                                                                                                                                                                                                                    
Location: docs/spec/architecture.md §"Inter-service communication" / docs/design/02-schema.md §2.8                                                                                                                                                    
Confidence: high

What the spec says
- architecture.md: "A high-water mark on the Provider side guarantees correctness across restarts."
- design/02-schema.md: Full DDL for provider_state table.

Why it's a problem                                                                                                                                                                                                                                    
The high-water mark is a spec-level correctness mechanism (it closes the LISTEN/NOTIFY durability gap). The table that stores it is not mentioned in schema.md or architecture.md as an entity. If the design changes the PK from provider_instance to
something else, the spec has no leverage.

Suggested resolution                                                                                                                                                                                                                                  
Add provider_state to schema.md §"Operational" as a first-class entity with the invariant: "Every new_post event with ready_at greater than the stored high-water mark for this instance is processed exactly once."
                                                                                                                                                                                                                                                        
---
[M21] Bot-admin bootstrap creates user on first startup, but what if contact-id format is adapter-specific?

Severity: major
Category: gap / operator                                                                                                                                                                                                                              
Location: docs/spec/deployment.md §"Operator inputs" / docs/spec/security.md §"Authorization model"
Confidence: medium

What the spec says
- deployment.md: "A bot-admin contact id. The cryptographic contact id of the user who will be the first bot admin. On startup, Provider ensures this user exists."
- security.md: "Auto-register if absent (DM only; group: only on @mention)."

Why it's a problem                                                                                                                                                                                                                                    
The bootstrap contact ID is a raw string in application.properties. If the operator is running the in-memory test adapter in dev and SimpleX in prod, the contact ID formats may differ (e.g., SimpleX uses long cryptographic IDs, the test adapter  
might use test-user-1). There is no per-adapter bootstrap property, and no validation that the configured ID is compatible with the active adapter.

Suggested resolution                                                                                                                                                                                                                                  
Document in deployment.md that the contact ID format must match the active adapter's identity scheme, and that switching adapters requires reviewing the bootstrap admin configuration.
                                                                                                                                                                                                                                                        
---
[M22] Asset command price_snapshot table ownership: Collector writes, Provider reads—at odds with DB role split

Severity: major
Category: security / inconsistency                                                                                                                                                                                                                    
Location: docs/spec/commands.md §"Asset commands" / docs/spec/security.md §"DB roles" / docs/design/02-schema.md §2.2
Confidence: high

What the spec says
- commands.md: "Snapshots live in a collector-owned price_snapshot (or equivalent) table."
- security.md DB roles: Collector role has INSERT/UPDATE on ingest-owned tables; Provider role has `write access on user-state tables; SELECT on collector-owned tables."
- design/02-schema.md: price_snapshot DDL is in the design notes, not placed in either role's ownership list.

Why it's a problem                                                                                                                                                                                                                                    
If price_snapshot is "collector-owned," the Provider role should only have SELECT on it. But commands.md also says asset commands are "not posts" and are "polled, cached, refreshed on a tick" by the Collector. The Provider's DB role document does
not mention price_snapshot at all. If the schema is shared but the role grants are incomplete, either the Provider cannot read prices or the security model has a gap.

Suggested resolution                                                                                                                                                                                                                                  
Add price_snapshot explicitly to the Collector role grant (INSERT/UPDATE/DELETE for pruner) and Provider role grant (SELECT). Update security.md DB roles section and 02-schema.md role description.
                                                                                                                                                                                                                                                        
---
[M23] bootstrap_meta table is design-only, but /status (admin) is spec-level

Severity: minor
Category: layering                                                                                                                                                                                                                                    
Location: docs/spec/commands.md §"/status" / docs/design/02-schema.md §2.8
Confidence: medium

What the spec says
- commands.md: /status returns "runtime status (active profile, uptime, scope-specific counts; admin sees more)."
- design/02-schema.md: bootstrap_meta exists to answer "are all instances running the same bootstrap config?"

Why it's a problem                                                                                                                                                                                                                                    
/status is spec-level, but its ability to show bootstrap consistency depends on a table not documented in the spec. If the table is removed or renamed in design, the spec command has no backing data.

Suggested resolution
Either add bootstrap_meta to schema.md §"Operational" as a spec-level entity, or remove bootstrap-consistency from /status and make it a design-only admin tool.
                                                                                                                                                                                                                                                        
---
[M24] post.body_summary population condition references a design threshold (2000 chars) in DDL comments

Severity: minor
Category: layering                                                                                                                                                                                                                                    
Location: docs/design/02-schema.md §2.3
Confidence: high

What the spec says
- schema.md: "Post... carries status, body, title..." — no mention of body_summary.
- design/02-schema.md: body_summary TEXT with comment "populated when length(body) > 2000 chars (see 05-llm §5.4)."

Why it's a problem                                                                                                                                                                                                                                    
The 2000-character threshold is an implementation value, but the column's existence and purpose are spec-adjacent (they affect whether /summary sees the full body or an abstract). The spec is silent on whether body_summary exists or when it is
used.

Suggested resolution                                                                                                                                                                                                                                  
Add body_summary to schema.md §"Post" with a one-line invariant: "Optional LLM-generated abstract; used when the original body exceeds a profile-driven length threshold."
                                                                                                                                                                                                                                                        
---
[M25] source soft-delete and re-add semantics clash with subscription ownership

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/schema.md §"Invariants" #4 / docs/design/02-schema.md §2.2
Confidence: medium

What the spec says
- schema.md: "Soft-delete only for sources... re-adding via /add-source clears deleted_at."
- design/02-schema.md: "re-adding via /add-source clears deleted_at on the existing row instead of inserting."

Why it's a problem                                                                                                                                                                                                                                    
If Alice subscribes to Source X, then Bob (bot admin) /remove-source's it, then Alice re-adds it, does Alice become the added_by user? Is the old source_subscription row for Alice still present, or was it cascade-deleted on soft-delete? The spec
doesn't describe what happens to subscriptions when a source is soft-deleted.

Suggested resolution                                                                                                                                                                                                                                  
Document in schema.md: "Soft-deleting a source does not delete source_subscription rows. Re-adding clears deleted_at and may update added_by to the re-adder; existing subscriptions remain active."
                                                                                                                                                                                                                                                        
---             
[M26] Command parser applies "input length caps" but caps are entirely in design notes

Severity: minor
Category: layering / verification gap                                                                                                                                                                                                                 
Location: docs/spec/commands.md §"Surface conventions" / docs/spec/verification.md
Confidence: medium

What the spec says
- commands.md: "Input length caps are applied at the parser before any LLM or DB work. Specific caps live in design notes."

Why it's a problem
The existence of caps is spec-level, but the values are not. Verification tests cannot assert that a 10KB message is rejected without importing design values. A spec without bounds is not testable.

Suggested resolution                                                                                                                                                                                                                                  
Define the cap categories in the spec (e.g., "max command body 4096 chars, max chat-mode body 16384 chars") with the note that exact numbers are profile-tunable in design. Or, move the entire cap concept to design if it's not a behavioral        
commitment.
  
---                                                                                                                                                                                                                                                   
[M27] TranslationProvider v1 scope: "English + Czech" but no specification of Czech UI strings

Severity: minor
Category: scope / operator                                                                                                                                                                                                                            
Location: docs/spec/llm.md §"Translation flow" / docs/spec/decisions.md D29 / docs/spec/commands.md §"/lang"
Confidence: medium

What the spec says
- llm.md: "v1 ships English and Czech."
- commands.md: "/lang  — sets per-scope output language. v1 ships English and Czech."
- llm.md: "Command parsing is English-only in v1."

Why it's a problem
If a Czech-speaking user sets /lang cs, do error messages ("Unknown command", ban notice, confirmation timeout) also translate? The spec says translation is "presentation-only" for LLM output, but what about deterministic strings from the command
layer? The operator cannot know what translation work is required.

Suggested resolution                                                                                                                                                                                                                                  
State explicitly: "All deterministic command-layer strings (errors, confirmations, /help text) are looked up from a localization bundle. v1 bundles English and Czech. LLM-generated prose is translated via TranslationProvider if the model does not
natively support the target language."
                  
---                                                                                                                                                                                                                                                   
[M28] scope_tag deferred in MVP but /follow-tag and /unfollow-tag are also deferred

Severity: minor
Category: scope coherence                                                                                                                                                                                                                             
Location: docs/00-mvp.md §2 (Deferred) §5 (Commands) / docs/spec/commands.md §"Per-scope tag preferences"
Confidence: high

What the spec says
- 00-mvp.md: scope_tag table deferred; /follow-tag, /unfollow-tag deferred.
- 00-mvp.md: scope_preferences is in scope, with tag_subscription_version column.

Why it's a problem                                                                                                                                                                                                                                    
scope_preferences contains tag_subscription_version, whose purpose is to invalidate the periodic-digest cache when tag subscriptions change. But in the MVP there are no tag subscriptions (table and commands both deferred), and no periodic
digests. The column is dead weight in MVP. This isn't wrong, just wasteful and confusing.

Suggested resolution                                                                                                                                                                                                                                  
Mention in 00-mvp.md that tag_subscription_version exists as a placeholder column for forward compatibility and is always 0 in MVP.
                                                                                                                                                                                                                                                        
---
[M29] Confirmation token timeout: spec says "short window" but never commits to a range

Severity: minor
Category: ambiguity / verification gap                                                                                                                                                                                                                
Location: docs/spec/commands.md §"Surface conventions" / docs/spec/verification.md §"Commands and chat"
Confidence: medium

What the spec says
- commands.md: "Confirmation token for destructive commands... within a short window."
- verification.md: "30-second timeout rejects late confirms."

Why it's a problem                                                                                                                                                                                                                                    
The verification test hard-codes 30 seconds, but the spec never commits to 30 seconds. If design changes it to 60 seconds, the test breaks or the spec is silently violated.

Suggested resolution
Add "A fixed timeout (default 30 seconds; profile-tunable in design) resets the confirmation state." to commands.md.
                                                                                                                                                                                                                                                        
---
[M30] Nostr StreamSource: cross-relay dedup by "stable upstream id" but post.external_id has a 2KB cap and hash fallback

Severity: minor
Category: smell / failure-mode                                                                                                                                                                                                                        
Location: docs/spec/decisions.md D38 / docs/spec/security.md §"Nostr" / docs/design/02-schema.md §2.3
Confidence: medium

What the spec says
- D38: "Cross-relay dedup (same event-id from N relays → one posts row)."
- design/02-schema.md: post.external_id has CHECK (length(external_id) <= 2048) and "the fetcher hashes the raw value (sha256-hex) and stores the digest" beyond the cap.

Why it's a problem                                                                                                                                                                                                                                    
Nostr event IDs are 64-character hex strings, well under 2KB. But if the StreamSource hashes them for any reason (e.g., to combine event id + relay), the dedup guarantee breaks because two relays delivering the same original event would hash to
different values if the relay name is in the hash input.

Suggested resolution                                                                                                                                                                                                                                  
Document in security.md or architecture.md that Nostr dedup must use the raw id field from the event JSON, never a composite or hashed value.
                                                                                                                                                                                                                                                        
---
[M31] users.save_count denormalized counter is design-only but enforces a spec-level cap

Severity: minor
Category: layering                                                                                                                                                                                                                                    
Location: docs/spec/commands.md §"/save" / docs/design/02-schema.md §2.1 / §2.6
Confidence: medium

What the spec says
- commands.md: "/save... capped per user."
- design/02-schema.md: "Cap of 1000 saves per user... users.save_count is maintained by trigger."

Why it's a problem                                                                                                                                                                                                                                    
The 1000-save cap is an implementation value, but the existence of a cap is spec-level. The spec doesn't say that there is a cap, only that saves are "capped per user." An implementer might use a SELECT COUNT(*) check instead of the denormalized
counter.

Suggested resolution                                                                                                                                                                                                                                  
Add to commands.md: "Saves are capped at a per-profile maximum (default 1000) to prevent unbounded storage growth."
                                                                                                                                                                                                                                                        
---
3. Cross-cutting observations

Schema spec vs. design drift is the dominant risk. Findings B2, B3, B4, M5, M6, M13, M14, M18, M20, M23, M24, M28, M31 all trace to the same root cause: schema.md describes entities in prose and invariants, while design/02-schema.md provides the
DDL, and the two have diverged on table shapes, column names, and state machines. The spec/design boundary is being violated in both directions. The spec should be the source of truth for "what tables exist and what invariants they uphold"; right
now it is under-specified on table structure.

Failure-handling commitments are strong in intent but weak in mechanism. The separation of "verdict" vs. "infrastructure failure" (D22, security.md) is well described for Stage 2, but for the tagger, entity extraction, and embedding stages, the  
spec says "release with fallback" without defining what happens to partial or corrupted data. M15 (BENIGN undefined) and M24 (body_summary threshold uncommitted) are symptoms.

The MVP is not a strict subset in schema terms. M6 (post.embedding inline) and M10 (post_reference deferred vs. Nostr kind 6) show that the MVP cuts tables in ways that make v1 features impossible without migrations. The ambition to "prove the   
architecture" is undermined by schema choices that aren't forward-compatible.

Authorization has a vertical consistency problem. The ban check position (before parser, before DB) is well stated, but the interaction of bans with group admin slots (M9), with /export scope (M19), and with the last_ready_post_at reconciler     
(does a banned admin's Provider instance still advance the watermark?) are never addressed.

Operator usability degrades around JSON bootstrap files. M8 and M21 show that the operator cannot write correct bootstrap files from the spec alone. The asset commands design note (10-asset-commands.md) is more detailed than the sources          
bootstrap, which is ironic because sources are the core feature.
                                                                                                                                                                                                                                                        
---             
4. Spec evaluation

Completeness — 6/10
The functional surface (commands, ingest pipeline, admin tiers) is well covered. The data model is incomplete: too many tables (chat session, provider state, bootstrap meta, price snapshot) are treated as implementation details when they back    
spec-level behaviors. The Nostr and asset-command additions in v1 have under-specified bootstrap and schema integration.

Consistency — 5/10                                                                                                                                                                                                                                    
Direct contradictions exist between the command catalogue and MVP argument syntax (B1), between (kind, identifier, config) and (fetcher, url) (B2), and between chat_memory TTL policies (B4). The post.status enum has five states in design but
three in spec (M5). These would cause daily clarification requests during implementation.

Implementability — 6/10                                                                                                                                                                                                                               
An engineer can build the happy path from this spec. The unhappy paths (cancellation, banned admin group lockout, Stage 2 BENIGN, failed embedding re-evaluation timing) are where two teams would diverge. The LLM SPI shape is thin enough to wrap
LangChain4j; the adapter SPI is serviceable.

Testability — 7/10                                                                                                                                                                                                                                    
verification.md is the strongest file in the suite. It maps nearly every invariant to a test layer. Its weakness is believing design-note values (30s timeout, 11th call rate-limit) are spec-enforceable when the spec hasn't committed to them. The
MVP exit criteria are concrete and passable.

Evolvability — 7/10                                                                                                                                                                                                                                   
The spec/design split is principled and mostly honored. The leakage is from spec into design (not the reverse): the spec is too thin on schema, not too thick on implementation. The profile abstraction is solid. The deferred list in SPEC.md §4 is
well maintained. The biggest evolvability risk is the source identity generalization (B2): if it isn't aligned now, adding the next StreamSource will require a migration.
                  
---                                                                                                                                                                                                                                                   
5. Pros and cons of the current state

Pros
- Strong security posture. The trust-boundary layering (adapter → ban check → deterministic auth → LLM) is clearly stated and defensible. The "no LLM in the trust path" invariant is repeated often enough to stick.
- MVP is concrete. 00-mvp.md has unambiguous exit criteria. A team can aim at something testable.
- Verification strategy is thorough. Four test layers, explicit invariant-to-test mapping, fuzz scenarios for race conditions.
- Deferred items are explicit. The "NOT in v1" and "Deferred to v2" lists prevent scope creep.
- Per-scope isolation is a first-class principle. Repeated in schema invariants, command behavior, and cancellation semantics.

Cons
- Schema is under-specified at the spec layer. Too many tables live only in design notes, creating drift (M6, M18, M20, B4).
- Direct contradictions between files. Three blockers (B1, B2, B4) would stop an implementation sprint for clarification.
- Bootstrap operator experience is incomplete. JSON schemas for critical config files are in design notes or missing entirely (M8).
- Failure-mode coverage for non-LLM dependencies is thin. Postgres LISTEN/NOTIFY failure, pgvector index build failure, messaging adapter permanent disconnect—these have no stated behavior.
- The asset-command design note is richer than the sources spec. Cross-pollinating the rigor of 10-asset-commands.md into commands.md §"Asset commands" would help.

  ---                                                                                                                                                                                                                                                   
6. Recommended next actions

1. Fix the source identity contradiction (B2). This is the deepest structural issue. Decide whether to generalize to (kind, identifier, config) now or revert to (fetcher, url) + config_json, then update schema.md, decisions.md D38, 00-mvp.md, and
   02-schema.md in one pass. Unblocks Nostr v1.
2. Reconcile /add-source syntax (B1). One-line fix that unblocks parser implementation and MVP testing.
3. Resolve chat_memory TTL (B4). Decide the privacy model: auto-expire or user-purge-only. Update spec, design, and verification together.
4. Align post status enum (M5) and post_embedding table ownership (M6). Forward-MVP schema compatibility depends on this.
5. Add missing entities to schema.md (M18, M20, M23, B3). chat_session, provider_state, bootstrap_meta, and clarify whether scope is a table or a concept.
6. Document bootstrap JSON schemas in deployment.md (M8). The operator cannot stand the system up without knowing what to write in bootstrap-sources.json.
7. Clarify banned-group-admin promotion (M9) and /stop cancellation semantics (M16). Both are edge cases that will surface in integration testing; pre-answering them saves sprint interruption. 