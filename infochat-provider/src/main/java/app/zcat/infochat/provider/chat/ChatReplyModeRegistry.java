package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * The bar-clearing gate for native chat-reply mode (decision D79):
 * which (model, language) pairs a scope may generate in. A pair clears
 * only with a committed grounded in-language measurement record behind
 * it — the registry seeds from
 * {@code docs/measurement/direct-chat-e2e.md} §Bar-clearing matrix,
 * whose current end-state is the M1-858 restatement: the gemma
 * re-measure cleared cs/ru/tr; en and es still FAIL on citation-defect
 * L0 events, so those pairs stay out.
 *
 * <p>The set ships as a code constant, the {@code LanguageRegistry}
 * posture: never an operator key and never the router's operator-
 * declared {@code languages} capability key (D79, analysis P8) — either
 * could opt an unmeasured model into native generation and reopen the
 * whole-turn language-collapse class with no mechanical net to catch it
 * (native mode's only controls are this gate and the record it cites).
 * Clearing a pair is a ticketed, reviewed code change with the
 * measurement record named in the commit.
 */
@ApplicationScoped
public class ChatReplyModeRegistry {

    /** A (model, language) pair cleared for native generation. */
    public record ClearedPair(String model, String language) {}

    // Seeded from the record's RESTATED matrix (M1-858): gemma-4-26b-a4b
    // × cs/ru/tr clear the bar; en/es FAIL. Model id = the deployment's
    // infochat.llm.chat.model value for the gemma GGUF.
    private static final Set<ClearedPair> SHIPPED_CLEARED_PAIRS = Set.of(
            new ClearedPair("gemma-4-26b-a4b", "cs"),
            new ClearedPair("gemma-4-26b-a4b", "ru"),
            new ClearedPair("gemma-4-26b-a4b", "tr"));

    private final Set<ClearedPair> clearedPairs;

    /** Production registry: the shipped code constant. */
    public ChatReplyModeRegistry() {
        this(SHIPPED_CLEARED_PAIRS);
    }

    /** Test seam: a registry seeded with a fixture matrix. */
    public ChatReplyModeRegistry(Set<ClearedPair> clearedPairs) {
        this.clearedPairs = clearedPairs;
    }

    /** Whether the (model, language) pair may run native generation. */
    public boolean clears(String model, String language) {
        return clearedPairs.contains(new ClearedPair(model, language));
    }

    /** The shipped cleared (model, language) pairs. */
    public Set<ClearedPair> clearedPairs() {
        return clearedPairs;
    }
}
