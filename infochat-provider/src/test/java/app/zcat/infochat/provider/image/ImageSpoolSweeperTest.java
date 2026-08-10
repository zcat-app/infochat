package app.zcat.infochat.provider.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImageSpoolSweeperTest {

    @TempDir
    Path tempDir;

    @Test
    void sweepCompletesWhenTheSpoolIsAbsent() throws IOException {
        Path spoolDir = tempDir.resolve("absent-spool");
        ImageSpool spool = new ImageSpool(spoolDir, 1_000_000L);
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        ImageSpoolSweeper sweeper = new ImageSpoolSweeper(spool, Duration.ofHours(1),
                Clock.fixed(now, ZoneOffset.UTC));

        assertDoesNotThrow(sweeper::sweep);

        Files.createDirectories(spoolDir);
        Path aged = spool.write("aged.png", new byte[10]);
        Files.setLastModifiedTime(aged, FileTime.from(now.minus(Duration.ofHours(2))));

        sweeper.sweep();

        assertFalse(Files.exists(aged), "a real aged file is still evicted");
    }
}
