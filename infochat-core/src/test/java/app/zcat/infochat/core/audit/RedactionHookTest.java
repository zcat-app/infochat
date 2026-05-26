package app.zcat.infochat.core.audit;

import app.zcat.infochat.core.log.Redactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit unit tests for {@link DefaultRedactionHook}. No
 * Quarkus runtime — the hook is constructed via the public
 * constructor and exercised against synthesized {@link RedactionHook.AuditRow}
 * inputs.
 *
 * <p>The seven shape families from {@code docs/spec/security.md}
 * §Secrets handling each get a parameterized scenario that seeds a
 * key shape inside a JSON-shaped {@code details_json} blob and
 * asserts the hook redacts it. The pinned non-redaction case
 * (contact-id substring) and the watchdog fail-closed branch round
 * out the contract.</p>
 */
class RedactionHookTest {

    private final DefaultRedactionHook hook = new DefaultRedactionHook();

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
            // family, seeded key shape (the value that must be redacted)
            "OpenAI sk-,                       sk-abcdef0123456789abcdef0123456789",
            "OpenAI sk-proj-,                  sk-proj-AbCdEf0123456789AbCdEf0123456789",
            "OpenAI sk-svcacct-,               sk-svcacct-0123456789AbCdEfGhIjKlMnOpQr",
            "Anthropic sk-ant-,                sk-ant-api03-abcdef0123456789ABCDEF0123456789",
            "GitHub ghp_,                      ghp_AbCdEf0123456789AbCdEf0123456789",
            "GitHub gho_,                      gho_AbCdEf0123456789AbCdEf0123456789",
            "GitHub ghs_,                      ghs_AbCdEf0123456789AbCdEf0123456789",
            "AWS AKIA,                         AKIAIOSFODNN7EXAMPLE",
            "AWS ASIA,                         ASIAIOSFODNN7EXAMPLE",
            "Google AIza,                      AIzaSyA-1234567890abcdefghijklmnopqrstuvw",
            "Slack xoxb-,                      xoxb-1234567890-abcdef0123456789",
            "Generic api_key= base64 (32+),    api_key=AbCdEf0123456789AbCdEf0123456789AbCd"
    })
    void apiKeyShapeIsRedacted(String family, String key) {
        String detailsJson = "{\"payload\":\"" + key + "\"}";
        RedactionHook.AuditRow row = audit(detailsJson);

        RedactionHook.AuditRow redacted = hook.redact(row);

        assertFalse(redacted.detailsJson().contains(key),
                "family " + family + " — raw key survived: " + redacted.detailsJson());
        assertTrue(redacted.detailsJson().contains(Redactor.REDACTED),
                "family " + family + " — placeholder missing: " + redacted.detailsJson());
        if (family.startsWith("Generic")) {
            assertTrue(redacted.detailsJson().contains("api_key="),
                    "generic pattern must preserve keyword prefix: " + redacted.detailsJson());
        }
    }

    @Test
    void nonApiKeyValuesPassThrough() {
        // A plain JSON blob carrying a short identifier, a UUID, and
        // the literal substring "restored_group_admin" (the
        // UnbanCommandHandler audit-row shape). None of these match
        // the API-key catalogue; the hook must return the row
        // unchanged.
        String detailsJson = "{\"restored_group_admin\":[\"" + UUID.randomUUID()
                + "\"],\"reason\":\"unrelated\"}";
        RedactionHook.AuditRow row = audit(detailsJson);

        RedactionHook.AuditRow redacted = hook.redact(row);

        assertSame(row, redacted,
                "non-API-key payload should return the SAME instance (no allocation)");
        assertEquals(detailsJson, redacted.detailsJson());
    }

    @Test
    void targetContactIdIsNotRedacted() {
        // Per spec §Secrets handling, contact-id redaction is "outside
        // the audit log". The hook must NOT mutate target_contact_id
        // even if it carries an API-key-shaped substring (which is
        // pathological but the contract is clear).
        String contactId = "AKIAIOSFODNN7EXAMPLE";
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(UUID.randomUUID())
                .actorContactId("actor-contact-id-1234567890")
                .actorAdapter("test-adapter")
                .action(AuditAction.BAN)
                .targetKind("user")
                .targetId(UUID.randomUUID().toString())
                .targetContactId(contactId)
                .requestId("req-1")
                .detailsJson("{\"reason\":\"none\"}")
                .build();

        RedactionHook.AuditRow redacted = hook.redact(row);

        assertEquals(contactId, redacted.targetContactId(),
                "target_contact_id must pass through unredacted (spec §Secrets handling)");
    }

    @Test
    void detailsJsonContainingContactIdShapeIsNotRedacted() {
        // Contact ids in details_json (e.g. when carried as part of an
        // unban-restored list) are not API-key-shaped, so the hook is
        // a no-op on them. The audit_log_view's redact_contact_id()
        // applies at read time.
        String detailsJson = "{\"restored_group_admin\":[\"" + UUID.randomUUID() + "\"]}";
        RedactionHook.AuditRow row = audit(detailsJson);

        RedactionHook.AuditRow redacted = hook.redact(row);

        assertEquals(detailsJson, redacted.detailsJson());
    }

    @Test
    void nullDetailsJsonPassesThrough() {
        RedactionHook.AuditRow row = audit(null);
        RedactionHook.AuditRow redacted = hook.redact(row);
        assertSame(row, redacted);
    }

    @Test
    void emptyDetailsJsonPassesThrough() {
        RedactionHook.AuditRow row = audit("");
        RedactionHook.AuditRow redacted = hook.redact(row);
        assertSame(row, redacted);
    }

    @Test
    void multipleKeysInOneFieldAreAllRedacted() {
        String openai = "sk-AbCdEf0123456789AbCdEf0123456789";
        String aws = "AKIAIOSFODNN7EXAMPLE";
        String detailsJson = "{\"a\":\"" + openai + "\",\"b\":\"" + aws + "\"}";

        RedactionHook.AuditRow redacted = hook.redact(audit(detailsJson));

        assertFalse(redacted.detailsJson().contains(openai), "OpenAI key survived");
        assertFalse(redacted.detailsJson().contains(aws), "AWS key survived");
    }

    @Test
    void watchdogFallbackIsValidJsonb() {
        // The watchdog-fired fallback must be a valid JSON document
        // so AuditLogWriter's ?::jsonb cast succeeds. The timeout
        // path itself is tested in RedactingLogFilterTest; this test
        // pins the JSONB shape of the audit-specific sentinel.
        String fallback = DefaultRedactionHook.REDACTED_FIELD_JSONB;
        assertTrue(fallback.startsWith("{") && fallback.endsWith("}"),
                "fallback must be a JSON object: " + fallback);
        assertTrue(fallback.contains("\"_redacted\":true"),
                "fallback must carry the _redacted=true flag: " + fallback);
        assertTrue(fallback.contains("\"reason\""),
                "fallback must carry a reason key for operator triage: " + fallback);
    }

    @Test
    void genericPatternPreservesJsonStructure() {
        String value = "AbCdEf0123456789AbCdEf0123456789AbCd";
        String detailsJson = "{\"token\":\"" + value + "\"}";
        RedactionHook.AuditRow row = audit(detailsJson);

        RedactionHook.AuditRow redacted = hook.redact(row);

        assertFalse(redacted.detailsJson().contains(value),
                "secret value survived: " + redacted.detailsJson());
        assertTrue(redacted.detailsJson().contains("\"token\""),
                "keyword was consumed by replacement: " + redacted.detailsJson());
        assertTrue(redacted.detailsJson().contains("\"token\":\""),
                "JSON structure was damaged: " + redacted.detailsJson());
    }

    @Test
    void genericPatternProducesValidJson() {
        String value = "A".repeat(64);
        String detailsJson = "{\"token\":\"" + value + "\"}";
        RedactionHook.AuditRow row = audit(detailsJson);

        RedactionHook.AuditRow redacted = hook.redact(row);

        assertEquals("{\"token\":\"[REDACTED]\"}", redacted.detailsJson());
    }

    private static RedactionHook.AuditRow audit(String detailsJson) {
        return RedactionHook.AuditRow.builder()
                .actorUserId(UUID.randomUUID())
                .actorContactId("actor-1234567890ab")
                .actorAdapter("test-adapter")
                .action(AuditAction.LLM_OUTPUT_SANITIZED)
                .targetKind("system")
                .targetId("target-1")
                .requestId("req-1")
                .detailsJson(detailsJson)
                .build();
    }
}
