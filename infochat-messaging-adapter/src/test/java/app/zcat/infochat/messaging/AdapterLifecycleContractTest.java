package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.messaging.impl.signal.SignalAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter;
import org.junit.jupiter.api.Test;

/**
 * Cross-adapter contract for the SPI lifecycle methods:
 * {@link MessagingAdapter#start()} / {@link MessagingAdapter#stop()}
 * are no-op defaults so transportless adapters need no override, and
 * stop() is idempotent — stopping a never-started (or already-stopped)
 * adapter must not throw on any adapter.
 */
class AdapterLifecycleContractTest {

    @Test
    void transportlessAdapterInheritsNoOpLifecycleDefaults() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        assertDoesNotThrow(adapter::start,
                "InMemoryAdapter has no transport; the SPI default start() must be a no-op");
        assertDoesNotThrow(adapter::stop,
                "the SPI default stop() must be a no-op");
    }

    @Test
    void stopIsIdempotentOnNeverStartedProductionAdapters() {
        SimpleXAdapter simplex = new SimpleXAdapter();
        assertDoesNotThrow(simplex::stop,
                "stop() on a never-started SimpleX adapter must be a no-op");
        assertDoesNotThrow(simplex::stop,
                "a second stop() must be equally a no-op (idempotency)");

        SignalAdapter signal = new SignalAdapter();
        assertDoesNotThrow(signal::stop,
                "stop() on a never-started Signal adapter must be a no-op");
        assertDoesNotThrow(signal::stop,
                "a second stop() must be equally a no-op (idempotency)");
    }
}
