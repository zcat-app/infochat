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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Pins the contract of {@code scripts/fam-replica-restore.sh} (M1-954), the instance-free replica dump/restore/fingerprint instrument. */
class ReplicaRestoreWiringTest {

    private static final String[] REAL_TOOLS =
            {"bash", "dirname", "mkdir", "chmod", "date", "grep", "sha256sum",
             "wc", "head", "awk", "tee", "cat"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    // Instance-free test fixtures (§13: real instance values never appear here).
    private static final String PROJECT = "replica-eval-under-test";
    private static final String DERIVED_CONTAINER = PROJECT + "-postgres";
    private static final String DERIVED_VOLUME = PROJECT + "_pgdata";
    private static final String SOURCE_CONTAINER = "source-postgres-under-test";
    private static final String TARGET_PORT = "39123";
    private static final String IN_CONTAINER_DUMP = "/tmp/replica-restore.pgc";

    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "printf '%s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\"\n"
            + "if [[ \"$1\" == volume && \"$2\" == ls ]]; then\n"
            + "  [[ \"${FAKE_TARGET_VOLUME:-absent}\" == present ]] && echo \"${FAKE_VOLUME_NAME:-}\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == network && \"$2\" == inspect ]]; then\n"
            + "  if [[ \"$*\" == *'{{len .Containers}}'* ]]; then\n"
            + "    echo \"${FAKE_NETWORK_MEMBERS:-1}\"\n"
            + "  else\n"
            + "    echo \"${FAKE_NETWORK_MEMBER_NAMES:-}\"\n"
            + "  fi\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == compose ]]; then\n"
            + "  if [[ \"$*\" == *'up -d --wait'* ]]; then\n"
            + "    echo \"FAKE-DOCKER: isolated postgres bring-up (--wait)\"\n"
            + "    exit \"${FAKE_COMPOSE_UP_EXIT:-0}\"\n"
            + "  fi\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == exec ]]; then\n"
            + "  if [[ \"$*\" == *step=admin_role* ]]; then\n"
            + "    echo \"FAKE-DOCKER: admin-role psql over stdin (step=admin_role)\"\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  if [[ \"$*\" == *step=history_probe* ]]; then\n"
            + "    echo \"FAKE-DOCKER: flyway-history probe psql (step=history_probe)\"\n"
            + "    [[ -n \"${FAKE_FLYWAY_HISTORY_FILE:-}\" ]] && printf '%s\\n' \"$(<\"$FAKE_FLYWAY_HISTORY_FILE\")\"\n"
            + "    exit \"${FAKE_FLYWAY_HISTORY_EXIT:-0}\"\n"
            + "  fi\n"
            + "  if [[ \"$*\" == *step=seed_apply* ]]; then\n"
            + "    echo \"FAKE-DOCKER: eval-scope seed psql over stdin (step=seed_apply)\"\n"
            + "    if [[ -n \"${FAKE_SEED_OUTPUT_FILE:-}\" ]]; then\n"
            + "      printf '%s\\n' \"$(<\"$FAKE_SEED_OUTPUT_FILE\")\"\n"
            + "    else\n"
            + "      printf 'eval_scopes|5\\neval_scope_subscriptions|0\\neval_scope_exclusions|0\\n'\n"
            + "    fi\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  if [[ \"$*\" == *step=pin_read* ]]; then\n"
            + "    echo \"FAKE-DOCKER: pin readout psql over stdin (step=pin_read)\"\n"
            + "    [[ -n \"${FAKE_PIN_OUTPUT_FILE:-}\" ]] && printf '%s\\n' \"$(<\"$FAKE_PIN_OUTPUT_FILE\")\"\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  if [[ \"$*\" == *pg_restore* ]]; then\n"
            + "    echo \"FAKE-DOCKER: pg_restore of the in-container dump path\"\n"
            + "    [[ -n \"${FAKE_PG_RESTORE_STDERR_FILE:-}\" ]] && printf '%s\\n' \"$(<\"$FAKE_PG_RESTORE_STDERR_FILE\")\" >&2\n"
            + "    exit \"${FAKE_PG_RESTORE_EXIT:-0}\"\n"
            + "  fi\n"
            + "  if [[ \"$*\" == *pg_dump* ]]; then\n"
            + "    if [[ \"${FAKE_DUMP_GARBAGE:-}\" == 1 ]]; then\n"
            + "      printf 'not a dump at all'\n"
            + "    else\n"
            + "      printf 'PGDMP-fake-dump-bytes'\n"
            + "    fi\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == cp ]]; then\n"
            + "  echo \"FAKE-DOCKER: docker cp transfer\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == run ]]; then\n"
            + "  echo \"FAKE-DOCKER: flyway-CLI container apply\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "exit 0\n";

    private record RunResult(int exitCode, String output) {}

    @Test
    void usageListsAllVerbsAndRequiredFlags(@TempDir Path tmp) throws Exception {
        RunResult r = runScript(tmp, Map.of(), "-h");

        assertEquals(0, r.exitCode, "usage must exit zero:\n" + r.output);
        for (String token : List.of("dump", "restore", "fingerprint",
                "--source-container", "--project", "--port")) {
            assertTrue(r.output.contains(token),
                    "usage must document " + token + ":\n" + r.output);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void usageRequiresEveryInstanceShapedFlag(@TempDir Path tmp) throws Exception {
        Path dump = writeDump(tmp);

        RunResult dumpNoFlag = runScript(tmp, Map.of(), "dump");
        assertNotEquals(0, dumpNoFlag.exitCode, "dump without --source-container must fail:\n"
                + dumpNoFlag.output);
        assertTrue(dumpNoFlag.output.contains("--source-container"),
                "the failure must name the missing flag:\n" + dumpNoFlag.output);

        RunResult restoreNoProject = runScript(tmp, Map.of(),
                "restore", dump.toString(), "--port", TARGET_PORT);
        assertNotEquals(0, restoreNoProject.exitCode,
                "restore without --project must fail:\n" + restoreNoProject.output);
        assertTrue(restoreNoProject.output.contains("--project"),
                "the failure must name the missing flag:\n" + restoreNoProject.output);

        RunResult restoreNoPort = runScript(tmp, Map.of(),
                "restore", dump.toString(), "--project", PROJECT);
        assertNotEquals(0, restoreNoPort.exitCode,
                "restore without --port must fail:\n" + restoreNoPort.output);
        assertTrue(restoreNoPort.output.contains("--port"),
                "the failure must name the missing flag:\n" + restoreNoPort.output);

        RunResult fingerprintNoProject = runScript(tmp, Map.of(), "fingerprint");
        assertNotEquals(0, fingerprintNoProject.exitCode,
                "fingerprint without --project must fail:\n" + fingerprintNoProject.output);
        assertTrue(fingerprintNoProject.output.contains("--project"),
                "the failure must name the missing flag:\n" + fingerprintNoProject.output);
    }

    @Test
    void carriesNoPortShapedLiteralOutsideTheAllowlist() throws IOException {
        String script = Files.readString(scriptPath());

        // §13 placement leg: every port-shaped numeric literal is an allowlisted
        // one — in-container postgres (5432) or the two refusal fences.
        Pattern portShaped = Pattern.compile("\\b\\d{4,5}\\b");
        Matcher matcher = portShaped.matcher(script);
        while (matcher.find()) {
            String literal = matcher.group();
            assertTrue(List.of("5432", "15432", "25432").contains(literal),
                    "port-shaped literal outside the allowlist: " + literal);
        }
        assertFalse(script.contains("exec -T"),
                "docker 29 dropped the pseudo-TTY flag; it must not appear");
        List<String> bringUpLines = script.lines()
                .filter(line -> line.contains("up -d") || line.contains("docker run"))
                .toList();
        assertEquals(2, bringUpLines.size(),
                "exactly the isolated-postgres bring-up and the flyway-CLI apply may "
                        + "start anything:\n" + String.join("\n", bringUpLines));
        assertTrue(bringUpLines.stream().anyMatch(
                        line -> line.contains("--wait") && line.contains("postgres")),
                "the first bring-up must be the isolated postgres alone:\n" + bringUpLines);
        assertTrue(bringUpLines.stream().anyMatch(
                        line -> line.stripLeading().startsWith("docker run")),
                "the second bring-up must be the flyway-CLI container apply (the migrate "
                        + "command rides its continuation lines):\n" + bringUpLines);
        for (String section : List.of("world_fingerprint", "world_embedding_coverage",
                "embedding_metadata", "scope_language_census")) {
            assertTrue(script.contains(section),
                    "the pin readout must keep its " + section + " section");
        }
    }

    @Test
    void flywayChecksumMatchesThePinnedProdFunction() throws IOException {
        // The history gate's checksum must stay byte-identical to the prod
        // function RestoreFlywayChecksumIT pins against real Flyway; the Java
        // fixtures re-implement it, so a two-point edit would stay green.
        assertEquals(extractFlywayChecksumFunction(repoRoot().resolve("prod/scripts/restore.sh")),
                extractFlywayChecksumFunction(scriptPath()),
                "the script's flyway_checksum must stay byte-identical to the pinned "
                        + "prod original");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void fencedPortsAreRefusedBeforeAnyDockerCall(@TempDir Path tmp) throws Exception {
        Path dump = writeDump(tmp);

        for (String fenced : List.of("15432", "25432")) {
            RunResult r = runScript(tmp, Map.of(),
                    "restore", dump.toString(), "--project", PROJECT, "--port", fenced);

            assertNotEquals(0, r.exitCode, "target port " + fenced + " must be refused:\n"
                    + r.output);
            assertTrue(r.output.contains(fenced) && r.output.contains("REFUSING"),
                    "the refusal must name the offending port:\n" + r.output);
            String argvLog = argvLog(tmp);
            assertTrue(argvLog.isBlank(),
                    "no docker call may precede the port fence:\n" + argvLog);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void preExistingDerivedVolumeIsRefusedBeforeBringUp(@TempDir Path tmp) throws Exception {
        Path dump = writeDump(tmp);

        RunResult r = runScript(tmp, Map.of(
                "FAKE_TARGET_VOLUME", "present",
                "FAKE_VOLUME_NAME", DERIVED_VOLUME),
                "restore", dump.toString(), "--project", PROJECT, "--port", TARGET_PORT);

        assertNotEquals(0, r.exitCode, "a non-fresh target volume must be refused:\n"
                + r.output);
        assertTrue(r.output.contains(DERIVED_VOLUME) && r.output.contains("already exists"),
                "the refusal must name the derived volume:\n" + r.output);
        String argvLog = argvLog(tmp);
        assertFalse(argvLog.contains("up -d"),
                "the volume fence must fire before any bring-up:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingDumpIsRefusedBeforeAnyDockerCall(@TempDir Path tmp) throws Exception {
        RunResult r = runScript(tmp, Map.of(),
                "restore", tmp.resolve("absent.pgc").toString(),
                "--project", PROJECT, "--port", TARGET_PORT);

        assertNotEquals(0, r.exitCode, "a missing dump must be refused:\n" + r.output);
        assertTrue(r.output.contains("not found"),
                "the refusal must name the missing dump:\n" + r.output);
        assertTrue(argvLog(tmp).isBlank(),
                "no docker call may run for a missing dump:\n" + argvLog(tmp));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void foreignNetworkMemberIsRefusedAfterBringUpBeforeAnyMutation(@TempDir Path tmp)
            throws Exception {
        Path dump = writeDump(tmp);

        RunResult r = runScript(tmp, Map.of(
                "FAKE_NETWORK_MEMBERS", "2",
                "FAKE_NETWORK_MEMBER_NAMES", "foreign-container-x "),
                "restore", dump.toString(), "--project", PROJECT, "--port", TARGET_PORT);

        assertNotEquals(0, r.exitCode, "a foreign network member must be refused:\n"
                + r.output);
        assertTrue(r.output.contains("foreign-container-x") && r.output.contains("REFUSING"),
                "the refusal must name the foreign member:\n" + r.output);
        String argvLog = argvLog(tmp);
        assertTrue(argvLog.contains("up -d --wait"),
                "the isolated postgres comes up before the fence:\n" + argvLog);
        assertFalse(argvLog.lines().anyMatch(line -> line.startsWith("cp ")),
                "no dump transfer may follow the failed fence:\n" + argvLog);
        assertFalse(argvLog.contains("pg_restore") || argvLog.contains("step=seed_apply")
                        || argvLog.contains("step=pin_read"),
                "no mutation step may follow the failed fence:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void restoreOrderingPostgresAloneDockerCpRestoreSchemaSeedPinLast(@TempDir Path tmp)
            throws Exception {
        RunResult r = runHealthyRestore(tmp);

        assertEquals(0, r.exitCode, "the healthy drive must complete:\n" + r.output);
        String argvLog = argvLog(tmp);
        // psql legs are pinned by their EXEC-line step markers, never by any
        // transfer line; the single dump cp anchors the binary transport.
        int upIdx = argvLog.indexOf("up -d --wait");
        int adminRoleIdx = argvLog.indexOf("step=admin_role");
        int dumpCpIdx = argvLog.indexOf("\ncp ") + 1;
        int pgRestoreIdx = argvLog.indexOf("pg_restore -h 127.0.0.1");
        int historyProbeIdx = argvLog.indexOf("step=history_probe");
        int flywayRunIdx = argvLog.indexOf("\nrun ") + 1;
        int seedApplyIdx = argvLog.indexOf("step=seed_apply");
        int pinReadIdx = argvLog.indexOf("step=pin_read");
        for (int idx : List.of(upIdx, adminRoleIdx, dumpCpIdx, pgRestoreIdx,
                historyProbeIdx, flywayRunIdx, seedApplyIdx, pinReadIdx)) {
            assertTrue(idx >= 0, "every ordering marker must reach the argv log:\n" + argvLog);
        }
        assertTrue(upIdx < adminRoleIdx && adminRoleIdx < dumpCpIdx && dumpCpIdx < pgRestoreIdx
                        && pgRestoreIdx < historyProbeIdx && historyProbeIdx < flywayRunIdx
                        && flywayRunIdx < seedApplyIdx && seedApplyIdx < pinReadIdx,
                "order must be postgres-up, admin-role, dump cp, pg_restore, history "
                        + "verification, flyway apply, seed, pin read LAST (a moved step "
                        + "fails its neighbour comparison):\n" + argvLog);
        assertEquals(1, argvLog.lines().filter(line -> line.startsWith("cp ")).count(),
                "exactly one docker cp (the dump leg) may appear:\n" + argvLog);
        assertFalse(argvLog.contains("infochat-collector") || argvLog.contains("infochat-provider"),
                "postgres comes up ALONE — no app service may be booted:\n" + argvLog);
        String upLine = argvLog.lines().filter(line -> line.contains("up -d --wait")).findFirst().orElseThrow();
        assertTrue(upLine.endsWith(" postgres"),
                "the bring-up must select the postgres service alone: " + upLine);
        // The generated compose stays loopback-published and joins no foreign network.
        String compose = Files.readString(tmp.resolve("bench/docker-compose.yml"));
        assertTrue(compose.contains("127.0.0.1:" + TARGET_PORT + ":5432"),
                "the publish must be loopback-only with the operator port:\n" + compose);
        assertTrue(compose.contains("container_name: " + DERIVED_CONTAINER),
                "the container name must derive from the project flag:\n" + compose);
        assertFalse(compose.contains("external"),
                "the isolated postgres must join no external network:\n" + compose);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void restoreLoadsBinaryDumpViaDockerCpNeverStdin(@TempDir Path tmp) throws Exception {
        Path dump = writeDump(tmp);
        runHealthyRestore(tmp, dump);

        String argvLog = argvLog(tmp);
        List<String> cpLines = argvLog.lines().filter(line -> line.startsWith("cp ")).toList();
        assertEquals(1, cpLines.size(), "the dump must be transferred exactly once:\n" + argvLog);
        String cpLine = cpLines.getFirst();
        assertTrue(cpLine.contains(dump.toString()) && cpLine.contains(IN_CONTAINER_DUMP),
                "the single cp must be the dump leg into the in-container path:\n" + cpLine);
        String pgRestoreLine = argvLog.lines()
                .filter(line -> line.contains("pg_restore -h 127.0.0.1")).findFirst().orElseThrow();
        assertTrue(pgRestoreLine.contains(IN_CONTAINER_DUMP),
                "pg_restore must restore the IN-CONTAINER path:\n" + pgRestoreLine);
        assertTrue(pgRestoreLine.startsWith("exec "),
                "pg_restore must not ride a stdin pipe (exec without -i):\n" + pgRestoreLine);
        assertFalse(argvLog.lines().anyMatch(
                        line -> line.startsWith("exec -i ") && line.contains("pg_restore")),
                "the custom-format dump piped over stdin loses its PGDMP magic — the dump "
                        + "leg must never be an exec -i pipe:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void restoreAppliesPendingMigrationsViaFlywayCliNeverAnAppBoot(@TempDir Path tmp)
            throws Exception {
        RunResult r = runHealthyRestore(tmp);

        assertEquals(0, r.exitCode, "the behind-head drive must complete:\n" + r.output);
        String argvLog = argvLog(tmp);
        String flywayLine = argvLog.lines().filter(line -> line.startsWith("run ")).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "a pending migration must be applied via a container run:\n" + argvLog));
        assertTrue(flywayLine.contains("flyway/flyway:12") && flywayLine.contains("migrate"),
                "the apply must be the pinned flyway-CLI image with migrate:\n" + flywayLine);
        int historyProbeIdx = argvLog.indexOf("step=history_probe");
        int flywayRunIdx = argvLog.indexOf("\nrun ") + 1;
        int seedApplyIdx = argvLog.indexOf("step=seed_apply");
        assertTrue(historyProbeIdx >= 0 && flywayRunIdx > historyProbeIdx
                        && seedApplyIdx > flywayRunIdx,
                "the apply must sit between history verification and the seed:\n" + argvLog);
        assertFalse(argvLog.lines().anyMatch(line -> line.contains("infochat-collector")
                        || line.contains("infochat-provider")),
                "NO app boot may run against the replica:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void restoreRefusesAbsentAppliedVersionAndChecksumDrift(@TempDir Path tmp)
            throws Exception {
        Path dump = writeDump(tmp);
        Path absentFixture = tmp.resolve("history-absent.txt");
        Files.writeString(absentFixture, "999|V999__not_shipped_here.sql|42|t\n");
        RunResult absent = runHealthyRestore(tmp, dump, absentFixture);

        assertNotEquals(0, absent.exitCode,
                "an applied version absent from the checkout must fail:\n" + absent.output);
        assertTrue(absent.output.contains("V999") && absent.output.contains("NEWER"),
                "the refusal must name the version and the newer-revision diagnosis:\n"
                        + absent.output);
        assertFalse(absent.output.contains("checksum drift"),
                "the absent-version case is not a checksum drift:\n" + absent.output);
        String absentLog = argvLog(tmp);
        assertTrue(absentLog.contains("step=history_probe"),
                "the gate fires at the history probe:\n" + absentLog);
        assertFalse(absentLog.contains("step=seed_apply") || absentLog.contains("step=pin_read"),
                "no seed or pin read may follow the failed gate:\n" + absentLog);

        Path driftedFixture = tmp.resolve("history-drifted.txt");
        long drifted = flywayChecksum(migrationFile("V50__banned_admin_actor_checks.sql")) + 1;
        Files.writeString(driftedFixture,
                "50|V50__banned_admin_actor_checks.sql|" + drifted + "|t\n");
        RunResult driftedRun = runHealthyRestore(tmp, dump, driftedFixture);

        assertNotEquals(0, driftedRun.exitCode, "a checksum drift must fail:\n"
                + driftedRun.output);
        assertTrue(driftedRun.output.contains("V50") && driftedRun.output.contains("checksum drift"),
                "the refusal must name the drifted version:\n" + driftedRun.output);
        assertTrue(driftedRun.output.contains("source host's revision")
                        && driftedRun.output.contains("UPDATE flyway_schema_history SET checksum"),
                "the refusal must offer both recovery options:\n" + driftedRun.output);
        String driftedLog = argvLog(tmp);
        assertFalse(driftedLog.contains("step=seed_apply")
                        || driftedLog.contains("step=pin_read"),
                "no seed or pin read may follow the failed gate:\n" + driftedLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void seedProbeDeltaAbortsBeforePinRead(@TempDir Path tmp) throws Exception {
        Path dump = writeDump(tmp);
        Path badSeed = tmp.resolve("seed-output.txt");
        Files.writeString(badSeed,
                "eval_scopes|4\neval_scope_subscriptions|0\neval_scope_exclusions|0\n");

        RunResult r = runHealthyRestore(tmp, dump, null, Map.of(
                "FAKE_SEED_OUTPUT_FILE", badSeed.toString()));

        assertNotEquals(0, r.exitCode,
                "a corrupted seed delta must abort before the pin read:\n" + r.output);
        assertTrue(r.output.contains("5/0/0") && r.output.contains("eval_scopes|4"),
                "the abort must name the expected and observed delta:\n" + r.output);
        String argvLog = argvLog(tmp);
        assertTrue(argvLog.contains("step=seed_apply"),
                "the seed ran before the abort:\n" + argvLog);
        assertFalse(argvLog.contains("step=pin_read"),
                "no pin read may follow a failed seed probe:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void fingerprintVerbExecutesPsqlOnly(@TempDir Path tmp) throws Exception {
        RunResult r = runScript(tmp, Map.of(), "fingerprint", "--project", PROJECT);

        assertEquals(0, r.exitCode, "the fingerprint drive must complete:\n" + r.output);
        String argvLog = argvLog(tmp);
        assertTrue(argvLog.lines().anyMatch(
                        line -> line.startsWith("exec -i ") && line.contains("step=pin_read")),
                "the fingerprint verb must exec the pin-read psql over stdin:\n" + argvLog);
        assertFalse(argvLog.lines().anyMatch(line -> line.startsWith("cp ")),
                "the fingerprint verb must contain NO transfer step:\n" + argvLog);
        assertFalse(argvLog.lines().anyMatch(line -> !line.startsWith("exec ")),
                "the read-only verb may issue no other docker call:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void dumpRejectsNonCustomFormatOutput(@TempDir Path tmp) throws Exception {
        RunResult r = runScript(tmp, Map.of("FAKE_DUMP_GARBAGE", "1"),
                "dump", "--source-container", SOURCE_CONTAINER);

        assertNotEquals(0, r.exitCode,
                "a dump without the PGDMP magic must be refused:\n" + r.output);
        assertTrue(r.output.contains("PGDMP"),
                "the refusal must name the custom-format check:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void dumpWritesCustomFormatWithChecksum(@TempDir Path tmp) throws Exception {
        RunResult r = runScript(tmp, Map.of(),
                "dump", "--source-container", SOURCE_CONTAINER);

        assertEquals(0, r.exitCode, "the healthy dump drive must complete:\n" + r.output);
        assertTrue(r.output.contains("sha256:") && r.output.contains("bytes:"),
                "the dump leg must report the checksum and size:\n" + r.output);
        List<Path> dumps;
        try (var stream = Files.list(tmp.resolve("bench"))) {
            dumps = stream.filter(p -> p.getFileName().toString().endsWith(".pgc")).toList();
        }
        assertEquals(1, dumps.size(), "exactly one dump file may land in the work dir");
        assertTrue(Files.readString(dumps.getFirst()).startsWith("PGDMP"),
                "the dump must carry the custom-format magic");
    }

    // --- helpers ----------------------------------------------------------------

    /** The healthy behind-head drive: one valid applied migration (V50) leaves
     *  every later migration pending, so the flyway-CLI apply leg runs. */
    private RunResult runHealthyRestore(Path tmp) throws Exception {
        return runHealthyRestore(tmp, writeDump(tmp), historyFixture(tmp));
    }

    private RunResult runHealthyRestore(Path tmp, Path dump) throws Exception {
        return runHealthyRestore(tmp, dump, historyFixture(tmp));
    }

    private RunResult runHealthyRestore(Path tmp, Path dump, Path historyFixture)
            throws Exception {
        return runHealthyRestore(tmp, dump, historyFixture, Map.of());
    }

    private RunResult runHealthyRestore(Path tmp, Path dump, Path historyFixture,
            Map<String, String> extraEnv) throws Exception {
        Map<String, String> env = new java.util.HashMap<>();
        if (historyFixture != null) {
            env.put("FAKE_FLYWAY_HISTORY_FILE", historyFixture.toString());
        }
        env.putAll(extraEnv);
        return runScript(tmp, env,
                "restore", dump.toString(), "--project", PROJECT, "--port", TARGET_PORT);
    }

    private Path historyFixture(Path tmp) throws IOException {
        Path fixture = tmp.resolve("flyway-history.txt");
        Files.writeString(fixture, "50|V50__banned_admin_actor_checks.sql|"
                + flywayChecksum(migrationFile("V50__banned_admin_actor_checks.sql")) + "|t\n");
        return fixture;
    }

    private Path writeDump(Path tmp) throws IOException {
        Path dump = tmp.resolve("dump.pgc");
        Files.writeString(dump, "PGDMP-dummy");
        return dump;
    }

    /** Drive the real script under a controlled PATH; return exit code + combined output. */
    private RunResult runScript(Path tmp, Map<String, String> extraEnv, String... args)
            throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String tool : REAL_TOOLS) {
            Path link = bin.resolve(tool);
            if (Files.notExists(link)) {
                Files.createSymbolicLink(link, realTool(tool));
            }
        }
        Files.writeString(bin.resolve("docker"), FAKE_DOCKER);
        bin.resolve("docker").toFile().setExecutable(true);

        ProcessBuilder pb = new ProcessBuilder(
                bin.resolve("bash").toString(), scriptPath().toString());
        pb.command().addAll(List.of(args));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString());
        env.put("REPLICA_RESTORE_BENCH_DIR", tmp.resolve("bench").toString());
        env.put("FAKE_DOCKER_ARGV_LOG", tmp.resolve("docker-argv.log").toString());
        env.putAll(extraEnv);

        Process p = pb.start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        return new RunResult(rc, output);
    }

    private String argvLog(Path tmp) throws IOException {
        Path log = tmp.resolve("docker-argv.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    private Path scriptPath() {
        return repoRoot().resolve("scripts/fam-replica-restore.sh");
    }

    /** Extract the flyway_checksum function verbatim from a script (the
     *  RestoreFlywayChecksumIT extraction pattern). */
    private String extractFlywayChecksumFunction(Path script) throws IOException {
        List<String> lines = Files.readAllLines(script);
        StringBuilder fn = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (!inside && line.startsWith("flyway_checksum()")) {
                inside = true;
            }
            if (inside) {
                fn.append(line).append('\n');
                if (line.equals("}")) {
                    return fn.toString();
                }
            }
        }
        throw new IllegalStateException("flyway_checksum() not found in " + script);
    }

    /** One checkout migration file under infochat-core's migration dir. */
    private Path migrationFile(String name) {
        return repoRoot().resolve("infochat-core/src/main/resources/db/migration").resolve(name);
    }

    /** A migration's checksum per PINNED flyway-core (12.0.0 ChecksumCalculator): CRC32 over each
     *  line's UTF-8 bytes concatenated WITHOUT terminators, leading UTF-8 BOM stripped, signed int —
     *  ground truth for the fake history rows; RestoreFlywayChecksumIT pins it against a real DB. */
    private static long flywayChecksum(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        CRC32 crc = new CRC32();
        for (int i = start; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == '\n' || b == '\r') {
                continue;
            }
            crc.update(b);
        }
        long value = crc.getValue();
        return value >= (1L << 31) ? value - (1L << 32) : value;
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
