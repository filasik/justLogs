package me.skerik.justlogs.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.model.Category;
import me.skerik.justlogs.model.ChatRecord;
import me.skerik.justlogs.model.ItemRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Purge deletes old rows across categories, respects world filters, cleans orphaned attachments and optimizes SQLite. */
class PurgerTest {

    private static final Actor NOTCH = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Notch");

    @TempDir Path dir;
    Database db;
    Connection conn;
    IdCache ids;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(DbConfig.sqlite(dir.resolve("purge.db"), 2));
        Migrations.migrate(db);
        ids = new IdCache(DbConfig.Dialect.SQLITE, "");
        conn = db.openDedicated();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        db.close();
    }

    @Test
    void purgesOldRowsAndOrphanedAttachments() throws Exception {
        new BlockStore().insert(conn, ids, List.of(
                new BlockRecord(100, NOTCH, Action.BREAK, "world", 1, 64, 1, "chest", "", "air", "", new byte[]{1, 2}),
                new BlockRecord(500, NOTCH, Action.PLACE, "world", 2, 64, 2, "air", "", "stone", "")));
        new ItemStore().insert(conn, ids, List.of(
                new ItemRecord(100, NOTCH, Action.DROP, "world", 1, 64, 1, "diamond_sword", 1, new byte[]{3, 4}),
                new ItemRecord(500, NOTCH, Action.DROP, "world_nether", 1, 64, 1, "diamond_sword", 1, new byte[]{5, 6})));
        new TextStore().insertChat(conn, ids, List.of(
                new ChatRecord(100, NOTCH, "world", 1, 64, 1, "old message"),
                new ChatRecord(500, NOTCH, "world", 1, 64, 1, "new message")));

        Purger.Result res = Purger.purge(conn, ids, DbConfig.Dialect.SQLITE, 300, Set.of(), "world", true);
        assertEquals(3, res.deleted());
        assertEquals(1, count("block"));
        assertEquals(1, count("item"));
        assertEquals(1, count("chat"));
        assertEquals(0, count("block_nbt"));
        assertEquals(1, count("item_data"));

        Purger.Result catOnly = Purger.purge(conn, ids, DbConfig.Dialect.SQLITE, 600, Set.of(Category.CHAT), null, false);
        assertEquals(1, catOnly.deleted());
        assertEquals(0, count("chat"));
        assertTrue(count("block") == 1);
    }

    private int count(String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + Migrations.table(table))) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
