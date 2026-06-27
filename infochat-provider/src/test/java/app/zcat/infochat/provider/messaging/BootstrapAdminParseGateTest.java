package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** A bare SimpleX queue address (URL-safe base64, 44 chars ≥ the 43 floor). */
    static final String SIMPLEX_BARE_QUEUE_ID = "SimplexBootstrapAdminQueueAddr0000000000000A";

    /**
     * The same bare id wrapped in a full SimpleX contact link — the
     * operator-facing form gate 7b must canonicalize before validating
     * (M1-465). The SMP URI is URL-encoded so the test does not hand-roll
     * the percent-encoding the adapter's extractor expects.
     */
    static final String SIMPLEX_ADMIN_FULL_LINK =
            "https://simplex.chat/contact#/?v=2-7&smp="
                    + URLEncoder.encode("smp://hQ@smp.example.com/" + SIMPLEX_BARE_QUEUE_ID
                            + "#/?v=1&dh=AB", StandardCharsets.UTF_8);

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

    @Test
    void adminGivenAsFullContactLinkIsAcceptedAfterCanonicalization() {
        // The profile configures simplex.admin as a full SimpleX contact link.
        // On its own that raw link is NOT a well-formed bare queue id — the gate
        // accepts it only because gate 7b canonicalizes it first (M1-465).
        SimpleXAdapter simplex = new SimpleXAdapter();
        assertFalse(simplex.isWellFormedContactId(SIMPLEX_ADMIN_FULL_LINK),
                "a raw contact link must not be well-formed on its own");
        assertTrue(simplex.isWellFormedContactId(
                        simplex.canonicalizeContactId(SIMPLEX_ADMIN_FULL_LINK)),
                "canonicalizing the link must yield a well-formed bare id");

        assertDoesNotThrow(() -> registry.start("simplex"),
                "the parse gate must accept a full-link admin after canonicalization");
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
                    // Full SimpleX contact link: gate 7b canonicalizes it to the
                    // bare queue id before validating (M1-465), so it passes.
                    Map.entry("infochat.adapters.simplex.admin", SIMPLEX_ADMIN_FULL_LINK),
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
