package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Pins the tag-cutover gate of {@code prod/scripts/upgrade.sh}: a RED preflight aborts
 *  BEFORE build/restart naming findings + rulings file + the §7.14 instructions, GREEN
 *  flows through, an unreadable DB fails loud. The RestoreWiringTest seam. Linux-gated. */
@EnabledOnOs(OS.LINUX)
class UpgradeWiringTest {

    // Real coreutils upgrade.sh + backup.sh + tag-tree-cutover.sh invoke on the way
    // through the gates; symlinked into the controlled bin.

    // A restricted PATH still lets the scripts run from the controlled bin alone.
    private static final String[] REAL_TOOLS =
            {"bash", "dirname", "mktemp", "date", "mkdir", "grep", "tail", "sed",
             "sort", "tr", "cat", "chmod", "rm"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    // Clean tracked tree (empty status), one fixed HEAD (rev-parse is identical before
    // and after the pull — the no-op-pull redeploy leg), empty config diff.
    private static final String FAKE_GIT =
            "#!/usr/bin/env bash\n"
            + "case \"$*\" in\n"
            + "  *status*porcelain*) exit 0 ;;\n"
            + "  *rev-parse*) echo 0123456789abcdef0123456789abcdef01234567; exit 0 ;;\n"
            + "  *fetch*) exit 0 ;;\n"
            + "  *pull*) echo \"Already up to date.\"; exit 0 ;;\n"
            + "  *diff*) exit 0 ;;\n"
            + "esac\n"
            + "exit 0\n";

    // Every invocation's argv is appended to FAKE_DOCKER_ARGV_LOG so tests can assert
    // whether build/restart were reached.

    // The cutover gate's container-exec psql probe is parametrized by
    // FAKE_CUTOVER_MODE: red (findings rows), green (empty, default), fail
    // (postgres unreachable). inspect reports healthy; everything else no-ops.
    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "printf '%s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\"\n"
            + "if [[ \"$1\" == inspect ]]; then\n"
            + "  echo healthy\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == run ]]; then\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == compose ]]; then\n"
            + "  case \"$*\" in\n"
            + "    *pg_dump*) exit 0 ;;\n"
            + "    *psql*)\n"
            + "      case \"${FAKE_CUTOVER_MODE:-green}\" in\n"
            + "        red) printf 'tag|ai-image|1\\npost.tags|ai-image|2\\n'; exit 0 ;;\n"
            + "        fail) echo \"psql: could not connect to server: Connection refused\" >&2; exit 1 ;;\n"
            + "        *) exit 0 ;;\n"
            + "      esac ;;\n"
            + "    *\"ps -q\"*) echo fakecid; exit 0 ;;\n"
            + "  esac\n"
            + "  exit 0\n"
            + "fi\n"
            + "exit 0\n";

    private record RunResult(int exitCode, String output) {
    }

    @Test
    void preflightFindingsAbortBeforeBuildAndRestart(@TempDir Path tmp) throws Exception {
        RunResult r = runUpgrade(tmp, "red");

        assertEquals(1, r.exitCode, "a RED preflight aborts the upgrade:\n" + r.output);
        assertTrue(r.output.contains("tag: ai-image (1)"), "the findings print per surface: " + r.output);
        assertTrue(r.output.contains("post.tags: ai-image (2)"), "with counts: " + r.output);
        assertTrue(r.output.contains(tmp.resolve("runtime").resolve("tag-cutover-map.txt").toString()),
                "the abort names the rulings-file path: " + r.output);
        assertTrue(r.output.contains("tag-tree-cutover.sh"), "the abort names the cutover script: " + r.output);
        assertTrue(r.output.contains("§7.14"), "the abort points at the runbook: " + r.output);
        assertFalse(r.output.contains("reconcile-file"), "the wording stays subcommand-neutral: " + r.output);
        assertTrue(Files.notExists(tmp.resolve("runtime").resolve("tag-cutover-map.txt")),
                "the gate never writes the rulings skeleton");

        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argvLog.contains("build"), "the abort precedes the build — nothing to roll back:\n" + argvLog);
        assertFalse(argvLog.contains("up -d"), "the abort precedes any restart:\n" + argvLog);
    }

    @Test
    void cleanPreflightReachesTheRestart(@TempDir Path tmp) throws Exception {
        RunResult r = runUpgrade(tmp, "green");

        assertEquals(0, r.exitCode, "a GREEN preflight flows through:\n" + r.output);
        assertTrue(r.output.contains("checkout already at"),
                "the no-op-pull leg still redeploys (M1-476): " + r.output);
        assertTrue(r.output.contains("upgrade complete"), r.output);

        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(argvLog.contains("build infochat-collector infochat-provider"),
                "the build runs:\n" + argvLog);
        assertTrue(argvLog.contains("up -d --wait"), "the Collector restarts:\n" + argvLog);
        assertTrue(argvLog.contains("up -d infochat-provider"), "the Provider restarts:\n" + argvLog);
    }

    @Test
    void unreachableDatabaseFailsLoud(@TempDir Path tmp) throws Exception {
        RunResult r = runUpgrade(tmp, "fail");

        assertNotEquals(0, r.exitCode, "a gate that cannot read the DB never silently passes:\n" + r.output);
        assertTrue(r.output.contains("container-exec wrapper"), "the abort names the wrapper: " + r.output);
        assertTrue(r.output.contains("docker compose up -d postgres"),
                "the abort names the recovery: " + r.output);

        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argvLog.contains("build"), "the loud abort precedes the build:\n" + argvLog);
        assertFalse(argvLog.contains("up -d"), "the loud abort precedes any restart:\n" + argvLog);
    }

    /** Drives the real upgrade.sh (-y) under a restricted PATH with fake docker + git. */
    private RunResult runUpgrade(Path tmp, String cutoverMode) throws Exception {
        Path repoRoot = repoRoot();
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String tool : REAL_TOOLS) {
            Files.createSymbolicLink(bin.resolve(tool), realTool(tool));
        }
        writeFake(bin.resolve("docker"), FAKE_DOCKER);
        writeFake(bin.resolve("git"), FAKE_GIT);

        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Path simplexDir = Files.createDirectories(tmp.resolve("simplex-data"));
        Files.writeString(runtime.resolve("secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIMPLEX_DATA_DIR=\"" + simplexDir + "\"\n");
        Files.writeString(runtime.resolve("bootstrap-sources.json"),
                "[\n  {\n    \"kind\": \"rss\",\n"
                        + "    \"identifier\": \"https://upgrade.example.test/feed.xml\",\n"
                        + "    \"name\": \"Upgrade wiring source\",\n"
                        + "    \"category\": \"news\",\n"
                        + "    \"tags\": [\"ai\"]\n  }\n]\n");

        ProcessBuilder pb = new ProcessBuilder(
                bin.resolve("bash").toString(),
                repoRoot.resolve("prod/scripts/upgrade.sh").toString(),
                "-y");
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString());
        env.put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        env.put("FAKE_DOCKER_ARGV_LOG", tmp.resolve("docker-argv.log").toString());
        env.put("FAKE_CUTOVER_MODE", cutoverMode);

        Process p = pb.start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("upgrade.sh hung (>120s); output so far:\n" + output);
        }
        return new RunResult(p.exitValue(), output);
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
