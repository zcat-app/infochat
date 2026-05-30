package app.zcat.infochat.messaging.impl.signal;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Operator configuration for the Signal adapter. Carries the three
 * signal-cli inputs an operator must supply — the signal-cli binary
 * path, its data directory, and the registered account — and validates
 * them before the Provider serves any traffic.
 *
 * <p>{@code @ApplicationScoped @Startup} makes this an eager bean:
 * Quarkus instantiates it at Provider boot and runs {@link #validate()}
 * ({@code @PostConstruct}) immediately, so a misconfigured signal-cli
 * fails startup rather than surfacing on the first inbound Signal
 * message. The constructor reads {@code infochat.adapters.signal.*} via
 * {@code @ConfigProperty}; making the Provider <em>discover</em> this
 * bean (jandex / {@code quarkus.index-dependency}) is Provider-side
 * wiring (M1-035b/M1-105), not this module.</p>
 */
@ApplicationScoped
@Startup
public class SignalConfig {

    /** Path to the signal-cli binary. */
    public static final String BINARY_KEY = "infochat.adapters.signal.binary";

    /** signal-cli data directory (identity keys, sessions, group state). */
    public static final String DATA_DIR_KEY = "infochat.adapters.signal.data-dir";

    /** Registered signal-cli account (phone number or account identifier). */
    public static final String ACCOUNT_KEY = "infochat.adapters.signal.account";

    private final String binary;
    private final String dataDir;
    private final String account;

    @Inject
    SignalConfig(@ConfigProperty(name = BINARY_KEY) String binary,
                 @ConfigProperty(name = DATA_DIR_KEY) String dataDir,
                 @ConfigProperty(name = ACCOUNT_KEY) String account) {
        this.binary = binary;
        this.dataDir = dataDir;
        this.account = account;
    }

    /**
     * Enforce that the operator-supplied signal-cli inputs are usable:
     * the binary exists and is executable, the data directory exists and
     * is writable, and the account is non-empty. Throws naming the
     * offending property key so an operator can fix the exact value.
     * Runs eagerly at Provider boot via {@code @Startup}; a failure here
     * fails startup.
     *
     * @throws IllegalStateException if any check fails.
     */
    @PostConstruct
    public void validate() {
        Path binaryPath = Path.of(binary);
        if (!Files.exists(binaryPath) || !Files.isExecutable(binaryPath)) {
            throw new IllegalStateException(
                    BINARY_KEY + " must point to an existing, executable signal-cli binary: " + binary);
        }
        Path dataDirPath = Path.of(dataDir);
        if (!Files.isDirectory(dataDirPath) || !Files.isWritable(dataDirPath)) {
            throw new IllegalStateException(
                    DATA_DIR_KEY + " must be an existing, writable directory: " + dataDir);
        }
        if (account.isBlank()) {
            throw new IllegalStateException(
                    ACCOUNT_KEY + " must be a non-empty signal-cli account identifier");
        }
    }
}
