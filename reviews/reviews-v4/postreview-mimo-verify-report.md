Post-Review Audit: Missed Issues, Wrong Dismissals, and Coverage Gaps                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Scope. This report cross-references the live spec files (docs/spec/*.md, docs/00-mvp.md, CLAUDE.md) against the fix plan (reviews-v4-fix-plan.md) and its coverage report (reviews-v4-coverage.md). Every finding below was verified by reading the   
  actual file content, not by trusting the coverage report's grep claims.                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  Summary. 15 issues found: 4 coverage failures (the plan says "landed" but the edit didn't actually land or landed incompletely), 5 spec gaps (new issues not addressed by any plan item), 4 internal contradictions (within the fix plan or between   
  the plan and the live spec), and 2 verification gaps.                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ---
  Part 1 — Coverage Failures (plan says "landed" but didn't)                                                                                                                                                                                            

  CF-1. B26 (bot removed from group): removed_at column missing from schema.md Group entity

  What the plan says. B26 resolution: "Add to messaging.md and schema.md: groups.removed_at = NOW()…"

  What the coverage report says. "B26 | Bot removed from group | messaging.md §Failure handling | 'Bot removed from group'" — only messaging.md is listed.

  What the live spec says. schema.md Group entity (lines 20–23) defines only a per-group timezone. The string removed_at does not appear anywhere in schema.md. The messaging.md §Failure handling does mention removed_at, but the schema entity that
  would carry the column has no record of it.

  Why this is an issue. An engineer reading schema.md alone will build a groups table without removed_at. The DDL migration will lack the column, and the Provider code that writes it will fail at runtime. Schema.md is the authoritative entity
  definition; messaging.md describes behavior, not columns.

  Suggested fix. Add removed_at (nullable timestamp) to the Group entity definition in schema.md. Also add it to the invariant or description: "When the bot is removed from a group, removed_at is set; on re-add it is cleared. Group state
  (subscriptions, scope_tag, chat_memory) is preserved across remove/re-add cycles."

  ---
  CF-2. C1 (supportsCodeFormatting rename): 00-mvp.md still uses supportsMarkdownCode

  What the plan says. C1: "Rename supportsMarkdownCode to supportsCodeFormatting in messaging.md capability flags."

  What the coverage report says. "C1 | supportsCodeFormatting rename | messaging.md; CLAUDE.md | both flags present" — 00-mvp.md is not listed.

  What the live spec says. CLAUDE.md line 46 correctly uses supportsCodeFormatting. messaging.md correctly uses supportsCodeFormatting. But 00-mvp.md lines 79 and 91 still use supportsMarkdownCode:

  - Line 79: "The MessagingAdapter SPI (with supportsMarkdownCode capability flag)…"
  - Line 91: "the InMemoryAdapter reports supportsMarkdownCode=false"

  Why this is an issue. A new contributor reading 00-mvp.md first (per the reading order in SPEC.md) will see the old flag name and implement against it. If they then read messaging.md, they'll find the renamed flag. Two engineers will build
  different things.

  Suggested fix. Replace supportsMarkdownCode with supportsCodeFormatting in both 00-mvp.md lines 79 and 91.

  ---
  CF-3. A3 (onboarding contradiction): CLAUDE.md still says "Users self-register on first message"

  What the plan says. A3 resolution: Replace D23 to say "DM access is invite-gated (D44)."

  What the coverage report says. "A3 | D23/D44 onboarding contradiction | decisions.md D23 rewritten; 00-mvp.md notes legacy auto-register path" — CLAUDE.md is not listed.

  What the live spec says. decisions.md D23 correctly says "DM access is invite-gated (D44)." 00-mvp.md correctly notes the legacy auto-register path. But CLAUDE.md line 62 still says: "Users self-register on first message (auto-create + welcome
  with /help)."

  Why this is an issue. CLAUDE.md is the first thing a developer reads (it's the project-level instruction file for Claude Code and human contributors alike). It directly contradicts the revised D23 and the live commands.md §Onboarding which says
  "DM first interaction requires a valid invite code." A developer following CLAUDE.md will implement auto-registration, not invite-gating.

  Suggested fix. Replace CLAUDE.md lines 60–63 with:

  ## User registration & ban

  - DM access requires an invite code issued by a bot admin (decision D44). Unknown DM contacts without a valid code receive a fixed rejection reply.
  - Group access registers on first non-banned `@mention` (no invite required).
  - All newly registered users enter a slow-start probation tier (decision D45).
  - Bot admin can `/ban <contact>` / `/unban <contact>`. Banned users are blocked at message intake; they receive one fixed response and never reach the LLM or any DB query beyond the ban check.

  ---
  CF-4. B26 coverage claim is incomplete — only messaging.md updated, schema.md missed

  This is the same underlying issue as CF-1 but stated as a coverage-report error: the coverage report row for B26 lists only messaging.md, yet the plan's own resolution says "Add to messaging.md and schema.md." The coverage report should have
  flagged schema.md as missing. Either the edit was attempted and reverted, or it was never attempted. Either way, the coverage report's "verified" column is misleading — it checks for the string in messaging.md but doesn't verify the schema
  counterpart exists.

  ---
  Part 2 — Spec Gaps (not addressed by any plan item)

  SG-1. summary_anchor has no TTL, no pruner, and no bounded lifetime

  What the spec says. schema.md lines 191–201: the summary anchor is "cleared by any non-/retry input from the same (user, scope)" and "survives Provider restart for the bounded retry window." D36 defines a "small fixed retry cap" (count-based) and
   event-driven clearing.

  What's missing. Neither D36 nor schema.md defines a time-based TTL or a pruner for summary_anchor. If a user runs /summary and then never messages the bot again, the anchor row persists indefinitely. Unlike chat_memory (which has Invariant 9,
  D40, and a scheduled pruner), summary_anchor has no background cleanup.

  At scale — thousands of users each with a summary anchor per scope — this is unbounded growth of per-(user, scope) rows with no automated reclamation. The /forget path (B7) handles user-initiated purge, and /clear handles the chat session, but
  neither covers the "user walked away" case.

  Suggested fix. Add a TTL to summary_anchor — either piggyback on the chat_memory pruner (same horizon) or define a separate, shorter horizon (the retry window is conceptually shorter than memory retention). Document in Invariant 9 or a new
  Invariant 10. Add a verification entry: "summary_anchor rows older than the configured horizon are removed by the pruner."

  ---
  SG-2. chat_session has no TTL, no pruner, and no entity-level column specification

  What the spec says. schema.md lines 212–220 describe chat_session in prose as "per-(user, scope) live context state, persisted in the database." B7 added it to the /forget purge set. /clear wipes it. But:

  1. No columns are enumerated (unlike Invite code which has a full column list).
  2. No TTL or pruner is specified. Invariant 9 covers only chat_memory.
  3. A user who sends one message, gets a chat session created, and never returns leaves the row forever.

  Why this is an issue. Without a TTL, chat_session rows accumulate indefinitely for inactive users. The chat_memory pruner (D40) doesn't cover chat_session. The /forget path requires the user to actively invoke it. An operator running the bot for
  years will accumulate stale session rows.

  Suggested fix. (a) Add a minimal column list to the chat_session entity (at minimum: user_id, scope_type, scope_id, context_window_content, updated_at). (b) Either extend Invariant 9 to cover chat_session with its own TTL (possibly the same
  horizon as chat_memory), or add a separate invariant. (c) Add a verification entry for the pruner.

  ---
  SG-3. /summary does not distinguish clean-READY from redacted-READY posts

  What the spec says. commands.md line 78: /summary summarizes "READY posts in the window." security.md says Stage 2 infrastructure failure releases posts as READY with Stage 1 redactions retained and stage2_failed=true. These posts are
  user-visible but contain [REDACTED:<id>] placeholders.

  What's missing. The /summary description doesn't mention that some READY posts in the result set may have redacted content. A user reading a summary that includes [REDACTED:abc123] in the middle of a cluster description will be confused — the
  spec doesn't warn that this is expected behavior, nor does it tell the implementer how to handle redacted posts in the LLM prompt (should the redacted placeholder be passed to the summarizer as-is? should it be stripped? should the post be
  excluded from clusters?).

  Suggested fix. Add a note to /summary in commands.md: "Posts with Stage 1 redactions (released as READY due to Stage 2 infrastructure failure, stage2_failed=true) are included in the eligible set. The LLM summarizer receives the redacted body
  as-is; the [REDACTED:<id>] placeholders are not stripped before the prompt." This pins the expected behavior without changing the retrieval rules.

  ---
  SG-4. NEEDS_REVIEW posts and the admin-review TTL (B18) — does it apply?

  What the plan says. B18 adds an admin-review TTL to quarantine rows: "a quarantine row aged past the admin-review TTL auto-rejects and the placeholder becomes permanent."

  What's missing. NEEDS_REVIEW is a distinct status from QUARANTINED. The B18 resolution only mentions "quarantine rows." But NEEDS_REVIEW posts are also "hidden until an admin acts" and also need admin review. Does the admin-review TTL apply to
  NEEDS_REVIEW rows?

  If yes: the spec should say so explicitly, and define the auto-reject behavior for NEEDS_REVIEW (the post stays NEEDS_REVIEW permanently? transitions to what?).

  If no: NEEDS_REVIEW rows have no TTL at all — they're post-derived rows that should fall under Invariant 6's partition TTL, but the quarantine exemption (B18) only covers "quarantine rows," not NEEDS_REVIEW rows. An implementer might age them out
   via the normal post partition drop, silently losing posts that need admin review.

  Suggested fix. Explicitly extend B18's exemption to NEEDS_REVIEW rows: "Posts with status NEEDS_REVIEW are exempt from the post-derivative TTL, same as QUARANTINED rows. The same admin-review TTL applies; rows aged past the TTL auto-transition to
   QUARANTINED with a permanent placeholder (the system's verdict was 'couldn't classify' — permanent quarantine is the safe default)."

  ---
  SG-5. /retry and /stop during probation — ambiguous categorization

  What the spec says. D45 lists allowed commands during probation (/help, /status, /get-tags, /get-sources, /list-sources, /summary, /saved, asset commands, /export, /forget, /lang) and says "All other write operations (/add-source, /save, /unsave,
   /follow-tag, /unfollow-tag, /clear, /compress, /group-timezone) and chat mode are blocked."

  What's ambiguous. /retry and /stop are not in either list:

  - /retry invokes the LLM and replaces cached content — it's a write operation by any reasonable definition. But it's not named in the blocked list, and the parenthetical is a non-exhaustive "e.g." list. An implementer might leave /retry
  accessible during probation.
  - /stop is idempotent and cancels in-flight work. During probation, nothing can be in flight (all LLM-triggering commands are blocked), so /stop would just return the idempotent no-op reply. But should it return the no-op reply or the probation
  reply? The spec doesn't say.

  Suggested fix. Add /retry explicitly to the blocked list in D45 and security.md §Slow-start tier. Add a note for /stop: "During probation, /stop returns the standard idempotent no-op reply (nothing is in flight); it does not return the
  probation-blocked reply." This avoids an implementer adding /stop to the blocked list and confusing users who muscle-memory /stop during early conversations.

  ---
  Part 3 — Internal Contradictions in the Fix Plan

  IC-1. "What this plan does NOT do" section still says "defer Signal" — contradicts rewritten A9

  What the plan says. The A9 resolution (rewritten in-place, lines 283–321) correctly keeps Signal in v1. But §"What this plan does NOT do" (lines 1050–1053) still says:

  ▎ "Decide whether to ship Signal in v1 (A9). The plan picks defer as the simpler reconciliation; if the team disagrees, swap A9 for the alternate write-up…"

  Why this is an issue. A reader who skips to the summary section gets the old, withdrawn framing. The A9 entry itself notes "The earlier 'defer Signal' framing in this plan is withdrawn" but the summary section was not updated to match.

  Suggested fix. Replace lines 1050–1053 with: "Decide whether to ship Signal in v1 (A9). Resolved: Signal stays in v1. The plan's original 'defer' framing was withdrawn per operator directive; the applied resolution aligns deployment.md and
  00-mvp.md to SPEC.md/D32."

  ---
  IC-2. The plan says "six reports independently flag this" for A3, but the plan itself was built from six reports — the count is self-referential, not independent verification

  This is minor but worth noting: the plan repeatedly says "six reports independently flag this" as evidence of severity. But the plan was built by reading those six reports. The "independence" is that six LLM reviewers happened to flag the same
  thing — which is evidence of an obvious contradiction, not independent verification. The fix is correct regardless; the rhetorical framing is just slightly misleading.

  ---
  Part 4 — Verification Gaps

  VG-1. Infra-failure class → NEEDS_REVIEW not explicitly verified

  What verification.md says. The "UNKNOWN re-eval" entry (lines 153–156) verifies that an UNKNOWN-verdict post exhausts its cap and transitions to NEEDS_REVIEW. But there is no equivalent entry for the infra-failure class exhausting its (separate,
  higher) cap and also transitioning to NEEDS_REVIEW.

  Why this matters. security.md is clear that both classes share the NEEDS_REVIEW transition on cap exhaustion (lines 501–507). But verification.md only tests the UNKNOWN path. The infra-failure path has a different cap, different triggering
  conditions (LLM unreachable vs. LLM says "unknown"), and different intermediate states (READY with stage2_failed=true vs. QUARANTINED). A bug in the infra-failure cap counting would go undetected.

  Suggested fix. Add to verification.md §Commands and chat: "Stage 2 infra-failure re-eval exhaustion: a post released as READY with stage2_failed=true is picked up by the re-eval queue; the LLM remains unreachable for the full infra-failure cap;
  the post transitions to NEEDS_REVIEW; a coalesced admin notification fires."

  ---
  VG-2. /save snapshot stability after post status transition not verified

  What the spec says. D13: "retention exemption (snapshot copy at /save time so the underlying post's TTL does not break the bookmark)." /save visibility rules describe what gets snapshotted per status at save time.

  What's missing from verification. No verification entry asserts that a saved post's snapshot remains accessible after the underlying post transitions from READY to NEEDS_REVIEW or QUARANTINED. Since saves are snapshots (copies), they should be
  stable — but this invariant is not tested. An implementer who stores a FK reference instead of a snapshot body would break silently on status transition.

  Suggested fix. Add to verification.md: "A post saved while READY remains in the user's /saved list and retains its snapshotted body after the underlying post transitions to NEEDS_REVIEW or QUARANTINED. The snapshot is independent of the post's
  current status."

  ---
  Part 5 — Minor Issues

  MI-1. admin_notification_state entity has no column specification

  schema.md lines 239–240 define it as: "Backing store for the throttled admin notifier (decision D22)." That's the entire definition — no columns, no shape, no retention. Every other operational entity (provider_state, summary_cache, asset_config,
   price_snapshot) has at least a column list. This one is a single sentence.

  Suggested fix. Add a minimal column list: at minimum (channel, error_class, last_notified_at, count_in_window). The exact shape is design-level, but the spec should name the discriminator columns so the throttling key is unambiguous.

  ---
  MI-2. Group entity has no column specification beyond timezone

  The Group entity (schema.md lines 20–23) names only timezone. With B26 adding removed_at, and the existing need for id, adapter, group_identifier, created_at, the entity is underspecified compared to User and Invite code which both enumerate
  their columns.

  Suggested fix. Add a minimal column list to the Group entity: id, adapter, group_identifier (the adapter-specific group id), timezone, removed_at (nullable, per B26), created_at.

  ---
  MI-3. stage2_failed flag lifecycle is unspecified

  Posts released as READY with stage2_failed=true carry that flag indefinitely. When the re-evaluation job picks up the post and gets a BENIGN verdict, does it clear stage2_failed? The spec doesn't say. If the flag is never cleared, operators
  monitoring stage2_failed=true rows will see phantom entries long after the re-eval succeeded.

  Suggested fix. Add to security.md §Re-evaluation job: "On a successful re-eval verdict (BENIGN), stage2_failed is cleared to false and the post's quarantine row is closed. On INJECTION/MALWARE/UNKNOWN, the flag remains and the post transitions to
   QUARANTINED."

  ---
  Summary Table

  ┌─────┬──────────────────┬──────┬──────────┬───────────────────────────────────────────────────────────────┐
  │  #  │     Category     │  ID  │ Severity │                          Description                          │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 1   │ Coverage failure │ CF-1 │ High     │ B26: removed_at missing from schema.md Group entity           │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 2   │ Coverage failure │ CF-2 │ Medium   │ C1: 00-mvp.md still uses supportsMarkdownCode                 │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 3   │ Coverage failure │ CF-3 │ High     │ A3: CLAUDE.md still says "self-register on first message"     │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 4   │ Coverage failure │ CF-4 │ Low      │ Coverage report doesn't verify schema.md counterpart for B26  │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 5   │ Spec gap         │ SG-1 │ Medium   │ summary_anchor has no TTL or pruner (unbounded growth)        │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 6   │ Spec gap         │ SG-2 │ Medium   │ chat_session has no TTL, no pruner, no column spec            │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 7   │ Spec gap         │ SG-3 │ Low      │ /summary doesn't mention redacted-READY posts in results      │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 8   │ Spec gap         │ SG-4 │ High     │ NEEDS_REVIEW and admin-review TTL interaction unspecified     │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 9   │ Spec gap         │ SG-5 │ Medium   │ /retry and /stop during probation ambiguous                   │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 10  │ Contradiction    │ IC-1 │ Medium   │ Plan §"What this plan does NOT do" still says "defer Signal"  │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 11  │ Contradiction    │ IC-2 │ Low      │ "Six reports independently" is self-referential framing       │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 12  │ Verification gap │ VG-1 │ Medium   │ Infra-failure → NEEDS_REVIEW path not verified                │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 13  │ Verification gap │ VG-2 │ Low      │ /save snapshot stability after status transition not verified │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 14  │ Minor            │ MI-1 │ Low      │ admin_notification_state has no column spec                   │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 15  │ Minor            │ MI-2 │ Low      │ Group entity underspecified (no column list)                  │
  ├─────┼──────────────────┼──────┼──────────┼───────────────────────────────────────────────────────────────┤
  │ 16  │ Minor            │ MI-3 │ Low      │ stage2_failed flag lifecycle unspecified                      │
  └─────┴──────────────────┴──────┴──────────┴───────────────────────────────────────────────────────────────┘

  Recommended priority. CF-1, CF-3, and SG-4 are the highest priority — they will cause different engineers to build different things (the definition of a spec defect). CF-2 and SG-5 are next. The rest are hygiene and long-term operational
  correctness.
