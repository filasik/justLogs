package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.SessionEntry;
import me.skerik.justlogs.model.SessionRecord;

/** All SQL touching {@code jl_session}. */
public final class SessionStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("session")
            + " (time, user_id, world_id, x, y, z, action, ip_hash, ip) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String SELECT = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z,"
            + " b.ip_hash, b.ip FROM " + Migrations.table("session") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id";

    public void insert(Connection c, IdCache ids, List<SessionRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            SessionRecord r = rows.get(i);
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world())};
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT)) {
                for (int i = 0; i < rows.size(); i++) {
                    SessionRecord r = rows.get(i);
                    ps.setInt(1, r.time());
                    ps.setInt(2, resolved[i][0]);
                    ps.setInt(3, resolved[i][1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setInt(7, r.action().id());
                    if (r.ipHash() == null) ps.setNull(8, Types.VARCHAR);
                    else ps.setString(8, r.ipHash());
                    if (r.ip() == null) ps.setNull(9, Types.VARCHAR);
                    else ps.setString(9, r.ip());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static List<SessionEntry> read(ResultSet rs, int max) throws SQLException {
        List<SessionEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            Action action = Action.byId(rs.getInt(5)).orElse(Action.LOGIN);
            out.add(new SessionEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getString(11)));
        }
        return out;
    }
}
