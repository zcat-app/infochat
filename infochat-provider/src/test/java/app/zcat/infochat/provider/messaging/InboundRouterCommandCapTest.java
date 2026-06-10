package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin T6 (M1-244): the profile-driven slash-command body cap
 * ({@code infochat.command.body-cap}) rejects an over-cap slash body
 * with the fixed {@code error.command.body_too_large} reply BEFORE the
 * parser, while an under-cap slash body is parsed and dispatched
 * normally. Mirrors the existing chat-mode cap shape on the slash path.
 *
 * <p>Plain JUnit — {@code lookupUser} is overridden to return a
 * non-banned vouched snapshot so the body reaches the cap gate, and the
 * {@code commandBodyCap} field is set directly (no Quarkus boot).</p>
 */
class InboundRouterCommandCapTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "command-cap-test-contact";

    @Test
    void overCapSlashBodyIsRejectedWithErrorBeforeParser() {
        NoopBundleLoader bundleLoader = new NoopBundleLoader();
        InboundRouter router = newVouchedRouter(bundleLoader);
        router.commandBodyCap = 8;
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        // 19-char slash body, well over the cap of 8.
        router.onMessage(dmInbound("/help-me-please-now"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "over-cap slash body must produce exactly one error reply; got: " + target.captured);
        // Reaching the parser would yield the unknown-command reply; the
        // cap-error reply proves the gate fired BEFORE handleSlash.
        assertEquals(bundleLoader.get(BundleKeys.ERROR_COMMAND_BODY_TOO_LARGE),
                target.captured.get(0).text(),
                "the reply must be the error.command.body_too_large bundle entry");
    }

    @Test
    void underCapSlashBodyIsParsedAndDispatchedNormally() {
        CallLog log = new CallLog();
        NoopBundleLoader bundleLoader = new NoopBundleLoader();
        InboundRouter router = newVouchedRouter(bundleLoader);
        router.commandBodyCap = 64;
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        // 5-char slash body, comfortably under the cap of 64.
        router.onMessage(dmInbound("/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "under-cap slash body must produce exactly one dispatch reply; got: " + target.captured);
        assertEquals("handler-reply:help", target.captured.get(0).text(),
                "under-cap slash body must be parsed and dispatched to the handler");
        assertTrue(log.calls.contains("handler.handle(help)"),
                "under-cap slash body must reach the handler; got: " + log.calls);
    }

    private InboundRouter newVouchedRouter(NoopBundleLoader bundleLoader) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                return Optional.of(new UserSnapshot(UUID.randomUUID(), "vouched", false));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = bundleLoader;
        router.commandHandlers = new SingletonInstance<>();
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        router.maxInboundBodyBytes = 65536;
        return router;
    }

    private static InboundMessage dmInbound(String body) {
        return new InboundMessage(
                new Identity(DM_CONTACT, "Alice", Instant.now()),
                new ScopeRef.Dm(DM_CONTACT),
                body,
                Instant.now(),
                "msg-1");
    }
}
