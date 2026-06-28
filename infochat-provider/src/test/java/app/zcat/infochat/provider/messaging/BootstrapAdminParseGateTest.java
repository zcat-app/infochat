package app.zcat.infochat.provider.messaging;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-208: {@link AdapterRegistry}'s bootstrap-admin parse gate (gate 7b).
 * The gate validates each non-blank {@code infochat.adapters.<name>.admin}
 * value against its own adapter's well-formedness validator and refuses to
 * start on a mismatch — docs/spec/deployment.md §Operator inputs item 2:
 * "each value MUST be parseable by its own adapter; Provider validates
 * each at startup and refuses to start on a mismatch."
 *
 * <p>The profile configures a MALFORMED {@code signal.admin} (non-ACI);
 * activating {@code signal} exercises gate 7b's reject path.
 * {@link MessagingStartup} is excluded so {@code start()} is driven
 * manually and never launches the real Signal subprocess.</p>
 *
 * <p><b>M1-506 / D50.</b> The former SimpleX cases (a well-formed
 * {@code simplex.admin}, and a full SimpleX contact link canonicalized by
 * gate 7b) were removed: SimpleX has no pre-configurable cryptographic
 * sender address, so {@code simplex.admin} is no longer a bootstrap-admin
 * path (gate 7's union counts only {@code simplex.admin-token}), and
 * {@code start("simplex")} with only {@code .admin} now correctly fails
 * gate 7. The gate-7b SimpleX-link canonicalization those cases proved is
 * preserved by
 * {@code AdminBootstrapIT.simplexAdminTokenSatisfiesGate7Union}, whose
 * profile sets both {@code simplex.admin} (full link) and a token so
 * {@code start("simplex")} still runs gate-7b canonicalization on the
 * link.</p>
 */
@QuarkusTest
@TestProfile(BootstrapAdminParseGateTest.Profile.class)
class BootstrapAdminParseGateTest {

    @Inject
    AdapterRegistry registry;

    @Test
    void gateRejectsMalformedSignalAdminNamingAdapterAndProperty() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("signal"));
        assertTrue(e.getMessage().contains("signal"),
                "gate message must name the adapter, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("infochat.adapters.signal.admin"),
                "gate message must name the offending property, got: " + e.getMessage());
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Placeholder filesystem / port values satisfy the
            // ProductionAdapterBeans @ConfigProperty injection points;
            // MessagingStartup is excluded so adapter.start() — and thus the
            // subprocess launch + bot-identity validation — never fires.
            // AdminBootstrap is excluded too: it validates the same .admin
            // values at boot (before any write), so the deliberately
            // malformed signal.admin below would otherwise abort this test
            // app's startup before the gate under test ever runs. The only
            // value this test depends on is the malformed signal.admin.
            return Map.ofEntries(
                    Map.entry("quarkus.arc.exclude-types",
                            "app.zcat.infochat.provider.messaging.MessagingStartup,"
                                    + "app.zcat.infochat.provider.startup.AdminBootstrap"),
                    Map.entry("infochat.adapters", "signal"),
                    Map.entry("infochat.adapters.inmemory.allow-low-trust", "true"),
                    Map.entry("infochat.adapters.signal.binary", "/bin/sh"),
                    Map.entry("infochat.adapters.signal.data-dir", "/tmp"),
                    Map.entry("infochat.adapters.signal.account", "test-account"),
                    // Malformed signal.admin: not a UUID, so gate 7b rejects it.
                    Map.entry("infochat.adapters.signal.admin", "not-a-valid-aci"),
                    Map.entry("infochat.adapters.signal.endpoint", "127.0.0.1:7654")
            );
        }
    }
}
