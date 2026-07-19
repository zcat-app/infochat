package app.zcat.infochat.provider.help;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC DAO over {@code doc_embedding} (V60). Two collaborators share
 * this corpus through opposite ends:
 * <ul>
 *   <li>{@link CommandIntentIndexBuilder} at Provider startup writes
 *       the corpus via {@link #upsert}, the DELETE-then-INSERT shape
 *       the {@code doc_embedding} grants permit (provider role holds
 *       SELECT + INSERT + DELETE; UPDATE is withheld — see V60).</li>
 *   <li>{@link app.zcat.infochat.provider.chat.tool.HelpLookupTool}
 *       at chat-turn time reads the nearest match via the pgvector
 *       probe that lives in the tool itself (the tool owns the
 *       similarity threshold + tier filter; this DAO stays neutral on
 *       both).</li>
 * </ul>
 *
 * <p>The upsert is one transactional DELETE + INSERT per row, never an
 * UPDATE. Two reasons: (1) the {@code infochat_provider} role has no
 * UPDATE grant on {@code doc_embedding} (V60 — kept narrow because a
 * docs corpus is small and every change is a full re-embed of the
 * affected row, so a partial-column UPDATE path buys nothing but grant
 * surface); (2) the M1-648 r2 CLEAN audit (item 10) verified this
 * shape against the narrow grant and the spec carries that forward.
 *
 * <p>The content-hash skip the builder uses to decide whether to embed
 * a row at all runs through {@link #selectExistingForKind}: a restart
 * with an unchanged corpus returns {@code N} rows whose
 * {@link StoredRow#contentHash()} and {@link StoredRow#embeddingModel()}
 * all match, the builder computes zero deltas, and the embedding
 * backend is never called (M1-664 acceptance item 2 — pinned by
 * {@code CommandIntentIndexTest.restartWithUnchangedCorpusPerformsNoEmbeddingCall}).
 */
@ApplicationScoped
public class DocEmbeddingDao {

    private final DataSource dataSource;

    @Inject
    public DocEmbeddingDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Read every stored row for a corpus, keyed by {@code doc_id}, for
     * the startup builder's content-hash + model staleness diff. The
     * builder applies its in-memory source-text set against this map
     * and produces three buckets — unchanged-skip, re-embed (hash or
     * model differs), and prune (no longer in the in-memory set).
     *
     * @param docKind the corpus discriminator (v1: {@code "command_intent"})
     */
    public Map<String, StoredRow> selectExistingForKind(String docKind) {
        final String sql = "SELECT doc_id, content_hash, embedding_model "
                + "FROM doc_embedding WHERE doc_kind = ?";
        Map<String, StoredRow> out = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docKind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("doc_id"),
                            new StoredRow(rs.getString("content_hash"),
                                    rs.getString("embedding_model")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DocEmbeddingDao.selectExistingForKind failed for doc_kind=" + docKind, e);
        }
        return out;
    }

    /**
     * Delete {@code docIds} from corpus {@code docKind}, in one
     * transaction. Used by the builder to prune rows whose source text
     * has disappeared from the in-memory set (e.g. a catalogue command
     * was removed).
     */
    public void delete(String docKind, List<String> docIds) {
        if (docIds.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM doc_embedding WHERE doc_kind = ? AND doc_id = ANY(?)")) {
                ps.setString(1, docKind);
                ps.setArray(2, conn.createArrayOf("text", docIds.toArray()));
                ps.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DocEmbeddingDao.delete failed for doc_kind=" + docKind, e);
        }
    }

    /**
     * Upsert a batch of rows in ONE transaction, each as a
     * DELETE-then-INSERT pair keyed by {@code doc_id}. Atomic across
     * the batch — a single row's failure rolls back the whole batch,
     * which is the correct posture for a startup-built corpus (a
     * partial-write index is silently inconsistent with the catalogue;
     * a loud startup failure is the recovery signal).
     */
    public void upsert(String docKind, List<DocEmbeddingRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM doc_embedding WHERE doc_kind = ? AND doc_id = ?");
                 PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO doc_embedding "
                    + "(doc_id, doc_kind, target_ref, content_hash, embedding, embedding_model) "
                    + "VALUES (?, ?, ?, ?, ?::vector, ?)")) {
                for (DocEmbeddingRow row : rows) {
                    del.setString(1, docKind);
                    del.setString(2, row.docId());
                    del.executeUpdate();

                    ins.setString(1, row.docId());
                    ins.setString(2, docKind);
                    ins.setString(3, row.targetRef());
                    ins.setString(4, row.contentHash());
                    ins.setString(5, toVectorLiteral(row.embedding()));
                    ins.setString(6, row.embeddingModel());
                    ins.addBatch();
                }
                ins.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DocEmbeddingDao.upsert failed for doc_kind=" + docKind, e);
        }
    }

    /**
     * pgvector literal form: {@code "[v1,v2,...,vN]"} — the text input
     * shape {@code ?::vector} casts. Kept package-private so a sibling
     * class in the help package (e.g. a future direct-lookup helper)
     * can reuse it; the chat tool reimplements its own copy under
     * {@code provider.chat.tool} to avoid widening this DAO's API
     * across packages.
     */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Subset of {@code doc_embedding} the builder needs at preflight time. */
    public record StoredRow(String contentHash, String embeddingModel) {}

    /** Full row the builder writes when a delta needs embedding. */
    public record DocEmbeddingRow(String docId, String targetRef,
                                  String contentHash, float[] embedding,
                                  String embeddingModel) {

        /** Defensive copy on construction and read, mirroring {@code EmbeddingResult}. */
        public DocEmbeddingRow {
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }
}