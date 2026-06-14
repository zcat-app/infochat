package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignalConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void validConfig_passes() throws IOException {
        Path binary = Files.createFile(tempDir.resolve("signal-cli"));
        assertTrue(binary.toFile().setExecutable(true), "test setup: could not mark binary executable");

        SignalConfig config = new SignalConfig(binary.toString(), tempDir.toString(), "+15551234567");

        assertDoesNotThrow(config::validate);
    }

    @Test
    void missingBinary_failsStartup() {
        Path binary = tempDir.resolve("nonexistent-signal-cli");

        SignalConfig config = new SignalConfig(binary.toString(), tempDir.toString(), "+15551234567");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(ex.getMessage().contains(SignalConfig.BINARY_KEY),
                "message must name the offending property key: " + ex.getMessage());
    }

    @Test
    void missingDataDir_failsStartup() throws IOException {
        Path binary = Files.createFile(tempDir.resolve("signal-cli"));
        assertTrue(binary.toFile().setExecutable(true), "test setup: could not mark binary executable");
        Path dataDir = tempDir.resolve("nonexistent-data-dir");

        SignalConfig config = new SignalConfig(binary.toString(), dataDir.toString(), "+15551234567");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(ex.getMessage().contains(SignalConfig.DATA_DIR_KEY),
                "message must name the offending property key: " + ex.getMessage());
    }

    @Test
    void onStartup_isDormantWhenSignalNotEnabled() {
        // A binary path validate() would reject — but the gated @Startup hook
        // must not validate when "signal" is absent from infochat.adapters,
        // so a future CDI-index of this jar cannot fail boot for an
        // inmemory-/simplex-only deployment that never configured signal-cli.
        Path binary = tempDir.resolve("nonexistent-signal-cli");
        SignalConfig config = new SignalConfig(binary.toString(), tempDir.toString(), "+15551234567");
        config.enabledAdapters = Optional.of("simplex,inmemory");

        assertDoesNotThrow(config::onStartup);
    }

    @Test
    void onStartup_validatesWhenSignalEnabled() {
        Path binary = tempDir.resolve("nonexistent-signal-cli");
        SignalConfig config = new SignalConfig(binary.toString(), tempDir.toString(), "+15551234567");
        config.enabledAdapters = Optional.of("signal");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::onStartup);
        assertTrue(ex.getMessage().contains(SignalConfig.BINARY_KEY),
                "gated startup must run validate() when signal is enabled: " + ex.getMessage());
    }

    @Test
    void missingRequiredKeys_constructWithoutThrowing_butValidateNamesKey() {
        // F5: with Optional<String> injection a CDI-discovered bean whose
        // signal keys are unset constructs cleanly (Optional.empty()) instead
        // of throwing NoSuchElementException in the constructor before the
        // @PostConstruct enablement gate can run. The missing value surfaces
        // as a keyed IllegalStateException only at validate()-time.
        SignalConfig config = assertDoesNotThrow(() ->
                new SignalConfig(Optional.empty(), Optional.empty(), Optional.empty()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(ex.getMessage().contains(SignalConfig.BINARY_KEY),
                "validate() must name the missing key: " + ex.getMessage());
    }
}
