package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.ChatRecord;
import me.skerik.justlogs.model.CommandRecord;
import me.skerik.justlogs.model.TextEntry;

/** All SQL touching {@code jl_chat} and {@code jl_command}. */
public final class TextStore {

    private static final String INSERT_CHAT = "INSERT INTO " + Migrations.table("chat")
            + " (time, user_id, world_id, x, y, z, message) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_COMMAND = "INSERT INTO " + Migrations.table("command")
            + " (time, user_id, world_id, x, y, z, message) VALUES (?, ?, ?, ?, ?, ?, ?)";

    static final String SELECT_CHAT = "SELECT b.id, b.time, u.name, u.is_player, w.name, b.x, b.y, b.z, b.message FROM "
            + Migrations.table("chat") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id";

    static final String SELECT_COMMAND = "SELECT b.id, b.time, u.name, u.is_player, w.name, b.x, b.y, b.z, b.message FROM "
            + Migrations.table("command") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id";

    public void insertChat(Connection c, IdCache ids, List<ChatRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            ChatRecord r = rows.get(i);
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world())};
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT_CHAT)) {
                for (int i = 0; i < rows.size(); i++) {
                    ChatRecord r = rows.get(i);
                    ps.setInt(1, r.time());
                    ps.setInt(2, resolved[i][0]);
                    ps.setInt(3, resolved[i][1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setString(7, r.message());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void insertCommand(Connection c, IdCache ids, List<CommandRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            CommandRecord r = rows.get(i);
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world())};
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT_COMMAND)) {
                for (int i = 0; i < rows.size(); i++) {
                    CommandRecord r = rows.get(i);
                    ps.setInt(1, r.time());
                    ps.setInt(2, resolved[i][0]);
                    ps.setInt(3, resolved[i][1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setString(7, r.message());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static List<TextEntry> read(ResultSet rs, Action action, int max) throws SQLException {
        List<TextEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            String msg = rs.getString(9);
            out.add(new TextEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), msg == null ? "" : msg));
        }
        return out;
    }
}
