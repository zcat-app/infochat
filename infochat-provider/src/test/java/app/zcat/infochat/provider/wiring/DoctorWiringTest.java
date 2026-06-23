package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the wizard step-0 preflight {@code prod/scripts/0-doctor.sh} (M1-439) — the
 * gate that refuses to let the setup wizard proceed on a host that cannot run the
 * containerized prod stack.
 *
 * <p>The behaviour this test exists to guard is the aggregate, self-remediating
 * contract: the doctor must run EVERY check, report ALL unmet ones in a single run
 * (not exit at the first), attach an actionable remedy to each, and — critically —
 * never silently pass a check it could not actually verify (the {@code ss}-absent
 * port-check false-pass hazard at {@code 0-doctor.sh}, the original line 67).
 *
 * <p>The harness drives the REAL script via {@link ProcessBuilder} with a PATH
 * restricted to a controlled bin directory: real {@code bash}/{@code uname}/etc.
 * symlinked in, plus parametrized fake {@code docker}/{@code ss}/{@code df} whose
 * behaviour is steered by environment variables, and the presence/absence of a
 * tool expressed by whether its fake is created. This mirrors the established
 * prod-script wiring precedent (SwitchLlmWiringTest, SimpleXProvisioningWiringTest).
 * Linux-gated: the wizard targets Linux only and the script uses GNU/bash idioms.
 */
class DoctorWiringTest {

    // Coreutils the script genuinely invokes; symlinked from the real host into the
    // controlled bin so a restricted PATH still lets the script run while letting us
    // control the presence of docker/ss/df. grep is real (the port check pipes ss
    // into it); openssl/curl are presence-checked only, so they are cheap fakes.
    private static final String[] REAL_TOOLS = {"bash", "uname", "dirname", "grep", "awk"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "case \"$1\" in\n"
            + "  info)\n"
            + "    [[ \"${FAKE_DAEMON:-up}\" == down ]] && exit 1\n"
            + "    [[ \"$2\" == \"--format\" ]] && echo \"/var/lib/docker\"\n"
            + "    exit 0 ;;\n"
            + "  compose)\n"
            + "    [[ \"${FAKE_COMPOSE:-v2}\" == absent ]] && exit 1\n"
            + "    echo \"Docker Compose version v2.20.0\"; exit 0 ;;\n"
            + "  *) exit 0 ;;\n"
            + "esac\n";

    private static final String FAKE_SS =
            "#!/usr/bin/env bash\n"
            + "[[ \"${FAKE_PORT_BUSY:-no}\" == yes ]] && echo \"LISTEN 0 128 0.0.0.0:5432 0.0.0.0:*\"\n"
            + "exit 0\n";

    private static final String FAKE_DF =
            "#!/usr/bin/env bash\n"
            + "echo \"Filesystem 1024-blocks Used Available Capacity Mounted on\"\n"
            + "echo \"fakefs 999999999 0 ${FAKE_DISK_KB:-100000000} 1% /\"\n";

    private static final String FAKE_NOOP = "#!/usr/bin/env bash\nexit 0\n";

    // --- all-good: exit 0 with the success line (acceptance item 6c) -------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void allChecksPassExitsZeroWithSuccessLine(@TempDir Path tmp) throws Exception {
        RunResult r = runDoctor(tmp, new Scenario());

        assertEquals(0, r.exitCode, "an all-good host must exit 0:\n" + r.output);
        assertTrue(r.output.contains("doctor: all preflight checks passed."),
                "an all-good run must print the success line:\n" + r.output);
        assertFalse(r.output.contains("FAIL:"),
                "an all-good run must report no failures:\n" + r.output);
    }

    // --- aggregate: all simultaneous failures reported once, each with a remedy --
    //     (acceptance items 6a + 6b)

    @Test
    @EnabledOnOs(OS.LINUX)
    void multipleSimultaneousFailuresAreAllReportedWithRemediesInOneRun(@TempDir Path tmp) throws Exception {
        Scenario broken = new Scenario();
        broken.daemonUp = false;     // Docker daemon unreachable
        broken.composeV2 = false;    // Compose plugin missing
        broken.portBusy = true;      // 5432 occupied
        broken.diskKb = 1_000_000;   // ~0 GB free, below the 15 GB floor

        RunResult r = runDoctor(tmp, broken);

        assertNotEquals(0, r.exitCode, "any failed check must make the doctor exit non-zero:\n" + r.output);

        // (a) every failure appears in the single run, not just the first.
        assertTrue(r.output.contains("Docker daemon not reachable"),
                "the daemon failure must be reported:\n" + r.output);
        assertTrue(r.output.contains("Docker Compose v2 not available"),
                "the compose failure must be reported alongside the daemon failure:\n" + r.output);
        assertTrue(r.output.contains("TCP port 5432 is already in use"),
                "the busy-port failure must be reported too:\n" + r.output);
        assertTrue(r.output.contains("GB free on") && r.output.contains("need 15 GB"),
                "the low-disk failure must be reported too:\n" + r.output);

        // (b) each failure carries its actionable remedy, not just the symptom.
        assertTrue(r.output.contains("usermod -aG docker"),
                "the daemon failure must carry the docker-group remedy:\n" + r.output);
        assertTrue(r.output.contains("docker-compose-plugin"),
                "the compose failure must carry the v2-plugin install remedy:\n" + r.output);
        assertTrue(r.output.contains("systemctl stop postgresql"),
                "the busy-port failure must carry a free-the-port remedy:\n" + r.output);
        assertTrue(r.output.contains("free disk space") || r.output.contains("move the Docker data-root"),
                "the low-disk failure must carry a free-space remedy:\n" + r.output);

        assertFalse(r.output.contains("doctor: all preflight checks passed."),
                "a run with failures must not print the success line:\n" + r.output);
    }

    // --- dependency-aware: absent ss => port check UNVERIFIABLE, never a pass -----
    //     (acceptance item 6d / item 3)

    @Test
    @EnabledOnOs(OS.LINUX)
    void absentSsReportsPortCheckUnverifiableNotPassed(@TempDir Path tmp) throws Exception {
        Scenario noSs = new Scenario();
        noSs.ssPresent = false; // every other check is satisfiable; only ss is gone

        RunResult r = runDoctor(tmp, noSs);

        assertNotEquals(0, r.exitCode, "a missing ss must not yield a clean pass:\n" + r.output);
        assertTrue(r.output.contains("required tool 'ss' not found"),
                "ss is a required tool, so its absence is itself a failure:\n" + r.output);
        assertTrue(r.output.contains("TCP port check could not be verified")
                        && r.output.contains("NOT assumed free"),
                "with ss absent the port check must be reported UNVERIFIABLE, never silently passed:\n" + r.output);
        assertFalse(r.output.contains("doctor: all preflight checks passed."),
                "an unverifiable port check must not be reported as all-passed:\n" + r.output);
    }

    // --- helpers ----------------------------------------------------------------

    /** Knobs steering one doctor run; defaults describe an all-good host. */
    private static final class Scenario {
        boolean daemonUp = true;
        boolean composeV2 = true;
        boolean ssPresent = true;
        boolean portBusy = false;
        boolean dfPresent = true;
        long diskKb = 100_000_000L; // ~95 GB, comfortably above the 15 GB floor
    }

    private record RunResult(int exitCode, String output) {}

    /** Drive the real 0-doctor.sh under a controlled PATH; return exit code + combined output. */
    private RunResult runDoctor(Path tmp, Scenario s) throws Exception {
        Path repoRoot = repoRoot();
        Path bin = Files.createDirectories(tmp.resolve("bin"));

        for (String tool : REAL_TOOLS) {
            Files.createSymbolicLink(bin.resolve(tool), realTool(tool));
        }
        writeFake(bin.resolve("docker"), FAKE_DOCKER);
        writeFake(bin.resolve("openssl"), FAKE_NOOP);
        writeFake(bin.resolve("curl"), FAKE_NOOP);
        if (s.ssPresent) {
            writeFake(bin.resolve("ss"), FAKE_SS);
        }
        if (s.dfPresent) {
            writeFake(bin.resolve("df"), FAKE_DF);
        }

        ProcessBuilder pb = new ProcessBuilder(
                bin.resolve("bash").toString(), repoRoot.resolve("prod/scripts/0-doctor.sh").toString());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString()); // restricted: only our controlled bin
        env.put("FAKE_DAEMON", s.daemonUp ? "up" : "down");
        env.put("FAKE_COMPOSE", s.composeV2 ? "v2" : "absent");
        env.put("FAKE_PORT_BUSY", s.portBusy ? "yes" : "no");
        env.put("FAKE_DISK_KB", Long.toString(s.diskKb));

        Process p = pb.start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        return new RunResult(rc, output);
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
