package app.zcat.infochat.collector.eval.embedding;

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
 * row <em>and embeddings already exist</em>, startup is refused with a
 * descriptive error referencing the re-embed procedure." With no
 * embeddings yet the guard adopts the configured identity instead — see
 * {@link #evaluate} for the adopt-vs-enforce split.
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
 * M1-033's {@link app.zcat.infochat.llm.routing.LlmRouterStartupGuard}
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
     * The {@code @Priority} value for this guard, documented as a public
     * constant alongside the literal class-level {@code @Priority(125)}
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
        evaluate(dao.readSingleton(), configuredModel, configuredDimension, allowModelChange,
            dao.hasEmbeddings());
    }

    /**
     * Apply the model-identity guard against a hand-supplied stored
     * row + configured values + the post_embedding-emptiness signal.
     * Package-visible so {@code ReadyPromoterIT} can exercise the
     * adopt-on-first-boot, fail-fast, allow-model-change, and no-op
     * paths from a single @QuarkusTest @Test method without
     * re-bootstrapping Quarkus. Production calls flow through
     * {@link #onStartup()} which delegates here after reading from
     * {@link EmbeddingMetadataDao} and {@link ConfigProperty}.
     *
     * <p><strong>Adopt-vs-enforce (M1-443).</strong> The guard exists to
     * stop a <em>mid-deployment</em> model change from mixing
     * incompatible vectors in the fixed-width pgvector column. That
     * hazard only exists once vectors are stored, so {@code hasEmbeddings}
     * splits the behaviour: with <em>no</em> embeddings a model-identity
     * mismatch is harmless — there is nothing to be incompatible with —
     * so the guard ADOPTS the configured identity (rotates the singleton,
     * no re-embed required) and starts. This is what makes the spec's
     * "stored … on first use" ({@code docs/spec/llm.md} §Embedding
     * pipeline) true for backends whose configured identifier differs
     * from V11's seeded Ollama default (e.g. a llama.cpp deployment whose
     * GGUF filename is its model identity). Once embeddings exist the
     * original fatal refusal stands unless {@code allow-model-change=true}.
     *
     * <p>Side effect on the adopt and allow-model-change paths: this
     * method invokes {@link EmbeddingMetadataDao#updateSingleton(String,
     * int)} to rotate the singleton row. The test must read the stored
     * row before AND after to assert the rotation actually fired.
     */
    public void evaluate(Optional<EmbeddingMetadataDao.Metadata> stored,
                         String configuredModelValue,
                         int configuredDimensionValue,
                         boolean allowModelChangeValue,
                         boolean hasEmbeddings) {
        if (stored.isEmpty()) {
            if (hasEmbeddings) {
                // Embeddings exist but the identity row is gone — a
                // hand-cleaned DB or a seed-removing migration. The
                // model that produced the stored vectors is now
                // unknowable, so the guard cannot prove the configured
                // model matches them. This is the dangerous case the
                // empty-singleton fatal branch always covered; it stays
                // fatal, now gated on vectors actually existing.
                String fatal = "EmbeddingMetadataStartupGuard: embedding_metadata is empty but "
                    + "post_embedding is non-empty. The model identity of the already-stored "
                    + "vectors is unknown, so the configured model cannot be proven compatible. "
                    + "Re-embed procedure: " + REEMBED_PROCEDURE_PATH
                    + ". Refusing Collector startup.";
                LOG.fatal(fatal);
                throw new EmbeddingModelMismatchException(fatal);
            }
            // No identity row and no vectors. V11 seeds a row at first
            // Flyway run, so this state is reachable only via a
            // hand-cleaned DB; with zero vectors there is nothing to
            // protect, so permit startup. The identity is recorded on
            // first use by the embedding pipeline.
            LOG.warnf(
                "EmbeddingMetadataStartupGuard: embedding_metadata is empty and post_embedding "
                    + "has no rows — permitting startup; the configured identity "
                    + "(model=%s dimension=%d) is recorded on first use.",
                configuredModelValue, configuredDimensionValue);
            return;
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

        if (!hasEmbeddings) {
            // First-boot adopt: the singleton holds V11's seeded default
            // but the configured backend reports a different identity,
            // and no vectors exist yet. Rotate the singleton to the
            // configured identity and start — nothing was embedded, so
            // no re-embed is required. WARN (not INFO) so the recorded
            // identity is visible in the operator log.
            LOG.warnf(
                "EmbeddingMetadataStartupGuard: recording embedding model identity on first use — "
                    + "rotating embedding_metadata: old=(model=%s dimension=%d) → "
                    + "new=(model=%s dimension=%d). post_embedding is empty, so no re-embed "
                    + "is required.",
                storedMeta.modelIdentifier(), storedMeta.dimension(),
                configuredModelValue, configuredDimensionValue);
            dao.updateSingleton(configuredModelValue, configuredDimensionValue);
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
     * {@code @Startup} bean's {@code @PostConstruct} re-throws this, which
     * Quarkus treats as a fatal startup error and refuses to start.
     */
    public static final class EmbeddingModelMismatchException extends RuntimeException {
        public EmbeddingModelMismatchException(String message) {
            super(message);
        }
    }
}
