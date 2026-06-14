package app.zcat.infochat.provider.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Pins the Provider-side §6.12 emission wiring (M1-322): the inbound
 * chokepoint ({@link InboundRouter#onMessage} counts
 * {@code adapter.inbound.total}), the outbound chokepoint
 * ({@link OutboundDelivery} classifies {@code adapter.outbound.total}
 * into ok/retry/fail across its retry loop), and the
 * {@code invite_drop_total} counter
 * (docs/design/04-security.md §Invite-code registration — the M1-044a
 * deferral retired by this ticket) on {@link InviteCodeConsumer}'s
 * documented drop path. The router/delivery tests plain-construct
 * their beans with a private {@link SimpleMeterRegistry}; the invite
 * test drives the real CDI bean against the DevServices Postgres, so
 * it reads the deployment-wide registry.
 */
@QuarkusTest
class AdapterMetricsWiringTest {

    private static final String ADAPTER = "inmemory";

    @Inject
    InviteCodeConsumer inviteCodeConsumer;

    @Inject
    MeterRegistry deploymentRegistry;

    @Test
    void onMessageIncrementsInboundTotalForEveryDelivery() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboundRouter router = bannedUserRouter();
        router.adapterMetrics = new AdapterMetrics(registry);
        router.setReplyTarget(new CapturingAdapter());

        router.onMessage(dm("metrics-inbound-contact", "/help"), ADAPTER);
        router.onMessage(dm("metrics-inbound-contact", "/help"), ADAPTER);

        assertEquals(2.0, registry.get("adapter.inbound.total")
                        .tags("adapter", ADAPTER, "scope_kind", "dm").counter().count(),
                "every delivered inbound must count, including ones the ban gate drops");
        assertEquals(2, registry.get("adapter.message.bytes")
                        .tags("adapter", ADAPTER, "direction", "inbound").summary().count(),
                "every inbound body must record its byte size");
    }

    @Test
    void deliverClassifiesOkRetryAndFailOutcomes() {
        // Immediate success: one ok, no retries.
        SimpleMeterRegistry okRegistry = new SimpleMeterRegistry();
        OutboundDelivery okDelivery = delivery(okRegistry);
        assertNotNull(okDelivery.deliver(
                new FailingMessagingAdapter(ADAPTER, 0, FailureCategory.TRANSIENT), outbound()));
        assertEquals(1.0, outcome(okRegistry, "ok"));
        assertNull(okRegistry.find("adapter.outbound.total")
                .tags("adapter", ADAPTER, "scope_kind", "dm", "outcome", "retry").counter());

        // Two transient failures then success: two retry, one ok.
        SimpleMeterRegistry retryRegistry = new SimpleMeterRegistry();
        OutboundDelivery retryDelivery = delivery(retryRegistry);
        assertNotNull(retryDelivery.deliver(
                new FailingMessagingAdapter(ADAPTER, 2, FailureCategory.TRANSIENT), outbound()));
        assertEquals(2.0, outcome(retryRegistry, "retry"));
        assertEquals(1.0, outcome(retryRegistry, "ok"));

        // Permanent failure: one fail, no retries.
        SimpleMeterRegistry permRegistry = new SimpleMeterRegistry();
        OutboundDelivery permDelivery = delivery(permRegistry);
        assertNull(permDelivery.deliver(
                FailingMessagingAdapter.alwaysFailing(ADAPTER, FailureCategory.PERMANENT),
                outbound()));
        assertEquals(1.0, outcome(permRegistry, "fail"));

        // Exhausted transient budget (3 attempts): two retry, then fail.
        SimpleMeterRegistry capRegistry = new SimpleMeterRegistry();
        OutboundDelivery capDelivery = delivery(capRegistry);
        assertNull(capDelivery.deliver(
                FailingMessagingAdapter.alwaysFailing(ADAPTER, FailureCategory.TRANSIENT),
                outbound()));
        assertEquals(2.0, outcome(capRegistry, "retry"));
        assertEquals(1.0, outcome(capRegistry, "fail"));
    }

    @Test
    void rejectedConsumeIncrementsInviteDropTotal() {
        // v1 ships no exporter extension, so the deployment-wide CDI
        // registry is a childless composite whose counters are no-ops
        // (the provider pom records this as the committed surface).
        // Attaching a SimpleMeterRegistry child for the test's duration
        // makes the real bean's increments observable while still
        // proving the CDI wiring end-to-end.
        CompositeMeterRegistry composite = (CompositeMeterRegistry) deploymentRegistry;
        SimpleMeterRegistry observer = new SimpleMeterRegistry();
        composite.add(observer);
        try {
            double before = inviteDropCount(observer);

            // A non-UUID body is the §Invite-code registration "invalid"
            // drop shape; the consumer parses, fails, records the
            // attempt, and rejects.
            InviteCodeConsumer.Outcome outcome = inviteCodeConsumer.consume(
                    ADAPTER, "metrics-drop-contact-" + UUID.randomUUID(), "not-a-uuid");

            assertInstanceOf(InviteCodeConsumer.Rejected.class, outcome);
            assertEquals(before + 1.0, inviteDropCount(observer),
                    "the documented drop path must increment invite_drop_total");
        } finally {
            composite.remove(observer);
        }
    }

    private static double inviteDropCount(SimpleMeterRegistry observer) {
        var counter = observer.find("invite_drop_total").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static double outcome(SimpleMeterRegistry registry, String outcome) {
        return registry.get("adapter.outbound.total")
                .tags("adapter", ADAPTER, "scope_kind", "dm", "outcome", outcome)
                .counter().count();
    }

    private static OutboundDelivery delivery(SimpleMeterRegistry registry) {
        OutboundDelivery delivery = new OutboundDelivery(
                new RecordingAdminNotifier(), new RecordingGroupRepository(),
                3, 0L, 2.0, 3, millis -> { });
        delivery.adapterMetrics = new AdapterMetrics(registry);
        return delivery;
    }

    /**
     * Plain-constructed router whose user lookup reports a banned
     * snapshot — the earliest post-emission short-circuit, so the test
     * never reaches the DB-backed dispatch steps. Same construction
     * shape as {@code InboundRouterBanSnapshotTest}.
     */
    private static InboundRouter bannedUserRouter() {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                return Optional.of(new UserSnapshot(UUID.randomUUID(), "vouched", true, null));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = new NoopBundleLoader();
        router.maxInboundBodyBytes = 65536;
        return router;
    }

    private static InboundMessage dm(String contactId, String text) {
        return new InboundMessage(
                new Identity(contactId, "Mallory", Instant.now()),
                new ScopeRef.Dm(contactId),
                text,
                Instant.now(),
                "msg-metrics-" + UUID.randomUUID());
    }

    private static OutboundMessage outbound() {
        return new OutboundMessage(
                new ScopeRef.Dm("metrics-out-contact"), "hello", Instant.now(), "corr-metrics");
    }
}
