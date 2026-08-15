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
import java.util.List;
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
 *       either (binds stay on the compose network — the security ask). Also
 *       pins (M1-744): the operator-settable resource caps with their M1-512
 *       literal defaults, the base file's freedom from {@code devices:}
 *       passthrough, the generative service's {@code LLAMA_ARG_REASONING: "off"}
 *       (M1-560), and the opt-in {@code docker-compose.gpu.yml} overlay's
 *       Vulkan image + device/group_add keys for both services.</li>
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
    // Pinned GGUF download URLs (kept in lock-step with 4-llm.sh) — M1-571 persists these
    // into secrets.env so restore.sh can re-fetch the model on a fresh host.
    private static final String GEN_URL =
            "https://huggingface.co/unsloth/gemma-4-E4B-it-qat-GGUF/resolve/main/gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf";
    private static final String EMB_URL =
            "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.f16.gguf";
    // A custom generative GGUF URL for the P10 semantics drive: the preflight must
    // treat an HTTP-level HEAD refusal as reachability, not a network failure.
    private static final String CUSTOM_GEN_URL = "https://models.example.test/my-custom-gen.gguf";

    // Pinned in lock-step with docker-compose.yml (mirrors GEN_SHA/EMB_SHA): the
    // image both llama.cpp services must run. server-b5350 predates the gemma4
    // architecture and cannot load GEN_GGUF (M1-442); a downgrade fails the build.
    private static final String LLAMACPP_IMAGE = "ghcr.io/ggml-org/llama.cpp:server-b9776";
    // The Vulkan build of the SAME pinned release, declared by the opt-in
    // docker-compose.gpu.yml overlay (M1-744) — a second image, so the
    // anti-downgrade pin must cover it too rather than move off the base file.
    private static final String LLAMACPP_VULKAN_IMAGE = "ghcr.io/ggml-org/llama.cpp:server-vulkan-b9776";

    // The model volume the wizard's fetch_gguf writes to and the compose services
    // mount; must resolve to the same real Docker volume regardless of compose
    // project name (M1-442).
    private static final String MODEL_VOLUME = "infochat-llamacpp-models";

    private static final String LLAMACPP_URL = "http://llamacpp:8080/v1";
    private static final String LLAMACPP_EMBED_URL = "http://llamacpp-embeddings:8080/v1";
    private static final String OLLAMA_URL = "http://ollama:11434/v1";
    private static final String OLLAMA_NOMIC = "nomic-embed-text";

    // Four Enter answers accepting the recommended values at M1-550's step-4
    // prompt_timing reads (chat/summarizer × timeout-ms/max-tokens) — every
    // wizard drive must supply them or the script dies at EOF under set -e.
    private static final String ACCEPT_TIMING_DEFAULTS = "\n\n\n\n";

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

    @Test
    void bothLlamacppServicesPinTheGemma4CapableImage() throws IOException {
        // An accidental downgrade to server-b5350 (or any pre-gemma4 build) cannot
        // load the pinned generative GGUF and must fail the build (M1-442).
        assertTrue(composeServiceBlock("llamacpp").contains("image: " + LLAMACPP_IMAGE),
                "generative llamacpp service must pin " + LLAMACPP_IMAGE);
        assertTrue(composeServiceBlock("llamacpp-embeddings").contains("image: " + LLAMACPP_IMAGE),
                "embeddings llamacpp service must pin " + LLAMACPP_IMAGE);
    }

    @Test
    void resourceCapsAreOperatorSettableWithDefaultsEqualToTheM1_512Literals() throws IOException {
        // M1-744: the caps are interpolated so an operator can set them via the
        // same --env-file secrets.env the wizard drives, and every default IS the
        // M1-512 literal — a deployment that sets nothing renders byte-identical
        // caps (verified against `docker compose config` at authoring). An edit
        // that drops the interpolation or re-tunes a default fails here.
        String gen = composeServiceBlock("llamacpp");
        assertTrue(gen.contains("cpus: \"${INFOCHAT_LLAMACPP_CPUS:-3.0}\""),
                "generative llamacpp cpus must be operator-settable, default 3.0:\n" + gen);
        assertTrue(gen.contains("memory: \"${INFOCHAT_LLAMACPP_MEMORY:-7g}\""),
                "generative llamacpp memory limit must be operator-settable, default 7g:\n" + gen);
        assertTrue(gen.contains("memory: \"${INFOCHAT_LLAMACPP_MEMORY_RESERVATION:-3g}\""),
                "generative llamacpp memory reservation must be operator-settable, default 3g:\n" + gen);

        String emb = composeServiceBlock("llamacpp-embeddings");
        assertTrue(emb.contains("cpus: \"${INFOCHAT_LLAMACPP_EMBED_CPUS:-1.5}\""),
                "embeddings llamacpp cpus must be operator-settable, default 1.5:\n" + emb);
        assertTrue(emb.contains("memory: \"${INFOCHAT_LLAMACPP_EMBED_MEMORY:-2g}\""),
                "embeddings llamacpp memory limit must be operator-settable, default 2g:\n" + emb);
        assertTrue(emb.contains("memory: \"${INFOCHAT_LLAMACPP_EMBED_MEMORY_RESERVATION:-512m}\""),
                "embeddings llamacpp memory reservation must be operator-settable, default 512m:\n" + emb);
    }

    @Test
    void gpuOverlayPinsVulkanImageAndDeviceKeysForBothServices() throws IOException {
        // The opt-in docker-compose.gpu.yml overlay (M1-744) is the ONLY place
        // GPU wiring may live. The anti-downgrade control is duplicated here, not
        // moved off the base file: an accidental downgrade to a pre-gemma4 Vulkan
        // build cannot load the pinned generative GGUF and must fail the build
        // (M1-442 precedent).
        for (String service : new String[] {"llamacpp", "llamacpp-embeddings"}) {
            String block = composeServiceBlock("docker-compose.gpu.yml", service);
            assertTrue(block.contains("image: " + LLAMACPP_VULKAN_IMAGE),
                    "GPU overlay " + service + " must pin " + LLAMACPP_VULKAN_IMAGE
                            + " — the Vulkan build of the same pinned release:\n" + block);
            assertTrue(block.contains("/dev/dri:/dev/dri"),
                    "GPU overlay " + service + " must pass the render nodes through:\n" + block);
            assertTrue(block.contains("group_add:"),
                    "GPU overlay " + service + " must add the host render/video GIDs:\n" + block);
        }
    }

    @Test
    void baseComposeDeclaresNoDevicePassthrough() throws IOException {
        // Docker fails container creation when a `devices:` path is absent, so a
        // devices: key in the BASE file would break every host without an iGPU
        // (the VPS scenario in docs/spec/deployment.md). GPU wiring stays in the
        // opt-in overlay only (M1-744).
        assertFalse(composeServiceBlock("llamacpp").contains("devices:"),
                "generative llamacpp service must not declare devices: in the base file");
        assertFalse(composeServiceBlock("llamacpp-embeddings").contains("devices:"),
                "embeddings llamacpp service must not declare devices: in the base file");
    }

    @Test
    void generativeLlamacppServiceDisablesReasoning() throws IOException {
        // M1-560 set this; nothing pinned it until M1-744. Without it llama.cpp's
        // --reasoning auto detects a thinking-capable template and turns reasoning
        // ON, so the per-task max-tokens caps (M1-548, sized for VISIBLE output)
        // are consumed by thinking-channel tokens — empty or format-broken replies
        // (F-live-8, host-proven 2026-07-04; reproduced 2026-08-01 against a
        // DeepSeek-V4-Flash GGUF with content:"" and the whole answer in
        // reasoning_content).
        assertTrue(composeServiceBlock("llamacpp").contains("LLAMA_ARG_REASONING: \"off\""),
                "generative llamacpp service must declare LLAMA_ARG_REASONING: \"off\""
                        + " or thinking-capable models return empty/format-broken replies"
                        + " (F-live-8, M1-560)");
    }

    // --- Generated config (drive the real wizard) -------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void llamacppEmbeddingsShapePointsAtEmbeddingsServiceNeverGenerativeGguf(@TempDir Path tmp)
            throws Exception {
        // stdin: backend=llamacpp, generative=pinned (Enter), embeddings=llamacpp
        // (Enter), embeddings GGUF=pinned (Enter), timing defaults (4× Enter).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS);

        assertEquals(GEN_GGUF, props.get("infochat.llm.chat.model"),
                "every LLM task must use the generative GGUF");

        // Timing prompts accepted with Enter must write the local-backend vps
        // recommendations (M1-550's table; the fixture seeds quarkus.profile=vps).
        assertEquals("240000", props.get("infochat.llm.chat.timeout-ms"),
                "chat timeout-ms must be the local-backend vps recommendation");
        assertEquals("600", props.get("infochat.llm.chat.max-tokens"),
                "chat max-tokens must be the local-backend vps recommendation");
        assertEquals("240000", props.get("infochat.llm.summarizer.timeout-ms"),
                "summarizer timeout-ms must be the local-backend vps recommendation");
        assertEquals("400", props.get("infochat.llm.summarizer.max-tokens"),
                "summarizer max-tokens must be the local-backend vps recommendation");
        assertEquals(LLAMACPP_URL, props.get("infochat.llm.default.base-url"),
                "the generative endpoint must be the shared default key (D56/M1-603)");
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

        // M1-571: the URL + SHA are persisted alongside the filename so restore.sh can
        // recover the model on a fresh host (pinned here mirrors restore.sh's constants).
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF_URL=\"" + GEN_URL + "\""),
                "the wizard must persist the generative GGUF URL for restore recovery:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF_SHA=\"" + GEN_SHA + "\""),
                "the wizard must persist the generative GGUF SHA:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_EMBED_GGUF_URL=\"" + EMB_URL + "\""),
                "the wizard must persist the embeddings GGUF URL for restore recovery:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_EMBED_GGUF_SHA=\"" + EMB_SHA + "\""),
                "the wizard must persist the embeddings GGUF SHA:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void customGenerativeGgufUrlAndShaArePersistedForRestoreRecovery(@TempDir Path tmp) throws Exception {
        // M1-571: a CUSTOM generative GGUF's download URL + SHA must be persisted to
        // secrets.env (not just the filename) so restore.sh can re-fetch it on a fresh
        // host. Drive the wizard with a custom generative URL + SHA (pinned embedder);
        // the fake docker's wildcard sha256sum returns all-zeros, which the custom SHA
        // matches so the enforced-checksum path passes.
        String customUrl = "https://models.example.test/my-custom-gen.gguf";
        String customSha = "0000000000000000000000000000000000000000000000000000000000000000";
        // stdin: backend=llamacpp, generative=<custom url>, gen SHA=<customSha>,
        // embeddings=llamacpp (Enter), embeddings GGUF=pinned (Enter), timing (4× Enter).
        runWizard(tmp, "llamacpp\n" + customUrl + "\n" + customSha + "\n\n\n" + ACCEPT_TIMING_DEFAULTS);

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF=\"my-custom-gen.gguf\""),
                "the custom generative GGUF filename must be minted:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF_URL=\"" + customUrl + "\""),
                "the custom generative GGUF URL must be persisted so restore can recover it (M1-571):\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF_SHA=\"" + customSha + "\""),
                "the custom generative GGUF SHA must be persisted:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaEmbeddingsShapePointsAtOllamaNomicEndpoint(@TempDir Path tmp) throws Exception {
        // stdin: backend=llamacpp, generative=pinned (Enter), embeddings=ollama,
        // timing defaults (4× Enter).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\nollama\n" + ACCEPT_TIMING_DEFAULTS);

        assertEquals(GEN_GGUF, props.get("infochat.llm.chat.model"));
        assertEquals(LLAMACPP_URL, props.get("infochat.llm.default.base-url"),
                "the generative endpoint must be the shared default key (D56/M1-603)");
        assertEquals(OLLAMA_URL, props.get("infochat.embeddings.base-url"),
                "embeddings must point at the co-running Ollama endpoint");
        assertEquals(OLLAMA_NOMIC, props.get("infochat.embeddings.model"),
                "Ollama embeddings shape must use the nomic-class model");
        assertEquals("768", props.get("infochat.embeddings.dimension"));
        assertNotEquals(GEN_GGUF, props.get("infochat.embeddings.model"),
                "embeddings must NEVER resolve to the generative GGUF");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void fetchGgufWritesToTheVolumeComposeMounts(@TempDir Path tmp) throws Exception {
        // The drive layer no longer no-ops the `-v` argument: it captures the real
        // model volume fetch_gguf passes and asserts it equals the project-independent
        // real name the compose services mount. This is the assertion that would have
        // caught the M1-442 volume-name mismatch (the GGUFs landing in a volume the
        // servers never mount).
        runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS);

        String scriptVolume = modelVolumeFromDockerArgv(Files.readString(tmp.resolve("docker-argv.log")));
        assertEquals(MODEL_VOLUME, scriptVolume,
                "fetch_gguf must write/probe the GGUFs in volume " + MODEL_VOLUME);
        assertEquals(scriptVolume, composeModelVolumeRealName(),
                "the wizard's model volume must equal the project-independent real name compose pins");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void oneShotDownloadContainersUseTheHostNetworkPath(@TempDir Path tmp) throws Exception {
        // The one-shot download runs in the host netns (the path the reachability
        // preflight proves) with the host's proxy env forwarded name-only; the
        // probe-absent switch makes the shim issue the download, so the argv is real.
        runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS,
                Map.of("FAKE_DOCKER_PROBE_ABSENT", "1"));

        String download = downloadInvocationFromDockerArgv(Files.readString(tmp.resolve("docker-argv.log")));
        assertTrue(containsToken(download, "--network host"),
                "the one-shot GGUF download must run in the host netns (M1-808):\n" + download);
        for (String proxyVar : List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY")) {
            assertTrue(containsToken(download, "-e " + proxyVar),
                    "the download must forward the host's " + proxyVar + " in name-only form (M1-808):\n" + download);
        }
        assertTrue(containsToken(download, "-u 0:0"),
                "the download must keep the root-write flag (M1-808):\n" + download);
        assertTrue(containsToken(download, "-v infochat-llamacpp-models:/models"),
                "the download must keep the pinned model-volume mount (M1-442):\n" + download);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void llamacppPreflightAbortsOnUnreachableHostBeforeAnyDownload(@TempDir Path tmp) throws Exception {
        // M1-809 reproduction: the llamacpp branch used to start a multi-GB GGUF
        // download with NO reachability preflight. A network-class probe failure
        // (curl exit 6 = resolve) must abort with guidance BEFORE any docker invocation.
        WizardRun run = runWizardCapture(
                tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS, Map.of("FAKE_CURL_EXIT", "6"));

        assertNotEquals(0, run.rc, "a resolve failure must abort the wizard, not proceed to download:\n" + run.output);
        assertFalse(Files.exists(tmp.resolve("docker-argv.log")),
                "no docker invocation may be recorded when the preflight aborts (the download must never start)");
        assertTrue(Files.exists(tmp.resolve("curl-argv.log")),
                "the preflight must actually run curl (the failing probe)");
        assertTrue(run.output.contains("network path"),
                "the abort must state the checked path is the host's own network path:\n" + run.output);
        assertTrue(run.output.contains("proxy"),
                "the abort must name an actionable cause class + proxy remedy:\n" + run.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void llamacppPreflightHeadChecksEveryGgufUrlBeforeDownload(@TempDir Path tmp) throws Exception {
        // The preflight must HEAD every GGUF URL the branch will download — the
        // generative always, the embeddings when llama.cpp-backed — before the
        // first fetch; a HEAD-refusal exit (22) must not block (P10).
        WizardRun run = runWizardCapture(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS,
                Map.of("FAKE_DOCKER_PROBE_ABSENT", "1", "FAKE_CURL_EXIT", "22"));

        assertEquals(0, run.rc, "a HEAD refusal must not abort the download:\n" + run.output);
        assertTrue(run.output.contains("WARN"), "a HEAD refusal must print a warning:\n" + run.output);

        String curlLog = Files.readString(tmp.resolve("curl-argv.log"));
        assertTrue(curlLog.contains(GEN_URL) && curlLog.contains("-fsSLI"),
                "the generative GGUF URL must be HEAD-checked (4b's -fsSLI shape):\n" + curlLog);
        assertTrue(curlLog.contains(EMB_URL),
                "the embeddings GGUF URL must be HEAD-checked when llamacpp-backed:\n" + curlLog);

        String dockerLog = Files.readString(tmp.resolve("docker-argv.log"));
        String download = downloadInvocationFromDockerArgv(dockerLog);
        assertTrue(download.contains(GEN_URL),
                "the download (gated by the preflight — see the abort test) must still be issued:\n" + dockerLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaEmbeddingsShapePreflightsOnlyTheGenerativeGguf(@TempDir Path tmp) throws Exception {
        // The ollama-pull leg runs on the compose network (verified working), NOT
        // the host path — a host-curl preflight of it would be the P1 false-pass
        // shape. The ollama-embeddings drive must HEAD ONLY the generative URL.
        WizardRun run = runWizardCapture(tmp, "llamacpp\n\nollama\n" + ACCEPT_TIMING_DEFAULTS,
                Map.of("FAKE_DOCKER_PROBE_ABSENT", "1"));

        assertEquals(0, run.rc, "the ollama-embeddings shape must still succeed:\n" + run.output);
        List<String> heads = Files.readString(tmp.resolve("curl-argv.log")).lines().toList();
        assertEquals(1, heads.size(), "exactly one preflight HEAD (the generative GGUF):\n" + heads);
        assertTrue(heads.get(0).contains(GEN_URL), "the HEADed URL must be the generative GGUF:\n" + heads);
        assertFalse(heads.get(0).contains(EMB_URL),
                "the embeddings URL must NOT be HEADed for the ollama leg (false-pass shape):\n" + heads);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void preflightDistinguishesNetworkFailureFromHeadRefusal(@TempDir Path tmp) throws Exception {
        // P10: the preflight verifies the NETWORK PATH, not the asset. An
        // HTTP-level refusal (exit 22 — the server answered) proves reachability
        // and must warn + continue; only exits 6/7/28 abort.
        WizardRun refusal = runWizardCapture(tmp, "llamacpp\n" + CUSTOM_GEN_URL + "\n\n\n\n" + ACCEPT_TIMING_DEFAULTS,
                Map.of("FAKE_DOCKER_PROBE_ABSENT", "1", "FAKE_CURL_EXIT", "22"));
        assertEquals(0, refusal.rc, "a HEAD refusal must not abort a reachable custom URL:\n" + refusal.output);
        assertTrue(refusal.output.contains("WARN"), "a HEAD refusal must print a warning:\n" + refusal.output);
        String dockerAfterRefusal = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(downloadInvocationFromDockerArgv(dockerAfterRefusal).contains(CUSTOM_GEN_URL),
                "the download must continue after a HEAD refusal:\n" + dockerAfterRefusal);

        for (String exit : new String[] {"6", "7", "28"}) {
            Files.deleteIfExists(tmp.resolve("docker-argv.log"));
            Files.deleteIfExists(tmp.resolve("curl-argv.log"));
            WizardRun run = runWizardCapture(
                    tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS, Map.of("FAKE_CURL_EXIT", exit));
            assertNotEquals(0, run.rc, "network-class exit " + exit + " must abort:\n" + run.output);
            assertFalse(Files.exists(tmp.resolve("docker-argv.log")),
                    "network-class exit " + exit + " must abort before any download:\n" + run.output);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void preflightFailsHardOnMalformedUrl(@TempDir Path tmp) throws Exception {
        // M1-823 reproduction: a malformed URL (curl exit 3) never went on the
        // wire, so neither the 6/7/28 abort nor the 22 warn applies — it must
        // hard-fail with the malformed/path cause class before any download.
        WizardRun run = runWizardCapture(
                tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS, Map.of("FAKE_CURL_EXIT", "3"));

        assertNotEquals(0, run.rc,
                "a malformed URL must abort the wizard, not continue to download:\n" + run.output);
        assertFalse(Files.exists(tmp.resolve("docker-argv.log")),
                "no docker invocation may be recorded when the preflight aborts (the download must never start)");
        assertTrue(Files.exists(tmp.resolve("curl-argv.log")),
                "the preflight must actually run curl (the failing probe)");
        assertTrue(run.output.contains("malformed"),
                "the abort must name the malformed-URL cause class:\n" + run.output);
        assertTrue(run.output.contains("file path"),
                "the abort must name the looks-like-a-path cause class:\n" + run.output);
        assertTrue(run.output.contains("press Enter for the pinned default"),
                "the abort must give today's remedy (full https:// URL or the pinned default):\n" + run.output);
        assertFalse(run.output.contains("staging"),
                "the abort must not advertise M1-824's not-yet-existing staging flow:\n" + run.output);
        assertFalse(run.output.contains("reachability confirmed"),
                "a probe that reached nothing must not claim reachability:\n" + run.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void hostlessSchemeValidUrlAbortsLikeALocalPath(@TempDir Path tmp) throws Exception {
        // P1 failure-mode drive: the classification keys on the exit class
        // (rc == 3), never on string-matching the operator's input — a
        // scheme-valid but hostless URL must abort identically to a local path.
        WizardRun run = runWizardCapture(
                tmp, "llamacpp\nhttps://\n\n\n\n" + ACCEPT_TIMING_DEFAULTS, Map.of("FAKE_CURL_EXIT", "3"));

        assertNotEquals(0, run.rc,
                "a hostless scheme-valid URL must abort like a malformed one:\n" + run.output);
        assertFalse(Files.exists(tmp.resolve("docker-argv.log")),
                "no docker invocation may be recorded when the preflight aborts:\n" + run.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void switchingAwayFromRemoteToLlamacppClearsStaleRemoteApiKeys(@TempDir Path tmp) throws Exception {
        // Seed the runtime as a prior `remote` run left it: seven generative api-key
        // lines + an embeddings api-key in application.properties, and the
        // INFOCHAT_LLM_API_KEY secret in secrets.env. Switching to a LOCAL backend
        // (llamacpp) must clear all of them (M1-530) — a local backend carries no key.
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        StringBuilder seed = new StringBuilder("quarkus.profile=vps\n");
        for (String task : new String[] {"security", "tagger", "entity", "classifier", "summarizer", "chat", "translator"}) {
            seed.append("infochat.llm.").append(task).append(".api-key=${INFOCHAT_LLM_API_KEY}\n");
        }
        seed.append("infochat.embeddings.api-key=${INFOCHAT_LLM_API_KEY}\n");
        Files.writeString(runtime.resolve("application.properties"), seed.toString());
        Files.writeString(runtime.resolve("secrets.env"), "INFOCHAT_LLM_API_KEY=\"sk-prior-remote-key\"\n");

        // backend=llamacpp, generative=pinned (Enter), embeddings=llamacpp (Enter),
        // embeddings GGUF=pinned (Enter), timing defaults (4× Enter).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS);

        for (String key : props.keySet()) {
            assertFalse(key.matches("infochat\\.llm\\..*\\.api-key"),
                    "no generative api-key line may survive the switch to a local backend: " + key);
        }
        assertFalse(props.containsKey("infochat.embeddings.api-key"),
                "the embeddings api-key must be cleared switching to a local backend");
        // Sanity: the switch still produced a working local config.
        assertEquals(GEN_GGUF, props.get("infochat.llm.chat.model"),
                "the generative GGUF must still drive every LLM task after the switch");

        String secrets = Files.readString(runtime.resolve("secrets.env"));
        assertFalse(secrets.contains("INFOCHAT_LLM_API_KEY"),
                "the stale remote API key secret must be removed from secrets.env:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void gpuCapableHostMergesTheVulkanOverlayForBothLlamacppServices(@TempDir Path tmp) throws Exception {
        // Reproduction (P10 made structural): the SAME positional stdin as the
        // pinned-default drive — no new prompt — with the probe seam forcing
        // GPU-present regardless of the host's /dev state (P3).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on"));
        assertEquals(GEN_GGUF, props.get("infochat.llm.chat.model"),
                "the generative GGUF must still drive every LLM task under the merged overlay");

        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        String merged = "-f " + repoRoot().resolve("docker-compose.yml")
                + " -f " + repoRoot().resolve("docker-compose.gpu.yml");
        for (String service : new String[] {"llamacpp", "llamacpp-embeddings"}) {
            List<String> ups = composeUpInvocations(argv, service);
            assertEquals(1, ups.size(), "exactly one compose up for " + service + ":\n" + argv);
            assertTrue(ups.get(0).contains(merged),
                    service + " up must merge the base file first, then the Vulkan overlay:\n" + ups.get(0));
            assertTrue(ups.get(0).contains("--env-file"),
                    service + " up must keep the --env-file seam:\n" + ups.get(0));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void forcedGpuOffKeepsTheBaseFileOnly(@TempDir Path tmp) throws Exception {
        // Override seam: INFOCHAT_LLAMACPP_GPU=off keeps base-file-only ups even
        // against render nodes; the decision is probe-driven and never a prompt.
        runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        String base = "-f " + repoRoot().resolve("docker-compose.yml");
        assertFalse(argv.contains("docker-compose.gpu.yml"),
                "INFOCHAT_LLAMACPP_GPU=off must keep every compose call on the base file:\n" + argv);
        for (String service : new String[] {"llamacpp", "llamacpp-embeddings"}) {
            List<String> ups = composeUpInvocations(argv, service);
            assertEquals(1, ups.size(), "exactly one compose up for " + service + ":\n" + argv);
            assertTrue(ups.get(0).contains(base),
                    service + " up must keep the base compose file:\n" + ups.get(0));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaEmbeddingsUpNeverMergesTheGpuOverlay(@TempDir Path tmp) throws Exception {
        // P9 negative: the overlay defines no ollama keys — with GPU forced on,
        // the llamacpp up merges it and the ollama-embeddings up stays base-only.
        runWizard(tmp, "llamacpp\n\nollama\n" + ACCEPT_TIMING_DEFAULTS,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on"));

        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        String base = "-f " + repoRoot().resolve("docker-compose.yml");
        String merged = base + " -f " + repoRoot().resolve("docker-compose.gpu.yml");
        List<String> llamacppUps = composeUpInvocations(argv, "llamacpp");
        assertEquals(1, llamacppUps.size(), "exactly one generative compose up:\n" + argv);
        assertTrue(llamacppUps.get(0).contains(merged),
                "the llamacpp up must merge the Vulkan overlay with GPU forced on:\n" + llamacppUps.get(0));
        List<String> ollamaUps = composeUpInvocations(argv, "ollama");
        assertEquals(1, ollamaUps.size(), "exactly one ollama compose up:\n" + argv);
        assertTrue(ollamaUps.get(0).contains(base),
                "the ollama up must keep the base compose file:\n" + ollamaUps.get(0));
        assertFalse(ollamaUps.get(0).contains("docker-compose.gpu.yml"),
                "the ollama up must never merge the GPU overlay:\n" + ollamaUps.get(0));
    }

    // --- helpers ----------------------------------------------------------------

    /** Run prod/scripts/4-llm.sh with a fake docker on PATH; return generated props. */
    private Map<String, String> runWizard(Path tmp, String stdin) throws Exception {
        return runWizard(tmp, stdin, Map.of());
    }

    /** runWizard plus extra env for the drive (e.g. the probe-absent switch). */
    private Map<String, String> runWizard(Path tmp, String stdin, Map<String, String> extraEnv) throws Exception {
        WizardRun run = runWizardCapture(tmp, stdin, extraEnv);
        assertEquals(0, run.rc, "4-llm.sh must exit 0; output:\n" + run.output);
        return parseProps(runtimeDir(tmp).resolve("application.properties"));
    }

    /** Drive the wizard without the exit-0 assertion, returning the raw outcome. */
    private WizardRun runWizardCapture(Path tmp, String stdin, Map<String, String> extraEnv) throws Exception {
        Path repoRoot = repoRoot();
        Path runtime = Files.createDirectories(runtimeDir(tmp));
        // Seed the profile 1-profile.sh would have written (vps has a nomic embedder),
        // but honor a config a test pre-staged (e.g. a prior `remote` run's api-keys,
        // M1-530) — only write the bare profile when nothing is already seeded.
        Path propsFile = runtime.resolve("application.properties");
        if (!Files.exists(propsFile)) {
            Files.writeString(propsFile, "quarkus.profile=vps\n");
        }

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, fakeDockerScript());
        fakeDocker.toFile().setExecutable(true);
        Path fakeCurl = bin.resolve("curl");
        Files.writeString(fakeCurl, fakeCurlScript());
        fakeCurl.toFile().setExecutable(true);

        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot.resolve("prod/scripts/4-llm.sh").toString());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", ""));
        // The fake docker appends each invocation's argv here so the test can read
        // back the `-v <volume>:/models` argument fetch_gguf passes (M1-442).
        env.put("FAKE_DOCKER_ARGV", tmp.resolve("docker-argv.log").toString());
        // The fake curl records its argv so the test can assert the preflight HEADs
        // the GGUF URLs before any download (M1-809).
        env.put("FAKE_CURL_ARGV", tmp.resolve("curl-argv.log").toString());
        env.putAll(extraEnv);

        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        return new WizardRun(rc, output);
    }

    /** The drive's runtime dir (application.properties / secrets.env live here). */
    private Path runtimeDir(Path tmp) {
        return tmp.resolve("runtime");
    }

    /**
     * Minimal fake docker: record argv (so the test can assert the `-v` model-volume
     * argument fetch_gguf passes), no-op the compose up / exec and the volume
     * download/run, answer the sha256sum probe with the pinned digests so the
     * enforced-checksum path passes. No real container ever runs.
     */
    private String fakeDockerScript() {
        return "#!/usr/bin/env bash\n"
                + "printf '%s\\n' \"$*\" >> \"$FAKE_DOCKER_ARGV\"\n"
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
                + "  if [ \"$ep\" = \"ls\" ] && [ -n \"$FAKE_DOCKER_PROBE_ABSENT\" ]; then\n"
                + "    exit 1\n"  // opt-in: report the volume probe ABSENT so the download is issued and recorded
                + "  fi\n"
                + "  exit 0\n"  // ls reports present (skip download); download/rm no-op
                + "fi\n"
                + "exit 0\n";
    }

    /** Minimal fake curl: record argv, exit FAKE_CURL_EXIT (default 0). No real egress (P9). */
    private String fakeCurlScript() {
        return "#!/usr/bin/env bash\n"
                + "printf '%s\\n' \"$*\" >> \"$FAKE_CURL_ARGV\"\n"
                + "exit \"${FAKE_CURL_EXIT:-0}\"\n";
    }

    /** The base-file compose block for a 2-space-indented service, header to the next service key. */
    private String composeServiceBlock(String service) throws IOException {
        return composeServiceBlock("docker-compose.yml", service);
    }

    /** The compose block for a 2-space-indented service in the named compose file. */
    private String composeServiceBlock(String composeFile, String service) throws IOException {
        String compose = Files.readString(repoRoot().resolve(composeFile));
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

    /** The volume name from the first `-v <volume>:/models` argument the fake docker recorded. */
    private String modelVolumeFromDockerArgv(String argv) {
        Matcher m = Pattern.compile("-v (\\S+):/models").matcher(argv);
        assertTrue(m.find(), "fetch_gguf must pass a `-v <volume>:/models` argument:\n" + argv);
        return m.group(1);
    }

    /** The recorded download invocation — the argv line carrying `-fL -o` (the download, not a probe). */
    private String downloadInvocationFromDockerArgv(String argv) {
        return argv.lines()
                .filter(l -> l.contains("-fL") && l.contains("-o "))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no download invocation recorded in the fake-docker argv:\n" + argv));
    }

    /** The recorded compose {@code up -d <service>} argv lines (an up line ends with the service name). */
    private List<String> composeUpInvocations(String argv, String service) {
        return argv.lines().filter(l -> l.endsWith("up -d " + service)).toList();
    }

    /** True when the line carries the exact whitespace-delimited token (name-only -e flags stay unexpanded). */
    private boolean containsToken(String line, String token) {
        return Pattern.compile("(^|\\s)" + Pattern.quote(token) + "(\\s|$)").matcher(line).find();
    }

    /** The volume alias a compose service mounts at /models. */
    private String mountedModelVolumeAlias(String service) throws IOException {
        String block = composeServiceBlock(service);
        Matcher m = Pattern.compile("(?m)^\\s*-\\s*(\\S+):/models\\s*$").matcher(block);
        assertTrue(m.find(), service + " service must mount a volume at /models:\n" + block);
        return m.group(1);
    }

    /**
     * The project-independent real Docker volume name the compose services mount.
     * Both llama.cpp services must mount the same alias, and the top-level volume
     * declaration must pin an explicit {@code name:} — without it compose namespaces
     * the alias to {@code <project>_<alias>} (project = working-dir basename), which
     * the wizard's literal {@code -v} never matches (the M1-442 volume-name bug).
     */
    private String composeModelVolumeRealName() throws IOException {
        String genAlias = mountedModelVolumeAlias("llamacpp");
        String embAlias = mountedModelVolumeAlias("llamacpp-embeddings");
        assertEquals(genAlias, embAlias, "both llama.cpp services must mount the same model volume");

        String compose = Files.readString(repoRoot().resolve("docker-compose.yml"));
        Matcher decl = Pattern.compile(
                "(?m)^  " + Pattern.quote(genAlias) + ":[ \\t]*\\r?\\n[ \\t]+name:[ \\t]*(\\S+)")
                .matcher(compose);
        assertTrue(decl.find(),
                "top-level volume '" + genAlias + "' must pin an explicit name: (project-independent):\n"
                        + compose);
        return decl.group(1);
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

    /** The outcome of a wizard drive that does not assert the exit code itself. */
    private record WizardRun(int rc, String output) {}
}
