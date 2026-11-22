package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.InteractEntry;
import me.skerik.justlogs.model.InteractRecord;

/** All SQL touching {@code jl_interact}. */
public final class InteractStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("interact")
            + " (time, user_id, world_id, x, y, z, action, mat) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    static final String SELECT = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z, m.name FROM "
            + Migrations.table("interact") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id"
            + " LEFT JOIN " + Migrations.table("materials") + " m ON m.id = b.mat";

    public void insert(Connection c, IdCache ids, List<InteractRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            InteractRecord r = rows.get(i);
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world()), ids.material(c, r.material())};
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT)) {
                for (int i = 0; i < rows.size(); i++) {
                    InteractRecord r = rows.get(i);
                    ps.setInt(1, r.time());
                    ps.setInt(2, resolved[i][0]);
                    ps.setInt(3, resolved[i][1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setInt(7, r.action().id());
                    ps.setInt(8, resolved[i][2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static List<InteractEntry> read(ResultSet rs, int max) throws SQLException {
        List<InteractEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            Action action = Action.byId(rs.getInt(5)).orElse(Action.CLICK);
            String mat = rs.getString(10);
            out.add(new InteractEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), mat == null ? "" : mat));
        }
        return out;
    }
}
