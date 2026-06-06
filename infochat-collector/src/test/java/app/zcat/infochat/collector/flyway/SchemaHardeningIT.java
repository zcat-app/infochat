package app.zcat.infochat.collector.flyway;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.audit.AuditAction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions over the schema-hardening migration:
 * <ul>
 *   <li>The {@code post_stage2_verdict_chk} CHECK closes
 *       {@code stage2_verdict} over
 *       {@code ('BENIGN','INJECTION','MALWARE','UNKNOWN')} — every
 *       closed-set value and NULL insert, any other value (including
 *       the {@code Verdict.INFRA_FAILURE} member, which by contract
 *       never writes the column) is rejected with SQLState 23514
 *       (check_violation).</li>
 *   <li>The audit verb the V27 migration writes directly in SQL is
 *       represented in the {@link AuditAction} application-layer
 *       closure — cross-checked against the literal in the V27
 *       classpath resource so the two cannot silently diverge.</li>
 *   <li>The {@code idx_post_source_published} composite index exists
 *       on {@code post(source_id, published_at DESC)} — the
 *       NostrStreamSource reconnect-cursor read
 *       ({@code SELECT MAX(published_at) … WHERE source_id = ?})
 *       terminates at the first index entry per source.</li>
 * </ul>
 */
@QuarkusTest
class SchemaHardeningIT {

    private static final String V27_AUDIT_VERB = "D47_GROUP_ONLY_PREBAN_CONVERSION";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Test
    void stage2VerdictCheckAcceptsClosedSetAndNull() throws SQLException {
        UUID sourceId = seedSource("stage2-check-accepts");
        for (String verdict : new String[] {"BENIGN", "INJECTION", "MALWARE", "UNKNOWN", null}) {
            UUID postId = insertPostWithVerdict(sourceId, verdict);
            assertNotNull(postId, "Insert with stage2_verdict=" + verdict + " must succeed");
        }
    }

    @Test
    void stage2VerdictCheckRejectsValueOutsideClosedSet() throws SQLException {
        UUID sourceId = seedSource("stage2-check-rejects");
        SQLException rejected = assertThrows(SQLException.class,
            () -> insertPostWithVerdict(sourceId, "INFRA_FAILURE"),
            "stage2_verdict outside the closed set must violate post_stage2_verdict_chk");
        assertEquals("23514", rejected.getSQLState(),
            "Expected check_violation (23514); was: " + rejected.getSQLState());
    }

    @Test
    void v27AuditVerbIsInAuditActionClosedSet() throws IOException {
        // The enum lookup throws IllegalArgumentException if the verb is
        // missing, failing the test.
        assertNotNull(AuditAction.valueOf(V27_AUDIT_VERB));

        // Cross-check against the migration source so a rename on either
        // side surfaces here instead of as an unrepresented audit verb.
        String v27Sql = readClasspathResource("db/migration/V27__d47_remove_group_only.sql");
        assertTrue(v27Sql.contains("'" + V27_AUDIT_VERB + "'"),
            "V27 must write the verb the AuditAction closed set represents: " + V27_AUDIT_VERB);
    }

    @Test
    void idxPostSourcePublishedExistsWithExpectedShape() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT indexdef FROM pg_indexes WHERE tablename = 'post' AND indexname = ?")) {
            ps.setString(1, "idx_post_source_published");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "idx_post_source_published must exist on post");
                String indexdef = rs.getString(1);
                assertTrue(indexdef.contains("source_id") && indexdef.contains("published_at DESC"),
                    "Index must cover (source_id, published_at DESC); was: " + indexdef);
            }
        }
    }

    // ---------- helpers ----------

    private UUID seedSource(String slug) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[], 'active') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET status = 'active' "
                     + "RETURNING id")) {
            ps.setString(1, "https://schema-hardening-test.example/" + slug);
            ps.setString(2, "Schema Hardening " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertPostWithVerdict(UUID sourceId, String stage2Verdict) throws SQLException {
        String uid = "sh-" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, status_changed_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts,"
                     + "  stage2_verdict"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'title', 'body',"
                     + "  ?, 'QUARANTINED', now(),"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  ?"
                     + ") RETURNING id")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + uid);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, stage2Verdict);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String readClasspathResource(String path) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Classpath resource must exist: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
