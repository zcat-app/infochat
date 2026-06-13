package app.zcat.infochat.collector;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QuarkusBootstrapTest {

    @Test
    void contextStarts() {
        // Collector boot canary: a @QuarkusTest only reaches this body if the
        // container started, so the booting itself is the proof. The explicit
        // ArC-running assertion states that plainly instead of leaving an empty
        // body — booting alone proves the collector's CDI wiring, config
        // resolution, and Dev Services datasource came up without error.
        assertTrue(Arc.container().isRunning(),
                "Quarkus ArC container must be running after collector bootstrap");
    }
}
