package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Pins the INVOCATION SHAPE of the adapter-identity tar in {@code prod/scripts/backup.sh}
 * (M1-587). backup.sh is upgrade.sh's step-1 safety backup; the Provider runs as root and
 * the signal-cli daemon it spawns locks its account store to mode 0700, so a non-root host
 * {@code tar} cannot read it. Run as the non-root deploy user — exactly how upgrade.sh calls
 * it — the pre-M1-587 host-side {@code tar -C /} failed {@code Permission denied} on
 * signal-cli/data and aborted the whole upgrade before any container was touched.
 *
 * <p>M1-587 brings backup.sh to parity with the M1-569 pack.sh/restore.sh fix: the identity
 * tar runs as root INSIDE a throwaway container ({@code docker run -u 0:0 --entrypoint tar}),
 * each data-dir bind-mounted READ-ONLY, so no host-side sudo is needed. This test pins that
 * shape by reading the script source — the same source-assertion style {@link RestoreWiringTest}
 * uses for restore.sh's ordering invariant. The REAL root-owned round trip (backup.sh as the
 * deploy user against a live root:root 0700 signal-cli dir → the tgz contains signal-cli/data)
 * needs real Docker + root-owned fixtures, so it is HOST validation, not mvn verify — exactly
 * as M1-569's round trip is.
 */
class BackupWiringTest {

    private String backupScript() throws Exception {
        return Files.readString(repoRoot().resolve("prod/scripts/backup.sh"));
    }

    @Test
    void adapterIdentityTarRunsAsRootInContainer() throws Exception {
        String script = backupScript();
        // The M1-569 privileged mechanism: a root (-u 0:0) throwaway container running GNU
        // tar (--entrypoint tar) from the already-pulled image, streaming the archive to the
        // host artifact via stdout. This is what lets a non-root backup read the root:root
        // 0700 signal-cli store that the pre-M1-587 host tar could not.
        assertTrue(script.contains("docker run --rm -u 0:0"),
                "the identity tar must run in a root container (docker run --rm -u 0:0):\n" + script);
        assertTrue(script.contains("--entrypoint tar"),
                "the identity tar must exec tar via --entrypoint tar:\n" + script);
        assertTrue(script.contains("\"$IDENTITY_TAR_IMAGE\""),
                "the identity tar must use the shared IDENTITY_TAR_IMAGE:\n" + script);
        assertTrue(script.contains("-czpf - \"${adapter_rel_paths[@]}\" > \"$ADAPTERS_ARTIFACT\""),
                "the container tar must stream to the host artifact via stdout redirection:\n" + script);
    }

    @Test
    void identityTarIsNotBareHostTar() throws Exception {
        String script = backupScript();
        // The pre-M1-587 shape: a host-side tar running as the deploy user, which cannot read
        // the root-owned 0700 signal-cli dir. Its removal is the fix — assert it is gone so a
        // future edit cannot silently reintroduce the Permission-denied failure.
        assertFalse(script.contains("tar -C / -czpf \"$ADAPTERS_ARTIFACT\""),
                "the bare host-side identity tar (pre-M1-587) must be gone:\n" + script);
    }

    @Test
    void identityMountsAreReadOnly() throws Exception {
        String script = backupScript();
        // backup.sh only READS the identity dirs; the bind-mounts are read-only so the
        // privileged container has no write surface onto the host (no tar-slip concern).
        assertTrue(script.contains("-v \"/$rel:/$rel:ro\""),
                "each identity data-dir must be bind-mounted read-only (:ro):\n" + script);
    }

    @Test
    void dataDirGuardAppliedBeforeBuildingMount() throws Exception {
        String script = backupScript();
        // M1-584 guard: a colon would mis-parse the new -v mount spec, a system-prefix path
        // would tar a system dir as identity material. The guard must be DEFINED and CALLED
        // before the mount is built (secrets.env is a system boundary).
        assertTrue(script.contains("reject_unsafe_data_dir() {"),
                "backup.sh must define the M1-584 data-dir guard:\n" + script);
        assertTrue(script.contains("contains ':'"),
                "the guard must reject a colon in the data-dir:\n" + script);
        assertTrue(script.contains("SYSTEM_DATA_DIR_PREFIXES"),
                "the guard must reject a system-prefix data-dir:\n" + script);
        int guardCall = script.indexOf("reject_unsafe_data_dir \"$key\" \"$dir\"");
        int dockerRun = script.indexOf("docker run --rm -u 0:0");
        assertTrue(guardCall >= 0, "the guard must be CALLED in the adapter loop:\n" + script);
        assertTrue(guardCall < dockerRun,
                "the data-dir guard must run before the -v mount is built:\n" + script);
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
