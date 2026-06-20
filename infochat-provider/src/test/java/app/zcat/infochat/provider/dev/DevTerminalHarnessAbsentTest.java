package app.zcat.infochat.provider.dev;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Structural proof that the dev harness cannot reach a production build (M1-414,
 * acceptance item 2). This test runs under the normal {@code test} profile
 * WITHOUT {@code infochat.dev.harness.enabled} set. Because the harness is gated
 * by {@code @IfBuildProperty(enableIfMissing=false)} — a build-time decision —
 * the bean is excluded from any build that does not opt in, so a production build
 * (which never sets the flag) cannot contain the inbound-injection surface.
 */
@QuarkusTest
class DevTerminalHarnessAbsentTest {

    @Inject
    Instance<DevTerminalHarness> harness;

    @Test
    void harnessBeanAbsentWhenFlagUnset() {
        assertFalse(harness.isResolvable(),
                "DevTerminalHarness must NOT be a bean when "
                        + "infochat.dev.harness.enabled is unset — it is the prod-absence "
                        + "guarantee that the inbound-injection surface cannot ship");
    }
}
