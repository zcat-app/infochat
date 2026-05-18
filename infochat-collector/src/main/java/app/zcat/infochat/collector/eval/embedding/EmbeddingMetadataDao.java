package app.zcat.infochat.collector.eval.embedding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * The SOLE Java-side writer to the {@code embedding_metadata}
 * singleton row in M1. Per the model identity guard in
 * {@code docs/spec/llm.md} §Embedding pipeline ("The active embedding
 * model's identifier and dimensionality are stored in a singleton
 * metadata row on first use; mismatch refuses startup unless an
 * explicit operator override flag is set"):
 *
 * <ul>
 *   <li>{@link #readSingleton()} returns the seeded row (V11 inserts
 *       {@code ('nomic-embed-text', 768)} during M1-034a's migration,
 *       so the row exists before this Dao runs).</li>
 *   <li>{@link #updateSingleton(String, int)} rotates the row to a
 *       new {@code (model_identifier, dimension)} pair AND advances
 *       {@code updated_at} via {@code now()}. Used ONLY by the
 *       operator-override path in {@link EmbeddingMetadataStartupGuard};
 *       the steady-state production path is read-only.</li>
 * </ul>
 *
 * <p>V11's seed INSERT is the only other write — it lives in a
 * {@code .sql} migration file (not under {@code src/main/java/}), so
 * the "sole writer in Java" boundary is mechanical: a grep over
 * {@code src/main/java/} for {@code INSERT INTO embedding_metadata}
 * or {@code UPDATE embedding_metadata} returns matches only here.
 *
 * <p>The Dao deliberately stays minimal — no caching, no transaction
 * boundary of its own. The startup guard (the only steady-state
 * consumer) runs once per JVM lifetime, so a re-query is a non-issue;
 * the EmbeddingWorker reads {@code embedding_model} via this Dao at
 * INSERT time but the value is immutable across the JVM lifetime so
 * it caches the result in its own state.
 */
@ApplicationScoped
public class EmbeddingMetadataDao {

    @Inject
    DataSource dataSource;

    /**
     * Read the singleton metadata row. Returns {@link Optional#empty()}
     * if no row exists (defensive: V11's seed INSERT guarantees the
     * row from the first Flyway run forward, but a hand-cleaned test
     * DB or a future migration that removed the seed should not NPE).
     */
    public Optional<Metadata> readSingleton() {
        final String sql =
            "SELECT model_identifier, dimension FROM embedding_metadata LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new Metadata(
                rs.getString("model_identifier"),
                rs.getInt("dimension")));
        } catch (SQLException e) {
            throw new IllegalStateException(
                "EmbeddingMetadataDao: read of embedding_metadata failed", e);
        }
    }

    /**
     * Rotate the singleton row to a new {@code (model, dimension)}
     * pair. The CREATE UNIQUE INDEX ON {@code embedding_metadata
     * ((TRUE))} guarantees one row, so the unqualified UPDATE
     * targets exactly the singleton; {@code updated_at = now()}
     * records the rotation timestamp for the audit trail.
     */
    public void updateSingleton(String modelIdentifier, int dimension) {
        final String sql =
            "UPDATE embedding_metadata SET model_identifier = ?, dimension = ?, updated_at = now()";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modelIdentifier);
            ps.setInt(2, dimension);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "EmbeddingMetadataDao: update of embedding_metadata failed", e);
        }
    }

    /** Snapshot of the singleton row at read time. */
    public record Metadata(String modelIdentifier, int dimension) {
    }
}
