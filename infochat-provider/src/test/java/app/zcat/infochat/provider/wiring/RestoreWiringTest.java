package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the fail-loud PRECONDITION GATES of {@code prod/scripts/restore.sh} (M1-567) —
 * the host-clone reconstructor. restore.sh performs an irreversible {@code pg_restore}
 * and extracts identity material to absolute paths, so acceptance item 4 requires it to
 * fail LOUD and EARLY at every precondition rather than half-restore. This test drives
 * the REAL script via {@link ProcessBuilder} under a restricted PATH (real coreutils +
 * a parametrized fake {@code docker}) and asserts each gate refuses BEFORE any mutation,
 * naming an actionable fix.
 *
 * <p><b>Scope: the gates only.</b> The happy-path ordering invariant (pg_restore into a
 * fresh DB BEFORE the Collector's first Flyway pass, model rehydration, full bring-up) is
 * verified by the real pack&rarr;transfer&rarr;restore round trip, which is HOST validation
 * (ticket {@code test_plan.notes}; like M1-566's post-merge live step), NOT mvn verify.
 * Every gate asserted here fails before the {@code tar -C /} identity extraction, so the
 * test never mutates the host or exercises Docker for real — the fake {@code docker} only
 * has to satisfy {@code command -v docker} and the one {@code docker volume ls} probe the
 * fresh-volume gate makes. Mirrors the prod-script wiring precedent (DoctorWiringTest,
 * SwitchLlmWiringTest). Linux-gated: the scripts use GNU/bash idioms and target Linux only.
 */
class RestoreWiringTest {

    // Real coreutils restore.sh invokes on the way to (and through) the gates: path
    // resolution (dirname), staging (mktemp), unpack + list (tar/gzip), the dotenv/tar
    // reads (grep/tail), and the exit-trap cleanup (rm). Symlinked from the host into the
    // controlled bin so a restricted PATH still lets the script run.
    private static final String[] REAL_TOOLS =
            {"bash", "dirname", "mktemp", "tar", "gzip", "grep", "tail", "rm"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    // The gates need only `command -v docker` to resolve and
    // `docker volume ls --filter name=infochat-pgdata -q` to report whether a pgdata
    // volume exists; FAKE_PGDATA_VOLUME=present trips the fresh-volume gate. No other
    // docker verb is reached before a gate fails.
    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "if [[ \"$1\" == volume && \"$2\" == ls ]]; then\n"
            + "  [[ \"${FAKE_PGDATA_VOLUME:-absent}\" == present ]] && echo \"infochat_infochat-pgdata\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "exit 0\n";

    private record RunResult(int exitCode, String output) {}

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingBundleFailsLoud(@TempDir Path tmp) throws Exception {
        RunResult r = runRestore(tmp, tmp.resolve("does-not-exist.tgz"), false);

        assertNotEquals(0, r.exitCode, "a missing bundle must fail:\n" + r.output);
        assertTrue(r.output.contains("bundle not found"),
                "the failure must name the missing bundle:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void corruptBundleFailsLoud(@TempDir Path tmp) throws Exception {
        Path bundle = tmp.resolve("corrupt.tgz");
        Files.writeString(bundle, "this is not a gzip tar archive");

        RunResult r = runRestore(tmp, bundle, false);

        assertNotEquals(0, r.exitCode, "a corrupt bundle must fail:\n" + r.output);
        assertTrue(r.output.contains("could not unpack") || r.output.contains("corrupt"),
                "the failure must flag the unusable bundle:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void alreadyConfiguredHostFailsLoud(@TempDir Path tmp) throws Exception {
        // A pre-existing secrets.env means this host already runs a deployment; restore
        // must refuse rather than clobber it (the "fresh host" contract).
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Files.writeString(runtime.resolve("secrets.env"), "INFOCHAT_DB_PASSWORD=\"x\"\n");
        Path bundle = buildValidBundle(tmp, "/var/lib/infochat/signal-cli", "var/lib/infochat/signal-cli");

        RunResult r = runRestore(tmp, bundle, false);

        assertNotEquals(0, r.exitCode, "an already-configured host must be refused:\n" + r.output);
        assertTrue(r.output.contains("already exists") && r.output.contains("already configured"),
                "the failure must say the host is already configured:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void preexistingPgdataVolumeFailsLoud(@TempDir Path tmp) throws Exception {
        Path bundle = buildValidBundle(tmp, "/var/lib/infochat/signal-cli", "var/lib/infochat/signal-cli");

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ true);

        assertNotEquals(0, r.exitCode, "a pre-existing pgdata volume must fail:\n" + r.output);
        assertTrue(r.output.contains("infochat-pgdata") && r.output.contains("already exists"),
                "the failure must name the pre-existing volume:\n" + r.output);
        assertTrue(r.output.contains("FRESH"),
                "the failure must state the fresh-host requirement:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void identityPathMismatchFailsLoud(@TempDir Path tmp) throws Exception {
        // secrets.env says the Signal dir is /var/lib/infochat/signal-cli, but the bundled
        // identities.tgz reconstructs a DIFFERENT path — v1 requires the same absolute path.
        Path bundle = buildValidBundle(tmp, "/var/lib/infochat/signal-cli", "somewhere/else");

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false);

        assertNotEquals(0, r.exitCode, "an identity path mismatch must fail:\n" + r.output);
        assertTrue(r.output.contains("path mismatch"),
                "the failure must flag the path mismatch:\n" + r.output);
        assertTrue(r.output.contains("SAME absolute") || r.output.contains("same absolute"),
                "the failure must state the same-absolute-path constraint:\n" + r.output);
    }

    // --- helpers ----------------------------------------------------------------

    /** Drive the real restore.sh under a controlled PATH; return exit code + combined output. */
    private RunResult runRestore(Path tmp, Path bundle, boolean pgdataVolumePresent) throws Exception {
        Path repoRoot = repoRoot();
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String tool : REAL_TOOLS) {
            Files.createSymbolicLink(bin.resolve(tool), realTool(tool));
        }
        writeFake(bin.resolve("docker"), FAKE_DOCKER);

        ProcessBuilder pb = new ProcessBuilder(
                bin.resolve("bash").toString(),
                repoRoot.resolve("prod/scripts/restore.sh").toString(),
                bundle.toString());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString()); // restricted: only our controlled bin
        // Redirect runtime placement to the sandbox so restore.sh never reads or writes
        // the real prod/runtime; the fake docker keeps the volume probe off real Docker.
        env.put("INFOCHAT_RUNTIME_DIR", tmp.resolve("runtime").toString());
        env.put("FAKE_PGDATA_VOLUME", pgdataVolumePresent ? "present" : "absent");

        Process p = pb.start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        return new RunResult(rc, output);
    }

    /**
     * Build a structurally-valid pack.sh bundle for the gate tests: the required members
     * (db/infochat.pgc, runtime/{secrets.env,application.properties,bootstrap-sources.json},
     * identities.tgz). {@code secretsDataDir} is written as INFOCHAT_SIGNAL_DATA_DIR; the
     * nested identity tar reconstructs {@code identityTarTop} (relative to /, as pack.sh
     * stores it) — pass a path matching secretsDataDir for a consistent bundle, or a
     * different one to exercise the path-mismatch gate.
     */
    private Path buildValidBundle(Path tmp, String secretsDataDir, String identityTarTop) throws Exception {
        int uid = (secretsDataDir + "|" + identityTarTop).hashCode() & 0xffff;
        Path staging = Files.createDirectories(tmp.resolve("bsrc" + uid));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIGNAL_DATA_DIR=\"" + secretsDataDir + "\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"), "quarkus.profile=vps\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        // Nested identity tar, mirroring pack.sh's `tar -C / ...` (paths relative to /).
        Path idsrc = Files.createDirectories(tmp.resolve("idsrc" + uid));
        Path leaf = idsrc.resolve(identityTarTop);
        Files.createDirectories(leaf);
        Files.writeString(leaf.resolve("keyfile"), "id");
        run(idsrc, "tar", "-czf", staging.resolve("identities.tgz").toString(), ".");

        Path bundle = tmp.resolve("bundle" + uid + ".tgz");
        run(staging, "tar", "-czf", bundle.toString(), ".");
        return bundle;
    }

    /** Run a host command in {@code cwd}, failing the test on a non-zero exit. */
    private void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("bundle-build command failed (" + rc + "): "
                    + String.join(" ", cmd) + "\n" + out);
        }
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
