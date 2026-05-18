package app.zcat.infochat.collector.eval.tagger;

import io.quarkus.runtime.Startup;
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
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Controlled-vocabulary cache for the Tagger pipeline. Loads
 * {@code SELECT name FROM tag} once at startup into an immutable
 * {@link Set} so per-post tagger validation is an O(1) hash lookup
 * rather than a per-call DB round-trip.
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

    /**
     * The tag-name character class — same regex as V6's
     * {@code tag.name} CHECK constraint. Inlined here to keep the
     * normalization rule self-contained at every site that applies
     * it.
     */
    static final Pattern TAG_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,47}$");

    @Inject
    DataSource dataSource;

    private Set<String> names = Set.of();

    @PostConstruct
    void load() {
        Set<String> loaded = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM tag ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString(1);
                String normalized = normalize(raw);
                if (normalized != null) {
                    loaded.add(normalized);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "TagVocabulary: failed to load tag table", e);
        }
        this.names = Set.copyOf(loaded);
        LOG.infof("TagVocabulary: loaded %d controlled-vocabulary tags", names.size());
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

    /**
     * Apply the tag normalization rule (NFC + Locale.ROOT lower-case
     * + character class) and return the normalized form, or
     * {@code null} when the input fails the character-class filter.
     * Package-private so {@link TaggerWorker} reuses the same logic
     * without a public-API commitment.
     */
    // TODO(T1-D): move to TagNormalizer helper alongside
    // BootstrapLoader.normalizeTag.
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String lower = nfc.toLowerCase(Locale.ROOT);
        return TAG_NAME_PATTERN.matcher(lower).matches() ? lower : null;
    }
}
