package io.infochat.collector.flyway;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots the full Quarkus app against a DevServices-managed Postgres and
 * asserts that Flyway has applied the V1 bootstrap migration. This is the
 * end-to-end proof that the scaffolding wired in M1-005 actually migrates
 * a fresh DB on startup; later migration tickets (M1-008 umbrella) extend
 * this baseline.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin (see
 * {@code infochat-collector/pom.xml}) so this test runs in the verify phase
 * and surefire-test-phase reports stay focused on unit-scoped tests.
 */
@QuarkusTest
class FlywayMigrationIT {

    @Inject
    Flyway flyway;

    @Test
    void v1IsAppliedSuccessfully() {
        assertNotNull(flyway, "Flyway must be injectable as a CDI bean when quarkus-flyway is on the classpath");
        MigrationInfo[] applied = flyway.info().applied();
        for (MigrationInfo info : applied) {
            if ("1".equals(info.getVersion().getVersion())) {
                assertEquals(MigrationState.SUCCESS, info.getState(),
                    "V1 must be in SUCCESS state; was: " + info.getState());
                return;
            }
        }
        fail("Migration V1 was not applied. Applied migrations: " + Arrays.toString(applied));
    }
}
