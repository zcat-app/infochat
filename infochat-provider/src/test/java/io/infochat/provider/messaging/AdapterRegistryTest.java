package io.infochat.provider.messaging;

import io.infochat.messaging.AdapterTrustLevel;
import io.infochat.messaging.CapabilityFlags;
import io.infochat.messaging.Identity;
import io.infochat.messaging.InboundMessage;
import io.infochat.messaging.MessageHandle;
import io.infochat.messaging.MessagingAdapter;
import io.infochat.messaging.OutboundMessage;
import io.infochat.messaging.ScopeRef;
import io.infochat.messaging.impl.inmemory.InMemoryAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Happy-path activation tests for {@link AdapterRegistry}. The
 * gate sad paths live in {@link StartupGatesTest}; this class
 * asserts the two happy shapes from acceptance item 24:
 *
 * <ul>
 *   <li><b>Single-adapter:</b> {@code infochat.adapters=inmemory}
 *       activates exactly one adapter and registers the
 *       {@link InboundRouter} as its inbound handler — verified
 *       functionally by delivering an inbound message through
 *       {@link InMemoryAdapter#deliverDm(String, String)} and
 *       asserting the unknown-command reply lands in
 *       {@link InMemoryAdapter#sentMessages()}.</li>
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
    void resetAdapterState() {
        inMemoryAdapter.reset();
    }

    @Test
    void singleAdapterHappyPathActivatesInMemoryAndRegistersRouter() {
        registry.start("inmemory");

        List<MessagingAdapter> activated = registry.activatedAdapters();
        assertEquals(1, activated.size(), "exactly one adapter should activate");
        assertEquals("inmemory", activated.get(0).name());

        // Verify the handler that InMemoryAdapter received is the
        // Provider's InboundRouter bean: a deliverDm round-trip
        // should route through the router and produce the
        // unknown-command reply. The probe uses /xyz — a name that
        // will never be implemented — so the assertion stays valid
        // as the milestone fills in real CommandHandlers.
        inMemoryAdapter.deliverDm("alice", "/xyz");
        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "router should have produced exactly one outbound reply");
        assertEquals(InboundRouter.UNKNOWN_COMMAND_REPLY, sent.get(0).text(),
                "reply must come from the InboundRouter's slash-prefix branch");
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

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.arc.exclude-types",
                    "io.infochat.provider.messaging.MessagingStartup",
                    "infochat.adapters.inmemory.allow-low-trust", "true"
            );
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
        public Identity assertIdentity(InboundMessage msg) {
            return msg.sender();
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            return new MessageHandle("fake-x");
        }

        @Override
        public void update(MessageHandle handle, String body) {
        }

        @Override
        public void finalize(MessageHandle handle, String body) {
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
        public Identity assertIdentity(InboundMessage msg) {
            return msg.sender();
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            return new MessageHandle("fake-y");
        }

        @Override
        public void update(MessageHandle handle, String body) {
        }

        @Override
        public void finalize(MessageHandle handle, String body) {
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
                    /* maxInflightSends           */ 1_000,
                    /* maxSendsPerSecond          */ 10_000,
                    /* supportsMessageEdit        */ true,
                    /* supportsTypingIndicator    */ true,
                    /* minEditInterval            */ Duration.ZERO);
        }
    }
}
