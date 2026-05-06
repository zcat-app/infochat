> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in `docs/spec/`.

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
                                                                                                                                                                                                                                                        
  The suffix `m` is intentionally not accepted (ambiguous between minutes and months: minutes are too small to be useful, and the longest meaningful window is `30d`, which equals the post TTL). Use `30d` if you want a 30-day window.

  Default `-w 24h` for `/summary` and similar commands.                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ### Tag arguments                                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  Tag arguments are exact-match against the controlled vocabulary. Case-insensitive lookup; output uses the canonical `display` casing. Unknown tag → friendly error with fuzzy suggestions.

  **The `socials` tag** is part of the controlled vocabulary, seeded by `bootstrap-sources.json`. The tagger auto-assigns `socials` to every post coming from a source whose `category = 'social'` (Reddit, Bluesky, Nitter, Nostr, Odysee, YouTube). It otherwise behaves like any other tag: `/follow-tag socials`, `/unfollow-tag socials`, and `/summary socials [-w 24h]` all work. This gives users a one-shot way to opt into / out of social-feed noise without listing every social fetcher individually.
                                                                                                                                                                                                                                                        
  ### Confirmation for destructive commands                                        
                                                                                                                                                                                                                                                        
  Destructive commands (`/clear`, `/remove-source`, `/ban`, `/forget` v2) require confirmation:
                                                                                                                                                                                                                                                        
  ▎ /clear
  ▎ This will wipe your active chat context. Type `/clear confirm` within 30s.

  ▎ /clear confirm
  ▎ Context cleared.

  The confirmation message MUST start with the same slash command as the original (so a bare `confirm` does not trigger anything, and a `confirm` typed in the wrong scope cannot accidentally fire a destructive action). Confirmation tokens are scoped to (user, scope) and expire after 30 seconds.

  Sending any other input cancels the pending confirmation. The bot replies with a one-line acknowledgement so a user who fat-fingers a follow-up message is never left guessing whether the original `/clear` (or `/ban`, etc.) "stuck":

  ▎ /clear
  ▎ This will wipe your active chat context. Type `/clear confirm` within 30s.

  ▎ what's the weather?
  ▎ Pending /clear cancelled.
  ▎ (and the bot answers `what's the weather?` normally)

  The cancellation reply names the original command so the user knows exactly what was rolled back.
                                                                                                                                                                                                                                                        
  ### Friendly errors
                                                                                                                                                                                                                                                        
  Unknown command, unknown tag, unknown source ID, malformed flag → response includes:
  1. What was wrong (specific token)
  2. Up to 3 fuzzy suggestions, ranked by Levenshtein distance, capped at
     `min(2, ceil(len(input) / 2))`. The adaptive bound prevents pathological
     suggestions for short tokens — e.g. `/help` distance-2 from `/heap`/`/hold`/`/herd`
     is correct, but a 3-character tag at distance 2 collapses into "any 3-letter
     tag in the vocab" and the suggestions are noise.
  3. The exact help line for the command (or `/help` pointer)                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  Example:                                                                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  ▎ /summary securty -w 24h                                                                                                                                                                                                                             
  ▎ Unknown tag securty. Did you mean: security, sectors?                          
  ▎ Available tags: ai, bitcoin, blog, devops, java, news, science, security, socials.                                                                                                                                                                  
  ▎ Usage: /summary [tag] [-w 24h]                                                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  ### Output formatting                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Plain text. Inline code wrapped in single backticks. Multi-line code in triple backticks. URLs bare. Adapters with `supportsMarkdownCode = true` may render backticks as styled monospace.                                                            

  ### Input length limits

  Hard caps applied at the parser before any LLM or DB work. Inputs over the cap are rejected with a friendly error.

  | Field | Cap |
  |---|---|
  | Chat message body | `profile.context_window / 8` chars (laptop=2048, vps=1024, pi=512, remote=4096) |
  | `--name` | 200 chars |
  | `--reason` / `--note` | 500 chars |
  | Personal tags (sum of all `-t` values per `/save`) | 200 chars |
  | Single tag value | 32 chars |
  | Slash command line (whole input) | 4096 chars |

  Limits are constants in `infochat-core`; not user-tunable.

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
  | `/clear` | ✅ self | ✅ self | ✅ self | ✅ self |
  | `/compress` | ✅ self | ✅ self | ✅ self | ✅ self |                                                                                                                                                                                                    
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

  **No-arg behavior with many followed tags.** When `/summary` is called with no positional tag and the calling scope follows more than 5 tags, the retrieval is restricted to the **3 most-active tags in the window** (most posts in the requested `-w`). The reply is prefixed with:

  ▎ Showing top 3 of N followed tags. Use `/summary <tag>` for a specific topic.

  This avoids the failure mode where a user with 30 followed tags gets a summary that times out or quietly drops most of the content into the cluster cap. Scopes with ≤5 followed tags continue to summarize across all of them — the restriction only kicks in at 6+.                                                          
                                                                                   
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
                                                                                                                                                                                                                                                        
  `-w` defaults to 24h.

  **Cluster cap is profile-driven** via `infochat.summary.cluster-cap`:

  | Profile | Cap |
  |---|---|
  | `laptop` | 200 |
  | `vps` | 100 |
  | `pi` | 50 |
  | `remote` | 500 |

  When the deterministic SQL retrieval returns more posts than the cap, the **oldest** posts are dropped (the most recent within the window survive). The response notes both the cap and the excluded count, e.g. `Showing 100 of 137 posts (cap: vps=100; 37 oldest excluded)`. See [05-llm-and-embeddings.md §5.7](05-llm-and-embeddings.md) for how the cap interacts with cluster sizing and prompt budget.
                                                                                                                                                                                                                                                        
  ### `/save <uid>` / `/save <uid> -t tag1,tag2`                                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  Saves a post into the calling user's library. Always private to the user, even in groups. Personal tags are free-form and never become Tier-1 vocabulary. 1000-save cap per user.                                                                     
                                                                                   
  ### `/saved [tag] [-w 7d] [--page N]`

  Lists saved posts. Optional positional tag filters by personal tag. Optional `-w` filters by saved-within window. Optional `--page N` selects the 1-indexed page; default `1`. **Page size is fixed at 20** and is not user-tunable.

  Saved posts (5 of 47 total, page 1/3, filter: ai)
  - [p-a91] OpenSSL heap overflow — saved 2d ago — tags: security, read-later
  - [p-b04] LangChain4j 1.0 release — saved 5h ago — tags: java, read-later
  ...
  Tip: use `/saved ai --page 2` for the next page.                                                                              
                                                                                                                                                                                                                                                        
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
                                                                                                                                                                                                                                                        
  ### `/list-sources [--all] [--page N]`

  Without `--all`: sources the calling scope subscribes to. With `--all` (bot admin only): every source globally with subscriber count and status.

  Paginated like `/saved`: `--page N` is 1-indexed, **page size fixed at 20**, total count + page indicator shown in the header. The footer suggests `/list-sources --page <N+1>` when more pages remain.                                                                                                      
   
  ### `/unfollow-source <id>`

  Removes the calling scope's `source_subscription`. The source itself remains globally if other scopes still subscribe. **Not the same as `/remove-source` below**: `/unfollow-source` is per-scope and available to non-admins; `/remove-source` is a global admin-only soft-delete.                                                                                                                                
                                                                                   
  - DM: any non-banned user (their own scope).                                                                                                                                                                                                          
  - Group: group admin only.                                                       
  - Bot admin: any scope.                                                                                                                                                                                                                               
                                                                                   
  ### `/remove-source <id>` *(bot admin only, requires confirm)*

  **Soft-delete only.** Hard delete is forbidden in v1.

  Behavior:
  - Sets `source.deleted_at = now()` and stops the fetcher for this source on the next scheduler tick.
  - `post` rows are kept untouched. `post.source_id` is `ON DELETE RESTRICT`, so post history (and any clusters / summaries derived from it) survives the removal.
  - `saved_post` references continue to resolve normally — bookmarked posts remain readable for every user who saved them.
  - All `source_subscription` rows referencing this source are removed (no scope continues to fetch a deleted source).
  - Re-adding the same `(fetcher, url)` pair via `/add-source` clears `deleted_at` and reactivates the source (a "restore", not a duplicate row).

  See [02-schema.md §2.2](02-schema.md) for the FK behavior and `deleted_at` column.

  ▎ /remove-source 7f3a-...
  ▎ This will soft-delete source Example AI Blog. Affected subscribers: 12. Posts and saves stay intact.
  ▎ Type `/remove-source 7f3a-... confirm` within 30s.                                                                                                                                                                                                     
   
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
                                                                                   
  Wipes the active context window for the calling (user, scope). Does NOT touch `chat_memory` (long-term).

  Every user has an independent (user, scope) session, even inside a group — there is no "shared" group context. `/clear` only ever affects the caller's own (user, scope) row; other users in the same group are untouched.

  - DM: clears the calling user's DM context.
  - Group: clears the calling user's context for *that group only*.
                                                                                                                                                                                                                                                        
  ### `/compress`                                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  Forces an immediate `chat_memory` checkpoint for the calling (user, scope). Auto-triggered at 75% of profile context window; this command lets users do it explicitly. Like `/clear`, it only ever affects the caller's own (user, scope) — there is no shared group memory.                                                                                                              
                                                                                   
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
                                                                                   
  ### `/quarantine list [-w 24h] [--page N]`

  Lists pending quarantine items in the window. Output includes `quarantine_id`, `post_id`, `flagged_by` (`stage1` or `stage2`), `rule_id`, `placeholder_id`, and a short context excerpt. Raw HTML is NOT shown via chat — admins use psql for that.

  Paginated like `/saved` and `/list-sources`: `--page N` 1-indexed, page size fixed at 20.   
   
  ### `/quarantine approve <id> [--note "..."]` / `/quarantine reject <id> [--note "..."]`                                                                                                                                                              
                                                                                   
  Approve = restores the original span in `post.body`, sets `post.status='READY'`, NOTIFY new_post. Reject = leaves the placeholder in place permanently.                                                                                               
                                                                                   
  ### `/audit [-w 24h] [--actor <contact>] [--action <kind>] [--page N]`

  Reads from `audit_log`. Default window 24h. Optional filters. Paginated: `--page N` 1-indexed, page size fixed at 20.                                                                                                                                                                                         
   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 3.9 Onboarding

  A user's first interaction triggers auto-registration. The welcome message branches on three modes — each tuned so the user is not steered toward an action that will fail or feel empty.

  ### Mode 1 — DM, fresh user (no sources subscribed yet)

  Chat would be empty (no posts to ground on), so we steer the user to `/add-source` instead of inviting open-ended chat.

  [bot] Welcome! You're registered as <contact_id_short>. I aggregate news and social posts.
  [bot] You don't have any sources yet — I won't have anything to summarize or chat about until you add one.
  [bot] Try: /add-source --type rss --url https://example.com/feed --tags news
  [bot] Run /help any time to see what else I can do.

  ### Mode 2 — DM, returning user (already has sources)

  Short welcome-back, suggest the commands they're most likely to want first.

  [bot] Welcome back. You have <N> sources subscribed.
  [bot] Try /summary for the last 24h, /saved to revisit bookmarks, or just ask me about a topic.

  ### Mode 3 — Group, first @mention by a specific user

  Fires once per (user, group) pair. The bot is shared, so we point the user at `/help` rather than dumping setup advice into the channel.

  [bot] Hi @<contact_id_short>! Use /help to see what I can do here.
  [bot] (Group admins can /add-source and /follow-tag to curate the feed for everyone in this group.)                                                                                                                                                  
                                                                                                                                                                                                                                                        
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
