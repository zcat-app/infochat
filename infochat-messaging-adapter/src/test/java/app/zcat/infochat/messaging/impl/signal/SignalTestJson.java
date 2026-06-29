package app.zcat.infochat.messaging.impl.signal;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;

/**
 * Shared JSON parse helper for the Signal codec / handler / mention tests:
 * reads a JSON object out of a frame string. Consolidated from the per-test
 * {@code parse} copies (27#F1).
 */
final class SignalTestJson {

    private SignalTestJson() {
    }

    static JsonObject parse(String json) {
        try (JsonReader r = Json.createReader(new StringReader(json))) {
            return r.readObject();
        }
    }
}
