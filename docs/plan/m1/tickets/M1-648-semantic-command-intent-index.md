---
id: M1-648
title: "Semantic command-intent index with deterministic answer composition"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by:
  - M1-645
  - M1-646
  - M1-647
  - M1-654
files_budget: 21
files_scope:
  - infochat-core/src/main/resources/db/migration/V60__doc_embedding.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndexBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/DocEmbeddingDao.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandIntentSynonyms.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
  - docs/spec/commands.md
  - docs/spec/llm.md
  - docs/spec/decisions.md
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
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
  - >-
    docs/spec/verification.md. M1-654 removes its duplicated tool-name
    enumeration and repoints it at security.md's table as the single source of
    truth; this ticket must not re-add an enumeration there. The only spec list
    this ticket edits is security.md's table.
  - >-
    The closed-allowlist mechanism itself, and M1-654's parity guard. This
    ticket adds one ROW to the spec table and one NAME to the registry; the
    guard then proves the two agree. Do not modify, relax, or work around it.
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
  - >-
    docs/spec/security.md §Prompt-injection defenses gains a table row for the
    new tool — name `helpLookup`, its free-text input, its output shape, and a
    Notes entry recording the tier filter — placed INSIDE the
    `<!-- tool-allowlist:begin -->` / `<!-- tool-allowlist:end -->` markers
    M1-654 adds, so M1-654's
    ChatToolAllowlistSpecParityTest.registryMatchesMarkedSpecTable stays green.
    The registry key and the spec row's name must match byte-for-byte.
  - >-
    AUTHORIZED PRE-EXISTING TEST CHANGE —
    ChatToolRegistryTest.registryContainsExactlySpecTools pins a six-name
    Set.of and fails once ChatToolRegistry gains a 7th entry. Its expected set
    becomes the SEVEN names (the existing six plus `helpLookup`). Nothing else
    in that file changes, and the assertion stays an exact set equality — never
    a containment check, a size check, or a removal.
  - >-
    CommandIntentSynonyms is reachable from the index builder. It is currently
    `final class` (package-private) in provider.messaging with a private
    INTENT_TO_COMMAND map, while files_scope places CommandIntentIndexBuilder in
    provider.help — so seeding per acceptance item 2 requires widening the class
    and exposing a read-only accessor. Widen no further than the builder needs;
    the map stays immutable and is never mutated by the index.
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolTest.java
  modifies:
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
      change: >-
        registryContainsExactlySpecTools — the expected Set.of grows from six
        names to seven (adds `helpLookup`). Exact-equality assertion retained.
  preserves:
    - all tests currently green on main
    - >-
      M1-654's ChatToolAllowlistSpecParityTest — it stays green only if the
      security.md row and the registry key match exactly, which is the point.
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D21
  - D54
  - D58
revisions:
  - date: 2026-07-18
    reason: >-
      clarity-fail rework. The user chose `refine` from the escalation menu;
      this was NOT a bounded self-refine, because the fix widens files_budget
      and files_scope, which run.md excludes from self-refine. Four blockers,
      all scope-AUTHORIZATION defects rather than design defects: the ticket
      registers a 7th chat tool into a spec-closed allowlist without the files
      or the acceptance language that requires. Every blocker was verified
      against the cited file:line before refining, and one route around the
      first was tested and rejected — a deterministic-only tool cannot bypass
      the registry, since ChatToolDispatcher:140 rejects unregistered names and
      :116 advertises to the model from that same set. docs/spec/verification.md
      is deliberately NOT added to scope: the newly-filed M1-654 (now a blocker)
      deletes its duplicated enumeration and repoints it at security.md, so this
      ticket touches only security.md's table. Two blockers were found during
      post-verdict verification, not by the clarity subagent.
    prior_values: |
      status: escalated. files_budget: 16, with files_scope holding exactly 16
      entries — no docs/spec/security.md, no docs/design/05-llm-and-embeddings.md,
      no CommandIntentSynonyms.java, no ChatToolRegistryTest.java.
      acceptance had 9 items: the security.md table-row item, the AUTHORIZED
      PRE-EXISTING TEST CHANGE item and the CommandIntentSynonyms-reachability
      item did not exist. test_plan had no `modifies:` block and did not name
      M1-654's parity test under `preserves`. decision_refs was [D19, D54, D58].
      out_of_scope had 6 entries; the verification.md and closed-allowlist
      boundaries did not exist. blocked_by gained M1-654 earlier, in 91bc5cd8.
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

**The closed tool allowlist is a spec gate, not a code detail.** `ChatToolRegistry`'s
header comment and `security.md` §Prompt-injection defenses both state the v1
tool list is closed and that additions are spec amendments. Registering
`helpLookup` therefore requires the security.md table row, and there is no way
around it in code: `ChatToolDispatcher:140` rejects any name absent from
`toolNames()`, and `:116` advertises tools to the model from that same set, so a
"deterministic-only" tool is not a loophole. M1-654, now a blocker, turns that
requirement into a build failure instead of something a reviewer must notice.

**Package boundaries the acceptance items imply.** `CommandIntentSynonyms` is
package-private in `provider.messaging` with a private map, and `HelpTier`,
`CommandHelp` and the 41-entry `CATALOGUE` are package-private members of
`HelpCommandHandler`. The new `provider.help` and `provider.chat.tool` classes
sit outside that package, so both need a visibility widening. Widen the minimum
the callers need — this is not licence to make the catalogue a public API.

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
