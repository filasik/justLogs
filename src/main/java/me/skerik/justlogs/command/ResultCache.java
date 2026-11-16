package me.skerik.justlogs.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import org.jspecify.annotations.Nullable;

/** The last lookup of every sender, so {@code /jo page n} can continue it. Entries expire. */
public final class ResultCache {

    /** The console shares one slot. */
    public static final UUID CONSOLE = new UUID(0, 0);

    /**
     * @param cursors page number to the keyset cursor that starts it (page 1 has none); filled as pages are visited
     * @param spot    true for an inspector result (different header), with its position
     */
    public record Entry(LogQuery query, Origin origin, int total, boolean capped, int pageSize, boolean spot,
                        String spotWorld, int spotX, int spotY, int spotZ, Map<Integer, String> cursors, long createdMillis) {

        public int pages() {
            return Math.max(1, (total + pageSize - 1) / pageSize);
        }
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public void put(UUID key, Entry entry) {
        entries.put(key, entry);
    }

    public @Nullable Entry get(UUID key, long ttlMillis) {
        Entry e = entries.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.createdMillis() > ttlMillis) {
            entries.remove(key, e);
            return null;
        }
        return e;
    }

    public void remove(UUID key) {
        entries.remove(key);
    }

    public void expire(long ttlMillis) {
        long now = System.currentTimeMillis();
        entries.values().removeIf(e -> now - e.createdMillis() > ttlMillis);
    }
}
