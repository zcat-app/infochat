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

    private final Optional<String> binary;
    private final Optional<String> dataDir;
    private final Optional<String> account;

    // Field-injected (not a constructor param) so the @Startup gate can read
    // it without widening the constructor the adapter-construction path uses.
    // Seeded non-null so plain-construction unit tests that call validate()
    // directly never touch a null here.
    @ConfigProperty(name = ADAPTERS_KEY)
    Optional<String> enabledAdapters = Optional.empty();

    // CDI binds this constructor (the @Inject one). The required keys are
    // injected as Optional<String> with no defaultValue so a CDI-indexed jar
    // whose signal keys are unset constructs cleanly (Optional.empty())
    // instead of throwing NoSuchElementException before the @PostConstruct
    // enablement gate runs — the values are validated at use (validate()) for
    // an activated adapter only.
    @Inject
    SignalConfig(@ConfigProperty(name = BINARY_KEY) Optional<String> binary,
                 @ConfigProperty(name = DATA_DIR_KEY) Optional<String> dataDir,
                 @ConfigProperty(name = ACCOUNT_KEY) Optional<String> account) {
        this.binary = binary;
        this.dataDir = dataDir;
        this.account = account;
    }

    // Convenience constructor for unit tests, which already hold concrete,
    // present values. Not @Inject — CDI uses the Optional constructor above.
    SignalConfig(String binary, String dataDir, String account) {
        this(Optional.of(binary), Optional.of(dataDir), Optional.of(account));
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
        // require() resolves each Optional value and throws naming the key if
        // it was never set (the validate-at-use half of the CDI optionality
        // fix), so a missing key surfaces as a keyed IllegalStateException
        // here rather than a NoSuchElementException at construction.
        String binaryValue = require(binary, BINARY_KEY);
        Path binaryPath = Path.of(binaryValue);
        if (!Files.exists(binaryPath) || !Files.isExecutable(binaryPath)) {
            throw new IllegalStateException(
                    BINARY_KEY + " must point to an existing, executable signal-cli binary: " + binaryValue);
        }
        String dataDirValue = require(dataDir, DATA_DIR_KEY);
        Path dataDirPath = Path.of(dataDirValue);
        if (!Files.isDirectory(dataDirPath) || !Files.isWritable(dataDirPath)) {
            throw new IllegalStateException(
                    DATA_DIR_KEY + " must be an existing, writable directory: " + dataDirValue);
        }
        if (require(account, ACCOUNT_KEY).isBlank()) {
            throw new IllegalStateException(
                    ACCOUNT_KEY + " must be a non-empty signal-cli account identifier");
        }
    }

    private static String require(Optional<String> value, String key) {
        return value.orElseThrow(() -> new IllegalStateException(
                key + " must be set for an activated signal adapter"));
    }
}
