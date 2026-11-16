package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.model.BlockRecord;

/** All SQL touching {@code jl_block}. */
public final class BlockStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("block")
            + " (time, user_id, world_id, x, y, z, action, old_mat, old_data, new_mat, new_data, flags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Column list shared with {@link QueryCompiler}; read with {@link #read}. */
    static final String SELECT = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z,"
            + " om.name, od.data, nm.name, nd.data, b.flags FROM " + Migrations.table("block") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id"
            + " LEFT JOIN " + Migrations.table("materials") + " om ON om.id = b.old_mat"
            + " LEFT JOIN " + Migrations.table("blockdata") + " od ON od.id = b.old_data"
            + " LEFT JOIN " + Migrations.table("materials") + " nm ON nm.id = b.new_mat"
            + " LEFT JOIN " + Migrations.table("blockdata") + " nd ON nd.id = b.new_data";

    /** Flag bit 1 of {@code jl_block.flags}: a row in {@code jl_block_nbt} belongs to this block row. */
    static final int FLAG_NBT = 2;

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
                    if (rows.get(i).oldNbt() != null) continue;
                    bindRow(ps, rows.get(i), resolved[i], 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // Rows with a tile entity snapshot are rare (broken chests): one by one, to learn the generated id
            for (int i = 0; i < rows.size(); i++) {
                BlockRecord r = rows.get(i);
                if (r.oldNbt() == null) continue;
                long id;
                try (PreparedStatement ps = tx.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                    bindRow(ps, r, resolved[i], FLAG_NBT);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("no generated id for a block row");
                        id = keys.getLong(1);
                    }
                }
                try (PreparedStatement ps = tx.prepareStatement("INSERT INTO " + Migrations.table("block_nbt")
                        + " (block_id, old_nbt) VALUES (?, ?)")) {
                    ps.setLong(1, id);
                    ps.setBytes(2, r.oldNbt());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private static void bindRow(PreparedStatement ps, BlockRecord r, int[] id, int flags) throws SQLException {
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
        ps.setInt(12, flags);
    }

    /** The stored tile entity snapshots of the given block rows (only rows flagged with one are looked up). */
    public java.util.Map<Long, byte[]> nbtFor(Connection c, List<BlockEntry> rows) throws SQLException {
        java.util.Map<Long, byte[]> out = new java.util.HashMap<>();
        List<Long> ids = rows.stream().filter(BlockEntry::hasNbt).map(BlockEntry::id).toList();
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> part = ids.subList(from, Math.min(ids.size(), from + 500));
            String in = "?" + ", ?".repeat(part.size() - 1);
            try (PreparedStatement ps = c.prepareStatement("SELECT block_id, old_nbt FROM " + Migrations.table("block_nbt")
                    + " WHERE block_id IN (" + in + ")")) {
                for (int i = 0; i < part.size(); i++) ps.setLong(i + 1, part.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] data = rs.getBytes(2);
                        if (data != null) out.put(rs.getLong(1), data);
                    }
                }
            }
        }
        return out;
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
                    newMat == null ? "" : newMat, nullToEmpty(rs.getString(13)), (rs.getInt(14) & 1) != 0, (rs.getInt(14) & FLAG_NBT) != 0));
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
