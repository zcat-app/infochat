package app.zcat.infochat.provider.testing;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Applies the pre-evaluated READY-post seed fixture
 * ({@code /fixtures/seed-ready-posts.sql}) to the test database. The fixture
 * inserts rows in their post-evaluation TERMINAL state directly — no Stage 1/2,
 * tagging, or embedding pipeline is run — so retrieval-tier tests get realistic,
 * already-evaluated content without an LLM or a network fetch (M1-413).
 *
 * <p>The SQL is idempotent (it deletes its own rows, keyed on fixed UUIDs and
 * the {@code m1-413-} identifier prefix, before re-inserting), so the loader is
 * safe to call once per {@code @QuarkusTest} without cross-run races.
 *
 * <p>The deterministic identifiers below are the stable contract the downstream
 * testing-tool tickets (M1-414 dev harness, M1-415 golden path) assert against;
 * keep them in sync with the SQL resource.
 */
final class SeedFixture {

    /** DM scope owner the fixture subscribes to {@link #SOURCE_ID}. */
    static final UUID USER_ID = UUID.fromString("00000413-0000-4000-8000-000000000001");

    /** Active, non-deleted source the seeded posts belong to. */
    static final UUID SOURCE_ID = UUID.fromString("00000413-0000-4000-8000-000000000010");

    /** {@code source_subscription.scope_kind} for the seeded DM scope. */
    static final String SCOPE_KIND = "dm";

    /** uid of the seeded READY post that HAS an embedding row. */
    static final String READY_UID_SECURITY = "m1-413-ready-security";

    /** uid of a seeded READY post with NULL embedding. */
    static final String READY_UID_AI = "m1-413-ready-ai";

    /** uid of a seeded READY post with NULL embedding. */
    static final String READY_UID_JAVA = "m1-413-ready-java";

    /** uid of the seeded RAW post (excluded from deterministic retrieval). */
    static final String RAW_UID = "m1-413-raw";

    /** uid of the seeded QUARANTINED post (excluded from deterministic retrieval). */
    static final String QUARANTINED_UID = "m1-413-quarantined";

    private static final String FIXTURE_RESOURCE = "/fixtures/seed-ready-posts.sql";

    private SeedFixture() {
    }

    /**
     * Load and execute the seed SQL against {@code dataSource}. Callers supply
     * the owner-role {@code @SeedDataSource} so the fixture may write
     * collector-owned tables (post_embedding) the service role cannot. The
     * whole script runs as one multi-statement {@link Statement#execute} — the
     * PostgreSQL driver's simple-query protocol applies the semicolon-separated
     * statements in order.
     */
    static void applyTo(DataSource dataSource) {
        String sql = readFixtureSql();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply seed fixture " + FIXTURE_RESOURCE, e);
        }
    }

    private static String readFixtureSql() {
        try (InputStream in = SeedFixture.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Seed fixture resource not found: " + FIXTURE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seed fixture " + FIXTURE_RESOURCE, e);
        }
    }
}
