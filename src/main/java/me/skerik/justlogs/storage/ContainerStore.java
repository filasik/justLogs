package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.ContainerEntry;
import me.skerik.justlogs.model.ContainerRecord;

/** All SQL touching {@code jl_container} and the deduplicated item data in {@code jl_item_data}. */
public final class ContainerStore {

    private static final String INSERT = "INSERT INTO " + Migrations.table("container")
            + " (time, user_id, world_id, x, y, z, action, holder_mat, mat, amount, item_data_id, flags)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";

    private static final String FROM = " FROM " + Migrations.table("container") + " b"
            + " JOIN " + Migrations.table("users") + " u ON u.id = b.user_id"
            + " JOIN " + Migrations.table("worlds") + " w ON w.id = b.world_id"
            + " LEFT JOIN " + Migrations.table("materials") + " hm ON hm.id = b.holder_mat"
            + " LEFT JOIN " + Migrations.table("materials") + " im ON im.id = b.mat";

    private static final String COLUMNS = "SELECT b.id, b.time, u.name, u.is_player, b.action, w.name, b.x, b.y, b.z,"
            + " hm.name, im.name, b.amount, b.item_data_id, b.flags";

    /** Lookups: no item bytes. */
    static final String SELECT = COLUMNS + ", NULL" + FROM;
    /** Rollbacks: with the item bytes of items that have data. */
    static final String SELECT_WITH_DATA = COLUMNS + ", d.data" + FROM
            + " LEFT JOIN " + Migrations.table("item_data") + " d ON d.id = b.item_data_id";

    private final ItemDataStore itemDataStore;

    public ContainerStore() {
        this(new ItemDataStore());
    }

    ContainerStore(ItemDataStore itemDataStore) {
        this.itemDataStore = itemDataStore;
    }

    public void insert(Connection c, IdCache ids, List<ContainerRecord> rows) throws SQLException {
        if (rows.isEmpty()) return;
        int[][] resolved = new int[rows.size()][];
        long[] itemData = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            ContainerRecord r = rows.get(i);
            resolved[i] = new int[] {ids.user(c, r.actor(), r.time()), ids.world(c, r.world()),
                    ids.material(c, r.holderMaterial()), ids.material(c, r.material())};
            itemData[i] = r.itemData() == null ? 0 : itemDataStore.intern(c, r.itemData(), resolved[i][3]);
        }
        Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(INSERT)) {
                for (int i = 0; i < rows.size(); i++) {
                    ContainerRecord r = rows.get(i);
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
                    ps.setInt(10, r.amount());
                    if (itemData[i] == 0) ps.setNull(11, java.sql.Types.BIGINT);
                    else ps.setLong(11, itemData[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    static List<ContainerEntry> read(ResultSet rs, int max) throws SQLException {
        List<ContainerEntry> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            Action action = Action.byId(rs.getInt(5)).orElse(Action.DEPOSIT);
            String holder = rs.getString(10);
            String mat = rs.getString(11);
            long dataId = rs.getLong(13);
            out.add(new ContainerEntry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4) != 0, action,
                    rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), holder == null ? "" : holder,
                    mat == null ? "" : mat, rs.getInt(12), dataId != 0, rs.getBytes(15), (rs.getInt(14) & 1) != 0));
        }
        return out;
    }

    /** The container rows a past operation touched. */
    public List<ContainerEntry> rowsOf(Connection c, long rollbackId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT container_id FROM " + Migrations.table("rollback_crows")
                + " WHERE rollback_id = ?")) {
            ps.setLong(1, rollbackId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }
        List<ContainerEntry> rows = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> part = ids.subList(from, Math.min(ids.size(), from + 500));
            String in = "?" + ", ?".repeat(part.size() - 1);
            try (PreparedStatement ps = c.prepareStatement(SELECT_WITH_DATA + " WHERE b.id IN (" + in + ")")) {
                for (int i = 0; i < part.size(); i++) ps.setLong(i + 1, part.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    rows.addAll(read(rs, Integer.MAX_VALUE));
                }
            }
        }
        rows.sort(java.util.Comparator.comparingInt(ContainerEntry::time).thenComparingLong(ContainerEntry::id));
        return rows;
    }
}
