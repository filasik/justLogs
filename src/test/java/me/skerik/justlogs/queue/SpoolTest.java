package me.skerik.justlogs.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.model.ContainerRecord;
import me.skerik.justlogs.model.LogRecord;
import me.skerik.justlogs.storage.BlockStore;
import me.skerik.justlogs.storage.Database;
import me.skerik.justlogs.storage.DbConfig;
import me.skerik.justlogs.storage.IdCache;
import me.skerik.justlogs.storage.Migrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolTest {

    private static final Logger LOG = Logger.getLogger("SpoolTest");

    @TempDir Path dir;

    @Test
    void roundTripsBlockAndContainerRecords() {
        Spool spool = new Spool(dir.resolve("spool"), LOG);
        Actor player = Actor.player(UUID.randomUUID(), "Steve");
        BlockRecord b1 = new BlockRecord(1000, player, Action.BREAK, "world", 1, 64, -2,
                "chest", "facing=north", "air", "", new byte[]{1, 2, 3, 4});
        BlockRecord b2 = new BlockRecord(1001, Actor.TNT, Action.EXPLODE, "world_nether", 5, 30, 8,
                "netherrack", "", "air", "");
        ContainerRecord c1 = new ContainerRecord(1002, player, Action.DEPOSIT, "world", 10, 64, 10,
                "chest", "diamond_sword", 1, new byte[]{9, 8, 7});
        ContainerRecord c2 = new ContainerRecord(1003, player, Action.WITHDRAW, "world", 10, 64, 10,
                "barrel", "cobblestone", 64, null);

        assertTrue(spool.write(List.of(b1, b2)));
        assertTrue(spool.write(List.of(c1, c2)));
        assertEquals(4, spool.pendingCount());
        assertTrue(spool.pendingBytes() > 0);

        // Simulate restart: new Spool instance scans existing files
        Spool reopened = new Spool(dir.resolve("spool"), LOG);
        assertEquals(4, reopened.pendingCount());

        Spool.Batch first = reopened.peek().orElseThrow();
        assertEquals(2, first.records().size());
        BlockRecord rb1 = (BlockRecord) first.records().get(0);
        assertEquals(b1.actor(), rb1.actor());
        assertEquals(b1.action(), rb1.action());
        assertEquals("chest", rb1.oldMaterial());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, rb1.oldNbt());
        BlockRecord rb2 = (BlockRecord) first.records().get(1);
        assertEquals(Actor.TNT, rb2.actor());
        assertEquals(null, rb2.oldNbt());
        reopened.ack(first);
        assertEquals(2, reopened.pendingCount());

        Spool.Batch second = reopened.peek().orElseThrow();
        assertEquals(2, second.records().size());
        ContainerRecord rc1 = (ContainerRecord) second.records().get(0);
        assertEquals("diamond_sword", rc1.material());
        assertArrayEquals(new byte[]{9, 8, 7}, rc1.itemData());
        ContainerRecord rc2 = (ContainerRecord) second.records().get(1);
        assertEquals(64, rc2.amount());
        assertEquals(null, rc2.itemData());
        reopened.ack(second);

        assertEquals(0, reopened.pendingCount());
        assertEquals(0, reopened.pendingBytes());
        assertTrue(reopened.peek().isEmpty());
    }

    @Test
    void discardsCorruptAndTempFilesWithoutLosingValidOnes() throws Exception {
        Path spoolDir = dir.resolve("spool");
        Files.createDirectories(spoolDir);
        Files.writeString(spoolDir.resolve("spool-0000000000001-000001.bin"), "not-a-spool");
        Files.writeString(spoolDir.resolve("spool-0000000000002-000002.tmp"), "partial");

        Spool spool = new Spool(spoolDir, LOG);
        BlockRecord b = new BlockRecord(2000, Actor.FIRE, Action.BURN, "world", 0, 64, 0, "oak_log", "", "air", "");
        assertTrue(spool.write(List.of(b)));
        assertEquals(1, spool.pendingCount());

        Spool.Batch batch = spool.peek().orElseThrow();
        assertEquals(1, batch.records().size());
        spool.ack(batch);
        assertFalse(Files.exists(spoolDir.resolve("spool-0000000000002-000002.tmp")));
    }

    @Test
    void respectsMaxBytesLimit() {
        Spool spool = new Spool(dir.resolve("spool"), 64, LOG);
        BlockRecord b = new BlockRecord(2000, Actor.FIRE, Action.BURN, "world", 0, 64, 0, "oak_log", "", "air", "");
        assertTrue(spool.write(List.of(b))); // first write pushes pendingBytes >= 64
        assertFalse(spool.write(List.of(b)));
    }

    @Test
    void batchWriterSpoolsOnOutageAndReplaysOnRecovery() throws Exception {
        Database db = new Database(DbConfig.sqlite(dir.resolve("test.db"), 3));
        try {
            Migrations.migrate(db);
            Path spoolDir = dir.resolve("spool");
            Spool spool = new Spool(spoolDir, LOG);
            LogQueue queue = new LogQueue(1000);
            BatchWriter writer = new BatchWriter(queue, db, new IdCache(DbConfig.Dialect.SQLITE, ""),
                    new BlockStore(), spool, 20, 100, LOG);
            writer.start();

            // Break DB so writes fail and spool to disk
            db.call(c -> {
                c.createStatement().execute("ALTER TABLE jl_block RENAME TO jl_block_away");
                return null;
            });
            for (int i = 0; i < 15; i++) {
                queue.offer(new BlockRecord(3000 + i, Actor.FIRE, Action.BURN, "world", i, 64, 0, "oak_log", "", "air", ""));
            }
            for (int i = 0; i < 100 && writer.spooledCount() < 15; i++) Thread.sleep(25);
            assertEquals(15, writer.spooledCount());
            assertEquals(0, queue.size());

            // Restore DB and stop/restart writer to replay immediately without waiting for 1s backoff
            writer.stop(true, 2000);
            db.call(c -> {
                c.createStatement().execute("ALTER TABLE jl_block_away RENAME TO jl_block");
                return null;
            });

            Spool restartedSpool = new Spool(spoolDir, LOG);
            assertEquals(15, restartedSpool.pendingCount());
            BatchWriter restarted = new BatchWriter(queue, db, new IdCache(DbConfig.Dialect.SQLITE, ""),
                    new BlockStore(), restartedSpool, 20, 100, LOG);
            restarted.start();
            for (int i = 0; i < 100 && restarted.spooledCount() > 0; i++) Thread.sleep(25);
            restarted.flushAll().get(5, TimeUnit.SECONDS);

            assertEquals(0, restarted.spooledCount());
            long count = db.call(c -> {
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM jl_block")) {
                    rs.next();
                    return rs.getLong(1);
                }
            });
            assertEquals(15, count);
            restarted.stop(false, 2000);
        } finally {
            db.close();
        }
    }
}
