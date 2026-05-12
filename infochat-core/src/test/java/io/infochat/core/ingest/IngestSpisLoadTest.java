package io.infochat.core.ingest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: verifies the three ingest SPI types compile and are
 * loadable on the infochat-core classpath with the kinds the spec
 * commits to (interface for Fetcher / StreamSource, record for
 * NormalizedPost). No behavior is exercised here — that lives with
 * the impl-side tickets that will land concrete Fetchers and
 * StreamSources. The cross-module load test (Fetcher + LLM SPIs +
 * Messaging SPIs all visible from the same classpath) is the M1-007
 * umbrella commit's verification surface, not this one.
 */
class IngestSpisLoadTest {

    @Test
    void fetcherIsLoadableInterface() throws ClassNotFoundException {
        Class<?> type = Class.forName("io.infochat.core.ingest.Fetcher");
        assertNotNull(type);
        assertTrue(type.isInterface(), "Fetcher must be an interface");
    }

    @Test
    void streamSourceIsLoadableInterface() throws ClassNotFoundException {
        Class<?> type = Class.forName("io.infochat.core.ingest.StreamSource");
        assertNotNull(type);
        assertTrue(type.isInterface(), "StreamSource must be an interface");
    }

    @Test
    void normalizedPostIsLoadableRecord() throws ClassNotFoundException {
        Class<?> type = Class.forName("io.infochat.core.ingest.NormalizedPost");
        assertNotNull(type);
        assertTrue(type.isRecord(), "NormalizedPost must be a record");
    }
}
