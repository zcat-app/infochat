package app.zcat.infochat.llm.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the llama.cpp backend wiring (M1-417, D49) the suite previously never
 * exercised — the gap that let the non-functional {@code llamacpp} compose
 * service ship undetected.
 *
 * <p>Two layers, no real containers or network:
 * <ul>
 *   <li><b>Static compose</b> — asserts {@code docker-compose.yml} gives the
 *       generative {@code llamacpp} service its model + host args and a
 *       healthcheck, declares a second {@code llamacpp-embeddings} service in
 *       embedding mode with its own healthcheck, and publishes no host port for
 *       either (binds stay on the compose network — the security ask).</li>
 *   <li><b>Generated config</b> — drives the real {@code prod/scripts/4-llm.sh}
 *       wizard with a fake {@code docker} on {@code PATH} (no-ops the compose /
 *       run calls, answers the checksum probe) and scripted stdin, then asserts
 *       the generated {@code application.properties}: in BOTH embeddings shapes
 *       the generative GGUF drives every LLM task, embeddings resolve to the
 *       chosen embeddings backend (a second llama.cpp instance OR Ollama nomic)
 *       and NEVER the generative GGUF, and {@code dimension=768} is preserved.</li>
 * </ul>
 *
 * <p>The live-drive layer is Linux-gated: {@code 4-llm.sh} uses GNU {@code sed -i}
 * and bash, matching the Linux deployment target (the Signal subprocess tests set
 * the {@link ProcessBuilder}-from-JUnit precedent). The real-server smoke (curl
 * {@code llamacpp:8080/v1/models}, generate an embedding) is a manual VPS check,
 * too heavy for {@code mvn verify}.
 */
class LlamacppWiringTest {

    // Curated GGUFs the wizard pins (kept in lock-step with 4-llm.sh); the fake
    // docker shim answers sha256sum with these so the enforced-checksum path passes.
    private static final String GEN_GGUF = "gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf";
    private static final String GEN_SHA =
            "b3052f962d6449b4eb2075733c068bdec1c51eadb7b237e6c3157bfbb7b1dae0";
    private static final String EMB_GGUF = "nomic-embed-text-v1.5.f16.gguf";
    private static final String EMB_SHA =
            "f7af6f66802f4df86eda10fe9bbcfc75c39562bed48ef6ace719a251cf1c2fdb";

    private static final String LLAMACPP_URL = "http://llamacpp:8080/v1";
    private static final String LLAMACPP_EMBED_URL = "http://llamacpp-embeddings:8080/v1";
    private static final String OLLAMA_URL = "http://ollama:11434/v1";
    private static final String OLLAMA_NOMIC = "nomic-embed-text";

    // --- Static compose wiring --------------------------------------------------

    @Test
    void generativeLlamacppServiceHasModelAndHostArgs() throws IOException {
        String block = composeServiceBlock("llamacpp");
        assertTrue(block.contains("LLAMA_ARG_MODEL: /models/${INFOCHAT_LLAMACPP_GGUF"),
                "generative llamacpp service must load the operator GGUF via LLAMA_ARG_MODEL:\n" + block);
        assertTrue(block.contains("LLAMA_ARG_HOST: 0.0.0.0"),
                "generative llamacpp service must bind 0.0.0.0 so it is reachable as llamacpp:8080:\n" + block);
    }

    @Test
    void generativeLlamacppServiceHasHealthcheck() throws IOException {
        assertTrue(composeServiceBlock("llamacpp").contains("healthcheck:"),
                "generative llamacpp service must declare a healthcheck");
    }

    @Test
    void embeddingsLlamacppServiceIsEmbeddingModeWithOwnHealthcheck() throws IOException {
        String block = composeServiceBlock("llamacpp-embeddings");
        assertTrue(block.contains("LLAMA_ARG_EMBEDDINGS"),
                "second llama.cpp service must run in embedding mode (LLAMA_ARG_EMBEDDINGS):\n" + block);
        assertTrue(block.contains("LLAMA_ARG_MODEL: /models/${INFOCHAT_LLAMACPP_EMBED_GGUF"),
                "embeddings service must load the embeddings GGUF, not the generative one:\n" + block);
        assertTrue(block.contains("healthcheck:"),
                "embeddings service must declare its own healthcheck:\n" + block);
    }

    @Test
    void llamacppServicesPublishNoHostPort() throws IOException {
        // Binds stay on the compose network (security_relevant + redteam ask): no
        // `ports:` host-publish on either llama.cpp service, mirroring the
        // postgres/ollama loopback posture.
        assertFalse(composeServiceBlock("llamacpp").contains("ports:"),
                "generative llamacpp service must not publish a host port");
        assertFalse(composeServiceBlock("llamacpp-embeddings").contains("ports:"),
                "embeddings llamacpp service must not publish a host port");
    }

    // --- Generated config (drive the real wizard) -------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void llamacppEmbeddingsShapePointsAtEmbeddingsServiceNeverGenerativeGguf(@TempDir Path tmp)
            throws Exception {
        // stdin: backend=llamacpp, generative=pinned (Enter), embeddings=llamacpp
        // (Enter), embeddings GGUF=pinned (Enter).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n");

        assertEquals(GEN_GGUF, props.get("infochat.llm.chat.model"),
                "every LLM task must use the generative GGUF");
        assertEquals(LLAMACPP_URL, props.get("infochat.llm.chat.base-url"));
        assertEquals(LLAMACPP_EMBED_URL, props.get("infochat.embeddings.base-url"),
                "embeddings must point at the second llama.cpp instance");
        assertEquals(EMB_GGUF, props.get("infochat.embeddings.model"));
        assertEquals("768", props.get("infochat.embeddings.dimension"),
                "embeddings dimension must stay 768");
        assertNotEquals(GEN_GGUF, props.get("infochat.embeddings.model"),
                "embeddings must NEVER resolve to the generative GGUF (the M1-417 bug)");

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF=\"" + GEN_GGUF + "\""),
                "the wizard must mint the generative GGUF filename into secrets.env:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_EMBED_GGUF=\"" + EMB_GGUF + "\""),
                "the wizard must mint the embeddings GGUF filename into secrets.env:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaEmbeddingsShapePointsAtOllamaNomicEndpoint(@TempDir Path tmp) throws Exception {
        // stdin: backend=llamacpp, generative=pinned (Enter), embeddings=ollama.
        Map<String, String> props = runWizard(tmp, "llamacpp\n\nollama\n");

        assertEquals(GEN_GGUF, props.get("infochat.llm.chat.model"));
        assertEquals(LLAMACPP_URL, props.get("infochat.llm.chat.base-url"));
        assertEquals(OLLAMA_URL, props.get("infochat.embeddings.base-url"),
                "embeddings must point at the co-running Ollama endpoint");
        assertEquals(OLLAMA_NOMIC, props.get("infochat.embeddings.model"),
                "Ollama embeddings shape must use the nomic-class model");
        assertEquals("768", props.get("infochat.embeddings.dimension"));
        assertNotEquals(GEN_GGUF, props.get("infochat.embeddings.model"),
                "embeddings must NEVER resolve to the generative GGUF");
    }

    // --- helpers ----------------------------------------------------------------

    /** Run prod/scripts/4-llm.sh with a fake docker on PATH; return generated props. */
    private Map<String, String> runWizard(Path tmp, String stdin) throws Exception {
        Path repoRoot = repoRoot();
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        // Seed the profile 1-profile.sh would have written (vps has a nomic embedder).
        Files.writeString(runtime.resolve("application.properties"), "quarkus.profile=vps\n");

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
     * Minimal fake docker: no-op the compose up / exec and the volume download/run,
     * answer the sha256sum probe with the pinned digests so the enforced-checksum
     * path passes. No real container ever runs.
     */
    private String fakeDockerScript() {
        return "#!/usr/bin/env bash\n"
                + "sub=\"$1\"\n"
                + "if [ \"$sub\" = \"compose\" ]; then exit 0; fi\n"
                + "if [ \"$sub\" = \"run\" ]; then\n"
                + "  ep=\"\"; prev=\"\"; last=\"\"\n"
                + "  for a in \"$@\"; do\n"
                + "    [ \"$prev\" = \"--entrypoint\" ] && ep=\"$a\"\n"
                + "    prev=\"$a\"; last=\"$a\"\n"
                + "  done\n"
                + "  if [ \"$ep\" = \"sha256sum\" ]; then\n"
                + "    case \"$last\" in\n"
                + "      *" + GEN_GGUF + ") echo \"" + GEN_SHA + "  $last\" ;;\n"
                + "      *" + EMB_GGUF + ") echo \"" + EMB_SHA + "  $last\" ;;\n"
                + "      *) echo \"0000000000000000000000000000000000000000000000000000000000000000  $last\" ;;\n"
                + "    esac\n"
                + "  fi\n"
                + "  exit 0\n"  // ls reports present (skip download); download/rm no-op
                + "fi\n"
                + "exit 0\n";
    }

    /** The compose block for a 2-space-indented service, header to the next service key. */
    private String composeServiceBlock(String service) throws IOException {
        String compose = Files.readString(repoRoot().resolve("docker-compose.yml"));
        Pattern header = Pattern.compile("(?m)^  " + Pattern.quote(service) + ":\\s*$");
        Matcher m = header.matcher(compose);
        assertTrue(m.find(), "service '" + service + "' not found in docker-compose.yml");
        int start = m.start();
        // The block ends at the next 2-space-indented key (sibling service) or the
        // top-level `volumes:` trailer.
        Matcher next = Pattern.compile("(?m)^(  [A-Za-z0-9_.-]+:\\s*$|[A-Za-z].*$)").matcher(compose);
        int end = compose.length();
        int from = m.end();
        while (next.find(from)) {
            end = next.start();
            from = next.end();
            break;
        }
        return compose.substring(start, end);
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
