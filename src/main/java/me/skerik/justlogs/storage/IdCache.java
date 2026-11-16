package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.skerik.justlogs.model.Actor;

/**
 * In-memory view of the dictionary tables (users, worlds, materials, block states) so fact rows can be written with
 * numeric ids. Missing entries are inserted by the writer thread only (never by a listener). Insert-ignore plus a
 * select makes the insert safe if two servers share one database and race for the same new name.
 */
public final class IdCache {

    private final DbConfig.Dialect dialect;
    private final String server;
    private final Map<String, Integer> users = new ConcurrentHashMap<>();
    private final Map<Integer, String> userNames = new ConcurrentHashMap<>();
    private final Map<String, Integer> worlds = new ConcurrentHashMap<>();
    private final Map<String, Integer> materials = new ConcurrentHashMap<>();
    private final Map<String, Integer> blockData = new ConcurrentHashMap<>();

    public IdCache(DbConfig.Dialect dialect, String server) {
        this.dialect = dialect;
        this.server = server;
    }

    /** Loads the small dictionaries up front. */
    public void preload(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, name FROM " + Migrations.table("materials"))) {
                while (rs.next()) materials.put(rs.getString(2), rs.getInt(1));
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name FROM " + Migrations.table("worlds") + " WHERE server = ?")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) worlds.put(rs.getString(2), rs.getInt(1));
            }
        }
    }

    /** Material id, 0 for the empty key (no material). */
    public int material(Connection c, String key) throws SQLException {
        if (key.isEmpty()) return 0;
        Integer cached = materials.get(key);
        if (cached != null) return cached;
        int id = intern(c, "materials", "name", key);
        materials.put(key, id);
        return id;
    }

    /** Block state id, 0 when the block has no state. */
    public int blockData(Connection c, String data) throws SQLException {
        if (data.isEmpty()) return 0;
        Integer cached = blockData.get(data);
        if (cached != null) return cached;
        int id = intern(c, "blockdata", "data", data);
        blockData.put(data, id);
        return id;
    }

    public int world(Connection c, String name) throws SQLException {
        Integer cached = worlds.get(name);
        if (cached != null) return cached;
        String table = Migrations.table("worlds");
        try (PreparedStatement ps = c.prepareStatement(insertIgnore(table, "server, name", "?, ?"))) {
            ps.setString(1, server);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE server = ? AND name = ?")) {
            ps.setString(1, server);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int id = rs.getInt(1);
                worlds.put(name, id);
                return id;
            }
        }
    }

    /** User id; creates the row on first sight and records a name change of a player. */
    public int user(Connection c, Actor actor, int now) throws SQLException {
        Integer cached = users.get(actor.ident());
        if (cached != null) {
            if (actor.player() && !actor.name().equals(userNames.get(cached))) renameUser(c, cached, actor.name(), now);
            return cached;
        }
        String table = Migrations.table("users");
        try (PreparedStatement ps = c.prepareStatement(insertIgnore(table, "ident, name, is_player, first_seen", "?, ?, ?, ?"))) {
            ps.setString(1, actor.ident());
            ps.setString(2, actor.name());
            ps.setInt(3, actor.player() ? 1 : 0);
            ps.setInt(4, now);
            ps.executeUpdate();
        }
        int id;
        String storedName;
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name FROM " + table + " WHERE ident = ?")) {
            ps.setString(1, actor.ident());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getInt(1);
                storedName = rs.getString(2);
            }
        }
        users.put(actor.ident(), id);
        userNames.put(id, storedName);
        if (actor.player()) {
            if (!actor.name().equals(storedName)) renameUser(c, id, actor.name(), now);
            else recordName(c, id, actor.name(), now);
        }
        return id;
    }

    private void renameUser(Connection c, int id, String name, int now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + Migrations.table("users") + " SET name = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
        recordName(c, id, name, now);
        userNames.put(id, name);
    }

    /** History of names; one row per (user, second) is enough, duplicates are ignored. */
    private void recordName(Connection c, int id, String name, int now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(insertIgnore(Migrations.table("user_names"), "user_id, since, name", "?, ?, ?"))) {
            ps.setInt(1, id);
            ps.setInt(2, now);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    // ── reads for queries ──────────────────────────────────────────────────────────

    /** Ids of users with these names (case-insensitive); unknown names are skipped. */
    public List<Integer> findUsers(Connection c, List<String> names) throws SQLException {
        String table = Migrations.table("users");
        String sql = "SELECT id FROM " + table + " WHERE name = ?" + (dialect == DbConfig.Dialect.SQLITE ? " COLLATE NOCASE" : "");
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (String n : names) {
                ps.setString(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    public List<Integer> findMaterials(Connection c, List<String> keys) throws SQLException {
        List<Integer> out = new ArrayList<>();
        for (String k : keys) {
            Integer cached = materials.get(k.toLowerCase(Locale.ROOT));
            if (cached != null) out.add(cached);
        }
        if (out.size() == keys.size()) return out;
        out.clear();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + Migrations.table("materials") + " WHERE name = ?")) {
            for (String k : keys) {
                ps.setString(1, k.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    /** World id on this server, or -1 when no such world has ever been logged. */
    public int findWorld(Connection c, String name) throws SQLException {
        Integer cached = worlds.get(name);
        if (cached != null) return cached;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + Migrations.table("worlds") + " WHERE server = ? AND name = ?")) {
            ps.setString(1, server);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    worlds.put(name, rs.getInt(1));
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private int intern(Connection c, String tableName, String column, String value) throws SQLException {
        String table = Migrations.table(tableName);
        try (PreparedStatement ps = c.prepareStatement(insertIgnore(table, column, "?"))) {
            ps.setString(1, value);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE " + column + " = ?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String insertIgnore(String table, String columns, String values) {
        String verb = dialect == DbConfig.Dialect.SQLITE ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
        return verb + table + " (" + columns + ") VALUES (" + values + ")";
    }
}
