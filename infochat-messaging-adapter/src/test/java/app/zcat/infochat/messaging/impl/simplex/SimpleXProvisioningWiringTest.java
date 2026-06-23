package app.zcat.infochat.messaging.impl.simplex;

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

/**
 * Pins the SimpleX bot-identity provisioning wiring (M1-431) the suite otherwise
 * never exercises — the wizard step that replaces the manual
 * {@code simplex-chat}/{@code /ad}/{@code /auto_accept on} walkthrough.
 *
 * <p>Drives the REAL {@code prod/scripts/6b-simplex-provision.sh} with a fake
 * {@code docker} on {@code PATH} (the script reaches {@code simplex-chat} only
 * via {@code docker compose run --entrypoint /usr/local/bin/simplex-chat}, so
 * the fake {@code docker} stands in for the in-container binary). No real
 * containers, daemon, or network. The fake records the {@code simplex-chat}
 * argv and emulates the pinned v6.5.4 behaviour the provisioning spike captured
 * ({@code .scratch/simplex-spike-findings.md}): a bad command still exits 0; a
 * second {@code /ad} reports "you already have chat address".
 *
 * <p>Linux-gated, matching the existing wizard-wiring precedent
 * ({@code LlamacppWiringTest}): {@code 6b-simplex-provision.sh} uses bash and is
 * driven from {@link ProcessBuilder}, and a real end-to-end SimpleX provision is
 * a manual VPS check too heavy for {@code mvn verify}.
 */
class SimpleXProvisioningWiringTest {

    private static final String DISPLAY_NAME = "test-bot";
    private static final String FAKE_LINK = "https://smp9.example/a#FAKEADDR";

    // --- the three provisioning commands carry the in-dir <data-dir>/simplex_v1 prefix ---

    @Test
    @EnabledOnOs(OS.LINUX)
    void issuesThreeProvisioningCommandsWithInDirPrefix(@TempDir Path tmp) throws Exception {
        Result r = runProvision(tmp, false);
        assertEquals(0, r.exit(), "provisioning must succeed; output:\n" + r.output());

        String prefix = r.dataDir() + "/simplex_v1";
        // The prefix trap (M1-429): -d must be <data-dir>/simplex_v1, never the
        // bare data-dir (which writes the identity DBs as siblings outside the mount).
        assertNotEquals(r.dataDir(), prefix);

        String profile = commandContaining(r.cmdLog(), "--create-bot-display-name");
        assertTrue(profile.contains("--create-bot-display-name " + DISPLAY_NAME),
                "profile-create must pass the operator display name:\n" + profile);
        assertTrue(profile.contains("-d " + prefix),
                "profile-create must use the in-dir prefix, not a bare data-dir:\n" + profile);

        String address = commandContaining(r.cmdLog(), "-e /ad");
        assertTrue(address.contains("-d " + prefix),
                "address-create (/ad) must use the in-dir prefix:\n" + address);

        String autoAccept = commandContaining(r.cmdLog(), "-e /auto_accept on");
        assertTrue(autoAccept.contains("-d " + prefix),
                "auto-accept must use the in-dir prefix:\n" + autoAccept);

        // No command may pass a bare `-d <data-dir>` (the latent SETUP_GUIDE bug).
        for (String line : r.cmdLog()) {
            assertFalse(line.matches(".*-d " + java.util.regex.Pattern.quote(r.dataDir()) + "( .*|$)")
                            && !line.contains("-d " + prefix),
                    "no provisioning command may use a bare data-dir prefix:\n" + line);
        }
    }

    // --- a second run is idempotent: no rotation, still succeeds ---

    @Test
    @EnabledOnOs(OS.LINUX)
    void secondRunIsNoOpAgainstProvisionedFake(@TempDir Path tmp) throws Exception {
        // Shared state dir makes the fake report an already-provisioned identity on
        // the second run ("you already have chat address"), exactly as the pinned
        // binary does (spike item 7).
        Path state = Files.createDirectories(tmp.resolve("sxstate"));

        Result first = runProvision(tmp, false, state);
        assertEquals(0, first.exit(), "first run must succeed; output:\n" + first.output());

        Result second = runProvision(tmp, false, state);
        assertEquals(0, second.exit(),
                "a second run must succeed (idempotent — no rotation); output:\n" + second.output());
        // The fake reports the address already exists ("you already have chat
        // address"); the script must NOT treat that no-op response as a failure…
        assertFalse(second.output().contains("FAIL"),
                "the already-provisioned no-op must not be treated as a failure:\n" + second.output());
        // …and must still re-issue the provisioning commands (idempotent re-invocation).
        assertTrue(commandContaining(second.cmdLog(), "-e /ad").contains("/ad"),
                "the second run must still issue /ad (idempotent re-invocation)");
    }

    // --- a `bad chat command` on stdout fails the script despite exit 0 ---

    @Test
    @EnabledOnOs(OS.LINUX)
    void stdoutBadChatCommandFailsDespiteExit0(@TempDir Path tmp) throws Exception {
        // The fake emits "bad chat command" on stdout for /auto_accept on and still
        // exits 0 (spike item 5). The script must parse stdout, not the exit code.
        Result r = runProvision(tmp, true);
        assertNotEquals(0, r.exit(),
                "a stdout error marker must fail provisioning even though simplex-chat exits 0:\n"
                        + r.output());
        assertTrue(r.output().contains("FAIL") && r.output().contains("auto-accept"),
                "the failure message must name the rejected step:\n" + r.output());
    }

    // --- the contact link is surfaced to the operator but never persisted (D37) ---

    @Test
    @EnabledOnOs(OS.LINUX)
    void linkIsSurfacedButNeverWrittenToConfigOrSecrets(@TempDir Path tmp) throws Exception {
        Result r = runProvision(tmp, false);
        assertEquals(0, r.exit(), "provisioning must succeed; output:\n" + r.output());

        assertTrue(r.output().contains(FAKE_LINK),
                "the bot contact link must be surfaced transiently to the operator:\n" + r.output());

        String props = Files.readString(tmp.resolve("runtime/application.properties"));
        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"));
        assertFalse(props.contains("smp9.example"),
                "D37: the raw queue address must never be written to application.properties");
        assertFalse(secrets.contains("smp9.example"),
                "D37: the raw queue address must never be written to secrets.env");
    }

    // --- helpers ----------------------------------------------------------------

    private record Result(int exit, String output, List<String> cmdLog, String dataDir) { }

    private Result runProvision(Path tmp, boolean badAutoAccept) throws Exception {
        return runProvision(tmp, badAutoAccept, Files.createDirectories(tmp.resolve("sxstate")));
    }

    /** Drive the real 6b script with a fake docker on PATH; return exit + output + recorded argv. */
    private Result runProvision(Path tmp, boolean badAutoAccept, Path state) throws Exception {
        Path repoRoot = repoRoot();
        Path runtime = Files.createDirectories(tmp.resolve("runtime"));
        Path dataDir = tmp.resolve("botdata");
        // Seed exactly what 6-adapter.sh writes, including the wizard-only display-name key.
        Files.writeString(runtime.resolve("application.properties"),
                "infochat.adapters=simplex\n"
                        + "infochat.adapters.simplex.binary=/usr/local/bin/simplex-chat\n"
                        + "infochat.adapters.simplex.data-dir=" + dataDir + "\n"
                        + "infochat.adapters.simplex.ws-port=5225\n"
                        + "infochat.adapters.simplex.display-name=" + DISPLAY_NAME + "\n");
        // An empty secrets.env so the script's `--env-file` target exists.
        Files.writeString(runtime.resolve("secrets.env"), "");

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, fakeDockerScript());
        fakeDocker.toFile().setExecutable(true);

        Path cmdLog = tmp.resolve("cmdlog.txt");
        Files.writeString(cmdLog, "");

        ProcessBuilder pb = new ProcessBuilder(
                "bash", repoRoot.resolve("prod/scripts/6b-simplex-provision.sh").toString());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("INFOCHAT_RUNTIME_DIR", runtime.toString());
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", ""));
        env.put("SX_CMDLOG", cmdLog.toString());
        env.put("SX_STATE", state.toString());
        if (badAutoAccept) {
            env.put("SX_BAD", "1");
        }

        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        List<String> log = Files.exists(cmdLog) ? Files.readAllLines(cmdLog) : List.of();
        return new Result(rc, output, log, dataDir.toString());
    }

    /** The single recorded simplex-chat command line containing the marker. */
    private String commandContaining(List<String> cmdLog, String marker) {
        return cmdLog.stream()
                .filter(line -> line.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no provisioning command recorded containing '" + marker + "'; log:\n"
                                + String.join("\n", cmdLog)));
    }

    /**
     * Fake docker: emulate `compose run --entrypoint simplex-chat infochat-provider …`.
     * Records the simplex-chat argv (everything after the service name) to $SX_CMDLOG
     * and emits the pinned-binary's responses, keyed on the -e command and a state
     * file so a second run reports an already-provisioned identity. A bad command
     * still exits 0 (spike item 5); $SX_BAD makes /auto_accept on emit that marker.
     */
    private String fakeDockerScript() {
        return "#!/usr/bin/env bash\n"
                + "if [ \"$1\" != \"compose\" ]; then exit 0; fi\n"
                + "case \" $* \" in *\" run \"*) ;; *) exit 0 ;; esac\n"
                + "collect=0; args=()\n"
                + "for a in \"$@\"; do\n"
                + "  if [ \"$collect\" = \"1\" ]; then args+=(\"$a\"); fi\n"
                + "  if [ \"$a\" = \"infochat-provider\" ]; then collect=1; fi\n"
                + "done\n"
                + "printf '%s\\n' \"${args[*]}\" >> \"$SX_CMDLOG\"\n"
                + "cmd=\"\"; prev=\"\"\n"
                + "for a in \"${args[@]}\"; do\n"
                + "  if [ \"$prev\" = \"-e\" ]; then cmd=\"$a\"; fi\n"
                + "  prev=\"$a\"\n"
                + "done\n"
                + "mkdir -p \"$SX_STATE\"\n"
                + "case \"$cmd\" in\n"
                + "  \"/show_address\")\n"
                + "    if [ -f \"$SX_STATE/address\" ]; then\n"
                + "      echo \"Current user: " + DISPLAY_NAME + "\"\n"
                + "      echo \"Your chat address:\"; echo\n"
                + "      echo \"" + FAKE_LINK + "\"\n"
                + "      echo \"auto_accept on\"\n"
                + "    else\n"
                + "      echo \"Current user: " + DISPLAY_NAME + "\"\n"
                + "      echo \"no chat address, to create: /ad\"\n"
                + "    fi ;;\n"
                + "  \"/ad\")\n"
                + "    if [ -f \"$SX_STATE/address\" ]; then\n"
                + "      echo \"Current user: " + DISPLAY_NAME + "\"\n"
                + "      echo \"you already have chat address, to show: /sa\"\n"
                + "    else\n"
                + "      : > \"$SX_STATE/address\"\n"
                + "      echo \"Current user: " + DISPLAY_NAME + "\"\n"
                + "      echo \"Your new chat address is created!\"; echo\n"
                + "      echo \"" + FAKE_LINK + "\"\n"
                + "    fi ;;\n"
                + "  \"/auto_accept on\")\n"
                + "    if [ -n \"${SX_BAD:-}\" ]; then\n"
                + "      echo \"bad chat command: Failed reading: empty\"\n"
                + "    else\n"
                + "      echo \"auto_accept on\"\n"
                + "    fi ;;\n"
                + "esac\n"
                + "exit 0\n";
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
