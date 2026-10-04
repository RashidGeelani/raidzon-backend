package com.raidzon.config;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Per-request database timing. {@link TimedDataSource} adds the time spent waiting for a pooled
 * connection and running statements, commits and rollbacks; {@link TimingFilter} reports it.
 */
public final class RequestTiming {
    private static final ThreadLocal<long[]> CURRENT = new ThreadLocal<>();
    private RequestTiming() {}

    static void start() { CURRENT.set(new long[3]); }
    /** [db nanos, statements, connection-wait nanos], or null outside a timed request. */
    static long[] finish() { var value = CURRENT.get(); CURRENT.remove(); return value; }

    private static void add(int slot, long nanos, boolean counts) {
        var value = CURRENT.get();
        if (value == null) return;
        value[slot] += nanos;
        if (counts) value[1]++;
    }

    /** A HikariDataSource whose connections time their round trips to Postgres. */
    public static class TimedDataSource extends HikariDataSource {
        public TimedDataSource(com.zaxxer.hikari.HikariConfig config) { super(config); }

        @Override public Connection getConnection() throws SQLException {
            long started = System.nanoTime();
            var connection = super.getConnection();
            add(2, System.nanoTime() - started, false);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler(connection, true));
        }
    }

    private static InvocationHandler handler(Object target, boolean connection) {
        return (proxy, method, args) -> {
            String name = method.getName();
            boolean timed = name.startsWith("execute") || name.equals("commit") || name.equals("rollback");
            long started = timed ? System.nanoTime() : 0;
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            } finally {
                if (timed) add(0, System.nanoTime() - started, true);
            }
            if (connection && result instanceof Statement statement) {
                Class<?> type = method.getReturnType();
                if (type.isInterface() && Statement.class.isAssignableFrom(type))
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {type},
                        handler(statement, false));
            }
            return result;
        };
    }
}
