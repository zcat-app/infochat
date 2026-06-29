package app.zcat.infochat.core.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Settable {@link Clock} test double, fixed to UTC: a single component instance
 * can be advanced across a window/cooldown boundary by mutating {@code now}
 * mid-test, which a {@code Clock.fixed(...)} cannot. Shared by the
 * ThrottledAdminNotifier window tests; {@code now} is {@code volatile} so an
 * advance on the test thread is visible to a notifier reading time on another.
 */
public final class MutableClock extends Clock {
    private volatile Instant now;

    public MutableClock(Instant initial) {
        this.now = initial;
    }

    public void advance(Duration d) {
        this.now = this.now.plus(d);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
