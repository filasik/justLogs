package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.model.BlockRecord;

/** All SQL touching {@code jl_block}. */
public final class BlockStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("block")
            + " (time, user_id, world_id, x, y, z, action, old_mat, old_data, new_mat, new_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Column list shared with {@link QueryCompiler}; read with {@link #read}. */
    static final String SELECT = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z,"
            + " om.name, od.data, nm.name, nd.data, b.flags FROM " + Migrations.table("block") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id"
            + " LEFT JOIN " + Migrations.table("materials") + " om ON om.id = b.old_mat"
            + " LEFT JOIN " + Migrations.table("blockdata") + " od ON od.id = b.old_data"
            + " LEFT JOIN " + Migrations.table("materials") + " nm ON nm.id = b.new_mat"
            + " LEFT JOIN " + Migrations.table("blockdata") + " nd ON nd.id = b.new_data";

    /** Resolves dictionary ids (autocommit, idempotent) and inserts the rows in one transaction. */
    public void insert(Connection c, IdCache ids, List<BlockRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            BlockRecord r = rows.get(i);
            resolved[i] = new int[] {
                    ids.user(c, r.actor(), r.time()), ids.world(c, r.world()),
                    ids.material(c, r.oldMaterial()), ids.blockData(c, r.oldData()),
                    ids.material(c, r.newMaterial()), ids.blockData(c, r.newData())};
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT)) {
                for (int i = 0; i < rows.size(); i++) {
                    BlockRecord r = rows.get(i);
                    int[] id = resolved[i];
                    ps.setInt(1, r.time());
                    ps.setInt(2, id[0]);
                    ps.setInt(3, id[1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setInt(7, r.action().id());
                    ps.setInt(8, id[2]);
                    ps.setInt(9, id[3]);
                    ps.setInt(10, id[4]);
                    ps.setInt(11, id[5]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Reads rows produced by the {@link #SELECT} column list. */
    static List<BlockEntry> read(ResultSet rs, int max) throws SQLException {
        List<BlockEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            Action action = Action.byId(rs.getInt(5)).orElse(Action.BREAK);
            String oldMat = rs.getString(10);
            String newMat = rs.getString(12);
            out.add(new BlockEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                    oldMat == null ? "" : oldMat, nullToEmpty(rs.getString(11)),
                    newMat == null ? "" : newMat, nullToEmpty(rs.getString(13)), (rs.getInt(14) & 1) != 0));
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
