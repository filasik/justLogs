package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import me.skerik.justlogs.model.Category;
import org.jspecify.annotations.Nullable;

/** Deletes old log rows (manual {@code /jo purge} and automatic retention) and cleans up orphaned attachments. */
public final class Purger {

    private Purger() {
    }

    public record Result(long deleted, long durationMs) {
    }

    /**
     * Deletes rows older than {@code cutoffSec} across {@code categories} (all 9 categories when empty).
     * When {@code worldName} is non-null, only rows in that world are deleted.
     */
    public static Result purge(Connection c, IdCache ids, DbConfig.Dialect dialect, int cutoffSec,
                               Collection<Category> categories, @Nullable String worldName, boolean optimize)
            throws SQLException {
        long started = System.nanoTime();
        Collection<Category> targets = categories.isEmpty() ? List.of(Category.values()) : categories;
        Integer worldId = null;
        if (worldName != null) {
            int wid = ids.findWorld(c, worldName);
            if (wid < 0) return new Result(0, 0);
            worldId = wid;
        }
        long deleted = 0;
        boolean touchedBlock = false;
        boolean touchedItems = false;
        Integer finalWorldId = worldId;
        for (Category cat : targets) {
            if (cat == Category.BLOCK) touchedBlock = true;
            if (cat == Category.CONTAINER || cat == Category.ITEM) touchedItems = true;
            String table = Migrations.table(cat.table());
            String sql = "DELETE FROM " + table + " WHERE time < ?" + (finalWorldId != null ? " AND world_id = ?" : "");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, cutoffSec);
                if (finalWorldId != null) ps.setInt(2, finalWorldId);
                deleted += ps.executeUpdate();
            }
        }
        if (touchedBlock) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM " + Migrations.table("block_nbt")
                        + " WHERE block_id NOT IN (SELECT id FROM " + Migrations.table("block") + ")");
            }
        }
        if (touchedItems) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM " + Migrations.table("item_data")
                        + " WHERE id NOT IN (SELECT item_data_id FROM " + Migrations.table("container")
                        + " WHERE item_data_id IS NOT NULL)"
                        + " AND id NOT IN (SELECT item_data_id FROM " + Migrations.table("item")
                        + " WHERE item_data_id IS NOT NULL)");
            }
        }
        if (optimize) {
            optimize(c, dialect, targets);
        }
        return new Result(deleted, (System.nanoTime() - started) / 1_000_000);
    }

    private static void optimize(Connection c, DbConfig.Dialect dialect, Collection<Category> targets)
            throws SQLException {
        if (dialect == DbConfig.Dialect.SQLITE) {
            boolean prev = c.getAutoCommit();
            if (!prev) c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA optimize");
                st.execute("VACUUM");
            } finally {
                if (!prev) c.setAutoCommit(false);
            }
        } else {
            for (Category cat : targets) {
                try (Statement st = c.createStatement()) {
                    st.execute("OPTIMIZE TABLE " + Migrations.table(cat.table()));
                }
            }
        }
    }
}
