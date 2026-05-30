package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Operator configuration for the SimpleX adapter. Carries the three
 * simplex-chat inputs an operator must supply — the simplex-chat
 * binary path, the identity-material data directory, and the
 * WebSocket API port — and validates them via {@link #validate()}
 * before the Provider serves any traffic.
 *
 * <p>This class is intentionally a plain value object with NO
 * CDI/Quarkus annotations ({@code @ApplicationScoped},
 * {@code @ConfigMapping}, {@code @ConfigProperty},
 * {@code @PostConstruct}, etc.) in production scope, mirroring
 * {@link app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter}.
 * Reading {@code infochat.adapters.simplex.*} from Quarkus config,
 * constructing the {@link SimpleXConfig} value, and invoking
 * {@link #validate()} at startup are Provider-side responsibilities
 * (the AdapterRegistry adapter wiring, M1-035b/M1-105) — a bad config
 * fails Provider startup, not this module's compile.</p>
 */
public final class SimpleXConfig {

    /** Path to the simplex-chat binary. */
    public static final String BINARY_KEY = "infochat.adapters.simplex.binary";

    /** simplex-chat data directory (identity keys, queue state). */
    public static final String DATA_DIR_KEY = "infochat.adapters.simplex.data-dir";

    /** WebSocket API port simplex-chat listens on (simplex-chat default 5225). */
    public static final String WS_PORT_KEY = "infochat.adapters.simplex.ws-port";

    /** simplex-chat's default WebSocket API port. */
    public static final int DEFAULT_WS_PORT = 5225;

    private final String binary;
    private final String dataDir;
    private final int wsPort;

    public SimpleXConfig(@NonNull String binary, @NonNull String dataDir, int wsPort) {
        this.binary = binary;
        this.dataDir = dataDir;
        this.wsPort = wsPort;
    }

    public String binary() {
        return binary;
    }

    public String dataDir() {
        return dataDir;
    }

    public int wsPort() {
        return wsPort;
    }

    /**
     * Enforce that the operator-supplied simplex-chat inputs are
     * usable: the binary exists and is executable, the data directory
     * exists and is writable, and the WebSocket port is in the valid
     * TCP range. Throws naming the offending property key so an
     * operator can fix the exact value. Provider invokes this during
     * its startup gates; a failure here fails Provider startup.
     *
     * @throws IllegalStateException if any check fails; the message
     *         names the offending property key.
     */
    public void validate() {
        Path binaryPath = Path.of(binary);
        if (!Files.exists(binaryPath) || !Files.isExecutable(binaryPath)) {
            throw new IllegalStateException(
                    BINARY_KEY + " must point to an existing, executable simplex-chat binary: " + binary);
        }
        Path dataDirPath = Path.of(dataDir);
        if (!Files.isDirectory(dataDirPath) || !Files.isWritable(dataDirPath)) {
            throw new IllegalStateException(
                    DATA_DIR_KEY + " must be an existing, writable directory: " + dataDir);
        }
        if (wsPort < 1 || wsPort > 65535) {
            throw new IllegalStateException(
                    WS_PORT_KEY + " must be a TCP port in 1..65535: " + wsPort);
        }
    }
}
