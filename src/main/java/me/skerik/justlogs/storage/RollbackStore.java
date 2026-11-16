package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.BlockEntry;
import org.jspecify.annotations.Nullable;

/** All SQL touching {@code jl_rollbacks}, {@code jl_rollback_rows} and the rolled-back flag of {@code jl_block}. */
public final class RollbackStore {

    public static final int KIND_ROLLBACK = 1;
    public static final int KIND_RESTORE = 2;
    /** Undo of a rollback (re-applies the changes, so it acts like a restore). */
    public static final int KIND_UNDO_ROLLBACK = 4;
    /** Undo of a restore (acts like a rollback). */
    public static final int KIND_UNDO_RESTORE = 5;

    public static final int STATUS_DONE = 3;
    public static final int SOURCE_GAME = 1;
    public static final int SOURCE_CONSOLE = 4;

    private static final int CHUNK = 500;

    /** A finished operation. {@code restores} tells which way it changed the world. */
    public record Entry(long id, int kind, String query, boolean containers) {

        public boolean restores() {
            return kind == KIND_RESTORE || kind == KIND_UNDO_ROLLBACK;
        }
    }

    /** Inserts the rollback row and the ids it touched in one transaction; returns the new id. */
    public long record(Connection c, int userId, int source, int kind, String query, int blocks, int chunks, long durationMs,
                       @Nullable Long undoOf, boolean containers, List<Long> blockIds, int nowSec) throws SQLException {
        return Database.transaction(c, tx -> {
            long id;
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO " + Migrations.table("rollbacks")
                    + " (time, user_id, source, kind, status, query, bounds, blocks, chunks, duration_ms, undo_of)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, nowSec);
                ps.setInt(2, userId);
                ps.setInt(3, source);
                ps.setInt(4, kind);
                ps.setInt(5, STATUS_DONE);
                ps.setString(6, query);
                ps.setString(7, "{}");
                ps.setInt(8, blocks);
                ps.setInt(9, chunks);
                ps.setLong(10, Math.min(durationMs, 0xFFFF_FFFFL));
                if (undoOf == null) ps.setNull(11, java.sql.Types.INTEGER);
                else ps.setLong(11, undoOf);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("no generated id for the rollback row");
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO "
                    + Migrations.table(containers ? "rollback_crows" : "rollback_rows")
                    + (containers ? " (rollback_id, container_id)" : " (rollback_id, block_id)") + " VALUES (?, ?)")) {
                for (long blockId : blockIds) {
                    ps.setLong(1, id);
                    ps.setLong(2, blockId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            setFlag(tx, containers ? "container" : "block", blockIds, kind == KIND_ROLLBACK || kind == KIND_UNDO_RESTORE);
            return id;
        });
    }

    /** Sets or clears the rolled-back bit of the given block rows. */
    static void setFlag(Connection c, String table, List<Long> ids, boolean rolledBack) throws SQLException {
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> part = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            String in = "?" + ", ?".repeat(part.size() - 1);
            String set = rolledBack ? "flags = (flags | 1)" : "flags = (flags & ~1)";
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + Migrations.table(table) + " SET " + set
                    + " WHERE id IN (" + in + ")")) {
                for (int i = 0; i < part.size(); i++) ps.setLong(i + 1, part.get(i));
                ps.executeUpdate();
            }
        }
    }

    /** The newest operation of this user that has not been undone yet. */
    public @Nullable Entry lastUndoable(Connection c, int userId) throws SQLException {
        String sql = "SELECT id, kind, query, EXISTS (SELECT 1 FROM " + Migrations.table("rollback_crows")
                + " x WHERE x.rollback_id = r.id) FROM " + Migrations.table("rollbacks") + " r WHERE user_id = ? AND status = "
                + STATUS_DONE + " AND NOT EXISTS (SELECT 1 FROM " + Migrations.table("rollbacks")
                + " u WHERE u.undo_of = r.id) ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Entry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0) : null;
            }
        }
    }

    /** The block rows a past operation touched, oldest first. */
    public List<BlockEntry> rowsOf(Connection c, long rollbackId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT block_id FROM " + Migrations.table("rollback_rows")
                + " WHERE rollback_id = ?")) {
            ps.setLong(1, rollbackId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }
        List<BlockEntry> rows = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> part = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            String in = "?" + ", ?".repeat(part.size() - 1);
            try (PreparedStatement ps = c.prepareStatement(BlockStore.SELECT + " WHERE b.id IN (" + in + ")")) {
                for (int i = 0; i < part.size(); i++) ps.setLong(i + 1, part.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    rows.addAll(BlockStore.read(rs, Integer.MAX_VALUE));
                }
            }
        }
        rows.sort(java.util.Comparator.comparingInt(BlockEntry::time).thenComparingLong(BlockEntry::id));
        return rows;
    }
}
