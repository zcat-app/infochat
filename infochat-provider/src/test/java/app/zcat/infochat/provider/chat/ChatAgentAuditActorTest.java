package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins that {@link ChatAgent#writeAuditRow} attributes the CHAT_MODE audit row
 * to the actor's denormalized identity read off the request-scoped
 * {@link InboundContext} (M1-595). Unlike {@code ChatAgentTest}, which overrides
 * {@code writeAuditRow} with a no-op and so never exercises the actor columns,
 * this test drives the REAL method against a capturing {@link AuditLogWriter}.
 * Before M1-595 the builder set only {@code actorUserId}, leaving
 * {@code actorContactId}/{@code actorAdapter} SQL-null (red-before); the two
 * identity assertions below are green only once those two fields are populated.
 */
class ChatAgentAuditActorTest {

    private static final String SENDER_CONTACT_ID = "simplex-contact-42";
    private static final String ADAPTER_NAME = "simplex";

    @Test
    void writeAuditRowAttributesActorFromInboundContext() {
        AtomicReference<RedactionHook.AuditRow> captured = new AtomicReference<>();
        AuditLogWriter capturingWriter = new AuditLogWriter(row -> row) {
            @Override
            public void write(Connection conn, RedactionHook.AuditRow row) {
                captured.set(row);
            }
        };

        InboundContext inboundContext = new InboundContext();
        inboundContext.setSenderContactId(SENDER_CONTACT_ID);
        inboundContext.setAdapterName(ADAPTER_NAME);

        // writeAuditRow reads only auditLogWriter, dataSource, and inboundContext;
        // the remaining collaborators are unused on this path, so they are null.
        ChatAgent agent = new ChatAgent(
                null, null, null, null, null, null, null, null, null,
                capturingWriter, SanitizerTestDoubles.noOpDataSource(), inboundContext,
                null, null, null, null);

        agent.writeAuditRow(UUID.randomUUID(), "dm", UUID.randomUUID());

        RedactionHook.AuditRow row = captured.get();
        assertNotNull(row, "writeAuditRow must emit exactly one audit row");
        assertEquals(AuditAction.CHAT_MODE, row.action());
        assertEquals(SENDER_CONTACT_ID, row.actorContactId(),
                "CHAT_MODE row must attribute the actor's contact id from InboundContext");
        assertEquals(ADAPTER_NAME, row.actorAdapter(),
                "CHAT_MODE row must record the inbound adapter from InboundContext");
    }
}
