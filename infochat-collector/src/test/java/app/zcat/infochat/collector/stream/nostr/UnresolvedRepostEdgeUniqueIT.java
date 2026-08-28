package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins V49: {@code idx_post_ref_unique_edge} is rebuilt with
 * {@code NULLS NOT DISTINCT}, so two identical unresolved repost edges
 * (same from_post, NULL to_post, same link_type, same created_at) are
 * rejected — under V34's default NULLS DISTINCT index the NULL to_post
 * values never compared equal and both rows were admitted.
 */
@QuarkusTest
class UnresolvedRepostEdgeUniqueIT {

    private static final String TEST_TARGET_MARKER = "unresolved-edge-unique-it-target";

    /**
     * Pinned created_at inside the current-month partition. A fixed past
     * date cannot work here: the PartitionPruner drops a month's partitions
     * once they age past retention (02-schema.md §2.4.4) — the V29 May-2026
     * bootstrap partition is already gone by IT time.
     * The value is captured once so both INSERTs collide on the same key.
     */
    private static final Timestamp CREATED_AT =
        Timestamp.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @AfterEach
    void clearTestEdges() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post_reference WHERE to_upstream_identifier = ?")) {
            ps.setString(1, TEST_TARGET_MARKER);
            ps.executeUpdate();
        }
    }

    @Test
    void duplicateUnresolvedRepostEdgeIsRejected() throws Exception {
        UUID fromPost = UUID.randomUUID();
        insertUnresolvedEdge(fromPost);

        SQLException violation = assertThrows(SQLException.class,
            () -> insertUnresolvedEdge(fromPost),
            "a second identical unresolved edge must violate idx_post_ref_unique_edge");
        assertEquals("23505", violation.getSQLState(),
            "the rejection must be the unique-violation SQLSTATE, not some other failure");
    }

    private void insertUnresolvedEdge(UUID fromPost) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post_reference "
                     + "(from_post, to_post, to_upstream_identifier, link_type, score, created_at) "
                     + "VALUES (?, NULL, ?, 'repost', 1.0, ?)")) {
            ps.setObject(1, fromPost);
            ps.setString(2, TEST_TARGET_MARKER);
            ps.setTimestamp(3, CREATED_AT);
            ps.executeUpdate();
        }
    }
}
