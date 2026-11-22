package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.SignEntry;
import me.skerik.justlogs.model.SignRecord;

/** All SQL touching {@code jl_sign}. */
public final class SignStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("sign")
            + " (time, user_id, world_id, x, y, z, action, side, color, flags, old_text, new_text)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)";

    static final String SELECT = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z, b.side,"
            + " b.old_text, b.new_text, b.flags FROM " + Migrations.table("sign") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id";

    public void insert(Connection c, IdCache ids, List<SignRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            SignRecord r = rows.get(i);
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world())};
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT)) {
                for (int i = 0; i < rows.size(); i++) {
                    SignRecord r = rows.get(i);
                    ps.setInt(1, r.time());
                    ps.setInt(2, resolved[i][0]);
                    ps.setInt(3, resolved[i][1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setInt(7, r.action().id());
                    ps.setInt(8, r.side());
                    ps.setString(9, r.oldText());
                    ps.setString(10, r.newText());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static List<SignEntry> read(ResultSet rs, int max) throws SQLException {
        List<SignEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            Action action = Action.byId(rs.getInt(5)).orElse(Action.SIGN_EDIT);
            String oldText = rs.getString(11);
            String newText = rs.getString(12);
            out.add(new SignEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10),
                    oldText == null ? "" : oldText, newText == null ? "" : newText, (rs.getInt(13) & 1) != 0));
        }
        return out;
    }
}
