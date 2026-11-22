package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.ItemEntry;
import me.skerik.justlogs.model.ItemRecord;

/** All SQL touching {@code jl_item}. */
public final class ItemStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("item")
            + " (time, user_id, world_id, x, y, z, action, mat, amount, item_data_id, flags)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";

    static final String SELECT = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z,"
            + " m.name, b.amount, b.item_data_id, b.flags FROM " + Migrations.table("item") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id"
            + " LEFT JOIN " + Migrations.table("materials") + " m ON m.id = b.mat";

    private final ItemDataStore itemDataStore;

    public ItemStore() {
        this(new ItemDataStore());
    }

    ItemStore(ItemDataStore itemDataStore) {
        this.itemDataStore = itemDataStore;
    }

    public void insert(Connection c, IdCache ids, List<ItemRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        long[] itemData = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            ItemRecord r = rows.get(i);
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world()), ids.material(c, r.material())};
            itemData[i] = r.itemData() == null ? 0 : itemDataStore.intern(c, r.itemData(), resolved[i][2]);
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT)) {
                for (int i = 0; i < rows.size(); i++) {
                    ItemRecord r = rows.get(i);
                    ps.setInt(1, r.time());
                    ps.setInt(2, resolved[i][0]);
                    ps.setInt(3, resolved[i][1]);
                    ps.setInt(4, r.x());
                    ps.setInt(5, r.y());
                    ps.setInt(6, r.z());
                    ps.setInt(7, r.action().id());
                    ps.setInt(8, resolved[i][2]);
                    ps.setInt(9, r.amount());
                    if (itemData[i] == 0) ps.setNull(10, Types.BIGINT);
                    else ps.setLong(10, itemData[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static List<ItemEntry> read(ResultSet rs, int max) throws SQLException {
        List<ItemEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            Action action = Action.byId(rs.getInt(5)).orElse(Action.DROP);
            String mat = rs.getString(10);
            out.add(new ItemEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), mat == null ? "" : mat,
                    rs.getInt(11), rs.getLong(12) > 0, (rs.getInt(13) & 1) != 0));
        }
        return out;
    }
}
