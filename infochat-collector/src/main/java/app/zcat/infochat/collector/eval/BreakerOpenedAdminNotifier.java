package app.zcat.infochat.collector.eval;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.impl.LlmHttpSupport;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerOpenedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Raises the throttled admin notification when an LLM circuit breaker opens; the shared
 * DB-row throttle coalesces both services' emissions under one key (ThrottledAdminNotifier). */
@ApplicationScoped
public class BreakerOpenedAdminNotifier {

    // Sync twin: app.zcat.infochat.provider.chat.BreakerOpenedAdminNotifier — keep the
    // observer body identical; only package and comments may differ (M1-834 P7).
    static final String ERROR_CLASS_BREAKER_OPEN = "llm-breaker-open";

    private final ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    BreakerOpenedAdminNotifier(ThrottledAdminNotifier throttledAdminNotifier) {
        this.throttledAdminNotifier = throttledAdminNotifier;
    }

    void onBreakerOpened(@Observes LlmCircuitBreakerOpenedEvent event) {
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_BREAKER_OPEN + ":" + event.transportKind() + ":"
                + LlmHttpSupport.redactUserInfo(event.endpoint()),
            ERROR_CLASS_BREAKER_OPEN,
            "LLM circuit breaker OPEN for " + LlmHttpSupport.redactUserInfo(event.endpoint())
                + " (" + event.transportKind() + " SPI): calls short-circuit without an HTTP "
                + "attempt until recovery; user-facing degrade: chat error.chat.unavailable");
    }
}
