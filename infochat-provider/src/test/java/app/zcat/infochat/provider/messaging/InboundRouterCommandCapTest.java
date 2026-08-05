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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin T6 (M1-244): the profile-driven slash-command body cap
 * ({@code infochat.command.body-cap}) rejects an over-cap slash body
 * with the fixed {@code error.command.body_too_large} reply BEFORE the
 * parser, while an under-cap slash body is parsed and dispatched
 * normally. Mirrors the existing chat-mode cap shape on the slash path.
 *
 * <p>Also pins the single-line rule that sits immediately after the cap
 * (M1-772): a slash body carrying content past its first line is
 * rejected with {@code error.command.multiline} before the parser, so a
 * flag on a later line can no longer join the argument run of a command
 * word on the first.</p>
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

    @Test
    void multiLineSlashBodyIsRejectedAndReachesNoHandler() {
        CallLog log = new CallLog();
        NoopBundleLoader bundleLoader = new NoopBundleLoader();
        InboundRouter router = newVouchedRouter(bundleLoader);
        router.commandBodyCap = 64;
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/help\nand a second line"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "multi-line slash body must produce exactly one error reply; got: " + target.captured);
        assertEquals(bundleLoader.get(BundleKeys.ERROR_COMMAND_MULTILINE),
                target.captured.get(0).text(),
                "the reply must be the error.command.multiline bundle entry");
        assertTrue(log.calls.isEmpty(),
                "a multi-line slash body must not reach any handler; got: " + log.calls);
    }

    @Test
    void flagOnASecondLineDoesNotDispatchTheAdminListing() {
        // The security case (M1-772): before the single-line rule, every
        // argument parser tokenized the whole body with split("\\s+") — and
        // Java's \s matches \n — so a --all on any later line set all=true
        // and dispatched the admin-only deployment-wide source listing.
        // Asserted on DISPATCH, not on the reply text: the point is that
        // the handler never runs, not which string comes back.
        CallLog log = new CallLog();
        NoopBundleLoader bundleLoader = new NoopBundleLoader();
        InboundRouter router = newVouchedRouter(bundleLoader);
        router.commandBodyCap = 64;
        router.commandHandlers =
                new SingletonInstance<>(new RecordingCommandHandler(log, "list-sources"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/list-sources\n--all"), ADAPTER);

        assertTrue(log.calls.isEmpty(),
                "a flag on a second line must not reach the handler; got: " + log.calls);
    }

    @Test
    void theSameCommandOnOneLineStillDispatches() {
        // The contrast to the two tests above: the rejection keys on the
        // newline alone, so the identical command with its argument on the
        // command's own line is unaffected.
        CallLog log = new CallLog();
        NoopBundleLoader bundleLoader = new NoopBundleLoader();
        InboundRouter router = newVouchedRouter(bundleLoader);
        router.commandBodyCap = 64;
        router.commandHandlers =
                new SingletonInstance<>(new RecordingCommandHandler(log, "list-sources"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/list-sources --all"), ADAPTER);

        assertTrue(log.calls.contains("handler.handle(list-sources)"),
                "a single-line command must still reach the handler; got: " + log.calls);
    }

    @Test
    void multiLineChatBodyIsNotACommandAndIsUnaffected() {
        assertFalse(InboundRouter.isMultiLineCommand("hello\nworld"),
                "a multi-line body without a leading slash is chat, not a command");
        assertFalse(InboundRouter.isMultiLineCommand("```\ncode\n```"),
                "normalize preserves fenced code blocks for chat mode; they must not be rejected");
        assertTrue(InboundRouter.isMultiLineCommand("/help\nx"),
                "a slash body with a second line is a multi-line command");
        assertFalse(InboundRouter.isMultiLineCommand("/help x"),
                "a single-line slash body is not a multi-line command");
    }

    @Test
    void everyLineTerminatorIsRejectedNotJustNewline() {
        // normalize() splits on \n alone and appendNormalized preserves
        // every other terminator (none is a bidi control or zero-width,
        // and NFKC folds none of them). A \n-only test would therefore
        // pass "/list-sources\r--all" straight through while the
        // handlers' split("\\s+") still tokenizes \r as a separator —
        // the admin listing would dispatch from a body that never looked
        // like it had a second line.
        assertTrue(InboundRouter.isMultiLineCommand("/list-sources\r--all"),
                "a bare CR separates tokens for split(\"\\\\s+\") and must be rejected");
        assertTrue(InboundRouter.isMultiLineCommand("/list-sources" + (char) 0x000B + "--all"),
                "VT is in \\s and must be rejected");
        assertTrue(InboundRouter.isMultiLineCommand("/list-sources\f--all"),
                "FF is in \\s and must be rejected");
        // These three are line boundaries to the reader but are NOT in
        // Java's \s, so they cannot split a token today. Rejected anyway:
        // the rule is about what occupies one line, read as the reader
        // reads it, and that must not depend on \s's coverage gaps.
        assertTrue(InboundRouter.isMultiLineCommand("/list-sources" + (char) 0x0085 + "--all"),
                "NEL is a line boundary and must be rejected");
        assertTrue(InboundRouter.isMultiLineCommand("/list-sources" + (char) 0x2028 + "--all"),
                "U+2028 is a line boundary and must be rejected");
        assertTrue(InboundRouter.isMultiLineCommand("/list-sources" + (char) 0x2029 + "--all"),
                "U+2029 is a line boundary and must be rejected");
        // A tab is in-line whitespace, not a line boundary: one line, so
        // it still dispatches and the sanitizer's within-line scan covers
        // a closed-list entry spanning it.
        assertFalse(InboundRouter.isMultiLineCommand("/list-sources\t--all"),
                "a tab is not a line boundary and must not be rejected");
    }

    @Test
    void carriageReturnSeparatedFlagDoesNotDispatch() {
        // The dispatch-level twin of the predicate test above: this is
        // the shape a \n-only check would have shipped.
        CallLog log = new CallLog();
        NoopBundleLoader bundleLoader = new NoopBundleLoader();
        InboundRouter router = newVouchedRouter(bundleLoader);
        router.commandBodyCap = 64;
        router.commandHandlers =
                new SingletonInstance<>(new RecordingCommandHandler(log, "list-sources"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/list-sources\r--all"), ADAPTER);

        assertTrue(log.calls.isEmpty(),
                "a flag behind a bare CR must not reach the handler; got: " + log.calls);
    }

    private InboundRouter newVouchedRouter(NoopBundleLoader bundleLoader) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                return Optional.of(new UserSnapshot(UUID.randomUUID(), "vouched", false, null));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.registeredContactSet = new NoopRegisteredContactSet();
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
        router.outboundDelivery = TestOutboundDelivery.passThrough();
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
