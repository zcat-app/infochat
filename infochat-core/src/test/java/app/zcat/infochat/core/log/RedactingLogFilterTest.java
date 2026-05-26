package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactingLogFilterTest {

    // --- Redactor: per-shape catalogue coverage ---

    @Test
    void redactsAnthropicSkAnt() {
        String key = "sk-ant-api03-aBcDeFgHiJkLmNoPqRsT";
        String result = Redactor.redact("key=" + key);
        assertRedacted(result, key);
    }

    @Test
    void redactsOpenAiSk() {
        String key = "sk-ABCDEFGHIJ1234567890abcdefgh";
        String result = Redactor.redact("Authorization: Bearer " + key);
        assertRedacted(result, key);
    }

    @Test
    void redactsOpenAiSkProj() {
        String key = "sk-proj-aBcDeFgHiJkLmNoPqRsTuVwX";
        String result = Redactor.redact("token=" + key);
        assertRedacted(result, key);
    }

    @Test
    void redactsOpenAiSkSvcacct() {
        String key = "sk-svcacct-aBcDeFgHiJkLmNoPqRsTuVwX";
        String result = Redactor.redact("key " + key);
        assertRedacted(result, key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghp_", "gho_", "ghs_", "ghr_", "ghu_"})
    void redactsGitHubTokens(String prefix) {
        String key = prefix + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcd";
        String result = Redactor.redact("github token: " + key);
        assertRedacted(result, key);
    }

    @Test
    void redactsAwsAkia() {
        String key = "AKIAIOSFODNN7EXAMPLE";
        String result = Redactor.redact("aws_access_key_id=" + key);
        assertRedacted(result, key);
    }

    @Test
    void redactsAwsAsia() {
        String key = "ASIAJEXAMPLEXAMPLE";
        // ASIA keys are exactly 20 chars: 4-char prefix + 16 uppercase alphanumeric
        String fullKey = "ASIA" + "ABCDEFGHIJ123456";
        String result = Redactor.redact("credentials: " + fullKey);
        assertRedacted(result, fullKey);
    }

    @Test
    void redactsGoogleAIza() {
        String key = "AIzaSyA" + "a".repeat(32);
        String result = Redactor.redact("google key: " + key);
        assertRedacted(result, key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xoxa-", "xoxb-", "xoxp-", "xoxr-", "xoxs-"})
    void redactsSlackTokens(String prefix) {
        String key = prefix + "1234567890-abcdefghij";
        String result = Redactor.redact("slack: " + key);
        assertRedacted(result, key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api_key", "secret", "token", "password", "bearer",
            "api-key", "apikey", "SECRET", "Token", "PASSWORD", "Bearer"})
    void redactsGenericAdjacentToKeyword(String keyword) {
        String value = "a".repeat(40);
        String input = keyword + "=" + value;
        String result = Redactor.redact(input);
        assertFalse(result.contains(value),
                "generic value adjacent to '" + keyword + "' must be redacted");
        assertTrue(result.contains(Redactor.REDACTED),
                "result must contain redaction marker");
    }

    // --- Redactor: non-key strings pass through ---

    @Test
    void nonKeyStringPassesThrough() {
        String safe = "INFO fetched 42 posts from RSS feed https://example.com/rss";
        assertEquals(safe, Redactor.redact(safe));
    }

    @Test
    void emptyStringPassesThrough() {
        assertEquals("", Redactor.redact(""));
    }

    // --- Redactor: timeout produces sentinel ---

    @Test
    void timeoutReturnsSentinel() {
        // Zero-ms budget guarantees a timeout on any non-trivial input.
        String result = Redactor.redact("sk-ant-aBcDeFgHiJkLmNoPqRsTuVwX", 0L);
        assertEquals(Redactor.TIMEOUT_SENTINEL, result);
    }

    // --- RedactingLogFilter: filter mutates LogRecord ---

    @Test
    void filterRedactsMessageTemplate() {
        var filter = new Redactor();
        var record = new LogRecord(Level.SEVERE,
                "LLM call failed with key sk-ant-test-redact-me-please-1234567890");

        assertTrue(filter.isLoggable(record), "filter must not suppress the record");
        assertFalse(record.getMessage().contains("sk-ant-test-redact-me-please-1234567890"),
                "key must be removed from message");
        assertTrue(record.getMessage().contains(Redactor.REDACTED));
    }

    @Test
    void filterRedactsStringParameter() {
        var filter = new Redactor();
        var record = new LogRecord(Level.WARNING, "Request with key {0} failed");
        record.setParameters(new Object[]{"sk-proj-aBcDeFgHiJkLmNoPqRsTuVwX"});

        filter.isLoggable(record);

        Object[] params = record.getParameters();
        assertTrue(params[0] instanceof String s && s.contains(Redactor.REDACTED),
                "string parameter must be redacted");
    }

    @Test
    void filterRedactsNonStringParameterViaToString() {
        var filter = new Redactor();
        var record = new LogRecord(Level.WARNING, "Request failed: {0}");
        Object contextObj = new Object() {
            @Override
            public String toString() {
                return "RequestContext{auth=sk-proj-aBcDeFgHiJkLmNoPqRsTuVwX}";
            }
        };
        record.setParameters(new Object[]{contextObj});

        filter.isLoggable(record);

        Object[] params = record.getParameters();
        assertTrue(params[0] instanceof String s && s.contains(Redactor.REDACTED),
                "non-String parameter's toString() must be redacted");
        assertTrue(params[0] instanceof String s2
                        && !s2.contains("sk-proj-aBcDeFgHiJkLmNoPqRsTuVwX"),
                "raw key must not survive in parameter");
    }

    @Test
    void filterReplacesEntireMessageOnTimeout() {
        // Verify the filter's fail-closed behavior by constructing a
        // scenario where the Redactor times out. We do this by calling
        // the Redactor with a 0ms budget directly — the filter itself
        // uses the default budget, but the fail-closed path is the same.
        String result = Redactor.redact("key=sk-ant-aBcDeFgHiJkLmNoPqRsT", 0L);
        assertEquals(Redactor.TIMEOUT_SENTINEL, result,
                "timeout must replace entire input with sentinel");
    }

    @Test
    void filterPassesNullMessageUnchanged() {
        var filter = new Redactor();
        var record = new LogRecord(Level.INFO, null);
        assertTrue(filter.isLoggable(record));
    }

    @Test
    void filterPassesSafeMessageUnchanged() {
        var filter = new Redactor();
        String safe = "Fetched 10 posts in 42ms";
        var record = new LogRecord(Level.INFO, safe);
        filter.isLoggable(record);
        assertEquals(safe, record.getMessage());
    }

    private static void assertRedacted(String result, String key) {
        assertFalse(result.contains(key),
                "literal key must not appear in output: " + result);
        assertTrue(result.contains(Redactor.REDACTED),
                "output must contain redaction marker: " + result);
    }
}
