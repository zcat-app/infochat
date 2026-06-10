package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain-JUnit (no Quarkus boot) coverage of the M1-212 dispatch seam:
 * a {@link CommandHandler} that owns its own message lifecycle via the
 * {@code ProgressNotifier} signals "already delivered" by returning
 * {@code null} from {@link CommandHandler#handle}, and
 * {@link InboundRouter} performs NO send for that invocation — so the
 * self-delivering handler's reply is sent exactly once (by the notifier,
 * modeled here as the handler's own adapter send), never duplicated by
 * the router. Non-self-sending handlers are unaffected: the router still
 * sends their returned text.
 *
 * <p>Mirrors the router-wiring pattern in
 * {@link InboundRouterConfirmCancelTest}: {@link InboundRouter#lookupUser}
 * is overridden to a fixed vouched snapshot so the dispatch reaches
 * {@code handleSlash}.</p>
 */
class RouterNoDoubleSendTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "alice-dm-contact-id";
    private static final UUID ACTOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void selfSendingHandlerReturningNullIsSentExactlyOnceNeverTwice() {
        CapturingAdapter capture = new CapturingAdapter();
        // The handler delivers its own reply through the adapter (as the
        // ProgressNotifier would) and returns null to signal "delivered".
        SelfDeliveringHandler handler = new SelfDeliveringHandler("summary", capture);
        InboundRouter router = newRouter(capture);
        router.commandHandlers = new SingletonInstance<>(handler);

        router.onMessage(dmInbound(DM_CONTACT, "/summary"), ADAPTER);

        assertEquals(1, capture.captured.size(),
                "a self-delivering handler's reply must be sent exactly once (its own "
                        + "notifier send) — the router must NOT add a second; got: "
                        + capture.captured);
        assertEquals("self-delivered:summary", capture.captured.get(0).text(),
                "the single outbound must be the handler's own delivery, not a router send");
        assertEquals(1, handler.handleCount, "the handler must have dispatched exactly once");
    }

    @Test
    void ordinaryTextReturningHandlerIsStillSentByTheRouter() {
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(capture);
        router.commandHandlers = new SingletonInstance<>(
                new RecordingCommandHandler(new CallLog(), "ping"));

        router.onMessage(dmInbound(DM_CONTACT, "/ping"), ADAPTER);

        assertEquals(1, capture.captured.size(),
                "an ordinary text-returning handler's reply is still sent by the router; got: "
                        + capture.captured);
        assertEquals("handler-reply:ping", capture.captured.get(0).text(),
                "the router must send the handler's returned text unchanged");
    }

    // ----- router wiring + fakes -------------------------------------------

    private InboundRouter newRouter(CapturingAdapter target) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                return Optional.of(new UserSnapshot(ACTOR_ID, "vouched", false));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = new NoopBundleLoader();
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        router.maxInboundBodyBytes = 65536;
        router.commandBodyCap = 65536;
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
     * Self-delivering {@link CommandHandler}: sends its reply through the
     * supplied adapter (modeling the {@code ProgressNotifier}
     * placeholder/finalize) and returns {@code null} so the router
     * performs no send of its own.
     */
    private static final class SelfDeliveringHandler implements CommandHandler {
        private final String name;
        private final CapturingAdapter adapter;
        int handleCount = 0;

        SelfDeliveringHandler(String name, CapturingAdapter adapter) {
            this.name = name;
            this.adapter = adapter;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            handleCount++;
            adapter.send(new OutboundMessage(
                    scope, "self-delivered:" + name, Instant.now(), UUID.randomUUID().toString()));
            return null;
        }
    }
}
