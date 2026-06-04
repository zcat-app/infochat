package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.MessagingAdapter;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Drives {@link AdapterRegistry#start()} once Quarkus is up. Per
 * {@code docs/design/01-architecture.md} §1.4.3 the Provider startup
 * priorities are: 50 (lock guard), 100 (Flyway), 250 (new-post
 * reconciler), 260 (new-post listener), 300 (adapter registry). This
 * bean owns the 300 slot.
 *
 * <p>The post-construct calls {@link AdapterRegistry#start()} to run
 * the six startup gates and register the inbound handler on each
 * activated adapter, then iterates the activated set to issue any
 * per-adapter transport startup. Per §6.7 per-adapter resilience: a
 * connection failure on one adapter is logged at ERROR via SLF4J
 * and the loop continues — the failure does NOT abort Provider
 * startup. The per-adapter {@code start()} dispatch lives in
 * {@link #startAllAdapters} and is reflective ({@link Class#getMethod})
 * because {@link MessagingAdapter} does not declare {@code start()}
 * on the SPI — only the production adapter classes (SimpleX, Signal)
 * carry it. InMemoryAdapter has no transport and no {@code start()}
 * method, so the loop is a no-op for it (the {@link NoSuchMethodException}
 * from the reflective lookup is the silent skip).</p>
 */
@Startup
@Priority(300)
@ApplicationScoped
public class MessagingStartup {

    private static final Logger log = LoggerFactory.getLogger(MessagingStartup.class);

    @Inject
    AdapterRegistry adapterRegistry;

    @PostConstruct
    void onStartup() {
        adapterRegistry.start();
        startAllAdapters();
    }

    /**
     * Per-adapter transport startup with per-adapter resilience. A
     * failure on one adapter is logged at ERROR and does not propagate;
     * the next adapter is still attempted (§6.7 invariant).
     *
     * <p>{@link MessagingAdapter} does not declare {@code start()} on
     * the SPI (M1-120's out-of-scope freeze: the interface stays
     * unchanged). Concrete adapter classes — {@link app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter},
     * {@link app.zcat.infochat.messaging.impl.signal.SignalAdapter} —
     * carry their own {@code start()} methods. The reflective
     * {@link Class#getMethod} lookup invokes them when present and
     * skips silently when absent ({@link app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter} has no
     * transport and exposes no {@code start()}). The catch is on
     * {@link Throwable} because reflective invocation unwraps the
     * target exception as a {@link Throwable} and the per-adapter
     * resilience invariant requires that NO subclass — checked, runtime,
     * or otherwise (e.g. SimpleX's checked
     * {@link app.zcat.infochat.messaging.MessagingException}) — abort
     * the loop.</p>
     */
    void startAllAdapters() {
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            try {
                Method startMethod;
                try {
                    startMethod = adapter.getClass().getMethod("start");
                } catch (NoSuchMethodException notPresent) {
                    // Adapter declares no start() — InMemoryAdapter is the
                    // shape today. The activation log line still fires so
                    // the operator sees the adapter has come up.
                    log.info("adapter has no transport start(): {}", adapter.name());
                    continue;
                }
                startMethod.invoke(adapter);
                log.info("started adapter transport: {}", adapter.name());
            } catch (InvocationTargetException invocationFailure) {
                SafeLog.error(log,
                        "Adapter " + adapter.name()
                                + " failed to start; continuing with the remaining adapters",
                        Objects.requireNonNullElse(invocationFailure.getCause(), invocationFailure));
            } catch (Throwable t) {
                SafeLog.error(log,
                        "Adapter " + adapter.name()
                                + " failed to start; continuing with the remaining adapters",
                        t);
            }
        }
    }
}
