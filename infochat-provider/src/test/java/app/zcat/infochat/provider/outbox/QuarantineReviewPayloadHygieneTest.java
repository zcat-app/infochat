package app.zcat.infochat.provider.outbox;

import org.junit.jupiter.api.Test;
import org.postgresql.core.Notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins NewPostListener parity for the quarantine_review channel: the
 * raw NOTIFY payload is never echoed into ERROR logs or exception
 * messages — neither by {@code parsePayload}'s two reject sites nor by
 * {@code dispatch}'s unparseable-payload ERROR line.
 */
class QuarantineReviewPayloadHygieneTest {

    private static final String PAYLOAD_MARKER = "RAW-PAYLOAD-MARKER-do-not-log";

    @Test
    void missingFieldsExceptionMessageOmitsPayload() {
        String payload = "{\"oops\":\"" + PAYLOAD_MARKER + "\"}";

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> QuarantineReviewListener.parsePayload(payload));

        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().contains(PAYLOAD_MARKER),
                "the reject message must not echo the raw payload");
    }

    @Test
    void outOfSetTargetKindExceptionMessageOmitsPayload() {
        String payload = "{\"target_kind\":\"comment\",\"target_id\":"
                + "\"00000000-0000-0000-0000-0000000000aa\","
                + "\"new_status\":\"" + PAYLOAD_MARKER + "\"}";

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> QuarantineReviewListener.parsePayload(payload));

        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().contains(PAYLOAD_MARKER),
                "the discriminator reject message must not echo the raw payload");
    }

    @Test
    void dispatchUnparseablePayloadErrorLogOmitsPayload() {
        char esc = (char) 0x1B;
        String garbage = "not json at all " + PAYLOAD_MARKER + esc + "[2J";
        var listener = new QuarantineReviewListener();

        List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    captured.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // jboss-logging routes through the JBoss LogManager only when it
        // is installed as the JVM's LogManager; otherwise it falls back
        // to stock JUL. Attach to both hierarchies — the identity check
        // prevents double capture when they are the same logger object.
        java.util.logging.Logger jul = java.util.logging.Logger
                .getLogger(QuarantineReviewListener.class.getName());
        java.util.logging.Logger ctx = org.jboss.logmanager.LogContext.getLogContext()
                .getLogger(QuarantineReviewListener.class.getName());
        jul.addHandler(capture);
        if (ctx != jul) {
            ctx.addHandler(capture);
        }
        try {
            listener.dispatch(new Notification(
                    QuarantineReviewListener.CHANNEL, 0, garbage));
        } finally {
            jul.removeHandler(capture);
            if (ctx != jul) {
                ctx.removeHandler(capture);
            }
        }

        assertEquals(1, captured.size(), "exactly one drop-with-log ERROR expected");
        LogRecord record = captured.get(0);
        String text = record.getMessage() + " " + Arrays.toString(record.getParameters());
        assertFalse(text.contains(PAYLOAD_MARKER),
                "the ERROR line must not echo the raw NOTIFY payload");
        Throwable bound = record.getThrown();
        assertNotNull(bound, "the shape exception itself still travels for diagnostics");
        assertTrue(bound.getMessage() != null
                        && !bound.getMessage().contains(PAYLOAD_MARKER),
                "the bound exception's message must not echo the raw payload either");
    }
}
