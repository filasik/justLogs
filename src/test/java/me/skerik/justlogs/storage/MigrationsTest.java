package me.skerik.justlogs.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import me.skerik.justlogs.model.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

    @TempDir Path dir;

    @Test
    void freshDatabaseReachesTheLatestVersionAndMigratingAgainIsANoop() throws Exception {
        try (Database db = new Database(DbConfig.sqlite(dir.resolve("fresh.db"), 2))) {
            assertEquals(Migrations.latestVersion(), Migrations.migrate(db));
            assertEquals(Migrations.latestVersion(), Migrations.migrate(db));
        }
    }

    @Test
    void createsEveryTableAndFillsTheActionDictionaryFromTheEnum() throws Exception {
        try (Database db = new Database(DbConfig.memory())) {
            Migrations.migrate(db);
            Set<String> tables = new HashSet<>();
            Set<Integer> actionIds = new HashSet<>();
            db.call(c -> {
                try (var st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                    while (rs.next()) tables.add(rs.getString(1));
                }
                try (var st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM " + Migrations.table("actions"))) {
                    while (rs.next()) actionIds.add(rs.getInt(1));
                }
                return null;
            });
            for (String t : new String[] {"meta", "users", "user_names", "worlds", "materials", "blockdata", "entities", "actions",
                    "block", "block_nbt", "sign", "item_data", "container", "item", "entity_data", "entity", "interact", "chat",
                    "command", "session", "chunk_activity", "rollbacks", "audit", "web_sessions", "schema"}) {
                assertTrue(tables.contains(Migrations.table(t)), "missing table " + t);
            }
            assertEquals(Action.values().length, actionIds.size());
            for (Action a : Action.values()) assertTrue(actionIds.contains(a.id()));
        }
    }

    @Test
    void canStopAtAnOlderVersionAndContinueLater() throws Exception {
        try (Database db = new Database(DbConfig.sqlite(dir.resolve("old.db"), 2))) {
            assertEquals(1, Migrations.migrate(db, 1));
            assertEquals(Migrations.latestVersion(), Migrations.migrate(db));
        }
    }

    @Test
    void mariaDbDdlHasNoPlaceholderLeftAndKeepsItsOwnStatements() {
        for (int v = 1; v <= Migrations.latestVersion(); v++) {
            for (String sql : Migrations.rendered(v, DbConfig.Dialect.MARIADB)) {
                assertFalse(sql.contains("{") || sql.contains("}"), "unresolved placeholder: " + sql);
                assertFalse(sql.contains("@mariadb:") || sql.contains("@sqlite:"), sql);
            }
            for (String sql : Migrations.rendered(v, DbConfig.Dialect.SQLITE)) {
                assertFalse(sql.contains("{") || sql.contains("}"), "unresolved placeholder: " + sql);
                assertFalse(sql.contains("ENGINE=") || sql.contains("UNSIGNED") || sql.contains("FULLTEXT"), "MariaDB syntax in SQLite: " + sql);
            }
        }
        var maria = Migrations.rendered(1, DbConfig.Dialect.MARIADB);
        assertTrue(maria.stream().anyMatch(s -> s.contains("FULLTEXT")));
        assertTrue(maria.stream().anyMatch(s -> s.startsWith("CREATE TABLE jl_block ") && s.contains("BIGINT UNSIGNED NOT NULL AUTO_INCREMENT")));
    }

    @Test
    void renderOnlyKeepsStatementsForItsOwnDialect() {
        assertEquals("", Migrations.render("@mariadb:SELECT 1", DbConfig.Dialect.SQLITE));
        assertEquals("SELECT 1", Migrations.render("@mariadb:SELECT 1", DbConfig.Dialect.MARIADB));
        assertEquals("SELECT 2", Migrations.render("@sqlite:SELECT 2", DbConfig.Dialect.SQLITE));
    }
}
