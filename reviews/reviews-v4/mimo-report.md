1. Executive Summary                                                                                                                                                                                                                                  
                  
  I read all ten spec files (SPEC.md, decisions.md, architecture.md, security.md, schema.md, commands.md, llm.md, messaging.md, deployment.md, verification.md) plus the MVP slice (00-mvp.md). The spec is impressively thorough for an early-stage
  project — the decision log is unusually disciplined, the security model is concrete, and the determinism boundary is crisply stated. However, the spec has three structural problems: (1) the MVP slice contradicts the v1 scope on invite-code
  registration and the slow-start tier, creating an ambiguity about what "v1" means; (2) the /add-source permission model allows any non-banned DM user to overwrite the global bootstrap_tags on a source shared by other scopes, which is a
  privilege-escalation hole; and (3) several failure modes (compression, translation provider down, localization bundle miss) are unspecified despite being on the critical path for degraded operation.

  ---
  2. Findings

  ▎ F01 — MVP auto-registration directly contradicts v1 invite-code gate

  Severity: blocker
  Category: inconsistency
  Location: docs/SPEC.md §4 (lines 91–95), docs/00-mvp.md §4 (line 89), docs/spec/security.md §Invite-code registration
  Confidence: high

  What the spec says: SPEC.md §4 lists "Invite-code registration (decision D44)" and "Slow-start tier (decision D45)" as in scope for v1. Security.md specifies that an unknown DM contact's first message must be a valid PENDING invite code;
  otherwise registration fails.

  Why it's a problem: 00-mvp.md §4 says: "Onboarding: auto-register on first DM message; reply with /help." This is the D23 auto-register path, which D44 explicitly supersedes for DM access. The MVP also defers /invite, /vouch, /ban, /unban — all
  prerequisites for the invite-code and slow-start systems to function. An implementer building the MVP will build auto-registration; then when they reach v1, they must rip it out and replace it with invite-code gating plus the probation tier. The
  MVP is supposed to be a "strict subset" of the spec, but it implements a path the spec has retired.

  Suggested resolution: Either (a) explicitly list D44 invite-code registration and D45 slow-start tier in 00-mvp.md §5 deferred list and note that the MVP uses the legacy D23 auto-register path, or (b) include invite-code registration in the MVP
  (add /invite create and /vouch to the MVP command set, add the invite_code table to §2). Option (a) is smaller but creates a v1 migration burden; option (b) is larger but keeps the MVP honest.

  ---
  ▎ F02 — /add-source allows any DM user to overwrite global bootstrap_tags

  Severity: blocker
  Category: security / inconsistency
  Location: docs/spec/commands.md §Source management (lines 174–201)
  Confidence: high

  What the spec says: "DM: any non-banned user adds to their own scope. … the new call's --tags replace the existing bootstrap_tags for that source row."

  Why it's a problem: Source rows are global (decision D7). When user A in DM does /add-source https://bbc.co.uk/rss --tags cats,dogs on a source that was bootstrapped with tags news,world, the bootstrap_tags on the shared source row change for
  every subscriber — every group, every other DM user. The tagger's deterministic fallback (decision D22) now uses cats,dogs instead of news,world. A malicious or careless user can silently corrupt the fallback tags for all consumers of that
  source. The permission for /add-source in DM is "any non-banned user" (decision D14), so there's no admin gate.

  Suggested resolution: Tag replacement on an existing global source row should require bot admin. For non-admin DM users, /add-source on an existing (kind, identifier) should be a no-op subscription upsert (create the source_subscription if it
  doesn't exist) without touching the source row's bootstrap_tags. Draft replacement: "When the source (kind, identifier) already exists, a non-admin caller's source_subscription is upserted but the source row's bootstrap_tags are NOT modified.
  Only a bot admin may replace bootstrap_tags on an existing source."

  ---
  ▎ F03 — Failure mode of /compress and auto-compress is unspecified

  Severity: major
  Category: gap
  Location: docs/spec/security.md, docs/spec/llm.md, docs/spec/commands.md
  Confidence: high

  What the spec says: /compress "forces an immediate chat_memory checkpoint" (commands.md §Conversation control). Auto-compress fires "near the context-window ceiling" (decision D24). Neither security.md's failure-handling section nor llm.md's
  failure-handling recap mentions compression.

  Why it's a problem: Compression invokes the LLM (to produce a summary + keywords). If the LLM is down when auto-compress triggers (e.g., the context window is full but Ollama is unreachable), what happens? Options include: (a) the chat session is
   frozen (user can't send more messages because the context is full and compression can't run); (b) the oldest messages are silently dropped without summarization; (c) the user is told to run /clear. The spec doesn't say. This is on the critical
  path: a user chatting with a local Ollama that crashes mid-conversation hits this scenario within minutes.

  Suggested resolution: Add a compression-failure paragraph to security.md §Failure handling: "Compression failure (LLM unreachable, timeout, schema-violating output after retry) → the context window is NOT truncated; the user receives a 'context
  full, run /clear or try again later' error on their next message. Auto-compress failure does not silently drop history."

  ---
  ▎ F04 — /add-source URL validation runs from Provider but SSRF allowlist is Collector-owned

  Severity: major
  Category: ambiguity / smell
  Location: docs/spec/commands.md §Source management (lines 181–192), docs/spec/security.md §SSRF
  Confidence: medium

  What the spec says: "For HTTP-shaped kinds, the Provider performs a lightweight HEAD … reachability probe through the Collector's SSRF allowlist (security.md §SSRF) before the source row is written."

  Why it's a problem: The SSRF allowlist logic (IP blocklist, DNS-rebind defense, redirect caps) is specified as a Collector concern in security.md. The Provider now needs to duplicate or share this logic. The spec doesn't say whether this is a
  shared library, an internal API call from Provider to Collector, or a code-level duplication. If it's duplication, the two copies can drift. If it's an API call, the spec doesn't define the contract. The phrase "through the Collector's SSRF
  allowlist" is ambiguous — does the Provider import the Collector's code (Maven dependency?) or call an endpoint?

  Suggested resolution: Add one sentence to commands.md or architecture.md specifying the sharing mechanism: "The SSRF allowlist is a shared library module consumed by both services" (or "the Provider calls a Collector-internal validation
  endpoint", whichever is intended).

  ---
  ▎ F05 — source_subscription lifecycle on source soft-delete is unspecified

  Severity: major
  Category: gap
  Location: docs/spec/schema.md §Sources and tags (lines 46–64, 65–66)
  Confidence: high

  What the spec says: source has a deleted_at column. deleted_at "records 'user removed this from a scope and the source has no remaining subscribers'." The fetcher scheduler selects WHERE status = 'active' AND deleted_at IS NULL.
  source_subscription is "a (scope, source) link."

  Why it's a problem: The spec implies that deleted_at is set when "no remaining subscribers" exist, which means subscriptions are removed before the soft-delete. But the spec doesn't say: (a) are source_subscription rows deleted when
  /unfollow-source removes the last subscriber, or is there a separate step? (b) what happens to source_subscription rows if a source is soft-deleted by /remove-source (bot-admin command) while other scopes still have subscriptions? (c) does
  /remove-source cascade-delete all subscriptions, or does it only set deleted_at? An implementer could reasonably build any of these.

  Suggested resolution: Add one invariant or paragraph to schema.md: "/remove-source (bot admin) sets deleted_at on the source row and cascade-deletes all source_subscription rows for that source. /unfollow-source deletes only the caller's
  subscription row; if the last subscription is removed, the source row is NOT soft-deleted (sources can exist without subscribers)." Or whatever the intended behavior is — the point is that it must be stated.

  ---
  ▎ F06 — category field on sources has no specified usage

  Severity: minor
  Category: gap
  Location: docs/spec/schema.md §Sources (line 45), docs/spec/deployment.md §Bootstrap behavior (line 68)
  Confidence: medium

  What the spec says: Source carries a category field. Bootstrap entries require category — "one of news / blog / social." /add-source does not mention category.

  Why it's a problem: The category field is required on bootstrap entries but never appears in any retrieval, filtering, or display logic anywhere in the spec. It's not used in /summary, /list-sources, /get-sources, or the chat agent's tool
  surface. If it's purely informational metadata, the spec should say so. If it's intended for filtering or display, the spec is missing that commitment. Additionally, /add-source doesn't accept a --category flag — so user-added sources presumably
  get a default, but the spec doesn't say what.

  Suggested resolution: Either (a) state "category is informational metadata on the source row; it is not used for retrieval or filtering in v1" and add --category to /add-source with a default of news, or (b) specify a retrieval/filtering use
  (e.g., "the chat agent's tool surface can filter by category").

  ---
  ▎ F07 — /compress and auto-compress failure: no specification for LLM-down scenario

  Severity: major
  Category: failure-mode
  Location: docs/spec/security.md §Failure handling, docs/spec/llm.md §Failure handling
  Confidence: high

  (This is the same underlying gap as F03 — included separately because it also manifests in llm.md's failure-handling recap, which lists every other LLM-dependent stage but omits compression.)

  What the spec says: llm.md §Failure handling (recap) lists: Security Stage 2, Tagger, Entity extractor, Embedding, Translation. Compression is absent.

  Why it's a problem: An implementer reading the failure-handling sections will implement fallbacks for every listed stage and miss compression entirely. The auto-compress path is especially dangerous because it's triggered by system state (context
   window fullness), not user action, so the user has no opportunity to retry.

  Suggested resolution: Add "Compression (manual /compress or auto-compress) — LLM failure → context window is NOT truncated; user receives a 'context full' error; manual /clear is the escape hatch" to both security.md §Failure handling and llm.md
  §Failure handling (recap).

  ---
  ▎ F08 — MVP /summary references post_reference clustering but that table is deferred

  Severity: major
  Category: inconsistency
  Location: docs/00-mvp.md §4 (line 85), docs/spec/commands.md §Content (lines 67–77), docs/00-mvp.md §2 (line 43)
  Confidence: high

  What the spec says: 00-mvp.md §4: "/summary [-w 1h|24h|7d] — DM only; on-the-fly summarization (no cache); deterministic SQL select of READY posts in the time window for the user's subscriptions; LLM produces prose." 00-mvp.md §2 defers
  post_reference. Commands.md §/summary: "Clusters (connected components of the post_reference graph) are computed by deterministic SQL traversal before any LLM call."

  Why it's a problem: The MVP's /summary is supposed to be a "strict subset" of the spec, but the spec's /summary depends on post_reference for clustering, which the MVP defers. An implementer must choose: (a) skip clustering entirely in the MVP
  (each post is its own "cluster"), or (b) build post_reference anyway. The MVP doesn't say which. The MVP exit criterion #6 says "LLM prose covering only that user's subscribed posts in the window" — no mention of clusters — which suggests (a),
  but this should be explicit.

  Suggested resolution: Add a note to 00-mvp.md §4: "In the MVP, /summary treats each post as an independent item (no clustering). The post_reference table and cluster-cap logic are deferred per §5."

  ---
  ▎ F09 — Verification test hardcodes 30-second confirmation timeout

  Severity: minor
  Category: layering
  Location: docs/spec/verification.md §Commands and chat (line 87)
  Confidence: high

  What the spec says: "Confirmation token state machine: 30-second timeout rejects late confirms."

  Why it's a problem: Commands.md §Surface conventions says the confirmation timeout is "a fixed, profile-tunable timeout" whose "exact duration is a profile-driven value (design notes)." The verification test hardcodes 30 seconds. If the design
  notes pick a different value, the test either needs to be updated (spec/design coupling) or it tests the wrong thing. The verification spec should test the behavior ("late confirm past the timeout is rejected"), not a specific value.

  Suggested resolution: Change verification.md to: "Confirmation token state machine: a confirm arriving after the profile-configured timeout is rejected; bare confirm doesn't fire anything; cross-scope confirm rejected; non-confirm input cancels
  with an explicit ack."

  ---
  ▎ F10 — MVP scope does not mention invite-code or slow-start as deferred

  Severity: major
  Category: scope
  Location: docs/00-mvp.md §5
  Confidence: high

  What the spec says: 00-mvp.md §5 deferred list does not mention D44 (invite-code registration), D45 (slow-start tier), or the invite_code table.

  Why it's a problem: SPEC.md §4 lists both as v1 scope. The MVP is a "strict subset" of v1. If the MVP defers them, they must appear in §5. Their absence means an implementer cannot tell whether to build invite-code gating in the MVP or not.
  Combined with F01, this creates a contradiction: the MVP auto-registers, the v1 spec requires invite codes, and neither document explicitly acknowledges the gap.

  Suggested resolution: Add to 00-mvp.md §5: "Invite-code registration (decision D44): the invite_code table, /invite commands, and invite-gated DM registration. Slow-start tier (decision D45): the probation_until column, /vouch, and the
  probation-aware permission matrix. The MVP uses the legacy D23 auto-register path."

  ---
  ▎ F11 — TranslationProvider failure: "fall back to English" is ambiguous when provider is a local model

  Severity: minor
  Category: ambiguity
  Location: docs/spec/llm.md §Failure handling (line 243–245)
  Confidence: medium

  What the spec says: "Translation — sanity-check the output: if the response is identical to the input, empty, or clearly not in the target language, fall back to English with a one-line note."

  Why it's a problem: The "fall back to English" path assumes the English text already exists (which it does — it's the LLM-generated prose). But the spec doesn't say what happens when the TranslationProvider call itself fails (timeout, exception,
  provider down). The sanity-check language covers garbage output but not a hard failure. Is the behavior the same (send English + note)? If so, the spec should say "on provider failure or garbage output, send the English text with a one-line
  note." The current wording only covers the garbage-output case.

  Suggested resolution: Replace with: "Translation failure (provider unreachable, timeout, or sanity-check failure: response identical to input, empty, or not in target language) → send the English text with a one-line note. The user must never see
   a hung or garbled response because translation flaked."

  ---
  ▎ F12 — /save on a QUARANTINED post: behavior unspecified

  Severity: minor
  Category: gap
  Location: docs/spec/commands.md §Content, docs/spec/security.md §Quarantine workflow
  Confidence: medium

  What the spec says: /save <uid> bookmarks a post. Quarantine workflow says posts with INJECTION/MALWARE/UNKNOWN verdicts "hides the entire post." A BENIGN verdict keeps the post visible with redactions.

  Why it's a problem: Can a user /save a quarantined post? If the post is QUARANTINED and hidden, /save should fail with "unknown UID." But if the post is QUARANTINED with a BENIGN verdict (visible with redactions), should /save succeed? The
  snapshot copy would capture the redacted body. The spec doesn't distinguish these cases.

  Suggested resolution: Add to commands.md §/save: "A post with status = 'QUARANTINED' and a hiding verdict (INJECTION, MALWARE, UNKNOWN) is treated as an unknown UID. A post with status = 'QUARANTINED' and a BENIGN verdict (visible with
  redactions) may be saved; the snapshot captures the redacted body."

  ---
  ▎ F13 — post.status values inconsistent between MVP and re-evaluation job

  Severity: minor
  Category: inconsistency
  Location: docs/00-mvp.md §2 (line 33), docs/spec/security.md §Re-evaluation job (line 406)
  Confidence: medium

  What the spec says: 00-mvp.md §2 lists post status as RAW | READY | QUARANTINED. Security.md §Re-evaluation job mentions NEEDS_REVIEW as a terminal status for posts that exhaust re-evaluation attempts.

  Why it's a problem: The MVP doesn't mention NEEDS_REVIEW. If the MVP includes Stage 2 (it does — "Stage 2 only on hits"), then a post could theoretically reach NEEDS_REVIEW if the re-evaluation job is also in scope. The MVP's deferred list
  doesn't explicitly defer the re-evaluation job, though it does defer "Quarantine review workflow (admin chat commands, admin notification throttling beyond a stub log line)." The re-evaluation job is a background Collector job, not an admin chat
  command — so is it in the MVP or not?

  Suggested resolution: Add NEEDS_REVIEW to the MVP's post status list and clarify whether the re-evaluation job is in the MVP: "The re-evaluation job is deferred; posts released with Stage 1 redactions retain stage2_failed=true permanently until a
   future version re-evaluates them."

  ---
  ▎ F14 — No specification for what happens when bot is removed from a group

  Severity: minor
  Category: gap
  Location: docs/spec/schema.md, docs/spec/commands.md §Periodic group summaries
  Confidence: medium

  What the spec says: Groups have a groups row created on first sight. Periodic summaries fire on per-group local time. Nothing describes what happens when the bot is removed from the group.

  Why it's a problem: If the bot is kicked from a group, the groups row persists, the periodic digest scheduler keeps trying to send summaries to a group it can no longer reach, and the messaging adapter's send() will fail. The spec should say
  whether: (a) the adapter surfaces a "bot removed" event and the Provider cleans up (soft-deletes the group, cancels the digest); or (b) the digest fails silently until an admin notices; or (c) the adapter's send failure (permanent, not transient)
   triggers cleanup.

  Suggested resolution: Add to messaging.md or schema.md: "When the adapter detects the bot has been removed from a group (adapter-specific signal), the Provider sets groups.removed_at (soft-delete) and cancels the periodic digest scheduler for
  that group. The groups row is preserved for audit."

  ---
  ▎ F15 — Localization bundle miss for unsupported /lang code

  Severity: minor
  Category: gap
  Location: docs/spec/commands.md §Conversation control (line 248), docs/spec/llm.md §Translation flow
  Confidence: medium

  What the spec says: /lang <code> — "An unsupported code produces a friendly error that lists the supported codes." v1 ships en and cs bundles.

  Why it's a problem: The command rejects unsupported codes, so in theory a user can never reach the translator with an unsupported language. But the spec doesn't say what happens if the localization bundle itself is missing a key for a supported
  language (e.g., a new deterministic string is added with an en key but the cs bundle wasn't updated). This is a spec-vs-design boundary question: should the spec commit to "missing bundle key falls back to English" or is that a design detail?

  Suggested resolution: Add to llm.md §Translation flow: "A missing key in the target-language localization bundle falls back to the English bundle entry. A missing key in the English bundle is a configuration error that fails the startup check."

  ---
  ▎ F16 — category not accepted by /add-source

  Severity: minor
  Category: gap
  Location: docs/spec/commands.md §Source management (lines 174–201), docs/spec/deployment.md §Bootstrap behavior (line 68)
  Confidence: high

  What the spec says: Bootstrap entries require category (one of news/blog/social). /add-source accepts <url> --tags … and an optional --type <kind> but does NOT accept --category.

  Why it's a problem: User-added sources have no way to set category. If category is required on the source row (the bootstrap schema implies it is), the system must either default it (to what?) or reject the command. The spec doesn't say.

  Suggested resolution: Either (a) add --category to /add-source with a default of news, or (b) state "category defaults to news for user-added sources and may be overridden by bot admin via a future command."

  ---
  ▎ F17 — Verification §Security does not test invite-code or slow-start paths

  Severity: major
  Category: verification
  Location: docs/spec/verification.md
  Confidence: high

  What the spec says: Verification.md §Commands and chat tests the end-to-end happy path: "auto-registration on first DM, /help reply, /add-source …" No test for invite-code registration, invite expiry, --contact vs --open matching, slow-start
  probation, /vouch, or pre-ban.

  Why it's a problem: D44 and D45 are v1 scope. The verification spec must prove they work. Currently, the only onboarding test is the D23 auto-register path (which is the MVP path, not the v1 path). An implementer could ship v1 with broken
  invite-code logic and the test suite would pass.

  Suggested resolution: Add verification entries: "Invite-code registration: --contact invite consumed by the bound contact; wrong contact rejected; expired code rejected; --open invite consumed by first unknown contact; both-flags error;
  neither-flags hint. Slow-start tier: newly registered user blocked from /add-source with probation reply; /vouch immediately graduates; probation expiry auto-promotes."

  ---
  ▎ F18 — EmbeddingProvider batch failure: entire-batch retry may amplify cost

  Severity: nit
  Category: smell
  Location: docs/spec/llm.md §Embedding pipeline (lines 123–130)
  Confidence: medium

  What the spec says: "If the provider returns a batch result of the wrong shape, an exception, or any per-element error the Collector cannot map back to a specific post, the entire batch retries once."

  Why it's a problem: The batch size is profile-driven. On a remote profile with a large batch and a remote embedding provider, a single malformed response causes the entire batch (potentially dozens of posts) to re-embed. The cost is bounded (one
  retry), but the spec doesn't say whether the batch is retried as-is or split into smaller chunks on retry. For a provider that's intermittently failing on large batches, splitting would be more resilient.

  Suggested resolution: This is a nit — the one-retry bound is reasonable. Consider adding: "On retry, the batch is NOT split; the same batch is re-submitted. If the failure is batch-size-related, the operator reduces the profile's batch size."
  This makes the behavior explicit without changing the commitment.

  ---
  ▎ F19 — scope_tag default behavior ("all tags") is dynamic but the digest query isn't specified

  Severity: minor
  Category: ambiguity
  Location: docs/spec/commands.md §Per-scope tag preferences (lines 225–237)
  Confidence: medium

  What the spec says: "Default for a fresh scope is 'all tags from subscribed sources' (decision D15) — and the default is dynamic, recomputed at each digest run. A scope with no scope_tag rows opts into the union of tags currently attached to its
  subscribed sources at digest time."

  Why it's a problem: The "union of tags currently attached to its subscribed sources" requires a join across source_subscription, source.tags, and potentially post.tags at digest time. The spec doesn't say whether this is a SQL query against the
  source.tags array or against the post.tags array. If a source was bootstrapped with tags news,tech but no posts have been tagged tech yet, does tech appear in the digest filter? The answer depends on which table the "union" queries. The spec
  should be explicit.

  Suggested resolution: Clarify: "The 'all tags' default queries source.bootstrap_tags (the source's declared tags), not post.tags (the tagger's output). This means a tag appears in the digest filter as soon as a source declares it, even before
  posts are tagged with it."

  ---
  ▎ F20 — D31 progress notifier: no specification for concurrent requests from the same (user, scope)

  Severity: minor
  Category: gap
  Location: docs/spec/commands.md §Conversation control, docs/spec/decisions.md D35
  Confidence: medium

  What the spec says: /stop cancels the "currently in-flight interruptible request" per (user, scope). The progress notifier renders per (scope, requestId).

  Why it's a problem: The spec implies at most one in-flight request per (user, scope) (since /stop cancels "the" request, singular). But it doesn't explicitly state this as a concurrency invariant. If a user sends a chat message and then
  immediately sends /summary before the chat reply completes, does the second request queue, reject, or cancel the first? The /stop semantics assume singularity but the spec doesn't enforce it.

  Suggested resolution: Add to commands.md §Chat mode or §Conversation control: "At most one interruptible request (chat-mode reply or user-issued /summary) is in flight per (user, scope) at any time. A second request while one is in flight is
  rejected with a friendly 'request already in progress' reply."

  ---
  ▎ F21 — /retry for periodic digests: cache expiry interaction unspecified

  Severity: minor
  Category: gap
  Location: docs/spec/commands.md §Conversation control (lines 298–314), docs/spec/decisions.md D36
  Confidence: medium

  What the spec says: /retry for periodic group digests "requires group admin or bot admin and replaces the cached digest (decision D17)." The post selection is "the frozen selection captured when the original digest was generated."

  Why it's a problem: Decision D17 says periodic digests are "cached briefly." If the cache has already expired by the time /retry runs, what does /retry replace? The spec says it "replaces the cached digest" — but if there's no cached digest, does
   it create a new one? Does it fail? The "frozen selection" language suggests the selection is stored somewhere beyond the cache, but the spec doesn't say where.

  Suggested resolution: Clarify: "The frozen post selection for a periodic digest is stored in summary_cache alongside the prose. /retry is valid as long as the summary_cache row exists; if the row has been evicted, /retry returns a friendly 'no
  eligible digest' error."

  ---
  ▎ F22 — Spec layering: verification.md §Security hardcodes metric names that are design-level

  Severity: nit
  Category: layering
  Location: docs/spec/verification.md §Security (lines 213–214)
  Confidence: medium

  What the spec says: "Rate limits: per-user LLM-trigger cap rejects the 11th call in a window."

  Why it's a problem: "11th call" is a specific value. The spec's rate-limit section (security.md) says "Exact numbers are profile-driven." The verification spec should test the behavior ("the N+1th call beyond the cap is rejected"), not a specific
   number.

  Suggested resolution: Change to: "Rate limits: per-user LLM-trigger cap rejects the call that exceeds the profile-configured cap."

  ---
  3. Cross-cutting Observations

  1. Failure-mode coverage is uneven. The spec is thorough about failure modes for the ingest pipeline (Stage 1, Stage 2, tagger, entity, embedding — each with a named fallback) and for translation. But compression, the localization bundle, and the
   periodic digest scheduler have no failure-mode specification. The pattern: any LLM-dependent path that isn't in the ingest pipeline is underspecified. (F03, F07, F11, F15)

  2. The MVP/v1 boundary is fuzzy. The MVP is supposed to be a "strict subset" of v1, but it implements D23 auto-registration while v1 requires D44 invite-code gating. The deferred list in 00-mvp.md §5 omits D44, D45, and the re-evaluation job. The
   MVP's /summary references clustering that depends on a deferred table. These aren't isolated omissions — they suggest the MVP was written before D44/D45 were added to the v1 scope and wasn't updated. (F01, F08, F10, F13)

  3. Global-vs-scope tension on source management. Sources are global (decision D7) but subscriptions are per-scope. The spec handles this cleanly for subscriptions but not for tag replacement: /add-source in DM modifies a global row, which affects
   all scopes. The fix (F02) is straightforward but reveals a broader pattern: the spec should audit every path that writes to a global row and verify the permission is appropriate. (F02, F05, F16)

  4. Verification gaps track the MVP/v1 boundary. The verification spec tests the MVP happy path (auto-register, add-source, summary, save, retry, forget) but not the v1 paths (invite-code registration, slow-start tier, probation graduation,
  pre-ban). This is consistent with the MVP-first approach but means v1 has no verification coverage. (F17)

  5. The determinism boundary is well-stated but not enforced for /compress. The spec is crisp about where the LLM can and cannot influence outcomes (decision D19, architecture principle 1, llm.md §Determinism boundary). But /compress invokes the
  LLM to produce a summary that becomes the chat_memory entry, which is later folded into the chat agent's prompt. The quality of future chat replies depends on the quality of the compression — but the spec doesn't address whether compression
  output is auditable or whether a bad compression can poison the chat agent. This is a latent determinism-boundary erosion.

  ---
  4. Spec Evaluation

  Completeness — 7/10. The spec covers the major surfaces well: commands, security, schema, LLM integration, messaging, deployment. Gaps are concentrated in failure modes (compression, localization) and the MVP/v1 boundary (invite codes,
  slow-start). The category field and source_subscription lifecycle are underspecified.

  Consistency — 6/10. The spec files generally agree with each other and with decisions.md. The major inconsistency is the MVP vs. v1 scope conflict on invite-code registration (F01). Minor inconsistencies include post.status values (F13) and the
  hardcoded 30-second timeout in verification.md (F09).

  Implementability — 7/10. An engineer could build most of the system from this spec. The biggest implementability risk is the /add-source tag-replacement behavior (F02) — an engineer following the spec literally would build a privilege-escalation
  hole. The SSRF-allowlist sharing question (F04) would require a design-time decision that the spec doesn't guide.

  Testability — 7/10. Verification.md is unusually thorough for a spec at this stage. The four-layer test strategy is well-chosen. Gaps: no tests for invite-code or slow-start paths (F17), no test for compression failure (F03), and some tests
  hardcode values that are profile-driven (F09, F22).

  Evolvability — 8/10. The spec/design split is well-maintained. The decision log is disciplined. The SPI boundaries (LlmProvider, MessagingAdapter, Fetcher, StreamSource) are clean. The most likely leak point is the determinism boundary: as more
  LLM-dependent features are added (compression, translation, periodic digests), the "LLM only generates prose" rule will need active enforcement.

  ---
  5. Pros and Cons

  Pros:
  - The decision log (decisions.md) is exceptional — each decision is self-contained, cross-referenced, and states trade-offs explicitly. This is the best decision log I've seen in a spec at this stage.
  - The determinism boundary is crisply stated and consistently referenced across files. An implementer knows exactly where the LLM is and isn't allowed to influence outcomes.
  - The security model is concrete: threat catalogue, trust boundaries, ingest pipeline, LLM tool surface, SSRF defense, and failure handling are all specified with named commitments, not vague intentions.
  - The spec/design split is well-maintained. Every file has a "What lives in design notes" trailer that draws the line clearly.
  - The verification spec is proactive — it lists what must be tested before code is written, not after.

  Cons:
  - The MVP/v1 boundary is the spec's biggest structural weakness. The MVP implements a retired onboarding path (D23) while the v1 scope requires a different one (D44/D45), and neither document explicitly acknowledges the gap.
  - Failure-mode coverage is uneven: the ingest pipeline is thorough, but compression, localization, and the periodic digest scheduler are underspecified. Any LLM-dependent path outside the ingest pipeline is a gap.
  - The global-vs-scope tension on source rows creates a privilege-escalation risk that the current permission model doesn't address.
  - The verification spec doesn't cover the v1 invite-code or slow-start paths, which means v1 has no automated proof that its entry gate works.

  ---
  6. Recommended Next Actions

  1. Fix the MVP/v1 boundary (F01, F10, F13). Add D44, D45, and the re-evaluation job to 00-mvp.md §5 deferred list. Acknowledge that the MVP uses the D23 auto-register path. This is the highest-value fix because it removes the contradiction that
  every implementer will hit first.
  2. Lock down /add-source tag replacement (F02). Restrict bootstrap_tags replacement to bot admin. This is a one-sentence spec change that closes a privilege-escalation hole.
  3. Specify compression failure modes (F03, F07). Add a paragraph to security.md §Failure handling and llm.md §Failure handling. This is the most impactful gap for user-facing degraded operation.
  4. Clarify source_subscription lifecycle (F05). One paragraph in schema.md specifying cascade behavior on soft-delete and /remove-source.
  5. Add invite-code and slow-start tests to verification.md (F17). This is the cheapest way to ensure v1 doesn't ship with a broken entry gate.
  6. Specify category usage or declare it informational (F06, F16). Either commit to a use or mark it as metadata with a default.
  7. Clarify SSRF-allowlist sharing mechanism (F04). One sentence in architecture.md or commands.md.
