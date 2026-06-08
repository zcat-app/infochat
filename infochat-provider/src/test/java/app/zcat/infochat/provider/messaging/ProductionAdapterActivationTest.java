package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.signal.SignalAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter;
import app.zcat.infochat.provider.health.AdapterConnectionState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-120 acceptance: SimpleX and Signal are CDI-resolvable alongside
 * InMemory, {@link AdapterRegistry#start(String)} passes all seven
 * gates for {@code "simplex,signal"}, and
 * {@link MessagingStartup#startAllAdapters()} invokes each activated
 * adapter's {@code start()} with §6.7 per-adapter resilience (one
 * adapter's failure does not abort the loop).
 *
 * <p>{@link MessagingStartup} is excluded from this test's ArC graph
 * so its {@code @PostConstruct} does not fire {@code start()} on the
 * real {@link SimpleXAdapter} / {@link SignalAdapter} with the
 * placeholder filesystem paths supplied by {@link Profile} (which
 * would log subprocess-launch errors but not actually fail the test
 * — exclusion just keeps the log clean). The startup-resilience
 * test methods build a {@link MessagingStartup} directly and feed
 * it a stub {@link AdapterRegistry} that returns local test fakes,
 * so the production SimpleX / Signal adapters are never started in
 * this test class.</p>
 */
@QuarkusTest
@TestProfile(ProductionAdapterActivationTest.Profile.class)
class ProductionAdapterActivationTest {

    @Inject
    @Any
    Instance<MessagingAdapter> allAdapters;

    @Inject
    AdapterRegistry registry;

    @Test
    void bothAdaptersResolveAsBeans() {
        // Acceptance item 4: Instance<MessagingAdapter> contains all
        // three production beans by name. Production typing pinned: the
        // simplex / signal beans must be the actual production classes
        // (not test fakes named "simplex" / "signal").
        Map<String, MessagingAdapter> byName = allAdapters.stream()
                .collect(Collectors.toMap(MessagingAdapter::name, a -> a));
        assertTrue(byName.containsKey("inmemory"),
                "Instance<MessagingAdapter> must contain inmemory bean; got: " + byName.keySet());
        assertTrue(byName.containsKey("simplex"),
                "Instance<MessagingAdapter> must contain simplex bean; got: " + byName.keySet());
        assertTrue(byName.containsKey("signal"),
                "Instance<MessagingAdapter> must contain signal bean; got: " + byName.keySet());
        assertTrue(byName.get("simplex") instanceof SimpleXAdapter,
                "simplex bean must be the production SimpleXAdapter class, got: "
                        + byName.get("simplex").getClass().getName());
        assertTrue(byName.get("signal") instanceof SignalAdapter,
                "signal bean must be the production SignalAdapter class, got: "
                        + byName.get("signal").getClass().getName());

        // Acceptance item 5: AdapterRegistry.start("simplex,signal")
        // passes all seven gates and adds both adapters to
        // activatedAdapters().
        registry.start("simplex,signal");
        List<String> activatedNames = registry.activatedAdapters().stream()
                .map(MessagingAdapter::name)
                .toList();
        assertEquals(2, activatedNames.size(),
                "exactly two adapters activated; got: " + activatedNames);
        assertTrue(activatedNames.contains("simplex"), "simplex must be activated");
        assertTrue(activatedNames.contains("signal"), "signal must be activated");
    }

    @Test
    void messagingStartupCallsAdapterStart() throws Exception {
        // Acceptance item 8: a recording adapter receives start() from
        // MessagingStartup.startAllAdapters().
        StartRecordingAdapter recording = new StartRecordingAdapter("rec1", false);
        MessagingStartup startup = newMessagingStartup(List.of(recording));

        startup.startAllAdapters();

        assertEquals(1, recording.startCount(),
                "MessagingStartup must invoke start() exactly once on each activated adapter");
    }

    @Test
    void startFailureDoesNotAbortLoop() throws Exception {
        // Acceptance item 9: §6.7 per-adapter resilience. When one
        // adapter's start() throws, the next adapter's start() is
        // still invoked and the loop completes normally (Provider
        // startup does not abort).
        StartRecordingAdapter failing = new StartRecordingAdapter("fail1", true);
        StartRecordingAdapter recording = new StartRecordingAdapter("rec2", false);
        // Order matters: failing first guarantees the loop reaches
        // the second adapter after a thrown exception.
        MessagingStartup startup = newMessagingStartup(List.of(failing, recording));

        assertDoesNotThrow(startup::startAllAdapters,
                "MessagingStartup must absorb adapter start() failures and not propagate");

        assertEquals(1, failing.startCount(),
                "failing adapter must still see its own start() invocation");
        assertEquals(1, recording.startCount(),
                "recording adapter must be reached after the failing adapter's exception");
    }

    private static MessagingStartup newMessagingStartup(List<MessagingAdapter> activated) throws Exception {
        MessagingStartup startup = new MessagingStartup();
        Field f = MessagingStartup.class.getDeclaredField("adapterRegistry");
        f.setAccessible(true);
        f.set(startup, new StubAdapterRegistry(activated));
        Field cs = MessagingStartup.class.getDeclaredField("connectionState");
        cs.setAccessible(true);
        cs.set(startup, new AdapterConnectionState());
        return startup;
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // simplex,signal CSV exercises the production multi-adapter
            // activation shape. The .admin entries satisfy gate 7
            // (bootstrap admin union non-empty). The .bot-queue-address /
            // .bot-aci entries — DISTINCT from the .admin values — wire
            // the bot's own D10 trust anchor per the post-/redteam
            // separation of bootstrap-admin from bot-identity. The
            // .binary, .data-dir, .ws-port, .account, .endpoint entries
            // satisfy the remaining @ConfigProperty injection points on
            // ProductionAdapterBeans; values are placeholders only —
            // MessagingStartup is excluded so adapter.start() is never
            // invoked on the production SimpleX / Signal beans within
            // this test class.
            return Map.ofEntries(
                    Map.entry("quarkus.arc.exclude-types",
                            "app.zcat.infochat.provider.messaging.MessagingStartup"),
                    Map.entry("infochat.adapters", "simplex,signal"),
                    Map.entry("infochat.adapters.inmemory.allow-low-trust", "true"),
                    Map.entry("infochat.adapters.simplex.binary", "/bin/sh"),
                    Map.entry("infochat.adapters.simplex.data-dir", "/tmp"),
                    Map.entry("infochat.adapters.simplex.ws-port", "5225"),
                    // Well-formed SimpleX queue address (URL-safe base64,
                    // >=43 chars) so AdapterRegistry's bootstrap-admin parse
                    // gate passes; the prior kebab-slug value is rejected by
                    // SimpleXIdentity.isWellFormed (M1-208).
                    Map.entry("infochat.adapters.simplex.admin",
                            "SimplexBootstrapAdminQueueAddr0000000000000A"),
                    Map.entry("infochat.adapters.simplex.bot-queue-address", "simplex-test-bot-identity"),
                    Map.entry("infochat.adapters.signal.binary", "/bin/sh"),
                    Map.entry("infochat.adapters.signal.data-dir", "/tmp"),
                    Map.entry("infochat.adapters.signal.account", "test-account"),
                    Map.entry("infochat.adapters.signal.admin", "00000000-0000-0000-0000-000000000001"),
                    Map.entry("infochat.adapters.signal.bot-aci", "00000000-0000-0000-0000-000000000002"),
                    Map.entry("infochat.adapters.signal.endpoint", "127.0.0.1:7654")
            );
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of();
        }
    }

    /**
     * Test-only {@link AdapterRegistry} stub. Returns a fixed
     * {@code activatedAdapters()} list bypassing the gate machinery
     * so {@link MessagingStartup#startAllAdapters()} can be exercised
     * with hand-built fakes. Constructed directly inside test methods
     * (not as a CDI bean) so the production registry stays
     * unaffected.
     */
    static final class StubAdapterRegistry extends AdapterRegistry {
        private final List<MessagingAdapter> activated;

        StubAdapterRegistry(List<MessagingAdapter> activated) {
            this.activated = List.copyOf(activated);
        }

        @Override
        public List<MessagingAdapter> activatedAdapters() {
            return activated;
        }
    }

    /**
     * Test-only adapter that records {@code start()} invocations and
     * optionally throws on call. Mirrors the inner-fake pattern from
     * {@link AdapterRegistryTest.FakeAdapterX} but overrides the SPI's
     * {@code start()} that {@link MessagingStartup} dispatches
     * directly. {@code throwOnStart=true} still increments
     * the counter so the resilience test can also assert "failing
     * adapter saw its own start()".
     */
    static final class StartRecordingAdapter implements MessagingAdapter {
        private final String name;
        private final boolean throwOnStart;
        private final AtomicInteger startCount = new AtomicInteger();

        StartRecordingAdapter(String name, boolean throwOnStart) {
            this.name = name;
            this.throwOnStart = throwOnStart;
        }

        @Override
        public void start() {
            startCount.incrementAndGet();
            if (throwOnStart) {
                throw new IllegalStateException(
                        "test-injected start() failure for adapter: " + name);
            }
        }

        int startCount() {
            return startCount.get();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CapabilityFlags capabilities() {
            return TestCaps.standard();
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            return AdapterTrustLevel.HIGH;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            return new MessageHandle(name);
        }

        @Override
        public void update(MessageHandle handle, String body) {
        }

        @Override
        public void finalizeMessage(MessageHandle handle, String body) {
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
        }
    }

    static final class TestCaps {
        static CapabilityFlags standard() {
            return new CapabilityFlags(
                    /* supportsMentionByContactId */ true,
                    /* supportsMembershipEvents   */ true,
                    /* supportsCodeFormatting     */ false,
                    /* supportsMarkdownLinks      */ false,
                    /* supportsMultilineCode      */ false,
                    /* supportsAttachments        */ false,
                    /* supportsThreading          */ false,
                    /* maxMessageBytes            */ 100_000,
                    /* maxInboundMessageBytes     */ 100_000,
                    /* maxSendsPerSecond          */ 10_000,
                    /* supportsMessageEdit        */ true,
                    /* supportsTypingIndicator    */ true,
                    /* minEditInterval            */ Duration.ZERO);
        }
    }
}
