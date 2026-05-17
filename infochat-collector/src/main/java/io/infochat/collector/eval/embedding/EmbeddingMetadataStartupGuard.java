package io.infochat.collector.eval.embedding;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Collector-side {@code @Startup} bean enforcing the embedding model
 * identity guard per {@code docs/spec/llm.md} §Embedding pipeline:
 * "On every startup the EmbeddingProvider reports its current
 * identifier and dimensionality; if either differs from the stored
 * row, startup is refused with a descriptive error referencing the
 * re-embed procedure."
 *
 * <h2>Why this guard exists</h2>
 * <p>Dimensionality mismatch silently corrupts cosine similarity
 * scores (the pgvector column is fixed-width; storing vectors of
 * mixed dimensions in the same column produces nonsense distances
 * that no query plan can detect). The only safe recovery from a
 * mid-deployment model change is a full re-embed (see
 * {@code docs/design/02-schema.md} §2.8). This guard fires the
 * forcing function at startup so the failure mode is reachable only
 * via explicit operator intent.
 *
 * <h2>Operator-override path</h2>
 * <p>Per {@code docs/spec/llm.md} §Embedding pipeline: "An explicit
 * operator override flag bypasses the check for intentional migration
 * runs; its property key and semantics are in design notes." The flag
 * is {@code infochat.embeddings.allow-model-change=true}. When set:
 * <ol>
 *   <li>The guard does NOT refuse startup on mismatch.</li>
 *   <li>It rotates the {@code embedding_metadata} singleton row to
 *       the new {@code (model, dimension)} pair via
 *       {@link EmbeddingMetadataDao#updateSingleton(String, int)}.</li>
 *   <li>It logs WARN naming the rotation (old → new) so the
 *       operator can see the change in the audit trail.</li>
 * </ol>
 *
 * <h2>Startup-bean priority ordering</h2>
 * <p>{@code @Priority(125)} — strictly between Flyway (100) and
 * M1-033's {@link io.infochat.llm.routing.LlmRouterStartupGuard}
 * (150). The model identity check must run AFTER Flyway (V11's seed
 * row from M1-034a must exist before this guard reads it) and
 * BEFORE the LLM router guard (model identity is the most
 * fundamental data invariant; the router config is operator
 * preference — surface the data-integrity error first for
 * operator-debugging clarity).
 */
@Startup
@Priority(125)
@ApplicationScoped
public class EmbeddingMetadataStartupGuard {

    /**
     * @Priority value for this guard, documented as a public
     * constant alongside the literal class-level @Priority(125)
     * annotation above. The annotation uses the literal so a
     * reviewer-side regex over the annotation source line sees
     * the bare integer; this constant exists so other code (the
     * LlmRouterStartupGuard's neighbour, or any future
     * priority-aware test) can reference the value without
     * re-typing the magic number.
     */
    public static final int PRIORITY_BETWEEN_FLYWAY_AND_ROUTER = 125;

    /** Operator-facing property: master switch for the override path. */
    public static final String CONFIG_KEY_ALLOW_MODEL_CHANGE =
        "infochat.embeddings.allow-model-change";

    /** Path to the re-embed procedure documentation, embedded in fatal log lines. */
    public static final String REEMBED_PROCEDURE_PATH = "docs/design/02-schema.md §2.8";

    private static final Logger LOG = Logger.getLogger(EmbeddingMetadataStartupGuard.class);

    @Inject
    EmbeddingMetadataDao dao;

    @ConfigProperty(name = "infochat.embeddings.model")
    String configuredModel;

    @ConfigProperty(name = "infochat.embeddings.dimension")
    int configuredDimension;

    @ConfigProperty(name = CONFIG_KEY_ALLOW_MODEL_CHANGE, defaultValue = "false")
    boolean allowModelChange;

    @PostConstruct
    void onStartup() {
        evaluate(dao.readSingleton(), configuredModel, configuredDimension, allowModelChange);
    }

    /**
     * Apply the model-identity guard against a hand-supplied stored
     * row + configured values. Package-private so {@code
     * ReadyPromoterIT} can exercise both the fail-fast and the
     * allow-model-change paths from a single @QuarkusTest @Test method
     * without re-bootstrapping Quarkus. Production calls flow through
     * {@link #onStartup()} which delegates here after reading from
     * {@link EmbeddingMetadataDao} and {@link ConfigProperty}.
     *
     * <p>Side effect on the allow-model-change path: this method
     * invokes {@link EmbeddingMetadataDao#updateSingleton(String, int)}
     * to rotate the singleton row. The test must read the stored row
     * before AND after to assert the rotation actually fired.
     *
     * <p>Public so the cross-package test
     * {@code ReadyPromoterIT} (in {@code io.infochat.collector.eval.ready})
     * can drive both paths without re-bootstrapping Quarkus.
     */
    public void evaluate(Optional<EmbeddingMetadataDao.Metadata> stored,
                         String configuredModelValue,
                         int configuredDimensionValue,
                         boolean allowModelChangeValue) {
        if (stored.isEmpty()) {
            // V11's seed INSERT should guarantee a row by the time
            // this @Priority(125) guard runs (Flyway @Priority is
            // earlier). An empty result here means a hand-cleaned
            // DB or a future migration that removed the seed —
            // either way the guard cannot make a safety
            // determination, so refuse startup with a descriptive
            // error.
            String fatal = "EmbeddingMetadataStartupGuard: embedding_metadata is empty. "
                + "V11 should seed one row at first Flyway run; ensure the migration "
                + "has applied. Re-embed procedure: " + REEMBED_PROCEDURE_PATH
                + ". Refusing Collector startup.";
            LOG.fatal(fatal);
            throw new EmbeddingModelMismatchException(fatal);
        }

        EmbeddingMetadataDao.Metadata storedMeta = stored.get();
        boolean modelMatches = storedMeta.modelIdentifier().equals(configuredModelValue);
        boolean dimensionMatches = storedMeta.dimension() == configuredDimensionValue;
        if (modelMatches && dimensionMatches) {
            LOG.infof(
                "EmbeddingMetadataStartupGuard: model identity OK (model=%s dimension=%d)",
                storedMeta.modelIdentifier(), storedMeta.dimension());
            return;
        }

        if (allowModelChangeValue) {
            // Operator-override path: rotate the singleton and log
            // WARN with the old→new pair so the change is visible.
            LOG.warnf(
                "EmbeddingMetadataStartupGuard: %s=true — rotating embedding_metadata: "
                    + "old=(model=%s dimension=%d) → new=(model=%s dimension=%d). "
                    + "Operator must run the re-embed procedure (%s) to refresh post_embedding rows.",
                CONFIG_KEY_ALLOW_MODEL_CHANGE,
                storedMeta.modelIdentifier(), storedMeta.dimension(),
                configuredModelValue, configuredDimensionValue,
                REEMBED_PROCEDURE_PATH);
            dao.updateSingleton(configuredModelValue, configuredDimensionValue);
            return;
        }

        // Fatal path: descriptive error naming stored, configured,
        // and the re-embed procedure per the spec.
        String fatal = "EmbeddingMetadataStartupGuard: embedding model identity mismatch. "
            + "stored=(model=" + storedMeta.modelIdentifier()
            + " dimension=" + storedMeta.dimension() + ") "
            + "configured=(model=" + configuredModelValue
            + " dimension=" + configuredDimensionValue + "). "
            + "Set " + CONFIG_KEY_ALLOW_MODEL_CHANGE + "=true to rotate "
            + "(and run the re-embed procedure: " + REEMBED_PROCEDURE_PATH + "). "
            + "Refusing Collector startup.";
        LOG.fatal(fatal);
        throw new EmbeddingModelMismatchException(fatal);
    }

    /**
     * Thrown when the configured embedding model differs from the
     * stored singleton row AND
     * {@code infochat.embeddings.allow-model-change} is not set. The
     * @Startup bean's @PostConstruct re-throws this, which Quarkus
     * treats as a fatal startup error and refuses to start.
     */
    public static final class EmbeddingModelMismatchException extends RuntimeException {
        public EmbeddingModelMismatchException(String message) {
            super(message);
        }
    }
}
