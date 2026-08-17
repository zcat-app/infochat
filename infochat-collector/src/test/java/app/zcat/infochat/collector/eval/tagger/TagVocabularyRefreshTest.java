package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.util.TagNormalizer;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the runtime-refresh contract on {@link TagVocabulary}: a tag
 * added to the {@code tag} table after startup (the {@code /add-source}
 * path extends the vocabulary at runtime) becomes visible to the tagger
 * without a Collector restart. The behavioural test drives
 * {@link TagVocabulary#refresh()} directly — deterministic, no waiting
 * on the scheduler tick — and the reflective test pins the scheduler
 * wiring that makes the same reload happen unattended (same convention
 * as ReEvaluationJobConcurrentExecutionTest).
 */
@QuarkusTest
class TagVocabularyRefreshTest {

    private static final String RUNTIME_TAG = "m1-276-refresh-tag";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TagVocabulary tagVocabulary;

    @AfterEach
    void removeRuntimeTag() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM tag WHERE name = ?")) {
            ps.setString(1, RUNTIME_TAG);
            ps.executeUpdate();
        }
        // Re-sync the bean so the deleted tag does not linger in the
        // shared vocabulary for later test classes.
        tagVocabulary.refresh();
    }

    @Test
    void runtimeAddedTagBecomesVisibleAfterRefreshWithoutRestart() throws Exception {
        assertFalse(tagVocabulary.contains(RUNTIME_TAG),
            "fixture invalid: runtime tag already in the startup-loaded vocabulary");

        insertTag(RUNTIME_TAG);
        tagVocabulary.refresh();

        assertTrue(tagVocabulary.contains(RUNTIME_TAG),
            "a tag added at runtime must enter the vocabulary on refresh, without a restart");
        assertTrue(tagVocabulary.names().contains(RUNTIME_TAG),
            "the refreshed set must also surface through names() (prompt builders)");
    }

    /**
     * The vocabulary is rendered straight into the tagger prompt, so its
     * iteration order reaches the model and must not change from one
     * Collector run to the next. Both publication paths — the startup load
     * and the scheduled refresh — return through the same method, so both
     * are asserted here.
     *
     * <p>The comparison is deliberately against a {@link List}: a
     * set-equality assertion holds on the {@code Set.copyOf} publication
     * this test exists to catch, because that defect reorders the same
     * names rather than changing them (M1-751).
     */
    @Test
    void namesIteratesInQueryOrderAfterInitialLoadAndAfterRefresh() throws Exception {
        tagVocabulary.load();

        List<String> afterLoad = tagTableInQueryOrder();
        assertTrue(afterLoad.size() > 1,
            "fixture invalid: an order assertion over fewer than two tags cannot fail");
        assertEquals(afterLoad, List.copyOf(tagVocabulary.names()),
            "names() must iterate in the load query's ORDER BY name order after the startup load");

        insertTag(RUNTIME_TAG);
        tagVocabulary.refresh();

        List<String> afterRefresh = tagTableInQueryOrder();
        assertTrue(afterRefresh.contains(RUNTIME_TAG),
            "fixture invalid: the runtime tag is not in the table the refresh reads");
        assertEquals(afterRefresh, List.copyOf(tagVocabulary.names()),
            "the refresh must publish the same ORDER BY name order as the startup load");
    }

    @Test
    void refreshIsScheduledWithSkipConcurrentExecution() throws NoSuchMethodException {
        Method refresh = TagVocabulary.class.getDeclaredMethod("refresh");
        Scheduled scheduled = refresh.getAnnotation(Scheduled.class);
        assertEquals("{infochat.tagger.vocabulary-refresh-interval:5m}", scheduled.every(),
            "refresh must run on the property-driven interval so the reload happens unattended");
        assertEquals(Scheduled.ConcurrentExecution.SKIP, scheduled.concurrentExecution(),
            "overlapping reloads would be wasted duplicate table scans");
    }

    /**
     * The exact sequence {@code TagVocabulary.loadFromDatabase} projects —
     * the same query, the same leaf and normalization filter, the same
     * insertion-ordered de-duplication — so the assertion pins the order
     * the load publishes rather than a second guess at what it should be.
     */
    private List<String> tagTableInQueryOrder() throws Exception {
        Set<String> ordered = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                  "SELECT name FROM tag WHERE node_kind = 'leaf' ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String normalized = TagNormalizer.normalize(rs.getString(1));
                if (normalized != null) {
                    ordered.add(normalized);
                }
            }
        }
        return List.copyOf(ordered);
    }

    private void insertTag(String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin) VALUES (?, ?, 'user') "
                     + "ON CONFLICT (name) DO NOTHING")) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }
}
