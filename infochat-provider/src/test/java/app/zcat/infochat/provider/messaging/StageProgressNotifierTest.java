package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit (no Quarkus boot) tests for {@link StageProgressNotifier},
 * the concrete provider-side {@link app.zcat.infochat.messaging.ProgressNotifier}.
 * Drives the notifier against a {@link RecordingMessagingAdapter} wired
 * through an {@link AdapterRegistry} whose {@code activatedAdapters()} is
 * overridden to return the recording double, with the bound adapter
 * resolved by {@link InboundContext#adapterName()}.
 *
 * <p>Pins {@code docs/spec/messaging.md} §Progress notifications steps
 * 1–4 plus the two cross-cutting constraints (edit-coalescing cadence;
 * no user input in stage strings):</p>
 * <ul>
 *   <li>Step 1+2: first publish sends a placeholder and turns typing on;</li>
 *   <li>Step 3: stage edits coalesce within the floor window;</li>
 *   <li>Step 4: terminal {@code complete} finalizes with the real text
 *       and turns typing off; {@code fail} finalizes with the localized
 *       failure string;</li>
 *   <li>Security: stage strings resolve from the D43 bundle and never
 *       interpolate user-authored text.</li>
 *   <li>{@code deliverFresh} routes LLM-authored /summary bodies through
 *       the M1-794 empty-body seam: a blank body is refused (WARN, no
 *       transport call), a non-blank one ships unchanged (M1-795).</li>
 * </ul>
 */
class StageProgressNotifierTest {

    private static final ScopeRef SCOPE = new ScopeRef.Dm("npc-contact-id");

    private RecordingMessagingAdapter adapter;
    private BundleLoader bundleLoader;
    private StageProgressNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new RecordingMessagingAdapter();
        bundleLoader = newRealBundleLoader();
        notifier = newNotifier(adapter, bundleLoader, /* floorMs */ 600);
    }

    @Test
    void firstPublishSendsLocalizedPlaceholderAndTurnsTypingOn() {
        notifier.publish(SCOPE, ProgressStage.STARTED);

        assertEquals(1, adapter.sends.size(), "first publish must send exactly one placeholder");
        assertEquals(bundleLoader.get(BundleKeys.PROGRESS_STARTED), adapter.sends.get(0),
                "placeholder body is the localized STARTED stage string");
        assertEquals(1, adapter.typing.size(), "typing must be toggled exactly once (on)");
        assertTrue(adapter.typing.get(0).typing(), "first publish turns typing ON");
        assertEquals(SCOPE, adapter.typing.get(0).scope());
        assertEquals(0, adapter.finalizes.size(), "no finalize before a terminal call");
    }

    @Test
    void completeFinalizesPlaceholderWithRealTextAndTurnsTypingOff() {
        notifier.publish(SCOPE, ProgressStage.STARTED);
        notifier.complete(SCOPE, "THE REAL SUMMARY BODY");

        // Placeholder was sent once; the summary is delivered via
        // finalizeMessage (NOT a second send) — one visibly-evolving msg.
        assertEquals(1, adapter.sends.size(), "no extra send on complete — finalize edits in place");
        assertEquals(List.of("THE REAL SUMMARY BODY"), adapter.finalizes,
                "the finalized body must be the real summary text, not a stage label");
        assertEquals(2, adapter.typing.size(), "typing toggled twice (on then off)");
        assertFalse(adapter.typing.get(1).typing(), "terminal complete turns typing OFF");
    }

    @Test
    void failFinalizesWithLocalizedFailureStringAndTurnsTypingOff() {
        notifier.publish(SCOPE, ProgressStage.STARTED);
        notifier.fail(SCOPE);

        assertEquals(List.of(bundleLoader.get(BundleKeys.PROGRESS_FAILED)), adapter.finalizes,
                "fail() finalizes with the localized failure string from the bundle");
        assertFalse(adapter.typing.get(adapter.typing.size() - 1).typing(),
                "terminal fail turns typing OFF");
    }

    @Test
    void terminalCompleteWithoutPriorPublishSendsFinalTextSoUserStillGetsOutput() {
        // No placeholder acquired (publish never ran). The terminal text
        // must still reach the user — delivered as a fresh send.
        notifier.complete(SCOPE, "STANDALONE SUMMARY");

        assertEquals(List.of("STANDALONE SUMMARY"), adapter.sends,
                "complete() with no placeholder delivers the final text via send()");
        assertEquals(0, adapter.finalizes.size(), "nothing to finalize without a handle");
        assertEquals(0, adapter.typing.size(),
                "typing was never turned on, so it is not toggled off");
    }

    @Test
    void abandonedPublishIsDrainedAtRequestEndTurningTypingOffAndClearingState() {
        // The handler published a placeholder but threw / abandoned before a
        // terminal complete()/fail(). The @RequestScoped InboundContext's
        // request-end drain must finalize the dangling placeholder with the
        // failed string and turn typing OFF (M1-334).
        notifier.publish(SCOPE, ProgressStage.STARTED);
        assertEquals(1, adapter.sends.size(), "publish sent the placeholder");
        assertTrue(adapter.typing.get(0).typing(), "publish turned typing ON");

        notifier.inboundContext.drainAbandonedProgress();

        assertEquals(List.of(bundleLoader.get(BundleKeys.PROGRESS_FAILED)), adapter.finalizes,
                "request-end drain finalizes the abandoned placeholder with the failed string");
        assertFalse(adapter.typing.get(adapter.typing.size() - 1).typing(),
                "request-end drain turns typing OFF");

        // The states entry is gone: a subsequent publish in the same scope
        // sends a FRESH placeholder rather than updating the stale handle.
        notifier.publish(SCOPE, ProgressStage.STARTED);
        assertEquals(2, adapter.sends.size(),
                "next publish after drain sends a fresh placeholder, not an update of the stale handle");
    }

    @Test
    void requestEndDrainIsNoopAfterNormalCompleteSoTheLifecycleIsUnchanged() {
        // A scope that terminated normally is still tracked for cleanup, but
        // the drain must not re-fire for it — no spurious failed message, no
        // extra typing toggle (M1-334).
        notifier.publish(SCOPE, ProgressStage.STARTED);
        notifier.complete(SCOPE, "REAL SUMMARY");
        int sendsAfterComplete = adapter.sends.size();
        int finalizesAfterComplete = adapter.finalizes.size();
        int typingAfterComplete = adapter.typing.size();

        notifier.inboundContext.drainAbandonedProgress();

        assertEquals(sendsAfterComplete, adapter.sends.size(),
                "drain must not send a spurious failed message after a normal completion");
        assertEquals(finalizesAfterComplete, adapter.finalizes.size(),
                "drain must not re-finalize a scope that already completed normally");
        assertEquals(typingAfterComplete, adapter.typing.size(),
                "drain must not toggle typing for an already-completed scope");
    }

    @Test
    void stageEditsWithinFloorCoalesceToAtMostOneUpdate() {
        // Large floor: the two non-terminal stage events after the
        // placeholder fall inside the window and coalesce.
        notifier = newNotifier(adapter, bundleLoader, /* floorMs */ 10_000);

        notifier.publish(SCOPE, ProgressStage.STARTED);     // placeholder send
        notifier.publish(SCOPE, ProgressStage.RETRIEVING);  // within floor
        notifier.publish(SCOPE, ProgressStage.GENERATING);  // within floor

        assertTrue(adapter.updateCount() <= 1,
                "two stage events closer together than the floor must coalesce into at most "
                        + "one update(); got " + adapter.updateCount());
    }

    @Test
    void zeroFloorEmitsEachStageUpdateProvingCoalescingIsTimeGated() {
        notifier = newNotifier(adapter, bundleLoader, /* floorMs */ 0);

        notifier.publish(SCOPE, ProgressStage.STARTED);     // placeholder send
        notifier.publish(SCOPE, ProgressStage.RETRIEVING);  // emits
        notifier.publish(SCOPE, ProgressStage.GENERATING);  // emits

        assertEquals(2, adapter.updateCount(),
                "with a zero floor each non-placeholder stage emits its own update()");
        assertEquals(bundleLoader.get(BundleKeys.PROGRESS_GENERATING),
                adapter.updates.get(adapter.updates.size() - 1),
                "the latest update carries the latest stage's localized string");
    }

    @Test
    void deliverFreshRefusesAnEmptiedLlmAuthoredBody() {
        // REPRODUCTION (M1-795): a blank LLM-authored /summary body
        // shipped through plain deliver() as an empty message. The wired
        // seam must refuse it: WARN, no transport call, false return.
        CapturingHandler logCapture = new CapturingHandler();
        org.jboss.logmanager.Logger jbossLogger =
                LogContext.getLogContext().getLogger(OutboundDelivery.class.getName());
        java.util.logging.Logger julLogger =
                java.util.logging.Logger.getLogger(OutboundDelivery.class.getName());
        jbossLogger.addHandler(logCapture);
        julLogger.addHandler(logCapture);
        try {
            assertFalse(notifier.deliverFresh(SCOPE, ""),
                    "an emptied LLM-authored body is refused, never shipped");
            assertTrue(adapter.sends.isEmpty(), "no transport call is made");
            long warns = logCapture.records.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .filter(r -> r.getMessage().contains("empty body"))
                    .count();
            assertEquals(1, warns, "the refusal is observable: exactly one WARN, no send");
        } finally {
            jbossLogger.removeHandler(logCapture);
            julLogger.removeHandler(logCapture);
        }
    }

    @Test
    void deliverFreshDeliversANonBlankBodyUnchanged() {
        // FAILURE-MODE (P1): a non-blank body ships through the seam
        // exactly once, byte-identical; the plain-deliver leg still
        // ships deliberately empty deterministic bodies.
        assertTrue(notifier.deliverFresh(SCOPE, "SUMMARY SECTION PROSE"),
                "a non-blank body is delivered through the seam");
        assertEquals(List.of("SUMMARY SECTION PROSE"), adapter.sends,
                "the body reaches the adapter byte-identical, exactly once");
        assertTrue(adapter.finalizes.isEmpty(), "no finalize on the fresh-send leg");

        notifier.complete(SCOPE, "");
        assertEquals(List.of("SUMMARY SECTION PROSE", ""), adapter.sends,
                "the plain-deliver leg keeps shipping deliberately empty deterministic bodies");
    }

    @Test
    void adapterMinEditIntervalRaisesEffectiveFloorAboveSystemFloor() {
        // With a 0ms system floor each stage would emit its own update
        // (see zeroFloorEmitsEachStageUpdateProvingCoalescingIsTimeGated).
        // An adapter that declares a 600ms minEditInterval must raise the
        // effective floor to max(0ms, 600ms) = 600ms, so the rapid
        // sub-600ms stage events coalesce to zero updates — proving the
        // larger of the system floor and the adapter floor governs.
        RecordingMessagingAdapter slowAdapter =
                new RecordingMessagingAdapter().withMinEditInterval(Duration.ofMillis(600));
        StageProgressNotifier slowFloorNotifier =
                newNotifier(slowAdapter, bundleLoader, /* floorMs */ 0);

        slowFloorNotifier.publish(SCOPE, ProgressStage.STARTED);     // placeholder send
        slowFloorNotifier.publish(SCOPE, ProgressStage.RETRIEVING);  // within the adapter floor
        slowFloorNotifier.publish(SCOPE, ProgressStage.GENERATING);  // within the adapter floor

        assertEquals(0, slowAdapter.updateCount(),
                "the adapter's 600ms minEditInterval must win over the 0ms system floor, "
                        + "coalescing the sub-600ms stage events to zero updates");
    }

    @Test
    void stageStringsResolveFromBundleAndNeverInterpolateUserInput() {
        // The scope carries a user-controlled contact id; a request may
        // also carry free-form user text. None of it may surface in any
        // stage string — those resolve only from the D43 bundle.
        String marker = "PWNED-user-authored-injection";
        ScopeRef hostileScope = new ScopeRef.Dm(marker);
        StageProgressNotifier zeroFloor = newNotifier(adapter, bundleLoader, /* floorMs */ 0);

        zeroFloor.publish(hostileScope, ProgressStage.STARTED);
        zeroFloor.publish(hostileScope, ProgressStage.RETRIEVING);
        zeroFloor.publish(hostileScope, ProgressStage.GENERATING);

        // Every stage body equals its bundle value and contains no user
        // input.
        assertEquals(bundleLoader.get(BundleKeys.PROGRESS_STARTED), adapter.sends.get(0),
                "placeholder is the bundle STARTED string verbatim");
        for (String body : adapter.sends) {
            assertFalse(body.contains(marker), "no user input in a sent stage string: " + body);
        }
        for (String body : adapter.updates) {
            assertFalse(body.contains(marker), "no user input in an updated stage string: " + body);
            assertTrue(
                    body.equals(bundleLoader.get(BundleKeys.PROGRESS_RETRIEVING))
                            || body.equals(bundleLoader.get(BundleKeys.PROGRESS_GENERATING)),
                    "each update body is a bundle stage string: " + body);
        }
    }

    // ----- wiring helpers --------------------------------------------------

    /** JUL-style log capture for the WARN-observability assertion (M1-795). */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static StageProgressNotifier newNotifier(MessagingAdapter adapter,
                                                     BundleLoader bundleLoader,
                                                     long floorMs) {
        StageProgressNotifier notifier = new StageProgressNotifier();
        notifier.adapterRegistry = new AdapterRegistry() {
            @Override
            public List<MessagingAdapter> activatedAdapters() {
                return List.of(adapter);
            }
        };
        InboundContext context = new InboundContext();
        context.setAdapterName(adapter.name());
        notifier.inboundContext = context;
        notifier.bundleLoader = bundleLoader;
        notifier.minEditIntervalMs = floorMs;
        // The notifier now routes every primitive through the outbound
        // chokepoint. A pass-through OutboundDelivery calls the adapter's
        // send/update/finalize once each with no transport failure, so the
        // recorded step 1–4 sequence is identical to the pre-chokepoint
        // behavior these tests pin.
        notifier.outboundDelivery = TestOutboundDelivery.passThrough();
        return notifier;
    }

}
