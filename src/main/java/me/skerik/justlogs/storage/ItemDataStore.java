package me.skerik.justlogs.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Deduplicates serialized {@code ItemStack} bytes in {@code jl_item_data} by SHA-1 hash. */
final class ItemDataStore {

    private final Map<String, Long> itemIds = new ConcurrentHashMap<>();

    long intern(Connection c, byte[] data, int materialId) throws SQLException {
        byte[] hash = sha1(data);
        String key = HexFormat.of().formatHex(hash);
        Long cached = itemIds.get(key);
        if (cached != null) return cached;
        long id = findItem(c, hash);
        if (id == 0) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + Migrations.table("item_data")
                    + " (hash, mat, data) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setBytes(1, hash);
                ps.setInt(2, materialId);
                ps.setBytes(3, data);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0;
                }
            } catch (SQLException duplicate) {
                id = findItem(c, hash);
                if (id == 0) throw duplicate;
            }
        }
        if (itemIds.size() > 20_000) itemIds.clear();
        itemIds.put(key, id);
        return id;
    }

    private static long findItem(Connection c, byte[] hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + Migrations.table("item_data") + " WHERE hash = ?")) {
            ps.setBytes(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
