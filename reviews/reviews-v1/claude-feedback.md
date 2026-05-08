● Honest Evaluation of the Three Review Reports                                                                                                                                          
                                              
  I read all three reports (mimo, kimik, gpt) and verified the major claims against the actual spec files. Here is my consolidated, honest assessment — what to fix, what to push back   
  on, and what to defer.                                          
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  TL;DR                                                                                                                                                                                  
                                                                                                                                                                                         
  The three reviews substantially agree on the real problems but differ in tone:                                                                                                         
                                                                                                                                                                                         
  - mimo is the most pragmatic and concrete — clean ranking, no fluff. Best starting point.
  - kimik is the most technically sharp — caught the partition-key bug and a real schema duplication bug that the others missed or only hinted at.
  - gpt is the deepest on policy/contract issues (security failure semantics, scope-naming consistency) and the most agentic-development-aware, but is also the most prescriptive — a few
   of its suggestions over-engineer for v1.                    
                                                                                                                                                                                         
  The spec is good as a design document but has roughly 8–10 verifiable contradictions that would actively break implementation. Those must be fixed. Most of the rest is opinion or     
  scope creep.                                                                                                                                                                           
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  A. CONFIRMED BUGS — Fix before any code is written                                                                                                                                     
                                                                                                                                                                                         
  I verified each of these directly in the docs.
                                                                                                                                                                                         
  A1. post_reference partition + PK is invalid Postgres (kimik §3) — REAL BUG, MUST FIX
                                                     
  02-schema.md:240–247 declares PRIMARY KEY (from_post, to_post, link_type) while PARTITION BY RANGE (created_at). Postgres will refuse to create this table. The partition column must  
  be in every unique constraint, including the PK.             
                                                                                                                                                                                         
  Fix: Change PK to (from_post, to_post, link_type, created_at). Same fix needed for post_embedding (line 224–225: PK on (post_id) but partitioned by fetched_at). Adopt as written.     
                                                                                                                                                                                         
  A2. scope_tag table defined twice (kimik §1) — REAL BUG                                                                                                                                
                                                               
  02-schema.md:129–134 and 136–141 are both CREATE TABLE scope_tag. Schema migrations would fail.                                                                                        
                                                                                                                                                                                         
  Fix: Delete the duplicate. Adopt.                  
                                                                                                                                                                                         
  A3. scope_kind enum values disagree across schema (gpt §11, kimik) — REAL BUG
                                                                                                                                                                                         
  - Line 117 (source_subscription): 'user' or 'group'
  - Line 312 (chat_memory): 'dm' or 'group'                                                                                                                                              
                                                     
  Fix: Pick one canonical value set everywhere. I agree with gpt's recommendation: use dm | group, because that matches product language (the SPEC.md glossary already says "DM" and     
  "group chat") and avoids the awkward "scope_kind=user" wording. Update every CREATE TABLE comment and any Java enum to match. Adopt.
                                                                                                                                                                                         
  A4. post.source_id ON DELETE CASCADE vs saved_post (mimo 2.9, gpt §3) — REAL BUG
                                                                                                                                                                                         
  post.source_id is ON DELETE CASCADE (line 162). saved_post.post_id is ON DELETE RESTRICT (line 293). If anyone has /saved a post from a source, /remove-source will throw.             
                                                                                                                                                                                         
  The text in 03-commands.md:205 even describes the fix ("orphan-tolerant via a soft reference") but the DDL doesn't match.                                                              
                                                                                                                                                                                         
  Fix: Either:                                       
  - (a) Make post.source_id ON DELETE SET NULL and add deleted_at to source (gpt's soft-delete proposal), or                                                                             
  - (b) Keep ON DELETE RESTRICT and have /remove-source only soft-delete (set deleted_at, stop fetching).   
                                                                                                                                                                                         
  Recommend (b) — soft-delete is simpler, preserves history correctly, matches the existing TTL-based pruning model, and lets you reactivate sources. Don't add READY_REDACTED and
  deleted_by and a whole new role just to satisfy edge cases. Adopt the principle, not all of gpt's specific schema additions.                                                           
                                                     
  A5. Stage 2 security failure policy contradicts itself (gpt §1) — REAL CONTRADICTION                                                                                                   
                                                               
  - SPEC.md:60 and 01-architecture.md:83: "1 retry → quarantine. Never falls through to READY."                                                                                          
  - 04-security.md:92: "if still UNKNOWN/fail → leave Stage 1 redactions in place, set status='READY' (degraded but safe)."                                                              
                                                                                                                                                                                         
  These cannot both be true. This is security-critical — agents will pick whichever they read last.                                                                                      
                                                     
  Fix: Pick one and propagate. My recommendation: stick with the 04-security.md behavior ("READY with Stage 1 redactions on Stage 2 LLM failure"). Reasoning:                            
  - Stage 1 already stripped the dangerous spans deterministically. Stage 2 is a secondary judge.
  - Quarantining on every LLM hiccup means Ollama crashes → entire feed dark.                                                                                                            
  - Update SPEC.md cross-cutting table row 16 to say: "Security stage: Stage 1 hit → Stage 2; Stage 2 INJECTION/MALWARE → quarantine; Stage 2 UNKNOWN/LLM-fail after retry → release with
   Stage 1 redactions, admin notify."                                                                                                                                                    
  - gpt's READY_REDACTED status is over-engineered for v1 — stage1_flagged=true already exists on post (line 172) and serves the same purpose.                                           
                                                                                                                                              
  Adopt the contradiction fix, reject the new status enum value.                                                                                                                         
                                                               
  A6. Worker-count contradiction (kimik §1, gpt by implication) — REAL CONTRADICTION                                                                                                     
                                                                                                                                                                                         
  - 01-architecture.md (text): "1 on pi, otherwise max(2, groups/10)"                                                                                                                    
  - 01-architecture.md:240 and 05-llm-and-embeddings.md:438: fixed 4 / 2 / 1 / 8 per profile                                                                                             
                                                                                                                                                                                         
  Fix: Use the fixed profile values. They match the rest of the profile-driven model. Delete the max(2, groups/10) line from 01-architecture.md. Adopt.                                  
                                                                                                                                                                                         
  A7. quarkus.http.port collision in shared properties example (kimik §3) — REAL BUG                                                                                                     
                                                               
  07-deployment.md shows both ports in one file; last-key-wins means both services land on 8081. Split into per-service application.properties snippets in the doc. Adopt.               
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  B. SHOULD FIX — Real ambiguities that will block implementation                                                                                                                        
                                                                 
  B1. SSRF protection on /add-source (mimo 1.1, gpt §9) — ADOPT                                                                                                                          
                                                     
  Both reviews are right. "Valid URL" is not enough — feed fetchers are a textbook SSRF surface. Add to 04-security.md:                                                                  
                                                     
  - http/https only                                                                                                                                                                      
  - Reject RFC1918, loopback, link-local, multicast, CGNAT, and cloud metadata IPs (169.254.169.254, fd00::/8, etc.)
  - Resolve DNS, recheck the resolved IP before connect (defends against DNS rebinding)                                                                                                  
  - Cap redirects (≤3), revalidate each                                                                                                                                                  
  - Cap response body size, decompressed size, item count, fetch duration                                                                                                                
                                                                                                                                                                                         
  This is a 30-minute spec change with high security ROI. Adopt.
                                                                                                                                                                                         
  B2. scope_tag is duplicated and its meaning is unclear (gpt §16) — ADOPT (light version)                                                                                               
                                                                                                                                                                                         
  After dedup, you still have the implicit-vs-explicit /follow-tag mode problem. gpt's suggestion of a tag_follow_mode column in scope_preferences is correct. The first call to         
  /follow-tag should copy the implicit set to explicit, then mutate. Adopt.                                                                                                              
                                                                                                                                                                                         
  B3. body_summary generation is referenced but never defined (mimo 2.5, kimik §3) — ADOPT                                                                                               
                                         
  It's used in the embedding pipeline but no doc says when it's generated, by which model, or the threshold. Pick: "Generated during eval pipeline after tagging, before embedding, using
   the summarizer model. Trigger: body_length > 2000 chars. Fallback on LLM failure: use first 800 chars verbatim." Adopt.
                                                                                                                                                                                         
  B4. /clear group semantics (all three reviews) — ADOPT (with gpt's wording)                                                                                                            
                                 
  All three reviewers flag the "shared context for that user" wording as nonsensical given the per-(user, scope) PK on chat_session. gpt's fix is correct: regular group members can     
  /clear their own (user, group) session. Group admin status is irrelevant for a per-user resource. Drop the "group admin only" requirement for /clear and /compress in groups. Adopt.
                                                                                                                                                                                         
  B5. socials tag behavior is unspecified (mimo 2.3) — ADOPT                                                                                                                             
                                 
  Trivially fixable: state explicitly that socials is a Tier-1 tag, auto-assigned when source.category='social', included in the bootstrap vocab, and followable via /follow-tag socials.
   One paragraph in 02-schema.md. Adopt.                       
                                                                                                                                                                                         
  B6. /summary post cap: 200 vs profile-dependent (mimo 2.1) — ADOPT                                                                                                                     
                                 
  03-commands.md:144 says hard 200; 05-llm-and-embeddings.md:440 says 200/100/50/500 by profile. The profile-dependent value is clearly the intended design (the smaller pi cap exists   
  for a reason). Change 03-commands.md to reference infochat.summary.cluster-cap. Adopt.
                                                                                                                                                                                         
  B7. Bidi-control stripping in Provider intake (mimo 1.2) — ADOPT                                                                                                                       
                                 
  Cheap fix, real gap. Stripping bidi controls (U+202A–U+202E, U+2066–U+2069) in Stage 1 only protects ingested posts, not user-typed chat messages. A user can type bidi-control text   
  into a group chat and visually spoof bot output. Add a single normalization step in the Provider's message intake. ~10 lines of code. Adopt.
                                                                                                                                                                                         
  B8. Input length limits (mimo 1.3) — ADOPT, BUT BUDGET-DRIVEN                                                                                                                          
                                 
  Don't pick numbers out of the air. Tie them to the profile's context window so you don't blow tokens on a 100KB chat message:                                                          
  - chat message: profile.context_window / 8 in chars (≈2KB on pi, ≈4KB on laptop)
  - --name: 200 chars                                                                                                                                                                    
  - --reason, --note: 500 chars                                                                                                                                                          
  - personal_tags total: 200 chars                   
                                                                                                                                                                                         
  Adopt.                                             
                                                                                                                                                                                         
  B9. ReDoS / regex timeout on Stage 1 (kimik §2) — ADOPT
                                                                                                                                                                                         
  Java's default Pattern has no timeout. RE2j (com.google.re2j) gives linear-time guarantees and is a one-line dependency change for the ingest path. Either RE2j or wrap with a watchdog
   at 100ms. Adopt.                                                                                                                                                                      
                                                                                                                                                                                         
  B10. Topic ID stability promise is too strong (gpt §17) — ADOPT (weakened version)                                                                                                     
                                                                                                                                                                                         
  Connected components shift as post_reference partitions are dropped. Either persist a topic table or weaken the promise. Weaken for v1: "Topic IDs are stable within a 60-minute cache 
  window." Don't build a topic-persistence subsystem in v1. Adopt the weakening.
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  C. WORTH DOING — Spec-quality fixes
                                                                                                                                                                                         
  C1. 1m ambiguity (mimo 2.7) — PARTIAL ADOPT
                                                                                                                                                                                         
  I disagree with gpt/kimik that 1m is a problem in practice (the table at 03-commands.md:14–21 defines it explicitly), but I agree with mimo that m=minutes is so universal that any
  user typing -w 1m expects 60 seconds. Just remove 1m from the accepted set; users who want 30 days can type 30d. Cleaner than introducing capital-M. Adopt removal.
                                                                                                                                                                                         
  C2. Pagination on /saved (mimo 3.1) — ADOPT                  
                                                                                                                                                                                         
  1000 entries with no pagination on a 4KB-message platform produces dozens of fragments. Add --page N (default page size 20). Adopt.                                                    
                                                                                                                                                                                         
  C3. Confirmation syntax: slash-prefix consistency (gpt §19) — ADOPT                                                                                                                    
                                                                                                                                                                                         
  03-commands.md:32-36 shows clear confirm (no slash); the project rule is slash-only. Change examples to /clear confirm. Adopt.
                                                                                                                                                                                         
  C4. source.added_by ON DELETE behavior (kimik §3) — ADOPT
                                                                                                                                                                                         
  Specify ON DELETE SET NULL so deleting a user doesn't cascade through their sources. Adopt.
                                                                                                                                                                                         
  C5. Startup bean ordering (kimik §5) — ADOPT       
                                                                                                                                                                                         
  Document @Priority ordering: Flyway → BootstrapLoader → AdminBootstrap → OutboxRehydrator. Worth 3 lines in 07-deployment.md. Adopt.
                                                                                                                                                                                         
  C6. searchByTag row cap (kimik §2) — ADOPT         
                                                                                                                                                                                         
  Cap the LLM-callable tool at 200 rows (matching /summary). Defense-in-depth against an injected agent exfiltrating bulk data. Adopt.
                                                                                                                                                                                         
  C7. Drop-newest queue eviction (kimik §4) — ADOPT  
                                                                                                                                                                                         
  06-messaging.md says drop oldest on overflow. That kills the user's command and keeps their "are you there?" follow-up. Drop newest, return a friendly throttle message. Adopt.
                                                                                                                                                                                         
  C8. Prompt-injection wrapper canonicalization (gpt §7) — ADOPT
                                                                                                                                                                                         
  Different docs use <<<UNTRUSTED>>>, <<<UNTRUSTED_CONTENT>>>, and other variants. Pick one, define escaping rules, randomize the delimiter token per-call. Adopt.
                                                                                                                                                                                         
  C9. Welcome message + onboarding UX (kimik §4) — ADOPT
                                                                                                                                                                                         
  The "or just chat with me about a topic" pitch on a brand-new user with zero sources will produce "I couldn't find anything." Replace the welcome message with a /help plus a one-line
  "you have no sources yet — try /add-source <url> --tags news." Adopt.                                                                                                                  
                                                     
  ---                                                                                                                                                                                    
  D. PUSH BACK — Suggestions I would NOT adopt as written
                                                                                                                                                                                         
  D1. gpt §2: separate eval_stage column with five values — REJECT
                                                                                                                                                                                         
  The current schema (status='RAW' | 'EVALUATING' | ...) is sufficient if you accept that the rehydrator simply re-runs the whole pipeline for EVALUATING rows. The pipeline stages are
  idempotent (security check is deterministic, tagger overwrites, embedding overwrites). Adding a parallel eval_stage enum doubles the state machine without removing real bugs. Keep 
  current schema; just clarify the rehydrator scans RAW or EVALUATING.                                                                                                                   
                                                               
  D2. gpt §6: split /quarantine approve into three commands — REJECT for v1                                                                                                              
                                                                                                                                                                                         
  Three separate verbs (approve-redacted, restore, reject) is correct for an enterprise moderation tool. For v1, with one or two bot admins, a single /quarantine approve <id> plus the  
  existing /quarantine reject is enough. Defer to v2.                                                                                                                                    
                                                     
  D3. gpt §8: full "remote LLM privacy" subsystem — PARTIAL                                                                                                                              
                                 
  A /privacy user-facing command, opt-in flags, etc. is overkill. Adopt only: a startup log line listing active providers, and a sentence in 04-security.md saying remote providers see  
  post text and chat content. The rest is v2.                  
                                                                                                                                                                                         
  D4. mimo 1.4 / gpt §20: bot-admin gating on new tag creation — REJECT for DM, ADOPT for group                                                                                          
                                 
  In a DM, the user is the only consumer of their tag space. Letting them create tags freely is fine. In a group, however, tags are shared, and /add-source --tags foo from any non-admin
   pollutes everyone's vocabulary. The spec already requires group admin for /add-source in groups, so this is already handled — no change needed. Reject mimo's recommendation; the 
  existing permission split is correct.                                                                                                                                                  
                                                                                                                                                                                         
  D5. mimo 1.5 / gpt §6: require --reason for /quarantine approve — REJECT
                                                                                                                                                                                         
  Friction for friction's sake. The --note field is optional; admins know what they're doing. v2 if abuse happens. Defer.
                                                                                                                                                                                         
  D6. mimo 1.6: SHA-256 collision in translation cache — REJECT
                                                                                                                                                                                         
  This is not a real concern. SHA-256 collisions are computationally infeasible. The reviewer flagged it as LOW themselves. Reject.
                                                                                                                                                                                         
  D7. All three reviews: "Add 00-tasks.md / TASKS.md / requirement IDs" — PARTIAL
                                                                                                                                                                                         
  A task decomposition is genuinely useful, but each review prescribes a different format. My recommendation: produce a single docs/00-mvp.md with the v1 critical-path subset (kimik's
  "MVS" idea) — that's high-value. Skip the heavy [REQ-AUTH-001] requirement-ID system; it's bureaucracy that pays off only in regulated environments. Adopt the MVP doc, reject the     
  requirement-ID scheme.                             
                                                                                                                                                                                         
  D8. mimo 2.10 / gpt §21: split /status into user/admin views — PARTIAL
                                                                                                                                                                                         
  Hiding profile/uptime from users is reasonable. Building a separate /admin-status command is not necessary in v1 — just gate the admin-only fields inside the existing /status handler.
   Adopt the gating, reject the new command.                                                                                                                                             
                                                     
  D9. gpt §22: timed confirmation of first-mention group admin — REJECT for v1                                                                                                           
                                 
  Overcomplicates a workable bootstrap. Document the behavior, allow bot-admin override (/promote, /demote) — already in spec. Keep current design.                                      
                                                               
  D10. gpt §13: normalize bootstrap_tags into a join table — REJECT                                                                                                                      
                                                                                                                                                                                         
  A TEXT[] column that mirrors tag.name values is fine. Postgres GIN indexes text[] well. A second join table just to model the same data is over-normalization for a small bootstrap
  config. Keep TEXT[], but add a constraint comment that values must exist in tag.name.                                                                                                  
                                                     
  D11. mimo 1.7: CSRF on /q/health — REJECT (deployment guidance only)                                                                                                                   
                                         
  This is not a CSRF issue; it's an exposure issue. Just add a sentence in 07-deployment.md saying the management endpoints should be bound to localhost or behind a reverse proxy. Adopt
   that sentence, reject the CSRF framing.                     
                                                                                                                                                                                         
  D12. kimik §2: drop quarantine approval rate limit from 100/min to 10/min — REJECT                                                                                                     
                                 
  100/min is fine. A bot admin reviewing the daily backlog after a regex update could legitimately approve 50–100. 10/min creates artificial friction. Reject.                           
                                                               
  ---                                                                                                                                                                                    
  E. Things ALL three reviewers got right that should be fast wins                                                                                                                       
                                                                  
  These are the cheapest, highest-value fixes:                                                                                                                                           
                                                     
  1. Dedup scope_tag (5 minutes)                                                                                                                                                         
  2. Fix partition PKs on post_reference and post_embedding (15 minutes)
  3. Pick dm | group everywhere (30 minutes find-and-replace)                                                                                                                            
  4. Reconcile /summary cap (5 minutes)                        
  5. Fix quarkus.http.port example in deployment doc (5 minutes)                                                                                                                         
  6. Pick one Stage 2 LLM-failure policy and propagate (30 minutes)                                                                                                                      
  7. SSRF allowlist for /add-source (1 hour spec, then matters at impl)                                                                                                                  
                                                                                                                                                                                         
  That alone resolves the majority of "agent gets confused and stalls" risk.                                                                                                             
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  F. Comparing the three reviewers                                                                                                                                                       
                                                                                                                                                                                         
  ┌──────────┬────────────────────────────────────────────────────────────────────────────────┬──────────────────────────────────────────────────────────────────────────────────────┐   
  │ Reviewer │                                    Strength                                    │                                       Weakness                                       │   
  ├──────────┼────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────┤   
  │ mimo     │ Most pragmatic priority ranking; best at separating MUST-FIX from nice-to-have │ Missed the partition-PK bug; missed scope_kind enum mismatch                         │
  ├──────────┼────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────┤   
  │ kimik    │ Sharpest on schema/Postgres correctness; caught the partition PK and table dup │ Some recommendations are over-tightening (10/min approval limit)                     │
  │          │  bugs                                                                          │                                                                                      │   
  ├──────────┼────────────────────────────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────┤
  │ gpt      │ Best on cross-doc contradictions, naming consistency, agentic-development      │ Tendency to over-engineer (READY_REDACTED, three-way quarantine split,               │
  │          │ affordances                                                                    │ requirement-ID system)                                                               │
  └──────────┴────────────────────────────────────────────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────┘   
                                                                                                                                                                                         
  If you read only one, read mimo. It's the cleanest action list. Then mine kimik for the schema/PK bugs and gpt for the policy-contract issues.                                         
                                                                                                                                                                                         
  ---                                                
  G. Recommended order of operations                                                                                                                                                     
                                         
  1. Critical fixes (A1–A7) — blocking; do first.                                                                                                                                        
  2. Should-fix (B1–B10) — before each module's work begins.
  3. Quality fixes (C1–C9) — folded into normal doc passes.                                                                                                                              
  4. Skip (D1–D12) — explicitly note in the spec changelog that these were considered and deferred, so the issues don't get re-raised.
  5. Add a small docs/00-mvp.md listing the minimal v1 critical path: schema + RSS fetcher + eval pipeline (security+tag+embed) + InMemoryAdapter + /help//add-source//summary. Defer
  translation, periodic digests, quarantine UI, and /audit to "after MVP works end-to-end."
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  Honest bottom line                                                                                                                                                                     
                                                                                                                                                                                         
  The spec is above-average for an LLM-assisted greenfield project — most projects don't have this much structure. The reviewers are not wrong that it has contradictions, but they      
  sometimes catastrophize ("medium-risk for implementation"). The truly blocking issues number about 7, all listed in section A, all fixable in a half-day of focused editing. The       
  remaining 60+ findings across the three reports are real but mostly polish or scope-creep candidates.                                                                                  
                                                     
  You don't have a foundation problem. You have a consistency-pass problem. Do section A today, section B before coding starts, ignore most of section D, and you're in good shape. 
