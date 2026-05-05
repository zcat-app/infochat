---                                                                                                                                                                                                                                                   
  # 03 — Slash commands                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  All bot commands start with `/`. Anything not starting with `/` is treated as chat-mode input and routed to the ChatAgent. There is no "command mode" toggle.                                                                                         
                                                                                                                                                                                                                                                        
  Commands work the same in DM and group chat unless explicitly noted. In a group, the bot only sees a message if the user `@mentions` the bot's display name. The `@mention` is stripped before parsing.
                                                                                                                                                                                                                                                        
---                                                                              
                                                                                                                                                                                                                                                        
  ## 3.1 Conventions                                                               
                                                                                                                                                                                                                                                        
  ### Time window flag
                                                                                                                                                                                                                                                        
  A single `-w <duration>` flag is used everywhere a time window is needed. Accepted values:
                                                                                                                                                                                                                                                        
  | Form | Meaning |
  |---|---|                                                                                                                                                                                                                                             
  | `1h`, `12h`, `48h` | hours (1–168) |                                           
  | `1d`, `7d`, `30d` | days (1–30) |   
  | `1w`, `4w` | weeks (1–4) |                                                                                                                                                                                                                          
  | `1m` | rolling 30 days |  
                                                                                                                                                                                                                                                        
  Default `-w 24h` for `/summary` and similar commands.                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ### Tag arguments                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  Tag arguments are exact-match against the controlled vocabulary. Case-insensitive lookup; output uses the canonical `display` casing. Unknown tag → friendly error with fuzzy suggestions.
                                                                                                                                                                                                                                                        
  ### Confirmation for destructive commands                                        
                                                                                                                                                                                                                                                        
  Destructive commands (`/clear`, `/remove-source`, `/ban`, `/forget` v2) require confirmation:
                                                                                                                                                                                                                                                        
  ▎ /clear
  ▎ This will wipe your active chat context. Type clear confirm within 30s.                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  ▎ clear confirm
  ▎ Context cleared.                                                                                                                                                                                                                                    
                                                                                   
  Confirmation tokens are scoped to (user, scope) and expire after 30 seconds. Sending any other input cancels the pending confirmation.
                                                                                                                                                                                                                                                        
  ### Friendly errors
                                                                                                                                                                                                                                                        
  Unknown command, unknown tag, unknown source ID, malformed flag → response includes:                                                                                                                                                                  
  1. What was wrong (specific token)
  2. Up to 3 fuzzy suggestions (Levenshtein distance ≤ 2)                                                                                                                                                                                               
  3. The exact help line for the command (or `/help` pointer)                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  Example:                                                                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  ▎ /summary securty -w 24h                                                                                                                                                                                                                             
  ▎ Unknown tag securty. Did you mean: security, sectors?                          
  ▎ Available tags: ai, bitcoin, blog, devops, java, news, science, security, socials.                                                                                                                                                                  
  ▎ Usage: /summary [tag] [-w 24h]                                                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  ### Output formatting                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Plain text. Inline code wrapped in single backticks. Multi-line code in triple backticks. URLs bare. Adapters with `supportsMarkdownCode = true` may render backticks as styled monospace.                                                            
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 3.2 Permission matrix                                                                                                                                                                                                                              
   
  | Command | DM | Group (member) | Group (group admin) | Bot admin (anywhere) |                                                                                                                                                                        
  |---|---|---|---|---|                                                            
  | `/help` | ✅ | ✅ | ✅ | ✅ |                                                                                                                                                                                                                       
  | `/status` | ✅ | ✅ | ✅ | ✅ |                                                                                                                                                                                                                     
  | `/summary [tag]` | ✅ | ✅ | ✅ | ✅ |
  | `/get-tags` | ✅ | ✅ | ✅ | ✅ |                                                                                                                                                                                                                   
  | `/get-sources` | ✅ | ✅ | ✅ | ✅ |                                           
  | `/list-sources` | ✅ | ✅ | ✅ | ✅ |                                                                                                                                                                                                               
  | `/list-sources --all` | ❌ | ❌ | ❌ | ✅ |                                    
  | `/add-source --tags ...` | ✅ self | ❌ | ✅ for group | ✅ |                                                                                                                                                                                       
  | `/remove-source <id>` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                         
  | `/unfollow-source <id>` | ✅ self | ❌ | ✅ for group | ✅ |                                                                                                                                                                                        
  | `/follow-tag <tag>` | ✅ self | ❌ | ✅ for group | ✅ |                                                                                                                                                                                            
  | `/unfollow-tag <tag>` | ✅ self | ❌ | ✅ for group | ✅ |                                                                                                                                                                                          
  | `/save <uid>` | ✅ self | ✅ self | ✅ self | ✅ self |                                                                                                                                                                                             
  | `/saved [tag]` | ✅ | ✅ | ✅ | ✅ |                                                                                                                                                                                                                
  | `/unsave <uid>` | ✅ | ✅ | ✅ | ✅ |                                                                                                                                                                                                               
  | `/lang <code>` | ✅ self | ❌ | ✅ for group | ✅ |                                                                                                                                                                                                 
  | `/clear` | ✅ self | ❌ | ✅ for group | ✅ |                                                                                                                                                                                                       
  | `/compress` | ✅ self | ❌ | ✅ for group | ✅ |                                                                                                                                                                                                    
  | `/promote <contact>` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                          
  | `/demote <contact>` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                           
  | `/grant-admin <contact>` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                      
  | `/revoke-admin <contact>` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                     
  | `/ban <contact> [--reason "..."]` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                             
  | `/unban <contact>` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                            
  | `/quarantine list` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                            
  | `/quarantine approve <id>` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                    
  | `/quarantine reject <id>` | ❌ | ❌ | ❌ | ✅ |                                
  | `/audit [-w 24h]` | ❌ | ❌ | ❌ | ✅ |                                                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  Banned users get one fixed reply for any command or chat input: `Your access has been revoked.` They never reach the parser/router.                                                                                                                   
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 3.3 Discovery commands                                                                                                                                                                                                                             
   
  ### `/help`                                                                                                                                                                                                                                           
                                                                                   
  Lists all commands available **to the calling user in the current scope**. Group members see a different list than group admins. Bot admins see admin commands at the bottom.                                                                         
   
  ### `/status`                                                                                                                                                                                                                                         
                                                                                   
  Reports:                                                                                                                                                                                                                                              
  - Active hardware profile
  - Bot uptime                                                                                                                                                                                                                                          
  - Number of sources subscribed by the calling scope                                                                                                                                                                                                   
  - Number of posts in the last 24h matching this scope
  - For bot admins only: total users, banned users, pending quarantine, eval-failure counts (last 1h)                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ### `/get-tags`                                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  Lists the controlled vocabulary, sorted alphabetically. Marks tags the calling scope follows with a leading `*`.                                                                                                                                      
   
  ### `/get-sources`                                                                                                                                                                                                                                    
                                                                                   
  Alias for `/list-sources` (no `--all`). Lists sources subscribed by the calling scope.                                                                                                                                                                
   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 3.4 Content commands

  ### `/summary [tag] [-w 24h]`                                                                                                                                                                                                                         
   
  Generates an on-the-fly summary of posts matching the tag (or all followed tags if no arg) within the time window. Cluster grouping by `post_reference` graph; LLM writes prose per cluster.                                                          
                                                                                   
  Output structure (plain text):                                                                                                                                                                                                                        
                                                                                   
  News (last 24h)                                                                                                                                                                                                                                       
   
  [topic_id=t-7f3a]                                                                                                                                                                                                                                     
  CVE-2026-1234 — OpenSSL heap overflow                                            
  covered by: Bleeping Computer (uid p-a91), TheHackerNews (uid p-b04), /r/netsec (uid p-c12)                                                                                                                                                           
  score: high (3 sources, news+social)                                                                                                                                                                                                                  
  summary: A heap overflow in OpenSSL 3.5 lets a remote attacker execute code...                                                                                                                                                                        
  classification: technical, urgent                                                                                                                                                                                                                     
  tags: security, ai                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  [topic_id=t-9e02]                                                                                                                                                                                                                                     
  ...                                                                              
                                                                                                                                                                                                                                                        
  `-w` defaults to 24h. Hard cap on number of posts processed: 200 (oldest dropped); if more match, response notes the cap.
                                                                                                                                                                                                                                                        
  ### `/save <uid>` / `/save <uid> -t tag1,tag2`                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  Saves a post into the calling user's library. Always private to the user, even in groups. Personal tags are free-form and never become Tier-1 vocabulary. 1000-save cap per user.                                                                     
                                                                                   
  ### `/saved [tag] [-w 7d]`                                                                                                                                                                                                                            
                                                                                   
  Lists saved posts. Optional positional tag filters by personal tag. Optional `-w` filters by saved-within window.                                                                                                                                     
   
  Saved posts (5 total, filter: ai)                                                                                                                                                                                                                     
  - [p-a91] OpenSSL heap overflow — saved 2d ago — tags: security, read-later      
  - [p-b04] LangChain4j 1.0 release — saved 5h ago — tags: java, read-later                                                                                                                                                                             
  ...                                                                              
                                                                                                                                                                                                                                                        
  ### `/unsave <uid>`                                                              
                                                                                                                                                                                                                                                        
  Removes from library. No confirmation (cheap to redo).                                                                                                                                                                                                
   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 3.5 Source management                                                                                                                                                                                                                              
   
  ### `/add-source --type <fetcher> --url <url> --tags tag1,tag2 [--name "..."] [--category <cat>]`                                                                                                                                                     
                                                                                   
  Adds a source for the calling scope.                                                                                                                                                                                                                  
                                                                                   
  - DM: any non-banned user can add. Source is private to the calling user.                                                                                                                                                                             
  - Group: only group admins. Source is shared across the group.
                                                                                                                                                                                                                                                        
  Required:                                                                                                                                                                                                                                             
  - `--type` one of `rss`, `nitter`, `bluesky`, `odysee`, `youtube`, `reddit`, `nostr`
  - `--url` valid URL                                                                                                                                                                                                                                   
  - `--tags` comma-separated, ≥1 tag from controlled vocab. New tag values are accepted and added to vocab on the spot. **No tags = command fails.** This guarantees deterministic tagger fallback.
                                                                                                                                                                                                                                                        
  Optional:                                                                        
  - `--name` display name (auto-detected from feed if omitted)                                                                                                                                                                                          
  - `--category` `news`, `blog`, `social` (defaults: `social` for nitter/bluesky/youtube/odysee/reddit/nostr; `news` otherwise)                                                                                                                         
                                                                                                                                                                                                                                                        
  Behavior:                                                                                                                                                                                                                                             
  - If the `(fetcher, url)` pair already exists globally → just create the `source_subscription` for the calling scope (no re-fetch interval reset).                                                                                                    
  - If new → insert `source` + `source_subscription`. First fetch happens on next scheduler tick.                                                                                                                                                       
                                                                                                                                                                                                                                                        
  ▎ /add-source --type rss --url https://example.com/feed --tags ai,research --name "Example AI Blog"                                                                                                                                                   
  ▎ Added source Example AI Blog. First fetch in ~5 minutes. Use /list-sources to confirm.                                                                                                                                                              
                                                                                                                                                                                                                                                        
  ### `/list-sources [--all]`                                                      
                                                                                                                                                                                                                                                        
  Without `--all`: sources the calling scope subscribes to. With `--all` (bot admin only): every source globally with subscriber count and status.                                                                                                      
   
  ### `/unfollow-source <id>`                                                                                                                                                                                                                           
                                                                                   
  Removes the calling scope's `source_subscription`. The source itself remains globally if other scopes still subscribe.                                                                                                                                
                                                                                   
  - DM: any non-banned user (their own scope).                                                                                                                                                                                                          
  - Group: group admin only.                                                       
  - Bot admin: any scope.                                                                                                                                                                                                                               
                                                                                   
  ### `/remove-source <id>` *(bot admin only, requires confirm)*                                                                                                                                                                                        
   
  Deletes the source globally. Cascades to all subscriptions and post records (posts are kept — only the source link is severed via `ON DELETE CASCADE`? **No**: posts are kept; `source_subscription` cascades but `post.source_id` becomes            
  orphan-tolerant via a soft reference. See [02-schema.md](02-schema.md) for exact FK behavior.)
                                                                                                                                                                                                                                                        
  ▎ /remove-source 7f3a-...                                                        
  ▎ This will remove the source Example AI Blog for ALL scopes. Affected subscribers: 12.
  ▎ Type remove-source 7f3a-... confirm within 30s.                                                                                                                                                                                                     
   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 3.6 Tag preferences (per-scope)                                                                                                                                                                                                                    
   
  ### `/follow-tag <tag>` / `/unfollow-tag <tag>`                                                                                                                                                                                                       
                                                                                   
  Controls which tags appear in the periodic 8am/8pm digest for the calling scope.                                                                                                                                                                      
                                                                                   
  - DM: any non-banned user (their own scope).                                                                                                                                                                                                          
  - Group: group admin only.                                                       
  - Default behavior on a fresh scope: follows all tags from sources the scope is subscribed to. Calling `/follow-tag` switches to explicit list.                                                                                                       
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ## 3.7 Conversation control                                                                                                                                                                                                                           
   
  ### `/clear` *(requires confirm)*                                                                                                                                                                                                                     
                                                                                   
  Wipes the active context window for (user, scope). Does NOT touch `chat_memory` (long-term).                                                                                                                                                          
   
  - DM: any user (own context).                                                                                                                                                                                                                         
  - Group: group admin (clears the group's shared context for that user; **other users' per-(user, group) contexts are untouched**).
                                                                                                                                                                                                                                                        
  ### `/compress`                                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  Forces an immediate `chat_memory` checkpoint. Auto-triggered at 75% of profile context window; this command lets users do it explicitly.                                                                                                              
                                                                                   
  ▎ /compress                                                                                                                                                                                                                                           
  ▎ Compressed 47 messages into a memory entry (8 sentences, 12 keywords, 4 referenced posts).
                                                                                                                                                                                                                                                        
  ### `/lang <code>`
                                                                                                                                                                                                                                                        
  Sets the per-scope output language. ISO 639-1 codes; v1 ships with `en` and `cs`.                                                                                                                                                                     
   
  - DM: own scope.                                                                                                                                                                                                                                      
  - Group: group admin.                                                            
                                                                                                                                                                                                                                                        
  ▎ /lang cs                                                                       
  ▎ Output language for this scope set to Czech (cs). Source posts remain in their original language.
                                                                                                                                                                                                                                                        
  ---
                                                                                                                                                                                                                                                        
  ## 3.8 Bot-admin commands                                                        

  ### `/promote <contact>` / `/demote <contact>` *(group context only)*                                                                                                                                                                                 
   
  Promote a group member to group admin (or demote). Bot admins can run this from inside any group; group admins cannot promote others.                                                                                                                 
                                                                                   
  ### `/grant-admin <contact>` / `/revoke-admin <contact>`                                                                                                                                                                                              
                                                                                   
  Bot-wide admin grant/revoke. `/revoke-admin` rejects if target is the only remaining bot admin.                                                                                                                                                       
                                                                                   
  ### `/ban <contact> [--reason "..."]` / `/unban <contact>` *(requires confirm)*                                                                                                                                                                       
                                                                                   
  Bot-wide ban. Cannot ban self, cannot ban the last admin. Banned user's response after ban: `Your access has been revoked.` regardless of input.                                                                                                      
                                                                                   
  ### `/quarantine list [-w 24h]`                                                                                                                                                                                                                       
                                                                                   
  Lists pending quarantine items in the window. Output includes `quarantine_id`, `post_id`, `flagged_by` (`stage1` or `stage2`), `rule_id`, `placeholder_id`, and a short context excerpt. Raw HTML is NOT shown via chat — admins use psql for that.   
   
  ### `/quarantine approve <id> [--note "..."]` / `/quarantine reject <id> [--note "..."]`                                                                                                                                                              
                                                                                   
  Approve = restores the original span in `post.body`, sets `post.status='READY'`, NOTIFY new_post. Reject = leaves the placeholder in place permanently.                                                                                               
                                                                                   
  ### `/audit [-w 24h] [--actor <contact>] [--action <kind>]`                                                                                                                                                                                           
                                                                                   
  Reads from `audit_log`. Default window 24h. Optional filters.                                                                                                                                                                                         
   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 3.9 Onboarding

  A user's first message to the bot triggers auto-registration:                                                                                                                                                                                         
   
  [bot] Welcome! You're registered as <contact_id_short>. I aggregate news and social posts.                                                                                                                                                            
  [bot] Try /help to see commands, or just chat with me about a topic.             
  [bot] Pro tip: /summary for the last 24h, /save <uid> to bookmark a post.                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  In a group, the welcome only fires the first time a specific user `@mentions` the bot in that group.                                                                                                                                                  
                                                                                                                                                                                                                                                        
  ---                                                                              
                                                                                                                                                                                                                                                        
  ## 3.10 Examples (smoke flow)                                                    

  [user] /add-source --type rss --url https://hnrss.org/frontpage --tags tech                                                                                                                                                                           
  [bot]  Added source Hacker News Frontpage. First fetch in ~5 minutes.
                                                                                                                                                                                                                                                        
  [user] /follow-tag tech                                                          
  [bot]  Following tag tech for daily digest.                                                                                                                                                                                                           
                                                                                   
  [user] /summary tech                                                                                                                                                                                                                                  
  [bot]  Tech (last 24h)
         [topic_id=t-...] ... (4 sources)                                                                                                                                                                                                               
         summary: ...                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  [user] /save p-a91 -t to-read,interesting                                                                                                                                                                                                             
  [bot]  Saved post p-a91 with tags: to-read, interesting.                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  [user] /saved to-read
  [bot]  Saved posts (1, filter: to-read)                                                                                                                                                                                                               
         - [p-a91] ...                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  [user] hey, can you tell me more about the OpenSSL bug from earlier today?                                                                                                                                                                            
  [bot]  (chat agent: pre-fetch memory, look up referenced posts, write prose)                                                                                                                                                                          
                                                                                                                                                                                                                                                        
                                                                                   
  --- 
