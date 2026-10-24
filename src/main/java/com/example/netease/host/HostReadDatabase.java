package com.example.netease.host;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Read-only JDBC-shaped view over the host's own SQLite library.
 * Loading a second SQLite implementation against the same Linux file breaks
 * process-wide POSIX locks, even when that second connection is read-only. */
public final class HostReadDatabase {
    private HostReadDatabase() { }

    public static Connection open(Path path) throws SQLException {
        Reader connection = nativeConnection(path);
        try {
            connection.pragma("PRAGMA query_only=ON");
            return proxy(Connection.class, connection);
        } catch (Throwable error) { connection.close(); throw sqlError(error); }
    }

    /** A consistent backup without opening/closing the live DB/WAL/SHM with file I/O.
     * POSIX locks belong to the process: closing even a copy-only file descriptor
     * can release SQLite's locks and invalidate its live WAL mapping. */
    public static void backup(Path source, Path destination) throws SQLException {
        Reader connection = nativeConnection(source);
        try { connection.pragma("VACUUM INTO '" + destination.toAbsolutePath().toString().replace("'", "''") + "'"); }
        finally { connection.close(); }
    }

    private static Reader nativeConnection(Path path) throws SQLException {
        try {
            var loader = HostReadDatabase.class.getClassLoader();
            Class<?> driverApi = Class.forName("androidx.sqlite.SQLiteDriver", true, loader);
            Class<?> connectionApi = Class.forName("androidx.sqlite.SQLiteConnection", true, loader);
            Class<?> statementApi = Class.forName("androidx.sqlite.SQLiteStatement", true, loader);
            // Verified Steam Linux 1.19.2 driver. Do not fall back to sqlite-jdbc.
            Class<?> driverType = Class.forName("androidx.sqlite.driver.bundled.\u0528", true, loader);
            if (!driverApi.isAssignableFrom(driverType)) throw new SQLException("宿主 SQLite 驱动不兼容");
            Object driver = driverType.getConstructor().newInstance();
            Object raw = driverApi.getMethod("open", String.class).invoke(driver, path.toAbsolutePath().toString());
            var connection = new Reader(raw, connectionApi, statementApi);
            try {
                connection.pragma("PRAGMA busy_timeout=3000");
                return connection;
            } catch (Throwable error) { connection.close(); throw error; }
        } catch (Throwable error) { throw sqlError(error); }
    }

    private static SQLException sqlError(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error instanceof SQLException sql ? sql : new SQLException("宿主只读查询失败：" + error, error);
    }

    private static Object call(Class<?> api, Object target, String method, Object... args) throws SQLException {
        try {
            Class<?>[] types = Arrays.stream(args).map(a -> a instanceof Integer ? int.class
                    : a instanceof Long ? long.class : a instanceof Double ? double.class : a.getClass()).toArray(Class<?>[]::new);
            return api.getMethod(method, types).invoke(target, args);
        } catch (Throwable error) { throw sqlError(error); }
    }

    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(HostReadDatabase.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "HostSQLiteReadOnly(" + proxy.getClass().getInterfaces()[0].getSimpleName() + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new IllegalArgumentException(method.getName());
        };
    }

    private static SQLFeatureNotSupportedException unsupported(Method method) {
        return new SQLFeatureNotSupportedException("宿主只读连接不支持 " + method.getName());
    }

    private static final class Reader implements InvocationHandler {
        private final Object raw;
        private final Class<?> connectionApi, statementApi;
        private final Set<Query> statements = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;
        Reader(Object raw, Class<?> connectionApi, Class<?> statementApi) {
            this.raw = raw; this.connectionApi = connectionApi; this.statementApi = statementApi;
        }
        void pragma(String sql) throws SQLException {
            Object statement = call(connectionApi, raw, "prepare", sql);
            try { call(statementApi, statement, "step"); }
            finally { call(statementApi, statement, "close"); }
        }
        void close() throws SQLException {
            if (closed) return;
            try {
                for (Query query : List.copyOf(statements)) query.close();
            } finally { closed = true; call(connectionApi, raw, "close"); }
        }
        @Override public Object invoke(Object self, Method method, Object[] values) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, values);
            String name = method.getName();
            if (name.equals("close")) { close(); return null; }
            if (name.equals("isClosed")) return closed;
            if (closed) throw new SQLException("连接已关闭");
            return switch (name) {
                case "createStatement", "prepareStatement" -> {
                    Query query = new Query(this, values == null || values.length == 0 ? null : (String)values[0]);
                    statements.add(query);
                    yield name.equals("prepareStatement") ? proxy(PreparedStatement.class, query) : proxy(Statement.class, query);
                }
                case "isReadOnly", "getAutoCommit", "isValid" -> true;
                case "setReadOnly", "setAutoCommit" -> {
                    if (!Boolean.TRUE.equals(values[0])) throw unsupported(method);
                    yield null;
                }
                default -> throw unsupported(method);
            };
        }
    }

    private static final class Query implements InvocationHandler {
        private final Reader owner;
        private Object raw;
        private boolean closed;
        Query(Reader owner, String sql) throws SQLException { this.owner = owner; if (sql != null) prepare(sql); }
        void prepare(String sql) throws SQLException {
            if (raw != null) { Object previous = raw; raw = null; call(owner.statementApi, previous, "close"); }
            String normalized = sql.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!(normalized.startsWith("SELECT ") || normalized.startsWith("WITH ") || normalized.startsWith("EXPLAIN ")
                    || normalized.matches("PRAGMA (TABLE_INFO\\(.*\\)|FOREIGN_KEY_CHECK|FOREIGN_KEYS(?:=ON)?|QUERY_ONLY=ON|BUSY_TIMEOUT=\\d+)")))
                throw new SQLException("只读连接拒绝执行写入语句");
            raw = call(owner.connectionApi, owner.raw, "prepare", sql);
        }
        void close() throws SQLException {
            if (closed) return;
            closed = true; owner.statements.remove(this);
            if (raw != null) { Object old = raw; raw = null; call(owner.statementApi, old, "close"); }
        }
        @Override public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, args);
            String name = method.getName();
            if (name.equals("close")) { close(); return null; }
            if (name.equals("isClosed")) return closed;
            if (closed || owner.closed) throw new SQLException("语句已关闭");
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                Object value = name.equals("setNull") ? null : args[1];
                if (value == null) call(owner.statementApi, raw, "bindNull", index);
                else if (value instanceof byte[]) call(owner.statementApi, raw, "bindBlob", index, value);
                else if (value instanceof Float || value instanceof Double) call(owner.statementApi, raw, "bindDouble", index, ((Number)value).doubleValue());
                else if (value instanceof Number) call(owner.statementApi, raw, "bindLong", index, ((Number)value).longValue());
                else call(owner.statementApi, raw, "bindText", index, String.valueOf(value));
                return null;
            }
            if (name.equals("executeQuery") || name.equals("execute")) {
                if (args != null && args.length > 0) prepare((String)args[0]);
                if (raw == null) throw new SQLException("缺少查询语句");
                if (name.equals("execute")) return call(owner.statementApi, raw, "step");
                return proxy(ResultSet.class, new Rows(this));
            }
            if (name.equals("clearParameters")) { call(owner.statementApi, raw, "clearBindings"); return null; }
            throw unsupported(method);
        }
    }

    private static final class Rows implements InvocationHandler {
        private final Query query;
        private boolean wasNull;
        private boolean closed;
        Rows(Query query) { this.query = query; }
        Object read(String name, Object... args) throws SQLException { return call(query.owner.statementApi, query.raw, name, args); }
        @Override public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, args);
            String name = method.getName();
            if (name.equals("close")) {
                if (!closed && !query.closed && !query.owner.closed) read("reset");
                closed = true; return null;
            }
            if (name.equals("isClosed")) return closed || query.closed;
            if (closed || query.closed || query.owner.closed) throw new SQLException("结果集已关闭");
            if (name.equals("next")) return read("step");
            if (name.equals("wasNull")) return wasNull;
            if (name.equals("getMetaData")) return proxy(ResultSetMetaData.class, (p,m,a) -> switch(m.getName()) {
                case "getColumnCount" -> read("getColumnCount");
                case "getColumnName", "getColumnLabel" -> read("getColumnName", (Integer)a[0]-1);
                default -> throw unsupported(m);
            });
            if (args == null || args.length != 1 || !(args[0] instanceof Integer index)) throw unsupported(method);
            int column = index - 1;
            wasNull = (Boolean)read("isNull", column);
            return switch (name) {
                case "getString" -> wasNull ? null : read("getText", column);
                case "getLong" -> wasNull ? 0L : read("getLong", column);
                case "getInt" -> wasNull ? 0 : ((Long)read("getLong", column)).intValue();
                case "getDouble" -> wasNull ? 0d : read("getDouble", column);
                case "getBytes" -> wasNull ? null : read("getBlob", column);
                case "getObject" -> wasNull ? null : switch ((Integer)read("getColumnType", column)) {
                    case 1 -> read("getLong", column); case 2 -> read("getDouble", column);
                    case 4 -> read("getBlob", column); default -> read("getText", column);
                };
                default -> throw unsupported(method);
            };
        }
    }
}
