1. Executive Summary

I read the full infochat specification: SPEC.md (map), decisions.md (39 cross-cutting decisions), architecture.md, security.md, schema.md, commands.md, llm.md, messaging.md, deployment.md, verification.md, and 00-mvp.md. This is a remarkably     
thorough and well-structured spec — the layering between "what/why" (spec) and "how" (design) is consistently maintained, the threat model is concrete, and the determinism boundary is stated clearly and enforced in multiple places.

Overall verdict: The spec is strong enough to build from, but has several gaps in failure-mode coverage, a few cross-file inconsistencies, and underspecified concurrency/race-condition stories that would force implementers to make architectural  
decisions on their own.

Top 3 issues by severity:

1. Provider high-water mark catch-up races with LISTEN/NOTIFY — the spec doesn't define the ordering guarantee between the two delivery paths, creating a window for duplicate or missed processing. (architecture.md)
2. /summary determinism under concurrent writes — the deterministic-SQL promise is unbounded in time; if a post transitions to READY mid-query, two callers can get different result sets for the same window. (llm.md, commands.md)
3. /add-source concurrency in groups — two group admins could race on the same URL; the idempotency contract says "upsert by (kind, identifier)" but doesn't specify the conflict behavior for simultaneous inserts with different tags. (commands.md,
   schema.md)

  ---                                                                                                                                                                                                                                                   
2. Findings

[F01] Provider catch-up vs. LISTEN/NOTIFY ordering is unspecified

Severity: major                                                                                                                                                                                                                                       
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/architecture.md § Inter-service communication                                                                                                                                                                                     
Confidence: high

What the spec says: "NOTIFY is the latency optimization; the high-water mark is the correctness guarantee." Provider replays READY posts since last_ready_post_at on startup.

Why it's a problem: If the Provider is running and receives a new_post NOTIFY and simultaneously the Collector updates the same post (e.g., from QUARANTINED back to READY after admin approval), the Provider could process the post twice — once    
from NOTIFY, once from catch-up on next restart. More critically: if Provider processes NOTIFY and then crashes before advancing the high-water mark, on restart the catch-up replays the same post. The spec doesn't say whether the Provider's
processing of a post is idempotent, and doesn't define the ordering guarantee between "process NOTIFY" and "advance high-water mark." Two engineers would build different things: one batches NOTIFY events and advances the mark after each batch;   
the other advances per-event in the same transaction as the side effect.

Suggested resolution: Add a commitment: "Processing a new_post event is idempotent. The high-water mark is advanced in the same database transaction as the side effect (e.g., sending the user a notification or updating a cache). The catch-up     
query uses WHERE post_id > last_ready_post_at to exclude already-processed posts."
                                                                                                                                                                                                                                                        
---             
[F02] /summary determinism is unbounded — concurrent post transitions break reproducibility

Severity: major
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/llm.md § Determinism boundary; docs/spec/commands.md § /summary                                                                                                                                                                   
Confidence: high

What the spec says: "Same query → same posts twice in a row" and "/summary security -w 24h returns the same set of posts twice in a row because the SQL doesn't depend on the LLM."

Why it's a problem: The determinism promise has no temporal bound. If a post transitions from RAW to READY between two /summary calls (even milliseconds apart), the second call returns a different set. The spec doesn't clarify whether "twice in a
row" means "with no intervening writes" (a vacuous guarantee) or "within a snapshot isolation window." An implementer using default PostgreSQL READ COMMITTED would get non-deterministic results if the Collector is concurrently writing. This also
affects /retry (decision D36) — "deterministic post selection and clustering are reused" implies the selection is cached, but the spec doesn't say where or for how long.

Suggested resolution: Clarify: "The determinism boundary means: given the same database state, the same query returns the same results. It does not guarantee the same results across database state changes. For /retry, the post selection and      
clustering from the original command are cached and reused — the SQL is not re-executed." This pins down whether /retry re-queries or reuses.
                                                                                                                                                                                                                                                        
---             
[F03] Group admin auto-promote race condition underspecified

Severity: major
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/security.md § Authorization model; docs/spec/schema.md § Invariants                                                                                                                                                               
Confidence: high

What the spec says: "One group admin per group at any time. Enforced by a partial unique index so the 'first @mention wins' auto-promote path is race-safe" and "INSERT … ON CONFLICT DO NOTHING."

Why it's a problem: The spec says the partial unique index makes the auto-promote race-safe, but it doesn't specify what happens to the second user who @mentions the bot in the same group. ON CONFLICT DO NOTHING silently drops the second insert —
but does the second user get an error, a "you're not admin" response, or nothing? The spec is silent on the UX outcome. Additionally, if the first user is later /demoted, the partial unique index would allow a new admin — but who becomes the new
admin? The spec doesn't define whether demoting the only admin in a group is allowed (it is allowed for bot admins because of last-admin protection, but no equivalent exists for group admin).

Suggested resolution: Add: "When two users @mention the bot in a new group simultaneously, the first insert wins and the second is a no-op; the second user receives the standard non-admin response. Demoting a group admin when no other group admin
exists is allowed (the group simply has no admin until a bot admin promotes someone). Group admin has no 'last-admin protection' — that invariant applies only to bot admin."
                                                                                                                                                                                                                                                        
---             
[F04] /add-source tag conflict on concurrent inserts

Severity: minor
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/commands.md § /add-source; docs/spec/schema.md § Sources and tags
Confidence: medium

What the spec says: /add-source is "idempotent on (kind, identifier)" and "Tags are mandatory (≥1)."

Why it's a problem: If two users simultaneously /add-source the same URL with different --tags sets, the upsert wins one set. The spec doesn't define whether tags are merged, replaced, or whether the second call errors. Since tags are the        
tagger's deterministic fallback (decision D22), this matters for ingest behavior. The same applies in DM scope: a user re-adding a source with different tags — does the new set replace or merge?

Suggested resolution: Add to commands.md: "When /add-source targets an existing (kind, identifier), the tags from the new call replace the existing bootstrap tags. The caller receives a confirmation indicating the source already existed and tags
were updated."
                                                                                                                                                                                                                                                        
---             
[F05] No failure mode specified for translation provider returning garbage

Severity: major
Category: failure-mode                                                                                                                                                                                                                                
Location: docs/spec/llm.md § Failure handling; docs/spec/security.md § Failure handling                                                                                                                                                               
Confidence: high

What the spec says: "Translation — fall back to English with a one-line note (the user should never see a hung response because translation flaked)."

Why it's a problem: "Flaked" covers crashes and timeouts, but not the case where the translation provider returns plausible-looking text in the wrong language, or returns the input text unchanged (a common failure mode of small local models). The
spec says English is the default and translation is presentation-only, but if a Czech-language scope receives an untranslated English reply with no note, the user may think the bot is broken. The spec should distinguish "provider threw" from
"provider returned but the output is suspect."

Suggested resolution: Add: "If the TranslationProvider returns text that fails a source-language-vs-target-language heuristic (or returns the input unchanged), the system falls back to sending the original English text with the same one-line     
note. The heuristic lives in design notes."
                                                                                                                                                                                                                                                        
---             
[F06] StreamSource lifecycle management: no spec-level commitment for graceful shutdown

Severity: major
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/architecture.md § Ingest SPIs; docs/spec/deployment.md
Confidence: high

What the spec says: StreamSource is "started once at Collector startup" with "reconnect with backoff." Per-relay degradation is specified. The Collector can be "redeployed without dropping user conversations."

Why it's a problem: The spec doesn't commit to what happens to active StreamSource connections during Collector shutdown. Does the Collector drain in-flight events before exiting? What's the maximum graceful-shutdown window? If the Collector is  
killed (SIGKILL), are events that arrived on the websocket but haven't been persisted lost? The outbox pattern handles posts already persisted as RAW, but events in the StreamSource buffer between "received from relay" and "persisted as RAW" have
no durability guarantee. An implementer needs to know whether at-least-once or at-most-once is the contract for the StreamSource → outbox handoff.

Suggested resolution: Add to architecture.md: "The StreamSource → outbox handoff is at-least-once: an event received from the relay may be persisted as RAW and then re-delivered after a crash (the dedup by stable upstream id prevents duplicates).
On graceful shutdown, the Collector signals each StreamSource to stop accepting new events and drains in-flight persists. The drain timeout lives in design notes."
                                                                                                                                                                                                                                                        
---             
[F07] /export data completeness is underspecified for groups

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/commands.md § /export                                                                                                                                                                                                             
Confidence: medium

What the spec says: "Group: scoped to the calling (user, group) only — never another user's state, never group-wide content beyond the caller's own contributions."

Why it's a problem: "The caller's own contributions" is ambiguous. Does this mean: (a) the caller's chat_memory and saved_post rows in that group, or (b) the actual messages the caller sent to the group (which would require a group_message_log   
table)? The spec lists group_message_log as a deferred table in MVP but in-scope for v1 in schema.md. If (a), the spec should say so explicitly. If (b), the table must exist. Two engineers would build different things.

Suggested resolution: Clarify in commands.md: "The caller's own contributions in a group /export means: their chat_memory entries, their saved_post entries, and their scope_preferences for that (user, group). It does not include a log of their   
raw messages to the group (that is a group_message_log concern and is not part of /export in v1)."
                                                                                                                                                                                                                                                        
---             
[F08] price_snapshot retention and TTL unspecified

Severity: minor
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/commands.md § Asset commands; docs/spec/schema.md
Confidence: high

What the spec says: "Snapshots are stored in a collector-owned table outside the post pipeline." "TTL by partitioning, not DELETE" is a schema invariant.

Why it's a problem: The schema.md invariants say "TTL by partitioning" for post_reference, post_embedding, and "similar bulk-derived rows." But price_snapshot is not a post-derived table — it's polled on a refresh interval. The spec doesn't say  
whether price_snapshot uses the same partition-drop TTL or has its own retention. If the table grows unbounded (one snapshot per asset per sub-verb per refresh interval), a small deployment could accumulate significant data. The spec also doesn't
say whether the Fetcher SPI's price-snapshot polling reuses the same outbox pattern or has its own write path.

Suggested resolution: Add a line to commands.md or schema.md: "price_snapshot retention is profile-driven and lives in design notes. Old snapshots are aged out by partition drop, consistent with the TTL-by-partitioning invariant. The             
asset-snapshot fetcher writes directly to price_snapshot (no outbox, no eval pipeline) and is scheduled on the profile-driven refresh interval."
                                                                                                                                                                                                                                                        
---             
[F09] Spec mentions /get-tags and /get-sources but they're not in the command catalogue

Severity: minor
Category: inconsistency                                                                                                                                                                                                                               
Location: docs/spec/commands.md § Discovery vs. § Source management                                                                                                                                                                                   
Confidence: high

What the spec says: Under "Discovery": "/get-tags — controlled vocabulary, marking the scope's followed tags" and "/get-sources — alias of /list-sources without --all."

Why it's a problem: /get-sources is described as an alias of /list-sources, but the alias relationship isn't formalized — does the same permission matrix apply? Does /get-sources appear in /help alongside /list-sources or replace it? More        
importantly, /get-tags has no permission specification. The Source management section has /list-sources with --all being bot-admin only, but /get-tags has no equivalent constraint. If /get-tags is available to all users, that's fine, but the spec
doesn't say.

Suggested resolution: Add permission entries for both commands in the catalogue. For /get-sources, state: "Permission: same as /list-sources without --all. Appears in /help as the primary command; /list-sources is the extended form." For         
/get-tags: "Permission: any non-banned user, DM and group."
                                                                                                                                                                                                                                                        
---             
[F10] No spec-level commitment for what happens when the LLM returns structured output in the wrong format

Severity: major
Category: failure-mode                                                                                                                                                                                                                                
Location: docs/spec/security.md § Failure handling; docs/spec/llm.md
Confidence: high

What the spec says: Stage 2 "infrastructure failure (LLM unreachable, timeout, unparseable reply after retry)." Tagger: "1 retry → fallback to source's bootstrap tags."

Why it's a problem: "Unparseable reply" is only specified for Stage 2. For the tagger, the spec says "fallback to bootstrap tags" but doesn't define what triggers the fallback — is it only an exception/timeout, or also a response that doesn't    
match the expected tag format? If the tagger LLM returns a tag that's not in the controlled vocabulary, is that a "tagger failure" (fallback to bootstrap) or silently ignored? The entity extractor has the same gap. An LLM returning plausible but
non-conforming output is the most common real-world failure mode, and it's only partially addressed.

Suggested resolution: Add to the failure handling section: "For the tagger: any output that does not match the controlled vocabulary is treated as a tagger failure (the retry-then-fallback path applies). For the entity extractor: any output that
does not conform to the expected structured schema is treated as an extraction failure (release without artifact). 'Unparseable' includes both exceptions and schema-violating output."
                                                                                                                                                                                                                                                        
---             
[F11] post_reference and topic clustering have no failure-mode story

Severity: minor
Category: failure-mode                                                                                                                                                                                                                                
Location: docs/spec/schema.md; docs/spec/architecture.md                                                                                                                                                                                              
Confidence: high

What the spec says: post_reference holds edges in the cross-source link graph with link_type and score. Topic IDs are "stable only within the periodic-summary cache window." Cross-source linking uses "hybrid: named-entity match (precision) +     
cosine similarity over embeddings (recall)."

Why it's a problem: The cross-source linking pipeline (entity extraction + cosine similarity) runs as part of the ingest pipeline, but the spec doesn't define what happens when it fails. Security.md covers tagger and embedding failures, but      
entity extraction failure is mentioned only as "release without artifact." The post_reference graph builder — which depends on both entity matching and embedding similarity — has no failure mode. If the entity extractor is down, do all new posts
get no cross-source links? Is there a retry? A degraded mode? This is a gap in the failure-handling cascade.

Suggested resolution: Add to security.md failure handling: "Entity extraction failure → release without entities; cross-source linking for that post is degraded (embedding-only similarity, no named-entity match). The post_reference graph builder
runs on whatever artifacts are available; missing entities reduce precision but don't block linking."
                                                                                                                                                                                                                                                        
---             
[F12] /retry for periodic digests: "replaces the cached digest" is ambiguous

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/commands.md § /retry; decisions.md D36
Confidence: medium

What the spec says: "For periodic group digests /retry requires group admin or bot admin and replaces the cached digest (decision D17)."

Why it's a problem: "Replaces" doesn't specify: (a) does /retry re-run the full pipeline (SQL + LLM prose), or (b) does it reuse the deterministic post selection and only re-roll the prose (as with user /summary)? Decision D36 says the latter    
("reusing the deterministic post selection and clustering"). But for a periodic digest, the cached digest was generated at a specific time — if posts have been added since then, reusing the old selection is correct per D19/D36, but the spec
should explicitly say the selection is frozen to the original generation time, not re-queried.

Suggested resolution: Add to commands.md: "/retry for periodic digests reuses the post selection and clustering from the original digest generation (frozen at the original generation time); only the LLM prose is re-rolled. The new prose replaces
the cached digest entry."
                                                                                                                                                                                                                                                        
---             
[F13] scope_preferences table shape: scope_tag rows are mentioned but the entity isn't defined

Severity: minor
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/schema.md § Per-scope state; docs/spec/commands.md § /follow-tag                                                                                                                                                                  
Confidence: high

What the spec says: "Scope tag. Per-scope follow / unfollow preference for digest content." In MVP 00-mvp.md, scope_tag is listed as deferred.

Why it's a problem: The scope_tag entity is referenced in the deferred list and in commands.md (/follow-tag, /unfollow-tag), but it's not defined in schema.md's entity list. The entity section lists "Scope preferences" but doesn't mention        
scope_tag as a separate entity. Is scope_tag a separate table, or rows in scope_preferences? The spec should define the entity even if it's deferred from MVP.

Suggested resolution: Add a scope_tag entry to the schema.md entity list under "Per-scope state": "Scope tag. A (scope, tag) row indicating the scope follows that tag in its periodic digest. Default for a fresh scope is 'all tags from subscribed
sources.' Rows are created/deleted by /follow-tag and /unfollow-tag."
                                                                                                                                                                                                                                                        
---             
[F14] Chat output sanitizer scope: does it run on periodic digest output?

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/security.md § Chat output sanitizer
Confidence: medium

What the spec says: "Before any chat-mode reply is sent, the candidate text is passed through a deterministic outbound regex pass that strips or refuses replies containing admin command strings."

Why it's a problem: The sanitizer is described as running on "chat-mode replies." Periodic group digests are not chat-mode replies — they're scheduled, push-based output. If the LLM prose in a digest happens to contain a string that looks like an
admin command (e.g., a post about a tool called /ban), does the sanitizer run? If not, a malicious post body that survived Stage 1/2 could inject plausible admin commands into a digest. The spec should clarify whether the sanitizer runs on all
outbound LLM-generated text or only chat-mode replies.

Suggested resolution: Change the sanitizer scope: "Before any LLM-generated text is sent to a user (chat-mode replies, /summary output, periodic digest prose), the candidate text is passed through the deterministic outbound regex pass."
   
---                                                                                                                                                                                                                                                   
[F15] No spec commitment for what happens when two providers run simultaneously

Severity: major
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/architecture.md; docs/spec/deployment.md                                                                                                                                                                                          
Confidence: high

What the spec says: "Independent scaling. Ingest load and user load move on different schedules and benefit from independent process boundaries."

Why it's a problem: The spec implies multiple Collector or Provider instances could run (independent scaling), but doesn't address the consequences. If two Provider instances run: (a) who processes the periodic group digest — both? (b) who       
handles the LISTEN/NOTIFY catch-up — both? (c) /stop is per-(user, scope) — does it cancel across instances? If two Collector instances run: (d) both run the fetch scheduler — do they double-poll? (e) both run the outbox rehydrator — do they
double-process? The LISTEN/NOTIFY model works for push but the scheduler and periodic digest are singleton concerns. The spec should either commit to single-instance-per-service or define the multi-instance contract.

Suggested resolution: Add to architecture.md: "v1 assumes one Collector instance and one Provider instance. The fetch scheduler, outbox rehydrator, periodic digest scheduler, and LISTEN/NOTIFY listener are singleton concerns. Running multiple    
instances of either service is unsupported in v1 and would require leader election or partitioning, which are deferred."
                                                                                                                                                                                                                                                        
---             
[F16] EmbeddingProvider SPI: no contract for vector dimensionality

Severity: minor
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/llm.md § SPI shape; docs/spec/schema.md                                                                                                                                                                                           
Confidence: high

What the spec says: "One embedding per post (title + summary, by convention)." "The embedding model is chosen per profile and must not change for an existing deployment without a re-embed plan, because vectors from different models are not       
comparable."

Why it's a problem: The spec commits that changing the embedding model invalidates vectors, but doesn't specify how the system detects or prevents a dimensionality mismatch. If an operator switches from a 384-dim model to a 768-dim model without
re-embedding, existing pgvector queries will either error or return garbage. The spec should commit to either (a) a startup check that the configured model's dimension matches the pgvector column, or (b) storing the dimension alongside the vector
and validating at query time.

Suggested resolution: Add to llm.md: "On startup, the embedding provider reports its output dimensionality. The system validates this against the pgvector column dimension; a mismatch refuses startup with a clear error message and a reference to
the re-embed procedure."
                                                                                                                                                                                                                                                        
---             
[F17] /save cap per user is mentioned but not bounded

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/decisions.md D13; docs/spec/schema.md                                                                                                                                                                                             
Confidence: high

What the spec says: Decision D13: "/save semantics: Per-user only… capped per user."

Why it's a problem: "Capped per user" is a spec-level commitment but the cap value is not stated — not even as a range or order of magnitude. Is it 100? 10,000? The cap affects schema design (whether an index on (user_id) is sufficient or        
partitioning is needed) and UX (what error message the user gets when they hit the cap). The spec should at least commit to whether the cap is profile-driven, a fixed constant, or operator-configurable.

Suggested resolution: Add to commands.md: "The per-user /save cap is profile-driven (value in design notes). Hitting the cap produces a friendly error suggesting /unsave to free space."
   
---                                                                                                                                                                                                                                                   
[F18] Verification.md doesn't test the degraded Stage-2-failure release path end-to-end

Severity: major
Category: verification                                                                                                                                                                                                                                
Location: docs/spec/verification.md § Security
Confidence: high

What the spec says: "Stage 2 infrastructure failure: fake LLM throws; post is released as READY with redactions retained, stage2_failed=true, throttled admin notify; the periodic re-eval job picks it up when the LLM recovers."

Why it's a problem: The verification entry tests that the post is released with redactions. But it doesn't test the re-evaluation path — when the LLM recovers, the periodic re-eval job should re-run Stage 2 on stage2_failed=true posts. This is a
spec commitment (security.md: "mark the post for re-evaluation when the LLM returns") but it's not in the verification matrix. If the re-eval job is buggy or never implemented, posts with prompt-injection content would remain permanently in the
degraded release state.

Suggested resolution: Add a verification entry: "Stage 2 re-evaluation: a post released with stage2_failed=true is re-evaluated when the LLM becomes available; if the re-eval returns INJECTION/MALWARE, the post transitions to QUARANTINED and a   
NOTIFY is sent."
                                                                                                                                                                                                                                                        
---             
[F19] Spec doesn't define the Fetcher SPI contract for pagination or incremental fetching

Severity: minor
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/architecture.md § Ingest SPIs
Confidence: medium

What the spec says: "The fetcher is stateless between ticks; 'what's new since last time' is a query against posts, not in-memory state."

Why it's a problem: For RSS this is fine (dedup by URL). But for sources like Bluesky or Reddit, the API may return paginated results. The spec says the fetcher is stateless between ticks and dedup is against posts, but doesn't commit to whether
the Fetcher SPI supports pagination (returning a page token) or must fetch everything in one call. If a source has more new posts than a single API page, an implementer needs to know whether to paginate within a single tick or defer to the next
tick. This affects freshness and API rate limits.

Suggested resolution: Add to architecture.md: "The Fetcher SPI returns a list of normalized posts per tick. If the upstream API is paginated, the implementation fetches all pages within a single tick (bounded by a per-source max-page cap in      
design notes). Dedup against posts ensures idempotency across ticks."
                                                                                                                                                                                                                                                        
---             
[F20] bootstrap-assets.json loaded by Collector but asset commands served by Provider

Severity: minor
Category: smell                                                                                                                                                                                                                                       
Location: docs/spec/deployment.md § Bootstrap behavior; docs/spec/commands.md § Asset commands
Confidence: high

What the spec says: "Collector loads the bootstrap assets file if configured and upserts the per-asset enabled-sub-verb allowlist." But asset commands (/zcash, /monero) are served by the Provider.

Why it's a problem: The Collector loads the asset configuration, but the Provider needs to know which assets and sub-verbs are enabled to route commands and render /help. The spec doesn't define how the Provider learns the asset allowlist.       
Options: (a) the Provider reads the same bootstrap-assets.json on startup (but the spec says the Collector loads it), (b) the Provider reads from the DB (where the Collector wrote it), (c) both services load the file independently. The current
spec implies (b) via the shared DB, but doesn't state it. This is a hidden coupling that could confuse implementers.

Suggested resolution: Add to deployment.md: "The Provider reads the asset enablement and sub-verb allowlist from the database (written by the Collector's bootstrap loader). Both services do not need to read the same file independently."
   
---                                                                                                                                                                                                                                                   
[F21] /follow-tag default behavior for existing scopes is unspecified

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/commands.md § Per-scope tag preferences
Confidence: medium

What the spec says: "Default for a fresh scope is 'all tags from subscribed sources' (decision D15)."

Why it's a problem: The spec defines the default for a fresh scope but doesn't define what happens when a new source is added to an existing scope. If a scope follows no explicit tags (default = all), and a new source with tag crypto is added,   
the scope should implicitly include crypto in its digest. But if the scope has explicitly followed tags (e.g., only tech), does adding a source with crypto tags change anything? The spec should clarify whether "all tags from subscribed sources"
is a dynamic default (recomputed on subscription change) or a one-time snapshot.

Suggested resolution: Add: "The 'all tags from subscribed sources' default is dynamic: it is recomputed on every periodic digest based on the current subscription set. A scope with explicit /follow-tag entries uses only those entries regardless  
of subscription changes."
                                                                                                                                                                                                                                                        
---             
[F22] No commitment on ordering of /retry relative to /stop

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/commands.md § /retry; docs/spec/decisions.md D36
Confidence: medium

What the spec says: D36: "No eligible anchor (or anchor cleared, or prior command cancelled by /stop) → friendly error."

Why it's a problem: The spec says /retry after /stop on the same command produces a friendly error. But what about /stop during a /retry? If the user issues /summary, then /retry, and then /stop while the /retry LLM call is in flight — does /stop
cancel the /retry? Does the anchor survive? The spec says /stop cancels "the calling (user, scope)'s in-flight interruptible request" and /retry re-rolls the LLM prose, which is interruptible. But D36 says the anchor is cleared by "any
non-/retry input" — /stop is a non-/retry input, so it should clear the anchor. This creates a contradiction: the /stop cancels the /retry (good) but also clears the anchor (so a second /retry fails). This is arguably correct behavior but should
be stated explicitly.

Suggested resolution: Add to commands.md: "/stop cancels an in-flight /retry and clears the retry anchor (since /stop is a non-/retry input). A subsequent /retry returns a friendly error."
   
---                                                                                                                                                                                                                                                   
[F23] Verification matrix doesn't cover the onboarding → /add-source → /summary happy path as a single flow

Severity: minor
Category: verification                                                                                                                                                                                                                                
Location: docs/spec/verification.md
Confidence: high

What the spec says: The verification matrix tests individual invariants (permissions, banned-user intake, confirmation tokens, etc.) and the MVP exit criteria test the end-to-end flow.

Why it's a problem: The MVP exit criteria are gated on 00-mvp.md §6, but the v1 spec adds many more commands and features. The verification matrix tests invariants in isolation but doesn't commit to testing the most common user flow end-to-end:  
new user → onboarding → /add-source → /summary → /save → /saved → /retry. This integration-level flow test would catch regressions in the interaction between onboarding, subscription, summarization, and saving. The MVP smoke covers a subset, but
the v1 additions (groups, /save, /retry, /lang) are only tested as isolated invariants.

Suggested resolution: Add a verification entry: "V1 happy-path integration test: new user onboarding → /add-source → /summary → /save → /saved → /retry → /forget. Covers the interaction between registration, subscription, summarization, saving,  
retry, and purge."
                                                                                                                                                                                                                                                        
---             
[F24] TranslationProvider SPI contract: no specification for handling unsupported language codes

Severity: minor
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/llm.md § Translation flow; docs/spec/commands.md § /lang
Confidence: high

What the spec says: "v1 ships English + Czech" and "TranslationProvider SPI; English by default; opt-in per-scope language via /lang."

Why it's a problem: The spec doesn't define what happens when a user issues /lang fr (French) in v1. Is it a friendly error ("French is not supported"), or does it silently set the language and then fall back to English on every reply (with the  
one-line note)? The SPI shape says TranslationProvider is pluggable, but the v1 contract should define behavior for unsupported codes.

Suggested resolution: Add to commands.md: "/lang <code> rejects unsupported language codes with a friendly error listing the available codes. The set of supported codes is determined by the configured TranslationProvider implementations."
   
---                                                                                                                                                                                                                                                   
[F25] No commitment on whether post.embedding is nullable in the READY state

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/schema.md; docs/spec/llm.md § Embedding pipeline
Confidence: high

What the spec says: "On failure (after one retry), the post is released without a vector and is excluded from semantic linking."

Why it's a problem: The spec says a post can be READY without an embedding, but schema.md's entity description says the Post "carries status… and Tier-1 tags" without mentioning that embedding is nullable. An implementer might design the schema  
with embedding NOT NULL and then be surprised when the embedding pipeline fails. The invariant "TTL by partitioning" mentions post_embedding as a separate entity — is the embedding in the same table or a separate table?

Suggested resolution: Add to schema.md: "post.embedding (or the post_embedding row) is nullable; a READY post may lack an embedding if the embedding pipeline failed. Queries for semantic linking filter WHERE embedding IS NOT NULL."
   
---                                                                                                                                                                                                                                                   
[F26] group_message_log mentioned in schema but purpose is never defined

Severity: minor
Category: gap                                                                                                                                                                                                                                         
Location: docs/spec/schema.md § Identity and access
Confidence: high

What the spec says: group_message_log is listed in the deferred table list in 00-mvp.md and referenced in schema.md's entity list (under "Identity and access" → Group membership).

Why it's a problem: The entity is named but never described. What does it log? Every message sent to the group? Only messages that @mention the bot? Who reads it? Is it for /export, for audit, for the periodic digest? Without a definition, two   
engineers would build different schemas and different retention policies. This is a gap in the entity catalog.

Suggested resolution: Add a one-line entity description in schema.md: "Group message log. Records messages sent by the bot to a group (outbound only, not user messages). Used for audit and /retry anchor resolution. Retention is profile-driven."
   
---                                                                                                                                                                                                                                                   
[F27] EmbeddingProvider batch semantics unspecified

Severity: minor
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/llm.md § SPI shape                                                                                                                                                                                                                
Confidence: medium

What the spec says: "EmbeddingProvider — text → vector batch."

Why it's a problem: "Batch" implies the provider can embed multiple texts in one call, but the spec says "one embedding per post." If the provider supports batching, does the eval pipeline batch multiple posts into a single embedding call? This  
affects throughput and the failure contract — if one text in a batch fails, does the whole batch fail? The SPI shape should clarify whether batching is a provider capability (optional optimization) or a pipeline commitment.

Suggested resolution: Add to llm.md: "The EmbeddingProvider SPI accepts a batch of texts and returns a vector per text. The eval pipeline may batch multiple posts in a single call; a failure on one text fails the entire batch (the pipeline       
retries the batch). The batch size is profile-driven."
                                                                                                                                                                                                                                                        
---             
[F28] /list-sources --all permission: bot-admin only, but the spec doesn't say what "all" means

Severity: nit
Category: ambiguity                                                                                                                                                                                                                                   
Location: docs/spec/commands.md § /list-sources
Confidence: medium

What the spec says: "/list-sources [--all] [--page N] — sources subscribed by the calling scope; --all is bot-admin only."

Why it's a problem: "All" could mean: (a) all sources globally (including soft-deleted), (b) all non-deleted sources globally, (c) all sources including those the admin's scope isn't subscribed to. The soft-delete contract says sources are never
hard-deleted, so "all" should probably exclude soft-deleted sources unless --include-deleted is also specified. But the spec doesn't clarify.

Suggested resolution: Add: "--all shows all non-deleted sources globally. Soft-deleted sources are excluded unless a future --include-deleted flag is added."
   
---                                                                                                                                                                                                                                                   
3. Cross-cutting Observations

Failure handling is consistently underspecified for downstream pipeline stages. Security.md has excellent coverage for Stage 1/2 failures (F10, F18), but later stages (tagger, entity extraction, embedding, cross-source linking, translation) have
progressively thinner failure stories. The tagger fallback to bootstrap tags is well-defined, but entity extraction (F11), translation garbage (F05), and embedding dimensionality mismatch (F16) are gaps. The pattern is: "LLM fails → fallback" but
"LLM returns wrong-shaped output" is only addressed for Stage 2.

The determinism boundary is stated in principle but underspecified in practice. It's declared in llm.md, architecture.md, and decisions.md (D19), but the concrete guarantees are ambiguous: no temporal bound on "same query → same results" (F02),  
no clarity on whether /retry re-queries or reuses (F2, F12), and the interaction with concurrent writes is unspecified.

Spec/design layering is excellent. I found no cases of implementation details (regex, retry counts, property keys) leaking into spec files. The "What lives in design notes" trailers at the end of each spec file are consistently present and       
correctly scoped. This is the strongest aspect of the spec.

Multi-instance / scaling story is a blind spot. The spec mentions "independent scaling" as a reason for the service split (architecture.md) but then doesn't address the consequences for singleton concerns like schedulers, LISTEN/NOTIFY, and      
periodic digests (F15). This will bite anyone trying to run more than one instance of either service.

The scope_tag entity is orphaned. It's mentioned in commands.md and deferred in 00-mvp.md, but never defined in the schema entity catalog (F13). This is a minor gap but indicative of the entity catalog being slightly out of date with the command
catalogue.
                                                                                                                                                                                                                                                        
---             
4. Spec Evaluation

Completeness — 7/10. The spec covers the happy path thoroughly and has excellent threat-model coverage. The main gaps are in failure modes for later pipeline stages (F05, F10, F11), multi-instance concerns (F15), and a few undefined entities
(F13, F26). An engineer could build 90% of the system from this spec; the remaining 10% requires design decisions that the spec should own.

Consistency — 8/10. Cross-file references are generally accurate. The decisions.md entries match how they're described in section files. The main inconsistencies are minor: /get-tags and /get-sources appearing in the discovery section without    
full permission specs (F09), and the post.embedding nullability being implied but not stated (F25).

Implementability — 7/10. The spec is clear enough to start building, but several ambiguities would force an implementer to make architectural decisions: the catch-up vs. NOTIFY ordering (F01), the multi-instance contract (F15), the /add-source   
tag conflict resolution (F04), and the Fetcher pagination contract (F19). Each of these is a "two engineers would build different things" situation.

Testability — 8/10. The verification.md matrix is comprehensive and maps well to spec commitments. The main gap is the missing re-evaluation test (F18) and the missing v1 happy-path integration flow (F23). The MVP exit criteria are well-defined  
and testable.

Evolvability — 8/10. The spec/design split is the strongest architectural decision in the document set. The SPI-based approach for LLM, embedding, translation, messaging, and ingest means adding new implementations is well-defined. The most      
likely leak point is the price_snapshot table (F08, F20) — it sits outside the post pipeline but its lifecycle isn't fully integrated into the spec's existing invariants.
                                                                                                                                                                                                                                                        
---             
5. Pros and Cons

Pros:
- The spec/design layering is exemplary — "What lives in design notes" trailers keep implementation details out of commitments.
- The threat model is concrete (T1–T9 catalogued in design, spec-level commitments cover them) and the trust boundaries are clearly drawn.
- The determinism boundary is a powerful architectural commitment that simplifies reasoning about the system.
- Decision D38 (Nostr ingestion) is unusually thorough for a v1 addition: kind allowlist, signature verification, forever-read-only, per-relay degradation, cross-relay dedup.
- The failure-handling cascade (verdict vs. infrastructure split) is well-designed and prevents the common "LLM down → system down" failure mode.
- The verification matrix is directly traceable to spec commitments — no orphaned tests, no untested commitments (except F18, F23).

Cons:
- Failure modes are front-loaded (Stage 1/2 excellent) but thin for later pipeline stages (tagger output validation, entity extraction, translation, embedding dimensionality).
- The multi-instance / scaling story is completely absent despite the spec citing "independent scaling" as a design motivation.
- Several entities are named but never defined (scope_tag, group_message_log), creating ambiguity for schema implementers.
- The determinism boundary's temporal scope is unspecified — "same query → same results" is vacuous without a snapshot-isolation or caching commitment.
- Asset commands (/zcash, /monero) add significant surface area but the cross-service bootstrap flow (Collector loads config, Provider serves commands) has a hidden coupling that isn't explicitly committed.

  ---                                                                                                                                                                                                                                                   
6. Recommended Next Actions

1. F01 — Define the catch-up vs. NOTIFY ordering contract. This is the most architecturally consequential gap. An implementer who gets this wrong will have duplicate or missed processing. Fix: add a paragraph to architecture.md defining
   idempotent processing and same-transaction high-water mark advancement.
2. F15 — Commit to single-instance-per-service in v1. This eliminates an entire class of ambiguity (scheduler conflicts, LISTEN/NOTIFY duplication, /stop cross-instance). Fix: add one sentence to architecture.md. If multi-instance is desired
   later, it becomes a spec amendment.
3. F02 — Clarify the determinism boundary's temporal scope and /retry's caching model. This affects the /retry implementation and the meaning of "deterministic." Fix: add a paragraph to llm.md clarifying that determinism means "same DB state →
   same results" and /retry reuses cached selection.
4. F10 — Extend the "unparseable reply" contract to all LLM-consuming stages. The tagger, entity extractor, and translator all receive LLM output that could be non-conforming. Fix: add a paragraph to the failure-handling section covering
   schema-violating output.
5. F06 — Define the StreamSource → outbox handoff contract (at-least-once vs. at-most-once) and graceful shutdown behavior. Fix: add a paragraph to architecture.md.
6. F03, F04, F07, F09, F13, F24, F26 — Fill the remaining entity and command gaps. These are smaller fixes (a sentence or two each) that prevent implementer confusion. Prioritize F03 (group admin race UX) and F04 (tag conflict resolution) as they
   affect the most common user flows.