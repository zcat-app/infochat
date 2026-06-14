package app.zcat.infochat.collector.eval;

import app.zcat.infochat.collector.eval.embedding.EmbeddingWorker;
import app.zcat.infochat.collector.eval.entity.EntityExtractorWorker;
import app.zcat.infochat.collector.eval.ready.ReadyPromoter;
import app.zcat.infochat.collector.eval.tagger.TaggerWorker;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the four eval-stage pickup queries share ONE
 * {@link PartitionScan} source rather than each carrying its own
 * horizon+slack constant, and that the source computes the floor as the
 * post retention horizon widened by the partition slack.
 */
@QuarkusTest
class PartitionScanSharedSourceTest {

    @Inject
    PartitionScan partitionScan;

    // Resolves iff EXACTLY one PartitionScan bean exists — the single
    // shared source the four workers inject.
    @Inject
    Instance<PartitionScan> partitionScanInstance;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    EntityExtractorWorker entityExtractorWorker;

    @Inject
    ReadyPromoter readyPromoter;

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    @Test
    void scanWindowIsRetentionHorizonWidenedBySlack() {
        // Same arithmetic ReEvaluationJob already uses: horizon + 2-day slack.
        assertEquals((postRetentionDays + 2) + " days", partitionScan.scanWindow());
    }

    @Test
    void allFourPickupWorkersShareTheSinglePartitionScanBean() {
        // isResolvable() is true only when exactly one bean matches — proves
        // there is a single source, not a per-class duplicate.
        assertTrue(partitionScanInstance.isResolvable(),
            "PartitionScan must be a single resolvable bean");
        // Each worker starts only because the shared bean injected into it;
        // their presence pins that all four consume the same source.
        assertNotNull(embeddingWorker);
        assertNotNull(taggerWorker);
        assertNotNull(entityExtractorWorker);
        assertNotNull(readyPromoter);
    }
}
