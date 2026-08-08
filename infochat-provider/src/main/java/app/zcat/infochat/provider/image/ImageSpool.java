package app.zcat.infochat.provider.image;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

/** The tmpfs image spool (D74; design 06-messaging.md §6.2.4). Provider
 * owns the file lifecycle: spool, hand to the adapter, reclaim on
 * completion — the age sweeper is the crash guarantee (P3). */
@ApplicationScoped
public class ImageSpool {

    private final Path dir;
    private final long capacityBytes;

    @Inject
    public ImageSpool(
            @ConfigProperty(name = "infochat.image.spool.dir") Path dir,
            @ConfigProperty(name = "infochat.image.spool.capacity-bytes") long capacityBytes) {
        this.dir = dir;
        this.capacityBytes = capacityBytes;
    }

    /** Spool {@code bytes} as {@code fileName}; refuses with
     * {@link SpoolFullException} before any file is created when the
     * total would exceed the bound. Synchronized: check+write atomic. */
    public synchronized Path write(String fileName, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        if (totalBytes() + bytes.length > capacityBytes) {
            throw new SpoolFullException(
                    "spooled " + totalBytes() + " bytes + " + bytes.length
                            + " would exceed capacity " + capacityBytes);
        }
        Path spoolDir = dir.toAbsolutePath().normalize();
        // Refuse non-bare names before anything is created: resolve()
        // would let a '..' or absolute name escape the tmpfs spool
        // (D75, M1-805) — a caller bug (IAE), never a capacity outcome.
        if (fileName == null || fileName.isEmpty()
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || !spoolDir.resolve(fileName).normalize().startsWith(spoolDir)) {
            throw new IllegalArgumentException(
                    "spool name must be bare and resolve inside the spool dir: "
                            + fileName);
        }
        // Write to a temp sibling then move, so a crash mid-write never
        // leaves a half-written file visible to the adapter (the sweeper
        // reclaims orphaned temp files by age like any other).
        Path temp = Files.createTempFile(dir, fileName + ".", ".part");
        try {
            Files.write(temp, bytes);
            Path target = dir.resolve(fileName);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** Reclaim the spool file (delete-on-completion, messaging.md
     * §Required SPI surface). Idempotent — a missing file is a no-op,
     * so a completed delivery racing the sweeper cannot fail. */
    public void delete(String filePath) {
        try {
            Path spoolDir = dir.toAbsolutePath().normalize();
            // Out-of-spool paths are no-ops — the reclaim runs in the
            // delivery finally and must never throw (M1-805).
            Path resolved = spoolDir.resolve(filePath).normalize();
            if (!resolved.startsWith(spoolDir)) {
                return;
            }
            Files.deleteIfExists(resolved);
        } catch (IOException e) {
            // A failed reclaim is not a delivery failure: the sweeper
            // remains the guarantee. Log nothing user-visible; the sweeper
            // will reclaim by age.
        }
    }

    /** Total spooled bytes (regular files only, not directory entries). */
    public long totalBytes() throws IOException {
        long total = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    total += Files.size(entry);
                }
            }
        }
        return total;
    }

    /** The spool files older than {@code now - maxAge} — the sweeper's
     * candidate set. Time is passed in, never read here, so the decision
     * runs on the injected app-wide {@code Clock} (§9, M1-444 pattern). */
    public java.util.List<Path> agedFiles(Instant now, Duration maxAge) throws IOException {
        Instant cutoff = now.minus(maxAge);
        java.util.List<Path> aged = new java.util.ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)
                        && Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
                    aged.add(entry);
                }
            }
        }
        return aged;
    }

    /** Delete every spool file older than {@code now - maxAge}. Races a
     * concurrent {@link #delete} — both are idempotent. */
    public void evictAgedFiles(Instant now, Duration maxAge) throws IOException {
        for (Path aged : agedFiles(now, maxAge)) {
            Files.deleteIfExists(aged);
        }
    }

    /** The write would exceed the configured capacity; raised BEFORE
     * anything is written, so no partial file is left behind. */
    public static final class SpoolFullException extends IOException {
        public SpoolFullException(String message) {
            super(message);
        }
    }
}
