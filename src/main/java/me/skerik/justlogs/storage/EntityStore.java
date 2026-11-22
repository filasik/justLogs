package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.EntityEntry;
import me.skerik.justlogs.model.EntityRecord;

/** All SQL touching {@code jl_entity}. */
public final class EntityStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("entity")
            + " (time, user_id, world_id, x, y, z, action, entity_id, victim_user_id, cause, name, detail, entity_data_id, flags)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)";

    static final String SELECT = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z,"
            + " e.name, vu.name, b.name, b.detail, b.flags FROM " + Migrations.table("entity") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id"
            + " LEFT JOIN " + Migrations.table("entities") + " e ON e.id = b.entity_id"
            + " LEFT JOIN " + Migrations.table("users") + " vu ON vu.id = b.victim_user_id";

    public void insert(Connection c, IdCache ids, List<EntityRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            EntityRecord r = rows.get(i);
            int victimId = r.victim() == null ? 0 : ids.user(c, r.victim(), r.time());
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world()),
                    ids.entity(c, r.entityType()), victimId};
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT)) {
                for (int i = 0; i < rows.size(); i++) {
                    EntityRecord r = rows.get(i);
                    ps.setInt(1, r.time());
                    ps.setInt(2, resolved[i][0]);
                    ps.setInt(3, resolved[i][1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setInt(7, r.action().id());
                    ps.setInt(8, resolved[i][2]);
                    if (resolved[i][3] == 0) ps.setNull(9, Types.INTEGER);
                    else ps.setInt(9, resolved[i][3]);
                    ps.setInt(10, r.cause());
                    if (r.name() == null) ps.setNull(11, Types.VARCHAR);
                    else ps.setString(11, r.name());
                    if (r.detail() == null) ps.setNull(12, Types.VARCHAR);
                    else ps.setString(12, r.detail());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static List<EntityEntry> read(ResultSet rs, int max) throws SQLException {
        List<EntityEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            Action action = Action.byId(rs.getInt(5)).orElse(Action.KILL);
            String entityType = rs.getString(10);
            out.add(new EntityEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                    entityType == null ? "" : entityType, rs.getString(11), rs.getString(12),
                    rs.getString(13), (rs.getInt(14) & 1) != 0));
        }
        return out;
    }
}
