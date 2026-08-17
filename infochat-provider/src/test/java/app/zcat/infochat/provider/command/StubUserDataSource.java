package app.zcat.infochat.provider.command;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hand-rolled JDBC stub: returns the seeded {@code users} row for
 * the contact_id passed as the second parameter to the handler's
 * actor-lookup SELECT (the first parameter is the adapter name,
 * which is asserted in {@code InboundContext} setup and not used
 * as a lookup key here). Uses {@link Proxy} for the inner JDBC
 * types so the unused surface (~200 interface methods) does not
 * leak into the test files. Mockito is intentionally absent from
 * the Provider classpath.
 */
class StubUserDataSource extends UnsupportedDataSource {

    private record UserRow(UUID id, boolean isAdmin, boolean isBanned) {}

    private final Map<String, UserRow> rowsByContactId = new ConcurrentHashMap<>();

    /**
     * Stub whose {@code getConnection()} throws {@link AssertionError}
     * — wired by scenarios that must prove the handler never touches
     * the DataSource at all.
     */
    static StubUserDataSource neverCalled() {
        return new StubUserDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError(
                        "DataSource.getConnection() called on neverCalled() stub");
            }
        };
    }

    void seedUser(String contactId, boolean isAdmin, boolean isBanned) {
        rowsByContactId.put(contactId, new UserRow(UUID.randomUUID(), isAdmin, isBanned));
    }

    @Override
    public Connection getConnection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "prepareStatement" -> newPreparedStatement();
                    case "close" -> null;
                    case "toString" -> "StubConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(
                            "Connection." + method.getName() + " not stubbed");
                });
    }

    private PreparedStatement newPreparedStatement() {
        // Capture per-statement setString parameters so executeQuery
        // can resolve the row by contact_id (parameter index 2).
        Map<Integer, String> params = new HashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "setString" -> {
                        params.put((Integer) methodArgs[0], (String) methodArgs[1]);
                        yield null;
                    }
                    case "executeQuery" -> {
                        String contactId = params.get(2);
                        // A parameterless SELECT (the unknown-tag gate's
                        // vocabulary load) has no param 2 → no row → empty.
                        UserRow row = contactId == null ? null : rowsByContactId.get(contactId);
                        yield newResultSet(row);
                    }
                    case "close" -> null;
                    case "toString" -> "StubPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(
                            "PreparedStatement." + method.getName() + " not stubbed");
                });
    }

    private ResultSet newResultSet(UserRow row) {
        boolean[] consumed = { row == null };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getObject" -> row.id();
                    case "getBoolean" -> {
                        String col = (String) methodArgs[0];
                        yield switch (col) {
                            case "is_admin" -> row.isAdmin();
                            case "is_banned" -> row.isBanned();
                            default -> throw new UnsupportedOperationException(
                                    "ResultSet.getBoolean unknown column: " + col);
                        };
                    }
                    case "close" -> null;
                    case "toString" -> "StubResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(
                            "ResultSet." + method.getName() + " not stubbed");
                });
    }
}
