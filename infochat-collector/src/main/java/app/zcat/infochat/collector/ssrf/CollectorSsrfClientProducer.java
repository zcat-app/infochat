package app.zcat.infochat.collector.ssrf;

import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * CDI producer for the collector's shared {@link SsrfGuardedHttpClient}.
 *
 * <p>{@link SsrfGuardedHttpClient} lives in {@code infochat-ssrf}, a
 * foundational library module that carries no CDI dependency, so the class
 * cannot annotate itself as a bean — and it is {@code final}, so it cannot
 * be a normal-scoped (proxied) bean either. This producer supplies one
 * shared, default-strict instance through CDI so collector consumers obtain
 * the SSRF guard by injection rather than constructing their own: a single
 * managed client that a test can override (a CDI alternative/mock) and that
 * future consumers share.
 *
 * <p>The produced instance is the no-arg default-strict guard — the real
 * {@link app.zcat.infochat.ssrf.IpBlocklist} plus the M1-025/M1-026 timeout,
 * body-cap, and redirect-cap defaults. Behavior is identical to a direct
 * {@code new SsrfGuardedHttpClient()}; only the ownership changes (CDI-managed
 * instead of field-constructed).
 *
 * <p>{@code @Singleton} (not {@code @ApplicationScoped}) is required because
 * the bean type is {@code final}: a pseudo-scope needs no client proxy, so a
 * final type is legal, whereas a normal scope would fail at build time trying
 * to subclass the final class.
 */
@ApplicationScoped
public class CollectorSsrfClientProducer {

    @Produces
    @Singleton
    public SsrfGuardedHttpClient ssrfGuardedHttpClient() {
        return new SsrfGuardedHttpClient();
    }
}
