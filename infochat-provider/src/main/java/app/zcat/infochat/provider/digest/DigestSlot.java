package app.zcat.infochat.provider.digest;


import java.time.Instant;
import java.util.UUID;

/**
 * Value emitted by {@link DigestScheduler} when a group's morning or
 * evening digest window is open and has not yet fired. The downstream
 * DigestWorker (M1-080b) consumes these to generate digest content.
 */
public record DigestSlot(
        UUID groupId,
        String groupTimezone,
        String slotKind,
        Instant windowStart,
        Instant windowEnd) {
}
