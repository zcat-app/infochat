package app.zcat.infochat.provider.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot-time coupling of the two dispatch knobs (M1-639): {@code
 * per-user-cap >= max-concurrency} must refuse boot — cap >= pool
 * silently voids the single-sender-cannot-fill-the-pool property the
 * cap exists for (M1-636 re-audit). Drives {@link
 * InterruptibleDispatcher#init()} on hand-constructed instances (the
 * CDI-managed path is init()'s only caller, so direct field assignment
 * stands in for {@code @ConfigProperty} injection). Covers ONLY the
 * new coupling check — the pre-existing {@code >= 2} / {@code >= 1}
 * gates predate this ticket and are out of its scope.
 */
class InterruptibleDispatcherValidationTest {

    private static InterruptibleDispatcher dispatcherWith(int maxConcurrency, int perUserCap) {
        InterruptibleDispatcher dispatcher = new InterruptibleDispatcher();
        dispatcher.maxConcurrency = maxConcurrency;
        dispatcher.perUserCap = perUserCap;
        return dispatcher;
    }

    @Test
    void initRefusesBootWhenPerUserCapEqualsMaxConcurrency() {
        InterruptibleDispatcher dispatcher = dispatcherWith(4, 4);
        IllegalStateException e = assertThrows(IllegalStateException.class, dispatcher::init);
        assertTrue(e.getMessage().contains("infochat.chat.dispatch.per-user-cap"),
                "refusal must name the cap knob, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("infochat.chat.dispatch.max-concurrency"),
                "refusal must name the pool knob, got: " + e.getMessage());
    }

    @Test
    void initRefusesBootWhenPerUserCapExceedsMaxConcurrency() {
        InterruptibleDispatcher dispatcher = dispatcherWith(4, 7);
        IllegalStateException e = assertThrows(IllegalStateException.class, dispatcher::init);
        assertTrue(e.getMessage().contains("infochat.chat.dispatch.per-user-cap"),
                "refusal must name the cap knob, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("infochat.chat.dispatch.max-concurrency"),
                "refusal must name the pool knob, got: " + e.getMessage());
    }

    @Test
    void initAcceptsPerUserCapBelowMaxConcurrency() {
        // The baked defaults (pool 4, cap 2) — the shape every real
        // boot runs; a false-positive refusal here would brick startup.
        InterruptibleDispatcher dispatcher = dispatcherWith(4, 2);
        dispatcher.init();
        try {
            assertEquals(2, dispatcher.perUserCap());
        } finally {
            dispatcher.shutdown();
        }
    }
}
