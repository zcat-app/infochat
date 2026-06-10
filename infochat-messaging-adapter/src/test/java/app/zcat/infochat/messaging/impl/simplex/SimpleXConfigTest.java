package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimpleXConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void validConfig_passes() throws IOException {
        Path binary = Files.createFile(tempDir.resolve("simplex-chat"));
        assertTrue(binary.toFile().setExecutable(true), "test setup: could not mark binary executable");

        SimpleXConfig config = new SimpleXConfig(
                binary.toString(), tempDir.toString(), SimpleXConfig.DEFAULT_WS_PORT);

        assertDoesNotThrow(config::validate);
    }

    @Test
    void missingBinary_failsStartup() {
        Path binary = tempDir.resolve("nonexistent-simplex-chat");

        SimpleXConfig config = new SimpleXConfig(
                binary.toString(), tempDir.toString(), SimpleXConfig.DEFAULT_WS_PORT);

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(ex.getMessage().contains(SimpleXConfig.BINARY_KEY),
                "message must name the offending property key: " + ex.getMessage());
    }

    @Test
    void missingDataDir_failsStartup() throws IOException {
        Path binary = Files.createFile(tempDir.resolve("simplex-chat"));
        assertTrue(binary.toFile().setExecutable(true), "test setup: could not mark binary executable");
        Path dataDir = tempDir.resolve("nonexistent-data-dir");

        SimpleXConfig config = new SimpleXConfig(
                binary.toString(), dataDir.toString(), SimpleXConfig.DEFAULT_WS_PORT);

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(ex.getMessage().contains(SimpleXConfig.DATA_DIR_KEY),
                "message must name the offending property key: " + ex.getMessage());
    }

    @Test
    void onStartup_isDormantWhenSimplexNotEnabled() {
        // A binary path validate() would reject — but the gated @Startup hook
        // must not validate when "simplex" is absent from infochat.adapters,
        // so a future CDI-index of this jar cannot fail boot for an
        // inmemory-/signal-only deployment that never configured simplex-chat.
        Path binary = tempDir.resolve("nonexistent-simplex-chat");
        SimpleXConfig config = new SimpleXConfig(
                binary.toString(), tempDir.toString(), SimpleXConfig.DEFAULT_WS_PORT);
        config.enabledAdapters = Optional.of("signal,inmemory");

        assertDoesNotThrow(config::onStartup);
    }

    @Test
    void onStartup_validatesWhenSimplexEnabled() {
        Path binary = tempDir.resolve("nonexistent-simplex-chat");
        SimpleXConfig config = new SimpleXConfig(
                binary.toString(), tempDir.toString(), SimpleXConfig.DEFAULT_WS_PORT);
        config.enabledAdapters = Optional.of("simplex");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::onStartup);
        assertTrue(ex.getMessage().contains(SimpleXConfig.BINARY_KEY),
                "gated startup must run validate() when simplex is enabled: " + ex.getMessage());
    }
}
