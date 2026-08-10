package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-JUnit tests for {@link ImageCreditGate} over the real
 * {@link RateCapBucket} seam with a fixed clock (§9): the D76 gates in spec
 * order — cooldown in DM AND group, the credit AND with paired refund, queue budget. */
class ImageCreditGateTest {

    private static final class SettableClock extends Clock {
        Instant now = Instant.parse("2026-08-10T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private SettableClock clock;
    private ImageCreditGate gate;
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void buildGate() {
        clock = new SettableClock();
        gate = new ImageCreditGate();
        gate.maxQueueDepth = 3;
        gate.rateCapBucket = new RateCapBucket(clock, RateCapBucket.Settings.defaults()
                .withImageUserCreditBucket(2, Duration.ofHours(1))
                .withImageGroupCreditBucket(2, Duration.ofHours(1))
                .withImageCooldownWindow(Duration.ofSeconds(15)));
    }

    @Test
    void cooldownAppliesInDmAndGroup() {
        assertTrue(gate.charge(userId, null) instanceof ImageCreditGate.Admitted,
                "the first DM attempt passes");

        ImageCreditGate.GateResult dmAgain = gate.charge(userId, null);
        assertTrue(dmAgain instanceof ImageCreditGate.Rejected,
                "an immediate second DM attempt trips the cooldown");
        assertEquals(BundleKeys.IMAGE_ERROR_COOLDOWN,
                ((ImageCreditGate.Rejected) dmAgain).bundleKey());

        clock.now = clock.now.plusSeconds(5);
        ImageCreditGate.GateResult groupAttempt = gate.charge(userId, groupId);
        assertTrue(groupAttempt instanceof ImageCreditGate.Rejected,
                "the cooldown is keyed per USER — it spans DM and group alike");
        assertEquals(BundleKeys.IMAGE_ERROR_COOLDOWN,
                ((ImageCreditGate.Rejected) groupAttempt).bundleKey());

        clock.now = clock.now.plusSeconds(11);
        assertTrue(gate.charge(userId, groupId) instanceof ImageCreditGate.Admitted,
                "once the full window elapsed, the group attempt passes");
    }

    @Test
    void creditGateIsAnAndAndRefundReturnsBoth() {
        gate.rateCapBucket = new RateCapBucket(clock, RateCapBucket.Settings.defaults()
                .withImageUserCreditBucket(1, Duration.ofHours(1))
                .withImageGroupCreditBucket(1, Duration.ofHours(1))
                .withImageCooldownWindow(Duration.ofMillis(1)));

        assertTrue(gate.charge(userId, groupId) instanceof ImageCreditGate.Admitted,
                "both buckets yield on the first attempt");

        clock.now = clock.now.plusMillis(2);
        ImageCreditGate.GateResult second = gate.charge(userId, groupId);
        assertTrue(second instanceof ImageCreditGate.Rejected,
                "with both buckets drained the AND gate refuses");
        assertEquals(BundleKeys.IMAGE_ERROR_CREDITS_EXHAUSTED,
                ((ImageCreditGate.Rejected) second).bundleKey());

        gate.refund(userId, groupId);
        clock.now = clock.now.plusMillis(2);
        assertTrue(gate.charge(userId, groupId) instanceof ImageCreditGate.Admitted,
                "one paired refund returns BOTH halves of the AND gate");
    }

    @Test
    void groupCreditRejectRefundsThePerUserToken() {
        gate.rateCapBucket = new RateCapBucket(clock, RateCapBucket.Settings.defaults()
                .withImageUserCreditBucket(2, Duration.ofHours(1))
                .withImageGroupCreditBucket(1, Duration.ofHours(1))
                .withImageCooldownWindow(Duration.ofMillis(1)));

        assertTrue(gate.charge(userId, groupId) instanceof ImageCreditGate.Admitted);

        clock.now = clock.now.plusMillis(2);
        ImageCreditGate.GateResult second = gate.charge(userId, groupId);
        assertTrue(second instanceof ImageCreditGate.Rejected,
                "the group bucket is empty, so the AND gate refuses");
        assertEquals(BundleKeys.IMAGE_ERROR_CREDITS_EXHAUSTED,
                ((ImageCreditGate.Rejected) second).bundleKey());

        clock.now = clock.now.plusMillis(2);
        assertTrue(gate.charge(userId, null) instanceof ImageCreditGate.Admitted,
                "the group-side reject refunded the per-user token drawn alongside it, "
                        + "so a fixed reply never drains the sender's personal budget");
    }

    @Test
    void queueOverBudgetRefusesImmediatelyAndRefunds() {
        assertFalse(gate.queueOverBudget(2), "below the budget depth the gate admits");
        assertTrue(gate.queueOverBudget(3), "AT the budget depth the gate refuses");
        assertTrue(gate.queueOverBudget(4), "above the budget depth the gate refuses");

        assertTrue(gate.charge(userId, groupId) instanceof ImageCreditGate.Admitted,
                "the attempt was charged before the queue read");
        assertTrue(gate.queueOverBudget(5));
        gate.refund(userId, groupId);

        clock.now = clock.now.plusSeconds(16);
        assertTrue(gate.charge(userId, groupId) instanceof ImageCreditGate.Admitted,
                "the over-budget refusal refunded the attempt — the budget is whole again");
    }
}
