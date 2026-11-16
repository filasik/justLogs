package me.skerik.justlogs.rollback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.skerik.justlogs.Scheduler;
import me.skerik.justlogs.listener.Containers;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.ContainerEntry;
import me.skerik.justlogs.util.Items;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Container rollback: every matching row is undone on its own (a rolled-back withdraw puts the items back, a rolled-back
 * deposit takes them out again; a restore does the opposite). Items that are no longer there, or do not fit, are counted
 * as skipped, never invented.
 */
public final class ContainerPlan implements Plan {

    /** One item move. {@code add}: put the items into the container, otherwise take them out. */
    record Op(int x, int y, int z, String material, int amount, byte @Nullable [] data, boolean add) {
    }

    record ChunkOps(String world, int chunkX, int chunkZ, List<Op> ops) {
    }

    private final List<ChunkOps> chunks;
    private final List<Long> rowIds;
    private final int moves;

    private ContainerPlan(List<ChunkOps> chunks, List<Long> rowIds, int moves) {
        this.chunks = chunks;
        this.rowIds = rowIds;
        this.moves = moves;
    }

    /** @param rows matching rows, oldest first */
    public static ContainerPlan build(List<ContainerEntry> rows, boolean restore) {
        Map<String, List<Op>> byChunk = new LinkedHashMap<>();
        Map<String, int[]> coords = new LinkedHashMap<>();
        Map<String, String> worlds = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>(rows.size());
        // Newest first for a rollback so the latest moves are undone first; oldest first for a restore
        List<ContainerEntry> ordered = new ArrayList<>(rows);
        if (!restore) java.util.Collections.reverse(ordered);
        for (ContainerEntry row : ordered) {
            ids.add(row.id());
            boolean add = restore ? row.action() == Action.DEPOSIT : row.action() == Action.WITHDRAW;
            int cx = Math.floorDiv(row.x(), 16);
            int cz = Math.floorDiv(row.z(), 16);
            String key = row.world() + '\u0000' + cx + ',' + cz;
            byChunk.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Op(row.x(), row.y(), row.z(), row.material(), row.amount(), row.itemData(), add));
            coords.putIfAbsent(key, new int[] {cx, cz});
            worlds.putIfAbsent(key, row.world());
        }
        List<ChunkOps> chunks = new ArrayList<>();
        for (Map.Entry<String, List<Op>> e : byChunk.entrySet()) {
            int[] c = coords.get(e.getKey());
            chunks.add(new ChunkOps(worlds.get(e.getKey()), c[0], c[1], List.copyOf(e.getValue())));
        }
        return new ContainerPlan(List.copyOf(chunks), List.copyOf(ids), rows.size());
    }

    @Override
    public int blocks() {
        return moves;
    }

    @Override
    public int chunkCount() {
        return chunks.size();
    }

    @Override
    public List<Long> rowIds() {
        return rowIds;
    }

    @Override
    public boolean containers() {
        return true;
    }

    @Override
    public CompletableFuture<ApplyResult> apply(Scheduler scheduler, int msPerTick) {
        List<ChunkRunner.Job> jobs = new ArrayList<>(chunks.size());
        for (ChunkOps chunk : chunks) {
            jobs.add(new ChunkRunner.Job(chunk.world(), chunk.chunkX(), chunk.chunkZ(), chunk.ops().size(),
                    (world, changed, skipped) -> applyChunk(world, chunk, changed, skipped)));
        }
        return ChunkRunner.run(scheduler, msPerTick, jobs);
    }

    private static void applyChunk(World world, ChunkOps chunk, AtomicInteger changed, AtomicInteger skipped) {
        for (Op op : chunk.ops()) {
            Block block = world.getBlockAt(op.x(), op.y(), op.z());
            Inventory inventory = Containers.inventory(block);
            Material material = Material.matchMaterial(op.material());
            ItemStack stack = material == null ? null : Items.build(material, op.amount(), op.data());
            if (inventory == null || stack == null) {
                skipped.incrementAndGet();
                continue;
            }
            int left = 0;
            for (ItemStack rest : (op.add() ? inventory.addItem(stack) : inventory.removeItem(stack)).values()) {
                left += rest.getAmount();
            }
            if (left < op.amount()) changed.incrementAndGet();
            if (left > 0) skipped.incrementAndGet();
        }
    }
}
