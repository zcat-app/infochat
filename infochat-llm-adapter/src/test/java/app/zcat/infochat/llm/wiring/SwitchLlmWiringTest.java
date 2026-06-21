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
 * Pins the POST-setup per-task LLM backend switcher {@code prod/switch-llm.sh}
 * (M1-418) — the operator tool that re-routes any generative task between
 * {@code remote}/{@code ollama}/{@code llamacpp} after install, regenerating only
 * the per-task {@code infochat.llm.<task>.*} config.
 *
 * <p>The switcher carries two security-relevant guarantees this test exists to
 * guard: an all-default run is a byte-identical no-op (so an operator can inspect
 * without fear of churn), and the {@code infochat.embeddings.*} block is never
 * touched (changing it corrupts pgvector retrieval, rejected at Collector
 * startup). It also writes {@code secrets.env} and prints a per-task privacy
 * disclosure whose exposure claims must be correct.
 *
 * <p>The harness drives the real {@code prod/switch-llm.sh} with a fake
 * {@code docker} on {@code PATH} (no-ops the {@code compose up} calls) and
 * scripted stdin, then diffs/parses the generated config — mirroring the sibling
 * {@link LlamacppWiringTest}. Linux-gated: the script uses GNU {@code sed -i} and
 * bash, matching the deployment target.
 */
class SwitchLlmWiringTest {

    private static final String OLLAMA_URL = "http://ollama:11434/v1";
    private static final String API_KEY_REF = "${INFOCHAT_LLM_API_KEY}";

    // An all-ollama runtime config as 4-llm.sh's ollama branch would leave it:
    // every LLM task points at the ollama endpoint with a model, NO api-key
    // lines, and the locked embeddings block. The security task carries an extra
    // max-concurrency line to prove the switcher leaves non-target keys untouched.
    private static final String BASELINE_OLLAMA =
            "quarkus.profile=vps\n"
            + "infochat.llm.security.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.security.model=llama3.2:3b\n"
            + "infochat.llm.security.max-concurrency=2\n"
            + "infochat.llm.tagger.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.tagger.model=llama3.2:3b\n"
            + "infochat.llm.entity.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.entity.model=llama3.2:3b\n"
            + "infochat.llm.summarizer.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.summarizer.model=llama3.2:3b\n"
            + "infochat.llm.chat.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.chat.model=llama3.2:3b\n"
            + "infochat.llm.translator.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.translator.model=llama3.2:3b\n"
            + "infochat.embeddings.base-url=" + OLLAMA_URL + "\n"
            + "infochat.embeddings.model=nomic-embed-text\n"
            + "infochat.embeddings.dimension=768\n";

    // A mixed config: chat already routed to a remote provider (base-url + api-key
    // reference + remote model), every other task on ollama. The remote shape
    // requires the key to already live in secrets.env.
    private static final String BASELINE_CHAT_REMOTE =
            "quarkus.profile=vps\n"
            + "infochat.llm.security.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.security.model=llama3.2:3b\n"
            + "infochat.llm.tagger.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.tagger.model=llama3.2:3b\n"
            + "infochat.llm.entity.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.entity.model=llama3.2:3b\n"
            + "infochat.llm.summarizer.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.summarizer.model=llama3.2:3b\n"
            + "infochat.llm.chat.base-url=https://api.example.com/v1\n"
            + "infochat.llm.chat.api-key=" + API_KEY_REF + "\n"
            + "infochat.llm.chat.model=gpt-remote\n"
            + "infochat.llm.translator.base-url=" + OLLAMA_URL + "\n"
            + "infochat.llm.translator.model=llama3.2:3b\n"
            + "infochat.embeddings.base-url=" + OLLAMA_URL + "\n"
            + "infochat.embeddings.model=nomic-embed-text\n"
            + "infochat.embeddings.dimension=768\n";

    private static final String EXISTING_SECRETS = "INFOCHAT_LLM_API_KEY=\"preexisting-secret\"\n";

    // --- byte-identical no-op (acceptance item 2) -------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void allEnterIsByteIdenticalNoOp(@TempDir Path tmp) throws Exception {
        // Six tasks, all ollama: one Enter each keeps the current backend.
        RunResult r = runSwitch(tmp, BASELINE_OLLAMA, null, "\n\n\n\n\n\n");

        assertEquals(BASELINE_OLLAMA, r.rawConfig,
                "an all-default run must leave application.properties byte-identical:\n" + r.output);
        assertTrue(r.output.contains("Nothing to do"),
                "an all-default run must report no changes:\n" + r.output);
        assertTrue(listBackups(r.runtimeDir).isEmpty(),
                "a no-op run must not create backups (nothing was written)");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void allEnterIsByteIdenticalNoOpEvenWithARemoteTask(@TempDir Path tmp) throws Exception {
        // chat is remote, the key already in secrets.env. All-Enter must accept
        // remote (backend), the current base-url, and the current model — three
        // Enters for chat — and still produce a byte-identical file.
        RunResult r = runSwitch(tmp, BASELINE_CHAT_REMOTE, EXISTING_SECRETS, "\n\n\n\n\n\n\n\n");

        assertEquals(BASELINE_CHAT_REMOTE, r.rawConfig,
                "all-default over a mixed config must be byte-identical:\n" + r.output);
        // The classifier produced the right per-task defaults (else lines would
        // have been rewritten): chat stayed remote, the rest stayed ollama.
        assertEquals("https://api.example.com/v1", r.props.get("infochat.llm.chat.base-url"));
        assertEquals(OLLAMA_URL, r.props.get("infochat.llm.security.base-url"));
    }

    // --- embeddings block is untouched (acceptance item 4) ----------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void embeddingsBlockUnchangedEvenWhenReRoutingAGenerativeTask(@TempDir Path tmp) throws Exception {
        // Re-route chat to remote (a real mutation) and assert every
        // infochat.embeddings.* line is byte-identical before and after.
        List<String> before = embeddingsLines(BASELINE_OLLAMA);
        RunResult r = runSwitch(tmp, BASELINE_OLLAMA, null,
                "\n\n\n\nremote\nhttps://api.example.com/v1\ngpt-test\n\nsk-test-key\n");

        assertEquals(before, embeddingsLines(r.rawConfig),
                "the embeddings block must be byte-identical after a generative re-route:\n" + r.rawConfig);
    }

    // --- routing to remote (acceptance item 3, first half) ----------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void routeTaskToRemoteWritesBaseUrlApiKeyRefAndModel(@TempDir Path tmp) throws Exception {
        // chat is the 5th task: four Enters (ollama), then remote + base-url +
        // model, then an Enter for translator, then the API key prompt.
        RunResult r = runSwitch(tmp, BASELINE_OLLAMA, null,
                "\n\n\n\nremote\nhttps://api.example.com/v1\ngpt-test\n\nsk-test-key\n");

        assertEquals("https://api.example.com/v1", r.props.get("infochat.llm.chat.base-url"));
        assertEquals(API_KEY_REF, r.props.get("infochat.llm.chat.api-key"),
                "a remote task must reference the key by env var, never inline a secret");
        assertEquals("gpt-test", r.props.get("infochat.llm.chat.model"));

        String secrets = Files.readString(r.runtimeDir.resolve("secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLM_API_KEY=\"sk-test-key\""),
                "the key must be recorded dotenv-quoted in secrets.env:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void existingApiKeyIsReusedNotReprompted(@TempDir Path tmp) throws Exception {
        // tagger is the 2nd task: Enter (security), then remote + base-url + model,
        // then four Enters for entity/summarizer/chat/translator. With the key
        // already in secrets.env there is NO key prompt — so eight lines total.
        RunResult r = runSwitch(tmp, BASELINE_OLLAMA, EXISTING_SECRETS,
                "\nremote\nhttps://r.example.com/v1\nmodelX\n\n\n\n\n");

        assertTrue(r.output.contains("reused, not re-prompted"),
                "an existing INFOCHAT_LLM_API_KEY must be reused, not re-prompted:\n" + r.output);
        assertEquals(EXISTING_SECRETS, Files.readString(r.runtimeDir.resolve("secrets.env")),
                "secrets.env must be untouched when the key is reused");
        assertEquals(API_KEY_REF, r.props.get("infochat.llm.tagger.api-key"));
    }

    // --- routing to local clears the api-key (acceptance item 3, second half) ----

    @Test
    @EnabledOnOs(OS.LINUX)
    void routeTaskToLocalClearsApiKey(@TempDir Path tmp) throws Exception {
        // Start with chat remote; route it back to ollama. Four Enters, then
        // ollama for chat, then an Enter for translator. No remote task remains,
        // so no key prompt.
        RunResult r = runSwitch(tmp, BASELINE_CHAT_REMOTE, EXISTING_SECRETS, "\n\n\n\nollama\n\n");

        assertEquals(OLLAMA_URL, r.props.get("infochat.llm.chat.base-url"),
                "routing to ollama must point the base-url at the ollama service");
        assertNull(r.props.get("infochat.llm.chat.api-key"),
                "routing to a local backend must CLEAR (remove) the api-key line");
    }

    // --- backup + rollback (acceptance item 5) ----------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void mutatingRunBacksUpAndPrintsRollback(@TempDir Path tmp) throws Exception {
        RunResult r = runSwitch(tmp, BASELINE_OLLAMA, null,
                "\n\n\n\nremote\nhttps://api.example.com/v1\ngpt-test\n\nsk-test-key\n");

        assertFalse(listBackups(r.runtimeDir).isEmpty(),
                "a mutating run must back up application.properties to a timestamped copy");
        assertTrue(r.output.contains("Rollback") && r.output.contains("cp "),
                "a mutating run must print the rollback command:\n" + r.output);
    }

    // --- dynamic privacy disclosure (acceptance item 6) -------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void privacyDisclosureNamesOnlyRemoteTasksWithCorrectExposure(@TempDir Path tmp) throws Exception {
        // (a) chat remote -> the loudest private-message warning.
        RunResult chat = runSwitch(tmp.resolve("a"), BASELINE_OLLAMA, null,
                "\n\n\n\nremote\nhttps://api.example.com/v1\ngpt-test\n\nsk-test-key\n");
        assertTrue(chat.output.contains("chat — YOUR PRIVATE MESSAGES"),
                "routing chat remote must print the loudest private-message warning:\n" + chat.output);

        // (b) only an ingest task (entity, the 3rd) remote -> topic-interest
        // framing, and NOT the chat private-message line.
        RunResult entity = runSwitch(tmp.resolve("b"), BASELINE_OLLAMA, null,
                "\n\nremote\nhttps://api.example.com/v1\ngpt-test\n\n\n\nsk-test-key\n");
        assertTrue(entity.output.contains("entity — entity extraction over fetched PUBLIC posts"),
                "an ingest task must be framed as topic-interest exposure:\n" + entity.output);
        assertFalse(entity.output.contains("YOUR PRIVATE MESSAGES"),
                "an ingest-only re-route must NOT claim private-message exposure:\n" + entity.output);

        // (c) a mutating run that leaves NO task remote (chat remote -> ollama)
        // prints no disclosure at all.
        RunResult none = runSwitch(tmp.resolve("c"), BASELINE_CHAT_REMOTE, EXISTING_SECRETS, "\n\n\n\nollama\n\n");
        assertFalse(none.output.contains("PRIVACY DISCLOSURE"),
                "no remote task means no privacy disclosure:\n" + none.output);
    }

    // --- recreate command (acceptance item 7) -----------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void recreateCommandUsesUpNotRestart(@TempDir Path tmp) throws Exception {
        RunResult r = runSwitch(tmp, BASELINE_OLLAMA, null,
                "\n\n\n\nremote\nhttps://api.example.com/v1\ngpt-test\n\nsk-test-key\n");

        assertTrue(r.output.contains("up -d collector provider"),
                "the recreate command must use `up -d collector provider`:\n" + r.output);
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
