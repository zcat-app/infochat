package app.zcat.infochat.core.audit;

import app.zcat.infochat.core.log.Redactor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;

import java.io.StringReader;

/**
 * Default {@link RedactionHook} delegating to the shared
 * {@link Redactor} catalogue from {@code docs/spec/security.md}
 * §Secrets handling. The catalogue and watchdog engine live in
 * {@link Redactor} — this class adapts the string-level redaction
 * to the {@link RedactionHook.AuditRow} shape and guarantees the
 * returned row's {@code details_json} is null/empty or a JSON
 * document that survives {@link AuditLogWriter}'s {@code ?::jsonb}
 * cast.
 *
 * <h2>Contact-id handling</h2>
 * <p>The hook does NOT touch {@code target_contact_id} or any
 * contact-id-shaped substring inside {@code details_json}. Per
 * spec §Secrets handling, contact-id redaction is "outside the
 * audit log" and is handled at read time by the
 * {@code audit_log_view} (V5 §2.1.9).</p>
 */
@ApplicationScoped
public class DefaultRedactionHook implements RedactionHook {

    /**
     * Whole-field fallback when the regex watchdog fires. Must be
     * valid JSONB because {@link AuditLogWriter} binds
     * {@code details_json} with {@code ?::jsonb}.
     */
    public static final String REDACTED_FIELD_JSONB =
            "{\"_redacted\":true,\"reason\":\"regex_watchdog_timeout\"}";

    @Override
    public AuditRow redact(AuditRow row) {
        String detailsJson = row.detailsJson();
        if (detailsJson == null || detailsJson.isEmpty()) {
            return row;
        }
        String redacted = Redactor.redact(detailsJson);
        if (Redactor.TIMEOUT_SENTINEL.equals(redacted)) {
            redacted = REDACTED_FIELD_JSONB;
        }
        // Fail-closed post-condition: the value we hand back must survive
        // AuditLogWriter's ?::jsonb cast. Off-contract input — malformed JSON
        // from a buggy caller, or a value carrying a U+0000 escape that is valid
        // JSON text but that PostgreSQL jsonb cannot store — would otherwise
        // reach the cast and abort the surrounding audit-before-effect
        // transaction with an opaque SQLException, taking the admin action down
        // and losing the audit row. Substituting the sentinel here keeps the
        // failure inside the redaction layer that already owns the 'valid JSONB'
        // promise. isJsonbSafe parses authoritatively (catching balanced-but-
        // invalid forms the cast also rejects) and additionally rejects U+0000.
        if (!isJsonbSafe(redacted)) {
            redacted = REDACTED_FIELD_JSONB;
        }
        if (redacted.equals(detailsJson)) {
            return row;
        }
        return new AuditRow(
                row.actorUserId(),
                row.actorContactId(),
                row.actorAdapter(),
                row.action(),
                row.targetKind(),
                row.targetId(),
                row.targetContactId(),
                row.scopeId(),
                row.requestId(),
                redacted);
    }

    /**
     * Authoritative check that {@code value} will survive
     * {@link AuditLogWriter}'s {@code ?::jsonb} cast. Two conditions must
     * hold, and they are checked independently because a spec-compliant JSON
     * parse alone is not sufficient:
     * <ol>
     *   <li>{@code value} parses as exactly one top-level JSON object or array
     *       with no trailing content. A streaming parse (replacing the loose
     *       brace-balance heuristic this once was) rejects the
     *       balanced-but-syntactically-invalid forms the cast also rejects —
     *       {@code {"a":}}, {@code {"a" "b"}}, {@code {"a":1}garbage}, and two
     *       concatenated documents.</li>
     *   <li>No string node — object key or string value, at any depth — carries
     *       the U+0000 code point. A compliant JSON parse ACCEPTS a U+0000
     *       escape (it is valid JSON), but PostgreSQL {@code jsonb} is the one
     *       sink that cannot store the NUL code point, so such a value casts to
     *       an error. The parse must therefore be paired with an explicit NUL
     *       rejection.</li>
     * </ol>
     * A top-level JSON scalar would in fact cast cleanly, but every audit
     * {@code details_json} is an object or array, so a scalar is treated as
     * off-contract and fails closed — preserving the prior guard's
     * object/array-only contract. Anything failing either condition is replaced
     * upstream with {@link #REDACTED_FIELD_JSONB} before it can reach the cast.
     */
    private static boolean isJsonbSafe(String value) {
        JsonValue parsed;
        try (JsonParser parser = Json.createParser(new StringReader(value))) {
            if (!parser.hasNext()) {
                return false;
            }
            JsonParser.Event first = parser.next();
            if (first != JsonParser.Event.START_OBJECT && first != JsonParser.Event.START_ARRAY) {
                return false;
            }
            parsed = parser.getValue();
            // getValue() consumed exactly the top-level structure; a remaining
            // token means trailing junk ({"a":1}garbage) or a second document.
            if (parser.hasNext()) {
                return false;
            }
        } catch (JsonException malformed) {
            return false;
        }
        return !containsNul(parsed);
    }

    /**
     * True if any string node — object key or string value, at any depth —
     * carries the U+0000 code point that PostgreSQL {@code jsonb} cannot store.
     * The parser has already decoded escapes, so a U+0000 escape in the source
     * surfaces here as an actual NUL char, while an escaped backslash followed
     * by the literal text {@code u0000} does not.
     */
    private static boolean containsNul(JsonValue value) {
        return switch (value.getValueType()) {
            case STRING -> ((JsonString) value).getString().indexOf('\u0000') >= 0;
            case OBJECT -> ((JsonObject) value).entrySet().stream()
                    .anyMatch(entry -> entry.getKey().indexOf('\u0000') >= 0
                            || containsNul(entry.getValue()));
            case ARRAY -> ((JsonArray) value).stream().anyMatch(DefaultRedactionHook::containsNul);
            default -> false;
        };
    }
}
