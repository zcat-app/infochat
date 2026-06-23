package app.zcat.infochat.collector.outbox;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-433 integration test: {@link PostPersister} strips obfuscation
 * codepoints (bidi-control, zero-width, control characters) from the
 * upstream-controlled {@code title} and {@code url} at the ingest
 * convergence point, against the Quarkus DevServices Postgres.
 *
 * <p>Obfuscation codepoints are embedded via {@code (char) 0xNNNN} so
 * the source stays free of invisible characters.
 *
 * <ul>
 *   <li>A title carrying a {@code U+202E} bidi override is stored with
 *       the override stripped.</li>
 *   <li>A url carrying an embedded control character is stored with the
 *       control character stripped when the result is still a valid
 *       {@code http}/{@code https} URI, or NULL when stripping leaves
 *       an invalid URI.</li>
 * </ul>
 */
@QuarkusTest
class PostPersisterNormalizationIT {

    private static final char BIDI_OVERRIDE = (char) 0x202E;   // RIGHT-TO-LEFT OVERRIDE
    private static final char CONTROL_BEL = (char) 0x0007;     // BELL (C0 control)

    private static final Instant FETCHED_AT =
        Instant.parse("2026-05-15T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PostPersister postPersister;

    @Test
    void persistStripsBidiFromTitleAndControlFromValidUrl() throws Exception {
        UUID sourceUuid = seedRssSource(
            "https://norm-it.example.test/feed-valid.xml",
            "Normalization IT source valid");

        // Title with an embedded bidi override; url with an embedded
        // control character whose removal leaves a still-valid https URL.
        NormalizedPost normalized = new NormalizedPost(
            1L,
            "urn:norm-it:post:valid-url",
            /* title */ "Legit" + BIDI_OVERRIDE + "Title",
            /* body */ "body",
            /* url */ "https://norm-it.example.test/p" + CONTROL_BEL + "q",
            /* publishedAt */ null,
            FETCHED_AT,
            Map.of());

        Optional<PostPersister.PersistedPostKey> key =
            postPersister.persist(sourceUuid, normalized);
        assertTrue(key.isPresent(), "persist must INSERT");

        assertEquals("LegitTitle", readTitle(key.get().id()),
            "the U+202E bidi override must be stripped from the stored title");
        assertEquals("https://norm-it.example.test/pq", readUrl(key.get().id()),
            "the control character must be stripped and the still-valid url stored");
    }

    @Test
    void persistBindsNullUrlWhenStrippingLeavesInvalidUri() throws Exception {
        UUID sourceUuid = seedRssSource(
            "https://norm-it.example.test/feed-invalid.xml",
            "Normalization IT source invalid");

        // After stripping the control character the url is a scheme-less
        // relative path — no longer a valid http/https URI, so it is
        // bound as NULL rather than stored mangled.
        NormalizedPost normalized = new NormalizedPost(
            1L,
            "urn:norm-it:post:invalid-url",
            "Plain title",
            "body",
            /* url */ "/no-scheme" + CONTROL_BEL + "path",
            null,
            FETCHED_AT,
            Map.of());

        Optional<PostPersister.PersistedPostKey> key =
            postPersister.persist(sourceUuid, normalized);
        assertTrue(key.isPresent(), "persist must INSERT");

        assertNull(readUrl(key.get().id()),
            "a url that no longer parses as a valid http/https URI after "
            + "stripping must be bound as NULL");
    }

    private @Nullable String readTitle(UUID id) throws Exception {
        return readColumn(id, "title");
    }

    private @Nullable String readUrl(UUID id) throws Exception {
        return readColumn(id, "url");
    }

    private @Nullable String readColumn(UUID id, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM post WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(column);
            }
        }
    }

    private UUID seedRssSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                 + "VALUES ('rss', ?, ?, 'news', '{}') "
                 + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
