---
id: M1-589
title: "Provider chat: digest-first semantic RAG — a general assistant grounded in pgvector nearest-neighbour retrieval, replacing tag-guessing"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 13
files_scope:
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Keeping vs. removing the existing tag-only searchPosts tool. This ticket ADDS
    semantic retrieval; whether searchPosts stays (a deterministic exact-tag path
    alongside semantic search) or is retired is the IMPLEMENTER'S call. If it
    stays, its "Unknown tag" behaviour and TOOL_INSTRUCTIONS placeholder are
    UNCHANGED by this ticket except for the general-assistant framing; if it is
    removed, drop it from the allowlist / dispatcher / prompt in the same diff.
    Either choice is in-budget; do NOT expand into re-tuning the tag vocabulary.
  - >-
    Reranking. A cross-encoder or LLM rerank pass over the ANN candidate set is a
    separate quality improvement. v1 grounds on the raw pgvector cosine order
    (ORDER BY embedding <=> ? — the LinkingJob shape) with a distance threshold;
    no second-stage rerank.
  - >-
    Any change to the COLLECTOR's embedding pipeline, EmbeddingWorker, the
    EmbeddingMetadataStartupGuard, LinkingJob, or the pgvector index. The provider
    is a pure READER of post_embedding (V11 grants it SELECT only). No re-embed, no
    new index, no dimension change, no write path. The provider MUST NOT run the
    embedding-metadata identity/dimension guard (that is a collector-owned
    write-side concern, D54); it only reads existing vectors.
  - >-
    Schema / migration. post_embedding already exists (V11) with a provider SELECT
    grant and a vector(768) column; migration_touch=false. No DDL.
  - >-
    Localization/translation of the retrieval layer. The system prompt and
    TOOL_INSTRUCTIONS are Java string constants (not localization-bundle keys), so
    no en/cs bundle twin is added (D43 bilateral-keyset rule is not triggered). The
    existing per-scope /lang translation of the FINAL prose reply is unchanged;
    source post bodies are never translated (llm.md §Translation flow).
  - >-
    Multi-turn retrieval memory / caching of the ANN result across turns, and any
    "background subscription" or streaming retrieval. Each turn embeds the current
    message and probes once.
acceptance:
  - >-
    The provider gains an EmbeddingProvider it obtains by plain CDI (@Inject
    EmbeddingProvider — the same SPI EmbeddingWorker uses; NOT the LlmRouter /
    ModelTask path, which is generative-only), pointed at the SAME local nomic-768
    endpoint the collector uses via infochat.embeddings.* config added to the
    provider's application.properties. Per D54 embeddings ALWAYS run on the local
    backend and are NEVER routed to a remote provider; the provider's
    infochat.embeddings.base-url therefore points at the local Ollama/llama.cpp
    nomic embedder, never at a remote API, on every profile.
  - >-
    A NEW tool app.zcat.infochat.provider.chat.tool.SemanticSearchTool implements
    ChatToolRegistry.ChatTool (String execute(UUID userId, String scopeKind, UUID
    scopeId, Map<String,Object> args) throws SQLException). On a query it embeds
    the user's message text (embeddingProvider.embed(List.of(text)) -> the single
    EmbeddingResult.vector() float[]), formats that vector as a pgvector text
    literal ([f0,f1,...]), and runs a nearest-neighbour probe over post_embedding
    reusing LinkingJob's index-driving shape: an inner "SELECT ... (pe.embedding
    <=> ?::vector) AS distance ... ORDER BY pe.embedding <=> ?::vector LIMIT ?"
    (the HNSW-indexable distance-only ORDER BY) wrapped by a "WHERE distance < ?"
    relevance threshold. Results are SCOPED to the caller's subscriptions with the
    SAME predicate SearchPostsTool uses (p.source_id IN (SELECT source_id FROM
    source_subscription WHERE scope_kind = ? AND scope_id = ?)) and to READY posts,
    so semantic search can never surface a post outside the (user, scope)'s
    subscribed sources. Return shape is a byte-budgeted JSON array (mirroring
    SearchPostsTool.MAX_RESULT_BYTES) of the retrieved posts' displayable fields
    (uid, title, url, and the cosine similarity) — never the raw embedding vector
    (D5: embeddings are internal, never shown).
  - >-
    The tool is registered end to end: added to ChatToolRegistry's TOOL_NAMES
    allowlist (the closed tool set), wired by name in ChatToolDispatcher's
    Map.of(...) with its bean injected in the constructor (so the existing
    requireHandlerForEveryAdvertisedTool startup completeness check passes), and
    described in ChatAgent.TOOL_INSTRUCTIONS. Because the closed allowlist is a
    documented spec surface (security.md §Prompt-injection defenses names it a spec
    amendment), docs/spec/security.md §Prompt-injection defenses and
    docs/design/05-llm-and-embeddings.md §5.4.5 are updated to reflect the widened
    tool set and the semantic-retrieval capability.
  - >-
    ChatPromptBuilder's CHAT_SYSTEM_PROMPT_TEMPLATE is rewritten from the tag-only
    news-bot framing ("Answer questions using only the tools provided and the
    conversation history") to a GENERAL-ASSISTANT framing: answer any question;
    when relevant posts are retrieved, GROUND the answer in them and cite their
    source URLs bare (no markdown links); when nothing relevant is retrieved,
    answer from general knowledge. The prompt-injection defence block is preserved
    VERBATIM — the <<<UNTRUSTED_CONTENT id="...">>> wrapper rules AND the exact
    "[REFUSAL: <reason>]" clause (the ChatAgent prefix interceptor depends on that
    exact token), plus the plain-text/bare-URL-only rule.
  - >-
    Orchestration: semanticSearch is run on EVERY turn deterministically (folded
    into the prompt before the model's final answer — the ChatMemoryPreFetcher /
    D28 "always runs" pattern), NOT left to the model to choose. The distance
    THRESHOLD gates grounding-vs-general: candidates above the configured cosine
    threshold ground the reply; if none clear the threshold the model answers from
    general knowledge. Retrieved posts are re-injected through the SAME
    UNTRUSTED_CONTENT wrapper ChatAgent already applies to tool results (they are a
    trust boundary — injection surface). Determinism (D19) is preserved: which
    posts come back and in what order is decided by SQL (ORDER BY embedding <=> ?),
    not by the LLM; the LLM only embeds (allowed) and writes prose (allowed).
  - >-
    NAMED TESTS. SemanticSearchToolTest: with a stub EmbeddingProvider returning a
    fixed vector against seeded post_embedding rows, asserts (a) the k nearest
    subscribed posts are returned in cosine order; (b) posts outside the caller's
    subscription are NEVER returned; (c) candidates beyond the distance threshold
    are excluded (empty result -> the general-knowledge path); (d) the raw vector
    is not present in the JSON. ChatToolRegistryTest / ChatToolDispatcherTest:
    allowlist and dispatcher map include semanticSearch and the startup
    completeness check still passes. ChatAgentTest: TOOL_INSTRUCTIONS advertise
    semanticSearch and the turn orchestration always invokes it. ChatPromptBuilderTest:
    the new general-assistant framing is present AND the untrusted-content wrapper
    + exact [REFUSAL: ...] clause are retained. Red-before/green-after on the
    subscription-scope and threshold assertions.
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolTest.java
      — stub-EmbeddingProvider + seeded post_embedding rows; asserts cosine-ordered
      k-NN, subscription scoping, distance-threshold exclusion, and no-raw-vector in
      the JSON.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolRegistryTest.java
      — allowlist now includes semanticSearch.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
      — dispatcher maps semanticSearch and the completeness check passes with the
      widened tool set.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      — TOOL_INSTRUCTIONS advertise semanticSearch; the turn always invokes it.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
      — general-assistant framing present; untrusted-content wrapper + exact
      [REFUSAL: ...] clause retained.
  preserves:
    - all tests currently green on main
    - >-
      the existing prompt-injection-defence tests (untrusted-content wrapper,
      ChatAgentRefusalInterceptTest's [REFUSAL: ...] prefix interception) — the
      defence text is retained verbatim.
    - >-
      SearchPostsTool's own tests IF the tool is kept (see out_of_scope: the
      keep/remove choice is the implementer's).
spec_refs:
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Memory retrieval
  - docs/spec/security.md §Prompt-injection defenses
  - docs/design/05-llm-and-embeddings.md §5.4.5 Chat agent
  - docs/design/05-llm-and-embeddings.md §5.5 Embeddings
decision_refs:
  - D5
  - D6
  - D19
  - D28
  - D54
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-589: digest-first semantic RAG — a general assistant grounded in pgvector retrieval

## Context

Verified live 2026-07-08 (SimpleX test-user walkthrough). The provider's chat
agent is a **tag-only, restricted-by-design news bot** that (a) refuses general
questions and (b) cannot reliably find posts that ARE in the corpus. Four
concrete facts, all confirmed against the source:

1. **The system prompt refuses general questions.**
   `ChatPromptBuilder.CHAT_SYSTEM_PROMPT_TEMPLATE` (lines 28–44) opens with:

   > You are a helpful news assistant. Answer questions using **only the tools
   > provided and the conversation history**.

   Live effect: "Python vs Rust?" was refused; a dog-legs / Finland question was
   answered **only because the model disobeyed** the "only the tools" clause.

2. **The only search tool is tag + time-window, no keyword/semantic path.**
   `SearchPostsTool` filters by controlled-vocabulary tags and a clamped time
   window, scoped to the caller's subscriptions:

   ```sql
   SELECT p.uid, p.title, p.url, p.ready_at, p.tags FROM post p
   WHERE p.status = 'READY'
     AND p.published_at >= ?
     AND p.source_id IN (SELECT source_id FROM source_subscription
         WHERE scope_kind = ? AND scope_id = ?)
   [AND p.tags && ?::TEXT[]]
   ORDER BY p.published_at DESC, p.id DESC LIMIT ?
   ```

   An unknown tag throws `IllegalArgumentException("Unknown tag: " + tag)`.
   There is no free-text or vector retrieval anywhere in the provider.

3. **The model is never told the real tag vocabulary, so it hallucinates tags.**
   `ChatAgent.TOOL_INSTRUCTIONS` advertises searchPosts with a **literal
   placeholder**:

   ```
   - searchPosts {"tags": ["tag1"], "window": "P7D", "limit": 10} — search posts by tags within a time window
   ```

   The real controlled vocabulary (≈23 tags) is **never injected**, so the model
   guesses tag names, hits "Unknown tag", and fails to retrieve posts that exist
   (e.g. a "Tenda backdoor" post it could not surface).

4. **Embeddings exist but the provider does no semantic retrieval.**
   `post_embedding` (V11, `vector(768)`, HNSW `idx_post_embedding_hnsw`) is
   written for every post and consumed by the **collector's** `LinkingJob` for
   post-to-post cosine linking (`ORDER BY embedding <=> ?::vector`). The
   **provider** has **no EmbeddingProvider injected anywhere** and the chat does
   no vector search — the embeddings are unused for Q&A. V11 already grants the
   provider role `SELECT` on `post_embedding`, so the read path is available and
   unused.

## The design (user-approved)

Make the chat a **general assistant that does digest-first RAG**. On each query:
embed the user's message, run a pgvector nearest-neighbour semantic search over
`post_embedding` (scoped to the caller's subscriptions, with a cosine-distance
relevance threshold); if relevant posts come back, ground the answer in them and
cite bare source URLs; if nothing clears the threshold, answer from general LLM
knowledge. This **replaces tag-guessing** — no vocabulary knowledge required.

Four pieces:

**(a) Provider embedding client.** The provider already depends on
`infochat-llm-adapter` (`EmbeddingProvider`, `EmbeddingResult`,
`OpenAiCompatibleEmbeddingProvider`, and the `MeteredEmbeddingProvider`
decorator are all on its classpath) but injects no `EmbeddingProvider` today and
its `application.properties` has **no `infochat.embeddings.*` block**. Add that
block pointing at the **same local nomic-768 endpoint the collector uses** — per
D54 embeddings are ALWAYS local, NEVER remote, on every profile — so a plain
`@Inject EmbeddingProvider` resolves to the config-driven
`OpenAiCompatibleEmbeddingProvider`. This is the SAME SPI `EmbeddingWorker` uses
(`embeddingProvider.embed(List<String>) -> List<EmbeddingResult>`); it is NOT
the `LlmRouter` / `ModelTask` path, which is generative-only (the embedder is
one-provider-per-deployment, not a `ModelTask`).

**(b) `SemanticSearchTool`.** New `@ApplicationScoped` class implementing
`ChatToolRegistry.ChatTool` — `String execute(UUID userId, String scopeKind,
UUID scopeId, Map<String,Object> args) throws SQLException`. It:
- embeds the user's message text (`embed(List.of(text))`, take the single
  `EmbeddingResult.vector()`),
- formats the `float[]` as a pgvector text literal `[f0,f1,...]` and binds it via
  `setString` through a `?::vector` cast (LinkingJob's binding shape — LinkingJob
  reads its literal from `embedding::text`; here we build it from the fresh query
  vector),
- runs the k-NN probe reusing LinkingJob's **index-driving** shape: an inner
  `SELECT ..., (pe.embedding <=> ?::vector) AS distance FROM post_embedding pe
  WHERE ... ORDER BY pe.embedding <=> ?::vector LIMIT ?` (distance-only ORDER BY
  so HNSW drives it), wrapped by `WHERE distance < ?` for the relevance
  threshold, scoped by the SAME `source_id IN (SELECT source_id FROM
  source_subscription WHERE scope_kind = ? AND scope_id = ?)` predicate and
  `status = 'READY'`,
- returns a byte-budgeted JSON array (mirror `SearchPostsTool.MAX_RESULT_BYTES`)
  of `{uid, title, url, similarity}` — **never the raw vector** (D5: embeddings
  are internal, never shown; similarity = `1 - distance` as LinkingJob computes).

Register it: add `semanticSearch` to `ChatToolRegistry.TOOL_NAMES`; inject the
bean in `ChatToolDispatcher`'s constructor and add it to the `Map.of(...)`
(the existing `requireHandlerForEveryAdvertisedTool` startup check then covers
it); describe it in `ChatAgent.TOOL_INSTRUCTIONS`.

**(c) General-assistant system prompt.** Rewrite
`CHAT_SYSTEM_PROMPT_TEMPLATE`'s framing from "answer using only the tools" to
"answer any question; ground in retrieved posts when relevant and cite bare
source URLs; otherwise answer from general knowledge." **Preserve verbatim** the
`<<<UNTRUSTED_CONTENT id="...">>>` wrapper rules and the exact
`[REFUSAL: <reason>]` clause (the `ChatAgent` prefix interceptor and
`ChatAgentRefusalInterceptTest` depend on that exact token) plus the
plain-text/bare-URL-only rule.

**(d) Orchestration — always run, threshold gates.** Run `semanticSearch` on
**every** turn deterministically and fold its result into the prompt before the
model's final answer — the `ChatMemoryPreFetcher` / D28 "always runs, folded in"
pattern — rather than relying on the model to choose to call it (the model
cannot reliably decide, and tag-guessing is exactly the failure we are removing).
The distance **threshold** gates grounding-vs-general: candidates under the
cosine-distance threshold ground the reply; if none clear it, the model answers
from general knowledge. Retrieved posts are re-injected through the SAME
`UNTRUSTED_CONTENT` wrapper `ChatAgent` already applies to tool results (post
bodies/titles are attacker-influenced content — an injection surface).

**Spec alignment.** Retrieval stays **SQL-deterministic** (D19): the set of
posts and their order come from `ORDER BY embedding <=> ?` in SQL — the LLM only
*embeds* the query (an allowed LLM task) and *writes prose* (allowed); it never
picks the set. This extends embeddings from D5/D6's "internal linking only" use
to chat retrieval **without ever showing a vector** — the widening is recorded
in `docs/design/05-llm-and-embeddings.md` §5.4.5/§5.5, and the widened tool
allowlist in `docs/spec/security.md` §Prompt-injection defenses (which names the
closed tool set a spec surface). A separate cosine-distance threshold property
(`infochat.chat.*`) is added for chat relevance — do NOT reuse
`infochat.linking.semantic-threshold`, which tunes a different decision
(post-to-post linking).

## Out-of-scope

See frontmatter. Notably: the keep-or-remove decision on the tag-only
`searchPosts` tool and any reranking are both deferred/implementer's-call; the
collector's embedding pipeline, `LinkingJob`, the pgvector index, and the
embedding-metadata guard are untouched (the provider reads `post_embedding`
SELECT-only); no schema/migration; no localization-bundle keys (the prompt and
tool instructions are Java constants, so D43's en/cs twin rule is not triggered).

## Notes

- **Provenance.** Live-test finding 2026-07-08 (SimpleX test-user walkthrough).
  Not a red-team finding. `security_relevant: true` because chat now sends the
  user's message AND attacker-influenced retrieved post content to the LLM — the
  retrieval result is a trust boundary and MUST ride the existing
  `UNTRUSTED_CONTENT` wrapper; the `[REFUSAL: ...]` and injection-defence text is
  preserved verbatim.
- **Vector binding delta.** `LinkingJob` reads its driving vector from the DB as
  text (`embedding::text`); this tool builds the literal from a **fresh** query
  embedding (`EmbeddingResult.vector()` `float[]` -> `[f0,f1,...]`). Same
  `?::vector` bind, different source.
- **Dimension.** `post_embedding` is `vector(768)` by default but the column is
  deployment-configurable (pi 384, remote-llm override 1536). The query vector
  and the stored column share the SAME local nomic backend by construction (D54),
  so dimensions match without a hardcoded 768 — do not hardcode the dimension.
- **No metadata guard in the provider.** The embedding identity/dimension
  startup guard is a collector write-side concern; the provider is a pure reader
  and must not re-run it.
- **Determinism knob.** Because the query embedding is deterministic for a given
  message + model and the ranking is SQL, "same DB state + same message -> same
  retrieved set/order" holds (D19's temporal-scope guarantee).
</content>
</invoke>
