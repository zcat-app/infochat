package app.zcat.infochat.core.log;

import app.zcat.infochat.core.schema.PostgresSchemaTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard between the write-side Java secret catalogue
 * ({@link Redactor#CATALOGUE}) and the read-side SQL mirror
 * ({@code redact_secrets_jsonb} in migration V31). The console filter
 * and the audit-write hook share {@code Redactor.redact} structurally,
 * but V31 hand-copies the catalogue as a sequence of
 * {@code regexp_replace} calls kept in sync only by a comment — this
 * IT makes that sync mechanical (docs/spec/security.md §Secrets
 * handling, "cannot drift").
 *
 * <p>Two chained tripwires: the sample table is pinned to
 * {@code CATALOGUE.size()}, so adding a Java family without adding a
 * sample fails the build (tripwire #1); every sample must then be
 * masked by BOTH {@code Redactor.redact} and
 * {@code redact_secrets_jsonb}, so a family present in Java but
 * missing from V31 fails too (tripwire #2). A negative control guards
 * against an over-broad SQL regex that masks everything and would
 * thereby hide drift behind always-green tripwire-#2 assertions.
 *
 * <p>Known limit: the Anthropic family is a strict prefix of the
 * OpenAI family ({@code sk-ant-…} vs {@code sk-…}), so dropping ONLY
 * the Anthropic line from V31 is shadowed by the OpenAI pattern and
 * not detectable by sample masking; the size tripwire still covers
 * additions.
 *
 * <p>Lives in {@code app.zcat.infochat.core.log} to read the
 * package-private {@code CATALOGUE}; reuses
 * {@link PostgresSchemaTestBase} for the Testcontainers Postgres with
 * all Flyway migrations applied. The {@code *IT} suffix routes it
 * through maven-failsafe under {@code mvn verify}.
 */
class RedactorSqlParityIT extends PostgresSchemaTestBase {

    /**
     * One representative secret per catalogue family, in catalogue
     * order. {@code mustVanish} is the high-entropy portion that no
     * redacted output may still contain — the whole sample for the
     * provider-pinned families, the 32-char body for the generic
     * keyword-adjacent family (whose mask deliberately keeps the
     * keyword prefix via the {@code $1}/{@code \1} group).
     */
    private record SecretSample(String family, String sample, String mustVanish) {

        static SecretSample of(String family, String sample) {
            return new SecretSample(family, sample, sample);
        }
    }

    private static final String GENERIC_SECRET_BODY = "FAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE";

    private static final List<SecretSample> SAMPLES = List.of(
            SecretSample.of("anthropic", "sk-ant-api03FAKEFAKEFAKEFAKE1234"),
            SecretSample.of("openai", "sk-proj-FAKEFAKEFAKEFAKEFAKE"),
            SecretSample.of("github", "ghp_FAKEFAKEFAKEFAKEFAKE"),
            SecretSample.of("aws", "AKIAFAKEFAKEFAKEFAKE"),
            SecretSample.of("google", "AIzaFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE012"),
            SecretSample.of("slack", "xoxb-FAKEFAKEFAKE"),
            new SecretSample("generic-keyword-adjacent",
                    "api_key=" + GENERIC_SECRET_BODY, GENERIC_SECRET_BODY));

    private static final String NON_SECRET = "plain non-secret text";

    /**
     * Fast canary: a test profile that silently stopped migrating
     * before V31 would make the SQL-side assertions exercise the V5
     * RETURN-input stub instead of the real redactor.
     */
    @Test
    void migrationCursorReachesV31() throws SQLException {
        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version FROM flyway_schema_history "
                             + "WHERE success = TRUE "
                             + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rs.next(), "expected at least one applied Flyway version");
            int maxVersion = Integer.parseInt(rs.getString("version"));
            assertTrue(maxVersion >= 31,
                    "expected migration cursor at V31 or later, got V" + maxVersion);
        }
    }

    /** Tripwire #1: Java catalogue grew, sample table not updated. */
    @Test
    void sampleTableCoversEveryCatalogueFamily() {
        assertEquals(Redactor.CATALOGUE.size(), SAMPLES.size(),
                "Redactor.CATALOGUE and the parity sample table have diverged: "
                        + "add one representative sample per new family "
                        + "(and mirror the family in V31's redact_secrets_jsonb)");
    }

    @Test
    void everySampleMaskedByJavaRedactor() {
        for (SecretSample s : SAMPLES) {
            String redacted = Redactor.redact(s.sample());
            assertTrue(redacted.contains(Redactor.REDACTED),
                    "family " + s.family() + ": Redactor.redact did not mask " + s.sample());
            assertFalse(redacted.contains(s.mustVanish()),
                    "family " + s.family() + ": secret survived Redactor.redact: " + redacted);
        }
    }

    /**
     * Tripwire #2: read-side SQL mask lags the write-side. Calls the
     * V31 function directly — no need to route through
     * {@code audit_log_view}.
     */
    @Test
    void everySampleMaskedBySqlRedactSecretsJsonb() throws SQLException {
        for (SecretSample s : SAMPLES) {
            String masked = sqlRedactedValue(s.sample());
            assertTrue(masked.contains(Redactor.REDACTED),
                    "family " + s.family()
                            + ": redact_secrets_jsonb did not mask " + s.sample());
            assertFalse(masked.contains(s.mustVanish()),
                    "family " + s.family()
                            + ": secret survived redact_secrets_jsonb: " + masked);
        }
    }

    /**
     * Negative control: an over-broad SQL regex that masked everything
     * would pass tripwire #2 unconditionally and hide real drift.
     */
    @Test
    void nonSecretTextUnchangedByBothMasks() throws SQLException {
        assertEquals(NON_SECRET, Redactor.redact(NON_SECRET),
                "Redactor.redact altered a non-secret string");
        assertEquals(NON_SECRET, sqlRedactedValue(NON_SECRET),
                "redact_secrets_jsonb altered a non-secret string");
    }

    private static String sqlRedactedValue(String value) throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT redact_secrets_jsonb(jsonb_build_object('k', ?::text)) ->> 'k'")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "redact_secrets_jsonb returned no row");
                return rs.getString(1);
            }
        }
    }
}
