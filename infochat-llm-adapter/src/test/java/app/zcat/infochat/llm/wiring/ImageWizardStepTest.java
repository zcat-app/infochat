package app.zcat.infochat.llm.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the 4b-image.sh picker output split (M1-829): default output leads with
 * the six-row decision table (one-line provenance header, one-line hardware
 * scope, one-line --verbose pointer), while the audit detail — the
 * 2026-08-09-spike provenance preamble, latency footnotes, disk-arithmetic
 * formulas and the full hardware-scope block — moves behind {@code --verbose},
 * verbatim (strict superset).
 *
 * <p>The M1-798 honesty basis stays in DEFAULT output: all six curated options,
 * the container-measured latencies (4.07 / 22.37 / 22.41), the per-model disk
 * figures, the Z-Image steady-state marker (inline in its row), and the Krea
 * licence disclosure before any download.
 *
 * <p>Drives the real script with fake {@code docker}/{@code curl} on
 * {@code PATH} and a temp {@code INFOCHAT_RUNTIME_DIR}, on the
 * {@code LlamacppWiringTest} harness pattern (M1-829 analysis ground truth).
 * Linux-gated: the script uses GNU {@code sed -i} and bash.
 */
class ImageWizardStepTest {

    private static final String SCRIPT = "prod/scripts/4b-image.sh";

    private static final String HEADER =
            "Curated models — three models x two tiers, hardcoded; latency + disk measured, pre-commit.";
    private static final String HARDWARE_ONE_LINE =
            "Hardware scope: LOCAL is ROCm-only, validated on Strix Halo (gfx1151) alone — NVIDIA not covered.";
    private static final String VERBOSE_POINTER =
            "Detail (spike sourcing, latency footnotes, disk arithmetic): run with --verbose.";
    private static final String STEADY_STATE_CELL = "22.37 s (steady state)";

    // The detail lines print_picker moves behind --verbose — the old default's
    // audit block, kept verbatim (analysis P7 strict-superset rule).
    private static final List<String> MOVED_DETAIL = List.of(
            // provenance preamble (4)
            "Curated models — three models x two tiers, hardcoded (never a raw repo",
            "listing). Latency: container-measured steady state (2026-08-09 spike,",
            "M1-797 protocol). Disk: measured checkpoint + encoder + VAE footprint,",
            "printed BEFORE you commit.",
            // latency footnotes (3)
            "Z-Image's 22.37 s is the steady state — a first run takes ~28.6 s while",
            "kernels autotune (the probe warm-up absorbs that). Krea's number is the",
            "0.6 MP sampling budget; its decode stage delivers 1792x1344 (~2.4 MP).",
            // disk arithmetic (7)
            "Per-model disk (bf16 tier): Mage-Flow 7.7 + 8.3 (qwen3vl_4b) + 0.33 = ~16.5 GB;",
            "Z-Image 12 + 7.5 (qwen_3_4b) + 0.32 = ~20 GB; Krea 2 25 + 8.3 (qwen3vl_4b)",
            "+ 0.24 (stock VAE) + 0.51 (krea2RealVae) + 0.51 (Wan2.1 2x VAE) = ~34.5 GB.",
            "Smaller-footprint tier: ~13 / ~11.5 / ~20 GB — same speed on this hardware",
            "(quantization buys no speed on gfx1151), slight quality cost. The qwen3vl_4b",
            "encoder is shared between Mage-Flow and Krea 2 (identical blob) — installing",
            "both downloads it once.",
            // hardware scope (3)
            "Hardware scope: the LOCAL container path is ROCm-only and validated on",
            "Strix Halo (gfx1151) alone — other ROCm GPUs are unverified and NVIDIA is",
            "not covered by the overlay.");

    private static final String KREA_REALVAE_FILE = "krea2RealVae_v10.safetensors";
    private static final String KREA_2X_VAE_FILE = "Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors";

    // Conda-measured numbers M1-798's honesty basis bans from picker output
    // (its round-1 REWORK replaced them with container measurements).
    private static final List<String> CONDA_NUMBERS = List.of("22.14", "22.54", "53.07");

    // --- Reproduction (M1-829) -----------------------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void pickerLeadsWithDecisionTableNotAuditDetail(@TempDir Path tmp) throws Exception {
        WizardRun def = drive(tmp, "--dry-run", "");
        assertEquals(0, def.rc, "4b-image.sh --dry-run must exit 0:\n" + def.output);
        List<String> lines = def.output.lines().toList();

        // The picker leads with the table: one-line provenance header, then the
        // six-row decision table with the steady-state marker inline in the
        // Z-Image row, then one-line hardware scope and one-line --verbose pointer.
        assertTrue(lines.contains(HEADER), "default picker must lead with the one-line provenance header:\n" + def.output);
        int tableIdx = firstIndex(lines, l -> l.startsWith("  #    Model"));
        assertTrue(tableIdx >= 0, "default picker must print the decision table:\n" + def.output);
        assertTrue(firstIndex(lines, l -> l.contains("1)   Mage-Flow Turbo")) > tableIdx,
                "the six-row table must follow the header immediately (table first):\n" + def.output);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Z-Image Turbo") && l.contains(STEADY_STATE_CELL)),
                "the Z-Image steady-state marker must ride inline in the row (M1-798 item 3):\n" + def.output);
        assertTrue(lines.contains(HARDWARE_ONE_LINE), "default picker must keep the one-line hardware scope:\n" + def.output);
        assertTrue(lines.contains(VERBOSE_POINTER), "default picker must carry the one-line --verbose pointer:\n" + def.output);

        // The audit detail appears ONLY under --verbose (moved verbatim).
        assertTrue(MOVED_DETAIL.stream().noneMatch(def.output::contains),
                "default --dry-run output must not contain the moved detail lines:\n" + def.output);

        WizardRun verb = drive(tmp, "--dry-run --verbose", "");
        assertEquals(0, verb.rc, "4b-image.sh --dry-run --verbose must exit 0:\n" + verb.output);
        for (String detail : MOVED_DETAIL) {
            assertTrue(verb.output.contains(detail), "--verbose must carry the moved line verbatim:\n" + detail);
        }
        // The moved block keeps its internal order.
        List<String> vLines = verb.output.lines().toList();
        int prev = -1;
        for (String detail : MOVED_DETAIL) {
            int idx = vLines.indexOf(detail);
            assertTrue(idx > prev, "moved detail lines must keep their original order: " + detail);
            prev = idx;
        }
        assertTrue(vLines.contains(HEADER) && vLines.contains(VERBOSE_POINTER),
                "--verbose output must keep the one-line header/pointer:\n" + verb.output);
    }

    // --- Honesty basis survives (analysis P1/P2; M1-798 acceptance items) -----

    @Test
    @EnabledOnOs(OS.LINUX)
    void dryRunDefaultKeepsDecisionTableAndNumbers(@TempDir Path tmp) throws Exception {
        WizardRun run = drive(tmp, "--dry-run", "");
        assertEquals(0, run.rc, "4b-image.sh --dry-run must exit 0:\n" + run.output);

        for (String row : new String[] {
                "1)   Mage-Flow Turbo  — Recommended (bf16)",
                "2)   Mage-Flow Turbo  — Smaller footprint",
                "3)   Z-Image Turbo   — Recommended (bf16)",
                "4)   Z-Image Turbo   — Smaller footprint",
                "5)   Krea 2 Turbo    — Recommended (bf16)",
                "6)   Krea 2 Turbo    — Smaller footprint"}) {
            assertTrue(run.output.contains(row), "all six curated options must stay in default output:\n" + run.output);
        }
        for (String number : new String[] {"4.07 s", "22.37 s", "22.41 s"}) {
            assertTrue(run.output.contains(number), "container-measured latency must stay in default output:\n" + run.output);
        }
        for (String disk : new String[] {"~16.5 GB", "~13 GB", "~20 GB", "~11.5 GB", "~34.5 GB"}) {
            assertTrue(run.output.contains(disk), "per-model disk figures must stay in default output:\n" + run.output);
        }
        assertTrue(run.output.contains(STEADY_STATE_CELL),
                "the Z-Image steady-state marker must stay in default output (M1-798 item 3):\n" + run.output);
        for (String conda : CONDA_NUMBERS) {
            assertFalse(run.output.contains(conda),
                    "a conda-measured number must never appear in picker output (M1-798 REWORK):\n" + run.output);
        }
    }

    // --- Krea licence disclosure (analysis P1; M1-798 P27) --------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void kreaLicenceDisclosureSurvivesCompaction(@TempDir Path tmp) throws Exception {
        // The local drive passes the physical hardware gate (4b-image.sh:789);
        // skip explicitly on hosts without the AMD ROCm device nodes (r1 finding 1).
        Assumptions.assumeTrue(Files.exists(Path.of("/dev/kfd")) && Files.exists(Path.of("/dev/dri")),
                "local-install drive requires the AMD ROCm device nodes (/dev/kfd + /dev/dri)");
        // Krea local path: mode=local, model=5 (krea_bf16), decode=1 (spacepxl 2x),
        // translate=\n (bare Enter — the per-model recommendation, D78).
        WizardRun run = drive(tmp, "", "local\n5\n1\n\n");
        assertEquals(0, run.rc, "the Krea local drive must exit 0:\n" + run.output);

        int head = run.output.indexOf("+ HEAD");
        assertTrue(head >= 0, "the local path must preflight with HEAD checks:\n" + run.output);
        String preDownload = run.output.substring(0, head);

        assertTrue(preDownload.contains("COMMUNITY ASSET LICENCES"),
                "the licence disclosure must print BEFORE any download:\n" + preDownload);
        for (String fact : new String[] {
                KREA_REALVAE_FILE,                                   // both community VAE filenames
                KREA_2X_VAE_FILE,
                "licence UNDECLARED",                                // the label itself
                "Apache-2.0",                                        // the spacepxl card note
                "MIT"}) {                                            // the ComfyUI-VAE-Utils node line
            assertTrue(preDownload.contains(fact),
                    "the licence disclosure must keep fact '" + fact + "' in default pre-download output:\n" + preDownload);
        }

        // Script-order probe: in the local branch the licence print precedes the
        // first head_check call (M1-798 P27 control order).
        String script = Files.readString(repoRoot().resolve(SCRIPT));
        int modeCase = script.indexOf("case \"$mode\" in");
        int remoteCase = script.indexOf("\n  remote)", modeCase);
        String localBlock = script.substring(modeCase, remoteCase);
        assertTrue(localBlock.indexOf("print_krea_asset_licences") < localBlock.indexOf("head_check"),
                "print_krea_asset_licences must be called before the first head_check in the local branch:\n" + localBlock);
    }

    // --- Output-only flag (analysis P7) ---------------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void verboseIsOutputSupersetDefaultFlowUnchanged(@TempDir Path tmp) throws Exception {
        // The local drive passes the physical hardware gate (4b-image.sh:789);
        // skip explicitly on hosts without the AMD ROCm device nodes (r1 finding 1).
        Assumptions.assumeTrue(Files.exists(Path.of("/dev/kfd")) && Files.exists(Path.of("/dev/dri")),
                "local-install drive requires the AMD ROCm device nodes (/dev/kfd + /dev/dri)");
        // One runtime dir for both drives: the script prints its own runtime path
        // in the disk-check line, which must be identical across the two runs.
        // The default drive's written config is deleted before the --verbose drive
        // so the second run sees a fresh install (no re-run prompt).
        String stdin = "local\n1\n\n"; // mode=local, model=1 (mage_bf16), translate=\n (recommendation, D78)
        WizardRun def = drive(tmp, "", stdin);
        Map<String, String> defaultProps = parseProps(runtimeDir(tmp).resolve("application.properties"));
        Files.delete(runtimeDir(tmp).resolve("application.properties"));
        WizardRun verb = drive(tmp, "--verbose", stdin);
        Map<String, String> verboseProps = parseProps(runtimeDir(tmp).resolve("application.properties"));

        assertEquals(0, def.rc, "the default drive must exit 0:\n" + def.output);
        assertEquals(0, verb.rc, "the --verbose drive must exit 0:\n" + verb.output);

        // Identical written application.properties — the flag gates printing only.
        assertEquals(defaultProps, verboseProps,
                "with and without --verbose the written config must be identical");

        // The verbose output is the default output with the moved detail block
        // inserted: removing the block's span leaves the default output exactly.
        // The two host-measurement lines (disk/memory checks) carry live numbers
        // that drift between the two runs — normalized before the comparison.
        List<String> a = normalizeHostChecks(def.output).lines().toList();
        List<String> b = normalizeHostChecks(verb.output).lines().toList();
        int start = b.indexOf(MOVED_DETAIL.get(0));
        int end = b.lastIndexOf(MOVED_DETAIL.get(MOVED_DETAIL.size() - 1));
        assertTrue(start >= 0 && end > start,
                "--verbose must carry the whole moved detail block:\n" + verb.output);
        List<String> bWithoutBlock = new java.util.ArrayList<>(b.subList(0, start));
        bWithoutBlock.addAll(b.subList(end + 1, b.size()));
        assertEquals(a, bWithoutBlock,
                "--verbose must change NOTHING but the inserted detail block (identical prompts, order, flow):\n"
                        + "default:\n" + def.output + "\nverbose:\n" + verb.output);
    }

    // --- Input validation (analysis P2) ---------------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void unknownFlagStillExits2(@TempDir Path tmp) throws Exception {
        WizardRun run = drive(tmp, "--bogus", "");
        assertEquals(2, run.rc, "an unknown flag must exit 2 with usage on stderr:\n" + run.output);
        assertTrue(run.output.contains("Usage: 4b-image.sh"), "usage must print on a bad flag:\n" + run.output);
        assertTrue(run.output.contains("--verbose"), "usage() must document --verbose:\n" + run.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void scriptPassesBashN(@TempDir Path tmp) throws Exception {
        Process p = new ProcessBuilder("bash", "-n", repoRoot().resolve(SCRIPT).toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "bash -n must pass:\n" + output);
    }

    // --- helpers (LlamacppWiringTest harness pattern) -------------------------

    /**
     * Drive 4b-image.sh with fake docker/curl on PATH and a temp runtime dir
     * carrying quarkus.profile=laptop (local install offered, Krea path driveable).
     */
    private WizardRun drive(Path tmp, String args, String stdin) throws Exception {
        Path runtime = Files.createDirectories(runtimeDir(tmp));
        Path propsFile = runtime.resolve("application.properties");
        if (!Files.exists(propsFile)) {
            Files.writeString(propsFile, "quarkus.profile=laptop\n");
        }

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, fakeDockerScript());
        fakeDocker.toFile().setExecutable(true);
        Path fakeCurl = bin.resolve("curl");
        Files.writeString(fakeCurl, fakeCurlScript());
        fakeCurl.toFile().setExecutable(true);

        String[] argv = args.isBlank() ? new String[0] : args.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot().resolve(SCRIPT).toString());
        pb.command().addAll(List.of(argv));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", ""));

        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        return new WizardRun(rc, output);
    }

    /**
     * Minimal fake docker: no-ops compose / volume / run / info, and answers
     * the compose-exec ETA probe and healthcheck with a steady-state mean so the
     * local drive completes and writes config. No real container ever runs.
     */
    private String fakeDockerScript() {
        return "#!/usr/bin/env bash\n"
                + "sub=\"$1\"\n"
                + "if [ \"$sub\" = \"compose\" ]; then\n"
                + "  for a in \"$@\"; do\n"
                + "    [ \"$a\" = \"exec\" ] && { echo \"22.35\"; exit 0; }\n"
                + "  done\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [ \"$sub\" = \"run\" ] || [ \"$sub\" = \"volume\" ] || [ \"$sub\" = \"info\" ]; then exit 0; fi\n"
                + "exit 0\n";
    }

    /** Minimal fake curl: no real egress (P9 precedent). */
    private String fakeCurlScript() {
        return "#!/usr/bin/env bash\n"
                + "exit 0\n";
    }

    private Path runtimeDir(Path tmp) {
        return tmp.resolve("runtime");
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

    private int firstIndex(List<String> lines, java.util.function.Predicate<String> test) {
        for (int i = 0; i < lines.size(); i++) {
            if (test.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Live host-measurement lines (disk/memory checks) differ between two runs; pin their shape, not the numbers. */
    private String normalizeHostChecks(String output) {
        return output.replaceAll("(?m)\\+ disk check: need ~\\d+ GB, \\d+ GB available at \\S+",
                        "+ disk check: need ~N GB, N GB available at <path>")
                .replaceAll("(?m)\\+ memory/VRAM check: need ~\\d+ GB, \\d+ GB available",
                        "+ memory/VRAM check: need ~N GB, N GB available");
    }

    /** The outcome of a wizard drive that does not assert the exit code itself. */
    private record WizardRun(int rc, String output) {}
}
