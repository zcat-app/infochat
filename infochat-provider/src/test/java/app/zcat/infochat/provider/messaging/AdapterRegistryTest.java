package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring-tier happy-path tests for {@link AdapterRegistry}. The
 * gate sad paths live in {@link StartupGatesTest}; this class
 * asserts the two happy shapes from acceptance item 24:
 *
 * <ul>
 *   <li><b>Single-adapter:</b> {@code infochat.adapters=inmemory}
 *       activates exactly one adapter and registers the
 *       {@link InboundRouter} as its inbound handler — verified
 *       via the {@link RecordingInboundRouter} {@link Alternative}
 *       that captures the {@code (InboundMessage, adapterName)} pair
 *       handed to {@code onMessage}. Asserting wiring (router was
 *       invoked with the delivered body) rather than reply content
 *       narrows this test's scope to the SPI handshake; the
 *       unknown-command literal is asserted by
 *       {@code InboundRouterTest.unknownCommandProducesFriendlyUnknownCommandReply}.</li>
 *   <li><b>Multi-adapter:</b> {@code infochat.adapters=fake-x,fake-y}
 *       activates both test-only adapter beans. (The acceptance
 *       text's "e.g. inmemory and inmemory2" is illustrative — the
 *       production-exclusion gate (#5) rejects {@code inmemory} +
 *       any other adapter, so the multi-adapter happy path must NOT
 *       activate {@code inmemory} alongside a second bean. Two
 *       distinct non-{@code inmemory} adapters are the legitimate
 *       shape.)</li>
 * </ul>
 *
 * <p>{@link MessagingStartup} is excluded from the test ARC
 * container so {@code start()} does not fire automatically with
 * whichever {@code infochat.adapters} Quarkus picks up; each
 * {@code @Test} sets {@link AdapterRegistry#adaptersCsv} and calls
 * {@code start()} explicitly.</p>
 */
@QuarkusTest
@TestProfile(AdapterRegistryTest.Profile.class)
class AdapterRegistryTest {

    @Inject
    AdapterRegistry registry;

    @Inject
    InMemoryAdapter inMemoryAdapter;

    @Inject
    InboundRouter inboundRouter;

    @BeforeEach
    void resetAdapterAndRouterState() {
        inMemoryAdapter.reset();
        ((RecordingInboundRouter) inboundRouter).reset();
    }

    @Test
    void singleAdapterHappyPathActivatesInMemoryAndRegistersRouter() {
        registry.start("inmemory");

        List<MessagingAdapter> activated = registry.activatedAdapters();
        assertEquals(1, activated.size(), "exactly one adapter should activate");
        assertEquals("inmemory", activated.get(0).name());

        // Wiring assertion: the handler InMemoryAdapter received must
        // route through the InboundRouter bean. A deliverDm probe with
        // an arbitrary body lands in RecordingInboundRouter.onMessage,
        // which records the (body, adapterName) pair. Asserting on the
        // capture confirms the SPI wiring without coupling this test
        // to any specific command's reply text.
        inMemoryAdapter.deliverDm("alice", "/xyz");
        RecordingInboundRouter recording = (RecordingInboundRouter) inboundRouter;
        assertEquals(1, recording.capturedBodyCount(),
                "router should have been invoked exactly once");
        assertEquals("/xyz", recording.lastCapturedBody(),
                "router must receive the body delivered through the adapter");
        assertEquals("inmemory", recording.lastCapturedAdapterName(),
                "router must receive the source adapter's name through the wiring lambda");
    }

    @Test
    void multiAdapterHappyPathActivatesBothFakeAdapters() {
        registry.start("fake-x,fake-y");

        List<MessagingAdapter> activated = registry.activatedAdapters();
        assertEquals(2, activated.size(), "both fake adapters should activate");
        assertTrue(activated.stream().anyMatch(a -> "fake-x".equals(a.name())),
                "fake-x must be in the activated set");
        assertTrue(activated.stream().anyMatch(a -> "fake-y".equals(a.name())),
                "fake-y must be in the activated set");
    }

    /**
     * M1-105 acceptance item 10. Empty {@code infochat.adapters}
     * trips gate 1 (zero enabled adapters causes startup failure).
     * Independent coverage from {@link StartupGatesTest#gate1RejectsEmptyAdaptersList}:
     * the M1-105 IT plan ties the at-least-one-up readiness invariant
     * explicitly to {@link AdapterRegistryTest}; this method pins the
     * behavior on that named class even though the gate sad-path
     * already has a sibling test in {@link StartupGatesTest}.
     */
    @Test
    void atLeastOneAdapterRequired() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start(""));
        assertTrue(e.getMessage().contains("no adapters configured"),
                "gate 1 message must pinpoint the empty adapters list, got: "
                        + e.getMessage());
    }

    /**
     * M1-105 acceptance item 11. An adapter that declares
     * {@code supportsMarkdownLinks=true} trips gate 3 (fail-fast per
     * messaging.md §Capability flags). Uses the
     * {@code "bad-md"} {@link StartupGatesTest.BadMarkdownLinksAdapter}
     * bean discovered globally by ArC across the test classpath; the
     * cross-file reference is intentional — duplicating the fake here
     * would push this class past the 5-inner-class refactor threshold
     * documented in the auto-memory entry on inner-class fakes.
     * The {@link StartupGatesTest} import below makes the dependency
     * explicit so a rename would surface as a compile error.
     */
    @Test
    void markdownLinksValidation() {
        // Compile-time backreference so a rename of the cross-file
        // fake adapter breaks this test at javac time rather than at
        // runtime via a gate-2 "unknown adapter" miss.
        @SuppressWarnings("unused")
        Class<?> backreference = StartupGatesTest.BadMarkdownLinksAdapter.class;
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("bad-md"));
        assertTrue(e.getMessage().contains("supportsMarkdownLinks"),
                "gate 3 message must name the offending capability, got: "
                        + e.getMessage());
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // fake-x.admin satisfies the M1-105 gate-7 union for the
            // multi-adapter happy path (fake-x + fake-y). The inmemory
            // bootstrap admin is inherited from the %test default in
            // application.properties; gate-1 / gate-3 paths trip an
            // earlier gate and never reach gate 7.
            return Map.of(
                    "quarkus.arc.exclude-types",
                    "app.zcat.infochat.provider.messaging.MessagingStartup",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.adapters.fake-x.admin", "fake-x-test-bootstrap"
            );
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(RecordingInboundRouter.class);
        }
    }

    /**
     * CDI {@link Alternative} that records every {@code onMessage}
     * dispatch the {@link AdapterRegistry} wiring routes through. The
     * production {@link InboundRouter}'s side effects (normalize,
     * dispatch, auto-register, DB writes) are intentionally suppressed
     * — this test asserts the wiring handshake, not the router's own
     * behavior (covered by {@link InboundRouterTest}).
     *
     * <p>Activation is scoped via
     * {@link QuarkusTestProfile#getEnabledAlternatives()}; no
     * {@code @Priority} so this alternative does not leak into other
     * test classes' CDI graphs.
     */
    @Alternative
    @ApplicationScoped
    public static class RecordingInboundRouter extends InboundRouter {

        private final java.util.List<String> capturedBodies =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<String> capturedAdapterNames =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onMessage(InboundMessage msg, String adapterName) {
            capturedBodies.add(msg.text());
            capturedAdapterNames.add(adapterName);
        }

        int capturedBodyCount() {
            return capturedBodies.size();
        }

        String lastCapturedBody() {
            return capturedBodies.get(capturedBodies.size() - 1);
        }

        String lastCapturedAdapterName() {
            return capturedAdapterNames.get(capturedAdapterNames.size() - 1);
        }

        void reset() {
            capturedBodies.clear();
            capturedAdapterNames.clear();
        }
    }

    @ApplicationScoped
    public static class FakeAdapterX implements MessagingAdapter {
        @Override
        public String name() {
            return "fake-x";
        }

        @Override
        public CapabilityFlags capabilities() {
            return MultiAdapterCaps.standard();
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            return AdapterTrustLevel.HIGH;
        }

        @Override
        public boolean isWellFormedContactId(String contactId) {
            return true;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            return new MessageHandle("fake-x");
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

    @ApplicationScoped
    public static class FakeAdapterY implements MessagingAdapter {
        @Override
        public String name() {
            return "fake-y";
        }

        @Override
        public CapabilityFlags capabilities() {
            return MultiAdapterCaps.standard();
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            return AdapterTrustLevel.HIGH;
        }

        @Override
        public boolean isWellFormedContactId(String contactId) {
            return true;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            return new MessageHandle("fake-y");
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

    static final class MultiAdapterCaps {
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
