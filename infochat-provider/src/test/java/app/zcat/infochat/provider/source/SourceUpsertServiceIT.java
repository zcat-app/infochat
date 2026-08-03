package app.zcat.infochat.provider.source;

import app.zcat.infochat.provider.source.KindResolver.SourceKind;
import app.zcat.infochat.provider.source.SourceUpsertService.Outcome;
import app.zcat.infochat.provider.source.SourceUpsertService.UpsertResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
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
 * <p>The spec'd branches in
 * {@code docs/spec/commands.md} §Source management for
 * {@code /add-source} get one {@code @Test} each plus an
 * idempotency assertion: a Branch A repeat for the same caller does
 * not insert a second source row, the existing subscription stays,
 * and {@code bootstrap_tags} is not rewritten. The bot-admin-against-a-
 * soft-deleted-row branch (M1-669) gets two: one pinning the outcome
 * and the untouched tags, one pinning the absent audit row.</p>
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
            // audit_log rows are NOT deleted here: V5's trg_audit_log_no_delete
            // raises on any matching DELETE (Invariant 10, append-only), so the
            // statement that used to sit here could only ever no-op or abort.
            // It went unnoticed while the one audit-writing test happened to
            // sort last in JUnit's method order and no later @BeforeEach saw its
            // row. Leaving the rows is safe: every assertion here is keyed on a
            // source id AND an actor id minted fresh by this test, so a prior
            // test's rows can never match. The users DELETE below skips the
            // actors those rows pin (audit_log.actor_user_id is NO ACTION).
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
                    "DELETE FROM users WHERE contact_id LIKE 'm1-036-%' "
                            + "  AND id NOT IN ("
                            + "    SELECT actor_user_id FROM audit_log "
                            + "     WHERE actor_user_id IS NOT NULL)");
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
                "news", "en",
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
        assertEquals("user", row.sourceOrigin,
                "an /add-source'd source is a private custom — origin must be 'user' (D59)");

        assertEquals(2L, countTags("m1-036-tag-a", "m1-036-tag-b"),
                "both supplied --tags must be unioned into the controlled vocabulary");
        assertEquals(1L, countSubscriptions(caller, result.sourceId()),
                "one source_subscription row must exist for the caller's DM scope");
        assertEquals(0L, countAuditRows(result.sourceId(), caller),
                "Branch A writes NO audit row (only Branch C does, per spec §Source management)");
    }

    // D59: /add-source against an existing BOOTSTRAP row must not demote
    // it to 'user' — the DO UPDATE arm deliberately never touches
    // source_origin (a demote would silently privatize a public source;
    // the promote direction is collector-side only).
    @Test
    void upsertAgainstBootstrapRowDoesNotDemoteOrigin() throws Exception {
        UUID caller = insertUser("m1-036-caller-boot", false);
        UUID bootstrapId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status, source_origin) "
                             + "VALUES ('rss', 'https://example.com/m1-036-boot.xml', "
                             + "'Boot Feed', 'news', '{}', 'active', 'bootstrap') "
                             + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                bootstrapId = (UUID) rs.getObject("id");
            }
        }

        UpsertResult result = service.upsert(
                caller, false, "dm", caller, SourceKind.RSS,
                "https://example.com/m1-036-boot.xml", "Boot Feed Re-add", "news", "en",
                List.of("m1-036-tag-a"));

        assertEquals(Outcome.SUBSCRIBED_EXISTING, result.outcome());
        assertEquals("bootstrap", readSource(bootstrapId).sourceOrigin,
                "re-adding a bootstrap source must not demote its origin to 'user'");
        assertEquals(1L, countSubscriptions(caller, bootstrapId),
                "the re-add still subscribes the caller (their per-source re-include)");
    }

    // A bootstrap-origin fixture must not outlive this class: under the
    // D59 world predicate it would enter every other class's world.
    @AfterEach
    void cleanupBootstrapFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            execute(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "  SELECT id FROM source "
                            + "   WHERE identifier LIKE 'https://example.com/m1-036-%' "
                            + "     AND source_origin = 'bootstrap')");
            execute(conn,
                    "DELETE FROM source "
                            + "WHERE identifier LIKE 'https://example.com/m1-036-%' "
                            + "  AND source_origin = 'bootstrap'");
        }
    }

    // (d) Idempotency: a SECOND /add-source for the same URL by the SAME
    // non-admin caller is idempotent — no second source row, the
    // existing subscription row remains, --tags are NOT rewritten.
    @Test
    void branchAIsIdempotentForRepeatCallBySameNonAdminCaller() throws Exception {
        UUID caller = insertUser("m1-036-caller-a-repeat", false);

        UpsertResult first = service.upsert(
                caller, false, "dm", caller, SourceKind.RSS,
                "https://example.com/m1-036-repeat.xml", "Repeat Feed", "news", "en",
                List.of("m1-036-tag-a"));
        assertEquals(Outcome.FRESH_INSERT, first.outcome());

        UpsertResult second = service.upsert(
                caller, false, "dm", caller, SourceKind.RSS,
                "https://example.com/m1-036-repeat.xml", "Repeat Feed", "news", "en",
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
                "https://example.com/m1-036-shared.xml", "Shared Feed", "news", "en",
                List.of("m1-036-tag-a"));
        assertEquals(Outcome.FRESH_INSERT, first.outcome());

        UpsertResult second = service.upsert(
                nonAdminCaller, false, "dm", nonAdminCaller, SourceKind.RSS,
                "https://example.com/m1-036-shared.xml", "Shared Feed", "news", "en",
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
                "https://example.com/m1-036-admin-rewrite.xml", "Rewrite Feed", "news", "en",
                List.of("m1-036-tag-a"));
        assertEquals(Outcome.FRESH_INSERT, seed.outcome());

        UpsertResult rewrite = service.upsert(
                rewriteAdmin, true, "dm", rewriteAdmin, SourceKind.RSS,
                "https://example.com/m1-036-admin-rewrite.xml", "Rewrite Feed", "news", "en",
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

    // M1-669: a bot admin re-adding a SOFT-DELETED source. UPSERT_SOURCE_SQL's
    // CASE guard already refuses to touch bootstrap_tags on a removed row; the
    // outcome must say so rather than report Branch C's replacement. Reply and
    // SQL are pinned to the same truth here: the outcome AND the stored tags.
    @Test
    void adminReAddOfRemovedSourceDoesNotClaimTagsReplaced() throws Exception {
        UUID admin = insertUser("m1-036-669-admin-outcome", true);
        UUID removedId = insertRemovedSource(
                "https://example.com/m1-036-669-removed-outcome.xml", "m1-036-tag-a");

        UpsertResult result = service.upsert(
                admin, /* actorIsBotAdmin */ true, "dm", admin, SourceKind.RSS,
                "https://example.com/m1-036-669-removed-outcome.xml",
                "Removed Feed", "news", "en",
                List.of("m1-036-tag-b", "m1-036-tag-c"));

        assertEquals(Outcome.ADMIN_EXISTING_REMOVED, result.outcome(),
                "a bot admin against a soft-deleted row must NOT report "
                        + "ADMIN_TAGS_REPLACED — the SQL skipped the replacement");
        assertEquals(removedId, result.sourceId(),
                "the conflict must resolve to the existing removed row, not a new one");

        SourceRow row = readSource(removedId);
        assertEquals(List.of("m1-036-tag-a"), row.bootstrapTags,
                "bootstrap_tags on a removed row MUST survive the admin re-add — "
                        + "this is the DB half of the same truth the outcome reports");
        assertNotNull(row.deletedAt,
                "/add-source must not revive the row; reviving is /source-enable's "
                        + "confirmed action");
    }

    // M1-669: the audit_log is the accountability record, so it must not carry
    // an ADD_SOURCE row attributing a tag replacement that the SQL skipped.
    @Test
    void adminReAddOfRemovedSourceWritesNoTagReplacementAudit() throws Exception {
        UUID admin = insertUser("m1-036-669-admin-audit", true);
        UUID removedId = insertRemovedSource(
                "https://example.com/m1-036-669-removed-audit.xml", "m1-036-tag-a");

        long auditRowsBefore = countAuditRows(removedId, admin);
        assertEquals(0L, auditRowsBefore,
                "fixture precondition: the removed row starts with no audit history "
                        + "for this admin, so the after-count below cannot pass vacuously");

        UpsertResult result = service.upsert(
                admin, /* actorIsBotAdmin */ true, "dm", admin, SourceKind.RSS,
                "https://example.com/m1-036-669-removed-audit.xml",
                "Removed Feed", "news", "en",
                List.of("m1-036-tag-b"));

        assertEquals(Outcome.ADMIN_EXISTING_REMOVED, result.outcome());
        assertEquals(auditRowsBefore, countAuditRows(removedId, admin),
                "the no-op branch must write NO ADD_SOURCE audit row — a false entry "
                        + "in the accountability record is worse than none");
    }

    // M1-669: the same defect in the other tier. A non-admin against a removed
    // row must not collapse into plain SUBSCRIBED_EXISTING — that reply promises
    // a feed which delivers nothing and is hidden from /list-sources, and this
    // tier cannot run /source-enable (it is bot-admin-only), so its remedy
    // differs. Tags stay ignored and no audit row is written either way.
    @Test
    void nonAdminReAddOfRemovedSourceIsReportedAsRemovedNotPlainSubscribed() throws Exception {
        UUID caller = insertUser("m1-036-669-non-admin", false);
        UUID removedId = insertRemovedSource(
                "https://example.com/m1-036-669-removed-non-admin.xml", "m1-036-tag-a");

        UpsertResult result = service.upsert(
                caller, /* actorIsBotAdmin */ false, "dm", caller, SourceKind.RSS,
                "https://example.com/m1-036-669-removed-non-admin.xml",
                "Removed Feed", "news", "en",
                List.of("m1-036-tag-b"));

        assertEquals(Outcome.SUBSCRIBED_EXISTING_REMOVED, result.outcome(),
                "a non-admin against a soft-deleted row must be distinguishable "
                        + "from a subscribe against a LIVE existing row");
        assertEquals(removedId, result.sourceId());
        assertEquals(List.of("m1-036-tag-a"), readSource(removedId).bootstrapTags,
                "a non-admin never replaces bootstrap_tags, removed row or not");
        assertEquals(0L, countAuditRows(removedId, caller),
                "the non-admin removed branch writes no audit row");
        assertEquals(1L, countSubscriptions(caller, removedId),
                "the subscription is still upserted — subscription semantics are "
                        + "unchanged by M1-669; only the reply stops over-promising");
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

    // M1-750: the declared language round-trips into source.language on
    // insert, and a re-upsert (ON CONFLICT DO UPDATE) does NOT overwrite
    // it — V31's column-scoped UPDATE grant excludes language, so the
    // DO UPDATE arm cannot (and per D29 must not) touch it; changing a
    // declared language is an operator action, never a chat command.
    @Test
    void declaredLanguageRoundTripsAndIsInsertOnly() throws Exception {
        UUID caller = insertUser("m1-036-750-lang", false);

        UpsertResult first = service.upsert(
                caller, /* actorIsBotAdmin */ false,
                "dm", caller,
                SourceKind.RSS,
                "https://example.com/m1-036-750-lang.xml",
                "Language Feed", "news", "cs",
                List.of("m1-036-tag-a"));
        assertEquals(Outcome.FRESH_INSERT, first.outcome());
        assertEquals("cs", readSource(first.sourceId()).language,
                "the declared --lang must round-trip into source.language on insert");

        UpsertResult second = service.upsert(
                caller, false, "dm", caller, SourceKind.RSS,
                "https://example.com/m1-036-750-lang.xml",
                "Language Feed", "news", "en",
                List.of("m1-036-tag-b"));
        assertEquals(Outcome.SUBSCRIBED_EXISTING, second.outcome());
        assertEquals("cs", readSource(first.sourceId()).language,
                "the re-upsert must NOT overwrite an existing row's declared language "
                        + "(INSERT-only per the V31 grant; D29 operator-declared)");
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

    // Soft-deleted fixture: deleted_at set, status left alone. That mirrors
    // /remove-source, which is a deleted_at/deleted_by write only — 'removed'
    // is not one of the V6 status CHECK values.
    private UUID insertRemovedSource(String identifier, String bootstrapTag) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status, source_origin, deleted_at) "
                             + "VALUES ('rss', ?, 'Removed Feed', 'news', ARRAY[?]::TEXT[], "
                             + "'active', 'user', now()) RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, bootstrapTag);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject("id");
            }
        }
    }

    private record SourceRow(
            String kind,
            String identifier,
            String status,
            String category,
            List<String> bootstrapTags,
            java.sql.Timestamp deletedAt,
            String sourceOrigin,
            String language) {}

    private SourceRow readSource(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT kind, identifier, status, category, bootstrap_tags, deleted_at, "
                             + "source_origin, language FROM source WHERE id = ?")) {
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
                        rs.getTimestamp("deleted_at"),
                        rs.getString("source_origin"),
                        rs.getString("language"));
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
