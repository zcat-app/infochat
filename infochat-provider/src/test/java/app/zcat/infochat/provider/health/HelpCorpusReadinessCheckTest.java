package app.zcat.infochat.provider.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.provider.help.CommandIntentIndex;
import app.zcat.infochat.provider.help.HelpTopicCorpus;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/** Pins the help-corpora readiness check: ALWAYS UP — an absent embedding
 * backend is a supported degraded mode (deployment.md §Bootstrap behavior on startup);
 * data is exactly one boolean per reported corpus (security.md §Trust boundaries item 6). */
class HelpCorpusReadinessCheckTest {

    /** The reproduction: a failed corpus build surfaces as a per-corpus
     * degraded entry while the check stays UP — before this ticket the outcome
     * stopped at the ERROR log line (deployment.md §Bootstrap behavior on startup). */
    @Test
    void failedCorpusBuildSurfacesAsInformationalReadinessData() {
        HelpCorpusBuildState state = new HelpCorpusBuildState();
        state.reportFailed(CommandIntentIndex.DOC_KIND);
        state.reportFailed(HelpTopicCorpus.DOC_KIND);

        HealthCheckResponse response = HelpCorpusReadinessCheck.evaluate(state.snapshot());

        assertEquals("help-corpora", response.getName(),
                "the corpus-build outcome rides its own named check, never folded"
                        + " into messaging-adapters (whose data map is pinned to"
                        + " adapter semantics)");
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "a failed corpus build must NOT fail readiness — the degraded mode"
                        + " is supported (docs/spec/deployment.md §Bootstrap behavior"
                        + " on startup); failing readiness here would convert a"
                        + " chat-tier convenience outage into a total Provider outage");
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals(Boolean.FALSE, data.get(CommandIntentIndex.DOC_KIND),
                "the failed command_intent build is visible as a degraded entry");
        assertEquals(Boolean.FALSE, data.get(HelpTopicCorpus.DOC_KIND),
                "the failed topic build is visible as a degraded entry");
    }

    /** Shape pin (M1-818 P2, P8): exactly the reported corpus keys, boolean values — never
     * exception text; an empty snapshot yields no data and UP, never an invented
     * pending key (security.md §Trust boundaries item 6). */
    @Test
    void payloadCarriesExactlyTheReportedCorpusOutcomes() {
        HelpCorpusBuildState state = new HelpCorpusBuildState();
        state.reportBuilt(CommandIntentIndex.DOC_KIND);
        state.reportFailed(HelpTopicCorpus.DOC_KIND);

        HealthCheckResponse response = HelpCorpusReadinessCheck.evaluate(state.snapshot());

        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals(Set.of(CommandIntentIndex.DOC_KIND, HelpTopicCorpus.DOC_KIND),
                data.keySet(),
                "the data map must carry exactly the reported corpus keys — any"
                        + " other key widens the unauthenticated payload");
        assertEquals(Boolean.TRUE, data.get(CommandIntentIndex.DOC_KIND),
                "each corpus key maps to its build-outcome boolean");
        assertEquals(Boolean.FALSE, data.get(HelpTopicCorpus.DOC_KIND),
                "each corpus key maps to its build-outcome boolean");
    }

    @Test
    void emptySnapshotYieldsNoDataAndUp() {
        HealthCheckResponse response = HelpCorpusReadinessCheck.evaluate(Map.of());

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "the holder starts empty; the check must be honest in that state"
                        + " — readiness itself is gated on startup completion by"
                        + " the priority<500 rule (docs/design/01-architecture.md"
                        + " §1.4.3), so no invented pending key is needed");
        assertTrue(response.getData().isEmpty(),
                "an empty snapshot contributes no data entries");
    }
}
