package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.source.KindResolver;
import app.zcat.infochat.provider.source.SourceUpsertService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins §Secrets handling's "Contact IDs are logged in redacted form
 * (prefix + ellipsis + suffix) outside the audit log" guarantee on
 * the two {@link IllegalStateException} construction paths
 * remediated by M1-039 (resolving M1-036 red-team finding 3):
 *
 * <ul>
 *   <li>{@link AddSourceCommandHandler#handle(ScopeRef, String)} →
 *       {@code lookupActor} wraps a {@link SQLException} from
 *       {@code DataSource.getConnection()} into an
 *       {@link IllegalStateException}; the wrapping message must
 *       carry only a redacted contact id, never the raw value.</li>
 *   <li>{@link SourceUpsertService#upsert} wraps a
 *       {@link SQLException} into an {@link IllegalStateException};
 *       the wrapping message must carry only a redacted source
 *       identifier, never the raw URL.</li>
 * </ul>
 *
 * <p>The tests instantiate each SUT directly and swap in a
 * {@link BrokenDataSource} that throws on every
 * {@code getConnection()} call. This isolates the test from
 * Postgres (no migrations, no DB role boot) and pins the redaction
 * shape regardless of Quarkus boot state. The
 * {@link SQLException} cause must remain attached so ops still see
 * the underlying SQL diagnostic in the cause's stack trace; only
 * the user-derived strings in the wrapping
 * {@link IllegalStateException} message are redacted.</p>
 */
class AddSourceContactIdRedactionTest {

    /**
     * A contact id at least
     * {@code ContactIds.MIN_REDACTABLE_LENGTH=16} characters long so
     * {@code ContactIds.redact} returns the prefix + "..." + suffix
     * form rather than {@code <short>}; the assertion that the raw
     * value does NOT appear in the wrapping message only carries
     * weight against an input long enough to be visibly distinct
     * from its redacted form.
     */
    private static final String LONG_CONTACT_ID =
            "simplex-queue-abc1234567890def4567890";

    /**
     * A source URL whose full form must not appear in the wrapping
     * exception. Long enough to exceed
     * {@code MIN_REDACTABLE_LENGTH} so the redacted form is distinct.
     */
    private static final String LONG_URL =
            "https://example.com/m1-039-redaction-test/feed.xml";

    @Test
    void lookupActorIllegalStateExceptionRedactsContactId() {
        AddSourceCommandHandler handler = new AddSourceCommandHandler();
        // Package-private fields — same package as the test.
        handler.dataSource = new BrokenDataSource();
        // M1-040 wired an InboundContext lookup into lookupActor
        // (adapter + contact_id qualified SELECT). It runs BEFORE the
        // SQLException short-circuit; supply an empty InboundContext
        // so adapterName() returns null without an NPE — the adapter
        // value never reaches the SELECT because getConnection()
        // throws first.
        handler.inboundContext = new InboundContext();
        // The other @Inject fields stay null; the SQLException short-
        // circuits before any of them is dereferenced.

        IllegalStateException ise = assertThrows(
                IllegalStateException.class,
                () -> handler.handle(new ScopeRef.Dm(LONG_CONTACT_ID),
                        "/add-source https://example.com/x --tags m1-039-news"));

        assertFalse(ise.getMessage().contains(LONG_CONTACT_ID),
                "wrapping IllegalStateException must not carry the raw contact id "
                        + "— message: " + ise.getMessage());
        assertNotNull(ise.getCause(),
                "the SQLException cause must be preserved for ops debugging");
        assertTrue(ise.getCause() instanceof SQLException,
                "the cause must be the underlying SQLException, not a wrapped form "
                        + "— cause: " + ise.getCause());
    }

    @Test
    void sourceUpsertServiceIllegalStateExceptionRedactsIdentifier() throws Exception {
        SourceUpsertService service = new SourceUpsertService();
        // SourceUpsertService is in a different package; the field is
        // package-private, so reflect with setAccessible(true) to set
        // the test DataSource without widening visibility in production.
        Field dataSourceField = SourceUpsertService.class.getDeclaredField("dataSource");
        dataSourceField.setAccessible(true);
        dataSourceField.set(service, new BrokenDataSource());

        IllegalStateException ise = assertThrows(
                IllegalStateException.class,
                () -> service.upsert(
                        UUID.randomUUID(),                  // actorUserId
                        false,                              // actorIsBotAdmin
                        "dm",                               // scopeKind
                        UUID.randomUUID(),                  // scopeId
                        KindResolver.SourceKind.RSS,        // kind
                        LONG_URL,                           // identifier
                        "example",                          // displayName
                        "news",                             // category
                        "en",                               // language
                        List.of("m1-039-news")));           // tags

        assertFalse(ise.getMessage().contains(LONG_URL),
                "wrapping IllegalStateException must not carry the raw source URL "
                        + "— message: " + ise.getMessage());
        assertNotNull(ise.getCause(),
                "the SQLException cause must be preserved for ops debugging");
        assertTrue(ise.getCause() instanceof SQLException,
                "the cause must be the underlying SQLException, not a wrapped form "
                        + "— cause: " + ise.getCause());
    }

    /**
     * DataSource stub whose every {@code getConnection()} call throws
     * a {@link SQLException}, simulating a transient pool failure or
     * a Postgres connection refusal during failover. The other
     * DataSource methods are never called by the SUT on this path;
     * they return reasonable no-op values so a JVM that introspects
     * the bean does not surprise the test.
     */
    private static final class BrokenDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("simulated transient connection failure");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("simulated transient connection failure");
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("test"); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
