package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code @Test} per startup gate from
 * {@code docs/design/06-messaging.md} §6.7. Each test exercises a
 * single failing configuration and asserts {@link AdapterRegistry#start()}
 * raises {@link IllegalStateException} whose message names the
 * offending adapter (or the offending property value). Gates fire in
 * §6.7's documented order so each sad-path setup is the minimum that
 * trips the target gate while satisfying all earlier gates.
 *
 * <p>{@link MessagingStartup} is excluded from the test ARC container
 * via {@code quarkus.arc.exclude-types} so {@code start()} does not
 * fire automatically at Quarkus boot — each {@code @Test} sets
 * {@link AdapterRegistry#adaptersCsv} and calls {@code start()}
 * manually, which is the only way to vary the configured adapter
 * list per-test without forcing a Quarkus restart per gate.</p>
 *
 * <p>Fake adapter beans for the broken-capability scenarios are
 * declared as static nested {@code @ApplicationScoped} types so the
 * test file count remains at 1 (the {@code files_budget} for this
 * subticket leaves no room for separate fake classes). Each fake has
 * a unique {@link MessagingAdapter#name()} so CDI discovery does not
 * collide; gates 5 and 6 use the production {@code inmemory} producer
 * so the production-exclusion + LOW-trust paths exercise the real
 * adapter shape rather than a synthetic look-alike.</p>
 */
@QuarkusTest
@TestProfile(StartupGatesTest.Profile.class)
class StartupGatesTest {

    @Inject
    AdapterRegistry registry;

    @Test
    void gate1RejectsEmptyAdaptersList() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start(""));
        assertTrue(e.getMessage().contains("no adapters configured"),
                "gate 1 message must pinpoint the empty list, got: " + e.getMessage());
    }

    @Test
    void gate2RejectsUnknownAdapterName() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("inmemory,nope"));
        assertTrue(e.getMessage().contains("nope"),
                "gate 2 message must name the unknown adapter, got: " + e.getMessage());
    }

    @Test
    void gate3RejectsMarkdownLinksTrue() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("bad-md"));
        assertTrue(e.getMessage().contains("bad-md"),
                "gate 3 message must name the adapter, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("supportsMarkdownLinks"),
                "gate 3 message must mention the offending capability, got: " + e.getMessage());
    }

    @Test
    void gate4RejectsMentionByIdFalseWithGroupSpiWired() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("bad-mention"));
        assertTrue(e.getMessage().contains("bad-mention"),
                "gate 4 message must name the adapter, got: " + e.getMessage());
    }

    @Test
    void gate5RejectsInMemoryAlongsideOtherAdapter() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("inmemory,fake-a"));
        assertTrue(e.getMessage().contains("inmemory"),
                "gate 5 message must name inmemory, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("fake-a"),
                "gate 5 message must name the conflicting adapter, got: " + e.getMessage());
    }

    @Test
    void gate6RejectsLowTrustWithoutOptIn() {
        // Gate 6 inspects per-adapter allow-low-trust against the
        // ConfigProvider, not the csv arg. The profile intentionally
        // does NOT set inmemory.allow-low-trust=true so this test
        // trips on the production InMemoryAdapter's LOW default.
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("inmemory"));
        assertTrue(e.getMessage().contains("inmemory"),
                "gate 6 message must name inmemory, got: " + e.getMessage());
        assertTrue(
                e.getMessage().contains("allow-low-trust")
                        || e.getMessage().contains("trust=LOW"),
                "gate 6 message must name the missing opt-in, got: " + e.getMessage());
    }

    @Test
    void rejectsDuplicateAdapterName() {
        // M1-125: a repeated name in infochat.adapters (e.g.
        // "inmemory,inmemory") would double-wire the same adapter; the
        // dedup check fails fast naming the duplicate before bean
        // resolution.
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> registry.start("inmemory,inmemory"));
        assertTrue(e.getMessage().contains("duplicate"),
                "duplicate-name check must flag the duplication, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("inmemory"),
                "duplicate-name check must name the duplicated adapter, got: " + e.getMessage());
    }

    /**
     * Single profile shared by every gate test. Excludes
     * {@link MessagingStartup} from ARC so its {@code @PostConstruct}
     * does not run {@code start()} at boot with whichever adapter list
     * Quarkus picks up by default. Sets the gate-4-specific
     * {@code test-group-spi-wired} flag on {@code bad-mention} so
     * gate 4's @Test trips. The {@code inmemory.allow-low-trust} key
     * is INTENTIONALLY omitted so gate 6's @Test trips on the
     * production InMemoryAdapter.
     */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // application.properties' %test default sets
            // infochat.adapters.inmemory.allow-low-trust=true so most
            // @QuarkusTest classes boot the inmemory adapter cleanly.
            // Gate 6's @Test needs that opt-in MISSING; this profile
            // explicitly overrides the %test default to false so gate 6
            // trips on the production InMemoryAdapter's LOW default.
            return Map.of(
                    "quarkus.arc.exclude-types",
                    "app.zcat.infochat.provider.messaging.MessagingStartup",
                    "infochat.adapters.bad-mention.test-group-spi-wired", "true",
                    "infochat.adapters.inmemory.allow-low-trust", "false"
            );
        }
    }

    @ApplicationScoped
    public static class BadMarkdownLinksAdapter implements MessagingAdapter {
        @Override
        public String name() {
            return "bad-md";
        }

        @Override
        public CapabilityFlags capabilities() {
            return TestCapabilities.with(/* mentionById */ true,
                    /* markdownLinks */ true);
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
            return new MessageHandle("bad-md");
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
    public static class BadMentionByIdAdapter implements MessagingAdapter {
        @Override
        public String name() {
            return "bad-mention";
        }

        @Override
        public CapabilityFlags capabilities() {
            return TestCapabilities.with(/* mentionById */ false,
                    /* markdownLinks */ false);
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
            return new MessageHandle("bad-mention");
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
    public static class FakeAdapterA implements MessagingAdapter {
        @Override
        public String name() {
            return "fake-a";
        }

        @Override
        public CapabilityFlags capabilities() {
            return TestCapabilities.with(/* mentionById */ true,
                    /* markdownLinks */ false);
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
            return new MessageHandle("fake-a");
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

    /**
     * Shared {@link CapabilityFlags} factory for the test fakes. Keeps
     * the gate-specific tweaks one named-argument away so the per-fake
     * intent reads at a glance — the alternative is 14 verbose record
     * components duplicated four times across the file.
     */
    static final class TestCapabilities {
        static CapabilityFlags with(boolean mentionById, boolean markdownLinks) {
            return new CapabilityFlags(
                    /* supportsMentionByContactId */ mentionById,
                    /* supportsMembershipEvents   */ true,
                    /* supportsCodeFormatting     */ false,
                    /* supportsMarkdownLinks      */ markdownLinks,
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
