package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the PATH GUARD and consent gate of {@code prod/scripts/shred-bundle.sh} (M1-572) —
 * the operator-invoked secure-disposal helper for pack.sh bundles. The script is
 * irreversibly destructive ({@code shred -uz} then remove), so its guard is load-bearing:
 * it must refuse dangerous targets (nonexistent, {@code /}, {@code $HOME}, the repo root)
 * and anything not shaped like bundle / recovery material, and destroy nothing without
 * explicit consent. This test drives the REAL script via {@link ProcessBuilder} under a
 * restricted PATH (real coreutils only — the script needs no docker and no network), the
 * same wiring pattern as {@link RestoreWiringTest}.
 *
 * <p>Unlike the restore round-trip, secure disposal has no multi-GB or privileged step, so
 * BOTH sides are fully exercised here on JUnit {@code @TempDir} fixtures: the refusal paths
 * (guard fires, control fixture untouched) and the success paths (real {@code shred} + remove
 * of a fixture bundle file and a fixture recovery directory tree). The refusal cases against
 * REAL dangerous paths (repo root) deliberately omit {@code --yes}, so even a hypothetically
 * broken guard could not destroy anything — stdin is not a TTY, so the consent gate refuses
 * too; the assertions then require the GUARD's message, proving which gate fired. Linux-gated:
 * the script uses GNU/bash idioms and targets Linux only.
 */
class ShredBundleWiringTest {

    // Every external tool shred-bundle.sh invokes: path resolution (dirname, realpath),
    // inventory (find, wc, du), and destruction (shred, rm). Symlinked from the host into
    // the controlled bin so the restricted PATH pins the full tool surface.
    private static final String[] REAL_TOOLS =
            {"bash", "dirname", "realpath", "find", "wc", "du", "shred", "rm"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    private record RunResult(int exitCode, String output) {}

    @Test
    @EnabledOnOs(OS.LINUX)
    void refusesRepoRoot(@TempDir Path tmp) throws Exception {
        // No --yes on purpose (see class javadoc): the consent gate is the backstop while
        // the assertion pins that the repo-root GUARD, not the consent gate, refused.
        Path control = eligibleRecoveryDir(tmp);

        RunResult r = runShred(tmp, repoRoot().toString());

        assertNotEquals(0, r.exitCode, "the repo root must be refused:\n" + r.output);
        assertTrue(r.output.contains("refusing to act on the repo root"),
                "the refusal must come from the repo-root guard:\n" + r.output);
        assertTrue(Files.exists(control.resolve("identities-20260705.tgz")),
                "a refusal must leave the control fixture untouched:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void refusesNonexistentTarget(@TempDir Path tmp) throws Exception {
        Path control = eligibleRecoveryDir(tmp);

        RunResult r = runShred(tmp, tmp.resolve("no-such-bundle.tgz").toString(), "--yes");

        assertNotEquals(0, r.exitCode, "a nonexistent target must be refused:\n" + r.output);
        assertTrue(r.output.contains("does not exist"),
                "the refusal must name the missing target:\n" + r.output);
        assertTrue(Files.exists(control.resolve("identities-20260705.tgz")),
                "a refusal must leave the control fixture untouched:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void refusesHomeDirectory(@TempDir Path tmp) throws Exception {
        // $HOME is redirected into the sandbox and given ELIGIBLE shape (a *.tgz inside),
        // proving the HOME refusal wins over shape eligibility — even with --yes.
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.writeString(home.resolve("infochat-migration-20260706.tgz"), "bundle-bytes");

        RunResult r = runShred(tmp, home, home.toString(), "--yes");

        assertNotEquals(0, r.exitCode, "the invoking user's HOME must be refused:\n" + r.output);
        assertTrue(r.output.contains("refusing to act on the invoking user's HOME"),
                "the refusal must come from the HOME guard:\n" + r.output);
        assertTrue(Files.exists(home.resolve("infochat-migration-20260706.tgz")),
                "a refusal must leave the target untouched:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void refusesDirectoryWithoutRecoveryMaterialShape(@TempDir Path tmp) throws Exception {
        // --yes is safe here: the fixture is sandboxed, and the assertion that it survives
        // is exactly the point — refusal must mean nothing was removed.
        Path plainDir = Files.createDirectories(tmp.resolve("not-recovery"));
        Files.writeString(plainDir.resolve("notes.txt"), "not bundle material");

        RunResult r = runShred(tmp, plainDir.toString(), "--yes");

        assertNotEquals(0, r.exitCode,
                "a directory without bundle/recovery shape must be refused:\n" + r.output);
        assertTrue(r.output.contains("does not look like bundle/recovery material"),
                "the refusal must name the shape mismatch:\n" + r.output);
        assertTrue(Files.exists(plainDir.resolve("notes.txt")),
                "a refusal must leave the directory untouched:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void refusesWithoutConsentWhenStdinIsNotInteractive(@TempDir Path tmp) throws Exception {
        // Eligible target, no --yes, stdin closed (not a TTY): destruction requires
        // explicit consent, so the script must refuse rather than silently proceed.
        Path bundle = tmp.resolve("infochat-migration-20260706.tgz");
        Files.writeString(bundle, "bundle-bytes");

        RunResult r = runShred(tmp, bundle.toString());

        assertNotEquals(0, r.exitCode, "no consent must mean no destruction:\n" + r.output);
        assertTrue(r.output.contains("confirmation required"),
                "the refusal must say consent is missing:\n" + r.output);
        assertTrue(Files.exists(bundle),
                "a consent refusal must leave the bundle untouched:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void yesFlagShredsBundleFile(@TempDir Path tmp) throws Exception {
        Path bundle = tmp.resolve("infochat-migration-20260706.tgz");
        Files.writeString(bundle, "bundle-bytes-to-be-overwritten");
        String resolvedTarget = bundle.toRealPath().toString(); // resolve while it still exists

        RunResult r = runShred(tmp, bundle.toString(), "--yes");

        assertEquals(0, r.exitCode, "a --yes run on a bundle file must succeed:\n" + r.output);
        assertTrue(Files.notExists(bundle),
                "the bundle file must be shredded and removed:\n" + r.output);
        // The operator sees the resolved absolute target and the inventory before
        // destruction (acceptance: confirmation shows exactly what will be overwritten).
        assertTrue(r.output.contains(resolvedTarget),
                "the run must print the resolved absolute target:\n" + r.output);
        assertTrue(r.output.contains("1 file(s)"),
                "the run must print the file-count inventory:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void yesFlagShredsRecoveryDirectoryTree(@TempDir Path tmp) throws Exception {
        Path recovery = eligibleRecoveryDir(tmp);

        RunResult r = runShred(tmp, recovery.toString(), "--yes");

        assertEquals(0, r.exitCode,
                "a --yes run on a recovery directory must succeed:\n" + r.output);
        assertTrue(Files.notExists(recovery),
                "the whole recovery tree must be shredded and removed — no empty tree left:\n"
                        + r.output);
    }

    // --- helpers ----------------------------------------------------------------

    /**
     * A directory shaped like the recovery safety-copy material pack.sh's lifecycle
     * produces: an identities tarball ({@code *.tgz}) plus {@code raw-config/secrets.env}.
     */
    private Path eligibleRecoveryDir(Path tmp) throws Exception {
        Path recovery = Files.createDirectories(tmp.resolve("recovery"));
        Files.writeString(recovery.resolve("identities-20260705.tgz"), "identity-tar-bytes");
        Path rawConfig = Files.createDirectories(recovery.resolve("raw-config"));
        Files.writeString(rawConfig.resolve("secrets.env"), "INFOCHAT_DB_PASSWORD=\"pw\"\n");
        return recovery;
    }

    /** Drive the real shred-bundle.sh with {@code $HOME} pointed into the sandbox. */
    private RunResult runShred(Path tmp, String target, String... flags) throws Exception {
        return runShred(tmp, tmp.resolve("home-unused"), target, flags);
    }

    /**
     * Drive the real shred-bundle.sh under a controlled PATH; stdin is closed (not a TTY),
     * {@code $HOME} is {@code home}. Returns exit code + combined output.
     */
    private RunResult runShred(Path tmp, Path home, String target, String... flags)
            throws Exception {
        Path bin = tmp.resolve("bin");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            for (String tool : REAL_TOOLS) {
                Files.createSymbolicLink(bin.resolve(tool), realTool(tool));
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.resolve("bash").toString());
        cmd.add(repoRoot().resolve("prod/scripts/shred-bundle.sh").toString());
        cmd.addAll(List.of(flags));
        cmd.add(target);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tmp.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString()); // restricted: only our controlled bin
        env.put("HOME", home.toString()); // never the real HOME — the guard compares against it

        Process p = pb.start();
        p.getOutputStream().close(); // stdin closed: no TTY, no piped consent
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        return new RunResult(rc, output);
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
