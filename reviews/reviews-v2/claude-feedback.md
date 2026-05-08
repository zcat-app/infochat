  ---                                                                                                                                            
  🔴 Critical — fix before implementation                                                                                                        
                                                                                                                                                 
  F1. Provider has no startup reconciliation for missed NOTIFY new_post events                                                                   
                                         
  (Kimi §1.3 = Mimo C2)                    
                                           
  Verdict: FIX. Both reviewers caught this and they're right. The spec proudly notes "outbox provides durability, NOTIFY provides push" but the  
  durability guarantee only protects the Collector side. If the Provider is down when NOTIFY new_post fires, the event is gone — Postgres        
  LISTEN/NOTIFY explicitly does not buffer for disconnected listeners.                                                                           
                                                                                                                                                 
  Why their fix is good: A provider_high_water_mark (or just last_seen_post_id / last_seen_ready_at per provider instance) plus a startup query
  is ~10 lines and closes the gap completely. Mimo's variant (include fetched_at in the NOTIFY payload, track high-water mark) is slightly       
  cleaner than Kimi's provider_seen_posts table because it avoids a new write path on every event.
                                                                                                                                                 
  Proposed spec edit (01-architecture.md §1.5 + 02-schema.md §2.8):
  - Add a provider_state table with (provider_instance, last_ready_post_at TIMESTAMPTZ).                                                         
  - @Startup Provider hook runs SELECT id FROM post WHERE status='READY' AND ready_at > :last_ready_post_at ORDER BY ready_at and feeds those
  into the same handler the NOTIFY listener uses.                                                                                                
  - Document that NOTIFY is best-effort push; the high-water mark is the correctness guarantee.
                                                                                                                                                 
  F2. Chat agent has no post-LLM output filter for admin commands
                                                                                                                                                 
  (Kimi §4.3, §8.2)                        
                                                                                                                                                 
  Verdict: FIX. The spec explicitly admits the Provider runs no Stage 1 regex on chat input ("…that lives only in the Collector ingest path").   
  The system prompt is asked to refuse with [refused-action], but this is advisory, not enforced — small models on the Pi profile are easily     
  jailbroken into emitting /grant-admin <id> in a chat reply.                                                                                    
                                                                                                                                                 
  The good news: this is a one-screen fix. Admin actions are dispatched by CommandRouter, not by the LLM. If a user copy-pastes a command from
  chat output, the router still requires is_admin=true — so the actual escalation surface is narrow. But the chat reply itself can still be a    
  vector for social engineering ("hey @victim, the bot just told me to run /grant-admin abc, please confirm"). A deterministic outbound scrubber
  on ChatAgent.respond() is cheap.                                                                                                               
   
  Proposed spec edit (04-security.md §4.4 + 08-verification.md §8.4.11):                                                                         
  - Add §4.4.X "Chat output sanitizer": before delivering any chat-mode reply, run a regex pass
  ((^|\s)/(grant-admin|demote|ban|unban|remove-source|promote)\b) and either strip the matched span or refuse the entire reply with
  [refused-action]. Audit-log every match.                                                                                                       
  - Verification test: FakeLlmProvider returns "Sure! Here's the command: /grant-admin abc123"; assert reply is sanitized and audit row is
  written.                                                                                                                                       
                                         
  I'd push back on Kimi's secondary suggestion ("add a Stage 1.5 language detector"). That's higher complexity for marginal benefit — once you   
  have an output filter, multilingual injection that succeeds still can't produce executable commands.
                                                                                                                                                 
  F3. chat_session.messages JSONB array does not scale
                                                                                                                                                 
  (Kimi §2.2 = Mimo S5)                  
                                                                                                                                                 
  Verdict: FIX. This is a textbook anti-pattern. JSONB array append in Postgres is a full row rewrite — read entire blob, deserialize, append,
  re-serialize, write. With auto-compress at 75% of context, sessions routinely sit at 50–100 messages × ~2KB each. Every chat reply triggers a  
  TOAST round-trip.                      
                                                                                                                                                 
  The fix is mechanical: a chat_message child table keyed by (session_id, seq), with chat_session holding only metadata + token_count (maintained
   by trigger or app code). Append is O(1). /clear becomes one DELETE. Token counting becomes a SUM.                                             
                                         
  Mimo's "maybe just enforce auto-compress harder" is the weaker option — it papers over the underlying O(n) cost rather than fixing it. Kimi's  
  version is correct.                      
                                                                                                                                                 
  Proposed spec edit (02-schema.md §2.6): Replace the chat_session.messages JSONB column with a separate chat_message(session_id, seq, role,     
  content, tokens, ts) table; keep chat_session.token_count as a denormalized counter updated by trigger.
                                                                                                                                                 
  F4. Stage 1 prompt-injection regex is bypassable                                                                                               
                                           
  (Kimi §4.2 = Mimo S1, partly)                                                                                                                  
                                                                                                                                                 
  Verdict: PARTIAL FIX (mostly clarify). Kimi is technically right that regex can't catch multilingual or paraphrased injection. But the spec
  already concedes Stage 2 is the real boundary. The fix here is mostly documentation honesty, not a new defense layer.                          
                                         
  I'd push back on Kimi's "Stage 1.5 language detector" — language detection libraries are themselves attackable, and the marginal lift is small 
  once F2 (output sanitizer) is in place.  
                                                                                                                                                 
  Proposed spec edit (04-security.md §4.2): Add a paragraph after the regex list explicitly stating: "Stage 1 is a coarse filter, not a complete 
  defense. It catches naive English-language injection patterns to (a) reduce Stage 2 load, (b) provide a degraded mode when the judge is 
  offline. Stage 2 is the actual security boundary. Multilingual, paraphrased, and encoded injection bypasses Stage 1 by design."                
                                                                                                                                                 
  Mimo's S1 (Stage 2-down auto-release) is a related but separate concern → see F8.
                                                                                                                                                 
  ---                                    
  ⚠️  High — fix in v1                                                                                                                            
                                         
  F5. Docker-compose 'changeme' passwords baked into init scripts                                                                                
                                         
  (Kimi §7.1)                                                                                                                                    
   
  Verdict: FIX. Literal 'changeme' in checked-in init SQL is a footgun even with the disclaimer above it. Devs copy-paste, the password ends up  
  in someone's lab VPS, and now there's a SUPERUSER role with a known password.
                                                                                                                                                 
  Proposed spec edit (07-deployment.md §7.7): Replace literal passwords with ${INFOCHAT_DB_PASSWORD:?}, ${INFOCHAT_COLLECTOR_PASSWORD:?},        
  ${INFOCHAT_PROVIDER_PASSWORD:?} — Postgres init substitutes from env, and :? makes Postgres refuse to start if unset. docker-compose.yml uses
  ${VAR:-$(openssl rand -hex 24)} for the dev default. This is one paragraph of spec change.                                                     
                                                                                                                                                 
  F6. systemd unit missing User=
                                                                                                                                                 
  (Kimi §7.2)                            

  Verdict: FIX. Trivial — add User=infochat, Group=infochat, and NoNewPrivileges=yes to the [Service] block. Also worth adding
  ProtectSystem=strict, ProtectHome=true, PrivateTmp=true since we're already there.

  F7. Saved-post pruning NOT IN (SELECT...) defeats fetched_at index                                                                             
                                           
  (Mimo S3)                                                                                                                                      
                                                                                                                                                 
  Verdict: FIX. Mimo is right. NOT IN (SELECT post_id FROM saved_post) either degrades to a hash anti-join (full scan of post) or sequential
  lookup per row. With a partitioned post table this is especially bad because the planner can't prune partitions by fetched_at while it's       
  checking the subquery membership.      
                                                                                                                                                 
  Proposed spec edit (02-schema.md §2.6): Add post.is_saved BOOLEAN NOT NULL DEFAULT false maintained by a trigger on saved_post insert/delete.
  Pruner becomes DELETE FROM post WHERE fetched_at < ... AND is_saved = false. Add a partial index on (fetched_at) WHERE is_saved = false.       
                                         
  This also subsumes Kimi §2.5 (1000-save-cap trigger COUNT(*) is expensive) — once we're maintaining counters via triggers, denormalize the     
  per-user save count to users.save_count as he suggests.
                                                                                                                                                 
  F8. Stage 2 down → auto-release is too permissive                                                                                              
                                           
  (Mimo S1)                                                                                                                                      
                                                                                                                                                 
  Verdict: FIX (config-gated). Right now if the judge LLM is dead, every post sails through with only Stage 1 redactions. That's exactly when you
   most want fail-closed behavior, not fail-open. Mimo's compromise is right: keep current behavior as default for laptop/Pi profiles where      
  keeping the bot useful matters, but require explicit opt-in for production.
                                                                                                                                                 
  Proposed spec edit (04-security.md §4.7): Add infochat.security.release-on-stage2-failure=true|false. Default true for laptop/pi profiles,
  false for vps/remote. When false, posts with stage2_failed=true stay QUARANTINED until the re-evaluation job clears them. Also: log the        
  released-with-stage1-only count as a Prometheus counter so operators can see the degradation.
                                                                                                                                                 
  F9. /q/health/ready doesn't probe the LLM
                                                                                                                                                 
  (Kimi §7.4)                            
                                                                                                                                                 
  Verdict: FIX (with care). Adding an LLM round-trip to readiness is correct in principle but dangerous to misconfigure. If the readiness check
  times out aggressively, a slow LLM flips the pod to NotReady and Kubernetes restarts the bot — masking, not fixing, the problem.               
                                         
  Proposed spec edit (07-deployment.md §7.12): Add a separate /q/health/llm endpoint that probes the LLM (5s timeout, trivial prompt). It is not 
  part of /ready — /ready keeps DB + adapter only. /health/llm is wired to a Prometheus alert (alert: LlmDown for: 5m), not to orchestrator
  health. This catches the "Ollama silently dead" case Kimi describes without creating a restart loop.                                           
                                                                                                                                                 
  F10. SimpleX session token has no rotation / fail-fatal
                                                                                                                                                 
  (Mimo S6)                              
                                                                                                                                                 
  Verdict: FIX (small). The reconnect-forever-after-revocation behavior is a real bug — once the token is revoked, the adapter will hammer the
  WebSocket forever, filling the log and burning CPU.                                                                                            
                                         
  Proposed spec edit (06-messaging.md §6.4.1 + 07-deployment.md §7.15):                                                                          
  - Distinguish auth failures (401-equivalent close codes) from network failures.
  - After 3 consecutive auth failures, the adapter transitions to state=AUTH_FAILED (terminal), reports unhealthy, and stops reconnecting until  
  restart.                                                                                                                                       
  - Add metric adapter.simplex.auth.fail separate from the existing adapter.identity.assert.fail (which is per-message, not session-auth).
                                                                                                                                                 
  F11. external_id length unbounded      
                                                                                                                                                 
  (Mimo S4)                                
                                                                                                                                                 
  Verdict: FIX (one line). CHECK (length(external_id) <= 2048). RSS GUIDs are usually URLs <256 chars; 2KB is generous. Beyond that, hash and    
  store the digest. This is a 1-line schema change with real DoS protection — the cheapest fix in either report.                                 
                                                                                                                                                 
  F12. Group-admin auto-promote race                                                                                                             
   
  (Mimo S2)                                                                                                                                      
                                           
  Verdict: FIX. Two simultaneous @mention messages can both pass the "no admin yet" check before either INSERT lands. Easy to fix with a partial
  unique index.                                                                                                                                  
   
  Proposed spec edit (04-security.md §4.4):                                                                                                      
  CREATE UNIQUE INDEX one_admin_per_group  
    ON group_membership (group_id) WHERE is_group_admin = true;                                                                                  
  The bootstrap path becomes INSERT … ON CONFLICT DO NOTHING. Whichever transaction commits first wins; the loser silently no-ops. Cleaner than  
  SELECT FOR UPDATE.                       
                                                                                                                                                 
  ---                                                                                                                                            
  ⚠️  Medium — fix in v1 if cheap, defer otherwise
                                                                                                                                                 
  F13. LinkingJob processes the full 4-day window every run
                                                                                                                                                 
  (Kimi §1.4)                                                                                                                                    
   
  Verdict: FIX (small). Add post.last_linked_at TIMESTAMPTZ and only process posts where last_linked_at IS NULL OR last_linked_at < fetched_at.  
  The bidirectional-write decision (Kimi §2.3) interacts with this — you still need to consider both directions, but the driving set shrinks to
  "new since last run."                                                                                                                          
                                                                                                                                                 
  I'd push back on Kimi §2.3 (eliminate bidirectional post_reference rows). The spec deliberately picked simpler queries over storage savings;
  with 4-day retention on post_reference and partitioned storage, the doubling is bounded and recursive-CTE walks are noticeably slower than     
  index lookups in both directions. Keep current design; document the tradeoff explicitly.
                                                                                                                                                 
  F14. UNKNOWN-rate threshold            
                                                                                                                                                 
  (Kimi §4.5)                            
                                                                                                                                                 
  Verdict: PUSH BACK. Kimi suggests auto-downgrading UNKNOWN to BENIGN if the rate exceeds 20%. This is the wrong direction. A high UNKNOWN rate
  means the judge is broken — auto-releasing content because the security check is degraded is exactly the failure mode F8 already worries about.
                                         
  The right response is the opposite: monitor and alert (Prometheus counter eval.stage2.unknown_total, alert if rate > 20% over 1h), but do not  
  auto-release. Keep the fail-closed QUARANTINED policy.
                                                                                                                                                 
  Proposed spec edit (04-security.md §4.7): Add the alert spec. Reject the auto-downgrade.                                                       
                                           
  F15. Tagger JSON robustness on small models                                                                                                    
                                                                                                                                                 
  (Kimi §5.3)                            
                                                                                                                                                 
  Verdict: ALREADY-ADDRESSED, partial clarification. Spec already has JSON_MODE capability and a 1-retry → fallback. What's missing is what Kimi
  actually nails: the retry uses the same prompt and will produce the same garbage. Two-line addition.                                           
                                         
  Proposed spec edit (05-llm-and-embeddings.md §5.4.2): Specify that the retry uses a simplified prompt (prompts/tagger-fallback.md) requesting  
  TAGS: tag1, tag2 line format, parsed by regex. This buys reliability on llama3.2:1b without requiring JSON mode.
                                                                                                                                                 
  F16. Cosine threshold 0.18 hardcoded                                                                                                           
                                           
  (Kimi §5.6)                                                                                                                                    
                                                                                                                                                 
  Verdict: FIX (config exposure). Turn it into infochat.linking.semantic-threshold with profile-specific defaults in §5.7's table. One-line
  config addition.                                                                                                                               
                                         
  F17. Chat-mode rate limit too high relative to LLM cost                                                                                        
                                           
  (Kimi §3.4 §4.6 = Mimo C1)                                                                                                                     
                                                                                                                                                 
  Verdict: FIX (combined). The reviewers converge here. Both note that 60 chat/min × tool calls on a Pi with 1 LLM slot creates massive backlog.
  Mimo's tool-call budget per turn complements Kimi's per-minute LLM cap.                                                                        
                                         
  Proposed spec edit (04-security.md §4.9):                                                                                                      
  - New row in rate-limit table: LLM-triggering ops (chat + summary) at 10/min per user (per profile: 5/min on Pi).
  - New constraint: max-tool-calls-per-turn = 5 for the chat agent. Exceeding it returns "I've hit my tool-use budget for this turn — please ask 
  a more specific question."                                                                                                                     
  - Cache tool results within a single conversation turn (Mimo's suggestion).
                                                                                                                                                 
  F18. Connection pool exhaustion        
                                                                                                                                                 
  (Mimo C3)                                
                                                                                                                                                 
  Verdict: FIX. Both services share the spec's single quarkus.datasource.jdbc.max-size=20. Provider holds a connection across LLM calls          
  (potentially 5–30 seconds), so under 10 concurrent chats it can starve the Collector's writes.                                                 
                                                                                                                                                 
  Proposed spec edit (07-deployment.md §7.4):                                                                                                    
  - Per-service: quarkus.datasource.jdbc.max-size defaults provider=30, collector=15.
  - Document explicitly: "The Provider must release the JDBC connection before invoking any LLM call. The pattern is: load context → close       
  connection → call LLM → reopen for write." Add a verification test (08).
                                         
  F19. Bounded chat memory                                                                                                                       
   
  (Kimi §1.5)                                                                                                                                    
                                           
  Verdict: FIX (small). Cap at 200 entries per (user, scope); LRU evict on insert. Mirrors the existing 1000-save cap. Documented as v1, not v2. 
                                                                                                                                                 
  F20. Confirmation cancelled silently   
                                                                                                                                                 
  (Kimi §3.5)                            
                                                                                                                                                 
  Verdict: FIX (UX). One-line behavior change: when pending confirmation is cancelled by a non-confirm input, reply Pending /clear cancelled.
  Cheap, prevents a real footgun.                                                                                                                
                                         
  F21. /summary no-args footgun                                                                                                                  
                                           
  (Kimi §3.3)                                                                                                                                    
                                                                                                                                                 
  Verdict: CLARIFY. Spec already caps results via cluster cap. The UX problem is real but doesn't require a redesign — top-3-by-activity is
  sensible.                                                                                                                                      
                                         
  Proposed spec edit (03-commands.md §3.4): When /summary runs with no tag and >5 followed tags exist, restrict to the 3 most-active tags in the 
  window and prepend: Showing top 3 of N followed tags. Use /summary <tag> for specific topics.
                                                                                                                                                 
  F22. Levenshtein-2 too loose for short tags                                                                                                    
                                           
  (Kimi §3.2)                                                                                                                                    
                                                                                                                                                 
  Verdict: CLARIFY. Switch to min(2, ceil(len(input)/2)) as the distance bound. Don't go to Jaro-Winkler — it's another dependency for marginal
  gain.                                                                                                                                          
                                         
  F23. Pagination on /list-sources, /quarantine list, /audit                                                                                     
                                           
  (Mimo M6)                                                                                                                                      
                                                                                                                                                 
  Verdict: FIX. /saved already has --page N; mirror that pattern across the three commands. Default page-size 20.
                                                                                                                                                 
  F24. body plain-text-vs-HTML clarity   
                                                                                                                                                 
  (Mimo M1)
                                                                                                                                                 
  Verdict: CLARIFY (one paragraph). Spec uses body TEXT, -- sanitized HTML→text and Stage 1 explicitly converts HTML→text. Mimo's concern is
  real: spec doesn't say what length(body) > 2000 measures (chars of plain text). Add one sentence: "post.body is always plain text. length()    
  semantics are characters of plain text."
                                                                                                                                                 
  F25. Digest cache invalidation on tag/source changes
                                                                                                                                                 
  (Mimo M5)                              
                                                                                                                                                 
  Verdict: FIX. Cache key is currently (group_id, slot). Add tag_subscription_version and source_subscription_version (incremented on
  /follow-tag, /unfollow-tag, /add-source, etc.). Cache key becomes (group_id, slot, tag_v, src_v). Stale entries age out naturally via the      
  existing 60-min TTL.                   
                                                                                                                                                 
  ---
  💡 Low — clarify language, no code change                                                                                                      
                                           
  F26. Concurrency test for same (user, scope) (Kimi §8.3)
                                         
  FIX (test only). Add to 08-verification.md §8.4. Once F3 (chat_message child table) lands, this test is straightforward.                       
                                           
  F27. ReDoS test (Kimi §8.5)                                                                                                                    
                                                                                                                                                 
  FIX (test only). Spec already requires RE2/J or 100ms timeout (§4.2). Verification just needs an explicit test case with an adversarial input. 
  Cheap.                                                                                                                                         
                                         
  F28. Performance gates in CI (Kimi §8.4)                                                                                                       
                                           
  PUSH BACK. Pre-release perf testing on representative hardware is the right discipline. CI perf gates flake constantly and erode trust in the  
  test suite. Keep current spec. If anything, tighten the pre-release runbook in §7.                                                             
   
  F29. Embedding dimension migration (Kimi §5.5 = Mimo M2)                                                                                       
                                           
  DEFER to v2. Both reviewers want a separate-table-per-dimension approach. This is genuinely cleaner, but it's a non-trivial schema redesign    
  with no v1 user impact (operators usually pick one model and stick with it). Document as "v2 if multi-model embeddings become operational      
  requirement."
                                                                                                                                                 
  F30. Outbound queue persistence (Kimi §6.2)
                                                                                                                                                 
  DEFER (document). Honest: not worth the complexity in v1. Document the limitation explicitly: "On Provider restart, in-flight outbound messages
   are lost. Users may need to re-issue commands. Persistent outbound is a v2 feature."                                                          
   
  F31. Identity farming (Kimi §4.4)                                                                                                              
                                         
  DEFER (document). SimpleX gives no fingerprinting hooks. Kimi acknowledges this. Document as known v1 limitation.                              
                                           
  F32. infochat-core module split (Kimi §1.2)
                                                                                                                                                 
  PUSH BACK. Kimi argues this is necessary for "agentic development." I disagree — the cognitive-overhead claim doesn't survive contact with
  reality (an agent reading entity classes for context isn't a problem; agents handle large symbol surfaces fine). The compile-time-coupling     
  claim is true but trivial (Quarkus rebuilds are fast). The real cost of the split is three more pom.xml files, three more migration
  directories, and the perennial question of "does this enum belong in core-api or core-collector?". Keep single infochat-core. Revisit if/when a
   third consumer shows up, exactly as the spec already says.
                                                                                                                                                 
  F33. Confirmation token randomness (Mimo m4)
                                           
  INVALID. Mimo misread the spec. There is no opaque token — confirmation is "re-type the same slash command + confirm within 30s, scoped to
  (user, scope)." There's nothing to make cryptographically random. Skip.                                                                        
   
  F34. Source soft-delete semantics (Mimo M3)                                                                                                    
                                         
  ALREADY-ADDRESSED. Spec §3.5 separates /remove-source (admin, global soft-delete) from /unfollow-source (per-scope subscription removal).      
  Mimo's confusion is fair — could read the spec twice — but no edit needed; perhaps a one-line cross-reference from §2.2 to §3.5.

  F35. Error code catalog, dependency graph, full SPI Java interfaces (Kimi §9)                                                                  
                                           
  PUSH BACK on framing, partial accept on substance. Kimi frames these as "needed for agentic development." That's marketing, not architecture.  
  But error codes and a module-dep matrix are useful for any development. Add a brief 09-reference.md with:                                      
  - Module dependency table (5 lines)
  - Error code catalog (E1xxx user errors, E2xxx LLM, E3xxx eval, E4xxx infra)                                                                   
  - Skip the "full Java interfaces in spec" suggestion — that belongs in code, not in a markdown spec.
                                                                                                                                                 
  F36. Bootstrap sources versioning (Mimo m3), social_score formula location (Mimo m2), referenced_topics ephemerality (Mimo m1)                 
                                           
  FIX (all trivial). Each is a 1–2 line documentation tweak. Bundle them into one polish pass.                                                   
                                                                                                                                                 
  F37. maxRequestsPerSecond ambiguity (Mimo M4)                                                                                                  
                                                                                                                                                 
  FIX. Mimo is right that the spec conflates rate and concurrency. Rename to maxInflightSends (concurrency) and add a separate maxSendsPerSecond 
  (rate); document each precisely.                                                                                                               
                                         
  ---                                                                                                                                            
  Summary table                          
                                                                                                                                                 
  ┌─────┬────────────────────────────────────────────┬──────────────────┬───────────┬────────┐
  │  #  │                    Item                    │      Source      │  Verdict  │ Effort │
  ├─────┼────────────────────────────────────────────┼──────────────────┼───────────┼────────┤
  │ F1  │ Provider startup reconciliation for NOTIFY │ K§1.3 / M C2     │ FIX       │ S      │
  ├─────┼────────────────────────────────────────────┼──────────────────┼───────────┼────────┤
  │ F2  │ Chat output sanitizer for admin commands   │ K§4.3            │ FIX       │ S      │                                                   
  ├─────┼────────────────────────────────────────────┼──────────────────┼───────────┼────────┤
  │ F3  │ chat_message child table                   │ K§2.2 / M S5     │ FIX       │ M      │                                                   
  ├─────┼────────────────────────────────────────────┼──────────────────┼───────────┼────────┤                                                   
  │ F4  │ Stage 1 honesty paragraph                  │ K§4.2            │ CLARIFY    │ XS     │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤                                                  
  │ F5  │ docker-compose env-var passwords           │ K§7.1            │ FIX        │ XS     │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F6  │ systemd User= + hardening                  │ K§7.2            │ FIX        │ XS     │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F7  │ post.is_saved + partial index              │ M S3             │ FIX        │ S      │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F8  │ Stage 2 fail-closed config flag            │ M S1             │ FIX        │ S      │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F9  │ Separate /health/llm endpoint              │ K§7.4            │ FIX        │ S      │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F10 │ SimpleX auth-fail terminal state           │ M S6             │ FIX        │ S      │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F11 │ external_id length CHECK                   │ M S4             │ FIX        │ XS     │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F12 │ Partial unique index on group admin        │ M S2             │ FIX        │ XS     │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F13 │ LinkingJob incremental cursor              │ K§1.4            │ FIX        │ S      │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F14 │ Reject auto-downgrade; add alert           │ K§4.5            │ PUSH BACK  │ XS     │                                                  
  ├─────┼────────────────────────────────────────────┼──────────────────┼────────────┼────────┤
  │ F15 │ Tagger fallback prompt                     │ K§5.3            │ CLARIFY           │ XS     │                                           
  ├─────┼────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F16 │ Cosine threshold configurable              │ K§5.6            │ FIX               │ XS     │                                           
  ├─────┼────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F17 │ LLM-call-cap + tool budget                 │ K§3.4§4.6 / M C1 │ FIX               │ S      │                                           
  ├─────┼────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F18 │ Per-service pool sizes; release-before-LLM   │ M C3             │ FIX               │ S      │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F19 │ Bounded chat memory cap                      │ K§1.5            │ FIX               │ S      │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F20 │ Confirmation cancellation feedback           │ K§3.5            │ FIX               │ XS     │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F21 │ /summary no-args top-3                       │ K§3.3            │ CLARIFY           │ S      │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F22 │ Adaptive Levenshtein bound                   │ K§3.2            │ CLARIFY           │ XS     │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F23 │ Pagination on list/quarantine/audit          │ M M6             │ FIX               │ S      │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F24 │ body plain-text clarification                │ M M1             │ CLARIFY           │ XS     │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F25 │ Digest cache invalidation versions           │ M M5             │ FIX               │ S      │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F26 │ Concurrency test                             │ K§8.3            │ FIX (test)        │ XS     │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F27 │ ReDoS test                                   │ K§8.5            │ FIX (test)        │ XS     │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F28 │ CI perf gates                                │ K§8.4            │ PUSH BACK         │ —      │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F29 │ Embedding dimension separate tables          │ K§5.5 / M M2     │ DEFER v2          │ —      │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F30 │ Outbound persistence                         │ K§6.2            │ DEFER v2          │ —      │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F31 │ Identity farming                             │ K§4.4            │ DEFER v2          │ —      │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F32 │ Split infochat-core                          │ K§1.2            │ PUSH BACK         │ —      │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F33 │ Confirmation token randomness                │ M m4             │ INVALID           │ —      │
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F34 │ Source soft-delete                           │ M M3             │ ALREADY-ADDRESSED │ —      │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F35 │ Error catalog + module DAG                   │ K§9              │ FIX (subset)      │ M      │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F36 │ Polish: versioning, social_score, topic refs │ M m1-m3          │ FIX               │ XS     │                                         
  ├─────┼──────────────────────────────────────────────┼──────────────────┼───────────────────┼────────┤
  │ F37 │ maxRequestsPerSecond rename                  │ M M4             │ FIX               │ XS     │                                         
  └─────┴──────────────────────────────────────────────┴──────────────────┴───────────────────┴────────┘
                                                                                                                                                 
  Push-backs (4): F14 (auto-downgrade UNKNOWN — wrong direction), F28 (CI perf gates — flaky), F32 (split core — solving a non-problem), F33
  (confirmation tokens — misread spec).                                                                                                          
                                         
  Defers (3): F29, F30, F31 — all valid for v2.
