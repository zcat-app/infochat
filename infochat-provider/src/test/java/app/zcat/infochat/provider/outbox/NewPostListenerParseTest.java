package app.zcat.infochat.provider.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain JUnit (no {@code @QuarkusTest}) — exercises the static, pure
 * {@link NewPostListener#parsePayload(String)} on the NOTIFY
 * deserialization boundary. Two invariants are pinned:
 *
 * <ol>
 *   <li>a well-formed {@code new_post} payload parses to the expected
 *       {@code (post_id, ready_at)} reference;</li>
 *   <li>a malformed payload is rejected WITHOUT echoing the raw inbound
 *       JSON into the exception message — info-leak hygiene on this
 *       boundary (T30 / deepseek #F3+#F4).</li>
 * </ol>
 */
class NewPostListenerParseTest {

    @Test
    void wellFormedPayloadParsesToExpectedPostReference() {
        UUID postId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Instant readyAt = Instant.parse("2026-06-09T12:00:00Z");
        String json = "{\"ready_at\":\"" + readyAt + "\",\"post_id\":\"" + postId + "\"}";

        NewPostListener.Payload payload = NewPostListener.parsePayload(json);

        assertEquals(postId, payload.postId(),
                "the parsed post_id must equal the payload's post_id field");
        assertEquals(readyAt, payload.readyAt(),
                "the parsed ready_at must equal the payload's ready_at field");
    }

    @Test
    void malformedPayloadRejectedWithoutEchoingRawJson() {
        // post_id missing → shape failure. The sentinel value stands in
        // for any sensitive bytes that might ride the channel; the
        // rejection message must not echo it (nor the whole raw payload)
        // back into the exception that flows on to the log.
        String rawJson = "{\"ready_at\":\"2026-06-09T12:00:00Z\",\"sentinel\":\"LEAK-MARKER\"}";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> NewPostListener.parsePayload(rawJson),
                "a payload missing the post_id field must be rejected");

        String message = Objects.requireNonNullElse(ex.getMessage(), "");
        assertFalse(message.contains(rawJson),
                "the rejection message must not echo the raw inbound JSON");
        assertFalse(message.contains("LEAK-MARKER"),
                "the rejection message must not echo any raw payload content");
    }
}
