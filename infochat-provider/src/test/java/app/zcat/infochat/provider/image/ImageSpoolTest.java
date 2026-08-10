package app.zcat.infochat.provider.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain JUnit — {@link ImageSpool} + {@link ImageSpoolSweeper} over a
 * {@code @TempDir}: capacity refusal and the fixed-Clock eviction
 * decision, no tmpfs mount or scheduler. */
class ImageSpoolTest {

    @TempDir
    Path tempDir;

    @Test
    void absentSpoolDirIsAnEmptySpool() throws IOException {
        ImageSpool spool = new ImageSpool(tempDir.resolve("absent-spool"), 1_000_000L);
        Instant now = Instant.parse("2026-08-10T12:00:00Z");

        assertEquals(List.of(), spool.agedFiles(now, Duration.ofHours(1)));
        assertDoesNotThrow(() -> spool.evictAgedFiles(now, Duration.ofHours(1)));
    }

    @Test
    void refusesWritesPastTheCapacityBound() throws IOException {
        // FAILURE-MODE (analysis P3): over-capacity writes refuse cleanly,
        // no partial file left — tmpfs exhaustion is host memory exhaustion.
        ImageSpool spool = new ImageSpool(tempDir, 100L);

        spool.write("a.png", new byte[60]);
        assertThrows(ImageSpool.SpoolFullException.class,
                () -> spool.write("b.png", new byte[60]),
                "a write past the capacity bound is refused");

        assertEquals(List.of("a.png"), listing(),
                "the refused write leaves no partial file behind");
        assertEquals(60L, spool.totalBytes(),
                "the spooled total is unchanged by the refused write");
    }

    @Test
    void refusesNonBareSpoolName() throws IOException {
        // REPRODUCTION (M1-805, D75): resolve() semantics mean a '..' or
        // absolute name escapes the tmpfs spool — refused before anything
        // is created; the JDK only blocks separator names, so '..' is red.
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        Path escapeTarget = tempDir.resolveSibling("escape.png");
        assertThrows(IllegalArgumentException.class,
                () -> spool.write("..", new byte[10]),
                "a bare '..' name escaping the spool dir is refused");
        assertThrows(IllegalArgumentException.class,
                () -> spool.write("../escape.png", new byte[10]),
                "a '..' path name escaping the spool dir is refused");
        assertThrows(IllegalArgumentException.class,
                () -> spool.write(escapeTarget.toString(), new byte[10]),
                "an absolute name replacing the spool dir is refused");
        assertTrue(Files.notExists(escapeTarget),
                "no file lands outside the spool dir");
        assertEquals(List.of(), listing(),
                "the refused writes created nothing in the spool");
    }

    @Test
    void deleteOutsideSpoolIsANoOp() throws IOException {
        // P4 (M1-805): the reclaim runs in OutboundDelivery's finally, so
        // an out-of-spool path must delete nothing and throw nothing.
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        Path sibling = tempDir.resolveSibling("keep-sibling.png");
        Path absolute = tempDir.resolveSibling("keep-absolute.png");
        Files.write(sibling, new byte[10]);
        Files.write(absolute, new byte[10]);

        spool.delete("../keep-sibling.png");
        spool.delete(absolute.toString());

        assertTrue(Files.exists(sibling),
                "a '..'-sibling of the spool dir is untouched");
        assertTrue(Files.exists(absolute),
                "an unrelated absolute path is untouched");
    }

    @Test
    void sweeperEvictsAgedFilesAndKeepsFreshOnes() throws IOException {
        // P3: the sweeper is the crash guarantee; eviction is decision-time
        // logic on the injected Clock (§9) — a fixed Clock proves it.
        ImageSpool spool = new ImageSpool(tempDir, 1_000_000L);
        Instant now = Instant.parse("2026-08-08T12:00:00Z");
        Path aged = spool.write("aged.png", new byte[10]);
        Files.setLastModifiedTime(aged, FileTime.from(now.minus(Duration.ofHours(2))));
        Path fresh = spool.write("fresh.png", new byte[10]);
        Files.setLastModifiedTime(fresh, FileTime.from(now.minus(Duration.ofMinutes(30))));

        ImageSpoolSweeper sweeper = new ImageSpoolSweeper(spool, Duration.ofHours(1),
                Clock.fixed(now, ZoneOffset.UTC));
        sweeper.sweep();

        assertTrue(Files.notExists(aged), "a file older than the age bound is evicted");
        assertTrue(Files.exists(fresh), "a file inside the age bound survives");
    }

    @Test
    void concurrentWritesNeverExceedTheCapacityBound() throws Exception {
        // FAILURE-MODE (round-1 review finding 2): the capacity check and
        // the write are atomic, so concurrent jobs cannot both pass the
        // check and overshoot the RAM-backed bound.
        ImageSpool spool = new ImageSpool(tempDir, 100L);
        int workers = 2;
        int iterations = 500;
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            int id = w;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    try {
                        spool.write("w" + id + "-" + i + ".png", new byte[60]);
                    } catch (ImageSpool.SpoolFullException e) {
                        refused.incrementAndGet();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    try {
                        assertTrue(spool.totalBytes() <= 100L,
                                "the spool never exceeds the capacity bound");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        assertTrue(spool.totalBytes() <= 100L, "the final total respects the bound");
        assertTrue(refused.get() > 0,
                "at least one concurrent write is refused (the check was actually hit)");
    }

    private List<String> listing() throws IOException {
        try (var stream = Files.list(tempDir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
