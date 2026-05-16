package io.infochat.collector.eval.stage2;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Collector-side @Startup bean that emits the
 * {@code release-on-stage2-failure=true} operator-posture warning
 * + audit row per {@code docs/design/04-security.md} §4.7. Fires
 * once per process start, only when the flag is effective for the
 * active profile (laptop / pi by default; vps / remote-llm leave
 * it false).
 *
 * <h2>Why the WARN matters</h2>
 * <p>{@code release-on-stage2-failure=true} is a deliberate
 * profile-specific trade-off: when the LLM judge is down, posts
 * are released with Stage 1 redactions only. Per
 * {@code docs/spec/security.md} §Ingest pipeline, Stage 1 is a
 * coarse English-language pattern matcher — multilingual,
 * paraphrased, base64-encoded, and otherwise obfuscated injection
 * content bypasses Stage 1 by design. On the profiles where this
 * flag is true, the operator is honouring that trade-off in
 * exchange for keeping the bot useful when Ollama crashes under
 * memory pressure. The WARN makes the trade-off audible at boot;
 * the audit row makes the operating posture reconstructible from
 * audit history per the design wording "written to the audit_log
 * once per process start ... so the operating posture is
 * reconstructible from audit history."
 *
 * <h2>Doc-bug routing</h2>
 * <p>The design wording at §4.7 says "the Provider emits a
 * prominent WARN-level startup line." Stage 2 runs in the
 * Collector, so the WARN belongs on the Collector startup chain.
 * The "Provider" naming in §4.7 is a doc bug to be flagged in a
 * separate {@code spec:} commit; in this ticket the WARN + audit
 * row land on the Collector.
 *
 * <h2>@Priority slot</h2>
 * <p>{@code @Priority(150)} sits between Flyway (100) and
 * OutboxRehydrator (300) — the audit table is queryable as soon
 * as Flyway finishes, and the WARN posts before the first feed
 * fetch fires. Co-located with {@link io.infochat.llm.routing.LlmRouterStartupGuard}
 * (also priority 150); neither depends on the other.
 */
@Startup
@Priority(150)
@ApplicationScoped
public class StartupReleaseOnStage2FailureWarn {

    /**
     * Audit verb pinned at the {@code audit_log.action} value the
     * spec design commits to. Held as a public constant so the
     * audit-write helper and the IT both reference one literal.
     */
    public static final String AUDIT_VERB = "STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE";

    private static final Logger LOG = Logger.getLogger(StartupReleaseOnStage2FailureWarn.class);

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "infochat.security.release-on-stage2-failure")
    boolean releaseOnStage2Failure;

    @ConfigProperty(name = "infochat.profile.label", defaultValue = "unknown")
    String profileLabel;

    @PostConstruct
    void onStartup() {
        if (!releaseOnStage2Failure) {
            // The flag is off — no WARN, no audit row. The vps /
            // remote-llm profiles take this branch by default.
            return;
        }

        LOG.warnf("infochat.security.release-on-stage2-failure=true (profile=%s): "
                + "when the Stage 2 LLM judge is unreachable, posts will be released with Stage 1 "
                + "redactions only. Stage 1 is an English-language coarse filter — multilingual or "
                + "obfuscated injection content may reach LLM call sites with only the Stage 1 "
                + "redactions applied. See docs/spec/security.md §Failure handling.",
            profileLabel);

        try {
            writeAuditRow();
        } catch (SQLException e) {
            // Fail @Startup loudly: an unwritten audit row leaves
            // the operating posture unreconstructible from history,
            // which the design's "reconstructible from audit
            // history" commitment forbids. Throwing here aborts
            // Collector startup — preferable to silently dropping
            // the audit commitment.
            throw new IllegalStateException(
                "StartupReleaseOnStage2FailureWarn: failed to INSERT audit_log row", e);
        }
    }

    private void writeAuditRow() throws SQLException {
        String targetId = hostPidTargetId();
        String detailsJson = "{\"profile\":\"" + jsonEscape(profileLabel) + "\"}";

        final String sql =
            "INSERT INTO audit_log ("
                + "  actor_user_id, actor_contact_id, actor_adapter,"
                + "  action, target_kind, target_id, target_contact_id,"
                + "  scope_id, request_id, details_json"
                + ") VALUES ("
                + "  NULL, NULL, NULL,"
                + "  ?, 'system', ?, NULL,"
                + "  NULL, NULL, ?::jsonb"
                + ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, AUDIT_VERB);
            ps.setString(2, targetId);
            ps.setObject(3, detailsJson, Types.OTHER);
            ps.executeUpdate();
        }
    }

    /**
     * Build a {@code target_id} string identifying the Collector
     * process: {@code <host>-<pid>}. ManagementFactory.getRuntimeMXBean()
     * returns {@code <pid>@<host>} on most JVMs in v1 (JDK 25); a
     * runtime that doesn't follow that convention falls back to
     * a stable substring. The exact format is not load-bearing
     * for any downstream consumer in M1 — the audit row's
     * {@code action} value is the key signal.
     */
    private static String hostPidTargetId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name == null || name.isEmpty() ? "collector-unknown" : name;
    }

    /**
     * Minimal JSON-string escape for the profile-label field.
     * {@code profile.label} values are operator-controlled but
     * derived from a small enum (laptop / vps / pi / remote-llm /
     * unknown), so the realistic charset is ASCII alphanumeric +
     * hyphen — the escape is precaution against a misconfigured
     * value containing quotes or backslashes.
     */
    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
