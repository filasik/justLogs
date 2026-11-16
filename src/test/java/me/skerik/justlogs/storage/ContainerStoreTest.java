package me.skerik.justlogs.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.model.Category;
import me.skerik.justlogs.model.ContainerEntry;
import me.skerik.justlogs.model.ContainerRecord;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QueryParser;
import me.skerik.justlogs.rollback.ContainerPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Container rows, item data deduplication, tile entity snapshots and the container rollback bookkeeping. */
class ContainerStoreTest {

    private static final long NOW = 100_000;
    private static final Actor NOTCH = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Notch");

    @TempDir Path dir;
    Database db;
    Connection conn;
    IdCache ids;
    QueryCompiler compiler;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(DbConfig.sqlite(dir.resolve("c.db"), 2));
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

    private static ContainerRecord rec(int time, Action action, String material, int amount, byte[] data) {
        return new ContainerRecord(time, NOTCH, action, "world", 10, 64, 10, "chest", material, amount, data);
    }

    private LogQuery q(String text) throws Exception {
        return QueryParser.parse(text, NOW, QueryParser.NO_TAGS, 0);
    }

    @Test
    void containerQueriesGoToTheContainerTable() throws Exception {
        assertEquals(Category.CONTAINER, QueryCompiler.categoryOf(q("a:container")));
        assertEquals(Category.CONTAINER, QueryCompiler.categoryOf(q("a:withdraw")));
        assertEquals(Category.BLOCK, QueryCompiler.categoryOf(q("a:block")));
        assertEquals(Category.BLOCK, QueryCompiler.categoryOf(q("u:Notch")));
    }

    @Test
    void storesAndFindsContainerMoves() throws Exception {
        byte[] enchanted = {1, 2, 3};
        new ContainerStore().insert(conn, ids, List.of(
                rec((int) NOW - 300, Action.DEPOSIT, "diamond", 5, null),
                rec((int) NOW - 200, Action.WITHDRAW, "diamond", 2, null),
                rec((int) NOW - 100, Action.DEPOSIT, "diamond_sword", 1, enchanted)));

        var all = compiler.lookup(conn, q("a:container u:Notch r:#global"), Origin.NONE, null, 10);
        assertEquals(3, all.rows().size());
        assertEquals(2, compiler.lookup(conn, q("a:deposit r:#global"), Origin.NONE, null, 10).rows().size());
        assertEquals(1, compiler.lookup(conn, q("a:-container r:#global"), Origin.NONE, null, 10).rows().size());
        assertEquals(2, compiler.lookup(conn, q("a:container b:diamond r:#global"), Origin.NONE, null, 10).rows().size());

        ContainerEntry newest = (ContainerEntry) all.rows().get(0);
        assertEquals("diamond_sword", newest.material());
        assertEquals("chest", newest.holderMaterial());
        assertTrue(newest.hasData());
        assertEquals(3, compiler.count(conn, q("a:container r:#global"), Origin.NONE, 100));
    }

    @Test
    void identicalItemDataIsStoredOnce() throws Exception {
        byte[] data = {9, 9, 9};
        ContainerStore store = new ContainerStore();
        store.insert(conn, ids, List.of(rec(1, Action.DEPOSIT, "stick", 1, data), rec(2, Action.WITHDRAW, "stick", 1, data)));
        store.insert(conn, ids, List.of(rec(3, Action.DEPOSIT, "stick", 1, data)));
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM jl_item_data")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
        var rows = compiler.forContainerChange(conn, q("a:container r:#global"), Origin.NONE, false, 10);
        assertEquals(3, rows.size());
        assertArrayEquals(data, rows.get(0).itemData());
    }

    @Test
    void rollbackFlagsAndUndoLookupWorkForContainers() throws Exception {
        new ContainerStore().insert(conn, ids, List.of(rec(10, Action.DEPOSIT, "gold_ingot", 4, null),
                rec(20, Action.WITHDRAW, "gold_ingot", 1, null)));
        var rows = compiler.forContainerChange(conn, q("a:container r:#global"), Origin.NONE, false, 10);
        ContainerPlan plan = ContainerPlan.build(rows, false);
        assertEquals(2, plan.blocks());
        assertTrue(plan.containers());

        RollbackStore store = new RollbackStore();
        int user = ids.user(conn, NOTCH, 1);
        long id = store.record(conn, user, 1, RollbackStore.KIND_ROLLBACK, "a:container", 2, 1, 5, null, true,
                plan.rowIds(), 30);
        assertEquals(0, compiler.forContainerChange(conn, q("a:container r:#global"), Origin.NONE, false, 10).size());
        assertEquals(2, compiler.forContainerChange(conn, q("a:container r:#global"), Origin.NONE, true, 10).size());

        RollbackStore.Entry last = store.lastUndoable(conn, user);
        assertNotNull(last);
        assertEquals(id, last.id());
        assertTrue(last.containers());
        assertEquals(2, new ContainerStore().rowsOf(conn, last.id()).size());
    }

    @Test
    void containerPlanReversesEachMove() throws Exception {
        new ContainerStore().insert(conn, ids, List.of(rec(10, Action.DEPOSIT, "gold_ingot", 4, null),
                rec(20, Action.WITHDRAW, "gold_ingot", 1, null)));
        var rows = compiler.forContainerChange(conn, q("a:container r:#global"), Origin.NONE, false, 10);
        // rollback: the withdraw is put back (add), the deposit is taken out (remove), newest first
        // (checked through the public numbers only: two moves in one chunk)
        assertEquals(1, ContainerPlan.build(rows, false).chunkCount());
        assertEquals(2, ContainerPlan.build(rows, true).blocks());
    }

    @Test
    void brokenChestKeepsItsContentsForTheRollback() throws Exception {
        byte[] contents = {5, 4, 3, 2, 1};
        new BlockStore().insert(conn, ids, List.of(
                new BlockRecord((int) NOW - 50, NOTCH, Action.BREAK, "world", 3, 70, 3, "chest", "facing=north,type=single,waterlogged=false",
                        "air", "", contents),
                new BlockRecord((int) NOW - 40, NOTCH, Action.BREAK, "world", 4, 70, 3, "stone", "", "air", "")));
        List<BlockEntry> rows = compiler.forChange(conn, q("u:Notch r:#global"), Origin.NONE, false, 10);
        assertEquals(2, rows.size());
        BlockEntry chest = rows.stream().filter(r -> r.oldMaterial().equals("chest")).findFirst().orElseThrow();
        assertTrue(chest.hasNbt());
        Map<Long, byte[]> nbt = new BlockStore().nbtFor(conn, rows);
        assertEquals(1, nbt.size());
        assertArrayEquals(contents, nbt.get(chest.id()));
    }
}
