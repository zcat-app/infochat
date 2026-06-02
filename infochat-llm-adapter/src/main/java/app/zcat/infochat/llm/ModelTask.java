package app.zcat.infochat.llm;

/**
 * The closed set of LLM call shapes the v1 pipeline issues. The router
 * signature {@code (ModelTask, scope_language) → LlmProvider} is part of
 * the SPI contract (docs/spec/llm.md §SPI shape); adding a value here
 * widens that contract and requires a spec amendment.
 *
 * <h2>Scope of the enum</h2>
 *
 * <p>The embedder is deliberately NOT a {@code ModelTask}. Embedding has
 * its own SPI ({@link EmbeddingProvider}), its own provider selection,
 * and its own lifecycle (model-identity guard, dimensionality invariant)
 * — routing it through the same enum would conflate two unrelated
 * lifecycles. See docs/spec/llm.md §SPI shape "Scope of the enum".</p>
 *
 * <p>{@link #TRANSLATOR} is a {@code ModelTask} because the LLM-backed
 * translation path uses an {@link LlmProvider} call; the higher-level
 * {@code TranslationProvider} SPI (presentation-layer concern, lives in
 * {@code infochat-messaging-adapter}) is a different surface that may
 * dispatch to this task internally.</p>
 */
public enum ModelTask {
    SECURITY_JUDGE("security"),
    TAGGER("tagger"),
    ENTITY("entity"),
    SUMMARIZER("summarizer"),
    CHAT_AGENT("chat"),
    TRANSLATOR("translator");

    private final String keySegment;

    ModelTask(String keySegment) {
        this.keySegment = keySegment;
    }

    /**
     * The operator-facing config-key segment for this task: the
     * {@code <seg>} in {@code infochat.llm.<seg>.<property>} (e.g.
     * {@code SECURITY_JUDGE} → {@code security}, abbreviating the enum
     * name; {@code CHAT_AGENT} → {@code chat}). Single source of truth
     * so the router, the per-provider config readers, and the startup
     * guard cannot drift in how they spell the same task's keys.
     */
    public String keySegment() {
        return keySegment;
    }
}
