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
import java.util.zip.CRC32;

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
 * <p><b>Scope: the precondition gates PLUS the identity-extraction allowlist (M1-568)
 * over the M1-569 root-in-container untar PLUS the bounded pg_restore error gate
 * (M1-580, the two cases driving past the gates to the DB step) PLUS the single-owner
 * Provider-start consent gate (M1-582, the two cases driving past the DB step to
 * bring-up) PLUS the M1-584 data-dir boundary refusals (a system-prefix or colon
 * value refused in the DATA_DIR validation loop, before any mount is built) PLUS the
 * M1-819 post-pg_restore Flyway-history gate (restored flyway_schema_history checksums vs
 * the checkout's migrations; drift fails loud BEFORE model rehydration/image build).</b>
 * The gate cases fail before the identity extraction, so they never mutate the host or
 * exercise Docker for real — the fake {@code docker} only has to satisfy
 * {@code command -v docker} and the one {@code docker volume ls} probe the fresh-volume gate
 * makes. One further case ({@code extractionWritesOnlyAllowlistedIdentityDirs}) drives PAST
 * every gate to prove the extraction writes ONLY the configured adapter data-dirs and ignores
 * a tampered bundle's extra member. Since M1-569 runs the untar as root inside a throwaway
 * container ({@code docker run -u 0:0 --entrypoint tar}), the fake {@code docker} MODELS that
 * invocation: it verifies the privilege mechanism and re-execs the real {@code tar} with the
 * exact members the script named (bundle on stdin), so the allowlist's filesystem effect is
 * genuinely exercised. It stays sandboxed by pointing the data-dir at an absolute path INSIDE
 * the JUnit TempDir, so {@code tar -C /} lands back inside the sandbox (never the real host
 * root) and the fake {@code docker} makes the later pg_restore step a no-op. The
 * full happy-path ordering invariant (pg_restore into a fresh DB BEFORE the Collector's first
 * Flyway pass, model rehydration, full bring-up) remains HOST validation (ticket
 * {@code test_plan.notes}; like M1-566's post-merge live step), NOT mvn verify. Mirrors the
 * prod-script wiring precedent (DoctorWiringTest, SwitchLlmWiringTest). Linux-gated: the
 * scripts use GNU/bash idioms and target Linux only.
 */
class RestoreWiringTest {

    // Real coreutils restore.sh invokes on the way to (and through) the gates: path
    // resolution (dirname), staging (mktemp), unpack + list (tar/gzip), the dotenv/tar
    // reads (grep/tail), and the exit-trap cleanup (rm). mkdir/cp/chmod are reached only by
    // the allowlist-extraction case, which drives PAST the gates to place config/secrets and
    // extract identities (M1-568); tee by the cases that reach the pg_restore step, whose
    // stderr capture tees to the console (M1-580); sed (read_prop) and cat (the
    // single-owner gate's banner heredoc) only by the M1-582 consent-gate cases that
    // continue past the DB step into model rehydration and bring-up. Symlinked from the
    // host into the controlled bin so a restricted PATH still lets the script run.
    // sort: the ollama re-pull loop dedups model names with `sort -u`; the
    // new-format-ollama case (M1-603) is the first to reach that loop under
    // this restricted PATH.
    // awk: the M1-819 Flyway-history gate recomputes each checkout migration's
    // checksum with a dependency-free awk CRC32 (flyway_checksum).
    private static final String[] REAL_TOOLS =
            {"bash", "dirname", "mktemp", "tar", "gzip", "grep", "tail", "rm",
             "mkdir", "cp", "chmod", "tee", "sed", "cat", "sort", "awk"};
    private static final String[] TOOL_DIRS = {"/usr/bin", "/bin", "/usr/local/bin"};

    // The gates need only `command -v docker` to resolve and
    // `docker volume ls --filter name=infochat-pgdata -q` to report whether a pgdata
    // volume exists; FAKE_PGDATA_VOLUME=present trips the fresh-volume gate. The
    // allowlist-extraction case additionally reaches the M1-569 privileged untar,
    // `docker run --rm -u 0:0 -i -v ... --entrypoint tar <image> -C / -xzpf - <members>`.
    // The `run` branch MODELS it: parse past the run flags to the image, verify the
    // privilege mechanism (`-u 0:0` -> root-user=yes), then exec the REAL tar with the
    // exact -C/members the script passed (inheriting the bundle on stdin) so the M1-568
    // allowlist's filesystem effect is genuinely exercised — only the named members land,
    // in the TempDir sandbox. It also RECORDS the `-v` mount specs (M1-585) and echoes
    // them, so a test can pin that the untar bind-mounts EXACTLY the allowlisted data-dir
    // set (mount SHAPE; write-confinement stays host-validated per M1-569). No other
    // docker verb is reached before a gate fails.
    //
    // The `compose` branch (M1-580) parametrizes the DB probes: pg_restore
    // (FAKE_PG_RESTORE_EXIT/_STDERR_FILE), the `\dt` backstop (FAKE_DB_TABLES), and the M1-819
    // flyway-history probe (`flyway_schema_history` in argv; FAKE_FLYWAY_HISTORY_FILE/_EXIT).
    // M1-821 adds the Collector `up -d --wait` bring-up (FAKE_COLLECTOR_WAIT_FAIL; the
    // infochat-collector marker distinguishes it from the postgres bring-up), the `logs` call
    // (FAKE_COLLECTOR_LOGS_FILE/_FAIL), and the `--entrypoint ls` GGUF probe (FAKE_MODEL_LS_ABSENT).
    // Every invocation's argv is appended to FAKE_DOCKER_ARGV_LOG so tests can assert
    // which docker steps ran (e.g. that no image build/bring-up followed a failed gate).
    // Each recognized in-container exec (role-reconstruct psql, pg_restore, table probe)
    // additionally echoes an identifying `FAKE-DOCKER:` line (M1-585), so a driven run's
    // ORDER — infochat_admin role reconstruct strictly BEFORE pg_restore (M1-570) — is
    // behaviorally pinnable, not merely source-order checkable.
    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "printf '%s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\"\n"
            + "if [[ \"$1\" == volume && \"$2\" == ls ]]; then\n"
            + "  [[ \"${FAKE_PGDATA_VOLUME:-absent}\" == present ]] && echo \"infochat_infochat-pgdata\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == run ]]; then\n"
            + "  shift\n"
            + "  saw_root_user=no; entrypoint=\"\"; image=\"\"; cmd_args=(); mounts=()\n"
            + "  while [[ $# -gt 0 ]]; do\n"
            + "    case \"$1\" in\n"
            + "      --rm|-i) shift ;;\n"
            + "      -u) [[ \"$2\" == 0:0 ]] && saw_root_user=yes; shift 2 ;;\n"
            + "      -v) mounts+=(\"$2\"); shift 2 ;;\n"
            + "      --entrypoint) entrypoint=\"$2\"; shift 2 ;;\n"
            + "      *) image=\"$1\"; shift; cmd_args=(\"$@\"); break ;;\n"
            + "    esac\n"
            + "  done\n"
            + "  if [[ \"$entrypoint\" == tar ]]; then\n"
            + "    echo \"FAKE-DOCKER: modeled privileged untar via docker run --entrypoint tar (root-user=${saw_root_user}, image=${image})\"\n"
            + "    echo \"FAKE-DOCKER: untar mount specs: ${mounts[*]}\"\n"
            + "    exec tar \"${cmd_args[@]}\"\n"
            + "  fi\n"
            + "  if [[ \"$entrypoint\" == ls ]]; then\n"
            + "    [[ \"${FAKE_MODEL_LS_ABSENT:-}\" == 1 ]] && exit 1\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == compose ]]; then\n"
            + "  case \"$*\" in\n"
            + "    *pg_restore*)\n"
            + "      echo \"FAKE-DOCKER: exec pg_restore into fresh infochat database\"\n"
            + "      [[ -n \"${FAKE_PG_RESTORE_STDERR_FILE:-}\" ]] && printf '%s\\n' \"$(<\"$FAKE_PG_RESTORE_STDERR_FILE\")\" >&2\n"
            + "      exit \"${FAKE_PG_RESTORE_EXIT:-0}\" ;;\n"
            + "    *ON_ERROR_STOP=1*)\n"
            + "      echo \"FAKE-DOCKER: exec role-reconstruct psql (infochat_admin, ON_ERROR_STOP)\"\n"
            + "      exit 0 ;;\n"
            + "    *flyway_schema_history*)\n"
            + "      echo \"FAKE-DOCKER: exec flyway-history probe psql (M1-819 gate)\"\n"
            + "      [[ -n \"${FAKE_FLYWAY_HISTORY_FILE:-}\" ]] && printf '%s\\n' \"$(<\"$FAKE_FLYWAY_HISTORY_FILE\")\"\n"
            + "      exit \"${FAKE_FLYWAY_HISTORY_EXIT:-0}\" ;;\n"
            + "    *-tAqc*)\n"
            + "      echo \"FAKE-DOCKER: exec table-presence probe psql\"\n"
            + "      [[ \"${FAKE_DB_TABLES:-absent}\" == present ]] && echo \"public|flyway_schema_history|table|infochat\"\n"
            + "      exit 0 ;;\n"
            + "    *\"up -d --wait\"*infochat-collector*)\n"
            + "      echo \"FAKE-DOCKER: collector bring-up (--wait)\"\n"
            + "      [[ \"${FAKE_COLLECTOR_WAIT_FAIL:-}\" == 1 ]] && exit 1\n"
            + "      exit 0 ;;\n"
            + "    *logs*infochat-collector*)\n"
            + "      echo \"FAKE-DOCKER: collector log excerpt\"\n"
            + "      [[ -n \"${FAKE_COLLECTOR_LOGS_FILE:-}\" ]] && printf '%s\\n' \"$(<\"$FAKE_COLLECTOR_LOGS_FILE\")\"\n"
            + "      [[ \"${FAKE_COLLECTOR_LOGS_FAIL:-}\" == 1 ]] && exit 1\n"
            + "      exit 0 ;;\n"
            + "  esac\n"
            + "  exit 0\n"
            + "fi\n"
            + "exit 0\n";

    // The exact two notices the 2026-07-05 live round-trip produced — the ONLY pg_restore
    // errors a healthy restore emits (postgres-init pre-creates vector/pgcrypto, so the
    // dump's COMMENT ON EXTENSION statements fail ownership), and therefore the whole
    // known-ignorable set restore.sh's bounded error gate accepts (M1-580).
    private static final String IGNORABLE_PG_RESTORE_STDERR =
            "pg_restore: error: could not execute query: ERROR:  must be owner of extension pgcrypto\n"
            + "Command was: COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';\n"
            + "pg_restore: error: could not execute query: ERROR:  must be owner of extension vector\n"
            + "Command was: COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';\n"
            + "pg_restore: warning: errors ignored on restore: 2\n";

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

    @Test
    @EnabledOnOs(OS.LINUX)
    void prefixedIdentityMembersRefusedPreMutation(@TempDir Path tmp) throws Exception {
        // M1-581: the path-consistency gate must match an EXACT tar listing line, not a
        // substring. A relocated/hand-repacked bundle whose identity members sit under a
        // prefix (backup/<data-dir>/...) contains "<data-dir>/" only as a SUBSTRING of
        // "backup/<data-dir>/"; the old `grep -qF` gate false-passed it and the run then
        // aborted MID-mutation at the extraction step (tar: member not found) — after
        // config placement, violating the gate's own "Aborted before any change to this
        // host" promise. pack.sh names each data-dir as a tar member (tar -C / -czpf -
        // <rel>...), so a well-formed bundle always lists exactly "<rel>/" and the
        // exact-match gate keeps passing it.
        String dataDirAbs = tmp.resolve("idroot/signal-cli").toString(); // absolute, inside tmp
        String dataRel = dataDirAbs.substring(1);       // "${dir#/}" — strip the leading '/'
        Path bundle = buildPrefixedBundle(tmp, dataDirAbs, dataRel);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false);

        assertNotEquals(0, r.exitCode, "a prefixed-member bundle must be refused:\n" + r.output);
        assertTrue(r.output.contains("path mismatch"),
                "the refusal must reuse the existing mismatch message:\n" + r.output);
        assertTrue(r.output.contains("Aborted"),
                "the refusal must state it aborted before any change:\n" + r.output);
        // Pre-mutation: the gate fires BEFORE config placement, so nothing lands on the
        // host — the exact promise the substring gate broke.
        assertTrue(Files.notExists(tmp.resolve("runtime").resolve("secrets.env")),
                "the refusal must come BEFORE any mutation (no secrets.env placed):\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void systemPrefixDataDirRefusedPreMutation(@TempDir Path tmp) throws Exception {
        // M1-584: under the M1-569 ROOT untar the identity bind-mount `-v /$rel:/$rel`
        // is writable, so a data-dir naming a system dir would let the root tar write
        // root-owned files onto the host. restore.sh refuses such a value in the DATA_DIR
        // validation loop BEFORE any mount is built (an explicit equivalent of the EACCES
        // property the non-root untar gave for free). /etc/cron.d is the audit's worked
        // example. The refusal fires ahead of the tar-consistency grep, so buildValidBundle's
        // consistent (if ./-prefixed) identity tar is never reached.
        Path bundle = buildValidBundle(tmp, "/etc/cron.d", "etc/cron.d");

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false);

        assertNotEquals(0, r.exitCode, "a system-prefix data-dir must be refused:\n" + r.output);
        assertTrue(r.output.contains("resolves under the system prefix")
                        && r.output.contains("INFOCHAT_SIGNAL_DATA_DIR"),
                "the refusal must name the offending key and the system-prefix rule:\n" + r.output);
        // Pre-mutation: the gate fires in the DATA_DIR loop, before config placement.
        assertTrue(Files.notExists(tmp.resolve("runtime").resolve("secrets.env")),
                "the refusal must come BEFORE any mutation (no secrets.env placed):\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void colonDataDirRefusedWithNamedMessage(@TempDir Path tmp) throws Exception {
        // M1-584: ':' is docker's -v mount-spec separator, so a data-dir containing one
        // yields a mis-parsed mount and an obscure docker error. restore.sh refuses it in
        // the DATA_DIR validation loop with a message naming the constraint — a pure
        // error-message improvement for trusted-but-fallible operator config. The
        // identityTarTop is irrelevant: the colon refusal fires before the consistency gate.
        Path bundle = buildValidBundle(tmp, "/var/lib/infochat/sig:nal", "var/lib/infochat/signal");

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false);

        assertNotEquals(0, r.exitCode, "a colon-containing data-dir must be refused:\n" + r.output);
        assertTrue(r.output.contains("contains ':'")
                        && r.output.contains("INFOCHAT_SIGNAL_DATA_DIR"),
                "the refusal must name the offending key and the colon constraint:\n" + r.output);
        assertTrue(Files.notExists(tmp.resolve("runtime").resolve("secrets.env")),
                "the refusal must come BEFORE any mutation (no secrets.env placed):\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void extractionWritesOnlyAllowlistedIdentityDirs(@TempDir Path tmp) throws Exception {
        // Drive restore.sh PAST every gate so the identity extraction runs, and prove it
        // writes ONLY the allowlisted data-dir: a TAMPERED bundle carrying an extra
        // out-of-allowlist member must NOT have that member written to the host (M1-568).
        // M1-569 runs the untar as root in-container (docker run -u 0:0 --entrypoint tar);
        // the fake docker models it, so this case pins BOTH the allowlist AND the privileged
        // mechanism (acceptance item 5).
        //
        // Sandboxing: `tar -C /` extracts members relative to the real root, so the configured
        // data-dir is an ABSOLUTE path INSIDE this TempDir — its rel form ("${dir#/}") then
        // lands back inside the TempDir under `-C /`. The smuggled extra member is a SIBLING
        // under the same TempDir (NOT under the data-dir subtree), so both the positive and
        // negative assertions stay inside the sandbox and the test needs no root. The script
        // fails later at the (faked) pg_restore step; this case asserts the FILESYSTEM side
        // effects of extraction, not the exit code.
        Path idRoot = tmp.resolve("idroot");
        Path dataDir = idRoot.resolve("signal-cli");   // allowlisted (the configured data-dir)
        Path extraDir = idRoot.resolve("EVIL-EXTRA");   // out-of-allowlist sibling member
        String dataDirAbs = dataDir.toString();         // absolute, inside tmp
        String dataRel = dataDirAbs.substring(1);       // "${dir#/}" — strip the leading '/'
        String extraRel = extraDir.resolve("pwn").toString().substring(1);

        Path bundle = buildTamperedBundle(tmp, dataDirAbs, dataRel, extraRel);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false);

        // The extraction ran through the M1-569 privileged mechanism (docker run as root with
        // --entrypoint tar), not a bare host tar — pins the invocation shape (acceptance 5).
        assertTrue(r.output.contains(
                        "modeled privileged untar via docker run --entrypoint tar (root-user=yes"),
                "extraction must run via the root in-container tar (docker run -u 0:0 --entrypoint tar):\n"
                        + r.output);
        // Mount SHAPE pin (M1-585 / M1-569): the fake docker now RECORDS the -v specs it was
        // handed (it previously discarded them). The privileged untar must bind-mount EXACTLY
        // the allowlisted data-dir set read-write — dropping the mount (no -v) or widening it
        // (e.g. -v /:/host) must both fail here. Mount ENFORCEMENT — writes actually CONFINED to
        // the mounts — is NOT modeled: the fake re-execs host tar, so write-confinement stays
        // HOST validation, exactly the M1-569 ticket posture. Single configured adapter, so the
        // recorded set is exactly one spec `/<rel>:/<rel>` = `dataDirAbs:dataDirAbs`.
        String mountPrefix = "FAKE-DOCKER: untar mount specs: ";
        String recordedMounts = r.output.lines()
                .filter(line -> line.startsWith(mountPrefix))
                .map(line -> line.substring(mountPrefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the fake docker must record the privileged untar's -v mount specs:\n" + r.output));
        assertEquals(dataDirAbs + ":" + dataDirAbs, recordedMounts,
                "the privileged untar must bind-mount EXACTLY the single allowlisted data-dir "
                        + "(no dropped mount, no /:/host widening):\n" + r.output);
        // The allowlisted identity dir WAS reconstructed at its absolute path (inside tmp).
        assertTrue(Files.exists(dataDir.resolve("keyfile")),
                "the allowlisted identity dir must be extracted:\n" + r.output);
        // The out-of-allowlist member was IGNORED — never written to the host filesystem.
        assertTrue(Files.notExists(extraDir),
                "an out-of-allowlist bundle member must NOT be extracted:\n" + r.output);
    }

    @Test
    void roleReconstructionRunsAfterPostgresUpAndBeforePgRestore() throws Exception {
        // M1-570: a single-DB `pg_dump -F c` omits cluster-global roles, so the Flyway-created
        // NOLOGIN principal infochat_admin is absent on the fresh target (postgres-init mints
        // only infochat + infochat_collector + infochat_provider). The dump's ACL entries that
        // GRANT to infochat_admin then fail, and because pg_dump emits each object's GRANT set
        // as ONE atomic multi-statement command, that failure also rolls back the co-located
        // service-role grants — the Collector dies on its first heartbeat write. restore.sh must
        // reconstruct the role AFTER Postgres is up and STRICTLY BEFORE pg_restore so the ACLs
        // apply on the first restore. The real grant round trip (pg_restore emits no role error,
        // Collector boots) needs a live Postgres and stays HOST validation (test_plan.notes);
        // restore.sh is linear across these steps, so the ORDERING invariant is pinned by source
        // order here — dropping the step (marker absent) or moving it after pg_restore fails the
        // build, exactly as acceptance item 4 requires.
        String script = Files.readString(repoRoot().resolve("prod/scripts/restore.sh"));

        int postgresUpIdx = script.indexOf("\"$POSTGRES_SCRIPT\"");            // the bring-up call
        int guardIdx = script.indexOf("pg_roles WHERE rolname = 'infochat_admin'");
        int createRoleIdx = script.indexOf("CREATE ROLE infochat_admin NOLOGIN");
        int pgRestoreIdx = script.indexOf("pg_restore -h 127.0.0.1");         // the real invocation

        assertTrue(postgresUpIdx >= 0, "restore.sh must invoke the Postgres bring-up (3-postgres.sh)");
        assertTrue(createRoleIdx >= 0,
                "restore.sh must reconstruct the Flyway-created infochat_admin NOLOGIN role");
        assertTrue(pgRestoreIdx >= 0, "restore.sh must pg_restore the dump");
        assertTrue(guardIdx >= 0 && guardIdx < createRoleIdx,
                "role creation must be idempotent — guarded by a NOT EXISTS pg_roles check before "
                        + "the CREATE (mirrors V2 __roles)");
        assertTrue(postgresUpIdx < createRoleIdx,
                "infochat_admin must be reconstructed AFTER Postgres is up — postgres-init mints "
                        + "only the service roles, so the role cannot pre-exist the bring-up");
        assertTrue(createRoleIdx < pgRestoreIdx,
                "infochat_admin must be reconstructed STRICTLY BEFORE pg_restore, so the dump's "
                        + "ACL grants to it (and the co-located service-role grants) apply cleanly");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void roleReconstructRunsBeforePgRestoreInDrivenRun(@TempDir Path tmp) throws Exception {
        // M1-585: a BEHAVIORAL pin for the M1-570 ordering invariant, complementing the
        // source-order pre-check above. That check reads the script TEXT — it catches a
        // deleted or reordered step, but not a run where the steps fire out of order at
        // execution time. Here we drive restore.sh PAST the gates so it actually execs the
        // in-container commands, then prove from the fake docker's ORDERED argv log that the
        // infochat_admin role-reconstruct psql ran STRICTLY BEFORE pg_restore. The sandboxed
        // bundle drives through the DB step and ends at model rehydration (its config names no
        // local backend) — the role + pg_restore steps have both run by then; this case asserts
        // ORDER, not the exit code (like the extraction case).
        Path bundle = buildSandboxedBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false,
                Map.of("FAKE_DB_TABLES", "present"));

        // The infochat_admin DO-block psql is the only compose exec carrying ON_ERROR_STOP=1;
        // pg_restore is the only one carrying `pg_restore -h 127.0.0.1` — so the raw argv log
        // distinguishes them unambiguously and preserves their execution order.
        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        int roleArgvIdx = argvLog.indexOf("ON_ERROR_STOP=1");
        int pgRestoreArgvIdx = argvLog.indexOf("pg_restore -h 127.0.0.1");
        assertTrue(roleArgvIdx >= 0,
                "the infochat_admin role-reconstruct psql must reach the fake docker:\n" + argvLog);
        assertTrue(pgRestoreArgvIdx >= 0, "pg_restore must reach the fake docker:\n" + argvLog);
        assertTrue(roleArgvIdx < pgRestoreArgvIdx,
                "infochat_admin must be reconstructed STRICTLY BEFORE pg_restore at run time "
                        + "(fake-docker argv log order):\n" + argvLog);
        // The fake docker's per-command identifying echoes pin the same ordering on the run's
        // console output: the role-reconstruct marker must precede the pg_restore marker.
        int roleMarkerIdx = r.output.indexOf("FAKE-DOCKER: exec role-reconstruct psql");
        int pgRestoreMarkerIdx = r.output.indexOf("FAKE-DOCKER: exec pg_restore");
        assertTrue(roleMarkerIdx >= 0 && pgRestoreMarkerIdx >= 0,
                "each exec'd in-container command must echo an identifying FAKE-DOCKER line:\n" + r.output);
        assertTrue(roleMarkerIdx < pgRestoreMarkerIdx,
                "the role-reconstruct marker must precede the pg_restore marker in output:\n" + r.output);
    }

    @Test
    void restoreRecoversCustomGgufFromPersistedUrl() throws Exception {
        // M1-571: restore.sh's ensure_gguf recovers a CUSTOM (non-pinned) GGUF from the URL
        // 4-llm.sh persisted into secrets.env, instead of failing loud. The real multi-GB
        // download stays HOST validation (like the rest of this file), so pin the wiring by
        // source inspection: the custom branch must fetch from the persisted URL, the callers
        // must read INFOCHAT_LLAMACPP_GGUF_URL / _EMBED_ from the restored secrets.env, and
        // the pre-M1-571 (no persisted URL) fail-loud fallback must be preserved.
        String script = Files.readString(repoRoot().resolve("prod/scripts/restore.sh"));

        assertTrue(script.contains("elif [[ -n \"$persisted_url\" ]]; then"),
                "ensure_gguf must recover a custom GGUF when a persisted URL is present");
        // Pin the FULL three-argument invocation, not just the `fetch_gguf "$persisted_url"`
        // prefix (M1-585): a prefix-only assertion left the `"$persisted_sha"` argument — the
        // restore-side GGUF SHA-256 verification — deletable with the build still green (no
        // test anywhere referenced persisted_sha). fetch_gguf skips integrity only on an EMPTY
        // expected SHA, so dropping the arg silently disables the check for a recovered custom GGUF.
        assertTrue(script.contains("fetch_gguf \"$persisted_url\" \"$file\" \"$persisted_sha\""),
                "the custom-recovery branch must pass all three args (persisted url, file, persisted SHA) "
                        + "so restore-side GGUF SHA verification cannot be silently dropped");
        assertTrue(script.contains("bundle predates M1-571"),
                "the no-persisted-URL fail-loud fallback must be preserved for older bundles");
        assertTrue(script.contains("read_dotenv_value INFOCHAT_LLAMACPP_GGUF_URL"),
                "the generative caller must read the persisted URL from secrets.env");
        assertTrue(script.contains("read_dotenv_value INFOCHAT_LLAMACPP_EMBED_GGUF_URL"),
                "the embeddings caller must read the persisted URL from secrets.env");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void nonIgnorablePgRestoreErrorAbortsBeforeImageBuildAndBringUp(@TempDir Path tmp) throws Exception {
        // M1-580: pg_restore exiting 1 with a REAL error beyond the two ignorable notices
        // (here: disk full mid-data-load) must fail the restore BEFORE the app image
        // build / bring-up — replacing the old behavior where "at least one table landed"
        // yielded "DB restored (schema present)." over a partial clone. FAKE_DB_TABLES=
        // present is load-bearing: with tables reported, ONLY the error gate can abort,
        // so a gate regression surfaces as "DB restored" + build steps in the argv log.
        Path stderrFixture = tmp.resolve("pg-restore-stderr.txt");
        Files.writeString(stderrFixture, IGNORABLE_PG_RESTORE_STDERR
                + "pg_restore: error: could not execute query: ERROR:  could not extend file"
                + " \"base/16384/16723\": No space left on device\n");
        Path bundle = buildSandboxedBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_PG_RESTORE_EXIT", "1",
                "FAKE_PG_RESTORE_STDERR_FILE", stderrFixture.toString(),
                "FAKE_DB_TABLES", "present"));

        assertNotEquals(0, r.exitCode,
                "a non-ignorable pg_restore error must fail the restore:\n" + r.output);
        assertTrue(r.output.contains("Failing lines:")
                        && r.output.contains("No space left on device"),
                "the failure must name the non-ignorable lines:\n" + r.output);
        assertTrue(r.output.contains("INCOMPLETE"),
                "the failure must state the clone is INCOMPLETE:\n" + r.output);
        assertTrue(r.output.contains("7.10.1"),
                "the failure must point at the §7.10.1 partial-state recovery doc:\n" + r.output);
        assertFalse(r.output.contains("DB restored"),
                "a failed restore must not claim the DB was restored:\n" + r.output);
        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argvLog.contains("build") || argvLog.contains("infochat-collector")
                        || argvLog.contains("infochat-provider"),
                "no image-build/bring-up docker step may run after the failed restore:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onlyIgnorableNoticesProceedPastDbStepAndArePrinted(@TempDir Path tmp) throws Exception {
        // M1-580: pg_restore exit 1 whose stderr carries ONLY the two known extension-
        // COMMENT notices — the proven 2026-07-05 live round-trip shape — must still pass
        // the DB step (the tolerance is bounded, not removed), and the gate must print
        // the ignored count and the ignored lines themselves (never silence).
        Path stderrFixture = tmp.resolve("pg-restore-stderr.txt");
        Files.writeString(stderrFixture, IGNORABLE_PG_RESTORE_STDERR);
        Path bundle = buildSandboxedBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_PG_RESTORE_EXIT", "1",
                "FAKE_PG_RESTORE_STDERR_FILE", stderrFixture.toString(),
                "FAKE_DB_TABLES", "present"));

        assertTrue(r.output.contains("DB restored (schema present)."),
                "the ignorable-notices shape must proceed past the DB step:\n" + r.output);
        assertTrue(r.output.contains("all 2 error line(s) match the known-ignorable"),
                "the count of ignored errors must be printed:\n" + r.output);
        int ignoredReportIdx = r.output.indexOf("Ignored:");
        assertTrue(ignoredReportIdx >= 0,
                "the gate must print its ignored-lines report:\n" + r.output);
        String ignoredReport = r.output.substring(ignoredReportIdx);
        assertTrue(ignoredReport.contains("must be owner of extension pgcrypto")
                        && ignoredReport.contains("must be owner of extension vector"),
                "the ignored lines themselves must be printed in the report (not only tee'd):\n"
                        + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void nonTtyRunWithoutSourceStoppedStopsBeforeProviderStart(@TempDir Path tmp) throws Exception {
        // M1-582: the Provider is the messaging-identity consumer, so restore.sh gates its
        // start on single-owner consent. A non-TTY run (stdin here is a closed pipe) without
        // --source-stopped must stop AFTER the Collector (bring-up unaffected) and BEFORE
        // `compose up -d infochat-provider`, naming the flag — otherwise an unattended
        // restore against a still-running source opens the two-live-consumers corruption
        // window on the unrecoverable identity with zero friction.
        Path bundle = buildBringUpBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false,
                Map.of("FAKE_DB_TABLES", "present"));

        assertNotEquals(0, r.exitCode,
                "withheld consent must exit non-zero (the restore is incomplete):\n" + r.output);
        assertTrue(r.output.contains("PROVIDER NOT STARTED"),
                "the stop must print the documented withheld message:\n" + r.output);
        assertTrue(r.output.contains("--source-stopped"),
                "the withheld message must name the unattended-run flag:\n" + r.output);
        // NOT a failure recipe: the deliberate stop must not print the partial-state /
        // return-to-fresh advice (the clone's data is complete at this point).
        assertFalse(r.output.contains("PARTIAL RESTORE"),
                "a deliberate consent stop must not print the partial-state note:\n" + r.output);
        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(argvLog.contains("up -d --wait") && argvLog.contains("infochat-collector"),
                "Collector bring-up must be unaffected by the gate:\n" + argvLog);
        assertFalse(argvLog.contains("up -d infochat-provider"),
                "no provider-up docker step may run without consent:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void sourceStoppedFlagReachesProviderStart(@TempDir Path tmp) throws Exception {
        // M1-582 positive case: --source-stopped is the unattended-run consent, so the same
        // non-TTY drive-past-gates run WITH the flag must reach `compose up -d
        // infochat-provider` and run to completion.
        Path bundle = buildBringUpBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false,
                Map.of("FAKE_DB_TABLES", "present"), "--source-stopped");

        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(argvLog.contains("up -d --wait") && argvLog.contains("infochat-collector"),
                "Collector bring-up must be unaffected by the gate:\n" + argvLog);
        assertTrue(argvLog.contains("up -d infochat-provider"),
                "the Provider start must be reached with --source-stopped:\n"
                        + r.output + "\n" + argvLog);
        assertEquals(0, r.exitCode,
                "the consented run must complete end to end:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void newFormatSharedDefaultConfigClassifiesGenerativeBackendCorrectly(@TempDir Path tmp) throws Exception {
        // M1-603: the wizard now writes the generative endpoint as the SHARED
        // infochat.llm.default.base-url (no per-task chat line), so
        // rehydrate_models must classify the backend from the default key —
        // before the fix it read only infochat.llm.chat.base-url, classified a
        // new-format ollama bundle as 'remote', and never re-pulled the ollama
        // models: a restored deployment with a dead LLM. Old-format bundles
        // (per-task lines, no default key) stay covered by the chat.base-url
        // fallback the other bring-up cases exercise.
        Path bundle = buildNewFormatOllamaBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false,
                Map.of("FAKE_DB_TABLES", "present"), "--source-stopped");

        assertTrue(r.output.contains("generative backend: ollama"),
                "a new-format shared-default ollama config must classify as ollama:\n" + r.output);
        assertFalse(r.output.contains("generative backend: remote"),
                "the new-format ollama config must NOT misclassify as remote:\n" + r.output);
        assertTrue(r.output.contains("ollama pull llama3.2:3b")
                        && r.output.contains("ollama pull llama3.1:8b"),
                "the restored config's ollama models must be re-pulled:\n" + r.output);
        assertEquals(0, r.exitCode,
                "the consented new-format restore must complete end to end:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void restoredHistoryChecksumMismatchFailsLoudAfterPgRestore(@TempDir Path tmp) throws Exception {
        // M1-819 reproduction: comment-only edits to applied migrations (live 2026-08-11) left
        // the dump's history checksums diverging from the checkout; the gate must fail loud right
        // after pg_restore — version, BOTH recovery options, partial note — before model/build/start.
        Path historyFixture = tmp.resolve("flyway-history.txt");
        long drifted = flywayChecksum(migrationFile("V50__banned_admin_actor_checks.sql")) + 1;
        Files.writeString(historyFixture,
                "50|V50__banned_admin_actor_checks.sql|" + drifted + "|t\n");
        Path bundle = buildSandboxedBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_FLYWAY_HISTORY_FILE", historyFixture.toString()));

        assertNotEquals(0, r.exitCode,
                "a checksum drift between dump history and checkout must fail the restore:\n"
                        + r.output);
        assertTrue(r.output.contains("V50"),
                "the failure must name the drifted migration version:\n" + r.output);
        assertTrue(r.output.contains("source host's revision"),
                "the failure must offer recovery option (a) — a matching-revision checkout:\n"
                        + r.output);
        assertTrue(r.output.contains("UPDATE flyway_schema_history SET checksum"),
                "the failure must offer recovery option (b) — the printed flyway-repair UPDATE:\n"
                        + r.output);
        assertTrue(r.output.contains("PARTIAL RESTORE"),
                "the gate fails post-mutation, so the M1-581 partial-state note must print:\n"
                        + r.output);
        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        int pgRestoreIdx = argvLog.indexOf("pg_restore -h 127.0.0.1");
        assertTrue(pgRestoreIdx >= 0, "pg_restore must have run before the gate:\n" + argvLog);
        String afterPgRestore = argvLog.substring(pgRestoreIdx);
        assertFalse(afterPgRestore.contains("build") || afterPgRestore.contains("up -d"),
                "neither model rehydration, image build, nor any app start may follow the "
                        + "failed gate:\n" + afterPgRestore);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void failedHistoryRowsAndNonSqlRowsAreIgnored(@TempDir Path tmp) throws Exception {
        // M1-819 P3: only rows that can bite are validated. The success=false row names a script
        // ABSENT from the checkout — skipped by the success filter BEFORE any file lookup (else it
        // would trip the newer-bundle message); the non-SQL row has no V*.sql file to match.
        Path historyFixture = tmp.resolve("flyway-history.txt");
        Files.writeString(historyFixture,
                "999|V999__failed_apply_that_never_landed.sql|12345|f\n"
                + "|<< Flyway Baseline >>||t\n"
                + "50|V50__banned_admin_actor_checks.sql|"
                + flywayChecksum(migrationFile("V50__banned_admin_actor_checks.sql")) + "|t\n");
        Path bundle = buildBringUpBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_FLYWAY_HISTORY_FILE", historyFixture.toString()), "--source-stopped");

        assertEquals(0, r.exitCode,
                "failed and non-SQL history rows must not trip the gate:\n" + r.output);
        assertTrue(r.output.contains("Flyway history matches this checkout"),
                "the gate must confirm the validated history:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void appliedVersionAbsentFromCheckoutGetsNewerBundleMessage(@TempDir Path tmp) throws Exception {
        // M1-819 P3: an applied version with NO matching checkout file is a different defect
        // class than checksum drift — a newer bundle into an older checkout — with its own message.
        Path historyFixture = tmp.resolve("flyway-history.txt");
        Files.writeString(historyFixture, "999|V999__not_shipped_here.sql|42|t\n");
        Path bundle = buildSandboxedBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_FLYWAY_HISTORY_FILE", historyFixture.toString()));

        assertNotEquals(0, r.exitCode,
                "an applied migration absent from the checkout must fail the restore:\n"
                        + r.output);
        assertTrue(r.output.contains("V999") && r.output.contains("NEWER"),
                "the failure must name the version and the newer-bundle diagnosis:\n" + r.output);
        assertFalse(r.output.contains("checksum drift"),
                "the newer-bundle case must NOT use the checksum-drift wording:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void historyProbeFailureAbortsWithPartialStateNote(@TempDir Path tmp) throws Exception {
        // M1-819 P10: the probe runs under set -euo pipefail with the ERR trap armed — a gate
        // that cannot READ the history must not silently pass it: the failed SELECT aborts through
        // the normal failure path, and the single-print flag yields exactly one partial-state note.
        Path bundle = buildSandboxedBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_FLYWAY_HISTORY_EXIT", "1"));

        assertNotEquals(0, r.exitCode,
                "a failed history probe must abort the restore:\n" + r.output);
        int first = r.output.indexOf("PARTIAL RESTORE");
        assertTrue(first >= 0,
                "the post-mutation abort must print the partial-state note:\n" + r.output);
        assertEquals(-1, r.output.indexOf("PARTIAL RESTORE", first + 1),
                "the partial-state note must print exactly once:\n" + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void matchingHistoryPassesGateAndRestoreContinues(@TempDir Path tmp) throws Exception {
        // M1-819 healthy path: a history whose checksums match the checkout's
        // recomputed values passes the gate, and the run proceeds to model/build
        // steps — the argv log proves the gate did not stop the restore.
        Path historyFixture = tmp.resolve("flyway-history.txt");
        Files.writeString(historyFixture,
                "50|V50__banned_admin_actor_checks.sql|"
                + flywayChecksum(migrationFile("V50__banned_admin_actor_checks.sql")) + "|t\n"
                + "55|V55__auto_joined_group.sql|"
                + flywayChecksum(migrationFile("V55__auto_joined_group.sql")) + "|t\n");
        Path bundle = buildBringUpBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_FLYWAY_HISTORY_FILE", historyFixture.toString()), "--source-stopped");

        assertEquals(0, r.exitCode,
                "a matching history must pass the gate and complete the restore:\n" + r.output);
        assertTrue(r.output.contains("Flyway history matches this checkout (2 applied SQL"),
                "the confirmation must name the checked count:\n" + r.output);
        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(argvLog.contains("build"),
                "the model/build steps must be reached after the gate passes:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void partialStateNoteNamesHowToVerifyAndFinishCommands(@TempDir Path tmp) throws Exception {
        // M1-821 reproduction: a post-mutation failure (M1-819 drifted checksum) prints,
        // AFTER placed items + the fresh-recipe, a verify block naming 8-verify.sh and
        // both Collector log signatures (P6 order; P5: sentinel passwords never appear).
        Path historyFixture = tmp.resolve("flyway-history.txt");
        long drifted = flywayChecksum(migrationFile("V50__banned_admin_actor_checks.sql")) + 1;
        Files.writeString(historyFixture,
                "50|V50__banned_admin_actor_checks.sql|" + drifted + "|t\n");
        Path bundle = buildSandboxedBundleWithSecrets(tmp,
                "INFOCHAT_DB_PASSWORD=\"SENTINEL-DB-7f3a\"\n"
                + "INFOCHAT_COLLECTOR_PASSWORD=\"SENTINEL-COLLECTOR-7f3a\"\n"
                + "INFOCHAT_PROVIDER_PASSWORD=\"SENTINEL-PROVIDER-7f3a\"\n");

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_FLYWAY_HISTORY_FILE", historyFixture.toString()));

        assertNotEquals(0, r.exitCode,
                "the drifted history must fail the restore:\n" + r.output);
        assertTrue(r.output.contains("PARTIAL RESTORE"),
                "the post-mutation failure must print the partial-state note:\n" + r.output);
        // P6 ordering: placed items, then the return-to-fresh recipe, then the verify block.
        int placedIdx = r.output.indexOf("Placed so far:");
        int recipeIdx = r.output.indexOf("return this host to FRESH");
        int verifyIdx = r.output.indexOf("HOW TO VERIFY");
        assertTrue(placedIdx >= 0 && recipeIdx >= 0 && verifyIdx >= 0,
                "the note must print placed items, the fresh-recipe, and the verify block:\n" + r.output);
        assertTrue(placedIdx < recipeIdx && recipeIdx < verifyIdx,
                "the verify block must come AFTER the placed-items list and the return-to-fresh "
                        + "recipe (P6):\n" + r.output);
        assertTrue(r.output.contains("8-verify.sh"),
                "the verify block must name 8-verify.sh:\n" + r.output);
        assertTrue(r.output.contains("FlywayValidateException"),
                "the verify block must name the FlywayValidateException signature:\n" + r.output);
        assertTrue(r.output.contains("no password was provided")
                        && r.output.contains("MANUAL bring-up")
                        && r.output.contains("--env-file"),
                "the SCRAM signature must be framed as a manual-bring-up diagnosis naming the "
                        + "missing --env-file (P5):\n" + r.output);
        // P5: the new text references the secrets.env PATH only — no expanded secret values.
        assertFalse(r.output.contains("SENTINEL-DB-7f3a"),
                "no expanded INFOCHAT_DB_PASSWORD value may appear in printed output (P5):\n"
                        + r.output);
        assertFalse(r.output.contains("SENTINEL-COLLECTOR-7f3a"),
                "no expanded INFOCHAT_COLLECTOR_PASSWORD value may appear in printed output (P5):\n"
                        + r.output);
        assertFalse(r.output.contains("SENTINEL-PROVIDER-7f3a"),
                "no expanded INFOCHAT_PROVIDER_PASSWORD value may appear in printed output (P5):\n"
                        + r.output);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void customGgufFailurePrintsExactEnvFileBearingComposeCommands(@TempDir Path tmp) throws Exception {
        // M1-821 acceptance 2: the no-persisted-URL fail-loud message prints EXACT
        // bring-up commands — each carrying -f, --env-file, the compose() profiles — with
        // the manual GGUF-fetch docker command intact (M1-571 control).
        Path bundle = buildCustomGgufBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_MODEL_LS_ABSENT", "1"));

        assertNotEquals(0, r.exitCode,
                "a custom GGUF with no persisted URL must fail loud:\n" + r.output);
        assertTrue(r.output.contains("download URL was never persisted"),
                "the failure must name the never-persisted URL:\n" + r.output);
        assertTrue(r.output.contains("docker run --rm -u 0:0 --network host")
                        && r.output.contains("-fL -o \"/models/custom-model.gguf\""),
                "the manual GGUF-fetch docker command must stay intact (M1-571 control):\n"
                        + r.output);
        // Every printed bring-up command carries -f, --env-file, and the compose() profiles.
        // (Only the indented command lines — the verify block's prose mention of
        // "docker compose logs" must not count as a printed command.)
        List<String> composeLines = r.output.lines()
                .filter(line -> line.startsWith("        docker compose"))
                .toList();
        assertFalse(composeLines.isEmpty(),
                "the message must print exact bring-up commands:\n" + r.output);
        for (String line : composeLines) {
            assertTrue(line.contains("-f ") && line.contains("--env-file")
                            && line.contains("--profile prod"),
                    "each bring-up command must carry -f, --env-file, and --profile prod "
                            + "(compose() at restore.sh:116): " + line + "\n" + r.output);
        }
        assertTrue(r.output.contains("--profile llamacpp up -d llamacpp")
                        && r.output.contains("--profile llamacpp-embeddings up -d llamacpp-embeddings"),
                "the llamacpp service starts must carry their profile flags:\n" + r.output);
        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertFalse(argvLog.contains("build") || argvLog.contains("up -d infochat-provider"),
                "no image-build or provider-start step may follow the failed GGUF check:\n"
                        + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void collectorWaitFailurePrintsLogExcerptAndSignatures(@TempDir Path tmp) throws Exception {
        // M1-821 acceptance 3: a failing Collector --wait prints a BOUNDED log excerpt
        // (--tail 60 reached the fake), the named-signature guidance, then the partial
        // note exactly once (P10).
        Path logsFixture = tmp.resolve("collector-logs.txt");
        Files.writeString(logsFixture,
                "2026-08-15T00:00:00Z ERROR [org.flywaydb.core] Migration checksum mismatch"
                        + " for migration version 50\n"
                + "Caused by: org.flywaydb.core.api.FlywayValidateException: Validate failed\n");
        Path bundle = buildBringUpBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_COLLECTOR_WAIT_FAIL", "1",
                "FAKE_COLLECTOR_LOGS_FILE", logsFixture.toString()), "--source-stopped");

        assertNotEquals(0, r.exitCode,
                "a failing Collector --wait must fail the restore:\n" + r.output);
        assertTrue(r.output.contains("checksum mismatch for migration version 50"),
                "the bounded log excerpt must be printed:\n" + r.output);
        assertTrue(r.output.contains("Named signatures")
                        && r.output.contains("no password was provided")
                        && r.output.contains("MANUAL bring-up"),
                "the failure leg must print the named-signature guidance:\n" + r.output);
        int first = r.output.indexOf("PARTIAL RESTORE");
        assertTrue(first >= 0,
                "the post-mutation failure must print the partial-state note:\n" + r.output);
        assertEquals(-1, r.output.indexOf("PARTIAL RESTORE", first + 1),
                "the partial-state note must print exactly once:\n" + r.output);
        String argvLog = Files.readString(tmp.resolve("docker-argv.log"));
        assertTrue(argvLog.contains("logs --tail 60 infochat-collector"),
                "the log excerpt must be bounded (--tail 60 reached the fake docker):\n"
                        + argvLog);
        assertFalse(argvLog.contains("up -d infochat-provider"),
                "no Provider start may follow the failed Collector wait:\n" + argvLog);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void collectorLogsFailureDegradesToSkipLineAndSinglePartialNote(@TempDir Path tmp) throws Exception {
        // M1-821 P10 second variant: `compose logs` itself failing degrades to a skip
        // line — the original --wait failure survives and the note prints exactly once.
        Path bundle = buildBringUpBundle(tmp);

        RunResult r = runRestore(tmp, bundle, /* pgdataVolumePresent= */ false, Map.of(
                "FAKE_DB_TABLES", "present",
                "FAKE_COLLECTOR_WAIT_FAIL", "1",
                "FAKE_COLLECTOR_LOGS_FAIL", "1"), "--source-stopped");

        assertNotEquals(0, r.exitCode,
                "the original --wait failure must survive a failed logs call:\n" + r.output);
        assertTrue(r.output.contains("collecting the log excerpt failed"),
                "a failed compose logs must print the skip line (P10):\n" + r.output);
        int first = r.output.indexOf("PARTIAL RESTORE");
        assertTrue(first >= 0,
                "the partial-state note must still print:\n" + r.output);
        assertEquals(-1, r.output.indexOf("PARTIAL RESTORE", first + 1),
                "the partial-state note must print exactly once (P10):\n" + r.output);
    }

    // --- helpers ----------------------------------------------------------------

    /** Drive the real restore.sh under a controlled PATH; return exit code + combined output. */
    private RunResult runRestore(Path tmp, Path bundle, boolean pgdataVolumePresent) throws Exception {
        return runRestore(tmp, bundle, pgdataVolumePresent, Map.of());
    }

    /**
     * As {@link #runRestore(Path, Path, boolean)}, with extra environment entries for the
     * fake docker's per-case knobs (FAKE_PG_RESTORE_EXIT / _STDERR_FILE, FAKE_DB_TABLES)
     * and optional extra script arguments (e.g. {@code --source-stopped}, M1-582).
     */
    private RunResult runRestore(Path tmp, Path bundle, boolean pgdataVolumePresent,
            Map<String, String> extraEnv, String... scriptArgs) throws Exception {
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
        pb.command().addAll(List.of(scriptArgs));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString()); // restricted: only our controlled bin
        // Redirect runtime placement to the sandbox so restore.sh never reads or writes
        // the real prod/runtime; the fake docker keeps the volume probe off real Docker.
        env.put("INFOCHAT_RUNTIME_DIR", tmp.resolve("runtime").toString());
        env.put("FAKE_PGDATA_VOLUME", pgdataVolumePresent ? "present" : "absent");
        env.put("FAKE_DOCKER_ARGV_LOG", tmp.resolve("docker-argv.log").toString());
        env.putAll(extraEnv);

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

    /**
     * Build a bundle whose identities.tgz mirrors pack.sh's {@code tar -C / -czpf <rel>...}
     * (paths relative to /, no {@code ./} prefix) but is TAMPERED: it carries the allowlisted
     * {@code dataRel} data-dir subtree AND an extra {@code extraRel} sibling member that a
     * well-formed pack.sh bundle would never include. secrets.env names {@code dataDirAbs} as
     * the sole configured adapter (INFOCHAT_SIGNAL_DATA_DIR) so the path-consistency gate
     * passes and extraction is reached.
     */
    private Path buildTamperedBundle(Path tmp, String dataDirAbs, String dataRel, String extraRel)
            throws Exception {
        int uid = (dataRel + "|" + extraRel).hashCode() & 0xffff;
        Path staging = Files.createDirectories(tmp.resolve("tsrc" + uid));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIGNAL_DATA_DIR=\"" + dataDirAbs + "\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"), "quarkus.profile=vps\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        // Nested identity tar built relative to idsrc (cwd), naming the rel paths exactly as
        // pack.sh names adapter_rel_paths — so entries are `dataRel/...` and `extraRel`.
        Path idsrc = Files.createDirectories(tmp.resolve("tid" + uid));
        Files.createDirectories(idsrc.resolve(dataRel));
        Files.writeString(idsrc.resolve(dataRel).resolve("keyfile"), "id");
        Files.createDirectories(idsrc.resolve(extraRel).getParent());
        Files.writeString(idsrc.resolve(extraRel), "pwn");
        run(idsrc, "tar", "-czpf", staging.resolve("identities.tgz").toString(), dataRel, extraRel);

        Path bundle = tmp.resolve("tampered" + uid + ".tgz");
        run(staging, "tar", "-czf", bundle.toString(), ".");
        return bundle;
    }

    /**
     * Build a bundle whose identities.tgz members all sit under a {@code backup/} PREFIX
     * ({@code backup/<dataRel>/...}), as a hand-repacked or relocated bundle would — so
     * the tar listing contains {@code <dataRel>/} only as a substring of
     * {@code backup/<dataRel>/}, never as an exact line. secrets.env names
     * {@code dataDirAbs} as the sole configured adapter, so only the exact-line gate
     * distinguishes this bundle from a well-formed one (M1-581).
     */
    private Path buildPrefixedBundle(Path tmp, String dataDirAbs, String dataRel) throws Exception {
        Path staging = Files.createDirectories(tmp.resolve("psrc"));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIGNAL_DATA_DIR=\"" + dataDirAbs + "\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"), "quarkus.profile=vps\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        // Nested identity tar naming "backup" as the member, so every entry carries the
        // backup/ prefix — the shape the substring gate false-passed.
        Path idsrc = Files.createDirectories(tmp.resolve("pid"));
        Path prefixed = idsrc.resolve("backup").resolve(dataRel);
        Files.createDirectories(prefixed);
        Files.writeString(prefixed.resolve("keyfile"), "id");
        run(idsrc, "tar", "-czpf", staging.resolve("identities.tgz").toString(), "backup");

        Path bundle = tmp.resolve("prefixed-bundle.tgz");
        run(staging, "tar", "-czf", bundle.toString(), ".");
        return bundle;
    }

    /**
     * Build a well-formed bundle whose configured identity data-dir is an absolute path
     * INSIDE the TempDir, so a run that drives past every gate keeps the modeled root
     * untar ({@code tar -C /}) inside the sandbox — the same sandboxing as
     * {@link #buildTamperedBundle}, without the tampered extra member. The M1-580
     * error-gate cases use it because they must genuinely reach (and pass) the DB step.
     */
    private Path buildSandboxedBundle(Path tmp) throws Exception {
        String dataDirAbs = tmp.resolve("idroot/signal-cli").toString();
        String dataRel = dataDirAbs.substring(1);       // "${dir#/}" — strip the leading '/'
        Path staging = Files.createDirectories(tmp.resolve("csrc"));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIGNAL_DATA_DIR=\"" + dataDirAbs + "\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"), "quarkus.profile=vps\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        // Nested identity tar naming the rel path exactly as pack.sh names
        // adapter_rel_paths (relative to /, no ./ prefix) so the allowlisted extraction
        // matches the member by name.
        Path idsrc = Files.createDirectories(tmp.resolve("cid"));
        Files.createDirectories(idsrc.resolve(dataRel));
        Files.writeString(idsrc.resolve(dataRel).resolve("keyfile"), "id");
        run(idsrc, "tar", "-czpf", staging.resolve("identities.tgz").toString(), dataRel);

        Path bundle = tmp.resolve("clean-bundle.tgz");
        run(staging, "tar", "-czf", bundle.toString(), ".");
        return bundle;
    }

    /** As {@link #buildSandboxedBundle}, but with a caller-supplied secrets.env body so a
     * test can plant SENTINEL password values and assert they never leak (M1-821 P5). */
    private Path buildSandboxedBundleWithSecrets(Path tmp, String secretsEnv) throws Exception {
        String dataDirAbs = tmp.resolve("idroot/signal-cli").toString();
        String dataRel = dataDirAbs.substring(1);       // "${dir#/}" — strip the leading '/'
        Path staging = Files.createDirectories(tmp.resolve("ssrc"));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                secretsEnv + "INFOCHAT_SIGNAL_DATA_DIR=\"" + dataDirAbs + "\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"), "quarkus.profile=vps\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        // Nested identity tar naming the rel path exactly as pack.sh names
        // adapter_rel_paths (relative to /, no ./ prefix) so the allowlisted extraction
        // matches the member by name.
        Path idsrc = Files.createDirectories(tmp.resolve("ssid"));
        Files.createDirectories(idsrc.resolve(dataRel));
        Files.writeString(idsrc.resolve(dataRel).resolve("keyfile"), "id");
        run(idsrc, "tar", "-czpf", staging.resolve("identities.tgz").toString(), dataRel);

        Path bundle = tmp.resolve("sentinel-bundle.tgz");
        run(staging, "tar", "-czf", bundle.toString(), ".");
        return bundle;
    }

    /**
     * As {@link #buildSandboxedBundle} (same TempDir-sandboxed identity dir), but with an
     * application.properties whose backend endpoints let {@code rehydrate_models} succeed
     * under the fake docker — remote generative (no model step) + llama.cpp embeddings
     * (the fake's {@code --entrypoint ls} probe reports the GGUF present, so no fetch) —
     * so the run continues past the DB step into the Collector/Provider bring-up the
     * M1-582 consent-gate cases assert on. The M1-580 cases keep the plain sandboxed
     * bundle, whose config makes them end at the model step exactly as before.
     */
    private Path buildBringUpBundle(Path tmp) throws Exception {
        String dataDirAbs = tmp.resolve("idroot/signal-cli").toString();
        String dataRel = dataDirAbs.substring(1);       // "${dir#/}" — strip the leading '/'
        Path staging = Files.createDirectories(tmp.resolve("gsrc"));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIGNAL_DATA_DIR=\"" + dataDirAbs + "\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"),
                "quarkus.profile=vps\n"
                + "infochat.llm.chat.base-url=https://api.example.com/v1\n"
                + "infochat.embeddings.base-url=http://llamacpp-embeddings:8080/v1\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        Path idsrc = Files.createDirectories(tmp.resolve("gid"));
        Files.createDirectories(idsrc.resolve(dataRel));
        Files.writeString(idsrc.resolve(dataRel).resolve("keyfile"), "id");
        run(idsrc, "tar", "-czpf", staging.resolve("identities.tgz").toString(), dataRel);

        Path bundle = tmp.resolve("bringup-bundle.tgz");
        run(staging, "tar", "-czf", bundle.toString(), ".");
        return bundle;
    }

    /**
     * As {@link #buildBringUpBundle}, but with a NEW-format (M1-603 shared-default)
     * application.properties: the generative endpoint lives ONLY in
     * {@code infochat.llm.default.base-url} (ollama), with the per-task model lines
     * the ollama re-pull reads — the config shape the reworked wizard writes.
     */
    private Path buildNewFormatOllamaBundle(Path tmp) throws Exception {
        String dataDirAbs = tmp.resolve("idroot/signal-cli").toString();
        String dataRel = dataDirAbs.substring(1);       // "${dir#/}" — strip the leading '/'
        Path staging = Files.createDirectories(tmp.resolve("nfsrc"));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIGNAL_DATA_DIR=\"" + dataDirAbs + "\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"),
                "quarkus.profile=vps\n"
                + "infochat.llm.default.base-url=http://ollama:11434/v1\n"
                + "infochat.llm.security.model=llama3.2:3b\n"
                + "infochat.llm.chat.model=llama3.1:8b\n"
                + "infochat.embeddings.base-url=http://ollama:11434/v1\n"
                + "infochat.embeddings.model=nomic-embed-text\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        Path idsrc = Files.createDirectories(tmp.resolve("nfid"));
        Files.createDirectories(idsrc.resolve(dataRel));
        Files.writeString(idsrc.resolve(dataRel).resolve("keyfile"), "id");
        run(idsrc, "tar", "-czpf", staging.resolve("identities.tgz").toString(), dataRel);

        Path bundle = tmp.resolve("newformat-bundle.tgz");
        run(staging, "tar", "-czf", bundle.toString(), ".");
        return bundle;
    }

    /** As {@link #buildBringUpBundle}, but secrets.env names a CUSTOM generative GGUF with
     * no persisted URL (pre-M1-571 shape) — rehydrate_models then reaches the
     * no-persisted-URL fail-loud message (M1-821 acceptance 2). */
    private Path buildCustomGgufBundle(Path tmp) throws Exception {
        String dataDirAbs = tmp.resolve("idroot/signal-cli").toString();
        String dataRel = dataDirAbs.substring(1);       // "${dir#/}" — strip the leading '/'
        Path staging = Files.createDirectories(tmp.resolve("usrc"));
        Files.createDirectories(staging.resolve("db"));
        Files.createDirectories(staging.resolve("runtime"));
        Files.writeString(staging.resolve("db/infochat.pgc"), "PGDMP-dummy");
        Files.writeString(staging.resolve("runtime/secrets.env"),
                "INFOCHAT_DB_PASSWORD=\"pw\"\nINFOCHAT_SIGNAL_DATA_DIR=\"" + dataDirAbs + "\"\n"
                + "INFOCHAT_LLAMACPP_GGUF=\"custom-model.gguf\"\n");
        Files.writeString(staging.resolve("runtime/application.properties"),
                "quarkus.profile=vps\n"
                + "infochat.llm.chat.base-url=http://llamacpp:8080/v1\n"
                + "infochat.embeddings.base-url=http://llamacpp-embeddings:8080/v1\n");
        Files.writeString(staging.resolve("runtime/bootstrap-sources.json"), "[]\n");

        Path idsrc = Files.createDirectories(tmp.resolve("uid"));
        Files.createDirectories(idsrc.resolve(dataRel));
        Files.writeString(idsrc.resolve(dataRel).resolve("keyfile"), "id");
        run(idsrc, "tar", "-czpf", staging.resolve("identities.tgz").toString(), dataRel);

        Path bundle = tmp.resolve("custom-gguf-bundle.tgz");
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
