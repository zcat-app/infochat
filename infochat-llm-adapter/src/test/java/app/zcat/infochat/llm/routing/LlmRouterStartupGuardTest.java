package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.impl.AnthropicProvider;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-577: verifies the provider/base-url/model mismatch scan. The guard
 * flags an internally-inconsistent (provider, base-url, model) triple that
 * would HTTP-400 every call — the DeepSeek misconfig that produced 3883
 * silent 400s this session (a {@code remote-llm} profile pointed at DeepSeek
 * while {@code provider=anthropic} and Ollama model names stayed in place).
 *
 * <p>Two shapes must be flagged (acceptance minimum): (a) {@code anthropic}
 * against an OpenAI-format endpoint — neither an {@code anthropic.com} host
 * nor an {@code /anthropic}-path route (a third-party Anthropic-format route,
 * DeepSeek-style, is a valid pairing) — and (b) {@code openai-compatible}
 * with a local-runtime model name against a non-loopback remote. The three
 * supported shapes — local Ollama, an Anthropic remote, a correctly-configured
 * OpenAI-compatible remote — must NOT be flagged. Fail-fast aborts on a
 * flagged triple; advisory (the default) only WARNs.
 *
 * <p>Plain JUnit5 (no Quarkus boot): the pure-function seams
 * {@link LlmRouterStartupGuard#detectProviderModelMismatches(Map)} and
 * {@link LlmRouterStartupGuard#checkProviderModelMismatch(Map, boolean)} are
 * invoked directly with hand-rolled snapshots — the same seam the local-only
 * tests use. Remote host literals ({@code api.deepseek.com}) are treated as
 * non-loopback whether or not DNS resolves them, so the assertions are stable
 * offline; the "is flagged" checks read the returned finding list directly
 * rather than captured logs, so the incidental "DNS resolution failed" WARN
 * an offline remote lookup emits cannot perturb them.
 */
class LlmRouterStartupGuardTest {

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_ANTHROPIC_FORMAT_BASE_URL = "https://api.deepseek.com/anthropic";
    private static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final String OPENAI_FORMAT_REMOTE_BASE_URL = "https://api.openai.com/v1";
    private static final String LOOPBACK_BASE_URL = "http://localhost:11434/v1";

    // Per-task keys, spelled as the operator sets them. CHAT_AGENT stands in
    // for the chat/summarizer/translator tasks the misconfig left on
    // provider=anthropic; TAGGER for the tagger/entity/security tasks it left
    // on Ollama model names.
    private static final String CHAT_PROVIDER_KEY = "infochat.llm.chat.provider";
    private static final String CHAT_BASE_URL_KEY = "infochat.llm.chat.base-url";
    private static final String CHAT_MODEL_KEY = "infochat.llm.chat.model";
    private static final String TAGGER_PROVIDER_KEY = "infochat.llm.tagger.provider";
    private static final String TAGGER_BASE_URL_KEY = "infochat.llm.tagger.base-url";
    private static final String TAGGER_MODEL_KEY = "infochat.llm.tagger.model";

    private Logger jul;
    private CapturingHandler capturer;

    @BeforeEach
    void attachLogHandler() {
        jul = Logger.getLogger(LlmRouterStartupGuard.class.getName());
        capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        jul.addHandler(capturer);
        jul.setLevel(Level.ALL);
    }

    @AfterEach
    void detachLogHandler() {
        jul.removeHandler(capturer);
    }

    @Test
    void anthropicProviderAgainstNonAnthropicHostIsFlagged() {
        // Shape (a): the Anthropic wire dialect against a DeepSeek host — the
        // chat/summarizer/translator half of the DeepSeek misconfig.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(CHAT_PROVIDER_KEY, AnthropicProvider.PROVIDER_NAME);
        snapshot.put(CHAT_BASE_URL_KEY, DEEPSEEK_BASE_URL);
        snapshot.put(CHAT_MODEL_KEY, "claude-3-5-sonnet-20240620");

        List<String> findings = LlmRouterStartupGuard.detectProviderModelMismatches(snapshot);
        String finding = onlyFindingFor(findings, "CHAT_AGENT");
        // The WARN must name the task, provider, base-url host, model, and fix.
        assertTrue(finding.contains("CHAT_AGENT"), "names the task; got: " + finding);
        assertTrue(finding.contains("provider=" + AnthropicProvider.PROVIDER_NAME),
            "names the provider; got: " + finding);
        assertTrue(finding.contains("api.deepseek.com"), "names the base-url host; got: " + finding);
        assertTrue(finding.contains("claude-3-5-sonnet-20240620"), "names the model; got: " + finding);
        assertTrue(finding.contains("Fix:"), "states the likely fix; got: " + finding);
    }

    @Test
    void openAiCompatibleWithLocalModelAgainstRemoteHostIsFlagged() {
        // Shape (b): an Ollama model name against a DeepSeek host — the
        // tagger/entity/security half. Provider is left unset so this also
        // exercises the router's openai-compatible default fallback.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(TAGGER_BASE_URL_KEY, DEEPSEEK_BASE_URL);
        snapshot.put(TAGGER_MODEL_KEY, "llama3.1:8b");

        List<String> findings = LlmRouterStartupGuard.detectProviderModelMismatches(snapshot);
        String finding = onlyFindingFor(findings, "TAGGER");
        assertTrue(finding.contains("TAGGER"), "names the task; got: " + finding);
        assertTrue(finding.contains("provider=" + OpenAiCompatibleProvider.PROVIDER_NAME),
            "names the (defaulted) provider; got: " + finding);
        assertTrue(finding.contains("api.deepseek.com"), "names the base-url host; got: " + finding);
        assertTrue(finding.contains("llama3.1:8b"), "names the model; got: " + finding);
        assertTrue(finding.contains("Fix:"), "states the likely fix; got: " + finding);
    }

    @Test
    void localOllamaShapeIsNotFlagged() {
        // Supported shape 1: openai-compatible (defaulted) + a local model +
        // a LOOPBACK base-url. The loopback host is what keeps shape (b) from
        // firing — the normal local-Ollama setup.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(TAGGER_BASE_URL_KEY, LOOPBACK_BASE_URL);
        snapshot.put(TAGGER_MODEL_KEY, "llama3.1:8b");

        assertTrue(LlmRouterStartupGuard.detectProviderModelMismatches(snapshot).isEmpty(),
            "local Ollama (loopback + llama model) must not be flagged");
        assertDoesNotThrow(() -> LlmRouterStartupGuard.checkProviderModelMismatch(snapshot, true),
            "a supported shape must not abort even in fail-fast mode");
    }

    @Test
    void anthropicRemoteShapeIsNotFlagged() {
        // Supported shape 2: provider=anthropic against an Anthropic host with
        // claude-* models — the correctly-configured Anthropic remote.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(CHAT_PROVIDER_KEY, AnthropicProvider.PROVIDER_NAME);
        snapshot.put(CHAT_BASE_URL_KEY, ANTHROPIC_BASE_URL);
        snapshot.put(CHAT_MODEL_KEY, "claude-3-5-sonnet-20240620");

        assertTrue(LlmRouterStartupGuard.detectProviderModelMismatches(snapshot).isEmpty(),
            "anthropic provider against api.anthropic.com must not be flagged");
        assertDoesNotThrow(() -> LlmRouterStartupGuard.checkProviderModelMismatch(snapshot, true),
            "a supported shape must not abort even in fail-fast mode");
    }

    @Test
    void openAiCompatibleRemoteWithProviderNativeModelIsNotFlagged() {
        // Supported shape 3: openai-compatible against a remote host with a
        // model the remote actually serves (not a local-runtime family name)
        // — the correctly-configured OpenAI-compatible remote.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(CHAT_PROVIDER_KEY, OpenAiCompatibleProvider.PROVIDER_NAME);
        snapshot.put(CHAT_BASE_URL_KEY, OPENAI_FORMAT_REMOTE_BASE_URL);
        snapshot.put(CHAT_MODEL_KEY, "gpt-4o-mini");

        assertTrue(LlmRouterStartupGuard.detectProviderModelMismatches(snapshot).isEmpty(),
            "openai-compatible + a provider-native model against a remote host must not be flagged");
        assertDoesNotThrow(() -> LlmRouterStartupGuard.checkProviderModelMismatch(snapshot, true),
            "a supported shape must not abort even in fail-fast mode");
    }

    @Test
    void anthropicProviderAgainstAnthropicPathRouteIsNotFlagged() {
        // Some OpenAI-compatible vendors also expose an Anthropic-FORMAT
        // endpoint on an /anthropic path (DeepSeek's api.deepseek.com/anthropic
        // is the documented case) — provider=anthropic against such a route is
        // a valid, working pairing. Host-only detection would false-positive
        // here and, under fail-fast, block a correct config. No model key set:
        // shape (a) judges the (provider, base-url) pair alone.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(CHAT_PROVIDER_KEY, AnthropicProvider.PROVIDER_NAME);
        snapshot.put(CHAT_BASE_URL_KEY, DEEPSEEK_ANTHROPIC_FORMAT_BASE_URL);

        assertTrue(LlmRouterStartupGuard.detectProviderModelMismatches(snapshot).isEmpty(),
            "anthropic provider against an /anthropic-path route must not be flagged");
        assertDoesNotThrow(() -> LlmRouterStartupGuard.checkProviderModelMismatch(snapshot, true),
            "a valid Anthropic-format pairing must not abort even in fail-fast mode");
    }

    @Test
    void failFastAbortsOnFlaggedTriple() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(CHAT_PROVIDER_KEY, AnthropicProvider.PROVIDER_NAME);
        snapshot.put(CHAT_BASE_URL_KEY, DEEPSEEK_BASE_URL);
        snapshot.put(CHAT_MODEL_KEY, "claude-3-5-sonnet-20240620");

        LlmRouterStartupGuard.ProviderModelMismatchException ex = assertThrows(
            LlmRouterStartupGuard.ProviderModelMismatchException.class,
            () -> LlmRouterStartupGuard.checkProviderModelMismatch(snapshot, true),
            "a flagged triple with fail-fast=true must abort startup");
        assertTrue(ex.getMessage().contains(LlmRouterStartupGuard.CONFIG_KEY_MISMATCH_FAIL_FAST),
            "abort message must name the fail-fast flag; got: " + ex.getMessage());
    }

    @Test
    void advisoryModeWarnsButDoesNotAbortOnFlaggedTriple() {
        // Shape (a) so the only WARN is the finding itself (this path parses
        // the host without a DNS lookup, so no incidental resolution WARN).
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(CHAT_PROVIDER_KEY, AnthropicProvider.PROVIDER_NAME);
        snapshot.put(CHAT_BASE_URL_KEY, DEEPSEEK_BASE_URL);
        snapshot.put(CHAT_MODEL_KEY, "claude-3-5-sonnet-20240620");

        assertDoesNotThrow(() -> LlmRouterStartupGuard.checkProviderModelMismatch(snapshot, false),
            "advisory mode must not abort on a flagged triple");

        List<LogRecord> warns = capturer.recordsAtLevel(Level.WARNING);
        assertTrue(warns.stream().anyMatch(r -> {
            String m = CapturingHandler.formatMessage(r);
            return m.contains("CHAT_AGENT") && m.contains("mismatch");
        }), "advisory mode must emit a WARN naming the mismatched task; captured: "
            + capturer.formattedAll());
    }

    @Test
    void cleanConfigEmitsNoWarnAndDoesNotAbort() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(CHAT_PROVIDER_KEY, OpenAiCompatibleProvider.PROVIDER_NAME);
        snapshot.put(CHAT_BASE_URL_KEY, LOOPBACK_BASE_URL);
        snapshot.put(CHAT_MODEL_KEY, "llama3.1:8b");

        assertDoesNotThrow(() -> LlmRouterStartupGuard.checkProviderModelMismatch(snapshot, false),
            "a clean config must not abort");
        assertTrue(capturer.recordsAtLevel(Level.WARNING).isEmpty(),
            "a clean config must emit no WARN; captured: " + capturer.formattedAll());
    }

    /**
     * Assert exactly one finding is present and that it concerns the named
     * task, then return it. Keeps each behavioral test focused on message
     * content while proving the scan does not over-flag sibling tasks.
     */
    private static String onlyFindingFor(List<String> findings, String taskName) {
        assertFalse(findings.isEmpty(), "expected the triple to be flagged, but no finding was returned");
        assertTrue(findings.size() == 1,
            "expected exactly one finding for " + taskName + ", got: " + findings);
        return findings.get(0);
    }

    static {
        // Quarkus tests can leave WARNING-and-above on the root logger
        // suppressed depending on the LogManager bootstrap order. Pin ALL on
        // the class logger explicitly so the @BeforeEach attach captures
        // every record.
        Logger.getLogger(LlmRouterStartupGuard.class.getName()).setLevel(Level.ALL);
    }
}
