package me.skerik.justlogs.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QueryParser;
import me.skerik.justlogs.query.QuerySyntaxException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryCompilerTest {

    private static final long NOW = 100_000;
    private static final Actor NOTCH = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Notch");
    private static final Actor JEB = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000002"), "jeb_");

    @TempDir Path dir;
    Database db;
    QueryCompiler compiler;
    Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(DbConfig.sqlite(dir.resolve("q.db"), 2));
        Migrations.migrate(db);
        IdCache ids = new IdCache(DbConfig.Dialect.SQLITE, "");
        compiler = new QueryCompiler(ids);
        conn = db.openDedicated();
        List<BlockRecord> rows = new ArrayList<>();
        // Notch breaks 5 stone in a row at y=64, then places dirt; jeb_ breaks diamond ore far away; TNT blows up sand
        for (int i = 0; i < 5; i++) rows.add(new BlockRecord((int) NOW - 1000 + i, NOTCH, Action.BREAK, "world", i, 64, 0, "stone", "", "air", ""));
        rows.add(new BlockRecord((int) NOW - 500, NOTCH, Action.PLACE, "world", 0, 64, 0, "air", "", "dirt", ""));
        rows.add(new BlockRecord((int) NOW - 400, JEB, Action.BREAK, "world", 500, 12, 500, "diamond_ore", "", "air", ""));
        rows.add(new BlockRecord((int) NOW - 300, Actor.TNT, Action.EXPLODE, "world_nether", 1, 70, 1, "sand", "", "air", ""));
        rows.add(new BlockRecord((int) NOW - 200, NOTCH, Action.PLACE, "world", 1, 64, 0, "air", "", "oak_stairs", "facing=north,half=top"));
        new BlockStore().insert(conn, ids, rows);
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        db.close();
    }

    private QueryCompiler.Page lookup(String query, Origin origin, int limit) throws Exception {
        LogQuery q = QueryParser.parse(query, NOW, QueryParser.NO_TAGS, 0);
        return compiler.lookup(conn, q, origin, null, limit);
    }

    private static final Origin AT_SPAWN = new Origin("world", 0, 64, 0);

    @Test
    void filtersByUserCaseInsensitivelyAndNegated() throws Exception {
        assertEquals(7, lookup("u:notch", Origin.NONE, 50).rows().size());
        assertEquals(1, lookup("u:jeb_", Origin.NONE, 50).rows().size());
        assertEquals(2, lookup("u:!notch", Origin.NONE, 50).rows().size());
        assertEquals(0, lookup("u:nobody", Origin.NONE, 50).rows().size());
        assertEquals(1, lookup("u:#tnt", Origin.NONE, 50).rows().size());
    }

    @Test
    void filtersByActionGroupAndMaterial() throws Exception {
        assertEquals(2, lookup("a:+block", Origin.NONE, 50).rows().size());
        assertEquals(7, lookup("a:-block", Origin.NONE, 50).rows().size());
        assertEquals(1, lookup("a:explode", Origin.NONE, 50).rows().size());
        assertEquals(0, lookup("a:chat", Origin.NONE, 50).rows().size());
        assertEquals(5, lookup("b:stone", Origin.NONE, 50).rows().size());
        assertEquals(1, lookup("b:oak_stairs", Origin.NONE, 50).rows().size(), "matches the new material of a place");
        assertEquals(4, lookup("b:!stone", Origin.NONE, 50).rows().size());
        assertEquals(0, lookup("b:bedrock", Origin.NONE, 50).rows().size());
    }

    @Test
    void radiusAndWorldScopeTheSearch() throws Exception {
        assertEquals(7, lookup("r:10", AT_SPAWN, 50).rows().size());
        assertEquals(2, lookup("r:0", AT_SPAWN, 50).rows().size(), "only (0,64,0): the break and the place");
    }

    @Test
    void anExactSpotShowsTheWholeHistoryNewestFirst() throws Exception {
        LogQuery q = new LogQuery(List.of(), false, 0, 0, null, me.skerik.justlogs.query.Radius.cube(0), java.util.Set.of(),
                List.of(), List.of(), false, "world", new int[] {0, 64, 0}, java.util.Set.of());
        List<BlockEntry> rows = compiler.lookup(conn, q, Origin.NONE, null, 50).rows().stream().map(r -> (BlockEntry) r).toList();
        assertEquals(2, rows.size());
        assertEquals(Action.PLACE, rows.get(0).action());
        assertEquals("dirt", rows.get(0).newMaterial());
        assertEquals("stone", rows.get(1).oldMaterial());
        assertEquals("Notch", rows.get(0).user());
    }

    @Test
    void worldAndTimeFilters() throws Exception {
        assertEquals(1, lookup("w:world_nether", Origin.NONE, 50).rows().size());
        assertEquals(0, lookup("w:nowhere", Origin.NONE, 50).rows().size());
        assertEquals(3, lookup("t:450s", Origin.NONE, 50).rows().size());
        assertEquals(1, lookup("t:600s-450s", Origin.NONE, 50).rows().size());
    }

    @Test
    void keysetPagingWalksEverythingExactlyOnce() throws Exception {
        LogQuery q = QueryParser.parse("r:#global", NOW, QueryParser.NO_TAGS, 0);
        List<Long> seen = new ArrayList<>();
        QueryCompiler.Cursor cursor = null;
        int pages = 0;
        do {
            QueryCompiler.Page page = compiler.lookup(conn, q, Origin.NONE, cursor, 3);
            page.rows().forEach(r -> seen.add(r.id()));
            cursor = page.next();
            pages++;
        } while (cursor != null);
        assertEquals(3, pages);
        assertEquals(9, seen.size());
        assertEquals(9, seen.stream().distinct().count());
        List<Long> sorted = new ArrayList<>(seen);
        sorted.sort((a, b) -> Long.compare(b, a));
        assertEquals(sorted, seen, "newest first");
    }

    @Test
    void countStopsAtTheCap() throws Exception {
        LogQuery q = QueryParser.parse("r:#global", NOW, QueryParser.NO_TAGS, 0);
        assertEquals(9, compiler.count(conn, q, Origin.NONE, 1000));
        assertEquals(4, compiler.count(conn, q, Origin.NONE, 4));
    }

    @Test
    void rowsCarryTheBlockStateAndTheUserKind() throws Exception {
        BlockEntry stairs = (BlockEntry) lookup("b:oak_stairs", Origin.NONE, 5).rows().get(0);
        assertEquals("facing=north,half=top", stairs.newData());
        assertEquals("", stairs.oldData());
        assertTrue(stairs.userIsPlayer());
        assertTrue(!lookup("u:#tnt", Origin.NONE, 5).rows().get(0).userIsPlayer());
    }

    @Test
    void aRadiusWithoutAPositionIsRejectedNotGuessed() throws Exception {
        LogQuery q = QueryParser.parse("r:5", NOW, QueryParser.NO_TAGS, 0);
        QuerySyntaxException e = assertThrows(QuerySyntaxException.class, () -> compiler.lookup(conn, q, Origin.NONE, null, 5));
        assertEquals("need-position", e.code());
        assertNotNull(QueryCompiler.Cursor.decode("12.34"));
        assertNull(QueryCompiler.Cursor.decode("garbage"));
        assertNull(QueryCompiler.Cursor.decode(null));
    }
}
