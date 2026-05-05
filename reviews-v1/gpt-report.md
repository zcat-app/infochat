Overall: the spec is strong as a product/architecture overview, but not yet safe as an implementation contract for agentic development. The biggest issue is not missing detail; it is 
  contradictory detail across files, especially around security-stage failure handling, schema/status enums, source deletion, scope naming, and command permissions.
                                                                                                                                                                                         
  High-priority findings                                                                                                                                                                 
                                                                                                                                                                                         
  1. Security-stage failure policy contradicts itself                                                                                                                                    
                                         
  Where:

  - docs/SPEC.md:60 says security stage failure is 1 retry → quarantine.
  - docs/01-architecture.md:81-82 says Stage 2 security judge failure after retry quarantines.
  - docs/04-security.md:89-93 says Stage 2 UNKNOWN or LLM failure after retry releases the post as READY with redactions preserved.
  - docs/04-security.md:276-283 repeats that Stage 2 failure releases redacted content.

  Why this matters:

  This is a security-critical behavior. An implementation agent could reasonably choose either:

  1. fail closed: quarantine the whole post, or
  2. degrade safely: show redacted post.

  Both are defensible, but the spec must choose one.

  Recommendation:

  Pick one policy and make it canonical everywhere. I would recommend:

  ▎ Stage 2 LLM failure after retry releases only the Stage-1-redacted body as READY_REDACTED or READY with stage1_flagged=true; original content is never restored unless explicitly
  ▎ approved.

  Reason: this preserves availability without releasing suspicious original content. If you want stricter security, choose quarantine instead, but then update 04-security.md to match.

  ---
  2. Post status enum is inconsistent across spec, architecture, and schema

  Where:

  - docs/SPEC.md:60 lists per-stage statuses like STAGE2_DONE, TAGGED, ENTITIES_DONE, EMBEDDED.
  - docs/01-architecture.md:55-63 also describes per-stage progression.
  - docs/02-schema.md:170 defines only 'RAW','EVALUATING','READY','QUARANTINED','FAILED'.
  - docs/01-architecture.md:76-78 says rehydrator scans RAW and EVALUATING, not the per-stage statuses.
  - Earlier overview text mentions re-enqueueing at the last completed stage, but the schema does not store that stage precisely.

  Why this matters:

  The outbox/rehydration design depends on knowing exactly where a post failed or paused. With only EVALUATING, the system cannot reliably resume at “tagger done but embedding not done”
   unless there is another stage field.

  Recommendation:

  Use either:

  status TEXT CHECK status IN ('RAW','EVALUATING','READY','READY_REDACTED','QUARANTINED','FAILED'),
  eval_stage TEXT CHECK eval_stage IN ('SECURITY','TAGGING','ENTITIES','EMBEDDING','FINALIZING')

  or use one canonical status enum with every stage represented.

  For agentic development, the first option is clearer: status describes user visibility, eval_stage describes pipeline progress.

  ---
  3. Source deletion/removal behavior is contradictory and potentially dangerous

  Where:

  - docs/SPEC.md:46 describes soft-delete with global source plus subscriptions.
  - docs/SPEC.md:52 says /add-source creates sources/subscriptions.
  - docs/03-commands.md:201-204 says /remove-source deletes globally, but then says posts are kept and source link is “orphan-tolerant”.
  - docs/02-schema.md:159-161 defines post.source_id UUID NOT NULL REFERENCES source(id) ON DELETE CASCADE.
  - docs/02-schema.md:84-98 defines source, but the table shown does not include deleted_at, despite soft-delete being part of the spec.

  Why this matters:

  With the current schema, deleting a source can cascade-delete all posts from that source. That contradicts the UX text and could cause unintended data loss.

  Recommendation:

  Make source removal explicitly soft-delete:

  ALTER TABLE source ADD COLUMN deleted_at TIMESTAMPTZ;
  ALTER TABLE source ADD COLUMN deleted_by UUID REFERENCES users(id);

  And change post.source_id to preserve historical posts:

  source_id UUID REFERENCES source(id) ON DELETE RESTRICT
                                         
  or keep the FK and never physically delete sources until all dependent posts age out.
                                     
  Command behavior should say:           
                                     
  ▎ /remove-source sets source.deleted_at, disables fetching, keeps existing posts until normal TTL, and removes or disables subscriptions after the grace period.                       
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  4. DB role description is incomplete                                                                                                                                                   
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/02-schema.md:8-10 says provider “Reads everything except” and then stops.                                                                                                       
  - docs/04-security.md:328-340 has a fuller least-privilege table.
                                                                                                                                                                                         
  Why this matters:                  
                                                                                                                                                                                         
  This is a security-sensitive boundary. The provider must not read raw quarantine HTML if chat output could expose it.                                                                  
                                     
  Recommendation:                                                                                                                                                                        
                                         
  Complete the schema doc wording:                                                                                                                                                       
                                                                                                                                                                                         
  ▎ infochat_provider reads all user-facing tables and collector-owned post metadata, but cannot select quarantine.original_html; it reads only quarantine_review.
                                                                                                                                                                                         
  Also specify migrations are run by a separate migration/admin role, not by the app roles, if that is intended.
                                                                                                                                                                                         
  ---                           
  5. Banned-user “no DB access” wording is impossible as written                                                                                                                         
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/SPEC.md:50 says banned users receive one fixed response and never reach “any DB query beyond the ban check”.                                                                    
  - docs/04-security.md:222 says no DB queries beyond ban check.
                                                                                                                                                                                         
  Issue:                                 
                                                                                                                                                                                         
  The ban check itself requires resolving identity and querying users. That is fine, but the spec should also define whether last_seen_at, audit logs, rate-limit counters, or metrics   
  are updated for banned users.      
                                                                                                                                                                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Clarify:                                                                                                                                                                               
                                         
  ▎ For banned users, the provider performs only identity resolution and a single user/ban lookup. It does not update chat state, run command parsing, run LLM calls, query posts, or    
  ▎ mutate user-visible state. Metrics/logging may record a redacted rejected intake event.                                                                                              
                                
  This makes the testable boundary precise.                                                                                                                                              
                                         
  ---                                                                                                                                                                                    
  Security issues and hardening suggestions
                                                                                                                                                                                         
  6. /quarantine approve can restore prompt-injection content directly to users                                                                                                          
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/04-security.md:260-263 says approval restores original span and can set post READY.                                                                                             
  - docs/03-commands.md:270-272 says approve restores original span in post.body.
                                                                                                                                                                                         
  Why this matters:                                                                                                                                                                      
                                                                                                                                                                                         
  Approval is powerful. An admin could accidentally approve malicious text after seeing only a preview, especially because raw HTML is not visible through chat.                         
                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Split actions:                                                                                                                                                                         
                                                                                                                                                                                         
  - /quarantine approve-redacted <id>: keep redaction, mark reviewed.
  - /quarantine restore <id>: restore original, requires stronger confirmation.                                                                                                          
  - /quarantine reject <id>: keep placeholder.
                                                                                                                                                                                         
  Also require approval commands to show post title, source, fetch time, rule, and affected span count before confirmation.
                                                                                                                                                                                         
  ---                                    
  7. Prompt-injection wrappers are inconsistently documented                                                                                                                             
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/SPEC.md:59 uses delimiter wrappers.                                                                                                                                             
  - docs/01-architecture.md:131 uses <<<UNTRUSTED>>>.
  - docs/04-security.md:86 uses <<<UNTRUSTED_CONTENT>>>...<<<END>>>.                                                                                                                     
  - docs/04-security.md:109-111 shows placeholder <<>>, likely malformed.
  - docs/04-security.md:119 says wrapper ID is randomized per call.                                                                                                                      
                                                                                                                                                                                         
  Why this matters:                  
                                                                                                                                                                                         
  Prompt construction is an implementation-sensitive area. Agents need exact templates, not several variants.                                                                            
                                                                                                                                                                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Define one canonical format, e.g.:                                                                                                                                                     
                                                                                                                                                                                         
  <untrusted_content id="{uuid}">
  {escaped content}                                                                                                                                                                      
  </untrusted_content id="{uuid}">       
                                                                                                                                                                                         
  Then require:                          
                                                                                                                                                                                         
  - delimiter strings are generated per call,                                                                                                                                            
  - content is escaped or encoded so it cannot close the wrapper,                                                                                                                        
  - the model is told the wrapper body is data only,                                                                                                                                     
  - output must not include hidden instructions from the content.                                                                                                                        
                                         
  ---                                                                                                                                                                                    
  8. Remote LLM privacy is under-specified
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/SPEC.md:80 includes OpenAI-compatible and Anthropic providers.                                                                                                                  
  - docs/04-security.md:37-38 mentions embedding exfiltration risk.                                                                                                                      
  - docs/05 was not available in the inspected output, but the visible spec implies remote providers are supported.                                                                      
                                         
  Issue:                                                                                                                                                                                 
                                                                                                                                                                                         
  The spec does not clearly say which user data/post data may be sent to remote providers, whether this is opt-in, and whether group admins/users are informed.
                                                                                                                                                                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Add a “Remote provider data disclosure” section:                                                                                                                                       
                                                                                                                                                                                         
  - local-only default if privacy is a goal,
  - remote provider requires explicit config,                                                                                                                                            
  - startup logs a warning,              
  - /status admin view reports active providers,                                                                                                                                         
  - optionally /privacy tells users whether their chat content can leave the host,
  - redact or minimize content sent to remote LLMs where possible.                                                                                                                       
                                         
  ---                                                                                                                                                                                    
  9. /add-source URL validation needs stronger SSRF guidance                                                                                                                             
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/04-security.md:31-33 mentions source poisoning and huge/slow sources.                                                                                                           
  - docs/03-commands.md:173-176 says URL must be valid.                                                                                                                                  
                                                                                                                                                                                         
  Issue:                             
                                                                                                                                                                                         
  “Valid URL” is not enough. Feed fetchers are a classic SSRF surface.                                                                                                                   
                                     
  Recommendation:                                                                                                                                                                        
                                         
  Specify URL validation rules:                                                                                                                                                          
                                                                                                                                                                                         
  - allow only http/https,               
  - reject private, loopback, link-local, multicast, and metadata IP ranges,                                                                                                             
  - resolve DNS and re-check resolved IP before connect,
  - defend against DNS rebinding,    
  - limit redirects and revalidate each redirect target,
  - cap response size, decompressed size, item count, and fetch duration.                                                                                                                
                                         
  ---                                                                                                                                                                                    
  10. Admin contact lookup by <contact> is ambiguous
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/03-commands.md:254-264 uses /promote <contact>, /grant-admin <contact>, /ban <contact>.                                                                                         
  - docs/04-security.md:301-307 says display names are informational only.                                                                                                               
                                                                                                                                                                                         
  Issue:                             
                                                                                                                                                                                         
  The UX says <contact>, but does not define whether this is full SimpleX contact ID, short ID, display name, mention, or alias. Using display name would be unsafe.                     
                                     
  Recommendation:                                                                                                                                                                        
                                         
  Define contact resolution:                                                                                                                                                             
                                                                                                                                                                                         
  - admin commands accept only full contact ID or an exact previously-seen contact handle generated by the adapter,
  - display names are never accepted for authorization targets,                                                                                                                          
  - ambiguous matches are rejected,      
  - confirmation shows redacted contact ID and display name.                                                                                                                             
                                         
  ---                                                                                                                                                                                    
  Ambiguities and consistency issues     
                                                                                                                                                                                         
  11. Scope naming is inconsistent: user, dm, group, scope_type, scope_kind
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/SPEC.md:46 uses scope.                                                                                                                                                          
  - docs/SPEC.md:108 defines scope as user or group.
  - docs/02-schema.md:116-121 uses scope_kind values 'user' or 'group'.                                                                                                                  
  - docs/02-schema.md:311-312 uses scope_kind values 'dm' or 'group'.
  - docs/01-architecture.md:213 says every stateful row has scope_type and scope_id.                                                                                                     
                                         
  Why this matters:                      
                                                                                                                                                                                         
  Agentic implementation will likely create mismatched enums and broken queries.                                                                                                         
                                                                                                                                                                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Standardize everywhere:                                                                                                                                                                
                                                                                                                                                                                         
  scope_kind: 'dm' | 'group'             
  scope_id:                                                                                                                                                                              
    - for dm: users.id                   
    - for group: groups.id                                                                                                                                                               
                                         
  or:                                                                                                                                                                                    
                                                                                                                                                                                         
  scope_kind: 'user' | 'group'                                                                                                                                                           
                                                                                                                                                                                         
  Pick one. I recommend dm|group because it matches product language.                                                                                                                    
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  12. scope_tag table is duplicated                                                                                                                                                      
                                         
  Where:                                                                                                                                                                                 
                                
  - docs/02-schema.md:126-141 defines scope_tag twice.                                                                                                                                   
                                         
  Recommendation:                                                                                                                                                                        
                                     
  Remove the duplicate. Small issue, but it signals that schema snippets need cleanup before implementation.                                                                             
                                                                                                                                                                                         
  ---                           
  13. source table differs between SPEC and schema                                                                                                                                       
                                                  
  Where:                                                                                                                                                                                 
                                         
  - docs/SPEC.md:46-47 mentions per-scope subscription, source bootstrap, tags, soft delete.                                                                                             
  - docs/02-schema.md:84-98 lacks deleted_at.
  - docs/02-schema.md:90 uses bootstrap_tags TEXT[], but there is also a normalized tag table and post_user_tag.                                                                         
                                         
  Issue:                                                                                                                                                                                 
                                                                                                                                                                                         
  It is unclear whether source bootstrap tags are raw text, FK rows, or both.                                                                                                            
                                                                                                                                                                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Normalize this:                                                                                                                                                                        
                                                                                                                                                                                         
  source_bootstrap_tag (        
    source_id UUID REFERENCES source(id),                                                                                                                                                
    tag_id UUID REFERENCES tag(id),      
    PRIMARY KEY (source_id, tag_id)                                                                                                                                                      
  )                                      
                                                                                                                                                                                         
  If keeping TEXT[] for simplicity, say it is denormalized and must match tag.name.                                                                                                      
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  14. /clear group permission is confusing                                                                                                                                               
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/SPEC.md:67 says /clear wipes only active context window.                                                                                                                        
  - docs/03-commands.md:80 says group admin only in group.
  - docs/03-commands.md:230-231 says group admin clears the group’s shared context for that user, while other users’ contexts are untouched.                                             
  - docs/SPEC.md:68 says group memory is per-user/group.
                                                                                                                                                                                         
  Issue:                                                                                                                                                                                 
                                    
  If group context is per (user, group), a regular group member should probably be allowed to clear their own group context. Requiring group admin for a private per-user context is     
  surprising.                                                                                                                                                                            
                                                                                                                                                                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Change group /clear to:                                                                                                                                                                
   
  - group member: clears own (user, group) context,                                                                                                                                      
  - group admin: optionally can clear group-level digest/cache if such a thing exists, with a distinct command.
                                         
  Do not call it “group’s shared context” if it is per-user.
                                     
  ---                                                                                                                                                                                    
  15. /compress group permission has the same issue
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/03-commands.md:81 says group admin only.                                                                                                                                        
  - But memory is per (user, group).     
                                                                                                                                                                                         
  Recommendation:                    
                                                                                                                                                                                         
  Allow every group member to /compress their own group conversation memory. Keep group-wide memory out of v1 unless explicitly designed.                                                
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  16. /follow-tag default behavior needs a precise state model
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                
  - docs/03-commands.md:220 says fresh scope follows all tags from subscribed sources; calling /follow-tag switches to explicit list.                                                    
                                         
  Issue:                                                                                                                                                                                 
                                    
  This implies a hidden mode: implicit-follow-all vs explicit-follow-list. The schema only has scope_tag, not a preference flag.                                                         
                                                                                                                                                                                         
  Recommendation:               
                                                                                                                                                                                         
  Add to scope_preferences:              
                                                                                                                                                                                         
  tag_follow_mode TEXT NOT NULL DEFAULT 'implicit_source_tags'
                                                                                                                                                                                         
  Allowed values:                        
                                                                                                                                                                                         
  - implicit_source_tags                 
  - explicit                                                                                                                                                                             
                                                                                                                                                                                         
  Then define how /unfollow-tag behaves in implicit mode.                                                                                                                                
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  17. Topic ID stability is underspecified
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/SPEC.md:112 says Topic ID is stable for lifetime of cluster.                                                                                                                    
  - docs/02-schema.md:425 says no tier2_topic table; topic IDs are computed at query time from connected components and cached in memory.
                                                                                                                                                                                         
  Issue:                             
                                                                                                                                                                                         
  Computed connected components can change as links expire or new posts arrive. Stable topic IDs are hard without persistence.                                                           
                                                                                                                                                                                         
  Recommendation:                                                                                                                                                                        
                                         
  Either:                                                                                                                                                                                
                                                                                                                                                                                         
  1. weaken the promise: “Topic IDs are stable within a response/cache window”, or
  2. add a topic table with deterministic representative post ID.                                                                                                                        
                                         
  For v1, I recommend weakening the promise.
                                     
  ---                                                                                                                                                                                    
  18. Summary determinism is slightly overstated
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/SPEC.md:53 says /summary returns deterministic results.                                                                                                                         
  - docs/01-architecture.md:210 says same /summary security returns same set of posts twice in a row.
  - But summaries depend on now(), TTL, late fetches, and post status changes.                                                                                                           
                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Clarify:                                                                                                                                                                               
                                                                                                                                                                                         
  ▎ Determinism means the same database snapshot, scope, command arguments, and time-window anchor produce the same post set. Generated prose is not deterministic unless model          
  ▎ temperature and prompt are fixed.                                                                                                                                                    
                                                                                                                                                                                         
  Also consider anchoring command execution time and showing it in the response.
                                     
  ---                                                                                                                                                                                    
  UX findings                       
                                                                                                                                                                                         
  19. Confirmation examples omit slash prefix                                                                                                                                            
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/03-commands.md:32-36 says type clear confirm, not /clear confirm.
  - docs/SPEC.md:53 says <command> confirm.
                                                                                                                                                                                         
  Issue:                                 
                                                                                                                                                                                         
  The project’s core convention is slash-prefix only. A non-slash confirmation conflicts with this.                                                                                      
                                     
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Use slash-prefixed confirmations:                                                                                                                                                      
                                                                                                                                                                                         
  /clear confirm                         
  /remove-source <id> confirm                                                                                                                                                            
  /ban <contact> confirm                 
                                                                                                                                                                                         
  This is more consistent and easier to parse.
                                                                                                                                                                                         
  ---                                    
  20. /add-source --tags accepts new tags, but tags are also controlled vocabulary                                                                                                       
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/SPEC.md:43 says Tier 1 is controlled vocab.                                                                                                                                     
  - docs/03-commands.md:176 says new tag values are accepted and added to vocab on the spot.
                                                                                                                                                                                         
  Issue:                                 
                                                                                                                                                                                         
  If every user can add arbitrary Tier-1 tags in DM, the “controlled vocabulary” can become noisy quickly.                                                                               
                                     
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Decide whether Tier-1 is truly controlled:                                                                                                                                             
                                                                                                                                                                                         
  Option A:                              
                                                                                                                                                                                         
  - only bootstrap/admin can create global tags,
  - normal users must choose existing tags.                                                                                                                                              
                                
  Option B:                                                                                                                                                                              
                                         
  - users can create tags, but only scoped/private until promoted.                                                                                                                       
                                         
  For UX and quality, I recommend scoped user-created tags first, with admin promotion to global controlled vocabulary.                                                                  
                                         
  ---                           
  21. /status availability may leak operational info                                                                                                                                     
                                         
  Where:                                                                                                                                                                                 
                                         
  - docs/03-commands.md:65 allows /status for everyone.                                                                                                                                  
  - docs/03-commands.md:103-110 includes active hardware profile, uptime, source counts, post counts.
                                                                                                                                                                                         
  Issue:                                 
                                                                                                                                                                                         
  Basic status is probably fine, but profile and uptime can be fingerprinting data.                                                                                                      
                                     
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Split:                                                                                                                                                                                 
                                                                                                                                                                                         
  - /status: user-safe status, subscribed sources, recent post count.
  - /admin-status: profile, uptime, queue depth, pending quarantine, failure counts.                                                                                                     
                                         
  Or keep /status but hide operational fields from non-admins.
                                     
  ---                                                                                                                                                                                    
  22. Group first-mention auto-admin is usable but risky
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/SPEC.md:48 and docs/04-security.md:172-184.                                                                                                                                     
                                         
  Issue:                                                                                                                                                                                 
                                    
  The first person to mention the bot becomes group admin. That is simple, but in a large group it may not be the real group owner.                                                      
                                                                                                                                                                                         
  Recommendation:               
                                                                                                                                                                                         
  Keep it for v1, but add mitigation:    
                                                                                                                                                                                         
  - announce clearly in group who became bot admin,
  - allow bot admin override,                                                                                                                                                            
  - maybe require the first group admin to confirm setup within 5 minutes,
  - document that this is “bot interaction admin”, not messaging-platform admin.                                                                                                         
                                         
  ---                                                                                                                                                                                    
  23. Plain-text output is good, but examples still look markdown-ish
                                                                                                                                                                                         
  Where:                                                                                                                                                                                 
                                                                                                                                                                                         
  - docs/SPEC.md:70                                                                                                                                                                      
  - docs/03-commands.md:54-56                                                                                                                                                            
  - examples use [topic_id=...], backticks, bullets.                                                                                                                                     
                                         
  Recommendation:                                                                                                                                                                        
                                                                                                                                                                                         
  Define a strict output style guide:    
                                                                                                                                                                                         
  - maximum summary length,                                                                                                                                                              
  - max topics per message,                                                                                                                                                              
  - truncation behavior,                                                                                                                                                                 
  - UID/topic formatting,                                                                                                                                                                
  - whether bullets are allowed,         
  - whether adapter message splitting is supported.                                                                                                                                      
                                         
  This will help agents implement consistent response formatters.                                                                                                                        
                                                                                                                                                                                         
  ---                                                                                                                                                                                    
  Agentic-development usability assessment                                                                                                                                               
                                                                                                                                                                                         
  Current usability: medium-good for architecture, medium-risk for implementation                                                                                                        
                                                                                                                                                                                         
  The spec is useful for:                                                                                                                                                                
                                         
  - understanding the product,                                                                                                                                                           
  - dividing modules,                
  - identifying major commands,                                                                                                                                                          
  - understanding security intent,                                                                                                                                                       
  - writing high-level implementation plans.                                                                                                                                             
                                                                                                                                                                                         
  It is risky for autonomous implementation because:
                                                                                                                                                                                         
  - the same behavior is sometimes specified differently in different files,                                                                                                             
  - schema snippets do not consistently match command behavior,                                                                                                                          
  - security failure behavior is contradictory,                                                                                                                                          
  - enum values are not canonical,                                                                                                                                                       
  - several features imply hidden state not present in schema,                                                                                                                           
  - command permissions sometimes conflict with privacy model.                                                                                                                           
                                         
  Best-practice improvements for agentic development                                                                                                                                     
                                                                                                                                                                                         
  1. Add a canonical “implementation contract” section                                                                                                                                   
                                                                                                                                                                                         
  Create a short docs/00-contract.md or expand SPEC.md with canonical decisions:                                                                                                         
                                                                                                                                                                                         
  - canonical status enum,                                                                                                                                                               
  - canonical scope enum,                                                                                                                                                                
  - canonical command list,              
  - canonical permission matrix,                                                                                                                                                         
  - canonical data-retention table,      
  - canonical security failure policy.                                                                                                                                                   
                                         
  Agents should treat companion docs as explanatory, but this contract as authoritative.
                                                                                                                                                                                         
  2. Add machine-checkable tables                                                                                                                                                        
                                                                                                                                                                                         
  For example:                                                                                                                                                                           
                                                                                                                                                                                         
  post_statuses:                         
    - RAW                                                                                                                                                                                
    - EVALUATING                         
    - READY                                                                                                                                                                              
    - READY_REDACTED                     
    - QUARANTINED                                                                                                                                                                        
    - FAILED                             
                                                                                                                                                                                         
  scope_kinds:                                                                                                                                                                           
    - dm                                                                                                                                                                                 
    - group                                                                                                                                                                              
                                         
  Even if kept in Markdown, a structured table helps implementation agents avoid inventing names.                                                                                        
                                         
  3. Add “must not implement” boundaries                                                                                                                                                 
                                         
  The spec already has deferred features, which is good. Add explicit v1 exclusions near relevant sections:
                                                                                                                                                                                         
  - no arbitrary URL fetching by chat agent,
  - no admin LLM tools,                                                                                                                                                                  
  - no group-shared memory,              
  - no display-name authorization,                                                                                                                                                       
  - no hard-delete source in command path,
  - no raw quarantine HTML through messaging.                                                                                                                                            
                                         
  4. Add acceptance criteria per module                                                                                                                                                  
                                         
  For each module, define “done means”:                                                                                                                                                  
                                     
  Example for provider:                                                                                                                                                                  
                                         
  - permission matrix test passes,       
  - banned intake test proves no parser/LLM call,                                                                                                                                        
  - /summary uses deterministic SQL,
  - group member cannot mutate group settings,                                                                                                                                           
  - group member can clear own context if that is chosen.
                                                                                                                                                                                         
  Example for collector:             
                                                                                                                                                                                         
  - duplicate fetch is idempotent,                                                                                                                                                       
  - outbox rehydrator resumes at correct stage,                                                                                                                                          
  - Stage 1 redaction offsets survive normalization,                                                                                                                                     
  - source URL validation blocks private IPs.                                                                                                                                            
                                                                                                                                                                                         
  5. Add test IDs matching spec requirements                                                                                                                                             
                                     
  Example:                                                                                                                                                                               
                                                                                                                                                                                         
  SEC-001: Stage 1 strips script/style/on* attributes.                                                                                                                                   
  SEC-002: Stage 2 failure never restores original suspicious span.                                                                                                                      
  AUTH-001: Display name never authorizes admin target.                                                                                                                                  
  DB-001: /remove-source does not delete historical posts.                                                                                                                               
                                         
  This is very useful for agentic development because agents can implement one requirement/test pair at a time.
                                                                                                                                                                                         
  ---     
