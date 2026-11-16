package me.skerik.justlogs.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.queue.BatchWriter;
import me.skerik.justlogs.queue.LogQueue;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QueryParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the same storage paths against a real MariaDB or MySQL server. Skipped unless the environment says where one is:
 * {@code JL_TEST_MARIADB=127.0.0.1:3306/justlogs_test}, plus {@code JL_TEST_MARIADB_USER} and {@code JL_TEST_MARIADB_PASSWORD}.
 * The database is wiped (every {@code jl_} table is dropped), so point it at a throwaway one.
 */
@EnabledIfEnvironmentVariable(named = "JL_TEST_MARIADB", matches = ".+")
class MariaDbIntegrationTest {

    private static final long NOW = 100_000;

    private static DbConfig config() {
        String target = System.getenv("JL_TEST_MARIADB");
        String[] hostPortDb = target.split("[:/]");
        return new DbConfig(DbConfig.Dialect.MARIADB, null, hostPortDb[0], Integer.parseInt(hostPortDb[1]), hostPortDb[2],
                System.getenv().getOrDefault("JL_TEST_MARIADB_USER", "root"),
                System.getenv().getOrDefault("JL_TEST_MARIADB_PASSWORD", ""), 3, "trust");
    }

    private static void wipe(Connection c) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'jl\\_%'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        try (Statement st = c.createStatement()) {
            for (String t : tables) st.execute("DROP TABLE IF EXISTS " + t);
        }
    }

    @Test
    void schemaWritePathAndLookupsWorkOnMariaDb() throws Exception {
        try (Database db = new Database(config())) {
            try (Connection c = db.openDedicated()) {
                wipe(c);
            }
            assertEquals(Migrations.latestVersion(), Migrations.migrate(db));
            assertEquals(Migrations.latestVersion(), Migrations.migrate(db), "second run is a no-op");

            try (Connection c = db.openDedicated()) {
                assertEquals(2, scalar(c, "SELECT COUNT(DISTINCT table_name) FROM information_schema.statistics"
                        + " WHERE table_schema = DATABASE() AND index_type = 'FULLTEXT'"), "chat and command fulltext indexes");
            }

            IdCache ids = new IdCache(DbConfig.Dialect.MARIADB, "main");
            LogQueue queue = new LogQueue(100_000);
            BatchWriter writer = new BatchWriter(queue, db, ids, new BlockStore(), 20, 100, Logger.getLogger("it"));
            writer.start();
            Actor notch = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Notch");
            Actor jeb = Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000002"), "jeb_");
            for (int i = 0; i < 250; i++) {
                queue.offer(new BlockRecord((int) NOW - 1000 + i, i % 5 == 0 ? jeb : notch, i % 2 == 0 ? Action.BREAK : Action.PLACE,
                        "world", i, 64, 0, i % 2 == 0 ? "stone" : "air", "", i % 2 == 0 ? "air" : "oak_stairs",
                        i % 2 == 0 ? "" : "facing=north,half=top"));
            }
            queue.offer(new BlockRecord((int) NOW, Actor.player(UUID.fromString(notch.ident()), "NotchRenamed"),
                    Action.PLACE, "world", 999, 64, 0, "air", "", "dirt", ""));
            writer.flushAll().get(20, TimeUnit.SECONDS);
            assertEquals(null, writer.lastError());
            writer.stop(true, 5000);

            QueryCompiler compiler = new QueryCompiler(ids);
            try (Connection c = db.openDedicated()) {
                assertEquals(251, count(c, "jl_block"));
                assertEquals(2, count(c, "jl_users"));
                assertEquals(2, count(c, "jl_user_names") - 1, "one rename recorded on top of the first names");
                LogQuery all = QueryParser.parse("r:#global", NOW, QueryParser.NO_TAGS, 0);
                assertEquals(251, compiler.count(c, all, Origin.NONE, 10_000));
                assertEquals(50, compiler.lookup(c, QueryParser.parse("u:jeb_", NOW, QueryParser.NO_TAGS, 0), Origin.NONE, null, 500).rows().size());
                assertEquals(125, compiler.lookup(c, QueryParser.parse("a:-block", NOW, QueryParser.NO_TAGS, 0), Origin.NONE, null, 500).rows().size());
                assertEquals(125, compiler.lookup(c, QueryParser.parse("b:oak_stairs", NOW, QueryParser.NO_TAGS, 0), Origin.NONE, null, 500).rows().size());
                assertEquals(3, compiler.lookup(c, QueryParser.parse("r:1", NOW, QueryParser.NO_TAGS, 0), new Origin("world", 1, 64, 0), null, 50).rows().size());
                var page = compiler.lookup(c, all, Origin.NONE, null, 100);
                assertEquals(100, page.rows().size());
                assertTrue(page.next() != null);
                var page2 = compiler.lookup(c, all, Origin.NONE, page.next(), 100);
                assertTrue(page2.rows().get(0).id() < page.rows().get(99).id(), "keyset paging continues below the cursor");
                assertEquals("oak_stairs", compiler.lookup(c, QueryParser.parse("b:oak_stairs", NOW, QueryParser.NO_TAGS, 0), Origin.NONE, null, 1).rows().get(0) instanceof me.skerik.justlogs.model.BlockEntry be ? be.newMaterial() : "");
            }
        }
    }

    private static long count(Connection c, String table) throws Exception {
        return scalar(c, "SELECT COUNT(*) FROM " + table);
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
