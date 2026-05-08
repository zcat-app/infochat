  1. Executive summary                                                                                                                                                                                                                                  
                  
  I read SPEC.md, decisions.md (D1–D45), architecture.md, security.md, schema.md, commands.md, llm.md, messaging.md, deployment.md, verification.md, and 00-mvp.md.                                                                                     
                                                                                                                                                                                                                                                        
  Overall verdict. The spec is unusually disciplined for its size — the determinism boundary, trust path, and spec/design layering are coherent and a competent team could build from it — but it has a meaningful set of unresolved ambiguities, a     
  handful of internal contradictions around invite codes / Signal / asset commands, and a few security commitments that verification.md doesn't pin down.
                                                                                                                                                                                                                                                        
  Top 3 issues by severity:                                                                                                                                                                                                                             
   
  1. F-01 (blocker). Signal adapter is in v1 scope per SPEC.md and decisions D32/D44, but no spec section defines it; messaging.md only names it once and deployment.md says "One messaging adapter backend (SimpleX in v1)" — directly contradicting v1
   scope.         
  2. F-02 (blocker). Asset Fetchers write directly to price_snapshot without a source.id (architecture.md), but Source is keyed by (kind, identifier) and the Fetcher is "stateless between ticks; what's new is a query against posts". The asset      
  Fetcher has no row to schedule from and no scheduling key — the scheduler contract is unspecified.                                                                                                                                                    
  3. F-03 (major). Decision numbering is non-monotonic and the table is out of order (D43 appears after D45 in the table). With 45 decisions cross-referenced from every section, this is a maintenance/lookup hazard.
                                                                                                                                                                                                                                                        
  ---             
  2. Findings                                                                                                                                                                                                                                           
                  
  F-01 Signal adapter is named in v1 scope but never specified
                                                                                                                                                                                                                                                        
  - Severity: blocker
  - Category: inconsistency                                                                                                                                                                                                                             
  - Location: SPEC.md §"In scope for v1" (line 91); decisions.md D32 (line 43); deployment.md §Topology (line 19); messaging.md §Goals (line 11)                                                                                                        
  - Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says. SPEC.md commits "SimpleX and Signal adapters plus an in-memory test adapter" for v1. D32 likewise says "v1 ships SimpleX, Signal, and an in-memory test adapter." messaging.md goals echo "v1 ships SimpleX and Signal." But      
  deployment.md §Topology says "One messaging adapter backend (SimpleX in v1)" and §"Deployment scenarios" enumerates "SimpleX adapter for real use" without mentioning Signal. The design-notes trailer in messaging.md mentions "The Signal adapter   
  wire protocol (signal-cli / JSON-RPC framing)," which implies a single concrete impl exists, but messaging.md does not describe Signal-specific behaviour, capability defaults, or identity-trust level.                                              
                  
  Why it's a problem. A v1 builder reading deployment.md will not stand up Signal; a v1 builder reading SPEC.md will be told it's missing. Worse: invite-code design (D44) is "applied uniformly across all adapters" and "cross-adapter isolation. An  
  invite bound to (contact-id-A, simplex) cannot be consumed from (contact-id-A, signal)" — which presumes Signal exists. If Signal slips to v2, D44 is over-specified; if Signal stays in v1, deployment.md is wrong.
                                                                                                                                                                                                                                                        
  Suggested resolution. Pick one. Either (a) keep Signal in v1, fix deployment.md §Topology to "One or more messaging adapter backends (SimpleX and Signal in v1)" and add a Signal section to messaging.md (trust level, identity shape, capability    
  defaults — at spec depth, not design); or (b) defer Signal to v2 and remove from SPEC.md, D32, messaging.md goals, and the cross-adapter clauses in D44/security.md §Invite-code.
                                                                                                                                                                                                                                                        
  ---             
  F-02 Asset Fetchers have no schedulable identity
                                                                                                                                                                                                                                                        
  - Severity: blocker
  - Category: gap                                                                                                                                                                                                                                       
  - Location: architecture.md §"Ingest SPIs" / "Output type" (lines 89–98); commands.md §"Asset commands" (lines 112–134); decisions.md D39 (line 50)
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. "There are no 'ghost' source rows for asset feeds: price_snapshot is keyed by (asset, sub-verb) alone, not by a source.id" (architecture.md). Asset Fetchers reuse the existing Fetcher SPI on a profile-driven refresh interval. 
  The "what's new since last time" query is "against posts" (architecture.md §"Ingest SPIs"). The fetcher is "stateless between ticks."
                                                                                                                                                                                                                                                        
  Why it's a problem. The general Fetcher contract is per-source row driven (the scheduler ticks on a per-source interval; failure-policy D42 transitions source.status on per-source thresholds). Asset Fetchers have no source row, so:               
  
  1. There is no row to drive scheduling from (what does "per-source interval" mean for the Kraken Zcash fetcher?).                                                                                                                                     
  2. D42's failure model — "After N consecutive per-source failures the source's status transitions to 'failed'" — has no source.status to flip for a Kraken outage. The asset-command failure mode is therefore unspecified at the spec level.
  3. /list-sources --all lists "every source row globally where deleted_at IS NULL" (commands.md). It will not surface unhealthy asset Fetchers.                                                                                                        
  4. The freshness contract (commands.md §Asset commands) talks about "source is failing" but has no place to record that.                                                                                                                              
                                                                                                                                                                                                                                                        
  Suggested resolution. Either (a) add ghost source rows for asset endpoints (one per (asset, sub-verb)) — small spec change, makes the existing SPI reusable; or (b) define an explicit asset_fetcher registry in deployment.md / schema.md with its   
  own scheduling key, status field, and failure semantics, and have D42 explicitly cover it. Option (a) is the smaller delta.                                                                                                                           
                                                                                                                                                                                                                                                        
  ---             
  F-03 Decision numbering is non-monotonic and out of order

  - Severity: major
  - Category: smell / layering
  - Location: decisions.md table (lines 11–56)                                                                                                                                                                                                          
  - Confidence: high
                                                                                                                                                                                                                                                        
  What the spec says. The table jumps D40 → D41 → D42 → D44 → D45 → D43, with D43 (Localization vs. translation) physically positioned after D44 (invite-code) and D45 (slow-start).                                                                    
  
  Why it's a problem. Every other spec file cross-references decisions by D-number with no further title (e.g. "decision D43" in llm.md, messaging.md, security.md). A reader scanning for D43 has to read the entire table; future renumbering or      
  insertions invite collisions and merge conflicts. The numbering pattern signals that decisions were back-filled; without a stable convention (append-only by date), future spec changes will compound the disorder.
                                                                                                                                                                                                                                                        
  Suggested resolution. Either (a) re-sort the table strictly by D-number and commit to "D-numbers are append-only — never re-used, never reordered," or (b) drop the implicit ordering claim and make every cross-reference cite both number and title 
  (decision D43 (localization vs. translation)). I recommend (a) plus a one-line policy note above the table.
                                                                                                                                                                                                                                                        
  ---             
  F-04 "Bot admin contact id is adapter-specific" but invite codes are cross-adapter
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: inconsistency                                                                                                                                                                                                                             
  - Location: deployment.md §"Operator inputs" item 2 (lines 42–52); security.md §"Invite-code registration" (lines 270–308); decisions.md D44
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. deployment.md: "The contact-id string format is adapter-specific … Provider validates the contact id against the active adapter at startup and refuses to start on a mismatch." Security.md §Invite: invites carry a (contact_id, 
  adapter) pair; D44 explicitly enables --adapter <name> and "uniform across all adapters."
                                                                                                                                                                                                                                                        
  Why it's a problem. With multiple adapters in v1 (SimpleX + Signal), the bot admin's is_admin=true is bound to one contact id which is valid for one adapter only. The bot admin therefore cannot bootstrap or use the bot from the other adapter     
  without a separate row. There's no spec text covering: can the bot admin issue /invite create --adapter signal --contact <signal-id> to themselves to gain a Signal identity? Are admin privileges per-(user_row) or per-(user_row, adapter)? The
  schema has one users row keyed by contact_id (presumably) — but contact_id is adapter-specific.                                                                                                                                                       
                  
  Suggested resolution. Pin down in security.md §Authorization: is users.contact_id plain or (adapter, contact_id)? If the latter, say so explicitly and clarify that the bot admin row is bound to one adapter and cross-adapter linking is out of v1. 
  If the former, pick which adapter the bootstrap admin uses (probably the first adapter listed in config) and forbid the inverse case.
                                                                                                                                                                                                                                                        
  ---             
  F-05 chat_session persistence is mandated but its TTL/lifecycle is silent
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: gap                                                                                                                                                                                                                                       
  - Location: schema.md §"Per-scope state" / Chat session (lines 122–130); decisions.md D24, D25, D40
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. "Chat session / context window. Per-(user, scope) live context state, persisted in the database. … /clear wipes only this entity for the calling (user, scope)." D40 establishes a TTL on chat_memory rows. No TTL is defined for 
  chat_session.   
                                                                                                                                                                                                                                                        
  Why it's a problem. D37/D40 commit to data minimization for chat_memory and explicitly say /saved posts are independent. chat_session is the most user-content-heavy table (every chat-mode message body lives there to feed auto-compress) and the   
  spec is silent on whether it's pruned, partitioned, retained forever, or cleared by /forget. /forget (commands.md) purges chat_memory and saved_post, not chat_session. So a banned user's last 10 chat-mode messages survive /ban and /forget.
  Verification.md §/forget purge does not assert anything about chat_session.                                                                                                                                                                           
                  
  Suggested resolution. Add a schema invariant for chat_session retention: either (a) bound by retention horizon paired to chat_memory's, with the same pruner, or (b) cleared on /clear, /compress, and /forget, and otherwise persistent until next   
  compress. Update commands.md /forget and verification.md /forget purge accordingly.
                                                                                                                                                                                                                                                        
  ---             
  F-06 Stage 1 "kind filter is part of Stage 1" contradicts "kind filter applies after the signature check and before any body interpretation"
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: security.md §"Per-source trust boundaries" / Nostr / Kind allowlist (lines 130–139)
  - Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says. "Ordering: signature verification runs first … and the kind filter is part of Stage 1. The kind filter applies after the signature check and before any body interpretation."                                                     
                                                                                                                                                                                                                                                        
  Why it's a problem. Stage 1 (per security.md §Ingest pipeline) runs after every event reaches the post outbox; per-source trust boundaries explicitly run before Stage 1. So the kind filter cannot be both "part of Stage 1" and "before any body    
  interpretation" if "before" means before Stage 1. Two implementers will build this differently: one drops kind-4 events at the StreamSource (never reaching the outbox); the other writes the post then quarantines it.
                                                                                                                                                                                                                                                        
  Suggested resolution. Pick one. Recommended: kind filter runs at the StreamSource implementation, between signature verification and outbox write, before Stage 1. Update text to: "The kind filter is part of the StreamSource trust boundary;       
  signature verification runs first, then kind filtering, then the event is written to the outbox where Stage 1 begins." Drop "part of Stage 1."
                                                                                                                                                                                                                                                        
  ---             
  F-07 Stage 2 verdict "BENIGN" semantics are inconsistent

  - Severity: major
  - Category: inconsistency
  - Location: security.md §"Quarantine workflow" (line 337) vs. §"Failure handling" (lines 358–361) vs. §"Ingest pipeline" (lines 64–67)
  - Confidence: high

  What the spec says. "A Stage 2 BENIGN verdict keeps the post visible with Stage 1 redactions retained" (Quarantine workflow). But §Failure handling: "Stage 2 verdict of BENIGN → post released to the tagger and embedding stage; Stage 1 redactions 
  remain in the body (quarantine spans are closed, not deleted — the original text is restorable only via admin /quarantine approve)." The re-evaluation job §: "On a BENIGN re-evaluation verdict the Stage 1 redactions are lifted (equivalent to a
  quarantine approve) and the post continues."                                                                                                                                                                                                          
                  
  Why it's a problem. The first-time-Stage-2 path retains redactions on BENIGN; the re-evaluation path lifts redactions on BENIGN. Same verdict, opposite effect. A re-evaluated post that originally got BENIGN-with-redactions and now gets
  BENIGN-again would have its redactions lifted on the second pass even though Stage 2 has said "benign" both times. That is either a bug or an intentional asymmetry that should be justified.

  Suggested resolution. Pick one rule and apply it both places. The simplest: "Stage 2 BENIGN closes the spans (still retrievable via admin /quarantine approve); the original text is never auto-restored." Then the re-evaluation BENIGN clause       
  becomes: "BENIGN on re-eval is equivalent to first-pass BENIGN — redactions remain." Update verification.md §Re-evaluation job too.
                                                                                                                                                                                                                                                        
  ---             
  F-08 /forget in groups: scope semantics are unclear
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: commands.md §"Conversation control" / /forget (lines 258–265); verification.md §/forget purge (lines 96–107)
  - Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says. "/forget — immediate purge of the calling (user, scope)'s chat memory and saved-post list."                                                                                                                                       
                  
  Why it's a problem. "Saved post" per D13 is "Per-user only (private even in groups)" — so saves are not per-(user, scope). When user U calls /forget from group G, does it purge: (a) only U's (user, group=G) chat_memory and U's entire saved list? 
  (b) U's (user, group=G) chat_memory and U's saves filtered to group=G (impossible — saves are not scoped)? (c) only the per-scope chat_memory and no saved rows? Verification.md tests the DM case but for groups says "another user's data in the
  same group is untouched; the same user's data in a different scope (e.g. their DM) is untouched" — silent on the saved-post collision.                                                                                                                
                  
  Suggested resolution. State explicitly in commands.md: "/forget is per-(user, scope). It purges the caller's chat_memory rows for that scope and, for DM scope only, the caller's entire saved-post list (since saves are per-user not per-scope,     
  D13). In a group, /forget does not touch saves; the caller must /forget from DM to clear saves." Add a verification line to match.
                                                                                                                                                                                                                                                        
  ---             
  F-09 /export table list and the "users row" carve-out underspecified
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: commands.md §"/export" (lines 266–280); verification.md §/export scope isolation
  - Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says. "DM: full self-export under the same table list, scoped to the calling user's DM scope. Group: scoped to the calling (user, group) only … plus the caller's own users row (excluding fields derived from the authorization state  
  of other users — last-admin counters, etc.)."                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  Why it's a problem. "Excluding fields derived from the authorization state of other users" is vague — the listed example ("last-admin counters") is a denormalized counter that is design, not spec. Two implementers will pick different exclusions: 
  one will redact is_admin; another will include it. Furthermore, the list omits groups (but says "the group's groups row beyond id and timezone" must not appear — a partial inclusion that's not clearly documented in the table list). And the list
  omits audit_log of the caller's own intents — does the user's /audit for themselves get exported? The spec is silent.                                                                                                                                 
                  
  Suggested resolution. Replace the carve-out with a positive list: "the caller's users row in full except admin/ban metadata fields (is_admin, banned_by, ban_reason, banned_at, probation_until)." For audit log: state explicitly whether /export    
  returns the caller's own audit rows (recommend: yes, scoped to actor=self). Update verification.md to assert the field-level inclusion/exclusion.
                                                                                                                                                                                                                                                        
  ---             
  F-10 chat_memory recall is "scope-filtered" but D26 says memory is per-(user, group)
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: llm.md §"Memory retrieval" (lines 215–225); decisions.md D26 and D28; security.md §"Prompt-injection defenses" (line 167)
  - Confidence: medium                                                                                                                                                                                                                                  
                  
  What the spec says. "Cheap deterministic keyword match on chat_memory for the calling (user, scope)". D26: "Per-(user, group) — same privacy model as /save." Recall tool is "scope-filtered."                                                        
                  
  Why it's a problem. "Scope" in this codebase is (scope_kind, scope_id) where scope_kind is 'dm' or 'group'. For DM the user's memory is (user, dm-self); for group it's (user, group). The recall tool is described as scope-filtered, which is       
  consistent. But there is no spec statement on whether the agent in DM can recall memory captured in a group, or vice versa. D25/D26 imply strict isolation (per-(user, scope), no cross-scope), but the spec never says it explicitly, and
  verification.md does not test it. With D37 promising user data minimization, this is exactly the kind of leak that needs an explicit invariant.                                                                                                       
                  
  Suggested resolution. Add to schema.md §Per-scope state / chat memory: "Cross-scope memory access is forbidden. The chat agent in scope S can only recall memory rows whose scope key equals S — never DM memory from a group context, never another  
  group's memory from this group." Add a verification.md test to invariant 1 (per-(user, scope) isolation): a DM-only memory entry never surfaces in a group recall.
                                                                                                                                                                                                                                                        
  ---             
  F-11 LLM output sanitizer regex catalogue is not bounded
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: gap / verification                                                                                                                                                                                                                        
  - Location: security.md §"LLM output sanitizer" (lines 180–194); verification.md §"Chat output sanitizer"
  - Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says. "strips or refuses output containing admin command strings (/grant-admin, /ban, /promote, /remove-source, etc.)."                                                                                                                 
                                                                                                                                                                                                                                                        
  Why it's a problem. "etc." is the open-set ambiguity that bites future maintenance. A new admin command added (e.g. /vouch, which is admin-only per D45) is not automatically in the sanitizer's match set. Verification.md tests /grant-admin abc    
  only. There is no spec rule like "the sanitizer's match set is computed from the permission matrix's bot-admin row at build time." So the sanitizer can silently fall behind the command surface.
                                                                                                                                                                                                                                                        
  Suggested resolution. Spec change: "The sanitizer match set is the set of slash-command names whose permission row is bot-admin only (or group-admin only for group surfaces). Adding a new admin command automatically extends the sanitizer set. CI 
  fails if a bot-admin command's name is not in the sanitizer match set." Add corresponding verification entry: "every command in the bot-admin row of the permission matrix is in the sanitizer set."
                                                                                                                                                                                                                                                        
  ---             
  F-12 /compress failure path is silent
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: failure-mode                                                                                                                                                                                                                              
  - Location: commands.md §"Conversation control" / /compress (lines 244–246); decisions.md D24
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. "/compress — forces an immediate chat_memory checkpoint for the calling (user, scope). Auto-triggered near the context-window ceiling (decision D24)."                                                                            
                  
  Why it's a problem. /compress is summary-producing (it calls the LLM). What happens when it's auto-triggered and the LLM is down? The spec promises auto-compress at the ceiling (architecture principle, D24). If the LLM call fails, does the chat  
  session continue past the ceiling, get rejected, get truncated, or get held? None of the failure-handling tables (security.md §Failure handling, llm.md §Failure handling) cover the auto-compress LLM failure case. This is exactly the "complete LLM
   outage degrades quality, not safety" promise — but here a degradation could be "user can't chat" or "user's old context is silently lost," which is not the same.                                                                                    
                  
  Suggested resolution. Add to llm.md §Failure handling: "Auto-compress LLM failure: the chat session is held at the ceiling; subsequent chat-mode input from the same (user, scope) returns a friendly 'memory checkpoint pending; please /compress    
  manually or try again later' until the LLM recovers. The session is never silently truncated. Manual /compress failure surfaces the same friendly error and leaves the session unchanged."
                                                                                                                                                                                                                                                        
  ---             
  F-13 Pre-banned unknown contact + invite-code path is contradictory
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: inconsistency                                                                                                                                                                                                                             
  - Location: security.md §"Authorization model" (lines 230–246) + §"Invite-code registration" (lines 301–305); decisions.md D44
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. Authorization step 2: unknown contact, message body must be a valid PENDING invite code; otherwise drop. Step 4: ban check. §Invite-code: "/ban <contact> against an unknown contact creates the user row with is_banned=true …   
  the pre-ban row means the invite check (step 2) finds a known contact and routes to the ban path instead."
                                                                                                                                                                                                                                                        
  Why it's a problem. A pre-banned row is a known contact (a users row exists). So step 2 ("if no user row exists for this (contact_id, adapter)") is false; intake skips invite checks and goes straight to ban check. Good. But what if a banned user 
  later receives an invite code from an admin (/invite create --contact <banned-id> — does the spec forbid this?) and presents it? Step 2 (no row) is false; step 4 (ban) fires the fixed reply; the invite stays PENDING forever (or until TTL'd). The
  spec doesn't forbid issuing invites for already-banned users; verification.md doesn't test it.                                                                                                                                                        
                  
  Suggested resolution. In security.md §Invite-code: add "/invite create --contact <id> against a known banned user is rejected with a friendly error pointing the admin at /unban. Open invites that are consumed by a banned user (impossible by step 
  2/4 ordering) cannot occur." Add verification line.
                                                                                                                                                                                                                                                        
  ---             
  F-14 SimpleX adapter trust level is asserted but unspecified
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: gap                                                                                                                                                                                                                                       
  - Location: messaging.md §"Capability flags" (lines 53–55); D10
  - Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says. "trustLevel — HIGH for cryptographically anchored ids, LOW otherwise. Provider rejects identity assertions from LOW adapters unless the operator explicitly opts in."                                                             
                  
  Why it's a problem. What level does SimpleX assert? Signal? The in-memory adapter? D10 says "the messaging adapter's cryptographic contact ID is the trust anchor" — but that's a system-wide claim, not a per-adapter capability. A future adapter   
  author needs a rubric: "Trust level is HIGH iff the wire protocol provides a cryptographic identity that the adapter can verify out-of-band (Signal Safety Numbers, SimpleX queue cryptographic addresses) and the adapter does not delegate identity
  assertion to a TOFU step." The spec does not say. And the in-memory test adapter — does it assert HIGH or LOW? Verification.md says "Low-trust adapter rejected unless explicit opt-in" but doesn't say which v1 adapter is which.                    
                  
  Suggested resolution. Add a one-paragraph rubric to messaging.md §Capability flags defining what HIGH means concretely. Then state, per adapter: "SimpleX: HIGH (queue address is cryptographic). Signal: HIGH (Safety Number-anchored). InMemory:    
  configurable; defaults LOW so tests must opt in to admin paths."
                                                                                                                                                                                                                                                        
  ---             
  F-15 D17 "staggered" is undefined; D17 conflicts with D41 single-Provider topology
                                                                                                                                                                                                                                                        
  - Severity: major
  - Category: ambiguity / inconsistency                                                                                                                                                                                                                 
  - Location: decisions.md D17; commands.md §"Periodic group summaries" (lines 401–406); D41
  - Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says. D17: "Staggered start within the slot window; results cached so a follow-up /summary is served from cache; degraded fallback (headlines + sources, no LLM prose) when worker is overloaded."                                      
                  
  Why it's a problem. With D41 fixing v1 to "exactly one Collector and exactly one Provider," "staggered" means within a single Provider's worker pool. The spec doesn't define the slot window (is morning 06:00–09:00 local, 8am ±15min, ±1h?).       
  "Worker pool overloaded" is undefined — overload by what queue depth? By time-budget? Verification.md asserts "generated within the staggered slot window" without saying what the window is. Two implementers will build different schedules.
                                                                                                                                                                                                                                                        
  Suggested resolution. Pin the slot-window concept in commands.md §Periodic: "Each digest fires within a [profile-driven] window centred on the scope's configured local hour; the exact window value is design notes. The 'overload' fallback fires   
  when, at slot-window-end, the digest hasn't started." Then verification asserts the window-bounded behaviour.
                                                                                                                                                                                                                                                        
  ---             
  F-16 Rate-limit categories list omits /quarantine reject, /audit, /export, asset commands
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: gap                                                                                                                                                                                                                                       
  - Location: security.md §"Rate limiting" (lines 412–424)
  - Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says. Buckets: parser-only commands, /add-source, chat, LLM-triggering operations, tool calls per turn, /quarantine approve.                                                                                                            
                  
  Why it's a problem. /audit is a paginated read-the-DB command that an admin can spam; /export is a paginated, possibly-large dump; asset commands are public, no-auth, and could be used to trigger CoinGecko ToS issues if a malicious user spams    
  them. None of these are bucketed. The spec's "parser-only command rate" might cover them, but /audit and /export definitely aren't parser-only. Asset commands hit cache so are cheap, but a flood still wastes CPU.
                                                                                                                                                                                                                                                        
  Suggested resolution. Either (a) add an explicit /audit and /export bucket category, and a "DB-read paginated" bucket; or (b) state the implicit grouping ("parser-only + DB-read commands share a bucket; asset commands share the cache-hit bucket; 
  LLM-triggering is its own bucket") so a builder doesn't have to guess.
                                                                                                                                                                                                                                                        
  ---             
  F-17 Probation list and Slow-start command list inconsistencies
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: inconsistency                                                                                                                                                                                                                             
  - Location: SPEC.md §"In scope for v1" line 96–99; security.md §"Slow-start tier" (lines 313–322); D45
  - Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says. Allowed list in security.md: /help, /status, /get-tags, /get-sources, /list-sources, /summary, /saved, asset commands, /export. Blocked list explicitly includes /forget. Allowed list in D45 mirrors security.md.                
                                                                                                                                                                                                                                                        
  Why it's a problem. /forget is the user's privacy lever. Blocking it during probation means a user who registers, decides they don't want to be in the system, cannot purge their data until probation ends. This contradicts the "data minimization" 
  framing of D37 ("/forget for user-initiated purge"). The spec doesn't justify blocking /forget in probation.
                                                                                                                                                                                                                                                        
  Suggested resolution. Move /forget to the allowed list in §Slow-start tier and D45. There's no abuse vector — the user is purging their own data — and it strengthens the privacy story.                                                              
  
  ---                                                                                                                                                                                                                                                   
  F-18 /lang blocked during probation but it has no LLM call cost
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: smell                                                                                                                                                                                                                                     
  - Location: security.md §"Slow-start tier" Blocked list (lines 318–320); D45                                                                                                                                                                          
  - Confidence: medium
                                                                                                                                                                                                                                                        
  What the spec says. "Blocked: chat mode, /add-source, /save, /unsave, /follow-tag, /unfollow-tag, /lang, /clear, /compress, /forget, /group-timezone, and any admin command."                                                                         
  
  Why it's a problem. Probation's stated purpose is to bound resource damage from a hostile newcomer. /lang is a single-row UPDATE on scope_preferences.language, no LLM, cheap. Blocking it means a non-English user can't read /help in their language
   during probation — which is the period when they most need to understand the rules.
                                                                                                                                                                                                                                                        
  Suggested resolution. Move /lang to allowed during probation. Same reasoning applies to /follow-tag//unfollow-tag (cheap writes), but they could be argued either way; /lang is the clearest case.                                                    
  
  ---                                                                                                                                                                                                                                                   
  F-19 D38 Source identity says (kind, identifier, config); schema/D38 elsewhere says (kind, identifier) is the unique key
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: inconsistency                                                                                                                                                                                                                             
  - Location: decisions.md D38 ("(kind, identifier, config)"); architecture.md §"Source identity" (lines 119–129); schema.md §"Sources and tags" (lines 41–47)
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. D38 says "Source identity generalizes from (fetcher, url) to (kind, identifier, config)." Architecture.md says identity is (kind, identifier, config) but then "Together with kind it forms the unique key for source rows" —     
  referring only to identifier. Schema.md: "keyed by (kind, identifier)." 00-mvp.md: "idempotent on (kind='rss', identifier=<url>)."
                                                                                                                                                                                                                                                        
  Why it's a problem. Is config part of the unique key or not? If yes, two Nostr sources differing only by relay-list config are distinct rows; if no, the second one updates the first's config. Bootstrap loader idempotency (deployment.md) and      
  /add-source tag-conflict resolution (commands.md) implicitly assume (kind, identifier) as the key — but if someone changes a relay list in bootstrap-sources.json, what happens?
                                                                                                                                                                                                                                                        
  Suggested resolution. Pin in schema.md and D38 (matching): "Unique key is (kind, identifier). The config block is a value attached to that key; it is updated in place by the bootstrap loader and by /add-source (mirroring the tag-replacement      
  rule)." Drop the misleading (kind, identifier, config) framing in D38.
                                                                                                                                                                                                                                                        
  ---             
  F-20 /retry for periodic digest needs group admin, but the cached digest is not associated with one user
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: commands.md §"/retry" (lines 298–314); D36
  - Confidence: medium                                                                                                                                                                                                                                  
                  
  What the spec says. "For periodic group digests, /retry is group-admin or bot-admin only and replaces the cached digest."                                                                                                                             
                  
  Why it's a problem. /retry (per spec) is anchored to "the last summary-producing command in the calling (user, scope)." The periodic digest is generated by the system, not by a user — so what is the (user, scope) anchor for a group admin who     
  calls /retry in the group hours after the digest fired? The spec does not define when a group admin's /retry targets the group-digest cache vs. the group admin's own most recent /summary. Two implementers will disagree.
                                                                                                                                                                                                                                                        
  Suggested resolution. Define routing: "/retry from a group admin in a group resolves in this order: (1) if the group admin's own most recent /summary in this group is within the retry-anchor window, retry that; (2) else if a cached periodic      
  digest exists for this group, retry that; (3) else friendly error." Or simpler: introduce /retry --digest to disambiguate. Mirror in verification.md.
                                                                                                                                                                                                                                                        
  ---             
  F-21 Translation cache cross-scope timing side-channel acknowledged, but the cache has a v1 spec hole
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: security                                                                                                                                                                                                                                  
  - Location: security.md §"What's intentionally NOT in v1" (lines 504–517); llm.md §"Translation flow" (lines 178–180)
  - Confidence: low                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  What the spec says. "Translated outputs are cached by (hash(text), target_language) for a short window so a digest sent to ten group members is not translated ten times." The cross-scope timing side-channel is accepted as a v1 trade-off.         
                                                                                                                                                                                                                                                        
  Why it's a problem. The security note correctly observes the cache is shared across scopes. But it doesn't address: a /lang cs user issues /summary and sees the cached translation. A second /lang cs user in a different group issues an            
  identical-text /summary (same posts, same prose — feasible because the SQL is deterministic per D19). Cache hit → cross-scope inference of "this content was generated for someone else." This is a stronger leak than timing — it's a content
  equivalence test. The spec doesn't say whether scope id is part of the cache key.                                                                                                                                                                     
                  
  Suggested resolution. Either (a) add scope_id to the cache key (kills the dedup benefit for groups but not for digest-fan-out within a group, which is the original motivation), or (b) explicitly state in the trade-off section that "the cache is  
  keyed by (hash(text), target_language) and not scoped — content equivalence is observable; this is acceptable because translated content is bot-authored prose." The current text implies (b) but only addresses timing, not content equivalence.
                                                                                                                                                                                                                                                        
  ---             
  F-22 /quarantine reject permanence is asserted but /quarantine list filtering after reject is silent
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: commands.md §"Admin (bot admin)" (lines 341–343); security.md §"Quarantine workflow" (lines 333–347)
  - Confidence: medium                                                                                                                                                                                                                                  
                  
  What the spec says. "/quarantine list — pending review queue. … reject leaves the placeholder permanently."                                                                                                                                           
                  
  Why it's a problem. "Pending review queue" implies rejected entries don't show. But the spec doesn't say there's a state field on the quarantine row that is filtered, nor does it define the verb-states (PENDING/APPROVED/REJECTED). Schema.md only 
  says "review status" without enumerating values. Two implementers will pick different semantics for /quarantine list after a reject (one shows the rejected row with a status; another hides it).
                                                                                                                                                                                                                                                        
  Suggested resolution. In schema.md §Quarantine: enumerate review statuses (PENDING, APPROVED, REJECTED). In commands.md /quarantine list: "shows PENDING only by default; --all includes all statuses, bot-admin only."                               
  
  ---                                                                                                                                                                                                                                                   
  F-23 failed source recovery procedure is "admin command" but no command is listed
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: gap                                                                                                                                                                                                                                       
  - Location: schema.md §"Status state machine" (lines 51–64); D42; commands.md §"Source management"
  - Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says. "failed → active is set by an admin recovery command or by a successful manual probe."                                                                                                                                            
                                                                                                                                                                                                                                                        
  Why it's a problem. No such command appears in the commands.md catalogue. /list-sources --all shows status; there's no /source-enable <id> or /source-retry <id>. An operator following the spec literally has no way to flip a failed source to      
  active short of psql as the admin DB role.
                                                                                                                                                                                                                                                        
  Suggested resolution. Add to commands.md §"Source management": "/source-enable <id> — bot-admin only, transitions a failed or disabled source back to active. Audit-logged. Emits a probe before the transition; a probe failure leaves the source in 
  its prior state with a friendly error." Or, simpler: state explicitly in deployment.md that source-status changes are operator-side (psql) and out of v1 chat surface. Either way, pin it.
                                                                                                                                                                                                                                                        
  ---             
  F-24 /clear confirm requirement vs. friction tradeoff
                                                                                                                                                                                                                                                        
  - Severity: nit
  - Category: smell                                                                                                                                                                                                                                     
  - Location: commands.md §"Conversation control" / /clear (line 241–243)
  - Confidence: low                                                                                                                                                                                                                                     
  
  What the spec says. "/clear — wipes the calling (user, scope) active context window only. … Requires confirm."                                                                                                                                        
                  
  Why it's a problem. /clear is reversible only if the active context is not yet recoverable; it's effectively the user's only way to "start fresh" in chat. Requiring confirm on every use creates friction for the lightest touch action. Not a       
  security issue, but the spec does not explain the rationale ("destructive" is overloaded — /clear doesn't touch persistent state past the live window). A future maintainer might drop the confirm.
                                                                                                                                                                                                                                                        
  Suggested resolution. Add a one-sentence rationale in commands.md: "Confirm is required because the live window often holds the only bridging context between an evolving conversation and a forthcoming /compress; an accidental /clear is           
  irrecoverable. The confirm prompt is the same as any destructive command (per Surface conventions)."
                                                                                                                                                                                                                                                        
  ---             
  F-25 Verification gap: D44 "open invite" race between two unknown contacts
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: verification                                                                                                                                                                                                                              
  - Location: verification.md §"Commands and chat"; D44
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. D44: "--open — adapter-bound invite, not pre-bound to a contact_id; the first unknown contact on that adapter to present the code is registered." Verification.md tests permission matrix, banned-user intake, etc., but no test  
  for --open invite races.
                                                                                                                                                                                                                                                        
  Why it's a problem. Two unknown contacts presenting the same --open code at the same instant is a race. The spec implies "first wins" via single-use (USED cannot transition back to PENDING), but the schema-level protection isn't called out. A    
  naive implementation does a SELECT-then-UPDATE that races; the correct one uses UPDATE … WHERE status='PENDING' RETURNING … or equivalent.
                                                                                                                                                                                                                                                        
  Suggested resolution. Add to verification.md §Schema: "Invite-code single-use atomicity: simulated race of N concurrent unknown-contact attempts on the same --open code produces exactly one USED transition and exactly one new user row." Add to   
  schema.md §Invite code: "Single-use atomicity is enforced by the row-level state transition guard."
                                                                                                                                                                                                                                                        
  ---             
  F-26 Verification gap: probation enforcement is not table-tested
                                                                  
  - Severity: minor
  - Category: verification                                                                                                                                                                                                                              
  - Location: verification.md §"Commands and chat"; security.md §"Slow-start tier"
  - Confidence: high                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  What the spec says. Permission matrix test "every command × every actor type." But probation is a time-based actor state, not a fixed actor type.                                                                                                     
                                                                                                                                                                                                                                                        
  Why it's a problem. The matrix test as described doesn't cover probation × every command. A future addition that lands a new write command without adding it to the slow-start blocked list would not fail any test.                                  
                  
  Suggested resolution. Extend the matrix dimensionality: "every command × every actor type × {full-access, probation}." Add invariant: "every write command appears on the probation-blocked list; the test asserts this from the command registry, not
   a hand-written list."
                                                                                                                                                                                                                                                        
  ---             
  F-27 Verification gap: D40 promised "spec direction wins" over old design notes — no test proves it
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: verification                                                                                                                                                                                                                              
  - Location: D40 (line 51); verification.md §Schema "Chat-memory TTL pruner"
  - Confidence: high                                                                                                                                                                                                                                    
                  
  What the spec says. D40 settles the contradiction: spec direction (TTL+pruner) wins. The pruner test asserts a row older than the configured horizon is removed.                                                                                      
                  
  Why it's a problem. "Configured horizon" is design-notes-driven. If a future design-notes change sets the horizon to "infinity" or skips the pruner schedule registration, the spec invariant is violated but the test would (depending on            
  construction) just take longer to fail or never fire. The verification line as written doesn't pin "the pruner is registered and runs on the configured cadence."
                                                                                                                                                                                                                                                        
  Suggested resolution. Add: "the pruner bean is registered and has fired at least once at startup-N within deployment-Y" as a startup-bean assertion, alongside the row-deletion test.                                                                 
  
  ---                                                                                                                                                                                                                                                   
  F-28 Schema invariant 4 says "Soft-delete only for sources" but commands.md /remove-source is bot-admin only
                                                                                                                                                                                                                                                        
  - Severity: nit
  - Category: smell                                                                                                                                                                                                                                     
  - Location: schema.md invariant 4 (line 158); commands.md /remove-source (line 220)                                                                                                                                                                   
  - Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says. "Soft-delete only for sources. source is never hard-deleted; FKs from post and saved_post rely on this."                                                                                                                          
                  
  Why it's a problem. Slight: schema-level invariants are often enforced by triggers, but here the only code path that could hard-delete a source (/remove-source) is bot-admin only and explicitly soft-delete. So the trigger-level enforcement might 
  be considered redundant. But invariant 2 (last-admin protection) makes a similar argument and is enforced at the trigger layer specifically because "a buggy command cannot bypass it." The spec should either commit to trigger-level enforcement for
   soft-delete-only too (consistency) or explain why it's only command-level.                                                                                                                                                                           
                  
  Suggested resolution. Add to invariant 4: "Enforced at the trigger layer (revoke DELETE on source from all roles; the admin role uses an explicit unsafe escape hatch for emergency cleanup)." Add a verification line under §Schema: "DB-level test: 
  as Provider role, DELETE FROM source fails."
                                                                                                                                                                                                                                                        
  ---             
  F-29 Bootstrap admin: no spec rule on what happens when contact id changes
                                                                                                                                                                                                                                                        
  - Severity: nit
  - Category: operator                                                                                                                                                                                                                                  
  - Location: deployment.md §"Operator inputs" item 2 (lines 42–52)                                                                                                                                                                                     
  - Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says. "Provider validates the contact id against the active adapter at startup and refuses to start on a mismatch." On startup, "Provider ensures this user exists with is_admin = true (creating the user if needed) and writes a      
  bootstrap row to audit_log."
                                                                                                                                                                                                                                                        
  Why it's a problem. If the operator changes infochat.admin.contact-id in config (e.g., the admin lost their contact and re-keyed), startup will create a new admin row. But the old admin row still has is_admin=true. Last-admin protection means    
  neither can be revoked except by the other. The spec is silent on this scenario; a fresh operator hitting it has no guidance.
                                                                                                                                                                                                                                                        
  Suggested resolution. Add to deployment.md §"Bootstrap behavior on startup": "If the configured admin contact-id does not match an existing is_admin=true row, Provider creates the new admin row (audit-logged) and leaves any prior admin rows in   
  place. Pruning prior bootstrap admins is an operator action via /revoke-admin (run from the new admin's chat)."
                                                                                                                                                                                                                                                        
  ---             
  F-30 D44 /invite revoke confirm is asserted but not in commands.md
                                                                                                                                                                                                                                                        
  - Severity: nit
  - Category: inconsistency                                                                                                                                                                                                                             
  - Location: D44 (no confirm requirement on revoke); commands.md /invite revoke (line 336): "Requires confirm."
  - Confidence: high                                                                                                                                                                                                                                    
  
  What the spec says. D44 doesn't mention confirm for /invite revoke. commands.md /invite revoke <code> says "Requires confirm."                                                                                                                        
                  
  Why it's a problem. D44 is the cross-cutting decision row; commands.md is the surface spec. The confirm requirement should be reflected in D44 if it's a commitment, since otherwise a future spec amendment editing D44 to allow no-confirm would    
  silently disagree with commands.md.
                                                                                                                                                                                                                                                        
  Suggested resolution. Update D44: "/invite revoke <code> requires confirm — accidental revocation is recoverable only by issuing a fresh code, but cancelling an already-handed-out code is a footgun."                                               
  
  ---                                                                                                                                                                                                                                                   
  F-31 /list-sources --all "every source row" might leak across-group confidentiality
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: security                                                                                                                                                                                                                                  
  - Location: commands.md §"/list-sources" (lines 203–209); D7
  - Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says. "--all is bot-admin only and lists every source row globally where deleted_at IS NULL (across all kinds, all scopes, regardless of subscription)."                                                                                
                  
  Why it's a problem. D7 says DM source subscriptions are "private to the user." But /list-sources --all returns the global source row, which was created by a specific user via /add-source. If user U1 added                                          
  https://gossip-rss.example/u2-private-feed.rss from their DM, that URL surfaces to every bot admin via --all even though the subscription is private. This is probably intended ("admins can see source URLs"), but the spec doesn't explicitly say
  so, and a privacy-aware operator wouldn't expect their DM-only source URLs to appear in admin listings.                                                                                                                                               
                  
  Suggested resolution. Either (a) document explicitly in commands.md and security.md: "Source URLs are global state visible to bot admins; users adding private feeds via /add-source should treat the URL as visible to operators." Or (b) hide the   
  URL in --all listings (show id, kind, name, status only) and require a separate /source-show <id> for the URL.
                                                                                                                                                                                                                                                        
  ---             
  F-32 Two-stage SSRF "DNS re-resolved after every redirect" silent on the websocket case
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: security.md §"SSRF and outbound connections" (lines 81–104)
  - Confidence: medium                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  What the spec says. "DNS is re-resolved after every redirect (TOCTOU defense); the IP blocklist re-applies each hop. For long-lived StreamSource connections the IP check applies on every reconnect."                                                
                                                                                                                                                                                                                                                        
  Why it's a problem. Websocket connections don't have HTTP redirect semantics, but they can have connection migration via DNS TTL expiry mid-session. A relay's hostname could resolve to public-A on connect, then to private-B during a long-lived   
  session. The spec only requires the check on reconnect. A relay that maintains a long session could TOCTOU around this if the wire library supports re-resolve-on-keepalive.
                                                                                                                                                                                                                                                        
  Suggested resolution. Add to §SSRF: "For StreamSource connections that remain established across DNS-TTL expiry, the implementation MUST verify the remote peer IP matches the SSRF-allowed resolution at connect time and MUST treat any peer-IP     
  change observed at the socket layer as a hard close." This is a tighter spec; design notes pin the implementation.
                                                                                                                                                                                                                                                        
  ---             
  F-33 /audit filters can leak privileged data to non-admins (but /audit is admin-only)
                                                                                                                                                                                                                                                        
  - Severity: nit
  - Category: security / clarity                                                                                                                                                                                                                        
  - Location: commands.md §"/audit" (line 344)
  - Confidence: low                                                                                                                                                                                                                                     
                  
  What the spec says. "/audit [-w …] [--actor …] [--action …] [--page N] — read audit_log with filters."                                                                                                                                                
                  
  Why it's a problem. Permission tier is implicit (placed under "Admin (bot admin)"). --actor <contact> accepts arbitrary contact ids — what's the response when the supplied contact has no audit rows? "No rows" leaks the existence/non-existence of 
  a registered contact. Probably acceptable for bot admins (they can see all users via /list-users or equivalent — wait, there's no such command). Without /list-users, the bot admin's only way to enumerate users is /audit --actor <guess>. Workable,
   but the spec should note it.                                                                                                                                                                                                                         
                  
  Suggested resolution. Either add a /list-users [--page N] admin command, or explicitly note in commands.md /audit that "the result distinguishes 'no audit rows' from 'unknown contact' since both are visible to a bot admin." Or, lower priority:   
  leave as is.
                                                                                                                                                                                                                                                        
  ---             
  F-34 v1 scope says "Per-group bans / /kick distinct from bot-wide ban" deferred — but D26 group-memory privacy depends on group membership
                                                                                                                                                                                                                                                        
  - Severity: nit
  - Category: scope coherence                                                                                                                                                                                                                           
  - Location: SPEC.md §"Deferred to v2" (line 153); D26
  - Confidence: low                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  What the spec says. "Per-group bans / /kick distinct from bot-wide ban." deferred. D26: per-(user, group) memory.                                                                                                                                     
                                                                                                                                                                                                                                                        
  Why it's a problem. Without /kick, removing a misbehaving member from a group's bot interaction requires bot-wide ban (heavy) or group-admin demotion (doesn't ban) or relying on the messaging adapter's own kick (out-of-band). The spec doesn't    
  surface this v1 limitation; group admins discovering it post-deploy will be surprised. Not a contradiction, but worth acknowledging.
                                                                                                                                                                                                                                                        
  Suggested resolution. Add a one-line note in SPEC.md §Deferred: "Group admins in v1 cannot kick a misbehaving member from bot interaction without escalating to a bot admin for /ban. This is the deferred-feature limitation."                       
  
  ---                                                                                                                                                                                                                                                   
  F-35 Scope coherence: progress notifier "localized via bundle" presupposes cs bundle has every progress key on day 1
                                                                                                                                                                                                                                                        
  - Severity: minor
  - Category: scope coherence / verification                                                                                                                                                                                                            
  - Location: messaging.md §"Progress notifications" (line 88–94); D43; verification.md
  - Confidence: medium                                                                                                                                                                                                                                  
  
  What the spec says. "Stage strings are looked up by enum from the deterministic localization bundle." D43: v1 ships en and cs.                                                                                                                        
                  
  Why it's a problem. No spec requirement says "every progress-event enum value has a bundle entry in every shipped language" — meaning a cs user could get a falls-back-to-en progress string mid-stream, which is presentation-layer non-determinism  
  that contradicts the cleanly-translated UX the spec promises. Verification.md doesn't test bundle-completeness.
                                                                                                                                                                                                                                                        
  Suggested resolution. Add to D43 / llm.md §Translation flow: "Each shipped language bundle MUST be complete with respect to the localization-key registry. CI fails on missing keys for any shipped language." Add a verification line.               
  
  ---                                                                                                                                                                                                                                                   
  F-36 /save <uid> cap behavior on the boundary is ambiguous
                                                                                                                                                                                                                                                        
  - Severity: nit
  - Category: ambiguity                                                                                                                                                                                                                                 
  - Location: commands.md /save (lines 78–85); D13                                                                                                                                                                                                      
  - Confidence: medium
                                                                                                                                                                                                                                                        
  What the spec says. "Each user's saved-post library is bounded by a profile-driven per-user cap. … A /save that would exceed the cap returns a friendly error pointing the user at /unsave."                                                          
                                                                                                                                                                                                                                                        
  Why it's a problem. Race-free? If a user's count is at cap-1 and two /save commands fire in quick succession, naive implementations admit both. The spec doesn't say the cap is atomic.                                                               
                  
  Suggested resolution. Add: "The cap is enforced atomically (e.g. via a CHECK constraint on a derived counter or a SELECT … FOR UPDATE pattern). Concurrent saves cannot exceed the cap." Verification line: "two concurrent /save calls at cap-1      
  result in exactly one success."
                                                                                                                                                                                                                                                        
  ---             
  3. Cross-cutting observations
                               
  - Failure handling for read-only side branches is consistently underspecified. F-12 (/compress LLM failure), F-23 (no /source-enable command), F-15 (digest "overload" undefined), and F-27 (pruner runs?) all share the pattern: the spec specifies
  the happy path, names the failure stages, but leaves the user-visible / operator-visible recovery story to inference. Similar gaps exist around translation-cache eviction and re-evaluation backoff.                                                 
  - Cross-adapter and cross-scope semantics are stated abstractly but not enforced. F-04 (admin contact id binding), F-10 (chat-memory cross-scope leakage), F-21 (translation cache content equivalence), and F-31 (--all source listing) all share the
   shape: "scopes are isolated" is asserted, but the transitive closure of what each shared resource can leak is not.                                                                                                                                   
  - The asset-commands subsystem fits the existing SPI uneasily. F-02 (no source row), and the asymmetric Provider→Collector contract for price_snapshot (Provider does direct DB read instead of going through the post outbox) suggest the Fetcher SPI
   is being stretched. This is fine for v1 but the seams should be explicit so v2's TickerStream SPI doesn't have to retrofit.                                                                                                                          
  - The MVP is well-defined; v1 expands it in ways MVP didn't preview. D44 (invite codes) and D45 (slow-start) are v1-only and not exercised in MVP. The MVP exit criteria (00-mvp.md §6) auto-register users — directly contradicted by D44's
  invite-code requirement once v1 lands. The MVP slice is fine in isolation; what's missing is a "v1 expansion path" doc that says "after MVP, you must add the invite-code check before re-running MVP test #3."                                       
  - Decisions log is becoming the load-bearing reference but isn't disciplined enough for that role. F-03 (numbering), F-19 (D38 inconsistency with itself), and F-30 (D44 vs commands.md) all stem from the same pattern: decisions are added by
  amendment without sync-checking the section files they affect.                                                                                                                                                                                        
                  
  ---                                                                                                                                                                                                                                                   
  4. Spec evaluation
                    
  Completeness. ~85%. The trust path, ingest pipeline, command catalogue, and SPI shapes are covered. Gaps cluster around (a) failure modes of the periphery (compress, source-recovery commands, pruner registration), (b) cross-adapter semantics, and
   (c) asset-commands integration with the existing SPI.                                                                                                                                                                                                
   
  Consistency. Mostly good, with a few sharp contradictions: F-01 (Signal in/out of v1), F-06 (Nostr kind filter ordering), F-07 (BENIGN verdict semantics differ between first-pass and re-eval), F-19 (Source identity tuple). Each of these is       
  fixable in a few lines.
                                                                                                                                                                                                                                                        
  Implementability. A senior engineer can build from this with an estimated 5–10 follow-up clarifications. The well-structured spec/design split helps a lot — most ambiguities are at the spec level and are acknowledged ("value lives in design      
  notes") rather than in design.
                                                                                                                                                                                                                                                        
  Testability. verification.md is more thorough than typical, but F-25, F-26, F-27, F-35, F-36 surface specific gaps. The matrix-test pattern (every command × actor) needs probation × probation-clear as another dimension.                           
   
  Evolvability. The spec/design split is well-defended. The single-instance topology (D41) is honest about its limits and points at v2. The biggest evolvability risk is the decisions log becoming a wiki-like dumping ground (see cross-cutting       
  observation 5) — disciplining that now is cheap.
                                                                                                                                                                                                                                                        
  ---             
  5. Pros and cons
                  
  Pros (preserve these):
                                                                                                                                                                                                                                                        
  - Determinism boundary stated, repeated, and respected throughout — /retry semantics, /summary cluster computation, the LLM tool allowlist.                                                                                                           
  - spec/design layering with the "value vs. commitment" rule is genuinely useful and applied consistently.                                                                                                                                             
  - Trust-path discipline: ban check before parser, no LLM in authorization, sanitizer on every LLM-authored surface.                                                                                                                                   
  - Failure-mode taxonomy (verdict vs infrastructure split) is an unusually clean primitive.                                                                                                                                                            
  - D41 (single-instance) honesty avoids a v1 build that pretends to be horizontally scalable.                                                                                                                                                          
  - Verification.md exists and is mostly tied to spec invariants, not vibes.                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  Cons (prioritized):                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  - Signal adapter / asset Fetcher integration / D43 ordering — the three blocker- or major-tier inconsistencies that need fixing before a v1 build starts.                                                                                             
  - chat_session retention is the largest practical gap in the data-minimization story (F-05).
  - Decisions log discipline (F-03, F-19, F-30) — small fix, big future payoff.                                                                                                                                                                         
  - Probation list overreaches (/forget, /lang blocked) — fixable but they signal that the slow-start tier was designed without walking through new-user UX.                                                                                            
  - Cross-scope/cross-adapter invariants are stated in security.md but not enforced in verification.md (F-10, F-25).                                                                                                                                    
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  6. Recommended next actions                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  In order:
                                                                                                                                                                                                                                                        
  1. Resolve F-01 (Signal in or out). Highest ROI: a single decision unblocks deployment.md, messaging.md, D44 cross-adapter clauses. ~2 hours of spec writing.                                                                                         
  2. Resolve F-02 (Asset Fetcher scheduling/identity). Add ghost source rows. Pulls failure model under D42, fixes /list-sources --all for asset feeds.
  3. Reconcile contradictions F-06 (kind-filter ordering), F-07 (BENIGN semantics), F-19 (source identity tuple). All small wording fixes.                                                                                                              
  4. Discipline the decisions log (F-03): sort, append-only policy, +sync-check-on-amend.                                                                                                                                                               
  5. Close the data-minimization gap on chat_session (F-05) and fix probation overreach for /forget and /lang (F-17, F-18). Together these strengthen the privacy story for negligible spec cost.                                                       
  6. Add the cross-scope isolation invariant for chat memory (F-10) and verification entries for it (F-25, F-26).                                                                                                                                       
  7. Specify failure modes for /compress auto-trigger (F-12) and add a /source-enable command or document the psql-only path (F-23).                                                                                                                    
  8. Add the field-level positive list for /export (F-09) and a permission-matrix-derived sanitizer rule (F-11).                                                                                                                                        
                                                                                                                                                                                                                                                        
  After (1)–(4), the spec is implementable for a v1 build with no remaining blockers. Items (5)–(8) are prerequisites for the privacy story being externally defensible.
