package app.zcat.infochat.messaging.impl.signal;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Test double for the liveness-probe scheduler: captures the task
 *  {@link SignalJsonRpcClient#connect()} schedules; fired executions run on
 *  this scheduler's own {@code signal-liveness-probe} thread. */
final class ControllableProbeScheduler extends ScheduledThreadPoolExecutor {

    private final AtomicReference<Runnable> captured = new AtomicReference<>();
    private final AtomicReference<Thread> lastExecutionThread = new AtomicReference<>();
    private final AtomicLong scheduleInvocations = new AtomicLong();
    private volatile long initialDelayMillis = -1L;
    private volatile long periodMillis = -1L;

    ControllableProbeScheduler() {
        super(1, r -> {
            Thread t = new Thread(r, "signal-liveness-probe");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        captured.set(command);
        initialDelayMillis = unit.toMillis(initialDelay);
        periodMillis = unit.toMillis(delay);
        scheduleInvocations.incrementAndGet();
        // A far-future placeholder the client can cancel; it never runs.
        return super.schedule(() -> { }, 1, TimeUnit.DAYS);
    }

    /** Run the captured probe task once on this scheduler's own thread; the
     *  returned latch releases when the task completes. */
    CountDownLatch fireCaptured() {
        Runnable command = captured.get();
        if (command == null) {
            throw new AssertionError("no liveness task captured — connect() did not schedule the probe");
        }
        CountDownLatch completed = new CountDownLatch(1);
        execute(() -> {
            lastExecutionThread.set(Thread.currentThread());
            try {
                command.run();
            } finally {
                completed.countDown();
            }
        });
        return completed;
    }

    long scheduleInvocations() {
        return scheduleInvocations.get();
    }

    long initialDelayMillis() {
        return initialDelayMillis;
    }

    long periodMillis() {
        return periodMillis;
    }

    @Nullable Thread lastExecutionThread() {
        return lastExecutionThread.get();
    }
}
