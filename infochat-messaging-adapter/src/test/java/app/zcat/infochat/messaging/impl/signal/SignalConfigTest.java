package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
