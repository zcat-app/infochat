package io.infochat.collector.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Hardware-profile selector for the infochat services.
 *
 * <p>The four allowed profiles map 1:1 to deployment shapes:
 * <ul>
 *   <li>{@link #LAPTOP} — operator laptop, full local stack including Ollama.</li>
 *   <li>{@link #VPS} — everything on a VPS, including local LLM.</li>
 *   <li>{@link #PI} — Raspberry-Pi-class hardware, smaller models, lower concurrency.</li>
 *   <li>{@link #REMOTE_LLM} — local DB + services, remote LLM API.</li>
 * </ul>
 *
 * <p><b>Why no separate {@code infochat.profile} key.</b> CLAUDE.md and the
 * spec consistently use the phrase "infochat.profile" — that is the
 * <i>concept</i> name. The actual configuration <i>mechanism</i> is Quarkus'
 * built-in profile system ({@code quarkus.profile} / {@code QUARKUS_PROFILE}).
 * Introducing a separate key would create two sources of truth for the active
 * profile; this enum reuses the built-in and validates that the active
 * Quarkus profile chain contains one of the four allowed names.
 */
public enum InfochatProfile {
    LAPTOP("laptop"),
    VPS("vps"),
    PI("pi"),
    REMOTE_LLM("remote-llm");

    private final String configName;

    InfochatProfile(String configName) {
        this.configName = configName;
    }

    /** The profile name as it appears in {@code %<name>.} property-file prefixes. */
    public String configName() {
        return configName;
    }

    /**
     * Look up the enum constant for a Quarkus profile name (case-insensitive).
     * Returns empty for Quarkus-internal profiles ({@code test}, {@code dev},
     * {@code prod}) and for any unrecognised value.
     */
    public static Optional<InfochatProfile> fromConfigName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (InfochatProfile p : values()) {
            if (p.configName.equals(lower)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve the active infochat profile from a Quarkus profile chain.
     *
     * <p>The chain comes from {@link SmallRyeConfig#getProfiles()} and is
     * ordered from highest to lowest priority — the active profile first,
     * then its {@code quarkus.config.profile.parent} ancestors. The first
     * entry matching a known infochat profile wins.
     *
     * <p>Fails with {@link IllegalStateException} when no entry in the chain
     * names a known infochat profile. The message lists the full chain so
     * the operator can see exactly what the application resolved.
     */
    public static InfochatProfile resolveOrThrow(List<String> profileChain) {
        if (profileChain == null || profileChain.isEmpty()) {
            throw new IllegalStateException(
                "No active Quarkus profile detected; expected one of " +
                "laptop, vps, pi, remote-llm. Set QUARKUS_PROFILE (or the " +
                "quarkus.profile system property) to one of those values.");
        }
        for (String name : profileChain) {
            Optional<InfochatProfile> matched = fromConfigName(name);
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        throw new IllegalStateException(
            "No known infochat profile in active Quarkus profile chain " +
            profileChain + "; expected one of laptop, vps, pi, remote-llm. " +
            "Set QUARKUS_PROFILE to one of those values, or configure " +
            "`%<profile>.quarkus.config.profile.parent=laptop` for a derived " +
            "profile.");
    }

    /**
     * Startup CDI bean that validates the active Quarkus profile chain on boot.
     * Fails fast (throws from {@code onStart}) if the chain does not contain
     * a known infochat profile, so a misconfigured deployment crashes loudly
     * rather than running with surprising defaults.
     *
     * <p>In {@link LaunchMode#TEST} and {@link LaunchMode#DEVELOPMENT} the
     * check is skipped: {@code @QuarkusTest} always boots under the built-in
     * {@code test} profile (which the test runner forces regardless of any
     * inherited parent) and {@code mvn quarkus:dev} boots under {@code dev}.
     * Failing in those modes would make tests impossible to run without
     * touching every test class with {@code @TestProfile} and would block
     * iterative dev. {@link LaunchMode#NORMAL} (production startup) still
     * enforces the strict check — that is the surface the operator
     * configures, and silently picking a default there would mask
     * misconfiguration.
     */
    @ApplicationScoped
    public static class Validator {
        private static final Logger LOG = Logger.getLogger(Validator.class);

        void onStart(@Observes StartupEvent event) {
            LaunchMode mode = LaunchMode.current();
            if (mode == LaunchMode.TEST || mode == LaunchMode.DEVELOPMENT) {
                LOG.infof("Skipping infochat profile validation in %s launch mode.", mode);
                return;
            }
            SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
            List<String> profileChain = config.getProfiles();
            InfochatProfile resolved = resolveOrThrow(profileChain);
            LOG.infof("Active infochat profile: %s (Quarkus profile chain: %s)",
                resolved.configName(), profileChain);
        }
    }
}
