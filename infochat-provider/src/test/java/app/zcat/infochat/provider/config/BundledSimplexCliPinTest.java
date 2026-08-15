package app.zcat.infochat.provider.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the bundled simplex-chat CLI version in the Provider image
 * (Dockerfile.jvm) to the release the adapter estate is verified against —
 * currently v7.0.0 (M1-838). Every launch-surface premise (loopback bind,
 * {@code -d} prefix semantics, exit-0-on-bad-command, provisioning flags,
 * DB-migration path) is empirical per exact binary, so a silent version
 * change voids the evidence base the spec's trust boundary #7 and the 6b
 * provisioning wizard rest on. The pin must also carry a build-time sha256
 * guarding the TLS download: the {@code sha256sum -c} layer is the
 * supply-chain control (M1-004 / M1-394), and a pin without it trusts
 * whatever upstream serves that day.
 *
 * <p>Working-directory handling follows DocumentedConfigKeyParityTest:
 * surefire runs with the module directory as the working directory, so the
 * repo root is one level up.
 */
class BundledSimplexCliPinTest {

    private static final Path REPO_ROOT = Path.of("..");
    private static final Path DOCKERFILE =
            REPO_ROOT.resolve("infochat-provider/src/main/docker/Dockerfile.jvm");

    private static final String PINNED_VERSION = "v7.0.0";

    private static final Pattern VERSION_ENV = Pattern.compile(
            "ENV\\s+SIMPLEX_CHAT_VERSION=(\\S+)");
    private static final Pattern SHA_ASSIGNMENT = Pattern.compile(
            "SIMPLEX_CHAT_SHA256=([0-9a-f]{64})");
    private static final Pattern SHA_CHECK = Pattern.compile(
            "sha256sum\\s+-c");
    private static final Pattern DOWNLOAD = Pattern.compile(
            "releases/download/\\$\\{SIMPLEX_CHAT_VERSION\\}/simplex-chat-\\S+");

    @Test
    void dockerfilePinsV700WithBuildTimeSha256() throws IOException {
        String dockerfile = read();

        // Anti-vacuity floor: the extraction below matched the real pin
        // lines, not an empty file or a renamed ENV.
        String version = firstGroup(VERSION_ENV, dockerfile);
        assertNotNull(version, () -> "No `ENV SIMPLEX_CHAT_VERSION=…` line found in "
                + DOCKERFILE + " — the pin site moved or was renamed; this gate is the "
                + "only build-time guard for the bundled CLI version.");
        assertEquals(PINNED_VERSION, version, () ->
                "The bundled simplex-chat is " + version + " but the adapter estate is "
                + "verified against " + PINNED_VERSION + ". A silent bundle change voids "
                + "every per-binary launch-surface premise (loopback bind, -d prefix, "
                + "provisioning markers) — see M1-838 and docs/design/06-messaging.md.");

        String sha = firstGroup(SHA_ASSIGNMENT, dockerfile);
        assertNotNull(sha, () -> "No `SIMPLEX_CHAT_SHA256=<64-hex>` assignment found in "
                + DOCKERFILE + " — the TLS download is unguarded; a pin without a "
                + "build-time checksum trusts whatever upstream serves that day.");
        assertNotEquals("8c33b69e3cd5691e7a7aec455fc82955347d631572f0ff2c68eb3e12f50ab655", sha,
                "The sha256 is still the v6.5.4 artifact hash — the version pin moved to "
                + PINNED_VERSION + " but the download is not guarded with the matching "
                + "v7.0.0 checksum.");

        assertTrue(SHA_CHECK.matcher(dockerfile).find(), () ->
                "No `sha256sum -c` verification layer in " + DOCKERFILE
                + " — the recorded sha is dead text unless the build fails non-zero on a "
                + "changed upstream artifact.");
        assertTrue(DOWNLOAD.matcher(dockerfile).find(), () ->
                "No `${SIMPLEX_CHAT_VERSION}`-templated release download found in "
                + DOCKERFILE + " — the version pin and the downloaded artifact have "
                + "decoupled.");
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String read() {
        try {
            return Files.readString(DOCKERFILE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + DOCKERFILE.toAbsolutePath()
                    + " (working directory " + Path.of("").toAbsolutePath() + ")", e);
        }
    }
}
