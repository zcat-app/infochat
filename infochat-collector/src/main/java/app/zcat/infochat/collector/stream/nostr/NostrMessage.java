package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.core.log.SafeLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.OptionalLong;

/**
 * NIP-01 wire frames. Every frame is a JSON array whose first element is the
 * verb. The relay pool sends only {@code REQ} (built by {@link #serializeReq})
 * and consumes three inbound verbs — {@code EVENT}, {@code EOSE},
 * {@code NOTICE} — modelled by the three permitted records. {@code CLOSE} and
 * other verbs are not produced or consumed in v1.
 */
public sealed interface NostrMessage
        permits NostrMessage.Event, NostrMessage.Eose, NostrMessage.Notice {

    /** Shared NIP-01 (de)serializer. Tolerates relay-added fields. */
    ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** {@code ["EVENT", subscriptionId, {event}]} — a new event on a subscription. */
    record Event(String subscriptionId, NostrEvent event) implements NostrMessage {
    }

    /** {@code ["EOSE", subscriptionId]} — end of stored events; the live tail follows. */
    record Eose(String subscriptionId) implements NostrMessage {
    }

    /** {@code ["NOTICE", message]} — a human-readable relay notice. */
    record Notice(String message) implements NostrMessage {
    }

    /**
     * Parse one inbound NIP-01 frame.
     *
     * @throws MalformedFrameException if the frame is not a recognized,
     *         well-formed inbound message. A relay is an untrusted boundary,
     *         so the caller logs and skips rather than letting a bad frame
     *         tear the connection down.
     */
    static NostrMessage parse(String frame) {
        JsonNode root;
        try {
            root = MAPPER.readTree(frame);
        } catch (JsonProcessingException e) {
            throw new MalformedFrameException("frame is not valid JSON: " + summarize(frame), e);
        }
        if (!root.isArray() || root.isEmpty()) {
            throw new MalformedFrameException("frame is not a non-empty JSON array: " + summarize(frame));
        }
        String verb = root.get(0).asText();
        return switch (verb) {
            case "EVENT" -> parseEvent(root);
            case "EOSE" -> new Eose(requireText(root, 1, frame));
            case "NOTICE" -> new Notice(requireText(root, 1, frame));
            // The verb is raw relay bytes too — same strip-and-cap as the
            // frame summaries.
            default -> throw new MalformedFrameException("unknown NIP-01 verb '" + summarize(verb) + "'");
        };
    }

    private static Event parseEvent(JsonNode root) {
        if (root.size() < 3 || !root.get(1).isTextual() || !root.get(2).isObject()) {
            throw new MalformedFrameException("EVENT frame missing subscription id or event object");
        }
        try {
            NostrEvent event = MAPPER.treeToValue(root.get(2), NostrEvent.class);
            return new Event(root.get(1).asText(), event);
        } catch (JsonProcessingException e) {
            throw new MalformedFrameException("EVENT event object did not parse", e);
        }
    }

    private static String requireText(JsonNode root, int index, String frame) {
        if (root.size() <= index || !root.get(index).isTextual()) {
            throw new MalformedFrameException("frame element " + index + " missing or not text: " + summarize(frame));
        }
        return root.get(index).asText();
    }

    /**
     * Serialize an outbound REQ frame: {@code ["REQ", subscriptionId, filter]}.
     * {@code filterSpec} is the source's canonical filter JSON (the
     * {@code source.identifier} per D38); when {@code since} is present it is
     * merged into the filter so a reconnecting relay replays only events newer
     * than the last persisted one (relays that honour {@code since}).
     *
     * @throws MalformedFrameException if {@code filterSpec} is not a JSON object.
     */
    static String serializeReq(String subscriptionId,
                               String filterSpec, OptionalLong since) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(filterSpec);
        } catch (JsonProcessingException e) {
            throw new MalformedFrameException("filter spec is not valid JSON: " + summarize(filterSpec), e);
        }
        if (!parsed.isObject()) {
            throw new MalformedFrameException("filter spec is not a JSON object: " + summarize(filterSpec));
        }
        ObjectNode filter = (ObjectNode) parsed;
        since.ifPresent(s -> filter.put("since", s));
        ArrayNode req = MAPPER.createArrayNode();
        req.add("REQ");
        req.add(subscriptionId);
        req.add(filter);
        try {
            return MAPPER.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            // req is a freshly-built node tree; serialization cannot fail.
            throw new IllegalStateException("re-serializing REQ frame failed", e);
        }
    }

    private static String summarize(String frame) {
        // Control-strip BEFORE embedding relay bytes in an exception
        // message: the message reaches WARN logs via the read loop's
        // catch, and the console Redactor scans for API-key shapes
        // only — without the strip an untrusted relay could forge log
        // lines or ANSI sequences (C0, DEL, C1 incl. 0x9B CSI).
        String stripped = SafeLog.stripControls(frame);
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 120) + "…";
    }

    /**
     * Thrown when an inbound frame, or a configured filter spec, is not
     * well-formed NIP-01. Unchecked: the relay-connection read loop catches it
     * at the boundary and skips the frame.
     */
    final class MalformedFrameException extends RuntimeException {
        MalformedFrameException(String message) {
            super(message);
        }

        MalformedFrameException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
