package me.skerik.justlogs.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.skerik.justlogs.model.Actor;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Remembers who is behind an indirect change: who lit a fire block, who primed a TNT. Entries expire, so the maps stay
 * small. Where nobody can be named the callers fall back to an honest source such as {@code #fire} or {@code #tnt}.
 */
public final class CauseTracker {

    private static final long FIRE_TTL_MS = 120_000;
    private static final long TNT_TTL_MS = 60_000;

    private record Entry(Actor actor, long expires) {
    }

    private final Map<String, Entry> fires = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> tnt = new ConcurrentHashMap<>();

    private static String key(Block b) {
        return b.getWorld().getName() + ':' + b.getX() + ',' + b.getY() + ',' + b.getZ();
    }

    /** The fire at {@code block} was started (or spread) on behalf of {@code actor}. */
    public void fire(Block block, Actor actor) {
        fires.put(key(block), new Entry(actor, System.currentTimeMillis() + FIRE_TTL_MS));
    }

    public @Nullable Actor fireOwner(@Nullable Block block) {
        if (block == null) return null;
        Entry e = fires.get(key(block));
        return e == null || e.expires < System.currentTimeMillis() ? null : e.actor;
    }

    public void tnt(UUID entity, Actor actor) {
        tnt.put(entity, new Entry(actor, System.currentTimeMillis() + TNT_TTL_MS));
    }

    public @Nullable Actor tntOwner(UUID entity) {
        Entry e = tnt.get(entity);
        return e == null || e.expires < System.currentTimeMillis() ? null : e.actor;
    }

    public void expire() {
        long now = System.currentTimeMillis();
        fires.values().removeIf(e -> e.expires < now);
        tnt.values().removeIf(e -> e.expires < now);
    }
}
