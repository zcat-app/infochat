---                                                                                                          
  Infochat Spec Review: Findings & Recommendations                                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  1. Security Issues
                                                                                                                                                                                                                                                        
  1.1 SSRF risk in /add-source (HIGH)

  The spec says --url is "validated as well-formed URL" but doesn't mention SSRF protection. The Collector fetches from whatever URL is registered. A malicious (or compromised) user could register http://169.254.169.254/latest/meta-data/ (cloud
  metadata), http://localhost:6379/ (Redis), or other internal endpoints. Recommendation: Explicitly specify URL validation that blocks private/reserved IP ranges, localhost, and non-HTTP(S) schemes. Add to threat model as T10.

  1.2 Bidi-control stripping only in Stage 1 (MEDIUM)

  Unicode bidi controls (U+202A–U+202E) are stripped only during Collector ingest (Stage 1). Chat-mode messages from users bypass Stage 1 entirely and go directly to the LLM. A user could embed bidi controls in a chat message to visually spoof the
  assistant's reply in their messaging client, or craft text that looks different from what the LLM reads. Recommendation: Strip bidi controls in the Provider's message intake path too, before the LLM sees it.

  1.3 No input length validation (MEDIUM)

  The spec defines no maximum length for:
  - Chat-mode messages passed to the LLM
  - /add-source --name
  - /ban --reason
  - /quarantine approve --note
  - /save personal tags

  A user could send a 100KB chat message that consumes enormous LLM tokens. Recommendation: Define max lengths per field (e.g., chat message 4000 chars, name 200 chars, reason 500 chars) and enforce at the adapter or command parser level.

  1.4 /add-source lets any user expand the controlled vocabulary (MEDIUM)

  The spec says new --tags values "are accepted and added to vocab on the spot." Any non-banned user can create new Tier-1 tags via /add-source. A malicious user could pollute the vocabulary with junk tags. Combined with the tagger's "If none fit
  well, output {"tags": []}" behavior, this could degrade tag quality. Recommendation: Either require bot-admin approval for new tags, or add a rate limit specifically on new-tag creation, or limit tag creation to admins.

  1.5 original_html in quarantine could re-inject (LOW, but documented)

  The spec correctly notes that raw HTML is not shown via chat. However, if an admin uses /quarantine approve <id> without seeing the raw content, they're approving blind. The --note field doesn't help here. Recommendation: Consider adding a
  sanitized preview (text-only, no HTML) to the /quarantine list output, or require --reason for approve.

  1.6 Translation cache keyed by SHA-256(text) (LOW)

  Two different texts with the same SHA-256 would return the wrong translation. While cryptographically infeasible to exploit intentionally, it's a correctness issue. Recommendation: Include text length in the cache key, or use a more robust key.

  1.7 No CSRF protection on management endpoints (LOW)

  /q/health, /q/metrics are exposed by Quarkus. If the host is internet-facing without a reverse proxy, these leak operational data. Recommendation: Document that these must be bound to localhost or protected by firewall in production.

  ---
  2. Ambiguous / Contradictory Cases

  2.1 /summary post cap: 200 vs profile-specific (CONTRADICTION)

  - 03-commands.md §3.4: "Hard cap on number of posts processed: 200"
  - 05-llm-and-embeddings.md §5.7: infochat.summary.cluster-cap is 200/100/50/500 per profile

  These disagree. Recommendation: Reconcile. The profile-specific cap makes more sense; update 03-commands.md to say "cap driven by infochat.summary.cluster-cap per profile."

  2.2 /clear in group: "shared context" vs per-(user, group) (AMBIGUOUS)

  - 03-commands.md: "Group: group admin (clears the group's shared context for that user; other users' per-(user, group) contexts are untouched)"
  - 02-schema.md: chat_session PK is (user_id, scope_kind, scope_id) — every user has their own session

  If context is per-user, what does "the group's shared context for that user" mean? Is there a shared group context layer? Recommendation: Clarify that /clear in a group clears only the calling user's session for that group. Remove the confusing
  "shared context" wording.

  2.3 socials tag: auto-assigned but not followable? (AMBIGUOUS)

  - 02-schema.md: "socials tag is auto-assigned to any post whose source.category = 'social'"
  - No mention of socials in the controlled vocabulary, /follow-tag, or /get-tags

  Is socials a Tier-1 tag? Can users follow it? Is it in the vocabulary? Recommendation: Explicitly define whether socials is a special tag or a regular Tier-1 tag. If special, document its behavior in the tag system.

  2.4 /follow-tag default behavior on fresh scope (AMBIGUOUS)

  The spec says: "Default behavior on a fresh scope: follows all tags from sources the scope is subscribed to. Calling /follow-tag switches to explicit list."

  But what's in the initial explicit list when the user first calls /follow-tag? Empty? All currently followed? Recommendation: Specify: "First /follow-tag call copies the current implicit set to explicit, then adds/removes the specified tag."

  2.5 body_summary generation: when and where? (UNSPECIFIED)

  The post table has body_summary TEXT described as "LLM-generated abstract if body length > threshold." But:
  - What threshold?
  - Which pipeline stage generates it?
  - Which LLM task?
  - What's the fallback if it fails?

  This is referenced in the embedding pipeline ("title + body_summary or first 800 chars of body") but never defined. Recommendation: Add a §1.3.x or §5.4.x specifying: threshold (e.g., 2000 chars), generated during eval pipeline between tagger and
   embedder, uses the summarizer model, fallback = first N chars of body.

  2.6 Group members can run /summary? (CONTRADICTION)

  - Permission matrix (§3.2): /summary — Group (member) = ✅
  - But periodic digest is the group's main content delivery mechanism
  - /summary in a group: does it scope to the group's sources? The user's sources? Both?

  Recommendation: Clarify: "In a group, /summary uses the group's source subscriptions and tag follows. Any group member can run it."

  2.7 1m = rolling 30 days (UX CONFUSION)

  1m means "1 month" = rolling 30 days. But m universally means "minutes" in time notation. A user typing /summary -w 1m expecting "last 1 minute" gets "last 30 days." Recommendation: Change to 1M (capital) for month, or use 30d and drop 1m
  entirely.

  2.8 source.status = 'disabled': no command to set it (GAP)

  The schema defines status TEXT NOT NULL DEFAULT 'active' with values 'active','failed','disabled'. But no command sets disabled. The /remove-source deletes the source entirely. Recommendation: Either add a /disable-source command, or remove
  'disabled' from the status enum.

  2.9 post.source_id ON DELETE CASCADE conflicts with saved_post (POTENTIAL BUG)

  - post.source_id has ON DELETE CASCADE — deleting a source deletes its posts
  - saved_post.post_id has ON DELETE RESTRICT — can't delete a saved post
  - These conflict: /remove-source would fail if any post from that source is saved

  The spec says "posts are kept; source_subscription cascades but post.source_id becomes orphan-tolerant via a soft reference" — but the DDL says ON DELETE CASCADE. Recommendation: Change post.source_id FK to ON DELETE SET NULL (make source_id
  nullable) or ON DELETE RESTRICT and handle deletion in application code.

  2.10 scope_preferences.timezone: who sets it? (UNSPECIFIED)

  The table has a timezone column that overrides groups.timezone. But no command sets it. Recommendation: Either add a /timezone command or document it as operator-only config.

  ---
  3. UX Problems

  3.1 No pagination on /saved (HIGH)

  Up to 1000 saves per user, no pagination. On SimpleX with a 4000-byte message limit, the output would be chunked into many messages. Recommendation: Add pagination: /saved [tag] [-w 7d] [--page N] or limit output to 20 items with "showing 1-20 of
   142."

  3.2 /summary output includes score: high but prompt doesn't define it (INCONSISTENCY)

  The example output in §3.4 shows score: high (3 sources, news+social) but the summarizer prompt template (§5.4.4) doesn't include a score field. The prompt asks for classification instead. Recommendation: Either remove score from the example or
  add it to the prompt template.

  3.3 No /help for a specific command (MINOR)

  /help lists all commands. But if a user types /help summary, there's no per-command help. The friendly error system shows the usage line, but proactive help would be better. Recommendation: Support /help <command> for detailed per-command help.
  Low priority for v1.

  3.4 Welcome message shows truncated contact ID (MINOR)

  "You're registered as " — SimpleX contact IDs are long cryptographic strings. Showing even a truncated version is meaningless to users. Recommendation: Let users set a display name, or just say "You're registered!"

  3.5 /unsave has no confirmation but /save could be accidental (MINOR)

  The spec says /unsave needs no confirmation because "cheap to redo." But if a user accidentally unsaves a post from a source that gets TTL-pruned, the post is gone. Recommendation: Acceptable for v1, but consider confirmation for v2.

  3.6 No feedback on rate-limit overflow (PARTIALLY ADDRESSED)

  The spec says "friendly reject, 'slow down, try again in {N}s'" — good. But the per-user /add-source rate limit (5/hour) doesn't say what the error message is. Recommendation: Specify error messages for all rate limits.

  ---
  4. Usability for Agentic Development

  4.1 Strengths (what works well)

  - Clear module boundaries — 5 Maven modules with well-defined responsibilities
  - SPI contracts — LlmProvider, EmbeddingProvider, MessagingAdapter are concrete interfaces
  - Explicit "not in v1" — every doc has a §X.N "What's intentionally NOT in v1" section, preventing scope creep
  - Deterministic boundary — "LLM only for prose, SQL for retrieval" is a clear rule agents can follow
  - Profile-driven defaults — single config knob reduces decision fatigue
  - Detailed prompt templates — actual prompt text in §5.4, not just descriptions
  - Verification spec — §08 defines exactly what tests to write, with naming conventions

  4.2 Weaknesses (what hinders agentic development)

  No task decomposition

  The spec reads as a design document, not a work breakdown. An agent would struggle to answer:
  - "What do I implement first?"
  - "What depends on what?"
  - "How do I estimate this?"

  Recommendation: Add a docs/00-tasks.md with ordered, dependency-annotated tasks:
  T1: infochat-core entities + Flyway migrations (blocks everything)
  T2: MessagingAdapter SPI + InMemoryAdapter (blocks T5, T6)
  T3: LlmProvider SPI + FakeLlmProvider (blocks T4, T5)
  T4: Collector eval pipeline (depends: T1, T3)
  T5: Provider command router + chat agent (depends: T1, T2, T3)
  T6: E2E smoke tests (depends: T4, T5)
  ...

  No acceptance criteria per feature

  The spec describes behavior but doesn't define "done." For example:
  - /add-source — what's the exact error when --tags is missing? What's the success message format?
  - /summary — what happens with 0 matching posts? What's the exact output format?

  Recommendation: For each command, add a §X.Y.Z "Acceptance criteria" with:
  1. Happy path input → expected output (exact text or pattern)
  2. Each error case → expected error message
  3. Edge cases → expected behavior

  Cross-references are fragile

  The spec uses [02-schema.md](02-schema.md) style links. An agent implementing a feature needs to chase 3-4 references to get the full picture. Recommendation: For each major feature, consolidate the relevant cross-references into a single
  "implementation brief" section, or use a task-oriented structure.

  No data model ERD

  The schema is defined as individual CREATE TABLE statements. An agent needs to mentally construct the relationships. Recommendation: Add a Mermaid ERD or textual relationship map showing FK relationships at a glance.

  Inconsistent specificity

  Some sections are extremely detailed (e.g., §5.4 prompt templates are copy-pasteable), while others are vague (e.g., "body_summary if body length > threshold"). Agents need uniform specificity to avoid getting stuck. Recommendation: Audit each
  section for "would an agent need to ask a clarifying question?" and fill gaps.

  ---
  5. Summary of Recommendations

  Critical (fix before implementation)

  1. Resolve post.source_id ON DELETE CASCADE vs saved_post ON DELETE RESTRICT conflict — this will cause runtime errors
  2. Reconcile /summary post cap (200 vs profile-specific)
  3. Define body_summary generation — it's referenced but never specified
  4. Add SSRF protection to URL validation in /add-source

  High Priority

  5. Add input length limits for chat messages, names, reasons
  6. Strip bidi controls in Provider intake (not just Stage 1)
  7. Clarify /clear group semantics — remove "shared context" ambiguity
  8. Define socials tag behavior — is it Tier-1? Followable?
  9. Add pagination to /saved

  Medium Priority (improve spec quality for agentic dev)

  10. Create task decomposition (00-tasks.md) with dependencies
  11. Add acceptance criteria per command
  12. Add ERD for the data model
  13. Specify error messages for all rate limits and validation failures
  14. Document 1m ambiguity — change to 1M or 30d

  Low Priority

  15. Add /help <command> support
  16. Clarify scope_preferences.timezone setter
  17. Remove or implement source.status = 'disabled'
  18. Add per-command acceptance tests to §08
