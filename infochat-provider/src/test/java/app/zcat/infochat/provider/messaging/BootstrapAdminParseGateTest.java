package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.MessagingAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * <p>The profile configures a MALFORMED {@code signal.admin} (non-UUID)
 * and a WELL-FORMED {@code simplex.admin} (URL-safe base64 queue address);
 * each {@code @Test} activates exactly one adapter so both the reject and
 * the proceed case are exercised against a single fixed profile.
 * {@link MessagingStartup} is excluded so {@code start()} is driven
 * manually and never launches the real SimpleX / Signal subprocesses.</p>
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

    @Test
    void gateAcceptsWellFormedSimplexAdminAndProceeds() {
        assertDoesNotThrow(() -> registry.start("simplex"),
                "a well-formed simplex.admin must pass the parse gate");
        List<String> activated = registry.activatedAdapters().stream()
                .map(MessagingAdapter::name)
                .toList();
        assertTrue(activated.contains("simplex"),
                "startup must proceed past the gate and activate simplex; got: " + activated);
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
            // values this test depends on are the two .admin entries.
            return Map.ofEntries(
                    Map.entry("quarkus.arc.exclude-types",
                            "app.zcat.infochat.provider.messaging.MessagingStartup,"
                                    + "app.zcat.infochat.provider.startup.AdminBootstrap"),
                    Map.entry("infochat.adapters", "simplex,signal"),
                    Map.entry("infochat.adapters.inmemory.allow-low-trust", "true"),
                    Map.entry("infochat.adapters.simplex.binary", "/bin/sh"),
                    Map.entry("infochat.adapters.simplex.data-dir", "/tmp"),
                    Map.entry("infochat.adapters.simplex.ws-port", "5225"),
                    // Well-formed SimpleX queue address (URL-safe base64, >=43 chars).
                    Map.entry("infochat.adapters.simplex.admin",
                            "SimplexBootstrapAdminQueueAddr0000000000000A"),
                    Map.entry("infochat.adapters.simplex.bot-queue-address",
                            "SimplexBotIdentityQueueAddr00000000000000001"),
                    Map.entry("infochat.adapters.signal.binary", "/bin/sh"),
                    Map.entry("infochat.adapters.signal.data-dir", "/tmp"),
                    Map.entry("infochat.adapters.signal.account", "test-account"),
                    // Malformed signal.admin: not a UUID, so gate 7b rejects it.
                    Map.entry("infochat.adapters.signal.admin", "not-a-valid-aci"),
                    Map.entry("infochat.adapters.signal.bot-aci",
                            "00000000-0000-0000-0000-000000000002"),
                    Map.entry("infochat.adapters.signal.endpoint", "127.0.0.1:7654")
            );
        }
    }
}
