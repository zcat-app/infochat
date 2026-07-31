package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.digest.DigestRetryService.RetryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit tests for {@link DigestRetryService}'s gap-filling replay path
 * (M1-652). Three regimes: the pre-M1-652 fallback re-run (no sections →
 * worker.execute with a clamped windowEnd), byte-faithful replay (sections
 * present → only missing categories re-sent, worker never invoked), and the
 * no-op all-already-delivered short-circuit (acceptance item 5's
 * empty-list guard).
 */
class DigestRetryServiceTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final String SLOT_KIND = "morning";
    private static final Instant SLOT_FIRED_AT = Instant.parse("2026-05-26T07:45:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-05-26T08:15:00Z");
    private static final String GROUP_TIMEZONE = "UTC";
    private static final String ADAPTER_NAME = "inmemory";
    private static final String UPSTREAM_GROUP_ID = "group-retry";

    private DigestRetryService service;
    private RecordingDigestWorker digestWorker;
    private RecordingSectionRepository sectionRepository;
    private RecordingCategoryDeliveryRepository deliveryRepository;
    private RecordingDigestDelivery digestDelivery;

    @BeforeEach
    void setUp() {
        service = new DigestRetryService();
        digestWorker = new RecordingDigestWorker();
        sectionRepository = new RecordingSectionRepository();
        deliveryRepository = new RecordingCategoryDeliveryRepository();
        digestDelivery = new RecordingDigestDelivery();
        service.digestWorker = digestWorker;
        service.sectionRepository = sectionRepository;
        service.deliveryRepository = deliveryRepository;
        service.digestDelivery = digestDelivery;
        service.adapterRegistry = new StubAdapterRegistry(ADAPTER_NAME);
        service.retryCooldown = Duration.ofMinutes(2);
        service.windowWidthMinutes = 30;
        service.dataSource = stubDataSource(false);
    }

    // ----- fallback path (no sections / expired row) ------------------------

    @Test
    void retryDigest_replacesCacheRow() {
        // No sections → fallback worker.execute. EXPIRES_AT is in the past
        // relative to the test's wall clock, so the clamp min(EXPIRES_AT,
        // now + windowWidth) resolves to EXPIRES_AT — the pre-M1-652
        // windowEnd, preserved byte-for-byte.
        service.dataSource = stubDataSource(false);

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.SUCCESS, result);
        assertEquals(1, digestWorker.executeCount,
                "DigestWorker.execute must be called once on the fallback path");
        assertEquals(GROUP_ID, digestWorker.lastSlot.groupId());
        assertEquals(SLOT_KIND, digestWorker.lastSlot.slotKind());
        assertEquals(SLOT_FIRED_AT, digestWorker.lastSlot.windowStart());
        assertEquals(EXPIRES_AT, digestWorker.lastSlot.windowEnd(),
                "an expired row clamps to its own expires_at (min of past + future)");
        assertEquals(GROUP_TIMEZONE, digestWorker.lastSlot.groupTimezone());
    }

    @Test
    void retryDigest_regeneratesFullProseFromDegraded() {
        // Cache row has isDegraded=true — fallback still calls worker.
        service.dataSource = stubDataSource(true);

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.SUCCESS, result);
        assertEquals(1, digestWorker.executeCount,
                "worker must be called to regenerate full prose on the fallback path");
    }

    @Test
    void retryDigest_workerSkippedInFlight_returnsAlreadyInProgress() {
        digestWorker.outcome = DigestWorker.SlotOutcome.SKIPPED_IN_FLIGHT;

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.ALREADY_IN_PROGRESS, result,
                "a skipped worker run must never be reported as SUCCESS");
        assertEquals(1, digestWorker.executeCount,
                "worker must be invoked exactly once");
    }

    @Test
    void retryDigest_serializedPerGroup() {
        RetryResult first = service.retryDigest(GROUP_ID);
        assertEquals(RetryResult.SUCCESS, first);
        assertEquals(1, digestWorker.executeCount);

        // Hold the per-group in-flight guard reflectively (same seam the
        // pre-M1-652 test used) to prove serialization.
        try {
            var cooldownField = DigestRetryService.class.getDeclaredField("lastRetryAt");
            cooldownField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cooldownMap = (java.util.concurrent.ConcurrentHashMap<UUID, Instant>)
                    cooldownField.get(service);
            cooldownMap.remove(GROUP_ID);

            var field = DigestRetryService.class.getDeclaredField("inFlight");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) field.get(service);
            map.put(GROUP_ID, Boolean.TRUE);

            RetryResult concurrent = service.retryDigest(GROUP_ID);
            assertEquals(RetryResult.ALREADY_IN_PROGRESS, concurrent,
                    "second concurrent retry must be rejected");
            assertEquals(1, digestWorker.executeCount,
                    "the rejected retry must short-circuit before re-invoking the worker");
            map.remove(GROUP_ID);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Reflection setup failed", e);
        }
    }

    @Test
    void retryWithoutSectionsFallsBackToRerun() {
        // Sections empty (pre-V61 row, degraded, zero-post, or
        // crash-stranded) → fallback re-run. Acceptance item 6.
        assertTrue(sectionRepository.findOrderedSections(GROUP_ID, SLOT_FIRED_AT).isEmpty(),
                "fixture: no persisted sections");

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.SUCCESS, result,
                "a section-less slot falls back to the full re-run");
        assertEquals(1, digestWorker.executeCount,
                "the worker must be invoked on the fallback path");
        assertEquals(0, digestDelivery.deliverCalls.size(),
                "DigestDelivery is not invoked on the fallback path — the worker delivers");
    }

    // ----- replay path (persisted sections present, row non-expired) --------

    @Test
    void replaySendsPersistedBytesWithoutRerender() {
        // A non-expired row with persisted sections replays the bytes — no
        // re-collection, no render, no LLM call. The worker is NEVER
        // invoked; DigestDelivery receives the section list as-is.
        // Acceptance item 4.
        pinClockToLiveRow();
        sectionRepository.seedSections(List.of(
                new RenderedSection("security", "section A bytes"),
                new RenderedSection("crypto", "section B bytes"),
                new RenderedSection(null, "section Other bytes")));

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.REPLAYED_MISSING, result);
        assertEquals(0, digestWorker.executeCount,
                "the worker must NOT be invoked on a replay — no re-collection, no render, no LLM");
        assertEquals(1, digestDelivery.deliverCalls.size(),
                "DigestDelivery.deliver is called exactly once");
        RecordingDigestDelivery.DeliverCall call = digestDelivery.deliverCalls.get(0);
        assertEquals(GROUP_ID, call.internalGroupId());
        assertEquals(SLOT_FIRED_AT, call.windowStart());
        assertEquals(List.of(
                new RenderedSection("security", "section A bytes"),
                new RenderedSection("crypto", "section B bytes"),
                new RenderedSection(null, "section Other bytes")),
                call.sections(),
                "replay delivers the persisted bytes verbatim — no re-derivation");
    }

    @Test
    void retryFillsOnlyMissingCategories() {
        // Acceptance item 4: replay sends ONLY the categories with no
        // delivery record, in stored position order. Categories already
        // delivered are skipped.
        pinClockToLiveRow();
        sectionRepository.seedSections(List.of(
                new RenderedSection("security", "section A"),
                new RenderedSection("crypto", "section B"),
                new RenderedSection("ai", "section C"),
                new RenderedSection(null, "section Other")));
        deliveryRepository.seedDelivered(java.util.Set.of("security", "ai"));

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.REPLAYED_MISSING, result);
        assertEquals(0, digestWorker.executeCount);
        assertEquals(List.of(
                new RenderedSection("crypto", "section B"),
                new RenderedSection(null, "section Other")),
                digestDelivery.deliverCalls.get(0).sections(),
                "only the missing categories are replayed, in stored position order");
    }

    @Test
    void replayWhenAllCategoriesAlreadyDelivered_returnsAllAlreadyDelivered() {
        // Acceptance item 5's counter-safety guard: a replay that filters
        // down to zero categories MUST NOT call DigestDelivery
        // (deliverSequenceToGroup's empty-list path would increment the
        // permanent-failure counter). Returns ALL_ALREADY_DELIVERED without
        // touching the delivery seam.
        pinClockToLiveRow();
        sectionRepository.seedSections(List.of(
                new RenderedSection("security", "section A"),
                new RenderedSection("crypto", "section B")));
        deliveryRepository.seedDelivered(java.util.Set.of("security", "crypto"));

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.ALL_ALREADY_DELIVERED, result);
        assertEquals(0, digestWorker.executeCount,
                "no worker invocation for a no-op retry");
        assertTrue(digestDelivery.deliverCalls.isEmpty(),
                "an all-delivered slot MUST NOT reach DigestDelivery — the empty-list "
                        + "counter-safety short-circuit (OutboundDelivery is frozen)");
    }

    @Test
    void replayBatchesOriginalBytesInNormalMode() {
        // M1-734 acceptance item 5, replay branch: the D65 byte-faithful
        // replay re-posts the ORIGINALLY-RENDERED bytes and stays in the
        // mode the slot was rendered in — for a normal-mode slot that means
        // ONE batched re-send, not N per-category messages. The mode travels
        // from the groups row (SELECT_GROUP_FOR_REPLAY) into
        // DigestDelivery.deliver; the missing set is the full section list
        // (a failed batch leaves every slug missing), so the joined re-send
        // reproduces the original batched message byte-for-byte.
        // (The fallback branch's half of the pair is pinned by
        // retryDigest_replacesCacheRow: DigestWorker.execute re-renders in
        // the group's current mode via its own readGroupMetadata.)
        pinClockToLiveRow();
        sectionRepository.seedSections(List.of(
                new RenderedSection("security", "section A bytes"),
                new RenderedSection("crypto", "section B bytes"),
                new RenderedSection(null, "section Other bytes")));

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.REPLAYED_MISSING, result);
        assertEquals(0, digestWorker.executeCount,
                "the replay branch never re-renders");
        assertEquals(1, digestDelivery.deliverCalls.size());
        RecordingDigestDelivery.DeliverCall call = digestDelivery.deliverCalls.get(0);
        assertEquals(DigestMode.NORMAL, call.mode(),
                "a normal-mode slot replays BATCHED — one re-send, not N per-category messages");
        assertEquals(List.of(
                new RenderedSection("security", "section A bytes"),
                new RenderedSection("crypto", "section B bytes"),
                new RenderedSection(null, "section Other bytes")),
                call.sections(),
                "the full section list re-sends — the join reproduces the original bytes");
    }

    @Test
    void replayKeepsPerCategoryDeliveryInFullMode() {
        // M1-734 acceptance item 5, replay branch twin: a full-mode slot
        // keeps the D63 per-category replay — DigestDelivery receives FULL,
        // so the missing categories go out as individual messages.
        service.dataSource = stubDataSource(false, "full");
        pinClockToLiveRow();
        sectionRepository.seedSections(List.of(
                new RenderedSection("security", "section A bytes"),
                new RenderedSection("crypto", "section B bytes")));

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.REPLAYED_MISSING, result);
        assertEquals(1, digestDelivery.deliverCalls.size());
        assertEquals(DigestMode.FULL, digestDelivery.deliverCalls.get(0).mode(),
                "a full-mode slot replays PER-CATEGORY — the D63 framing is preserved");
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * Pin the service clock so EXPIRES_AT is in the future, putting the row
     * on the replay path (non-expired). The cache row's expires_at is
     * 2026-05-26T08:15:00Z; pinning now to 2026-05-26T07:45:00Z makes
     * expires_at 30 minutes ahead — the replay gate's
     * {@code coords.expiresAt.isAfter(now)} passes.
     */
    private void pinClockToLiveRow() {
        service.clock = Clock.fixed(SLOT_FIRED_AT, ZoneOffset.UTC);
    }

    // ----- stubs ------------------------------------------------------------

    static class RecordingDigestWorker extends DigestWorker {
        int executeCount = 0;
        DigestSlot lastSlot;
        SlotOutcome outcome = SlotOutcome.RAN;

        @Override
        public SlotOutcome execute(DigestSlot slot) {
            executeCount++;
            lastSlot = slot;
            return outcome;
        }

        @Override
        public void onDigestSlot(DigestSlot slot) {
            // no-op: tests drive execute(...) directly
        }
    }

    /** Captures {@code deliver()} calls so replay tests can assert the section list. */
    static final class RecordingDigestDelivery extends DigestDelivery {
        final List<DeliverCall> deliverCalls = new ArrayList<>();

        @Override
        public void deliver(MessagingAdapter adapter, String upstreamGroupId,
                            UUID internalGroupId, Instant windowStart,
                            List<RenderedSection> sections, DigestMode mode) {
            deliverCalls.add(new DeliverCall(adapter, upstreamGroupId,
                    internalGroupId, windowStart, List.copyOf(sections), mode));
        }

        record DeliverCall(MessagingAdapter adapter, String upstreamGroupId,
                           UUID internalGroupId, Instant windowStart,
                           List<RenderedSection> sections, DigestMode mode) {}
    }

    static final class StubAdapterRegistry extends app.zcat.infochat.provider.messaging.AdapterRegistry {
        private final List<MessagingAdapter> adapters;

        StubAdapterRegistry(String... names) {
            this.adapters = java.util.Arrays.stream(names)
                    .map(n -> new NameOnlyAdapter(n))
                    .map(a -> (MessagingAdapter) a)
                    .toList();
        }

        @Override
        public List<MessagingAdapter> activatedAdapters() { return adapters; }
    }

    static final class NameOnlyAdapter implements MessagingAdapter {
        private final String name;
        NameOnlyAdapter(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public app.zcat.infochat.messaging.CapabilityFlags capabilities() { return null; }
        @Override public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() {
            return app.zcat.infochat.messaging.AdapterTrustLevel.HIGH;
        }
        @Override public boolean isWellFormedContactId(String contactId) { return true; }
        @Override public MessageHandle send(OutboundMessage msg) { return null; }
        @Override public void update(MessageHandle h, String b) {}
        @Override public void finalizeMessage(MessageHandle h, String b) {}
        @Override public void setTyping(app.zcat.infochat.messaging.ScopeRef s, boolean t) {}
        @Override public void setInboundHandler(InboundHandler h) {}
    }

    /**
     * Stub DataSource for DigestRetryService. Handles two SQL patterns:
     * 1. SELECT slot_kind, slot_fired_at, expires_at FROM summary_cache ...
     * 2. SELECT timezone, adapter, upstream_group_id, digest_mode FROM groups ...
     */
    private static DataSource stubDataSource(boolean isDegraded) {
        return stubDataSource(isDegraded, "normal");
    }

    private static DataSource stubDataSource(boolean isDegraded, String digestMode) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "prepareStatement" -> {
                                String sql = (String) args[0];
                                yield stubPs(sql, isDegraded, digestMode);
                            }
                            case "close" -> null;
                            default -> throw new UnsupportedOperationException(
                                    "Conn." + method.getName());
                        });
            }

            @Override public Connection getConnection(String u, String p) { return getConnection(); }
            @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
            @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
            @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
            @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
            @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static PreparedStatement stubPs(String sql, boolean isDegraded, String digestMode) {
        boolean isCacheQuery = sql.contains("summary_cache") && sql.contains("SELECT");
        boolean isGroupQuery = sql.contains("FROM groups");
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setObject", "setTimestamp" -> null;
                    case "executeQuery" -> {
                        if (isCacheQuery) yield cacheResultSet(isDegraded);
                        if (isGroupQuery) yield groupResultSet(digestMode);
                        yield emptyResultSet();
                    }
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "PS." + method.getName());
                });
    }

    private static ResultSet cacheResultSet(boolean isDegraded) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getString" -> SLOT_KIND;
                    case "getTimestamp" -> {
                        String col = (String) args[0];
                        yield switch (col) {
                            case "slot_fired_at" -> Timestamp.from(SLOT_FIRED_AT);
                            case "expires_at" -> Timestamp.from(EXPIRES_AT);
                            default -> throw new UnsupportedOperationException("col: " + col);
                        };
                    }
                    case "getBoolean" -> isDegraded;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet groupResultSet(String digestMode) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getString" -> {
                        String col = (String) args[0];
                        yield switch (col) {
                            case "timezone" -> GROUP_TIMEZONE;
                            case "adapter" -> ADAPTER_NAME;
                            case "upstream_group_id" -> UPSTREAM_GROUP_ID;
                            case "digest_mode" -> digestMode;
                            default -> throw new UnsupportedOperationException("col: " + col);
                        };
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }
}
