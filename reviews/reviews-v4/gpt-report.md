1. Executive summary                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  I read docs/SPEC.md, all files under docs/spec/, and docs/00-mvp.md. Overall verdict: the spec is unusually strong on trust boundaries and determinism, but several late-added features have not been fully reconciled across schema, permissions,    
  deployment, MVP, and verification.                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  Top 3 issues by severity:                                                                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  1. Provider DB role cannot support promised admin commands: /audit and quarantine review require reads/updates that security.md explicitly denies to the Provider role.                                                                               
  2. Global source metadata is mutated by per-scope /add-source calls: a DM user can replace bootstrap_tags on a global source, changing fallback behavior for other users/groups.                                                                      
  3. MVP is not a strict subset of v1: it keeps auto-registration while the v1 spec now requires invite-code DM onboarding and slow-start probation.
                                         
  The most important repair is a reconciliation pass across security.md, schema.md, commands.md, deployment.md, and verification.md for admin DB capabilities, onboarding, source/subscription ownership, and asset enablement.
                                
  ---                           
  2. Findings                              
                                           
  [F01] Provider DB role cannot read audit log but /audit is a Provider command                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  Severity: blocker                                                                                                                                                                                                                                     
  Category: inconsistency / security                                                                                                                                                                                                                    
  Location: docs/spec/security.md §DB roles lines 428-446; docs/spec/commands.md §Admin lines 315-344
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                         
  - Provider role has INSERT-only on audit_log.                                                                                                                                                                                                         
  - /audit [-w …] [--actor …] [--action …] [--page N] reads audit_log.
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  A bot admin issues /audit -w 24h through the Provider. The command is authorized in deterministic Java, but the Provider DB role cannot SELECT from audit_log. Either the command fails in production, or implementers grant broader permissions than 
  the security spec allows.                                                                                                                                                                                                                             
                                         
  Suggested resolution:                                                                                                                                                                                                                                 
                                           
  Pick one:                                                                                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  - Grant Provider a restricted audit-read capability:
  ▎ The Provider role has INSERT on audit_log and SELECT through a redacted audit_log_view used only by /audit.                                                                                                                                         
  - Or remove /audit from chat commands and make audit inspection operator-only via the admin DB role.
                                         
  The first option preserves the current command catalogue but needs a redacted view and explicit tests.
                                
  ---                                                                                                                                                                                                                                                   
  [F02] Provider DB role cannot perform quarantine approve/reject as specified
                                                                                                                                                                                                                                                        
  Severity: blocker                                                                                                                                                                                                                                     
  Category: inconsistency / security                                                                                                                                                                                                                    
  Location: docs/spec/security.md §Quarantine workflow lines 330-347 and §DB roles lines 428-446; docs/spec/commands.md §Admin lines 340-342                                                                                                            
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Admins review via /quarantine list and /quarantine approve|reject.                                                                                                                                                                                  
  - Provider role has SELECT on the quarantine review view and no raw original content.                                                                                                                                                                 
  - Approve restores the original span.                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  A bot admin sends /quarantine approve <id>. Restoring the original span requires mutating quarantine/post state and accessing or applying the stored original. But the Provider role is specified as read-only for quarantine review and forbidden    
  from seeing raw original content.        
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Specify the exact safe mechanism. For example:                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  ▎ The Provider role may execute approve_quarantine(id, actor_id) and reject_quarantine(id, actor_id) stored procedures. These procedures update review status and restore/reject spans without granting Provider SELECT on raw original content.
                                                                                                                                                                                                                                                        
  Add corresponding DB-role and integration tests.                                                                                                                                                                                                      
                            
  ---                                                                                                                                                                                                                                                   
  [F03] /add-source lets one scope overwrite global fallback tags for all scopes
                                                                                                                                                                                                                                                        
  Severity: blocker                      
  Category: security / gap / scope                                                                                                                                                                                                                      
  Location: docs/spec/commands.md §Source management lines 169-201; docs/spec/schema.md §Sources and tags lines 40-74; docs/spec/decisions.md D7/D14/D22 lines 17-24 and 32
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - source rows are global; subscriptions are per-scope.                                                                                                                                                                                                
  - If an existing source is added, the new call’s --tags replace the existing bootstrap_tags.                                                                                                                                                          
  - Tagger failure falls back to source.bootstrap_tags.                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Alice in a DM adds an already-existing source with tags celebrity,gossip. That replaces global bootstrap_tags. Later the tagger fails for the same source, and Bob’s group summaries classify posts using Alice’s tags. A per-user action changes     
  global ingest fallback for unrelated scopes.
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Do not let per-scope /add-source replace global fallback tags. Minimal spec change:                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ▎ For an existing source, /add-source upserts the caller’s source_subscription and records the caller-supplied tags on that subscription. It does not mutate source.bootstrap_tags; only bootstrap reload or bot-admin source maintenance may update 
  ▎ global fallback tags.                                                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  If global source tags must be mutable by users, require bot-admin approval and audit it as a global effect.
                                                                                                                                                                                                                                                        
  ---                                    
  [F04] MVP onboarding contradicts invite-gated v1 onboarding while claiming to be a strict subset                                                                                                                                                      
                                                                                                  
  Severity: blocker                                                                                                                                                                                                                                     
  Category: scope / inconsistency                                                                                                                                                                                                                       
  Location: docs/00-mvp.md §Messaging adapter and commands lines 76-89 and §MVP exit criteria lines 149-162; docs/SPEC.md §v1 scope lines 86-98; docs/spec/security.md §Invite-code registration lines 265-308
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                            
  - MVP says first DM auto-registers and receives /help.                                                                                                                                                                                                
  - SPEC v1 says DM access requires invite-code registration and slow-start.
  - MVP says it is a strict subset of the spec.                                                                                                                                                                                                         
                                         
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  An engineer building MVP will implement auto-registration. An engineer building v1 will implement invite-gated registration. Since MVP is declared a strict subset, the MVP behavior should not need to be ripped out or inverted later.              
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Either make MVP include invite-code registration minimally, or explicitly mark MVP onboarding as a temporary scaffold. Better replacement:                                                                                                            
                                                                                                                                                                                                                                                        
  ▎ MVP implements invite-gated DM onboarding with a pre-seeded test invite code. Unknown DMs without a valid invite receive the fixed invitation-required reply.                                                                                       
                                         
  ---                                                                                                                                                                                                                                                   
  [F05] Invite-code schema does not model open invites
                                                                                                                                                                                                                                                        
  Severity: major           
  Category: inconsistency / gap                                                                                                                                                                                                                         
  Location: docs/spec/schema.md §Identity and access lines 26-34; docs/spec/security.md §Invite-code registration lines 270-285; docs/spec/decisions.md D44 line 53
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Invite code carries expected (contact_id, adapter) pair.                                                                                                                                                                                            
  - D44 supports --open, bound only to adapter; first unknown contact may consume it.                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  A bot admin creates /invite create --adapter simplex --open. The schema description says the code is bound to an expected contact id, but open invites intentionally have no expected contact id. Implementers may make contact_id nullable, invent an
   invite type, or misuse sentinel values.                                                                                                                                                                                                              
                                
  Suggested resolution:                                                                                                                                                                                                                                 
                                           
  Add an explicit invite type:                                                                                                                                                                                                                          
                                                                                                                                                                                                                                                        
  ▎ Invite code carries invite_type = CONTACT_BOUND | OPEN_ADAPTER, adapter, nullable expected_contact_id, and a constraint requiring expected_contact_id IS NOT NULL only for CONTACT_BOUND.
                                                                                                                                                                                                                                                        
  ---                                      
  [F06] source identity alternates between (kind, identifier) and (kind, identifier, config)
                                                                                                                                                                                                                                                        
  Severity: major           
  Category: ambiguity / inconsistency                                                                                                                                                                                                                   
  Location: docs/spec/schema.md §Source lines 40-47; docs/spec/architecture.md §Ingest SPIs lines 118-128; docs/spec/decisions.md D38 line 48; docs/spec/deployment.md §Bootstrap behavior lines 95-105
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Schema: source is globally unique by (kind, identifier).                                                                                                                                                                                            
  - Architecture: “Source identity is (kind, identifier, config),” then says (kind, identifier) forms the unique key.                                                                                                                                   
  - Deployment: bootstrap upserts by (kind, identifier) and updates config.                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  For Nostr, two bootstrap entries may use the same filter spec but different relay lists. One implementer treats those as distinct sources because config is part of identity; another updates one row because uniqueness is (kind, identifier). This  
  affects dedup, subscriptions, and StreamSource worker startup.
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Make one sentence authoritative everywhere:                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  ▎ Source identity and uniqueness are (kind, identifier). config is mutable per-kind configuration on that source and is not part of identity.
                                                                                                                                                                                                                                                        
  If config must distinguish Nostr sources, change schema/deployment to unique (kind, identifier, config_hash).
                            
  ---                                                                                                                                                                                                                                                   
  [F07] /unfollow-source requires per-adder ownership not present in the schema
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: gap / inconsistency                                                                                                                                                                                                                         
  Location: docs/spec/commands.md §Source management lines 209-218; docs/spec/schema.md §Sources and tags lines 64-65
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - A group member may unfollow a subscription they added.                                                                                                                                                                                              
  - source_subscription is only described as a (scope, source) link.                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Alice adds a source to a group, then Bob also tries to add the same source. There is one group-scope subscription row. Later Bob sends /unfollow-source <id>. The command needs to know whether Bob “added” it, but the schema has no                 
  contributor/owner model.                                                                                                                                                                                                                              
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Either simplify permission:                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  ▎ In groups, only group admin or bot admin may unfollow group subscriptions.
                                                                                                                                                                                                                                                        
  Or add a contributor model:                                                                                                                                                                                                                           
                      
  ▎ source_subscription records added_by_user_id; repeated additions by different members create source_subscription_contributor rows. A non-admin may remove only their contributor row; the subscription disappears only when the last contributor is 
  ▎ removed, subject to the last-source rule.
                                                                                                                                                                                                                                                        
  ---                                    
  [F08] Scope tag preference state cannot represent the documented /unfollow-tag behavior                                                                                                                                                               
                                         
  Severity: major                                                                                                                                                                                                                                       
  Category: inconsistency / gap 
  Location: docs/spec/schema.md §Scope tag lines 68-74; docs/spec/commands.md §Per-scope tag preferences lines 224-237                                                                                                                                  
  Confidence: high                         
                                
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Absence of scope_tag rows means “all tags.”                                                                                                                                                                                                         
  - Each row corresponds to /follow-tag; /unfollow-tag removes the row.                                                                                                                                                                                 
  - First /follow-tag or /unfollow-tag switches scope to explicit mode.                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  A fresh group defaults to all tags. A user sends /unfollow-tag sports expecting to exclude sports. If /unfollow-tag removes a row and no rows exist, the resulting state is still absence-of-rows, which means all tags — the command has no effect or
   needs hidden state not described.       
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Add explicit mode state:                                                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  ▎ scope_preferences.tag_mode = ALL | EXPLICIT. In ALL, /unfollow-tag <tag> switches to EXPLICIT and seeds rows for all currently subscribed-source tags except <tag>. In EXPLICIT, /follow-tag adds and /unfollow-tag removes rows.
                                                                                                                                                                                                                                                        
  Or remove “first /unfollow-tag switches explicit mode” and add a v1 /set-tags command.
                            
  ---                                                                                                                                                                                                                                                   
  [F09] Re-evaluation terminal state NEEDS_REVIEW is not in the post status model
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: inconsistency                                                                                                                                                                                                                               
  Location: docs/spec/security.md §Re-evaluation job lines 398-408; docs/spec/schema.md §Posts and derivatives lines 78-87; docs/00-mvp.md §Schema lines 27-35
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Post status is RAW, READY, QUARANTINED.                                                                                                                                                                                                             
  - Re-evaluation exhaustion permanently marks the post NEEDS_REVIEW.                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Stage 2 is down for hours. Posts are released redacted and queued for re-evaluation. After attempts are exhausted, the implementation must mark NEEDS_REVIEW, but there is no such status in the schema. One engineer adds a status; another adds a   
  flag; another reuses QUARANTINED.                                                                                                                                                                                                                     
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Define it as either a status or a flag. Minimal change:                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  ▎ Re-evaluation exhaustion leaves post.status = READY, keeps Stage 1 redactions, and sets post.security_review_state = NEEDS_REVIEW.
                                                                                                                                                                                                                                                        
  Then add that field to schema commitments.                                                                                                                                                                                                            
                            
  ---                                                                                                                                                                                                                                                   
  [F10] architecture.md timestamp high-water mark can miss posts with equal timestamps
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: gap / failure-mode                                                                                                                                                                                                                          
  Location: docs/spec/architecture.md §Inter-service communication lines 32-45
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Catch-up query uses > last_ready_post_at.                                                                                                                                                                                                           
  - High-water mark advances in same DB transaction as side effect.                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Two posts become READY with the same ready_at timestamp. Provider processes the first and advances last_ready_post_at to that timestamp. A later catch-up query using > skips the second post forever.                                                
                                                                                                                                                                                                                                                        
  Suggested resolution:                    
                                                                                                                                                                                                                                                        
  Use a total ordering:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ▎ The catch-up cursor is (ready_at, post_id) and the query uses (ready_at, post_id) > (:last_ready_at, :last_post_id) ordered by both columns.                                                                                                        
                                         
  Alternatively use a monotonic sequence/event id instead of timestamps.                                                                                                                                                                                
                                         
  ---                                                                                                                                                                                                                                                   
  [F11] /add-source validation violates the “DB-only” service communication story
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: inconsistency / smell                                                                                                                                                                                                                       
  Location: docs/spec/architecture.md §Inter-service communication lines 32-49; docs/spec/commands.md §Source management lines 180-191; docs/spec/security.md §SSRF lines 79-104
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Collector and Provider communicate only through the shared database.                                                                                                                                                                                
  - /add-source Provider performs a reachability probe through the Collector’s SSRF allowlist before writing the source row.                                                                                                                            
  - Security says Collector outbound connections include /add-source URL validation.                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  A user sends /add-source https://example.com/feed --tags news. Does Provider make an outbound HTTP request? Does it call Collector? Does it insert a validation job and wait? These imply different trust boundaries, DB roles, timeouts, and failure 
  paths.                                   
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Specify the mechanism without changing the trust model. For example:                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  ▎ Provider performs /add-source validation locally using the shared SSRF validation library and the same transport caps as Collector fetchers. There is no Provider→Collector RPC.
                                                                                                                                                                                                                                                        
  Or:                                                                                                                                                                                                                                                   
                      
  ▎ Provider writes a pending validation request to the DB; Collector validates and finalizes the source asynchronously.                                                                                                                                
                                         
  The first is simpler; the second preserves Collector-only outbound fetches.                                                                                                                                                                           
                                         
  ---                                                                                                                                                                                                                                                   
  [F12] LLM outage behavior is underspecified for user-facing chat and summaries
                                                                                                                                                                                                                                                        
  Severity: major           
  Category: failure-mode                                                                                                                                                                                                                                
  Location: docs/spec/security.md §Failure handling lines 348-397; docs/spec/llm.md §Failure handling lines 226-246; docs/spec/commands.md §Content lines 66-76 and §Chat mode lines 359-371
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Stage, tagger, embedding, and translation failures have policies.                                                                                                                                                                                   
  - /summary and chat agent use LLM prose.                                                                                                                                                                                                              
  - “A complete LLM outage degrades quality, not safety.”                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  The summarizer provider is down. A user sends /summary -w 24h; another sends chat-mode input. The spec does not say whether Provider returns headlines-only, an error, cached output, partial SQL result, or waits until timeout.                     
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Add user-facing LLM failure rules:                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  ▎ If summarizer/chat LLM is unavailable or times out, /summary returns the deterministic post list/headlines with a localized “prose unavailable” note; chat mode returns a localized temporary-unavailable reply. No retry loop runs synchronously   
  ▎ beyond the per-call timeout.         
                                                                                                                                                                                                                                                        
  ---                                    
  [F13] Asset commands can be disabled by absent optional bootstrap file despite being v1 scope                                                                                                                                                         
                                                                                               
  Severity: major                                                                                                                                                                                                                                       
  Category: scope / operator             
  Location: docs/SPEC.md §v1 scope lines 136-144; docs/spec/commands.md §Asset commands lines 89-166; docs/spec/deployment.md §Operator inputs lines 76-82                                                                                              
  Confidence: high                         
                                
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - /zcash and /monero are v1 commands.                                                                                                                                                                                                                 
  - bootstrap-assets.json is optional; absent file means asset commands disabled.                                                                                                                                                                       
  - /help only shows operator-enabled assets.                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  A fresh operator follows the spec but does not provide bootstrap-assets.json. The system is v1 but lacks commands that SPEC.md says are in scope. Tests may pass in one deployment and fail in another depending on optional config.                  
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Either make the file required for v1, or narrow the v1 commitment:                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  ▎ Asset command infrastructure is v1; /zcash and /monero are enabled only when bootstrap-assets.json enables them.                                                                                                                                    
                                         
  If commands are truly v1-mandatory, ship a default bootstrap asset file and make startup fail if required entries are missing.                                                                                                                        
                                         
  ---                                                                                                                                                                                                                                                   
  [F14] Asset soft-disable is referenced but not specified
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: operator / gap                                                                                                                                                                                                                              
  Location: docs/spec/deployment.md §Bootstrap behavior lines 95-105; docs/spec/commands.md §Asset commands lines 160-166
  Confidence: medium                                                                                                                                                                                                                                    
                                         
  What the spec says:                                                                                                                                                                                                                                   
                      
  - Collector loads bootstrap assets and never deletes assets.                                                                                                                                                                                          
  - Removing an asset from the file is a soft-disable in the operator runbook.
  - Provider exposes only enabled assets.                                                                                                                                                                                                               
                                         
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  An operator removes Monero from bootstrap-assets.json. The loader “never deletes,” but the Provider must stop exposing /monero. The spec does not define where enabled/disabled state lives, how sub-verbs are disabled, or how stale snapshots are   
  treated.                                 
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Add an asset config entity:                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  ▎ asset_config is keyed by asset symbol, carries enabled flag, default sub-verb, enabled sub-verbs, and attribution metadata. Bootstrap upserts entries present in the file and marks missing previously-bootstrap-managed assets disabled.
                                                                                                                                                                                                                                                        
  Or state that removal from file has no effect and disabling is a manual DB/runbook operation.                                                                                                                                                         
                            
  ---                                                                                                                                                                                                                                                   
  [F15] Deployment config says overrides always win, but rate limits can only be lowered
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: inconsistency / operator                                                                                                                                                                                                                    
  Location: docs/spec/deployment.md §Configuration surface lines 116-145; docs/spec/architecture.md §Hardware profiles lines 182-191
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Profile values can be overridden per-property; explicit operator setting always wins.                                                                                                                                                               
  - Rate limits are capped at profile defaults; operator can lower, not raise.                                                                                                                                                                          
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  An operator on remote wants to raise the LLM-triggering rate limit because they use a paid remote provider. One section says explicit override wins; another says raising is forbidden. Implementers may build either behavior.
                                                                                                                                                                                                                                                        
  Suggested resolution:                  
                                                                                                                                                                                                                                                        
  Clarify the exception:                   
                                                                                                                                                                                                                                                        
  ▎ Profile values can be overridden per-property except safety caps explicitly marked as ceilings. Rate-limit overrides may only lower the profile ceiling unless a separate unsafe/operator-acknowledged override is set.                             
                            
  Or allow raising and rely on operator judgment.                                                                                                                                                                                                       
                                         
  ---                                                                                                                                                                                                                                                   
  [F16] v1 adapter scope disagrees across files
                                                                                                                                                                                                                                                        
  Severity: major           
  Category: inconsistency / scope                                                                                                                                                                                                                       
  Location: docs/SPEC.md §v1 scope lines 88-91; docs/spec/messaging.md §Goals lines 7-23; docs/spec/deployment.md §Topology lines 7-19; docs/00-mvp.md §Messaging adapter and commands lines 76-79
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - SPEC and messaging say v1 ships SimpleX and Signal.                                                                                                                                                                                                 
  - Deployment topology says one messaging adapter backend, SimpleX in v1.                                                                                                                                                                              
  - MVP says SimpleX is deferred and only in-memory adapter exists.                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  A v1 implementer cannot tell whether Signal is mandatory for v1, optional after v1, or only an SPI proof target. Operator inputs also assume one configured backend, not multiple adapters.                                                           
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Pick a clear scope:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ▎ v1 production adapter: SimpleX. v1 test adapter: in-memory. Signal is deferred to v1.x/v2.                                                                                                                                                          
                                         
  Or update deployment to say v1 supports selecting either SimpleX or Signal, one active at a time.                                                                                                                                                     
                                         
  ---                                                                                                                                                                                                                                                   
  [F17] Decisions log still contains stale auto-registration decision
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: inconsistency                                                                                                                                                                                                                               
  Location: docs/spec/decisions.md D23 line 33; docs/spec/security.md §Invite-code registration lines 265-308; docs/spec/commands.md §Onboarding lines 373-398
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - D23 says auto-register on first message.                                                                                                                                                                                                            
  - Later sections say DM first interaction requires valid invite code.                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  A reader starts with decisions.md as instructed. They see auto-registration as a settled cross-cutting decision, then later find invite-gated registration. The conflict affects security posture and onboarding implementation.
                                                                                                                                                                                                                                                        
  Suggested resolution:         
                                                                                                                                                                                                                                                        
  Replace D23 with:                        
                                                                                                                                                                                                                                                        
  ▎ Onboarding: DM registration requires invite code; group @mention auto-registers with probation; banned users blocked at intake.                                                                                                                     
                            
  ---                                                                                                                                                                                                                                                   
  [F18] Group admin refill behavior is internally ambiguous
                                           
  Severity: major           
  Category: ambiguity / inconsistency                                                                                                                                                                                                                   
  Location: docs/SPEC.md §Glossary lines 187-191; docs/spec/security.md §Authorization model lines 206-224
  Confidence: medium                                                                                                                                                                                                                                    
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Group admin is bootstrapped by first @mention in a new group.                                                                                                                                                                                       
  - Group can have zero admins; next bot-admin /promote or first-mention path refills the slot.                                                                                                                                                         
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  A group admin is banned, leaving zero reachable admins. A regular member later @mentions the bot. Is this an existing group, so first-mention bootstrapping no longer applies? Or does “first-mention path refills the slot” mean the first mention   
  after zero-admin state grants admin?                                                                                                                                                                                                                  
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Define zero-admin recovery explicitly:                                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  ▎ First-mention auto-promotion runs only when the group row is first created. Existing zero-admin groups require bot-admin /promote.
                                                                                                                                                                                                                                                        
  Or:                                                                                                                                                                                                                                                   
                            
  ▎ If a group has zero group admins, the next non-banned full-access member to @mention the bot is auto-promoted.                                                                                                                                      
                                         
  The first is safer; the second is more self-healing but broader.                                                                                                                                                                                      
                            
  ---                                                                                                                                                                                                                                                   
  [F19] /retry for periodic digest cache replacement is underspecified for restart and message delivery
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: gap / failure-mode                                                                                                                                                                                                                          
  Location: docs/spec/commands.md §Conversation control lines 297-313; docs/spec/schema.md §Operational lines 133-138; docs/spec/messaging.md §Message handles lines 68-77
  Confidence: medium                                                                                                                                                                                                                                    
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - /retry for periodic group digests replaces the cached digest.                                                                                                                                                                                       
  - Message handles must not be persisted and are valid only in-process.                                                                                                                                                                                
  - Summary cache is keyed by group, slot, and subscription versions.                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  A group admin retries yesterday evening’s digest after Provider restart. The cache row may exist, but the original message handle cannot. Does the bot edit the old digest, send a new message, or only affect future /summary reads? Different       
  implementations will produce different user-visible behavior.
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Separate cache mutation from transport mutation:                                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  ▎ /retry for a periodic digest updates the summary_cache row. If the original message handle is still live in-process, Provider may edit/finalize that message; otherwise it sends a new digest reply noting it replaces the cached digest for 
  ▎ subsequent reads.                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ---                 
  [F20] Translation routing statement is too broad before later narrowing it                                                                                                                                                                            
                                         
  Severity: minor                                                                                                                                                                                                                                       
  Category: ambiguity                    
  Location: docs/spec/llm.md §Translation flow lines 151-180; docs/spec/decisions.md D43 line 55                                                                                                                                                        
  Confidence: medium                       
                                
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - “For each user-visible reply,” non-English goes through TranslationProvider.                                                                                                                                                                        
  - Later says deterministic strings come from localization bundle, not translator.                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  A Czech scope sends /help. One implementer follows the broad sentence and sends /help through the translator; another follows D43 and uses the bundle. The latter is safer and intended, but the earlier wording invites a determinism/sanitizer
  bypass.                                                                                                                                                                                                                                               
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Replace the broad sentence:                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  ▎ For each LLM-authored user-visible prose reply, if the scope language is non-English, the text goes through TranslationProvider; deterministic strings are localized by bundle and never translated by model.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  [F21] Sanitizer order after translation is not explicit
                                                                                                                                                                                                                                                        
  Severity: minor                        
  Category: security / gap                                                                                                                                                                                                                              
  Location: docs/spec/security.md §LLM output sanitizer lines 178-193; docs/spec/llm.md §Translation flow lines 151-180
  Confidence: medium                                                                                                                                                                                                                                    
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Before any LLM-generated text is delivered, sanitizer strips/refuses admin command strings.                                                                                                                                                         
  - TranslationProvider produces LLM-authored prose for non-English output.                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  A benign English summary is sanitized, then translated to Czech. The translator emits a string containing /ban or /grant-admin due to prompt drift. If implementers sanitize only before translation, the final user-visible text bypasses the
  sanitizer.                                                                                                                                                                                                                                            
                                
  Suggested resolution:                                                                                                                                                                                                                                 
                                           
  Add ordering:                                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  ▎ The final text after translation or native target-language generation is passed through the LLM output sanitizer immediately before delivery.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  [F22] Verification omits invite-code and slow-start coverage
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: verification                                                                                                                                                                                                                                
  Location: docs/spec/verification.md §Commands and chat lines 78-170; docs/spec/security.md §Invite-code registration lines 265-328 and §Slow-start tier lines 309-328
  Confidence: high                                                                                                                                                                                                                                      
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Invite codes gate DM registration.                                                                                                                                                                                                                  
  - New users enter probation with a restricted command subset.                                                                                                                                                                                         
  - Verification has onboarding-mode tests but no invite-code matrix or slow-start permission tests.                                                                                                                                                    
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  A regression could allow unknown DMs to auto-register, replay used invite codes, consume a code from the wrong adapter, or execute /add-source during probation. The v1 security gate would be broken without a required failing test.                
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Add verification bullets for:                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  - contact-bound invite success/failure                                                                                                                                                                                                                
  - open invite single-use consumption   
  - expired/revoked/used replay rejection                                                                                                                                                                                                               
  - cross-adapter rejection              
  - pre-banned unknown contact path                                                                                                                                                                                                                     
  - slow-start allowlist/denylist        
  - /vouch immediate graduation                                                                                                                                                                                                                         
                                           
  ---                                                                                                                                                                                                                                                   
  [F23] Verification hard-codes a confirmation timeout despite spec saying profile-driven                                                                                                                                                               
                                                                                                                                                                                                                                                        
  Severity: minor                                                                                                                                                                                                                                       
  Category: layering / verification                                                                                                                                                                                                                     
  Location: docs/spec/verification.md §Commands and chat lines 83-88; docs/spec/commands.md §Surface conventions lines 20-28                                                                                                                            
  Confidence: high                                                                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Confirmation timeout is fixed per deployment and profile-driven; exact duration lives in design notes.                                                                                                                                              
  - Verification says “30-second timeout rejects late confirms.”                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  If design notes choose 60 seconds for a profile, the spec-level verification text is wrong. If tests hard-code 30 seconds, they enforce a design value at the wrong layer.
                                                                                                                                                                                                                                                        
  Suggested resolution:                    
                                                                                                                                                                                                                                                        
  Replace with:                                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  ▎ Confirmation timeout: using the configured profile timeout, late confirms are rejected; bare confirm does not fire anything; cross-scope confirm is rejected.                                                                                       
                                         
  ---                                                                                                                                                                                                                                                   
  [F24] Verification does not cover asset bootstrap enable/disable semantics
                                                                                                                                                                                                                                                        
  Severity: major                        
  Category: verification / operator                                                                                                                                                                                                                     
  Location: docs/spec/verification.md §Commands and chat lines 132-144 and §Deployment lines 259-272; docs/spec/deployment.md §Operator inputs lines 76-82 and §Bootstrap behavior lines 95-105
  Confidence: medium                                                                                                                                                                                                                                    
                                         
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - Bootstrap assets file controls enabled assets and sub-verbs.                                                                                                                                                                                        
  - Absent file disables asset commands.                                                                                                                                                                                                                
  - Verification tests command behavior against existing snapshots but not bootstrap semantics.                                                                                                                                                         
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                         
  An implementation could expose /monero even when not enabled, fail to hide disabled sub-verbs from /help, or mishandle a removed asset on reload. None of the required tests would catch it.                                                          
                                           
  Suggested resolution:                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Add deployment/integration tests for:                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  - absent bootstrap-assets.json                                                                                                                                                                                                                        
  - enabled /zcash and /monero
  - disabled asset not appearing in /help                                                                                                                                                                                                               
  - sub-verb allowlist enforcement from bootstrap config
  - reload/idempotency behavior                                                                                                                                                                                                                         
                                         
  ---                                                                                                                                                                                                                                                   
  [F25] MVP contains broken or misleading relative links
                                                                                                                                                                                                                                                        
  Severity: minor                                                                                                                                                                                                                                       
  Category: operator / layering                                                                                                                                                                                                                         
  Location: docs/00-mvp.md §Schema lines 23-35; §Commands lines 80-86                                                                                                                                                                                   
  Confidence: high                                                                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
                                           
  - MVP references [02-schema.md](02-schema.md) and [03-commands.md](03-commands.md).                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Why it's a problem:                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Those files are not in docs/; the actual spec files are under docs/spec/, and design files are under docs/design/. A fresh contributor following the MVP gets dead links at exactly the points where they need schema/command context.                
                                                                                                                                                                                                                                                        
  Suggested resolution:                                                                                                                                                                                                                                 
                                         
  Update links to either:                                                                                                                                                                                                                               
                                         
  - spec/schema.md and spec/commands.md, if referring to spec commitments; or                                                                                                                                                                           
  - design/02-schema.md and design/03-commands.md, if referring to implementation details.
                                                                                                                                                                                                                                                        
  ---                                    
  [F26] MVP includes implementation values that belong in design notes                                                                                                                                                                                  
                                                                      
  Severity: minor                                                                                                                                                                                                                                       
  Category: layering                     
  Location: docs/00-mvp.md §Pipeline lines 57-63 and §What is NOT in MVP lines 101-108                                                                                                                                                                  
  Confidence: medium                       
                                                                                                                                                                                                                                                        
  What the spec says:                                                                                                                                                                                                                                   
                            
  - Stage 1 uses “RE2/J or 100 ms watchdog.”                                                                                                                                                                                                            
  - Auto-compress at 75% context is deferred.
                                                                                                                                                                                                                                                        
  Why it's a problem:                    
                                                                                                                                                                                                                                                        
  docs/SPEC.md says regex choices, retry counts, and profile values live in design. MVP is a scope document, but these values can silently diverge from design and full spec.
                                                                                                                                                                                                                                                        
  Suggested resolution:                    
                                                                                                                                                                                                                                                        
  Move values to design notes and make MVP refer to behavior only:                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  ▎ Security Stage 1 includes deterministic regex scanning with bounded execution time.                                                                                                                                                                 
                                         
  And:                                                                                                                                                                                                                                                  
                                         
  ▎ Auto-compress near the profile-defined context-window ceiling is deferred.                                                                                                                                                                          
                            
  ---                                                                                                                                                                                                                                                   
  3. Cross-cutting observations          
                                                                                                                                                                                                                                                        
  - Late onboarding changes were not propagated everywhere. Invite codes and slow-start are well specified in security.md, but decisions.md, 00-mvp.md, and verification.md still carry older auto-registration assumptions. See F04, F17, F22.
  - Global-vs-scope ownership needs a schema pass. The spec wants global source rows with per-scope subscriptions, but commands now need per-adder ownership, per-scope tags, and non-leaking fallback behavior. See F03, F07, F08.                     
  - Least-privilege DB roles are strong but currently over-constrain Provider features. The desired role split is good, but /audit and quarantine admin commands need either views/stored procedures or removal from chat surface. See F01, F02.
  - Asset commands are specified in detail at command level but not fully as an operational lifecycle. Enablement, soft-disable, bootstrap reload behavior, and verification are still thin. See F13, F14, F24.                                         
  - Failure handling is excellent for ingest stages but weaker for Provider-time LLM features. Stage 1/2/tagger/embedding failures are clear; chat and on-demand summary provider failures are not. See F12, F21.
  - The spec/design boundary mostly holds, but a few values leak into spec-level verification/MVP. See F23 and F26.                                                                                                                                     
                                           
  ---                                                                                                                                                                                                                                                   
  4. Spec evaluation                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  Completeness                                                                                                                                                                                                                                          
                                                                                                                                                                                                                                                        
  The spec covers most v1 behavior needed for a serious build: service split, ingest pipeline, trust model, commands, LLM routing, adapter contract, deployment inputs, and verification layers. The main completeness gaps are admin DB capabilities,  
  source subscription ownership, asset enablement lifecycle, and user-facing LLM outage behavior.                                                                                                                                                       
                                                                                                                                                                                                                                                        
  Consistency                                                                                                                                                                                                                                           
                                           
  The core architecture is consistent, especially around deterministic retrieval and “no LLM in trust path.” However, several newer decisions conflict with older text: invite-gated onboarding vs auto-registration, SimpleX/Signal scope, asset       
  optionality vs mandatory commands, and Provider least-privilege vs admin command catalogue.                                                                                                                                                           
                                
  Implementability                                                                                                                                                                                                                                      
                                           
  An engineer could build large parts of v1 from this spec, but would need clarification on several blocking paths: quarantine approval, audit reading, source tag mutation, open invite schema, and group subscription ownership. These are not
  cosmetic; they affect schema and permissions.                                                                                                                                                                                                         
                                
  Testability                                                                                                                                                                                                                                           
                                           
  verification.md is unusually thorough and maps many commitments to tests. The biggest omissions are invite-code registration, slow-start restrictions, asset bootstrap enablement, and the DB-role mechanics for /audit and quarantine review. It also
   contains at least one design-value leak: the 30-second confirmation timeout.                                                                                                                                                                         
                                           
  Evolvability                                                                                                                                                                                                                                          
                                                                                                                                                                                                                                                        
  The spec/design split is a strong foundation. The most likely leakage points are profile values in MVP/verification, asset configuration details, and source identity/config semantics. The global source model also risks future pain as more
  per-scope behaviors attach to sources.                                                                                                                                                                                                                
                                         
  ---                                                                                                                                                                                                                                                   
  5. Pros and cons of the current state    
                                         
  Pros                                                                                                                                                                                                                                                  
                                
  - Strong trust-boundary model: identity, ban checks, authorization, and LLM tool limits are clearly separated.                                                                                                                                        
  - Determinism boundary is stated repeatedly and concretely.
  - Ingest failure handling is detailed and safety-oriented.
  - Verification strategy is much more complete than typical specs.                                                                                                                                                                                     
  - Spec/design layering is explicit and mostly respected.
  - Operator inputs are mostly enumerated rather than implied.                                                                                                                                                                                          
                                                                                                                                                                                                                                                        
  Cons                                     
                                                                                                                                                                                                                                                        
  - Admin command surface conflicts with least-privilege DB role definitions.                                                                                                                                                                           
  - Onboarding changed to invite/slow-start but older MVP/decision/test text remains.                                                                                                                                                                   
  - Source/subscription/tag ownership model is under-specified for group and multi-scope behavior.                                                                                                                                                      
  - Asset command lifecycle is split across command/deployment/spec without one authoritative enablement model.                                                                                                                                         
  - User-facing LLM outage behavior is less specified than ingest LLM failure behavior.                                                                                                                                                                 
  - Some spec-level files still contain design-level concrete values or broken links.                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ---                                      
  6. Recommended next actions                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  1. Fix Provider DB-role/admin command contradictions first. Decide how /audit and quarantine approve/reject work under least privilege. This blocks schema, migrations, permissions, and tests.                                                       
  2. Reconcile onboarding across decisions, MVP, and verification. Update D23, MVP exit criteria, and test requirements for invite-code registration and slow-start.                                                                                    
  3. Redesign source subscription/tag ownership semantics. Decide whether tags and contributor ownership live on source, source_subscription, or new contributor/preference tables. This prevents cross-scope leakage.                                  
  4. Clarify source identity and StreamSource config semantics. Make (kind, identifier) vs (kind, identifier, config) unambiguous before implementing Nostr/bootstrap behavior.                                                                         
  5. Specify asset enablement lifecycle. Define required/default bootstrap-assets.json, disabled state, reload behavior, and corresponding tests.                                                                                                       
  6. Add Provider-time LLM failure behavior. State what /summary and chat mode return when summarizer/chat providers are slow, down, or malformed.                                                                                                      
  7. Clean layering and doc hygiene. Remove hard-coded values from spec-level verification/MVP and fix MVP links.

