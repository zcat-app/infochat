package app.zcat.infochat.provider.command.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit test for {@link AssetRegistry#loadBootstrapMeta()} path branches.
 * Pins the configured-but-unreadable fail-fast contract (a missing file is
 * broken intent, not opt-out) and the unset-path opt-out contract
 * (docs/spec/deployment.md §Asset bootstrap). Plain JUnit per M1-049 test pyramid.
 */
class AssetRegistryLoadTest {

    @Test
    void unreadableConfiguredFileFailsFastWithPathInMessage(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.json");
        AssetRegistry registry = new AssetRegistry();
        registry.assetsFilePath = Optional.of(missing.toString());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                registry::loadBootstrapMeta);
        assertTrue(ex.getMessage().contains(missing.toString()),
                "fatal message must identify the configured path; was: " + ex.getMessage());
    }

    @Test
    void unsetPathReturnsEmptyMetaWithoutFailing() {
        AssetRegistry registry = new AssetRegistry();
        registry.assetsFilePath = Optional.empty();

        assertEquals(0, registry.loadBootstrapMeta().size(),
                "unset path is opt-out: empty meta, asset commands disabled, no failure");
    }
}
