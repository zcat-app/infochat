---
id: M1-037
title: /summary command (eligible-post SQL + cluster traversal + LLM prose + sanitizer + degraded fallback)
status: pending
created: 2026-05-17
last_updated: 2026-05-17
blocked_by:
  - M1-035
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/io/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/io/infochat/provider/command/SummaryArgs.java
  - infochat-provider/src/main/java/io/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/io/infochat/provider/summary/ClusterTraversal.java
  - infochat-provider/src/main/java/io/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/io/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/main/resources/bundle/en.properties
  - infochat-provider/src/test/java/io/infochat/provider/command/SummaryArgsTest.java
  - infochat-provider/src/test/java/io/infochat/provider/summary/EligiblePostQueryIT.java
  - infochat-provider/src/test/java/io/infochat/provider/summary/ClusterTraversalTest.java
  - infochat-provider/src/test/java/io/infochat/provider/summary/SummaryProseGeneratorTest.java
  - infochat-provider/src/test/java/io/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/test/java/io/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/io/infochat/provider/command/SummaryIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change under infochat-messaging-adapter/ (SPI + InMemoryAdapter are FROZEN at M1-035a; defects file a follow-up per docs/process/workflow.md §M1 workflow — never amend a passed commit)
  - any change to AdapterRegistry, InboundRouter, MessagingStartup, CommandHandler interface, AutoRegisterService, BundleLoader, BundleKeys, HelpCommandHandler, or the M1-035c bundle infrastructure (the T1-E surface is FROZEN at the M1-035 umbrella's round; this ticket consumes the CommandHandler SPI and BundleKeys as-is)
  - any Flyway migration under infochat-core/src/main/resources/db/migration/ (T1-F is migration-free; the `post`, `source_subscription`, `scope_preferences`, `scope_tag`, `tag` tables exist from M1-008b/c; `post_embedding` exists from M1-034a V11; reaching for V12 is an escalation trigger, not an authoring choice)
  - any /add-source command surface (M1-036 territory; this ticket adds NO new BundleKeys / handler / DAO that /add-source would also need — the two T1-F handlers share no implementation files)
  - any /save, /saved, /unsave, /retry, /stop, /clear, /compress, /forget, /export command (T2-D/E territory; this ticket has NO interaction with chat_memory, chat_session, saved_post, or summary_anchor)
  - any /follow-tag / /unfollow-tag / /tag-mode (T2-B territory; this ticket READS scope_tag and scope_preferences.tag_mode to filter eligible posts but does NOT author the follow/unfollow handlers)
  - any group `@mention` dispatch, group scope, or periodic group digest (T2-F territory; this ticket's IT exercises DM scope only — the periodic-digest path uses summary_cache + the staggered scheduler which are deferred; /summary's on-the-fly path is the only path here)
  - any post_reference graph row INSERT / mutation (T2 / Tier-3 territory; this ticket READS post_reference for the cluster traversal — when the table has zero rows the connected-components algorithm returns N singleton clusters where N is the eligible-post count — but does NOT write to it; the M1-034a V11 migration does NOT create post_reference, so the cluster traversal MUST tolerate a missing table OR the ticket adds a NO-OP V12 ... actually the table is reached only through the query layer; since post_reference is not created in MVP, ClusterTraversal MUST NOT issue any SQL against it — instead it returns N singleton clusters unconditionally; T2-D adds the real graph traversal when the table lands)
  - any TranslationProvider integration / `/lang` (T2-C; outbound replies use English BundleKeys only — translation pre-pass through TranslationProvider is T2-C's wiring)
  - any LLM SPI / OpenAI-compatible LlmProvider client change (M1-033 ships the client and the per-task router; this ticket CONSUMES `LlmProvider` for the prose generation task — see docs/spec/llm.md §Per-task routing — but does NOT modify the SPI or the M1-033 client surface)
  - any new ModelTask enum value or scope_language router lookup change (M1-033 territory; the prose task uses an existing ModelTask enum value, NOT a new one. If the existing enum lacks a `SUMMARY_PROSE` value, this is an escalation trigger — escalate to M1-033 to add it, do NOT add it inline here)
  - any prompt-injection defense BEYOND the spec-mandated retention of `[REDACTED:<id>]` placeholders in the prompt (docs/spec/security.md §Failure handling); the Stage 1 / Stage 2 ingest defenses live in M1-032 / M1-033 and run upstream of `READY` — this ticket trusts that the eligible-post set is post-Stage-2 BENIGN per the `status='READY'` filter
  - any new `audit_log.action` verb (the closed catalogue from M1-024 is consumed as-is; the spec docs/spec/security.md §LLM output sanitizer requires per-match audit logging — this ticket calls into the existing AuditLogWriter for sanitizer matches and assumes the catalogue already exposes a `llm_output.sanitized` verb OR an equivalent; if the verb is missing, escalate to refine — do NOT add it inline)
  - any RetrievalCache, SummaryCache, or in-memory cluster cache (the spec's on-the-fly /summary path docs/design/03-commands.md §`/summary` is uncached; periodic-group-digest's `summary_cache` is T2-F)
  - any future LLM-emitted output surface BEYOND /summary (the sanitizer ships here per M1-035b out_of_scope which explicitly defers it to T1-F's /summary — but the sanitizer is a SHARED component callable from chat-mode, periodic digests, and /retry; this ticket lands the sanitizer + its FIRST consumer surface (/summary); chat-mode + periodic digests + /retry wire their own call sites in T2-D / T2-F)
acceptance:
  - "infochat-provider/src/main/java/io/infochat/provider/command/SummaryCommandHandler.java exists, is `@ApplicationScoped`, and implements `io.infochat.provider.messaging.CommandHandler`. Verify: `grep -E '@ApplicationScoped' SummaryCommandHandler.java` returns ≥1 match AND `grep -E 'implements\\s+CommandHandler' SummaryCommandHandler.java` returns ≥1 match"
  - "SummaryCommandHandler.name() returns the literal string \"summary\" (no leading slash; InboundRouter strips the slash before lookup). Verify: `grep -E '\"summary\"' SummaryCommandHandler.java` returns ≥1 match in a `name()` accessor"
  - "SummaryCommandHandler is discovered by InboundRouter via `Instance<CommandHandler>` (no manual registration). Verify: SummaryCommandHandlerTest asserts that calling InboundRouter.onMessage with a `/summary` body reaches SummaryCommandHandler.handle exactly once"
  - "SummaryArgs parses `[tag] [-w <duration>]` per docs/design/03-commands.md §Time window flag. Per-element assertions in SummaryArgsTest:
    - bare `/summary` → tag=NONE, window=24h (the documented default per design 03-commands.md §3.5 `/summary`)
    - `/summary security` → tag=`security` (post-TagNormalizer canonical form)
    - `/summary security -w 48h` → tag=`security`, window=48h
    - `/summary -w 7d` → tag=NONE, window=7d
    - `/summary -w 5m` → friendly error `error.summary.window_minutes_not_accepted` (the suffix `m` is intentionally rejected per design 03-commands.md §Time window flag)
    - `/summary -w 200h` → friendly error `error.summary.window_out_of_range` (range is 1h–168h | 1d–30d | 1w–4w)
    - `/summary an-invalid-tag-not-in-vocab` → friendly error `error.summary.unknown_tag` with fuzzy-suggestion footer over the controlled vocabulary
    - `/summary security-with-very-long-name-exceeding-48-chars-aaaaaaa` → tag rejected by TagNormalizer (>48 chars) → friendly error `error.summary.tag_malformed`"
  - "EligiblePostQuery returns READY posts deterministically, ordered by `published_at DESC, id DESC` (the secondary id key breaks ties stably so retest order is reproducible). Per-rule assertions in EligiblePostQueryIT (DB-tier, @QuarkusTest with seed fixtures):
    - filter `status='READY'` — QUARANTINED / NEEDS_REVIEW / RAW posts are EXCLUDED
    - filter `published_at >= now() - window` — older posts EXCLUDED
    - filter `source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind=? AND scope_id=?)` — posts from unsubscribed sources EXCLUDED
    - filter by tag when supplied: post.tags array contains the normalized tag
    - filter by scope_preferences.tag_mode when NO positional tag: `EXPLICIT` mode restricts to the union of scope_tag rows; `ALL` mode returns posts across all subscribed sources' tags
    - empty-result case: zero subscribed sources → returns empty List<Post>
    - empty-result case: subscribed but no READY posts in window → returns empty List<Post>
    - retained-redaction case: a post with `stage2_failed=true` and `[REDACTED:<id>]` in the body IS included in the eligible set (docs/spec/security.md §Failure handling; the placeholder is NOT stripped before retrieval — the prose generator sees it as-is per docs/spec/commands.md §Content)
    - cap enforcement: when more than `infochat.summary.cluster-cap` (laptop=200) posts match, the OLDEST posts are dropped and the response surfaces `Showing 100 of 137 posts (cap: <profile>=<n>; <m> oldest excluded)`"
  - "EligiblePostQuery applies the `no-arg with >5 followed tags` rule per docs/design/03-commands.md §`/summary`: when SummaryArgs.tag is NONE AND `scope_tag` rows for the scope count >5, retrieval is restricted to the 3 most-active tags in the window (by post count) AND the reply is prefixed with `bundle('reply.summary.top_3_of_n_prefix')` interpolated with N=count(scope_tag). Verify: EligiblePostQueryIT seeds 7 followed tags + 15 posts unevenly distributed and asserts the result set contains only posts whose `tags` array intersects the top-3 tag set"
  - "ClusterTraversal returns connected components of the post_reference graph. For MVP — post_reference table has zero rows — every eligible post becomes its OWN singleton cluster: input List<Post> of size N → output List<Cluster> of size N where each cluster's posts list has exactly 1 element. Per-rule assertions in ClusterTraversalTest:
    - empty input List<Post> → empty List<Cluster>
    - one Post → one Cluster, cluster.posts.size()==1, cluster.posts.get(0)==<the input post>
    - N posts with NO post_reference rows → N singleton clusters in the SAME order as the input (the ordering is deterministic per EligiblePostQuery's `published_at DESC, id DESC` sort)
    - cluster.topicId is a deterministic function of cluster.posts (e.g. derived from the lexicographically-smallest post.uid in the cluster) so the same input produces the same topicId across runs"
  - "SummaryProseGenerator invokes the M1-033 LlmProvider for prose generation per cluster, with a PER-CLUSTER prompt that includes `[REDACTED:<id>]` placeholders UNCHANGED (per docs/spec/commands.md §Content — placeholders are NOT stripped before the prompt). Per-rule assertions in SummaryProseGeneratorTest (mocked LlmProvider):
    - on a one-cluster input the LLM is invoked exactly once
    - on a three-cluster input the LLM is invoked exactly three times (one per cluster)
    - the prompt body contains the literal substring `[REDACTED:` when the input post had a redaction placeholder (assert via captured-prompt argument)
    - the LLM response is treated as PROSE — no markdown link syntax `[text](url)` is allowed in the response; if present the sanitizer strips it (next acceptance item)
    - LLM unreachable (mock throws): the generator falls back to the SAME degraded form as a saturated periodic digest per docs/design/03-commands.md §`/summary`: headlines (title) + source URLs bare + post UIDs, no prose. Reply prefix = `bundle('reply.summary.degraded_notice')`. The deterministic post selection is UNAFFECTED"
  - "LlmOutputSanitizer applies a deterministic outbound regex pass over the closed privileged-tier command list from docs/spec/commands.md §Permission model §Closed list of privileged-tier commands. The match set is DERIVED FROM THE CLOSED LIST at boot — NOT hand-maintained. Per-command assertions in LlmOutputSanitizerTest (one @Test per command in the closed list — heterogeneous string set, count is NOT load-bearing):
    - bot-admin set: `/grant-admin`, `/revoke-admin`, `/ban`, `/unban`, `/promote`, `/demote`, `/vouch`, `/invite create`, `/invite list`, `/invite revoke`, `/quarantine list`, `/quarantine approve`, `/quarantine reject`, `/audit`, `/remove-source`, `/source-enable`, `/source-disable`, `/list-sources --all`, `/list-sources --include-deleted`
    - group-admin set: `/add-source` in groups, `/unfollow-source` in groups, `/lang` in groups, `/group-timezone`, `/follow-tag` in groups, `/unfollow-tag` in groups
    Each @Test feeds an LLM-prose blob containing the exact command string and asserts the sanitizer either STRIPS the command or REFUSES the output (exact contract per docs/spec/security.md §LLM output sanitizer — implementer chooses strip-or-refuse; the test asserts the chosen behavior is applied uniformly across all closed-list commands)"
  - "LlmOutputSanitizer audit-logs EVERY match (per-occurrence, NOT throttled) per docs/spec/security.md §LLM output sanitizer. Verify: LlmOutputSanitizerTest feeds a blob containing 3 distinct matches and asserts `SELECT COUNT(*) FROM audit_log WHERE action='<sanitizer verb from M1-024 catalogue>'` returns 3 (the action verb is consumed from M1-024's closed catalogue as-is; the test asserts the count, NOT the verb spelling)"
  - "LlmOutputSanitizer CI completeness check per docs/spec/security.md §LLM output sanitizer §Match-set derivation: a startup or test-tier assertion fails when the sanitizer's match set diverges from the spec's closed list (a new admin command added without a matching sanitizer entry, or a sanitizer entry that no longer corresponds to a listed command). Verify: LlmOutputSanitizerTest contains one @Test that asserts the sanitizer's runtime match-set EQUALS the closed-list constants (sourced from a shared constant holder or parsed from a versioned resource); the @Test fails if either side has an entry the other side lacks"
  - "SummaryCommandHandler stitches the pieces: SummaryArgs → EligiblePostQuery → ClusterTraversal → SummaryProseGenerator → LlmOutputSanitizer → outbound reply. Per-branch assertions in SummaryCommandHandlerTest:
    - happy path: 3 eligible posts → 3 singleton clusters → 3 LLM calls (mocked) → sanitizer pass → reply contains 3 cluster blocks in the documented output structure (`[topic_id=<id>]` / headline / `covered by:` / `score:` / `summary:` / `tags:`)
    - empty window: zero eligible posts → bundle `reply.summary.no_posts_yet` reply, NO LLM call, NO sanitizer call (a `@Test` asserts `verifyNoInteractions(llmProvider)` and `verifyNoInteractions(sanitizer)`)
    - zero subscriptions: same friendly `no_posts_yet` reply path
    - LLM unreachable: degraded fallback reply with `reply.summary.degraded_notice` prefix + headlines + bare URLs + UIDs, NO sanitizer call (degraded prose is deterministic, not LLM-authored)
    - cap excess: 250 eligible posts on `laptop` profile (cap=200) → reply prefix `bundle('reply.summary.cap_excess_notice')` interpolated with the cap value and excluded count, top-200 are summarized"
  - "SummaryIT exercises MVP exit criterion §6 end-to-end via the InMemoryAdapter: (a) seed 2 sources, subscribe DM `mvp-user-1` to both, seed 4 READY posts (2 per source) within 24h, mock the LlmProvider to return a fixed prose blob per cluster; (b) `adapter.deliverDm(\"mvp-user-1\", \"/summary -w 24h\")` produces exactly ONE outbound message; (c) the reply body contains all 4 post UIDs; (d) the reply body contains the 2 source URLs (bare, per docs/spec/commands.md §Surface conventions Plain-text-no-markdown); (e) the reply body contains NO markdown-link syntax `[text](url)` (sanitizer + format invariant); (f) the LlmProvider was invoked 4 times (4 singleton clusters); (g) when LlmProvider is configured to throw, the same `/summary -w 24h` produces the degraded-fallback reply"
  - "Plain-text invariant docs/spec/commands.md §Surface conventions: replies use single backticks for inline code and bare URLs (no markdown link syntax). Verify: `grep -E '\\]\\(http' en.properties` returns zero matches AND SummaryIT's response-body assertion confirms no `](http` substring in any LLM-pass-through prose (the sanitizer enforces this on LLM output too — a small LLM emitting `[link](http://...)` MUST be stripped to bare URL)"
  - "en.properties under infochat-provider/src/main/resources/bundle/ ships ALL the bundle keys referenced above. Per-key assertions:
    - `error.summary.window_minutes_not_accepted` present
    - `error.summary.window_out_of_range` present
    - `error.summary.unknown_tag` present
    - `error.summary.tag_malformed` present
    - `reply.summary.no_posts_yet` present
    - `reply.summary.degraded_notice` present
    - `reply.summary.cap_excess_notice` present (interpolation tokens for profile, cap, excluded)
    - `reply.summary.top_3_of_n_prefix` present (interpolation token for N)
    Verify per key: `grep -E '^<key>=' en.properties` returns exactly 1 match"
  - "BundleLoader's bundle-completeness CI check (M1-035c's commit) continues to pass: every BundleKeys constant has a matching en.properties value AND no orphan en.properties entries exist. Verify: `mvn -B clean verify` exits 0 — the BundleLoaderTest from M1-035c asserts no missing-key and no orphan-key on boot"
  - "Cluster cap is read from `infochat.summary.cluster-cap` property (per docs/design/03-commands.md §`/summary`); the default value per profile MUST live in application.properties or an @ConfigProperty default — laptop=200, vps=100, pi=50, remote-llm=500. Verify: `grep -E 'infochat\\.summary\\.cluster-cap' SummaryCommandHandler.java SummaryProseGenerator.java EligiblePostQuery.java` returns ≥1 match in at least one of those files"
  - "Every prior test continues to pass: M1-003 @QuarkusTest stubs, M1-007 cross-module AllSpisLoadIT, M1-007a/b/c SPI smoke tests, M1-008 per-scope isolation IT, M1-008a/b/c per-row schema tests, M1-022/023/024/025/026 ingest-source tests, M1-027/028 outbox/NOTIFY tests, M1-032/033/034a/034b eval-pipeline tests (including the M1-033 LlmProvider client + per-task router), M1-035/035a/035b/035c T1-E surface (including AdapterRouterIT). Verify: `mvn -B clean verify` from the repo root exits 0 AND failsafe / surefire reports show zero failures"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/command/SummaryArgsTest.java (unit)
    - infochat-provider/src/test/java/io/infochat/provider/summary/EligiblePostQueryIT.java (DB-tier @QuarkusTest)
    - infochat-provider/src/test/java/io/infochat/provider/summary/ClusterTraversalTest.java (unit — singleton degenerate behavior for MVP)
    - infochat-provider/src/test/java/io/infochat/provider/summary/SummaryProseGeneratorTest.java (unit — mocked LlmProvider)
    - infochat-provider/src/test/java/io/infochat/provider/llm/LlmOutputSanitizerTest.java (unit + match-set completeness assertion)
    - infochat-provider/src/test/java/io/infochat/provider/command/SummaryCommandHandlerTest.java (handler-tier @QuarkusTest with mocked LLM + mocked sanitizer interactions)
    - infochat-provider/src/test/java/io/infochat/provider/command/SummaryIT.java (end-to-end via InMemoryAdapter — MVP exit criterion §6)
  preserves:
    - all M1-008a/b/c *Test.java classes (schema tier)
    - all M1-022/023/024/025/026 *Test.java and *IT.java classes (ingest + SSRF)
    - all M1-027/028 *Test.java and *IT.java classes (outbox/NOTIFY)
    - all M1-032/033/034a/034b *Test.java and *IT.java classes (eval pipeline — including M1-033 per-task router and OpenAI-compatible LlmProvider)
    - all M1-035/035a/035b/035c *Test.java and *IT.java classes (T1-E surface)
spec_refs:
  - docs/spec/commands.md §Content (`/summary`)
  - docs/spec/commands.md §Surface conventions
  - docs/spec/commands.md §Permission model (closed list of privileged-tier commands — sanitizer match-set source)
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Failure handling ([REDACTED:<id>] placeholder retention)
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing
  - docs/design/03-commands.md §Time window flag
  - docs/design/03-commands.md §`/summary [tag] [-w 24h]`
  - docs/design/00-mvp.md §6 MVP exit criteria (criterion §6)
decision_refs:
  - D12
  - D17
  - D18
  - D19
  - D36
  - D43

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-037: /summary command (eligible-post SQL + cluster traversal + LLM prose + sanitizer + degraded fallback)

## Context

Second of T1-F's two MVP-completing command handlers. After this ticket
and M1-036 land, MVP exit criterion §6 (docs/design/00-mvp.md §6)
— `/summary -w 24h` returns LLM prose covering only that user's
subscribed posts in the window, plus bare-URL citations — runs
end-to-end against the full T1-E surface (M1-035 umbrella) and the
M1-033 OpenAI-compatible LlmProvider.

The ticket's scope is the docs/spec/commands.md §Content contract for
`/summary` in its entirety: positional [tag] + `-w <window>` parsing
per docs/design/03-commands.md §Time window flag; deterministic SQL
retrieval of READY posts in the caller's subscribed sources; cluster
traversal that degenerates to singletons in MVP (no post_reference
graph exists yet — T2 builds it); per-cluster LLM prose via the
M1-033 LlmProvider; the LLM output sanitizer from docs/spec/security.md
§LLM output sanitizer (which lands here per M1-035b's out_of_scope
explicit deferral); the degraded-fallback path when the summarizer LLM
is unreachable.

## Definition of Done

- SummaryCommandHandler is a CDI-discovered `@ApplicationScoped`
  implementation of the M1-035b `CommandHandler` SPI, bound to the
  literal name `"summary"`, and InboundRouter dispatches a
  `/summary ...` inbound to it without router-side changes.
- Argument parsing handles `[tag]` + `-w <duration>` per the
  docs/design/03-commands.md §Time window flag accepted-values table
  and produces friendly errors via en.properties bundle keys for
  every rejection path.
- EligiblePostQuery returns READY posts deterministically (sorted by
  `published_at DESC, id DESC`) scoped to the caller's subscriptions,
  the supplied window, and the supplied tag (or the scope's followed
  tags when no tag is supplied, including the `>5 followed tags`
  top-3 restriction).
- ClusterTraversal returns connected components of the post_reference
  graph; for MVP — post_reference table empty — every post becomes
  its own singleton cluster, preserving the spec-shape contract.
- SummaryProseGenerator invokes the M1-033 LlmProvider once per
  cluster, retains `[REDACTED:<id>]` placeholders in the prompt
  unchanged, and falls back to the degraded-form reply (headlines +
  bare URLs + UIDs, no prose) when the LLM is unreachable.
- LlmOutputSanitizer enforces the docs/spec/security.md §LLM output
  sanitizer contract over the closed privileged-tier command list,
  audit-logs every match, and fails CI on a match-set / closed-list
  divergence.
- SummaryIT exercises MVP exit criterion §6 end-to-end through the
  InMemoryAdapter with a mocked LlmProvider.
- `mvn -B clean verify` from the repo root exits 0; every prior
  test continues to pass.

## Implementation notes

Non-binding hints — the developer reads these as context, not a recipe.

- **CommandHandler dispatch contract.** Same as M1-036: M1-035b's
  `CommandHandler` SPI is `String name()` + `OutboundMessage
  handle(ScopeRef, String)`. The router has normalized the body and
  confirmed the leading slash; the handler sees `/summary [tag]
  [-w <window>]` verbatim with the leading slash.
- **TagNormalizer.** docs/design/03-commands.md §Tag arguments
  specifies the closed normalization pipeline (trim + NFC +
  toLowerCase(Locale.ROOT) + `[a-z0-9][a-z0-9-]{0,47}` regex).
  `infochat-core` ships `TagNormalizer.normalize(String)`; consume
  it from SummaryArgs's tag parser. Do NOT re-implement the
  pipeline; do NOT add a new normalizer.
- **LlmProvider.** M1-033 ships `LlmProvider` + the per-task router
  + the OpenAI-compatible HTTP client. SummaryProseGenerator's
  prompt task uses the existing `ModelTask.SUMMARY_PROSE` enum value
  (or whichever value the M1-033 ticket already declared for the
  summary use case — verify by inspecting `infochat-llm-adapter/src/
  main/java/io/infochat/llm/ModelTask.java` before authoring; if no
  such value exists, this is an escalation trigger, NOT an inline
  enum addition).
- **post_reference is empty in MVP.** docs/design/00-mvp.md §2 lists
  the MVP tables: `post`, `source`, `source_subscription`,
  `scope_preferences`, `tag`, `audit_log`, `users`. post_reference
  is NOT in MVP. ClusterTraversal MUST NOT issue any SQL against
  post_reference (a missing-table error would crash on boot). The
  MVP implementation returns N singleton clusters from the input
  List<Post>; T2 / T3 adds the real graph traversal when the table
  lands.
- **Cluster cap.** docs/design/03-commands.md §`/summary` mandates
  `infochat.summary.cluster-cap` as a profile-driven property:
  laptop=200, vps=100, pi=50, remote-llm=500. The cap applies to
  the EligiblePostQuery result count, not the cluster count (in MVP
  the two are equal because every post is its own cluster).
- **Degraded fallback.** docs/design/03-commands.md §`/summary`
  Summarizer LLM unreachable: "/summary falls back to the same
  degraded form as a saturated periodic digest (decision D17):
  headlines + source URLs + post UIDs, no prose. The friendly
  degraded notice (localization-bundle string per D43) replaces the
  prose block; the deterministic post selection is unaffected."
- **Output structure.** docs/design/03-commands.md §`/summary`
  shows the literal reply layout:
  ```
  News (last 24h)

  [topic_id=t-7f3a]
  CVE-2026-1234 — OpenSSL heap overflow
  covered by: Bleeping Computer (uid p-a91), TheHackerNews (uid p-b04)
  score: high (3 sources, news+social)
  summary: A heap overflow in OpenSSL 3.5 lets a remote attacker ...
  classification: technical, urgent
  tags: security, ai
  ```
  Cluster blocks are spec'd at design tier; the reviewer treats the
  layout as the contract for the IT's response-body grep assertions.
- **Sanitizer match-set source.** docs/spec/commands.md §Permission
  model §Closed list of privileged-tier commands. Author a constant
  holder (e.g.
  `infochat-provider/src/main/java/io/infochat/provider/command/
  PrivilegedCommandCatalogue.java`) that mirrors the spec list, and
  have LlmOutputSanitizer derive its match set from that constant.
  The CI completeness @Test asserts the constant equals the spec
  list — file an issue and FAIL CI if they diverge. Note:
  PrivilegedCommandCatalogue.java is NOT in the files_scope budget
  because the constant can live INSIDE LlmOutputSanitizer.java as
  a private constant `List<String>` field; if the developer prefers
  a separate class, the choice triggers a files_scope refine, NOT
  inline scope expansion.

## Big-picture notes

What the implementer must keep in mind that isn't in the immediate diff.

- **The LLM is invoked only inside the determinism boundary.** The
  cluster set is computed by deterministic SQL BEFORE the LLM is
  invoked; the LLM writes prose per pre-computed cluster. The same
  input DB state produces the same cluster set across runs — only
  the prose varies. This is the docs/spec/llm.md §Determinism
  boundary commitment.
- **Determinism extends to ordering.** EligiblePostQuery's `ORDER BY
  published_at DESC, id DESC` is load-bearing: ties broken stably
  on id ensure that two `/summary` calls one second apart against
  the same DB state produce the same post list in the same order.
  Without the secondary key, Postgres is free to return rows in any
  order, which would break the determinism contract.
- **Stage-1 redaction placeholders flow through unchanged.** The
  spec is explicit: `[REDACTED:<id>]` placeholders are NOT stripped
  before the LLM prompt. The placeholder serves the same defensive
  purpose at summarize time as it does at delivery time — it tells
  the model that text was removed without revealing what. The
  acceptance items pin this with a captured-prompt argument
  assertion.
- **Sanitizer is preventative, not reactive.** None of the
  closed-list privileged-tier commands have HANDLERS in MVP
  (/grant-admin, /ban, etc. all land in T2-A / T2-B / T2-F). The
  sanitizer ships now anyway because LLM output can still CLAIM to
  be such a command, and a copy-pasted reply could mislead the
  reader. The dispatch-side defense (admin commands routed only
  through the deterministic command path) is the necessary half;
  the sanitizer closes the social-engineering surface.
- **Sanitizer audit logging is per-occurrence, not throttled.**
  docs/spec/security.md §LLM output sanitizer: "Every match is
  audit-logged (per-occurrence, not throttled)." Two matches in one
  reply produce two audit_log rows. This is intentional — the audit
  log is the operator's signal that a model is producing
  privileged-looking output, and throttling would mask a worsening
  pattern.
- **Plain-text-only invariant.** docs/spec/commands.md §Surface
  conventions + CLAUDE.md §Key conventions: bare URLs, no markdown
  link syntax. The sanitizer strips `[text](url)` patterns from LLM
  output as part of its plain-text enforcement (the closed-list
  regex pass is separate from the markdown-strip pass; both run
  before the reply leaves the provider).
- **Per-task routing is M1-033's job.** SummaryProseGenerator hands
  the LlmProvider a `(ModelTask, prompt)` pair; M1-033's per-task
  router resolves it to an actual model + endpoint. SummaryProse-
  Generator does NOT hard-code an OpenAI URL or a model name; if
  the developer is tempted to, that is a scope-drift signal that
  the M1-033 router is being bypassed.
- **The on-the-fly path is uncached.** Periodic group digests
  (T2-F) use `summary_cache` for the pre-generated path. User
  `/summary` is on-the-fly per decision D18 — every call re-runs
  the query and re-invokes the LLM. No `summary_cache` write here.
- **Decomposition watch.** If clarity pre-flight (per docs/process/
  workflow.md §Ticket-clarity pre-flight) FAILs this ticket as too
  large given the spec breadth (args + retrieval + clustering +
  prose generation + sanitizer + degraded fallback + IT), the
  workflow path is `/m1-tick escalate decompose` into a (a) umbrella
  + (b) retrieval+clustering subticket + (c) prose+sanitizer+IT
  subticket. Do NOT decompose inline; the umbrella+subticket
  pattern (docs/process/workflow.md §Ticket-ID placeholder
  convention) carries the whole-topic IT.

## Out-of-scope expansion

The `out_of_scope` list above carries the load-bearing exclusions; the
prose here explains the boundaries the reviewer uses to judge scope
drift.

- **post_reference graph is T2 territory.** docs/design/00-mvp.md §2
  lists the MVP schema tables; post_reference is NOT in MVP.
  ClusterTraversal in this ticket runs over an empty graph (returns
  singletons) and does NOT issue SQL against post_reference. T2 / T3
  adds the V12+ migration that creates post_reference and the
  Tagger pipeline that populates it; the cluster traversal's
  algorithm shape is preserved across the transition (connected
  components on the post_reference graph) so T2's plug-in is purely
  additive.
- **Periodic group digests are T2-F.** The on-the-fly path here
  shares the prose generator + sanitizer with the periodic-digest
  path that lands in T2-F. The cache (summary_cache) and the
  staggered scheduler are NOT in this ticket; the prose generator
  exposes its API in a shape that T2-F can consume.
- **Cancellation / retry are T2-D.** /stop (decision D35) and
  /retry (decision D36) wrap chat-mode and /summary in a
  cancellation-aware execution context. This ticket's /summary
  runs to completion synchronously inside the InboundRouter
  dispatch; cancellation wiring is T2-D's chat-mode ticket. /retry
  against a degraded /summary regenerates the prose if the LLM has
  recovered — T2-D wires the retry path; this ticket commits only
  to the degraded-fallback path on the FIRST call.
- **Translation is T2-C.** Every outbound reply here uses English
  BundleKeys; TranslationProvider integration / `/lang` is T2-C.
  Bundle keys are designed for translation (interpolation tokens,
  no concatenation of substrings) so T2-C's wiring drops in
  without re-authoring the keys.

## Authorized test changes

This ticket adds tests but does not modify existing ones. Touching any
of the M1-033/034a/034b LLM-pipeline tests or the M1-035/035a/035b/
035c T1-E tests constitutes a test-integrity violation per
docs/process/engineering-rules-verbatim.md §8.

- (none — this ticket adds the seven tests enumerated in
  `test_plan.adds` and does not modify existing ones)

## Alternatives considered

- **Alt A — Skip the sanitizer; ship it later as a separate ticket.**
  Rejected: M1-035b's out_of_scope explicitly defers the sanitizer
  to T1-F's /summary commit, AND /summary is the FIRST consumer
  surface for LLM-authored output reaching users (the M1-033 Stage 2
  judge produces structured verdicts, not user-visible prose).
  Shipping /summary without the sanitizer would expose the LLM's
  output to users without the spec-mandated defense; the
  social-engineering surface (a small LLM emitting plausible-looking
  `/grant-admin <attacker-id>` text) would be open between this
  commit and the future sanitizer commit.
- **Alt B — Implement clusters as full post_reference SQL traversal
  in MVP, with a V12 migration creating post_reference here.**
  Rejected: T1-F is migration-free (per the T1-E handoff and the
  out_of_scope explicit constraint); adding V12 here would block
  parallel work on T2 / T3 schema tickets and would commit to a
  table shape before the Tagger pipeline (T2-D) has determined what
  the graph edges actually carry. The MVP singleton-cluster path
  is spec-consistent (connected components on an empty graph = N
  singletons) and forward-compatible with T2's plug-in.
- **Alt C — Generate one LLM prose call across ALL clusters in a
  single prompt (cheaper).** Rejected: docs/spec/commands.md §Content
  is explicit that the LLM writes prose per pre-computed cluster.
  Batching breaks the determinism contract (a small per-cluster
  prompt change cascades through all clusters' prose); per-cluster
  invocation preserves the docs/spec/llm.md §Determinism boundary
  property that the cluster set is reproducible. Batching is also
  a worse fit for the cluster-cap-excess case where the OLDEST
  posts are dropped — per-cluster generation makes the drop a
  clean boundary.
- **Alt D — Authoring as an umbrella + three subtickets
  (args+retrieval / clustering+prose / sanitizer+IT).** Held in
  reserve as the decomposition path if clarity pre-flight FAILs
  the single-ticket shape. Not chosen up-front because the T1-F
  handoff commits to 2 tickets for /add-source + /summary, not 4+.
  The decomposition path is documented in Big-picture notes so the
  workflow path is clear if it triggers.
