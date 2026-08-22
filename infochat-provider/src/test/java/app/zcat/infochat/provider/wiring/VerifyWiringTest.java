package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Pins the config-freshness leg of {@code prod/scripts/8-verify.sh} (M1-907). */
class VerifyWiringTest {

    // Real coreutils 8-verify.sh invokes on its way through the leg: path
    // resolution (dirname), read_prop (sed/tail), the health-body scans
    // (grep), and the freshness leg's epoch arithmetic (date, stat).
    private static final String[] REAL_TOOLS =
            {"bash", "dirname", "sed", "tail", "grep", "date", "stat", "sleep"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    // The fake answers the health polls (UP bodies), the ollama embedding
    // probe (serving the configured model), and the leg's `compose ps -q` /
    // `docker inspect` pair with CANNED RFC3339Nano StartedAts.
    // FAKE_PS_FAIL / FAKE_INSPECT_FAIL model instrumentation failure;
    // unmodeled invocations are argv-logged for a fail-loud assertion.
    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "printf '%s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\"\n"
            + "if [[ \"$1\" == inspect ]]; then\n"
            + "  [[ \"${FAKE_INSPECT_FAIL:-}\" == 1 ]] && exit 1\n"
            + "  case \"$*\" in\n"
            + "    *cid-collector*) printf '%s\\n' \"${FAKE_STARTED_AT_COLLECTOR:?test sets it}\" ;;\n"
            + "    *cid-provider*) printf '%s\\n' \"${FAKE_STARTED_AT_PROVIDER:?test sets it}\" ;;\n"
            + "    *) printf 'FAKE-DOCKER: UNMODELED inspect invocation: %s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\" ;;\n"
            + "  esac\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == compose ]]; then\n"
            + "  case \"$*\" in\n"
            + "    *\"ps -q infochat-collector\"*)\n"
            + "      [[ \"${FAKE_PS_FAIL:-}\" == 1 ]] && exit 1\n"
            + "      echo \"cid-collector\" ;;\n"
            + "    *\"ps -q infochat-provider\"*)\n"
            + "      echo \"cid-provider\" ;;\n"
            + "    *\"exec -T infochat-collector curl\"*)\n"
            + "      printf '{\"status\": \"UP\"}\\n' ;;\n"
            + "    *\"exec -T infochat-provider curl\"*)\n"
            + "      printf '{\"status\": \"UP\"}\\n' ;;\n"
            + "    *\"exec -T ollama ollama list\"*)\n"
            + "      printf 'NAME              ID      SIZE\\nnomic-embed-text  abc123  274MB\\n' ;;\n"
            + "    *) printf 'FAKE-DOCKER: UNMODELED compose invocation: %s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\" ;;\n"
            + "  esac\n"
            + "  exit 0\n"
            + "fi\n"
            + "printf 'FAKE-DOCKER: UNMODELED docker invocation: %s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\"\n"
            + "exit 0\n";

    // Fixture mtimes are anchored to the canned StartedAts, never the
    // test-run wall clock (the M1-740 rot shape): FRESH predates both
    // container starts, STALE postdates both.
    private static final String COLLECTOR_STARTED_AT = "2026-01-10T12:00:00.123456789Z";
    private static final String PROVIDER_STARTED_AT = "2026-01-10T12:30:00.987654321Z";
    private static final Instant FRESH_MTIME = Instant.parse("2026-01-10T11:00:00Z");
    private static final Instant STALE_MTIME = Instant.parse("2026-01-10T13:00:00Z");

    private static final String GREEN_COLLECTOR = "GREEN     Collector (infochat-collector:8080) — UP";
    private static final String GREEN_PROVIDER = "GREEN     Provider (infochat-provider:8081) — UP";
    private static final String ALL_HEALTHY = "all components healthy.";

    private record RunResult(int exitCode, String output) {}

    @Test
    @EnabledOnOs(OS.LINUX)
    void staleRuntimeFileWarnsAndNamesServiceAndFile(@TempDir Path tmp) throws Exception {
        // The reproduction (M1-907): a config rewrite that lands AFTER a service
        // booted is invisible to that service — config is read at boot only —
        // so a green health poll vouches for the PRE-rewrite file.
        Path runtime = fixtureRuntime(tmp);
        setMtime(runtime.resolve("application.properties"), FRESH_MTIME);
        setMtime(runtime.resolve("secrets.env"), FRESH_MTIME);
        setMtime(runtime.resolve("bootstrap-sources.json"), STALE_MTIME);
        setMtime(runtime.resolve("bootstrap-assets.json"), STALE_MTIME);

        RunResult r = runVerify(tmp);

        assertEquals(0, r.exitCode(), "a stale runtime file is a WARN, never a RED — the exit"
                + " contract is non-zero iff a service never reaches UP:\n" + r.output());

        List<String> sourcesWarns = warnLines(r.output(), "bootstrap-sources.json");
        assertEquals(1, sourcesWarns.size(), "exactly one WARN line per stale file:\n" + r.output());
        String sourcesWarn = sourcesWarns.get(0);
        assertTrue(sourcesWarn.contains("infochat-collector"),
                "the WARN must name the service that reads the file:\n" + sourcesWarn);
        assertFalse(sourcesWarn.contains("infochat-provider"),
                "bootstrap-sources.json is Collector-mounted only (docker-compose.yml) — the"
                        + " Provider must not be named:\n" + sourcesWarn);
        assertTrue(sourcesWarn.contains("newer than the last"),
                "the WARN must state the freshness predicate:\n" + sourcesWarn);
        assertTrue(sourcesWarn.contains("config is read at boot"),
                "the WARN must state the read-at-boot reason:\n" + sourcesWarn);
        assertTrue(sourcesWarn.contains("Restart") && sourcesWarn.contains("8-verify.sh"),
                "the WARN must carry the Restart-and-re-run guidance:\n" + sourcesWarn);

        List<String> assetsWarns = warnLines(r.output(), "bootstrap-assets.json");
        assertEquals(1, assetsWarns.size(), "one WARN per stale file, not per service:\n" + r.output());
        assertTrue(assetsWarns.get(0).contains("infochat-collector")
                && assetsWarns.get(0).contains("infochat-provider"),
                "bootstrap-assets.json is mounted by BOTH services — both must be named:\n"
                        + assetsWarns.get(0));

        assertTrue(r.output().contains(GREEN_COLLECTOR) && r.output().contains(GREEN_PROVIDER),
                "the health legs must still run and report:\n" + r.output());
        assertTrue(r.output().contains("=== deployment health summary ==="),
                "the summary must still print:\n" + r.output());
        assertFalse(r.output().contains(ALL_HEALTHY),
                "the staleness WARNs must suppress the all-healthy line via warn_count:\n" + r.output());
        assertTrue(r.output().contains("warning(s)"),
                "the warn_count summary line must print:\n" + r.output());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void freshRuntimeFilesPrintNoStalenessWarning(@TempDir Path tmp) throws Exception {
        // Every file predates both starts: the restore.sh `cp -p` shape (old
        // bundle mtimes preserved) and the wizard's write-then-boot shape.
        Path runtime = fixtureRuntime(tmp);
        for (String file : List.of("application.properties", "secrets.env",
                "bootstrap-sources.json", "bootstrap-assets.json")) {
            setMtime(runtime.resolve(file), FRESH_MTIME);
        }

        RunResult r = runVerify(tmp);

        assertEquals(0, r.exitCode(), "a fresh runtime dir must verify green:\n" + r.output());
        assertFalse(r.output().lines().anyMatch(l -> l.contains("newer than the last")),
                "no staleness WARN may fire when every file predates both StartedAts:\n" + r.output());
        assertFalse(r.output().lines().anyMatch(l -> l.startsWith("WARN")),
                "a clean fixture (health UP, embedding served) must produce zero WARNs:\n" + r.output());
        assertTrue(r.output().contains(ALL_HEALTHY),
                "the all-healthy line must print when nothing warned:\n" + r.output());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void inspectFailureDegradesToSkipNote(@TempDir Path tmp) throws Exception {
        // P13: an unobservable start time is an instrumentation gap — it must
        // not impersonate a stale config (fabricated WARN) nor a down service.
        Path runtime = fixtureRuntime(tmp);
        for (String file : List.of("application.properties", "secrets.env",
                "bootstrap-sources.json", "bootstrap-assets.json")) {
            setMtime(runtime.resolve(file), FRESH_MTIME);
        }

        RunResult r = runVerify(tmp, Map.of("FAKE_INSPECT_FAIL", "1"));

        assertEquals(0, r.exitCode(), "an instrumentation failure must not change the exit code:\n"
                + r.output());
        assertTrue(r.output().lines().anyMatch(l -> l.contains("config freshness not checked")),
                "the degradation must surface as a one-line skip note:\n" + r.output());
        assertFalse(r.output().lines().anyMatch(l -> l.contains("newer than the last")),
                "no fabricated staleness WARN may print:\n" + r.output());
        assertTrue(r.output().contains(GREEN_COLLECTOR) && r.output().contains(GREEN_PROVIDER),
                "the health summary must be unaffected:\n" + r.output());
        assertTrue(r.output().contains(ALL_HEALTHY),
                "the skip note must not impersonate a warning:\n" + r.output());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void absentOptionalFileIsSkipped(@TempDir Path tmp) throws Exception {
        // Absence is legitimate (the wizard makes bootstrap-assets.json
        // optional; the script already tolerates a missing CONFIG_FILE).
        Path runtime = fixtureRuntime(tmp);
        Files.delete(runtime.resolve("bootstrap-assets.json"));
        for (String file : List.of("application.properties", "secrets.env",
                "bootstrap-sources.json")) {
            setMtime(runtime.resolve(file), FRESH_MTIME);
        }

        RunResult r = runVerify(tmp);

        assertEquals(0, r.exitCode(), "an absent optional file is not an error:\n" + r.output());
        assertFalse(r.output().contains("bootstrap-assets.json"),
                "an absent optional file must yield no line at all:\n" + r.output());
        assertFalse(r.output().lines().anyMatch(l -> l.startsWith("WARN")),
                "no WARN may fire for an absent file:\n" + r.output());
        assertTrue(r.output().contains(ALL_HEALTHY),
                "the all-healthy line must print:\n" + r.output());
    }

    // --- helpers ----------------------------------------------------------------

    private static List<String> warnLines(String output, String needle) {
        return output.lines().filter(l -> l.startsWith("WARN") && l.contains(needle)).toList();
    }

    /** The runtime fixture: the four boot-read files, embedding wired to the
     *  ollama shape the fake answers GREEN so a clean fixture stays WARN-free. */
    private Path fixtureRuntime(Path tmp) throws Exception {
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Files.writeString(runtime.resolve("secrets.env"), "INFOCHAT_DB_PASSWORD=\"pw\"\n");
        Files.writeString(runtime.resolve("application.properties"),
                "quarkus.profile=vps\n"
                        + "infochat.embeddings.base-url=http://ollama:11434/v1\n"
                        + "infochat.embeddings.model=nomic-embed-text\n");
        Files.writeString(runtime.resolve("bootstrap-sources.json"), "[]\n");
        Files.writeString(runtime.resolve("bootstrap-assets.json"), "[]\n");
        return runtime;
    }

    private static void setMtime(Path file, Instant time) throws Exception {
        Files.setLastModifiedTime(file, FileTime.from(time));
    }

    /** Drive the real 8-verify.sh under a controlled PATH; return exit code + combined output. */
    private RunResult runVerify(Path tmp, Map<String, String> extraEnv) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String tool : REAL_TOOLS) {
            Files.createSymbolicLink(bin.resolve(tool), realTool(tool));
        }
        writeFake(bin.resolve("docker"), FAKE_DOCKER);

        ProcessBuilder pb = new ProcessBuilder(
                bin.resolve("bash").toString(),
                repoRoot().resolve("prod/scripts/8-verify.sh").toString());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString()); // restricted: only our controlled bin
        env.put("INFOCHAT_RUNTIME_DIR", tmp.resolve("runtime").toString());
        env.put("FAKE_DOCKER_ARGV_LOG", tmp.resolve("docker-argv.log").toString());
        env.put("FAKE_STARTED_AT_COLLECTOR", COLLECTOR_STARTED_AT);
        env.put("FAKE_STARTED_AT_PROVIDER", PROVIDER_STARTED_AT);
        env.putAll(extraEnv);

        Process p = pb.start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        Path argvLogPath = tmp.resolve("docker-argv.log");
        String argvLog = Files.exists(argvLogPath) ? Files.readString(argvLogPath) : "";
        assertFalse(argvLog.contains("UNMODELED"),
                "the fake docker saw an invocation it does not model:\n" + argvLog);
        return new RunResult(rc, output);
    }

    private RunResult runVerify(Path tmp) throws Exception {
        return runVerify(tmp, Map.of());
    }

    private void writeFake(Path path, String body) throws Exception {
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

    /** Walk up from the module CWD to the repo root (the dir with docker-compose.yml). */
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
