package app.zcat.infochat.collector.stream;

import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test proving the supervisor is a live CDI bean that starts
 * as part of the Collector lifecycle (@Startup @Priority(450),
 * @PostConstruct ran → ready) and drains a registered source in-container
 * using the injected profile-driven {@code infochat.stream.drain-timeout}.
 */
@QuarkusTest
class StreamSourceSupervisorIT {

    @Inject
    StreamSourceSupervisor supervisor;

    @Test
    void supervisorIntegratesWithCollectorLifecycle() throws InterruptedException {
        assertTrue(supervisor.isReady(), "CDI-managed supervisor is ready after Collector startup");

        StreamDispatchKey dispatchKey = new StreamDispatchKey(4242L);
        List<NormalizedPost> buffered = List.of(FakeStreamSource.samplePost("it-event"));
        FakeStreamSource source = FakeStreamSource.flushingOnStop(buffered);
        List<NormalizedPost> delivered = new CopyOnWriteArrayList<>();

        supervisor.register(dispatchKey, "spec", source, delivered::add);
        try {
            assertTrue(source.startEntered.await(2, TimeUnit.SECONDS), "worker started in-container");

            Map<StreamDispatchKey, Boolean> outcomes = supervisor.drainAll(Duration.ofSeconds(5));

            assertEquals(Boolean.TRUE, outcomes.get(dispatchKey), "source flushed during drain");
            assertEquals(buffered.size(), delivered.size(), "buffered event flushed to the deliver callback");
        } finally {
            // Remove the test registration so the @PreDestroy shutdown drain
            // does not re-run against it.
            supervisor.stop(dispatchKey);
        }
    }
}
