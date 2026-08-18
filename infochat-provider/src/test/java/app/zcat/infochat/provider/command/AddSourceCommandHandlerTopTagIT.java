package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AddSourceCommandHandlerTopTagIT.TopTagProfile.class)
class AddSourceCommandHandlerTopTagIT {

    private static final String CONTACT_ID = "m1-882-top-adapter-user";
    private static final String URL = "https://example.com/m1-882-top-adapter.xml";

    @Inject
    InMemoryAdapter adapter;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            execute(conn,
                    "DELETE FROM source_subscription WHERE source_id IN "
                            + "(SELECT id FROM source WHERE identifier = ?)", URL);
            execute(conn, "DELETE FROM source WHERE identifier = ?", URL);
            execute(conn, "DELETE FROM users WHERE contact_id = ?", CONTACT_ID);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state, probation_until) "
                            + "VALUES ('inmemory', ?, FALSE, 'vouched', NULL)")) {
                ps.setString(1, CONTACT_ID);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void rejectedTopUsesLocalizedLeafSuggestionsWithoutEchoingInput() throws Exception {
        adapter.deliverDm(CONTACT_ID, "/add-source " + URL + " --tags tech");

        assertEquals(1, adapter.sentMessages().size(),
                "a rejected top must produce exactly one friendly reply");
        String body = adapter.sentMessages().get(0).text();
        assertTrue(body.contains("source tags must name existing leaves"),
                "the reply must explain the restricted leaf dictionary — got: " + body);
        assertTrue(body.contains("Did you mean"),
                "the reply must provide trusted dictionary suggestions — got: " + body);
        assertTrue(readLeafNames().stream().anyMatch(body::contains),
                "the suggestions must come from existing leaf rows — got: " + body);
        assertFalse(body.matches("(?s).*(?<![A-Za-z0-9_-])tech(?![A-Za-z0-9_-]).*"),
                "the reply must not echo the supplied top token — got: " + body);
        assertEquals(0L, countRows(
                "SELECT count(*) FROM source WHERE identifier = '" + URL + "'"),
                "a rejected top must not write a source");
        assertEquals(0L, countRows(
                "SELECT count(*) FROM source_subscription WHERE source_id IN "
                        + "(SELECT id FROM source WHERE identifier = '" + URL + "')"),
                "a rejected top must not write a subscription");
    }

    private List<String> readLeafNames() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name FROM tag WHERE node_kind = 'leaf' ORDER BY name LIMIT 5");
             ResultSet rs = ps.executeQuery()) {
            List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        }
    }

    private long countRows(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void execute(Connection conn, String sql, String value) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.executeUpdate();
        }
    }

    @Alternative
    @ApplicationScoped
    public static class MockUrlProbe extends UrlProbe {
        @Override
        public ProbeResult probe(URI url) {
            return ProbeResult.success(200, Optional.of("application/rss+xml"));
        }
    }

    public static final class TopTagProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(MockUrlProbe.class);
        }
    }
}
