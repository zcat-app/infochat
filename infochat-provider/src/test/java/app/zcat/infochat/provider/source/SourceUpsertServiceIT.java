package app.zcat.infochat.provider.source;

import app.zcat.infochat.provider.source.KindResolver.SourceKind;
import app.zcat.infochat.provider.source.SourceUpsertService.Outcome;
import app.zcat.infochat.provider.source.SourceUpsertService.UpsertResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-tier integration tests for {@link SourceUpsertService} against
 * the DevServices Postgres container (Flyway-applied schema, V5
 * users + audit_log, V6 source + tag, V7 source_subscription).
 *
 * <p>The three spec'd branches in
 * {@code docs/spec/commands.md} §Source management for
 * {@code /add-source} get one {@code @Test} each plus an
 * idempotency assertion: a Branch A repeat for the same caller does
 * not insert a second source row, the existing subscription stays,
 * and {@code bootstrap_tags} is not rewritten.</p>
 *
 * <p>Test isolation: each {@code @Test} seeds rows under the
 * {@code 'm1-036-'} contact-id prefix and the
 * {@code https://example.com/m1-036-*} URL prefix; {@link #cleanup()}
 * deletes only those before each test.</p>
 */
@QuarkusTest
class SourceUpsertServiceIT {

    @Inject
    SourceUpsertService service;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the DELETE on test admin rows.
            // Contact-id prefix is intentionally OUTSIDE the
            // 'm1-036-' delete pattern below so the guardian is never
            // collected by this test's cleanup.
            execute(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES ('inmemory', 'guardian-m1-036-svc-permanent', TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE");
            execute(conn,
                    "DELETE FROM audit_log "
                            + "WHERE target_kind = 'source' "
                            + "  AND target_id IN ("
                            + "    SELECT id::TEXT FROM source "
                            + "     WHERE identifier LIKE 'https://example.com/m1-036-%')");
            execute(conn,
                    "DELETE FROM source_subscription "
                            + "WHERE source_id IN ("
                            + "  SELECT id FROM source "
                            + "   WHERE identifier LIKE 'https://example.com/m1-036-%')");
            execute(conn,
                    "DELETE FROM source "
                            + "WHERE identifier LIKE 'https://example.com/m1-036-%'");
            execute(conn,
                    "DELETE FROM tag WHERE name IN ('m1-036-tag-a', 'm1-036-tag-b', 'm1-036-tag-c')");
            execute(conn,
                    "DELETE FROM users WHERE contact_id LIKE 'm1-036-%'");
        }
    }

    // (a) fresh insert writes one source row with the spec-required defaults.
    // (b) the same --tags values appear as tag rows.
    // (c) one source_subscription row exists for the caller's scope.
    @Test
    void branchAFreshInsertWritesSourceTagsSubscriptionInOneTransaction() throws Exception {
        UUID caller = insertUser("m1-036-caller-a", false);

        UpsertResult result = service.upsert(
                caller, /* actorIsBotAdmin */ false,
                "dm", caller,
                SourceKind.RSS,
                "https://example.com/m1-036-fresh.xml",
                "Example Fresh Feed",
                "news",
                List.of("m1-036-tag-a", "m1-036-tag-b"));

        assertEquals(Outcome.FRESH_INSERT, result.outcome(),
                "first /add-source against (kind, identifier) must report FRESH_INSERT");
        assertNotNull(result.sourceId(), "FRESH_INSERT must surface the source id");
        assertEquals("Example Fresh Feed", result.displayName());

        SourceRow row = readSource(result.sourceId());
        assertEquals("rss", row.kind);
        assertEquals("https://example.com/m1-036-fresh.xml", row.identifier);
        assertEquals("active", row.status);
        assertEquals("news", row.category);
        assertEquals(List.of("m1-036-tag-a", "m1-036-tag-b"), row.bootstrapTags);
        assertTrue(row.deletedAt == null, "fresh insert must leave deleted_at NULL");

        assertEquals(2L, countTags("m1-036-tag-a", "m1-036-tag-b"),
                "both supplied --tags must be unioned into the controlled vocabulary");
        assertEquals(1L, countSubscriptions(caller, result.sourceId()),
                "one source_subscription row must exist for the caller's DM scope");
        assertEquals(0L, countAuditRows(result.sourceId(), caller),
                "Branch A writes NO audit row (only Branch C does, per spec §Source management)");
    }

    // (d) Idempotency: a SECOND /add-source for the same URL by the SAME
    // non-admin caller is idempotent — no second source row, the
    // existing subscription row remains, --tags are NOT rewritten.
    @Test
    void branchAIsIdempotentForRepeatCallBySameNonAdminCaller() throws Exception {
        UUID caller = insertUser("m1-036-caller-a-repeat", false);

        UpsertResult first = service.upsert(
                caller, false, "dm", caller, SourceKind.RSS,
                "https://example.com/m1-036-repeat.xml", "Repeat Feed", "news",
                List.of("m1-036-tag-a"));
        assertEquals(Outcome.FRESH_INSERT, first.outcome());

        UpsertResult second = service.upsert(
                caller, false, "dm", caller, SourceKind.RSS,
                "https://example.com/m1-036-repeat.xml", "Repeat Feed", "news",
                List.of("m1-036-tag-b"));
        assertEquals(Outcome.SUBSCRIBED_EXISTING, second.outcome(),
                "second /add-source for the same (kind, identifier) by the same "
                        + "non-admin caller must report SUBSCRIBED_EXISTING");
        assertEquals(first.sourceId(), second.sourceId(),
                "the source id must be stable across the idempotent re-call");

        SourceRow row = readSource(first.sourceId());
        assertEquals(List.of("m1-036-tag-a"), row.bootstrapTags,
                "Branch B: --tags supplied on the second call MUST be ignored — "
                        + "bootstrap_tags stays at the original Branch A value");
        assertEquals(1L, countSubscriptions(caller, first.sourceId()),
                "exactly one source_subscription row must exist (ON CONFLICT DO NOTHING)");
    }

    // Branch B: non-admin caller against an existing row inserted by
    // someone else — bootstrap_tags unchanged, supplied tags ignored,
    // subscription upserted, NO audit row.
    @Test
    void branchBNonAdminAgainstExistingRowIgnoresTagsAndSubscribesOnly() throws Exception {
        UUID adminInserter = insertUser("m1-036-admin-seed-b", true);
        UUID nonAdminCaller = insertUser("m1-036-non-admin-b", false);

        UpsertResult first = service.upsert(
                adminInserter, true, "dm", adminInserter, SourceKind.RSS,
                "https://example.com/m1-036-shared.xml", "Shared Feed", "news",
                List.of("m1-036-tag-a"));
        assertEquals(Outcome.FRESH_INSERT, first.outcome());

        UpsertResult second = service.upsert(
                nonAdminCaller, false, "dm", nonAdminCaller, SourceKind.RSS,
                "https://example.com/m1-036-shared.xml", "Shared Feed", "news",
                List.of("m1-036-tag-b"));
        assertEquals(Outcome.SUBSCRIBED_EXISTING, second.outcome());

        SourceRow row = readSource(first.sourceId());
        assertEquals(List.of("m1-036-tag-a"), row.bootstrapTags,
                "Branch B: non-admin caller's --tags MUST be ignored on the existing row");

        assertEquals(1L, countSubscriptions(nonAdminCaller, first.sourceId()),
                "non-admin caller's subscription must exist after Branch B");
        assertEquals(0L, countAuditRows(first.sourceId(), nonAdminCaller),
                "Branch B writes NO audit row");
    }

    // Branch C: bot-admin caller against an existing row — bootstrap_tags
    // REPLACED, vocab union runs over the new values, one audit_log row
    // is INSERTed for the bot admin actor against the source target.
    @Test
    void branchCBotAdminAgainstExistingRowReplacesTagsAndWritesAuditRow() throws Exception {
        UUID seedAdmin = insertUser("m1-036-admin-seed-c", true);
        UUID rewriteAdmin = insertUser("m1-036-admin-rewrite-c", true);

        UpsertResult seed = service.upsert(
                seedAdmin, true, "dm", seedAdmin, SourceKind.RSS,
                "https://example.com/m1-036-admin-rewrite.xml", "Rewrite Feed", "news",
                List.of("m1-036-tag-a"));
        assertEquals(Outcome.FRESH_INSERT, seed.outcome());

        UpsertResult rewrite = service.upsert(
                rewriteAdmin, true, "dm", rewriteAdmin, SourceKind.RSS,
                "https://example.com/m1-036-admin-rewrite.xml", "Rewrite Feed", "news",
                List.of("m1-036-tag-b", "m1-036-tag-c"));
        assertEquals(Outcome.ADMIN_TAGS_REPLACED, rewrite.outcome());
        assertEquals(seed.sourceId(), rewrite.sourceId(),
                "the source id must be stable across the bot-admin rewrite");

        SourceRow row = readSource(seed.sourceId());
        assertEquals(List.of("m1-036-tag-b", "m1-036-tag-c"), row.bootstrapTags,
                "Branch C: bot-admin caller's --tags REPLACE bootstrap_tags on the existing row");

        assertEquals(1L, countAuditRows(seed.sourceId(), rewriteAdmin),
                "Branch C must write exactly one audit_log row referencing the source id "
                        + "and the bot-admin actor (V5 §2.1.8 ADD_SOURCE verb)");
        assertEquals("ADD_SOURCE", readSingleAuditAction(seed.sourceId(), rewriteAdmin),
                "the audit row's action verb must be the V5-closed 'ADD_SOURCE' literal");
    }

    // M1-365: the tag-vocab union must be ONE round-trip for N tags, not one
    // executeUpdate per tag. Drives the package-private upsertTagVocab with a
    // Connection proxy that counts executeUpdate on the tag INSERT, then asserts
    // exactly one execution AND that all three tags landed (the single statement
    // still unions every supplied tag, ON CONFLICT DO NOTHING idempotency intact).
    @Test
    void tagVocabUpsertIssuesOneStatementForManyTags() throws Exception {
        int[] tagSqlExecuteCount = {0};
        try (Connection real = dataSource.getConnection()) {
            Connection counting = countingTagExecuteUpdates(real, tagSqlExecuteCount);
            service.upsertTagVocab(counting,
                    List.of("m1-036-tag-a", "m1-036-tag-b", "m1-036-tag-c"));
        }

        assertEquals(1, tagSqlExecuteCount[0],
                "the array-bind unnest upsert must issue ONE executeUpdate for N tags, "
                        + "not one per tag");
        assertEquals(3L, countTags("m1-036-tag-a", "m1-036-tag-b", "m1-036-tag-c"),
                "the single statement must still union all three supplied tags");
    }

    // --- helpers ---------------------------------------------------------

    // Wraps a real Connection so executeUpdate on the tag INSERT is counted; every
    // other call (createArrayOf, the actual prepare/execute) delegates so the
    // union runs for real against the DevServices DB.
    private static Connection countingTagExecuteUpdates(Connection real, int[] count) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (method.getName().equals("prepareStatement")
                            && args != null && args.length > 0
                            && args[0] instanceof String sql && sql.contains("INSERT INTO tag")
                            && result instanceof PreparedStatement ps) {
                        return countingStatement(ps, count);
                    }
                    return result;
                });
    }

    private static PreparedStatement countingStatement(PreparedStatement real, int[] count) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("executeUpdate")) {
                        count[0]++;
                    }
                    return invoke(real, method, args);
                });
    }

    private static @Nullable Object invoke(Object target, Method method,
                                           Object @Nullable [] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    private UUID insertUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES ('inmemory', ?, ?, 'invited') RETURNING id")) {
            ps.setString(1, contactId);
            ps.setBoolean(2, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private record SourceRow(
            String kind,
            String identifier,
            String status,
            String category,
            List<String> bootstrapTags,
            java.sql.Timestamp deletedAt) {}

    private SourceRow readSource(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT kind, identifier, status, category, bootstrap_tags, deleted_at "
                             + "FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "source row must exist for id=" + sourceId);
                java.sql.Array tagsArray = rs.getArray("bootstrap_tags");
                String[] tags = (String[]) tagsArray.getArray();
                return new SourceRow(
                        rs.getString("kind"),
                        rs.getString("identifier"),
                        rs.getString("status"),
                        rs.getString("category"),
                        Arrays.asList(tags),
                        rs.getTimestamp("deleted_at"));
            }
        }
    }

    private long countTags(String... names) throws Exception {
        Set<String> needle = new HashSet<>(Arrays.asList(names));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM tag WHERE name = ANY (?)")) {
            ps.setArray(1, conn.createArrayOf("TEXT", needle.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private long countSubscriptions(UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM source_subscription "
                             + "WHERE scope_kind = 'dm' AND scope_id = ? AND source_id = ?")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private long countAuditRows(UUID sourceId, UUID actorUserId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log "
                             + "WHERE target_kind = 'source' AND target_id = ? "
                             + "  AND actor_user_id = ?")) {
            ps.setString(1, sourceId.toString());
            ps.setObject(2, actorUserId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private String readSingleAuditAction(UUID sourceId, UUID actorUserId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT action FROM audit_log "
                             + "WHERE target_kind = 'source' AND target_id = ? "
                             + "  AND actor_user_id = ?")) {
            ps.setString(1, sourceId.toString());
            ps.setObject(2, actorUserId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "audit row must exist");
                String action = rs.getString("action");
                assertNotEquals(true, rs.next(),
                        "exactly one audit row must exist for (source, actor)");
                return action;
            }
        }
    }

    private static void execute(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
