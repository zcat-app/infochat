package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.ConfirmStateService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit (no Quarkus boot) router-level coverage of the M1-051
 * step 4.5 confirm-cancel sweep. Constructs {@link InboundRouter} by
 * hand with recording fakes — mirrors the pattern from
 * {@link InboundRouterIntakeOrderingTest} but focuses specifically on
 * the sweep's cancel-vs-leave-alone decision tree.
 *
 * <p>Three scenarios pin acceptance item 22 of M1-051:</p>
 * <ul>
 *   <li>(a) non-matching input: peek → non-empty, body NOT confirm-shape,
 *       takeAny + cancellation reply sent BEFORE dispatch;</li>
 *   <li>(b) matching confirm input: peek → non-empty, body IS confirm-shape,
 *       sweep does NOT takeAny, no cancellation reply, dispatch proceeds;</li>
 *   <li>(c) empty pending: peek → empty, no-op path; no takeAny, no
 *       cancellation, dispatch proceeds.</li>
 * </ul>
 */
class InboundRouterConfirmCancelTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "alice-dm-contact-id";
    private static final UUID ACTOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void nonMatchingInputCancelsPendingAndSendsAcknowledgement() {
        // Pending /ban exists; user sends /help (any other input) →
        // sweep takeAny + cancellation reply sent BEFORE dispatch.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new ConfirmStateService.PendingConfirm.Ban("target-1", null)));
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        // Two outbounds in order: cancellation, then the /help dispatch
        // (UNKNOWN_COMMAND_REPLY since the test wires no /help handler).
        assertEquals(2, capture.outbounds.size(),
                "expected one cancellation + one dispatch outbound; got: " + capture.outbounds);
        assertEquals("Pending `ban` cancelled.", capture.outbounds.get(0).text(),
                "first outbound must be the cancellation acknowledgement BEFORE dispatch");
        assertEquals(InboundRouter.UNKNOWN_COMMAND_REPLY, capture.outbounds.get(1).text(),
                "second outbound must be the /help dispatch (UNKNOWN_COMMAND_REPLY)");

        // ConfirmStateService call sequence: peek → takeAny (sweep
        // drained the pending entry before dispatch).
        assertTrue(confirmState.calls.contains("peek"),
                "sweep must call peek to detect the pending entry");
        assertTrue(confirmState.calls.contains("takeAny"),
                "sweep must call takeAny on a non-matching input to drain the pending entry");
    }

    @Test
    void matchingConfirmInputDoesNotCancelAndProceedsToDispatch() {
        // Pending /ban exists; user sends "/ban confirm" (matching
        // confirm-shape) → sweep does NOT takeAny, no cancellation
        // reply, dispatch proceeds.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new ConfirmStateService.PendingConfirm.Ban("target-2", "spam")));
        CapturingAdapter capture = new CapturingAdapter();
        RecordingCommandHandler banHandler = new RecordingCommandHandler("ban", "ban-dispatched");
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(banHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/ban confirm"), ADAPTER);

        // Single outbound — the dispatch reply only. NO cancellation
        // reply because the sweep recognized the confirm-shape and
        // left the pending entry alone for the handler.
        assertEquals(1, capture.outbounds.size(),
                "expected exactly one outbound (dispatch only); got: " + capture.outbounds);
        assertEquals("ban-dispatched", capture.outbounds.get(0).text(),
                "outbound must be the /ban handler's reply, not a cancellation acknowledgement");
        assertEquals(1, banHandler.dispatchCount,
                "/ban handler must have dispatched exactly once");

        // ConfirmStateService call sequence: peek only (no takeAny).
        assertTrue(confirmState.calls.contains("peek"),
                "sweep must call peek even on matching confirm input");
        assertFalse(confirmState.calls.contains("takeAny"),
                "sweep must NOT call takeAny on a matching confirm-shape body — "
                        + "the handler's takeMatching is the authoritative pop");
    }

    @Test
    void emptyPendingTakesNoOpPath() {
        // No pending state; user sends /help → sweep peek returns
        // empty → no-op (no takeAny, no cancellation), dispatch
        // proceeds with the existing reply.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(Optional.empty());
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        // Single outbound — the /help dispatch only (UNKNOWN_COMMAND_REPLY).
        assertEquals(1, capture.outbounds.size(),
                "expected exactly one outbound (dispatch only); got: " + capture.outbounds);
        assertEquals(InboundRouter.UNKNOWN_COMMAND_REPLY, capture.outbounds.get(0).text(),
                "outbound must be the /help dispatch reply, not a cancellation");

        // ConfirmStateService call sequence: peek only.
        assertTrue(confirmState.calls.contains("peek"),
                "sweep must call peek even on empty pending (to determine emptiness)");
        assertFalse(confirmState.calls.contains("takeAny"),
                "sweep must NOT call takeAny when peek returns empty (no-op path)");
    }

    // ----- router wiring + fakes -------------------------------------------

    /**
     * Construct an {@link InboundRouter} with all M1-044b + M1-051
     * collaborators replaced by no-op / recording fakes.
     * {@link InboundRouter#lookupUser} is overridden to return a
     * fixed "vouched" snapshot so step 2 (DM unknown) is skipped and
     * step 7 DM-gate (group_only-only) does not fire — the sweep at
     * step 4.5 IS the focus of these scenarios.
     */
    private InboundRouter newRouter(FakeConfirmStateService confirmState, CapturingAdapter target) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
                return Optional.of(new UserSnapshot(ACTOR_ID, false, "vouched"));
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.autoRegisterService = new NoopAutoRegisterService();
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.banCheck = new NoopBanCheck();
        router.bundleLoader = new FakeBundleLoader();
        router.confirmStateService = confirmState;
        router.maxInboundBodyBytes = 65536;
        router.setReplyTarget(target);
        return router;
    }

    private static InboundMessage dmInbound(String contactId, String body) {
        return new InboundMessage(
                new Identity(contactId, "Alice", Instant.now()),
                new ScopeRef.Dm(contactId),
                body,
                Instant.now(),
                "msg-1");
    }

    /**
     * Recording {@link ConfirmStateService} fake — every accessor
     * call appends to {@code calls}; {@code peek} returns the
     * constructor-supplied value; {@code takeAny} returns the same
     * AND clears the stored value (mirroring the production
     * pop-on-take semantics).
     */
    private static final class FakeConfirmStateService extends ConfirmStateService {
        final List<String> calls = new ArrayList<>();
        private Optional<ConfirmStateService.PendingConfirm> stored;

        FakeConfirmStateService(Optional<ConfirmStateService.PendingConfirm> initial) {
            this.stored = initial;
        }

        @Override
        public Optional<ConfirmStateService.PendingConfirm> peek(UUID actor, ScopeRef scope) {
            calls.add("peek");
            return stored;
        }

        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeAny(UUID actor, ScopeRef scope) {
            calls.add("takeAny");
            Optional<ConfirmStateService.PendingConfirm> result = stored;
            stored = Optional.empty();
            return result;
        }

        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeMatching(UUID actor, ScopeRef scope, String commandName) {
            calls.add("takeMatching:" + commandName);
            return Optional.empty();
        }

        @Override
        public void remember(UUID actor, ScopeRef scope, ConfirmStateService.PendingConfirm pending) {
            calls.add("remember");
        }
    }

    /** No-op rate-cap — always admits. */
    private static final class NoopRateCapBucket extends RateCapBucket {
        @Override
        public boolean tryAcquire(String adapter, String contactId) {
            return true;
        }
    }

    /** No-op invite consumer — never invoked (snapshot is non-empty). */
    private static final class NoopInviteCodeConsumer extends InviteCodeConsumer {
        @Override
        public Outcome consume(String adapter, String contactId, String body) {
            throw new UnsupportedOperationException(
                    "inviteCodeConsumer must not run when the user snapshot is non-empty");
        }
    }

    /** No-op ban check — never banned. */
    private static final class NoopBanCheck extends BanCheck {
        @Override
        public boolean isBanned(String adapter, String contactId) {
            return false;
        }
    }

    /** No-op auto-register — never invoked (DM scope). */
    private static final class NoopAutoRegisterService extends AutoRegisterService {
        @Override
        public java.util.UUID resolveOrRegisterGroup(Identity identity, String adapter) {
            throw new UnsupportedOperationException("not exercised in DM-scope tests");
        }
    }

    /**
     * Bundle loader that returns the actual production-string body
     * for {@link BundleKeys#REPLY_CONFIRM_CANCELLED} so the test can
     * assert the exact rendered cancellation literal. Other keys
     * return a deterministic stub.
     */
    private static final class FakeBundleLoader extends BundleLoader {
        @Override
        public String get(String key) {
            return switch (key) {
                case BundleKeys.REPLY_CONFIRM_CANCELLED -> "Pending `{0}` cancelled.";
                default -> "bundle:" + key;
            };
        }
    }

    /** Capture-only {@link MessagingAdapter} — appends sends into {@code outbounds}. */
    private static final class CapturingAdapter implements MessagingAdapter {
        final List<OutboundMessage> outbounds = new ArrayList<>();

        @Override public String name() { return "inmemory"; }
        @Override public CapabilityFlags capabilities() { throw new UnsupportedOperationException(); }
        @Override public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() { throw new UnsupportedOperationException(); }
        @Override public Identity assertIdentity(InboundMessage msg) { throw new UnsupportedOperationException(); }
        @Override public MessageHandle send(OutboundMessage outbound) {
            outbounds.add(outbound);
            return null;
        }
        @Override public void update(MessageHandle handle, String body) { throw new UnsupportedOperationException(); }
        @Override public void finalize(MessageHandle handle, String body) { throw new UnsupportedOperationException(); }
        @Override public void setTyping(ScopeRef scope, boolean typing) { throw new UnsupportedOperationException(); }
        @Override public void setInboundHandler(InboundHandler handler) {}
    }

    /**
     * Recording {@link CommandHandler} — counts dispatches and
     * returns a deterministic stub. The test exercises this for the
     * matching-confirm scenario to prove dispatch proceeds.
     */
    private static final class RecordingCommandHandler implements CommandHandler {
        private final String name;
        private final String stubBody;
        int dispatchCount = 0;

        RecordingCommandHandler(String name, String stubBody) {
            this.name = name;
            this.stubBody = stubBody;
        }

        @Override public String name() { return name; }

        @Override
        public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
            dispatchCount++;
            return new OutboundMessage(scope, stubBody, Instant.now(), "h-" + dispatchCount);
        }
    }

    /**
     * Minimal {@link Instance} for the test's single command handler
     * slot. Empty by default; tests that need a wired handler call
     * {@code new SingletonInstance<>(handler)}.
     */
    private static final class SingletonInstance<T> implements Instance<T> {
        private final List<T> items;

        @SafeVarargs
        SingletonInstance(T... items) { this.items = List.of(items); }

        @Override public Iterator<T> iterator() { return items.iterator(); }
        @Override public T get() { throw new UnsupportedOperationException(); }
        @Override public Instance<T> select(Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public boolean isUnsatisfied() { return items.isEmpty(); }
        @Override public boolean isAmbiguous() { return items.size() > 1; }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends Handle<T>> handles() { throw new UnsupportedOperationException(); }
    }
}
