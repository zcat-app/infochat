package app.zcat.infochat.provider.image;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Age-based sweeper for the tmpfs {@link ImageSpool} — the crash
 * guarantee for delete-on-completion (P3). Eviction time comes from the
 * injected app-wide {@code Clock} (§9, M1-444 pattern). */
@ApplicationScoped
public class ImageSpoolSweeper {

    private static final Logger log = LoggerFactory.getLogger(ImageSpoolSweeper.class);

    private final ImageSpool spool;
    private final Duration maxAge;
    private final Clock clock;

    @Inject
    public ImageSpoolSweeper(
            ImageSpool spool,
            @ConfigProperty(name = "infochat.image.spool.max-age") Duration maxAge,
            Clock clock) {
        this.spool = spool;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Scheduled(every = "{infochat.image.spool.sweep-interval:15m}")
    void sweep() {
        try {
            spool.evictAgedFiles(clock.instant(), maxAge);
        } catch (IOException e) {
            // A failed sweep must not take down the scheduler; the next
            // cadence retries, and the spool's capacity refusal bounds the
            // worst case in the meantime.
            log.warn("Image spool sweep failed", e);
        }
    }
}
