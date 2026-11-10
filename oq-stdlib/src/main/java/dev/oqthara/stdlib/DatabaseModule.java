package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * SQLite support, shaped by ADR-0040.
 *
 * <p>The language has no {@code close} and no destructors, so a connection must never become a
 * value the program can hold. Every entry point here opens, works, and closes within one call,
 * or hands the connection to a body that cannot retain it. See ADR-0040 D2 and D4.
 */
final class DatabaseModule {
    private DatabaseModule() {
    }

    private static final String DRIVER = "org.sqlite.JDBC";

    /**
     * One open transaction, visible only to the call stack of the body that started it.
     * Threaded rather than stored in a field so a task that opens a transaction cannot join
     * another task's.
     */
    private static final ThreadLocal<Connection> ACTIVE = new ThreadLocal<>();

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("db_query", 3, (host, args, span) -> {
            String path = requirePath(args.get(0), "db_query", span);
            String sql = requireSql(args.get(1), "db_query", span);
            List<Object> parameters = parameters(args.get(2), "db_query", span);
            return withConnection(path, "db_query", host, span, connection -> {
                // executeQuery, not execute: the driver rejects a non-reading statement
                // *before* running it. Measured — an INSERT passed to executeQuery raised
                // "Query does not return results" and wrote no row, whereas execute() committed
                // the row and then left nothing to read back.
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bind(statement, parameters, "db_query", span);
                    try (ResultSet rows = statement.executeQuery()) {
                        return readRows(rows);
                    } catch (SQLException e) {
                        if (isNotAQuery(e)) {
                            throw new OqError("db_query() needs a statement that returns rows",
                                    span, "db_query() is for SELECT. Use db_execute() for "
                                            + "INSERT, UPDATE, DELETE and DDL.");
                        }
                        throw e;
                    }
                }
            });
        }));

        interpreter.defineBuiltin(NativeFunction.of("db_execute", 3, (host, args, span) -> {
            String path = requirePath(args.get(0), "db_execute", span);
            String sql = requireSql(args.get(1), "db_execute", span);
            List<Object> parameters = parameters(args.get(2), "db_execute", span);
            return withConnection(path, "db_execute", host, span, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bind(statement, parameters, "db_execute", span);
                    int affected = statement.executeUpdate();
                    // Last insert id is read on the same connection, so it can only describe
                    // the statement that just ran.
                    long inserted = lastInsertId(connection);
                    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                    result.put("affected", (long) affected);
                    result.put("last_id", inserted);
                    return new OqMap(result);
                }
            });
        }));

        interpreter.defineBuiltin(NativeFunction.of("db_transaction", 2, (host, args, span) -> {
            String path = requirePath(args.get(0), "db_transaction", span);
            NativeFunction fn = callable(args.get(1), "db_transaction", span);
            Connection existing = ACTIVE.get();
            if (existing != null) {
                // Nesting would need savepoints to be correct, and a silently flattened nested
                // transaction is a correctness bug that only appears under error handling.
                throw new OqError("db_transaction() cannot be nested", span,
                        "The enclosing transaction is still open. Run these statements in the "
                                + "outer body instead of opening a second transaction.");
            }
            return withConnection(path, "db_transaction", host, span, connection -> {
                try {
                    connection.setAutoCommit(false);
                } catch (SQLException e) {
                    throw databaseError(e, "db_transaction", span);
                }
                ACTIVE.set(connection);
                Object value;
                try {
                    value = fn.call(host, new ArrayList<>(), span);
                } catch (RuntimeException | Error failure) {
                    ACTIVE.remove();
                    rollbackQuietly(connection);
                    throw failure;
                }
                ACTIVE.remove();
                try {
                    connection.commit();
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw databaseError(e, "db_transaction", span);
                }
                return value;
            });
        }));
    }

    private interface SqlWork {
        Object run(Connection connection) throws SQLException;
    }

    /**
     * Opens the database, runs {@code work}, and closes the connection on every path. A body
     * that is already inside a transaction reuses that connection rather than opening a second
     * one, which is what makes {@code db_execute} inside {@code db_transaction} participate.
     */
    private static Object withConnection(String path, String call, OqHost host, Span span,
            SqlWork work) {
        Connection active = ACTIVE.get();
        if (active != null) {
            try {
                return work.run(active);
            } catch (SQLException e) {
                throw databaseError(e, call, span);
            }
        }
        loadDriver(span);
        String url = path.equals(":memory:") ? "jdbc:sqlite::memory:" : "jdbc:sqlite:" + path;
        if (!path.equals(":memory:")) {
            Path file = Path.of(path);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                throw new OqError("no such directory for database: '" + parent + "'", span,
                        "Create the directory first, or pass a path inside one that exists.");
            }
        }
        try (Connection connection = DriverManager.getConnection(url)) {
            return work.run(connection);
        } catch (SQLException e) {
            throw databaseError(e, call, span);
        }
    }

    private static void loadDriver(Span span) {
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            throw new OqError("database support is unavailable", span,
                    "The SQLite driver is missing from this build. Reinstall the oq "
                            + "distribution; the standalone jar bundles it.");
        }
    }

    private static long lastInsertId(Connection connection) {
        try (Statement statement = connection.createStatement();
                ResultSet keys = statement.executeQuery("SELECT last_insert_rowid()")) {
            return keys.next() ? keys.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    private static List<Object> readRows(ResultSet rows) throws SQLException {
        List<Object> result = new ArrayList<>();
        ResultSetMetaData meta = rows.getMetaData();
        int columns = meta.getColumnCount();
        while (rows.next()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                row.put(meta.getColumnLabel(i), convert(rows, i));
            }
            result.add(new OqMap(row));
        }
        return result;
    }

    /**
     * SQL NULL becomes {@code nothing}, which is Java {@code null} here, so the mapping is the
     * identity for that case. Integers become {@code Long} and reals {@code Double}, matching
     * the numeric model; anything else is read as text rather than guessed at.
     */
    private static Object convert(ResultSet rows, int index) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null) return Values.NONE;
        if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Double || value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof String text) {
            return text;
        }
        return rows.getString(index);
    }

    private static void bind(PreparedStatement statement, List<Object> parameters, String call,
            Span span) throws SQLException {
        // SQLite binds an unbound placeholder as NULL rather than failing, so a forgotten
        // parameter would silently write NULL into a column instead of reporting the mistake.
        // Measured: INSERT with one placeholder and [] stored `nothing` and exited 0.
        int expected = statement.getParameterMetaData().getParameterCount();
        if (expected != parameters.size()) {
            throw new OqError(call + "() has " + expected + " placeholder"
                    + (expected == 1 ? "" : "s") + " but got " + parameters.size()
                    + " parameter" + (parameters.size() == 1 ? "" : "s"), span,
                    "Pass one value per '?', in order. Unbound placeholders become NULL "
                            + "without an error, so this is checked before running.");
        }
        for (int i = 0; i < parameters.size(); i++) {
            Object value = parameters.get(i);
            if (value == null) {
                statement.setObject(i + 1, null);
            } else if (value instanceof Boolean flag) {
                statement.setInt(i + 1, flag ? 1 : 0);
            } else if (value instanceof Long || value instanceof Double
                    || value instanceof String) {
                // Bound, never spliced: see ADR-0040 D3. This is the whole reason parameters
                // exist as a separate argument.
                statement.setObject(i + 1, value);
            } else if (value instanceof byte[] bytes) {
                statement.setBytes(i + 1, bytes);
            } else if (value instanceof List<?>) {
                throw new OqError(call + "() cannot bind a list as one parameter", span,
                        "Expand it with a loop, or pass scalars. A list bound whole would be "
                                + "stored as a Java object, which SQLite cannot read back.");
            } else {
                statement.setString(i + 1, Values.display(value));
            }
        }
    }

    /** True when the driver refused the statement because it produces no result set. */
    private static boolean isNotAQuery(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains("does not return results");
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting; a rollback that also fails would
            // only hide it.
        }
    }

    private static OqError databaseError(SQLException e, String call, Span span) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String hint = "Check the SQL and the parameter count. " + call
                + "() takes one placeholder per parameter, in order.";
        return new OqError("database error: " + message, span, hint);
    }

    private static String requirePath(Object value, String call, Span span) {
        String path = Values.display(value);
        if (path.isEmpty()) {
            throw new OqError(call + "() needs a database path", span,
                    "Pass a file name like \"app.db\", or \":memory:\" for a scratch database.");
        }
        return path;
    }

    private static String requireSql(Object value, String call, Span span) {
        if (!(value instanceof String sql)) {
            throw new OqError(call + "() needs SQL text, got " + Values.typeName(value), span,
                    "Pass the statement as a string.");
        }
        if (sql.isBlank()) {
            throw new OqError(call + "() needs SQL text", span, "The statement was blank.");
        }
        return sql;
    }

    private static List<Object> parameters(Object value, String call, Span span) {
        if (value == null) return new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            throw new OqError(call + "() needs a list of parameters, got "
                    + Values.typeName(value), span,
                    "Pass [] when the statement has no placeholders.");
        }
        return new ArrayList<>(list);
    }

    private static NativeFunction callable(Object value, String call, Span span) {
        if (value instanceof NativeFunction function) return function;
        if (value instanceof dev.oqthara.runtime.OqCallable callable) {
            return NativeFunction.of("db_transaction body", 0, (host, args, inner) ->
                    callable.call(host, args, inner));
        }
        throw new OqError(call + "() needs a function, got " + Values.typeName(value), span,
                "Pass a function that runs the statements, like "
                        + "db_transaction(\"app.db\", () -> { ... }).");
    }
}
