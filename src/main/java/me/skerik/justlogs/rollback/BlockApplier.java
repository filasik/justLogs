package me.skerik.justlogs.rollback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.skerik.justlogs.Scheduler;
import me.skerik.justlogs.listener.Containers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Applies a {@link RollbackPlan} to the world, one chunk per task (see {@link ChunkRunner}). Blocks are set without
 * physics. Two passes per chunk: full solid blocks and air first, then everything that hangs on or needs them (torches,
 * doors, rails, fluids, chests), both bottom-up. A block with a stored tile entity snapshot gets its contents back.
 */
public final class BlockApplier {

    private BlockApplier() {
    }

    public static CompletableFuture<ApplyResult> apply(Scheduler scheduler, int msPerTick, RollbackPlan plan) {
        Map<String, BlockData> parsed = new HashMap<>();
        List<ChunkRunner.Job> jobs = new ArrayList<>(plan.chunks().size());
        for (RollbackPlan.ChunkChanges chunk : plan.chunks()) {
            jobs.add(new ChunkRunner.Job(chunk.world(), chunk.chunkX(), chunk.chunkZ(), chunk.changes().size(),
                    (world, changed, skipped) -> applyChunk(world, chunk, changed, skipped, parsed)));
        }
        return ChunkRunner.run(scheduler, msPerTick, jobs);
    }

    private static void applyChunk(World world, RollbackPlan.ChunkChanges chunk, AtomicInteger changed,
                                   AtomicInteger skipped, Map<String, BlockData> parsed) {
        List<RollbackPlan.Change> second = new ArrayList<>();
        for (RollbackPlan.Change c : chunk.changes()) {
            BlockData data = blockData(c, parsed);
            if (data == null) {
                skipped.incrementAndGet();
            } else if (data.getMaterial().isAir() || data.getMaterial().isOccluding()) {
                set(world, c, data, changed);
            } else {
                second.add(c);
            }
        }
        for (RollbackPlan.Change c : second) set(world, c, blockData(c, parsed), changed);
    }

    private static void set(World world, RollbackPlan.Change c, BlockData data, AtomicInteger changed) {
        if (c.y() < world.getMinHeight() || c.y() >= world.getMaxHeight()) return;
        Block block = world.getBlockAt(c.x(), c.y(), c.z());
        block.setBlockData(data, false);
        changed.incrementAndGet();
        if (c.nbt() != null) Containers.restore(block, c.nbt());
    }

    static BlockData blockData(RollbackPlan.Change c, Map<String, BlockData> cache) {
        String key = c.data().isEmpty() ? c.material() : c.material() + '[' + c.data() + ']';
        BlockData cached = cache.get(key);
        if (cached != null) return cached;
        try {
            Material material = Material.matchMaterial(c.material());
            if (material == null || !material.isBlock()) return null;
            BlockData data = Bukkit.createBlockData("minecraft:" + key);
            cache.put(key, data);
            return data;
        } catch (IllegalArgumentException e) {
            return null; // a state this server version does not know
        }
    }
}
