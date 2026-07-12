package app.zcat.infochat.llm.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the POST-setup LLM backend switcher {@code prod/switch-llm.sh}
 * (M1-418, reshaped by M1-603) — the operator tool that re-routes the
 * deployment's generative tasks between {@code remote}/{@code ollama}/
 * {@code llamacpp} after install. Since M1-603 (D56) it writes the SHARED
 * {@code infochat.llm.default.{base-url,api-key}} keys every task inherits
 * (one backend choice for the whole deployment) and sweeps stale per-task
 * base-url/api-key lines on a mutating run — a leftover per-task line would
 * win over the default and silently pin its task to the old endpoint.
 *
 * <p>The switcher carries security-relevant guarantees this test exists to
 * guard. An all-default (Enter-through) run never silently rewrites the config,
 * but the shape of that guarantee depends on the config: over a UNIFORM
 * shared-default file (or an old-format file already uniform on the classified
 * backend) it is a byte-identical no-op, so an operator can inspect without fear
 * of churn; over a MIXED/PINNED file — one carrying a per-task base-url override,
 * e.g. chat hand-pinned to local ollama for privacy while the rest are remote —
 * a bare Enter would sweep the pin, so the switcher REFUSES and names the pins,
 * requiring an explicit typed backend to confirm (M1-605 consent gate). A
 * confirmed switch still names each swept per-task line before the write. The
 * {@code infochat.embeddings.*} block is never touched (changing it corrupts
 * pgvector retrieval, rejected at Collector startup). It also writes
 * {@code secrets.env} and prints a per-task privacy disclosure whose exposure
 * claims must be correct.
 *
 * <p>The harness drives the real {@code prod/switch-llm.sh} with a fake
 * {@code docker} on {@code PATH} (no-ops the {@code compose up} calls) and
 * scripted stdin, then diffs/parses the generated config — mirroring the sibling
 * {@link LlamacppWiringTest}. Linux-gated: the script uses GNU {@code sed -i} and
 * bash, matching the deployment target.
 */
class SwitchLlmWiringTest {

    private static final String OLLAMA_URL = "http://ollama:11434/v1";
    private static final String REMOTE_URL = "https://api.example.com/v1";
    private static final String API_KEY_REF = "${INFOCHAT_LLM_API_KEY}";
    private static final String[] TASKS =
            {"security", "tagger", "entity", "classifier", "summarizer", "chat", "translator"};

    // An all-ollama OLD-format runtime config as the pre-M1-603 wizard left it:
    // every LLM task carries its own base-url line, NO api-key lines, and the
    // locked embeddings block. The security task carries an extra
    // max-concurrency line to prove the switcher leaves non-target keys untouched.
    private static final String BASELINE_OLD_FORMAT_OLLAMA =
            "quarkus.profile=vps\n"
            + "infochat.llm.security.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.security.model=llama3.2:3b\n"
            + "infochat.llm.security.max-concurrency=2\n"
            + "infochat.llm.tagger.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.tagger.model=llama3.2:3b\n"
            + "infochat.llm.entity.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.entity.model=llama3.2:3b\n"
            + "infochat.llm.classifier.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.classifier.model=llama3.2:3b\n"
            + "infochat.llm.summarizer.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.summarizer.model=llama3.2:3b\n"
            + "infochat.llm.chat.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.chat.model=llama3.2:3b\n"
            + "infochat.llm.translator.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.translator.model=llama3.2:3b\n"
            + "infochat.embeddings.base-url=" + OLLAMA_URL + "\n"
            + "infochat.embeddings.model=nomic-embed-text\n"
            + "infochat.embeddings.dimension=768\n";

    // An all-remote OLD-format config (per-task base-url + api-key fan-out) as
    // the pre-M1-603 wizard's remote branch left it. The remote shape requires
    // the key to already live in secrets.env.
    private static final String BASELINE_OLD_FORMAT_REMOTE =
            "quarkus.profile=remote-llm\n"
            + "infochat.llm.security.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.security.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.security.model=gpt-remote\n"
            + "infochat.llm.tagger.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.tagger.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.tagger.model=gpt-remote\n"
            + "infochat.llm.entity.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.entity.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.entity.model=gpt-remote\n"
            + "infochat.llm.classifier.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.classifier.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.classifier.model=gpt-remote\n"
            + "infochat.llm.summarizer.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.summarizer.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.summarizer.model=gpt-remote\n"
            + "infochat.llm.chat.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.chat.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.chat.model=gpt-remote\n"
            + "infochat.llm.translator.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.translator.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.translator.model=gpt-remote\n"
            + "infochat.embeddings.base-url=" + OLLAMA_URL + "\n"
            + "infochat.embeddings.model=nomic-embed-text\n"
            + "infochat.embeddings.dimension=768\n";

    // A NEW-format all-remote config as the M1-603 wizard leaves it: the shared
    // default keys + per-task models, no per-task base-url/api-key lines.
    private static final String BASELINE_NEW_FORMAT_REMOTE =
            "quarkus.profile=remote-llm\n"
            + "infochat.llm.default.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.default.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.security.model=gpt-remote\n"
            + "infochat.llm.tagger.model=gpt-remote\n"
            + "infochat.llm.entity.model=gpt-remote\n"
            + "infochat.llm.classifier.model=gpt-remote\n"
            + "infochat.llm.summarizer.model=gpt-remote\n"
            + "infochat.llm.chat.model=gpt-remote\n"
            + "infochat.llm.translator.model=gpt-remote\n"
            + "infochat.embeddings.base-url=" + OLLAMA_URL + "\n"
            + "infochat.embeddings.model=nomic-embed-text\n"
            + "infochat.embeddings.dimension=768\n";

    // A MIXED/PINNED config: the deployment is on the shared REMOTE default, but
    // chat is hand-pinned to local ollama for privacy (a per-task base-url
    // override that WINS over the default). The deployment classifier reads the
    // REMOTE default, so a bare-Enter run classifies 'remote' and — before the
    // M1-605 gate — would sweep the chat pin, silently routing private DMs remote.
    private static final String BASELINE_MIXED_CHAT_PINNED_LOCAL =
            "quarkus.profile=remote-llm\n"
            + "infochat.llm.default.base-url=" + REMOTE_URL + "\n"
            + "infochat.llm.default.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.chat.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.security.model=gpt-remote\n"
            + "infochat.llm.tagger.model=gpt-remote\n"
            + "infochat.llm.entity.model=gpt-remote\n"
            + "infochat.llm.classifier.model=gpt-remote\n"
            + "infochat.llm.summarizer.model=gpt-remote\n"
            + "infochat.llm.chat.model=llama3.2:3b\n"
            + "infochat.llm.translator.model=gpt-remote\n"
            + "infochat.embeddings.base-url=" + OLLAMA_URL + "\n"
            + "infochat.embeddings.model=nomic-embed-text\n"
            + "infochat.embeddings.dimension=768\n";

    private static final String EXISTING_SECRETS = "INFOCHAT_LLM_API_KEY=\"preexisting-secret\"\n";

    // Prompt order on a remote run: backend, provider dialect (M1-614), base-url,
    // then one model per task (7), then — only when secrets.env has no key — the
    // API key prompt. The dialect answer here is Enter (accepts the
    // openai-compatible default), so these drive the openai-compatible path.
    private static final String STDIN_SWITCH_TO_REMOTE_WITH_KEY =
            "remote\n" + "\n" + REMOTE_URL + "\n" + "gpt-test\n".repeat(7) + "sk-test-key\n";
    private static final String STDIN_SWITCH_TO_REMOTE_KEY_REUSED =
            "remote\n" + "\n" + REMOTE_URL + "\n" + "gpt-test\n".repeat(7);
    // All-Enter over a remote-backend file: backend + dialect + base-url + 7
    // models, key reused from secrets.env (no prompt).
    private static final String STDIN_ALL_ENTER_REMOTE = "\n".repeat(10);
    // Switch to the deepseek dialect (M1-614): backend, dialect=deepseek, base-url
    // Enter (accepts the https://api.deepseek.com default), key reused. deepseek
    // pins one model for every task, so there are NO per-task model prompts.
    private static final String STDIN_SWITCH_TO_REMOTE_DEEPSEEK =
            "remote\n" + "deepseek\n" + "\n";

    // --- byte-identical no-op (acceptance item 2) -------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void allEnterIsByteIdenticalNoOp(@TempDir Path tmp) throws Exception {
        // One deployment-level backend prompt: a single Enter keeps ollama.
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_OLLAMA, null, "\n");

        assertEquals(BASELINE_OLD_FORMAT_OLLAMA, r.rawConfig,
                "an all-default run must leave application.properties byte-identical:\n" + r.output);
        assertTrue(r.output.contains("Nothing to do"),
                "an all-default run must report no changes:\n" + r.output);
        assertTrue(listBackups(r.runtimeDir).isEmpty(),
                "a no-op run must not create backups (nothing was written)");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void allEnterIsByteIdenticalNoOpOverOldFormatRemoteFile(@TempDir Path tmp) throws Exception {
        // The migration guarantee: an OLD-format per-task fan-out file is
        // migrated to the shared-default shape only by a run that actually
        // changes routing — all-Enter must not rewrite a single byte. The
        // no-op detection therefore judges EFFECTIVE per-task routing
        // (per-task line, else default), not the raw default key.
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_REMOTE, EXISTING_SECRETS, STDIN_ALL_ENTER_REMOTE);

        assertEquals(BASELINE_OLD_FORMAT_REMOTE, r.rawConfig,
                "all-default over an old-format remote file must be byte-identical:\n" + r.output);
        assertTrue(listBackups(r.runtimeDir).isEmpty(),
                "a no-op run must not create backups (nothing was written)");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void allEnterIsByteIdenticalNoOpOverNewFormatRemoteFile(@TempDir Path tmp) throws Exception {
        RunResult r = runSwitch(tmp, BASELINE_NEW_FORMAT_REMOTE, EXISTING_SECRETS, STDIN_ALL_ENTER_REMOTE);

        assertEquals(BASELINE_NEW_FORMAT_REMOTE, r.rawConfig,
                "all-default over a new-format (shared-default) file must be byte-identical:\n" + r.output);
    }

    // --- M1-605 mixed/pinned consent gate (acceptance items 1, 3) ---------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void mixedConfigAllEnterRefusesAndDoesNotSilentlySweepThePin(@TempDir Path tmp) throws Exception {
        // The regression: a bare-Enter run over a MIXED config (chat pinned local,
        // rest on the remote default) classifies 'remote' and USED to sweep the
        // chat pin to remote silently. The gate must refuse instead — the file
        // stays byte-identical, no backup is written, and the pin is named.
        RunResult r = runSwitch(tmp, BASELINE_MIXED_CHAT_PINNED_LOCAL, EXISTING_SECRETS,
                STDIN_ALL_ENTER_REMOTE);

        assertEquals(BASELINE_MIXED_CHAT_PINNED_LOCAL, r.rawConfig,
                "an all-default run over a mixed/pinned config must NOT silently sweep the"
                        + " per-task pin — the file must be byte-identical:\n" + r.output);
        assertTrue(listBackups(r.runtimeDir).isEmpty(),
                "a refused run writes nothing, so it must create no backup:\n" + r.output);
        assertTrue(r.output.contains("TYPE the backend"),
                "the refusal must tell the operator to type the backend explicitly:\n" + r.output);
        assertTrue(r.output.contains("infochat.llm.chat.base-url=" + OLLAMA_URL),
                "the refusal must NAME the per-task pin it declined to sweep:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void mixedConfigExplicitBackendConfirmsSweepAndNamesThePinBeforeWriting(@TempDir Path tmp)
            throws Exception {
        // A TYPED backend answer is explicit consent: the switch proceeds, but the
        // swept per-task pin must still be named before the write, and the backup
        // must cover the run (acceptance item 3).
        RunResult r = runSwitch(tmp, BASELINE_MIXED_CHAT_PINNED_LOCAL, EXISTING_SECRETS,
                "remote\n" + "\n".repeat(9));

        assertNull(r.props.get("infochat.llm.chat.base-url"),
                "a confirmed switch must sweep the chat pin onto the shared default");
        assertEquals(REMOTE_URL, r.props.get("infochat.llm.default.base-url"),
                "the shared default must carry the remote endpoint after the confirmed switch");
        assertTrue(r.output.contains("Sweeping these hand-pinned per-task routes"),
                "a confirmed switch must announce the sweep before writing:\n" + r.output);
        assertTrue(r.output.contains("infochat.llm.chat.base-url=" + OLLAMA_URL),
                "a confirmed switch must NAME the swept pin, never discard it silently:\n" + r.output);
        assertFalse(listBackups(r.runtimeDir).isEmpty(),
                "a confirmed mutating switch must back up before writing:\n" + r.output);
    }

    // --- embeddings block is untouched (acceptance item 4) ----------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void embeddingsBlockUnchangedEvenWhenReRoutingTheDeployment(@TempDir Path tmp) throws Exception {
        // Re-route the deployment to remote (a real mutation) and assert every
        // infochat.embeddings.* line is byte-identical before and after.
        List<String> before = embeddingsLines(BASELINE_OLD_FORMAT_OLLAMA);
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_OLLAMA, null, STDIN_SWITCH_TO_REMOTE_WITH_KEY);

        assertEquals(before, embeddingsLines(r.rawConfig),
                "the embeddings block must be byte-identical after a re-route:\n" + r.rawConfig);
    }

    // --- routing to remote (acceptance item 3, first half) ----------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void switchToRemoteWritesSharedDefaultKeysAndSweepsPerTaskLines(@TempDir Path tmp) throws Exception {
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_OLLAMA, null, STDIN_SWITCH_TO_REMOTE_WITH_KEY);

        assertEquals(REMOTE_URL, r.props.get("infochat.llm.default.base-url"),
                "the remote endpoint must be written ONCE as the shared default (D56)");
        assertEquals(API_KEY_REF, r.props.get("infochat.llm.default.api-key"),
                "the shared default must reference the key by env var, never inline a secret");
        for (String task : TASKS) {
            assertNull(r.props.get("infochat.llm." + task + ".base-url"),
                    task + " must carry NO per-task base-url after migration — a stale"
                            + " per-task line would win over the shared default");
            assertNull(r.props.get("infochat.llm." + task + ".api-key"),
                    task + " must carry NO per-task api-key after migration");
            assertEquals("gpt-test", r.props.get("infochat.llm." + task + ".model"),
                    task + " must carry its prompted per-task model");
        }

        String secrets = Files.readString(r.runtimeDir.resolve("secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLM_API_KEY=\"sk-test-key\""),
                "the key must be recorded dotenv-quoted in secrets.env:\n" + secrets);
    }

    // --- routing to the deepseek dialect (M1-614) -------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void switchToRemoteDeepseekWritesProviderAndFixedModel(@TempDir Path tmp) throws Exception {
        // The deepseek dialect: provider=deepseek + deepseek-v4-flash on every
        // task, base-url defaulted to the DeepSeek endpoint (Enter), key reused.
        // No per-task model prompt (deepseek pins one model) and NO reasoning-effort
        // key — deepseek runs thinking-off by default (M1-610 keeps it off).
        List<String> beforeEmbeddings = embeddingsLines(BASELINE_OLD_FORMAT_OLLAMA);
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_OLLAMA, EXISTING_SECRETS,
                STDIN_SWITCH_TO_REMOTE_DEEPSEEK);

        assertEquals("deepseek", r.props.get("infochat.llm.default.provider"),
                "a deepseek switch must write provider=deepseek on the shared default");
        assertEquals("https://api.deepseek.com", r.props.get("infochat.llm.default.base-url"),
                "the deepseek default base-url must be the DeepSeek endpoint");
        assertEquals(API_KEY_REF, r.props.get("infochat.llm.default.api-key"),
                "the shared default must reference the reused API key");
        for (String task : TASKS) {
            assertEquals("deepseek-v4-flash", r.props.get("infochat.llm." + task + ".model"),
                    task + " must be pinned to deepseek-v4-flash");
            assertNull(r.props.get("infochat.llm." + task + ".reasoning-effort"),
                    task + " must carry no reasoning-effort key (deepseek runs thinking-off)");
        }
        assertEquals(beforeEmbeddings, embeddingsLines(r.rawConfig),
                "the embeddings block must be untouched by a deepseek switch:\n" + r.rawConfig);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void existingApiKeyIsReusedNotReprompted(@TempDir Path tmp) throws Exception {
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_OLLAMA, EXISTING_SECRETS,
                STDIN_SWITCH_TO_REMOTE_KEY_REUSED);

        assertTrue(r.output.contains("reused, not re-prompted"),
                "an existing INFOCHAT_LLM_API_KEY must be reused, not re-prompted:\n" + r.output);
        assertEquals(EXISTING_SECRETS, Files.readString(r.runtimeDir.resolve("secrets.env")),
                "secrets.env must be untouched when the key is reused");
        assertEquals(API_KEY_REF, r.props.get("infochat.llm.default.api-key"));
    }

    // --- routing to local clears the api-key (acceptance item 3, second half) ----

    @Test
    @EnabledOnOs(OS.LINUX)
    void switchToLocalClearsApiKeysAndWritesSharedDefault(@TempDir Path tmp) throws Exception {
        // Old-format remote deployment routed back to ollama: one backend
        // answer, no url/model prompts, no key prompt.
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_REMOTE, EXISTING_SECRETS, "ollama\n");

        assertEquals(OLLAMA_URL, r.props.get("infochat.llm.default.base-url"),
                "routing to ollama must point the shared default at the ollama service");
        assertNull(r.props.get("infochat.llm.default.api-key"),
                "routing to a local backend must CLEAR (remove) the default api-key line");
        for (String task : TASKS) {
            assertNull(r.props.get("infochat.llm." + task + ".base-url"),
                    task + " must carry NO per-task base-url after migration");
            assertNull(r.props.get("infochat.llm." + task + ".api-key"),
                    task + " must carry NO per-task api-key after routing local");
        }
        // Coming FROM remote the stale provider-native models are flagged for
        // the operator (they are not valid local models).
        assertTrue(r.output.contains("kept its model 'gpt-remote'"),
                "leaving remote must flag the stale per-task models:\n" + r.output);
    }

    // --- backup + rollback (acceptance item 5) ----------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void mutatingRunBacksUpAndPrintsRollback(@TempDir Path tmp) throws Exception {
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_OLLAMA, null, STDIN_SWITCH_TO_REMOTE_WITH_KEY);

        assertFalse(listBackups(r.runtimeDir).isEmpty(),
                "a mutating run must back up application.properties to a timestamped copy");
        assertTrue(r.output.contains("Rollback") && r.output.contains("cp "),
                "a mutating run must print the rollback command:\n" + r.output);
    }

    // --- dynamic privacy disclosure (acceptance item 6) -------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void privacyDisclosureListsEveryTaskOnRemoteAndNoneOnLocal(@TempDir Path tmp) throws Exception {
        // (a) remote routes ALL tasks: the loudest private-message warning for
        // chat plus the per-task exposure framing for the ingest tasks.
        RunResult remote = runSwitch(tmp.resolve("a"), BASELINE_OLD_FORMAT_OLLAMA, null,
                STDIN_SWITCH_TO_REMOTE_WITH_KEY);
        assertTrue(remote.output.contains("chat — YOUR PRIVATE MESSAGES"),
                "routing remote must print the loudest private-message warning:\n" + remote.output);
        assertTrue(remote.output.contains("entity — entity extraction over fetched PUBLIC posts"),
                "an ingest task must be framed as topic-interest exposure:\n" + remote.output);

        // (b) a mutating run that routes everything LOCAL prints no disclosure.
        RunResult local = runSwitch(tmp.resolve("b"), BASELINE_OLD_FORMAT_REMOTE, EXISTING_SECRETS,
                "ollama\n");
        assertFalse(local.output.contains("PRIVACY DISCLOSURE"),
                "no remote task means no privacy disclosure:\n" + local.output);
    }

    // --- recreate command (acceptance item 7) -----------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void recreateCommandUsesUpNotRestart(@TempDir Path tmp) throws Exception {
        RunResult r = runSwitch(tmp, BASELINE_OLD_FORMAT_OLLAMA, null, STDIN_SWITCH_TO_REMOTE_WITH_KEY);

        assertTrue(r.output.contains("up -d infochat-collector infochat-provider"),
                "the recreate command must use `up -d infochat-collector infochat-provider`:\n" + r.output);
        // The script's prose deliberately names `restart` to warn against it, so
        // assert the absence of the wrong COMMAND form, not the bare word.
        assertFalse(r.output.contains("restart collector"),
                "the recreate command must NOT use `restart` (a new key needs a recreate):\n" + r.output);
        assertFalse(r.output.contains("compose restart"),
                "no docker compose restart command may be printed:\n" + r.output);
    }

    // --- helpers ----------------------------------------------------------------

    /** The captured output, raw final config, parsed props, and runtime dir of one run. */
    private record RunResult(String output, String rawConfig, Map<String, String> props, Path runtimeDir) {}

    /** Drive prod/switch-llm.sh with a fake docker on PATH; assert exit 0; return the result. */
    private RunResult runSwitch(Path tmp, String seedConfig, String seedSecrets, String stdin) throws Exception {
        Path repoRoot = repoRoot();
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Files.writeString(runtime.resolve("application.properties"), seedConfig);
        if (seedSecrets != null) {
            Files.writeString(runtime.resolve("secrets.env"), seedSecrets);
        }

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path fakeDocker = bin.resolve("docker");
        // No real container ever runs: no-op every docker invocation.
        Files.writeString(fakeDocker, "#!/usr/bin/env bash\nexit 0\n");
        fakeDocker.toFile().setExecutable(true);

        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot.resolve("prod/switch-llm.sh").toString());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", ""));

        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        assertEquals(0, rc, "switch-llm.sh must exit 0; output:\n" + output);

        Path configFile = runtime.resolve("application.properties");
        return new RunResult(output, Files.readString(configFile), parseProps(configFile), runtime);
    }

    /** infochat.embeddings.* lines, in file order, for a before/after diff. */
    private List<String> embeddingsLines(String config) {
        return config.lines().filter(l -> l.startsWith("infochat.embeddings.")).toList();
    }

    /** Timestamped application.properties backups the switcher wrote (empty if none). */
    private List<Path> listBackups(Path runtimeDir) throws IOException {
        try (Stream<Path> files = Files.list(runtimeDir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("application.properties.bak.")).toList();
        }
    }

    private Map<String, String> parseProps(Path file) throws IOException {
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
