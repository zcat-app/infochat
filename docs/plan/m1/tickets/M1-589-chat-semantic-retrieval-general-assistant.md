---
id: M1-589
title: "Provider chat: digest-first semantic RAG — a general assistant grounded in pgvector nearest-neighbour retrieval, replacing tag-guessing"
status: done
created: 2026-07-08
last_updated: 2026-07-11
blocked_by: []
files_budget: 13
files_scope:
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
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
    (ORDER BY embedding <=> ?, iterative filtered index scan) with a distance
    threshold; no second-stage rerank.
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
    as a SINGLE filtered query driven by a pgvector ITERATIVE index scan (SET
    LOCAL hnsw.iterative_scan = strict_order on the armed tool connection — the
    same SET LOCAL transaction armToolConnection already opens; pgvector >= 0.8,
    deployment and DevServices both run pgvector/pgvector:pg16, live 0.8.3):
    subscription, READY, and the "(pe.embedding <=> ?::vector) < ?" relevance
    threshold are WHERE predicates INSIDE the index-driven "ORDER BY pe.embedding
    <=> ?::vector LIMIT ?" query, wrapped by a deterministic outer re-sort
    (distance ASC, post_id ASC). Filtering inside the scan makes retrieval exact
    over the caller-visible corpus: recall does not depend on a global-top-k
    over-fetch, and observed recall carries no signal about unsubscribed content
    (redteam 2026-07-11 out-of-model density-oracle item eliminated by
    construction). Results are SCOPED to the caller's subscriptions with the
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
    docs/design/05-llm-and-embeddings.md §5.4.6 are updated to reflect the widened
    tool set and the semantic-retrieval capability. Because the deterministic
    per-turn pre-fetch runs BEFORE the chat LLM call, docs/spec/security.md
    §Failure handling ("Provider-side (user-facing) LLM failures") is also
    amended so its chat-mode bullet no longer promises "no tool invocation" on
    the LLM-unreachable path — that path may have already run the read-only
    deterministic semantic pre-fetch (rate-capped, statement_timeout-bounded)
    before the failure surfaced; no chat_session advance, no chat_memory write,
    and no MODEL-initiated tool call still hold. The ChatAgent LLM-failure catch
    comment is aligned to cite that amended bullet rather than silently
    reinterpreting the old absolute wording.
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
    The deterministic pre-fetch dispatches with the SAME TurnContext the tool
    loop uses (one context per chat turn), so an identical model-initiated
    semanticSearch call is served from the per-turn cache — no duplicate
    embed/probe — and the pre-fetch consumes a slot of the same per-turn call
    budget (remediation of the redteam 2026-07-11 low DOS finding; the spec's
    "fixed cap / identical calls don't re-query" promise holds turn-wide).
  - >-
    NAMED TESTS. SemanticSearchToolIT: with a stub EmbeddingProvider returning a
    fixed vector against seeded post_embedding rows, asserts (a) the k nearest
    subscribed posts are returned in cosine order; (b) posts outside the caller's
    subscription are NEVER returned; (c) candidates beyond the distance threshold
    are excluded (empty result -> the general-knowledge path); (d) the raw vector
    is not present in the JSON; (e) a subscribed post under the threshold remains
    retrievable when MORE than limit×4 semantically-nearer posts sit in
    UNSUBSCRIBED sources (crowding recall — red under the superseded
    global-top-k over-fetch shape, green under the iterative filtered scan).
    ChatToolRegistryTest / ChatToolDispatcherTest:
    allowlist and dispatcher map include semanticSearch and the startup
    completeness check still passes. ChatAgentTest: TOOL_INSTRUCTIONS advertise
    semanticSearch, the turn orchestration always invokes it, and an identical
    model-initiated semanticSearch call after the deterministic pre-fetch is
    served from the shared per-turn cache (the tool executes exactly once).
    ChatPromptBuilderTest:
    the new general-assistant framing is present AND the untrusted-content wrapper
    + exact [REFUSAL: ...] clause are retained. Red-before/green-after on the
    subscription-scope and threshold assertions.
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
      — stub-EmbeddingProvider + seeded post_embedding rows; asserts cosine-ordered
      k-NN, subscription scoping, distance-threshold exclusion, no-raw-vector in
      the JSON, and crowding recall (a subscribed post survives >limit×4 nearer
      unsubscribed neighbours — exact retrieval via the iterative filtered scan).
      Named *IT (failsafe) because it boots DevServices pgvector —
      integration-shaped per docs/design/08-verification.md §8.2 (M1-495 guard).
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
      — TOOL_INSTRUCTIONS advertise semanticSearch; the turn always invokes it;
      an identical model-initiated call is served from the shared per-turn
      cache (single execution — redteam DOS remediation pin).
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
  - docs/design/05-llm-and-embeddings.md §5.4.6 Chat agent
  - docs/design/05-llm-and-embeddings.md §5.5 Embeddings
decision_refs:
  - D5
  - D6
  - D19
  - D28
  - D54
reviews:
  - round: 3
    date: 2026-07-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 1254
      removed: 75
  - round: 2
    date: 2026-07-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 1106
      removed: 68
  - round: 1
    date: 2026-07-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 824
      removed: 30
escalations:
  - date: 2026-07-11
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Pre-commit /redteam M1-589 --in-progress re-run r2 (post r1
      remediation): FINDINGS (low=1, out-of-model=1). The r1 TurnContext
      finding is CLOSED; the crowding oracle is eliminated on HNSW. NEW low
      DOS/spec-drift finding (verbatim GAP): "The new deterministic
      semanticSearch pre-fetch executes BEFORE the chat LLM is resolved or
      called, so on the LLM-unreachable failure path a tool invocation (one
      embedding HTTP round-trip ... plus one pgvector k-NN index probe) has
      already run by the time the friendly error is returned ... security.md
      was amended in this diff (the semanticSearch tool-table row) but the
      §Failure handling bullet was NOT amended to match, so the shipped spec
      still promises zero tool work on this path." User chose refine (option
      1) to align §Failure handling + the ChatAgent catch comment in-scope
      (security.md and ChatAgent.java are already in files_scope); the
      out-of-model ivfflat caveat folds into the design-05 note. The
      router-level LLM circuit-breaker the finding gestures at is filed as a
      separate follow-up ticket (proper feature at the right altitude), not
      smuggled into this RAG ticket. Full record:
      docs/plan/m1/redteam/M1-589-2026-07-11-r2.md
  - date: 2026-07-11
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Pre-commit /redteam M1-589 --in-progress: FINDINGS (low=1,
      out-of-model=2). The low DOS finding (verbatim GAP): "The new
      deterministic per-turn semanticSearch pre-fetch dispatches through
      the 5-arg convenience overload — which creates a FRESH TurnContext
      per call — while runToolLoop creates its own separate TurnContext
      for the same chat turn. The pre-fetch's result is cached in a
      context that is immediately discarded, so within one chat turn an
      identical model-initiated semanticSearch call misses the cache and
      re-executes ... The pre-fetch execution also does not consume a
      slot of the loop's per-turn call budget ... so the effective
      per-turn execution bound is cap+1, not the single fixed cap the
      spec describes." Full record:
      docs/plan/m1/redteam/M1-589-2026-07-11.md
  - date: 2026-07-11
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-review). Developer-reported files_scope conflict: the new
      SemanticSearchToolTest is a DB-backed @QuarkusTest (injects
      @SeedDataSource DataSource, boots DevServices pgvector — required to
      exercise cosine order/threshold/subscription-scoping in real SQL), and
      infochat-core's IntegrationTestNamingGuardTest (M1-495 ratchet) fails
      the build for any NEW such class named *Test:
        "Offenders: [app.zcat.infochat.provider.chat.tool.SemanticSearchToolTest]
         — Rename each to *IT, or ... add it to
         src/test/resources/integration-test-naming-baseline.txt"
      Both sanctioned resolutions touch a path outside files_scope: renaming
      creates SemanticSearchToolIT.java (not the enumerated *Test path, and
      acceptance/test_plan name SemanticSearchToolTest verbatim); baselining
      edits infochat-core/src/test/resources/integration-test-naming-baseline.txt
      (a different module entirely). Full verify r1 red on exactly this one
      guard; all 67 targeted chat tests green, scope+threshold red-checks done.
overrides: []
revisions:
  - date: 2026-07-11
    reason: >-
      redteam-finding rework r2 (user-directed refine, escalation menu option
      1). The r2 re-run confirmed the r1 remediations closed but flagged a NEW
      low DOS/spec-drift finding: the always-run pre-fetch executes before the
      chat LLM call, so the LLM-unreachable failure path runs one read-only
      embed+probe while security.md §Failure handling still promised "no tool
      invocation" on that path. Fix (all in files_scope, no behaviour change):
      amend §Failure handling's chat-mode bullet to permit the deterministic
      read-only pre-fetch (still no session advance / no memory write / no
      model-initiated tool call); align the ChatAgent LLM-failure catch comment
      to cite the amended bullet; scope the design-05 "exact + leak-free" claim
      to the HNSW index v1 ships on every profile (the deferred ivfflat design
      would need ivfflat.iterative_scan, which has no strict_order — noted, not
      built). User rejected deferring/half-baking: the router-level LLM
      circuit-breaker the finding gestures at is filed as a SEPARATE follow-up
      ticket (proper feature at the router altitude, protecting all ModelTask
      consumers), not smuggled into this RAG ticket's third round. Acceptance
      item 3 gains the §Failure-handling-alignment clause.
    prior_values: |
      acceptance item 3 required only the §Prompt-injection-defenses +
      design-05 §5.4.6 doc updates (no §Failure handling clause). The
      ChatAgent catch comment read "No session advance, no memory write, no
      tool invocation beyond what already ran before the failure" with no
      spec anchor. design-05 read "both are exact and leak-free" unscoped.
  - date: 2026-07-11
    reason: >-
      redteam-finding rework (user-directed refine, escalation menu option 1;
      bundle confirmed by user). (a) Remediate the low DOS finding: the
      deterministic pre-fetch must share ONE TurnContext with the tool loop
      so identical calls hit the per-turn cache and the call budget covers
      pre-fetch + loop. (b') Replace the pinned LinkingJob inner-global-top-k
      / outer-filter SQL shape with a pgvector iterative-index-scan filtered
      probe (SET LOCAL hnsw.iterative_scan = strict_order; pgvector 0.8.3
      live, DevServices runs the same pgvector/pgvector:pg16 image):
      subscription + READY + threshold move INSIDE the index-driven query,
      which makes retrieval exact over the caller-visible corpus, removes
      the k×4 over-fetch recall trade-off, and ELIMINATES the out-of-model
      recall-crowding density oracle instead of documenting it. (c) The
      spec row documents that per-scope tag_mode intentionally does not
      apply to semantic retrieval (out-of-model item 1, decided
      intentional). Named tests extended accordingly (crowding-recall IT
      case red under the superseded shape; ChatAgentTest cache-sharing pin).
    prior_values: |
      acceptance item 2 pinned: "runs a nearest-neighbour probe over
      post_embedding reusing LinkingJob's index-driving shape: an inner
      'SELECT ... ORDER BY pe.embedding <=> ?::vector LIMIT ?' (the
      HNSW-indexable distance-only ORDER BY) wrapped by a 'WHERE
      distance < ?' relevance threshold."
      acceptance item 5 had no TurnContext-sharing clause; item 6 named
      only IT cases (a)-(d) and ChatAgentTest "always invokes it".
      out_of_scope reranking bullet read "(ORDER BY embedding <=> ? — the
      LinkingJob shape)". Body §(b) described the same inner/outer shape.
  - date: 2026-07-11
    reason: >-
      budget-breach rework (user-directed refine, escalation menu option 1) —
      the new tool test is a DB-backed @QuarkusTest (boots DevServices
      pgvector), which docs/design/08-verification.md §8.2 defines as
      integration-shaped: it must be named *IT (failsafe phase), and
      infochat-core's IntegrationTestNamingGuardTest (M1-495 ratchet) fails
      the build for any NEW such class named *Test. The ticket named it
      SemanticSearchToolTest by mirroring the grandfathered (baselined)
      sibling SearchPostsToolTest. Renamed SemanticSearchToolTest ->
      SemanticSearchToolIT in files_scope, acceptance item 6, and
      test_plan.adds. No scope or intent change; the test's assertions
      (a)-(d) are unchanged.
    prior_values: |
      files_scope entry:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolTest.java
      acceptance item 6 opened "NAMED TESTS. SemanticSearchToolTest: ...";
      test_plan.adds cited .../tool/SemanticSearchToolTest.java.
  - date: 2026-07-11
    reason: >-
      clarity-fail rework (run self-refine) — stale spec_refs anchor. §5.4.5 in
      docs/design/05-llm-and-embeddings.md is "Summarizer (cluster mode)"; the
      Chat agent section is §5.4.6 (renumbered after a §5.4.4 Classifier section
      was inserted upstream). Corrected §5.4.5 -> §5.4.6 in the spec_refs entry
      and in the two body references (acceptance item 3 "... are updated" and the
      Spec-alignment paragraph "§5.4.5/§5.5"); the §5.5 Embeddings citation was
      already correct and is unchanged. Also deleted two stray leftover-transcript
      lines (</content>, </invoke>) at the file tail (clarity WARNING, file
      hygiene). No scope/intent change; files_scope, acceptance, and out_of_scope
      are untouched.
    prior_values: |
      spec_refs (changed entry):
        - docs/design/05-llm-and-embeddings.md §5.4.5 Chat agent
      body: acceptance item 3 read "...§5.4.5 are updated...";
            Spec-alignment paragraph read "...§5.4.5/§5.5...".
      file tail carried two stray markup lines: </content> and </invoke>.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-11
    category: DOS
    severity: low
    resolved: 2026-07-11 in-branch (spec §Failure handling amended to permit the bounded read-only pre-fetch + ChatAgent comment aligned; r3 audit CLEAN)
    promise: |
      "Chat-mode replies with the chat-agent LLM unreachable -> return a
      localized friendly error from the bundle (D43); the message never
      reaches the chat agent loop, no chat_session advance, no chat_memory
      write, no tool invocation."
      (docs/spec/security.md §Failure handling, provider-side LLM failures)
    gap: |
      (r2 audit) The deterministic semanticSearch pre-fetch executes BEFORE
      the chat LLM is resolved or called, so on the LLM-unreachable path a
      tool invocation (one embedding HTTP round-trip + one pgvector k-NN
      probe) has already run by the time the friendly error returns. The
      ChatAgent catch-block comment rewrote the commitment to "no tool
      invocation beyond what already ran before the failure" without the
      matching spec amendment — security.md gained the semanticSearch
      tool-table row but the §Failure handling bullet was not amended, so
      the shipped spec still promises zero tool work on this path. Impact
      bounded by the existing per-user rate buckets; defense-in-depth
      erosion of a stated failure-handling commitment.
    repro: |
      Route CHAT_AGENT at a remote provider; take it offline. Each
      chat-mode message returns the friendly unavailable error but first
      burns one embed HTTP call + one pgvector probe (bounded by
      statement_timeout / hnsw.max_scan_tuples). Pre-M1-589 behaviour
      matched the promise: zero tool work on an LLM-unavailable turn.
    suggested_fix_class: other
  - date: 2026-07-11
    category: DOS
    severity: low
    resolved: 2026-07-11 in-branch (one shared TurnContext across pre-fetch + tool loop — identical calls hit the cache, pre-fetch consumes a budget slot; r2 audit confirmed closed)
    promise: |
      "Tool calls per chat turn — fixed cap. Tool results are cached
      within a single turn so identical calls don't re-query."
      (docs/spec/security.md §Rate limiting)
    gap: |
      The deterministic per-turn semanticSearch pre-fetch dispatches
      through the 5-arg convenience overload, which creates a FRESH
      TurnContext per call, while runToolLoop creates its own separate
      TurnContext for the same chat turn. The pre-fetch's result is
      cached in a context that is immediately discarded, so an
      identical model-initiated semanticSearch call in the same turn
      misses the cache and re-executes (a second embedding HTTP
      round-trip plus a second pgvector k-NN probe). The pre-fetch also
      does not consume a slot of the loop's per-turn call budget, so
      the effective per-turn execution bound is cap+1, not the single
      fixed cap the spec describes. Impact bounded (exactly one
      duplicate embed+probe per turn) — defense-in-depth erosion, not
      unbounded amplification.
    repro: |
      Send chat message M (≤500 chars). doHandle step 3 dispatches
      semanticSearch{query=M} (embed + probe #1). The model emits
      TOOL_CALL: semanticSearch with the byte-identical arg map; the
      loop's fresh TurnContext cache is empty, so the dispatcher
      executes embed + probe #2 in the same turn.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-11
    verdict: CLEAN
    base: "merge-base(main, m1/M1-589-chat-semantic-retrieval-general-assistant) = 09aab129"
    head: "working tree of m1/M1-589-chat-semantic-retrieval-general-assistant (post-r3 failure-handling alignment, pre-commit)"
    verdict_file: docs/plan/m1/redteam/M1-589-2026-07-11-r3.md
    out_of_model_count: 3
    note: |
      Third audit: CLEAN. Both prior findings confirmed closed (r1
      TurnContext DOS via shared per-turn context; r2 failure-handling
      spec-drift via the §Failure-handling amendment + aligned ChatAgent
      comment). Three out-of-model advisories, all bounded / not
      adversary-reachable / product decisions: (1) general-assistant
      framing = the ticket's intended design, rate-capped, no exfil path
      (product/cost decision — a topic gate or tighter LLM budget would be
      a separate call); (2) ivfflat future density side-channel — already
      documented HNSW-scoped in the diff, re-audit if ivfflat un-deferred;
      (3) the pre-fetch embed HTTP call is bounded by
      infochat.embeddings.timeout-ms, not statement_timeout (which caps only
      the probe) — the "bounded" claim holds (trusted local backend D54 +
      per-user rate cap), the citation is imprecise; folded into the M1-606
      circuit-breaker follow-up (which revisits §Failure handling), not
      re-opened here at round_cap 3 over an advisory.
  - date: 2026-07-11
    verdict: FINDINGS
    base: "merge-base(main, m1/M1-589-chat-semantic-retrieval-general-assistant) = 09aab129"
    head: "working tree of m1/M1-589-chat-semantic-retrieval-general-assistant (post-r2-remediation, pre-commit)"
    verdict_file: docs/plan/m1/redteam/M1-589-2026-07-11-r2.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Re-run after the r1 remediation: the r1 TurnContext finding is
      CLOSED (not re-flagged); the crowding density oracle is eliminated
      on HNSW (v1-shipped everywhere). One NEW low DOS/spec-drift finding:
      the always-run pre-fetch runs before the LLM call, so the
      LLM-unreachable path burns one embed+probe while security.md
      §Failure handling still promises "no tool invocation" — the bullet
      needs the spec amendment the architecture change implies. Out-of-
      model: iterative-scan leak-freedom is HNSW-scoped (ivfflat GUC has
      no strict_order); v1 ships HNSW on every profile — design-note
      scoping caveat suffices.
  - date: 2026-07-11
    verdict: FINDINGS
    base: "merge-base(main, m1/M1-589-chat-semantic-retrieval-general-assistant) = 09aab129"
    head: "working tree of m1/M1-589-chat-semantic-retrieval-general-assistant (pre-commit audit)"
    verdict_file: docs/plan/m1/redteam/M1-589-2026-07-11.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Pre-commit audit halted the run (run.md step 5). One low DOS
      finding: the pre-fetch's fresh TurnContext discards the per-turn
      cache entry and bypasses the call budget (bound cap+1). Fix class:
      share one TurnContext between the pre-fetch dispatch and
      runToolLoop (both ChatAgent-internal — no external callers).
      Out-of-model: tag_mode omission (decide + document) and
      recall-crowding density oracle (v1-acceptance note) — advisory.
clarity_check:
  date: 2026-07-11
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-589.md
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
- runs the k-NN probe as a SINGLE filtered query driven by a pgvector
  **iterative index scan** (`SET LOCAL hnsw.iterative_scan = strict_order` on
  the armed connection — it joins the SET LOCAL transaction armToolConnection
  already opens; strict_order keeps D19's exact deterministic order): the
  subscription predicate (`source_id IN (SELECT source_id FROM
  source_subscription WHERE scope_kind = ? AND scope_id = ?)`),
  `status = 'READY'`, and the `(pe.embedding <=> ?::vector) < ?` relevance
  threshold all sit INSIDE the index-driven `ORDER BY pe.embedding <=> ?::vector
  LIMIT ?` query, with a deterministic outer re-sort (distance ASC, post_id
  ASC). The scan walks the HNSW index until LIMIT rows survive the filters
  (bounded by hnsw.max_scan_tuples, default 20k — the live corpus is ~5.3k
  rows), so retrieval is exact over the caller-visible corpus: no global-top-k
  over-fetch, no recall dependence on unsubscribed-content density,
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
in `docs/design/05-llm-and-embeddings.md` §5.4.6/§5.5, and the widened tool
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
