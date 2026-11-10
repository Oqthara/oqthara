package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ADR-0040: SQLite support with no connection handle.
 *
 * <p>Every test uses a real database file in a temporary directory rather than a mock. The
 * decisions that matter here — that a connection is closed on the error path, that a
 * transaction rolls back, that parameters are bound rather than spliced — are properties of the
 * interaction between the driver and SQLite. A mock would assert that the code calls the methods
 * the code was written to call, which is circular.
 */
class DatabaseTest {

    private static Interpreter host() {
        Interpreter interpreter = new Interpreter(
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                new BufferedReader(new StringReader("")),
                List.of(), Path.of("."), new MethodResolverImpl());
        Stdlib.install(interpreter);
        return interpreter;
    }

    private static Object call(Interpreter interpreter, String name, Object... args) {
        // Arrays.asList, not List.of: `nothing` is Java null and List.of rejects it.
        return interpreter.builtins().get(name)
                .call(interpreter, new ArrayList<>(java.util.Arrays.asList(args)),
                        Span.synthetic());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> row(Object value) {
        return (Map<Object, Object>) value;
    }

    private static String path(Path dir, String name) {
        return dir.resolve(name).toString();
    }

    @Test
    void createsTableAndInsertsARow(@TempDir Path dir) {
        Interpreter host = host();
        String db = path(dir, "app.db");

        call(host, "db_execute", db, "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)", List.of());
        Object result = call(host, "db_execute", db,
                "INSERT INTO users (name) VALUES (?)", List.of("ana"));

        Map<Object, Object> counts = row(result);
        assertEquals(1L, counts.get("affected"));
        assertEquals(1L, counts.get("last_id"));

        List<Object> read = rows(call(host, "db_query", db,
                "SELECT id, name FROM users", List.of()));
        assertEquals(1, read.size());
        assertEquals("ana", row(read.get(0)).get("name"));
        assertEquals(1L, row(read.get(0)).get("id"));
    }

    @Test
    void aConnectionIsClosedSoTheFileIsNotLocked(@TempDir Path dir) {
        // ADR-0040 D2: the connection never escapes, so a second call can open the same file
        // without a lock error. If a connection leaked, a write on Windows would fail here.
        Interpreter host = host();
        String db = path(dir, "lock.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());
        for (int i = 0; i < 5; i++) {
            call(host, "db_execute", db, "INSERT INTO t (v) VALUES (?)", List.of("row" + i));
        }
        assertEquals(5, rows(call(host, "db_query", db, "SELECT v FROM t", List.of())).size());
    }

    @Test
    void aConnectionIsClosedWhenTheStatementFails(@TempDir Path dir) {
        // The error path is the one that would leak without try-with-resources. The proof that
        // it did not leak is that the same file is still writable afterwards.
        Interpreter host = host();
        String db = path(dir, "err.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());

        OqError error = assertThrows(OqError.class, () -> call(host, "db_query", db,
                "SELECT * FROM no_such_table", List.of()));
        assertTrue(error.getMessage().contains("database error"));

        call(host, "db_execute", db, "INSERT INTO t (v) VALUES (?)", List.of("after"));
        assertEquals(1, rows(call(host, "db_query", db, "SELECT v FROM t", List.of())).size());
    }

    @Test
    void nullRoundTripsAsNothing(@TempDir Path dir) {
        // ADR-0040 D5. `nothing` is Java null in the runtime, so this asserts the mapping in
        // both directions: a NULL read back, and a null parameter written.
        Interpreter host = host();
        String db = path(dir, "null.db");
        call(host, "db_execute", db, "CREATE TABLE t (id INTEGER, v TEXT)", List.of());
        // List.of rejects null, and `nothing` is null here, so the parameters are built
        // mutably to represent the absent value faithfully.
        List<Object> parameters = new ArrayList<>();
        parameters.add(1L);
        parameters.add(null);
        call(host, "db_execute", db, "INSERT INTO t (id, v) VALUES (?, ?)", parameters);

        List<Object> read = rows(call(host, "db_query", db, "SELECT v FROM t", List.of()));
        assertNull(row(read.get(0)).get("v"));
        assertEquals(true, call(host, "is_nothing", row(read.get(0)).get("v")));

        List<Object> direct = rows(call(host, "db_query", db, "SELECT NULL AS v", List.of()));
        assertNull(row(direct.get(0)).get("v"));
    }

    @Test
    void integersAndRealsKeepTheirNumericType(@TempDir Path dir) {
        Interpreter host = host();
        String db = path(dir, "types.db");
        List<Object> read = rows(call(host, "db_query", db,
                "SELECT 7 AS i, 1.5 AS r, 'x' AS s", List.of()));
        Map<Object, Object> value = row(read.get(0));
        assertEquals(7L, value.get("i"));
        assertEquals(1.5, value.get("r"));
        assertEquals("x", value.get("s"));
        assertTrue(value.get("i") instanceof Long);
        assertTrue(value.get("r") instanceof Double);
    }

    @Test
    void parametersAreBoundNotSpliced(@TempDir Path dir) {
        // ADR-0040 D3. A parameterised insert stores the payload as text and the table survives.
        Interpreter host = host();
        String db = path(dir, "inject.db");
        call(host, "db_execute", db, "CREATE TABLE users (name TEXT)", List.of());
        String payload = "'; DROP TABLE users--";
        call(host, "db_execute", db, "INSERT INTO users (name) VALUES (?)", List.of(payload));

        List<Object> read = rows(call(host, "db_query", db, "SELECT name FROM users", List.of()));
        assertEquals(payload, row(read.get(0)).get("name"));

        List<Object> counted = rows(call(host, "db_query", db,
                "SELECT count(*) AS n FROM users", List.of()));
        assertEquals(1L, row(counted.get(0)).get("n"));
    }

    @Test
    void aWrongParameterCountIsAnErrorNotASilentNull(@TempDir Path dir) {
        // Measured defect: SQLite binds an unbound placeholder as NULL, so without this check
        // a forgotten parameter wrote `nothing` and exited 0.
        Interpreter host = host();
        String db = path(dir, "arity.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());

        OqError tooFew = assertThrows(OqError.class, () -> call(host, "db_execute", db,
                "INSERT INTO t (v) VALUES (?)", List.of()));
        assertTrue(tooFew.getMessage().contains("1 placeholder but got 0"));

        OqError tooMany = assertThrows(OqError.class, () -> call(host, "db_execute", db,
                "INSERT INTO t (v) VALUES (?)", List.of("a", "b")));
        assertTrue(tooMany.getMessage().contains("1 placeholder but got 2"));

        assertEquals(0, rows(call(host, "db_query", db, "SELECT v FROM t", List.of())).size());
    }

    @Test
    void aTransactionCommitsWhenTheBodyReturns(@TempDir Path dir) {
        // ADR-0040 D4.
        Interpreter host = host();
        String db = path(dir, "commit.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());

        OqCallable body = new dev.oqthara.runtime.NativeFunction("body", 0, 0, (h, a, s) -> {
            call(host, "db_execute", db, "INSERT INTO t (v) VALUES (?)", List.of("a"));
            call(host, "db_execute", db, "INSERT INTO t (v) VALUES (?)", List.of("b"));
            return "done";
        });

        assertEquals("done", call(host, "db_transaction", db, body));
        assertEquals(2, rows(call(host, "db_query", db, "SELECT v FROM t", List.of())).size());
    }

    @Test
    void aTransactionRollsBackWhenTheBodyRaises(@TempDir Path dir) {
        // The central claim of D4: both inserts are discarded and the original error survives
        // rather than being replaced by a rollback failure.
        Interpreter host = host();
        String db = path(dir, "rollback.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());

        OqCallable body = new dev.oqthara.runtime.NativeFunction("body", 0, 0, (h, a, s) -> {
            call(host, "db_execute", db, "INSERT INTO t (v) VALUES (?)", List.of("a"));
            call(host, "db_execute", db, "INSERT INTO t (v) VALUES (?)", List.of("b"));
            throw new OqError("body failed", Span.synthetic(), "deliberate");
        });

        OqError error = assertThrows(OqError.class,
                () -> call(host, "db_transaction", db, body));
        assertEquals("body failed", error.getMessage());
        assertEquals(0, rows(call(host, "db_query", db, "SELECT v FROM t", List.of())).size());
    }

    @Test
    void aNestedTransactionIsRefused(@TempDir Path dir) {
        // Silently flattening a nested transaction would turn an inner rollback into an outer
        // commit, which is the kind of bug that only appears once error handling is added.
        Interpreter host = host();
        String db = path(dir, "nested.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());

        OqCallable inner = new dev.oqthara.runtime.NativeFunction("inner", 0, 0,
                (h, a, s) -> "inner");
        OqCallable outer = new dev.oqthara.runtime.NativeFunction("outer", 0, 0,
                (h, a, s) -> call(host, "db_transaction", db, inner));

        OqError error = assertThrows(OqError.class,
                () -> call(host, "db_transaction", db, outer));
        assertTrue(error.getMessage().contains("cannot be nested"));
    }

    @Test
    void queryingAStatementThatReturnsNoRowsIsAnError(@TempDir Path dir) {
        // A silent [] for an INSERT would look exactly like a query against an empty table.
        Interpreter host = host();
        String db = path(dir, "norows.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());

        OqError error = assertThrows(OqError.class, () -> call(host, "db_query", db,
                "INSERT INTO t (v) VALUES ('x')", List.of()));
        assertTrue(error.getMessage().contains("needs a statement that returns rows"));
        assertEquals(0, rows(call(host, "db_query", db, "SELECT v FROM t", List.of())).size());
    }

    @Test
    void aMissingDirectoryIsNamedNotJustASqlError(@TempDir Path dir) {
        Interpreter host = host();
        String db = path(dir, "no-such-dir/deeper/app.db");
        OqError error = assertThrows(OqError.class, () -> call(host, "db_execute", db,
                "CREATE TABLE t (v TEXT)", List.of()));
        assertTrue(error.getMessage().contains("no such directory"));
    }

    @Test
    void anInMemoryDatabaseDoesNotPersistAcrossCalls() {
        // ADR-0040 D7 documents this as a limitation rather than hiding it: the connection is
        // closed at the end of every call, so :memory: starts empty each time.
        Interpreter host = host();
        call(host, "db_execute", ":memory:", "CREATE TABLE t (v TEXT)", List.of());
        OqError error = assertThrows(OqError.class, () -> call(host, "db_execute", ":memory:",
                "INSERT INTO t (v) VALUES (?)", List.of("x")));
        assertTrue(error.getMessage().contains("no such table"));
    }

    @Test
    void aBlankStatementIsRejectedBeforeReachingTheDriver() {
        Interpreter host = host();
        OqError error = assertThrows(OqError.class,
                () -> call(host, "db_query", ":memory:", "   ", List.of()));
        assertTrue(error.getMessage().contains("needs SQL text"));
    }

    @Test
    void aNonListParameterArgumentIsNamed() {
        Interpreter host = host();
        OqError error = assertThrows(OqError.class,
                () -> call(host, "db_query", ":memory:", "SELECT 1", 5L));
        assertTrue(error.getMessage().contains("needs a list of parameters"));
    }

    @Test
    void updateAndDeleteReportAffectedCounts(@TempDir Path dir) {
        Interpreter host = host();
        String db = path(dir, "counts.db");
        call(host, "db_execute", db, "CREATE TABLE t (v TEXT)", List.of());
        for (int i = 0; i < 3; i++) {
            call(host, "db_execute", db, "INSERT INTO t (v) VALUES (?)", List.of("v" + i));
        }
        Map<Object, Object> updated = row(call(host, "db_execute", db,
                "UPDATE t SET v = ?", List.of("z")));
        assertEquals(3L, updated.get("affected"));

        Map<Object, Object> deleted = row(call(host, "db_execute", db,
                "DELETE FROM t WHERE v = ?", List.of("z")));
        assertEquals(3L, deleted.get("affected"));
    }
}
