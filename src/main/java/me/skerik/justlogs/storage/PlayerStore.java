package me.skerik.justlogs.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * EXAMPLE store: how to talk to the database. The pattern to keep:
 * every method returns a future that completes on the DB executor (never block the main thread), SQL is parameterized,
 * and dialect differences (here: the upsert) are handled in one switch.
 */
public final class PlayerStore {

    private static final String TABLE = Migrations.table("players");

    private final Database db;

    public PlayerStore(Database db) {
        this.db = db;
    }

    /** Inserts the player or refreshes name and last_seen; first_seen stays as it was. */
    public CompletableFuture<Void> touch(UUID uuid, String name, long now) {
        return db.supply(c -> {
            String sql = switch (db.dialect()) {
                case SQLITE -> "INSERT INTO " + TABLE + " (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen";
                case MARIADB -> "INSERT INTO " + TABLE + " (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen)";
            };
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<Long>> firstSeen(UUID uuid) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT first_seen FROM " + TABLE + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty();
                }
            }
        });
    }
}
