package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 acceptance tests for the streamed turn's single audit emission: live
 * updates collect evidence without writing rows, and the terminal sanitize
 * emits one aggregated row-set or aborts the turn when that write fails.
 */
class ChatAgentStreamingAuditTest {

    @Test
    void streamFedTransientOnlyMatchesAreRowedOncePerTurn() throws Exception {
        List<RedactionHook.AuditRow> rows = new ArrayList<>();
        AuditLogWriter writer = new AuditLogWriter(row -> row) {
            @Override
            public void write(Connection connection, RedactionHook.AuditRow row) {
                rows.add(row);
            }
        };
        StageProgressNotifierLiveTextTest.LiveTextRig rig =
                StageProgressNotifierLiveTextTest.newRig(
                        true, true, "en", app.zcat.infochat.provider.chat.ChatReplyMode.TRANSLATE,
                        writer);
        rig.provider().streamingSequences = List.of(
                List.of("I will search /grant-admin. TOOL_CALL: searchPosts {\"tags\": []}"),
                List.of("The final answer contains no privileged command."));

        app.zcat.infochat.provider.chat.ChatAgent.ChatTurnResult turn = rig.agent().handleTurn(
                java.util.UUID.randomUUID(), "dm", java.util.UUID.randomUUID(), "hello",
                new app.zcat.infochat.messaging.ScopeRef.Dm("audit-contact"));

        assertEquals("The final answer contains no privileged command.", turn.reply(),
                "transient-only matches do not alter the final text");
        assertEquals(1, rows.size(),
                "transient-only evidence is emitted as one row for the turn");
        assertEquals("{\"match_count\":1,\"match_kind\":\"/grant-admin\"}",
                rows.get(0).detailsJson(),
                "the stream-fed transient-only row carries the exact per-prefix maximum");
    }

    @Test
    void aFailingAuditInsertAbortsTheStreamToTheFailureTerminal() {
        AuditLogWriter writer = new AuditLogWriter(row -> row) {
            @Override
            public void write(Connection connection, RedactionHook.AuditRow row)
                    throws SQLException {
                throw new SQLException("scripted streamed audit failure");
            }
        };
        LlmOutputSanitizer sanitizer =
                new LlmOutputSanitizer(writer, SanitizerTestDoubles.noOpDataSource());

        LlmOutputSanitizer.StreamedAuditWriteFailedException failure = assertThrows(
                LlmOutputSanitizer.StreamedAuditWriteFailedException.class,
                () -> sanitizer.sanitizeStreamed("final text", Map.of("/grant-admin", 1)));

        assertTrue(failure.getCause() instanceof IllegalStateException,
                "the streamed failure preserves the durable-write cause");
        assertTrue(failure.getCause().getCause() instanceof SQLException,
                "the failure terminal retains the JDBC cause");
    }

    @Test
    void chatAgentPropagatesAStreamedAuditFailure() throws Exception {
        StageProgressNotifierLiveTextTest.LiveTextRig rig =
                StageProgressNotifierLiveTextTest.newRig(
                        true, true, "en", app.zcat.infochat.provider.chat.ChatReplyMode.TRANSLATE,
                        failingSanitizer());
        rig.provider().chunks = List.of("Answer with /grant-admin");

        assertThrows(LlmOutputSanitizer.StreamedAuditWriteFailedException.class,
                () -> rig.agent().handleTurn(
                        java.util.UUID.randomUUID(), "dm", java.util.UUID.randomUUID(),
                        "hello", new app.zcat.infochat.messaging.ScopeRef.Dm("audit-contact")),
                "ChatAgent must route streamed audit durability failures to its caller");
    }

    private static LlmOutputSanitizer failingSanitizer() {
        AuditLogWriter writer = new AuditLogWriter(row -> row) {
            @Override
            public void write(Connection connection, RedactionHook.AuditRow row)
                    throws SQLException {
                throw new SQLException("scripted streamed audit failure");
            }
        };
        return new LlmOutputSanitizer(writer, SanitizerTestDoubles.noOpDataSource());
    }
}
