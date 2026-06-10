package app.zcat.infochat.messaging.impl.signal;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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

    /** The provider's activated-adapter CSV — read only to gate eager validation. */
    static final String ADAPTERS_KEY = "infochat.adapters";

    /** Adapter-selection name whose enablement gates this config's eager validation. */
    static final String ADAPTER_NAME = "signal";

    private final String binary;
    private final String dataDir;
    private final String account;

    // Field-injected (not a constructor param) so the @Startup gate can read
    // it without widening the constructor the adapter-construction path uses.
    // Seeded non-null so plain-construction unit tests that call validate()
    // directly never touch a null here.
    @ConfigProperty(name = ADAPTERS_KEY)
    Optional<String> enabledAdapters = Optional.empty();

    @Inject
    SignalConfig(@ConfigProperty(name = BINARY_KEY) String binary,
                 @ConfigProperty(name = DATA_DIR_KEY) String dataDir,
                 @ConfigProperty(name = ACCOUNT_KEY) String account) {
        this.binary = binary;
        this.dataDir = dataDir;
        this.account = account;
    }

    /**
     * Eager-startup entry point, gated on adapter enablement. The
     * dormant-activation guard for the footgun in
     * {@code docs/design/07-deployment.md} §Adapter config bean
     * activation: this library jar is not CDI-indexed today, so this
     * {@code @Startup} hook does not run in the current build — but were a
     * future {@code quarkus.index-dependency} to add it, an ungated
     * {@code @PostConstruct} would run {@link #validate()}'s filesystem
     * checks (and fail boot) even for an inmemory- or simplex-only
     * deployment that never configured signal-cli. Only delegates to
     * {@link #validate()} when {@code "signal"} appears in
     * {@code infochat.adapters}.
     */
    @PostConstruct
    void onStartup() {
        if (!adapterEnabled()) {
            return;
        }
        validate();
    }

    private boolean adapterEnabled() {
        for (String name : enabledAdapters.orElse("").split(",")) {
            if (name.trim().equals(ADAPTER_NAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enforce that the operator-supplied signal-cli inputs are usable:
     * the binary exists and is executable, the data directory exists and
     * is writable, and the account is non-empty. Throws naming the
     * offending property key so an operator can fix the exact value.
     * Invoked by the enablement-gated {@link #onStartup()} (and directly
     * by callers that resolve the config); a failure fails startup.
     *
     * <p>This is a boot-time snapshot, not a standing guarantee: it proves
     * the binary and data directory were present and writable at the instant
     * it ran, but cannot prevent a later remount, deletion, or permission
     * change from making signal-cli unusable afterward. Runtime signal-cli
     * I/O failures must therefore still be handled, not assumed away. Mirrors
     * {@link app.zcat.infochat.messaging.impl.simplex.SimpleXConfig#validate()}.</p>
     *
     * @throws IllegalStateException if any check fails.
     */
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
