package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.core.util.TagNormalizer;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Controlled-vocabulary cache for the Tagger pipeline. Loads the {@code
 * tag} table at startup so per-post tagger validation is an O(1) hash
 * lookup rather than a per-call DB round-trip, then refreshes on a
 * schedule so a tag added at runtime (e.g. via {@code /add-source})
 * becomes visible to the tagger without a Collector restart.
 * <p>The published vocabulary is LEAVES ONLY ({@code node_kind='leaf'}, V82):
 * tops and the priority order never reach the prompt render (M1-865 analysis
 * P7/P8); the full row set rides along as the resolver's tree snapshot.
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
 * <h2>Iteration order (load-bearing)</h2>
 *
 * <p>{@link #names()} iterates in the {@code ORDER BY name} order of the
 * load query, and that is a contract rather than an accident.
 * {@link TaggerWorker#renderPrompt} expands the vocabulary straight into
 * the tagger prompt's {@code {#tags}} block, so this iteration order IS
 * the order the model sees, and LLM output is order-sensitive. Publishing
 * through a hash-ordered set would therefore make ingest tagging vary
 * from one Collector restart to the next — and between two Collectors on
 * the same database — for reasons unrelated to the post, the vocabulary
 * or the model, leaving an irreproducibility no operator can see. Hence
 * the load path preserves the query's order instead of copying into
 * {@link Set#copyOf}, whose iteration order is randomized per JVM by a
 * process-wide salt (M1-751).
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

    /** One tag row as the resolver sees it: the node-kind discriminator + normalized parent link (null = root) + the fallback marking (false until the V84 seed lands — M1-878). */
    public record TagNode(boolean top, @Nullable String parent, boolean fallback) {
    }

    /** The atomically-swapped load result: leaf-only names (query order) plus the full tree map — one field, never a mix. */
    record Snapshot(Set<String> names, Map<String, TagNode> tree) {
    }

    // volatile: the scheduled refresh swaps the (immutable) snapshot from
    // the scheduler thread while tagger worker threads read it; each
    // reader sees either the old or the new complete one, never a mix.
    private volatile Snapshot snapshot = new Snapshot(Set.of(), Map.of());

    @PostConstruct
    void load() {
        // Startup load failure is fatal (the tagger must never run with
        // an empty vocabulary it would interpret as "reject everything").
        this.snapshot = loadFromDatabase();
        LOG.infof("TagVocabulary: loaded %d controlled-vocabulary tags", snapshot.names().size());
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
        Snapshot reloaded;
        try {
            reloaded = loadFromDatabase();
        } catch (IllegalStateException e) {
            Throwable cause = e.getCause();
            Throwable rootCause = cause != null ? cause : e;
            LOG.warnf("TagVocabulary: refresh failed (%s); keeping previous %d-tag vocabulary",
                rootCause.getClass().getName(), snapshot.names().size());
            return;
        }
        if (reloaded.names().size() != snapshot.names().size()) {
            LOG.infof("TagVocabulary: refreshed vocabulary, %d -> %d tags",
                snapshot.names().size(), reloaded.names().size());
        }
        this.snapshot = reloaded;
    }

    private Snapshot loadFromDatabase() {
        Set<String> leaves = new LinkedHashSet<>();
        Map<String, TagNode> tree = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, node_kind, parent_name FROM tag ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = TagNormalizer.normalize(rs.getString(1));
                if (name == null) {
                    continue;
                }
                boolean top = "top".equals(rs.getString(2));
                // fallback=false is the true value of every row until the
                // V84 seed marks the fallback leaf (M1-878); no column exists before then.
                tree.put(name, new TagNode(top, TagNormalizer.normalize(rs.getString(3)), false));
                if (!top) {
                    leaves.add(name);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "TagVocabulary: failed to load tag table", e);
        }
        // Publish insertion order (the query's ORDER BY name), not Set.copyOf's
        // salted hash order — the class javadoc has the why. Local-only copies +
        // unmodifiable wrappers + the volatile snapshot supply safe publication.
        return new Snapshot(
            Collections.unmodifiableSet(leaves), Collections.unmodifiableMap(tree));
    }

    /**
     * The full, immutable vocabulary set — LEAVES ONLY — iterating in the
     * load query's {@code ORDER BY name} order; see the class javadoc's
     * <i>Iteration order</i> section for why that order is load-bearing.
     * Used by the tagger prompt builders that iterate the vocabulary into
     * a Qute template.
     */
    public Set<String> names() {
        return snapshot.names();
    }

    /** Byte-equal membership check against the normalized leaf vocabulary; callers normalize first. */
    public boolean contains(String normalized) {
        return snapshot.names().contains(normalized);
    }

    /** The full tree (tops included) the resolver walks; swapped atomically with {@link #names()}. */
    public Map<String, TagNode> tree() {
        return snapshot.tree();
    }

}
