package io.infochat.provider.spi;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Umbrella cross-module SPI load test for the M1-007 group.
 *
 * <p>The three subtickets (M1-007a / -b / -c) each verify, per-module, that their
 * own SPI types compile and load. This umbrella IT verifies the property no
 * subticket can prove in isolation: that all fourteen SPI types and supporting
 * records/enums from infochat-core + infochat-llm-adapter +
 * infochat-messaging-adapter are simultaneously visible on Provider's
 * classpath. Provider transitively depends on all three modules, so a single
 * IT here is sufficient — Collector's view is covered by the per-module
 * smoke tests.
 *
 * <p>Plain JUnit, not {@code @QuarkusTest}: this asserts classpath visibility,
 * not bean wiring. Spinning up the Quarkus context would slow the test and
 * could mask a real classpath issue behind an unrelated Quarkus startup
 * failure.
 *
 * <p>The {@code *IT} suffix reserves this test for {@code mvn verify} from the
 * repo root, which is the scope where "all three modules are on the classpath"
 * is the meaningful question; Failsafe (configured in
 * {@code infochat-provider/pom.xml}) executes it in the {@code integration-test}
 * phase.
 */
class AllSpisLoadIT {

    // Three categories of SPI types from the three new modules. Each list
    // is exhaustive for its category at M1-007 umbrella time; growing the
    // SPI surface is an impl-ticket concern, not an umbrella concern.
    private static final List<String> INTERFACE_FQNS = List.of(
            "io.infochat.core.ingest.Fetcher",
            "io.infochat.core.ingest.StreamSource",
            "io.infochat.llm.LlmProvider",
            "io.infochat.llm.EmbeddingProvider",
            "io.infochat.messaging.MessagingAdapter",
            "io.infochat.messaging.TranslationProvider",
            "io.infochat.messaging.ProgressNotifier");

    private static final List<String> RECORD_FQNS = List.of(
            "io.infochat.core.ingest.NormalizedPost",
            "io.infochat.llm.LlmResponse",
            "io.infochat.llm.EmbeddingResult",
            "io.infochat.messaging.MessageHandle",
            "io.infochat.messaging.CapabilityFlags");

    private static final List<String> ENUM_FQNS = List.of(
            "io.infochat.llm.ModelTask",
            "io.infochat.messaging.ProgressStage");

    @Test
    void allFourteenSpiTypesLoadAndMatchExpectedKind() throws ClassNotFoundException {
        // Sanity-check the category totals so a future drive-by edit cannot
        // silently shrink the surface (e.g. drop a record from the list and
        // claim "still passes"). The umbrella verifies fourteen types — if
        // the SPI surface evolves, those changes route through new tickets,
        // not through quiet edits here.
        assertTrue(INTERFACE_FQNS.size() + RECORD_FQNS.size() + ENUM_FQNS.size() == 14,
                "M1-007 umbrella covers exactly fourteen SPI types");

        for (String fqn : INTERFACE_FQNS) {
            Class<?> type = Class.forName(fqn);
            assertNotNull(type, fqn + " must be loadable");
            assertTrue(type.isInterface(), fqn + " must be an interface");
        }
        for (String fqn : RECORD_FQNS) {
            Class<?> type = Class.forName(fqn);
            assertNotNull(type, fqn + " must be loadable");
            assertTrue(type.isRecord(), fqn + " must be a record");
        }
        for (String fqn : ENUM_FQNS) {
            Class<?> type = Class.forName(fqn);
            assertNotNull(type, fqn + " must be loadable");
            assertTrue(type.isEnum(), fqn + " must be an enum");
        }
    }
}
