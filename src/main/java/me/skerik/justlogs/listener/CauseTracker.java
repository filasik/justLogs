package me.skerik.justlogs.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.skerik.justlogs.model.Actor;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Remembers who is behind an indirect change: who lit a fire block, who primed a TNT, who poured a fluid, who broke a
 * supporting block, or who caused a falling block. Entries expire, so the maps stay small. Where nobody can be named
 * the callers fall back to an honest source such as {@code #fire}, {@code #tnt}, {@code #water}, or {@code #fall}.
 */
public final class CauseTracker {

    private static final long FIRE_TTL_MS = 120_000;
    private static final long FLUID_TTL_MS = 120_000;
    private static final long TNT_TTL_MS = 60_000;
    private static final long FALL_TTL_MS = 30_000;
    private static final long BREAK_TTL_MS = 2_000;

    private record Entry(Actor actor, long expires) {
    }

    private final Map<String, Entry> fires = new ConcurrentHashMap<>();
    private final Map<String, Entry> fluids = new ConcurrentHashMap<>();
    private final Map<String, Entry> breaks = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> tnt = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> falls = new ConcurrentHashMap<>();

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

    /** The fluid at {@code block} was poured (or flowed) on behalf of {@code actor}. */
    public void fluid(Block block, Actor actor) {
        fluids.put(key(block), new Entry(actor, System.currentTimeMillis() + FLUID_TTL_MS));
    }

    public @Nullable Actor fluidOwner(@Nullable Block block) {
        if (block == null) return null;
        Entry e = fluids.get(key(block));
        return e == null || e.expires < System.currentTimeMillis() ? null : e.actor;
    }

    /** {@code actor} just broke {@code block}; used to attribute attached blocks that pop off on the same tick. */
    public void recentBreak(Block block, Actor actor) {
        breaks.put(key(block), new Entry(actor, System.currentTimeMillis() + BREAK_TTL_MS));
    }

    public @Nullable Actor recentBreakOwner(@Nullable Block block) {
        if (block == null) return null;
        Entry e = breaks.get(key(block));
        return e == null || e.expires < System.currentTimeMillis() ? null : e.actor;
    }

    public void tnt(UUID entity, Actor actor) {
        tnt.put(entity, new Entry(actor, System.currentTimeMillis() + TNT_TTL_MS));
    }

    public @Nullable Actor tntOwner(UUID entity) {
        Entry e = tnt.get(entity);
        return e == null || e.expires < System.currentTimeMillis() ? null : e.actor;
    }

    public void fall(UUID entity, Actor actor) {
        falls.put(entity, new Entry(actor, System.currentTimeMillis() + FALL_TTL_MS));
    }

    public @Nullable Actor fallOwner(UUID entity) {
        Entry e = falls.remove(entity);
        return e == null || e.expires < System.currentTimeMillis() ? null : e.actor;
    }

    public void expire() {
        long now = System.currentTimeMillis();
        fires.values().removeIf(e -> e.expires < now);
        fluids.values().removeIf(e -> e.expires < now);
        breaks.values().removeIf(e -> e.expires < now);
        tnt.values().removeIf(e -> e.expires < now);
        falls.values().removeIf(e -> e.expires < now);
    }
}
