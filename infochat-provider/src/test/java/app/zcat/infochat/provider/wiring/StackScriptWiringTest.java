package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Pins prod/scripts/stack.sh, the full-stack lifecycle verb: one named wrapper owns
 *  the four-profile + comfyui-overlay + env-file assembly, start resumes without ever
 *  recreating, and a never-created stack fails with a setup pointer. Drives the REAL
 *  script under a controlled PATH with an argv-recording fake docker; Linux-gated. */
class StackScriptWiringTest {

    private static final String[] REAL_TOOLS = {"bash", "dirname"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    private static final String[] ALL_PROFILES = {
        "--profile prod", "--profile ollama", "--profile llamacpp", "--profile llamacpp-embeddings"
    };

    // Records every argv on its own line; answers `ps -aq` per the container knob.
    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "echo \"$*\" >> \"$FAKE_DOCKER_LOG\"\n"
            + "if [[ \"$*\" == *\"ps -aq\"* ]]; then\n"
            + "  [[ \"${FAKE_CONTAINERS:-present}\" == present ]] && echo fakecid001\n"
            + "fi\n"
            + "exit 0\n";

    // --- reproduction: stop assembles profiles + overlays + env-file (and never
    //     the gpu overlay, in either direction)

    @Test
    @EnabledOnOs(OS.LINUX)
    void fullStackStopAssemblesProfilesOverlaysAndEnvFile(@TempDir Path tmp) throws Exception {
        RunResult r = runStack(tmp, new Scenario(), "stop");

        assertEquals(0, r.exitCode, "stop over the full assembly must exit 0:\n" + r.output);
        String stopLine = singleLineEndingWith(r.dockerInvocations, " stop");
        Path root = repoRoot();
        assertTrue(stopLine.contains("-f " + root.resolve("docker-compose.yml")),
                "the base compose file must be merged:\n" + stopLine);
        assertTrue(stopLine.contains("-f " + root.resolve("docker-compose.comfyui.yml")),
                "dropping the comfyui overlay leaves a running comfyui behind:\n" + stopLine);
        for (String profile : ALL_PROFILES) {
            assertTrue(stopLine.contains(profile), "missing " + profile + ":\n" + stopLine);
        }
        assertTrue(stopLine.contains("--env-file " + tmp.resolve("runtime").resolve("secrets.env")),
                "the secrets env-file must be passed when present:\n" + stopLine);
        assertTrue(r.dockerInvocations.stream().noneMatch(l -> l.contains("docker-compose.gpu.yml")),
                "the gpu overlay must never be merged (GPU-less-host create break):\n" + r.dockerInvocations);
    }

    // --- start resumes the existing set; it never creates

    @Test
    @EnabledOnOs(OS.LINUX)
    void startNeverRecreates(@TempDir Path tmp) throws Exception {
        RunResult r = runStack(tmp, new Scenario(), "start");

        assertEquals(0, r.exitCode, "start with an existing container set must exit 0:\n" + r.output);
        assertEquals(1, r.dockerInvocations.stream().filter(l -> l.endsWith(" start")).count(),
                "exactly one compose start invocation expected:\n" + r.dockerInvocations);
        assertTrue(r.dockerInvocations.stream().noneMatch(l -> l.contains(" up ") || l.endsWith(" up")),
                "up over four profiles would create both LLM backends plus comfyui:\n" + r.dockerInvocations);
    }

    // --- start with nothing to resume fails with the setup pointer

    @Test
    @EnabledOnOs(OS.LINUX)
    void startWithNoContainersFailsWithSetupPointer(@TempDir Path tmp) throws Exception {
        Scenario empty = new Scenario();
        empty.containersPresent = false;

        RunResult r = runStack(tmp, empty, "start");

        assertNotEquals(0, r.exitCode, "a never-created stack must not start silently:\n" + r.output);
        assertTrue(r.output.contains("./prod/setup.sh"),
                "the failure must point at the setup wizard:\n" + r.output);
        assertTrue(r.dockerInvocations.stream().noneMatch(l -> l.endsWith(" start")),
                "compose start must not run when no containers exist:\n" + r.dockerInvocations);
    }

    // --- M1-389 discipline: no secrets file means no --env-file, verbs still run

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingSecretsFileOmitsTheEnvFileFlag(@TempDir Path tmp) throws Exception {
        Scenario noSecrets = new Scenario();
        noSecrets.secretsPresent = false;

        RunResult last = runStack(tmp, noSecrets, "stop");
        assertEquals(0, last.exitCode, "stop must run without a secrets file:\n" + last.output);
        for (String verb : new String[] {"status", "start", "restart"}) {
            last = runStack(tmp, noSecrets, verb);
            assertEquals(0, last.exitCode, verb + " must run without a secrets file:\n" + last.output);
        }

        // the argv log accumulates across the runs, so the last result sees every verb
        assertTrue(last.dockerInvocations.stream().noneMatch(l -> l.contains("--env-file")),
                "no --env-file flag may be passed when secrets.env is absent:\n" + last.dockerInvocations);
    }

    // --- status uses the same assembly; restart is stop then start

    @Test
    @EnabledOnOs(OS.LINUX)
    void statusUsesTheSameAssembly(@TempDir Path tmp) throws Exception {
        RunResult r = runStack(tmp, new Scenario(), "status");

        assertEquals(0, r.exitCode, "status must exit 0:\n" + r.output);
        String psLine = singleLineEndingWith(r.dockerInvocations, " ps");
        Path root = repoRoot();
        assertTrue(psLine.contains("-f " + root.resolve("docker-compose.yml"))
                        && psLine.contains("-f " + root.resolve("docker-compose.comfyui.yml")),
                "status must merge base + comfyui overlay:\n" + psLine);
        for (String profile : ALL_PROFILES) {
            assertTrue(psLine.contains(profile), "missing " + profile + ":\n" + psLine);
        }

        RunResult restart = runStack(tmp, new Scenario(), "restart");
        assertEquals(0, restart.exitCode, "restart must exit 0:\n" + restart.output);
        int lastStop = lastIndexOfEndingWith(restart.dockerInvocations, " stop");
        int lastStart = lastIndexOfEndingWith(restart.dockerInvocations, " start");
        assertTrue(lastStop >= 0 && lastStart > lastStop,
                "restart must be stop then start:\n" + restart.dockerInvocations);
    }

    // --- helpers ----------------------------------------------------------------

    /** Knobs steering one stack.sh run; defaults describe a provisioned host. */
    private static final class Scenario {
        boolean secretsPresent = true;
        boolean containersPresent = true;
    }

    private record RunResult(int exitCode, String output, List<String> dockerInvocations) {}

    /** Drive the real stack.sh under a controlled PATH; capture argv via the fake docker. */
    private RunResult runStack(Path tmp, Scenario s, String verb) throws Exception {
        Path repoRoot = repoRoot();
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String tool : REAL_TOOLS) {
            Path link = bin.resolve(tool);
            if (!Files.exists(link)) {
                Files.createSymbolicLink(link, realTool(tool));
            }
        }
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        if (s.secretsPresent) {
            Files.writeString(runtime.resolve("secrets.env"), "INFOCHAT_DUMMY=dummy\n");
        }
        Path argvLog = tmp.resolve("docker-argv.log");
        writeFake(bin.resolve("docker"), FAKE_DOCKER);

        ProcessBuilder pb = new ProcessBuilder(
                bin.resolve("bash").toString(), repoRoot.resolve("prod/scripts/stack.sh").toString(), verb);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString());
        env.put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        env.put("FAKE_DOCKER_LOG", argvLog.toString());
        env.put("FAKE_CONTAINERS", s.containersPresent ? "present" : "empty");

        Process p = pb.start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        List<String> invocations = Files.exists(argvLog) ? Files.readAllLines(argvLog) : List.of();
        return new RunResult(rc, output, invocations);
    }

    private String singleLineEndingWith(List<String> lines, String suffix) {
        List<String> matches = lines.stream().filter(l -> l.endsWith(suffix)).toList();
        assertEquals(1, matches.size(), "expected exactly one invocation ending with '" + suffix + "':\n" + lines);
        return matches.getFirst();
    }

    private int lastIndexOfEndingWith(List<String> lines, String suffix) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).endsWith(suffix)) {
                return i;
            }
        }
        return -1;
    }

    private void writeFake(Path path, String body) throws IOException {
        Files.writeString(path, body);
        path.toFile().setExecutable(true);
    }

    /** Resolve a real host tool by scanning the standard bin directories. */
    private Path realTool(String name) {
        for (String dir : TOOL_DIRS) {
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("required host tool not found on standard paths: " + name);
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
