package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.summary.EligiblePostQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DigestPostCollector}'s SQL-driven post collection for
 * a group scope. JDBC is stubbed via dynamic proxies (Mockito is
 * intentionally absent from the Provider classpath).
 */
class DigestPostCollectorTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final Instant SINCE = Instant.parse("2026-05-25T00:00:00Z");

    private DigestPostCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DigestPostCollector();
        collector.cancellationService = new NoopCancellationService();
        collector.clusterCap = 200;
    }

    @Test
    void collectForGroup_filtersOnActiveSubscriptions() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Instant published = Instant.parse("2026-05-25T10:00:00Z");

        collector.dataSource = new StubDataSource(
                "EXPLICIT", 3L, 5L,
                List.of(new PostRow(postId, "uid-1", sourceId, "TechCrunch",
                        "Bitcoin $100k", "https://tc.com/btc", "body",
                        published, new String[]{"crypto", "bitcoin"})));

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(GROUP_ID, SINCE);

        assertEquals(1, result.posts().size());
        EligiblePostQuery.Post post = result.posts().getFirst();
        assertEquals(postId, post.id());
        assertEquals("uid-1", post.uid());
        assertEquals("TechCrunch", post.sourceDisplayName());
        assertEquals("Bitcoin $100k", post.title());
        assertEquals(published, post.publishedAt());
        assertEquals(List.of("crypto", "bitcoin"), post.tags());
        assertEquals(3L, result.tagSubscriptionVersion());
        assertEquals(5L, result.sourceSubscriptionVersion());
    }

    @Test
    void collectForGroup_returnsEmptyWhenWorldHasNoEligiblePosts() throws SQLException {
        // An empty SQL result maps to an empty CollectionResult. (Pre-D59
        // this test was named for the no-subscriptions empty cliff; under
        // the implicit-bootstrap world a subscription-less group is NOT
        // empty by construction — the behavioural proof lives in
        // DigestPostCollectorIT. This unit test only pins the mapping.)
        collector.dataSource = new StubDataSource("ALL", 0L, 0L, List.of());

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(GROUP_ID, SINCE);

        assertTrue(result.posts().isEmpty());
        assertEquals(0L, result.tagSubscriptionVersion());
        assertEquals(0L, result.sourceSubscriptionVersion());
    }

    @Test
    void collectForGroup_carriesRealClassificationInBothTagModes() throws SQLException {
        // M1-727: the digest routes on post.classification, so the
        // collected post must carry its real labels (both SELECTs project
        // the column since M1-724) — never the {unknown} sentinel a
        // non-projecting reader would have to substitute.
        for (String tagMode : List.of("ALL", "EXPLICIT")) {
            collector.dataSource = new StubDataSource(
                    tagMode, 1L, 1L,
                    List.of(new PostRow(UUID.randomUUID(), "uid-p", UUID.randomUUID(), "Bsky",
                            "my cat had a birthday", "https://bsky.example/cat", "body",
                            Instant.parse("2026-05-25T10:00:00Z"),
                            new String[]{"security"}, new String[]{"personal", "opinion"},
                            null, null, null, null)));

            DigestPostCollector.CollectionResult result =
                    collector.collectForGroup(GROUP_ID, SINCE);

            assertEquals(1, result.posts().size());
            assertEquals(List.of("personal", "opinion"),
                    result.posts().getFirst().classification(),
                    "mode " + tagMode + ": the real classification, not the unknown sentinel");
        }
    }

    @Test
    void collectForGroup_projectsDeclaredSourceLanguageInBothTagModes() throws SQLException {
        // M1-756: without this projection the digest's display-hit
        // translation leg is dead on arrival and SILENTLY so. mapPost's
        // shorter Post overload hard-codes sourceLanguage to NULL, which
        // the pipeline reads as "unknown — never translate", so every
        // digest row would no-op forever with no error anywhere. Both
        // SELECTs already JOIN source, so both must project s.language.
        for (String tagMode : List.of("ALL", "EXPLICIT")) {
            StubDataSource dataSource = new StubDataSource(
                    tagMode, 1L, 1L,
                    List.of(new PostRow(UUID.randomUUID(), "uid-cs", UUID.randomUUID(), "CT24",
                            "Titulek", "https://ct24.example/1", "body",
                            Instant.parse("2026-05-25T10:00:00Z"),
                            new String[]{"security"}, new String[]{"factual"},
                            null, null, null, null, "cs")));
            collector.dataSource = dataSource;

            DigestPostCollector.CollectionResult result =
                    collector.collectForGroup(GROUP_ID, SINCE);

            assertEquals(1, result.posts().size());
            assertEquals("cs", result.posts().getFirst().sourceLanguage(),
                    "mode " + tagMode + ": the source's declared language reaches the Post");
            assertTrue(dataSource.lastPostsSql().contains("s.language"),
                    "mode " + tagMode + ": the post SELECT must project the joined source's "
                            + "language; got: " + dataSource.lastPostsSql());
        }
    }

    @Test
    void collectForGroup_projectsTheEnglishAnchorInBothTagModes() throws SQLException {
        // M1-759, the same silent-failure shape M1-756 pinned above: there
        // are TWO post SELECTs (POSTS_ALL_SQL and POSTS_EXPLICIT_SQL) and
        // adding the columns to only one makes the digest render a
        // different primary line depending on the group's tag mode, with
        // no error anywhere. A missing anchor reads as "never translated",
        // which is a legitimate state, so nothing downstream complains.
        for (String tagMode : List.of("ALL", "EXPLICIT")) {
            StubDataSource dataSource = new StubDataSource(
                    tagMode, 1L, 1L,
                    List.of(new PostRow(UUID.randomUUID(), "uid-tr", UUID.randomUUID(), "TRT",
                            "Türkçe başlık", "https://trt.example/1", "gövde",
                            Instant.parse("2026-05-25T10:00:00Z"),
                            new String[]{"security"}, new String[]{"factual"},
                            null, null, null, null, "tr",
                            "Turkish headline", "body text")));
            collector.dataSource = dataSource;

            DigestPostCollector.CollectionResult result =
                    collector.collectForGroup(GROUP_ID, SINCE);

            assertEquals(1, result.posts().size());
            assertEquals("Turkish headline", result.posts().getFirst().titleEn(),
                    "mode " + tagMode + ": the English anchor reaches the Post");
            assertEquals("body text", result.posts().getFirst().bodyEn(),
                    "mode " + tagMode + ": the body anchor reaches the Post too");
            assertTrue(dataSource.lastPostsSql().contains("p.title_en"),
                    "mode " + tagMode + ": the post SELECT must project p.title_en; got: "
                            + dataSource.lastPostsSql());
            assertTrue(dataSource.lastPostsSql().contains("p.body_en"),
                    "mode " + tagMode + ": the post SELECT must project p.body_en; got: "
                            + dataSource.lastPostsSql());
        }
    }

    // ----- JDBC stubs (no Mockito) -----------------------------------------

    record PostRow(UUID id, String uid, UUID sourceId, String displayName,
                   String title, String url, String body, Instant publishedAt,
                   String[] tags, String[] classification,
                   Integer reposts, Integer likes, String kind,
                   Integer sourceWindowPosts, String language,
                   String titleEn, String bodyEn) {
        /** Pre-M1-724 shape: no prominence signals (all NULL). */
        PostRow(UUID id, String uid, UUID sourceId, String displayName,
                String title, String url, String body, Instant publishedAt,
                String[] tags) {
            this(id, uid, sourceId, displayName, title, url, body, publishedAt,
                    tags, new String[]{"unknown"}, null, null, null, null, "en");
        }

        /** Pre-M1-759 shape: no English anchor. */
        PostRow(UUID id, String uid, UUID sourceId, String displayName,
                String title, String url, String body, Instant publishedAt,
                String[] tags, String[] classification,
                Integer reposts, Integer likes, String kind,
                Integer sourceWindowPosts, String language) {
            this(id, uid, sourceId, displayName, title, url, body, publishedAt,
                    tags, classification, reposts, likes, kind, sourceWindowPosts,
                    language, null, null);
        }

        /** Pre-M1-756 shape: no declared source language. */
        PostRow(UUID id, String uid, UUID sourceId, String displayName,
                String title, String url, String body, Instant publishedAt,
                String[] tags, String[] classification,
                Integer reposts, Integer likes, String kind,
                Integer sourceWindowPosts) {
            this(id, uid, sourceId, displayName, title, url, body, publishedAt,
                    tags, classification, reposts, likes, kind, sourceWindowPosts, "en");
        }
    }

    /**
     * Hand-rolled DataSource stub that returns canned scope_preferences
     * and post rows. Routes queries by inspecting the SQL string.
     */
    private static class StubDataSource implements DataSource {
        private final String tagMode;
        private final long tagVer;
        private final long srcVer;
        private final List<PostRow> posts;
        /** The post SELECT this stub was handed — the projection is asserted on it. */
        private final AtomicReference<String> lastPostsSql = new AtomicReference<>();

        String lastPostsSql() { return lastPostsSql.get(); }

        StubDataSource(String tagMode, long tagVer, long srcVer, List<PostRow> posts) {
            this.tagMode = tagMode;
            this.tagVer = tagVer;
            this.srcVer = srcVer;
            this.posts = posts;
        }

        @Override
        public Connection getConnection() {
            return proxyConnection();
        }

        @Override
        public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> i) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }

        private Connection proxyConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> newPreparedStatement((String) args[0]);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName());
                    });
        }

        private PreparedStatement newPreparedStatement(String sql) {
            boolean isScopePrefs = sql.contains("scope_preferences");
            if (!isScopePrefs) {
                lastPostsSql.set(sql);
            }
            AtomicReference<Timestamp> capturedTimestamp = new AtomicReference<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setObject", "setString", "setInt" -> null;
                        case "setTimestamp" -> {
                            capturedTimestamp.set((Timestamp) args[1]);
                            yield null;
                        }
                        case "executeQuery" ->
                                isScopePrefs ? scopePrefsResultSet() : postsResultSet();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "PreparedStatement." + method.getName());
                    });
        }

        private ResultSet scopePrefsResultSet() {
            boolean[] consumed = {false};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) yield false;
                            consumed[0] = true;
                            yield true;
                        }
                        case "getString" -> {
                            String col = (String) args[0];
                            yield "tag_mode".equals(col) ? tagMode : null;
                        }
                        case "getLong" -> {
                            String col = (String) args[0];
                            yield "tag_subscription_version".equals(col) ? tagVer : srcVer;
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName());
                    });
        }

        private ResultSet postsResultSet() {
            int[] cursor = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            cursor[0]++;
                            yield cursor[0] < posts.size();
                        }
                        case "getObject" -> {
                            String col = (String) args[0];
                            PostRow row = posts.get(cursor[0]);
                            yield switch (col) {
                                case "id" -> row.id();
                                case "source_id" -> row.sourceId();
                                case "reposts" -> row.reposts();
                                case "likes" -> row.likes();
                                case "source_window_posts" -> row.sourceWindowPosts();
                                default -> throw new UnsupportedOperationException(
                                        "getObject(" + col + ")");
                            };
                        }
                        case "getString" -> {
                            String col = (String) args[0];
                            PostRow row = posts.get(cursor[0]);
                            yield switch (col) {
                                case "uid" -> row.uid();
                                case "display_name" -> row.displayName();
                                case "title" -> row.title();
                                case "url" -> row.url();
                                case "body" -> row.body();
                                case "kind" -> row.kind();
                                case "language" -> row.language();
                                case "title_en" -> row.titleEn();
                                case "body_en" -> row.bodyEn();
                                default -> null;
                            };
                        }
                        case "getTimestamp" -> {
                            PostRow row = posts.get(cursor[0]);
                            yield Timestamp.from(row.publishedAt());
                        }
                        case "getArray" -> {
                            String col = (String) args[0];
                            PostRow row = posts.get(cursor[0]);
                            yield switch (col) {
                                case "tags" -> stubArray(row.tags());
                                case "classification" -> stubArray(row.classification());
                                default -> throw new UnsupportedOperationException(
                                        "getArray(" + col + ")");
                            };
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName());
                    });
        }

        private static Array stubArray(String[] values) {
            return (Array) Proxy.newProxyInstance(
                    Array.class.getClassLoader(),
                    new Class<?>[]{Array.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getArray" -> values;
                        case "free" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Array." + method.getName());
                    });
        }
    }
}
