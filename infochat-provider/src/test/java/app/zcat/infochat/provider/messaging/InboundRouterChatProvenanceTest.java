package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.chat.ChatAgent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the router-side composition of the M1-617 retrieval-provenance
 * notice: {@link InboundRouter#dispatchChat} appends a non-null notice to a
 * non-null reply (blank-line separated, one outbound), ships a null-notice
 * reply verbatim, and propagates a null reply (/stop-cancelled) untouched.
 * Unlike {@link InboundRouterChatProgressTest} — which overrides
 * {@code dispatchChat} as a seam — this test exercises the REAL
 * {@code dispatchChat} against a stub {@link ChatAgent} overriding
 * {@code handleTurn}, because the composition under test lives inside
 * {@code dispatchChat} itself.
 */
class InboundRouterChatProvenanceTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();

    @Test
    void noticeIsAppendedToReplyBlankLineSeparated() {
        InboundRouter router = routerWith(new ChatAgent.ChatTurnResult(
                "The Tenda advisory says X.",
                () -> Optional.empty(),
                "Based on 2 posts from your subscribed feed."));

        String composed = router.dispatchChat(ACTOR_ID, "dm", SCOPE_ID, "tell me more");

        assertEquals("The Tenda advisory says X.\n\n"
                        + "Based on 2 posts from your subscribed feed.",
                composed,
                "reply and provenance notice ride ONE outbound, blank-line separated");
        assertNotNull(router.inboundContext.takePendingChatCommit(),
                "the deferred commit is stashed before composition, unchanged");
    }

    @Test
    void nullNoticeShipsReplyVerbatim() {
        InboundRouter router = routerWith(new ChatAgent.ChatTurnResult(
                "error.chat.unavailable", null, null));

        String composed = router.dispatchChat(ACTOR_ID, "dm", SCOPE_ID, "hi");

        assertEquals("error.chat.unavailable", composed,
                "a degrade turn (null notice) must ship the reply verbatim");
    }

    @Test
    void nullReplyPropagatesForStopCancelledTurn() {
        InboundRouter router = routerWith(new ChatAgent.ChatTurnResult(null, null, null));

        String composed = router.dispatchChat(ACTOR_ID, "dm", SCOPE_ID, "hi");

        assertNull(composed,
                "a /stop-cancelled turn stays null so the router's null-body "
                        + "branch skips the send");
    }

    /**
     * Bare router whose {@code chatAgent} returns {@code result} — only the
     * two collaborators {@code dispatchChat} touches are wired.
     */
    private InboundRouter routerWith(ChatAgent.ChatTurnResult result) {
        InboundRouter router = new InboundRouter();
        router.inboundContext = new InboundContext();
        router.chatAgent = new StubChatAgent(result);
        return router;
    }

    /** Overrides handleTurn outright; the null collaborators are never touched. */
    static class StubChatAgent extends ChatAgent {
        private final ChatTurnResult result;

        StubChatAgent(ChatTurnResult result) {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
            this.result = result;
        }

        @Override
        public ChatTurnResult handleTurn(UUID userId, String scopeKind,
                                         UUID scopeId, String userMessage) {
            return result;
        }
    }
}
