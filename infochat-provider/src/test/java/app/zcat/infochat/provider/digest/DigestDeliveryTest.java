package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit tests for {@link DigestDelivery}. Drives the full
 * {@link OutboundDelivery} chokepoint (real retry, real counter, real
 * soft-remove logic) against a scriptable adapter, so the per-category
 * delivery contract is pinned without a transport or database.
 *
 * <p>Configuration mirrors the {@code laptop}/base profile: 3 attempts
 * (original + two retries), permanent-failure threshold 3 — the threshold
 * whose "always &gt; 1" invariant a naive per-category deliverToGroup loop
 * would break.
 */
class DigestDeliveryTest {

    private static final String ADAPTER_NAME = "inmemory";
    private static final String UPSTREAM_GROUP_ID = "group-upstream";

    private static OutboundDelivery newDelivery(RecordingRepo repo) {
        // Public 6-arg constructor (the package-private Sleeper seam is not
        // accessible from this package). base-delay 0 + growth 2.0 keeps the
        // back-off envelope at zero — Thread::sleep(0) is a no-op — so the
        // retry loop runs without waiting. The recording/scripted adapters
        // never trigger real back-off in these scenarios (failures are
        // immediate PERMANENT or one-shot TRANSIENT-then-success).
        return new OutboundDelivery(new ThrottledAdminNotifier(), repo, 3, 0L, 2.0, 3);
    }

    private static RenderedSection section(String tag, String text) {
        return new RenderedSection(tag, text);
    }

    /** Build the correlationId {@link DigestDelivery} mints for a (groupId, windowStart, slug). */
    private static String correlationId(UUID groupId, Instant windowStart, String slug) {
        return "digest-" + groupId + "-" + windowStart + "-" + slug;
    }

    @Test
    void splitsOnCategoryBoundariesNotSize() {
        // A non-degraded digest produces one OutboundMessage per category,
        // bounded at categories+1 (the +1 is the Other bucket, present only
        // when non-empty). DigestDelivery never merges two categories into
        // one message and never splits a category across two.
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo());
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        List<RenderedSection> sections = List.of(
                section("security", "a".repeat(100)),
                section("crypto", "b".repeat(10_000)),
                section(null, "other-text"));

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, sections);

        assertEquals(3, adapter.sent.size(),
                "one OutboundMessage per category — bounded at categories+1, never size-merged");
        assertEquals("a".repeat(100), adapter.sent.get(0).text());
        assertEquals("b".repeat(10_000), adapter.sent.get(1).text());
        assertEquals("other-text", adapter.sent.get(2).text());
    }

    @Test
    void sendsCategoriesSequentiallyInSectionOrder() {
        // Category messages go out SEQUENTIALLY in section order (D62 order:
        // count desc, alpha ties, Other last) — never fanned out in parallel.
        // Sequential order is what makes affordance-on-last deterministic
        // and preserves the digest's narrative order. A parallel
        // implementation would pass every other listed test.
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo());
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        List<RenderedSection> sections = List.of(
                section("security", "section A"),
                section("crypto", "section B"),
                section("ai", "section C"),
                section(null, "section Other"));

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, sections);

        assertEquals(List.of("section A", "section B", "section C", "section Other"),
                adapter.sent.stream().map(OutboundMessage::text).toList(),
                "sends match section order exactly — never fanned out in parallel");
    }

    @Test
    void retriesFailedCategoryMessageIndependently() {
        // Each category message runs the existing per-message TRANSIENT-
        // retry / PERMANENT-abort ladder independently. A TRANSIENT failure
        // on one category retries ONLY that message; the other categories
        // are unaffected.
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        // Script the "security" category: one TRANSIENT then succeed.
        adapter.script(correlationId(groupId, windowStart, "security"),
                FailureCategory.TRANSIENT);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo());

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, List.of(
                section("security", "section A"),
                section("crypto", "section B"),
                section(null, "section Other")));

        assertEquals(3, adapter.sent.size(),
                "all three categories delivered — the transient failure retried, not aborted");
        assertEquals(2, adapter.attemptsByCorrelationId.get(
                correlationId(groupId, windowStart, "security")).get(),
                "the transient-failing category was attempted twice (one fail, one succeed)");
        assertEquals(1, adapter.attemptsByCorrelationId.get(
                correlationId(groupId, windowStart, "crypto")).get(),
                "other categories were attempted exactly once");
    }

    @Test
    void oneFailedDigestNeverSoftRemovesGroup() {
        // The crux: a single transport blip during the sequential loop
        // yields N PERMANENT failures (SimpleX classifies a send on a
        // closed/not-started WebSocket as IMMEDIATE PERMANENT). Naive
        // per-category deliverToGroup calls would attribute N consecutive
        // permanent failures to the group and soft-remove it (threshold 3)
        // — silently, with no admin notification. deliverSequenceToGroup
        // collapses the whole slot into ONE aggregate outcome, preserving
        // the threshold's "always > 1" invariant.
        RecordingRepo repo = new RecordingRepo();
        DigestDelivery digestDelivery = wiredDelivery(repo);
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        // 5 all-permanent messages — well past threshold 3.
        ScriptedAdapter adapter = ScriptedAdapter.allFailing(
                ADAPTER_NAME, FailureCategory.PERMANENT);

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, List.of(
                section("security", "A"),
                section("crypto", "B"),
                section("ai", "C"),
                section("news", "D"),
                section(null, "Other")));

        assertTrue(repo.removed.isEmpty(),
                "one slot of N permanent failures must NOT soft-remove the group — "
                        + "deliverSequenceToGroup attributes at most ONE aggregate counter "
                        + "outcome per slot, preserving the threshold-3 invariant");
    }

    @Test
    void retryRepostsEveryCategory() {
        // Nothing in v1 records which categories were delivered, so a
        // /retry --digest re-runs the slot and re-posts every category it
        // produces, including any that already landed. Each call to deliver
        // is independent and produces a fresh N sends — no dedup.
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo());
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        List<RenderedSection> sections = List.of(
                section("security", "A"),
                section("crypto", "B"),
                section(null, "Other"));

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, sections);
        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, sections);

        assertEquals(6, adapter.sent.size(),
                "two /retry --digest calls re-post every category each time — no dedup");
    }

    @Test
    void partialFailureDeliversRemainingCategories() {
        // A PERMANENT failure on one category still delivers the others.
        // The sequence continues past the permanent abort; partial success
        // is visible (≥1 delivered), not all-or-nothing.
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        // First category fails PERMANENT; the rest succeed.
        adapter.script(correlationId(groupId, windowStart, "security"),
                FailureCategory.PERMANENT);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo());

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, List.of(
                section("security", "section A"),
                section("crypto", "section B"),
                section(null, "section Other")));

        assertEquals(List.of("section B", "section Other"),
                adapter.sent.stream().map(OutboundMessage::text).toList(),
                "a PERMANENT on one category still delivers the others");
    }

    @Test
    void recordsDeliveryOnlyOnAdapterAcceptance() {
        // M1-652 acceptance item 3: a category message the adapter ACCEPTS
        // records a digest_category_delivery row; a failed send records
        // nothing, so the existing per-category ladder is unchanged. One
        // category fails PERMANENT, the rest succeed — only the successful
        // categories' slugs land in the delivery record.
        RecordingDeliveryRepo deliveryRepo = new RecordingDeliveryRepo();
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        // "security" fails PERMANENT on the first attempt and never lands;
        // the other three succeed on the first try.
        adapter.script(correlationId(groupId, windowStart, "security"),
                FailureCategory.PERMANENT);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo(), deliveryRepo);

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, List.of(
                section("security", "section A"),
                section("crypto", "section B"),
                section("ai", "section C"),
                section(null, "section Other")));

        assertEquals(Set.of("crypto", "ai", "other"),
                deliveryRepo.recordedSlugs(groupId, windowStart),
                "only accepted categories record a delivery — the PERMANENT-failing "
                        + "category records nothing");
    }

    @Test
    void recordsDeliveryOnceForRetriedThenSuccessfulCategory() {
        // A TRANSIENT-then-successful category throws on the failed attempt
        // (no recording) and returns once on the retry (one recording). The
        // delivery record fires exactly once per delivered message, not per
        // attempt — the recording seam runs inside the wrapper's send() on
        // normal return only.
        RecordingDeliveryRepo deliveryRepo = new RecordingDeliveryRepo();
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        adapter.script(correlationId(groupId, windowStart, "security"),
                FailureCategory.TRANSIENT);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo(), deliveryRepo);

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, List.of(
                section("security", "section A")));

        assertEquals(1, deliveryRepo.recordCount(groupId, windowStart, "security"),
                "a retried-then-successful category records exactly once");
    }

    @Test
    void deliveryRecordWriteFailure_doesNotAbortRemainingCategories() {
        // A record-write failure degrades to the spec-sanctioned duplicate
        // on later replay (D64 at-least-once) — propagation would abort the
        // remaining categories, the very defect this ticket fixes. The
        // wrapper catches, logs, and continues; every category still sends.
        RecordingDeliveryRepo deliveryRepo = RecordingDeliveryRepo.failingAlways();
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo(), deliveryRepo);

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, List.of(
                section("security", "A"),
                section("crypto", "B"),
                section(null, "Other")));

        assertEquals(3, adapter.sent.size(),
                "all three categories still delivered — a record-write failure must NOT "
                        + "abort the remaining categories");
    }

    @Test
    void appendsClosingAffordanceOnlyToFinalMessage() {
        // The closing affordance is folded into the LAST section's text
        // inside renderSections() (M1-652 fork closed, arm (b), 2026-07-20).
        // DigestDelivery appends nothing and preserves section order, so
        // only the final category message carries the affordance at the
        // wire — never once per message.
        String affordance =
                "@mention me to go deeper on any story, or ask about a topic you don't see here.";
        ScriptedAdapter adapter = new ScriptedAdapter(ADAPTER_NAME);
        DigestDelivery digestDelivery = wiredDelivery(new RecordingRepo());
        UUID groupId = UUID.randomUUID();
        Instant windowStart = Instant.now();
        // The LAST section's text already ends with the affordance (folded
        // inside renderSections); the others carry none.
        List<RenderedSection> sections = List.of(
                section("security", "section A"),
                section("crypto", "section B"),
                section(null, "section Other\n\n" + affordance));

        digestDelivery.deliver(adapter, UPSTREAM_GROUP_ID, groupId, windowStart, sections);

        assertEquals(3, adapter.sent.size());
        for (int i = 0; i < adapter.sent.size() - 1; i++) {
            assertFalse(adapter.sent.get(i).text().contains(affordance),
                    "non-final category message must not carry the affordance: "
                            + adapter.sent.get(i).text());
        }
        assertTrue(adapter.sent.getLast().text().endsWith(affordance),
                "the final category message carries the affordance, folded into the section text");
    }

    // ----- helpers and test doubles -----------------------------------------

    private static DigestDelivery wiredDelivery(RecordingRepo repo) {
        DigestDelivery digestDelivery = new DigestDelivery();
        digestDelivery.outboundDelivery = newDelivery(repo);
        digestDelivery.deliveryRepository = new RecordingDeliveryRepo();
        return digestDelivery;
    }

    private static DigestDelivery wiredDelivery(RecordingRepo repo, RecordingDeliveryRepo deliveryRepo) {
        DigestDelivery digestDelivery = new DigestDelivery();
        digestDelivery.outboundDelivery = newDelivery(repo);
        digestDelivery.deliveryRepository = deliveryRepo;
        return digestDelivery;
    }

    /**
     * Records {@link GroupRepository#markRemovedAudited(UUID, String)} calls
     * so the soft-remove path can be asserted without a database or audit
     * writer. The {@code null} datasource is never touched because
     * {@code markRemovedAudited} is overridden.
     */
    static final class RecordingRepo extends GroupRepository {
        final List<UUID> removed = new ArrayList<>();

        RecordingRepo() { super(null); }

        @Override
        public void markRemovedAudited(UUID groupId, String adapter) {
            removed.add(groupId);
        }
    }

    /**
     * Recording {@link DigestCategoryDeliveryRepository} stub: captures
     * {@code recordDelivery} calls keyed by (groupId, windowStart, slug)
     * without touching the DB; can be made to throw on every write to pin
     * the catch-and-log path.
     */
    static final class RecordingDeliveryRepo extends DigestCategoryDeliveryRepository {
        private final Map<String, Integer> counts = new HashMap<>();
        private final boolean fail;

        RecordingDeliveryRepo() { this(false); }

        private RecordingDeliveryRepo(boolean fail) { this.fail = fail; }

        static RecordingDeliveryRepo failingAlways() { return new RecordingDeliveryRepo(true); }

        Set<String> recordedSlugs(UUID groupId, Instant windowStart) {
            Set<String> slugs = new HashSet<>();
            String prefix = groupId + "|" + windowStart + "|";
            for (String key : counts.keySet()) {
                if (key.startsWith(prefix)) {
                    slugs.add(key.substring(prefix.length()));
                }
            }
            return slugs;
        }

        int recordCount(UUID groupId, Instant windowStart, String slug) {
            return counts.getOrDefault(groupId + "|" + windowStart + "|" + slug, 0);
        }

        @Override
        public void recordDelivery(UUID groupId, Instant windowStart, String categorySlug)
                throws SQLException {
            if (fail) {
                throw new SQLException("scripted record-write failure");
            }
            String key = groupId + "|" + windowStart + "|" + categorySlug;
            counts.merge(key, 1, Integer::sum);
        }
    }

    /**
     * Adapter that records every successful send and lets a test script
     * per-correlationId failure sequences. A correlationId with no script
     * entry succeeds immediately; a script deque drains one
     * {@link FailureCategory} per attempt, so a single TRANSIENT entry
     * fails once then succeeds on retry, two TRANSIENT entries fail twice
     * then succeed, a single PERMANENT aborts after one attempt.
     * {@link #allFailing(String, FailureCategory)} produces an adapter
     * whose every send fails with a fixed category.
     */
    static final class ScriptedAdapter implements MessagingAdapter {
        private final String name;
        final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();
        final Map<String, Deque<FailureCategory>> scripts = new HashMap<>();
        final Map<String, AtomicInteger> attemptsByCorrelationId = new HashMap<>();
        private FailureCategory failAllWith = null;

        ScriptedAdapter(String name) { this.name = name; }

        static ScriptedAdapter allFailing(String name, FailureCategory failWith) {
            ScriptedAdapter adapter = new ScriptedAdapter(name);
            adapter.failAllWith = failWith;
            return adapter;
        }

        void script(String correlationId, FailureCategory... sequence) {
            scripts.put(correlationId, new ArrayDeque<>(List.of(sequence)));
        }

        @Override
        public MessageHandle send(OutboundMessage msg) throws MessagingException {
            attemptsByCorrelationId.computeIfAbsent(msg.correlationId(), k -> new AtomicInteger())
                    .incrementAndGet();
            if (failAllWith != null) {
                throw new MessagingException(failAllWith,
                        "scripted " + failAllWith + " for " + msg.correlationId());
            }
            Deque<FailureCategory> q = scripts.get(msg.correlationId());
            if (q != null && !q.isEmpty()) {
                FailureCategory fc = q.poll();
                throw new MessagingException(fc,
                        "scripted " + fc + " for " + msg.correlationId());
            }
            sent.add(msg);
            return new MessageHandle("h-" + msg.correlationId());
        }

        @Override public String name() { return name; }
        @Override public boolean isWellFormedContactId(String contactId) { return true; }
        @Override public void update(MessageHandle h, String b) { }
        @Override public void finalizeMessage(MessageHandle h, String b) { }
        @Override public void setTyping(ScopeRef s, boolean t) { }
        @Override public void setInboundHandler(InboundHandler h) { }
        @Override public CapabilityFlags capabilities() { return null; }
        @Override public AdapterTrustLevel trustLevel() { return AdapterTrustLevel.HIGH; }
    }
}
