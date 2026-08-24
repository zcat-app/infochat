package app.zcat.infochat.llm.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 *   <li>The generated-config layer also drives the default Ollama backend,
 *       including its non-interactive {@code --defaults} path.</li>
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
    // The pinned one-shot image (lock-step with 4-llm.sh) that runs every volume
    // container — probe, download, and the M1-824 stage copy.
    private static final String CURL_IMAGE = "curlimages/curl:8.11.1";

    private static final String LLAMACPP_URL = "http://llamacpp:8080/v1";
    private static final String LLAMACPP_EMBED_URL = "http://llamacpp-embeddings:8080/v1";
    private static final String OLLAMA_URL = "http://ollama:11434/v1";
    private static final String OLLAMA_NOMIC = "nomic-embed-text";

    // Four Enter answers accepting the recommended values at M1-550's step-4
    // prompt_timing reads (chat/summarizer × timeout-ms/max-tokens) — every
    // wizard drive must supply them or the script dies at EOF under set -e.
    private static final String ACCEPT_TIMING_DEFAULTS = "\n\n\n\n";
    // One appended Enter answering the M1-895 reply-mode ask (fires after the
    // timing reads); every tail-reaching drive must supply it or the script
    // dies at EOF under set -e — the new prompt must never add unasked stdin.
    private static final String ACCEPT_REPLYMODE_DEFAULT = "\n";

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
    void gpuOverlayPinsAllLayersOffloadAndBaseStaysFree() throws IOException {
        // M1-905 amendment b: the explicit all-layers pin fails LOUD at model
        // load where b9776's fit-to-device auto offload would degrade SILENTLY
        // into partial offload; the overlay is GPU-only by definition.
        for (String service : new String[] {"llamacpp", "llamacpp-embeddings"}) {
            String block = composeServiceBlock("docker-compose.gpu.yml", service);
            assertTrue(block.contains("LLAMA_ARG_N_GPU_LAYERS: \"999\""),
                    "GPU overlay " + service + " must pin all-layers offload:\n" + block);
        }
        assertFalse(Files.readString(repoRoot().resolve("docker-compose.yml")).contains("LLAMA_ARG_N_GPU_LAYERS"),
                "the base file must declare no ngl key — GPU wiring stays overlay-only so the base"
                        + " file stays startable on GPU-less hosts (M1-744)");
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

    @Test
    void composeExposesParallelAndCtxKeysWithSafeDefaults() throws IOException {
        // M1-905: serving shape is operator-settable on the generative service
        // only. parallel default 1 = deliberate pin (image default is 4 auto
        // slots); ctx default 0 = b9776's own --ctx-size default (from model).
        String gen = composeServiceBlock("llamacpp");
        assertTrue(gen.contains("LLAMA_ARG_N_PARALLEL: \"${INFOCHAT_LLAMACPP_PARALLEL:-1}\""),
                "generative llamacpp must expose operator-settable slots:\n" + gen);
        assertTrue(gen.contains("LLAMA_ARG_CTX_SIZE: \"${INFOCHAT_LLAMACPP_CTX:-0}\""),
                "generative llamacpp must expose operator-settable ctx (default 0 = b9776 from-model default):\n" + gen);
        String emb = composeServiceBlock("llamacpp-embeddings");
        assertFalse(emb.contains("LLAMA_ARG_N_PARALLEL"),
                "embeddings service stays single-slot (M1-905 out-of-scope):\n" + emb);
        assertFalse(emb.contains("LLAMA_ARG_CTX_SIZE"),
                "embeddings service carries no ctx key (M1-905 out-of-scope):\n" + emb);
        // P9: LLAMA_ARG_PARALLEL is silently IGNORED by llama-server and is NOT
        // a substring of LLAMA_ARG_N_PARALLEL, so a plain scan is exact — the
        // wrong name must appear nowhere on the compose/wizard surface.
        List<String> surfaces = new ArrayList<>(List.of("docker-compose.yml", "docker-compose.gpu.yml"));
        try (Stream<Path> scripts = Files.list(repoRoot().resolve("prod/scripts"))) {
            scripts.forEach(p -> surfaces.add("prod/scripts/" + p.getFileName()));
        }
        for (String surface : surfaces) {
            assertFalse(Files.readString(repoRoot().resolve(surface)).contains("LLAMA_ARG_PARALLEL"),
                    "the silently-ignored wrong env name LLAMA_ARG_PARALLEL must appear nowhere: " + surface);
        }
    }

    @Test
    void composeExposesSpecDecodeKeysWithOffDefaults() throws IOException {
        String gen = composeServiceBlock("llamacpp");
        assertTrue(gen.contains("LLAMA_ARG_SPEC_TYPE: \"${INFOCHAT_LLAMACPP_SPEC_TYPE:-none}\""),
                "generative llamacpp must expose speculative-decoding type with none default:\n" + gen);
        assertTrue(gen.contains("LLAMA_ARG_SPEC_DRAFT_MODEL: \"${INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF:+/models/${INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF}}\""),
                "draft model must be empty when unset and /models/<filename> when set:\n" + gen);
        assertTrue(gen.contains("LLAMA_ARG_SPEC_DRAFT_N_MAX: \"${INFOCHAT_LLAMACPP_SPEC_N_MAX:-4}\""),
                "generative llamacpp must expose draft n-max with 4 default:\n" + gen);
        assertFalse(gen.contains("LLAMA_ARG_SPEC_DRAFT_MODEL: /models/"),
                "the off render must not make llama-server load the /models/ directory:\n" + gen);

        String emb = composeServiceBlock("llamacpp-embeddings");
        assertFalse(emb.contains("LLAMA_ARG_SPEC_"),
                "speculative-decoding keys belong only on the generative service:\n" + emb);

        List<String> surfaces = new ArrayList<>(List.of("docker-compose.yml", "docker-compose.gpu.yml"));
        try (Stream<Path> scripts = Files.list(repoRoot().resolve("prod/scripts"))) {
            scripts.forEach(p -> surfaces.add("prod/scripts/" + p.getFileName()));
        }
        for (String surface : surfaces) {
            String content = Files.readString(repoRoot().resolve(surface));
            assertFalse(content.contains("LLAMA_ARG_SPEC_N_MAX"),
                    "the silently-ignored wrong env name must appear nowhere: " + surface);
            assertFalse(content.contains("LLAMA_ARG_SPEC_MODEL"),
                    "the wrong draft-model env name must appear nowhere: " + surface);
            assertFalse(content.contains("LLAMA_ARG_DRAFT_MODEL"),
                    "the legacy wrong draft-model env name must appear nowhere: " + surface);
            assertFalse(content.contains("mtp-gemma"),
                    "the shipped surface must not pin a Gemma-specific draft head: " + surface);
        }
        // The draft-mtp pin covers COMPOSE only (M1-909 clarity_check): compose
        // must never pin an active spec type, but the wizard's GPU-branch offer
        // carries an overtypable [draft-mtp] prompt default, not a constant.
        for (String compose : new String[] {"docker-compose.yml", "docker-compose.gpu.yml"}) {
            assertFalse(Files.readString(repoRoot().resolve(compose)).contains("draft-mtp"),
                    "the shipped compose surface must not pin a speculative type: " + compose);
        }
    }

    @Test
    void composeExposesCacheRamKeyWithClassWrites() throws IOException {
        // M1-920: the prompt-cache MiB limit is FIXED (does not scale with ctx);
        // 8192 is the --help-verified image default (LLAMA_ARG_CACHE_RAM on
        // server-vulkan-b9776), so the base render stays byte-stable (§7.8.3).
        String gen = composeServiceBlock("llamacpp");
        assertTrue(gen.contains("LLAMA_ARG_CACHE_RAM: \"${INFOCHAT_LLAMACPP_CACHE_MB:-8192}\""),
                "generative llamacpp must expose the operator-settable prompt-cache MiB limit"
                        + " (default 8192 = the image default, byte-stable render):\n" + gen);
        String emb = composeServiceBlock("llamacpp-embeddings");
        assertFalse(emb.contains("LLAMA_ARG_CACHE_RAM"),
                "the cache key belongs only on the generative service (embedding prompts are tiny):\n" + emb);
        // P11: a misspelled LLAMA_ARG_* name is silently ignored — pin the exact
        // name by scanning the compose + wizard surface for wrong names, then
        // admit no LLAMA_ARG_CACHE_* variant outside the verified one.
        List<String> surfaces = new ArrayList<>(List.of("docker-compose.yml", "docker-compose.gpu.yml"));
        try (Stream<Path> scripts = Files.list(repoRoot().resolve("prod/scripts"))) {
            scripts.forEach(p -> surfaces.add("prod/scripts/" + p.getFileName()));
        }
        for (String surface : surfaces) {
            String content = Files.readString(repoRoot().resolve(surface));
            assertFalse(content.contains("LLAMA_ARG_CACHE_SIZE"),
                    "the plausible wrong cache env name must appear nowhere: " + surface);
            assertFalse(content.contains("LLAMA_ARG_CACHE_MB"),
                    "the plausible wrong cache env name must appear nowhere: " + surface);
            assertFalse(content.contains("LLAMA_ARG_PROMPT_CACHE"),
                    "the plausible wrong cache env name must appear nowhere: " + surface);
            Matcher m = Pattern.compile("LLAMA_ARG_CACHE_[A-Z_]+").matcher(content);
            while (m.find()) {
                assertEquals("LLAMA_ARG_CACHE_RAM", m.group(),
                        "no LLAMA_ARG_CACHE_* variant outside the verified name may appear on " + surface);
            }
        }
    }

    // --- Generated config (drive the real wizard) -------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void llamacppEmbeddingsShapePointsAtEmbeddingsServiceNeverGenerativeGguf(@TempDir Path tmp)
            throws Exception {
        // stdin: backend=llamacpp, generative=pinned (Enter), embeddings=llamacpp
        // (Enter), embeddings GGUF=pinned (Enter), timing defaults (4× Enter).
        // GPU forced OFF (M1-905 carve-out): `auto` is GPU-class on a /dev/dri
        // host, which the GPU timing branch would render 60000 against this
        // drive's vps-class pins — the env pins the CPU class it was written for.
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

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
        runWizard(tmp, "llamacpp\n" + customUrl + "\n" + customSha + "\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

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
    void localGgufPathIsStagedIntoTheVolumeWithoutDownload(@TempDir Path tmp) throws Exception {
        // M1-824 reproduction: a local path at the generative prompt is staged by an
        // argv-only cp from a read-only /stage mount — never preflighted, never
        // downloaded (RED on main: the path flowed to preflight + download instead).
        Path local = tmp.resolve("operator-local-model.gguf");
        Files.writeString(local, "fake gguf bytes");
        String localPath = local.toAbsolutePath().toString();
        runWizard(tmp, "llamacpp\n" + localPath + "\n" + "\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1"));

        String curlLog = Files.readString(tmp.resolve("curl-argv.log"));
        assertFalse(curlLog.contains(localPath),
                "no preflight or fetch may touch the local path (staged sources bypass the URL preflight):\n" + curlLog);

        String dockerLog = Files.readString(tmp.resolve("docker-argv.log"));
        String cp = dockerLog.lines()
                .filter(l -> l.contains("--entrypoint cp"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the staged source must be copied by an argv-only cp container:\n" + dockerLog));
        assertTrue(containsToken(cp, "-u 0:0"),
                "the cp must keep the root-write flag (a fresh volume is root-owned):\n" + cp);
        assertTrue(cp.contains("-v " + MODEL_VOLUME + ":/models"),
                "the cp must write into the pinned model volume:\n" + cp);
        assertTrue(cp.contains("-v " + local.getParent().toAbsolutePath() + ":/stage:ro"),
                "the operator's directory must be mounted READ-ONLY at /stage:\n" + cp);
        assertTrue(cp.contains(CURL_IMAGE),
                "the cp must run in the pinned one-shot image:\n" + cp);
        assertTrue(cp.endsWith("/models/" + local.getFileName()),
                "the cp target must be the basename in the model volume:\n" + cp);
        assertFalse(cp.contains("--entrypoint sh") || cp.contains("bash"),
                "the cp must stay argv-only — no shell:\n" + cp);

        Map<String, String> props = parseProps(tmp.resolve("runtime/application.properties"));
        for (String task : List.of("security", "tagger", "entity", "classifier", "summarizer", "chat", "translator")) {
            assertEquals(local.getFileName().toString(), props.get("infochat.llm." + task + ".model"),
                    "task " + task + " must use the staged GGUF's basename as the model");
        }

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF=\"" + local.getFileName() + "\""),
                "the staged GGUF filename must be minted:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF_URL=\"\""),
                "the staged flow must persist an EMPTY URL (a host path is not re-fetchable):\n" + secrets);
        assertFalse(secrets.contains(localPath),
                "the host path must NEVER be persisted into secrets.env:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void locallyStagedGgufRunDisclosesTheRestoreConsequence(@TempDir Path tmp) throws Exception {
        // M1-825 reproduction: a staged generative GGUF persists an EMPTY URL
        // (M1-824), so a fresh-host restore cannot re-fetch it — the staged
        // drive's output must disclose that at setup time.
        Path local = tmp.resolve("operator-local-model.gguf");
        Files.writeString(local, "fake gguf bytes");
        String localPath = local.toAbsolutePath().toString();
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n" + localPath + "\n" + "\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1"));

        assertEquals(0, run.rc, "the staged flow must succeed:\n" + run.output);
        String disclosure = run.output.lines()
                .filter(l -> l.contains("not re-fetchable"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the staged drive must disclose the restore consequence:\n" + run.output));
        assertTrue(disclosure.contains(local.getFileName().toString()),
                "the disclosure must name the staged GGUF's basename (P13):\n" + disclosure);
        assertFalse(disclosure.contains(local.getParent().toString()),
                "the disclosure must not echo the host directory (P13):\n" + disclosure);
        assertTrue(run.output.contains("keep the source file"),
                "the disclosure must tell the operator to keep the source file:\n" + run.output);
        assertTrue(run.output.contains("manual-staging recipe"),
                "the disclosure must point at restore.sh's manual-staging recipe:\n" + run.output);

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF_URL=\"\""),
                "the staged flow must persist an EMPTY URL (M1-824 day-one behavior, pinned as the end state):\n"
                        + secrets);
        assertFalse(secrets.contains(localPath),
                "the host path must appear NOWHERE in secrets.env:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void stagedEmbeddingsGgufDisclosesAndPersistsNoRefetchUrl(@TempDir Path tmp) throws Exception {
        // P6 twin: a staged EMBEDDINGS file drives the same disclosure and
        // persists an EMPTY INFOCHAT_LLAMACPP_EMBED_GGUF_URL — the symmetric
        // failure mode to the generative drive.
        Path emb = tmp.resolve("operator-local-embed.gguf");
        Files.writeString(emb, "fake gguf bytes");
        String embPath = emb.toAbsolutePath().toString();
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n" + embPath + "\nyes\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1"));

        assertEquals(0, run.rc, "a confirmed staged embeddings override must stage:\n" + run.output);
        String disclosure = run.output.lines()
                .filter(l -> l.contains("not re-fetchable"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the staged embeddings drive must disclose the restore consequence:\n" + run.output));
        assertTrue(disclosure.contains(emb.getFileName().toString()),
                "the embeddings disclosure must name the staged basename (P13):\n" + disclosure);
        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_EMBED_GGUF_URL=\"\""),
                "the staged embeddings flow must persist an EMPTY embeddings URL:\n" + secrets);
        assertFalse(secrets.contains(embPath),
                "the embeddings host path must never be persisted:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void pinnedDefaultRunPrintsNoRefetchDisclosure(@TempDir Path tmp) throws Exception {
        // Item-3 negative: the pinned-default path IS re-fetchable (restore.sh
        // re-fetches from its known URL) — a disclosure here would cry wolf on
        // the default path.
        WizardRun run = runWizardCapture(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertEquals(0, run.rc, "the pinned-default drive must succeed:\n" + run.output);
        assertFalse(run.output.contains("not re-fetchable"),
                "the re-fetchable pinned-default path must print NO disclosure:\n" + run.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void stagedGgufSkipsWhenAlreadyInTheVolume(@TempDir Path tmp) throws Exception {
        // P5 control parity: skip-if-present applies to staged files — a PRESENT
        // volume probe records no cp and no download and prints the skip line
        // (the item-6 session workaround is now the first-class flow).
        Path local = tmp.resolve("operator-local-model.gguf");
        Files.writeString(local, "fake gguf bytes");
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n" + local.toAbsolutePath() + "\n" + "\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertEquals(0, run.rc, "a staged source already in the volume must succeed:\n" + run.output);
        assertTrue(run.output.contains("skip GGUF staging"),
                "the skip line must print for a present staged file:\n" + run.output);
        String dockerLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(dockerLog.contains("--entrypoint cp"),
                "no cp may run when the staged file is already present:\n" + dockerLog);
        assertFalse(dockerLog.lines().anyMatch(l -> l.contains("-fL")),
                "no download may run when the staged file is already present:\n" + dockerLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void stagedGgufShaMismatchFailsAndRemoves(@TempDir Path tmp) throws Exception {
        // P5 FAILURE-MODE: a non-empty SHA on a staged file is enforced by the same
        // sha256sum probe container as downloads; a mismatch removes the file and
        // fails the wizard (the fake docker answers all-zeros for unknown basenames).
        Path local = tmp.resolve("operator-local-model.gguf");
        Files.writeString(local, "fake gguf bytes");
        String wrongSha = "f".repeat(64);
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n" + local.toAbsolutePath() + "\n" + wrongSha + "\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("FAKE_DOCKER_PROBE_ABSENT", "1"));

        assertNotEquals(0, run.rc, "a staged-file SHA mismatch must fail the wizard:\n" + run.output);
        assertTrue(run.output.contains("checksum mismatch"),
                "the failure must name the checksum mismatch:\n" + run.output);
        String dockerLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(dockerLog.contains("--entrypoint rm"),
                "the mismatched staged file must be removed from the volume:\n" + dockerLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void nonUrlNonFileAnswerFailsAtThePrompt(@TempDir Path tmp) throws Exception {
        // Evidence item 4 FAILURE-MODE: a non-URL answer that is not an existing
        // readable absolute file aborts AT THE PROMPT — before any curl or docker
        // (RED on main: the answer rode the URL flow and the preflight curl ran).
        for (String answer : new String[] {"foo.gguf", tmp.resolve("does-not-exist.gguf").toString()}) {
            Files.deleteIfExists(tmp.resolve("docker-argv.log"));
            Files.deleteIfExists(tmp.resolve("curl-argv.log"));
            WizardRun run = runWizardCapture(tmp,
                    "llamacpp\n" + answer + "\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT, Map.of());

            assertNotEquals(0, run.rc, "'" + answer + "' must abort the wizard at the prompt:\n" + run.output);
            assertFalse(Files.exists(tmp.resolve("curl-argv.log")),
                    "no curl may run when the answer is rejected at the prompt (" + answer + ")");
            assertFalse(Files.exists(tmp.resolve("docker-argv.log")),
                    "no docker may run when the answer is rejected at the prompt (" + answer + ")");
            assertTrue(run.output.contains(answer),
                    "the failure must name the rejected answer (" + answer + "):\n" + run.output);
            assertTrue(run.output.contains("download URL"),
                    "the failure must name the download-URL form (" + answer + "):\n" + run.output);
            assertTrue(run.output.contains("absolute path"),
                    "the failure must state that local paths must be absolute (" + answer + "):\n" + run.output);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void stagedEmbeddingsGgufKeepsTheDimensionGate(@TempDir Path tmp) throws Exception {
        // P5: the 768-dim confirmation gate fires for a staged embeddings file
        // exactly as for a URL override, BEFORE any staging.
        Path emb = tmp.resolve("operator-local-embed.gguf");
        Files.writeString(emb, "fake gguf bytes");
        String embPath = emb.toAbsolutePath().toString();

        WizardRun declined = runWizardCapture(tmp,
                "llamacpp\n\n\n" + embPath + "\nno\n" + ACCEPT_REPLYMODE_DEFAULT, Map.of());
        assertNotEquals(0, declined.rc, "an unconfirmed staged embeddings override must abort:\n" + declined.output);
        assertFalse(Files.exists(tmp.resolve("docker-argv.log")),
                "the gate must fire BEFORE any staging (no docker invocation):\n" + declined.output);

        WizardRun accepted = runWizardCapture(tmp,
                "llamacpp\n\n\n" + embPath + "\nyes\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1"));
        assertEquals(0, accepted.rc, "a confirmed staged embeddings override must stage:\n" + accepted.output);
        String dockerLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(dockerLog.lines()
                        .anyMatch(l -> l.contains("--entrypoint cp") && l.endsWith("/models/" + emb.getFileName())),
                "the embeddings file must be staged into the volume:\n" + dockerLog);
        Map<String, String> props = parseProps(tmp.resolve("runtime/application.properties"));
        assertEquals(emb.getFileName().toString(), props.get("infochat.embeddings.model"),
                "the staged embeddings basename must drive the embeddings model");
        assertEquals("768", props.get("infochat.embeddings.dimension"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void stagedGgufPathWithHashInNameKeepsFullBasename(@TempDir Path tmp) throws Exception {
        // A '#' in a local filename is data, not a URL fragment: the staged branch
        // must derive the filename with plain basename (the URL helper gguf_basename
        // truncates at the first '#' or '?' and the truncated cp would fail).
        Path local = tmp.resolve("model#2.gguf");
        Files.writeString(local, "fake gguf bytes");
        runWizard(tmp, "llamacpp\n" + local.toAbsolutePath() + "\n" + "\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1"));

        String dockerLog = Files.readString(tmp.resolve("docker-argv.log"));
        String cp = dockerLog.lines()
                .filter(l -> l.contains("--entrypoint cp"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cp invocation recorded:\n" + dockerLog));
        assertTrue(cp.contains("/stage/model#2.gguf"),
                "the cp source must carry the full basename (no fragment stripping):\n" + cp);
        assertTrue(cp.endsWith("/models/model#2.gguf"),
                "the cp target must carry the full basename:\n" + cp);

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_GGUF=\"model#2.gguf\""),
                "secrets.env must mint the full basename:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaEmbeddingsShapePointsAtOllamaNomicEndpoint(@TempDir Path tmp) throws Exception {
        // stdin: backend=llamacpp, generative=pinned (Enter), embeddings=ollama,
        // timing defaults (4× Enter).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\nollama\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

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
    void ollamaDefaultsTakesAndEchoesTheReplyModeRecommendation(@TempDir Path tmp) throws Exception {
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Files.writeString(runtime.resolve("secrets.env"), "");

        WizardRun run = runWizardCapture(tmp, "", Map.of(), "--defaults");

        assertEquals(0, run.rc, "the ollama --defaults drive must succeed:\n" + run.output);
        assertTrue(run.output.contains("taking reply-mode recommendation for llama3.2:3b: translate"),
                "the --defaults path must echo the recommendation:\n" + run.output);
        Map<String, String> props = parseProps(runtime.resolve("application.properties"));
        assertEquals(OLLAMA_URL, props.get("infochat.llm.default.base-url"));
        assertEquals("llama3.2:3b", props.get("infochat.llm.chat.model"));
        assertEquals("translate", props.get("infochat.chat.reply-mode"));
        assertEquals("240000", props.get("infochat.llm.chat.timeout-ms"));
        assertEquals("600", props.get("infochat.llm.chat.max-tokens"));
        assertEquals("240000", props.get("infochat.llm.summarizer.timeout-ms"));
        assertEquals("400", props.get("infochat.llm.summarizer.max-tokens"));
        assertEquals(1L, Files.readAllLines(runtime.resolve("application.properties")).stream()
                .filter(line -> line.equals("infochat.chat.reply-mode=translate"))
                .count());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaBackendPullsProfileModelsAndWiresSharedDefaults(@TempDir Path tmp) throws Exception {
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Files.writeString(runtime.resolve("secrets.env"), "");

        WizardRun run = runWizardCapture(tmp, "ollama\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT, Map.of());

        assertEquals(0, run.rc, "the interactive ollama drive must succeed:\n" + run.output);
        String dockerArgv = Files.readString(tmp.resolve("docker-argv.log"));
        assertEquals(1, composeUpInvocations(dockerArgv, "ollama").size(),
                "the ollama service must be started exactly once:\n" + dockerArgv);
        List<String> pulls = dockerArgv.lines()
                .filter(line -> line.contains("exec -T ollama ollama pull "))
                .toList();
        assertEquals(2, pulls.size(), "the vps profile must pull two distinct models:\n" + dockerArgv);
        assertEquals(1L, pulls.stream().filter(line -> line.endsWith("pull llama3.2:3b")).count(),
                "the shared vps chat/security model must be pulled once:\n" + dockerArgv);
        assertEquals(1L, pulls.stream().filter(line -> line.endsWith("pull nomic-embed-text")).count(),
                "the vps embedding model must be pulled once:\n" + dockerArgv);
        assertTrue(run.output.contains("chat reply-mode recommendation for llama3.2:3b: translate (unmeasured)"),
                "the interactive path must print the unmeasured recommendation:\n" + run.output);

        Map<String, String> props = parseProps(runtime.resolve("application.properties"));
        assertEquals(OLLAMA_URL, props.get("infochat.llm.default.base-url"));
        assertEquals(OLLAMA_URL, props.get("infochat.embeddings.base-url"));
        assertEquals("llama3.2:3b", props.get("infochat.llm.security.model"));
        for (String task : List.of("tagger", "entity", "classifier", "summarizer", "chat", "translator")) {
            assertEquals("llama3.2:3b", props.get("infochat.llm." + task + ".model"),
                    "the vps chat model must wire the " + task + " task");
        }
        assertEquals("nomic-embed-text", props.get("infochat.embeddings.model"));
        assertEquals("768", props.get("infochat.embeddings.dimension"));
        assertEquals("translate", props.get("infochat.chat.reply-mode"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void cpuClassIngestTimeoutsTrackTheAnsweredChatTimeout(@TempDir Path tmp) throws Exception {
        // D-15: CPU-class local backends scale the five ingest-role timeouts to
        // the ANSWERED chat timeout. The ollama arm is CPU-class by
        // construction (gpu_on is unset there), so this drive needs no seam.
        Files.writeString(Files.createDirectories(tmp.resolve("runtime")).resolve("secrets.env"), "");
        Map<String, String> props = runWizard(tmp,
                "ollama\n300000\n600\n360000\n400\n" + ACCEPT_REPLYMODE_DEFAULT);

        assertEquals("300000", props.get("infochat.llm.chat.timeout-ms"),
                "the answered chat timeout must be written");
        assertEquals("360000", props.get("infochat.llm.summarizer.timeout-ms"),
                "the answered summarizer timeout must be written");
        for (String task : List.of("security", "tagger", "entity", "classifier", "translator")) {
            assertEquals("300000", props.get("infochat.llm." + task + ".timeout-ms"),
                    "ingest role " + task + " must track the answered CHAT timeout, not the summarizer's");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaBackendOnRemoteLlmProfileRefuses(@TempDir Path tmp) throws Exception {
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Files.writeString(runtime.resolve("application.properties"), "quarkus.profile=remote-llm\n");

        WizardRun run = runWizardCapture(tmp, "ollama\n", Map.of());

        assertNotEquals(0, run.rc, "ollama must refuse a profile with no local models:\n" + run.output);
        assertTrue(run.output.contains("has no local models"),
                "the refusal must name the profile/backend mismatch:\n" + run.output);
        assertFalse(Files.readAllLines(runtime.resolve("application.properties")).stream()
                .anyMatch(line -> line.startsWith("infochat.chat.reply-mode=")),
                "the refusal must happen before a reply-mode property is written");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void fetchGgufWritesToTheVolumeComposeMounts(@TempDir Path tmp) throws Exception {
        // The drive layer no longer no-ops the `-v` argument: it captures the real
        // model volume fetch_gguf passes and asserts it equals the project-independent
        // real name the compose services mount. This is the assertion that would have
        // caught the M1-442 volume-name mismatch (the GGUFs landing in a volume the
        // servers never mount).
        runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

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
        runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1"));

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
                tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT, Map.of("FAKE_CURL_EXIT", "6"));

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
        WizardRun run = runWizardCapture(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1", "FAKE_CURL_EXIT", "22"));

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
        WizardRun run = runWizardCapture(tmp, "llamacpp\n\nollama\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1"));

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
        WizardRun refusal = runWizardCapture(tmp, "llamacpp\n" + CUSTOM_GEN_URL + "\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off", "FAKE_DOCKER_PROBE_ABSENT", "1", "FAKE_CURL_EXIT", "22"));
        assertEquals(0, refusal.rc, "a HEAD refusal must not abort a reachable custom URL:\n" + refusal.output);
        assertTrue(refusal.output.contains("WARN"), "a HEAD refusal must print a warning:\n" + refusal.output);
        String dockerAfterRefusal = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(downloadInvocationFromDockerArgv(dockerAfterRefusal).contains(CUSTOM_GEN_URL),
                "the download must continue after a HEAD refusal:\n" + dockerAfterRefusal);

        for (String exit : new String[] {"6", "7", "28"}) {
            Files.deleteIfExists(tmp.resolve("docker-argv.log"));
            Files.deleteIfExists(tmp.resolve("curl-argv.log"));
            WizardRun run = runWizardCapture(
                    tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT, Map.of("FAKE_CURL_EXIT", exit));
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
                tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT, Map.of("FAKE_CURL_EXIT", "3"));

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
        assertTrue(run.output.contains("absolute path to a local GGUF file"),
                "the abort must point at the local-file form the wizard accepts (M1-824):\n" + run.output);
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
                tmp, "llamacpp\nhttps://\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT, Map.of("FAKE_CURL_EXIT", "3"));

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
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

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
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
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
        runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
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
    void gpuHostGetsBenchmarkServingClassAndTiming(@TempDir Path tmp) throws Exception {
        // M1-905: the GPU class is the campaign-measured prod candidate —
        // parallel=3 / ctx 32768 with memory 40g / cpus 12 (the caps ride the
        // same gpu_on condition: GTT pages pin to the cgroup — P14).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on"));

        assertEquals("60000", props.get("infochat.llm.chat.timeout-ms"),
                "GPU class recommends the 60s chat timeout (M1-548 derivation from the P3 worst case)");
        assertEquals("600", props.get("infochat.llm.chat.max-tokens"),
                "GPU class keeps the 600-token chat cap");
        assertEquals("60000", props.get("infochat.llm.summarizer.timeout-ms"),
                "GPU class recommends the 60s summarizer timeout");
        assertEquals("400", props.get("infochat.llm.summarizer.max-tokens"),
                "GPU class keeps the 400-token summarizer cap");

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_PARALLEL=\"3\""),
                "GPU class must write the measured 3-slot serving secret:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_CTX=\"32768\""),
                "GPU class must write the measured 32768 ctx secret:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_MEMORY=\"40g\""),
                "GPU class must write the 40g memory cap through the M1-744 key:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_CPUS=\"12\""),
                "GPU class must write the 12-cpu cap through the M1-744 key:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_CACHE_MB=\"16384\""),
                "GPU class must write the 16 GiB prompt-cache secret (P12 arithmetic):\n" + secrets);

        for (String task : List.of("security", "tagger", "entity", "classifier", "translator")) {
            assertFalse(props.containsKey("infochat.llm." + task + ".timeout-ms"),
                    "GPU class writes NO ingest-role override (30s default measured adequate ~8x — D-15): " + task);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void forcedGpuOffKeepsCpuServingClass(@TempDir Path tmp) throws Exception {
        // M1-905 failure-mode: a GPU-class leak (serving values, caps, timing)
        // onto a CPU-forced host must fail here — the base defaults ARE the
        // intended CPU-class posture (P15 absence twins).
        Map<String, String> props = runWizard(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertEquals("240000", props.get("infochat.llm.chat.timeout-ms"),
                "CPU-forced host must keep the vps-class chat timing");
        assertEquals("600", props.get("infochat.llm.chat.max-tokens"));
        assertEquals("240000", props.get("infochat.llm.summarizer.timeout-ms"),
                "CPU-forced host must keep the vps-class summarizer timing");
        assertEquals("400", props.get("infochat.llm.summarizer.max-tokens"));

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_PARALLEL=\"1\""),
                "CPU class must pin the acceptance-tested single-slot posture:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_CTX=\"4096\""),
                "CPU class must write the 4096 ctx secret:\n" + secrets);
        assertFalse(secrets.lines().anyMatch(l -> l.startsWith("INFOCHAT_LLAMACPP_MEMORY=")),
                "CPU-forced host must get NO GPU memory-cap write (P15):\n" + secrets);
        assertFalse(secrets.lines().anyMatch(l -> l.startsWith("INFOCHAT_LLAMACPP_CPUS=")),
                "CPU-forced host must get NO GPU cpus-cap write (P15):\n" + secrets);
        assertFalse(secrets.lines().anyMatch(l -> l.startsWith("INFOCHAT_LLAMACPP_CACHE_MB=")),
                "CPU-forced host must get NO GPU-class cache write (P12):\n" + secrets);

        for (String task : List.of("security", "tagger", "entity", "classifier", "translator")) {
            assertEquals("240000", props.get("infochat.llm." + task + ".timeout-ms"),
                    "CPU class scales the ingest roles to the answered chat timeout (D-15): " + task);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void gpuToCpuRerunClearsStaleCacheSecret(@TempDir Path tmp) throws Exception {
        // P12 lifecycle twin: the GPU class writes a 16 GiB allocation target, so
        // a CPU-class re-run must clear it — left under the 7g cap it is an OOM
        // invitation. Mirrors the SPEC decline-clear's seeded re-run shape.
        Path runtime = Files.createDirectories(runtimeDir(tmp));
        Files.writeString(runtime.resolve("secrets.env"),
                "INFOCHAT_LLAMACPP_CACHE_MB=\"16384\"\n");

        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertEquals(0, run.rc, "the GPU->CPU re-run drive must succeed:\n" + run.output);
        String secrets = Files.readString(runtime.resolve("secrets.env"));
        assertFalse(secrets.lines().anyMatch(l -> l.startsWith("INFOCHAT_LLAMACPP_CACHE_MB=")),
                "a GPU->CPU re-run must clear the stale GPU-class cache secret (P12):\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ollamaEmbeddingsUpNeverMergesTheGpuOverlay(@TempDir Path tmp) throws Exception {
        // P9 negative: the overlay defines no ollama keys — with GPU forced on,
        // the llamacpp up merges it and the ollama-embeddings up stays base-only.
        runWizard(tmp, "llamacpp\n\nollama\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
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

    @Test
    @EnabledOnOs(OS.LINUX)
    void rootlessGpuHostWithoutRenderNodeAccessFailsWithTheSetfaclRemedy(@TempDir Path tmp) throws Exception {
        // Reproduction: rootless docker + GPU on + nodes this user cannot
        // read+write — the M1-744 trap (group_add ineffective, empty list,
        // no error). Gate fires BEFORE bring-up; names nodes, setfacl, udev (§7.8.7).
        Path renderNode = tmp.resolve("renderD128");
        Path cardNode = tmp.resolve("card0");
        for (Path node : new Path[] {renderNode, cardNode}) {
            Files.writeString(node, "node");
            Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("---------"));
        }

        WizardRun run = runWizardCapture(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on",
                       "FAKE_DOCKER_ROOTLESS", "1",
                       "INFOCHAT_LLAMACPP_GPU_NODES", renderNode + " " + cardNode));

        assertNotEquals(0, run.rc, "a rootless host without render-node access must fail loud:\n" + run.output);
        for (String expected : new String[] {"rootless", renderNode.toString(), cardNode.toString(),
                "setfacl", "udev", "§7.8.7"}) {
            assertTrue(run.output.contains(expected),
                    "the failure must name '" + expected + "':\n" + run.output);
        }
        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argv.contains("up -d"), "the gate must fire before any compose bring-up:\n" + argv);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rootlessGpuHostWithReadOnlyRenderNodeAccessFails(@TempDir Path tmp) throws Exception {
        // The write leg of the gate: a READABLE-but-not-writable node must
        // still refuse — read alone cannot map the device for container-root.
        Path roNode = tmp.resolve("renderD128");
        Files.writeString(roNode, "node");
        Files.setPosixFilePermissions(roNode, PosixFilePermissions.fromString("r--r--r--"));

        WizardRun run = runWizardCapture(tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on",
                       "FAKE_DOCKER_ROOTLESS", "1",
                       "INFOCHAT_LLAMACPP_GPU_NODES", roNode.toString()));

        assertNotEquals(0, run.rc, "a read-only render node under rootless must fail loud:\n" + run.output);
        assertTrue(run.output.contains(roNode.toString()),
                "the failure must name the read-only node:\n" + run.output);
        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argv.contains("up -d"), "the gate must fire before any compose bring-up:\n" + argv);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rootfulOrAclPresentGpuHostProceeds(@TempDir Path tmp) throws Exception {
        // FAILURE-MODE against over-blocking: rootful proceeds regardless of
        // node access (group_add works there); rootless + rw nodes (the prod
        // host's post-ACL shape) proceeds. Both reach bring-up AND verification.
        Path blockedNode = tmp.resolve("renderD128");
        Files.writeString(blockedNode, "node");
        Files.setPosixFilePermissions(blockedNode, PosixFilePermissions.fromString("---------"));
        runWizard(tmp, "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on",
                       "INFOCHAT_LLAMACPP_GPU_NODES", blockedNode.toString()));
        String rootfulArgv = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(rootfulArgv.contains("up -d llamacpp"),
                "rootful docker + GPU on must proceed to bring-up regardless of node access:\n" + rootfulArgv);

        Path aclHost = Files.createDirectories(tmp.resolve("acl-host"));
        Path rwNode = aclHost.resolve("renderD128");
        Files.writeString(rwNode, "node");
        Files.setPosixFilePermissions(rwNode, PosixFilePermissions.fromString("rw-rw-rw-"));
        runWizard(aclHost, "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on",
                       "FAKE_DOCKER_ROOTLESS", "1",
                       "INFOCHAT_LLAMACPP_GPU_NODES", rwNode.toString()));
        String aclArgv = Files.readString(aclHost.resolve("docker-argv.log"));
        assertTrue(aclArgv.contains("up -d llamacpp"),
                "rootless + ACL-present must proceed to bring-up:\n" + aclArgv);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void gpuUpFailsLoudWhenTheContainerSeesNoDevice(@TempDir Path tmp) throws Exception {
        // P11 end-of-path assertion: the -f flags prove the overlay, not the
        // device — after EACH GPU up the wizard execs llama-server
        // --list-devices; empty list or failed exec fails loud (both services).
        WizardRun genEmpty = runWizardCapture(tmp, "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on", "FAKE_DOCKER_NO_DEVICES", "1"));
        assertNotEquals(0, genEmpty.rc, "a generative container listing zero devices must fail loud:\n" + genEmpty.output);
        assertTrue(genEmpty.output.contains("§7.8.7"),
                "the trap failure must point at the §7.8.7 remedy:\n" + genEmpty.output);
        String genArgv = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(genArgv.contains("/app/llama-server --list-devices"),
                "the wizard must exec /app/llama-server --list-devices after the GPU up (the live image shape):\n" + genArgv);

        Path embHost = Files.createDirectories(tmp.resolve("emb-host"));
        WizardRun embEmpty = runWizardCapture(embHost, "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on", "FAKE_DOCKER_NO_EMBED_DEVICES", "1"));
        assertNotEquals(0, embEmpty.rc,
                "an embeddings container listing zero devices must fail loud:\n" + embEmpty.output);
        String embArgv = Files.readString(embHost.resolve("docker-argv.log"));
        assertTrue(embArgv.contains("llamacpp-embeddings /app/llama-server --list-devices"),
                "the embeddings GPU up must also be device-verified:\n" + embArgv);

        Path deadHost = Files.createDirectories(tmp.resolve("dead-host"));
        WizardRun dead = runWizardCapture(deadHost, "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on", "FAKE_DOCKER_EXEC_EXIT", "1"));
        assertNotEquals(0, dead.rc,
                "a failed verification exec (container down) is not-verified, never passed:\n" + dead.output);
    }

    // --- M1-895: the chat reply-mode ask (D79) -----------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void wizardAsksAndWritesChatReplyMode(@TempDir Path tmp) throws Exception {
        // M1-895 reproduction (D79 wizard half): the pinned-default llamacpp
        // drive answers the reply-mode ask with bare Enter — the unmeasured
        // gemma-4-E4B GGUF gets the conservative translate recommended/written.
        WizardRun run = runWizardCapture(
                tmp, "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertEquals(0, run.rc, "the drive must succeed:\n" + run.output);
        assertTrue(run.output.contains("recommendation for " + GEN_GGUF),
                "the printed recommendation must name the model:\n" + run.output);
        assertTrue(run.output.contains(": translate"),
                "the recommendation must be translate for the unmeasured pinned model:\n" + run.output);
        Map<String, String> props = parseProps(runtimeDir(tmp).resolve("application.properties"));
        assertEquals("translate", props.get("infochat.chat.reply-mode"),
                "the wizard must write the recommended reply-mode");
        assertEquals(1,
                Files.readAllLines(runtimeDir(tmp).resolve("application.properties")).stream()
                        .filter(l -> l.startsWith("infochat.chat.reply-mode=")).count(),
                "exactly one infochat.chat.reply-mode line must exist");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void replyModeOverrideWritesNative(@TempDir Path tmp) throws Exception {
        // M1-895 (D79 operator ownership): the recommendation is advice — an
        // explicit `native` answer writes the operator's value verbatim.
        Map<String, String> props = runWizard(tmp,
                "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + "native\n",
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertEquals("native", props.get("infochat.chat.reply-mode"),
                "the operator's explicit native must be written, not the recommendation");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void replyModeInvalidAnswerFailsAndWritesNothing(@TempDir Path tmp) throws Exception {
        // M1-895 FAILURE-MODE: closed-set validation (the 4b-image.sh:338-342
        // shape) — an invalid answer exits non-zero naming the valid answers
        // and writes NO reply-mode line (never silently coerced to a default).
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + "maybe\n",
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertNotEquals(0, run.rc, "an invalid reply-mode answer must fail:\n" + run.output);
        assertTrue(run.output.contains("FAIL"),
                "the failure must be loud:\n" + run.output);
        assertTrue(run.output.contains("native or translate"),
                "the failure must name the valid answers:\n" + run.output);
        assertFalse(parseProps(runtimeDir(tmp).resolve("application.properties"))
                        .containsKey("infochat.chat.reply-mode"),
                "an invalid answer must never be silently coerced to a default");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void replyModeRerunDefaultsToRecommendationAndDisclosesCurrent(@TempDir Path tmp) throws Exception {
        // M1-895 P5: the ask fires on every re-run; a pre-seeded native is
        // disclosed in the output and a bare Enter rewrites the recommendation
        // — a stale value never survives, and set_prop keeps one key line.
        Path runtime = Files.createDirectories(runtimeDir(tmp));
        Files.writeString(runtime.resolve("application.properties"),
                "quarkus.profile=vps\ninfochat.chat.reply-mode=native\n");

        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));

        assertEquals(0, run.rc, "the re-run drive must succeed:\n" + run.output);
        assertTrue(run.output.contains("currently set: native"),
                "the output must disclose the currently-set value:\n" + run.output);
        assertEquals("translate",
                parseProps(runtime.resolve("application.properties")).get("infochat.chat.reply-mode"),
                "a bare Enter must take the recommendation, not the stale value");
        assertEquals(1,
                Files.readAllLines(runtime.resolve("application.properties")).stream()
                        .filter(l -> l.startsWith("infochat.chat.reply-mode=")).count(),
                "set_prop idempotency must keep exactly one reply-mode line");
    }

    // --- M1-909: GPU-class spec-decode head offer --------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void gpuClassSpecDecodeOfferFetchesHeadAndMintsSecrets(@TempDir Path tmp) throws Exception {
        // M1-909 reproduction (wizard half): a GPU-class drive answering the offer
        // with an operator URL + SHA takes draft-mtp/4 defaults, HEAD-preflights,
        // fetch_ggufs before the up, mints five secrets (P3: no shipped default).
        String headUrl = "https://models.example.test/my-draft-head.gguf";
        String headSha = "0000000000000000000000000000000000000000000000000000000000000000";
        // stdin: backend, gen=Enter, emb=Enter, embgguf=Enter, offer=headUrl,
        // SHA, type=Enter (draft-mtp), n-max=Enter (4), timing (4× Enter), reply.
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n" + headUrl + "\n" + headSha + "\n\n\n"
                        + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on", "FAKE_DOCKER_PROBE_ABSENT", "1"));

        assertEquals(0, run.rc, "the spec-decode offer drive must succeed:\n" + run.output);
        String curlLog = Files.readString(tmp.resolve("curl-argv.log"));
        assertTrue(curlLog.contains("-fsSLI") && curlLog.contains(headUrl),
                "the head URL must be HEAD-preflighted before the download:\n" + curlLog);

        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        String headDownload = argv.lines()
                .filter(l -> l.contains("-fL") && l.contains(headUrl))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the head must download via fetch_gguf:\n" + argv));
        assertTrue(containsToken(headDownload, "-u 0:0") && containsToken(headDownload, "--network host")
                        && containsToken(headDownload, "-v infochat-llamacpp-models:/models"),
                "the head download must reuse fetch_gguf's argv-only posture (no second download helper):\n"
                        + headDownload);
        assertTrue(argv.indexOf(headDownload) < argv.indexOf("up -d llamacpp"),
                "the head download must precede `up -d llamacpp` (write-before-boot, P8):\n" + argv);

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF=\"my-draft-head.gguf\""),
                "the head filename must be minted:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF_URL=\"" + headUrl + "\""),
                "the head URL must be persisted for restore recovery:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF_SHA=\"" + headSha + "\""),
                "the head SHA must be persisted:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_SPEC_TYPE=\"draft-mtp\""),
                "the Enter-default spec type must be minted:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_SPEC_N_MAX=\"4\""),
                "the Enter-default n-max must be minted:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void gpuClassSpecDecodeDeclinedWritesNoSecrets(@TempDir Path tmp) throws Exception {
        // P3 off-default: a GPU-class drive answering the offer with bare Enter
        // writes NO spec secret and no head download; the printed offer names
        // "off" as the default. The shipped repo picks no head.
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on"));

        assertEquals(0, run.rc, "the declined offer drive must succeed:\n" + run.output);
        assertTrue(run.output.contains("default off"),
                "the printed offer must name 'off' as the default:\n" + run.output);
        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertFalse(secrets.lines().anyMatch(l -> l.startsWith("INFOCHAT_LLAMACPP_SPEC_")),
                "a declined offer must write NO spec secret:\n" + secrets);
        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argv.lines().anyMatch(l -> l.contains("-fL")),
                "no head download may run when the offer is declined:\n" + argv);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void forcedGpuOffWritesNoSpecDecodeSecrets(@TempDir Path tmp) throws Exception {
        // P5 CPU-side absence twin: the offer must fire ONLY on the GPU branch
        // — its stdin sits among all-Enter answers, so a CPU leak shifts reads
        // without tripping any drive; only this absence assertion catches it.
        Map<String, String> props = runWizard(tmp,
                "llamacpp\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "off"));
        assertEquals(GEN_GGUF, props.get("infochat.llm.chat.model"),
                "sanity: the CPU-class drive must complete the ordinary flow");
        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertFalse(secrets.lines().anyMatch(l -> l.startsWith("INFOCHAT_LLAMACPP_SPEC_")),
                "a CPU-class drive must never see the offer or a spec secret:\n" + secrets);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void specDecodeHeadStagedFromLocalPathPersistsEmptyUrl(@TempDir Path tmp) throws Exception {
        // P7 staged-head twin: an absolute-path answer stages via stage_gguf,
        // persists an EMPTY head URL (a host path is not re-fetchable), never
        // persists the host path, and prints the staged-source disclosure.
        Path head = tmp.resolve("operator-draft-head.gguf");
        Files.writeString(head, "fake head bytes");
        // stdin: ..., offer=head path, SHA=Enter (blank), type=Enter, n-max=Enter.
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n" + head.toAbsolutePath() + "\n\n\n\n"
                        + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on", "FAKE_DOCKER_PROBE_ABSENT", "1"));

        assertEquals(0, run.rc, "the staged-head drive must succeed:\n" + run.output);
        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        String cp = argv.lines()
                .filter(l -> l.contains("--entrypoint cp"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the head must stage via stage_gguf's cp:\n" + argv));
        assertTrue(containsToken(cp, "-u 0:0")
                        && cp.contains("-v " + MODEL_VOLUME + ":/models")
                        && cp.contains("-v " + head.getParent().toAbsolutePath() + ":/stage:ro")
                        && cp.contains(CURL_IMAGE)
                        && cp.endsWith("/models/" + head.getFileName()),
                "the head staging must reuse stage_gguf's argv-only read-only-mount posture:\n" + cp);
        assertFalse(cp.contains("bash") || cp.contains("--entrypoint sh"),
                "the cp must stay argv-only — no shell:\n" + cp);

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF=\"" + head.getFileName() + "\""),
                "the staged head filename must be minted:\n" + secrets);
        assertTrue(secrets.contains("INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF_URL=\"\""),
                "a staged head must persist an EMPTY URL:\n" + secrets);
        assertFalse(secrets.contains(head.toAbsolutePath().toString()),
                "the host path must NEVER be persisted into secrets.env:\n" + secrets);
        String disclosure = run.output.lines()
                .filter(l -> l.contains("not re-fetchable"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the staged head must print the disclosure:\n" + run.output));
        assertTrue(disclosure.contains(head.getFileName().toString()),
                "the disclosure must name the staged head's basename:\n" + disclosure);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void specDecodeHeadShaMismatchFailsAndRemoves(@TempDir Path tmp) throws Exception {
        // P12 FAILURE-MODE: a non-empty SHA mismatch fails the wizard and
        // removes the head — through fetch_gguf's own mismatch path (the fake
        // docker's wildcard digest cannot match 'f'*64), never a second helper.
        String headUrl = "https://models.example.test/bad-sha-head.gguf";
        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n" + headUrl + "\n" + "f".repeat(64) + "\n\n\n"
                        + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on", "FAKE_DOCKER_PROBE_ABSENT", "1"));

        assertNotEquals(0, run.rc, "a head SHA mismatch must fail the wizard:\n" + run.output);
        assertTrue(run.output.contains("checksum mismatch"),
                "the failure must name the checksum mismatch:\n" + run.output);
        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        String headDownload = argv.lines()
                .filter(l -> l.contains("-fL") && l.contains(headUrl))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the mismatch drive must download via fetch_gguf first:\n" + argv));
        assertTrue(containsToken(headDownload, "-u 0:0") && containsToken(headDownload, "--network host"),
                "the head fetch must run through fetch_gguf's exact argv shape:\n" + headDownload);
        assertTrue(argv.contains("--entrypoint rm"),
                "the mismatched head must be removed from the volume:\n" + argv);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void specDecodeNMaxRejectsNonInteger(@TempDir Path tmp) throws Exception {
        // P11 FAILURE-MODE: a non-integer n-max fails loud (prompt_timing
        // shape) BEFORE any head fetch; a junk head answer fails AT THE PROMPT
        // with no curl/docker invocation recorded for it.
        WizardRun nmax = runWizardCapture(tmp,
                "llamacpp\n\n\n\nhttps://models.example.test/head.gguf\n\n\nabc\n"
                        + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on"));
        assertNotEquals(0, nmax.rc, "a non-integer n-max must fail the wizard:\n" + nmax.output);
        assertTrue(nmax.output.contains("must be a positive integer (got 'abc')"),
                "the failure must use the prompt_timing shape:\n" + nmax.output);
        String argv = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argv.lines().anyMatch(l -> l.contains("-fL")),
                "the head must not be fetched before the n-max validation passes:\n" + argv);

        Files.deleteIfExists(tmp.resolve("docker-argv.log"));
        Files.deleteIfExists(tmp.resolve("curl-argv.log"));
        WizardRun junk = runWizardCapture(tmp,
                "llamacpp\n\n\n\nnot-a-head-answer\n\n\n\n"
                        + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on"));
        assertNotEquals(0, junk.rc, "a junk head answer must abort the wizard at the prompt:\n" + junk.output);
        assertTrue(junk.output.contains("not-a-head-answer")
                        && junk.output.contains("download URL")
                        && junk.output.contains("absolute path"),
                "the failure must name the answer and the accepted forms (the nonUrlNonFile shape):\n" + junk.output);
        String junkArgv = Files.readString(tmp.resolve("docker-argv.log"));
        String junkCurl = Files.readString(tmp.resolve("curl-argv.log"));
        assertFalse(junkArgv.contains("not-a-head-answer") || junkCurl.contains("not-a-head-answer"),
                "no docker or curl invocation may carry the rejected answer:\n" + junkArgv + junkCurl);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void specDecodeDeclineOnRerunClearsStaleSpecKeys(@TempDir Path tmp) throws Exception {
        // bare Enter means OFF, so a re-run against a runtime whose
        // secrets.env still carries a prior accepted offer's five keys
        // must clear them — else "press Enter for off" keeps the old head on.
        Path runtime = Files.createDirectories(runtimeDir(tmp));
        Files.writeString(runtime.resolve("secrets.env"),
                "INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF=\"stale-head.gguf\"\n"
                        + "INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF_URL=\"https://models.example.test/stale-head.gguf\"\n"
                        + "INFOCHAT_LLAMACPP_SPEC_DRAFT_GGUF_SHA=\"\"\n"
                        + "INFOCHAT_LLAMACPP_SPEC_TYPE=\"draft-mtp\"\n"
                        + "INFOCHAT_LLAMACPP_SPEC_N_MAX=\"4\"\n");

        WizardRun run = runWizardCapture(tmp,
                "llamacpp\n\n\n\n\n" + ACCEPT_TIMING_DEFAULTS + ACCEPT_REPLYMODE_DEFAULT,
                Map.of("INFOCHAT_LLAMACPP_GPU", "on"));

        assertEquals(0, run.rc, "the declined re-run drive must succeed:\n" + run.output);
        assertTrue(run.output.contains("speculative decoding off"),
                "the decline must print its confirmation:\n" + run.output);
        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertFalse(secrets.lines().anyMatch(l -> l.startsWith("INFOCHAT_LLAMACPP_SPEC_")),
                "a declined re-run must clear the stale spec keys:\n" + secrets);
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
        return runWizardCapture(tmp, stdin, extraEnv, new String[0]);
    }

    /** Drive the wizard with optional script arguments, returning the raw outcome. */
    private WizardRun runWizardCapture(Path tmp, String stdin, Map<String, String> extraEnv, String... scriptArgs)
            throws Exception {
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

        List<String> command = new ArrayList<>(2 + scriptArgs.length);
        command.add("bash");
        command.add(repoRoot.resolve("prod/scripts/4-llm.sh").toString());
        command.addAll(List.of(scriptArgs));
        ProcessBuilder pb = new ProcessBuilder(command);
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
     *
     * <p>GPU-gate stubs (M1-827): {@code info} prints rootless only under {@code FAKE_DOCKER_ROOTLESS};
     * {@code exec --list-devices} lists a fake device unless FAKE_DOCKER_NO_DEVICES[_EMBED] empties it.
     */
    private String fakeDockerScript() {
        return "#!/usr/bin/env bash\n"
                + "printf '%s\\n' \"$*\" >> \"$FAKE_DOCKER_ARGV\"\n"
                + "sub=\"$1\"\n"
                + "if [ \"$sub\" = \"info\" ]; then\n"
                + "  [ -n \"${FAKE_DOCKER_ROOTLESS:-}\" ] && echo 'name=rootless'\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [ \"$sub\" = \"compose\" ]; then\n"
                + "  case \"$*\" in\n"
                + "    *\"--list-devices\"*)\n"
                + "      if [ -z \"${FAKE_DOCKER_NO_DEVICES:-}\" ] \\\n"
                + "         && { [ -z \"${FAKE_DOCKER_NO_EMBED_DEVICES:-}\" ] || [[ \"$*\" != *llamacpp-embeddings* ]]; }; then\n"
                + "        echo 'Vulkan0: fake render device (RADV FAKE)'\n"
                + "      fi\n"
                + "      exit \"${FAKE_DOCKER_EXEC_EXIT:-0}\"\n"
                + "      ;;\n"
                + "  esac\n"
                + "  exit 0\n"
                + "fi\n"
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
