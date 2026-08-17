package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named acceptance test for M1-611: two operations publishing concurrently
 * into the SAME scope must not clobber each other's progress placeholder.
 *
 * <p>Each inbound dispatch is its own {@code @RequestScoped}
 * {@link InboundContext} with its own {@link InboundContext#operationId()};
 * production resolves that per-request through a CDI proxy on a single
 * {@link StageProgressNotifier}. This plain-JUnit test models the same shape
 * by pointing the notifier's {@code inboundContext} field at whichever
 * operation's context is "active" for the call it is about to make — the
 * faithful single-threaded stand-in for the proxy's dynamic dispatch.</p>
 *
 * <p>The vehicle is a {@link HandleRecordingAdapter} that records the
 * {@link MessageHandle} on every {@code update}/{@code finalizeMessage} — the
 * shared {@link RecordingMessagingAdapter} discards it, so it cannot attribute
 * an edit to a specific placeholder. Attribution is exactly what these
 * assertions need: an edit for operation A must land on A's handle, never
 * B's.</p>
 */
class ConcurrentSameScopeProgressTest {

    private static final ScopeRef SCOPE = new ScopeRef.Group("concurrent-scope-group");
    private static final String ADAPTER = "inmemory";

    private HandleRecordingAdapter adapter;
    private BundleLoader bundleLoader;
    private StageProgressNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new HandleRecordingAdapter();
        bundleLoader = newRealBundleLoader();
        notifier = newNotifier(adapter, bundleLoader);
    }

    @Test
    void concurrentSameScopeOperationsKeepUpdatesAndFinalizesOnTheirOwnHandle() {
        InboundContext opA = newOperation();
        InboundContext opB = newOperation();

        // Interleave two operations into ONE scope: both acquire a
        // placeholder, both emit a stage update, then both finalize.
        activate(opA);
        notifier.publish(SCOPE, ProgressStage.STARTED);      // placeholder A
        activate(opB);
        notifier.publish(SCOPE, ProgressStage.STARTED);      // placeholder B
        activate(opA);
        notifier.publish(SCOPE, ProgressStage.GENERATING);   // update -> A's handle
        activate(opB);
        notifier.publish(SCOPE, ProgressStage.GENERATING);   // update -> B's handle
        activate(opA);
        boolean aDelivered = notifier.completeDelivered(SCOPE, "A-REPLY");
        activate(opB);
        boolean bDelivered = notifier.completeDelivered(SCOPE, "B-REPLY");

        assertEquals(2, adapter.sends.size(), "each operation acquires its own placeholder");
        MessageHandle handleA = adapter.sentHandles.get(0);
        MessageHandle handleB = adapter.sentHandles.get(1);
        assertNotEquals(handleA, handleB, "the two placeholders are distinct handles");

        // No update lands on the other operation's handle: A's single update
        // is on A's handle, B's on B's.
        assertEquals(List.of(handleA), handlesOf(adapter.updates, handleA),
                "operation A's stage update must edit A's placeholder only");
        assertEquals(List.of(handleB), handlesOf(adapter.updates, handleB),
                "operation B's stage update must edit B's placeholder only");

        // Each placeholder's finalize carries its OWN operation's text.
        assertEquals(List.of("A-REPLY"), bodiesFinalizedOn(handleA),
                "A's placeholder is finalized with A's reply, never B's");
        assertEquals(List.of("B-REPLY"), bodiesFinalizedOn(handleB),
                "B's placeholder is finalized with B's reply, never A's");

        // Both final texts reach the adapter exactly once, and the delivery
        // outcome is reported per operation (M1-607 gate stays correct).
        assertEquals(1, totalOutboundOccurrences("A-REPLY"), "A's reply is delivered exactly once");
        assertEquals(1, totalOutboundOccurrences("B-REPLY"), "B's reply is delivered exactly once");
        assertTrue(aDelivered, "A's completeDelivered reports A's own delivery outcome");
        assertTrue(bDelivered, "B's completeDelivered reports B's own delivery outcome");
    }

    @Test
    void concurrentTerminalsInReverseOrderNeverFinalizeTheOtherOperationsPlaceholder() {
        InboundContext opA = newOperation();
        InboundContext opB = newOperation();

        activate(opA);
        notifier.publish(SCOPE, ProgressStage.STARTED);      // placeholder A
        activate(opB);
        notifier.publish(SCOPE, ProgressStage.STARTED);      // placeholder B
        // Reverse terminal order: B finalizes first. Under the old scope-only
        // keying B's terminal would remove the shared entry and finalize A's
        // placeholder with B's text — the bug this ticket fixes.
        activate(opB);
        notifier.completeDelivered(SCOPE, "B-REPLY");
        activate(opA);
        notifier.completeDelivered(SCOPE, "A-REPLY");

        MessageHandle handleA = adapter.sentHandles.get(0);
        MessageHandle handleB = adapter.sentHandles.get(1);
        assertEquals(List.of("A-REPLY"), bodiesFinalizedOn(handleA),
                "A's placeholder is finalized with A's reply even when B terminated first");
        assertEquals(List.of("B-REPLY"), bodiesFinalizedOn(handleB),
                "B's placeholder is finalized with B's reply");
        assertEquals(1, totalOutboundOccurrences("A-REPLY"), "A's reply is delivered exactly once");
        assertEquals(1, totalOutboundOccurrences("B-REPLY"), "B's reply is delivered exactly once");
    }

    @Test
    void abandonedOperationTerminatesOnlyItsOwnPlaceholderNotALiveConcurrentOne() {
        InboundContext opA = newOperation();
        InboundContext opB = newOperation();

        activate(opA);
        notifier.publish(SCOPE, ProgressStage.STARTED);      // placeholder A
        activate(opB);
        notifier.publish(SCOPE, ProgressStage.STARTED);      // placeholder B

        // Operation A abandons without a terminal: its request-end drain must
        // finalize ONLY A's placeholder (with the failed string) and must not
        // touch B's live placeholder (M1-334 + M1-611).
        activate(opA);
        opA.drainAbandonedProgress();

        MessageHandle handleA = adapter.sentHandles.get(0);
        MessageHandle handleB = adapter.sentHandles.get(1);
        String failed = bundleLoader.get(BundleKeys.PROGRESS_FAILED, "en");
        assertEquals(List.of(failed), bodiesFinalizedOn(handleA),
                "A's abandoned placeholder is finalized with the failed string");
        assertEquals(List.of(), bodiesFinalizedOn(handleB),
                "B's live placeholder must NOT be finalized by A's abandon");

        // B then completes normally: its own placeholder finalizes with its
        // own reply, untouched by A's drain.
        activate(opB);
        boolean bDelivered = notifier.completeDelivered(SCOPE, "B-REPLY");

        assertTrue(bDelivered, "B still delivers after A abandoned");
        assertEquals(List.of("B-REPLY"), bodiesFinalizedOn(handleB),
                "B's placeholder finalizes with B's reply, not A's failed string");
        assertEquals(1, totalOutboundOccurrences("B-REPLY"), "B's reply is delivered exactly once");
    }

    // ----- assertion helpers -----------------------------------------------

    /** Point the notifier at the operation whose dispatch is currently running. */
    private void activate(InboundContext operation) {
        notifier.inboundContext = operation;
    }

    private List<MessageHandle> handlesOf(List<HandleRecordingAdapter.Edit> edits, MessageHandle handle) {
        List<MessageHandle> matched = new ArrayList<>();
        for (HandleRecordingAdapter.Edit edit : edits) {
            if (edit.handle().equals(handle)) {
                matched.add(edit.handle());
            }
        }
        return matched;
    }

    private List<String> bodiesFinalizedOn(MessageHandle handle) {
        List<String> bodies = new ArrayList<>();
        for (HandleRecordingAdapter.Edit edit : adapter.finalizes) {
            if (edit.handle().equals(handle)) {
                bodies.add(edit.body());
            }
        }
        return bodies;
    }

    private long totalOutboundOccurrences(String body) {
        long occurrences = adapter.sends.stream().filter(body::equals).count();
        occurrences += adapter.updates.stream().map(HandleRecordingAdapter.Edit::body)
                .filter(body::equals).count();
        occurrences += adapter.finalizes.stream().map(HandleRecordingAdapter.Edit::body)
                .filter(body::equals).count();
        return occurrences;
    }

    // ----- wiring ----------------------------------------------------------

    private InboundContext newOperation() {
        InboundContext context = new InboundContext();
        context.setAdapterName(ADAPTER);
        return context;
    }

    private static StageProgressNotifier newNotifier(MessagingAdapter adapter, BundleLoader bundleLoader) {
        StageProgressNotifier notifier = new StageProgressNotifier();
        notifier.adapterRegistry = new AdapterRegistry() {
            @Override
            public List<MessagingAdapter> activatedAdapters() {
                return List.of(adapter);
            }
        };
        notifier.bundleLoader = bundleLoader;
        // Zero floor so every non-terminal stage lands as an observable
        // update — the coalescing cadence is not what this test pins.
        notifier.minEditIntervalMs = 0;
        notifier.outboundDelivery = TestOutboundDelivery.passThrough();
        return notifier;
    }

    /**
     * Recording {@link MessagingAdapter} that keeps the {@link MessageHandle}
     * on every edit, so a test can prove an operation's update/finalize landed
     * on its own placeholder and not a concurrent operation's. Kept local to
     * this test (rather than extending the shared
     * {@link RecordingMessagingAdapter}) so the single-operation contract the
     * shared double pins stays byte-for-byte unchanged.
     */
    static final class HandleRecordingAdapter implements MessagingAdapter {

        record Edit(MessageHandle handle, String body) {}

        final List<String> sends = new ArrayList<>();
        final List<MessageHandle> sentHandles = new ArrayList<>();
        final List<Edit> updates = new ArrayList<>();
        final List<Edit> finalizes = new ArrayList<>();
        private final AtomicInteger handleIds = new AtomicInteger();

        @Override
        public String name() {
            return ADAPTER;
        }

        @Override
        public boolean isWellFormedContactId(String contactId) {
            return true;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            MessageHandle handle = new MessageHandle("h-" + handleIds.incrementAndGet());
            sends.add(msg.text());
            sentHandles.add(handle);
            return handle;
        }

        @Override
        public void update(MessageHandle handle, String body) {
            updates.add(new Edit(handle, body));
        }

        @Override
        public void finalizeMessage(MessageHandle handle, String body) {
            finalizes.add(new Edit(handle, body));
        }

        @Override
        public void setTyping(ScopeRef scope, boolean isTyping) {
            // Not asserted by this test — the placeholder/finalize handle
            // attribution is what M1-611 pins.
        }

        @Override
        public CapabilityFlags capabilities() {
            return new CapabilityFlags(
                    /* supportsMentionByContactId */ false,
                    /* supportsMembershipEvents    */ false,
                    /* supportsCodeFormatting      */ false,
                    /* supportsMarkdownLinks       */ false,
                    /* maxInboundMessageBytes      */ 65536,
                    /* maxSendsPerSecond           */ 1,
                    /* supportsMessageEdit         */ true,
                    /* supportsLiveText            */ false,
                    /* supportsTypingIndicator     */ true,
                    /* minEditInterval             */ Duration.ZERO,
                    /* supportsOutboundAttachments */ false,
                    /* maxOutboundAttachmentBytes  */ 0);
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
