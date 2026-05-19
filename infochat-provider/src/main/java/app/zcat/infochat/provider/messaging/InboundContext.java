package app.zcat.infochat.provider.messaging;

import jakarta.enterprise.context.RequestScoped;

/**
 * Per-inbound-dispatch context bean. Carries the originating
 * adapter's {@link app.zcat.infochat.messaging.MessagingAdapter#name()
 * name} from the router into any downstream collaborator that needs to
 * qualify a per-actor lookup. The {@code CommandHandler} SPI signature
 * stays at {@code handle(ScopeRef, String)} so handlers that do NOT
 * need the adapter (e.g. {@code /help}) remain adapter-agnostic; the
 * two handlers that consult the {@code users} table — which is keyed
 * by the V5 {@code (adapter, contact_id)} UNIQUE constraint — read
 * the adapter through {@link #adapterName()}.
 *
 * <p><b>Why a CDI request-scope bean rather than a SPI parameter or
 * a ThreadLocal.</b> Adding a third parameter to
 * {@code CommandHandler.handle} would ripple through every current
 * and future handler regardless of whether it needs the adapter.
 * Extending {@code ScopeRef.Dm} would push adapter identity into a
 * shared messaging-adapter SPI type that intentionally stays minimal
 * (decisions D46-era SPI freeze). A ThreadLocal would be fragile on
 * Quarkus' virtual-thread + reactive dispatch — request-scope is the
 * framework-blessed propagation mechanism for per-dispatch data.
 *
 * <p><b>Concurrency.</b> CDI's request-scope semantics give each
 * inbound dispatch its own context instance; concurrent inbound from
 * different adapters cannot collide on the {@code adapterName} field.
 * The router sets the field once on entry to {@code onMessage} before
 * any handler dispatch runs (see {@link InboundRouter#onMessage}).
 */
@RequestScoped
public class InboundContext {

    private String adapterName;

    /**
     * The originating adapter's {@code name()} for the current
     * inbound dispatch. Set by {@link InboundRouter#onMessage}
     * before any handler dispatch runs; read by handlers that
     * need to qualify a {@code users} lookup with the adapter
     * column. Returns {@code null} only if a caller invokes a
     * handler outside an inbound dispatch (test code that bypasses
     * the router must set this via {@link #setAdapterName} in setup).
     */
    public String adapterName() {
        return adapterName;
    }

    public void setAdapterName(String adapterName) {
        this.adapterName = adapterName;
    }
}
