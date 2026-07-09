package app.zcat.infochat.llm.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * seven generative tasks carry the remote base-url + the {@code ${INFOCHAT_LLM_API_KEY}}
 * reference, while {@code infochat.embeddings.*} resolves to the local Ollama nomic
 * endpoint and is NEVER the remote base-url. Mirrors the generated-config layer of
 * {@link LlamacppWiringTest}; no static-compose assertions are needed for this branch.
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

    // Four Enter answers accepting the recommended values at M1-550's step-4
    // prompt_timing reads (chat/summarizer × timeout-ms/max-tokens) — every
    // wizard drive must supply them or the script dies at EOF under set -e.
    private static final String ACCEPT_TIMING_DEFAULTS = "\n\n\n\n";

    @Test
    @EnabledOnOs(OS.LINUX)
    void remoteBackendWiresGenerativeRemoteButEmbeddingsLocal(@TempDir Path tmp) throws Exception {
        // stdin: backend=remote, then the base-url, then the API key (no key yet in
        // secrets.env, so the branch reads it from stdin), timing defaults (4× Enter).
        Map<String, String> props = runWizard(tmp,
                "remote\n" + REMOTE_BASE_URL + "\n" + REMOTE_API_KEY + "\n" + ACCEPT_TIMING_DEFAULTS);

        // Generative tasks → remote endpoint + API-key reference.
        assertEquals(REMOTE_BASE_URL, props.get("infochat.llm.chat.base-url"),
                "generative tasks must point at the remote endpoint");
        assertEquals("${INFOCHAT_LLM_API_KEY}", props.get("infochat.llm.chat.api-key"),
                "generative tasks must reference the API key from secrets.env");

        // The classifier task (M1-599) must be routed remote like the others — the
        // whole point of teaching switch-llm/4-llm the classifier: an operator remote
        // switch must not silently leave it on localhost (red before it joined LLM_TASKS).
        assertEquals(REMOTE_BASE_URL, props.get("infochat.llm.classifier.base-url"),
                "the classifier task must point at the remote endpoint (M1-599)");
        assertEquals("${INFOCHAT_LLM_API_KEY}", props.get("infochat.llm.classifier.api-key"),
                "the classifier task must reference the API key from secrets.env (M1-599)");

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

    // --- helpers (trimmed mirror of LlamacppWiringTest) -------------------------

    /** Run prod/scripts/4-llm.sh with a fake docker on PATH; return generated props. */
    private Map<String, String> runWizard(Path tmp, String stdin) throws Exception {
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

        return parseProps(runtime.resolve("application.properties"));
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
