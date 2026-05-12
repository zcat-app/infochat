package io.infochat.llm;

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
    SECURITY_JUDGE,
    TAGGER,
    ENTITY,
    SUMMARIZER,
    CHAT_AGENT,
    TRANSLATOR
}
