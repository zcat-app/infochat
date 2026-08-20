package app.zcat.infochat.llm.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@code remote} backend wiring (M1-529, D54): even with a remote chat
 * backend, embeddings MUST run on a local nomic-768 Ollama endpoint and NEVER be
 * routed to the operator's remote provider. This is the regression guard for the
 * F11 bug, where the remote branch pointed {@code infochat.embeddings.base-url} at
 * the remote endpoint while leaving the model at {@code nomic-embed-text} — which
 * either fails the embeddings call (no such model remotely) or returns a different
 * dimension that {@code EmbeddingMetadataStartupGuard} rejects at Collector boot.
 *
 * <p>Drives the real {@code prod/scripts/4-llm.sh} remote branch with a fake
 * {@code docker} on {@code PATH} (no-ops the compose up / readiness / pull) and
 * scripted stdin, then asserts the generated {@code application.properties}: the
 * SHARED {@code infochat.llm.default.{base-url,api-key}} keys (written once and
 * inherited by all seven generative tasks, D56/M1-603 — no per-task
 * base-url/api-key lines are written at all), while {@code infochat.embeddings.*}
 * resolves to the local Ollama nomic endpoint and is NEVER the remote base-url.
 * Mirrors the generated-config layer of {@link LlamacppWiringTest}; no
 * static-compose assertions are needed for this branch.
 *
 * <p>Linux-gated for the same reason as {@link LlamacppWiringTest}: {@code 4-llm.sh}
 * uses GNU {@code sed -i} and bash, matching the Linux deployment target. The
 * real-endpoint smoke (a live remote chat call + a local Ollama embedding) is a
 * manual VPS check, too heavy for {@code mvn verify}.
 */
class RemoteLlmWiringTest {

    // Kept in lock-step with 4-llm.sh: the local embeddings endpoint + model the
    // remote branch must wire, and the frozen embedding dimension.
    private static final String OLLAMA_URL = "http://ollama:11434/v1";
    private static final String OLLAMA_NOMIC = "nomic-embed-text";
    private static final String EMBEDDINGS_DIMENSION = "768";

    // The remote endpoint + key the operator types; arbitrary test values.
    private static final String REMOTE_BASE_URL = "https://remote.example.com/v1";
    private static final String REMOTE_API_KEY = "sk-test-remote-key";
    // The generative model the operator enters for the openai-compatible dialect
    // (M1-614). Deliberately NOT a local-runtime prefix (llama/nomic/qwen/mistral)
    // so a remote base-url + this model does not trip the M1-577 mismatch scan.
    private static final String REMOTE_MODEL = "gpt-oss-120b";
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    // Four Enter answers accepting the recommended values at M1-550's step-4
    // prompt_timing reads (chat/summarizer × timeout-ms/max-tokens) — every
    // wizard drive must supply them or the script dies at EOF under set -e.
    private static final String ACCEPT_TIMING_DEFAULTS = "\n\n\n\n";
    // One appended Enter answering the M1-895 reply-mode ask (fires after the
    // timing reads); every tail-reaching drive must supply it or the script
    // dies at EOF under set -e — the new prompt must never add unasked stdin.
    private static final String ACCEPT_REPLYMODE_DEFAULT = "\n";

    @Test
    @EnabledOnOs(OS.LINUX)
    void remoteBackendWiresGenerativeRemoteButEmbeddingsLocal(@TempDir Path tmp) throws Exception {
        // stdin: backend=remote, provider dialect Enter (accepts openai-compatible),
        // base-url, model (M1-614), then the API key (no key yet in secrets.env, so
        // the branch reads it from stdin), timing defaults (4× Enter).
        Map<String, String> props = runWizard(tmp,
                "remote\n" + "\n" + REMOTE_BASE_URL + "\n" + REMOTE_MODEL + "\n"
                        + REMOTE_API_KEY + "\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT);

        // Generative tasks → the SHARED default endpoint + API-key reference,
        // written ONCE (D56/M1-603). Because inheritance is task-agnostic, a
        // future new ModelTask is routed remote automatically — the general
        // form of the M1-599 classifier guarantee (an operator remote switch
        // must not silently leave any task on localhost).
        assertEquals(REMOTE_BASE_URL, props.get("infochat.llm.default.base-url"),
                "the remote endpoint must be written once as the shared default");
        assertEquals("${INFOCHAT_LLM_API_KEY}", props.get("infochat.llm.default.api-key"),
                "the shared default must reference the API key from secrets.env");
        // The openai-compatible dialect (Enter default) is written explicitly for a
        // self-describing config (M1-614), even though it equals the LlmRouter
        // default.
        assertEquals("openai-compatible", props.get("infochat.llm.default.provider"),
                "the openai-compatible dialect must be written on the shared default");
        // No per-task route lines: a stale per-task line would win over the
        // shared default and silently pin its task to the old endpoint, so the
        // wizard writes none — the classifier (M1-599) included. The generative
        // MODEL, however, IS written per task (M1-614): leaving the baked local
        // model on a remote task 400s every call and trips the M1-577 mismatch scan.
        for (String task : new String[]{
                "security", "tagger", "entity", "classifier", "summarizer", "chat", "translator"}) {
            assertNull(props.get("infochat.llm." + task + ".base-url"),
                    task + " must carry no per-task base-url (inherits the shared default)");
            assertNull(props.get("infochat.llm." + task + ".api-key"),
                    task + " must carry no per-task api-key (inherits the shared default)");
            assertEquals(REMOTE_MODEL, props.get("infochat.llm." + task + ".model"),
                    task + " must carry the operator-entered remote model");
        }

        // Embeddings → local Ollama nomic, never the remote endpoint (the F11 guard).
        assertEquals(OLLAMA_URL, props.get("infochat.embeddings.base-url"),
                "embeddings must point at the local Ollama endpoint");
        assertEquals(OLLAMA_NOMIC, props.get("infochat.embeddings.model"),
                "embeddings must use the local nomic model");
        assertEquals(EMBEDDINGS_DIMENSION, props.get("infochat.embeddings.dimension"),
                "embeddings dimension must stay 768");
        assertNotEquals(REMOTE_BASE_URL, props.get("infochat.embeddings.base-url"),
                "embeddings must NEVER resolve to the remote endpoint (the F11/M1-529 bug)");

        // Timing prompts accepted with Enter must write the backend-first remote
        // recommendations (M1-550's table: a remote API answers prose in seconds
        // regardless of profile, so the local-profile values never apply here).
        assertEquals("60000", props.get("infochat.llm.chat.timeout-ms"),
                "chat timeout-ms must be the remote-backend recommendation");
        assertEquals("1024", props.get("infochat.llm.chat.max-tokens"),
                "chat max-tokens must be the remote-backend recommendation");
        assertEquals("60000", props.get("infochat.llm.summarizer.timeout-ms"),
                "summarizer timeout-ms must be the remote-backend recommendation");
        assertEquals("1024", props.get("infochat.llm.summarizer.max-tokens"),
                "summarizer max-tokens must be the remote-backend recommendation");

        // The API key lives in secrets.env (never in application.properties).
        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLM_API_KEY=\"" + REMOTE_API_KEY + "\""),
                "the wizard must mint the API key into secrets.env:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void installTimeDisclosurePutsTranslatorInTheLoudTierWithBothExposureFacts(@TempDir Path tmp)
            throws Exception {
        // M1-758. This is the EARLIER of the two runtime privacy disclosures and
        // reaches MORE operators: it prints before the operator types the remote
        // URL/key, and an operator who picks remote here and never runs
        // prod/switch-llm.sh sees only this text. It must therefore carry the same
        // two facts the switcher's Phase 4 block does, not the weaker pre-M1-746
        // wording that framed translator as bot-reply echo in the public-post tier.
        WizardRun run = runWizardCapturingOutput(tmp,
                "remote\n" + "\n" + REMOTE_BASE_URL + "\n" + REMOTE_MODEL + "\n"
                        + REMOTE_API_KEY + "\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT);

        assertTrue(run.output().contains("!! translator — carries PRIVATE user text"),
                "translator must sit in the loud/private tier beside chat at INSTALL time,"
                        + " not among the public-post tasks:\n" + run.output());
        // Target the pre-M1-746 framing by what is FALSE about it — its tier
        // placement and its "only bot prose" scope — never by a substring of the
        // reply-prose leg's natural phrasing. That leg is real (ChatAgent:558)
        // and spec-enumerated, so an assertion barring the words used to describe
        // it would make the suite block a TRUE exposure claim from being spelled
        // out, which is the anti-pattern this ticket exists to remove.
        assertFalse(run.output().contains("-  translator"),
                "translator must not sit in the '-' (public-post / topic-interest) tier"
                        + " the block itself defines as 'not private user data':\n" + run.output());
        assertFalse(run.output().contains("exposes the bot-reply text (can echo your queries)"),
                "the pre-M1-746 wording scoped translator to bot-prose echo, which"
                        + " understates six of its seven legs:\n" + run.output());

        // Fact 1 — the /lang-gated legs carry private user text.
        assertTrue(run.output().contains("IS your raw message, truncated, NOT redacted"),
                "the search query is the user's raw, unredacted message:\n" + run.output());
        // Fact 2 — the ingest leg, which no scope's /lang gates.
        assertTrue(run.output().contains("full TITLE AND BODY"),
                "the ingest leg sends whole posts, not headlines:\n" + run.output());
        assertTrue(run.output().contains("gated on the SOURCE's"),
                "the ingest leg must be attributed to the SOURCE's language:\n" + run.output());
        assertTrue(run.output().contains("all-English deployment is NOT exempt"),
                "an en-only operator must be told they are NOT exempt:\n" + run.output());
        assertFalse(run.output().contains("sends nothing"),
                "no blanket 'sends nothing' claim: the ingest leg ignores every scope's"
                        + " /lang, so the negative is false:\n" + run.output());

        // The mitigation advice must not promise per-task routing the switcher
        // cannot deliver (M1-603/D56: one backend for the whole deployment), nor
        // a pin-survival guard it does not have. switch-llm.sh's M1-605 gate only
        // REFUSES a bare-Enter run over a pinned config; a run that proceeds
        // deletes every pin unconditionally. Advising a pin without that caveat
        // hands the operator a control they think protects them and does not.
        assertTrue(run.output().contains("re-apply the pin"),
                "hand-pinning is only safe advice if the operator is told the pin does"
                        + " not survive a switch:\n" + run.output());
        assertFalse(run.output().contains("decline its consent prompt"),
                "there is no prompt that lets an operator switch AND keep a pin;"
                        + " promising one invents a safeguard:\n" + run.output());
        assertFalse(run.output().contains("one at a time"),
                "the switcher re-routes ALL generative tasks at once; promising per-task"
                        + " moves sends the operator to a tool that cannot do it:\n" + run.output());

        // Per-task parity with the switch-time block: this disclosure reaches more
        // operators, so no task's line may be weaker here. summarizer's unattended
        // whole-body leg (BodySummaryWorker, @Scheduled) is the one that diverged.
        assertTrue(run.output().contains("abstracts of EVERY long fetched PUBLIC post"),
                "summarizer's unattended ingest-time leg must be disclosed here too —"
                        + " 'summaries of the posts you query' reads as per-request:\n"
                        + run.output());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void remoteBackendDeepseekWritesProviderAndFixedModel(@TempDir Path tmp) throws Exception {
        // stdin: backend=remote, dialect=deepseek, base-url Enter (accepts the
        // https://api.deepseek.com default), NO model prompt (deepseek pins one
        // model), the API key, timing defaults (M1-614).
        Map<String, String> props = runWizard(tmp,
                "remote\n" + "deepseek\n" + "\n" + REMOTE_API_KEY + "\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT);

        // The deepseek dialect routes through the dedicated DeepSeekProvider:
        // provider=deepseek + deepseek-v4-flash on every task, endpoint defaulted
        // to DeepSeek. This triple passes the M1-577 mismatch scan cleanly
        // (deepseek is unscrutinized and in the remote-provider set).
        assertEquals("deepseek", props.get("infochat.llm.default.provider"),
                "the deepseek dialect must write provider=deepseek on the shared default");
        assertEquals(DEEPSEEK_BASE_URL, props.get("infochat.llm.default.base-url"),
                "the deepseek base-url must default to the DeepSeek endpoint");
        assertEquals("${INFOCHAT_LLM_API_KEY}", props.get("infochat.llm.default.api-key"),
                "the shared default must reference the API key from secrets.env");
        for (String task : new String[]{
                "security", "tagger", "entity", "classifier", "summarizer", "chat", "translator"}) {
            assertEquals(DEEPSEEK_MODEL, props.get("infochat.llm." + task + ".model"),
                    task + " must be pinned to deepseek-v4-flash");
            assertNull(props.get("infochat.llm." + task + ".base-url"),
                    task + " must carry no per-task base-url (inherits the shared default)");
            // deepseek runs thinking-off by default (M1-610), so the wizard writes
            // no reasoning-effort key — a set reasoning-effort with the wizard's
            // default max-tokens would trip the M1-610 coupling guard.
            assertNull(props.get("infochat.llm." + task + ".reasoning-effort"),
                    task + " must carry no reasoning-effort key (deepseek thinking-off)");
        }

        // The F11 guard still holds: embeddings run on the local Ollama nomic
        // endpoint regardless of the remote dialect.
        assertEquals(OLLAMA_URL, props.get("infochat.embeddings.base-url"),
                "embeddings must point at the local Ollama endpoint even for deepseek");
        assertEquals(OLLAMA_NOMIC, props.get("infochat.embeddings.model"),
                "embeddings must use the local nomic model");
        assertEquals(EMBEDDINGS_DIMENSION, props.get("infochat.embeddings.dimension"),
                "embeddings dimension must stay 768");

        // deepseek is a remote backend, so it takes the backend-first remote timing
        // recommendation (a remote API answers prose in seconds).
        assertEquals("60000", props.get("infochat.llm.chat.timeout-ms"),
                "chat timeout-ms must be the remote-backend recommendation");
        assertEquals("1024", props.get("infochat.llm.chat.max-tokens"),
                "chat max-tokens must be the remote-backend recommendation");

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLM_API_KEY=\"" + REMOTE_API_KEY + "\""),
                "the wizard must mint the API key into secrets.env:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void replyModeAskedAndWrittenForRemoteModel(@TempDir Path tmp) throws Exception {
        // M1-895: the single shared reply-mode ask fires on the remote branch
        // after chat-model selection; an operator-entered model is unmeasured,
        // so the conservative translate is written.
        WizardRun run = runWizardCapturingOutput(tmp,
                "remote\n" + "\n" + REMOTE_BASE_URL + "\n" + REMOTE_MODEL + "\n"
                        + REMOTE_API_KEY + "\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT);

        assertTrue(run.output().contains("recommendation for " + REMOTE_MODEL),
                "the recommendation must name the remote model:\n" + run.output());
        assertEquals("translate", run.props().get("infochat.chat.reply-mode"),
                "an unmeasured remote model must get translate written");
    }

    // --- helpers (trimmed mirror of LlamacppWiringTest) -------------------------

    /** Run prod/scripts/4-llm.sh with a fake docker on PATH; return generated props. */
    /** The captured stdout and parsed props of one wizard run. */
    private record WizardRun(String output, Map<String, String> props) {}

    private Map<String, String> runWizard(Path tmp, String stdin) throws Exception {
        return runWizardCapturingOutput(tmp, stdin).props();
    }

    private WizardRun runWizardCapturingOutput(Path tmp, String stdin) throws Exception {
        Path repoRoot = repoRoot();
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        // Seed the profile 1-profile.sh would have written. remote-llm is the
        // realistic pairing for the remote backend; the remote branch reads no
        // profile-derived model var, so this only satisfies the profile gate.
        Files.writeString(runtime.resolve("application.properties"), "quarkus.profile=remote-llm\n");

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, fakeDockerScript());
        fakeDocker.toFile().setExecutable(true);

        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot.resolve("prod/scripts/4-llm.sh").toString());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", ""));

        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        assertEquals(0, rc, "4-llm.sh must exit 0; output:\n" + output);

        return new WizardRun(output, parseProps(runtime.resolve("application.properties")));
    }

    /**
     * Minimal fake docker: the remote branch issues only {@code docker compose}
     * calls (up / readiness exec / pull exec) and no {@code docker run}, so a
     * blanket {@code exit 0} makes compose-up succeed and the readiness {@code until}
     * loop resolve on its first iteration. No real container ever runs.
     */
    private String fakeDockerScript() {
        return "#!/usr/bin/env bash\n"
                + "exit 0\n";
    }

    private Map<String, String> parseProps(Path file) throws java.io.IOException {
        Map<String, String> props = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                props.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return props;
    }

    /** Walk up from the module CWD until the repo root (the dir with docker-compose.yml). */
    private Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("docker-compose.yml"))) {
                return p;
            }
        }
        throw new IllegalStateException("docker-compose.yml not found walking up from " + dir);
    }
}
