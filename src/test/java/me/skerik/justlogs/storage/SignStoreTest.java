package me.skerik.justlogs.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.Category;
import me.skerik.justlogs.model.SignEntry;
import me.skerik.justlogs.model.SignRecord;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QueryParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sign edits: routing to jl_sign, storing and reading back. */
class SignStoreTest {

    private static final long NOW = 100_000;
    private static final Actor NOTCH = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Notch");

    @TempDir Path dir;
    Database db;
    Connection conn;
    IdCache ids;
    QueryCompiler compiler;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(DbConfig.sqlite(dir.resolve("s.db"), 2));
        Migrations.migrate(db);
        ids = new IdCache(DbConfig.Dialect.SQLITE, "");
        compiler = new QueryCompiler(ids);
        conn = db.openDedicated();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        db.close();
    }

    private LogQuery q(String text) throws Exception {
        return QueryParser.parse(text, NOW, QueryParser.NO_TAGS, 0);
    }

    @Test
    void signQueriesGoToTheSignTable() throws Exception {
        assertEquals(Category.SIGN, QueryCompiler.categoryOf(q("a:sign")));
        assertEquals(Category.SIGN, QueryCompiler.categoryOf(q("a:sign-edit")));
        assertEquals(Category.BLOCK, QueryCompiler.categoryOf(q("a:sign,break")));
    }

    @Test
    void storesAndFindsSignEdits() throws Exception {
        new SignStore().insert(conn, ids, List.of(
                new SignRecord((int) NOW - 200, NOTCH, Action.SIGN_EDIT, "world", 1, 64, 1, 0, "", "hello\n\nworld"),
                new SignRecord((int) NOW - 100, NOTCH, Action.SIGN_EDIT, "world", 1, 64, 1, 1, "hello\n\nworld", "bye")));
        var page = compiler.lookup(conn, q("a:sign u:Notch r:#global"), Origin.NONE, null, 10);
        assertEquals(2, page.rows().size());
        SignEntry newest = assertInstanceOf(SignEntry.class, page.rows().get(0));
        assertEquals("bye", newest.newText());
        assertEquals(1, newest.side());
        assertEquals("hello\n\nworld", newest.oldText());
        assertEquals(2, compiler.count(conn, q("a:sign r:#global"), Origin.NONE, 100));
    }
}
