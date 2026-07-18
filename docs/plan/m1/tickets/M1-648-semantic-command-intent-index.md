---
id: M1-648
title: "Semantic command-intent index with deterministic answer composition"
status: escalated
created: 2026-07-18
last_updated: 2026-07-18
blocked_by:
  - M1-645
  - M1-646
  - M1-647
  - M1-654
files_budget: 16
files_scope:
  - infochat-core/src/main/resources/db/migration/V60__doc_embedding.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndexBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/DocEmbeddingDao.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  - docs/spec/commands.md
  - docs/spec/llm.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    USER_GUIDE.md conceptual topics ("what is probation", "DM vs group",
    retention semantics) — M1-649. This ticket indexes COMMAND intents only,
    where every answer has a runtime source of truth to compose from. The
    prose-answer path, which does not, is deliberately deferred.
  - >-
    Injecting the command catalogue into the chat system prompt. Measured and
    rejected: ChatPromptBuilder hands the ENTIRE infochat.context-window to
    history alone with no subtraction for system prompt, tool instructions,
    memory, or retrieval block; the system prompt is re-sent up to
    MAX_TOOL_ITERATIONS (10) times per turn; and cache_control is set only by
    AnthropicProvider, so no local profile (laptop/vps/pi) has prompt caching.
    On pi (context-window=4096) the full catalogue exceeds the window outright.
    Retrieval must be on-demand.
  - >-
    Reusing post_embedding, or altering it, its partitioning, its TTL-by-
    partition-drop model, or its grants. The docs corpus gets its own table.
  - >-
    Changing which posts a query returns, or any part of the post retrieval
    path (SemanticSearchTool, ChatAgent's semantic pre-fetch). D19 is untouched.
  - >-
    LLM-in-the-retrieval-loop techniques — query rewriting, HyDE, LLM re-ranking
    — consistent with M1-617's out_of_scope and the D19 determinism boundary.
  - >-
    Deprecating M1-647's synonym map. It remains the probation-reachable path
    and the seed corpus; this ticket adds a second, chat-only tier.
acceptance:
  - >-
    Migration V60 creates doc_embedding (doc_id TEXT PK, doc_kind TEXT,
    target_ref TEXT, content_hash TEXT, embedding vector(N), embedding_model
    TEXT) with an HNSW cosine index, NOT partitioned, and grants the provider
    role SELECT + INSERT + DELETE. N is 768 — the single app-wide dimension
    already recorded in the embedding_metadata singleton (v1 ships 768 on every
    profile; see Notes) — so EmbeddingMetadataStartupGuard's model/dimension
    identity assumption still holds for both corpora.
  - >-
    On startup the provider embeds one short intent document per catalogue
    command (seeded from CommandIntentSynonyms plus the command's own name and
    short description) and upserts it into doc_embedding. Re-embedding is
    skipped when content_hash and embedding_model both match, so a restart with
    unchanged input performs zero embedding calls.
    CommandIntentIndexTest.restartWithUnchangedCorpusPerformsNoEmbeddingCall
    passes.
  - >-
    A change to the embedding model or to any intent document's text causes that
    row to be re-embedded on next startup — a stale vector can never outlive its
    source text. CommandIntentIndexTest.changedIntentTextIsReEmbedded passes.
  - >-
    A new read-only HelpLookupTool resolves a free-text intent to a command name
    via one fused pgvector query, filtered to the caller's visible tier BEFORE
    the result is returned, reusing the same HelpTier predicate HelpCommandHandler
    uses. HelpLookupToolTest.adminOnlyCommandNeverSurfacesToNonAdmin passes.
  - >-
    THE CORE INVARIANT — the tool returns a command NAME, and the answer body is
    composed at call time from the same code path /help <cmd> uses. Embedded
    text is used only for MATCHING, never for ASSERTING. A stale intent document
    can therefore degrade the match but can never produce wrong syntax.
    HelpLookupToolTest.answerBodyComesFromRuntimeHelpNotFromIndexedText passes by
    mutating an indexed intent document's text and asserting the returned body is
    unchanged.
  - >-
    Below the similarity threshold the tool returns no command and the agent is
    directed to say it does not know and point at /help, rather than answering
    from general knowledge. HelpLookupToolTest.belowThresholdReturnsNoCommand
    passes.
  - >-
    Asking "how do I stop seeing posts from this source" in chat yields
    /unfollow-source with its real usage and examples. An end-to-end test covers
    at least three phrasings that share no prefix with their target command.
  - >-
    Decision D66 in docs/spec/decisions.md records the second embedded corpus,
    the match-not-assert invariant, and the tier-filter-before-return rule.
    docs/spec/commands.md §Chat mode and docs/spec/llm.md §Embedding pipeline
    document the new tool and corpus. (Renumbered from D64 on 2026-07-18 —
    D64 was LANDED by M1-653 for an unrelated decision before this ticket
    reached it. See §Decision renumber in the body.)
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D54
  - D58
clarity_check:
  date: 2026-07-18
  verdict: FAIL
  warnings:
    - >-
      decision_refs (D19, D54, D58) does not list D64, the decision acceptance
      item 8 asks this ticket to mint. Conventional (decision_refs lists
      decisions relied on, not minted), but D64 may need to record the
      tool-allowlist widening too.
    - >-
      Acceptance item 7 (three-phrasing end-to-end intent test) does not name an
      exact test method, unlike items 2-6. Minor; still testable as written.
  blockers:
    - >-
      files_scope (16 entries, exactly matching files_budget: 16 — zero slack)
      omits docs/spec/security.md and docs/spec/verification.md.
      ChatToolRegistry.java is in scope and carries the header comment "Holds the
      closed six-tool allowlist for the chat agent. Additions or removals are
      spec amendments (security.md §Prompt-injection defenses)"; security.md:278
      confirms "The v1 list is closed at spec level (additions or removals are
      spec amendments, not design tweaks)" and its per-tool table is the
      byte-for-byte CI-parity source. Registering a 7th tool (HelpLookupTool,
      acceptance items 4-6) therefore REQUIRES amending both spec files, and the
      budget has no room for them. Fix: add both files to files_scope, raise
      files_budget to at least 18, and add an acceptance item naming the new
      security.md table row and the verification.md prose update.
    - >-
      No acceptance item, test_plan.modifies entry, or Notes/Out-of-scope text
      authorizes updating the pre-existing test
      ChatToolRegistryTest.registryContainsExactlySpecTools, which hard-asserts a
      six-name Set.of and will fail once ChatToolRegistry gains a 7th entry.
      Leaves the implementer choosing between an unauthorized test edit and a red
      mvn verify (contradicting acceptance item 9). Fix: add an acceptance item /
      test_plan.modifies entry naming the test and its new seven-name expected
      set, including the exact registry key the new tool registers under.
    - >-
      (Found during post-verdict verification, NOT by the clarity subagent.)
      CommandIntentSynonyms.java is declared `final class` — package-private, in
      app.zcat.infochat.provider.messaging, with INTENT_TO_COMMAND `private
      static final` and no public member anywhere in the file. Acceptance item 2
      requires seeding intent documents "from CommandIntentSynonyms", but
      files_scope places CommandIntentIndexBuilder in
      app.zcat.infochat.provider.help — a different package, from which the class
      is unreachable. Implementing item 2 therefore REQUIRES editing
      CommandIntentSynonyms.java (widen the class and expose an accessor), and
      that file is not in files_scope — only a prose mention in acceptance item
      2. Fix: add
      infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandIntentSynonyms.java
      to files_scope. Same class of coupling, already in scope and so NOT a
      blocker: HelpTier, CommandHelp and the 41-entry CATALOGUE are
      package-private members of HelpCommandHandler.java, which the tool must
      also reach across the package boundary.
    - >-
      (Found during post-verdict verification.) files_scope omits
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java,
      the file blocker 2 requires modifying. Authorizing the edit in an
      acceptance item is necessary but not sufficient — the changed file must
      also be in files_scope and counted against files_budget.
escalations:
  - date: 2026-07-18
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL
      FILES-BUDGET-PLAUSIBLE: FAIL — files_scope omits docs/spec/security.md and
      docs/spec/verification.md, which the closed-tool-allowlist amendment
      requires; files_budget 16 is fully consumed by the existing 16 entries.
      TEST-CHANGES-AUTHORIZED: FAIL — ChatToolRegistryTest
      .registryContainsExactlySpecTools asserts exactly six tool names and will
      break when a 7th is registered; no ticket text authorizes the update.
---

# M1-648: Semantic command-intent index with deterministic answer composition

## Context

Two findings from the 2026-07-18 help-surface audit motivate this.

**The chat agent invents command syntax.** `ChatPromptBuilder`'s system prompt
is a generic assistant preamble; `ChatToolRegistry` exposes six tools that all
search *posts*; nothing in the chat path knows the command surface exists. Asked
"how do I add a source", the agent finds no relevant posts and, per its own
prompt, answers from general knowledge — confidently, with invented syntax. That
is a trust defect, not a missing convenience.

**Prefix matching cannot close the vocabulary gap.** M1-647 maps the head of the
distribution with a hand-written synonym table, which is cheap, deterministic,
and reaches probation users. It will not cover the tail, and it is English-only.
A semantic index over intent phrasings does cover the tail.

The design question is what the retrieved text is allowed to DO. Straight
RAG-over-documentation would embed prose and let the model answer from the
retrieved chunk — which reintroduces exactly the drift this batch exists to
remove, and adds a third artifact (the embedded snapshot) that can contradict
both `/help` and the guide. M1-645 found four cases where the shipped help text
was already wrong; embedding a snapshot of wrong text would have made those
wrong answers confident and hard to trace.

So: retrieval finds a POINTER; the runtime composes the ANSWER. The embedded
intent document is a matching surface only. Its worst failure mode is a missed
match — never a wrong instruction.

## Acceptance

See `acceptance`. New table + grants, startup-built intent index with content-
hash staleness detection, a tier-filtered lookup tool, and the match-not-assert
invariant pinned by a mutation test.

## Out-of-scope

See `out_of_scope`. Note especially that prompt injection of the catalogue is
rejected on measured grounds, not taste, and that the post retrieval path and
D19 are untouched.

## Notes

**Why a separate table, same database.** One Postgres, one pgvector, one
embedding model. `embedding_metadata` is a singleton pinning model identifier
and dimension app-wide — two corpora cannot use different models without
breaking `EmbeddingMetadataStartupGuard`'s identity assumption. The dimension
is **768 on every profile in v1**, not profile-dependent: there is exactly one
`infochat.embeddings.dimension` key in the tree
(`infochat-collector/src/main/resources/application.properties:548`, value
`768`) with no per-profile override, and `V11__post_embedding.sql:65` hardcodes
`vector(768)`. The per-profile table in
`docs/design/05-llm-and-embeddings.md:705-716` (pi 384, remote-llm 1536) is
labelled in that same file as "the *intended* design, NOT the v1 shipped
reality", and D54 permanently supersedes the 1536 row (embeddings always run on
a local nomic-768 backend). So `vector(N)` in the acceptance above is
`vector(768)` concretely. What the docs corpus must
NOT share is `post_embedding`'s shape: that table is partitioned by `fetched_at`
for TTL-by-partition-drop (Invariant 6), and a docs corpus has no TTL.

**Grants are the non-obvious blocker.** `V11` grants the provider role only
`SELECT` on `post_embedding` — writes are the collector's. The intent index is
provider-owned and provider-written, so V60 must grant the provider
INSERT/DELETE on `doc_embedding` specifically. Do not widen the provider's
grants on any existing table.

**Corpus size and cost.** One document per catalogue command, ~41 rows of a
sentence or two. Startup embedding is a single batch call through the existing
`EmbeddingProvider` SPI, which is already entity-agnostic (`List<String>` →
`List<EmbeddingResult>`) and needs no change. The content-hash skip means the
steady-state startup cost is one SELECT.

**Threshold calibration.** The post-retrieval cutoff took two tickets to settle
(M1-616 set it, M1-619 moved 0.75→0.65). Expect the same here and do NOT reuse
the post cutoff — a 41-document intent corpus has different similarity
statistics than a post corpus. Pick a starting threshold, pin it as a named
constant with a comment recording how it was chosen, and treat recalibration as
a follow-up rather than blocking this ticket on a sweep.

**Tier filtering before return, not after.** The same existence-oracle risk as
M1-647, with a wider attack surface because the input is free text. Filter to
the caller's visible set inside the query or immediately on its result, before
anything reaches the model — never let an invisible command's name enter the
LLM's context and rely on the sanitizer to remove it.

**Reachability limit, stated honestly.** This is chat-only. Probation users
cannot reach it (`InboundRouter:1451-1454`). M1-647 is their path, which is why
that ticket is a prerequisite and not superseded.

**Plan sidecar.** complexity:high — `/m1-tick start` will spawn the plan-writer.
The migration, the startup build path, and the tool wiring are three separable
pieces; the sidecar should sequence them so the table and index land before the
tool is registered.

## Decision renumber — D64 → D66 (2026-07-18)

**Acceptance item 8 asked for `D64`; that number is gone.** This ticket was
filed at `db200608` when `decisions.md` maxed at D61, and the M1-645..649
batch forward-allocated D62→M1-641, D63→M1-642, **D64→M1-648**. A later
session re-derived the allocation from `decisions.md`'s max row alone,
without checking pending tickets, assigned D64 to M1-653, and **M1-653
landed it** in `832b4ff6` as an unrelated decision (outbound delivery is
at-least-once). Append-only forbids renumbering a landed row, so the landed
row keeps D64 and this unlanded claim moves.

**D66 is the next genuinely free number:** D63 is held by M1-642 and D65 by
M1-652 — both in *acceptance prose*, both invisible to `decisions.md`.
M1-649's `decision_refs` was updated to D66 in the same commit, since it
cites the row this ticket creates.

**Re-verify D66 is still free immediately before writing the row.** A
D-number claim lives in acceptance prose and is validated by **nothing** —
not `scripts/lint-ticket.py`, and not the clarity gate, whose
SPEC-REFS-VALID check covers `spec_refs` headings only. Checking
`decisions.md` is NOT sufficient; grep the claimants:
`grep -rn 'D6[0-9]' docs/plan/m1/tickets/*.md`.
