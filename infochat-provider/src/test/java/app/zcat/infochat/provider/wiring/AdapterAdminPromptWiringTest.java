package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the wizard step-6 bootstrap-admin prompt in {@code prod/scripts/6-adapter.sh}
 * (M1-441) — the fix that stops the wizard telling a single-adapter operator the
 * admin contact id is "optional; blank for none" when leaving it blank is
 * guaranteed to trip the non-empty-union gate seconds later.
 *
 * <p>The behaviour this test guards: when exactly one adapter is enabled the admin
 * prompt is REQUIRED and a blank re-prompts with the reason (immediate, local
 * feedback) instead of being accepted as 'none'; with 2+ adapters the prompt stays
 * per-adapter optional (§7.6.3) and the union gate at the end is the backstop for
 * the all-blank case. The gate itself is out of scope for this ticket and the
 * multi-adapter test asserts it still fires.
 *
 * <p>The harness drives the REAL script via {@link ProcessBuilder} with a temp
 * {@code INFOCHAT_RUNTIME_DIR} (so CONFIG_FILE/SECRETS_FILE land under the temp
 * dir) and scripted stdin — the same prod-script wiring shape as
 * {@link DoctorWiringTest}. Linux-gated: the wizard targets Linux only and the
 * script uses GNU/bash idioms.
 */
class AdapterAdminPromptWiringTest {

    // (a) single adapter + a blank admin re-prompts and does NOT fall through to
    //     the union FAIL on the first blank.
    @Test
    @EnabledOnOs(OS.LINUX)
    void singleAdapterBlankAdminRepromptsAndDoesNotHitUnionFail(@TempDir Path tmp) throws Exception {
        // simplex; binary/data-dir/ws-port/display-name defaults (blank); admin
        // blank (must re-prompt); then a valid admin id (the SimpleX '#' exercises
        // the dotenv quoting path too).
        RunResult r = runAdapter(tmp, "simplex\n\n\n\n\n\nqueueaddr#xyz\n");

        assertEquals(0, r.exitCode,
                "a single-adapter run that supplies a valid admin after a blank must succeed:\n" + r.output);
        assertTrue(r.output.contains("the only enabled adapter"),
                "a blank admin for the sole adapter must re-prompt with the required-reason:\n" + r.output);
        assertFalse(r.output.contains("FAIL: no bootstrap admin contact id was supplied"),
                "a first blank must NOT fall through to the union FAIL gate:\n" + r.output);
    }

    // (b) single adapter + a valid admin records the key and proceeds to write the
    //     adapters config.
    @Test
    @EnabledOnOs(OS.LINUX)
    void singleAdapterValidAdminRecordsKeyAndWritesConfig(@TempDir Path tmp) throws Exception {
        RunResult r = runAdapter(tmp, "simplex\n\n\n\n\nqueueaddr#xyz\n");

        assertEquals(0, r.exitCode, "a single-adapter run with a valid admin must exit 0:\n" + r.output);

        String secrets = Files.readString(tmp.resolve("runtime/secrets.env"), StandardCharsets.UTF_8);
        assertTrue(secrets.contains("INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID="),
                "the admin contact id must be recorded in secrets.env:\n" + secrets);

        String props = Files.readString(tmp.resolve("runtime/application.properties"), StandardCharsets.UTF_8);
        assertTrue(props.contains("infochat.adapters=simplex"),
                "the run must proceed to write the adapters config:\n" + props);
    }

    // (c) two adapters both blank still hits the union FAIL gate (backstop
    //     preserved — the per-prompt requirement does not catch the multi-adapter
    //     all-blank case, so the gate must).
    @Test
    @EnabledOnOs(OS.LINUX)
    void twoAdaptersBothBlankStillHitsUnionFailGate(@TempDir Path tmp) throws Exception {
        // simplex,signal; simplex binary/data-dir/ws-port/display-name + admin
        // blank; signal binary/data-dir blank; signal account (required) supplied;
        // signal admin blank. Both admins blank => union gate must abort.
        RunResult r = runAdapter(tmp, "simplex,signal\n\n\n\n\n\n\n\n+15551234567\n\n");

        assertNotEquals(0, r.exitCode,
                "two adapters with no admin on either must abort at the union gate:\n" + r.output);
        assertTrue(r.output.contains("FAIL: no bootstrap admin contact id was supplied for any chosen adapter"),
                "the multi-adapter all-blank case must hit the union FAIL gate:\n" + r.output);
    }

    // --- helpers ----------------------------------------------------------------

    private record RunResult(int exitCode, String output) {}

    /** Drive the real 6-adapter.sh with a temp runtime dir and scripted stdin. */
    private RunResult runAdapter(Path tmp, String stdin) throws Exception {
        Path repoRoot = repoRoot();
        Path runtime = tmp.resolve("runtime");

        ProcessBuilder pb = new ProcessBuilder(
                "bash", repoRoot.resolve("prod/scripts/6-adapter.sh").toString());
        pb.redirectErrorStream(true);
        pb.environment().put("INFOCHAT_RUNTIME_DIR", runtime.toString());

        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        return new RunResult(rc, output);
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
