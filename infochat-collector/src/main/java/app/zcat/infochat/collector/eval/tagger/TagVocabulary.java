package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.core.util.TagNormalizer;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Controlled-vocabulary cache for the Tagger pipeline. Loads
 * {@code SELECT name FROM tag} at startup into an immutable
 * {@link Set} so per-post tagger validation is an O(1) hash lookup
 * rather than a per-call DB round-trip, then refreshes the set on a
 * schedule so a tag added at runtime (e.g. via {@code /add-source})
 * becomes visible to the tagger without a Collector restart.
 *
 * <h2>Normalization rule (load-bearing)</h2>
 *
 * <p>Loaded names are passed through the tag normalization rule:
 * <ul>
 *   <li><b>NFC</b> — Unicode canonical composition.</li>
 *   <li><b>{@code Locale.ROOT.toLowerCase}</b> — locale-independent
 *       lower-casing (Turkish-dotted-i etc. cannot leak in via the
 *       JVM's default locale).</li>
 *   <li><b>Character class {@code ^[a-z0-9][a-z0-9-]{0,47}$}</b> —
 *       the same regex the {@code tag.name} CHECK constraint enforces
 *       in V6. Names that don't match are dropped at load time (they
 *       shouldn't exist in the table given the CHECK, but the
 *       belt-and-suspenders filter survives a future migration that
 *       relaxes the CHECK or seeds via a privileged path that
 *       bypasses it).</li>
 * </ul>
 *
 * <p>The same rule applies to tagger output in
 * {@link TaggerWorker#normalizeTag(String)} so {@link #contains(String)}
 * is byte-equal between the vocabulary and an LLM-emitted tag after
 * normalization. Diverging on this rule silently breaks partial-valid
 * handling (the membership check would falsely reject a tag that
 * differs only by composition form or case).
 *
 * <h2>Startup ordering</h2>
 *
 * <p>{@code @Priority(350)} runs after BootstrapLoader (200) — which
 * seeds the initial vocabulary — and the OutboxRehydrator (300), but
 * before TaggerWorker's first scheduled tick (which fires at the
 * configured poll-interval, not at @Startup). The vocabulary is
 * therefore populated before any tagger call would consult it.
 */
@Startup
@Priority(350)
@ApplicationScoped
public class TagVocabulary {

    private static final Logger LOG = Logger.getLogger(TagVocabulary.class);

    @Inject
    DataSource dataSource;

    // volatile: the scheduled refresh swaps the (immutable) set from the
    // scheduler thread while tagger worker threads read it; each reader
    // sees either the old or the new complete set, never a partial one.
    private volatile Set<String> names = Set.of();

    @PostConstruct
    void load() {
        // Startup load failure is fatal (the tagger must never run with
        // an empty vocabulary it would interpret as "reject everything").
        this.names = loadFromDatabase();
        LOG.infof("TagVocabulary: loaded %d controlled-vocabulary tags", names.size());
    }

    /**
     * Periodic reload so tags added at runtime ({@code /add-source})
     * enter the vocabulary without a restart. A transient DB failure
     * keeps the last good set — serving a slightly stale vocabulary
     * beats dropping it mid-flight. Only the exception class is logged:
     * an SQLException message can echo bound parameters.
     */
    @Scheduled(every = "{infochat.tagger.vocabulary-refresh-interval:5m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        Set<String> reloaded;
        try {
            reloaded = loadFromDatabase();
        } catch (IllegalStateException e) {
            Throwable cause = e.getCause();
            Throwable rootCause = cause != null ? cause : e;
            LOG.warnf("TagVocabulary: refresh failed (%s); keeping previous %d-tag vocabulary",
                rootCause.getClass().getName(), names.size());
            return;
        }
        if (reloaded.size() != names.size()) {
            LOG.infof("TagVocabulary: refreshed vocabulary, %d -> %d tags",
                names.size(), reloaded.size());
        }
        this.names = reloaded;
    }

    private Set<String> loadFromDatabase() {
        Set<String> loaded = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM tag ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString(1);
                String normalized = TagNormalizer.normalize(raw);
                if (normalized != null) {
                    loaded.add(normalized);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "TagVocabulary: failed to load tag table", e);
        }
        return Set.copyOf(loaded);
    }

    /**
     * The full, immutable vocabulary set. Used by the tagger prompt
     * builders that iterate the vocabulary into a Qute template.
     */
    public Set<String> names() {
        return names;
    }

    /**
     * Byte-equal membership check against the normalized vocabulary.
     * The caller is responsible for normalizing the input tag with
     * the same rule (see {@link TaggerWorker#normalizeTag(String)}).
     */
    public boolean contains(String normalized) {
        return normalized != null && names.contains(normalized);
    }

}
