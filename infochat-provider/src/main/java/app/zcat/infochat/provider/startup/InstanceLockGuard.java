package app.zcat.infochat.provider.startup;

import app.zcat.infochat.core.startup.AbstractInstanceLockGuard;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Single-instance enforcement guard for the Provider service (decision D41).
 * All logic lives in {@link AbstractInstanceLockGuard}; this subclass only
 * supplies the service name and schedules the held-session liveness probe.
 *
 * <p>The long-term shape is exit code {@code 42} paired with a systemd
 * {@code RestartPreventExitStatus=42} unit file
 * ({@code docs/design/07-deployment.md} §7.8.5); v1 uses {@code 1} and the
 * unit-file ticket later swaps the literal in one place.
 */
@Startup
@Priority(50)
@ApplicationScoped
public class InstanceLockGuard extends AbstractInstanceLockGuard {

    static final String SERVICE = "provider";

    @Override
    protected String serviceName() {
        return SERVICE;
    }

    // @Scheduled lives on the concrete bean rather than the core base so
    // Quarkus' build-time scheduler processor discovers it without scanning a
    // cross-module superclass method; the scheduler extension is a service-
    // module dependency, not part of the infochat-core library jar.
    @Scheduled(every = "30s")
    void probeLockSession() {
        probeHeldSession();
    }
}
