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
 * startup. MVP InMemoryAdapter has no {@code start()} method on the
 * SPI (M1-035a froze that shape), so the per-adapter loop is a
 * no-op for InMemory and gains meaning when T3-A's SimpleX/Signal
 * beans land.</p>
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
     * Per-adapter transport startup with per-adapter resilience.
     * A failure on one adapter is logged at ERROR and does not
     * propagate; the next adapter is still attempted. MVP InMemory
     * has no transport, so this loop is a no-op shape today.
     */
    void startAllAdapters() {
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            try {
                // SPI does not yet declare start(); InMemoryAdapter has
                // no transport. The shape is preserved here so T3-A's
                // SimpleX/Signal beans can drop in their connect call
                // without re-shaping the startup ordering.
                log.info("starting adapter transport: {}", adapter.name());
            } catch (RuntimeException e) {
                SafeLog.error(log,
                        "Adapter " + adapter.name()
                                + " failed to start; continuing with the remaining adapters",
                        e);
            }
        }
    }
}
