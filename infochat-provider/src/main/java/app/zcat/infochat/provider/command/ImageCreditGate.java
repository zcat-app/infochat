package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** The deterministic /image control gates in spec order (D76, commands.md
 * §Content): cooldown, then the per-user AND per-group credits charged on
 * attempt, plus the queue-depth budget predicate; refund returns both halves. */
@ApplicationScoped
public class ImageCreditGate {

    @Inject
    RateCapBucket rateCapBucket;

    /** Global backend queue-depth budget: at or above this depth the
     * command refuses immediately instead of queueing past budget. */
    @ConfigProperty(name = "infochat.image.max-queue-depth", defaultValue = "3")
    int maxQueueDepth;

    public sealed interface GateResult permits Admitted, Rejected {}

    /** Every gate yielded; the attempt may proceed (and is now charged). */
    public record Admitted() implements GateResult {}

    /** A gate refused; the bundle key and args name the friendly reply. */
    public record Rejected(String bundleKey, List<Object> interpolationArgs) implements GateResult {}

    /** Charge cooldown first, then the credit AND: a credit rejection leaves
     * the cooldown consumed (metered burst); a group-credit rejection refunds
     * the per-user token drawn alongside it (the group-LLM refund discipline). */
    public GateResult charge(UUID userId, @Nullable UUID groupId) {
        if (!rateCapBucket.tryAcquireImageCooldown(userId)) {
            return new Rejected(BundleKeys.IMAGE_ERROR_COOLDOWN,
                    List.of(Long.toString(rateCapBucket.imageCooldownRetryAfterSeconds(userId))));
        }
        if (!rateCapBucket.tryAcquireImageUserCredit(userId)) {
            return new Rejected(BundleKeys.IMAGE_ERROR_CREDITS_EXHAUSTED, List.of());
        }
        if (groupId != null && !rateCapBucket.tryAcquireImageGroupCredit(groupId)) {
            rateCapBucket.refundImageUserCredit(userId);
            return new Rejected(BundleKeys.IMAGE_ERROR_CREDITS_EXHAUSTED, List.of());
        }
        return new Admitted();
    }

    /** Return both halves of the AND gate — one refund per drawn bucket. */
    public void refund(UUID userId, @Nullable UUID groupId) {
        rateCapBucket.refundImageUserCredit(userId);
        if (groupId != null) {
            rateCapBucket.refundImageGroupCredit(groupId);
        }
    }

    /** The queue-depth budget predicate: depth is the backend's reported
     * running + pending count (the client's primitive; the decision is
     * here). */
    public boolean queueOverBudget(int queueDepth) {
        return queueDepth >= maxQueueDepth;
    }
}
