package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.provider.health.AdapterConnectionState;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives {@link AdapterRegistry#start()} once Quarkus is up. Per
 * {@code docs/design/01-architecture.md} §1.4.3 the Provider startup
 * priorities are: 50 (lock guard), 100 (Flyway), 250 (new-post
 * reconciler), 260 (new-post listener), 300 (adapter registry). This
 * bean owns the 300 slot.
 *
 * <p>The post-construct calls {@link AdapterRegistry#start()} to run
 * the six startup gates and register the inbound handler on each
 * activated adapter, then iterates the activated set to issue
 * per-adapter transport startup via the SPI's
 * {@link MessagingAdapter#start()} (a no-op default for transportless
 * adapters such as InMemoryAdapter). Per §6.7 per-adapter resilience:
 * a connection failure on one adapter is logged at ERROR via SLF4J
 * and the loop continues — the failure does NOT abort Provider
 * startup.</p>
 */
@Startup
@Priority(300)
@ApplicationScoped
public class MessagingStartup {

    private static final Logger log = LoggerFactory.getLogger(MessagingStartup.class);

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    AdapterConnectionState connectionState;

    @PostConstruct
    void onStartup() {
        adapterRegistry.start();
        startAllAdapters();
    }

    /**
     * Per-adapter transport startup with per-adapter resilience. A
     * failure on one adapter is logged at ERROR and does not propagate;
     * the next adapter is still attempted (§6.7 invariant). The catch
     * spans {@link Exception} — both the SPI's checked
     * {@link app.zcat.infochat.messaging.MessagingException} (SimpleX)
     * and the runtime exceptions Signal's start path raises — because
     * the resilience invariant requires that no adapter failure abort
     * the loop. JVM-level {@link Error}s propagate: a broken JVM is not
     * a per-adapter condition.
     *
     * <p>Each adapter's start outcome is recorded in
     * {@link AdapterConnectionState} so the readiness probe can apply
     * the §6.7 / deployment-spec rule "ready when at least one enabled
     * adapter is connected". The reset before the loop mirrors
     * {@link AdapterRegistry#start()}'s idempotent-restart clearing of
     * its activated set: a re-run must not retain connected flags for
     * adapters no longer activated.</p>
     */
    void startAllAdapters() {
        connectionState.reset();
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            try {
                adapter.start();
                connectionState.reportStarted(adapter.name());
                log.info("started adapter transport: {}", adapter.name());
            } catch (Exception e) {
                connectionState.reportFailed(adapter.name());
                SafeLog.error(log,
                        "Adapter " + adapter.name()
                                + " failed to start; continuing with the remaining adapters",
                        e);
            }
        }
    }
}
