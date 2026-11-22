package me.skerik.justlogs.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.ChatRecord;
import me.skerik.justlogs.model.CommandRecord;
import me.skerik.justlogs.model.EntityEntry;
import me.skerik.justlogs.model.EntityRecord;
import me.skerik.justlogs.model.InteractEntry;
import me.skerik.justlogs.model.InteractRecord;
import me.skerik.justlogs.model.ItemEntry;
import me.skerik.justlogs.model.ItemRecord;
import me.skerik.justlogs.model.SessionEntry;
import me.skerik.justlogs.model.SessionRecord;
import me.skerik.justlogs.model.TextEntry;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QueryParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chat, commands, sessions, interactions, items, entities, keyword search and multi-category keyset paging. */
class NewStoresTest {

    private static final long NOW = 100_000;
    private static final Actor NOTCH = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Notch");

    @TempDir Path dir;
    Database db;
    Connection conn;
    IdCache ids;
    QueryCompiler compiler;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(DbConfig.sqlite(dir.resolve("new.db"), 2));
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
    void chatCommandAndKeywordSearchWithMultiCategoryPaging() throws Exception {
        TextStore texts = new TextStore();
        texts.insertChat(conn, ids, List.of(
                new ChatRecord(99_900, NOTCH, "world", 1, 64, 1, "hello diamond world"),
                new ChatRecord(99_902, NOTCH, "world", 1, 64, 1, "nothing here")));
        texts.insertCommand(conn, ids, List.of(
                new CommandRecord(99_901, NOTCH, "world", 1, 64, 1, "/give Notch diamond 64"),
                new CommandRecord(99_903, NOTCH, "world", 1, 64, 1, "/spawn")));

        QueryCompiler.Page p1 = compiler.lookup(conn, q("a:chat,command r:#global"), Origin.NONE, null, 2);
        assertEquals(2, p1.rows().size());
        assertNotNull(p1.next());
        assertEquals("/spawn", assertInstanceOf(TextEntry.class, p1.rows().get(0)).message());
        assertEquals("nothing here", assertInstanceOf(TextEntry.class, p1.rows().get(1)).message());

        QueryCompiler.Page p2 = compiler.lookup(conn, q("a:chat,command r:#global"), Origin.NONE, p1.next(), 2);
        assertEquals(2, p2.rows().size());
        assertNull(p2.next());
        assertEquals("/give Notch diamond 64", assertInstanceOf(TextEntry.class, p2.rows().get(0)).message());
        assertEquals("hello diamond world", assertInstanceOf(TextEntry.class, p2.rows().get(1)).message());

        QueryCompiler.Page kw = compiler.lookup(conn, q("k:diamond r:#global"), Origin.NONE, null, 10);
        assertEquals(2, kw.rows().size());
        assertEquals(2, compiler.count(conn, q("k:diamond r:#global"), Origin.NONE, 100));
    }

    @Test
    void sessionsInteractionsItemsAndEntitiesRoundTrip() throws Exception {
        new SessionStore().insert(conn, ids, List.of(
                new SessionRecord(99_910, NOTCH, Action.LOGIN, "world", 0, 64, 0, "hash123", null)));
        new InteractStore().insert(conn, ids, List.of(
                new InteractRecord(99_911, NOTCH, Action.CLICK, "world", 5, 64, 5, "oak_door")));
        new ItemStore().insert(conn, ids, List.of(
                new ItemRecord(99_912, NOTCH, Action.DROP, "world", 5, 64, 5, "diamond_sword", 1, new byte[]{1, 2, 3})));
        new EntityStore().insert(conn, ids, List.of(
                new EntityRecord(99_913, NOTCH, Action.KILL, "world", 5, 64, 5, "creeper", null, 0, "Boomy", null)));

        SessionEntry se = assertInstanceOf(SessionEntry.class,
                compiler.lookup(conn, q("a:session r:#global"), Origin.NONE, null, 10).rows().getFirst());
        assertEquals("hash123", se.ipHash());

        InteractEntry ie = assertInstanceOf(InteractEntry.class,
                compiler.lookup(conn, q("a:click b:oak_door r:#global"), Origin.NONE, null, 10).rows().getFirst());
        assertEquals("oak_door", ie.material());

        ItemEntry it = assertInstanceOf(ItemEntry.class,
                compiler.lookup(conn, q("a:item b:diamond_sword r:#global"), Origin.NONE, null, 10).rows().getFirst());
        assertTrue(it.hasData());
        assertEquals(1, it.amount());

        EntityEntry en = assertInstanceOf(EntityEntry.class,
                compiler.lookup(conn, q("e:creeper r:#global"), Origin.NONE, null, 10).rows().getFirst());
        assertEquals("creeper", en.entityType());
        assertEquals("Boomy", en.name());
    }
}
