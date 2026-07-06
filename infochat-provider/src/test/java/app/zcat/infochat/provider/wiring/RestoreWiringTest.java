package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * bring-up).</b>
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
    private static final String[] REAL_TOOLS =
            {"bash", "dirname", "mktemp", "tar", "gzip", "grep", "tail", "rm",
             "mkdir", "cp", "chmod", "tee", "sed", "cat"};
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
    // in the TempDir sandbox. No other docker verb is reached before a gate fails.
    //
    // The `compose` branch (M1-580) parametrizes the two DB probes the error-gate cases
    // reach: the pg_restore exec replays FAKE_PG_RESTORE_STDERR_FILE to stderr and exits
    // FAKE_PG_RESTORE_EXIT (default 0), and the `\dt` table probe (`-tAqc`) reports a
    // table only when FAKE_DB_TABLES=present, so the backstop is steerable per case.
    // Every invocation's argv is appended to FAKE_DOCKER_ARGV_LOG so tests can assert
    // which docker steps ran (e.g. that no image build/bring-up followed a failed gate).
    private static final String FAKE_DOCKER =
            "#!/usr/bin/env bash\n"
            + "printf '%s\\n' \"$*\" >> \"${FAKE_DOCKER_ARGV_LOG:-/dev/null}\"\n"
            + "if [[ \"$1\" == volume && \"$2\" == ls ]]; then\n"
            + "  [[ \"${FAKE_PGDATA_VOLUME:-absent}\" == present ]] && echo \"infochat_infochat-pgdata\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == run ]]; then\n"
            + "  shift\n"
            + "  saw_root_user=no; entrypoint=\"\"; image=\"\"; cmd_args=()\n"
            + "  while [[ $# -gt 0 ]]; do\n"
            + "    case \"$1\" in\n"
            + "      --rm|-i) shift ;;\n"
            + "      -u) [[ \"$2\" == 0:0 ]] && saw_root_user=yes; shift 2 ;;\n"
            + "      -v) shift 2 ;;\n"
            + "      --entrypoint) entrypoint=\"$2\"; shift 2 ;;\n"
            + "      *) image=\"$1\"; shift; cmd_args=(\"$@\"); break ;;\n"
            + "    esac\n"
            + "  done\n"
            + "  if [[ \"$entrypoint\" == tar ]]; then\n"
            + "    echo \"FAKE-DOCKER: modeled privileged untar via docker run --entrypoint tar (root-user=${saw_root_user}, image=${image})\"\n"
            + "    exec tar \"${cmd_args[@]}\"\n"
            + "  fi\n"
            + "  exit 0\n"
            + "fi\n"
            + "if [[ \"$1\" == compose ]]; then\n"
            + "  case \"$*\" in\n"
            + "    *pg_restore*)\n"
            + "      [[ -n \"${FAKE_PG_RESTORE_STDERR_FILE:-}\" ]] && printf '%s\\n' \"$(<\"$FAKE_PG_RESTORE_STDERR_FILE\")\" >&2\n"
            + "      exit \"${FAKE_PG_RESTORE_EXIT:-0}\" ;;\n"
            + "    *-tAqc*)\n"
            + "      [[ \"${FAKE_DB_TABLES:-absent}\" == present ]] && echo \"public|flyway_schema_history|table|infochat\"\n"
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
        assertTrue(script.contains("fetch_gguf \"$persisted_url\""),
                "the custom-recovery branch must fetch from the persisted URL");
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
