package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code connectContact()} on the Signal adapter (M1-620): the registered
 * account IS Signal's shareable onboarding contact and is already held
 * in-process, so the accessor answers without a signal-cli round-trip —
 * and the capability-introspection constructor, which has no account,
 * honestly answers "no shareable contact".
 */
class SignalAdapterConnectContactTest {

    @TempDir
    Path tempDir;

    @Test
    void productionConstructorReturnsConfiguredAccount() {
        SignalAdapter adapter = new SignalAdapter(
                "/usr/bin/signal-cli",
                tempDir.toString(),
                "+4712345678",
                new InetSocketAddress("127.0.0.1", 7583));
        assertEquals(Optional.of("+4712345678"), adapter.connectContact());
    }

    @Test
    void introspectionConstructorReturnsEmpty() {
        assertEquals(Optional.empty(), new SignalAdapter().connectContact());
    }
}
