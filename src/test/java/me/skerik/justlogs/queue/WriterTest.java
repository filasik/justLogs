package me.skerik.justlogs.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.storage.BlockStore;
import me.skerik.justlogs.storage.Database;
import me.skerik.justlogs.storage.DbConfig;
import me.skerik.justlogs.storage.IdCache;
import me.skerik.justlogs.storage.Migrations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriterTest {

    @TempDir Path dir;
    Database db;
    LogQueue queue;
    BatchWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(DbConfig.sqlite(dir.resolve("t.db"), 3));
        Migrations.migrate(db);
        queue = new LogQueue(1000);
        writer = new BatchWriter(queue, db, new IdCache(DbConfig.Dialect.SQLITE, ""), new BlockStore(), 20, 100,
                Logger.getLogger("test"));
        writer.start();
    }

    @AfterEach
    void tearDown() {
        writer.stop(false, 2000);
        db.close();
    }

    private static BlockRecord record(String name, int i) {
        return new BlockRecord(1_000 + i, Actor.player(UUID.nameUUIDFromBytes(name.getBytes()), name), Action.BREAK,
                "world", i, 64, -i, "stone", "", "air", "");
    }

    private long rows(String table) throws Exception {
        return db.call(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void writesEverythingInOrderAndFlushWaitsForIt() throws Exception {
        for (int i = 0; i < 350; i++) queue.offer(record(i % 2 == 0 ? "Notch" : "jeb_", i));
        writer.flushAll().get(10, TimeUnit.SECONDS);
        assertEquals(350, rows("jl_block"));
        assertEquals(2, rows("jl_users"));
        assertEquals(1, rows("jl_worlds"));
        assertEquals(2, rows("jl_materials")); // stone and air, deduplicated
        assertEquals(350, writer.written());
        assertTrue(writer.throughput().perSecond(System.currentTimeMillis()) > 0);
    }

    @Test
    void flushOnAnEmptyQueueReturnsAtOnce() throws Exception {
        writer.flushAll().get(2, TimeUnit.SECONDS);
        assertEquals(0, rows("jl_block"));
    }

    @Test
    void aPlayerRenameIsRecordedAndKeepsTheSameUserRow() throws Exception {
        UUID id = UUID.randomUUID();
        queue.offer(new BlockRecord(5_000, Actor.player(id, "OldName"), Action.PLACE, "world", 0, 0, 0, "air", "", "stone", ""));
        queue.offer(new BlockRecord(6_000, Actor.player(id, "NewName"), Action.PLACE, "world", 1, 0, 0, "air", "", "stone", ""));
        writer.flushAll().get(10, TimeUnit.SECONDS);
        assertEquals(1, rows("jl_users"));
        assertEquals(2, rows("jl_user_names"));
        String name = db.call(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT name FROM jl_users")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertEquals("NewName", name);
    }

    @Test
    void survivesAnUnavailableDatabaseAndWritesLaterWithoutLosingRecords() throws Exception {
        db.call(c -> {
            c.createStatement().execute("ALTER TABLE jl_block RENAME TO jl_block_away");
            return null;
        });
        for (int i = 0; i < 20; i++) queue.offer(record("Notch", i));
        for (int i = 0; i < 100 && writer.lastError() == null; i++) Thread.sleep(50);
        assertNotNull(writer.lastError(), "the failure should be visible in /jo status");
        db.call(c -> {
            c.createStatement().execute("ALTER TABLE jl_block_away RENAME TO jl_block");
            return null;
        });
        writer.flushAll().get(15, TimeUnit.SECONDS);
        assertEquals(20, rows("jl_block"));
        assertEquals(null, writer.lastError());
    }

    @Test
    void shutdownWritesWhatIsStillQueued() throws Exception {
        for (int i = 0; i < 500; i++) queue.offer(record("Notch", i));
        assertEquals(0, writer.stop(true, 10_000));
        assertEquals(500, rows("jl_block"));
    }

    @Test
    void aFullQueueDropsLowPriorityRecordsFirstAndEverythingFarPastTheLimit() {
        LogQueue small = new LogQueue(10);
        BlockRecord normal = record("Notch", 1);
        BlockRecord flow = new BlockRecord(1, Actor.WATER, Action.FLOW, "world", 0, 0, 0, "air", "", "water", "level=1");
        for (int i = 0; i < 10; i++) assertTrue(small.offer(normal));
        assertFalse(small.offer(flow), "low priority is dropped at the limit");
        assertTrue(small.offer(normal), "normal records still fit up to twice the limit");
        for (int i = 0; i < 9; i++) small.offer(normal);
        assertFalse(small.offer(normal), "everything is dropped at twice the limit");
        assertEquals(2, small.dropped());
        assertEquals(20, small.size());
    }
}
