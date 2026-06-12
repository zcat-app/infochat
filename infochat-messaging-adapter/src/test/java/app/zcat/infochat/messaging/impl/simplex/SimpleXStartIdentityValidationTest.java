package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * U-35: {@link SimpleXAdapter#start()} validates the configured bot queue
 * address with {@link SimpleXIdentity#isWellFormed} (parity with Signal's
 * startup ACI validation), not merely a blank check. A malformed — or blank
 * — address is rejected at startup with a message naming the property key,
 * before the simplex-chat subprocess is launched.
 *
 * <p>The config is otherwise valid (an existing executable binary, a
 * writable data dir, an in-range port) so {@code cfg.validate()} passes and
 * execution reaches the identity gate; the gate throws before subprocess
 * launch, so no real simplex-chat binary is exercised.</p>
 */
@DisabledOnOs(OS.WINDOWS)
class SimpleXStartIdentityValidationTest {

    private static final String PROPERTY_KEY = "infochat.adapters.simplex.bot-queue-address";

    @TempDir
    Path tempDir;

    @Test
    void startRejectsMalformedBotQueueAddressNamingTheProperty() {
        // Non-blank but too short for a real queue address: passes the old
        // blank-only check, fails isWellFormed.
        SimpleXAdapter adapter = adapterWithBotAddress("too-short-not-well-formed");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, adapter::start);
        assertTrue(thrown.getMessage().contains(PROPERTY_KEY),
                "the rejection message must name the property key the operator"
                        + " fixes; was: " + thrown.getMessage());
    }

    @Test
    void startRejectsBlankBotQueueAddressNamingTheProperty() {
        SimpleXAdapter adapter = adapterWithBotAddress("");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, adapter::start);
        assertTrue(thrown.getMessage().contains(PROPERTY_KEY),
                "a blank address is still rejected, naming the property key;"
                        + " was: " + thrown.getMessage());
    }

    private SimpleXAdapter adapterWithBotAddress(String botQueueAddress) {
        // /bin/sh is an existing executable, tempDir is a writable directory,
        // and the port is in range — so cfg.validate() passes and start()
        // reaches the identity gate (which throws before launching anything).
        SimpleXConfig cfg = new SimpleXConfig("/bin/sh", tempDir.toString(), 12345);
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused */ },
                new SimpleXIdentity(botQueueAddress));
    }
}
