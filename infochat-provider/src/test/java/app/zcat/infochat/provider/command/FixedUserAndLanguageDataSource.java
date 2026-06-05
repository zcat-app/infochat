package app.zcat.infochat.provider.command;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Hand-rolled JDBC stub: answers the handler's users-id lookup with
 * the fixed constructor-supplied id (any contact id matches) and a
 * {@code scope_preferences} language lookup with {@code "en"}.
 * Mockito is intentionally absent from the Provider classpath.
 */
final class FixedUserAndLanguageDataSource extends UnsupportedDataSource {
    private final UUID userId;

    FixedUserAndLanguageDataSource(UUID userId) {
        this.userId = userId;
    }

    @Override
    public Connection getConnection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        String sql = (String) methodArgs[0];
                        yield newPreparedStatement(sql);
                    }
                    case "close" -> null;
                    case "toString" -> "StubConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(
                            "Connection." + method.getName() + " not stubbed");
                });
    }

    private PreparedStatement newPreparedStatement(String sql) {
        boolean isScopePrefsQuery = sql.contains("scope_preferences");
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "setString", "setObject" -> null;
                    case "executeQuery" ->
                            isScopePrefsQuery ? newLanguageResultSet() : newUserIdResultSet();
                    case "close" -> null;
                    case "toString" -> "StubPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(
                            "PreparedStatement." + method.getName() + " not stubbed");
                });
    }

    private ResultSet newUserIdResultSet() {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getObject" -> userId;
                    case "close" -> null;
                    case "toString" -> "StubResultSet(userId)";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(
                            "ResultSet." + method.getName() + " not stubbed");
                });
    }

    private ResultSet newLanguageResultSet() {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getString" -> "en";
                    case "close" -> null;
                    case "toString" -> "StubResultSet(language)";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(
                            "ResultSet." + method.getName() + " not stubbed");
                });
    }
}
