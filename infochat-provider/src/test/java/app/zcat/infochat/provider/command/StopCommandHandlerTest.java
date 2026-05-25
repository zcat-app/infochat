package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.messaging.InboundContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopCommandHandlerTest {

    private static final String PREFIX = "m1-065-stop-";
    private static final UUID USER_ID = UUID.randomUUID();

    private StopCommandHandler handler;
    private RecordingCancellationService cancellationService;
    private RecordingConfirmStateService confirmService;
    private InFlightTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        cancellationService = new RecordingCancellationService();
        confirmService = new RecordingConfirmStateService();
        tracker = new InFlightTracker();

        handler = new StopCommandHandler();
        handler.bundleLoader = newRealBundleLoader();
        handler.dataSource = stubUserDataSource(USER_ID);
        handler.cancellationService = cancellationService;
        handler.inFlightTracker = tracker;
        handler.confirmStateService = confirmService;
        InboundContext ctx = new InboundContext();
        ctx.setAdapterName("inmemory");
        handler.inboundContext = ctx;
    }

    @Test
    void handlerNameIsLiteralStop() {
        assertEquals("stop", handler.name());
    }

    @Test
    void cancelsInFlightChatRequest() {
        cancellationService.nextResult = true;

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "inflight"), "/stop");

        assertTrue(cancellationService.cancelCalled,
                "CancellationService.cancel must be called");
        assertTrue(reply.text().contains("Cancelled the in-flight request"),
                "reply must confirm the cancellation. Got: " + reply.text());
    }

    @Test
    void cancelsPendingConfirmation() {
        cancellationService.nextResult = false;
        confirmService.pendingCommandName = "forget";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "confirm"), "/stop");

        assertTrue(confirmService.takeAnyCalled,
                "ConfirmStateService.takeAny must be called");
        assertTrue(reply.text().contains("/forget"),
                "reply must name the cancelled confirmation. Got: " + reply.text());
    }

    @Test
    void idempotentWhenNothingInFlight() {
        cancellationService.nextResult = false;
        confirmService.pendingCommandName = null;

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "noop"), "/stop");

        assertTrue(reply.text().contains("Nothing to cancel"),
                "reply must indicate nothing was cancelled. Got: " + reply.text());
    }

    @Test
    void cancelsBothInFlightAndPendingConfirmation() {
        cancellationService.nextResult = true;
        confirmService.pendingCommandName = "forget";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "both"), "/stop");

        assertTrue(reply.text().contains("in-flight request"),
                "reply must mention in-flight cancellation. Got: " + reply.text());
        assertTrue(reply.text().contains("/forget"),
                "reply must mention the cancelled confirmation. Got: " + reply.text());
    }

    // ----- stubs -----------------------------------------------------------

    private static class RecordingCancellationService extends CancellationService {
        boolean cancelCalled = false;
        boolean nextResult = false;

        @Override
        public boolean cancel(UUID userId, String scopeKind, UUID scopeId) {
            cancelCalled = true;
            return nextResult;
        }
    }

    private static class RecordingConfirmStateService extends ConfirmStateService {
        boolean takeAnyCalled = false;
        String pendingCommandName = null;

        @Override
        public Optional<PendingConfirm> takeAny(UUID actorUserId, ScopeRef scope) {
            takeAnyCalled = true;
            if (pendingCommandName == null) {
                return Optional.empty();
            }
            return Optional.of(new PendingConfirm() {
                @Override
                public String commandName() { return pendingCommandName; }
                @Override
                public String sweepPrefix() { return pendingCommandName; }
            });
        }
    }

    private static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }

    private static DataSource stubUserDataSource(UUID userId) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "prepareStatement" -> (PreparedStatement) Proxy.newProxyInstance(
                                    PreparedStatement.class.getClassLoader(),
                                    new Class<?>[] { PreparedStatement.class },
                                    (pProxy, pMethod, pArgs) -> switch (pMethod.getName()) {
                                        case "setString", "setObject" -> null;
                                        case "executeQuery" -> {
                                            boolean[] consumed = { false };
                                            yield (ResultSet) Proxy.newProxyInstance(
                                                    ResultSet.class.getClassLoader(),
                                                    new Class<?>[] { ResultSet.class },
                                                    (rProxy, rMethod, rArgs) -> switch (rMethod.getName()) {
                                                        case "next" -> {
                                                            if (consumed[0]) yield false;
                                                            consumed[0] = true;
                                                            yield true;
                                                        }
                                                        case "getObject" -> userId;
                                                        case "close" -> null;
                                                        default -> throw new UnsupportedOperationException(
                                                                "RS." + rMethod.getName());
                                                    });
                                        }
                                        case "close" -> null;
                                        default -> throw new UnsupportedOperationException(
                                                "PS." + pMethod.getName());
                                    });
                            case "close" -> null;
                            default -> throw new UnsupportedOperationException(
                                    "Conn." + method.getName());
                        });
            }

            @Override public Connection getConnection(String u, String p) { return getConnection(); }
            @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
            @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
            @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
            @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
            @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
