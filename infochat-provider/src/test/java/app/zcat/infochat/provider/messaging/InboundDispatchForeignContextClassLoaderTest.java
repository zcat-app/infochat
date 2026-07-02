package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * M1-543 / live finding F-live-1 — the first real inbound DM crashed the
 * Provider inbound handler at {@code SimpleXAdminClaim} ARC bean creation
 * and was silently dropped per D37, on every inbound, while the whole CI
 * suite was green.
 *
 * <p><b>The live wiring difference this test captures.</b> Adapter transports
 * invoke the registry-installed inbound handler on threads they own — for
 * SimpleX a virtual {@code simplex-inbound-dispatch} thread created lazily by
 * a JDK-internal HttpClient WebSocket listener thread — whose <i>context
 * classloader is not the application classloader</i>. The MicroProfile Config
 * lookup that {@code @ConfigProperty} field injection performs at lazy ARC
 * bean creation resolves the {@code Config} by the thread context
 * classloader, so the first inbound that triggered
 * {@code SimpleXAdminClaim_Bean.create} threw SmallRye's
 * no-config-for-classloader {@code IllegalArgumentException}, wrapped by ARC
 * in a bare {@code RuntimeException}. Every pre-existing {@code @QuarkusTest}
 * (e.g. {@code SimpleXAdminClaimTokenTest}) dispatches from the JUnit thread,
 * whose context classloader is the application classloader — which is exactly
 * why the crash was live-only.</p>
 *
 * <p>The repro therefore dispatches through the real registry-installed
 * handler ({@link InMemoryAdapter#deliverDm}) from a <i>virtual thread whose
 * context classloader is a parentless classloader</i> (no {@code Config} is
 * registered for it, same as the live dispatch thread). Red before the
 * M1-543 fix (the create-time {@code RuntimeException} propagates and the
 * message yields no reply); green after (the registry pins the application
 * classloader around every adapter callback, the bean creates, and the DM is
 * routed: claim, then invite fall-through to the fixed
 * {@code error.invite.required} reply).</p>
 *
 * <p>Own profile: a fresh Quarkus instance guarantees {@code
 * SimpleXAdminClaim} has not already been created by another test class on a
 * well-loadered thread — the crash only exists at first creation. The
 * profile also defines the admin-token property, matching the live config
 * shape (the crash happens upstream of the property's value lookup, but the
 * fix must work with the property present, as live).</p>
 */
@QuarkusTest
@TestProfile(InboundDispatchForeignContextClassLoaderTest.Profile.class)
class InboundDispatchForeignContextClassLoaderTest {

    @Inject InMemoryAdapter adapter;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void resetAdapter() {
        // The @ApplicationScoped adapter is shared by both test methods; each
        // dispatch leaves a reply in sentMessages, so isolate per method.
        adapter.reset();
    }

    @Test
    void firstInboundFromForeignClassLoaderThreadIsRoutedNotDropped() throws Exception {
        // Per-run-unique contact id: the invite fall-through increments the
        // per-contact brute-force counter, and a repeated id would accumulate
        // counts across suite runs on a persistent dev database.
        String contactId = "m1-543-repro-" + UUID.randomUUID();

        Throwable thrown = dispatchOnForeignClassLoaderThread(
                () -> adapter.deliverDm(contactId, "m1-543 not-a-token not-an-invite"));

        assertNull(thrown,
                "F-live-1: inbound dispatch from a foreign-context-classloader thread must not"
                        + " throw at SimpleXAdminClaim ARC bean creation; got: " + thrown);
        // Routed, not dropped: an unknown-contact DM whose body is neither the
        // admin token nor an invite UUID falls through claim to the invite
        // path and gets the fixed reply. A D37-dropped message would leave
        // sentMessages empty.
        assertEquals(List.of(bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED)),
                adapter.sentMessages().stream().map(m -> m.text()).toList(),
                "the DM must be routed (claim then invite fall-through), producing the fixed"
                        + " invite-required reply — not silently dropped");
    }

    @Test
    void callerClassLoaderIsRestoredAfterDispatch() throws Exception {
        String contactId = "m1-543-restore-" + UUID.randomUUID();
        AtomicReference<ClassLoader> observedAfterDispatch = new AtomicReference<>();
        ClassLoader foreign = foreignClassLoader();

        Throwable thrown = runOnVirtualThreadWithClassLoader(foreign, () -> {
            adapter.deliverDm(contactId, "m1-543 restore probe");
            observedAfterDispatch.set(Thread.currentThread().getContextClassLoader());
        });

        assertNull(thrown, "dispatch must not throw; got: " + thrown);
        // The registry pin is scoped to the callback: after the handler
        // returns, the adapter-owned thread must see its own classloader
        // again, not a leaked application classloader.
        assertSame(foreign, observedAfterDispatch.get(),
                "the caller's context classloader must be restored after the dispatch");
    }

    private Throwable dispatchOnForeignClassLoaderThread(Runnable dispatch) throws Exception {
        return runOnVirtualThreadWithClassLoader(foreignClassLoader(), dispatch);
    }

    /**
     * Runs {@code work} on a virtual thread (the live dispatch thread is
     * virtual) whose context classloader is {@code loader}, and returns the
     * throwable the work died with, or null on success.
     */
    private static Throwable runOnVirtualThreadWithClassLoader(
            ClassLoader loader, Runnable work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().name("m1-543-repro-dispatch").unstarted(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.setContextClassLoader(loader);
        thread.start();
        thread.join();
        return failure.get();
    }

    /**
     * A parentless classloader: no {@code Config} is registered for it, the
     * same condition the live JDK-created dispatch thread's context
     * classloader is in.
     */
    private static ClassLoader foreignClassLoader() {
        return new URLClassLoader(new URL[0], null);
    }

    /**
     * Adds only the SimpleX admin-token (live config shape); the base
     * {@code %test} profile supplies {@code infochat.adapters=inmemory} and
     * the inmemory bootstrap admin. A distinct profile also forces a fresh
     * Quarkus instance, so the claim bean's first creation happens in this
     * test — the F-live-1 crash exists only at first creation.
     */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("infochat.adapters.simplex.admin-token",
                    "m1-543-live-shape-token");
        }
    }
}
