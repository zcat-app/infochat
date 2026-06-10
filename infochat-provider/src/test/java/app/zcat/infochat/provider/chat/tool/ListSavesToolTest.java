package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link ListSavesTool}'s /stop arming: the
 * single pooled connection it opens per call gets the profile-driven
 * {@code statement_timeout} applied and its Postgres backend pid
 * registered on the in-flight cancellation handle, so an in-flight
 * listSaves query is bounded and cancellable by /stop. Runs against the
 * &#64;QuarkusTest DevServices DB.
 */
@QuarkusTest
class ListSavesToolTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

    @Test
    void listSavesArmsTimeoutAndRegistersPid() throws Exception {
        // Construct the tool against a counting/recording DataSource that
        // delegates to the seed DB, plus the CDI CancellationService (whose
        // InFlightTracker is the injected singleton). The query runs for real
        // (no saved_post row need exist); the wrapper observes the SET
        // statement_timeout and pg_backend_pid the arming step issues.
        UUID userId = UUID.randomUUID();
        CountingRecordingDataSource countingDs = new CountingRecordingDataSource(dataSource);
        ListSavesTool directTool = new ListSavesTool(countingDs, cancellationService);

        // Hold the in-flight slot as ChatAgent.handle() does for a chat turn,
        // so the tool has a handle to register the backend pid on.
        InFlightTracker.CancellationHandle slot =
                Objects.requireNonNull(inFlightTracker.tryAcquire(userId, "dm", userId));
        try {
            directTool.execute(userId, "dm", userId, Map.of());

            assertTrue(countingDs.executedSql().stream()
                            .anyMatch(s -> s.contains("SET LOCAL statement_timeout")),
                    "listSaves's connection must have statement_timeout applied. Got: "
                            + countingDs.executedSql());
            assertTrue(slot.hasPgBackendPid(),
                    "listSaves must register the connection's pg backend pid on the in-flight handle");
        } finally {
            inFlightTracker.release(userId, "dm", userId, slot);
        }
    }
}
